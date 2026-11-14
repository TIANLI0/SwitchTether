//! Bridge to CC Switch's existing service layer.
//!
//! The Remote API never re-implements CC Switch business rules (provider
//! switching, live-config writes, usage accounting, MCP/skill sync…). It calls
//! this trait, which the `switchtether` app crate implements on top of the
//! upstream services (`ProviderService`, `Database` usage queries,
//! `session_manager`, `McpService`, …) — reusing their validation, atomic
//! writes, conflict checks and backups.
//!
//! Values are exchanged as JSON so that this crate does not depend on upstream
//! types. Implementations MUST redact secrets; the API layer additionally runs
//! [`crate::redact::redact_json`] over provider/MCP views as defense in depth.

use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UsageQuery {
    /// Unix seconds (inclusive).
    pub from: Option<i64>,
    /// Unix seconds (inclusive).
    pub to: Option<i64>,
    pub app: Option<String>,
    pub provider: Option<String>,
    pub model: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct IndexedSession {
    /// CC Switch provider id of the session source (e.g. "claude", "codex", "gemini").
    pub provider_id: String,
    pub session_id: String,
    pub title: Option<String>,
    pub summary: Option<String>,
    pub project_dir: Option<String>,
    pub created_at_ms: Option<i64>,
    pub last_active_at_ms: Option<i64>,
    pub source_path: Option<String>,
    pub resume_command: Option<String>,
}

pub type BackendResult<T> = Result<T, String>;

#[async_trait]
pub trait CcSwitchBackend: Send + Sync + 'static {
    /// False while CC Switch is not initialised (e.g. DB migration screen).
    fn available(&self) -> bool {
        true
    }

    // ---- providers ----
    async fn app_types(&self) -> BackendResult<Value>;
    /// `{ app, currentId, providers: [ProviderView] }` with secrets redacted.
    async fn providers(&self, app: &str) -> BackendResult<Value>;
    async fn switch_provider(&self, app: &str, id: &str) -> BackendResult<Value>;
    async fn test_provider(&self, app: &str, id: &str) -> BackendResult<Value>;
    /// Write-only credential update. `field` is one of the provider's secret
    /// fields as listed in its view (`secretFields`).
    async fn set_provider_credential(
        &self,
        app: &str,
        id: &str,
        field: &str,
        value: &str,
    ) -> BackendResult<Value>;

    // ---- usage ----
    async fn usage_summary(&self, q: UsageQuery) -> BackendResult<Value>;
    async fn usage_trends(&self, q: UsageQuery) -> BackendResult<Value>;
    async fn usage_models(&self, q: UsageQuery) -> BackendResult<Value>;
    async fn usage_providers(&self, q: UsageQuery) -> BackendResult<Value>;
    async fn quota(&self, tool: &str) -> BackendResult<Value>;

    // ---- MCP / skills / prompts ----
    async fn mcp_servers(&self) -> BackendResult<Value>;
    async fn mcp_toggle(&self, id: &str, app: &str, enabled: bool) -> BackendResult<Value>;
    async fn skills(&self) -> BackendResult<Value>;
    async fn skill_toggle(&self, id: &str, app: &str, enabled: bool) -> BackendResult<Value>;
    async fn prompts(&self, app: &str) -> BackendResult<Value>;
    async fn prompt_enable(&self, app: &str, id: &str) -> BackendResult<Value>;

    // ---- settings / proxy / health ----
    async fn settings(&self) -> BackendResult<Value>;
    async fn proxy_status(&self) -> BackendResult<Value>;
    async fn health(&self) -> BackendResult<Value>;

    // ---- session index (all agents CC Switch knows about) ----
    async fn session_index(&self) -> BackendResult<Vec<IndexedSession>>;
    /// Plain role/text transcript for agents without a rich adapter.
    async fn session_messages(&self, provider_id: &str, source_path: &str) -> BackendResult<Value>;
}

/// Backend used in tests and when CC Switch is not initialised.
pub struct UnavailableBackend;

#[async_trait]
impl CcSwitchBackend for UnavailableBackend {
    fn available(&self) -> bool {
        false
    }
    async fn app_types(&self) -> BackendResult<Value> {
        Ok(json!([]))
    }
    async fn providers(&self, _app: &str) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn switch_provider(&self, _a: &str, _i: &str) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn test_provider(&self, _a: &str, _i: &str) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn set_provider_credential(
        &self,
        _a: &str,
        _i: &str,
        _f: &str,
        _v: &str,
    ) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn usage_summary(&self, _q: UsageQuery) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn usage_trends(&self, _q: UsageQuery) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn usage_models(&self, _q: UsageQuery) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn usage_providers(&self, _q: UsageQuery) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn quota(&self, _t: &str) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn mcp_servers(&self) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn mcp_toggle(&self, _i: &str, _a: &str, _e: bool) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn skills(&self) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn skill_toggle(&self, _i: &str, _a: &str, _e: bool) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn prompts(&self, _a: &str) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn prompt_enable(&self, _a: &str, _i: &str) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn settings(&self) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn proxy_status(&self) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
    async fn health(&self) -> BackendResult<Value> {
        Ok(json!({ "backend": "unavailable" }))
    }
    async fn session_index(&self) -> BackendResult<Vec<IndexedSession>> {
        Ok(Vec::new())
    }
    async fn session_messages(&self, _p: &str, _s: &str) -> BackendResult<Value> {
        Err("backend unavailable".into())
    }
}
