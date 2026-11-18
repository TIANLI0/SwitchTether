//! Session endpoints.

use std::sync::Arc;

use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, Path, Query, State};
use axum::response::Response;
use axum::routing::{get, post, put};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};

use super::{idempotent, ApiContext, AuthDevice};
use crate::agents::{CreateSession, SessionQuery};
use crate::error::ApiResult;

pub fn routes() -> Router<Arc<ApiContext>> {
    Router::new()
        .route("/v1/sessions", get(list).post(create))
        .route("/v1/sessions/:id", get(detail))
        .route("/v1/sessions/:id/events", get(events))
        .route("/v1/sessions/:id/subagents", get(subagent_tree))
        .route(
            "/v1/sessions/:id/subagents/:agent_id/events",
            get(subagent_events),
        )
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
    let c = ctx.clone();
    let req = body.req;
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "session.create",
        || async move {
            let cwd = req.cwd.clone();
            let s = c.agents.create_managed(req).await?;
            c.registry.audit(
                Some(dev.id()),
                "session.create",
                Some(&cwd),
                "ok",
                Some(&s.id),
            );
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
    ctx.agents.ensure_can_send(&id)?;
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
}
