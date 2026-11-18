//! DeepSeek Harness sessions of the user's own `dsh web` host, controlled through the
//! opt-in SwitchTether bridge plugin (docs/AGENT_COMPATIBILITY.md §6b).
//!
//! The plugin connects to the attach broker and reports the sessions it hosts; each becomes an
//! attached session here. The timeline is read from the session's persisted log (as for
//! observed and managed dsh sessions); messages, cancel and approval answers go through the
//! plugin. SwitchTether never stops the user's dsh process: `kill` only forgets the session.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use parking_lot::Mutex;
use serde_json::{json, Value};
use tokio::sync::{mpsc, oneshot};

use crate::agents::managed::ManagedSession;
use crate::agents::model::{EventDraft, Liveness};
use crate::agents::store::SessionLog;
use crate::error::{ApiError, ApiResult};

/// One connected bridge plugin (one dsh host process).
pub struct DshPluginConn {
    tx: mpsc::UnboundedSender<String>,
    next: AtomicU64,
    replies: Mutex<HashMap<u64, oneshot::Sender<Result<(), String>>>>,
    /// dsh session id → attached session.
    pub sessions: Mutex<HashMap<String, Arc<AttachedDsh>>>,
}

impl DshPluginConn {
    pub fn new(tx: mpsc::UnboundedSender<String>) -> Arc<Self> {
        Arc::new(Self {
            tx,
            next: AtomicU64::new(1),
            replies: Mutex::new(HashMap::new()),
            sessions: Mutex::new(HashMap::new()),
        })
    }

    fn send(&self, frame: &Value) -> bool {
        self.tx.send(frame.to_string()).is_ok()
    }

    /// Sends a command frame and waits for the plugin's `result`.
    async fn call(&self, mut frame: Value) -> Result<(), String> {
        let id = self.next.fetch_add(1, Ordering::SeqCst);
        frame["id"] = json!(id);
        let (tx, rx) = oneshot::channel();
        self.replies.lock().insert(id, tx);
        if !self.send(&frame) {
            self.replies.lock().remove(&id);
            return Err("the DeepSeek Harness bridge is disconnected".into());
        }
        match tokio::time::timeout(Duration::from_secs(70), rx).await {
            Ok(Ok(r)) => r,
            Ok(Err(_)) => Err("the DeepSeek Harness bridge is disconnected".into()),
            Err(_) => {
                self.replies.lock().remove(&id);
                Err("DeepSeek Harness did not answer in time".into())
            }
        }
    }

    pub fn on_result(&self, frame: &Value) {
        let Some(id) = frame.get("id").and_then(Value::as_u64) else {
            return;
        };
        if let Some(tx) = self.replies.lock().remove(&id) {
            let r = if frame.get("ok").and_then(Value::as_bool) == Some(true) {
                Ok(())
            } else {
                Err(frame
                    .get("error")
                    .and_then(Value::as_str)
                    .unwrap_or("DeepSeek Harness refused the request")
                    .to_string())
            };
            let _ = tx.send(r);
        }
    }

    /// The plugin disconnected: every session it reported ends here (the user's dsh keeps
    /// running; it is only no longer controllable).
    pub fn on_disconnect(&self) {
        for (_, tx) in self.replies.lock().drain() {
            let _ = tx.send(Err("the DeepSeek Harness bridge is disconnected".into()));
        }
        for s in self.sessions.lock().values() {
            s.mark_ended();
        }
    }
}

struct State {
    liveness: Liveness,
    waiting: bool,
    last_activity_ms: i64,
    created_at_ms: i64,
    title: Option<String>,
}

pub struct AttachedDsh {
    pub id: String,
    session_id: String,
    cwd: PathBuf,
    conn: Arc<DshPluginConn>,
    log: Arc<Mutex<SessionLog>>,
    state: Arc<Mutex<State>>,
    /// requestId → event id of open approval cards.
    pending: Mutex<HashMap<String, String>>,
    resolved: Mutex<HashMap<String, String>>,
}

impl AttachedDsh {
    pub fn new(
        id: String,
        session_id: String,
        conn: Arc<DshPluginConn>,
        log: Arc<Mutex<SessionLog>>,
    ) -> Arc<Self> {
        let header = super::find_session_dir(&session_id)
            .and_then(|d| super::session_file(&d))
            .and_then(|f| super::read_header(&f));
        let cwd = header
            .as_ref()
            .and_then(|h| h.get("cwd").and_then(Value::as_str))
            .map(PathBuf::from)
            .unwrap_or_default();
        let now = crate::util::now_ms();
        let me = Arc::new(Self {
            id,
            session_id: session_id.clone(),
            cwd,
            conn,
            log: log.clone(),
            state: Arc::new(Mutex::new(State {
                liveness: Liveness::Live,
                waiting: false,
                last_activity_ms: now,
                created_at_ms: now,
                title: None,
            })),
            pending: Mutex::new(HashMap::new()),
            resolved: Mutex::new(HashMap::new()),
        });
        let state = me.state.clone();
        tokio::spawn(super::managed::follow_session_log(
            session_id,
            log,
            move || state.lock().liveness == Liveness::Live,
        ));
        me
    }

    pub fn touch(&self) {
        self.state.lock().last_activity_ms = crate::util::now_ms();
    }

    pub fn mark_ended(&self) {
        self.state.lock().liveness = Liveness::Ended;
        let open: Vec<(String, String)> = self.pending.lock().drain().collect();
        for (rid, ev) in open {
            self.resolved.lock().insert(rid, "desktop".into());
            self.log.lock().update(&ev, true, |e| {
                e.data["resolved"] = json!(true);
                e.data["by"] = json!("desktop");
            });
        }
    }

    /// An approval the dsh host asked for; the phone may answer it.
    pub fn on_approval(&self, f: &Value) {
        let Some(rid) = f.get("requestId").and_then(Value::as_str) else {
            return;
        };
        let tool = f.get("toolName").and_then(Value::as_str).unwrap_or("tool");
        let category = super::tool_category_of(tool);
        let kind = match category {
            "shell" => "command",
            "edit" | "write" => "file_change",
            "web" => "network",
            _ => "tool",
        };
        let event_id = format!("perm:{rid}");
        let mut d = EventDraft::new(
            &event_id,
            "approval_request",
            crate::util::now_rfc3339(),
            json!({
                "requestId": rid, "kind": kind, "title": f.get("reason").and_then(Value::as_str).unwrap_or(tool),
                "toolName": tool, "detail": f.get("reason"), "cwd": self.cwd.to_string_lossy(),
                "options": [ { "id": "allow", "label": "Allow" }, { "id": "deny", "label": "Deny" } ],
                "resolved": false, "toolUseId": f.get("callId"),
            }),
        );
        d.parent_id = f
            .get("callId")
            .and_then(Value::as_str)
            .map(|c| format!("tc:{c}"));
        self.log.lock().upsert(d, true);
        self.pending.lock().insert(rid.to_string(), event_id);
        let mut st = self.state.lock();
        st.waiting = true;
        st.last_activity_ms = crate::util::now_ms();
    }

    /// Answered in the dsh UI (or withdrawn) before the phone answered.
    pub fn on_approval_done(&self, f: &Value) {
        let Some(rid) = f.get("requestId").and_then(Value::as_str) else {
            return;
        };
        if let Some(ev) = self.pending.lock().remove(rid) {
            self.resolved
                .lock()
                .insert(rid.to_string(), "desktop".into());
            let decision = match f.get("outcome").and_then(Value::as_str) {
                Some("allowed-once") => "allow",
                Some("rejected") => "deny",
                _ => "cancelled",
            };
            self.log.lock().update(&ev, true, |e| {
                e.data["resolved"] = json!(true);
                e.data["decision"] = json!(decision);
                e.data["by"] = json!("desktop");
            });
        }
        let waiting = !self.pending.lock().is_empty();
        self.state.lock().waiting = waiting;
    }
}

#[async_trait]
impl ManagedSession for AttachedDsh {
    fn agent(&self) -> &'static str {
        "dsh"
    }
    fn log(&self) -> Arc<Mutex<SessionLog>> {
        self.log.clone()
    }
    fn cwd(&self) -> PathBuf {
        self.cwd.clone()
    }
    fn native_session_id(&self) -> Option<String> {
        Some(super::norm_id(&self.session_id).to_string())
    }
    fn status(&self) -> &'static str {
        let (live, waiting, recent) = {
            let st = self.state.lock();
            (
                st.liveness == Liveness::Live,
                st.waiting,
                crate::util::now_ms() - st.last_activity_ms < 15_000,
            )
        };
        if !live {
            return "completed";
        }
        if waiting {
            return "waiting";
        }
        // The session log's last turn decides (a stopped turn is idle at once, a long tool call
        // stays busy). Only before the log has been read is recent plugin activity the hint.
        let logged = self.log.lock().get(super::ROLLING_STATUS_ID).and_then(|e| {
            e.data
                .get("state")
                .and_then(Value::as_str)
                .map(str::to_string)
        });
        match logged.as_deref() {
            Some("running") => "busy",
            Some(_) => "idle",
            None if recent => "busy",
            None => "idle",
        }
    }
    fn liveness(&self) -> Liveness {
        self.state.lock().liveness
    }
    fn created_at_ms(&self) -> i64 {
        self.state.lock().created_at_ms
    }
    fn last_active_at_ms(&self) -> i64 {
        self.state.lock().last_activity_ms
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
    fn origin(&self) -> &'static str {
        "dsh_web"
    }

    async fn send_message(&self, text: &str) -> ApiResult<()> {
        if self.liveness() != Liveness::Live {
            return Err(ApiError::conflict(
                "this DeepSeek Harness session is no longer connected",
            ));
        }
        {
            let mut st = self.state.lock();
            // Title from the user's own words, never from the attachment path block.
            let own = crate::agents::attachments::split(text).map_or(text.to_string(), |(t, _)| t);
            if st.title.is_none() && !own.trim().is_empty() {
                st.title = Some(own.chars().take(80).collect());
            }
            st.last_activity_ms = crate::util::now_ms();
        }
        self.conn
            .call(json!({ "t": "prompt", "sessionId": self.session_id, "text": text }))
            .await
            .map_err(ApiError::conflict)
    }

    async fn interrupt(&self) -> ApiResult<()> {
        self.conn
            .call(json!({ "t": "cancel", "sessionId": self.session_id }))
            .await
            .map_err(ApiError::conflict)
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
        let outcome = match decision {
            "allow" | "allow_session" | "answer" => "allowed-once",
            "deny" => "rejected",
            "abort" | "cancel" => "cancelled",
            other => return Err(ApiError::validation(format!("unknown decision '{other}'"))),
        };
        let Some(ev) = self.pending.lock().remove(request_id) else {
            let by = self.resolved.lock().get(request_id).cloned();
            return Err(match by {
                Some(b) => ApiError::conflict("this request was already answered")
                    .with_details(json!({ "resolvedBy": b })),
                None => ApiError::not_found("no such pending request"),
            });
        };
        if !self
            .conn
            .send(&json!({ "t": "approve", "requestId": request_id, "outcome": outcome }))
        {
            self.pending.lock().insert(request_id.to_string(), ev);
            return Err(ApiError::conflict(
                "the DeepSeek Harness bridge is disconnected",
            ));
        }
        self.resolved
            .lock()
            .insert(request_id.to_string(), by.to_string());
        let updated = self.log.lock().update(&ev, true, |e| {
            e.data["resolved"] = json!(true);
            e.data["decision"] = json!(decision);
            e.data["by"] = json!(by);
        });
        let waiting = !self.pending.lock().is_empty();
        self.state.lock().waiting = waiting;
        if decision == "abort" {
            let _ = self.interrupt().await;
        }
        Ok(json!({ "resolved": true, "event": updated }))
    }

    /// Never stops the user's dsh: the session is only no longer followed.
    fn kill(&self) {
        self.mark_ended();
    }
}
