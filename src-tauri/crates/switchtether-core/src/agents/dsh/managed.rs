//! SwitchTether-managed DeepSeek Harness sessions through its ACP server
//! (`dsh --profile acp`, Agent Client Protocol v1, JSON-RPC over stdio).
//!
//! SwitchTether owns the process it starts; the user's own dsh windows are never touched.
//! Control goes through ACP (`session/new` / `session/resume`, `session/prompt`,
//! `session/cancel`, `session/request_permission`). The timeline is read from the session's
//! own persisted log with the same parser as observed sessions, so a managed session looks
//! exactly like an observed one (PTC steps, sub-agents) and survives a phone reconnect.

use std::collections::HashMap;
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use parking_lot::Mutex;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, Command};
use tokio::sync::oneshot;

use super::DshParser;
use crate::agents::managed::ManagedSession;
use crate::agents::model::{EventDraft, Liveness};
use crate::agents::store::SessionLog;
use crate::error::{ApiError, ApiResult};

pub struct ManagedDshOptions {
    pub dsh_bin: PathBuf,
    pub cwd: PathBuf,
    /// Existing dsh session to continue (`session/resume`).
    pub resume_session: Option<String>,
}

struct PendingPermission {
    rpc_id: Value,
    options: Vec<Value>,
    event_id: String,
}

struct State {
    status: &'static str,
    liveness: Liveness,
    session_id: Option<String>,
    title: Option<String>,
    created_at_ms: i64,
    last_active_at_ms: i64,
}

pub struct ManagedDsh {
    #[allow(dead_code)]
    id: String,
    options: ManagedDshOptions,
    log: Arc<Mutex<SessionLog>>,
    stdin: tokio::sync::Mutex<Option<ChildStdin>>,
    child: Mutex<Option<Child>>,
    pid: Option<u32>,
    next_id: AtomicU64,
    replies: Mutex<HashMap<u64, oneshot::Sender<Result<Value, Value>>>>,
    pending: Mutex<HashMap<String, PendingPermission>>,
    resolved: Mutex<HashMap<String, String>>,
    state: Arc<Mutex<State>>,
    /// A `session/prompt` is in flight (ACP allows one per session).
    prompting: Arc<AtomicBool>,
}

/// Tails a dsh session's persisted log into `log` (published live) while `alive()` holds,
/// waiting up to two minutes for the log to appear; one last read follows the end.
pub async fn follow_session_log(
    sid: String,
    log: Arc<Mutex<SessionLog>>,
    alive: impl Fn() -> bool + Send + 'static,
) {
    let mut tail: Option<crate::agents::Tail> = None;
    let started = std::time::Instant::now();
    loop {
        let live = alive();
        match tail.as_mut() {
            Some(t) => {
                if let Err(e) = crate::agents::tail_file(t, &log, true) {
                    log::debug!("[dsh] {sid}: tail failed: {e}");
                }
                if !live {
                    break;
                }
            }
            None => {
                let file = super::find_session_dir(&sid).and_then(|d| super::session_file(&d));
                if let Some(path) = file {
                    tail = Some(crate::agents::Tail::new(path, Box::new(DshParser::new())));
                    continue;
                }
                if !live || started.elapsed() > Duration::from_secs(120) {
                    break;
                }
            }
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
}

pub fn find_dsh_bin() -> Option<PathBuf> {
    if let Some(p) = crate::util::which("dsh") {
        return Some(p);
    }
    let home = dirs::home_dir()?;
    let candidates = if cfg!(windows) {
        vec![
            home.join(".bun").join("bin").join("dsh.exe"),
            home.join("AppData")
                .join("Roaming")
                .join("npm")
                .join("dsh.cmd"),
        ]
    } else {
        vec![
            home.join(".bun").join("bin").join("dsh"),
            home.join(".local").join("bin").join("dsh"),
            PathBuf::from("/opt/homebrew/bin/dsh"),
            PathBuf::from("/usr/local/bin/dsh"),
        ]
    };
    candidates.into_iter().find(|p| p.is_file())
}

/// ACP permission option for a SwitchTether decision (`allow`, `allow_session`, `deny`).
fn option_for(options: &[Value], decision: &str) -> Option<String> {
    let kinds: &[&str] = match decision {
        "allow" | "answer" => &["allow_once", "allow_always"],
        "allow_session" => &["allow_always", "allow_once"],
        "deny" => &["reject_once", "reject_always"],
        _ => return None,
    };
    kinds.iter().find_map(|k| {
        options
            .iter()
            .find(|o| o.get("kind").and_then(Value::as_str) == Some(k))
            .and_then(|o| o.get("optionId").and_then(Value::as_str))
            .map(str::to_string)
    })
}

/// SwitchTether approval options from the ACP ones.
fn approval_options(options: &[Value]) -> Vec<Value> {
    let mut out = Vec::new();
    let has = |k: &str| {
        options
            .iter()
            .any(|o| o.get("kind").and_then(Value::as_str) == Some(k))
    };
    if has("allow_once") || has("allow_always") {
        out.push(json!({ "id": "allow", "label": "Allow" }));
    }
    if has("allow_always") {
        out.push(json!({ "id": "allow_session", "label": "Always allow" }));
    }
    out.push(json!({ "id": "deny", "label": "Deny" }));
    out
}

impl ManagedDsh {
    pub async fn spawn(
        id: String,
        options: ManagedDshOptions,
        log: Arc<Mutex<SessionLog>>,
    ) -> anyhow::Result<Arc<Self>> {
        let mut cmd = Command::new(&options.dsh_bin);
        if let Some(path) = crate::util::child_path() {
            cmd.env("PATH", path);
        }
        cmd.args(["--profile", "acp"])
            .current_dir(&options.cwd)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true);
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
        let now = crate::util::now_ms();
        let me = Arc::new(Self {
            id,
            pid: child.id(),
            options,
            log,
            stdin: tokio::sync::Mutex::new(stdin),
            child: Mutex::new(Some(child)),
            next_id: AtomicU64::new(1),
            replies: Mutex::new(HashMap::new()),
            pending: Mutex::new(HashMap::new()),
            resolved: Mutex::new(HashMap::new()),
            state: Arc::new(Mutex::new(State {
                status: "idle",
                liveness: Liveness::Live,
                session_id: None,
                title: None,
                created_at_ms: now,
                last_active_at_ms: now,
            })),
            prompting: Arc::new(AtomicBool::new(false)),
        });
        let reader = me.clone();
        tokio::spawn(async move {
            let mut lines = BufReader::new(stdout).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if let Ok(v) = serde_json::from_str::<Value>(&line) {
                    reader.on_message(v).await;
                }
            }
            reader.on_exit();
        });

        let init = me
            .request(
                "initialize",
                json!({
                    "protocolVersion": 1,
                    "clientCapabilities": { "fs": { "readTextFile": false, "writeTextFile": false }, "terminal": false },
                    "clientInfo": { "name": "switchtether", "title": "SwitchTether", "version": env!("CARGO_PKG_VERSION") },
                }),
                Duration::from_secs(60),
            )
            .await;
        if let Err(e) = init {
            me.kill();
            anyhow::bail!("dsh ACP server did not initialize: {e}");
        }
        let cwd = me.options.cwd.to_string_lossy().to_string();
        let opened = match &me.options.resume_session {
            Some(sid) => me
                .request(
                    "session/resume",
                    json!({ "sessionId": sid, "cwd": cwd, "mcpServers": [] }),
                    Duration::from_secs(60),
                )
                .await
                .map(|_| sid.clone()),
            None => me
                .request(
                    "session/new",
                    json!({ "cwd": cwd, "mcpServers": [] }),
                    Duration::from_secs(60),
                )
                .await
                .and_then(|r| {
                    r.get("sessionId")
                        .and_then(Value::as_str)
                        .map(str::to_string)
                        .ok_or_else(|| json!({ "message": "no sessionId" }))
                }),
        };
        let sid = match opened {
            Ok(s) => s,
            Err(e) => {
                me.kill();
                anyhow::bail!("dsh could not open the session: {e}");
            }
        };
        me.state.lock().session_id = Some(sid.clone());
        let state = me.state.clone();
        tokio::spawn(follow_session_log(sid, me.log.clone(), move || {
            state.lock().liveness == Liveness::Live
        }));
        Ok(me)
    }

    async fn write_line(&self, v: &Value) -> ApiResult<()> {
        let mut g = self.stdin.lock().await;
        let w = g
            .as_mut()
            .ok_or_else(|| ApiError::conflict("the dsh process has exited"))?;
        let mut line = serde_json::to_vec(v).map_err(|e| ApiError::internal("encode", e))?;
        line.push(b'\n');
        w.write_all(&line)
            .await
            .map_err(|e| ApiError::internal("write to dsh", e))?;
        w.flush()
            .await
            .map_err(|e| ApiError::internal("write to dsh", e))
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
            Ok(Err(_)) => Err(json!({ "message": "dsh process exited" })),
            Err(_) => {
                self.replies.lock().remove(&id);
                Err(json!({ "message": format!("{method} timed out") }))
            }
        }
    }

    async fn on_message(&self, v: Value) {
        let method = v.get("method").and_then(Value::as_str).map(str::to_string);
        let id = v.get("id").cloned();
        match (method, id) {
            // Reply to one of our requests.
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
            // Request from the agent.
            (Some(m), Some(id)) => {
                if m == "session/request_permission" {
                    self.on_permission(id, v.get("params").cloned().unwrap_or(Value::Null));
                } else {
                    // We advertised no client file system / terminal: refuse anything else.
                    let _ = self
                        .write_line(&json!({ "jsonrpc": "2.0", "id": id,
                            "error": { "code": -32601, "message": "not supported by SwitchTether" } }))
                        .await;
                }
            }
            // Notification.
            (Some(m), None) => {
                if m == "session/update" {
                    let mut st = self.state.lock();
                    st.last_active_at_ms = crate::util::now_ms();
                    if st.status == "idle" {
                        st.status = "busy";
                    }
                }
            }
            (None, None) => {}
        }
    }

    fn on_permission(&self, rpc_id: Value, p: Value) {
        let options: Vec<Value> = p
            .get("options")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        let tool = p.get("toolCall").cloned().unwrap_or(Value::Null);
        let rid = format!(
            "dsh-perm-{}",
            rpc_id
                .as_u64()
                .map(|n| n.to_string())
                .unwrap_or_else(|| rpc_id.to_string())
        );
        let event_id = format!("perm:{rid}");
        let kind = match tool.get("kind").and_then(Value::as_str) {
            Some("execute") => "command",
            Some("edit" | "delete" | "move") => "file_change",
            Some("fetch") => "network",
            _ => "tool",
        };
        let input = tool.get("rawInput").cloned().unwrap_or(Value::Null);
        let title = tool
            .get("title")
            .and_then(Value::as_str)
            .map(str::to_string)
            .unwrap_or_else(|| "Permission request".into());
        let mut d = EventDraft::new(
            &event_id,
            "approval_request",
            crate::util::now_rfc3339(),
            json!({
                "requestId": rid, "kind": kind, "title": title,
                "toolName": tool.get("kind"), "command": input.get("command"),
                "cwd": self.options.cwd.to_string_lossy(), "input": input,
                "options": approval_options(&options), "resolved": false,
                "toolUseId": tool.get("toolCallId"),
            }),
        );
        d.parent_id = tool
            .get("toolCallId")
            .and_then(Value::as_str)
            .map(|c| format!("tc:{c}"));
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
        {
            let mut st = self.state.lock();
            st.liveness = Liveness::Ended;
            st.status = "completed";
        }
        for (_, tx) in self.replies.lock().drain() {
            let _ = tx.send(Err(json!({ "message": "process exited" })));
        }
        let pending: Vec<(String, PendingPermission)> = self.pending.lock().drain().collect();
        for (rid, p) in pending {
            self.resolved.lock().insert(rid, "agent".into());
            self.log.lock().update(&p.event_id, true, |e| {
                e.data["resolved"] = json!(true);
                e.data["decision"] = json!("cancelled");
                e.data["by"] = json!("agent");
            });
        }
    }
}

#[async_trait]
impl ManagedSession for ManagedDsh {
    fn agent(&self) -> &'static str {
        "dsh"
    }
    fn log(&self) -> Arc<Mutex<SessionLog>> {
        self.log.clone()
    }
    fn cwd(&self) -> PathBuf {
        self.options.cwd.clone()
    }
    fn native_session_id(&self) -> Option<String> {
        self.state.lock().session_id.clone()
    }
    fn pid(&self) -> Option<u32> {
        self.pid
    }
    fn status(&self) -> &'static str {
        let (live, fallback) = {
            let st = self.state.lock();
            (st.liveness == Liveness::Live, st.status)
        };
        if !live {
            return fallback;
        }
        if !self.pending.lock().is_empty() {
            return "waiting";
        }
        // Busy while our prompt is in flight or the log shows an open turn. A `session/update`
        // that arrives after a cancel no longer leaves the session marked busy.
        if self.prompting.load(Ordering::SeqCst) || super::log_turn_running(&self.log) {
            "busy"
        } else {
            "idle"
        }
    }
    fn liveness(&self) -> Liveness {
        self.state.lock().liveness
    }
    fn created_at_ms(&self) -> i64 {
        self.state.lock().created_at_ms
    }
    fn last_active_at_ms(&self) -> i64 {
        self.state.lock().last_active_at_ms
    }
    fn model(&self) -> Option<String> {
        None
    }
    fn title(&self) -> Option<String> {
        self.state.lock().title.clone()
    }
    fn has_pending_requests(&self) -> bool {
        !self.pending.lock().is_empty()
    }

    async fn send_message(&self, text: &str) -> ApiResult<()> {
        if self.state.lock().liveness != Liveness::Live {
            return Err(ApiError::conflict("the dsh process has exited"));
        }
        // ACP allows one prompt per session at a time.
        if self.prompting.swap(true, Ordering::SeqCst) {
            return Err(ApiError::conflict(
                "DeepSeek Harness is still working on the previous message; wait or interrupt it",
            ));
        }
        let sid = match self.state.lock().session_id.clone() {
            Some(s) => s,
            None => {
                self.prompting.store(false, Ordering::SeqCst);
                return Err(ApiError::conflict("the dsh session is not open yet"));
            }
        };
        {
            let mut st = self.state.lock();
            // Title from the user's own words, never from the attachment path block.
            let own = crate::agents::attachments::split(text).map_or(text.to_string(), |(t, _)| t);
            if st.title.is_none() && !own.trim().is_empty() {
                st.title = Some(own.chars().take(80).collect());
            }
            st.status = "busy";
            st.last_active_at_ms = crate::util::now_ms();
        }
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);
        let (tx, rx) = oneshot::channel();
        self.replies.lock().insert(id, tx);
        self.write_line(&json!({
            "jsonrpc": "2.0", "id": id, "method": "session/prompt",
            "params": { "sessionId": sid, "prompt": [{ "type": "text", "text": text }] },
        }))
        .await
        .inspect_err(|_| {
            self.replies.lock().remove(&id);
            self.prompting.store(false, Ordering::SeqCst);
        })?;
        // The prompt settles when the whole turn (and its sub-agents) is done; the phone's request
        // returns as soon as it is accepted.
        let state = self.state.clone();
        let prompting = self.prompting.clone();
        let log = self.log.clone();
        tokio::spawn(async move {
            let r = rx.await;
            prompting.store(false, Ordering::SeqCst);
            {
                let mut st = state.lock();
                if st.liveness == Liveness::Live && st.status != "waiting" {
                    st.status = "idle";
                }
                st.last_active_at_ms = crate::util::now_ms();
            }
            // The prompt settled (finished, cancelled or failed). Unless the log still shows the
            // turn open (its `turn/end` arrives with the next tail and publishes itself), say so
            // now: the status relay refreshes the phone's summary on this event.
            if !super::log_turn_running(&log) {
                log.lock().upsert(
                    EventDraft::new(
                        super::ROLLING_STATUS_ID,
                        "status",
                        crate::util::now_rfc3339(),
                        json!({ "state": "idle" }),
                    ),
                    true,
                );
            }
            if let Ok(Err(e)) = r {
                let msg = e
                    .get("message")
                    .and_then(Value::as_str)
                    .unwrap_or("the prompt failed")
                    .to_string();
                log.lock().upsert(
                    EventDraft::new(
                        format!("dsh-error:{}", crate::util::now_ms()),
                        "error",
                        crate::util::now_rfc3339(),
                        json!({ "message": msg }),
                    ),
                    true,
                );
            }
        });
        Ok(())
    }

    async fn interrupt(&self) -> ApiResult<()> {
        let sid = self
            .state
            .lock()
            .session_id
            .clone()
            .ok_or_else(|| ApiError::conflict("the dsh session is not open yet"))?;
        self.write_line(&json!({ "jsonrpc": "2.0", "method": "session/cancel", "params": { "sessionId": sid } }))
            .await
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
        let outcome = match decision {
            "abort" | "cancel" => json!({ "outcome": "cancelled" }),
            d => match option_for(&pending.options, d) {
                Some(option_id) => json!({ "outcome": "selected", "optionId": option_id }),
                None => {
                    self.pending.lock().insert(request_id.to_string(), pending);
                    return Err(ApiError::validation(format!("unknown decision '{d}'")));
                }
            },
        };
        self.write_line(
            &json!({ "jsonrpc": "2.0", "id": pending.rpc_id, "result": { "outcome": outcome } }),
        )
        .await?;
        self.resolved
            .lock()
            .insert(request_id.to_string(), by.to_string());
        let ev = self.log.lock().update(&pending.event_id, true, |e| {
            e.data["resolved"] = json!(true);
            e.data["decision"] = json!(decision);
            e.data["by"] = json!(by);
        });
        if self.pending.lock().is_empty() {
            self.state.lock().status = "busy";
        }
        if decision == "abort" {
            let _ = self.interrupt().await;
        }
        Ok(json!({ "resolved": true, "event": ev }))
    }

    fn kill(&self) {
        if let Some(mut c) = self.child.lock().take() {
            let _ = c.start_kill();
        }
    }
}
