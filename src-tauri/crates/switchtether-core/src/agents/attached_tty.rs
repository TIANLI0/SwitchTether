//! A Claude Code / Codex TUI running in the user's own terminal window,
//! started through `st-claude` / `st-codex` (switchtether-shim `tty`).
//!
//! The screen is mirrored as an external terminal (see `terminal`), so the
//! phone sees and drives exactly the same TUI as the person at the computer.
//! The conversation itself is read from the agent's transcript once the
//! session id is known. Messages are typed into the TUI; approvals are answered
//! on the mirrored terminal screen (the TUI menus are not a stable API).

use std::path::PathBuf;
use std::sync::Arc;

use async_trait::async_trait;
use parking_lot::Mutex;
use serde_json::{json, Value};

use super::managed::ManagedSession;
use super::model::{EventDraft, Liveness};
use super::store::SessionLog;
use super::Tail;
use crate::error::{ApiError, ApiResult};
use crate::terminal::Terminal;

struct State {
    liveness: Liveness,
    status: String,
    waiting_for: Option<String>,
    native_id: Option<String>,
    title: Option<String>,
    last_active_at_ms: i64,
}

pub struct AttachedTty {
    pub id: String,
    agent: &'static str,
    cwd: PathBuf,
    /// Process id of the agent inside the shim's PTY (for session matching).
    pub pid: Option<u32>,
    created_at_ms: i64,
    log: Arc<Mutex<SessionLog>>,
    term: Arc<Terminal>,
    state: Mutex<State>,
    pub(super) tail: Mutex<Option<Tail>>,
}

impl AttachedTty {
    pub fn new(
        id: String,
        agent: &'static str,
        cwd: PathBuf,
        pid: Option<u32>,
        log: Arc<Mutex<SessionLog>>,
        term: Arc<Terminal>,
    ) -> Arc<Self> {
        let now = crate::util::now_ms();
        log.lock().upsert(
            EventDraft::new(
                "tty:attached",
                "system",
                crate::util::now_rfc3339(),
                json!({
                    "subtype": "attached",
                    "text": "Mirrored from a terminal window on the computer. Open the terminal view to answer prompts.",
                    "terminalId": term.id,
                }),
            ),
            false,
        );
        Arc::new(Self {
            id,
            agent,
            cwd,
            pid,
            created_at_ms: now,
            log,
            term,
            state: Mutex::new(State {
                liveness: Liveness::Live,
                status: "running".into(),
                waiting_for: None,
                native_id: None,
                title: None,
                last_active_at_ms: now,
            }),
            tail: Mutex::new(None),
        })
    }

    pub fn needs_native_id(&self) -> bool {
        self.state.lock().native_id.is_none() && self.liveness() == Liveness::Live
    }

    pub(super) fn set_native(&self, native: String, title: Option<String>, tail: Option<Tail>) {
        let mut st = self.state.lock();
        st.native_id = Some(native);
        if st.title.is_none() {
            st.title = title;
        }
        *self.tail.lock() = tail;
    }

    pub fn update_live(&self, status: Option<String>, waiting_for: Option<String>) {
        let mut st = self.state.lock();
        if let Some(s) = status {
            st.status = s;
        }
        st.waiting_for = waiting_for;
        st.last_active_at_ms = crate::util::now_ms();
    }

    pub fn waiting_for(&self) -> Option<String> {
        self.state.lock().waiting_for.clone()
    }

    pub fn mark_ended(&self) {
        let mut st = self.state.lock();
        st.liveness = Liveness::Ended;
        st.status = "completed".into();
        st.waiting_for = None;
    }

    pub fn tail_now(&self) {
        let mut t = self.tail.lock();
        if let Some(t) = t.as_mut() {
            if let Err(e) = super::tail_file(t, &self.log, true) {
                log::debug!("[agents] tty tail error: {e}");
            }
        }
    }

    fn type_text(&self, bytes: &[u8]) -> ApiResult<()> {
        self.term
            .write_input(bytes)
            .map_err(|_| ApiError::conflict("the terminal window has closed"))
    }
}

#[async_trait]
impl ManagedSession for AttachedTty {
    fn agent(&self) -> &'static str {
        self.agent
    }
    fn log(&self) -> Arc<Mutex<SessionLog>> {
        self.log.clone()
    }
    fn cwd(&self) -> PathBuf {
        self.cwd.clone()
    }
    fn native_session_id(&self) -> Option<String> {
        self.state.lock().native_id.clone()
    }
    fn status(&self) -> &'static str {
        match self.state.lock().status.as_str() {
            "busy" => "busy",
            "idle" => "idle",
            "waiting" => "waiting",
            "completed" => "completed",
            _ => "running",
        }
    }
    fn liveness(&self) -> Liveness {
        self.state.lock().liveness
    }
    fn created_at_ms(&self) -> i64 {
        self.created_at_ms
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
        self.state.lock().waiting_for.is_some()
    }
    async fn send_message(&self, text: &str) -> ApiResult<()> {
        if self.liveness() != Liveness::Live {
            return Err(ApiError::conflict("the terminal window has closed"));
        }
        // `!cmd` runs a shell command directly in the TUI; shell access from
        // the phone must go through the step-up gated terminal view instead.
        if text.trim_start().starts_with('!') {
            return Err(ApiError::validation(
                "Shell commands (!…) can only be typed in the terminal view.",
            ));
        }
        // Bracketed paste keeps multi-line text as one prompt; Enter submits.
        let mut paste = b"\x1b[200~".to_vec();
        paste.extend_from_slice(text.replace("\r\n", "\n").as_bytes());
        paste.extend_from_slice(b"\x1b[201~");
        self.type_text(&paste)?;
        tokio::time::sleep(std::time::Duration::from_millis(120)).await;
        self.type_text(b"\r")
    }
    async fn interrupt(&self) -> ApiResult<()> {
        // Esc stops the running turn in both Claude Code and Codex TUIs.
        self.type_text(b"\x1b")
    }
    async fn respond(
        &self,
        _request_id: &str,
        _decision: &str,
        _message: Option<String>,
        _updated_input: Option<Value>,
        _answers: Option<Value>,
        _by: &str,
    ) -> ApiResult<Value> {
        Err(ApiError::unavailable_capability(
            "PARTIAL",
            "This session runs in a terminal window: answer the prompt in the terminal view.",
        )
        .with_details(json!({ "terminalId": self.term.id })))
    }
    fn kill(&self) {
        // Never kill a process the user started.
    }
    fn origin(&self) -> &'static str {
        "cli"
    }
    fn terminal_id(&self) -> Option<String> {
        Some(self.term.id.clone())
    }
}
