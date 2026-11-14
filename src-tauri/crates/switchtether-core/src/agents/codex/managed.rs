//! SwitchTether-managed Codex sessions over a private `codex app-server`
//! (JSON-RPC, one JSON object per line on stdio).
//!
//! The app-server is SwitchTether's own process; the thread it creates is a
//! NEW thread (labelled `origin: managed`). It reads the user's normal
//! `~/.codex` config/auth but never modifies configuration. Live threads of
//! other Codex processes are never resumed here (Codex itself refuses with
//! "thread already has an active writer" — docs/research/CODEX_RUNTIME.md).

use std::collections::HashMap;
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use parking_lot::Mutex;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, Command};
use tokio::sync::oneshot;

use crate::agents::managed::ManagedSession;
use crate::agents::model::{diff_stats, preview, EventDraft, Liveness, PREVIEW_LIMIT};
use crate::agents::store::SessionLog;
use crate::error::{ApiError, ApiResult};

#[derive(Debug, Clone)]
pub struct ManagedCodexOptions {
    pub codex_bin: PathBuf,
    pub cwd: PathBuf,
    pub model: Option<String>,
    /// `on-request` (default) or `untrusted`.
    pub approval_policy: String,
    /// Continue an existing thread (`thread/resume`) instead of starting one.
    pub resume_thread: Option<String>,
}

struct PendingApproval {
    rpc_id: Value,
    method: String,
    params: Value,
    event_id: String,
}

struct State {
    status: &'static str,
    liveness: Liveness,
    thread_id: Option<String>,
    turn_id: Option<String>,
    model: Option<String>,
    title: Option<String>,
    created_at_ms: i64,
    last_active_at_ms: i64,
}

pub struct ManagedCodex {
    pub id: String,
    pub options: ManagedCodexOptions,
    log: Arc<Mutex<SessionLog>>,
    stdin: tokio::sync::Mutex<Option<ChildStdin>>,
    child: Mutex<Option<Child>>,
    next_id: AtomicU64,
    replies: Mutex<HashMap<u64, oneshot::Sender<Result<Value, Value>>>>,
    pending: Mutex<HashMap<String, PendingApproval>>,
    resolved: Mutex<HashMap<String, String>>,
    output: Mutex<HashMap<String, String>>,
    state: Mutex<State>,
    /// Attached mode: the thread lives in a Codex process the user started
    /// (Codex Desktop via switchtether-shim); requests go through `conn`.
    remote: Option<Arc<CodexConn>>,
}

/// Canonical JSON-RPC id key (numbers and strings never collide).
pub fn rpc_id_key(v: &Value) -> Option<String> {
    match v {
        Value::String(s) => Some(format!("s:{s}")),
        Value::Number(n) => Some(format!("n:{n}")),
        _ => None,
    }
}

/// One shim connection in front of a user-started `codex app-server`
/// (Codex Desktop). Several threads can be loaded on it at once.
pub struct CodexConn {
    tx: tokio::sync::mpsc::UnboundedSender<String>,
    tag: String,
    next: AtomicU64,
    replies: Mutex<HashMap<String, oneshot::Sender<Result<Value, Value>>>>,
    verdicts: Mutex<HashMap<String, oneshot::Sender<bool>>>,
    pub threads: Mutex<HashMap<String, Arc<ManagedCodex>>>,
    pub origin: &'static str,
    pub cwd: PathBuf,
}

impl CodexConn {
    pub fn new(
        tx: tokio::sync::mpsc::UnboundedSender<String>,
        origin: &'static str,
        cwd: PathBuf,
    ) -> Arc<Self> {
        Arc::new(Self {
            tx,
            tag: crate::util::new_id().chars().take(8).collect(),
            next: AtomicU64::new(1),
            replies: Mutex::new(HashMap::new()),
            verdicts: Mutex::new(HashMap::new()),
            threads: Mutex::new(HashMap::new()),
            origin,
            cwd,
        })
    }

    fn inject(&self, v: &Value) -> ApiResult<()> {
        self.tx
            .send(json!({ "t": "inject", "line": v.to_string() }).to_string())
            .map_err(|_| ApiError::conflict("the attached Codex process has disconnected"))
    }

    async fn request(&self, method: &str, params: Value, timeout: Duration) -> ApiResult<Value> {
        let id = format!(
            "st-{}-{}",
            self.tag,
            self.next.fetch_add(1, Ordering::Relaxed)
        );
        let (tx, rx) = oneshot::channel();
        self.replies.lock().insert(id.clone(), tx);
        self.inject(&json!({ "id": id, "method": method, "params": params }))?;
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(Ok(v))) => Ok(v),
            Ok(Ok(Err(e))) => Err(ApiError::bad_request(
                e.get("message")
                    .and_then(|m| m.as_str())
                    .unwrap_or("codex request failed")
                    .to_string(),
            )),
            _ => {
                self.replies.lock().remove(&id);
                Err(ApiError::internal(
                    "codex request",
                    format!("{method} timed out"),
                ))
            }
        }
    }

    fn parse(frame: &Value) -> Option<Value> {
        frame
            .get("line")
            .and_then(|l| l.as_str())
            .and_then(|l| serde_json::from_str::<Value>(l).ok())
    }

    /// Threads that a frame reveals: `(threadId, cwd)`.
    pub fn discover(&self, frame: &Value) -> Vec<(String, Option<String>)> {
        if frame.get("t").and_then(|t| t.as_str()) != Some("out") {
            return vec![];
        }
        let Some(v) = Self::parse(frame) else {
            return vec![];
        };
        let known = |id: &str| self.threads.lock().contains_key(id);
        let mut out = Vec::new();
        // Replies to thread/start | thread/resume | thread/fork (host or ours).
        if let Some(t) = v.pointer("/result/thread") {
            if let Some(id) = s(t, "id") {
                if !known(&id) {
                    out.push((id, s(t, "cwd").or(s(v.get("result").unwrap(), "cwd"))));
                }
            }
        }
        if s(&v, "method").as_deref() == Some("thread/started") {
            if let Some(t) = v.pointer("/params/thread") {
                if let Some(id) = s(t, "id") {
                    if !known(&id) {
                        out.push((id, s(t, "cwd")));
                    }
                }
            }
        } else if let Some(id) = v.pointer("/params/threadId").and_then(|x| x.as_str()) {
            if !known(id) && !out.iter().any(|(t, _)| t == id) {
                out.push((id.to_string(), None));
            }
        }
        out
    }

    fn thread_of(&self, v: &Value) -> Option<Arc<ManagedCodex>> {
        let id = v.pointer("/params/threadId").and_then(|x| x.as_str())?;
        self.threads.lock().get(id).cloned()
    }

    pub async fn on_frame(&self, frame: Value) {
        match frame.get("t").and_then(|t| t.as_str()) {
            Some("out") => {
                let Some(v) = Self::parse(&frame) else { return };
                let method = s(&v, "method");
                match (v.get("id"), method) {
                    (Some(id), None) => {
                        // Reply to one of our requests.
                        if let Some(id) = id.as_str() {
                            if let Some(tx) = self.replies.lock().remove(id) {
                                let r = match v.get("error") {
                                    Some(e) => Err(e.clone()),
                                    None => Ok(v.get("result").cloned().unwrap_or(Value::Null)),
                                };
                                let _ = tx.send(r);
                            }
                        }
                    }
                    (Some(id), Some(m)) => {
                        if let Some(t) = self.thread_of(&v) {
                            t.on_server_request(
                                id.clone(),
                                &m,
                                v.get("params").cloned().unwrap_or(Value::Null),
                            )
                            .await;
                        }
                    }
                    (None, Some(m)) => {
                        if let Some(t) = self.thread_of(&v) {
                            t.on_notification(&m, v.get("params").cloned().unwrap_or(Value::Null));
                        }
                    }
                    _ => {}
                }
            }
            Some("in") => {
                // Codex Desktop answered an approval itself.
                let Some(v) = Self::parse(&frame) else { return };
                if v.get("method").is_none()
                    && (v.get("result").is_some() || v.get("error").is_some())
                {
                    if let Some(id) = v.get("id") {
                        let rid = format!(
                            "cx-{}",
                            match id {
                                Value::String(x) => x.clone(),
                                other => other.to_string(),
                            }
                        );
                        let threads: Vec<_> = self.threads.lock().values().cloned().collect();
                        for t in threads {
                            t.resolved_elsewhere(&rid, "desktop");
                        }
                    }
                }
            }
            Some(kind @ ("accepted" | "rejected")) => {
                if let Some(key) =
                    Self::parse(&frame).and_then(|v| v.get("id").and_then(rpc_id_key))
                {
                    if let Some(tx) = self.verdicts.lock().remove(&key) {
                        let _ = tx.send(kind == "accepted");
                    }
                }
            }
            Some("exit") => self.on_disconnect(),
            _ => {}
        }
    }

    pub fn on_disconnect(&self) {
        let threads: Vec<_> = self.threads.lock().values().cloned().collect();
        for t in threads {
            if t.liveness() == Liveness::Live {
                t.on_exit();
            }
        }
        for (_, tx) in self.replies.lock().drain() {
            let _ = tx.send(Err(json!({ "message": "process exited" })));
        }
    }
}

fn s(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(|x| x.as_str()).map(|x| x.to_string())
}

impl ManagedCodex {
    pub async fn spawn(
        id: String,
        options: ManagedCodexOptions,
        log: Arc<Mutex<SessionLog>>,
    ) -> anyhow::Result<Arc<Self>> {
        let mut cmd = Command::new(&options.codex_bin);
        cmd.arg("app-server")
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
            log,
            stdin: tokio::sync::Mutex::new(stdin),
            child: Mutex::new(Some(child)),
            next_id: AtomicU64::new(1),
            replies: Mutex::new(HashMap::new()),
            pending: Mutex::new(HashMap::new()),
            resolved: Mutex::new(HashMap::new()),
            output: Mutex::new(HashMap::new()),
            state: Mutex::new(State {
                status: "idle",
                liveness: Liveness::Live,
                thread_id: None,
                turn_id: None,
                model: options.model.clone(),
                title: None,
                created_at_ms: now,
                last_active_at_ms: now,
            }),
            options,
            remote: None,
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
                json!({ "clientInfo": { "name": "switchtether", "title": "SwitchTether", "version": env!("CARGO_PKG_VERSION") } }),
                Duration::from_secs(30),
            )
            .await;
        if let Err(e) = init {
            me.kill();
            anyhow::bail!("codex app-server did not initialize: {e}");
        }
        me.notify("initialized", None).await.ok();
        let mut params = json!({
            "cwd": me.options.cwd.to_string_lossy(),
            "approvalPolicy": me.options.approval_policy,
            "sandbox": "workspace-write",
        });
        if let Some(m) = &me.options.model {
            params["model"] = json!(m);
        }
        let (method, params) = match &me.options.resume_thread {
            Some(t) => {
                let mut p = params;
                p["threadId"] = json!(t);
                ("thread/resume", p)
            }
            None => ("thread/start", params),
        };
        let started = match me.request(method, params, Duration::from_secs(60)).await {
            Ok(v) => v,
            Err(e) => {
                me.kill();
                anyhow::bail!("{method} failed: {}", e.message);
            }
        };
        {
            let mut st = me.state.lock();
            st.thread_id = started.get("thread").and_then(|t| s(t, "id"));
            if let Some(m) = s(&started, "model") {
                st.model = Some(m);
            }
        }
        me.system(&format!(
            "Codex thread started{}",
            me.state
                .lock()
                .model
                .as_deref()
                .map(|m| format!(" · {m}"))
                .unwrap_or_default()
        ));
        Ok(me)
    }

    /// A thread loaded in a user-started Codex process (attached mode).
    pub fn attached(
        id: String,
        conn: Arc<CodexConn>,
        thread_id: String,
        cwd: PathBuf,
        log: Arc<Mutex<SessionLog>>,
    ) -> Arc<Self> {
        let now = crate::util::now_ms();
        Arc::new(Self {
            id,
            options: ManagedCodexOptions {
                codex_bin: PathBuf::new(),
                cwd,
                model: None,
                approval_policy: String::new(),
                resume_thread: None,
            },
            log,
            stdin: tokio::sync::Mutex::new(None),
            child: Mutex::new(None),
            next_id: AtomicU64::new(1),
            replies: Mutex::new(HashMap::new()),
            pending: Mutex::new(HashMap::new()),
            resolved: Mutex::new(HashMap::new()),
            output: Mutex::new(HashMap::new()),
            state: Mutex::new(State {
                status: "idle",
                liveness: Liveness::Live,
                thread_id: Some(thread_id),
                turn_id: None,
                model: None,
                title: None,
                created_at_ms: now,
                last_active_at_ms: now,
            }),
            remote: Some(conn),
        })
    }

    pub fn origin_name(&self) -> &'static str {
        self.remote.as_ref().map(|c| c.origin).unwrap_or("managed")
    }

    /// A pending request was answered by another client.
    fn resolved_elsewhere(&self, rid: &str, by: &str) {
        let removed = self.pending.lock().remove(rid);
        if let Some(pa) = removed {
            self.resolved.lock().insert(rid.to_string(), by.into());
            self.log.lock().update(&pa.event_id, true, |e| {
                e.data["resolved"] = json!(true);
                e.data["by"] = json!(by);
            });
            if self.pending.lock().is_empty() {
                self.set_status("busy");
                self.publish_status("running", None);
            }
        }
    }

    fn system(&self, text: &str) {
        self.log.lock().upsert(
            EventDraft::new(
                format!("sys:{}", crate::util::new_id()),
                "system",
                crate::util::now_rfc3339(),
                json!({ "subtype": "info", "text": text }),
            ),
            true,
        );
    }

    fn publish_status(&self, state: &str, detail: Option<&str>) {
        let mut d = EventDraft::new(
            "status:current",
            "status",
            crate::util::now_rfc3339(),
            json!({ "state": state, "detail": detail }),
        );
        d.id = "status:current".into();
        self.log.lock().upsert(d, true);
    }

    fn set_status(&self, status: &'static str) {
        let mut st = self.state.lock();
        st.status = status;
        st.last_active_at_ms = crate::util::now_ms();
    }

    async fn write(&self, v: &Value) -> ApiResult<()> {
        if let Some(c) = &self.remote {
            return c.inject(v);
        }
        let mut g = self.stdin.lock().await;
        let Some(stdin) = g.as_mut() else {
            return Err(ApiError::conflict("codex app-server has exited"));
        };
        let mut line = v.to_string();
        line.push('\n');
        stdin
            .write_all(line.as_bytes())
            .await
            .map_err(|e| ApiError::internal("codex stdin", e))?;
        stdin
            .flush()
            .await
            .map_err(|e| ApiError::internal("codex stdin", e))
    }

    async fn notify(&self, method: &str, params: Option<Value>) -> ApiResult<()> {
        let mut msg = json!({ "method": method });
        if let Some(p) = params {
            msg["params"] = p;
        }
        self.write(&msg).await
    }

    async fn request(&self, method: &str, params: Value, timeout: Duration) -> ApiResult<Value> {
        if let Some(c) = &self.remote {
            return c.request(method, params, timeout).await;
        }
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = oneshot::channel();
        self.replies.lock().insert(id, tx);
        self.write(&json!({ "id": id, "method": method, "params": params }))
            .await?;
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(Ok(v))) => Ok(v),
            Ok(Ok(Err(e))) => Err(ApiError::bad_request(
                e.get("message")
                    .and_then(|m| m.as_str())
                    .unwrap_or("codex request failed")
                    .to_string(),
            )),
            _ => {
                self.replies.lock().remove(&id);
                Err(ApiError::internal(
                    "codex request",
                    format!("{method} timed out"),
                ))
            }
        }
    }

    fn thread_id(&self) -> ApiResult<String> {
        self.state
            .lock()
            .thread_id
            .clone()
            .ok_or_else(|| ApiError::conflict("codex thread not started"))
    }

    async fn on_message(&self, v: Value) {
        let has_id = v.get("id").is_some();
        let method = s(&v, "method");
        match (has_id, method) {
            (true, None) => {
                let id = v.get("id").and_then(|i| i.as_u64()).unwrap_or(0);
                if let Some(tx) = self.replies.lock().remove(&id) {
                    let r = match v.get("error") {
                        Some(e) => Err(e.clone()),
                        None => Ok(v.get("result").cloned().unwrap_or(Value::Null)),
                    };
                    let _ = tx.send(r);
                }
            }
            (true, Some(m)) => {
                self.on_server_request(
                    v.get("id").cloned().unwrap_or(Value::Null),
                    &m,
                    v.get("params").cloned().unwrap_or(Value::Null),
                )
                .await
            }
            (false, Some(m)) => {
                self.on_notification(&m, v.get("params").cloned().unwrap_or(Value::Null))
            }
            _ => {}
        }
    }

    pub(crate) async fn on_server_request(&self, rpc_id: Value, method: &str, params: Value) {
        let ts = crate::util::now_rfc3339();
        let request_id = format!(
            "cx-{}",
            match &rpc_id {
                Value::String(x) => x.clone(),
                other => other.to_string(),
            }
        );
        let event_id = format!("req:{request_id}");
        let draft = match method {
            "item/commandExecution/requestApproval" => EventDraft::new(
                &event_id,
                "approval_request",
                &ts,
                json!({
                    "requestId": request_id, "kind": "command",
                    "title": s(&params, "command").unwrap_or_else(|| "Run command".into()),
                    "detail": params.get("reason"), "command": params.get("command"), "cwd": params.get("cwd"),
                    "toolName": "shell", "input": params,
                    "options": [
                        { "id": "allow", "label": "Allow" },
                        { "id": "allow_session", "label": "Allow for session" },
                        { "id": "deny", "label": "Deny" },
                        { "id": "abort", "label": "Deny and stop" }
                    ],
                    "resolved": false,
                }),
            ),
            "item/fileChange/requestApproval" => EventDraft::new(
                &event_id,
                "approval_request",
                &ts,
                json!({
                    "requestId": request_id, "kind": "file_change", "title": "Apply file changes",
                    "detail": params.get("reason"), "toolName": "apply_patch", "input": params,
                    "options": [
                        { "id": "allow", "label": "Allow" },
                        { "id": "allow_session", "label": "Allow for session" },
                        { "id": "deny", "label": "Deny" },
                        { "id": "abort", "label": "Deny and stop" }
                    ],
                    "resolved": false,
                }),
            ),
            _ if self.remote.is_some() => {
                // Codex Desktop owns the connection and answers these itself.
                return;
            }
            _ => {
                // Elicitations / permission profiles / dynamic tools are not
                // supported from the phone yet: answer explicitly so the turn
                // does not hang.
                let reply = match method {
                    "mcpServer/elicitation/request" => {
                        json!({ "id": rpc_id, "result": { "action": "decline" } })
                    }
                    _ => {
                        json!({ "id": rpc_id, "error": { "code": -32601, "message": "not supported by SwitchTether" } })
                    }
                };
                let _ = self.write(&reply).await;
                self.system(&format!("Declined unsupported request: {method}"));
                return;
            }
        };
        self.pending.lock().insert(
            request_id,
            PendingApproval {
                rpc_id,
                method: method.to_string(),
                params,
                event_id,
            },
        );
        self.log.lock().upsert(draft, true);
        self.set_status("waiting");
        self.publish_status("waiting_approval", None);
    }

    fn item_events(&self, item: &Value, turn_id: Option<&str>, done: bool) -> Vec<EventDraft> {
        let ts = crate::util::now_rfc3339();
        let id = s(item, "id").unwrap_or_default();
        let eid = format!("cx-item-{id}");
        let mut out = Vec::new();
        let mut push = |mut d: EventDraft| {
            d.turn_id = turn_id.map(|t| t.to_string());
            out.push(d);
        };
        match item.get("type").and_then(|t| t.as_str()) {
            Some("userMessage") => {
                let text: Vec<String> = item
                    .get("content")
                    .and_then(|c| c.as_array())
                    .map(|a| a.iter().filter_map(|x| s(x, "text")).collect())
                    .unwrap_or_default();
                push(EventDraft::new(
                    eid,
                    "user_message",
                    ts,
                    json!({ "text": text.join("\n"), "images": 0, "source": "phone" }),
                ));
            }
            Some("agentMessage") => {
                let mut d = EventDraft::new(
                    eid,
                    "assistant_message",
                    ts,
                    json!({ "text": s(item, "text").unwrap_or_default(), "model": self.state.lock().model }),
                );
                d.streaming = !done;
                push(d);
            }
            Some("reasoning") => {
                let content: Vec<String> = item
                    .get("content")
                    .and_then(|c| c.as_array())
                    .map(|a| {
                        a.iter()
                            .filter_map(|x| x.as_str().map(String::from))
                            .collect()
                    })
                    .unwrap_or_default();
                let summary: Vec<String> = item
                    .get("summary")
                    .and_then(|c| c.as_array())
                    .map(|a| {
                        a.iter()
                            .filter_map(|x| x.as_str().map(String::from))
                            .collect()
                    })
                    .unwrap_or_default();
                let (text, is_summary) = if content.is_empty() {
                    (summary.join("\n\n"), true)
                } else {
                    (content.join("\n\n"), false)
                };
                if !text.trim().is_empty() {
                    push(EventDraft::new(
                        eid,
                        "reasoning",
                        ts,
                        json!({ "text": text, "summary": is_summary }),
                    ));
                }
            }
            Some("plan") => {
                let text = s(item, "text").unwrap_or_default();
                let items: Vec<Value> = text
                    .lines()
                    .filter(|l| !l.trim().is_empty())
                    .map(|l| json!({ "text": l.trim_start_matches(['-', '*', ' ']).trim(), "status": "pending" }))
                    .collect();
                push(EventDraft::new(
                    eid,
                    "plan",
                    ts,
                    json!({ "items": items, "text": text }),
                ));
            }
            Some("commandExecution") => {
                let status = match item.get("status").and_then(|x| x.as_str()) {
                    Some("completed") => "completed",
                    Some("failed") => "failed",
                    Some("declined") => "denied",
                    _ => "running",
                };
                let cmd = s(item, "command").unwrap_or_default();
                push(EventDraft::new(
                    &eid,
                    "tool_call",
                    &ts,
                    json!({
                        "callId": id, "name": "shell", "title": preview(cmd.lines().next().unwrap_or(""), 200).0,
                        "category": "shell", "input": { "command": cmd, "cwd": item.get("cwd") }, "status": status,
                    }),
                ));
                let streamed = self.output.lock().get(&id).cloned();
                let text = s(item, "aggregatedOutput").or(streamed).unwrap_or_default();
                if done || !text.is_empty() {
                    let (p, truncated) = preview(&text, PREVIEW_LIMIT);
                    let mut d = EventDraft::new(
                        format!("res:{id}"),
                        "tool_result",
                        &ts,
                        json!({
                            "callId": id, "isError": status == "failed", "text": p, "truncated": truncated,
                            "bytes": text.len(), "exitCode": item.get("exitCode"),
                        }),
                    );
                    d.streaming = !done;
                    if truncated {
                        d.payload = Some(text);
                    }
                    push(d);
                }
            }
            Some("fileChange") => {
                let status = match item.get("status").and_then(|x| x.as_str()) {
                    Some("completed") => "completed",
                    Some("failed") => "failed",
                    Some("declined") => "denied",
                    _ => "running",
                };
                let changes = item
                    .get("changes")
                    .and_then(|c| c.as_array())
                    .cloned()
                    .unwrap_or_default();
                push(EventDraft::new(
                    &eid,
                    "tool_call",
                    &ts,
                    json!({
                        "callId": id, "name": "apply_patch",
                        "title": format!("{} file(s)", changes.len()), "category": "edit",
                        "input": { "files": changes.iter().filter_map(|c| s(c, "path")).collect::<Vec<_>>() },
                        "status": status,
                    }),
                ));
                for (i, c) in changes.iter().enumerate() {
                    let diff = s(c, "diff").unwrap_or_default();
                    let (a, d) = diff_stats(&diff);
                    let kind = match c
                        .get("kind")
                        .and_then(|k| k.get("type"))
                        .and_then(|t| t.as_str())
                    {
                        Some("add") => "add",
                        Some("delete") => "delete",
                        _ => "modify",
                    };
                    let (dp, trunc) = preview(&diff, 256 * 1024);
                    push(EventDraft::new(
                        format!("fc:{id}:{i}"),
                        "file_change",
                        &ts,
                        json!({
                            "callId": id, "path": s(c, "path"), "change": kind, "diff": dp,
                            "additions": a, "deletions": d, "truncated": trunc,
                        }),
                    ));
                }
            }
            Some("mcpToolCall") => {
                let status = match item.get("status").and_then(|x| x.as_str()) {
                    Some("completed") => "completed",
                    Some("failed") => "failed",
                    _ => "running",
                };
                let server = s(item, "server").unwrap_or_default();
                let tool = s(item, "tool").unwrap_or_default();
                push(EventDraft::new(
                    &eid,
                    "tool_call",
                    &ts,
                    json!({
                        "callId": id, "name": format!("mcp__{server}__{tool}"), "title": format!("{server} · {tool}"),
                        "category": "mcp", "input": item.get("arguments"), "status": status, "server": server, "tool": tool,
                    }),
                ));
                if done {
                    let text = item
                        .get("result")
                        .or(item.get("error"))
                        .map(|r| r.to_string())
                        .unwrap_or_default();
                    let (p, truncated) = preview(&text, PREVIEW_LIMIT);
                    push(EventDraft::new(
                        format!("res:{id}"),
                        "tool_result",
                        &ts,
                        json!({
                            "callId": id, "isError": status == "failed", "text": p, "truncated": truncated, "bytes": text.len(),
                        }),
                    ));
                }
            }
            Some("webSearch") => push(EventDraft::new(
                eid,
                "tool_call",
                ts,
                json!({
                    "callId": id, "name": "web_search", "title": s(item, "query").unwrap_or_else(|| "web search".into()),
                    "category": "web", "input": item, "status": if done { "completed" } else { "running" },
                }),
            )),
            Some("contextCompaction") => push(EventDraft::new(
                eid,
                "system",
                ts,
                json!({ "subtype": "compacted", "text": "Conversation compacted" }),
            )),
            Some(other) => push(EventDraft::new(
                eid,
                "system",
                ts,
                json!({ "subtype": other, "text": format!("[{other}]") }),
            )),
            None => {}
        }
        out
    }

    pub(crate) fn on_notification(&self, method: &str, p: Value) {
        let turn_id = s(&p, "turnId");
        match method {
            "turn/started" => {
                let tid = p.get("turn").and_then(|t| s(t, "id"));
                self.state.lock().turn_id = tid;
                self.set_status("busy");
                self.publish_status("running", None);
            }
            "turn/completed" => {
                let status = p
                    .get("turn")
                    .and_then(|t| s(t, "status"))
                    .unwrap_or_default();
                self.state.lock().turn_id = None;
                self.set_status("idle");
                self.publish_status(
                    match status.as_str() {
                        "interrupted" => "interrupted",
                        "failed" => "error",
                        _ => "idle",
                    },
                    None,
                );
            }
            "item/started" | "item/completed" => {
                if let Some(item) = p.get("item") {
                    let done = method == "item/completed";
                    let drafts = self.item_events(item, turn_id.as_deref(), done);
                    let mut log = self.log.lock();
                    for d in drafts {
                        log.upsert(d, true);
                    }
                }
            }
            "item/agentMessage/delta" => {
                let (Some(item_id), Some(delta)) = (s(&p, "itemId"), s(&p, "delta")) else {
                    return;
                };
                let eid = format!("cx-item-{item_id}");
                let mut log = self.log.lock();
                let existing = log
                    .get(&eid)
                    .and_then(|e| e.data["text"].as_str().map(String::from))
                    .unwrap_or_default();
                let mut d = EventDraft::new(
                    eid,
                    "assistant_message",
                    crate::util::now_rfc3339(),
                    json!({ "text": existing + &delta }),
                );
                d.streaming = true;
                d.turn_id = turn_id;
                log.upsert(d, true);
            }
            "item/commandExecution/outputDelta" => {
                let (Some(item_id), Some(delta)) = (s(&p, "itemId"), s(&p, "delta")) else {
                    return;
                };
                let mut out = self.output.lock();
                let buf = out.entry(item_id).or_default();
                if buf.len() < 4 * 1024 * 1024 {
                    buf.push_str(&delta);
                }
            }
            "turn/plan/updated" => {
                let items: Vec<Value> = p
                    .get("plan")
                    .and_then(|x| x.as_array())
                    .map(|a| {
                        a.iter()
                            .map(|i| json!({ "text": i.get("step"), "status": i.get("status") }))
                            .collect()
                    })
                    .unwrap_or_default();
                let mut d = EventDraft::new(
                    format!("plan:{}", turn_id.clone().unwrap_or_default()),
                    "plan",
                    crate::util::now_rfc3339(),
                    json!({ "items": items }),
                );
                d.turn_id = turn_id;
                self.log.lock().upsert(d, true);
            }
            "thread/tokenUsage/updated" => {
                let usage = p.get("tokenUsage").cloned().unwrap_or(p.clone());
                let last = usage.get("last").cloned().unwrap_or(Value::Null);
                let d = EventDraft::new(
                    format!("usage:{}", turn_id.clone().unwrap_or_default()),
                    "usage",
                    crate::util::now_rfc3339(),
                    json!({
                        "inputTokens": last.get("inputTokens"), "outputTokens": last.get("outputTokens"),
                        "cacheReadTokens": last.get("cachedInputTokens"),
                    }),
                );
                self.log.lock().upsert(d, true);
            }
            "thread/name/updated" => {
                self.state.lock().title = s(&p, "name").or(s(&p, "threadName"));
            }
            "serverRequest/resolved" => {
                // Another client (or Codex itself) resolved a request.
                let rid = p.get("requestId").map(|r| match r {
                    Value::String(x) => format!("cx-{x}"),
                    other => format!("cx-{other}"),
                });
                if let Some(rid) = rid {
                    if let Some(pa) = self.pending.lock().remove(&rid) {
                        self.resolved.lock().insert(rid, "other".into());
                        self.log.lock().update(&pa.event_id, true, |e| {
                            e.data["resolved"] = json!(true);
                            e.data["by"] = json!("other");
                        });
                    }
                }
            }
            "error" => {
                let msg = p
                    .get("error")
                    .and_then(|e| s(e, "message"))
                    .or(s(&p, "message"))
                    .unwrap_or_else(|| p.to_string());
                let msg = crate::redact::redact_text(&msg);
                let will_retry = p
                    .get("willRetry")
                    .and_then(|w| w.as_bool())
                    .unwrap_or(false);
                let turn = turn_id.clone().unwrap_or_default();
                let draft = if will_retry {
                    // Transient: one rolling notice per turn instead of an error per attempt.
                    EventDraft::new(
                        format!("retry:{turn}"),
                        "system",
                        crate::util::now_rfc3339(),
                        json!({ "subtype": "retry", "text": msg }),
                    )
                } else {
                    EventDraft::new(
                        format!("err:{}", crate::util::new_id()),
                        "error",
                        crate::util::now_rfc3339(),
                        json!({ "message": msg }),
                    )
                };
                self.log.lock().upsert(draft, true);
            }
            _ => {}
        }
    }

    pub(crate) fn on_exit(&self) {
        {
            let mut st = self.state.lock();
            st.liveness = Liveness::Ended;
            st.status = "completed";
        }
        for (_, tx) in self.replies.lock().drain() {
            let _ = tx.send(Err(json!({ "message": "process exited" })));
        }
        let pending: Vec<(String, PendingApproval)> = self.pending.lock().drain().collect();
        for (rid, p) in pending {
            self.resolved.lock().insert(rid, "agent".into());
            self.log.lock().update(&p.event_id, true, |e| {
                e.data["resolved"] = json!(true);
                e.data["decision"] = json!("cancelled");
                e.data["by"] = json!("agent");
            });
        }
        self.publish_status("completed", Some("codex app-server exited"));
    }
}

#[async_trait]
impl ManagedSession for ManagedCodex {
    fn agent(&self) -> &'static str {
        "codex"
    }
    fn log(&self) -> Arc<Mutex<SessionLog>> {
        self.log.clone()
    }
    fn cwd(&self) -> PathBuf {
        self.options.cwd.clone()
    }
    fn native_session_id(&self) -> Option<String> {
        self.state.lock().thread_id.clone()
    }
    fn status(&self) -> &'static str {
        self.state.lock().status
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
        self.state.lock().model.clone()
    }
    fn title(&self) -> Option<String> {
        self.state.lock().title.clone()
    }
    fn has_pending_requests(&self) -> bool {
        !self.pending.lock().is_empty()
    }

    async fn send_message(&self, text: &str) -> ApiResult<()> {
        if self.liveness() != Liveness::Live {
            return Err(ApiError::conflict("codex app-server has exited"));
        }
        let thread_id = self.thread_id()?;
        let input = json!([{ "type": "text", "text": text, "text_elements": [] }]);
        let current_turn = self.state.lock().turn_id.clone();
        match current_turn {
            // A turn is running: steer it with additional input.
            Some(turn) => {
                self.request(
                    "turn/steer",
                    json!({ "threadId": thread_id, "expectedTurnId": turn, "input": input }),
                    Duration::from_secs(30),
                )
                .await?;
            }
            None => {
                self.request(
                    "turn/start",
                    json!({ "threadId": thread_id, "input": input }),
                    Duration::from_secs(60),
                )
                .await?;
                self.set_status("busy");
            }
        }
        Ok(())
    }

    async fn interrupt(&self) -> ApiResult<()> {
        let thread_id = self.thread_id()?;
        let Some(turn) = self.state.lock().turn_id.clone() else {
            return Err(ApiError::conflict("no turn is running"));
        };
        self.request(
            "turn/interrupt",
            json!({ "threadId": thread_id, "turnId": turn }),
            Duration::from_secs(15),
        )
        .await
        .map(|_| ())
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
        let codex_decision = match decision {
            "allow" => "accept",
            "allow_session" => "acceptForSession",
            "deny" => "decline",
            "abort" => "cancel",
            other => return Err(ApiError::validation(format!("unknown decision '{other}'"))),
        };
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
        let verdict = match (&self.remote, rpc_id_key(&pending.rpc_id)) {
            (Some(c), Some(key)) => {
                let (tx, rx) = oneshot::channel();
                c.verdicts.lock().insert(key, tx);
                Some(rx)
            }
            _ => None,
        };
        self.write(&json!({ "id": pending.rpc_id, "result": { "decision": codex_decision } }))
            .await?;
        if let Some(rx) = verdict {
            // Codex Desktop may have answered at the same moment: the shim decides.
            if let Ok(Ok(false)) = tokio::time::timeout(Duration::from_secs(5), rx).await {
                self.resolved
                    .lock()
                    .insert(request_id.to_string(), "desktop".into());
                return Err(ApiError::conflict(
                    "this request was already answered on the computer",
                )
                .with_details(json!({ "resolvedBy": "desktop" })));
            }
        }
        let _ = (&pending.method, &pending.params);
        let ev = self.log.lock().update(&pending.event_id, true, |e| {
            e.data["resolved"] = json!(true);
            e.data["decision"] = json!(decision);
            e.data["by"] = json!(by);
        });
        if self.pending.lock().is_empty() {
            self.set_status("busy");
            self.publish_status("running", None);
        }
        Ok(json!({ "requestId": request_id, "decision": decision, "event": ev }))
    }

    fn kill(&self) {
        // Never kill a process the user started.
        if self.remote.is_some() {
            return;
        }
        if let Some(mut c) = self.child.lock().take() {
            let _ = c.start_kill();
        }
    }
    fn origin(&self) -> &'static str {
        self.origin_name()
    }
}

/// Locate the `codex` executable.
pub fn find_codex_bin() -> Option<PathBuf> {
    if let Some(p) = crate::util::which("codex") {
        return Some(p);
    }
    let home = dirs::home_dir()?;
    let candidates = if cfg!(windows) {
        vec![home
            .join("AppData")
            .join("Roaming")
            .join("npm")
            .join("codex.cmd")]
    } else {
        vec![
            PathBuf::from("/opt/homebrew/bin/codex"),
            PathBuf::from("/usr/local/bin/codex"),
            home.join(".local").join("bin").join("codex"),
        ]
    };
    candidates.into_iter().find(|p| p.is_file())
}
