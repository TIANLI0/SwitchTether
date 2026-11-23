//! Codex rollout (`~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`) parser →
//! normalized events. Rollout lines are `{timestamp, type, payload}` with
//! `type` ∈ `session_meta`, `response_item`, `event_msg`, `turn_context`,
//! `compacted`. Response items are the canonical conversation; selected
//! `event_msg`s add usage/abort information. Unknown shapes are ignored.

use std::collections::HashMap;

use serde_json::{json, Value};

use crate::agents::model::{
    diff_stats, is_encrypted_blob, preview, tool_category, tool_title, EventDraft, PREVIEW_LIMIT,
};

/// Longest sub-agent answer kept on its row.
const SUBAGENT_RESULT_LIMIT: usize = 4000;

/// A multi-agent v2 child (`spawn_agent {task_name, message}`), known by its agent path
/// (`/root/<task>`). Its row is `cx-sa-<thread id>` so the hub can open the child's rollout.
struct PathAgent {
    event_id: String,
    parent: Option<String>,
    data: Value,
    ts: String,
    turn: Option<String>,
}

#[derive(Default)]
pub struct CodexParser {
    n: u64,
    calls: HashMap<String, (String, Value, String)>,
    last_user_text: Option<String>,
    subagents: HashMap<String, (String, Value)>,
    /// v2 children by agent path.
    path_agents: HashMap<String, PathAgent>,
    /// `spawn_agent` inputs by call id (the call may complete before the child is announced).
    spawn_inputs: HashMap<String, Value>,
    /// Agent path of this thread (`/root` for a top-level thread).
    own_path: Option<String>,
    meta_seen: bool,
    /// A child's rollout starts with a copy of the parent's history (it is a fork); that part
    /// is skipped until the child's own records begin.
    inherited: bool,
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
        let ty = rec.get("type").and_then(|t| t.as_str());
        if self.inherited && ty != Some("session_meta") {
            if self.is_own_start(ty, &payload) {
                self.inherited = false;
            }
            return vec![];
        }
        match ty {
            Some("session_meta") => {
                let meta = payload.get("meta").cloned().unwrap_or(payload.clone());
                let id = s(&meta, "id");
                // A forked child's rollout repeats the parent's `session_meta` after its own.
                if self.meta_seen && id.is_some() && id != self.thread_id {
                    return vec![];
                }
                self.thread_id = id.or(self.thread_id.take());
                self.cwd = s(&meta, "cwd").or(self.cwd.take());
                self.originator = s(&meta, "originator").or(self.originator.take());
                self.cli_version = s(&meta, "cli_version").or(self.cli_version.take());
                if let Some(g) = payload.get("git").or(meta.get("git")) {
                    self.git_branch = s(g, "branch").or(self.git_branch.take());
                }
                if !self.meta_seen {
                    let spawn = meta.pointer("/source/subagent/thread_spawn");
                    self.own_path =
                        s(&meta, "agent_path").or_else(|| spawn.and_then(|t| s(t, "agent_path")));
                    self.inherited = spawn.is_some() && s(&meta, "forked_from_id").is_some();
                }
                self.meta_seen = true;
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

    /// First record that belongs to a forked child itself: Codex applies the child's thread
    /// settings (`thread_settings_applied` with its own id) right after the inherited history.
    /// Only the own id is unambiguous: a grandchild's inherited part contains its parent's
    /// "newly spawned child agent" note and turns.
    fn is_own_start(&self, ty: Option<&str>, p: &Value) -> bool {
        ty == Some("event_msg")
            && p.get("type").and_then(Value::as_str) == Some("thread_settings_applied")
            && s(p, "thread_id").is_some()
            && s(p, "thread_id") == self.thread_id
    }

    fn my_path(&self) -> &str {
        self.own_path.as_deref().unwrap_or("/root")
    }

    /// `event_msg/item_completed` with a `SubAgentActivity` item: a v2 child started, finished,
    /// or changed state. `started` carries the spawning call id and the child's thread id.
    fn subagent_activity(&mut self, item: &Value, ts: &str) -> Vec<EventDraft> {
        let Some(path) = s(item, "agent_path") else {
            return vec![];
        };
        let thread = s(item, "agent_thread_id");
        let kind = s(item, "kind").unwrap_or_default();
        // `interacted` (this thread sent that agent mail, also its own parent) and kinds not
        // known yet change nothing.
        let status = match kind.as_str() {
            "started" | "running" | "resumed" => "running",
            "completed" | "shutdown" | "closed" => "completed",
            "interrupted" | "cancelled" | "canceled" => "interrupted",
            "errored" | "failed" => "failed",
            _ => return vec![],
        };
        if kind == "started" {
            let call = s(item, "id");
            let input = call
                .as_ref()
                .and_then(|c| self.spawn_inputs.get(c))
                .cloned()
                .unwrap_or(Value::Null);
            let parent = call
                .as_ref()
                .filter(|c| self.spawn_inputs.contains_key(*c))
                .map(|c| format!("cx-tc-{c}"));
            self.ensure_path_agent(&path, thread.as_deref(), parent, &input, ts);
            if let Some(a) = self.path_agents.get_mut(&path) {
                if let Some(m) = s(item, "model") {
                    a.data["model"] = json!(m);
                }
                a.data["callId"] = json!(call);
            }
        }
        // Only children this thread started have rows.
        let Some(a) = self.path_agents.get_mut(&path) else {
            return vec![];
        };
        if let Some(t) = thread {
            a.data["agentId"] = json!(t);
        }
        // A finished child stays finished; a later `started` (resume) runs it again.
        a.data["status"] = json!(status);
        if status != "running" {
            a.data["finishedAt"] = json!(ts);
        }
        vec![Self::path_agent_draft(a)]
    }

    fn ensure_path_agent(
        &mut self,
        path: &str,
        thread: Option<&str>,
        parent: Option<String>,
        input: &Value,
        ts: &str,
    ) {
        if let Some(a) = self.path_agents.get_mut(path) {
            if a.parent.is_none() {
                a.parent = parent;
            }
            return;
        }
        let description = s(input, "message")
            .filter(|m| !is_encrypted_blob(m) && !m.trim().is_empty())
            .map(|m| preview(m.trim(), 160).0)
            .or_else(|| s(input, "task_name"))
            .unwrap_or_else(|| path.rsplit('/').next().unwrap_or(path).to_string());
        let event_id = format!("cx-sa-{}", thread.unwrap_or(path));
        self.path_agents.insert(
            path.to_string(),
            PathAgent {
                event_id,
                parent,
                data: json!({
                    "agentId": thread.unwrap_or(path),
                    "agentPath": path,
                    "description": description,
                    "agentType": s(input, "agent_type"),
                    "background": true,
                    "status": "running",
                }),
                ts: ts.to_string(),
                turn: self.current_turn.clone(),
            },
        );
    }

    fn path_agent_draft(a: &PathAgent) -> EventDraft {
        let mut d = EventDraft::new(a.event_id.clone(), "subagent", a.ts.clone(), a.data.clone());
        d.parent_id = a.parent.clone();
        d.turn_id = a.turn.clone();
        d
    }

    /// `response_item/agent_message`: v2 inter-agent mail. Its plain part is a header
    /// (`Message Type: …\nTask name: …\nSender: …\nPayload:\n<text>`); the payload may instead
    /// be encrypted. A child's answer updates its row; a task sent to this thread is shown like
    /// a prompt. Mail this thread sends is skipped (it also writes it as its own message).
    fn agent_message(&mut self, p: &Value, ts: &str) -> Vec<EventDraft> {
        let author = s(p, "author").unwrap_or_default();
        let recipient = s(p, "recipient").unwrap_or_default();
        if recipient != self.my_path() || author == recipient {
            return vec![];
        }
        let plain: String = p
            .get("content")
            .and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .filter(|b| b.get("type").and_then(Value::as_str) != Some("encrypted_content"))
                    .filter_map(|b| s(b, "text"))
                    .collect::<Vec<_>>()
                    .join("\n")
            })
            .unwrap_or_default();
        let (header, payload) = match plain.split_once("Payload:") {
            Some((h, rest)) => (h.to_string(), rest.trim().to_string()),
            None => (String::new(), plain.trim().to_string()),
        };
        let msg_type = header
            .lines()
            .find_map(|l| l.strip_prefix("Message Type:"))
            .map(|t| t.trim().to_string())
            .unwrap_or_default();
        let payload = Some(payload).filter(|t| !t.is_empty() && !is_encrypted_blob(t));
        if let Some(a) = self.path_agents.get_mut(&author) {
            match msg_type.as_str() {
                "FINAL_ANSWER" => {
                    // Codex reports an ending that is not a success as a final answer too.
                    let p = payload.as_deref().unwrap_or("");
                    let status =
                        if p.starts_with("Agent errored") || p.starts_with("Agent was not found") {
                            "failed"
                        } else if p.starts_with("Agent interrupted") {
                            "interrupted"
                        } else {
                            "completed"
                        };
                    a.data["status"] = json!(status);
                    a.data["finishedAt"] = json!(ts);
                    if let Some(t) = &payload {
                        let (r, truncated) = preview(t, SUBAGENT_RESULT_LIMIT);
                        a.data["result"] = json!(r);
                        a.data["resultTruncated"] = json!(truncated);
                        a.data["summary"] = json!(preview(t.lines().next().unwrap_or(""), 300).0);
                    }
                }
                _ => {
                    if let Some(t) = &payload {
                        a.data["summary"] = json!(preview(t.lines().next().unwrap_or(""), 300).0);
                    }
                }
            }
            return vec![Self::path_agent_draft(a)];
        }
        // A task (or follow-up) for this thread from its parent.
        match payload {
            Some(t) => {
                let mut out = self.user_message(t, ts);
                for d in &mut out {
                    d.data["source"] = json!("agent");
                    d.data["from"] = json!(author);
                }
                out
            }
            None => {
                let id = self.next_id("cx-mail-");
                vec![EventDraft::new(
                    id,
                    "system",
                    ts,
                    json!({ "subtype": "agent_message", "text": format!("Task from {author}") }),
                )]
            }
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
            Some("agent_message") => self.agent_message(p, ts),
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

        if name == "spawn_agent" {
            self.spawn_inputs.insert(call_id.clone(), input.clone());
        }
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
            let result = match &raw {
                Value::String(text) => serde_json::from_str::<Value>(text).unwrap_or(Value::Null),
                other => other.clone(),
            };
            if data["name"] == "spawn_agent" {
                // Multi-agent v2 answers with the child's path only; the child's row comes from
                // its `SubAgentActivity` (written before this output), created here if missing.
                if let Some(path) =
                    s(&result, "task_name").filter(|_| result.get("agent_id").is_none())
                {
                    if !self.path_agents.contains_key(&path) && !is_error {
                        let input = self
                            .spawn_inputs
                            .get(&call_id)
                            .cloned()
                            .unwrap_or(Value::Null);
                        self.ensure_path_agent(&path, None, Some(eid.clone()), &input, ts);
                        if let Some(a) = self.path_agents.get_mut(&path) {
                            a.data["callId"] = json!(call_id);
                            out.push(Self::path_agent_draft(a));
                        }
                    }
                }
                if let Some(id) = s(&result, "agent_id") {
                    let sa = json!({ "agentId": id, "agentType": data.pointer("/input/agent_type"),
                        "description": data.pointer("/input/message").and_then(Value::as_str).map(|t| preview(t, 160).0), "status": "running" });
                    self.subagents.insert(id.clone(), (eid.clone(), sa.clone()));
                    let mut d = EventDraft::new(format!("cx-sa-{id}"), "subagent", ts, sa);
                    d.parent_id = Some(eid.clone());
                    out.push(d);
                }
            }
            if let Some(states) = result.get("status").and_then(Value::as_object) {
                for (id, status) in states {
                    if let Some((parent, sa)) = self.subagents.get_mut(id) {
                        sa["status"] = json!(if status.get("completed").is_some() {
                            "completed"
                        } else if status.get("errored").is_some() || status == "not_found" {
                            "failed"
                        } else if status == "shutdown" {
                            "completed"
                        } else {
                            "running"
                        });
                        let mut d =
                            EventDraft::new(format!("cx-sa-{id}"), "subagent", ts, sa.clone());
                        d.parent_id = Some(parent.clone());
                        out.push(d);
                    }
                }
            }
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
            Some("item_completed")
                if p.pointer("/item/type").and_then(Value::as_str) == Some("SubAgentActivity") =>
            {
                let item = p.get("item").cloned().unwrap_or(Value::Null);
                self.subagent_activity(&item, ts)
            }
            // Legacy history mode writes started / interacted / interrupted this way (only
            // `completed` as an item); the spawn is then found by its task name.
            Some("sub_agent_activity") => {
                let mut item = p.clone();
                if s(&item, "kind").as_deref() == Some("started") {
                    let path = s(&item, "agent_path").unwrap_or_default();
                    let name = path.rsplit('/').next().unwrap_or("").to_string();
                    let call = self
                        .spawn_inputs
                        .iter()
                        .find(|(_, i)| s(i, "task_name").as_deref() == Some(name.as_str()))
                        .map(|(c, _)| c.clone());
                    item["id"] = json!(call);
                }
                self.subagent_activity(&item, ts)
            }
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
    #[test]
    fn spawn_and_wait_preserve_the_subagent_group() {
        let mut p = CodexParser::new();
        p.feed(&json!({"type":"response_item","payload":{"type":"function_call","name":"spawn_agent","call_id":"spawn","arguments":"{\"message\":\"Review login\",\"agent_type\":\"explorer\"}"}}));
        let events = p.feed(&json!({"type":"response_item","payload":{"type":"function_call_output","call_id":"spawn","output":"{\"agent_id\":\"child\"}"}}));
        let child = events.iter().find(|e| e.kind == "subagent").unwrap();
        assert_eq!(child.parent_id.as_deref(), Some("cx-tc-spawn"));
        assert_eq!(child.data["description"], "Review login");
        p.feed(&json!({"type":"response_item","payload":{"type":"function_call","name":"wait","call_id":"wait","arguments":"{}"}}));
        let events = p.feed(&json!({"type":"response_item","payload":{"type":"function_call_output","call_id":"wait","output":{"status":{"child":{"completed":"done"}}}}}));
        let child = events.iter().find(|e| e.kind == "subagent").unwrap();
        assert_eq!(child.parent_id.as_deref(), Some("cx-tc-spawn"));
        assert_eq!(child.data["status"], "completed");
    }

    const ENC: &str = "gAAAAABrAGpqUv7yFlFVw8nHPlAfcPGKAEpCvqOnIk8ELpTTGqTtlMIrcFDsQQx0hoAv65Yy";

    fn mail(author: &str, recipient: &str, kind: &str, payload: Option<&str>) -> Value {
        let mut content = vec![json!({"type":"input_text","text":
            format!("Message Type: {kind}\nTask name: {recipient}\nSender: {author}\nPayload:\n{}", payload.unwrap_or(""))})];
        if payload.is_none() {
            content.push(json!({"type":"encrypted_content","encrypted_content":ENC}));
        }
        json!({"type":"response_item","payload":{"type":"agent_message","author":author,"recipient":recipient,"content":content}})
    }

    /// Multi-agent v2 as written by Codex 0.137 (Desktop): `spawn_agent {task_name, message}`
    /// answers with the path only; `SubAgentActivity` items carry the child's thread id.
    #[test]
    fn multi_agent_v2_children_in_the_parent_rollout() {
        let mut p = CodexParser::new();
        p.feed(&json!({"type":"session_meta","payload":{"id":"root-th","source":"vscode","agent_path":null}}));
        p.feed(
            &json!({"type":"event_msg","payload":{"type":"user_message","message":"analyse it"}}),
        );
        let call = p.feed(&json!({"type":"response_item","payload":{"type":"function_call","name":"spawn_agent","namespace":"collaboration",
            "call_id":"c1","arguments":format!("{{\"task_name\":\"evidence\",\"message\":\"{ENC}\"}}")}}));
        assert_eq!(
            call[0].data["title"], "evidence",
            "an encrypted message is never the title"
        );
        let started = p.feed(&json!({"type":"event_msg","payload":{"type":"item_completed","thread_id":"root-th",
            "item":{"type":"SubAgentActivity","model":"gpt-x","id":"c1","kind":"started","agent_thread_id":"child-th","agent_path":"/root/evidence"}}}));
        assert_eq!(started.len(), 1);
        let row = &started[0];
        assert_eq!(row.id, "cx-sa-child-th");
        assert_eq!(row.kind, "subagent");
        assert_eq!(row.parent_id.as_deref(), Some("cx-tc-c1"));
        assert_eq!(row.data["agentId"], "child-th");
        assert_eq!(row.data["description"], "evidence");
        assert_eq!(row.data["status"], "running");
        assert_eq!(row.data["model"], "gpt-x");
        assert_eq!(row.turn_id.as_deref(), Some("cx-u-1"));

        // The output names the path only and adds no second row.
        let out = p.feed(&json!({"type":"response_item","payload":{"type":"function_call_output","call_id":"c1","output":"{\"task_name\":\"/root/evidence\"}"}}));
        assert!(out.iter().all(|e| e.kind != "subagent"));
        assert_eq!(
            out.iter().find(|e| e.kind == "tool_call").unwrap().data["status"],
            "completed"
        );

        // Progress mail with an encrypted payload changes nothing visible.
        let m = p.feed(&mail("/root/evidence", "/root", "MESSAGE", None));
        assert!(m[0].data.get("summary").is_none());
        p.feed(&json!({"type":"event_msg","payload":{"type":"user_message","message":"next question"}}));
        let done = p.feed(&json!({"type":"event_msg","payload":{"type":"item_completed",
            "item":{"type":"SubAgentActivity","id":"subagent-completed-x","kind":"completed","agent_thread_id":"child-th","agent_path":"/root/evidence"}}}));
        assert_eq!(done[0].id, "cx-sa-child-th");
        assert_eq!(done[0].data["status"], "completed");
        assert_eq!(
            done[0].turn_id.as_deref(),
            Some("cx-u-1"),
            "stays in the turn that spawned it"
        );
        let ans = p.feed(&mail(
            "/root/evidence",
            "/root",
            "FINAL_ANSWER",
            Some("Found three facts.\nMore detail."),
        ));
        assert_eq!(ans.len(), 1);
        assert_eq!(ans[0].id, "cx-sa-child-th");
        assert_eq!(ans[0].data["result"], "Found three facts.\nMore detail.");
        assert_eq!(ans[0].data["summary"], "Found three facts.");
        assert_eq!(ans[0].parent_id.as_deref(), Some("cx-tc-c1"));
        // Mail this thread sends is not shown again.
        assert!(p
            .feed(&mail("/root", "/root/evidence", "MESSAGE", Some("hi")))
            .is_empty());
    }

    /// Legacy history mode (`event_msg/sub_agent_activity`) and an error reported as a final answer.
    #[test]
    fn legacy_activity_and_errored_final_answer() {
        let mut p = CodexParser::new();
        p.feed(&json!({"type":"session_meta","payload":{"id":"root-th"}}));
        p.feed(
            &json!({"type":"response_item","payload":{"type":"function_call","name":"spawn_agent",
            "call_id":"c7","arguments":"{\"task_name\":\"lint\",\"message\":\"Run the linter\"}"}}),
        );
        let st = p.feed(
            &json!({"type":"event_msg","payload":{"type":"sub_agent_activity","event_id":"e1",
            "kind":"started","agent_thread_id":"th-l","agent_path":"/root/lint"}}),
        );
        assert_eq!(st[0].id, "cx-sa-th-l");
        assert_eq!(st[0].parent_id.as_deref(), Some("cx-tc-c7"));
        assert_eq!(st[0].data["description"], "Run the linter");
        let end = p.feed(&mail(
            "/root/lint",
            "/root",
            "FINAL_ANSWER",
            Some("Agent errored: tool crashed"),
        ));
        assert_eq!(end[0].data["status"], "failed");
    }

    /// A v2 child's rollout is a fork: its own `session_meta`, then the parent's, then the
    /// parent's history, then the child's records from `thread_settings_applied` on.
    #[test]
    fn child_rollout_skips_the_inherited_parent_history() {
        let mut p = CodexParser::new();
        let lines = vec![
            json!({"type":"session_meta","payload":{"id":"child-th","forked_from_id":"root-th","agent_path":"/root/evidence",
                "source":{"subagent":{"thread_spawn":{"parent_thread_id":"root-th","depth":1,"agent_path":"/root/evidence"}}}}}),
            json!({"type":"session_meta","payload":{"id":"root-th","source":"vscode","cwd":"/elsewhere"}}),
            json!({"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"parent prompt"}]}}),
            json!({"type":"response_item","payload":{"type":"function_call","name":"exec","call_id":"old","arguments":"{}"}}),
            json!({"type":"event_msg","payload":{"type":"thread_settings_applied","thread_id":"root-th"}}),
            json!({"type":"event_msg","payload":{"type":"turn_aborted","reason":"interrupted"}}),
            json!({"type":"response_item","payload":{"type":"message","role":"developer","content":[{"type":"input_text","text":"You are now a newly spawned child agent."}]}}),
            json!({"type":"event_msg","payload":{"type":"thread_settings_applied","thread_id":"child-th"}}),
            mail(
                "/root",
                "/root/evidence",
                "NEW_TASK",
                Some("Collect the evidence"),
            ),
            json!({"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"On it."}]}}),
            // The child mailed its parent: not a sub-agent of this thread.
            json!({"type":"event_msg","payload":{"type":"item_completed","item":{"type":"SubAgentActivity","id":"c9",
                "kind":"interacted","agent_thread_id":"root-th","agent_path":"/root"}}}),
        ];
        let ev: Vec<_> = lines.iter().flat_map(|l| p.feed(l)).collect();
        let kinds: Vec<_> = ev.iter().map(|e| e.kind).collect();
        assert_eq!(kinds, vec!["user_message", "assistant_message"]);
        assert_eq!(ev[0].data["text"], "Collect the evidence");
        assert_eq!(ev[0].data["from"], "/root");
        assert_eq!(p.thread_id.as_deref(), Some("child-th"));
        assert_eq!(p.title.as_deref(), Some("Collect the evidence"));

        // A child without its own text (encrypted task) gets a short note instead.
        let mut q = CodexParser::new();
        q.feed(&json!({"type":"session_meta","payload":{"id":"c2","agent_path":"/root/x"}}));
        let n = q.feed(&mail("/root", "/root/x", "NEW_TASK", None));
        assert_eq!(n[0].kind, "system");
    }
}
