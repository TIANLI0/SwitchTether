//! DeepSeek Harness Web endpoints (docs/PROTOCOL.md §7.14). Starting / stopping `dsh web` and
//! forwarding its port need step-up `port_forward`, like any forward: the phone ends up with a
//! normal forward lease (§7.11) to the `dsh web` port.

use std::sync::Arc;
use std::time::Duration;

use axum::extract::State;
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};

use super::{require_step_up, ApiContext, AuthDevice};
use crate::error::{ApiError, ApiResult};
use crate::forward::{self, LeaseError};
use crate::stepup::Scope;

pub fn routes() -> Router<Arc<ApiContext>> {
    Router::new()
        .route("/v1/dsh-web", get(status))
        .route("/v1/dsh-web/open", post(open))
        .route("/v1/dsh-web/stop", post(stop))
}

fn own_port(ctx: &ApiContext) -> Vec<u16> {
    vec![ctx.config.read().port]
}

async fn status(State(ctx): State<Arc<ApiContext>>, _d: AuthDevice) -> Json<Value> {
    Json(json!(ctx.dsh_web.status(&own_port(&ctx)).await))
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct OpenBody {
    /// Start `dsh web` when none runs (default true).
    start: Option<bool>,
    ttl_minutes: Option<u64>,
}

/// Reuse or start `dsh web`; once it runs, a forward lease to its port (+ the launch path when
/// SwitchTether started it). While it starts the response has only `status` (`starting`).
async fn open(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    body: Option<Json<OpenBody>>,
) -> ApiResult<Json<Value>> {
    require_step_up(&ctx, &dev, Scope::PortForward)?;
    let body = body.map(|b| b.0).unwrap_or_default();
    let exclude = own_port(&ctx);
    let st = if body.start.unwrap_or(true) {
        let cwd = dirs::home_dir().unwrap_or_else(std::env::temp_dir);
        let before = ctx.dsh_web.status(&exclude).await;
        let st = ctx.dsh_web.start(cwd, &exclude).await;
        if before.state == "stopped" && st.state != "stopped" {
            ctx.registry
                .audit(Some(dev.id()), "dsh_web.start", None, "ok", None);
        }
        st
    } else {
        ctx.dsh_web.status(&exclude).await
    };
    let Some(port) = st.port.filter(|_| st.is_running()) else {
        return Ok(Json(json!({ "status": st })));
    };
    let ttl = body
        .ttl_minutes
        .map(|m| Duration::from_secs(m.clamp(1, 24 * 60) * 60))
        .unwrap_or(forward::DEFAULT_TTL);
    let (lease, created) = ctx
        .forwards
        .create(dev.id(), port, ttl)
        .map_err(|e| match e {
            LeaseError::TooMany => ApiError::conflict(format!(
                "at most {} ports can be forwarded at once",
                forward::MAX_LEASES_PER_DEVICE
            )),
        })?;
    let listening = tokio::task::spawn_blocking(move || forward::is_listening(port))
        .await
        .unwrap_or(false);
    // The launch token is never written to the audit trail.
    ctx.registry.audit(
        Some(dev.id()),
        if created {
            "forward.create"
        } else {
            "forward.renew"
        },
        Some(&port.to_string()),
        "ok",
        Some(&format!(
            "dsh web; lease={} expires={}",
            lease.id,
            lease.expires_at_ms()
        )),
    );
    let mut fwd = json!(lease.view());
    fwd["listening"] = json!(listening);
    let mut out = json!({ "status": st, "forward": fwd });
    if st.owned {
        if let Some(p) = ctx.dsh_web.launch_path() {
            out["launchPath"] = json!(p);
        }
    }
    Ok(Json(out))
}

/// Stops the `dsh web` SwitchTether started; one the user started is never touched.
async fn stop(State(ctx): State<Arc<ApiContext>>, dev: AuthDevice) -> ApiResult<Json<Value>> {
    require_step_up(&ctx, &dev, Scope::PortForward)?;
    if !ctx.dsh_web.stop() {
        return Err(ApiError::conflict(
            "this DeepSeek Harness Web was not started by SwitchTether",
        ));
    }
    ctx.registry
        .audit(Some(dev.id()), "dsh_web.stop", None, "ok", None);
    let st = ctx.dsh_web.status(&own_port(&ctx)).await;
    Ok(Json(json!({ "stopped": true, "status": st })))
}
