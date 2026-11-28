//! Session endpoints.

use std::sync::Arc;

use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, Path, Query, State};
use axum::response::Response;
use axum::routing::{get, post, put};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};

use super::{idempotent, require_step_up, ApiContext, AuthDevice};
use crate::agents::{permission, CreateSession, SessionQuery};
use crate::error::ApiResult;
use crate::stepup::Scope;

/// Running an agent without approvals (`bypassPermissions`, Codex full access) is as powerful as
/// a remote terminal: it needs the terminal's biometric step-up.
fn full_access_check(ctx: &ApiContext, dev: &AuthDevice, mode: Option<&str>) -> Option<Response> {
    if !mode.is_some_and(permission::needs_step_up) {
        return None;
    }
    require_step_up(ctx, dev, Scope::Terminal)
        .err()
        .map(axum::response::IntoResponse::into_response)
}

pub fn routes() -> Router<Arc<ApiContext>> {
    Router::new()
        .route("/v1/sessions", get(list).post(create))
        .route("/v1/sessions/search", get(search))
        .route("/v1/sessions/:id", get(detail))
        .route("/v1/sessions/:id/events", get(events))
        .route("/v1/sessions/:id/subagents", get(subagent_tree))
        .route(
            "/v1/sessions/:id/subagents/:agent_id/events",
            get(subagent_events),
        )
        .route(
            "/v1/sessions/:id/subagents/:agent_id/stop",
            post(stop_subagent),
        )
        .route("/v1/sessions/:id/claude/:action", post(claude_tool))
        .route("/v1/sessions/:id/events/:event_id/payload", get(payload))
        .route("/v1/sessions/:id/messages", post(send_message))
        .route("/v1/sessions/:id/attachments", post(begin_attachment))
        .route(
            "/v1/sessions/:id/attachments/:attachment_id",
            put(attachment_chunk)
                .delete(cancel_attachment)
                .layer(DefaultBodyLimit::max(
                    crate::agents::attachments::MAX_CHUNK_BYTES + 1024,
                )),
        )
        .route("/v1/sessions/:id/interrupt", post(interrupt))
        .route("/v1/sessions/:id/options", post(set_options))
        .route("/v1/agents/:agent/models", get(models))
        .route("/v1/sessions/:id/stop", post(stop))
        .route("/v1/sessions/:id/approvals/:request_id", post(approve))
        .route("/v1/sessions/:id/answers/:request_id", post(answer))
}

async fn list(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(q): Query<SessionQuery>,
) -> Json<Value> {
    let (items, next) = ctx.agents.list(&q).await;
    Json(json!({ "items": items, "nextCursor": next }))
}

#[derive(Deserialize)]
struct SearchQuery {
    q: String,
    agent: Option<String>,
    limit: Option<usize>,
}

/// Full-text search over session contents (docs/PROTOCOL.md §7.13).
async fn search(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(q): Query<SearchQuery>,
) -> ApiResult<Json<Value>> {
    let query = q.q.trim();
    if query.is_empty() || query.chars().count() > crate::agents::search::MAX_QUERY_CHARS {
        return Err(crate::error::ApiError::validation(
            "q must be 1..200 characters",
        ));
    }
    let (results, partial) = ctx
        .agents
        .search(
            query,
            q.agent.as_deref().filter(|a| !a.is_empty()),
            q.limit.unwrap_or(20),
            std::time::Duration::from_secs(3),
        )
        .await;
    Ok(Json(json!({ "results": results, "partial": partial })))
}

async fn detail(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    ctx.agents.detail(&id).await.map(Json)
}

#[derive(Deserialize)]
struct EventsQuery {
    before: Option<u64>,
    after: Option<u64>,
    limit: Option<usize>,
}

async fn events(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Path(id): Path<String>,
    Query(q): Query<EventsQuery>,
) -> ApiResult<Json<Value>> {
    ctx.agents
        .events(&id, q.before, q.after, q.limit.unwrap_or(100))
        .await
        .map(Json)
}

async fn subagent_tree(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    ctx.agents.subagent_tree(&id).await.map(Json)
}

/// The Claude Code IDE's session tools (`permission_mode`, `context_usage`, `task_output`,
/// `plan`, `mcp_status`, `mcp_reconnect`, `run_in_background`, `cancel_message`,
/// `side_question`, `rename`, `rewind`, `export`, `commands`) for sessions SwitchTether started. The body is
/// the action's parameters plus an optional `commandId`; every call is audited.
async fn claude_tool(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path((id, action)): Path<(String, String)>,
    Json(body): Json<Value>,
) -> Response {
    if action == "permission_mode" {
        if let Some(r) = full_access_check(&ctx, &dev, body.get("mode").and_then(Value::as_str)) {
            return r;
        }
    }
    let c = ctx.clone();
    let command_id = body
        .get("commandId")
        .and_then(Value::as_str)
        .map(String::from);
    let what = format!("session.claude.{action}");
    idempotent(
        &ctx,
        dev.id().to_string(),
        command_id,
        "session.claude",
        || async move {
            let r = c.agents.claude_tool(&id, &action, &body).await?;
            c.registry
                .audit(Some(dev.id()), &what, Some(&id), "ok", None);
            Ok(r)
        },
    )
    .await
}

/// Agent map "Stop agent" / "Stop" for a background task (managed Claude Code sessions).
async fn stop_subagent(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path((id, agent_id)): Path<(String, String)>,
    Json(body): Json<CmdBody>,
) -> Response {
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "session.stop_subagent",
        || async move {
            let r = c.agents.stop_subagent(&id, &agent_id).await?;
            c.registry.audit(
                Some(dev.id()),
                "session.stop_subagent",
                Some(&id),
                "ok",
                None,
            );
            Ok(r)
        },
    )
    .await
}

async fn subagent_events(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Path((id, agent_id)): Path<(String, String)>,
    Query(q): Query<EventsQuery>,
) -> ApiResult<Json<Value>> {
    ctx.agents
        .subagent_events(&id, &agent_id, q.before, q.after, q.limit.unwrap_or(200))
        .await
        .map(Json)
}

#[derive(Deserialize)]
struct PayloadQuery {
    offset: Option<usize>,
    limit: Option<usize>,
}

async fn payload(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Path((id, event_id)): Path<(String, String)>,
    Query(q): Query<PayloadQuery>,
) -> ApiResult<Json<Value>> {
    ctx.agents
        .payload(
            &id,
            &event_id,
            q.offset.unwrap_or(0),
            q.limit.unwrap_or(64 * 1024),
        )
        .await
        .map(Json)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CreateBody {
    command_id: Option<String>,
    #[serde(flatten)]
    req: CreateSession,
}

async fn create(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Json(body): Json<CreateBody>,
) -> Response {
    if let Some(r) = full_access_check(&ctx, &dev, body.req.permission_mode.as_deref()) {
        return r;
    }
    let c = ctx.clone();
    let req = body.req;
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "session.create",
        || async move {
            let cwd = req.cwd.clone();
            let prompt = req.prompt.as_deref().is_some_and(|p| !p.trim().is_empty());
            let s = c.agents.create_managed(req).await?;
            c.registry.audit(
                Some(dev.id()),
                "session.create",
                Some(&cwd),
                "ok",
                Some(&s.id),
            );
            c.stats.task_started(dev.id(), &s.id, prompt);
            Ok(json!(s))
        },
    )
    .await
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MessageBody {
    command_id: Option<String>,
    #[serde(default)]
    text: String,
    #[serde(default)]
    images: Vec<crate::agents::managed::ImageAttachment>,
    /// Ids from `POST /v1/sessions/{id}/attachments`, fully uploaded.
    #[serde(default)]
    attachments: Vec<String>,
}

async fn send_message(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(id): Path<String>,
    Json(body): Json<MessageBody>,
) -> Response {
    let c = ctx.clone();
    let client_id = body.command_id.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "session.message",
        || async move {
            let r = c
                .agents
                .send_message_full(
                    &id,
                    &body.text,
                    &body.images,
                    &body.attachments,
                    dev.id(),
                    client_id.as_deref(),
                )
                .await?;
            c.registry.audit(
                Some(dev.id()),
                "session.message",
                Some(&id),
                "ok",
                Some(&format!(
                    "{} chars, {} images, {} attachments",
                    body.text.chars().count(),
                    body.images.len(),
                    body.attachments.len()
                )),
            );
            c.stats.message_sent(dev.id(), &id);
            Ok(r)
        },
    )
    .await
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct AttachmentBody {
    name: String,
    size: u64,
    media_type: Option<String>,
}

/// Starts uploading a file the phone attaches to its next message in this session.
async fn begin_attachment(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(id): Path<String>,
    Json(body): Json<AttachmentBody>,
) -> ApiResult<Json<Value>> {
    ctx.agents.ensure_can_send(&id).await?;
    let store = ctx.agents.attachments()?;
    let dev_id = dev.id().to_string();
    let v = tokio::task::spawn_blocking(move || {
        store.begin(
            &dev_id,
            &id,
            &body.name,
            body.size,
            body.media_type.as_deref(),
        )
    })
    .await
    .map_err(|e| crate::error::ApiError::internal("attachment", e))??;
    Ok(Json(json!(v)))
}

#[derive(Deserialize)]
struct ChunkQuery {
    offset: u64,
}

async fn attachment_chunk(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path((id, attachment_id)): Path<(String, String)>,
    Query(q): Query<ChunkQuery>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let store = ctx.agents.attachments()?;
    let dev_id = dev.id().to_string();
    let v = tokio::task::spawn_blocking(move || {
        store.chunk(&dev_id, &id, &attachment_id, q.offset, &body)
    })
    .await
    .map_err(|e| crate::error::ApiError::internal("attachment", e))??;
    Ok(Json(json!(v)))
}

async fn cancel_attachment(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path((id, attachment_id)): Path<(String, String)>,
) -> ApiResult<Json<Value>> {
    let store = ctx.agents.attachments()?;
    let dev_id = dev.id().to_string();
    let _ = tokio::task::spawn_blocking(move || store.cancel(&dev_id, &id, &attachment_id)).await;
    Ok(Json(json!({ "cancelled": true })))
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct CmdBody {
    command_id: Option<String>,
}

async fn interrupt(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(id): Path<String>,
    Json(body): Json<CmdBody>,
) -> Response {
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "session.interrupt",
        || async move {
            let r = c.agents.interrupt(&id).await?;
            c.registry
                .audit(Some(dev.id()), "session.interrupt", Some(&id), "ok", None);
            Ok(r)
        },
    )
    .await
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct OptionsBody {
    command_id: Option<String>,
    /// New model; `""` / `"default"` = the agent's default; absent = unchanged.
    model: Option<String>,
    /// New effort level; `""` / `"default"` = the default; absent = unchanged.
    effort: Option<String>,
    /// New permission mode (`crate::agents::permission` ids); absent = unchanged.
    permission_mode: Option<String>,
}

/// `POST /v1/sessions/{id}/options`: change model and / or effort of a running session.
async fn set_options(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(id): Path<String>,
    Json(body): Json<OptionsBody>,
) -> Response {
    if let Some(r) = full_access_check(&ctx, &dev, body.permission_mode.as_deref()) {
        return r;
    }
    let c = ctx.clone();
    let (model, effort, mode) = (body.model, body.effort, body.permission_mode);
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "session.options",
        || async move {
            let detail =
                json!({ "model": model, "effort": effort, "permissionMode": mode }).to_string();
            let r = c
                .agents
                .set_session_options(&id, model, effort, mode)
                .await?;
            c.registry.audit(
                Some(dev.id()),
                "session.options",
                Some(&id),
                "ok",
                Some(&detail),
            );
            Ok(json!(r))
        },
    )
    .await
}

#[derive(Deserialize)]
struct ModelsQuery {
    session: Option<String>,
}

/// `GET /v1/agents/{agent}/models?session=`: models and effort levels to offer.
async fn models(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Path(agent): Path<String>,
    Query(q): Query<ModelsQuery>,
) -> Json<Value> {
    Json(ctx.agents.model_options(&agent, q.session.as_deref()).await)
}

async fn stop(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(id): Path<String>,
    Json(body): Json<CmdBody>,
) -> Response {
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "session.stop",
        || async move {
            let r = c.agents.stop(&id).await?;
            c.registry
                .audit(Some(dev.id()), "session.stop", Some(&id), "ok", None);
            Ok(r)
        },
    )
    .await
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApprovalBody {
    command_id: Option<String>,
    decision: String,
    message: Option<String>,
    updated_input: Option<Value>,
}

async fn approve(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path((id, request_id)): Path<(String, String)>,
    Json(body): Json<ApprovalBody>,
) -> Response {
    let c = ctx.clone();
    idempotent(&ctx, dev.id().to_string(), body.command_id, "session.approve", || async move {
        let r = c
            .agents
            .respond(&id, &request_id, &body.decision, body.message, body.updated_input, None, "phone")
            .await?;
        c.registry.audit(
            Some(dev.id()),
            "session.approval",
            Some(&id),
            &body.decision,
            Some(&request_id),
        );
        c.stats.approval(dev.id(), &id, &body.decision);
        c.events.publish(
            "approval.resolved",
            json!({ "sessionId": id, "requestId": request_id, "decision": body.decision, "by": "phone" }),
        );
        Ok(r)
    })
    .await
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct AnswerBody {
    command_id: Option<String>,
    #[serde(default)]
    answers: Option<Value>,
    #[serde(default)]
    action: Option<String>,
}

async fn answer(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path((id, request_id)): Path<(String, String)>,
    Json(body): Json<AnswerBody>,
) -> Response {
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "session.answer",
        || async move {
            let decision = match body.action.as_deref() {
                Some("cancel") => "cancel",
                Some("decline") => "deny",
                _ => "answer",
            };
            let r = c
                .agents
                .respond(
                    &id,
                    &request_id,
                    decision,
                    None,
                    None,
                    body.answers,
                    "phone",
                )
                .await?;
            c.registry.audit(
                Some(dev.id()),
                "session.answer",
                Some(&id),
                decision,
                Some(&request_id),
            );
            c.stats.answered(dev.id(), &id);
            Ok(r)
        },
    )
    .await
}

#[cfg(test)]
mod tests {
    #[test]
    fn message_with_attachments_fixture() {
        let root =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../protocol/fixtures");
        let body: super::MessageBody = serde_json::from_str(
            &std::fs::read_to_string(root.join("send_message_attachments.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(body.attachments.len(), 2);
        assert!(body.images.is_empty());
        assert_eq!(body.command_id.as_deref(), Some("c-7f3a"));
    }

    #[test]
    fn session_options_and_model_options_fixtures() {
        let root =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../protocol/fixtures");
        let body: super::OptionsBody = serde_json::from_str(
            &std::fs::read_to_string(root.join("session_options.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(body.command_id.as_deref(), Some("c-41d2"));
        assert_eq!(body.model.as_deref(), Some("sonnet"));
        assert_eq!(body.effort.as_deref(), Some("high"));
        assert_eq!(body.permission_mode.as_deref(), Some("acceptEdits"));
        // What the desktop answers for a Claude Code session that reported its models.
        let fixture: serde_json::Value = serde_json::from_str(
            &std::fs::read_to_string(root.join("model_options.json")).unwrap(),
        )
        .unwrap();
        let reported = serde_json::json!([
            {"value": "default", "displayName": "Default (recommended)", "description": "Opus for complex work"},
            {"value": "sonnet", "displayName": "Sonnet", "supportedEffortLevels": ["low", "medium", "high"]},
            {"value": "haiku", "displayName": "Haiku"}
        ]);
        let built = crate::agents::model_catalog("claude_code", &reported);
        assert_eq!(
            built["models"].as_array().unwrap()[..3],
            fixture["models"].as_array().unwrap()[..]
        );
        assert_eq!(built["efforts"], fixture["efforts"]);
        assert_eq!(built["source"], fixture["source"]);
        assert_eq!(
            crate::agents::permission::modes("claude_code"),
            fixture["permissionModes"]
        );
        // Antigravity: `agy models` tiers grouped into models with thinking levels.
        let fixture: serde_json::Value = serde_json::from_str(
            &std::fs::read_to_string(root.join("model_options_agy.json")).unwrap(),
        )
        .unwrap();
        let pairs = crate::agents::agy::parse_models_output(
            "gemini-4-neon-high	Gemini 4 Neon (High)
gemini-4-neon-xhigh	Gemini 4 Neon (XHigh)
             gemini-4-neon-medium	Gemini 4 Neon (Medium)
gemini-4-neon-low	Gemini 4 Neon (Low)
             gemini-pro-orchestrator	Orchestrator (Carbon)
             claude-sonnet-5-5-low	Claude Sonnet 5.5 (Low)
claude-sonnet-5-5-medium	Claude Sonnet 5.5 (Medium)
             claude-sonnet-5-5-high	Claude Sonnet 5.5 (High)
",
        );
        let mut built =
            crate::agents::model_catalog("agy", &crate::agents::agy::group_models(&pairs));
        built["permissionModes"] = crate::agents::permission::modes("agy");
        assert_eq!(built, fixture);
    }
}
