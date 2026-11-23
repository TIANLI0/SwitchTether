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

/// "Rewind files" from the phone (`rewind_files`, needs file checkpoints): built and verified,
/// switched off for now.
pub const REWIND_FILES_ENABLED: bool = false;

#[derive(Debug, Clone)]
pub struct ManagedClaudeOptions {
    pub claude_bin: PathBuf,
    pub cwd: PathBuf,
    pub model: Option<String>,
    pub permission_mode: Option<String>,
    /// Continue from an existing (not live) session's history in a fork.
    pub resume_from: Option<String>,
    /// With `resume_from`: start a fork (new session id) instead of continuing it.
    pub fork: bool,
    /// Extra directories Claude Code may read without asking (`--add-dir`): the folder of files
    /// attached from the phone.
    pub add_dirs: Vec<PathBuf>,
    /// Conversation id for a new conversation (`--session-id`, also for forks): the session is a
    /// normal Claude Code conversation known by its own id from the start, listed by
    /// `claude --resume` and the IDE's history like any other.
    pub session_id: Option<String>,
    /// Effort level (`--effort`: low, medium, high, xhigh, max).
    pub effort: Option<String>,
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
    /// Pid of the spawned process (managed mode). The process also registers itself in Claude
    /// Code's live registry, where it must not show up as a second, observe-only session.
    pid: Option<u32>,
    pending: Mutex<HashMap<String, Pending>>,
    /// request ids already answered → who answered (for 409 details).
    resolved: Mutex<HashMap<String, String>>,
    replies: Mutex<HashMap<String, oneshot::Sender<Value>>>,
    sent_texts: Mutex<HashSet<String>>,
    state: Mutex<State>,
    next_req: std::sync::atomic::AtomicU64,
    stderr_tail: Mutex<Vec<String>>,
    /// Attached mode: lines are injected through the user's own process via
    /// switchtether-shim instead of a child spawned by SwitchTether.
    remote: Option<tokio::sync::mpsc::UnboundedSender<String>>,
    /// Attached mode: shim verdicts for injected approval answers.
    verdicts: Mutex<HashMap<String, oneshot::Sender<bool>>>,
    /// Attached mode: user texts already shown (from the IDE or the phone);
    /// a replayed copy on stdout is skipped.
    echoed: Mutex<HashSet<String>>,
    /// Permission mode as last set (`--permission-mode`, then `set_permission_mode`).
    permission_mode: Mutex<Option<String>>,
    /// `uuid` of the last message sent from the phone (`cancel_async_message` while queued).
    last_uuid: Mutex<Option<String>>,
    pub origin: &'static str,
}

#[derive(Debug, Clone)]
struct State {
    status: &'static str,
    liveness: Liveness,
    native_session_id: Option<String>,
    created_at_ms: i64,
    last_active_at_ms: i64,
    /// Model chosen from the phone during the session (`set_model`); wins over the parsed one
    /// until the agent reports a model again.
    model_override: Option<String>,
    effort: Option<String>,
    /// `models` from the initialize response (what this Claude Code offers), if any.
    models: Value,
}

impl ManagedClaude {
    pub fn native_session_id(&self) -> Option<String> {
        self.state.lock().native_session_id.clone()
    }
    pub fn pid(&self) -> Option<u32> {
        self.pid
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
        self.state
            .lock()
            .model_override
            .clone()
            .or_else(|| self.parser.lock().model.clone())
            .or(self.options.model.clone())
    }
    pub fn effort(&self) -> Option<String> {
        self.state.lock().effort.clone()
    }
    pub fn models(&self) -> Value {
        self.state.lock().models.clone()
    }

    /// Switch the model of the running session the way the official SDK does
    /// (`set_model` control request); `None` returns to the default model.
    pub async fn set_model(&self, model: Option<&str>) -> ApiResult<()> {
        self.control_request(
            json!({ "subtype": "set_model", "model": model }),
            Duration::from_secs(20),
        )
        .await?;
        self.state.lock().model_override = model.map(String::from);
        Ok(())
    }

    /// Change the effort level of the running session (`apply_flag_settings` with
    /// `effortLevel`, as the SDK's `applyFlagSettings`); `None` returns to the default.
    pub async fn set_effort(&self, effort: Option<&str>) -> ApiResult<()> {
        self.control_request(
            json!({ "subtype": "apply_flag_settings", "settings": { "effortLevel": effort } }),
            Duration::from_secs(20),
        )
        .await?;
        self.state.lock().effort = effort.map(String::from);
        Ok(())
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
        if let Some(e) = &options.effort {
            cmd.args(["--effort", e]);
        }
        if let Some(pm) = &options.permission_mode {
            cmd.args(["--permission-mode", pm]);
        }
        if let Some(r) = &options.resume_from {
            // Same conversation by default ("恢复会话"); the hub refuses that while the
            // original process is busy / waiting, so two writers never interleave in one
            // transcript. A fork (new id, same history) is the opt-in alternative.
            cmd.args(["--resume", r]);
            if options.fork {
                cmd.arg("--fork-session");
            }
        }
        if let Some(sid) = options.session_id.as_deref().filter(|_| {
            // `--session-id` names a new conversation: never together with an in-place resume.
            options.resume_from.is_none() || options.fork
        }) {
            cmd.args(["--session-id", sid]);
        }
        for d in options.add_dirs.iter().filter(|d| d.is_dir()) {
            cmd.arg("--add-dir").arg(d);
        }
        // No CLAUDE_CODE_ENTRYPOINT of our own: Claude Code names a stream-json session `sdk-cli`
        // itself (like the official Agent SDK); unknown names are not an official entrypoint.
        cmd.current_dir(&options.cwd)
            .env_remove("CLAUDE_CODE_ENTRYPOINT")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        if REWIND_FILES_ENABLED {
            // File checkpoints, as the Agent SDK's `enableFileCheckpointing`: lets the phone
            // rewind file changes (`rewind_files`). Claude Code's own data; nothing of the
            // user's configuration changes.
            cmd.env("CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING", "1");
        }
        let initial_mode = options.permission_mode.clone();
        #[cfg(windows)]
        {
            cmd.creation_flags(0x0800_0000);
        }
        let mut child = cmd.spawn()?;
        let stdin = child.stdin.take();
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| anyhow::anyhow!("no stdout"))?;
        let stderr = child.stderr.take();
        let pid = child.id();
        let now = crate::util::now_ms();
        // Continuing in place keeps the conversation id, so it is known before the first turn:
        // lists and the live registry match the session at once instead of after the next prompt.
        let native_session_id = options
            .resume_from
            .clone()
            .filter(|_| !options.fork)
            .or_else(|| options.session_id.clone());
        let effort = options.effort.clone();
        let me = Arc::new(Self {
            id,
            options,
            log,
            parser: Mutex::new(ClaudeParser::new()),
            stdin: tokio::sync::Mutex::new(stdin),
            child: Mutex::new(Some(child)),
            pid,
            pending: Mutex::new(HashMap::new()),
            resolved: Mutex::new(HashMap::new()),
            replies: Mutex::new(HashMap::new()),
            sent_texts: Mutex::new(HashSet::new()),
            state: Mutex::new(State {
                status: "idle",
                liveness: Liveness::Live,
                native_session_id,
                created_at_ms: now,
                last_active_at_ms: now,
                model_override: None,
                effort,
                models: Value::Null,
            }),
            next_req: std::sync::atomic::AtomicU64::new(1),
            stderr_tail: Mutex::new(Vec::new()),
            remote: None,
            verdicts: Mutex::new(HashMap::new()),
            echoed: Mutex::new(HashSet::new()),
            permission_mode: Mutex::new(initial_mode),
            last_uuid: Mutex::new(None),
            origin: "managed",
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
            .control_request(
                json!({ "subtype": "initialize", "hooks": null }),
                Duration::from_secs(60),
            )
            .await;
        match init {
            Err(e) => {
                me.kill();
                anyhow::bail!("claude did not initialize: {e}");
            }
            Ok(v) => {
                let models = v
                    .pointer("/response/models")
                    .or_else(|| v.get("models"))
                    .cloned()
                    .unwrap_or(Value::Null);
                me.state.lock().models = models;
            }
        }
        Ok(me)
    }

    /// Attach to a Claude Code process the user started (VS Code extension
    /// with the SwitchTether process wrapper enabled). Nothing is spawned or
    /// initialised: the IDE already owns the session.
    pub fn attached(
        id: String,
        cwd: PathBuf,
        log: Arc<Mutex<SessionLog>>,
        tx: tokio::sync::mpsc::UnboundedSender<String>,
        origin: &'static str,
    ) -> Arc<Self> {
        let now = crate::util::now_ms();
        Arc::new(Self {
            id,
            options: ManagedClaudeOptions {
                claude_bin: PathBuf::new(),
                cwd,
                model: None,
                permission_mode: None,
                resume_from: None,
                fork: false,
                add_dirs: Vec::new(),
                session_id: None,
                effort: None,
            },
            log,
            parser: Mutex::new(ClaudeParser::new()),
            stdin: tokio::sync::Mutex::new(None),
            child: Mutex::new(None),
            pid: None,
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
                model_override: None,
                effort: None,
                models: Value::Null,
            }),
            next_req: std::sync::atomic::AtomicU64::new(1),
            stderr_tail: Mutex::new(Vec::new()),
            remote: Some(tx),
            verdicts: Mutex::new(HashMap::new()),
            echoed: Mutex::new(HashSet::new()),
            permission_mode: Mutex::new(None),
            last_uuid: Mutex::new(None),
            origin,
        })
    }

    pub fn is_attached(&self) -> bool {
        self.remote.is_some()
    }

    /// Frames from switchtether-shim (attached mode).
    pub async fn on_remote_frame(&self, frame: Value) {
        let parse = |f: &Value| {
            f.get("line")
                .and_then(|l| l.as_str())
                .and_then(|l| serde_json::from_str::<Value>(l).ok())
        };
        match frame.get("t").and_then(|t| t.as_str()) {
            Some("out") => {
                if let Some(v) = parse(&frame) {
                    self.on_message(v).await;
                }
            }
            Some("in") => {
                let Some(v) = parse(&frame) else { return };
                if v.get("type").and_then(|t| t.as_str()) == Some("user") {
                    // A prompt typed in the IDE: show it right away.
                    self.echo_user(&v, Some("desktop"));
                    self.set_status("busy");
                    self.publish_status("running", None);
                    return;
                }
                // The IDE answered a permission request itself.
                if v.get("type").and_then(|t| t.as_str()) == Some("control_response") {
                    let rid = v
                        .get("response")
                        .and_then(|r| r.get("request_id"))
                        .and_then(|r| r.as_str())
                        .map(String::from);
                    if let Some(rid) = rid {
                        let removed = self.pending.lock().remove(&rid);
                        if let Some(p) = removed {
                            self.resolved.lock().insert(rid.clone(), "desktop".into());
                            let behavior = v
                                .pointer("/response/response/behavior")
                                .and_then(|b| b.as_str())
                                .unwrap_or("answered")
                                .to_string();
                            self.log.lock().update(&p.event_id, true, |e| {
                                e.data["resolved"] = json!(true);
                                e.data["decision"] = json!(behavior);
                                e.data["by"] = json!("desktop");
                            });
                            if self.pending.lock().is_empty() {
                                self.set_status("busy");
                                self.publish_status("running", None);
                            }
                        }
                    }
                }
            }
            Some(kind @ ("accepted" | "rejected")) => {
                if let Some(rid) = parse(&frame).and_then(|v| {
                    v.pointer("/response/request_id")
                        .and_then(|r| r.as_str())
                        .map(String::from)
                }) {
                    if let Some(tx) = self.verdicts.lock().remove(&rid) {
                        let _ = tx.send(kind == "accepted");
                    }
                }
            }
            Some("exit") => self.on_exit().await,
            _ => {}
        }
    }

    /// Plain text of a stream-json user message (None for tool results).
    fn user_text(v: &Value) -> Option<String> {
        let c = v.pointer("/message/content")?;
        if let Some(t) = c.as_str() {
            return Some(t.trim().to_string());
        }
        let arr = c.as_array()?;
        if arr
            .iter()
            .any(|b| b.get("type").and_then(|t| t.as_str()) == Some("tool_result"))
        {
            return None;
        }
        let t: Vec<&str> = arr
            .iter()
            .filter(|b| b.get("type").and_then(|t| t.as_str()) == Some("text"))
            .filter_map(|b| b.get("text").and_then(|t| t.as_str()))
            .collect();
        (!t.is_empty()).then(|| t.join("\n").trim().to_string())
    }

    /// Attached mode: record a user message that went into the agent.
    fn echo_user(&self, v: &Value, source: Option<&str>) {
        let Some(text) = Self::user_text(v) else {
            return;
        };
        self.echoed.lock().insert(text);
        let mut rec = v.clone();
        rec["uuid"] = json!(format!("in:{}", crate::util::new_id()));
        rec["timestamp"] = json!(crate::util::now_rfc3339());
        let drafts = self.parser.lock().feed(&rec);
        let mut log = self.log.lock();
        for mut d in drafts {
            if let Some(src) = source {
                if d.kind == "user_message" {
                    d.data["source"] = json!(src);
                }
            }
            log.upsert(d, true);
        }
    }

    async fn write_line(&self, v: &Value) -> ApiResult<()> {
        if let Some(tx) = &self.remote {
            return tx
                .send(json!({ "t": "inject", "line": v.to_string() }).to_string())
                .map_err(|_| ApiError::conflict("the attached session has disconnected"));
        }
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
        stdin
            .flush()
            .await
            .map_err(|e| ApiError::internal("claude stdin", e))
    }

    async fn control_request(&self, request: Value, timeout: Duration) -> ApiResult<Value> {
        let rid = format!(
            "st_{}",
            self.next_req
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        );
        let (tx, rx) = oneshot::channel();
        self.replies.lock().insert(rid.clone(), tx);
        self.write_line(
            &json!({ "type": "control_request", "request_id": rid, "request": request }),
        )
        .await?;
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(v)) => {
                if v.get("subtype").and_then(|s| s.as_str()) == Some("error") {
                    Err(ApiError::bad_request(
                        v.get("error")
                            .and_then(|e| e.as_str())
                            .unwrap_or("control request failed"),
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
        self.send_message_with_images(text, &[]).await
    }

    pub async fn send_message_with_images(
        &self,
        text: &str,
        images: &[crate::agents::managed::ImageAttachment],
    ) -> ApiResult<()> {
        if self.liveness() != Liveness::Live {
            return Err(ApiError::conflict("session process has exited"));
        }
        let mut blocks: Vec<Value> = Vec::new();
        if !text.trim().is_empty() {
            blocks.push(json!({ "type": "text", "text": text }));
        }
        for img in images {
            blocks.push(json!({
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": img.media_type,
                    "data": img.data_base64,
                }
            }));
        }
        let mut msg = json!({
            "type": "user",
            "session_id": "",
            "parent_tool_use_id": null,
            "message": { "role": "user", "content": blocks },
        });
        if self.remote.is_none() {
            self.sent_texts.lock().insert(text.trim().to_string());
        }
        // Our own id: a message queued while the agent works can be cancelled by it.
        let uuid = crate::util::new_id();
        msg["uuid"] = json!(uuid);
        *self.last_uuid.lock() = Some(uuid);
        self.write_line(&msg).await?;
        if self.remote.is_some() {
            self.echo_user(&msg, Some("phone"));
        }
        self.set_status("busy");
        self.publish_status("running", None);
        Ok(())
    }

    pub fn permission_mode(&self) -> Option<String> {
        self.permission_mode.lock().clone()
    }

    pub fn last_message_uuid(&self) -> Option<String> {
        self.last_uuid.lock().clone()
    }

    /// One control request of the IDE's session tools (the caller builds and whitelists it);
    /// returns its `response` object. In an attached session (the opt-in takeover hook) it goes
    /// through switchtether-shim like the phone's messages and approvals; the IDE ignores the
    /// reply to a request it did not send (an unmatched `control_response`).
    pub async fn control(&self, request: Value, timeout: Duration) -> ApiResult<Value> {
        if self.liveness() != Liveness::Live {
            return Err(ApiError::conflict("session process has exited"));
        }
        let subtype = request
            .get("subtype")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let v = self.control_request(request.clone(), timeout).await?;
        let response = v.get("response").cloned().unwrap_or(Value::Null);
        match subtype.as_str() {
            "set_permission_mode" => {
                let mode = response
                    .get("mode")
                    .or_else(|| request.get("mode"))
                    .and_then(Value::as_str)
                    .map(String::from);
                *self.permission_mode.lock() = mode;
            }
            "rename_session" => {
                if let Some(t) = request.get("title").and_then(Value::as_str) {
                    self.parser.lock().title = Some(t.to_string());
                }
            }
            _ => {}
        }
        Ok(response)
    }

    /// Stop one background task or sub-agent (`stop_task`, as the SDK's `stopTask` and the IDE's
    /// "Stop agent"), in a process SwitchTether started or an attached one (opt-in hook).
    pub async fn stop_task(&self, task_id: &str) -> ApiResult<()> {
        self.control_request(
            json!({ "subtype": "stop_task", "task_id": task_id }),
            Duration::from_secs(15),
        )
        .await
        .map(|_| ())
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
        self.resolved
            .lock()
            .insert(request_id.to_string(), by.to_string());
        let shown_answers = answers
            .clone()
            .filter(|_| matches!(decision, "allow" | "answer"));
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
            "deny" | "abort" | "cancel" => json!({
                "behavior": "deny",
                "message": message.unwrap_or_else(|| if decision == "cancel" {
                    "Cancelled from SwitchTether on the phone".into()
                } else {
                    "Denied from SwitchTether on the phone".into()
                }),
                "interrupt": decision == "abort",
            }),
            other => {
                // Put it back: invalid decision must not consume the request.
                self.resolved.lock().remove(request_id);
                self.pending.lock().insert(request_id.to_string(), pending);
                return Err(ApiError::validation(format!("unknown decision '{other}'")));
            }
        };
        let verdict = if self.remote.is_some() {
            let (tx, rx) = oneshot::channel();
            self.verdicts.lock().insert(request_id.to_string(), tx);
            Some(rx)
        } else {
            None
        };
        self.write_line(&json!({
            "type": "control_response",
            "response": { "subtype": "success", "request_id": request_id, "response": response },
        }))
        .await?;
        if let Some(rx) = verdict {
            // The IDE may have answered in the same instant: the shim decides.
            if let Ok(Ok(false)) = tokio::time::timeout(std::time::Duration::from_secs(5), rx).await
            {
                self.resolved
                    .lock()
                    .insert(request_id.to_string(), "desktop".into());
                return Err(ApiError::conflict(
                    "this request was already answered on the computer",
                )
                .with_details(json!({ "resolvedBy": "desktop" })));
            }
        }
        let ev = self.log.lock().update(&pending.event_id, true, |e| {
            e.data["resolved"] = json!(true);
            e.data["decision"] = json!(decision);
            e.data["by"] = json!(by);
            if let Some(a) = &shown_answers {
                crate::agents::model::record_answers(&mut e.data, a);
            }
        });
        if self.pending.lock().is_empty() {
            self.set_status("busy");
            self.publish_status("running", None);
        }
        let _ = pending.tool_name;
        Ok(json!({ "requestId": request_id, "decision": decision, "event": ev }))
    }

    pub fn kill(&self) {
        // Never kill a process the user started.
        if self.remote.is_some() {
            return;
        }
        if let Some(mut c) = self.child.lock().take() {
            let _ = c.start_kill();
        }
        let mut st = self.state.lock();
        if st.liveness == Liveness::Live {
            st.liveness = Liveness::Ended;
            st.status = "completed";
            st.last_active_at_ms = crate::util::now_ms();
        }
    }

    fn publish_status(&self, state: &str, detail: Option<&str>) {
        // Background agents / shells keep working after the turn's `result`; their count
        // tells the phone the session is idle but not finished.
        let background = self.parser.lock().background_tasks;
        let d = EventDraft::new(
            format!("status:{}", crate::util::now_ms()),
            "status",
            crate::util::now_rfc3339(),
            json!({ "state": state, "detail": detail, "backgroundTasks": background }),
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
            Some("user")
                if self.remote.is_some()
                    && Self::user_text(&v).is_some_and(|t| self.echoed.lock().remove(&t)) =>
            {
                // Replay of a prompt already shown.
            }
            Some(_) => {
                let ty = v.get("type").and_then(|t| t.as_str()).unwrap_or("");
                if let Some(sid) = v
                    .get("session_id")
                    .and_then(|s| s.as_str())
                    .filter(|s| !s.is_empty())
                {
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
                    "assistant" | "stream_event" if self.status() != "waiting" => {
                        self.set_status("busy");
                    }
                    "system"
                        if v.get("subtype").and_then(|s| s.as_str())
                            == Some("background_tasks_changed")
                            && self.status() == "idle" =>
                    {
                        self.publish_status("idle", None);
                    }
                    _ => {}
                }
            }
            None => {}
        }
    }

    async fn on_control_request(&self, v: Value) {
        let rid = v
            .get("request_id")
            .and_then(|r| r.as_str())
            .unwrap_or("")
            .to_string();
        let req = v.get("request").cloned().unwrap_or(Value::Null);
        match req.get("subtype").and_then(|s| s.as_str()) {
            Some("can_use_tool") => {
                let tool = req
                    .get("tool_name")
                    .and_then(|t| t.as_str())
                    .unwrap_or("")
                    .to_string();
                let input = req.get("input").cloned().unwrap_or(Value::Null);
                let suggestions = req
                    .get("permission_suggestions")
                    .cloned()
                    .unwrap_or(Value::Null);
                let ts = crate::util::now_rfc3339();
                let event_id = format!("req:{rid}");
                let mut draft = if tool == "AskUserQuestion" {
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
                            "allowOther": true,
                            "options": q.get("options").cloned().unwrap_or(json!([])),
                        }))
                        .collect();
                    EventDraft::new(
                        &event_id,
                        "question",
                        ts,
                        json!({
                            "requestId": rid, "questions": questions, "resolved": false,
                        }),
                    )
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
                    EventDraft::new(
                        &event_id,
                        "approval_request",
                        ts,
                        json!({
                            "requestId": rid, "kind": kind, "title": title, "toolName": tool,
                            "detail": req.get("decision_reason").or(req.get("blocked_path")),
                            "command": input.get("command"), "cwd": self.options.cwd.to_string_lossy(),
                            "input": input, "options": options, "resolved": false,
                            "toolUseId": req.get("tool_use_id"),
                        }),
                    )
                };
                if let Some(p) = v
                    .get("parent_tool_use_id")
                    .or_else(|| req.get("parent_tool_use_id"))
                    .and_then(|x| x.as_str())
                {
                    draft.parent_id = Some(p.to_string());
                }
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
            _ if self.remote.is_some() => {
                // Hooks / SDK MCP / elicitation belong to the IDE that owns the process.
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
