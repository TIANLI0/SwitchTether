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
    /// Slash commands the running Claude Code process reported (`{name, description,
    /// argumentHint}`), empty when unknown.
    fn claude_commands(&self) -> Vec<Value> {
        Vec::new()
    }
    /// Current permission mode (`default`, `acceptEdits`, `plan`, …) when known.
    fn permission_mode(&self) -> Option<String> {
        None
    }
    /// The phone may change the permission mode (ids of `crate::agents::permission`).
    fn can_change_permission_mode(&self) -> bool {
        false
    }
    /// Change the permission mode; returns the mode in effect (or chosen for the next turn).
    async fn set_permission_mode(&self, _mode: &str) -> ApiResult<String> {
        Err(ApiError::unavailable_capability(
            "BLOCKED",
            "This session cannot change its permission mode from the phone.",
        ))
    }
    /// Session tools of an agent other than Claude Code, answered by [`Self::session_tool`]
    /// (`POST /v1/sessions/{id}/claude/{action}`, same reply shapes).
    fn session_tools(&self) -> &'static [&'static str] {
        &[]
    }
    async fn session_tool(&self, _action: &str, _p: &Value) -> ApiResult<Value> {
        Err(ApiError::unavailable_capability(
            "BLOCKED",
            "This session does not offer session tools.",
        ))
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
    fn claude_commands(&self) -> Vec<Value> {
        super::claude::managed::ManagedClaude::commands(self)
    }
    fn permission_mode(&self) -> Option<String> {
        super::claude::managed::ManagedClaude::permission_mode(self)
    }
    fn can_change_permission_mode(&self) -> bool {
        true
    }
    async fn set_permission_mode(&self, mode: &str) -> ApiResult<String> {
        let mode = super::permission::normalize("claude_code", mode)
            .ok_or_else(|| ApiError::validation("unknown permission mode"))?;
        let r = super::claude::managed::ManagedClaude::control(
            self,
            serde_json::json!({ "subtype": "set_permission_mode", "mode": mode }),
            std::time::Duration::from_secs(20),
        )
        .await?;
        Ok(r.get("mode")
            .and_then(Value::as_str)
            .unwrap_or(mode)
            .to_string())
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
        // A process SwitchTether started, and a session attached through the opt-in takeover hook
        // (VS Code panel): `set_model` / `apply_flag_settings` are the same control requests the
        // IDE sends, injected through switchtether-shim. The IDE's own picker learns of the change
        // only from the next turn's model; a later change made in the IDE wins again (it is
        // observed and shown on the phone too). Not a mirrored terminal (no control channel).
        true
    }
    async fn models(&self) -> Value {
        super::claude::managed::ManagedClaude::models(self)
    }
    async fn set_model(&self, model: Option<&str>) -> ApiResult<()> {
        super::claude::managed::ManagedClaude::set_model(self, model).await
    }
    async fn set_effort(&self, effort: Option<&str>) -> ApiResult<()> {
        super::claude::managed::ManagedClaude::set_effort(self, effort).await
    }
}
