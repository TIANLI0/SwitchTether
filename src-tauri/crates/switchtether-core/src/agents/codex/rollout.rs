//! Codex rollout (`~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`) parser →
//! normalized events. Rollout lines are `{timestamp, type, payload}` with
//! `type` ∈ `session_meta`, `response_item`, `event_msg`, `turn_context`,
//! `compacted`. Response items are the canonical conversation; selected
//! `event_msg`s add usage/abort information. Unknown shapes are ignored.

use std::collections::HashMap;

use serde_json::{json, Value};

use crate::agents::model::{
    diff_stats, preview, tool_category, tool_title, EventDraft, PREVIEW_LIMIT,
};

#[derive(Default)]
pub struct CodexParser {
    n: u64,
    calls: HashMap<String, (String, Value, String)>,
    last_user_text: Option<String>,
    current_turn: Option<String>,
    pub thread_id: Option<String>,
    pub cwd: Option<String>,
    pub originator: Option<String>,
    pub cli_version: Option<String>,
    pub model: Option<String>,
    pub git_branch: Option<String>,
    pub first_ts: Option<String>,
    pub last_ts: Option<String>,
    pub title: Option<String>,
}

fn s(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(|x| x.as_str()).map(|x| x.to_string())
}

fn message_text(content: &Value) -> String {
    match content {
        Value::String(s) => s.clone(),
        Value::Array(items) => items
            .iter()
            .filter_map(|b| s(b, "text"))
            .collect::<Vec<_>>()
            .join("\n"),
        _ => String::new(),
    }
}

fn is_injected_context(text: &str) -> bool {
    let t = text.trim_start();
    t.starts_with("<environment_context>")
        || t.starts_with("<user_instructions>")
        || t.starts_with("# AGENTS.md instructions")
        || t.starts_with("<permissions instructions>")
        || t.starts_with("<developer_instructions>")
}

/// Convert an `apply_patch` envelope into per-file unified diffs.
pub fn apply_patch_to_diffs(patch: &str) -> Vec<(String, &'static str, String)> {
    let mut out: Vec<(String, &'static str, String)> = Vec::new();
    let mut cur: Option<(String, &'static str, String)> = None;
    for line in patch.lines() {
        let header = [
            ("*** Update File: ", "modify"),
            ("*** Add File: ", "add"),
            ("*** Delete File: ", "delete"),
        ]
        .iter()
        .find_map(|(p, k)| {
            line.strip_prefix(p)
                .map(|rest| (rest.trim().to_string(), *k))
        });
        if let Some((path, kind)) = header {
            if let Some(c) = cur.take() {
                out.push(c);
            }
            let head = match kind {
                "add" => format!("--- /dev/null\n+++ b/{path}\n"),
                "delete" => format!("--- a/{path}\n+++ /dev/null\n"),
                _ => format!("--- a/{path}\n+++ b/{path}\n"),
            };
            cur = Some((path, kind, head));
            continue;
        }
        if let Some(rest) = line.strip_prefix("*** Move to: ") {
            if let Some(c) = cur.as_mut() {
                c.1 = "rename";
                c.2.push_str(&format!("rename to {}\n", rest.trim()));
            }
            continue;
        }
        if line.starts_with("*** Begin Patch")
            || line.starts_with("*** End Patch")
            || line.starts_with("*** End of File")
        {
            continue;
        }
        if let Some(c) = cur.as_mut() {
            if line.starts_with("@@") {
                c.2.push_str(if line.trim() == "@@" { "@@ @@" } else { line });
                c.2.push('\n');
            } else {
                c.2.push_str(line);
                c.2.push('\n');
            }
        }
    }
    if let Some(c) = cur.take() {
        out.push(c);
    }
    out
}

impl CodexParser {
    pub fn new() -> Self {
        Self::default()
    }

    fn next_id(&mut self, prefix: &str) -> String {
        self.n += 1;
        format!("{prefix}{}", self.n)
    }

    pub fn feed(&mut self, rec: &Value) -> Vec<EventDraft> {
        let ts = s(rec, "timestamp").unwrap_or_else(crate::util::now_rfc3339);
        if rec.get("timestamp").is_some() {
            if self.first_ts.is_none() {
                self.first_ts = Some(ts.clone());
            }
            self.last_ts = Some(ts.clone());
        }
        let payload = rec.get("payload").cloned().unwrap_or(Value::Null);
        match rec.get("type").and_then(|t| t.as_str()) {
            Some("session_meta") => {
                let meta = payload.get("meta").cloned().unwrap_or(payload.clone());
                self.thread_id = s(&meta, "id").or(self.thread_id.take());
                self.cwd = s(&meta, "cwd").or(self.cwd.take());
                self.originator = s(&meta, "originator").or(self.originator.take());
                self.cli_version = s(&meta, "cli_version").or(self.cli_version.take());
                if let Some(g) = payload.get("git").or(meta.get("git")) {
                    self.git_branch = s(g, "branch").or(self.git_branch.take());
                }
                vec![]
            }
            Some("turn_context") => {
                self.model = s(&payload, "model").or(self.model.take());
                self.cwd = s(&payload, "cwd").or(self.cwd.take());
                vec![]
            }
            Some("response_item") => self.response_item(&payload, &ts),
            Some("event_msg") => self.event_msg(&payload, &ts),
            Some("compacted") => {
                let id = self.next_id("cx-compact-");
                vec![EventDraft::new(
                    id,
                    "system",
                    ts,
                    json!({ "subtype": "compacted", "text": "Conversation compacted" }),
                )]
            }
            _ => vec![],
        }
    }

    fn user_message(&mut self, text: String, ts: &str) -> Vec<EventDraft> {
        let text = text.trim().to_string();
        if text.is_empty() || is_injected_context(&text) {
            return vec![];
        }
        if self.last_user_text.as_deref() == Some(text.as_str()) {
            return vec![];
        }
        if self.title.is_none() {
            self.title = Some(preview(text.lines().next().unwrap_or(""), 120).0);
        }
        self.last_user_text = Some(text.clone());
        let id = self.next_id("cx-u-");
        self.current_turn = Some(id.clone());
        let (p, truncated) = preview(&text, 64 * 1024);
        let mut d = EventDraft::new(
            id.clone(),
            "user_message",
            ts,
            json!({ "text": p, "images": 0, "source": "unknown", "truncated": truncated }),
        );
        d.turn_id = Some(id);
        vec![d]
    }

    fn response_item(&mut self, p: &Value, ts: &str) -> Vec<EventDraft> {
        let turn = self.current_turn.clone();
        let mut out = match p.get("type").and_then(|t| t.as_str()) {
            Some("message") => match p.get("role").and_then(|r| r.as_str()) {
                Some("user") => {
                    return self
                        .user_message(message_text(p.get("content").unwrap_or(&Value::Null)), ts)
                }
                Some("assistant") => {
                    let text = message_text(p.get("content").unwrap_or(&Value::Null));
                    if text.trim().is_empty() {
                        return vec![];
                    }
                    let id = self.next_id("cx-a-");
                    vec![EventDraft::new(
                        id,
                        "assistant_message",
                        ts,
                        json!({ "text": text, "model": self.model }),
                    )]
                }
                _ => vec![],
            },
            Some("reasoning") => {
                let summary: Vec<String> = p
                    .get("summary")
                    .and_then(|v| v.as_array())
                    .map(|a| a.iter().filter_map(|x| s(x, "text")).collect())
                    .unwrap_or_default();
                let content: Vec<String> = p
                    .get("content")
                    .and_then(|v| v.as_array())
                    .map(|a| a.iter().filter_map(|x| s(x, "text")).collect())
                    .unwrap_or_default();
                let (text, is_summary) = if !content.is_empty() {
                    (content.join("\n\n"), false)
                } else {
                    (summary.join("\n\n"), true)
                };
                if text.trim().is_empty() {
                    return vec![];
                }
                let id = self.next_id("cx-r-");
                vec![EventDraft::new(
                    id,
                    "reasoning",
                    ts,
                    json!({ "text": text, "summary": is_summary }),
                )]
            }
            Some("function_call") | Some("custom_tool_call") | Some("local_shell_call") => {
                self.tool_call(p, ts)
            }
            Some("function_call_output") | Some("custom_tool_call_output") => {
                self.tool_output(p, ts)
            }
            Some("web_search_call") => {
                let id = self.next_id("cx-w-");
                let q = p
                    .get("action")
                    .and_then(|a| s(a, "query"))
                    .unwrap_or_else(|| "web search".into());
                vec![EventDraft::new(
                    id,
                    "tool_call",
                    ts,
                    json!({
                        "callId": null, "name": "web_search", "title": q, "category": "web",
                        "input": p.get("action"), "status": s(p, "status").unwrap_or_else(|| "completed".into()),
                    }),
                )]
            }
            _ => vec![],
        };
        for d in &mut out {
            if d.turn_id.is_none() {
                d.turn_id = turn.clone();
            }
        }
        out
    }

    fn tool_call(&mut self, p: &Value, ts: &str) -> Vec<EventDraft> {
        let ty = p.get("type").and_then(|t| t.as_str()).unwrap_or("");
        let call_id = s(p, "call_id")
            .or_else(|| s(p, "id"))
            .unwrap_or_else(|| self.next_id("cx-call-"));
        let (name, input) = match ty {
            "local_shell_call" => (
                "local_shell".to_string(),
                p.get("action").cloned().unwrap_or(Value::Null),
            ),
            "custom_tool_call" => (
                s(p, "name").unwrap_or_default(),
                json!({ "input": p.get("input") }),
            ),
            _ => {
                let args = p.get("arguments").cloned().unwrap_or(Value::Null);
                let parsed = match &args {
                    Value::String(a) => serde_json::from_str(a).unwrap_or(json!({ "raw": a })),
                    other => other.clone(),
                };
                (s(p, "name").unwrap_or_default(), parsed)
            }
        };
        let mut out = Vec::new();
        let event_id = format!("cx-tc-{call_id}");
        let data = json!({
            "callId": call_id, "name": name, "title": tool_title(&name, &input),
            "category": tool_category(&name), "input": input, "status": "running",
        });
        self.calls.insert(
            call_id.clone(),
            (event_id.clone(), data.clone(), ts.to_string()),
        );
        out.push(EventDraft::new(event_id, "tool_call", ts, data));

        if name == "update_plan" {
            if let Some(items) = input.get("plan").and_then(|v| v.as_array()) {
                let items: Vec<Value> = items
                    .iter()
                    .map(|i| json!({ "text": i.get("step"), "status": i.get("status").cloned().unwrap_or(json!("pending")) }))
                    .collect();
                out.push(EventDraft::new(
                    format!("cx-plan-{call_id}"),
                    "plan",
                    ts,
                    json!({ "items": items }),
                ));
            }
        }
        if name == "apply_patch" {
            let patch = input
                .get("input")
                .and_then(|v| v.as_str())
                .or_else(|| input.get("patch").and_then(|v| v.as_str()))
                .unwrap_or("");
            for (i, (path, kind, diff)) in apply_patch_to_diffs(patch).into_iter().enumerate() {
                let (a, d) = diff_stats(&diff);
                let (dp, trunc) = preview(&diff, 256 * 1024);
                out.push(EventDraft::new(
                    format!("cx-fc-{call_id}-{i}"),
                    "file_change",
                    ts,
                    json!({
                        "callId": call_id, "path": path, "change": kind, "diff": dp,
                        "additions": a, "deletions": d, "truncated": trunc,
                    }),
                ));
            }
        }
        out
    }

    fn tool_output(&mut self, p: &Value, ts: &str) -> Vec<EventDraft> {
        let call_id = s(p, "call_id").unwrap_or_default();
        let raw = p.get("output").cloned().unwrap_or(Value::Null);
        // Shell outputs are often a JSON string {output, metadata:{exit_code}}.
        let (text, exit_code, success) = match &raw {
            Value::String(st) => match serde_json::from_str::<Value>(st) {
                Ok(v) if v.get("output").is_some() => (
                    s(&v, "output").unwrap_or_default(),
                    v.get("metadata")
                        .and_then(|m| m.get("exit_code"))
                        .and_then(|c| c.as_i64()),
                    None,
                ),
                _ => (st.clone(), None, None),
            },
            Value::Object(_) => (
                s(&raw, "content").unwrap_or_else(|| raw.to_string()),
                None,
                raw.get("success").and_then(|b| b.as_bool()),
            ),
            other => (other.to_string(), None, None),
        };
        let is_error = success == Some(false) || exit_code.is_some_and(|c| c != 0);
        let mut out = Vec::new();
        if let Some((eid, mut data, cts)) = self.calls.remove(&call_id) {
            data["status"] = json!(if is_error { "failed" } else { "completed" });
            out.push(EventDraft::new(eid, "tool_call", cts, data));
        }
        let (pv, truncated) = preview(&text, PREVIEW_LIMIT);
        let mut d = EventDraft::new(
            format!("cx-res-{call_id}"),
            "tool_result",
            ts,
            json!({
                "callId": call_id, "isError": is_error, "text": pv, "truncated": truncated,
                "bytes": text.len(), "exitCode": exit_code,
            }),
        );
        if truncated {
            d.payload = Some(text);
        }
        out.push(d);
        out
    }

    fn event_msg(&mut self, p: &Value, ts: &str) -> Vec<EventDraft> {
        match p.get("type").and_then(|t| t.as_str()) {
            Some("user_message") => self.user_message(s(p, "message").unwrap_or_default(), ts),
            Some("token_count") => {
                let Some(info) = p.get("info").filter(|i| !i.is_null()) else {
                    return vec![];
                };
                let last = info.get("last_token_usage").cloned().unwrap_or(Value::Null);
                let id = format!("cx-usage-{}", self.current_turn.clone().unwrap_or_default());
                vec![EventDraft::new(
                    id,
                    "usage",
                    ts,
                    json!({
                        "inputTokens": last.get("input_tokens"), "outputTokens": last.get("output_tokens"),
                        "cacheReadTokens": last.get("cached_input_tokens"), "cacheWriteTokens": null,
                        "totalTokens": info.get("total_token_usage").and_then(|t| t.get("total_tokens")),
                    }),
                )]
            }
            Some("turn_aborted") => {
                let id = self.next_id("cx-abort-");
                vec![EventDraft::new(
                    id,
                    "status",
                    ts,
                    json!({ "state": "interrupted", "detail": s(p, "reason") }),
                )]
            }
            Some("error") | Some("stream_error") => {
                let id = self.next_id("cx-err-");
                vec![EventDraft::new(
                    id,
                    "error",
                    ts,
                    json!({ "message": s(p, "message").unwrap_or_default() }),
                )]
            }
            _ => vec![],
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_basic_rollout() {
        let mut p = CodexParser::new();
        let lines = vec![
            json!({"timestamp":"2026-11-14T00:00:00Z","type":"session_meta","payload":{"id":"th1","cwd":"/p","originator":"codex_cli_rs","cli_version":"0.137.0","git":{"branch":"main"}}}),
            json!({"timestamp":"2026-11-14T00:00:00Z","type":"turn_context","payload":{"model":"gpt-x","cwd":"/p"}}),
            json!({"timestamp":"2026-11-14T00:00:01Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"<environment_context>x</environment_context>"}]}}),
            json!({"timestamp":"2026-11-14T00:00:01Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"list files"}]}}),
            json!({"timestamp":"2026-11-14T00:00:01Z","type":"event_msg","payload":{"type":"user_message","message":"list files"}}),
            json!({"timestamp":"2026-11-14T00:00:02Z","type":"response_item","payload":{"type":"function_call","name":"shell","arguments":"{\"command\":[\"ls\"]}","call_id":"c1"}}),
            json!({"timestamp":"2026-11-14T00:00:03Z","type":"response_item","payload":{"type":"function_call_output","call_id":"c1","output":"{\"output\":\"a\\nb\",\"metadata\":{\"exit_code\":0}}"}}),
            json!({"timestamp":"2026-11-14T00:00:04Z","type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Two files."}]}}),
            json!({"timestamp":"2026-11-14T00:00:04Z","type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":5,"output_tokens":2},"total_token_usage":{"total_tokens":7}}}}),
        ];
        let ev: Vec<_> = lines.iter().flat_map(|l| p.feed(l)).collect();
        let kinds: Vec<_> = ev.iter().map(|e| e.kind).collect();
        assert_eq!(
            kinds,
            vec![
                "user_message",
                "tool_call",
                "tool_call",
                "tool_result",
                "assistant_message",
                "usage"
            ]
        );
        assert_eq!(ev[1].data["title"], "ls");
        assert_eq!(ev[2].data["status"], "completed");
        assert_eq!(ev[3].data["text"], "a\nb");
        assert_eq!(ev[3].data["exitCode"], 0);
        assert_eq!(p.thread_id.as_deref(), Some("th1"));
        assert_eq!(p.model.as_deref(), Some("gpt-x"));
        assert_eq!(p.git_branch.as_deref(), Some("main"));
        assert_eq!(p.title.as_deref(), Some("list files"));
    }

    #[test]
    fn apply_patch_diffs() {
        let patch = "*** Begin Patch\n*** Update File: src/a.rs\n@@\n-old\n+new\n*** Add File: b.txt\n+hello\n*** End Patch";
        let d = apply_patch_to_diffs(patch);
        assert_eq!(d.len(), 2);
        assert_eq!(d[0].0, "src/a.rs");
        assert_eq!(d[0].1, "modify");
        assert!(d[0].2.contains("-old\n+new"));
        assert_eq!(d[1].1, "add");
        let mut p = CodexParser::new();
        let ev = p.feed(&json!({"type":"response_item","payload":{"type":"custom_tool_call","name":"apply_patch","call_id":"c9","input":patch}}));
        assert_eq!(ev.iter().filter(|e| e.kind == "file_change").count(), 2);
    }

    #[test]
    fn plan_and_errors() {
        let mut p = CodexParser::new();
        let ev = p.feed(&json!({"type":"response_item","payload":{"type":"function_call","name":"update_plan","call_id":"p","arguments":"{\"plan\":[{\"step\":\"a\",\"status\":\"completed\"}]}"}}));
        assert!(ev.iter().any(|e| e.kind == "plan"));
        let ev = p.feed(&json!({"type":"response_item","payload":{"type":"function_call_output","call_id":"p","output":{"content":"bad","success":false}}}));
        assert_eq!(ev[0].data["status"], "failed");
        assert!(p.feed(&json!({"type":"weird"})).is_empty());
    }
}
