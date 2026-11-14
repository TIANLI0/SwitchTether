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
}

fn s(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(|x| x.as_str()).map(|x| x.to_string())
}

/// Remove `<system-reminder>…</system-reminder>` blocks injected by the harness.
fn strip_injected(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(start) = rest.find("<system-reminder>") {
        out.push_str(&rest[..start]);
        match rest[start..].find("</system-reminder>") {
            Some(end) => rest = &rest[start + end + "</system-reminder>".len()..],
            None => {
                rest = "";
            }
        }
    }
    out.push_str(rest);
    out.trim().to_string()
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
            Some("ai-title") => {
                self.title = s(rec, "aiTitle").or(self.title.take());
                vec![]
            }
            Some("summary") => {
                if self.title.is_none() {
                    self.title = s(rec, "summary");
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
                        &tool_result_text(b.get("content").unwrap_or(&Value::Null)),
                        rec.get("toolUseResult"),
                        ts,
                    ));
                }
            }
        }

        // Plain user text (string content or text blocks).
        let raw = content_text(&content);
        let text = strip_injected(&raw);
        if !text.is_empty() {
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
                self.current_turn = Some(uuid.clone());
                let images = match &content {
                    Value::Array(b) => b
                        .iter()
                        .filter(|x| x.get("type").and_then(|t| t.as_str()) == Some("image"))
                        .count(),
                    _ => 0,
                };
                let (p, truncated) = preview(&text, 64 * 1024);
                let mut d = EventDraft::new(
                    uuid.clone(),
                    "user_message",
                    ts,
                    json!({ "text": p, "images": images, "source": "unknown", "truncated": truncated }),
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
            // Sub-agent result → subagent event.
            if (name == "Task" || name == "Agent") && st.get("agentId").is_some() {
                let mut sa = EventDraft::new(
                    format!("sa:{call_id}"),
                    "subagent",
                    ts,
                    json!({
                        "agentId": st.get("agentId"), "status": st.get("status").cloned().unwrap_or(json!("completed")),
                        "callId": call_id,
                    }),
                );
                sa.turn_id = turn;
                out.push(sa);
            }
        }
        out
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
            _ => s(rec, "content").unwrap_or_else(|| subtype.clone()),
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
}
