//! `GET /v1/events` WebSocket: replay + live event stream.

use std::sync::Arc;
use std::time::Duration;

use axum::extract::ws::{CloseFrame, Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Query, State};
use axum::response::Response;
use axum::routing::get;
use axum::Router;
use serde::Deserialize;
use serde_json::json;
use tokio::sync::broadcast::error::RecvError;

use super::{ApiContext, AuthDevice};
use crate::events::Replay;

pub const PING_TIMEOUT: Duration = Duration::from_secs(60);
pub const CLOSE_UNPAIRED: u16 = 4401;
pub const CLOSE_REVOKED: u16 = 4403;
pub const CLOSE_IDLE: u16 = 4408;

pub fn routes() -> Router<Arc<ApiContext>> {
    Router::new().route("/v1/events", get(events))
}

#[derive(Deserialize)]
struct EventsQuery {
    #[serde(default)]
    since: u64,
    epoch: Option<String>,
}

async fn events(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Query(q): Query<EventsQuery>,
    ws: WebSocketUpgrade,
) -> Response {
    ws.max_message_size(64 * 1024)
        .on_upgrade(move |socket| run(ctx, dev, q, socket))
}

async fn send_json(socket: &mut WebSocket, v: &impl serde::Serialize) -> bool {
    match serde_json::to_string(v) {
        Ok(s) => socket.send(Message::Text(s)).await.is_ok(),
        Err(_) => true,
    }
}

async fn close(socket: &mut WebSocket, code: u16, reason: &'static str) {
    let _ = socket
        .send(Message::Close(Some(CloseFrame {
            code,
            reason: reason.into(),
        })))
        .await;
}

async fn run(ctx: Arc<ApiContext>, dev: AuthDevice, q: EventsQuery, mut socket: WebSocket) {
    let device_id = dev.id().to_string();
    // Subscribe before reading the backlog so nothing published in between is lost;
    // duplicates are filtered by `last_sent`.
    let mut rx = ctx.events.subscribe();
    let mut last_sent = q.since;
    match ctx.events.replay(q.since, q.epoch.as_deref()) {
        Replay::Events(evs) => {
            for ev in evs.iter().filter(|e| e.visible_to(&device_id)) {
                if !send_json(&mut socket, ev.as_ref()).await {
                    return;
                }
                last_sent = ev.seq;
            }
            if evs.is_empty() && q.epoch.is_none() {
                last_sent = ctx.events.head_seq();
            }
        }
        Replay::Resync { head_seq, reason } => {
            if !send_json(
                &mut socket,
                &json!({ "type": "resync", "epoch": ctx.events.epoch(), "headSeq": head_seq, "reason": reason }),
            )
            .await
            {
                return;
            }
            last_sent = head_seq;
        }
    }

    let mut deadline = tokio::time::Instant::now() + PING_TIMEOUT;
    loop {
        tokio::select! {
            _ = dev.peer.cancel.cancelled() => {
                let code = if ctx.registry.device_by_fingerprint(&dev.peer.fingerprint).is_none() {
                    CLOSE_REVOKED
                } else {
                    1001
                };
                close(&mut socket, code, "connection closed by desktop").await;
                return;
            }
            _ = tokio::time::sleep_until(deadline) => {
                close(&mut socket, CLOSE_IDLE, "idle timeout").await;
                return;
            }
            ev = rx.recv() => match ev {
                Ok(ev) => {
                    if ev.seq <= last_sent || !ev.visible_to(&device_id) {
                        continue;
                    }
                    if !send_json(&mut socket, ev.as_ref()).await {
                        return;
                    }
                    last_sent = ev.seq;
                }
                Err(RecvError::Lagged(_)) => {
                    // The client fell behind the broadcast buffer: tell it to resync.
                    let head = ctx.events.head_seq();
                    if !send_json(&mut socket, &json!({
                        "type": "resync", "epoch": ctx.events.epoch(), "headSeq": head, "reason": "lagged"
                    })).await {
                        return;
                    }
                    last_sent = head;
                }
                Err(RecvError::Closed) => return,
            },
            msg = socket.recv() => match msg {
                Some(Ok(Message::Text(t))) => {
                    deadline = tokio::time::Instant::now() + PING_TIMEOUT;
                    if let Ok(v) = serde_json::from_str::<serde_json::Value>(&t) {
                        if v["type"] == "ping"
                            && !send_json(&mut socket, &json!({ "type": "pong", "ts": crate::util::now_rfc3339() })).await
                        {
                            return;
                        }
                    }
                }
                Some(Ok(Message::Ping(p))) => {
                    deadline = tokio::time::Instant::now() + PING_TIMEOUT;
                    let _ = socket.send(Message::Pong(p)).await;
                }
                Some(Ok(Message::Close(_))) | None | Some(Err(_)) => return,
                Some(Ok(_)) => {
                    deadline = tokio::time::Instant::now() + PING_TIMEOUT;
                }
            }
        }
        // Defense in depth: a revoked device must not keep receiving events
        // even if the cancellation signal was missed.
        if ctx.registry.device_by_fingerprint(&dev.peer.fingerprint).is_none() {
            close(&mut socket, CLOSE_REVOKED, "device revoked").await;
            return;
        }
    }
}
