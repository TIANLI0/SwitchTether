//! Port forwarding endpoints (docs/PROTOCOL.md §7.11). Starting a forward needs step-up
//! `port_forward`; the lease it creates is what authorises its tunnel afterwards, so a
//! browser on the phone keeps working for the lease's lifetime without asking again.

use std::sync::Arc;
use std::time::Duration;

use axum::extract::ws::{CloseFrame, Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Path, State};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get};
use axum::{Json, Router};
use futures::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::{json, Value};
use tokio::sync::mpsc;

use super::{require_step_up, ApiContext, AuthDevice};
use crate::error::{ApiError, ApiResult};
use crate::forward::{self, Lease, LeaseError, TunnelEnd};
use crate::stepup::Scope;

pub fn routes() -> Router<Arc<ApiContext>> {
    Router::new()
        .route("/v1/forwards", get(list).post(create))
        .route("/v1/forwards/ports", get(ports))
        .route("/v1/forwards/:id", delete(stop))
        .route("/v1/forwards/:id/tunnel", get(tunnel))
}

async fn list(State(ctx): State<Arc<ApiContext>>, dev: AuthDevice) -> Json<Value> {
    Json(json!({ "forwards": ctx.forwards.list(dev.id()) }))
}

/// Ports listening on the desktop's loopback (the Remote server's own port excluded).
async fn ports(State(ctx): State<Arc<ApiContext>>, _d: AuthDevice) -> ApiResult<Json<Value>> {
    let own = ctx.config.read().port;
    let list = tokio::task::spawn_blocking(move || forward::listening_ports(&[own])).await?;
    Ok(Json(json!({ "ports": list })))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CreateBody {
    port: u32,
    ttl_minutes: Option<u64>,
}

async fn create(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Json(body): Json<CreateBody>,
) -> ApiResult<Json<Value>> {
    require_step_up(&ctx, &dev, Scope::PortForward)?;
    let port = u16::try_from(body.port)
        .ok()
        .filter(|p| *p != 0)
        .ok_or_else(|| ApiError::validation("port must be 1-65535"))?;
    if port == ctx.config.read().port {
        return Err(ApiError::forbidden(
            "the SwitchTether Remote port cannot be forwarded",
        ));
    }
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
            "lease={} expires={}",
            lease.id,
            lease.expires_at_ms()
        )),
    );
    let mut v = json!(lease.view());
    v["listening"] = json!(listening);
    Ok(Json(v))
}

async fn stop(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    let lease = ctx
        .forwards
        .remove(Some(dev.id()), &id)
        .ok_or_else(|| ApiError::not_found("unknown forward"))?;
    ctx.registry.audit(
        Some(dev.id()),
        "forward.stop",
        Some(&lease.port.to_string()),
        "ok",
        Some(&audit_detail(&lease)),
    );
    Ok(Json(json!({ "removed": true })))
}

fn audit_detail(lease: &Lease) -> String {
    let v = lease.view();
    format!(
        "lease={} connections={} down={} up={}",
        v.id, v.total_streams, v.bytes_down, v.bytes_up
    )
}

async fn tunnel(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(id): Path<String>,
    ws: WebSocketUpgrade,
) -> Response {
    let Some(lease) = ctx.forwards.get(dev.id(), &id) else {
        return ApiError::not_found("unknown or expired forward").into_response();
    };
    ws.max_message_size(forward::MAX_DATA + 64)
        .on_upgrade(move |socket| run_tunnel(ctx, dev, lease, socket))
}

async fn run_tunnel(ctx: Arc<ApiContext>, dev: AuthDevice, lease: Arc<Lease>, socket: WebSocket) {
    let cancel = lease.take_tunnel();
    let (mut sink, mut stream) = socket.split();
    let (in_tx, in_rx) = mpsc::channel::<Vec<u8>>(256);
    let (out_tx, mut out_rx) = mpsc::channel::<Vec<u8>>(256);
    let (close_tx, mut close_rx) = mpsc::channel::<(u16, &'static str)>(1);

    // Phone → mux. Pings are answered by axum; text frames are not part of the protocol.
    let reader = tokio::spawn(async move {
        while let Some(Ok(m)) = stream.next().await {
            let forwarded = match m {
                Message::Binary(b) => in_tx.send(b).await.is_ok(),
                Message::Close(_) => false,
                _ => true,
            };
            if !forwarded {
                break;
            }
        }
    });
    // Mux → phone, then the close frame (with a code when the desktop ended the tunnel).
    let writer = tokio::spawn(async move {
        let mut open = true;
        loop {
            tokio::select! {
                frame = out_rx.recv(), if open => match frame {
                    Some(f) => if sink.send(Message::Binary(f)).await.is_err() { return },
                    None => open = false,
                },
                close = close_rx.recv() => {
                    let frame = close.map(|(code, reason)| CloseFrame { code, reason: reason.into() });
                    let _ = sink.send(Message::Close(frame)).await;
                    return;
                }
            }
        }
    });

    let peer_cancel = dev.peer.cancel.clone();
    let end = tokio::select! {
        end = forward::serve_tunnel(lease.clone(), in_rx, out_tx, cancel.clone()) => end,
        _ = peer_cancel.cancelled() => TunnelEnd::Cancelled,
    };
    let close = match end {
        TunnelEnd::PeerClosed => None,
        TunnelEnd::Protocol => Some((1002, "protocol error")),
        TunnelEnd::Cancelled if dev.peer.cancel.is_cancelled() => Some((4403, "closed by desktop")),
        // Stopped / expired (the phone must start the forward again) vs replaced by a newer tunnel.
        TunnelEnd::Cancelled if lease.cancel.is_cancelled() => Some((4410, "forward ended")),
        TunnelEnd::Cancelled => Some((4409, "replaced by a newer tunnel")),
    };
    if let Some(c) = close {
        let _ = close_tx.send(c).await;
    }
    drop(close_tx);
    let _ = tokio::time::timeout(Duration::from_secs(2), writer).await;
    reader.abort();
    ctx.registry.audit(
        Some(dev.id()),
        "forward.tunnel",
        Some(&lease.port.to_string()),
        match end {
            TunnelEnd::PeerClosed => "closed",
            TunnelEnd::Cancelled => "ended",
            TunnelEnd::Protocol => "protocol_error",
        },
        Some(&audit_detail(&lease)),
    );
}
