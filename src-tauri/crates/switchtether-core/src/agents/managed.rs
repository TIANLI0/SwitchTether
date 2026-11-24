//! Common interface of SwitchTether-managed agent sessions (Claude Code via
//! stream-json, Codex via its own `codex app-server`).

use std::path::PathBuf;
use std::sync::Arc;

use async_trait::async_trait;
use parking_lot::Mutex;
use serde_json::Value;

use super::model::Liveness;
use super::store::SessionLog;
use crate::error::{ApiError, ApiResult};

#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImageAttachment {
    pub media_type: String,
    pub data_base64: String,
    #[serde(default)]
    pub name: Option<String>,
}

#[async_trait]
pub trait ManagedSession: Send + Sync {
    fn agent(&self) -> &'static str;
    fn log(&self) -> Arc<Mutex<SessionLog>>;
    fn cwd(&self) -> PathBuf;
    fn native_session_id(&self) -> Option<String>;
    /// Pid of a process SwitchTether spawned for this session (not of attached user processes).
    fn pid(&self) -> Option<u32> {
        None
    }
    fn status(&self) -> &'static str;
    fn liveness(&self) -> Liveness;
    fn created_at_ms(&self) -> i64;
    fn last_active_at_ms(&self) -> i64;
    fn model(&self) -> Option<String>;
    fn title(&self) -> Option<String>;
    fn has_pending_requests(&self) -> bool;
    fn supports_images(&self) -> bool {
        false
    }
    async fn send_message(&self, text: &str) -> ApiResult<()>;
    async fn send_message_with_images(
        &self,
        text: &str,
        images: &[ImageAttachment],
    ) -> ApiResult<()> {
        if !images.is_empty() {
            return Err(ApiError::unavailable_capability(
                "BLOCKED",
                "This session does not support image input.",
            ));
        }
        self.send_message(text).await
    }
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
    /// Effort / reasoning level currently chosen for the session, if known.
    fn effort(&self) -> Option<String> {
        None
    }
    /// The phone may change model and effort of this session while it runs.
    fn can_change_model(&self) -> bool {
        false
    }
    /// Claude Code control requests of the IDE's session tools (permission mode, context usage,
    /// plan, MCP status, side question, rename, rewind, export, …): only a Claude Code process
    /// SwitchTether started. Returns the request's `response` object.
    fn supports_claude_tools(&self) -> bool {
        false
    }
    async fn claude_control(
        &self,
        _request: Value,
        _timeout: std::time::Duration,
    ) -> ApiResult<Value> {
        Err(ApiError::unavailable_capability(
            "BLOCKED",
            "This session does not offer Claude Code session tools.",
        ))
    }
    /// Current permission mode (`default`, `acceptEdits`, `plan`, …) when known.
    fn permission_mode(&self) -> Option<String> {
        None
    }
    /// `uuid` given to the last message sent from the phone (to cancel it while it is queued).
    fn last_message_uuid(&self) -> Option<String> {
        None
    }
    /// Stop one background task or sub-agent of the session (Agent map "Stop agent").
    async fn stop_task(&self, _task_id: &str) -> ApiResult<()> {
        Err(ApiError::unavailable_capability(
            "BLOCKED",
            "Sub-agents of this session cannot be stopped from the phone.",
        ))
    }
    /// Models the agent offers (agent-specific JSON), `Null` when unknown.
    async fn models(&self) -> Value {
        Value::Null
    }
    /// Change the model (`None`: the agent's default) for the following turns.
    async fn set_model(&self, _model: Option<&str>) -> ApiResult<()> {
        Err(ApiError::unavailable_capability(
            "BLOCKED",
            "This session cannot change its model from the phone.",
        ))
    }
    /// Change the effort / reasoning level (`None`: the default) for the following turns.
    async fn set_effort(&self, _effort: Option<&str>) -> ApiResult<()> {
        Err(ApiError::unavailable_capability(
            "BLOCKED",
            "This session cannot change its effort level from the phone.",
        ))
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
    fn pid(&self) -> Option<u32> {
        self.pid()
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
    fn supports_images(&self) -> bool {
        true
    }
    async fn send_message(&self, text: &str) -> ApiResult<()> {
        super::claude::managed::ManagedClaude::send_message(self, text).await
    }
    async fn send_message_with_images(
        &self,
        text: &str,
        images: &[ImageAttachment],
    ) -> ApiResult<()> {
        super::claude::managed::ManagedClaude::send_message_with_images(self, text, images).await
    }
    async fn interrupt(&self) -> ApiResult<()> {
        super::claude::managed::ManagedClaude::interrupt(self).await
    }
    async fn stop_task(&self, task_id: &str) -> ApiResult<()> {
        super::claude::managed::ManagedClaude::stop_task(self, task_id).await
    }
    fn supports_claude_tools(&self) -> bool {
        // Started by SwitchTether, or attached through the opt-in takeover hook (stream-json
        // through switchtether-shim); a terminal session is a mirrored TUI and never gets here.
        true
    }
    async fn claude_control(
        &self,
        request: Value,
        timeout: std::time::Duration,
    ) -> ApiResult<Value> {
        super::claude::managed::ManagedClaude::control(self, request, timeout).await
    }
    fn permission_mode(&self) -> Option<String> {
        super::claude::managed::ManagedClaude::permission_mode(self)
    }
    fn last_message_uuid(&self) -> Option<String> {
        super::claude::managed::ManagedClaude::last_message_uuid(self)
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
    fn effort(&self) -> Option<String> {
        super::claude::managed::ManagedClaude::effort(self)
    }
    fn can_change_model(&self) -> bool {
        // Only a process SwitchTether started. In a session attached through the opt-in takeover
        // hook the IDE owns model and effort (its own picker would not learn of a change made
        // behind it), so `set_model` / `set_effort` below refuse; this must say the same, since
        // `capabilities.changeModel` is derived from it.
        !self.is_attached()
    }
    async fn models(&self) -> Value {
        super::claude::managed::ManagedClaude::models(self)
    }
    async fn set_model(&self, model: Option<&str>) -> ApiResult<()> {
        if self.is_attached() {
            return ManagedSessionDefaults::set_model_blocked();
        }
        super::claude::managed::ManagedClaude::set_model(self, model).await
    }
    async fn set_effort(&self, effort: Option<&str>) -> ApiResult<()> {
        if self.is_attached() {
            return ManagedSessionDefaults::set_model_blocked();
        }
        super::claude::managed::ManagedClaude::set_effort(self, effort).await
    }
}

pub(crate) struct ManagedSessionDefaults;
impl ManagedSessionDefaults {
    pub(crate) fn set_model_blocked() -> ApiResult<()> {
        Err(ApiError::unavailable_capability(
            "BLOCKED",
            super::ATTACHED_MODEL_REASON,
        ))
    }
}
