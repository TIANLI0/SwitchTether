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
    /// Subagent threadId -> parent threadId (routed into parent session, never listed as top-level).
    pub subagent_parents: Mutex<HashMap<String, String>>,
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
            subagent_parents: Mutex::new(HashMap::new()),
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

    fn subagent_parent_id(t: &Value) -> Option<String> {
        s(t, "parentThreadId").or_else(|| {
            t.pointer("/source/subagent/thread_spawn/parent_thread_id")
                .or_else(|| t.pointer("/source/subAgent/thread_spawn/parent_thread_id"))
                .and_then(|x| x.as_str())
                .map(String::from)
        })
    }

    /// Threads that a frame reveals: `(threadId, cwd)`.
    /// Subagent threads (`parentThreadId` set) are linked to their parent thread instead of
    /// creating separate top-level sessions.
    pub fn discover(&self, frame: &Value) -> Vec<(String, Option<String>)> {
        if frame.get("t").and_then(|t| t.as_str()) != Some("out") {
            return vec![];
        }
        let Some(v) = Self::parse(frame) else {
            return vec![];
        };
        let known = |id: &str| {
            self.threads.lock().contains_key(id) || self.subagent_parents.lock().contains_key(id)
        };
        let mut out = Vec::new();
        // Replies to thread/start | thread/resume | thread/fork (host or ours).
        if let Some(t) = v.pointer("/result/thread") {
            if let Some(id) = s(t, "id") {
                if let Some(parent) = Self::subagent_parent_id(t) {
                    self.subagent_parents.lock().insert(id, parent);
                } else if !known(&id) {
                    out.push((id, s(t, "cwd").or(s(v.get("result").unwrap(), "cwd"))));
                }
            }
        }
        if s(&v, "method").as_deref() == Some("thread/started") {
            if let Some(t) = v.pointer("/params/thread") {
                if let Some(id) = s(t, "id") {
                    if let Some(parent) = Self::subagent_parent_id(t) {
                        self.subagent_parents
                            .lock()
                            .insert(id.clone(), parent.clone());
                        if let Some(parent_session) = self.threads.lock().get(&parent).cloned() {
                            parent_session.on_subagent_started(&id, t);
                        }
                    } else if !known(&id) {
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

    fn thread_of(&self, v: &Value) -> Option<(Arc<ManagedCodex>, Option<String>)> {
        let id = v.pointer("/params/threadId").and_then(|x| x.as_str())?;
        if let Some(t) = self.threads.lock().get(id).cloned() {
            return Some((t, None));
        }
        let mut cur = id.to_string();
        let mut hops = 0;
        let parents = self.subagent_parents.lock();
        while let Some(p) = parents.get(&cur) {
            hops += 1;
            if hops > 8 {
                break;
            }
            if let Some(t) = self.threads.lock().get(p).cloned() {
                return Some((t, Some(id.to_string())));
            }
            cur = p.clone();
        }
        None
    }

    pub async fn on_frame(&self, frame: Value) {
        match frame.get("t").and_then(|t| t.as_str()) {
            Some("out") => {
                let Some(v) = Self::parse(&frame) else { return };
                let method = s(&v, "method");
                match (v.get("id"), method) {
                    (Some(id), None) => {
                        if let Some(thread) = v.pointer("/result/thread") {
                            if let Some(t) =
                                s(thread, "id").and_then(|id| self.threads.lock().get(&id).cloned())
                            {
                                t.on_thread_metadata(thread);
                            }
                        }
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
                        if let Some((t, subagent_tid)) = self.thread_of(&v) {
                            t.on_server_request_with_parent(
                                id.clone(),
                                &m,
                                v.get("params").cloned().unwrap_or(Value::Null),
                                subagent_tid.map(|tid| format!("cx-sa-{tid}")),
                            )
                            .await;
                        }
                    }
                    (None, Some(m)) => {
                        if m == "thread/started" {
                            if let Some(thread) = v.pointer("/params/thread") {
                                if let Some(t) = s(thread, "id")
                                    .and_then(|id| self.threads.lock().get(&id).cloned())
                                {
                                    t.on_thread_metadata(thread);
                                }
                            }
                        }
                        if let Some((t, subagent_tid)) = self.thread_of(&v) {
                            if let Some(tid) = subagent_tid {
                                t.on_subagent_notification(
                                    &tid,
                                    &m,
                                    v.get("params").cloned().unwrap_or(Value::Null),
                                );
                            } else {
                                t.on_notification(
                                    &m,
                                    v.get("params").cloned().unwrap_or(Value::Null),
                                );
                            }
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

fn elicitation_to_questions(params: &Value) -> Vec<Value> {
    let mode = s(params, "mode").unwrap_or_default();
    let msg = s(params, "message").unwrap_or_else(|| "Input requested".into());
    let server = s(params, "serverName").unwrap_or_default();
    if mode == "url" {
        let url = s(params, "url").unwrap_or_default();
        return vec![json!({
            "id": "__confirm__",
            "header": if server.is_empty() { Value::Null } else { json!(server) },
            "question": if url.is_empty() { msg } else { format!("{msg}\n{url}") },
            "multiSelect": false,
            "allowOther": false,
            "options": [
                { "label": "Accept", "description": "Confirm and continue" }
            ],
        })];
    }
    let Some(props) = params
        .pointer("/requestedSchema/properties")
        .and_then(|p| p.as_object())
    else {
        return vec![json!({
            "id": "__confirm__",
            "header": if server.is_empty() { Value::Null } else { json!(server) },
            "question": msg,
            "multiSelect": false,
            "allowOther": true,
            "options": [],
        })];
    };
    props
        .iter()
        .map(|(k, schema)| {
            let title = s(schema, "title").unwrap_or_else(|| k.clone());
            let desc = s(schema, "description");
            let ty = s(schema, "type").unwrap_or_else(|| "string".into());
            let mut multi = false;
            let mut options: Vec<Value> = Vec::new();
            if let Some(en) = schema.get("enum").and_then(|e| e.as_array()) {
                for v in en {
                    if let Some(lbl) = v.as_str() {
                        options.push(json!({ "label": lbl, "description": Value::Null }));
                    }
                }
            } else if let Some(one_of) = schema
                .get("oneOf")
                .or_else(|| schema.get("anyOf"))
                .and_then(|e| e.as_array())
            {
                for v in one_of {
                    if let Some(c) = v.get("const").and_then(|x| x.as_str()) {
                        let t = s(v, "title").unwrap_or_else(|| c.to_string());
                        options.push(json!({ "label": t, "value": c, "description": Value::Null }));
                    }
                }
            } else if ty == "array" {
                multi = true;
                if let Some(en) = schema.pointer("/items/enum").and_then(|e| e.as_array()) {
                    for v in en {
                        if let Some(lbl) = v.as_str() {
                            options.push(json!({ "label": lbl, "description": Value::Null }));
                        }
                    }
                } else if let Some(any_of) =
                    schema.pointer("/items/anyOf").and_then(|e| e.as_array())
                {
                    for v in any_of {
                        if let Some(c) = v.get("const").and_then(|x| x.as_str()) {
                            let t = s(v, "title").unwrap_or_else(|| c.to_string());
                            options.push(
                                json!({ "label": t, "value": c, "description": Value::Null }),
                            );
                        }
                    }
                }
            } else if ty == "boolean" {
                options.push(json!({ "label": "true", "description": "Yes" }));
                options.push(json!({ "label": "false", "description": "No" }));
            }
            let allow_other = options.is_empty();
            json!({
                "id": k,
                "header": if server.is_empty() { Value::Null } else { json!(server) },
                "question": if let Some(d) = desc { format!("{title}\n{d}") } else { title },
                "multiSelect": multi,
                "allowOther": allow_other,
                "isSecret": false,
                "required": params.pointer("/requestedSchema/required").and_then(Value::as_array)
                    .is_some_and(|fields| fields.iter().any(|field| field.as_str() == Some(k))),
                "options": options,
                "schemaType": ty,
            })
        })
        .collect()
}

fn build_elicitation_content(params: &Value, answers: Option<&Value>) -> Value {
    let Some(ans_map) = answers.and_then(|a| a.as_object()) else {
        return json!({});
    };
    let props = params
        .pointer("/requestedSchema/properties")
        .and_then(|p| p.as_object());
    let mut out = serde_json::Map::new();
    for (k, v) in ans_map {
        if k == "__confirm__" {
            continue;
        }
        let schema_ty = props
            .and_then(|p| p.get(k))
            .and_then(|s| s.get("type"))
            .and_then(|t| t.as_str())
            .unwrap_or("string");
        let val = match (schema_ty, v) {
            ("boolean", Value::String(s)) => json!(s == "true"),
            ("integer", Value::String(s)) => s
                .parse::<i64>()
                .map(|n| json!(n))
                .unwrap_or_else(|_| v.clone()),
            ("number", Value::String(s)) => s
                .parse::<f64>()
                .map(|n| json!(n))
                .unwrap_or_else(|_| v.clone()),
            ("array", Value::String(s)) => {
                let arr: Vec<&str> = s
                    .split(',')
                    .map(|x| x.trim())
                    .filter(|x| !x.is_empty())
                    .collect();
                json!(arr)
            }
            _ => v.clone(),
        };
        out.insert(k.clone(), val);
    }
    Value::Object(out)
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
                json!({
                    "clientInfo": { "name": "switchtether", "title": "SwitchTether", "version": env!("CARGO_PKG_VERSION") },
                    "capabilities": { "experimentalApi": true, "requestAttestation": false }
                }),
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
            st.title = started.get("thread").and_then(|t| s(t, "name"));
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
        self.on_server_request_with_parent(rpc_id, method, params, None)
            .await;
    }

    pub(crate) async fn on_server_request_with_parent(
        &self,
        rpc_id: Value,
        method: &str,
        params: Value,
        parent_id: Option<String>,
    ) {
        let ts = crate::util::now_rfc3339();
        let request_id = format!(
            "cx-{}",
            match &rpc_id {
                Value::String(x) => x.clone(),
                other => other.to_string(),
            }
        );
        let event_id = format!("req:{request_id}");
        let mut is_question = false;
        let mut draft = match method {
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
            "item/permissions/requestApproval" => EventDraft::new(
                &event_id,
                "approval_request",
                &ts,
                json!({
                    "requestId": request_id, "kind": "tool", "title": "Grant permissions",
                    "detail": params.get("reason"), "cwd": params.get("cwd"),
                    "toolName": "permissions", "input": params.get("permissions").cloned().unwrap_or(Value::Null),
                    "options": [
                        { "id": "allow", "label": "Allow" },
                        { "id": "allow_session", "label": "Allow for session" },
                        { "id": "deny", "label": "Deny" }
                    ],
                    "resolved": false,
                }),
            ),
            "item/tool/requestUserInput" => {
                is_question = true;
                let questions: Vec<Value> = params
                    .get("questions")
                    .and_then(|q| q.as_array())
                    .cloned()
                    .unwrap_or_default()
                    .into_iter()
                    .enumerate()
                    .map(|(i, q)| {
                        let id = q.get("id").cloned().unwrap_or_else(|| json!(i.to_string()));
                        let opts = q
                            .get("options")
                            .and_then(|o| o.as_array())
                            .cloned()
                            .unwrap_or_default();
                        let is_other = q
                            .get("isOther")
                            .and_then(|b| b.as_bool())
                            .unwrap_or(opts.is_empty());
                        let is_secret =
                            q.get("isSecret").and_then(|b| b.as_bool()).unwrap_or(false);
                        json!({
                            "id": id,
                            "header": q.get("header"),
                            "question": q.get("question"),
                            "multiSelect": q.get("multiSelect").and_then(Value::as_bool).unwrap_or(false),
                            "allowOther": is_other,
                            "isSecret": is_secret,
                            "options": opts,
                        })
                    })
                    .collect();
                EventDraft::new(
                    &event_id,
                    "question",
                    &ts,
                    json!({
                        "requestId": request_id,
                        "questions": questions,
                        "cancellable": true,
                        "resolved": false,
                    }),
                )
            }
            "mcpServer/elicitation/request" => {
                is_question = true;
                let questions = elicitation_to_questions(&params);
                let mut data = json!({
                    "requestId": request_id,
                    "questions": questions,
                    "cancellable": true,
                    "elicitationMode": params.get("mode"),
                    "serverName": params.get("serverName"),
                    "resolved": false,
                });
                if let Some(u) = params.get("url") {
                    data["url"] = u.clone();
                }
                EventDraft::new(&event_id, "question", &ts, data)
            }
            _ if self.remote.is_some() => {
                // Codex Desktop owns the connection and answers other internal requests itself.
                return;
            }
            _ => {
                // Dynamic tool calls or auth tokens are not supported from the phone:
                // answer explicitly so the turn does not hang.
                let reply = json!({ "id": rpc_id, "error": { "code": -32601, "message": "not supported by SwitchTether" } });
                let _ = self.write(&reply).await;
                self.system(&format!("Declined unsupported request: {method}"));
                return;
            }
        };
        if let Some(pid) = parent_id {
            draft.parent_id = Some(pid);
        }
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
        self.publish_status(
            if is_question {
                "waiting_input"
            } else {
                "waiting_approval"
            },
            None,
        );
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
                let mut text: Vec<String> = Vec::new();
                let mut image_items: Vec<Value> = Vec::new();
                if let Some(arr) = item.get("content").and_then(|c| c.as_array()) {
                    for x in arr {
                        match x.get("type").and_then(|t| t.as_str()) {
                            Some("text") => {
                                if let Some(t) = s(x, "text") {
                                    text.push(t);
                                }
                            }
                            Some("image") => {
                                if let Some(url) = s(x, "url") {
                                    image_items.push(json!({ "kind": "url", "url": url }));
                                }
                            }
                            Some("localImage") => {
                                if let Some(path) = s(x, "path") {
                                    image_items.push(json!({ "kind": "local", "path": path }));
                                }
                            }
                            _ => {
                                if let Some(t) = s(x, "text") {
                                    text.push(t);
                                }
                            }
                        }
                    }
                }
                push(EventDraft::new(
                    eid,
                    "user_message",
                    ts,
                    json!({
                        "text": text.join("\n"),
                        "images": image_items.len(),
                        "imageItems": image_items,
                        "source": "phone"
                    }),
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
            Some("collabAgentToolCall") => {
                let status = match item.get("status").and_then(|x| x.as_str()) {
                    Some("completed") => "completed",
                    Some("failed") => "failed",
                    _ => "running",
                };
                let tool = s(item, "tool").unwrap_or_else(|| "spawnAgent".into());
                let prompt = s(item, "prompt");
                let title = prompt
                    .as_deref()
                    .and_then(|p| p.lines().next())
                    .map(|l| preview(l, 120).0)
                    .unwrap_or_else(|| tool.clone());
                push(EventDraft::new(
                    &eid,
                    "tool_call",
                    &ts,
                    json!({
                        "callId": id, "name": tool, "title": title,
                        "category": "task", "input": item, "status": status,
                    }),
                ));
                let receivers: Vec<String> = item
                    .get("receiverThreadIds")
                    .and_then(|r| r.as_array())
                    .map(|a| {
                        a.iter()
                            .filter_map(|x| x.as_str().map(String::from))
                            .collect()
                    })
                    .unwrap_or_default();
                let states = item.get("agentsStates").and_then(|s| s.as_object());
                for tid in receivers {
                    let st = states.and_then(|m| m.get(&tid));
                    let sa_status = match st.and_then(|v| s(v, "status")).as_deref() {
                        Some("completed") => "completed",
                        Some("errored") => "failed",
                        Some("interrupted") => "interrupted",
                        Some("shutdown") => "completed",
                        Some("notFound") => "failed",
                        _ => {
                            if status == "failed" {
                                "failed"
                            } else {
                                "running"
                            }
                        }
                    };
                    let mut sa = EventDraft::new(
                        format!("cx-sa-{tid}"),
                        "subagent",
                        &ts,
                        json!({
                            "agentId": tid,
                            "description": prompt,
                            "status": sa_status,
                            "message": st.and_then(|v| s(v, "message")),
                            "callId": id,
                        }),
                    );
                    sa.parent_id = Some(id.clone());
                    push(sa);
                }
            }
            Some("imageView") => {
                let path = s(item, "path").unwrap_or_default();
                push(EventDraft::new(
                    eid,
                    "assistant_message",
                    ts,
                    json!({
                        "text": format!("Viewed image: `{}`", path),
                        "imageItems": [{ "kind": "local", "path": path }],
                    }),
                ));
            }
            Some("imageGeneration") => {
                let status = s(item, "status").unwrap_or_default();
                let saved = s(item, "savedPath");
                let prompt = s(item, "revisedPrompt").unwrap_or_default();
                let mut imgs = Vec::new();
                if let Some(p) = &saved {
                    imgs.push(json!({ "kind": "local", "path": p }));
                }
                push(EventDraft::new(
                    eid,
                    "assistant_message",
                    ts,
                    json!({
                        "text": if prompt.is_empty() { format!("Generated image ({status})") } else { prompt },
                        "imageItems": imgs,
                    }),
                ));
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

    pub(crate) fn on_subagent_started(&self, tid: &str, thread: &Value) {
        let desc = s(thread, "agentNickname")
            .or_else(|| s(thread, "agentRole"))
            .or_else(|| s(thread, "name"))
            .or_else(|| s(thread, "preview").filter(|p| !p.is_empty()));
        let mut d = EventDraft::new(
            format!("cx-sa-{tid}"),
            "subagent",
            crate::util::now_rfc3339(),
            json!({
                "agentId": tid,
                "agentType": s(thread, "agentRole"),
                "description": desc,
                "status": "running",
            }),
        );
        // Preserve existing parent_id if collabAgentToolCall already created this subagent row.
        let mut log = self.log.lock();
        if let Some(existing) = log.get(&d.id) {
            d.parent_id = existing.parent_id.clone();
            if d.data["description"].is_null() {
                d.data["description"] = existing.data["description"].clone();
            }
        }
        log.upsert(d, true);
    }

    pub(crate) fn on_subagent_notification(&self, tid: &str, method: &str, p: Value) {
        let sa_id = format!("cx-sa-{tid}");
        match method {
            "thread/status/changed" => {
                let ty = p.pointer("/status/type").and_then(|t| t.as_str());
                let mapped = match ty {
                    Some("idle") => Some("completed"),
                    Some("systemError") => Some("failed"),
                    Some("active") => Some("running"),
                    _ => None,
                };
                if let Some(st) = mapped {
                    self.log.lock().update(&sa_id, true, |e| {
                        e.data["status"] = json!(st);
                    });
                }
            }
            "turn/completed" => {
                let status = p
                    .get("turn")
                    .and_then(|t| s(t, "status"))
                    .unwrap_or_default();
                let mapped = match status.as_str() {
                    "interrupted" => "interrupted",
                    "failed" => "failed",
                    _ => "completed",
                };
                self.log.lock().update(&sa_id, true, |e| {
                    e.data["status"] = json!(mapped);
                });
            }
            "item/agentMessage/delta" => {
                let (Some(item_id), Some(delta)) = (s(&p, "itemId"), s(&p, "delta")) else {
                    return;
                };
                let eid = format!("cx-item-{item_id}");
                let mut log = self.log.lock();
                let existing = log
                    .get(&eid)
                    .and_then(|e| e.data["text"].as_str())
                    .unwrap_or("");
                let mut draft = EventDraft::new(
                    &eid,
                    "assistant_message",
                    crate::util::now_rfc3339(),
                    json!({ "text": format!("{existing}{delta}") }),
                );
                draft.parent_id = Some(sa_id);
                draft.turn_id = s(&p, "turnId");
                draft.streaming = true;
                log.upsert(draft, true);
            }
            "item/commandExecution/outputDelta" => self.on_notification(method, p),
            "item/started" | "item/completed" => {
                if let Some(item) = p.get("item") {
                    let done = method == "item/completed";
                    let turn_id = s(&p, "turnId");
                    let drafts = self.item_events(item, turn_id.as_deref(), done);
                    let mut log = self.log.lock();
                    for mut d in drafts {
                        if d.parent_id.is_none() {
                            d.parent_id = Some(sa_id.clone());
                        }
                        log.upsert(d, true);
                    }
                }
            }
            _ => {}
        }
    }

    fn on_thread_metadata(&self, thread: &Value) {
        if let Some(title) =
            s(thread, "name").and_then(|t| crate::agents::claude::transcript::clean_title(Some(t)))
        {
            self.state.lock().title = Some(title);
            self.system("Conversation title updated");
        }
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
                self.system("Conversation title updated");
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
    fn supports_images(&self) -> bool {
        true
    }

    async fn send_message(&self, text: &str) -> ApiResult<()> {
        self.send_message_with_images(text, &[]).await
    }

    async fn send_message_with_images(
        &self,
        text: &str,
        images: &[crate::agents::managed::ImageAttachment],
    ) -> ApiResult<()> {
        if self.liveness() != Liveness::Live {
            return Err(ApiError::conflict("codex app-server has exited"));
        }
        let thread_id = self.thread_id()?;
        let mut inputs: Vec<Value> = Vec::new();
        if !text.trim().is_empty() {
            inputs.push(json!({ "type": "text", "text": text, "text_elements": [] }));
        }
        for img in images {
            inputs.push(json!({
                "type": "image",
                "url": format!("data:{};base64,{}", img.media_type, img.data_base64),
            }));
        }
        let input = Value::Array(inputs);
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
        let reply_msg = match pending.method.as_str() {
            "item/tool/requestUserInput" => match decision {
                "answer" | "allow" => {
                    let ans_map = answers.as_ref().and_then(|a| a.as_object());
                    let mut out = serde_json::Map::new();
                    if let Some(m) = ans_map {
                        for (k, v) in m {
                            let arr: Vec<String> = match v {
                                Value::Array(a) => a
                                    .iter()
                                    .filter_map(|x| x.as_str().map(String::from))
                                    .collect(),
                                Value::String(s) => {
                                    if s.is_empty() {
                                        vec![]
                                    } else {
                                        vec![s.clone()]
                                    }
                                }
                                other => vec![other.to_string()],
                            };
                            out.insert(k.clone(), json!({ "answers": arr }));
                        }
                    }
                    json!({ "id": pending.rpc_id, "result": { "answers": Value::Object(out) } })
                }
                "cancel" | "deny" | "abort" => json!({
                    "id": pending.rpc_id,
                    "error": { "code": -32000, "message": "User cancelled" }
                }),
                other => {
                    self.pending.lock().insert(request_id.to_string(), pending);
                    return Err(ApiError::validation(format!("unknown decision '{other}'")));
                }
            },
            "mcpServer/elicitation/request" => match decision {
                "answer" | "allow" => {
                    let mode = s(&pending.params, "mode").unwrap_or_default();
                    let content = if mode == "url" {
                        Value::Null
                    } else {
                        build_elicitation_content(&pending.params, answers.as_ref())
                    };
                    json!({
                        "id": pending.rpc_id,
                        "result": { "action": "accept", "content": content, "_meta": null }
                    })
                }
                "deny" => json!({
                    "id": pending.rpc_id,
                    "result": { "action": "decline", "content": null, "_meta": null }
                }),
                "cancel" | "abort" => json!({
                    "id": pending.rpc_id,
                    "result": { "action": "cancel", "content": null, "_meta": null }
                }),
                other => {
                    self.pending.lock().insert(request_id.to_string(), pending);
                    return Err(ApiError::validation(format!("unknown decision '{other}'")));
                }
            },
            "item/permissions/requestApproval" => match decision {
                "allow" | "allow_session" => json!({
                    "id": pending.rpc_id,
                    "result": {
                        "permissions": pending.params.get("permissions").cloned().unwrap_or(json!({})),
                        "scope": if decision == "allow_session" { "session" } else { "turn" },
                    }
                }),
                "deny" | "abort" | "cancel" => json!({
                    "id": pending.rpc_id,
                    "result": { "permissions": {}, "scope": "turn" }
                }),
                other => {
                    self.pending.lock().insert(request_id.to_string(), pending);
                    return Err(ApiError::validation(format!("unknown decision '{other}'")));
                }
            },
            _ => {
                let codex_decision = match decision {
                    "allow" => "accept",
                    "allow_session" => "acceptForSession",
                    "deny" => "decline",
                    "abort" | "cancel" => "cancel",
                    other => {
                        self.pending.lock().insert(request_id.to_string(), pending);
                        return Err(ApiError::validation(format!("unknown decision '{other}'")));
                    }
                };
                json!({ "id": pending.rpc_id, "result": { "decision": codex_decision } })
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
        self.write(&reply_msg).await?;
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
        let mut st = self.state.lock();
        if st.liveness == Liveness::Live {
            st.liveness = Liveness::Ended;
            st.status = "completed";
            st.last_active_at_ms = crate::util::now_ms();
        }
    }
    fn origin(&self) -> &'static str {
        self.origin_name()
    }
}

/// Locate the `codex` executable: the newest of the CLI on PATH (or the usual
/// install locations) and, on Windows, the one bundled with Codex Desktop. An
/// old CLI may not support the models the user's account offers.
pub fn find_codex_bin() -> Option<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Some(p) = crate::util::which("codex") {
        candidates.push(p);
    }
    if let Some(home) = dirs::home_dir() {
        let extra = if cfg!(windows) {
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
        candidates.extend(extra.into_iter().filter(|p| p.is_file()));
    }
    if let Some(p) = desktop_bundled_codex() {
        candidates.push(p);
    }
    candidates.dedup();
    if candidates.len() <= 1 {
        return candidates.pop();
    }
    candidates
        .into_iter()
        .map(|p| (codex_version(&p), p))
        .max_by(|a, b| a.0.cmp(&b.0))
        .map(|(_, p)| p)
}

/// `codex --version` → comparable (major, minor, patch); unknown sorts lowest.
fn codex_version(p: &std::path::Path) -> (u64, u64, u64) {
    let mut cmd = std::process::Command::new(p);
    cmd.arg("--version")
        .stdin(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000);
    }
    let out = cmd.output().ok();
    let text = out
        .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
        .unwrap_or_default();
    parse_version(&text)
}

fn parse_version(text: &str) -> (u64, u64, u64) {
    let v = text
        .split_whitespace()
        .find(|w| w.chars().next().is_some_and(|c| c.is_ascii_digit()))
        .unwrap_or("");
    let mut it = v.split(['.', '-']).map(|x| x.parse::<u64>().unwrap_or(0));
    (
        it.next().unwrap_or(0),
        it.next().unwrap_or(0),
        it.next().unwrap_or(0),
    )
}

/// Codex Desktop's own `codex.exe` (MSIX package `OpenAI.Codex`).
fn desktop_bundled_codex() -> Option<PathBuf> {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let out = std::process::Command::new("powershell")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "(Get-AppxPackage -Name OpenAI.Codex | Select-Object -First 1).InstallLocation",
            ])
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .creation_flags(0x0800_0000)
            .output()
            .ok()?;
        let dir = String::from_utf8_lossy(&out.stdout).trim().to_string();
        if dir.is_empty() {
            return None;
        }
        let p = PathBuf::from(dir)
            .join("app")
            .join("resources")
            .join("codex.exe");
        p.is_file().then_some(p)
    }
    #[cfg(not(windows))]
    {
        None
    }
}

#[cfg(test)]
mod version_tests {
    #[test]
    fn versions_compare_numerically() {
        assert_eq!(
            super::parse_version(
                "codex-cli 0.167.0-alpha.4.1
"
            ),
            (0, 167, 0)
        );
        assert!(
            super::parse_version("codex-cli 0.137.0") < super::parse_version("codex-cli 0.167.0")
        );
        assert_eq!(super::parse_version(""), (0, 0, 0));
    }

    #[test]
    fn subagent_source_accepts_both_wire_spellings() {
        for key in ["subagent", "subAgent"] {
            let thread = serde_json::json!({"source": {key: {"thread_spawn": {"parent_thread_id": "parent"}}}});
            assert_eq!(
                super::CodexConn::subagent_parent_id(&thread).as_deref(),
                Some("parent")
            );
        }
    }

    #[tokio::test]
    async fn codex_user_input_elicitation_and_subagent_routing() {
        use super::*;
        use crate::agents::managed::ManagedSession;
        use crate::agents::store::SessionLog;
        use parking_lot::Mutex;
        use std::sync::Arc;

        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<String>();
        let conn = CodexConn::new(tx, "desktop_app", PathBuf::from("."));
        let log = Arc::new(Mutex::new(SessionLog::new("attached:p1", None)));
        let parent = ManagedCodex::attached(
            "attached:p1".into(),
            conn.clone(),
            "th-parent".into(),
            PathBuf::from("."),
            log.clone(),
        );
        conn.threads
            .lock()
            .insert("th-parent".into(), parent.clone());

        conn.on_frame(json!({"t":"out", "line": json!({"id":1,"result":{"thread":{"id":"th-parent","name":"Fix login"}}}).to_string()})).await;
        assert_eq!(parent.title().as_deref(), Some("Fix login"));
        parent.on_notification(
            "thread/name/updated",
            json!({"threadId":"th-parent", "threadName":"Renamed task"}),
        );
        assert_eq!(parent.title().as_deref(), Some("Renamed task"));

        // 1. Subagent thread discovered via thread/started is NOT returned as a top-level thread.
        let sub_frame = json!({
            "t": "out",
            "line": json!({
                "method": "thread/started",
                "params": {
                    "thread": {
                        "id": "th-child",
                        "source": { "subagent": { "thread_spawn": { "parent_thread_id": "th-parent" } } },
                        "agentRole": "explorer",
                        "agentNickname": "Scout"
                    }
                }
            }).to_string()
        });
        assert!(conn.discover(&sub_frame).is_empty());
        assert_eq!(
            conn.subagent_parents
                .lock()
                .get("th-child")
                .map(String::as_str),
            Some("th-parent")
        );
        let sa_ev = log.lock().get("cx-sa-th-child").cloned().unwrap();
        assert_eq!(sa_ev.kind, "subagent");
        assert_eq!(sa_ev.data["status"], "running");

        // Subagent turn/completed updates subagent status to completed.
        conn.on_frame(json!({
            "t": "out",
            "line": json!({
                "method": "turn/completed",
                "params": { "threadId": "th-child", "turn": { "id": "t1", "status": "completed" } }
            }).to_string()
        }))
        .await;
        assert_eq!(
            log.lock().get("cx-sa-th-child").unwrap().data["status"],
            "completed"
        );

        // 2. item/tool/requestUserInput produces a question event and accepts structured answers.
        parent
            .on_server_request(
                json!(42),
                "item/tool/requestUserInput",
                json!({
                    "threadId": "th-parent",
                    "turnId": "t1",
                    "itemId": "i1",
                    "questions": [{
                        "id": "q1",
                        "header": "Target",
                        "question": "Which env?",
                        "isOther": true,
                        "isSecret": false,
                        "options": [{ "label": "staging", "description": "Staging" }]
                    }, {"id":"checks", "question":"Choose checks", "multiSelect":true, "options":[{"label":"Unit, integration"},{"label":"Lint"}]}]
                }),
            )
            .await;
        let q_ev = log.lock().get("req:cx-42").cloned().unwrap();
        assert_eq!(q_ev.kind, "question");
        assert_eq!(q_ev.data["questions"][0]["allowOther"], true);

        // Answer in background (verdict channel waits for shim accepted/rejected in attached mode).
        let p2 = parent.clone();
        let ans_task = tokio::spawn(async move {
            p2.respond(
                "cx-42",
                "answer",
                None,
                None,
                Some(json!({ "q1": "staging", "checks": ["Unit, integration", "Lint"] })),
                "phone",
            )
            .await
        });
        let injected = rx.recv().await.unwrap();
        let inj_v: Value = serde_json::from_str(&injected).unwrap();
        let inner: Value = serde_json::from_str(inj_v["line"].as_str().unwrap()).unwrap();
        assert_eq!(inner["id"], 42);
        assert_eq!(inner["result"]["answers"]["q1"]["answers"][0], "staging");
        assert_eq!(
            inner["result"]["answers"]["checks"]["answers"],
            json!(["Unit, integration", "Lint"])
        );
        conn.on_frame(json!({ "t": "accepted", "line": inj_v["line"] }))
            .await;
        assert!(ans_task.await.unwrap().is_ok());

        // 3. mcpServer/elicitation/request (form) maps schema and cancel sends action=cancel.
        parent
            .on_server_request(
                json!("el-1"),
                "mcpServer/elicitation/request",
                json!({
                    "threadId": "th-parent",
                    "turnId": "t1",
                    "serverName": "github",
                    "mode": "form",
                    "message": "Select options",
                    "requestedSchema": {
                        "type": "object",
                        "properties": {
                            "repo": { "type": "string", "title": "Repository" },
                            "dryRun": { "type": "boolean", "title": "Dry run" }
                        }
                    }
                }),
            )
            .await;
        assert_eq!(log.lock().get("req:cx-el-1").unwrap().kind, "question");
        let p3 = parent.clone();
        let cancel_task = tokio::spawn(async move {
            p3.respond("cx-el-1", "cancel", None, None, None, "phone")
                .await
        });
        let injected2 = rx.recv().await.unwrap();
        let inj_v2: Value = serde_json::from_str(&injected2).unwrap();
        let inner2: Value = serde_json::from_str(inj_v2["line"].as_str().unwrap()).unwrap();
        assert_eq!(inner2["result"]["action"], "cancel");
        conn.on_frame(json!({ "t": "accepted", "line": inj_v2["line"] }))
            .await;
        assert!(cancel_task.await.unwrap().is_ok());
    }
}
