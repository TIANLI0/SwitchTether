//! Claude Code transcript / stream-json parser → normalized [`EventDraft`]s.
//!
//! The same parser handles both sources because they share record shapes:
//! - transcript lines in `~/.claude/projects/<proj>/<session>.jsonl`
//!   (read-only observation of any session), and
//! - `--output-format stream-json` messages of a SwitchTether-managed process
//!   (plus `stream_event` partial deltas and `result` records).
//!
//! The transcript format is internal to Claude Code and changes between
//! versions (docs/research/CLAUDE_CODE_RUNTIME.md §C.1); the parser is
//! defensive: unknown record types are ignored, unknown blocks become `system`
//! events, and nothing panics on malformed input.

use std::collections::HashMap;

use serde_json::{json, Value};

use crate::agents::model::{
    preview, structured_patch_to_diff, tool_category, tool_title, EventDraft, PREVIEW_LIMIT,
};

#[derive(Default)]
struct ToolCall {
    event_id: String,
    name: String,
    data: Value,
    turn_id: Option<String>,
    ts: String,
}

#[derive(Default)]
pub struct ClaudeParser {
    blocks_seen: HashMap<String, usize>,
    tools: HashMap<String, ToolCall>,
    stream_msg_id: Option<String>,
    stream_blocks: HashMap<usize, (String, &'static str, String)>,
    current_turn: Option<String>,
    result_count: usize,
    pub title: Option<String>,
    /// `/rename` title (`custom-title`); always wins over the generated `ai-title`.
    custom_title: Option<String>,
    /// [`Self::title`] is a real name (`ai-title`, `custom-title`, a host-generated title), not
    /// the first-prompt fallback.
    pub named: bool,
    pub model: Option<String>,
    pub cwd: Option<String>,
    pub git_branch: Option<String>,
    pub version: Option<String>,
    pub entrypoint: Option<String>,
    pub session_id: Option<String>,
    pub last_ts: Option<String>,
    pub first_ts: Option<String>,
    pub cost_usd: Option<f64>,
    /// tool_use ids that have no result yet.
    pub open_tool_calls: Vec<String>,
    /// Sub-agent rows by their Agent/Task call id, so a later `<task-notification>` (how a
    /// background agent reports back) can update the row in place.
    subagents: HashMap<String, SubagentRow>,
    /// Sub-agent id (`agentId`, also the notification's `<task-id>`) -> Agent/Task call id.
    agent_calls: HashMap<String, String>,
    /// Background shell task id (`backgroundTaskId`, the notification's `<task-id>`) -> Bash call id.
    bg_shells: HashMap<String, String>,
    /// The last record was a `compact_boundary`: a synthetic user message now is its summary.
    after_compact_boundary: bool,
    /// Background tasks (agents, shells) still running, from stream-json
    /// `background_tasks_changed`; always 0 for transcripts, which do not record it.
    pub background_tasks: usize,
}

struct SubagentRow {
    data: Value,
    turn_id: Option<String>,
    ts: String,
}

/// What a background task reported, from a `<task-notification>` (transcript) or a stream-json
/// `system` `task_notification` record.
#[derive(Default)]
struct TaskNote {
    task_id: Option<String>,
    tool_use_id: Option<String>,
    status: Option<String>,
    summary: Option<String>,
    result: Option<String>,
    tokens: Option<i64>,
    tool_uses: Option<i64>,
    duration_ms: Option<i64>,
}

/// Longest sub-agent result kept on the row; the full text stays in the transcript.
const SUBAGENT_RESULT_LIMIT: usize = 4000;

/// Normalizes the sub-agent states Claude Code writes to the step states the clients know.
fn subagent_status(raw: &str) -> &'static str {
    match raw {
        "async_launched" | "running" | "pending" => "running",
        "completed" | "success" | "forked" => "completed",
        "killed" | "stopped" | "cancelled" | "canceled" | "interrupted" => "interrupted",
        _ => "failed",
    }
}

/// Inner text of the first `<tag>…</tag>` in a task notification.
fn xml_tag<'a>(text: &'a str, tag: &str) -> Option<&'a str> {
    let open = format!("<{tag}>");
    let close = format!("</{tag}>");
    let start = text.find(&open)? + open.len();
    let end = text[start..].find(&close)? + start;
    Some(text[start..end].trim()).filter(|t| !t.is_empty())
}

/// How Claude Code records a slash command: the command the user typed, wrapped in
/// `<command-name>` / `<command-message>` / `<command-args>`, and what a local command printed,
/// wrapped in `<local-command-stdout>` / `<local-command-stderr>`.
#[derive(Debug, PartialEq)]
enum SlashRecord {
    /// The command as typed: `/name args`.
    Command(String),
    /// A local command's output as plain text (may be empty).
    Output(String),
    /// `<local-command-caveat>`: the harness's note to the model that local command output
    /// follows. Never shown.
    Caveat,
}

fn slash_record(text: &str) -> Option<SlashRecord> {
    const OUTPUT_TAGS: [&str; 2] = ["local-command-stdout", "local-command-stderr"];
    let t = text.trim_start();
    // The VS Code extension (2.1.331) classifies user records the same way: `<command-name>` →
    // record, `<local-command-stdout|stderr>` → output, `<local-command-caveat>` → caveat.
    if t.starts_with("<local-command-caveat>") {
        return Some(SlashRecord::Caveat);
    }
    if t.starts_with("<command-name>") || t.starts_with("<command-message>") {
        let name = xml_tag(t, "command-name").or_else(|| xml_tag(t, "command-message"))?;
        let mut cmd = format!("/{}", name.trim_start_matches('/'));
        if let Some(args) = xml_tag(t, "command-args") {
            cmd.push(' ');
            cmd.push_str(args);
        }
        return Some(SlashRecord::Command(cmd));
    }
    if OUTPUT_TAGS
        .iter()
        .any(|tag| t.starts_with(&format!("<{tag}>")))
    {
        let mut parts = Vec::new();
        // A forked skill's launch record (`<forked-skill-launch>{json}</forked-skill-launch>`)
        // may trail the output; the extension strips it, it is not output.
        let (mut rest, _) = take_blocks(t, "forked-skill-launch");
        for tag in OUTPUT_TAGS {
            let (r, blocks) = take_blocks(&rest, tag);
            rest = r;
            parts.extend(blocks);
        }
        parts.push(rest);
        let out = parts
            .iter()
            .map(|p| strip_ansi(p))
            .map(|p| p.trim().to_string())
            .filter(|p| !p.is_empty())
            .collect::<Vec<_>>()
            .join("\n");
        return Some(SlashRecord::Output(out));
    }
    None
}

/// `/name args` when `text` is Claude Code's record of a slash command (`<command-name>` …), as
/// the user typed it; `None` for anything else.
pub(crate) fn slash_command_text(text: &str) -> Option<String> {
    match slash_record(text)? {
        SlashRecord::Command(c) => Some(c),
        _ => None,
    }
}

/// Text the user typed to run a slash command (`/compact`, `/my-plugin:deploy args`), not a
/// path such as `/usr/bin/foo`. Such text never names the conversation.
fn is_typed_slash_command(text: &str) -> bool {
    let Some(rest) = text.strip_prefix('/') else {
        return false;
    };
    let name = rest.split_whitespace().next().unwrap_or("");
    !name.is_empty()
        && name
            .chars()
            .all(|c| c.is_alphanumeric() || matches!(c, '-' | '_' | ':' | '.'))
}

/// Text without terminal escape sequences (local commands print for a terminal).
fn strip_ansi(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        if c != '\u{1b}' {
            out.push(c);
            continue;
        }
        match chars.peek() {
            // CSI: parameters, then one final byte.
            Some('[') => {
                chars.next();
                for n in chars.by_ref() {
                    if ('\u{40}'..='\u{7e}').contains(&n) {
                        break;
                    }
                }
            }
            // OSC: up to BEL or ESC \.
            Some(']') => {
                chars.next();
                while let Some(n) = chars.next() {
                    if n == '\u{7}' {
                        break;
                    }
                    if n == '\u{1b}' {
                        chars.next();
                        break;
                    }
                }
            }
            _ => {
                chars.next();
            }
        }
    }
    out
}

fn is_task_notification(rec: &Value, text: &str) -> bool {
    rec.pointer("/origin/kind").and_then(|k| k.as_str()) == Some("task-notification")
        || text.trim_start().starts_with("<task-notification>")
}

fn s(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(|x| x.as_str()).map(|x| x.to_string())
}

fn is_sidechain(rec: &Value) -> bool {
    rec.get("isSidechain").and_then(|v| v.as_bool()) == Some(true)
}

fn rec_has_parent(rec: &Value) -> bool {
    s(rec, "parent_tool_use_id").is_some_and(|p| !p.is_empty())
}

/// First non-empty line, trimmed; `None` when nothing usable is left.
pub fn clean_title(raw: Option<String>) -> Option<String> {
    raw?.lines()
        .map(str::trim)
        .find(|l| !l.is_empty())
        .map(str::to_string)
}

/// Title of a Claude Code conversation read from the end of its transcript: the last
/// `custom-title` (`/rename`) wins over the last `ai-title`. Claude Code keeps this metadata
/// within the last 64 KiB, so a 256 KiB tail is enough. Sub-agent records are ignored.
pub fn tail_title(path: &std::path::Path) -> Option<String> {
    use std::io::{Read, Seek, SeekFrom};
    const TAIL: u64 = 256 * 1024;
    let mut f = std::fs::File::open(path).ok()?;
    let len = f.metadata().ok()?.len();
    let start = len.saturating_sub(TAIL);
    f.seek(SeekFrom::Start(start)).ok()?;
    let mut buf = Vec::new();
    f.read_to_end(&mut buf).ok()?;
    let text = String::from_utf8_lossy(&buf);
    let mut lines = text.lines();
    if start > 0 {
        lines.next(); // partial first line
    }
    let (mut custom, mut ai) = (None, None);
    for line in lines {
        let kind = if line.contains("\"custom-title\"") {
            "custom-title"
        } else if line.contains("\"ai-title\"") {
            "ai-title"
        } else {
            continue;
        };
        let Ok(v) = serde_json::from_str::<Value>(line) else {
            continue;
        };
        if v.get("type").and_then(|x| x.as_str()) != Some(kind)
            || is_sidechain(&v)
            || rec_has_parent(&v)
        {
            continue;
        }
        if kind == "custom-title" {
            custom = clean_title(s(&v, "customTitle")).or(custom);
        } else {
            ai = clean_title(s(&v, "aiTitle")).or(ai);
        }
    }
    custom.or(ai).or_else(|| {
        // Conversations may have no generated title yet; read a bounded head for the first prompt.
        use std::io::BufRead;
        let f = std::fs::File::open(path).ok()?;
        let mut parser = ClaudeParser::new();
        for line in std::io::BufReader::new(f.take(1024 * 1024))
            .lines()
            .take(200)
            .map_while(Result::ok)
        {
            if let Ok(v) = serde_json::from_str::<Value>(&line) {
                parser.feed(&v);
                if parser.title.is_some() {
                    return parser.title;
                }
            }
        }
        None
    })
}

/// Blocks the harness or the IDE extension adds to a user turn. The user never typed them.
const INJECTED_TAGS: &[&str] = &[
    "system-reminder",
    "ide_opened_file",
    "ide_selection",
    "ide_diagnostics",
    "browser_instruction",
];

/// Removes every `<tag>…</tag>` block of `tag`, returning the remaining text and the blocks' inner
/// texts. An unclosed block swallows the rest, like the harness does.
fn take_blocks(text: &str, tag: &str) -> (String, Vec<String>) {
    let open = format!("<{tag}>");
    let close = format!("</{tag}>");
    let mut out = String::with_capacity(text.len());
    let mut found = Vec::new();
    let mut rest = text;
    while let Some(start) = rest.find(&open) {
        out.push_str(&rest[..start]);
        let inner = &rest[start + open.len()..];
        match inner.find(&close) {
            Some(end) => {
                found.push(inner[..end].trim().to_string());
                rest = &inner[end + close.len()..];
            }
            None => {
                found.push(inner.trim().to_string());
                rest = "";
            }
        }
    }
    out.push_str(rest);
    (out, found)
}

/// Removes harness/IDE blocks from user text and returns what the IDE blocks said as context items for the clients
/// (`{"kind":"file"|"selection","path":…,"lines":…}`), so the bubble shows only what the user wrote.
fn strip_injected_with_context(text: &str) -> (String, Vec<Value>) {
    let mut rest = text.to_string();
    let mut context = Vec::new();
    for tag in INJECTED_TAGS {
        let (r, blocks) = take_blocks(&rest, tag);
        rest = r;
        for b in blocks {
            match *tag {
                // "The user opened the file <path> in the IDE. …"
                "ide_opened_file" => {
                    if let Some(p) = b
                        .split_once("opened the file ")
                        .and_then(|(_, r)| r.split_once(" in the IDE"))
                        .map(|(p, _)| p.trim())
                    {
                        context.push(json!({ "kind": "file", "path": p }));
                    }
                }
                // "The user selected the lines 17 to 20 from <path>:\n<code>…"
                "ide_selection" => {
                    let lines = b
                        .split_once("the lines ")
                        .and_then(|(_, r)| r.split_once(" from "))
                        .map(|(l, _)| l.replace(" to ", "-"));
                    let path = b
                        .split_once(" from ")
                        .map(|(_, r)| r.lines().next().unwrap_or("").trim_end_matches(':').trim());
                    if let Some(p) = path.filter(|p| !p.is_empty()) {
                        context.push(json!({ "kind": "selection", "path": p, "lines": lines }));
                    }
                }
                _ => {}
            }
        }
    }
    (rest.trim().to_string(), context)
}

/// Tool output without harness reminders; `<tool_use_error>` / `<persisted-output>` wrappers are
/// unwrapped to their message.
fn clean_tool_output(text: &str) -> String {
    let (mut t, _) = take_blocks(text, "system-reminder");
    for tag in ["tool_use_error", "persisted-output"] {
        let open = format!("<{tag}>");
        let close = format!("</{tag}>");
        if t.contains(&open) {
            t = t.replace(&open, "").replace(&close, "");
        }
    }
    t.trim().to_string()
}

fn extract_image_items(content: &Value) -> Vec<Value> {
    let Some(blocks) = content.as_array() else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for b in blocks {
        if b.get("type").and_then(|t| t.as_str()) != Some("image") {
            continue;
        }
        let Some(src) = b.get("source") else {
            continue;
        };
        match src.get("type").and_then(|t| t.as_str()) {
            Some("base64") => {
                let media_type = src
                    .get("media_type")
                    .and_then(|m| m.as_str())
                    .unwrap_or("image/png");
                if let Some(data) = src.get("data").and_then(|d| d.as_str()) {
                    // Cap inline base64 per image at 512 KiB to keep event logs bounded.
                    if data.len() <= 512 * 1024 {
                        out.push(json!({
                            "kind": "base64",
                            "mediaType": media_type,
                            "data": data,
                        }));
                    } else {
                        out.push(json!({
                            "kind": "omitted",
                            "mediaType": media_type,
                        }));
                    }
                }
            }
            Some("url") => {
                if let Some(url) = src.get("url").and_then(|u| u.as_str()) {
                    out.push(json!({
                        "kind": "url",
                        "url": url,
                    }));
                }
            }
            _ => {}
        }
    }
    out
}

fn content_text(content: &Value) -> String {
    match content {
        Value::String(s) => s.clone(),
        Value::Array(items) => items
            .iter()
            .filter_map(|b| match b.get("type").and_then(|t| t.as_str()) {
                Some("text") => b
                    .get("text")
                    .and_then(|t| t.as_str())
                    .map(|t| t.to_string()),
                _ => None,
            })
            .collect::<Vec<_>>()
            .join("\n"),
        _ => String::new(),
    }
}

fn tool_result_text(content: &Value) -> String {
    match content {
        Value::String(s) => s.clone(),
        Value::Array(items) => items
            .iter()
            .map(|b| match b.get("type").and_then(|t| t.as_str()) {
                Some("text") => b
                    .get("text")
                    .and_then(|t| t.as_str())
                    .unwrap_or("")
                    .to_string(),
                Some("image") => "[image]".to_string(),
                Some(other) => format!("[{other}]"),
                None => String::new(),
            })
            .collect::<Vec<_>>()
            .join("\n"),
        Value::Null => String::new(),
        other => other.to_string(),
    }
}

impl ClaudeParser {
    pub fn new() -> Self {
        Self::default()
    }

    fn next_block_id(&mut self, msg_id: &str) -> String {
        let n = self.blocks_seen.entry(msg_id.to_string()).or_insert(0);
        let id = format!("{msg_id}:{n}");
        *n += 1;
        id
    }

    /// Feed one JSON record; returns zero or more event drafts (new or updated).
    pub fn feed(&mut self, rec: &Value) -> Vec<EventDraft> {
        let ts = s(rec, "timestamp").unwrap_or_else(crate::util::now_rfc3339);
        if rec.get("timestamp").is_some() {
            if self.first_ts.is_none() {
                self.first_ts = Some(ts.clone());
            }
            self.last_ts = Some(ts.clone());
        }
        if let Some(c) = s(rec, "cwd") {
            self.cwd = Some(c);
        }
        if let Some(b) = s(rec, "gitBranch").filter(|b| !b.is_empty()) {
            self.git_branch = Some(b);
        }
        if let Some(v) = s(rec, "version") {
            self.version = Some(v);
        }
        if let Some(e) = s(rec, "entrypoint") {
            self.entrypoint = Some(e);
        }
        if let Some(sid) = s(rec, "sessionId").or_else(|| s(rec, "session_id")) {
            self.session_id = Some(sid);
        }
        let parent = s(rec, "parent_tool_use_id");
        // Stream-json: a sub-agent's own records carry `agent_id`; the first one shows it runs.
        let early = match (&parent, s(rec, "agent_id")) {
            (Some(p), Some(a)) if !p.is_empty() => self.ensure_running_subagent(p, &a, None, &ts),
            _ => None,
        };
        let mut out = match rec.get("type").and_then(|t| t.as_str()) {
            Some("user") => self.user(rec, &ts),
            Some("assistant") => {
                self.after_compact_boundary = false;
                self.assistant(rec, &ts)
            }
            Some("system") => self.system(rec, &ts),
            Some("stream_event") => self.stream_event(rec, &ts),
            Some("result") => self.result(rec, &ts),
            Some("attachment") => self.attachment(rec, &ts),
            // Titles of the conversation itself. Records written by a sub-agent (sidechain /
            // `parent_tool_use_id`) describe the sub-task, never the session.
            Some("ai-title") => {
                if !is_sidechain(rec) && !rec_has_parent(rec) {
                    if let Some(t) = clean_title(s(rec, "aiTitle")) {
                        if self.custom_title.is_none() {
                            self.title = Some(t);
                            self.named = true;
                        }
                    }
                }
                vec![]
            }
            Some("custom-title") => {
                if !is_sidechain(rec) && !rec_has_parent(rec) {
                    if let Some(t) = clean_title(s(rec, "customTitle")) {
                        self.custom_title = Some(t.clone());
                        self.title = Some(t);
                        self.named = true;
                    }
                }
                vec![]
            }
            Some("summary") => {
                if self.title.is_none() && !is_sidechain(rec) {
                    self.title = clean_title(s(rec, "summary"));
                }
                vec![]
            }
            Some("cost-state") => {
                self.cost_usd = rec.get("totalCostUSD").and_then(|v| v.as_f64());
                vec![]
            }
            _ => vec![],
        };
        if let Some(p) = parent {
            for d in &mut out {
                if d.parent_id.is_none() {
                    d.parent_id = Some(p.clone());
                }
            }
        }
        if let Some(e) = early {
            out.insert(0, e);
        }
        out
    }

    /// `attachment` records. A `queued_command` is input that arrived while the agent was busy:
    /// a message the user typed mid-turn (`commandMode: "prompt"`) or a background task
    /// reporting back (`commandMode: "task-notification"`). Both are absorbed into the running
    /// turn, so no `user` record follows; everything else here is bookkeeping.
    fn attachment(&mut self, rec: &Value, ts: &str) -> Vec<EventDraft> {
        if is_sidechain(rec) {
            return vec![];
        }
        let Some(a) = rec.get("attachment") else {
            return vec![];
        };
        if a.get("type").and_then(Value::as_str) != Some("queued_command") {
            return vec![];
        }
        let prompt = a.get("prompt").cloned().unwrap_or(Value::Null);
        let text = content_text(&prompt);
        let uuid = s(rec, "uuid").unwrap_or_else(crate::util::new_id);
        let mode = a.get("commandMode").and_then(Value::as_str);
        if mode == Some("task-notification")
            || a.pointer("/origin/kind").and_then(Value::as_str) == Some("task-notification")
            || text.trim_start().starts_with("<task-notification>")
        {
            return self.task_notification(&uuid, &text, ts);
        }
        if mode == Some("prompt") && !text.trim().is_empty() {
            let synthetic = json!({
                "type": "user",
                "uuid": uuid,
                "timestamp": ts,
                "message": { "role": "user", "content": prompt },
            });
            return self.user(&synthetic, ts);
        }
        vec![]
    }

    fn user(&mut self, rec: &Value, ts: &str) -> Vec<EventDraft> {
        let after_compact = std::mem::take(&mut self.after_compact_boundary);
        if rec.get("isMeta").and_then(|v| v.as_bool()) == Some(true) {
            return vec![];
        }
        let uuid = s(rec, "uuid").unwrap_or_else(crate::util::new_id);
        let msg = rec.get("message").cloned().unwrap_or(Value::Null);
        let content = msg.get("content").cloned().unwrap_or(Value::Null);
        let mut out = Vec::new();
        // stream-json marks what a transcript calls `isMeta` as `isSynthetic` (the VS Code
        // extension maps one to the other): a prompt command's expanded text, the local command
        // caveat, the summary a /compact leaves behind. The user did not type it.
        let synthetic = rec.get("isSynthetic").and_then(Value::as_bool) == Some(true);

        // A background task reporting back. Claude Code delivers it as a user turn, but the user
        // did not write it: it updates the sub-agent row and never becomes a message or a title.
        let raw_text = content_text(&content);
        if is_task_notification(rec, &raw_text) {
            return self.task_notification(&uuid, &raw_text, ts);
        }

        // The extension takes the synthetic string message right after a `compact_boundary` as
        // the summary, with or without `isCompactSummary`.
        if rec.get("isCompactSummary").and_then(|v| v.as_bool()) == Some(true)
            || (synthetic && after_compact && content.is_string())
        {
            let (p, _) = preview(&content_text(&content), PREVIEW_LIMIT);
            out.push(EventDraft::new(
                format!("{uuid}:summary"),
                "system",
                ts,
                json!({ "subtype": "compact_summary", "text": p }),
            ));
            return out;
        }

        // Tool results.
        if let Value::Array(blocks) = &content {
            for b in blocks {
                if b.get("type").and_then(|t| t.as_str()) == Some("tool_result") {
                    let call_id = s(b, "tool_use_id").unwrap_or_default();
                    let is_error = b.get("is_error").and_then(|v| v.as_bool()).unwrap_or(false);
                    out.extend(
                        self.tool_result(
                            &call_id,
                            is_error,
                            &clean_tool_output(&tool_result_text(
                                b.get("content").unwrap_or(&Value::Null),
                            )),
                            // Transcripts write `toolUseResult`; stream-json writes `tool_use_result`.
                            rec.get("toolUseResult")
                                .or_else(|| rec.get("tool_use_result")),
                            ts,
                        ),
                    );
                }
            }
        }

        if synthetic {
            return out;
        }

        // Plain user text (string content or text blocks) or image-only user message.
        let raw = content_text(&content);
        let (text, context) = strip_injected_with_context(&raw);
        let image_items = extract_image_items(&content);
        let images = match &content {
            Value::Array(b) => b
                .iter()
                .filter(|x| x.get("type").and_then(|t| t.as_str()) == Some("image"))
                .count(),
            _ => 0,
        };
        if !text.is_empty() || images > 0 {
            if text.starts_with("[Request interrupted by user") {
                out.push(EventDraft::new(
                    format!("{uuid}:interrupt"),
                    "status",
                    ts,
                    json!({ "state": "interrupted", "detail": text }),
                ));
            } else if let Some(record) = slash_record(&text) {
                out.extend(self.slash_events(&uuid, record, ts));
            } else {
                if self.title.is_none()
                    && !is_sidechain(rec)
                    && !rec_has_parent(rec)
                    && !text.is_empty()
                    && !is_typed_slash_command(&text)
                {
                    self.title =
                        clean_title(Some(preview(&crate::redact::redact_text(&text), 120).0));
                }
                self.current_turn = Some(uuid.clone());
                let (p, truncated) = preview(&text, 64 * 1024);
                let mut d = EventDraft::new(
                    uuid.clone(),
                    "user_message",
                    ts,
                    json!({
                        "text": p,
                        "images": images,
                        "imageItems": image_items,
                        "context": context,
                        "source": "unknown",
                        "truncated": truncated
                    }),
                );
                d.turn_id = Some(uuid);
                out.push(d);
            }
        }
        out
    }

    /// A slash command record: the command is what the user typed (a user message that never
    /// names the conversation), a local command's output is a `system` note.
    fn slash_events(&mut self, uuid: &str, record: SlashRecord, ts: &str) -> Vec<EventDraft> {
        match record {
            SlashRecord::Command(cmd) => {
                self.current_turn = Some(uuid.to_string());
                let (p, truncated) = preview(&cmd, PREVIEW_LIMIT);
                let mut d = EventDraft::new(
                    uuid.to_string(),
                    "user_message",
                    ts,
                    json!({
                        "text": p,
                        "images": 0,
                        "imageItems": [],
                        "context": [],
                        "source": "unknown",
                        "truncated": truncated
                    }),
                );
                d.turn_id = Some(uuid.to_string());
                vec![d]
            }
            SlashRecord::Caveat => vec![],
            SlashRecord::Output(text) if text.is_empty() => vec![],
            SlashRecord::Output(text) => {
                let (p, _) = preview(&text, PREVIEW_LIMIT);
                vec![EventDraft::new(
                    format!("{uuid}:cmd"),
                    "system",
                    ts,
                    json!({ "subtype": "command", "text": p }),
                )]
            }
        }
    }

    fn tool_result(
        &mut self,
        call_id: &str,
        is_error: bool,
        text: &str,
        structured: Option<&Value>,
        ts: &str,
    ) -> Vec<EventDraft> {
        let mut out = Vec::new();
        self.open_tool_calls.retain(|c| c != call_id);
        let (name, turn) = match self.tools.get_mut(call_id) {
            Some(tc) => {
                let interrupted = structured
                    .and_then(|s| s.get("interrupted"))
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false);
                let denied = is_error
                    && (text.contains("doesn't want to proceed")
                        || text.contains("user rejected")
                        || text.contains("Permission denied by user")
                        || text.contains("was rejected"));
                // `run_in_background`: the result only says the command started; it runs on
                // until its `<task-notification>` (see `task_notification`).
                let bg_task = structured
                    .and_then(|s| s.get("backgroundTaskId"))
                    .and_then(Value::as_str)
                    .map(String::from);
                tc.data["status"] = json!(if denied {
                    "denied"
                } else if interrupted {
                    "interrupted"
                } else if is_error {
                    "failed"
                } else if bg_task.is_some() {
                    "running"
                } else {
                    "completed"
                });
                if let Some(task) = bg_task.filter(|_| !is_error) {
                    tc.data["background"] = json!(true);
                    // "… Output is being written to: <dir>/<task>.output. You will be notified …"
                    let want = format!("{task}.output");
                    if let Some(path) = text
                        .find("Output is being written to: ")
                        .map(|i| &text[i + "Output is being written to: ".len()..])
                        .and_then(|rest| rest.find(want.as_str()).map(|e| &rest[..e + want.len()]))
                    {
                        tc.data["outputFile"] = json!(path.trim());
                    }
                    tc.data["taskId"] = json!(task);
                    self.bg_shells.insert(task, call_id.to_string());
                }
                let mut d = EventDraft::new(
                    tc.event_id.clone(),
                    "tool_call",
                    tc.ts.clone(),
                    tc.data.clone(),
                );
                d.turn_id = tc.turn_id.clone();
                out.push(d);
                (tc.name.clone(), tc.turn_id.clone())
            }
            None => (String::new(), self.current_turn.clone()),
        };

        // Prefer structured stdout/stderr for shell tools.
        let mut body = text.to_string();
        let mut exit_code = None;
        if let Some(st) = structured.filter(|v| v.is_object()) {
            if let (Some(out_s), err_s) = (
                st.get("stdout").and_then(|v| v.as_str()),
                st.get("stderr").and_then(|v| v.as_str()),
            ) {
                body = match err_s {
                    Some(e) if !e.is_empty() => format!("{out_s}\n{e}"),
                    _ => out_s.to_string(),
                };
            }
            exit_code = st.get("exitCode").and_then(|v| v.as_i64());
        }
        let (p, truncated) = preview(&body, PREVIEW_LIMIT);
        let mut d = EventDraft::new(
            format!("res:{call_id}"),
            "tool_result",
            ts,
            json!({
                "callId": call_id, "isError": is_error, "text": p, "truncated": truncated,
                "bytes": body.len(), "exitCode": exit_code,
            }),
        );
        if truncated {
            d.payload = Some(body);
        }
        d.turn_id = turn.clone();
        out.push(d);

        // AskUserQuestion answered (on the computer, or anywhere for a transcript): the same
        // question card a live session shows, resolved, with what was chosen. A process
        // SwitchTether runs shows its own card from the permission request instead.
        if name == "AskUserQuestion" {
            if let Some(q) = answered_question(call_id, structured, ts) {
                let mut q = q;
                q.turn_id = turn.clone();
                out.push(q);
            }
        }

        // File changes from Edit / Write / MultiEdit structured results.
        if let Some(st) = structured.filter(|v| v.is_object()) {
            if let (Some(path), Some(patch)) = (s(st, "filePath"), st.get("structuredPatch")) {
                if patch.as_array().is_some_and(|a| !a.is_empty())
                    || st.get("type").and_then(|t| t.as_str()) == Some("create")
                {
                    let created = st.get("type").and_then(|t| t.as_str()) == Some("create");
                    let (diff, add, del) = if created
                        && patch.as_array().is_none_or(|a| a.is_empty())
                    {
                        let content = s(st, "content").unwrap_or_default();
                        let lines: Vec<String> = content.lines().map(|l| format!("+{l}")).collect();
                        (
                            format!(
                                "--- /dev/null\n+++ b/{path}\n@@ -0,0 +1,{} @@\n{}\n",
                                lines.len(),
                                lines.join("\n")
                            ),
                            lines.len() as u32,
                            0,
                        )
                    } else {
                        structured_patch_to_diff(&path, patch)
                    };
                    let (dp, dtrunc) = preview(&diff, 256 * 1024);
                    let mut fc = EventDraft::new(
                        format!("fc:{call_id}"),
                        "file_change",
                        ts,
                        json!({
                            "callId": call_id, "path": path,
                            "change": if created { "add" } else { "modify" },
                            "diff": dp, "additions": add, "deletions": del, "truncated": dtrunc,
                        }),
                    );
                    fc.turn_id = turn.clone();
                    fc.parent_id = None;
                    out.push(fc);
                }
            }
            // TaskStop / KillShell stopped a background task: no notification follows a stopped
            // shell, so its row is marked interrupted here (a stopped agent's row too).
            if !is_error && matches!(name.as_str(), "TaskStop" | "KillShell" | "KillBash") {
                if let Some(task) = s(st, "task_id").or_else(|| s(st, "shell_id")) {
                    out.extend(self.task_stopped(&task, ts));
                }
            }
            // `SendMessage` to a finished (or failed, stopped) agent resumes it: its row runs again
            // until its next task notification. Without this a resumed agent stayed "failed".
            if let Some(agent) = s(st, "resumedAgentId").or_else(|| s(st, "revivedAgentId")) {
                out.extend(self.subagent_resumed(&agent));
            }
            // Sub-agent result → subagent event (linked under the Task/Agent tool_call).
            if (name == "Task" || name == "Agent") && st.get("agentId").is_some() {
                out.push(self.subagent_started(call_id, is_error, text, st, turn, ts));
            }
        }
        out
    }

    /// A background task was stopped — by the model (TaskStop / KillShell) or from the phone's agent
    /// map (`stop_task`) — and no notification follows a stopped shell: its `tool_call` row, or the
    /// sub-agent's row, is marked `interrupted` so the session stops counting it as running work.
    pub fn task_stopped(&mut self, task_id: &str, ts: &str) -> Vec<EventDraft> {
        let mut out = Vec::new();
        if let Some(bg_call) = self.bg_shells.remove(task_id) {
            if let Some(tc) = self.tools.get_mut(&bg_call) {
                if tc.data.get("status").and_then(Value::as_str) == Some("running") {
                    tc.data["status"] = json!("interrupted");
                    tc.data["finishedAt"] = json!(ts);
                    let mut d = EventDraft::new(
                        tc.event_id.clone(),
                        "tool_call",
                        tc.ts.clone(),
                        tc.data.clone(),
                    );
                    d.turn_id = tc.turn_id.clone();
                    out.push(d);
                }
            }
        }
        let call = self.agent_calls.get(task_id).cloned().or_else(|| {
            self.subagents
                .iter()
                .find(|(_, r)| r.data.get("agentId").and_then(Value::as_str) == Some(task_id))
                .map(|(c, _)| c.clone())
        });
        if let Some(call) = call {
            if let Some(row) = self.subagents.get_mut(&call) {
                if row.data.get("status").and_then(Value::as_str) == Some("running") {
                    row.data["status"] = json!("interrupted");
                    row.data["finishedAt"] = json!(ts);
                    out.push(Self::subagent_draft(&call, row));
                }
            }
        }
        out
    }

    /// The `subagent` row for an Agent/Task result. A background agent's result only says it was
    /// launched (`async_launched`), so the row stays `running` until its task notification.
    fn subagent_started(
        &mut self,
        call_id: &str,
        is_error: bool,
        text: &str,
        st: &Value,
        turn: Option<String>,
        ts: &str,
    ) -> EventDraft {
        let input = self
            .tools
            .get(call_id)
            .and_then(|tc| tc.data.get("input").cloned())
            .unwrap_or(Value::Null);
        let title = self.tools.get(call_id).and_then(|tc| s(&tc.data, "title"));
        let agent_id = s(st, "agentId").unwrap_or_default();
        let raw = s(st, "status").unwrap_or_else(|| "completed".into());
        let background = raw == "async_launched"
            || st.get("isAsync").and_then(Value::as_bool) == Some(true)
            || input.get("run_in_background").and_then(Value::as_bool) == Some(true);
        let status = if is_error || st.get("success").and_then(Value::as_bool) == Some(false) {
            "failed"
        } else {
            subagent_status(&raw)
        };
        let mut data = json!({
            "agentId": agent_id,
            "description": s(&input, "description").or_else(|| s(st, "description")).or(title),
            "agentType": s(&input, "subagent_type").or_else(|| s(st, "agentType")),
            "model": s(st, "resolvedModel"),
            "background": background,
            "status": status,
            "callId": call_id,
        });
        if let Some(f) = s(st, "outputFile") {
            data["outputFile"] = json!(f);
        }
        if status != "running" {
            let (p, truncated) = preview(text.trim(), SUBAGENT_RESULT_LIMIT);
            if !p.is_empty() {
                data["result"] = json!(p);
                data["resultTruncated"] = json!(truncated);
            }
        }
        for (from, to) in [
            ("totalTokens", "tokens"),
            ("totalToolUseCount", "toolUses"),
            ("totalDurationMs", "durationMs"),
        ] {
            if let Some(v) = st.get(from).and_then(Value::as_i64) {
                data[to] = json!(v);
            }
        }
        // The row may exist since the agent started (see `ensure_running_subagent`): keep its
        // start time and what only the live records knew.
        let mut ts = ts.to_string();
        let mut turn = turn;
        if let Some(prev) = self.subagents.get(call_id) {
            for k in [
                "tokens",
                "toolUses",
                "durationMs",
                "summary",
                "model",
                "agentType",
            ] {
                if data.get(k).is_none_or(Value::is_null) {
                    if let Some(v) = prev.data.get(k).filter(|v| !v.is_null()) {
                        data[k] = v.clone();
                    }
                }
            }
            if status == "running" {
                if let Some(a) = prev.data.get("activity") {
                    data["activity"] = a.clone();
                }
            }
            ts = prev.ts.clone();
            turn = prev.turn_id.clone().or(turn);
        }
        if !agent_id.is_empty() {
            self.agent_calls.insert(agent_id, call_id.to_string());
        }
        self.subagents.insert(
            call_id.to_string(),
            SubagentRow {
                data: data.clone(),
                turn_id: turn.clone(),
                ts: ts.clone(),
            },
        );
        let mut sa = EventDraft::new(format!("sa:{call_id}"), "subagent", ts, data);
        sa.turn_id = turn;
        sa.parent_id = Some(call_id.to_string());
        sa
    }

    /// An agent that had finished was resumed (`SendMessage` → `resumedAgentId`): its row is
    /// running again; the old result and end time no longer describe it.
    fn subagent_resumed(&mut self, agent_id: &str) -> Option<EventDraft> {
        let call_id = self.agent_calls.get(agent_id)?.clone();
        let row = self.subagents.get_mut(&call_id)?;
        if row.data["status"] == "running" {
            return None;
        }
        row.data["status"] = json!("running");
        if let Some(obj) = row.data.as_object_mut() {
            for k in [
                "result",
                "resultTruncated",
                "finishedAt",
                "summary",
                "activity",
            ] {
                obj.remove(k);
            }
        }
        Some(Self::subagent_draft(&call_id, row))
    }

    /// A sub-agent that is running but has no result yet (foreground agents report only when
    /// they finish) gets its row now, so its steps can be followed while it works. Called from
    /// the stream's `task_started` and from the first record the agent writes (`agent_id` +
    /// `parent_tool_use_id`). Returns None when the row exists or the call is not Agent/Task.
    fn ensure_running_subagent(
        &mut self,
        call_id: &str,
        agent_id: &str,
        background: Option<bool>,
        ts: &str,
    ) -> Option<EventDraft> {
        if self.subagents.contains_key(call_id) {
            return None;
        }
        let tc = self.tools.get(call_id)?;
        if !matches!(tc.name.as_str(), "Agent" | "Task") {
            return None;
        }
        let input = tc.data.get("input").cloned().unwrap_or(Value::Null);
        let background = background.unwrap_or_else(|| {
            input.get("run_in_background").and_then(Value::as_bool) == Some(true)
        });
        let row = SubagentRow {
            data: json!({
                "agentId": agent_id,
                "description": s(&input, "description").or_else(|| s(&tc.data, "title")),
                "agentType": s(&input, "subagent_type"),
                "background": background,
                "status": "running",
                "callId": call_id,
            }),
            turn_id: tc.turn_id.clone(),
            ts: ts.to_string(),
        };
        if !agent_id.is_empty() {
            self.agent_calls
                .insert(agent_id.to_string(), call_id.to_string());
        }
        let d = Self::subagent_draft(call_id, &row);
        self.subagents.insert(call_id.to_string(), row);
        Some(d)
    }

    /// `<task-notification>` (transcript, or a user turn of the stream): a background agent (or
    /// background shell task) finished. See [`Self::apply_task_note`].
    fn task_notification(&mut self, uuid: &str, text: &str, ts: &str) -> Vec<EventDraft> {
        let num = |tag: &str| xml_tag(text, tag).and_then(|v| v.parse::<i64>().ok());
        let note = TaskNote {
            task_id: xml_tag(text, "task-id").map(String::from),
            tool_use_id: xml_tag(text, "tool-use-id").map(String::from),
            status: xml_tag(text, "status").map(String::from),
            summary: xml_tag(text, "summary").map(String::from),
            result: xml_tag(text, "result").map(String::from),
            tokens: num("subagent_tokens"),
            tool_uses: num("tool_uses"),
            duration_ms: num("duration_ms"),
        };
        self.apply_task_note(uuid, note, ts)
    }

    /// Stream-json `system` records about background tasks (`task_started`, `task_progress`,
    /// `task_notification`). The stream reports background work this way instead of the
    /// transcript's `<task-notification>` user turns, so a managed session updates the same rows.
    fn task_system(&mut self, subtype: &str, rec: &Value, uuid: &str, ts: &str) -> Vec<EventDraft> {
        let task_id = s(rec, "task_id");
        let tool_use_id = s(rec, "tool_use_id");
        let usage = rec.get("usage");
        let num = |k: &str| usage.and_then(|u| u.get(k)).and_then(Value::as_i64);
        match subtype {
            // Learn task id -> call id early: a shell's notification may name only the task.
            "task_started" => {
                let (Some(t), Some(c)) = (task_id, tool_use_id) else {
                    return vec![];
                };
                if s(rec, "task_type").as_deref() == Some("local_bash") {
                    self.bg_shells.insert(t, c);
                    return vec![];
                }
                let background = rec.get("is_backgrounded").and_then(Value::as_bool);
                self.agent_calls.insert(t.clone(), c.clone());
                self.ensure_running_subagent(&c, &t, background, ts)
                    .into_iter()
                    .collect()
            }
            // A running agent's live counters and what it is doing now.
            "task_progress" => {
                let call_id = tool_use_id
                    .filter(|c| self.subagents.contains_key(c))
                    .or_else(|| task_id.and_then(|t| self.agent_calls.get(&t).cloned()));
                let Some(call_id) = call_id else {
                    return vec![];
                };
                let Some(row) = self.subagents.get_mut(&call_id) else {
                    return vec![];
                };
                if row.data["status"] != "running" {
                    return vec![];
                }
                for (k, key) in [
                    ("total_tokens", "tokens"),
                    ("tool_uses", "toolUses"),
                    ("duration_ms", "durationMs"),
                ] {
                    if let Some(n) = num(k) {
                        row.data[key] = json!(n);
                    }
                }
                if let Some(a) = s(rec, "description").filter(|a| !a.trim().is_empty()) {
                    row.data["activity"] = json!(preview(&a, 200).0);
                }
                vec![Self::subagent_draft(&call_id, row)]
            }
            _ => {
                // An agent's notification carries its final answer as the summary; a shell's
                // is the one-line "Background command … completed (exit code N)".
                let summary = s(rec, "summary");
                let is_agent = tool_use_id
                    .as_ref()
                    .is_some_and(|c| self.subagents.contains_key(c))
                    || task_id
                        .as_ref()
                        .is_some_and(|t| self.agent_calls.contains_key(t));
                let note = TaskNote {
                    task_id,
                    tool_use_id,
                    status: s(rec, "status"),
                    result: summary.clone().filter(|_| is_agent),
                    summary: summary.map(|t| {
                        if is_agent {
                            t.lines()
                                .map(str::trim)
                                .find(|l| !l.is_empty())
                                .unwrap_or("")
                                .to_string()
                        } else {
                            t
                        }
                    }),
                    tokens: num("total_tokens"),
                    tool_uses: num("tool_uses"),
                    duration_ms: num("duration_ms"),
                };
                self.apply_task_note(uuid, note, ts)
            }
        }
    }

    /// A background task finished. Known sub-agents and background shells are updated in place;
    /// anything else becomes a short system note.
    fn apply_task_note(&mut self, uuid: &str, note: TaskNote, ts: &str) -> Vec<EventDraft> {
        if let Some(d) = self.background_shell_finished(&note, ts) {
            return vec![d];
        }
        let call_id = note
            .tool_use_id
            .clone()
            .filter(|c| self.subagents.contains_key(c))
            .or_else(|| {
                note.task_id
                    .as_ref()
                    .and_then(|t| self.agent_calls.get(t).cloned())
            });
        let status = note.status.as_deref().map(subagent_status);
        let summary = note.summary.as_deref().map(|t| preview(t, 300).0);
        let Some(row) = call_id.as_deref().and_then(|c| self.subagents.get_mut(c)) else {
            return vec![EventDraft::new(
                format!("{uuid}:task"),
                "system",
                ts,
                json!({
                    "subtype": "task_notification",
                    "status": status,
                    "text": summary.unwrap_or_else(|| "Background task finished".into()),
                }),
            )];
        };
        let call_id = call_id.unwrap_or_default();
        if let Some(st) = status {
            row.data["status"] = json!(st);
        }
        if let Some(sm) = summary.filter(|t| !t.is_empty()) {
            row.data["summary"] = json!(sm);
        }
        if let Some(r) = note.result.as_deref().filter(|r| !r.trim().is_empty()) {
            let (p, truncated) = preview(r.trim(), SUBAGENT_RESULT_LIMIT);
            row.data["result"] = json!(p);
            row.data["resultTruncated"] = json!(truncated);
        }
        for (v, key) in [
            (note.tokens, "tokens"),
            (note.tool_uses, "toolUses"),
            (note.duration_ms, "durationMs"),
        ] {
            if let Some(n) = v {
                row.data[key] = json!(n);
            }
        }
        if let Some(o) = row.data.as_object_mut() {
            o.remove("activity");
        }
        row.data["finishedAt"] = json!(ts);
        vec![Self::subagent_draft(&call_id, row)]
    }

    fn subagent_draft(call_id: &str, row: &SubagentRow) -> EventDraft {
        let mut sa = EventDraft::new(
            format!("sa:{call_id}"),
            "subagent",
            row.ts.clone(),
            row.data.clone(),
        );
        sa.turn_id = row.turn_id.clone();
        sa.parent_id = Some(call_id.to_string());
        sa
    }

    /// A background shell command (`Bash` with `run_in_background`) finished: its `tool_call`
    /// row is updated in place (status, the notification's summary, exit code). Monitors and
    /// other tasks without a known Bash call return None.
    fn background_shell_finished(&mut self, note: &TaskNote, ts: &str) -> Option<EventDraft> {
        let call_id = note
            .tool_use_id
            .clone()
            .filter(|c| {
                self.tools
                    .get(c)
                    .is_some_and(|tc| tc.data.get("background") == Some(&json!(true)))
            })
            .or_else(|| {
                note.task_id
                    .as_ref()
                    .and_then(|t| self.bg_shells.get(t).cloned())
            })?;
        let tc = self.tools.get_mut(&call_id)?;
        let summary = note.summary.as_deref().map(|t| preview(t, 300).0);
        // "Background command "…" completed (exit code 0)"
        let exit_code = summary.as_deref().and_then(|t| {
            let rest = &t[t.rfind("exit code ")? + "exit code ".len()..];
            rest.trim_end_matches(')').trim().parse::<i64>().ok()
        });
        let status = match note.status.as_deref().map(subagent_status) {
            Some("completed") if exit_code.is_some_and(|c| c != 0) => "failed",
            Some(st) => st,
            None => "completed",
        };
        tc.data["status"] = json!(status);
        if let Some(sm) = summary {
            tc.data["backgroundSummary"] = json!(sm);
        }
        if let Some(c) = exit_code {
            tc.data["exitCode"] = json!(c);
        }
        tc.data["finishedAt"] = json!(ts);
        let mut d = EventDraft::new(
            tc.event_id.clone(),
            "tool_call",
            tc.ts.clone(),
            tc.data.clone(),
        );
        d.turn_id = tc.turn_id.clone();
        Some(d)
    }

    fn assistant(&mut self, rec: &Value, ts: &str) -> Vec<EventDraft> {
        let msg = rec.get("message").cloned().unwrap_or(Value::Null);
        let msg_id = s(&msg, "id")
            .or_else(|| s(rec, "uuid"))
            .unwrap_or_else(crate::util::new_id);
        if let Some(m) = s(&msg, "model").filter(|m| m != "<synthetic>") {
            self.model = Some(m);
        }
        let mut out = Vec::new();
        let turn = self.current_turn.clone();
        if rec.get("isApiErrorMessage").and_then(|v| v.as_bool()) == Some(true) {
            let text = content_text(msg.get("content").unwrap_or(&Value::Null));
            out.push(EventDraft::new(
                format!("{msg_id}:err"),
                "error",
                ts,
                json!({ "message": text }),
            ));
            return out;
        }
        if let Some(Value::Array(blocks)) = msg.get("content") {
            for b in blocks {
                let id = self.next_block_id(&msg_id);
                // A streamed block with the same position is now final.
                let mut d = match b.get("type").and_then(|t| t.as_str()) {
                    Some("text") => {
                        let text = s(b, "text").unwrap_or_default();
                        if text.trim().is_empty() {
                            continue;
                        }
                        EventDraft::new(
                            id,
                            "assistant_message",
                            ts,
                            json!({ "text": text, "model": self.model }),
                        )
                    }
                    Some("thinking") => {
                        let text = s(b, "thinking").unwrap_or_default();
                        if text.trim().is_empty() {
                            continue;
                        }
                        EventDraft::new(
                            id,
                            "reasoning",
                            ts,
                            json!({ "text": text, "summary": false }),
                        )
                    }
                    Some("redacted_thinking") => continue,
                    Some("tool_use") => {
                        let call_id = s(b, "id").unwrap_or_else(|| id.clone());
                        let name = s(b, "name").unwrap_or_default();
                        let input = b.get("input").cloned().unwrap_or(Value::Null);
                        let mut extra = Vec::new();
                        if name == "TodoWrite" {
                            if let Some(todos) = input.get("todos").and_then(|t| t.as_array()) {
                                let items: Vec<Value> = todos
                                    .iter()
                                    .map(|t| json!({
                                        "text": t.get("content").or_else(|| t.get("activeForm")).cloned().unwrap_or(json!("")),
                                        "status": t.get("status").cloned().unwrap_or(json!("pending")),
                                    }))
                                    .collect();
                                extra.push(EventDraft::new(
                                    format!("plan:{call_id}"),
                                    "plan",
                                    ts,
                                    json!({ "items": items }),
                                ));
                            }
                        }
                        let (server, tool) = match name.strip_prefix("mcp__") {
                            Some(rest) => {
                                let mut it = rest.splitn(2, "__");
                                (
                                    it.next().map(|x| x.to_string()),
                                    it.next().map(|x| x.to_string()),
                                )
                            }
                            None => (None, None),
                        };
                        let input_str = input.to_string();
                        let input_v = if input_str.len() > PREVIEW_LIMIT * 4 {
                            // Too big to carry whole (a long script, a large Write): keep the short
                            // fields the phone titles the step with (Bash's one-line description,
                            // the file a file tool works on).
                            let mut v = json!({ "truncated": true, "preview": preview(&input_str, PREVIEW_LIMIT).0 });
                            for key in ["description", "file_path", "notebook_path", "path"] {
                                if let Some(text) = input.get(key).and_then(Value::as_str) {
                                    if text.len() <= 1024 {
                                        v[key] = json!(text);
                                    }
                                }
                            }
                            v
                        } else {
                            input.clone()
                        };
                        let data = json!({
                            "callId": call_id, "name": name, "title": tool_title(&name, &input),
                            "category": tool_category(&name), "input": input_v, "status": "running",
                            "server": server, "tool": tool,
                        });
                        self.tools.insert(
                            call_id.clone(),
                            ToolCall {
                                event_id: id.clone(),
                                name: name.clone(),
                                data: data.clone(),
                                turn_id: turn.clone(),
                                ts: ts.to_string(),
                            },
                        );
                        self.open_tool_calls.push(call_id);
                        let mut d = EventDraft::new(id, "tool_call", ts, data);
                        d.turn_id = turn.clone();
                        out.push(d);
                        for mut e in extra {
                            e.turn_id = turn.clone();
                            out.push(e);
                        }
                        continue;
                    }
                    Some(other) => EventDraft::new(
                        id,
                        "system",
                        ts,
                        json!({ "subtype": other, "text": format!("[{other}]") }),
                    ),
                    None => continue,
                };
                d.turn_id = turn.clone();
                out.push(d);
            }
        }
        if msg.get("stop_reason").and_then(|v| v.as_str()) == Some("end_turn") {
            if let Some(u) = msg.get("usage") {
                let mut d = EventDraft::new(
                    format!("{msg_id}:usage"),
                    "usage",
                    ts,
                    json!({
                        "inputTokens": u.get("input_tokens"), "outputTokens": u.get("output_tokens"),
                        "cacheReadTokens": u.get("cache_read_input_tokens"),
                        "cacheWriteTokens": u.get("cache_creation_input_tokens"),
                    }),
                );
                d.turn_id = turn;
                out.push(d);
            }
        }
        out
    }

    fn system(&mut self, rec: &Value, ts: &str) -> Vec<EventDraft> {
        let uuid = s(rec, "uuid").unwrap_or_else(crate::util::new_id);
        let subtype = s(rec, "subtype").unwrap_or_else(|| "info".into());
        match subtype.as_str() {
            "task_started" | "task_progress" | "task_notification" => {
                return self.task_system(&subtype, rec, &uuid, ts);
            }
            // The live list of background tasks (stream-json only).
            "background_tasks_changed" => {
                self.background_tasks = rec
                    .get("tasks")
                    .and_then(Value::as_array)
                    .map_or(0, Vec::len);
                return vec![];
            }
            _ => {}
        }
        let text = match subtype.as_str() {
            "init" => {
                if let Some(m) = s(rec, "model") {
                    self.model = Some(m);
                }
                if let Some(c) = s(rec, "cwd") {
                    self.cwd = Some(c);
                }
                format!(
                    "Session started{}",
                    self.model
                        .as_deref()
                        .map(|m| format!(" · {m}"))
                        .unwrap_or_default()
                )
            }
            "compact_boundary" => {
                self.after_compact_boundary = true;
                "Conversation compacted".to_string()
            }
            "api_error" => {
                let attempt = rec
                    .get("retryAttempt")
                    .and_then(|v| v.as_i64())
                    .unwrap_or(0);
                let max = rec.get("maxRetries").and_then(|v| v.as_i64()).unwrap_or(0);
                format!("API error, retrying ({attempt}/{max})")
            }
            // Bookkeeping records (status, thinking_tokens, commands_changed, …)
            // carry no user-visible text: skip them.
            _ => match s(rec, "content").filter(|c| !c.trim().is_empty()) {
                // Transcripts keep local slash commands and their output as `system` records
                // (`local_command`) in the same wrapper tags as user records.
                Some(c) => match slash_record(&c) {
                    Some(record) => return self.slash_events(&uuid, record, ts),
                    None => strip_ansi(&c),
                },
                None => return vec![],
            },
        };
        let (p, _) = preview(&text, PREVIEW_LIMIT);
        vec![EventDraft::new(
            uuid,
            "system",
            ts,
            json!({ "subtype": subtype, "text": p }),
        )]
    }

    fn stream_event(&mut self, rec: &Value, ts: &str) -> Vec<EventDraft> {
        let ev = rec.get("event").cloned().unwrap_or(Value::Null);
        match ev.get("type").and_then(|t| t.as_str()) {
            Some("message_start") => {
                self.stream_msg_id = ev.get("message").and_then(|m| s(m, "id"));
                self.stream_blocks.clear();
                vec![]
            }
            Some("content_block_start") => {
                let (Some(mid), Some(idx)) = (
                    self.stream_msg_id.clone(),
                    ev.get("index").and_then(|i| i.as_u64()),
                ) else {
                    return vec![];
                };
                let kind = match ev
                    .get("content_block")
                    .and_then(|b| b.get("type"))
                    .and_then(|t| t.as_str())
                {
                    Some("text") => "assistant_message",
                    Some("thinking") => "reasoning",
                    _ => return vec![],
                };
                self.stream_blocks
                    .insert(idx as usize, (format!("{mid}:{idx}"), kind, String::new()));
                vec![]
            }
            Some("content_block_delta") => {
                let idx = ev.get("index").and_then(|i| i.as_u64()).unwrap_or(0) as usize;
                let delta = ev.get("delta").cloned().unwrap_or(Value::Null);
                let piece = s(&delta, "text")
                    .or_else(|| s(&delta, "thinking"))
                    .unwrap_or_default();
                let Some((id, kind, text)) = self.stream_blocks.get_mut(&idx) else {
                    return vec![];
                };
                text.push_str(&piece);
                let data = if *kind == "reasoning" {
                    json!({ "text": text, "summary": false })
                } else {
                    json!({ "text": text, "model": self.model })
                };
                let mut d = EventDraft::new(id.clone(), kind, ts, data);
                d.streaming = true;
                d.turn_id = self.current_turn.clone();
                vec![d]
            }
            _ => vec![],
        }
    }

    fn result(&mut self, rec: &Value, ts: &str) -> Vec<EventDraft> {
        self.result_count += 1;
        let is_error = rec
            .get("is_error")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        let subtype = s(rec, "subtype").unwrap_or_default();
        if let Some(c) = rec.get("total_cost_usd").and_then(|v| v.as_f64()) {
            self.cost_usd = Some(c);
        }
        let u = rec.get("usage").cloned().unwrap_or(Value::Null);
        let mut out = vec![EventDraft::new(
            format!("result:{}", self.result_count),
            "usage",
            ts,
            json!({
                "inputTokens": u.get("input_tokens"), "outputTokens": u.get("output_tokens"),
                "cacheReadTokens": u.get("cache_read_input_tokens"),
                "cacheWriteTokens": u.get("cache_creation_input_tokens"),
                "costUsd": rec.get("total_cost_usd"),
                "durationMs": rec.get("duration_ms"),
            }),
        )];
        if is_error && subtype == "error_during_execution" {
            // How Claude Code reports a turn that was interrupted mid-way.
            out.push(EventDraft::new(
                format!("result:{}:stopped", self.result_count),
                "system",
                ts,
                json!({ "subtype": "turn_stopped", "text": "Turn stopped before completion" }),
            ));
        } else if is_error {
            out.push(EventDraft::new(
                format!("result:{}:err", self.result_count),
                "error",
                ts,
                json!({ "message": s(rec, "result").unwrap_or(subtype) }),
            ));
        }
        out
    }
}

/// Id prefix of the question cards [`answered_question`] makes from transcripts.
pub const ANSWERED_QUESTION_PREFIX: &str = "q:";

/// A resolved `question` event for an AskUserQuestion result (`toolUseResult` `{questions,
/// answers: {<question text>: <answer>}}`), in the shape of a live question card: ids are the
/// question texts, so `answers` matches them. `None` when the result has no answers (declined).
fn answered_question(call_id: &str, structured: Option<&Value>, ts: &str) -> Option<EventDraft> {
    let st = structured?;
    let answers = st.get("answers").filter(|a| a.is_object())?;
    let questions: Vec<Value> = st
        .get("questions")
        .and_then(Value::as_array)?
        .iter()
        .enumerate()
        .map(|(i, q)| {
            json!({
                "id": q.get("question").cloned().unwrap_or(json!(i.to_string())),
                "header": q.get("header"), "question": q.get("question"),
                "multiSelect": q.get("multiSelect").cloned().unwrap_or(json!(false)),
                "allowOther": true,
                "options": q.get("options").cloned().unwrap_or(json!([])),
            })
        })
        .collect();
    let mut data = json!({
        "requestId": format!("{ANSWERED_QUESTION_PREFIX}{call_id}"), "questions": questions,
        "resolved": true, "decision": "answer", "callId": call_id,
    });
    crate::agents::model::record_answers(&mut data, answers);
    Some(EventDraft::new(
        format!("{ANSWERED_QUESTION_PREFIX}{call_id}"),
        "question",
        ts,
        data,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn feed_all(p: &mut ClaudeParser, lines: &[Value]) -> Vec<EventDraft> {
        lines.iter().flat_map(|l| p.feed(l)).collect()
    }

    #[test]
    fn user_assistant_tool_cycle() {
        let mut p = ClaudeParser::new();
        let ev = feed_all(
            &mut p,
            &[
                json!({"type":"user","uuid":"u1","timestamp":"2026-11-14T00:00:00Z","sessionId":"s1","cwd":"/p","message":{"role":"user","content":"Fix it<system-reminder>noise</system-reminder>"}}),
                json!({"type":"assistant","uuid":"a1","timestamp":"2026-11-14T00:00:01Z","message":{"id":"m1","model":"claude-x","content":[{"type":"thinking","thinking":"hmm"}]}}),
                json!({"type":"assistant","uuid":"a2","timestamp":"2026-11-14T00:00:02Z","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Edit","input":{"file_path":"/p/a.rs","old_string":"a","new_string":"b"}}]}}),
                json!({"type":"user","uuid":"u2","timestamp":"2026-11-14T00:00:03Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]},
                   "toolUseResult":{"filePath":"/p/a.rs","structuredPatch":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["-a","+b"]}]}}),
                json!({"type":"assistant","uuid":"a3","timestamp":"2026-11-14T00:00:04Z","message":{"id":"m2","content":[{"type":"text","text":"Done."}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}}),
            ],
        );
        let kinds: Vec<_> = ev.iter().map(|e| e.kind).collect();
        assert_eq!(
            kinds,
            vec![
                "user_message",
                "reasoning",
                "tool_call",
                "tool_call",
                "tool_result",
                "file_change",
                "assistant_message",
                "usage"
            ]
        );
        assert_eq!(ev[0].data["text"], "Fix it");
        assert_eq!(ev[2].id, "m1:1");
        assert_eq!(ev[2].data["category"], "edit");
        assert_eq!(ev[2].data["status"], "running");
        // updated in place
        assert_eq!(ev[3].id, "m1:1");
        assert_eq!(ev[3].data["status"], "completed");
        assert_eq!(ev[5].data["additions"], 1);
        assert!(ev[5].data["diff"].as_str().unwrap().contains("+b"));
        assert_eq!(p.model.as_deref(), Some("claude-x"));
        assert_eq!(p.session_id.as_deref(), Some("s1"));
        assert!(p.open_tool_calls.is_empty());
    }

    #[test]
    fn a_truncated_tool_input_keeps_its_description_and_path() {
        let mut p = ClaudeParser::new();
        let long = "echo x; ".repeat(PREVIEW_LIMIT);
        let ev = feed_all(
            &mut p,
            &[
                json!({"type":"assistant","uuid":"a1","timestamp":"2026-11-14T00:00:01Z","message":{"id":"m1","content":[
                    {"type":"tool_use","id":"t1","name":"Bash","input":{"command": long, "description":"Build the APK"}},
                    {"type":"tool_use","id":"t2","name":"Write","input":{"file_path":"/p/big.txt","content": long}}
                ]}}),
            ],
        );
        let input = &ev[0].data["input"];
        assert_eq!(input["truncated"], true);
        assert_eq!(input["description"], "Build the APK");
        assert!(input.get("command").is_none());
        assert_eq!(ev[1].data["input"]["file_path"], "/p/big.txt");
    }

    #[test]
    fn denied_and_large_results() {
        let mut p = ClaudeParser::new();
        feed_all(
            &mut p,
            &[
                json!({"type":"assistant","message":{"id":"m","content":[{"type":"tool_use","id":"t","name":"Bash","input":{"command":"rm -rf x"}}]}}),
            ],
        );
        let big = "x".repeat(PREVIEW_LIMIT + 10);
        let ev = p.feed(&json!({"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t","is_error":true,"content":"The user doesn't want to proceed with this tool use."}]}}));
        assert_eq!(ev[0].data["status"], "denied");
        feed_all(
            &mut p,
            &[
                json!({"type":"assistant","message":{"id":"m2","content":[{"type":"tool_use","id":"t2","name":"Bash","input":{"command":"cat big"}}]}}),
            ],
        );
        let ev = p.feed(&json!({"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"ignored"}]},"toolUseResult":{"stdout":big,"stderr":"","interrupted":false}}));
        let res = ev.iter().find(|e| e.kind == "tool_result").unwrap();
        assert_eq!(res.data["truncated"], true);
        assert!(res.payload.as_ref().unwrap().len() > PREVIEW_LIMIT);
    }

    #[test]
    fn todo_write_produces_plan() {
        let mut p = ClaudeParser::new();
        let ev = p.feed(&json!({"type":"assistant","message":{"id":"m","content":[{"type":"tool_use","id":"t","name":"TodoWrite","input":{"todos":[{"content":"a","status":"completed"},{"content":"b","status":"in_progress"}]}}]}}));
        let plan = ev.iter().find(|e| e.kind == "plan").unwrap();
        assert_eq!(plan.data["items"][1]["status"], "in_progress");
    }

    #[test]
    fn streaming_deltas_then_final() {
        let mut p = ClaudeParser::new();
        p.feed(
            &json!({"type":"stream_event","event":{"type":"message_start","message":{"id":"m9"}}}),
        );
        p.feed(&json!({"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}));
        let d1 = p.feed(&json!({"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}}));
        let d2 = p.feed(&json!({"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}}));
        assert_eq!(d1[0].id, "m9:0");
        assert!(d2[0].streaming);
        assert_eq!(d2[0].data["text"], "Hello");
        let fin = p.feed(&json!({"type":"assistant","message":{"id":"m9","content":[{"type":"text","text":"Hello!"}]}}));
        assert_eq!(fin[0].id, "m9:0");
        assert!(!fin[0].streaming);
    }

    #[test]
    fn bookkeeping_system_records_are_hidden() {
        let mut p = ClaudeParser::new();
        assert!(p
            .feed(&json!({"type":"system","subtype":"thinking_tokens"}))
            .is_empty());
        assert!(p
            .feed(&json!({"type":"system","subtype":"status","status":"x"}))
            .is_empty());
        assert_eq!(
            p.feed(&json!({"type":"system","subtype":"informational","content":"hi"}))[0].kind,
            "system"
        );
        assert_eq!(
            p.feed(&json!({"type":"system","subtype":"init","model":"m"}))[0].kind,
            "system"
        );
    }

    #[test]
    fn meta_and_commands() {
        let mut p = ClaudeParser::new();
        assert!(p
            .feed(&json!({"type":"user","isMeta":true,"message":{"content":"x"}}))
            .is_empty());
        let ev = p.feed(&json!({"type":"user","uuid":"c","message":{"content":"<command-name>/clear</command-name>"}}));
        assert_eq!(ev[0].kind, "user_message");
        assert_eq!(ev[0].data["text"], "/clear");
        assert!(p.title.is_none());
        assert!(p.feed(&json!({"type":"garbage"})).is_empty());
        assert!(p.feed(&json!("not an object")).is_empty());
        p.feed(&json!({"type":"ai-title","aiTitle":"My title"}));
        assert_eq!(p.title.as_deref(), Some("My title"));
    }

    /// A slash command is shown as the user typed it; what a local command printed is a readable
    /// `system` note. Neither shows wrapper tags, and output is never a user message.
    #[test]
    fn slash_commands_and_their_local_output() {
        let mut p = ClaudeParser::new();
        // Tag order differs between Claude Code versions.
        for (uuid, content, want) in [
            ("a", "<command-name>/compact</command-name>\n            <command-message>compact</command-message>\n            <command-args>keep the API notes</command-args>", "/compact keep the API notes"),
            ("b", "<command-message>review</command-message>\n<command-name>/review</command-name>\n<command-args>42</command-args>", "/review 42"),
            ("c", "<command-name>/clear</command-name>\n<command-message>clear</command-message>\n<command-args></command-args>", "/clear"),
            ("d", "<command-message>my-plugin:deploy</command-message>", "/my-plugin:deploy"),
        ] {
            let ev = p.feed(&json!({"type":"user","uuid":uuid,"message":{"content":content}}));
            assert_eq!(ev.len(), 1, "{uuid}");
            assert_eq!(ev[0].kind, "user_message", "{uuid}");
            assert_eq!(ev[0].data["text"], want, "{uuid}");
            assert_eq!(ev[0].turn_id.as_deref(), Some(uuid));
        }
        assert!(p.title.is_none());
        // Text blocks instead of a string.
        let ev = p.feed(&json!({"type":"user","uuid":"e","message":{"content":[
            {"type":"text","text":"<command-name>/cost</command-name><command-message>cost</command-message>"}]}}));
        assert_eq!(ev[0].data["text"], "/cost");
        // Local output, as a user record (older transcripts, stream replays) ...
        let ev = p.feed(&json!({"type":"user","uuid":"o1","message":{"content":
            "<local-command-stdout>\u{1b}[1mTotal cost:\u{1b}[22m $0.12\nDuration: 3m</local-command-stdout>"}}));
        assert_eq!(ev.len(), 1);
        assert_eq!(ev[0].kind, "system");
        assert_eq!(ev[0].data["subtype"], "command");
        assert_eq!(ev[0].data["text"], "Total cost: $0.12\nDuration: 3m");
        let ev = p.feed(&json!({"type":"user","uuid":"o2","message":{"content":
            "<local-command-stderr>Error: no such command</local-command-stderr>"}}));
        assert_eq!(ev[0].kind, "system");
        assert_eq!(ev[0].data["text"], "Error: no such command");
        // ... and as a `system` record (`local_command`), for the command and for its output.
        let ev = p.feed(&json!({"type":"system","subtype":"local_command","uuid":"s1","content":
            "<command-name>/mcp</command-name>\n<command-message>mcp</command-message>\n<command-args></command-args>"}));
        assert_eq!(ev[0].kind, "user_message");
        assert_eq!(ev[0].data["text"], "/mcp");
        let ev = p.feed(
            &json!({"type":"system","subtype":"local_command","uuid":"s2","content":
            "<local-command-stdout>No MCP servers configured.</local-command-stdout>"}),
        );
        assert_eq!(ev[0].kind, "system");
        assert_eq!(ev[0].data["text"], "No MCP servers configured.");
        // Any other `system` record with plain `content` is shown as it is. (No evidence that
        // stream-json uses a `local_command_output` subtype: the VS Code extension reads local
        // output from `<local-command-stdout>` user messages, see the next test.)
        let ev = p.feed(&json!({"type":"system","subtype":"local_command_output","uuid":"s3","content":"Compacted."}));
        assert_eq!(ev[0].kind, "system");
        assert_eq!(ev[0].data["text"], "Compacted.");
        // Empty output is nothing to show.
        assert!(p
            .feed(&json!({"type":"user","uuid":"o3","message":{"content":"<local-command-stdout></local-command-stdout>"}}))
            .is_empty());
        // The summary a /compact leaves behind and its boundary stay system notes.
        let ev = p.feed(&json!({"type":"system","subtype":"compact_boundary","uuid":"cb"}));
        assert_eq!(ev[0].data["text"], "Conversation compacted");
        let ev = p.feed(&json!({"type":"user","uuid":"cs","isCompactSummary":true,"message":{"content":"Summary of the work"}}));
        assert_eq!(ev[0].kind, "system");
        assert_eq!(ev[0].data["subtype"], "compact_summary");
        // An ordinary message that merely mentions a tag is untouched.
        let ev = p.feed(&json!({"type":"user","uuid":"u","message":{"content":"what does <command-name> mean?"}}));
        assert_eq!(ev[0].data["text"], "what does <command-name> mean?");
        assert_eq!(p.title.as_deref(), Some("what does <command-name> mean?"));
    }

    /// A slash command run in a stream-json session (`--replay-user-messages`), in the shapes the
    /// VS Code extension 2.1.331 handles: the command replayed under its own `uuid` as a
    /// `<command-name>` record, a prompt command's expanded text and the local command caveat as
    /// `isSynthetic` user messages (a transcript's `isMeta`), local output as a user message, the
    /// /compact summary as a synthetic string right after `compact_boundary`.
    #[test]
    fn stream_json_slash_command_records() {
        let mut p = ClaudeParser::new();
        // Typed text: a user message that never names the conversation.
        let ev = p.feed(&json!({"type":"user","uuid":"u0","message":{"content":[{"type":"text","text":"/review 42"}]}}));
        assert_eq!(ev[0].kind, "user_message");
        assert_eq!(ev[0].data["text"], "/review 42");
        assert!(p.title.is_none());
        // The replay: same id, so it replaces the bubble instead of adding one.
        let ev = p.feed(&json!({"type":"user","uuid":"u0","isReplay":true,"message":{"content":
            "<command-message>review</command-message>\n<command-name>/review</command-name>\n<command-args>42</command-args>"}}));
        assert_eq!((ev[0].id.as_str(), ev[0].kind), ("u0", "user_message"));
        assert_eq!(ev[0].data["text"], "/review 42");
        // The expanded prompt the model gets is not something the user typed.
        assert!(p
            .feed(&json!({"type":"user","uuid":"x1","isSynthetic":true,"isReplay":true,"message":{"content":
                [{"type":"text","text":"## Review the pull request\nRun gh pr view 42 and …"}]}}))
            .is_empty());
        // The caveat, flagged or not.
        for flag in [json!(true), Value::Null] {
            assert!(p
                .feed(&json!({"type":"user","uuid":"c1","isSynthetic":flag,"message":{"content":
                    "<local-command-caveat>Caveat: The messages below were generated by the user while running local commands. DO NOT respond to these messages.</local-command-caveat>"}}))
                .is_empty());
        }
        // Local output, with a forked skill's launch record behind it.
        let ev = p.feed(&json!({"type":"user","uuid":"o1","isReplay":true,"message":{"content":
            "<local-command-stdout>Launched skill</local-command-stdout>\n<forked-skill-launch>{\"agentId\":\"a1\",\"skillName\":\"s\",\"description\":\"/s\"}</forked-skill-launch>"}}));
        assert_eq!(ev.len(), 1);
        assert_eq!(ev[0].kind, "system");
        assert_eq!(ev[0].data["text"], "Launched skill");
        // /compact: boundary, then the summary as a synthetic string.
        p.feed(&json!({"type":"system","subtype":"compact_boundary","uuid":"cb","compact_metadata":{"trigger":"manual","pre_tokens":1000}}));
        let ev = p.feed(&json!({"type":"user","uuid":"cs","isSynthetic":true,"message":{"content":"This session is being continued … Summary: the work so far"}}));
        assert_eq!(ev.len(), 1);
        assert_eq!(ev[0].kind, "system");
        assert_eq!(ev[0].data["subtype"], "compact_summary");
        // Only right after the boundary.
        assert!(p
            .feed(&json!({"type":"user","uuid":"cs2","isSynthetic":true,"message":{"content":"later meta text"}}))
            .is_empty());
        // Tool results in a synthetic message still update their call.
        p.feed(
            &json!({"type":"assistant","uuid":"a1","message":{"id":"m1","content":[
            {"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}),
        );
        let ev = p.feed(
            &json!({"type":"user","uuid":"r1","isSynthetic":true,"message":{"content":[
            {"type":"tool_result","tool_use_id":"t1","content":"a.txt"}]}}),
        );
        assert!(ev.iter().any(|e| e.kind == "tool_call"), "{ev:?}");
        assert!(ev.iter().all(|e| e.kind != "user_message"));
        // A path is not a command: it names the conversation.
        p.feed(&json!({"type":"user","uuid":"u9","message":{"content":"/usr/local/bin/tool crashes on start"}}));
        assert_eq!(
            p.title.as_deref(),
            Some("/usr/local/bin/tool crashes on start")
        );
    }

    #[test]
    fn custom_title_beats_ai_title_and_subagents_never_title() {
        let mut p = ClaudeParser::new();
        p.feed(&json!({"type":"ai-title","aiTitle":"Generated\nsecond line"}));
        assert_eq!(p.title.as_deref(), Some("Generated"));
        p.feed(&json!({"type":"ai-title","isSidechain":true,"aiTitle":"Sub-agent task"}));
        p.feed(&json!({"type":"ai-title","parent_tool_use_id":"t1","aiTitle":"Other sub-agent"}));
        assert_eq!(p.title.as_deref(), Some("Generated"));
        p.feed(&json!({"type":"custom-title","customTitle":"  My rename "}));
        p.feed(&json!({"type":"ai-title","aiTitle":"Later generated"}));
        assert_eq!(p.title.as_deref(), Some("My rename"));
    }

    #[test]
    fn tail_title_reads_custom_then_ai_and_skips_sidechain() {
        let dir = tempfile::tempdir().unwrap();
        let f = dir.path().join("s.jsonl");
        let base = [
            r#"{"type":"ai-title","aiTitle":"First"}"#,
            r#"{"type":"user","message":{"content":"hi"}}"#,
            r#"{"type":"ai-title","aiTitle":"Latest"}"#,
            r#"{"type":"ai-title","isSidechain":true,"aiTitle":"Sub"}"#,
        ]
        .join("\n");
        std::fs::write(&f, &base).unwrap();
        assert_eq!(tail_title(&f).as_deref(), Some("Latest"));
        std::fs::write(
            &f,
            format!(
                "{base}\n{}",
                r#"{"type":"custom-title","customTitle":"Renamed"}"#
            ),
        )
        .unwrap();
        assert_eq!(tail_title(&f).as_deref(), Some("Renamed"));
    }
    #[test]
    fn first_prompt_is_a_fallback_not_a_subagent_or_command() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("s.jsonl");
        let records = [
            json!({"type":"user","isMeta":true,"message":{"content":"context"}}),
            json!({"type":"user","isSidechain":true,"message":{"content":"child task"}}),
            json!({"type":"user","message":{"content":"<command-name>/clear</command-name>"}}),
            json!({"type":"user","message":{"content":"Fix login\nMore details"}}),
        ];
        std::fs::write(
            &path,
            records
                .iter()
                .map(Value::to_string)
                .collect::<Vec<_>>()
                .join("\n"),
        )
        .unwrap();
        assert_eq!(tail_title(&path).as_deref(), Some("Fix login"));
        let mut parser = ClaudeParser::new();
        for record in records {
            parser.feed(&record);
        }
        parser.feed(&json!({"type":"ai-title","aiTitle":"Generated title"}));
        assert_eq!(parser.title.as_deref(), Some("Generated title"));
    }

    fn agent_call(id: &str, background: bool) -> Value {
        json!({"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":id,"name":"Agent",
            "input":{"description":"Scan files","prompt":"look","subagent_type":"Explore","run_in_background":background}}]}})
    }

    #[test]
    fn background_subagent_is_updated_by_its_task_notification() {
        let mut p = ClaudeParser::new();
        p.feed(&json!({"type":"user","uuid":"u1","message":{"content":"Do it"}}));
        p.feed(&agent_call("toolu_1", true));
        let started = p.feed(&json!({"type":"user","uuid":"u2",
            "message":{"content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"Async agent launched"}]},
            "toolUseResult":{"agentId":"a77","status":"async_launched","isAsync":true,"resolvedModel":"haiku"}}));
        let sa = started.iter().find(|d| d.kind == "subagent").unwrap();
        assert_eq!(sa.id, "sa:toolu_1");
        assert_eq!(sa.data["status"], "running");
        assert_eq!(sa.data["agentType"], "Explore");
        assert_eq!(sa.data["description"], "Scan files");
        assert_eq!(sa.data["background"], true);
        assert!(sa.data.get("result").is_none());

        let text =
            "<task-notification>\n<task-id>a77</task-id>\n<tool-use-id>toolu_1</tool-use-id>\n\
            <status>completed</status>\n<summary>Agent \"Scan files\" completed</summary>\n\
            <result>Found **3** files</result>\n<usage><subagent_tokens>1200</subagent_tokens>\
            <tool_uses>4</tool_uses><duration_ms>65000</duration_ms></usage>\n</task-notification>";
        let done = p.feed(
            &json!({"type":"user","uuid":"u3","origin":{"kind":"task-notification"},
            "message":{"content":text}}),
        );
        assert_eq!(done.len(), 1, "a notification is not a user message");
        let sa = &done[0];
        assert_eq!((sa.id.as_str(), sa.kind), ("sa:toolu_1", "subagent"));
        assert_eq!(sa.data["status"], "completed");
        assert_eq!(sa.data["result"], "Found **3** files");
        assert_eq!(sa.data["toolUses"], 4);
        assert_eq!(sa.data["durationMs"], 65000);
        assert_eq!(sa.turn_id.as_deref(), Some("u1"));
        assert_eq!(p.title.as_deref(), Some("Do it"));
    }

    #[test]
    fn a_resumed_agent_runs_again_until_its_next_notification() {
        let mut p = ClaudeParser::new();
        p.feed(&json!({"type":"user","uuid":"u1","message":{"content":"Do it"}}));
        p.feed(&agent_call("toolu_1", true));
        p.feed(&json!({"type":"user","uuid":"u2",
            "message":{"content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"Async agent launched"}]},
            "toolUseResult":{"agentId":"a77","status":"async_launched","isAsync":true}}));
        let note = |status: &str| {
            json!({"type":"user","uuid":format!("n-{status}"),"origin":{"kind":"task-notification"},
            "message":{"content":format!("<task-notification><task-id>a77</task-id><tool-use-id>toolu_1</tool-use-id><status>{status}</status><summary>s</summary><result>r</result></task-notification>")}})
        };
        let failed = p.feed(&note("failed"));
        assert_eq!(failed[0].data["status"], "failed");
        // SendMessage to the agent: Claude Code resumes it.
        p.feed(&json!({"type":"assistant","message":{"id":"m9","content":[{"type":"tool_use","id":"toolu_9","name":"SendMessage","input":{"to":"a77","message":"go on"}}]}}));
        let resumed = p.feed(&json!({"type":"user","uuid":"u9",
            "message":{"content":[{"type":"tool_result","tool_use_id":"toolu_9","content":"{\"success\":true}"}]},
            "toolUseResult":{"success":true,"message":"Resuming agent a77","resumedAgentId":"a77"}}));
        let sa = resumed
            .iter()
            .find(|d| d.kind == "subagent")
            .expect("the row is republished");
        assert_eq!(sa.id, "sa:toolu_1");
        assert_eq!(sa.data["status"], "running");
        assert!(sa.data.get("result").is_none());
        let done = p.feed(&note("completed"));
        assert_eq!(done[0].data["status"], "completed");
    }

    fn bash_call(id: &str) -> Value {
        json!({"type":"assistant","message":{"id":"m2","content":[{"type":"tool_use","id":id,"name":"Bash",
            "input":{"command":"cargo build","description":"Build","run_in_background":true}}]}})
    }

    #[test]
    fn background_shell_runs_until_its_notification() {
        let mut p = ClaudeParser::new();
        p.feed(&json!({"type":"user","uuid":"u1","message":{"content":"Build it"}}));
        p.feed(&bash_call("toolu_b"));
        let started = p.feed(&json!({"type":"user","uuid":"u2",
            "message":{"content":[{"type":"tool_result","tool_use_id":"toolu_b","content":"Command running in background with ID: bg1."}]},
            "toolUseResult":{"stdout":"","stderr":"","interrupted":false,"backgroundTaskId":"bg1"}}));
        let tc = started.iter().find(|d| d.kind == "tool_call").unwrap();
        assert_eq!(tc.data["status"], "running");
        assert_eq!(tc.data["background"], true);
        assert_eq!(tc.data["taskId"], "bg1");

        // Absorbed mid-turn: delivered as an attachment, not a user record.
        let done = p.feed(&json!({"type":"attachment","uuid":"a1","timestamp":"2026-11-17T18:01:39.520Z",
            "attachment":{"type":"queued_command","commandMode":"task-notification",
            "prompt":"<task-notification>\n<task-id>bg1</task-id>\n<tool-use-id>toolu_b</tool-use-id>\n<status>completed</status>\n<summary>Background command \"Build\" completed (exit code 2)</summary>\n</task-notification>"}}));
        assert_eq!(done.len(), 1);
        assert_eq!(done[0].kind, "tool_call");
        assert_eq!(done[0].id, tc.id, "the same row is updated in place");
        assert_eq!(done[0].data["status"], "failed", "non-zero exit code");
        assert_eq!(done[0].data["exitCode"], 2);
        assert_eq!(
            done[0].data["backgroundSummary"],
            "Background command \"Build\" completed (exit code 2)"
        );
        assert_eq!(p.title.as_deref(), Some("Build it"));
    }

    #[test]
    fn task_stop_interrupts_the_background_shell() {
        let mut p = ClaudeParser::new();
        p.feed(&bash_call("toolu_b"));
        p.feed(&json!({"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_b","content":"started"}]},
            "toolUseResult":{"stdout":"","backgroundTaskId":"bg2"}}));
        p.feed(&json!({"type":"assistant","message":{"id":"m3","content":[{"type":"tool_use","id":"toolu_s","name":"TaskStop","input":{"task_id":"bg2"}}]}}));
        let out = p.feed(&json!({"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_s","content":"Successfully stopped task: bg2"}]},
            "toolUseResult":{"message":"Successfully stopped task: bg2","task_id":"bg2","task_type":"local_bash"}}));
        let bg = out
            .iter()
            .find(|d| d.kind == "tool_call" && d.data["taskId"] == "bg2")
            .expect("background row updated");
        assert_eq!(bg.data["status"], "interrupted");
    }

    #[test]
    fn stopping_a_task_from_the_phone_interrupts_its_row() {
        let mut p = ClaudeParser::new();
        // A background shell: no notification follows its stop.
        p.feed(&bash_call("toolu_b"));
        p.feed(&json!({"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_b","content":"started"}]},
            "toolUseResult":{"stdout":"","backgroundTaskId":"bg3"}}));
        let out = p.task_stopped("bg3", "2026-11-28T15:00:00Z");
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].data["status"], "interrupted");
        assert!(
            p.task_stopped("bg3", "2026-11-28T15:00:01Z").is_empty(),
            "only once"
        );
        // A background agent, by its agent id.
        p.feed(&agent_call("toolu_1", true));
        p.feed(&json!({"type":"user","uuid":"u2",
            "message":{"content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"Async agent launched"}]},
            "toolUseResult":{"agentId":"a77","status":"async_launched","isAsync":true}}));
        let out = p.task_stopped("a77", "2026-11-28T15:00:02Z");
        let row = out
            .iter()
            .find(|d| d.kind == "subagent")
            .expect("agent row updated");
        assert_eq!(row.data["status"], "interrupted");
    }

    #[test]
    fn mid_turn_prompt_attachment_is_a_user_message() {
        let mut p = ClaudeParser::new();
        p.feed(&json!({"type":"user","uuid":"u1","message":{"content":"First"}}));
        let out = p.feed(&json!({"type":"attachment","uuid":"a2","timestamp":"2026-11-17T18:05:00Z",
            "attachment":{"type":"queued_command","commandMode":"prompt","prompt":[{"type":"text","text":"Also do this"}]}}));
        let um = out
            .iter()
            .find(|d| d.kind == "user_message")
            .expect("user message");
        assert_eq!(um.data["text"], "Also do this");
        // Other attachments are bookkeeping.
        assert!(p
            .feed(&json!({"type":"attachment","uuid":"a3","attachment":{"type":"todo_reminder"}}))
            .is_empty());
        assert!(p
            .feed(&json!({"type":"attachment","uuid":"a4","isSidechain":true,
            "attachment":{"type":"queued_command","commandMode":"prompt","prompt":"sub"}}))
            .is_empty());
    }

    /// Shapes recorded from `claude -p --output-format stream-json` 2.1.325 with a background
    /// Agent and a background Bash: the stream writes `tool_use_result` (snake case) and reports
    /// the tasks with `system` records instead of `<task-notification>` user turns.
    #[test]
    fn stream_json_background_agent_and_shell() {
        let mut p = ClaudeParser::new();
        p.feed(&json!({"type":"assistant","parent_tool_use_id":null,"message":{"id":"m1","content":[
            {"type":"tool_use","id":"toolu_sh","name":"Bash","input":{"command":"sleep 5","description":"Sleep","run_in_background":true}},
            {"type":"tool_use","id":"toolu_ag","name":"Agent","input":{"subagent_type":"general-purpose","description":"count files","prompt":"ls","run_in_background":true}}]}}));
        p.feed(&json!({"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"a9","task_type":"local_agent"}]}));
        p.feed(&json!({"type":"system","subtype":"task_started","task_id":"a9","tool_use_id":"toolu_ag","task_type":"local_agent","is_backgrounded":true}));
        let out = p.feed(&json!({"type":"user","parent_tool_use_id":null,"message":{"role":"user","content":[
            {"tool_use_id":"toolu_ag","type":"tool_result","content":[{"type":"text","text":"Async agent launched successfully."}]}]},
            "tool_use_result":{"isAsync":true,"status":"async_launched","agentId":"a9","description":"count files","resolvedModel":"claude-haiku-5-5"}}));
        let sa = out
            .iter()
            .find(|e| e.kind == "subagent")
            .expect("subagent row");
        assert_eq!(sa.data["status"], "running");
        assert_eq!(sa.data["background"], true);
        assert_eq!(sa.data["agentType"], "general-purpose");
        assert!(sa.data.get("result").is_none());

        p.feed(&json!({"type":"system","subtype":"task_started","task_id":"bsh","tool_use_id":"toolu_sh","task_type":"local_bash","is_backgrounded":true}));
        let out = p.feed(&json!({"type":"user","parent_tool_use_id":null,"message":{"role":"user","content":[
            {"tool_use_id":"toolu_sh","type":"tool_result","content":"Command running in background with ID: bsh.","is_error":false}]},
            "tool_use_result":{"stdout":"","stderr":"","interrupted":false,"backgroundTaskId":"bsh"}}));
        let sh = out.iter().find(|e| e.kind == "tool_call").unwrap();
        assert_eq!(
            sh.data["status"], "running",
            "a background shell is not done when launched"
        );
        assert_eq!(sh.data["background"], true);

        // The agent's own steps arrive inline, nested under its Agent call.
        let inner = p.feed(&json!({"type":"assistant","parent_tool_use_id":"toolu_ag","message":{"id":"m2","content":[
            {"type":"tool_use","id":"toolu_in","name":"Bash","input":{"command":"ls"}}]}}));
        assert_eq!(inner[0].parent_id.as_deref(), Some("toolu_ag"));

        let prog = p.feed(&json!({"type":"system","subtype":"task_progress","task_id":"a9","tool_use_id":"toolu_ag",
            "description":"Running List directory","usage":{"total_tokens":100,"tool_uses":1,"duration_ms":1700}}));
        assert_eq!(prog[0].id, "sa:toolu_ag");
        assert_eq!(prog[0].data["toolUses"], 1);
        assert_eq!(prog[0].data["activity"], "Running List directory");

        // The first turn ends while both tasks run.
        p.feed(&json!({"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"a9"},{"task_id":"bsh"}]}));
        assert_eq!(p.background_tasks, 2);

        let done = p.feed(&json!({"type":"system","subtype":"task_notification","task_id":"a9","tool_use_id":"toolu_ag",
            "status":"completed","summary":"The directory has 2 entries.\nDetails follow.","usage":{"total_tokens":380,"tool_uses":1,"duration_ms":5095}}));
        assert_eq!(done.len(), 1);
        assert_eq!(done[0].id, "sa:toolu_ag");
        assert_eq!(done[0].data["status"], "completed");
        assert_eq!(done[0].data["summary"], "The directory has 2 entries.");
        assert_eq!(
            done[0].data["result"],
            "The directory has 2 entries.\nDetails follow."
        );
        assert_eq!(done[0].data["durationMs"], 5095);
        assert!(done[0].data.get("activity").is_none());

        let done = p.feed(&json!({"type":"system","subtype":"task_notification","task_id":"bsh","tool_use_id":"toolu_sh",
            "status":"completed","summary":"Background command \"Sleep\" completed (exit code 0)"}));
        assert_eq!(done[0].kind, "tool_call");
        assert_eq!(done[0].data["status"], "completed");
        assert_eq!(done[0].data["exitCode"], 0);

        p.feed(&json!({"type":"system","subtype":"background_tasks_changed","tasks":[]}));
        assert_eq!(p.background_tasks, 0);
    }

    /// A foreground agent (recorded from stream-json 2.1.325) reports only when it finishes;
    /// its row exists from `task_started` on so the phone can follow it while it works.
    #[test]
    fn stream_json_foreground_agent_has_a_row_while_running() {
        let mut p = ClaudeParser::new();
        p.feed(&json!({"type":"assistant","message":{"id":"m1","content":[
            {"type":"tool_use","id":"toolu_fg","name":"Agent","input":{"subagent_type":"general-purpose","description":"list dir","prompt":"ls"}}]}}));
        let out = p.feed(&json!({"type":"system","subtype":"task_started","task_id":"ae5","tool_use_id":"toolu_fg",
            "description":"list dir","is_backgrounded":false,"task_type":"local_agent"}));
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].id, "sa:toolu_fg");
        assert_eq!(out[0].data["status"], "running");
        assert_eq!(out[0].data["agentId"], "ae5");
        assert_eq!(out[0].data["background"], false);
        assert_eq!(out[0].parent_id.as_deref(), Some("toolu_fg"));
        let start_ts = out[0].ts.clone();
        // Its own records do not create a second row.
        let inner = p.feed(&json!({"type":"assistant","parent_tool_use_id":"toolu_fg","agent_id":"ae5","message":{"id":"m2","content":[
            {"type":"tool_use","id":"toolu_in","name":"Bash","input":{"command":"ls"}}]}}));
        assert!(inner.iter().all(|e| e.kind != "subagent"));
        p.feed(&json!({"type":"system","subtype":"task_progress","task_id":"ae5","tool_use_id":"toolu_fg",
            "description":"Running ls","usage":{"total_tokens":10,"tool_uses":1,"duration_ms":900}}));
        p.feed(&json!({"type":"system","subtype":"task_notification","task_id":"ae5","tool_use_id":"toolu_fg","status":"completed"}));
        let res = p.feed(&json!({"type":"user","timestamp":"2026-11-21T00:00:09Z","message":{"role":"user","content":[
            {"tool_use_id":"toolu_fg","type":"tool_result","content":[{"type":"text","text":"3 files"}]}]},
            "tool_use_result":{"status":"completed","agentId":"ae5","agentType":"general-purpose"}}));
        let sa = res.iter().find(|e| e.kind == "subagent").unwrap();
        assert_eq!(sa.data["status"], "completed");
        assert_eq!(sa.data["result"], "3 files");
        assert_eq!(sa.data["toolUses"], 1, "live counters are kept");
        assert_eq!(sa.ts, start_ts, "the row keeps its start time");
        assert!(sa.data.get("activity").is_none());

        // Without `task_started`, the agent's first own record creates the row.
        let mut q = ClaudeParser::new();
        q.feed(&json!({"type":"assistant","message":{"id":"m1","content":[
            {"type":"tool_use","id":"toolu_x","name":"Task","input":{"description":"scan"}}]}}));
        let first = q.feed(&json!({"type":"user","parent_tool_use_id":"toolu_x","agent_id":"ax","message":{"role":"user","content":"go"}}));
        assert_eq!(first[0].id, "sa:toolu_x");
        assert_eq!(first[0].data["description"], "scan");
    }

    #[test]
    fn task_notification_without_tool_use_id_resolves_by_task_id() {
        let mut p = ClaudeParser::new();
        p.feed(&agent_call("toolu_2", true));
        p.feed(&json!({"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_2","content":"x"}]},
            "toolUseResult":{"agentId":"b1","status":"async_launched"}}));
        let out = p.feed(&json!({"type":"user","uuid":"n","message":{"content":
            "<task-notification><task-id>b1</task-id><status>killed</status></task-notification>"}}));
        assert_eq!(out[0].id, "sa:toolu_2");
        assert_eq!(out[0].data["status"], "interrupted");
    }

    #[test]
    fn unknown_task_notification_is_a_system_note_never_a_title() {
        let mut p = ClaudeParser::new();
        let out = p.feed(&json!({"type":"user","uuid":"n","origin":{"kind":"task-notification"},"message":{"content":
            "<task-notification><task-id>bash9</task-id><status>completed</status><summary>Build finished</summary></task-notification>"}}));
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].kind, "system");
        assert_eq!(out[0].data["subtype"], "task_notification");
        assert_eq!(out[0].data["text"], "Build finished");
        assert!(p.title.is_none());
    }

    #[test]
    fn synchronous_subagent_carries_its_result_and_stats() {
        let mut p = ClaudeParser::new();
        p.feed(&agent_call("toolu_3", false));
        let out = p.feed(&json!({"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_3","content":"All good"}]},
            "toolUseResult":{"agentId":"c1","status":"completed","totalTokens":900,"totalToolUseCount":2,"totalDurationMs":3000}}));
        let sa = out.iter().find(|d| d.kind == "subagent").unwrap();
        assert_eq!(sa.data["status"], "completed");
        assert_eq!(sa.data["background"], false);
        assert_eq!(sa.data["result"], "All good");
        assert_eq!(sa.data["toolUses"], 2);
    }

    #[test]
    fn ide_blocks_become_context_not_bubble_text_or_title() {
        let mut p = ClaudeParser::new();
        let text = "<ide_opened_file>The user opened the file d:/app/Main.kt in the IDE. This may or may not be related.</ide_opened_file><ide_selection>The user selected the lines 17 to 20 from d:/app/Main.kt:\nval x = 1</ide_selection><browser_instruction># Chrome\nlots</browser_instruction>Fix the dialog";
        let out = p.feed(
            &json!({"type":"user","uuid":"u","message":{"content":[{"type":"text","text":text}]}}),
        );
        let m = out.iter().find(|d| d.kind == "user_message").unwrap();
        assert_eq!(m.data["text"], "Fix the dialog");
        assert_eq!(m.data["context"][0]["kind"], "file");
        assert_eq!(m.data["context"][0]["path"], "d:/app/Main.kt");
        assert_eq!(m.data["context"][1]["lines"], "17-20");
        assert_eq!(p.title.as_deref(), Some("Fix the dialog"));
    }

    #[test]
    fn tool_output_drops_reminders_and_error_wrappers() {
        assert_eq!(
            clean_tool_output("<tool_use_error>Found 2 matches</tool_use_error>"),
            "Found 2 matches"
        );
        assert_eq!(
            clean_tool_output("ok\n<system-reminder>noise</system-reminder>"),
            "ok"
        );
    }

    /// An AskUserQuestion answered on the computer is a resolved question card with the choice,
    /// keyed like a live card (question text), next to the tool row.
    #[test]
    fn ask_user_question_answered_on_the_computer_shows_the_choice() {
        let mut p = ClaudeParser::new();
        let q = "Which release flow?";
        p.feed(&json!({"type":"assistant","uuid":"a1","timestamp":"2026-11-25T10:00:00Z",
            "message":{"role":"assistant","content":[{"type":"tool_use","id":"tq","name":"AskUserQuestion",
            "input":{"questions":[{"question":q,"header":"Release","multiSelect":false,
            "options":[{"label":"Nightly","description":"n"},{"label":"Stable","description":"s"}]}]}}]}}));
        let out = p.feed(&json!({"type":"user","uuid":"u2","timestamp":"2026-11-25T10:00:05Z",
            "message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tq",
            "content":"User has answered your questions: \"Which release flow?\"=\"Stable\"."}]},
            "toolUseResult":{"questions":[{"question":q,"header":"Release","multiSelect":false,
            "options":[{"label":"Nightly","description":"n"},{"label":"Stable","description":"s"}]}],
            "answers":{q:"Stable"}}}));
        let card = out
            .iter()
            .find(|d| d.kind == "question")
            .expect("question card");
        assert_eq!(card.id, "q:tq");
        assert_eq!(card.data["resolved"], true);
        assert_eq!(card.data["questions"][0]["id"], q);
        assert_eq!(card.data["answers"][q], "Stable");
        // Declined (no answers): no card, the tool row says so.
        let out = p.feed(&json!({"type":"user","uuid":"u3","timestamp":"2026-11-25T10:00:06Z",
            "message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tq2","is_error":true,
            "content":"The user doesn't want to proceed"}]}}));
        assert!(out.iter().all(|d| d.kind != "question"));
    }
}
