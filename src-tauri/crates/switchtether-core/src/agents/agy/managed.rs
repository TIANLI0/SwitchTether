//! SwitchTether-managed Google Antigravity sessions through agy's ACP server (`agy acp`, Agent
//! Client Protocol v1, JSON-RPC over stdio).
//!
//! SwitchTether owns the process it starts; Antigravity windows the user opened are never
//! touched. Control goes through ACP: `session/new` / `session/resume` (continuing a conversation
//! from agy's history), `session/prompt`, `session/cancel`, `session/request_permission`,
//! `session/set_config_option` (model, thinking level) and `session/set_mode` (agy's permission
//! modes, plus `--dangerously-skip-permissions` for `bypassPermissions`). The timeline is built
//! from the `session/update` notifications ([`super::AcpMapper`]); the conversation itself is
//! stored by agy in its usual data directory, so it stays in agy's own history.

use std::collections::HashMap;
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use parking_lot::Mutex;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::process::{Child, Command};
use tokio::sync::oneshot;

use super::AcpMapper;
use crate::agents::dsh::managed::{approval_options, option_for};
use crate::agents::managed::{ImageAttachment, ManagedSession};
use crate::agents::model::{EventDraft, Liveness};
use crate::agents::permission::FULL_ACCESS;
use crate::agents::store::SessionLog;
use crate::error::{ApiError, ApiResult};

#[derive(Clone)]
pub struct ManagedAgyOptions {
    pub agy_bin: PathBuf,
    pub cwd: PathBuf,
    /// Conversation of agy's history to continue (`session/resume`).
    pub resume_session: Option<String>,
    /// Model chosen on the phone (grouped id, tiered id or display name; see
    /// [`super::resolve_model`]); `None`: agy's own default.
    pub model: Option<String>,
    /// Thinking level (`low`, `medium`, `high`, `xhigh`).
    pub effort: Option<String>,
    /// SwitchTether permission mode id (`default`, `acceptEdits`, `auto`, `plan`,
    /// `bypassPermissions`).
    pub permission_mode: Option<String>,
    /// Extra workspace directories (`--add-dir`), e.g. where phone attachments are stored.
    pub add_dirs: Vec<PathBuf>,
}

type Writer = Box<dyn AsyncWrite + Send + Unpin>;

struct PendingPermission {
    rpc_id: Value,
    options: Vec<Value>,
    event_id: String,
}

struct State {
    status: &'static str,
    liveness: Liveness,
    session_id: Option<String>,
    /// Explicit rename set from the phone (`rename` session tool); `None` lets `AgentHub` pick up
    /// Antigravity's generated summary title from `conversation_summaries.db`.
    custom_title: Option<String>,
    created_at_ms: i64,
    last_active_at_ms: i64,
    /// Latest ACP `configOptions` (model, thought_level, mode).
    config: Vec<Value>,
    /// `models.availableModels` of the session (tiered ids with display names).
    available: Vec<(String, String)>,
    /// Current ACP mode id (`default`, `accept-edits`, `auto`, `plan`).
    mode: Option<String>,
    /// `available_commands_update` (slash commands and skills).
    commands: Vec<Value>,
    /// Context window use from `usage_update` (used, size).
    context: Option<(u64, u64)>,
    /// `promptCapabilities.image` of the server.
    images: bool,
}

struct Core {
    cwd: PathBuf,
    options: ManagedAgyOptions,
    log: Arc<Mutex<SessionLog>>,
    stdin: tokio::sync::Mutex<Option<Writer>>,
    child: Mutex<Option<Child>>,
    pid: Mutex<Option<u32>>,
    bypass: AtomicBool,
    restarting: AtomicBool,
    next_id: AtomicU64,
    replies: Mutex<HashMap<u64, oneshot::Sender<Result<Value, Value>>>>,
    pending: Mutex<HashMap<String, PendingPermission>>,
    resolved: Mutex<HashMap<String, String>>,
    state: Arc<Mutex<State>>,
    /// A `session/prompt` is in flight (ACP allows one per session).
    prompting: Arc<AtomicBool>,
    mapper: Arc<Mutex<AcpMapper>>,
}

pub struct ManagedAgy {
    core: Arc<Core>,
}

fn rpc_message(e: &Value) -> String {
    let msg = e
        .get("message")
        .and_then(Value::as_str)
        .unwrap_or("request failed");
    crate::redact::redact_text(msg)
}

fn spawn_agy_process(options: &ManagedAgyOptions, bypass: bool) -> anyhow::Result<Child> {
    let mut cmd = Command::new(&options.agy_bin);
    if let Some(path) = crate::util::child_path() {
        cmd.env("PATH", path);
    }
    cmd.arg("acp");
    if bypass {
        cmd.arg("--dangerously-skip-permissions");
    }
    for d in &options.add_dirs {
        cmd.arg("--add-dir").arg(d);
    }
    cmd.current_dir(&options.cwd)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true);
    #[cfg(windows)]
    {
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    Ok(cmd.spawn()?)
}

impl Core {
    fn spawn_reader(self: &Arc<Self>, reader: impl AsyncRead + Send + Unpin + 'static) {
        let rd = self.clone();
        tokio::spawn(async move {
            let mut lines = BufReader::new(reader).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if let Ok(v) = serde_json::from_str::<Value>(&line) {
                    rd.on_message(v).await;
                }
            }
            rd.on_exit();
        });
    }

    async fn open(&self, options: &ManagedAgyOptions) -> anyhow::Result<()> {
        let init = self
            .request(
                "initialize",
                json!({
                    "protocolVersion": 1,
                    "clientCapabilities": { "fs": { "readTextFile": false, "writeTextFile": false }, "terminal": false },
                    "clientInfo": { "name": "switchtether", "title": "SwitchTether", "version": env!("CARGO_PKG_VERSION") },
                }),
                Duration::from_secs(60),
            )
            .await
            .map_err(|e| anyhow::anyhow!("agy ACP server did not initialize: {}", rpc_message(&e)))?;
        self.state.lock().images = init
            .pointer("/agentCapabilities/promptCapabilities/image")
            .and_then(Value::as_bool)
            .unwrap_or(false);
        let cwd = self.cwd.to_string_lossy().to_string();
        // Loading a session (`session/load`) replays it, but agy's replay leaves out answers;
        // the history is read from agy's own files instead and the conversation resumed.
        let (method, params) = match &options.resume_session {
            Some(sid) => (
                "session/resume",
                json!({ "sessionId": sid, "cwd": cwd, "mcpServers": [] }),
            ),
            None => ("session/new", json!({ "cwd": cwd, "mcpServers": [] })),
        };
        let r = self
            .request(method, params, Duration::from_secs(120))
            .await
            .map_err(|e| anyhow::anyhow!("agy could not open the session: {}", rpc_message(&e)))?;
        let sid = r
            .get("sessionId")
            .and_then(Value::as_str)
            .map(str::to_string)
            .or_else(|| options.resume_session.clone())
            .ok_or_else(|| anyhow::anyhow!("agy did not name the session"))?;
        {
            let mut st = self.state.lock();
            st.session_id = Some(sid);
            st.available = super::available_pairs(r.get("models").unwrap_or(&Value::Null));
            st.config = r
                .get("configOptions")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            st.mode = r
                .pointer("/modes/currentModeId")
                .and_then(Value::as_str)
                .map(str::to_string)
                .or_else(|| super::option_current(&st.config, "mode"));
            self.mapper
                .lock()
                .set_model(super::option_current(&st.config, "model"));
        }
        if let Some(model) = options.model.as_deref().filter(|m| *m != "default") {
            self.apply_model(model, options.effort.as_deref())
                .await
                .map_err(|e| anyhow::anyhow!(e.message))?;
        } else if let Some(e) = options.effort.as_deref().filter(|e| *e != "default") {
            self.apply_effort(e)
                .await
                .map_err(|e| anyhow::anyhow!(e.message))?;
        }
        if let Some(mode) = options
            .permission_mode
            .as_deref()
            .filter(|m| *m != "default" && *m != FULL_ACCESS)
        {
            self.apply_acp_mode(mode)
                .await
                .map_err(|e| anyhow::anyhow!(e.message))?;
        }
        Ok(())
    }

    fn sid(&self) -> ApiResult<String> {
        self.state
            .lock()
            .session_id
            .clone()
            .ok_or_else(|| ApiError::conflict("the Antigravity session is not open yet"))
    }

    async fn write_line(&self, v: &Value) -> ApiResult<()> {
        let mut g = self.stdin.lock().await;
        let w = g
            .as_mut()
            .ok_or_else(|| ApiError::conflict("the agy process has exited"))?;
        let mut line = serde_json::to_vec(v).map_err(|e| ApiError::internal("encode", e))?;
        line.push(b'\n');
        w.write_all(&line)
            .await
            .map_err(|e| ApiError::internal("write to agy", e))?;
        w.flush()
            .await
            .map_err(|e| ApiError::internal("write to agy", e))
    }

    async fn request(
        &self,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<Value, Value> {
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);
        let (tx, rx) = oneshot::channel();
        self.replies.lock().insert(id, tx);
        if let Err(e) = self
            .write_line(&json!({ "jsonrpc": "2.0", "id": id, "method": method, "params": params }))
            .await
        {
            self.replies.lock().remove(&id);
            return Err(json!({ "message": e.message }));
        }
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(r)) => r,
            Ok(Err(_)) => Err(json!({ "message": "agy process exited" })),
            Err(_) => {
                self.replies.lock().remove(&id);
                Err(json!({ "message": format!("{method} timed out") }))
            }
        }
    }

    /// `session/set_config_option`; the reply carries the whole new option list.
    async fn set_config(&self, config_id: &str, value: &str) -> ApiResult<()> {
        let sid = self.sid()?;
        let r = self
            .request(
                "session/set_config_option",
                json!({ "sessionId": sid, "configId": config_id, "value": value }),
                Duration::from_secs(30),
            )
            .await
            .map_err(|e| {
                ApiError::conflict(format!("agy refused the change: {}", rpc_message(&e)))
            })?;
        if let Some(c) = r.get("configOptions").and_then(Value::as_array) {
            self.set_config_state(c.clone());
        }
        Ok(())
    }

    fn set_config_state(&self, config: Vec<Value>) {
        let model = super::option_current(&config, "model");
        let mode = super::option_current(&config, "mode");
        {
            let mut st = self.state.lock();
            st.config = config;
            if mode.is_some() {
                st.mode = mode;
            }
        }
        self.mapper.lock().set_model(model);
    }

    async fn apply_model(&self, model: &str, effort: Option<&str>) -> ApiResult<()> {
        let (config, available) = {
            let st = self.state.lock();
            (st.config.clone(), st.available.clone())
        };
        let (value, level) =
            super::resolve_model(&config, &available, model, effort).ok_or_else(|| {
                ApiError::validation(format!("Antigravity does not offer the model '{model}'"))
            })?;
        self.set_config("model", &value).await?;
        if let Some(l) = level {
            self.apply_effort(&l).await?;
        }
        Ok(())
    }

    async fn apply_effort(&self, effort: &str) -> ApiResult<()> {
        let levels = super::option_values(&self.state.lock().config, "thought_level");
        if !levels.iter().any(|l| l == effort) {
            return Err(ApiError::validation(format!(
                "this Antigravity model does not offer the thinking level '{effort}'"
            )));
        }
        self.set_config("thought_level", effort).await
    }

    async fn apply_acp_mode(&self, id: &str) -> ApiResult<String> {
        let acp = super::acp_mode(id)
            .ok_or_else(|| ApiError::validation("unknown permission mode for Antigravity"))?;
        let sid = self.sid()?;
        self.request(
            "session/set_mode",
            json!({ "sessionId": sid, "modeId": acp }),
            Duration::from_secs(30),
        )
        .await
        .map_err(|e| ApiError::conflict(format!("agy refused the mode: {}", rpc_message(&e))))?;
        self.state.lock().mode = Some(acp.to_string());
        Ok(id.to_string())
    }

    /// Applies any supported permission mode, seamlessly restarting `agy acp` via `session/resume`
    /// when toggling `bypassPermissions` (`--dangerously-skip-permissions`) mid-session.
    async fn apply_mode(self: &Arc<Self>, mode: &str) -> ApiResult<String> {
        let id = crate::agents::permission::normalize("agy", mode)
            .ok_or_else(|| ApiError::validation("unknown permission mode for Antigravity"))?;
        let want_bypass = id == FULL_ACCESS;
        let cur_bypass = self.bypass.load(Ordering::SeqCst);

        if want_bypass == cur_bypass {
            if want_bypass {
                return Ok(FULL_ACCESS.to_string());
            }
            return self.apply_acp_mode(id).await;
        }

        // Toggling `--dangerously-skip-permissions`: requires restarting `agy acp` while idle.
        if self.prompting.load(Ordering::SeqCst) {
            return Err(ApiError::conflict(
                "Wait for the current turn to finish (or interrupt it) before toggling Bypass permissions.",
            ));
        }
        let has_os_child = self.child.lock().is_some();
        if !has_os_child {
            // In-memory test pipe: flip the flag and update ACP mode when leaving bypass.
            self.bypass.store(want_bypass, Ordering::SeqCst);
            if !want_bypass {
                self.apply_acp_mode(id).await?;
            }
            return Ok(id.to_string());
        }

        let sid = self.sid()?;
        let (cur_model, cur_effort) = {
            let cfg = &self.state.lock().config;
            (
                super::option_current(cfg, "model"),
                super::option_current(cfg, "thought_level"),
            )
        };

        self.restarting.store(true, Ordering::SeqCst);
        {
            let mut g = self.stdin.lock().await;
            g.take();
        }
        if let Some(mut old) = self.child.lock().take() {
            let _ = old.start_kill();
        }

        let mut child = match spawn_agy_process(&self.options, want_bypass) {
            Ok(c) => c,
            Err(e) => {
                self.restarting.store(false, Ordering::SeqCst);
                self.on_exit();
                return Err(ApiError::internal("restart agy acp", e));
            }
        };
        let new_pid = child.id();
        let Some(stdin) = child.stdin.take() else {
            self.restarting.store(false, Ordering::SeqCst);
            return Err(ApiError::internal("restart agy acp", "no stdin"));
        };
        let Some(stdout) = child.stdout.take() else {
            self.restarting.store(false, Ordering::SeqCst);
            return Err(ApiError::internal("restart agy acp", "no stdout"));
        };
        *self.pid.lock() = new_pid;
        *self.child.lock() = Some(child);
        *self.stdin.lock().await = Some(Box::new(stdin));
        self.bypass.store(want_bypass, Ordering::SeqCst);
        self.spawn_reader(stdout);
        self.restarting.store(false, Ordering::SeqCst);

        let mut reopen = self.options.clone();
        reopen.resume_session = Some(sid);
        reopen.model = cur_model;
        reopen.effort = cur_effort;
        reopen.permission_mode = Some(id.to_string());
        self.open(&reopen)
            .await
            .map_err(|e| ApiError::internal("reopen agy session", format!("{e:#}")))?;
        Ok(id.to_string())
    }

    async fn on_message(&self, v: Value) {
        let method = v.get("method").and_then(Value::as_str).map(str::to_string);
        let id = v.get("id").cloned();
        match (method, id) {
            (None, Some(id)) => {
                if let Some(n) = id.as_u64() {
                    if let Some(tx) = self.replies.lock().remove(&n) {
                        let r = match v.get("error") {
                            Some(e) => Err(e.clone()),
                            None => Ok(v.get("result").cloned().unwrap_or(Value::Null)),
                        };
                        let _ = tx.send(r);
                    }
                }
            }
            (Some(m), Some(id)) => {
                if m == "session/request_permission" {
                    self.on_permission(id, v.get("params").cloned().unwrap_or(Value::Null));
                } else {
                    // No client file system or terminal was advertised: refuse anything else.
                    let _ = self
                        .write_line(&json!({ "jsonrpc": "2.0", "id": id,
                            "error": { "code": -32601, "message": "not supported by SwitchTether" } }))
                        .await;
                }
            }
            (Some(m), None) if m == "session/update" => {
                let Some(update) = v.pointer("/params/update") else {
                    return;
                };
                self.state.lock().last_active_at_ms = crate::util::now_ms();
                match update.get("sessionUpdate").and_then(Value::as_str) {
                    Some("config_option_update") => {
                        if let Some(c) = update.get("configOptions").and_then(Value::as_array) {
                            self.set_config_state(c.clone());
                        }
                    }
                    Some("current_mode_update") => {
                        self.state.lock().mode = update
                            .get("currentModeId")
                            .and_then(Value::as_str)
                            .map(str::to_string);
                    }
                    Some("available_commands_update") => {
                        let cmds: Vec<Value> = update
                            .get("availableCommands")
                            .and_then(Value::as_array)
                            .into_iter()
                            .flatten()
                            .map(|c| {
                                json!({
                                    "name": c.get("name"),
                                    "description": c.get("description"),
                                    "argumentHint": c.pointer("/input/hint"),
                                })
                            })
                            .collect();
                        if !cmds.is_empty() {
                            self.state.lock().commands = cmds;
                        }
                    }
                    Some("usage_update") => {
                        let used = update.get("used").and_then(Value::as_u64);
                        let size = update.get("size").and_then(Value::as_u64);
                        if let (Some(u), Some(s)) = (used, size) {
                            self.state.lock().context = Some((u, s));
                        }
                    }
                    _ => {
                        let mut mapper = self.mapper.lock();
                        mapper.apply(update, &mut self.log.lock(), true);
                    }
                }
            }
            _ => {}
        }
    }

    fn on_permission(&self, rpc_id: Value, p: Value) {
        let options: Vec<Value> = p
            .get("options")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        let tool = p.get("toolCall").cloned().unwrap_or(Value::Null);
        // agy numbers its own requests with strings ("1", "2", …).
        let raw = rpc_id
            .as_str()
            .map(str::to_string)
            .unwrap_or_else(|| rpc_id.to_string());
        let rid = format!("agy-perm-{raw}");
        let event_id = format!("perm:{rid}");
        let kind = match tool.get("kind").and_then(Value::as_str) {
            Some("execute") => "command",
            Some("edit" | "delete" | "move") => "file_change",
            Some("fetch") => "network",
            _ => "tool",
        };
        let input = tool
            .get("rawInput")
            .or_else(|| tool.get("args"))
            .cloned()
            .unwrap_or(Value::Null);
        let command = input
            .get("CommandLine")
            .or_else(|| input.get("command"))
            .cloned();
        let title = tool
            .get("title")
            .and_then(Value::as_str)
            .map(crate::redact::redact_text)
            .unwrap_or_else(|| "Permission request".into());
        let call_id = tool.get("toolCallId").and_then(Value::as_str);
        let mut redacted_input = input.clone();
        crate::redact::redact_json(&mut redacted_input);
        let mut d = EventDraft::new(
            &event_id,
            "approval_request",
            crate::util::now_rfc3339(),
            json!({
                "requestId": rid, "kind": kind, "title": title,
                "detail": input.get("toolSummary"),
                "toolName": tool.get("name"), "command": command,
                "cwd": self.cwd.to_string_lossy(), "input": redacted_input,
                "options": approval_options(&options), "resolved": false,
                "toolUseId": call_id,
            }),
        );
        d.parent_id = call_id.map(|c| format!("tc:{c}"));
        self.log.lock().upsert(d, true);
        self.pending.lock().insert(
            rid,
            PendingPermission {
                rpc_id,
                options,
                event_id,
            },
        );
        self.state.lock().status = "waiting";
    }

    fn on_exit(&self) {
        if self.restarting.load(Ordering::SeqCst) {
            return;
        }
        {
            let mut st = self.state.lock();
            st.liveness = Liveness::Ended;
            st.status = "completed";
        }
        self.prompting.store(false, Ordering::SeqCst);
        for (_, tx) in self.replies.lock().drain() {
            let _ = tx.send(Err(json!({ "message": "process exited" })));
        }
        self.cancel_pending("agent");
    }

    /// Open permission requests end with the turn (cancel, exit): marked cancelled.
    fn cancel_pending(&self, by: &str) -> Vec<Value> {
        let pending: Vec<(String, PendingPermission)> = self.pending.lock().drain().collect();
        let mut rpc_ids = Vec::new();
        for (rid, p) in pending {
            self.resolved.lock().insert(rid, by.into());
            self.log.lock().update(&p.event_id, true, |e| {
                e.data["resolved"] = json!(true);
                e.data["decision"] = json!("cancelled");
                e.data["by"] = json!(by);
            });
            rpc_ids.push(p.rpc_id);
        }
        rpc_ids
    }

    fn commands(&self) -> Vec<Value> {
        let reported = self.state.lock().commands.clone();
        if !reported.is_empty() {
            reported
        } else {
            super::default_commands()
        }
    }

    fn context_usage(&self) -> Value {
        let ctx = self.state.lock().context.or_else(|| {
            let lg = self.log.lock();
            let u = lg.iter().filter(|e| e.kind == "usage").last()?;
            let n = |k: &str| u.data.get(k).and_then(Value::as_u64).unwrap_or(0);
            let total =
                n("inputTokens") + n("outputTokens") + n("cacheReadTokens") + n("cacheWriteTokens");
            (total > 0).then_some((total, 1_000_000))
        });
        let pct = ctx
            .filter(|(_, s)| *s > 0)
            .map(|(u, s)| ((u as f64) * 100.0 / (s as f64)).min(100.0));
        json!({
            "totalTokens": ctx.map(|c| c.0),
            "maxTokens": ctx.map(|c| c.1),
            "percentage": pct,
            "categories": [],
        })
    }

    fn rename(&self, title: &str) -> ApiResult<Value> {
        if title.chars().count() > 200 {
            return Err(ApiError::validation("title too long"));
        }
        let clean = crate::redact::redact_text(title.trim());
        if clean.is_empty() {
            return Err(ApiError::validation("'title' is required"));
        }
        self.state.lock().custom_title = Some(clean.clone());
        Ok(json!({ "title": clean }))
    }
}

impl ManagedAgy {
    /// Starts `agy acp` in `options.cwd` and opens (or resumes) the conversation.
    pub async fn spawn(
        id: String,
        options: ManagedAgyOptions,
        log: Arc<Mutex<SessionLog>>,
    ) -> anyhow::Result<Arc<Self>> {
        let bypass = options.permission_mode.as_deref() == Some(FULL_ACCESS);
        let mut child = spawn_agy_process(&options, bypass)?;
        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| anyhow::anyhow!("no stdin"))?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| anyhow::anyhow!("no stdout"))?;
        Self::start(id, options, log, stdout, Box::new(stdin), Some(child)).await
    }

    /// Runs the ACP client over any byte stream (`agy acp`'s stdio, or a test double).
    pub async fn start(
        id: String,
        options: ManagedAgyOptions,
        log: Arc<Mutex<SessionLog>>,
        reader: impl AsyncRead + Send + Unpin + 'static,
        writer: Writer,
        child: Option<Child>,
    ) -> anyhow::Result<Arc<Self>> {
        let now = crate::util::now_ms();
        let run = format!("{:x}", now);
        let _ = id;
        let bypass = options.permission_mode.as_deref() == Some(FULL_ACCESS);
        let core = Arc::new(Core {
            cwd: options.cwd.clone(),
            pid: Mutex::new(child.as_ref().and_then(|c| c.id())),
            options: options.clone(),
            log,
            stdin: tokio::sync::Mutex::new(Some(writer)),
            child: Mutex::new(child),
            bypass: AtomicBool::new(bypass),
            restarting: AtomicBool::new(false),
            next_id: AtomicU64::new(1),
            replies: Mutex::new(HashMap::new()),
            pending: Mutex::new(HashMap::new()),
            resolved: Mutex::new(HashMap::new()),
            state: Arc::new(Mutex::new(State {
                status: "idle",
                liveness: Liveness::Live,
                session_id: None,
                custom_title: None,
                created_at_ms: now,
                last_active_at_ms: now,
                config: Vec::new(),
                available: Vec::new(),
                mode: None,
                commands: Vec::new(),
                context: None,
                images: false,
            })),
            prompting: Arc::new(AtomicBool::new(false)),
            mapper: Arc::new(Mutex::new(AcpMapper::new(run))),
        });
        core.spawn_reader(reader);
        let me = Arc::new(Self { core });
        if let Err(e) = me.core.open(&options).await {
            me.kill();
            return Err(e);
        }
        Ok(me)
    }
}

#[async_trait]
impl ManagedSession for ManagedAgy {
    fn agent(&self) -> &'static str {
        "agy"
    }
    fn log(&self) -> Arc<Mutex<SessionLog>> {
        self.core.log.clone()
    }
    fn cwd(&self) -> PathBuf {
        self.core.cwd.clone()
    }
    fn native_session_id(&self) -> Option<String> {
        self.core.state.lock().session_id.clone()
    }
    fn pid(&self) -> Option<u32> {
        *self.core.pid.lock()
    }
    fn status(&self) -> &'static str {
        let (live, fallback) = {
            let st = self.core.state.lock();
            (st.liveness == Liveness::Live, st.status)
        };
        if !live {
            return fallback;
        }
        if !self.core.pending.lock().is_empty() {
            "waiting"
        } else if self.core.prompting.load(Ordering::SeqCst) {
            "busy"
        } else {
            "idle"
        }
    }
    fn liveness(&self) -> Liveness {
        self.core.state.lock().liveness
    }
    fn created_at_ms(&self) -> i64 {
        self.core.state.lock().created_at_ms
    }
    fn last_active_at_ms(&self) -> i64 {
        self.core.state.lock().last_active_at_ms
    }
    fn model(&self) -> Option<String> {
        super::option_current(&self.core.state.lock().config, "model")
    }
    fn effort(&self) -> Option<String> {
        super::option_current(&self.core.state.lock().config, "thought_level")
    }
    fn title(&self) -> Option<String> {
        self.core.state.lock().custom_title.clone()
    }
    fn has_pending_requests(&self) -> bool {
        !self.core.pending.lock().is_empty()
    }
    fn supports_images(&self) -> bool {
        self.core.state.lock().images
    }

    async fn send_message(&self, text: &str) -> ApiResult<()> {
        self.send_message_with_images(text, &[]).await
    }

    async fn send_message_with_images(
        &self,
        text: &str,
        images: &[ImageAttachment],
    ) -> ApiResult<()> {
        if self.core.state.lock().liveness != Liveness::Live {
            return Err(ApiError::conflict("the agy process has exited"));
        }
        if !images.is_empty() && !self.supports_images() {
            return Err(ApiError::unavailable_capability(
                "BLOCKED",
                "This Antigravity session does not accept images.",
            ));
        }
        // ACP allows one prompt per session at a time.
        if self.core.prompting.swap(true, Ordering::SeqCst) {
            return Err(ApiError::conflict(
                "Antigravity is still working on the previous message; wait or interrupt it",
            ));
        }
        let sid = match self.core.sid() {
            Ok(s) => s,
            Err(e) => {
                self.core.prompting.store(false, Ordering::SeqCst);
                return Err(e);
            }
        };
        {
            let mut st = self.core.state.lock();
            st.status = "busy";
            st.last_active_at_ms = crate::util::now_ms();
        }
        let mut prompt = vec![json!({ "type": "text", "text": text })];
        for img in images {
            prompt.push(
                json!({ "type": "image", "mimeType": img.media_type, "data": img.data_base64 }),
            );
        }
        self.core
            .mapper
            .lock()
            .start_turn_with_user(text, images, &mut self.core.log.lock(), true);
        let id = self.core.next_id.fetch_add(1, Ordering::SeqCst);
        let (tx, rx) = oneshot::channel();
        self.core.replies.lock().insert(id, tx);
        self.core
            .write_line(&json!({
                "jsonrpc": "2.0", "id": id, "method": "session/prompt",
                "params": { "sessionId": sid, "prompt": prompt },
            }))
            .await
            .inspect_err(|_| {
                self.core.replies.lock().remove(&id);
                self.core.prompting.store(false, Ordering::SeqCst);
            })?;
        let state = self.core.state.clone();
        let prompting = self.core.prompting.clone();
        let log = self.core.log.clone();
        let mapper = self.core.mapper.clone();
        tokio::spawn(async move {
            let r = rx.await;
            {
                let mut m = mapper.lock();
                let mut lg = log.lock();
                match &r {
                    Ok(Ok(v)) => {
                        let reason = v
                            .get("stopReason")
                            .and_then(Value::as_str)
                            .unwrap_or("end_turn");
                        m.end_turn(reason, v.get("usage"), &mut lg, true);
                    }
                    Ok(Err(e)) => {
                        m.end_turn("error", None, &mut lg, true);
                        lg.upsert(
                            EventDraft::new(
                                format!("agy-error:{}", crate::util::now_ms()),
                                "error",
                                crate::util::now_rfc3339(),
                                json!({ "message": rpc_message(e) }),
                            ),
                            true,
                        );
                    }
                    Err(_) => m.end_turn("cancelled", None, &mut lg, true),
                }
            }
            prompting.store(false, Ordering::SeqCst);
            let mut st = state.lock();
            if st.liveness == Liveness::Live {
                st.status = "idle";
            }
            st.last_active_at_ms = crate::util::now_ms();
        });
        Ok(())
    }

    async fn interrupt(&self) -> ApiResult<()> {
        let sid = self.core.sid()?;
        self.core
            .write_line(
                &json!({ "jsonrpc": "2.0", "method": "session/cancel", "params": { "sessionId": sid } }),
            )
            .await?;
        for rpc_id in self.core.cancel_pending("phone") {
            let _ = self
                .core
                .write_line(&json!({ "jsonrpc": "2.0", "id": rpc_id,
                    "result": { "outcome": { "outcome": "cancelled" } } }))
                .await;
        }
        Ok(())
    }

    async fn respond(
        &self,
        request_id: &str,
        decision: &str,
        _message: Option<String>,
        _updated_input: Option<Value>,
        _answers: Option<Value>,
        by: &str,
    ) -> ApiResult<Value> {
        let pending = {
            let mut p = self.core.pending.lock();
            match p.remove(request_id) {
                Some(x) => x,
                None => {
                    let by = self.core.resolved.lock().get(request_id).cloned();
                    return Err(match by {
                        Some(b) => ApiError::conflict("this request was already answered")
                            .with_details(json!({ "resolvedBy": b })),
                        None => ApiError::not_found("no such pending request"),
                    });
                }
            }
        };
        let outcome = match decision {
            "abort" | "cancel" => json!({ "outcome": "cancelled" }),
            d => match option_for(&pending.options, d) {
                Some(option_id) => json!({ "outcome": "selected", "optionId": option_id }),
                None => {
                    self.core
                        .pending
                        .lock()
                        .insert(request_id.to_string(), pending);
                    return Err(ApiError::validation(format!("unknown decision '{d}'")));
                }
            },
        };
        self.core
            .write_line(
                &json!({ "jsonrpc": "2.0", "id": pending.rpc_id, "result": { "outcome": outcome } }),
            )
            .await?;
        self.core
            .resolved
            .lock()
            .insert(request_id.to_string(), by.to_string());
        let ev = self.core.log.lock().update(&pending.event_id, true, |e| {
            e.data["resolved"] = json!(true);
            e.data["decision"] = json!(decision);
            e.data["by"] = json!(by);
        });
        if self.core.pending.lock().is_empty() {
            self.core.state.lock().status = "busy";
        }
        if decision == "abort" {
            let _ = self.interrupt().await;
        }
        Ok(json!({ "resolved": true, "event": ev }))
    }

    fn kill(&self) {
        if let Ok(mut g) = self.core.stdin.try_lock() {
            g.take();
        }
        if let Some(mut c) = self.core.child.lock().take() {
            let _ = c.start_kill();
        }
        let mut st = self.core.state.lock();
        if st.liveness == Liveness::Live {
            st.liveness = Liveness::Ended;
            st.status = "completed";
            st.last_active_at_ms = crate::util::now_ms();
        }
    }

    fn can_change_model(&self) -> bool {
        true
    }
    async fn models(&self) -> Value {
        super::group_models(&self.core.state.lock().available)
    }
    async fn set_model(&self, model: Option<&str>) -> ApiResult<()> {
        match model {
            Some(m) => self.core.apply_model(m, None).await,
            None => Ok(()),
        }
    }
    async fn set_effort(&self, effort: Option<&str>) -> ApiResult<()> {
        match effort {
            Some(e) => self.core.apply_effort(e).await,
            None => Ok(()),
        }
    }
    fn permission_mode(&self) -> Option<String> {
        if self.core.bypass.load(Ordering::SeqCst) {
            return Some(FULL_ACCESS.to_string());
        }
        self.core
            .state
            .lock()
            .mode
            .as_deref()
            .and_then(super::mode_from_acp)
            .map(str::to_string)
    }
    fn can_change_permission_mode(&self) -> bool {
        true
    }
    async fn set_permission_mode(&self, mode: &str) -> ApiResult<String> {
        self.core.apply_mode(mode).await
    }
    fn session_tools(&self) -> &'static [&'static str] {
        &[
            "commands",
            "permission_mode",
            "context_usage",
            "task_output",
            "plan",
            "mcp_status",
            "rename",
            "export",
        ]
    }
    async fn session_tool(&self, action: &str, p: &Value) -> ApiResult<Value> {
        let arg = |k: &str| -> ApiResult<String> {
            p.get(k)
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|v| !v.is_empty() && v.len() <= 4000)
                .map(String::from)
                .ok_or_else(|| ApiError::validation(format!("'{k}' is required")))
        };
        match action {
            "commands" => Ok(json!(self.core.commands())),
            "permission_mode" => {
                let mode = arg("mode")?;
                Ok(json!({ "mode": self.core.apply_mode(&mode).await? }))
            }
            "context_usage" => Ok(self.core.context_usage()),
            "task_output" => {
                let task = arg("taskId")?;
                if !crate::agents::valid_agent_id(&task) {
                    return Err(ApiError::validation("invalid task id"));
                }
                super::task_output(&self.core.log.lock(), &task)
            }
            "plan" => {
                let brain = self.native_session_id().and_then(|sid| {
                    super::live::AgyObserver::new(super::live::AgyObserver::default_roots())
                        .brain_dir(&sid)
                });
                Ok(super::plan_view(brain.as_deref(), &self.core.log.lock()))
            }
            "mcp_status" => Ok(super::live::read_mcp_status(Some(&self.core.cwd))),
            "rename" => self.core.rename(&arg("title")?),
            "export" => {
                let sid = self.native_session_id().unwrap_or_else(|| "session".into());
                let text =
                    crate::redact::redact_text(&crate::agents::export_text(&self.core.log.lock()));
                let date = chrono::Local::now().format("%Y-%m-%d-%H%M%S");
                Ok(json!({ "text": text, "filename": format!("{date}-{sid}.txt") }))
            }
            _ => Err(ApiError::unavailable_capability(
                "BLOCKED",
                "Antigravity does not offer this session tool.",
            )),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

    async fn fake_agy(
        recording: Vec<Value>,
        server: tokio::io::DuplexStream,
        seen: Arc<Mutex<Vec<Value>>>,
        cancel_after_chunks: Option<usize>,
    ) {
        let (rd, mut wr) = tokio::io::split(server);
        let mut lines = BufReader::new(rd).lines();
        let mut by_method: HashMap<String, Vec<Value>> = HashMap::new();
        let mut prompts: Vec<Vec<Value>> = Vec::new();
        let mut methods: HashMap<u64, String> = HashMap::new();
        for l in &recording {
            let m = &l["msg"];
            if l["dir"] == "out" {
                if let (Some(id), Some(method)) = (m["id"].as_u64(), m["method"].as_str()) {
                    methods.insert(id, method.to_string());
                    if method == "session/prompt" {
                        prompts.push(Vec::new());
                    }
                }
                continue;
            }
            if m["method"] == "session/update" || m["method"] == "session/request_permission" {
                if let Some(p) = prompts.last_mut() {
                    p.push(m.clone());
                }
                continue;
            }
            if let Some(method) = m["id"].as_u64().and_then(|i| methods.get(&i)) {
                if method == "session/prompt" {
                    if let Some(p) = prompts.last_mut() {
                        p.push(m.clone());
                    }
                } else {
                    by_method.entry(method.clone()).or_default().push(m.clone());
                }
            }
        }
        prompts.reverse();
        let send = |v: Value| -> Vec<u8> {
            let mut s = serde_json::to_vec(&v).unwrap();
            s.push(b'\n');
            s
        };
        while let Ok(Some(line)) = lines.next_line().await {
            let v: Value = serde_json::from_str(&line).unwrap();
            seen.lock().push(v.clone());
            let Some(method) = v["method"].as_str() else {
                continue;
            };
            let id = v["id"].clone();
            if method == "session/cancel" {
                continue;
            }
            if method == "session/prompt" {
                let script = prompts.pop().unwrap_or_default();
                let mut chunks = 0;
                for mut m in script {
                    if m.get("result").is_some() {
                        m["id"] = id.clone();
                    }
                    let is_chunk = m.pointer("/params/update/sessionUpdate")
                        == Some(&json!("agent_message_chunk"));
                    if m["method"] == "session/request_permission" {
                        wr.write_all(&send(m)).await.unwrap();
                        loop {
                            let Ok(Some(l)) = lines.next_line().await else {
                                return;
                            };
                            let a: Value = serde_json::from_str(&l).unwrap();
                            seen.lock().push(a.clone());
                            if a.get("result").is_some() {
                                break;
                            }
                        }
                        continue;
                    }
                    wr.write_all(&send(m)).await.unwrap();
                    chunks += usize::from(is_chunk);
                    if cancel_after_chunks.is_some_and(|n| chunks == n) {
                        loop {
                            let Ok(Some(l)) = lines.next_line().await else {
                                return;
                            };
                            let a: Value = serde_json::from_str(&l).unwrap();
                            seen.lock().push(a.clone());
                            if a["method"] == "session/cancel" {
                                break;
                            }
                        }
                        let end =
                            json!({"jsonrpc":"2.0","id":id,"result":{"stopReason":"cancelled"}});
                        wr.write_all(&send(end)).await.unwrap();
                        break;
                    }
                }
                continue;
            }
            let reply = by_method
                .get_mut(method)
                .and_then(|r| (!r.is_empty()).then(|| r.remove(0)))
                .unwrap_or_else(|| json!({"jsonrpc":"2.0","result":{}}));
            let mut reply = reply;
            reply["id"] = id;
            wr.write_all(&send(reply)).await.unwrap();
        }
    }

    fn options(cwd: &std::path::Path) -> ManagedAgyOptions {
        ManagedAgyOptions {
            agy_bin: PathBuf::from("agy"),
            cwd: cwd.to_path_buf(),
            resume_session: None,
            model: None,
            effort: None,
            permission_mode: None,
            add_dirs: Vec::new(),
        }
    }

    async fn wait_idle(s: &ManagedAgy) {
        for _ in 0..200 {
            if s.status() == "idle" {
                return;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        panic!("session did not become idle: {}", s.status());
    }

    fn recording(names: &[&str]) -> Vec<Value> {
        names
            .iter()
            .flat_map(|n| super::super::tests::fixture(n))
            .collect()
    }

    #[tokio::test]
    async fn opens_sets_model_mode_tools_and_runs_a_turn_with_cancel() {
        let dir = tempfile::tempdir().unwrap();
        let (client, server) = tokio::io::duplex(1 << 20);
        let seen = Arc::new(Mutex::new(Vec::new()));
        let mut rec = recording(&["new"]);
        rec.extend(
            recording(&["turns"])
                .into_iter()
                .filter(|l| l["msg"]["method"] != "initialize"),
        );
        tokio::spawn(fake_agy(rec, server, seen.clone(), Some(5)));
        let (rd, wr) = tokio::io::split(client);
        let log = Arc::new(Mutex::new(SessionLog::new("starting:1", None)));
        let mut o = options(dir.path());
        o.model = Some("claude-sonnet-5-5-low".into());
        o.permission_mode = Some("acceptEdits".into());
        let s = ManagedAgy::start("x".into(), o, log.clone(), rd, Box::new(wr), None)
            .await
            .unwrap();
        assert_eq!(
            s.native_session_id().as_deref(),
            Some("9a85a077-bd10-4bd1-9e8b-353eb66c66b6")
        );
        // Builtin slash commands are served even before the first turn's update arrives.
        let initial_cmds = s.session_tool("commands", &json!({})).await.unwrap();
        assert!(initial_cmds
            .as_array()
            .unwrap()
            .iter()
            .any(|c| c["name"] == "goal"));

        // Bypass permissions toggle + rename + export session tools.
        assert_eq!(
            s.set_permission_mode("bypassPermissions").await.unwrap(),
            "bypassPermissions"
        );
        assert_eq!(s.permission_mode().as_deref(), Some("bypassPermissions"));
        assert_eq!(
            s.set_permission_mode("acceptEdits").await.unwrap(),
            "acceptEdits"
        );
        assert_eq!(s.permission_mode().as_deref(), Some("acceptEdits"));
        let renamed = s
            .session_tool("rename", &json!({ "title": "My Agy Task" }))
            .await
            .unwrap();
        assert_eq!(renamed["title"], "My Agy Task");
        assert_eq!(s.title().as_deref(), Some("My Agy Task"));

        log.lock().expect_user_message(
            "Run the shell command 'echo hi' in the terminal and tell me its output in one line.",
            "cmd-1",
        );
        s.send_message(
            "Run the shell command 'echo hi' in the terminal and tell me its output in one line.",
        )
        .await
        .unwrap();
        assert!(
            s.send_message("again").await.is_err(),
            "one prompt at a time"
        );
        wait_idle(&s).await;

        let exported = s.session_tool("export", &json!({})).await.unwrap();
        assert!(exported["text"]
            .as_str()
            .unwrap()
            .contains("The output was `hi`."));

        s.send_message("Write a numbered list of 60 different fruits one per line with a short sentence about each.")
            .await
            .unwrap();
        for _ in 0..200 {
            if log
                .lock()
                .iter()
                .filter(|e| e.kind == "assistant_message")
                .count()
                >= 2
            {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert_eq!(s.status(), "busy");
        s.interrupt().await.unwrap();
        wait_idle(&s).await;
        let lg = log.lock();
        let user = lg.iter().find(|e| e.kind == "user_message").unwrap();
        assert_eq!(user.data["clientId"], "cmd-1");
        assert!(lg
            .iter()
            .any(|e| e.kind == "tool_call" && e.data["status"] == "completed"));
        assert_eq!(
            lg.get(super::super::STATUS_ID).unwrap().data["state"],
            "interrupted"
        );
        assert!(seen.lock().iter().any(|v| v["method"] == "session/cancel"));
        drop(lg);
        s.kill();
    }

    #[tokio::test]
    async fn permission_request_is_answered_from_the_phone_once() {
        let dir = tempfile::tempdir().unwrap();
        let (client, server) = tokio::io::duplex(1 << 20);
        let seen = Arc::new(Mutex::new(Vec::new()));
        tokio::spawn(fake_agy(
            recording(&["permission"]),
            server,
            seen.clone(),
            None,
        ));
        let (rd, wr) = tokio::io::split(client);
        let log = Arc::new(Mutex::new(SessionLog::new("agy:95b7", None)));
        let mut o = options(dir.path());
        o.resume_session = Some("95b781b2-9cb2-4dcd-b660-a9bab237e8f5".into());
        let s = ManagedAgy::start("x".into(), o, log.clone(), rd, Box::new(wr), None)
            .await
            .unwrap();
        assert!(seen.lock().iter().any(|v| v["method"] == "session/resume"));
        s.send_message("Use a shell command (Remove-Item or del) to delete hello.txt in the current directory and then reply done.")
            .await
            .unwrap();
        let mut rid = None;
        for _ in 0..200 {
            rid = log
                .lock()
                .iter()
                .find(|e| e.kind == "approval_request")
                .and_then(|e| e.data["requestId"].as_str().map(str::to_string));
            if rid.is_some() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        let rid = rid.expect("approval request");
        assert_eq!(rid, "agy-perm-1");
        assert_eq!(s.status(), "waiting");
        {
            let lg = log.lock();
            let a = lg.iter().find(|e| e.kind == "approval_request").unwrap();
            assert_eq!(a.data["kind"], "command");
            assert_eq!(a.data["command"], "Remove-Item hello.txt");
            assert_eq!(a.data["options"][0]["id"], "allow");
        }
        s.respond(&rid, "allow", None, None, None, "phone")
            .await
            .unwrap();
        let again = s.respond(&rid, "deny", None, None, None, "phone").await;
        assert!(
            again.unwrap_err().details.is_some(),
            "already answered (resolvedBy)"
        );
        wait_idle(&s).await;
        let answer = seen
            .lock()
            .iter()
            .find(|v| v["id"] == "1" && v.get("result").is_some())
            .cloned()
            .unwrap();
        assert_eq!(answer["result"]["outcome"]["optionId"], "allow");
        assert!(log
            .lock()
            .iter()
            .any(|e| e.kind == "assistant_message" && e.data["text"] == "done"));
        s.kill();
    }
}
