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
}

struct SubagentRow {
    data: Value,
    turn_id: Option<String>,
    ts: String,
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
        let mut out = match rec.get("type").and_then(|t| t.as_str()) {
            Some("user") => self.user(rec, &ts),
            Some("assistant") => self.assistant(rec, &ts),
            Some("system") => self.system(rec, &ts),
            Some("stream_event") => self.stream_event(rec, &ts),
            Some("result") => self.result(rec, &ts),
            // Titles of the conversation itself. Records written by a sub-agent (sidechain /
            // `parent_tool_use_id`) describe the sub-task, never the session.
            Some("ai-title") => {
                if !is_sidechain(rec) && !rec_has_parent(rec) {
                    if let Some(t) = clean_title(s(rec, "aiTitle")) {
                        if self.custom_title.is_none() {
                            self.title = Some(t);
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
        out
    }

    fn user(&mut self, rec: &Value, ts: &str) -> Vec<EventDraft> {
        if rec.get("isMeta").and_then(|v| v.as_bool()) == Some(true) {
            return vec![];
        }
        let uuid = s(rec, "uuid").unwrap_or_else(crate::util::new_id);
        let msg = rec.get("message").cloned().unwrap_or(Value::Null);
        let content = msg.get("content").cloned().unwrap_or(Value::Null);
        let mut out = Vec::new();

        // A background task reporting back. Claude Code delivers it as a user turn, but the user
        // did not write it: it updates the sub-agent row and never becomes a message or a title.
        let raw_text = content_text(&content);
        if is_task_notification(rec, &raw_text) {
            return self.task_notification(&uuid, &raw_text, ts);
        }

        if rec.get("isCompactSummary").and_then(|v| v.as_bool()) == Some(true) {
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
                    out.extend(self.tool_result(
                        &call_id,
                        is_error,
                        &clean_tool_output(&tool_result_text(
                            b.get("content").unwrap_or(&Value::Null),
                        )),
                        rec.get("toolUseResult"),
                        ts,
                    ));
                }
            }
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
            } else if text.starts_with("<command-name>")
                || text.starts_with("<local-command-stdout>")
                || text.starts_with("<command-message>")
            {
                let clean = text
                    .replace("<command-name>", "")
                    .replace("</command-name>", "")
                    .replace("<command-message>", "")
                    .replace("</command-message>", "")
                    .replace("<command-args>", " ")
                    .replace("</command-args>", "")
                    .replace("<local-command-stdout>", "")
                    .replace("</local-command-stdout>", "");
                let (p, _) = preview(clean.trim(), PREVIEW_LIMIT);
                out.push(EventDraft::new(
                    format!("{uuid}:cmd"),
                    "system",
                    ts,
                    json!({ "subtype": "command", "text": p }),
                ));
            } else {
                if self.title.is_none()
                    && !is_sidechain(rec)
                    && !rec_has_parent(rec)
                    && !text.is_empty()
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
                tc.data["status"] = json!(if denied {
                    "denied"
                } else if interrupted {
                    "interrupted"
                } else if is_error {
                    "failed"
                } else {
                    "completed"
                });
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
            // Sub-agent result → subagent event (linked under the Task/Agent tool_call).
            if (name == "Task" || name == "Agent") && st.get("agentId").is_some() {
                out.push(self.subagent_started(call_id, is_error, text, st, turn, ts));
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
        if !agent_id.is_empty() {
            self.agent_calls.insert(agent_id, call_id.to_string());
        }
        self.subagents.insert(
            call_id.to_string(),
            SubagentRow {
                data: data.clone(),
                turn_id: turn.clone(),
                ts: ts.to_string(),
            },
        );
        let mut sa = EventDraft::new(format!("sa:{call_id}"), "subagent", ts, data);
        sa.turn_id = turn;
        sa.parent_id = Some(call_id.to_string());
        sa
    }

    /// `<task-notification>`: a background agent (or background shell task) finished. Known
    /// sub-agents are updated in place; anything else becomes a short system note.
    fn task_notification(&mut self, uuid: &str, text: &str, ts: &str) -> Vec<EventDraft> {
        let call_id = xml_tag(text, "tool-use-id")
            .map(String::from)
            .filter(|c| self.subagents.contains_key(c))
            .or_else(|| xml_tag(text, "task-id").and_then(|t| self.agent_calls.get(t).cloned()));
        let status = xml_tag(text, "status").map(subagent_status);
        let summary = xml_tag(text, "summary").map(|t| preview(t, 300).0);
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
        if let Some(sm) = summary {
            row.data["summary"] = json!(sm);
        }
        if let Some(r) = xml_tag(text, "result") {
            let (p, truncated) = preview(r, SUBAGENT_RESULT_LIMIT);
            row.data["result"] = json!(p);
            row.data["resultTruncated"] = json!(truncated);
        }
        for (tag, key) in [
            ("subagent_tokens", "tokens"),
            ("tool_uses", "toolUses"),
            ("duration_ms", "durationMs"),
        ] {
            if let Some(n) = xml_tag(text, tag).and_then(|v| v.parse::<i64>().ok()) {
                row.data[key] = json!(n);
            }
        }
        row.data["finishedAt"] = json!(ts);
        let mut sa = EventDraft::new(
            format!("sa:{call_id}"),
            "subagent",
            row.ts.clone(),
            row.data.clone(),
        );
        sa.turn_id = row.turn_id.clone();
        sa.parent_id = Some(call_id);
        vec![sa]
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
                            json!({ "truncated": true, "preview": preview(&input_str, PREVIEW_LIMIT).0 })
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
            "compact_boundary" => "Conversation compacted".to_string(),
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
                Some(c) => c,
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
        assert_eq!(ev[0].kind, "system");
        assert!(p.feed(&json!({"type":"garbage"})).is_empty());
        assert!(p.feed(&json!("not an object")).is_empty());
        p.feed(&json!({"type":"ai-title","aiTitle":"My title"}));
        assert_eq!(p.title.as_deref(), Some("My title"));
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
}
