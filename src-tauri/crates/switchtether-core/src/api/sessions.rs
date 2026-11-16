//! Session endpoints.

use std::sync::Arc;

use axum::extract::{Path, Query, State};
use axum::response::Response;
use axum::routing::{get, post};
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
        .route("/v1/sessions/:id/events/:event_id/payload", get(payload))
        .route("/v1/sessions/:id/messages", post(send_message))
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
struct MessageBody {
    command_id: Option<String>,
    #[serde(default)]
    text: String,
    #[serde(default)]
    images: Vec<crate::agents::managed::ImageAttachment>,
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
                .send_message_with_images(&id, &body.text, &body.images, client_id.as_deref())
                .await?;
            c.registry.audit(
                Some(dev.id()),
                "session.message",
                Some(&id),
                "ok",
                Some(&format!(
                    "{} chars, {} images",
                    body.text.chars().count(),
                    body.images.len()
                )),
            );
            Ok(r)
        },
    )
    .await
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
