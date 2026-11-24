//! CC Switch management endpoints (providers, usage, MCP, skills, prompts,
//! settings, proxy, health). Every call goes through [`CcSwitchBackend`], i.e.
//! the upstream services with their own validation and atomic writes.

use std::sync::Arc;

use axum::extract::{Path, Query, State};
use axum::response::Response;
use axum::routing::{get, post, put};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};

use super::{backend_err, idempotent, require_step_up, ApiContext, AuthDevice};
use crate::backend::UsageQuery;
use crate::error::{ApiError, ApiResult};
use crate::stepup::Scope;

pub fn routes() -> Router<Arc<ApiContext>> {
    Router::new()
        .route("/v1/providers/apps", get(app_types))
        .route("/v1/providers", get(providers))
        .route("/v1/providers/:app/:id/switch", post(switch_provider))
        .route("/v1/providers/:app/:id/test", post(test_provider))
        .route("/v1/providers/:app/:id/credential", put(set_credential))
        .route("/v1/usage/summary", get(usage_summary))
        .route("/v1/usage/trends", get(usage_trends))
        .route("/v1/usage/models", get(usage_models))
        .route("/v1/usage/providers", get(usage_providers))
        .route("/v1/usage/logs", get(usage_logs))
        .route("/v1/usage/logs/:id", get(usage_log))
        .route("/v1/usage/first-date", get(usage_first_date))
        .route("/v1/usage/last-sync", get(usage_last_sync))
        .route("/v1/usage/projects", get(usage_projects))
        .route("/v1/usage/quota/:tool", get(quota))
        .route("/v1/mcp/servers", get(mcp_servers))
        .route("/v1/mcp/servers/:id/toggle", post(mcp_toggle))
        .route("/v1/skills", get(skills))
        .route("/v1/skills/:id/toggle", post(skill_toggle))
        .route("/v1/prompts", get(prompts))
        .route("/v1/prompts/:app/:id/enable", post(prompt_enable))
        .route("/v1/settings", get(settings))
        .route("/v1/proxy/status", get(proxy_status))
        .route("/v1/health", get(health))
}

fn validate_ident(s: &str, what: &str) -> ApiResult<()> {
    if s.is_empty() || s.len() > 200 || s.chars().any(|c| c.is_control() || c == '/' || c == '\\') {
        return Err(ApiError::validation(format!("invalid {what}")));
    }
    Ok(())
}

fn redacted(mut v: Value) -> Value {
    crate::redact::redact_json(&mut v);
    v
}

async fn app_types(State(ctx): State<Arc<ApiContext>>, _d: AuthDevice) -> ApiResult<Json<Value>> {
    ctx.backend
        .app_types()
        .await
        .map(Json)
        .map_err(|e| backend_err(&ctx, e))
}

#[derive(Deserialize)]
struct AppQuery {
    app: Option<String>,
}

async fn providers(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(q): Query<AppQuery>,
) -> ApiResult<Json<Value>> {
    let app = q.app.unwrap_or_else(|| "claude".into());
    validate_ident(&app, "app")?;
    let v = ctx
        .backend
        .providers(&app)
        .await
        .map_err(|e| backend_err(&ctx, e))?;
    Ok(Json(redacted(v)))
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct CmdBody {
    command_id: Option<String>,
}

async fn switch_provider(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path((app, id)): Path<(String, String)>,
    Json(body): Json<CmdBody>,
) -> Response {
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "provider.switch",
        || async move {
            validate_ident(&app, "app")?;
            validate_ident(&id, "provider id")?;
            let r = c
                .backend
                .switch_provider(&app, &id)
                .await
                .map_err(|e| backend_err(&c, e))?;
            c.registry.audit(
                Some(dev.id()),
                "provider.switch",
                Some(&format!("{app}/{id}")),
                "ok",
                None,
            );
            c.events.publish(
                "provider.switched",
                json!({ "app": app, "providerId": id, "by": "remote" }),
            );
            Ok(redacted(r))
        },
    )
    .await
}

async fn test_provider(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path((app, id)): Path<(String, String)>,
    Json(body): Json<CmdBody>,
) -> Response {
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "provider.test",
        || async move {
            validate_ident(&app, "app")?;
            validate_ident(&id, "provider id")?;
            let r = c
                .backend
                .test_provider(&app, &id)
                .await
                .map_err(|e| backend_err(&c, e))?;
            Ok(redacted(r))
        },
    )
    .await
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CredentialBody {
    command_id: Option<String>,
    field: String,
    value: String,
}

async fn set_credential(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path((app, id)): Path<(String, String)>,
    Json(body): Json<CredentialBody>,
) -> Response {
    if let Err(e) = require_step_up(&ctx, &dev, Scope::ConfigWrite) {
        return axum::response::IntoResponse::into_response(e);
    }
    let c = ctx.clone();
    let CredentialBody {
        command_id,
        field,
        value,
    } = body;
    idempotent(
        &ctx,
        dev.id().to_string(),
        command_id,
        "provider.credential",
        || async move {
            validate_ident(&app, "app")?;
            validate_ident(&id, "provider id")?;
            validate_ident(&field, "field")?;
            if value.len() > 8192 || value.chars().any(|c| c == '\n' || c == '\r' || c == '\0') {
                return Err(ApiError::validation("invalid credential value"));
            }
            let r = c
                .backend
                .set_provider_credential(&app, &id, &field, &value)
                .await
                .map_err(|e| backend_err(&c, e))?;
            // The value itself is never logged or audited.
            c.registry.audit(
                Some(dev.id()),
                "provider.credential.update",
                Some(&format!("{app}/{id}:{field}")),
                "ok",
                None,
            );
            Ok(redacted(r))
        },
    )
    .await
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct UsageParams {
    from: Option<i64>,
    to: Option<i64>,
    app: Option<String>,
    provider: Option<String>,
    model: Option<String>,
}

impl From<UsageParams> for UsageQuery {
    fn from(p: UsageParams) -> Self {
        UsageQuery {
            from: p.from,
            to: p.to,
            app: p.app,
            provider: p.provider,
            model: p.model,
        }
    }
}

macro_rules! usage_route {
    ($name:ident, $method:ident) => {
        async fn $name(
            State(ctx): State<Arc<ApiContext>>,
            _d: AuthDevice,
            Query(p): Query<UsageParams>,
        ) -> ApiResult<Json<Value>> {
            ctx.backend
                .$method(p.into())
                .await
                .map(Json)
                .map_err(|e| backend_err(&ctx, e))
        }
    };
}

usage_route!(usage_summary, usage_summary);
usage_route!(usage_trends, usage_trends);
usage_route!(usage_models, usage_models);
usage_route!(usage_providers, usage_providers);

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ProjectParams {
    from: Option<i64>,
    to: Option<i64>,
    app: Option<String>,
    /// One project only (its name as the session list shows it, or a working directory).
    project: Option<String>,
}

/// `GET /v1/usage/projects?from&to&app&project` — usage grouped by project (session index join).
async fn usage_projects(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(p): Query<ProjectParams>,
) -> ApiResult<Json<Value>> {
    if let Some(pr) = p.project.as_deref() {
        if pr.is_empty() || pr.len() > 4096 || pr.chars().any(char::is_control) {
            return Err(ApiError::validation("invalid project"));
        }
    }
    let q = UsageQuery {
        from: p.from,
        to: p.to,
        app: p.app,
        provider: None,
        model: None,
    };
    let raw = ctx
        .backend
        .usage_sessions(q)
        .await
        .map_err(|e| backend_err(&ctx, e))?;
    let rows: Vec<crate::usage_projects::SessionUsageRow> =
        serde_json::from_value(raw).unwrap_or_default();
    let index = ctx.agents.session_index().await;
    let view = crate::usage_projects::group_by_project(&rows, &index, p.project.as_deref());
    Ok(Json(serde_json::to_value(view).unwrap_or(Value::Null)))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LogParams {
    // No `#[serde(flatten)]` here: with a flattened struct serde_urlencoded hands every value over
    // as a string, so `page=0` failed to parse as a number and every request answered 400
    // ("请求日志加载失败" on the phone).
    from: Option<i64>,
    to: Option<i64>,
    app: Option<String>,
    provider: Option<String>,
    model: Option<String>,
    status: Option<u16>,
    page: Option<u32>,
    page_size: Option<u32>,
}

impl LogParams {
    fn usage(&self) -> UsageQuery {
        UsageQuery {
            from: self.from,
            to: self.to,
            app: self.app.clone(),
            provider: self.provider.clone(),
            model: self.model.clone(),
        }
    }
}

/// `GET /v1/usage/logs?from&to&app&provider&model&status&page&pageSize`
/// (page is 0-based, pageSize 1..=100, default 20).
async fn usage_logs(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(p): Query<LogParams>,
) -> ApiResult<Json<Value>> {
    let page_size = p.page_size.unwrap_or(20).clamp(1, 100);
    ctx.backend
        .usage_logs(p.usage(), p.status, p.page.unwrap_or(0), page_size)
        .await
        .map(|mut v| {
            crate::redact::redact_json(&mut v);
            Json(v)
        })
        .map_err(|e| backend_err(&ctx, e))
}

async fn usage_log(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    if id.is_empty()
        || id.len() > 128
        || !id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "-_:.".contains(c))
    {
        return Err(ApiError::validation("invalid request id"));
    }
    match ctx.backend.usage_log(&id).await {
        Ok(Value::Null) => Err(ApiError::not_found("no such request")),
        Ok(mut v) => {
            crate::redact::redact_json(&mut v);
            Ok(Json(v))
        }
        Err(e) => Err(backend_err(&ctx, e)),
    }
}

async fn usage_first_date(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(p): Query<UsageParams>,
) -> ApiResult<Json<Value>> {
    ctx.backend
        .usage_first_date(p.into())
        .await
        .map(Json)
        .map_err(|e| backend_err(&ctx, e))
}

async fn usage_last_sync(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
) -> ApiResult<Json<Value>> {
    ctx.backend
        .usage_last_sync()
        .await
        .map(Json)
        .map_err(|e| backend_err(&ctx, e))
}

async fn quota(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Path(tool): Path<String>,
) -> ApiResult<Json<Value>> {
    validate_ident(&tool, "tool")?;
    match ctx.backend.quota(&tool).await {
        Ok(v) => Ok(Json(redacted(v))),
        // Quota sources are often unavailable (not logged in, API not exposed).
        // Never fabricate numbers: report unavailability with the reason.
        Err(e) => Ok(Json(json!({
            "tool": tool,
            "available": false,
            "reason": crate::redact::redact_text(&e),
        }))),
    }
}

async fn mcp_servers(State(ctx): State<Arc<ApiContext>>, _d: AuthDevice) -> ApiResult<Json<Value>> {
    let v = ctx
        .backend
        .mcp_servers()
        .await
        .map_err(|e| backend_err(&ctx, e))?;
    Ok(Json(redacted(v)))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ToggleBody {
    command_id: Option<String>,
    app: String,
    enabled: bool,
}

async fn mcp_toggle(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(id): Path<String>,
    Json(body): Json<ToggleBody>,
) -> Response {
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "mcp.toggle",
        || async move {
            validate_ident(&id, "server id")?;
            validate_ident(&body.app, "app")?;
            let r = c
                .backend
                .mcp_toggle(&id, &body.app, body.enabled)
                .await
                .map_err(|e| backend_err(&c, e))?;
            c.registry.audit(
                Some(dev.id()),
                "mcp.toggle",
                Some(&format!("{id}@{}={}", body.app, body.enabled)),
                "ok",
                None,
            );
            Ok(redacted(r))
        },
    )
    .await
}

async fn skills(State(ctx): State<Arc<ApiContext>>, _d: AuthDevice) -> ApiResult<Json<Value>> {
    ctx.backend
        .skills()
        .await
        .map(|v| Json(redacted(v)))
        .map_err(|e| backend_err(&ctx, e))
}

async fn skill_toggle(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(id): Path<String>,
    Json(body): Json<ToggleBody>,
) -> Response {
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "skill.toggle",
        || async move {
            validate_ident(&id, "skill id")?;
            validate_ident(&body.app, "app")?;
            let r = c
                .backend
                .skill_toggle(&id, &body.app, body.enabled)
                .await
                .map_err(|e| backend_err(&c, e))?;
            c.registry.audit(
                Some(dev.id()),
                "skill.toggle",
                Some(&format!("{id}@{}={}", body.app, body.enabled)),
                "ok",
                None,
            );
            Ok(r)
        },
    )
    .await
}

async fn prompts(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(q): Query<AppQuery>,
) -> ApiResult<Json<Value>> {
    let app = q.app.unwrap_or_else(|| "claude".into());
    validate_ident(&app, "app")?;
    ctx.backend
        .prompts(&app)
        .await
        .map(Json)
        .map_err(|e| backend_err(&ctx, e))
}

async fn prompt_enable(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path((app, id)): Path<(String, String)>,
    Json(body): Json<CmdBody>,
) -> Response {
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "prompt.enable",
        || async move {
            validate_ident(&app, "app")?;
            validate_ident(&id, "prompt id")?;
            let r = c
                .backend
                .prompt_enable(&app, &id)
                .await
                .map_err(|e| backend_err(&c, e))?;
            c.registry.audit(
                Some(dev.id()),
                "prompt.enable",
                Some(&format!("{app}/{id}")),
                "ok",
                None,
            );
            Ok(r)
        },
    )
    .await
}

async fn settings(State(ctx): State<Arc<ApiContext>>, _d: AuthDevice) -> ApiResult<Json<Value>> {
    ctx.backend
        .settings()
        .await
        .map(|v| Json(redacted(v)))
        .map_err(|e| backend_err(&ctx, e))
}

async fn proxy_status(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
) -> ApiResult<Json<Value>> {
    ctx.backend
        .proxy_status()
        .await
        .map(|v| Json(redacted(v)))
        .map_err(|e| backend_err(&ctx, e))
}

async fn health(State(ctx): State<Arc<ApiContext>>, _d: AuthDevice) -> ApiResult<Json<Value>> {
    let backend = ctx
        .backend
        .health()
        .await
        .unwrap_or_else(|e| json!({ "error": e }));
    Ok(Json(json!({
        "backendAvailable": ctx.backend.available(),
        "backend": redacted(backend),
        "agents": ctx.agents.health().await,
    })))
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::extract::Query;
    use axum::http::Uri;

    #[test]
    fn request_log_query_parses_numbers() {
        let uri: Uri = "/v1/usage/logs?from=1700000000&to=1700086400&app=claude&page=0&pageSize=20"
            .parse()
            .unwrap();
        let Query(p) = Query::<LogParams>::try_from_uri(&uri).expect("query parses");
        assert_eq!(p.page, Some(0));
        assert_eq!(p.page_size, Some(20));
        let q = p.usage();
        assert_eq!(q.from, Some(1_700_000_000));
        assert_eq!(q.app.as_deref(), Some("claude"));
    }
}
