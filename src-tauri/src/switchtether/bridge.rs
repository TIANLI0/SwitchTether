//! [`CcSwitchBackend`] implementation over CC Switch's existing services.
//!
//! Every remote operation calls the same upstream service the desktop UI uses
//! (validation, atomic live-config writes, backups and conflict handling are
//! inherited). Secrets are redacted structurally here and again in the API
//! layer. Desktop UI/tray are refreshed after remote changes so both sides
//! show the same state.

use std::str::FromStr;

use async_trait::async_trait;
use serde_json::{json, Value};
use switchtether_core::backend::{BackendResult, CcSwitchBackend, IndexedSession, UsageQuery};
use tauri::{Emitter, Manager};

use crate::app_config::AppType;
use crate::services::{McpService, PromptService, ProviderService, SkillService};
use crate::store::AppState;

/// App types exposed to the phone, in display order.
const APP_TYPES: &[&str] = &[
    "claude",
    "codex",
    "gemini",
    "claude-desktop",
    "grokbuild",
    "opencode",
    "openclaw",
    "hermes",
    "pi",
    "mcode",
    "kimi",
    "dsh",
    "agy",
    "omp",
    "copilot",
    "workbuddy",
];

pub struct CcSwitchBridge {
    app: tauri::AppHandle,
}

impl CcSwitchBridge {
    pub fn new(app: tauri::AppHandle) -> Self {
        Self { app }
    }

    fn state(&self) -> Result<AppState, String> {
        self.app
            .try_state::<AppState>()
            .map(|s| s.inner().clone())
            .ok_or_else(|| "CC Switch is not initialised".to_string())
    }

    async fn blocking<T: Send + 'static>(
        &self,
        f: impl FnOnce(AppState) -> Result<T, String> + Send + 'static,
    ) -> BackendResult<T> {
        let state = self.state()?;
        tokio::task::spawn_blocking(move || f(state))
            .await
            .map_err(|e| format!("task failed: {e}"))?
    }

    fn after_provider_change(&self, app_type: &AppType, provider_id: &str) {
        let proxy_enabled = crate::mode::current::is_proxy(app_type);
        let data = json!({
            "appType": app_type.as_str(),
            "proxyEnabled": proxy_enabled,
            "autoFailoverEnabled": false,
            "providerId": provider_id,
            "source": "remote",
        });
        let _ = self.app.emit("provider-switched", data);
        crate::tray::schedule_tray_refresh(&self.app);
    }
}

fn parse_app(app: &str) -> Result<AppType, String> {
    AppType::from_str(app).map_err(|e| e.to_string())
}

fn to_json<T: serde::Serialize>(v: T) -> Result<Value, String> {
    serde_json::to_value(v).map_err(|e| e.to_string())
}

/// JSON pointers of secret-bearing string leaves, with configured/hint.
pub fn secret_fields(config: &Value) -> Vec<Value> {
    fn walk(v: &Value, path: &str, out: &mut Vec<Value>) {
        match v {
            Value::Object(m) => {
                for (k, child) in m {
                    let p = format!("{path}/{}", k.replace('~', "~0").replace('/', "~1"));
                    if let Value::String(s) = child {
                        if ProviderService::is_sensitive_config_key(k)
                            || switchtether_core::redact::is_sensitive_key(k)
                        {
                            out.push(json!({
                                "field": p,
                                "name": k,
                                "configured": !s.trim().is_empty(),
                                "hint": switchtether_core::redact::mask_secret(s),
                            }));
                            continue;
                        }
                    }
                    walk(child, &p, out);
                }
            }
            Value::Array(a) => {
                for (i, child) in a.iter().enumerate() {
                    walk(child, &format!("{path}/{i}"), out);
                }
            }
            _ => {}
        }
    }
    let mut out = Vec::new();
    walk(config, "", &mut out);
    out
}

/// Same rule as `commands::stream_check::is_copilot_provider` (kept private upstream).
fn is_copilot(provider: &crate::provider::Provider) -> bool {
    provider
        .meta
        .as_ref()
        .and_then(|m| m.provider_type.as_deref())
        == Some("github_copilot")
        || provider
            .settings_config
            .pointer("/env/ANTHROPIC_BASE_URL")
            .and_then(|v| v.as_str())
            .is_some_and(|u| u.contains("githubcopilot.com"))
}

fn provider_view(p: &crate::provider::Provider, current: &str) -> Value {
    let mut settings = p.settings_config.clone();
    switchtether_core::redact::redact_json(&mut settings);
    json!({
        "id": p.id,
        "name": p.name,
        "category": p.category,
        "websiteUrl": p.website_url,
        "icon": p.icon,
        "notes": p.notes,
        "sortIndex": p.sort_index,
        "isCurrent": p.id == current,
        "settingsConfig": settings,
        "secretFields": secret_fields(&p.settings_config),
    })
}

#[async_trait]
impl CcSwitchBackend for CcSwitchBridge {
    fn available(&self) -> bool {
        self.app.try_state::<AppState>().is_some()
    }

    async fn app_types(&self) -> BackendResult<Value> {
        Ok(json!(APP_TYPES))
    }

    async fn providers(&self, app: &str) -> BackendResult<Value> {
        let app_type = parse_app(app)?;
        let app_name = app.to_string();
        self.blocking(move |state| {
            let list =
                ProviderService::list(&state, app_type.clone()).map_err(|e| e.to_string())?;
            let current = ProviderService::current(&state, app_type).unwrap_or_default();
            let providers: Vec<Value> = list.values().map(|p| provider_view(p, &current)).collect();
            Ok(json!({ "app": app_name, "currentId": current, "providers": providers }))
        })
        .await
    }

    async fn switch_provider(&self, app: &str, id: &str) -> BackendResult<Value> {
        let app_type = parse_app(app)?;
        let (a2, id2) = (app_type.clone(), id.to_string());
        let result = self
            .blocking(move |state| {
                crate::commands::switch_provider_internal(&state, a2, &id2)
                    .map_err(|e| e.to_string())
                    .and_then(to_json)
            })
            .await?;
        self.after_provider_change(&app_type, id);
        Ok(result)
    }

    async fn test_provider(&self, app: &str, id: &str) -> BackendResult<Value> {
        let app_type = parse_app(app)?;
        let state = self.state()?;
        let providers = state
            .db
            .get_all_providers(app_type.as_str())
            .map_err(|e| e.to_string())?;
        let provider = providers
            .get(id)
            .cloned()
            .ok_or_else(|| format!("provider {id} not found"))?;
        if is_copilot(&provider) {
            return Err(
                "Copilot connection tests need the desktop app (OAuth endpoint resolution)".into(),
            );
        }
        let r = crate::services::stream_check::StreamCheckService::check_with_retry(
            &app_type,
            &provider,
            &crate::services::stream_check::StreamCheckConfig::default(),
            None,
        )
        .await
        .map_err(|e| e.to_string())?;
        to_json(r)
    }

    async fn set_provider_credential(
        &self,
        app: &str,
        id: &str,
        field: &str,
        value: &str,
    ) -> BackendResult<Value> {
        let app_type = parse_app(app)?;
        let (a2, id2, field2, value2) = (
            app_type.clone(),
            id.to_string(),
            field.to_string(),
            value.to_string(),
        );
        self.blocking(move |state| {
            let list = ProviderService::list(&state, a2.clone()).map_err(|e| e.to_string())?;
            let mut provider = list
                .get(&id2)
                .cloned()
                .ok_or_else(|| format!("provider {id2} not found"))?;
            // Only existing secret fields can be written (no arbitrary config edits).
            let allowed = secret_fields(&provider.settings_config)
                .iter()
                .any(|f| f["field"].as_str() == Some(field2.as_str()));
            if !allowed {
                return Err("field is not a credential field of this provider".into());
            }
            let slot = provider
                .settings_config
                .pointer_mut(&field2)
                .ok_or_else(|| "field not found".to_string())?;
            *slot = Value::String(value2);
            ProviderService::update(&state, a2, None, provider).map_err(|e| e.to_string())?;
            Ok(json!({ "updated": true, "field": field2 }))
        })
        .await?;
        self.after_provider_change(&app_type, id);
        Ok(json!({ "updated": true }))
    }

    async fn usage_summary(&self, q: UsageQuery) -> BackendResult<Value> {
        self.blocking(move |s| {
            let total =
                s.db.get_usage_summary(
                    q.from,
                    q.to,
                    q.app.as_deref(),
                    q.provider.as_deref(),
                    q.model.as_deref(),
                )
                .map_err(|e| e.to_string())?;
            let by_app = s
                .db
                .get_usage_summary_by_app(q.from, q.to, q.provider.as_deref(), q.model.as_deref())
                .map_err(|e| e.to_string())?;
            Ok(json!({ "summary": to_json(total)?, "byApp": to_json(by_app)? }))
        })
        .await
    }

    async fn usage_trends(&self, q: UsageQuery) -> BackendResult<Value> {
        self.blocking(move |s| {
            s.db.get_daily_trends(
                q.from,
                q.to,
                q.app.as_deref(),
                q.provider.as_deref(),
                q.model.as_deref(),
            )
            .map_err(|e| e.to_string())
            .and_then(|v| to_json(v).map(|d| json!({ "days": d })))
        })
        .await
    }

    async fn usage_models(&self, q: UsageQuery) -> BackendResult<Value> {
        self.blocking(move |s| {
            s.db.get_model_stats(
                q.from,
                q.to,
                q.app.as_deref(),
                q.provider.as_deref(),
                q.model.as_deref(),
            )
            .map_err(|e| e.to_string())
            .and_then(|v| to_json(v).map(|d| json!({ "models": d })))
        })
        .await
    }

    async fn usage_providers(&self, q: UsageQuery) -> BackendResult<Value> {
        self.blocking(move |s| {
            s.db.get_provider_stats(
                q.from,
                q.to,
                q.app.as_deref(),
                q.provider.as_deref(),
                q.model.as_deref(),
            )
            .map_err(|e| e.to_string())
            .and_then(|v| to_json(v).map(|d| json!({ "providers": d })))
        })
        .await
    }

    async fn quota(&self, tool: &str) -> BackendResult<Value> {
        let q = crate::services::subscription::get_subscription_quota(tool).await?;
        to_json(q)
    }

    async fn mcp_servers(&self) -> BackendResult<Value> {
        self.blocking(|s| {
            let servers = McpService::get_all_servers(&s).map_err(|e| e.to_string())?;
            Ok(json!({ "servers": servers.values().collect::<Vec<_>>() }))
        })
        .await
    }

    async fn mcp_toggle(&self, id: &str, app: &str, enabled: bool) -> BackendResult<Value> {
        let app_type = parse_app(app)?;
        let id = id.to_string();
        let r = self
            .blocking(move |s| {
                McpService::toggle_app(&s, &id, app_type, enabled).map_err(|e| e.to_string())?;
                Ok(json!({ "ok": true }))
            })
            .await;
        let _ = self.app.emit("mcp-changed", json!({ "source": "remote" }));
        r
    }

    async fn skills(&self) -> BackendResult<Value> {
        self.blocking(|s| {
            SkillService::get_all_installed(&s.db)
                .map_err(|e| e.to_string())
                .and_then(|v| to_json(v).map(|sk| json!({ "skills": sk })))
        })
        .await
    }

    async fn skill_toggle(&self, id: &str, app: &str, enabled: bool) -> BackendResult<Value> {
        let app_type = parse_app(app)?;
        let id = id.to_string();
        self.blocking(move |s| {
            SkillService::toggle_app(&s.db, &id, &app_type, enabled).map_err(|e| e.to_string())?;
            Ok(json!({ "ok": true }))
        })
        .await
    }

    async fn prompts(&self, app: &str) -> BackendResult<Value> {
        let app_type = parse_app(app)?;
        self.blocking(move |s| {
            let p = PromptService::get_prompts(&s, app_type).map_err(|e| e.to_string())?;
            Ok(json!({ "prompts": p.values().collect::<Vec<_>>() }))
        })
        .await
    }

    async fn prompt_enable(&self, app: &str, id: &str) -> BackendResult<Value> {
        let app_type = parse_app(app)?;
        let id = id.to_string();
        self.blocking(move |s| {
            PromptService::enable_prompt(&s, app_type, &id).map_err(|e| e.to_string())?;
            Ok(json!({ "ok": true }))
        })
        .await
    }

    async fn settings(&self) -> BackendResult<Value> {
        let s = crate::settings::get_settings_for_frontend();
        let v = to_json(s)?;
        // Whitelist: only display-relevant, non-sensitive settings.
        let keys = [
            "language",
            "theme",
            "minimizeToTrayOnClose",
            "launchOnStartup",
            "silentStartup",
            "claudeConfigDir",
            "codexConfigDir",
            "geminiConfigDir",
            "quotaDisplay",
        ];
        let mut out = serde_json::Map::new();
        for k in keys {
            if let Some(x) = v.get(k) {
                out.insert(k.to_string(), x.clone());
            }
        }
        Ok(Value::Object(out))
    }

    async fn proxy_status(&self) -> BackendResult<Value> {
        let state = self.state()?;
        let status = state.proxy_service.get_status().await?;
        to_json(status)
    }

    async fn health(&self) -> BackendResult<Value> {
        let state = self.state()?;
        let running = state.proxy_service.is_running().await;
        Ok(json!({
            "database": "ok",
            "proxyRunning": running,
            "dataDir": "~/.switchtether",
        }))
    }

    async fn session_index(&self) -> BackendResult<Vec<IndexedSession>> {
        tokio::task::spawn_blocking(|| {
            crate::session_manager::scan_sessions()
                .into_iter()
                .map(|m| IndexedSession {
                    provider_id: m.provider_id,
                    session_id: m.session_id,
                    title: m.title,
                    summary: m.summary,
                    project_dir: m.project_dir,
                    created_at_ms: m.created_at,
                    last_active_at_ms: m.last_active_at,
                    source_path: m.source_path,
                    resume_command: m.resume_command,
                })
                .collect()
        })
        .await
        .map_err(|e| e.to_string())
    }

    async fn session_messages(&self, provider_id: &str, source_path: &str) -> BackendResult<Value> {
        let (p, s) = (provider_id.to_string(), source_path.to_string());
        tokio::task::spawn_blocking(move || {
            // load_transcript validates that source_path belongs to the provider.
            let loaded = crate::session_manager::load_transcript(&p, &s)?;
            let msgs: Vec<Value> = loaded
                .transcript
                .messages
                .iter()
                .map(|m| json!({ "role": m.role, "content": m.content, "ts": m.ts }))
                .collect();
            Ok(Value::Array(msgs))
        })
        .await
        .map_err(|e| e.to_string())?
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn secret_fields_are_pointers_with_hints() {
        let cfg = json!({
            "env": { "ANTHROPIC_AUTH_TOKEN": "sk-ant-0123456789abcdefWXYZ", "ANTHROPIC_BASE_URL": "https://x" },
            "auth": { "OPENAI_API_KEY": "" }
        });
        let f = secret_fields(&cfg);
        let tok = f
            .iter()
            .find(|x| x["field"] == "/env/ANTHROPIC_AUTH_TOKEN")
            .unwrap();
        assert_eq!(tok["configured"], true);
        assert_eq!(tok["hint"], "…WXYZ");
        let oai = f
            .iter()
            .find(|x| x["field"] == "/auth/OPENAI_API_KEY")
            .unwrap();
        assert_eq!(oai["configured"], false);
        assert!(!f.iter().any(|x| x["field"] == "/env/ANTHROPIC_BASE_URL"));
        let view = provider_view(
            &serde_json::from_value(json!({"id":"p","name":"P","settingsConfig": cfg})).unwrap(),
            "p",
        );
        assert!(!view.to_string().contains("0123456789abcdef"));
        assert_eq!(view["isCurrent"], true);
    }
}
