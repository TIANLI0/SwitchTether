//! Push wake subscription of the calling phone (docs/PROTOCOL.md §7.12).

use std::sync::Arc;

use axum::extract::State;
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};

use super::{ApiContext, AuthDevice};
use crate::error::{ApiError, ApiResult};

pub fn routes() -> Router<Arc<ApiContext>> {
    Router::new()
        .route("/v1/push", get(info).put(register).delete(unregister))
        .route("/v1/push/test", post(test))
}

async fn info(State(ctx): State<Arc<ApiContext>>, dev: AuthDevice) -> ApiResult<Json<Value>> {
    let reg = ctx.registry.clone();
    let id = dev.id().to_string();
    let registered =
        tokio::task::spawn_blocking(move || reg.push_subscription(&id).is_some()).await?;
    Ok(Json(json!({
        "registered": registered,
        "vapidPublicKey": ctx.push.vapid_public_key(),
    })))
}

#[derive(Deserialize)]
struct RegisterReq {
    endpoint: String,
    p256dh: String,
    auth: String,
}

async fn register(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Json(req): Json<RegisterReq>,
) -> ApiResult<Json<Value>> {
    let sub = crate::push::validate_subscription(&req.endpoint, &req.p256dh, &req.auth)
        .map_err(ApiError::validation)?;
    let reg = ctx.registry.clone();
    let id = dev.id().to_string();
    tokio::task::spawn_blocking(move || -> anyhow::Result<()> {
        let changed = reg.push_subscription(&id).as_ref() != Some(&sub);
        reg.set_push_subscription(&id, &sub)?;
        if changed {
            // The endpoint is a capability URL: only its host goes into the audit log.
            let host = sub
                .endpoint
                .parse::<http::Uri>()
                .ok()
                .and_then(|u| u.host().map(str::to_string));
            reg.audit(Some(&id), "push.register", host.as_deref(), "ok", None);
        }
        Ok(())
    })
    .await?
    .map_err(|e| ApiError::internal("push", e))?;
    ctx.push.refresh_subscribers();
    Ok(Json(json!({ "registered": true })))
}

async fn unregister(State(ctx): State<Arc<ApiContext>>, dev: AuthDevice) -> ApiResult<Json<Value>> {
    let reg = ctx.registry.clone();
    let id = dev.id().to_string();
    let removed = tokio::task::spawn_blocking(move || -> anyhow::Result<bool> {
        let removed = reg.remove_push_subscription(&id)?;
        if removed {
            reg.audit(Some(&id), "push.unregister", None, "ok", None);
        }
        Ok(removed)
    })
    .await?
    .map_err(|e| ApiError::internal("push", e))?;
    ctx.push.refresh_subscribers();
    Ok(Json(json!({ "removed": removed })))
}

/// Send one wake right now (settings "test" button), whether or not the phone is connected.
async fn test(State(ctx): State<Arc<ApiContext>>, dev: AuthDevice) -> ApiResult<Json<Value>> {
    match ctx.push.send_test(dev.id()).await {
        Ok(status) => Ok(Json(
            json!({ "status": status, "delivered": (200..300).contains(&status) }),
        )),
        Err(e) => Err(ApiError::bad_request(crate::redact::redact_text(&e))),
    }
}
