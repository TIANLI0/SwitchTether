//! Common interface of SwitchTether-managed agent sessions (Claude Code via
//! stream-json, Codex via its own `codex app-server`).

use std::path::PathBuf;
use std::sync::Arc;

use async_trait::async_trait;
use parking_lot::Mutex;
use serde_json::Value;

use super::model::Liveness;
use super::store::SessionLog;
use crate::error::ApiResult;

#[async_trait]
pub trait ManagedSession: Send + Sync {
    fn agent(&self) -> &'static str;
    fn log(&self) -> Arc<Mutex<SessionLog>>;
    fn cwd(&self) -> PathBuf;
    fn native_session_id(&self) -> Option<String>;
    fn status(&self) -> &'static str;
    fn liveness(&self) -> Liveness;
    fn created_at_ms(&self) -> i64;
    fn last_active_at_ms(&self) -> i64;
    fn model(&self) -> Option<String>;
    fn title(&self) -> Option<String>;
    fn has_pending_requests(&self) -> bool;
    async fn send_message(&self, text: &str) -> ApiResult<()>;
    async fn interrupt(&self) -> ApiResult<()>;
    #[allow(clippy::too_many_arguments)]
    async fn respond(
        &self,
        request_id: &str,
        decision: &str,
        message: Option<String>,
        updated_input: Option<Value>,
        answers: Option<Value>,
        by: &str,
    ) -> ApiResult<Value>;
    fn kill(&self);
    /// `managed` for sessions SwitchTether started; otherwise the origin of
    /// the user's own process it is attached to (`vscode`, `desktop_app`, `cli`).
    fn origin(&self) -> &'static str {
        "managed"
    }
    /// Optional terminal id when control happens through a mirrored TUI.
    fn terminal_id(&self) -> Option<String> {
        None
    }
}

#[async_trait]
impl ManagedSession for super::claude::managed::ManagedClaude {
    fn agent(&self) -> &'static str {
        "claude_code"
    }
    fn log(&self) -> Arc<Mutex<SessionLog>> {
        self.log.clone()
    }
    fn cwd(&self) -> PathBuf {
        self.options.cwd.clone()
    }
    fn native_session_id(&self) -> Option<String> {
        self.native_session_id()
    }
    fn status(&self) -> &'static str {
        self.status()
    }
    fn liveness(&self) -> Liveness {
        self.liveness()
    }
    fn created_at_ms(&self) -> i64 {
        self.created_at_ms()
    }
    fn last_active_at_ms(&self) -> i64 {
        self.last_active_at_ms()
    }
    fn model(&self) -> Option<String> {
        self.model()
    }
    fn title(&self) -> Option<String> {
        self.title()
    }
    fn has_pending_requests(&self) -> bool {
        !self.pending_request_ids().is_empty()
    }
    async fn send_message(&self, text: &str) -> ApiResult<()> {
        super::claude::managed::ManagedClaude::send_message(self, text).await
    }
    async fn interrupt(&self) -> ApiResult<()> {
        super::claude::managed::ManagedClaude::interrupt(self).await
    }
    async fn respond(
        &self,
        request_id: &str,
        decision: &str,
        message: Option<String>,
        updated_input: Option<Value>,
        answers: Option<Value>,
        by: &str,
    ) -> ApiResult<Value> {
        super::claude::managed::ManagedClaude::respond(
            self,
            request_id,
            decision,
            message,
            updated_input,
            answers,
            by,
        )
        .await
    }
    fn kill(&self) {
        super::claude::managed::ManagedClaude::kill(self)
    }
    fn origin(&self) -> &'static str {
        self.origin
    }
}
