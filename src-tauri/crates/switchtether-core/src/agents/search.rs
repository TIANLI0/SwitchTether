//! Full-text search over session transcripts (`GET /v1/sessions/search`, docs/PROTOCOL.md §7.13).
//!
//! Only what the user and the agent *said* is searched (the `user_message` / `assistant_message`
//! events the regular parsers produce), not tool output. A raw, case-folded substring test on each
//! line keeps the cost low: only matching lines are parsed. Matching is case-insensitive per
//! character (so CJK text and other scripts work) and snippets go through `redact_text`.

use std::io::{BufRead, Read};
use std::path::Path;
use std::time::Instant;

use serde::Serialize;
use serde_json::Value;

use super::LineParser;

/// Longest query accepted.
pub const MAX_QUERY_CHARS: usize = 200;
/// At most this much of a transcript's end is read.
pub const MAX_FILE_BYTES: u64 = 64 * 1024 * 1024;
/// Characters of context before / after a match in a snippet.
const BEFORE: usize = 40;
const AFTER: usize = 100;
const MAX_COUNT: usize = 999;

/// One matching session.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SearchResult {
    pub session_id: String,
    pub agent: String,
    pub title: Option<String>,
    pub cwd: Option<String>,
    pub time_ms: Option<i64>,
    /// Text around the newest match, whitespace collapsed, redacted.
    pub snippet: String,
    /// Matches in the searched messages (capped at 999).
    pub match_count: usize,
}

/// Lower-case one character the simple way (first char of its lowercase mapping), so every
/// folded character still maps to exactly one character of the original text.
fn fold_char(c: char) -> char {
    c.to_lowercase().next().unwrap_or(c)
}

pub fn fold(s: &str) -> String {
    s.chars().map(fold_char).collect()
}

/// Byte ranges (in `hay`) of every case-insensitive, non-overlapping occurrence of `needle`
/// (already folded with [`fold`]).
pub fn find_all(hay: &str, needle: &str) -> Vec<(usize, usize)> {
    let n: Vec<char> = needle.chars().collect();
    if n.is_empty() {
        return Vec::new();
    }
    let h: Vec<(usize, char)> = hay.char_indices().map(|(i, c)| (i, fold_char(c))).collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i + n.len() <= h.len() {
        if h[i..i + n.len()]
            .iter()
            .map(|(_, c)| *c)
            .eq(n.iter().copied())
        {
            let end = h.get(i + n.len()).map(|(b, _)| *b).unwrap_or(hay.len());
            out.push((h[i].0, end));
            i += n.len();
        } else {
            i += 1;
        }
    }
    out
}

/// `text` around `start..end`, with whitespace collapsed and "…" where it was cut.
pub fn snippet(text: &str, start: usize, end: usize) -> String {
    let before: Vec<char> = text[..start].chars().collect();
    let after: Vec<char> = text[end..].chars().collect();
    let b0 = before.len().saturating_sub(BEFORE);
    let mut s = String::new();
    if b0 > 0 {
        s.push('…');
    }
    s.extend(&before[b0..]);
    s.push_str(&text[start..end]);
    s.extend(after.iter().take(AFTER));
    if after.len() > AFTER {
        s.push('…');
    }
    let collapsed = s.split_whitespace().collect::<Vec<_>>().join(" ");
    crate::redact::redact_text(&collapsed)
}

/// What a file search found.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct FileHit {
    pub snippet: String,
    pub count: usize,
}

/// The JSON-escaped, folded form of the query as it appears inside a raw transcript line.
pub fn raw_needle(folded: &str) -> String {
    let quoted = serde_json::to_string(folded).unwrap_or_default();
    quoted
        .strip_prefix('"')
        .and_then(|s| s.strip_suffix('"'))
        .unwrap_or(&quoted)
        .to_string()
}

/// Search the lines of one transcript with `parser`. Returns `None` when nothing matched or
/// `deadline` passed (then `timed_out` is set).
pub(crate) fn search_lines(
    lines: impl Iterator<Item = String>,
    mut parser: Box<dyn LineParser>,
    folded: &str,
    deadline: Instant,
    timed_out: &mut bool,
) -> Option<FileHit> {
    let raw = raw_needle(folded);
    let mut hit = FileHit::default();
    for (n, line) in lines.enumerate() {
        if n % 256 == 0 && Instant::now() > deadline {
            *timed_out = true;
            break;
        }
        if !fold(&line).contains(&raw) {
            continue;
        }
        let Ok(v) = serde_json::from_str::<Value>(&line) else {
            continue;
        };
        for d in parser.feed(&v) {
            if d.kind != "user_message" && d.kind != "assistant_message" {
                continue;
            }
            let Some(text) = d.data.get("text").and_then(|t| t.as_str()) else {
                continue;
            };
            let found = find_all(text, folded);
            if let Some(&(s, e)) = found.first() {
                hit.count = (hit.count + found.len()).min(MAX_COUNT);
                // The newest message wins: lines come oldest first.
                hit.snippet = snippet(text, s, e);
            }
        }
    }
    (hit.count > 0).then_some(hit)
}

/// Search a plain transcript as CC Switch's session parser returns it (`[{role, content}]`,
/// oldest first): user and assistant messages only, like [`search_lines`].
pub(crate) fn search_messages(messages: &Value, folded: &str) -> Option<FileHit> {
    let mut hit = FileHit::default();
    for m in messages.as_array().map(Vec::as_slice).unwrap_or_default() {
        if !matches!(
            m.get("role").and_then(Value::as_str),
            Some("user" | "assistant")
        ) {
            continue;
        }
        let Some(text) = m.get("content").and_then(Value::as_str) else {
            continue;
        };
        let found = find_all(text, folded);
        if let Some(&(s, e)) = found.first() {
            hit.count = (hit.count + found.len()).min(MAX_COUNT);
            hit.snippet = snippet(text, s, e);
        }
    }
    (hit.count > 0).then_some(hit)
}

/// Lines of a transcript file (`.jsonl`, or DeepSeek Harness `.jsonl.zstd` frames), at most
/// [`MAX_FILE_BYTES`] from its end.
pub fn file_lines(path: &Path) -> std::io::Result<Box<dyn Iterator<Item = String>>> {
    let mut f = std::fs::File::open(path)?;
    let len = f.metadata()?.len();
    if super::is_zstd_file(path) {
        let mut buf = Vec::new();
        f.take(MAX_FILE_BYTES).read_to_end(&mut buf)?;
        let (decoded, _) = super::dsh::decode_frames(&buf);
        let text = String::from_utf8_lossy(&decoded).into_owned();
        let lines: Vec<String> = text.lines().map(str::to_string).collect();
        return Ok(Box::new(lines.into_iter()));
    }
    let start = len.saturating_sub(MAX_FILE_BYTES);
    if start > 0 {
        use std::io::Seek;
        f.seek(std::io::SeekFrom::Start(start))?;
    }
    let reader = std::io::BufReader::new(f);
    // A line cut by the seek is dropped (it cannot be parsed anyway).
    let skip = usize::from(start > 0);
    Ok(Box::new(
        reader
            .split(b'\n')
            .map_while(Result::ok)
            .skip(skip)
            .map(|l| String::from_utf8_lossy(&l).into_owned()),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agents::claude::transcript::ClaudeParser;
    use crate::agents::codex::rollout::CodexParser;
    use std::time::Duration;

    #[test]
    fn folding_and_finding_are_case_insensitive_and_cjk_safe() {
        assert_eq!(find_all("Hello hello HELLO", "hello").len(), 3);
        let t = "修复登录页面的BUG，然后修复登录";
        let f = find_all(t, &fold("修复登录"));
        assert_eq!(f.len(), 2);
        assert_eq!(&t[f[0].0..f[0].1], "修复登录");
        assert_eq!(find_all("ÄRGER ärger", &fold("Ärger")).len(), 2);
        assert!(find_all("abc", "").is_empty());
        // Byte ranges stay on char boundaries with multi-byte neighbours.
        let t = "前缀Rust后缀";
        let (s, e) = find_all(t, "rust")[0];
        assert_eq!(&t[s..e], "Rust");
    }

    #[test]
    fn snippets_are_trimmed_collapsed_and_redacted() {
        let text = format!(
            "{}needle\n\n  tail sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789 {}",
            "x".repeat(100),
            "y".repeat(300)
        );
        let (s, e) = find_all(&text, "needle")[0];
        let sn = snippet(&text, s, e);
        assert!(sn.starts_with('…') && sn.ends_with('…'));
        assert!(sn.contains("needle tail"));
        assert!(!sn.contains("abcdefghijklmnopqrstuvwxyz0123456789"), "{sn}");
    }

    #[test]
    fn claude_and_codex_transcripts_are_searched_by_message_text() {
        let dir = tempfile::tempdir().unwrap();
        let claude = dir.path().join("s.jsonl");
        let lines = [
            r#"{"type":"user","uuid":"u1","timestamp":"2026-11-20T00:00:00Z","sessionId":"s","cwd":"/p","message":{"role":"user","content":"请帮我修复 Login 页面"}}"#,
            r#"{"type":"assistant","uuid":"a1","timestamp":"2026-11-20T00:00:01Z","sessionId":"s","message":{"id":"m1","role":"assistant","model":"x","content":[{"type":"text","text":"好的，我来看看 login 的代码。"}]}}"#,
            r#"{"type":"assistant","uuid":"a2","timestamp":"2026-11-20T00:00:02Z","sessionId":"s","message":{"id":"m2","role":"assistant","model":"x","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"grep login"}}]}}"#,
        ];
        std::fs::write(&claude, lines.join("\n")).unwrap();
        let deadline = Instant::now() + Duration::from_secs(5);
        let mut to = false;
        let hit = search_lines(
            file_lines(&claude).unwrap(),
            Box::new(ClaudeParser::new()),
            &fold("LOGIN"),
            deadline,
            &mut to,
        )
        .unwrap();
        // The tool call's command is not message text.
        assert_eq!(hit.count, 2);
        assert!(hit.snippet.contains("login 的代码"), "{}", hit.snippet);
        assert!(search_lines(
            file_lines(&claude).unwrap(),
            Box::new(ClaudeParser::new()),
            &fold("grep"),
            deadline,
            &mut to,
        )
        .is_none());

        let codex = dir.path().join("rollout.jsonl");
        let lines = [
            r#"{"timestamp":"2026-11-14T00:00:01Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"refactor the Parser"}]}}"#,
            r#"{"timestamp":"2026-11-14T00:00:01Z","type":"event_msg","payload":{"type":"user_message","message":"refactor the Parser"}}"#,
            r#"{"timestamp":"2026-11-14T00:00:02Z","type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"The parser is split in two."}]}}"#,
        ];
        std::fs::write(&codex, lines.join("\n")).unwrap();
        let hit = search_lines(
            file_lines(&codex).unwrap(),
            Box::new(CodexParser::new()),
            &fold("parser"),
            deadline,
            &mut to,
        )
        .unwrap();
        assert!(hit.count >= 2);
        assert!(hit.snippet.contains("split in two"));
        assert!(!to);

        // An expired deadline stops the scan and says so.
        let mut to = false;
        let past = Instant::now() - Duration::from_secs(1);
        assert!(search_lines(
            file_lines(&codex).unwrap(),
            Box::new(CodexParser::new()),
            &fold("parser"),
            past,
            &mut to,
        )
        .is_none());
        assert!(to);
    }

    fn fixture(name: &str) -> serde_json::Value {
        let root =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../protocol/fixtures");
        serde_json::from_str(&std::fs::read_to_string(root.join(name)).unwrap()).unwrap()
    }

    #[test]
    fn result_matches_the_fixture() {
        let r = SearchResult {
            session_id: "claude:2b1c9f8e-0d1a-4c55-9a77-5e6f7a8b9c0d".into(),
            agent: "claude_code".into(),
            title: Some("修复登录页面".into()),
            cwd: Some(r"D:\proj\app".into()),
            time_ms: Some(1_763_452_800_000),
            snippet: "…好的，我来看看 login 的代码。".into(),
            match_count: 2,
        };
        assert_eq!(
            serde_json::to_value(r).unwrap(),
            fixture("session_search.json")["results"][0]
        );
    }

    #[test]
    fn plain_transcripts_are_searched_by_user_and_assistant_text() {
        let msgs = serde_json::json!([
            {"role": "user", "content": "Deploy the Widget"},
            {"role": "tool", "content": "widget log"},
            {"role": "assistant", "content": "The widget is deployed"},
        ]);
        let hit = search_messages(&msgs, &fold("WIDGET")).unwrap();
        assert_eq!(hit.count, 2);
        assert!(hit.snippet.contains("deployed"), "newest message wins");
        assert_eq!(search_messages(&msgs, &fold("log")), None);
        assert_eq!(search_messages(&serde_json::Value::Null, "x"), None);
    }

    #[test]
    fn quotes_in_queries_match_escaped_json() {
        assert_eq!(raw_needle(r#"say "hi""#), r#"say \"hi\""#);
        assert_eq!(raw_needle("中文"), "中文");
    }
}
