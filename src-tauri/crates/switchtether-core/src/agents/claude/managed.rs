//! SwitchTether-managed Claude Code sessions.
//!
//! These are NEW processes started by SwitchTether on explicit user request
//! (never presented as the user's existing session). They use the same
//! mechanism as the official VS Code extension and the Agent SDK:
//! `--input-format stream-json --output-format stream-json
//! --permission-prompt-tool stdio`, with `--setting-sources user,project,local`
//! so the user's plugins, skills, MCP servers and hooks load exactly as in a
//! normal session. Nothing in the user's configuration is modified.
//!
//! Control protocol (stdin/stdout JSON lines):
//! - host → CLI: `{type:"user", message}`; `{type:"control_request", request:{subtype:"initialize"|"interrupt"|…}}`
//! - CLI → host: `control_request` with `subtype:"can_use_tool"` (permission
//!   prompts and AskUserQuestion), answered with `control_response`;
//!   `control_cancel_request`; regular transcript-shaped messages.

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, Command};
use tokio::sync::oneshot;

use super::transcript::ClaudeParser;
use crate::agents::model::{EventDraft, Liveness};
use crate::agents::store::SessionLog;
use crate::error::{ApiError, ApiResult};

#[derive(Debug, Clone)]
pub struct ManagedClaudeOptions {
    pub claude_bin: PathBuf,
    pub cwd: PathBuf,
    pub model: Option<String>,
    pub permission_mode: Option<String>,
    /// Continue from an existing (not live) session's history in a fork.
    pub resume_from: Option<String>,
}

struct Pending {
    tool_name: String,
    input: Value,
    suggestions: Value,
    event_id: String,
}

pub struct ManagedClaude {
    pub id: String,
    pub options: ManagedClaudeOptions,
    pub log: Arc<Mutex<SessionLog>>,
    parser: Mutex<ClaudeParser>,
    stdin: tokio::sync::Mutex<Option<ChildStdin>>,
    child: Mutex<Option<Child>>,
    pending: Mutex<HashMap<String, Pending>>,
    /// request ids already answered → who answered (for 409 details).
    resolved: Mutex<HashMap<String, String>>,
    replies: Mutex<HashMap<String, oneshot::Sender<Value>>>,
    sent_texts: Mutex<HashSet<String>>,
    state: Mutex<State>,
    next_req: std::sync::atomic::AtomicU64,
    stderr_tail: Mutex<Vec<String>>,
}

#[derive(Debug, Clone)]
struct State {
    status: &'static str,
    liveness: Liveness,
    native_session_id: Option<String>,
    created_at_ms: i64,
    last_active_at_ms: i64,
}

impl ManagedClaude {
    pub fn native_session_id(&self) -> Option<String> {
        self.state.lock().native_session_id.clone()
    }
    pub fn status(&self) -> &'static str {
        self.state.lock().status
    }
    pub fn liveness(&self) -> Liveness {
        self.state.lock().liveness
    }
    pub fn created_at_ms(&self) -> i64 {
        self.state.lock().created_at_ms
    }
    pub fn last_active_at_ms(&self) -> i64 {
        self.state.lock().last_active_at_ms
    }
    pub fn model(&self) -> Option<String> {
        self.parser.lock().model.clone().or(self.options.model.clone())
    }
    pub fn title(&self) -> Option<String> {
        self.parser.lock().title.clone()
    }
    pub fn pending_request_ids(&self) -> Vec<String> {
        self.pending.lock().keys().cloned().collect()
    }
    pub fn stderr_tail(&self) -> Vec<String> {
        self.stderr_tail.lock().clone()
    }

    fn set_status(&self, status: &'static str) {
        let mut st = self.state.lock();
        st.status = status;
        st.last_active_at_ms = crate::util::now_ms();
    }

    pub async fn spawn(
        id: String,
        options: ManagedClaudeOptions,
        log: Arc<Mutex<SessionLog>>,
    ) -> anyhow::Result<Arc<Self>> {
        let mut cmd = Command::new(&options.claude_bin);
        cmd.args([
            "--output-format",
            "stream-json",
            "--verbose",
            "--input-format",
            "stream-json",
            "--permission-prompt-tool",
            "stdio",
            "--setting-sources",
            "user,project,local",
            "--include-partial-messages",
            "--replay-user-messages",
        ]);
        if let Some(m) = &options.model {
            cmd.args(["--model", m]);
        }
        if let Some(pm) = &options.permission_mode {
            cmd.args(["--permission-mode", pm]);
        }
        if let Some(r) = &options.resume_from {
            // Always fork: resuming a session in place while another process
            // might hold it would interleave two writers in one transcript.
            cmd.args(["--resume", r, "--fork-session"]);
        }
        cmd.current_dir(&options.cwd)
            .env("CLAUDE_CODE_ENTRYPOINT", "sdk-switchtether")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        #[cfg(windows)]
        {
            cmd.creation_flags(0x0800_0000);
        }
        let mut child = cmd.spawn()?;
        let stdin = child.stdin.take();
        let stdout = child.stdout.take().ok_or_else(|| anyhow::anyhow!("no stdout"))?;
        let stderr = child.stderr.take();
        let now = crate::util::now_ms();
        let me = Arc::new(Self {
            id,
            options,
            log,
            parser: Mutex::new(ClaudeParser::new()),
            stdin: tokio::sync::Mutex::new(stdin),
            child: Mutex::new(Some(child)),
            pending: Mutex::new(HashMap::new()),
            resolved: Mutex::new(HashMap::new()),
            replies: Mutex::new(HashMap::new()),
            sent_texts: Mutex::new(HashSet::new()),
            state: Mutex::new(State {
                status: "idle",
                liveness: Liveness::Live,
                native_session_id: None,
                created_at_ms: now,
                last_active_at_ms: now,
            }),
            next_req: std::sync::atomic::AtomicU64::new(1),
            stderr_tail: Mutex::new(Vec::new()),
        });

        let reader = me.clone();
        tokio::spawn(async move {
            let mut lines = BufReader::new(stdout).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if let Ok(v) = serde_json::from_str::<Value>(&line) {
                    reader.on_message(v).await;
                }
            }
            reader.on_exit().await;
        });
        if let Some(stderr) = stderr {
            let tail = me.clone();
            tokio::spawn(async move {
                let mut lines = BufReader::new(stderr).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    let mut t = tail.stderr_tail.lock();
                    t.push(crate::redact::redact_text(&line));
                    if t.len() > 50 {
                        t.remove(0);
                    }
                }
            });
        }

        // Initialize the control protocol (no hooks / SDK MCP servers registered).
        let init = me
            .control_request(json!({ "subtype": "initialize", "hooks": null }), Duration::from_secs(60))
            .await;
        if let Err(e) = init {
            me.kill();
            anyhow::bail!("claude did not initialize: {e}");
        }
        Ok(me)
    }

    async fn write_line(&self, v: &Value) -> ApiResult<()> {
        let mut guard = self.stdin.lock().await;
        let Some(stdin) = guard.as_mut() else {
            return Err(ApiError::conflict("session process has exited"));
        };
        let mut s = v.to_string();
        s.push('\n');
        stdin
            .write_all(s.as_bytes())
            .await
            .map_err(|e| ApiError::internal("claude stdin", e))?;
        stdin.flush().await.map_err(|e| ApiError::internal("claude stdin", e))
    }

    async fn control_request(&self, request: Value, timeout: Duration) -> ApiResult<Value> {
        let rid = format!(
            "st_{}",
            self.next_req.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        );
        let (tx, rx) = oneshot::channel();
        self.replies.lock().insert(rid.clone(), tx);
        self.write_line(&json!({ "type": "control_request", "request_id": rid, "request": request }))
            .await?;
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(v)) => {
                if v.get("subtype").and_then(|s| s.as_str()) == Some("error") {
                    Err(ApiError::bad_request(
                        v.get("error").and_then(|e| e.as_str()).unwrap_or("control request failed"),
                    ))
                } else {
                    Ok(v)
                }
            }
            _ => {
                self.replies.lock().remove(&rid);
                Err(ApiError::internal("claude control", "timed out"))
            }
        }
    }

    pub async fn send_message(&self, text: &str) -> ApiResult<()> {
        if self.liveness() != Liveness::Live {
            return Err(ApiError::conflict("session process has exited"));
        }
        self.sent_texts.lock().insert(text.trim().to_string());
        self.write_line(&json!({
            "type": "user",
            "session_id": "",
            "parent_tool_use_id": null,
            "message": { "role": "user", "content": [{ "type": "text", "text": text }] },
        }))
        .await?;
        self.set_status("busy");
        self.publish_status("running", None);
        Ok(())
    }

    pub async fn interrupt(&self) -> ApiResult<()> {
        self.control_request(json!({ "subtype": "interrupt" }), Duration::from_secs(15))
            .await
            .map(|_| ())
    }

    /// Answer a pending permission request / question. First answer wins;
    /// later answers get 409 with the resolver.
    pub async fn respond(
        &self,
        request_id: &str,
        decision: &str,
        message: Option<String>,
        updated_input: Option<Value>,
        answers: Option<Value>,
        by: &str,
    ) -> ApiResult<Value> {
        let pending = {
            let mut p = self.pending.lock();
            match p.remove(request_id) {
                Some(x) => x,
                None => {
                    let by = self.resolved.lock().get(request_id).cloned();
                    return Err(match by {
                        Some(b) => ApiError::conflict("this request was already answered")
                            .with_details(json!({ "resolvedBy": b })),
                        None => ApiError::not_found("no such pending request"),
                    });
                }
            }
        };
        self.resolved.lock().insert(request_id.to_string(), by.to_string());
        let response = match decision {
            "allow" | "allow_session" | "answer" => {
                let mut input = updated_input.unwrap_or_else(|| pending.input.clone());
                if let Some(a) = answers {
                    input["answers"] = a;
                }
                let mut r = json!({ "behavior": "allow", "updatedInput": input });
                if decision == "allow_session" && pending.suggestions.is_array() {
                    r["updatedPermissions"] = pending.suggestions.clone();
                }
                r
            }
            "deny" | "abort" => json!({
                "behavior": "deny",
                "message": message.unwrap_or_else(|| "Denied from SwitchTether on the phone".into()),
                "interrupt": decision == "abort",
            }),
            other => {
                // Put it back: invalid decision must not consume the request.
                self.resolved.lock().remove(request_id);
                self.pending.lock().insert(request_id.to_string(), pending);
                return Err(ApiError::validation(format!("unknown decision '{other}'")));
            }
        };
        self.write_line(&json!({
            "type": "control_response",
            "response": { "subtype": "success", "request_id": request_id, "response": response },
        }))
        .await?;
        let ev = self.log.lock().update(&pending.event_id, true, |e| {
            e.data["resolved"] = json!(true);
            e.data["decision"] = json!(decision);
            e.data["by"] = json!(by);
        });
        if self.pending.lock().is_empty() {
            self.set_status("busy");
            self.publish_status("running", None);
        }
        let _ = pending.tool_name;
        Ok(json!({ "requestId": request_id, "decision": decision, "event": ev }))
    }

    pub fn kill(&self) {
        if let Some(mut c) = self.child.lock().take() {
            let _ = c.start_kill();
        }
    }

    fn publish_status(&self, state: &str, detail: Option<&str>) {
        let d = EventDraft::new(
            format!("status:{}", crate::util::now_ms()),
            "status",
            crate::util::now_rfc3339(),
            json!({ "state": state, "detail": detail }),
        );
        // Status changes replace one rolling status event to keep the log small.
        let mut d = d;
        d.id = "status:current".into();
        self.log.lock().upsert(d, true);
    }

    async fn on_message(&self, v: Value) {
        match v.get("type").and_then(|t| t.as_str()) {
            Some("control_response") => {
                let resp = v.get("response").cloned().unwrap_or(Value::Null);
                if let Some(rid) = resp.get("request_id").and_then(|r| r.as_str()) {
                    if let Some(tx) = self.replies.lock().remove(rid) {
                        let _ = tx.send(resp);
                    }
                }
            }
            Some("control_request") => self.on_control_request(v).await,
            Some("control_cancel_request") => {
                if let Some(rid) = v.get("request_id").and_then(|r| r.as_str()) {
                    if let Some(p) = self.pending.lock().remove(rid) {
                        self.resolved.lock().insert(rid.to_string(), "agent".into());
                        self.log.lock().update(&p.event_id, true, |e| {
                            e.data["resolved"] = json!(true);
                            e.data["decision"] = json!("cancelled");
                            e.data["by"] = json!("agent");
                        });
                    }
                }
            }
            Some(_) => {
                let ty = v.get("type").and_then(|t| t.as_str()).unwrap_or("");
                if let Some(sid) = v.get("session_id").and_then(|s| s.as_str()).filter(|s| !s.is_empty()) {
                    self.state.lock().native_session_id = Some(sid.to_string());
                }
                let drafts = self.parser.lock().feed(&v);
                let mut log = self.log.lock();
                for mut d in drafts {
                    if d.kind == "user_message" {
                        let text = d.data["text"].as_str().unwrap_or("").trim().to_string();
                        if self.sent_texts.lock().remove(&text) {
                            d.data["source"] = json!("phone");
                        }
                    }
                    log.upsert(d, true);
                }
                drop(log);
                match ty {
                    "result" => {
                        self.set_status("idle");
                        self.publish_status("idle", None);
                    }
                    "assistant" | "stream_event" => {
                        if self.status() != "waiting" {
                            self.set_status("busy");
                        }
                    }
                    _ => {}
                }
            }
            None => {}
        }
    }

    async fn on_control_request(&self, v: Value) {
        let rid = v.get("request_id").and_then(|r| r.as_str()).unwrap_or("").to_string();
        let req = v.get("request").cloned().unwrap_or(Value::Null);
        match req.get("subtype").and_then(|s| s.as_str()) {
            Some("can_use_tool") => {
                let tool = req.get("tool_name").and_then(|t| t.as_str()).unwrap_or("").to_string();
                let input = req.get("input").cloned().unwrap_or(Value::Null);
                let suggestions = req.get("permission_suggestions").cloned().unwrap_or(Value::Null);
                let ts = crate::util::now_rfc3339();
                let event_id = format!("req:{rid}");
                let draft = if tool == "AskUserQuestion" {
                    let questions: Vec<Value> = input
                        .get("questions")
                        .and_then(|q| q.as_array())
                        .cloned()
                        .unwrap_or_default()
                        .into_iter()
                        .enumerate()
                        .map(|(i, q)| json!({
                            "id": q.get("question").cloned().unwrap_or(json!(i.to_string())),
                            "header": q.get("header"), "question": q.get("question"),
                            "multiSelect": q.get("multiSelect").cloned().unwrap_or(json!(false)),
                            "options": q.get("options").cloned().unwrap_or(json!([])),
                        }))
                        .collect();
                    EventDraft::new(&event_id, "question", ts, json!({
                        "requestId": rid, "questions": questions, "resolved": false,
                    }))
                } else {
                    let category = crate::agents::model::tool_category(&tool);
                    let kind = match category {
                        "shell" => "command",
                        "edit" | "write" => "file_change",
                        "web" => "network",
                        _ => "tool",
                    };
                    let title = crate::agents::model::tool_title(&tool, &input);
                    let mut options = vec![json!({ "id": "allow", "label": "Allow" })];
                    if suggestions.as_array().is_some_and(|a| !a.is_empty()) {
                        options.push(json!({ "id": "allow_session", "label": "Always allow" }));
                    }
                    options.push(json!({ "id": "deny", "label": "Deny" }));
                    EventDraft::new(&event_id, "approval_request", ts, json!({
                        "requestId": rid, "kind": kind, "title": title, "toolName": tool,
                        "detail": req.get("decision_reason").or(req.get("blocked_path")),
                        "command": input.get("command"), "cwd": self.options.cwd.to_string_lossy(),
                        "input": input, "options": options, "resolved": false,
                        "toolUseId": req.get("tool_use_id"),
                    }))
                };
                self.pending.lock().insert(
                    rid,
                    Pending {
                        tool_name: tool,
                        input,
                        suggestions,
                        event_id,
                    },
                );
                self.log.lock().upsert(draft, true);
                self.set_status("waiting");
                self.publish_status("waiting_approval", None);
            }
            _ => {
                // Unsupported host callbacks (hooks/SDK MCP/elicitation) — we
                // registered none, so decline explicitly instead of hanging.
                let _ = self
                    .write_line(&json!({
                        "type": "control_response",
                        "response": { "subtype": "error", "request_id": rid, "error": "not supported by SwitchTether" },
                    }))
                    .await;
            }
        }
    }

    async fn on_exit(&self) {
        *self.stdin.lock().await = None;
        let code = self
            .child
            .lock()
            .as_mut()
            .and_then(|c| c.try_wait().ok().flatten())
            .and_then(|s| s.code());
        {
            let mut st = self.state.lock();
            st.liveness = Liveness::Ended;
            st.status = "completed";
        }
        let pending: Vec<(String, Pending)> = self.pending.lock().drain().collect();
        for (rid, p) in pending {
            self.resolved.lock().insert(rid, "agent".into());
            self.log.lock().update(&p.event_id, true, |e| {
                e.data["resolved"] = json!(true);
                e.data["decision"] = json!("cancelled");
                e.data["by"] = json!("agent");
            });
        }
        for (_, tx) in self.replies.lock().drain() {
            let _ = tx.send(json!({ "subtype": "error", "error": "process exited" }));
        }
        self.publish_status(
            "completed",
            Some(&match code {
                Some(c) => format!("process exited ({c})"),
                None => "process exited".into(),
            }),
        );
    }
}
