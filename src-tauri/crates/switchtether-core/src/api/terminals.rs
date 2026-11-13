//! Terminal endpoints (step-up scope `terminal`).

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use axum::extract::ws::{CloseFrame, Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Path, Query, State};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use base64::Engine as _;
use serde::Deserialize;
use serde_json::{json, Value};

use super::{idempotent, require_step_up, ApiContext, AuthDevice};
use crate::error::{ApiError, ApiResult};
use crate::stepup::Scope;
use crate::terminal::{CreateTerminal, Line, Terminal};

pub fn routes() -> Router<Arc<ApiContext>> {
    Router::new()
        .route("/v1/terminals", get(list).post(create))
        .route("/v1/terminals/:id", get(info).delete(kill))
        .route("/v1/terminals/:id/resize", post(resize))
        .route("/v1/terminals/:id/scrollback", get(scrollback))
        .route("/v1/terminals/:id/stream", get(stream))
}

async fn list(State(ctx): State<Arc<ApiContext>>, _d: AuthDevice) -> Json<Value> {
    Json(json!({ "terminals": ctx.terminals.list(), "shells": crate::terminal::available_shells() }))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CreateBody {
    command_id: Option<String>,
    #[serde(flatten)]
    req: CreateTerminal,
}

async fn create(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Json(body): Json<CreateBody>,
) -> Response {
    if let Err(e) = require_step_up(&ctx, &dev, Scope::Terminal) {
        return e.into_response();
    }
    let c = ctx.clone();
    let req = body.req;
    idempotent(&ctx, dev.id().to_string(), body.command_id, "terminal.create", || async move {
        let mgr = c.terminals.clone();
        let owner = dev.id().to_string();
        let t = tokio::task::spawn_blocking(move || mgr.create(req, Some(owner)))
            .await?
            .map_err(|e| ApiError::validation(e.to_string()))?;
        let info = t.info();
        c.registry.audit(
            Some(dev.id()),
            "terminal.create",
            Some(&info.cwd),
            "ok",
            Some(&format!("shell={} id={}", info.shell, info.id)),
        );
        Ok(json!(info))
    })
    .await
}

fn get_term(ctx: &ApiContext, id: &str) -> ApiResult<Arc<Terminal>> {
    ctx.terminals
        .get(id)
        .ok_or_else(|| ApiError::not_found("unknown terminal"))
}

async fn info(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    Ok(Json(json!(get_term(&ctx, &id)?.info())))
}

async fn kill(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(id): Path<String>,
) -> ApiResult<Json<Value>> {
    require_step_up(&ctx, &dev, Scope::Terminal)?;
    let ok = ctx.terminals.remove(&id);
    ctx.registry
        .audit(Some(dev.id()), "terminal.kill", Some(&id), if ok { "ok" } else { "not_found" }, None);
    Ok(Json(json!({ "removed": ok })))
}

#[derive(Deserialize)]
struct ResizeBody {
    cols: u16,
    rows: u16,
}

async fn resize(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Path(id): Path<String>,
    Json(b): Json<ResizeBody>,
) -> ApiResult<Json<Value>> {
    get_term(&ctx, &id)?
        .resize(b.cols, b.rows)
        .map_err(|e| ApiError::bad_request(e.to_string()))?;
    Ok(Json(json!({ "ok": true })))
}

#[derive(Deserialize)]
struct ScrollbackQuery {
    offset: Option<usize>,
    limit: Option<usize>,
}

async fn scrollback(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(id): Path<String>,
    Query(q): Query<ScrollbackQuery>,
) -> ApiResult<Json<Value>> {
    require_step_up(&ctx, &dev, Scope::Terminal)?;
    let t = get_term(&ctx, &id)?;
    let (lines, total) = t.scrollback(q.offset.unwrap_or(0), q.limit.unwrap_or(200));
    Ok(Json(json!({ "lines": lines, "total": total })))
}

async fn stream(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(id): Path<String>,
    ws: WebSocketUpgrade,
) -> Response {
    if let Err(e) = require_step_up(&ctx, &dev, Scope::Terminal) {
        return e.into_response();
    }
    let term = match get_term(&ctx, &id) {
        Ok(t) => t,
        Err(e) => return e.into_response(),
    };
    ws.max_message_size(1024 * 1024)
        .on_upgrade(move |socket| run_stream(ctx, dev, term, socket))
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum ClientMsg {
    Input { data: String },
    InputB64 { data: String },
    Resize { cols: u16, rows: u16 },
    Ack {},
    Ping {},
}

async fn run_stream(ctx: Arc<ApiContext>, dev: AuthDevice, term: Arc<Terminal>, mut socket: WebSocket) {
    let mut rx = term.subscribe();
    let snap = term.snapshot();
    let mut last_lines: Vec<Line> = snap.lines.clone();
    let mut last_version = snap.version;
    let mut msg = json!(snap);
    msg["type"] = json!("snapshot");
    if socket.send(Message::Text(msg.to_string())).await.is_err() {
        return;
    }
    let mut input_bytes: u64 = 0;
    let frame_interval = Duration::from_millis(33);
    loop {
        tokio::select! {
            _ = dev.peer.cancel.cancelled() => {
                let _ = socket.send(Message::Close(Some(CloseFrame { code: 4403, reason: "closed by desktop".into() }))).await;
                break;
            }
            changed = rx.changed() => {
                if changed.is_err() { break; }
                // Coalesce bursts of output into ~30 fps frames.
                tokio::time::sleep(frame_interval).await;
                let snap = term.snapshot();
                if snap.version == last_version { continue; }
                let resized = snap.lines.len() != last_lines.len();
                let out = if resized {
                    let mut m = json!(snap);
                    m["type"] = json!("snapshot");
                    m
                } else {
                    let changed: Vec<Value> = snap.lines.iter().enumerate()
                        .filter(|(i, l)| last_lines.get(*i) != Some(*l))
                        .map(|(i, l)| json!({ "y": i, "line": l }))
                        .collect();
                    json!({
                        "type": "patch", "version": snap.version, "lines": changed,
                        "cursor": snap.cursor, "title": snap.title, "altScreen": snap.alt_screen,
                        "applicationCursor": snap.application_cursor, "bracketedPaste": snap.bracketed_paste,
                        "exited": snap.exited, "exitCode": snap.exit_code,
                    })
                };
                last_lines = snap.lines;
                last_version = snap.version;
                if socket.send(Message::Text(out.to_string())).await.is_err() { break; }
                if snap.exited {
                    let _ = socket.send(Message::Text(json!({ "type": "exit", "code": snap.exit_code }).to_string())).await;
                }
            }
            incoming = socket.recv() => {
                let Some(Ok(m)) = incoming else { break };
                let text = match m {
                    Message::Text(t) => t,
                    Message::Close(_) => break,
                    _ => continue,
                };
                // Re-check the grant for every input frame: an expired grant
                // stops input immediately.
                if !ctx.stepup.has_grant(dev.id(), Scope::Terminal) {
                    let _ = socket.send(Message::Text(json!({ "type": "error", "code": "step_up_required" }).to_string())).await;
                    let _ = socket.send(Message::Close(Some(CloseFrame { code: 4401, reason: "step-up expired".into() }))).await;
                    break;
                }
                match serde_json::from_str::<ClientMsg>(&text) {
                    Ok(ClientMsg::Input { data }) => {
                        input_bytes += data.len() as u64;
                        let t = term.clone();
                        let _ = tokio::task::spawn_blocking(move || t.write_input(data.as_bytes())).await;
                    }
                    Ok(ClientMsg::InputB64 { data }) => {
                        if let Ok(bytes) = base64::engine::general_purpose::STANDARD.decode(data) {
                            input_bytes += bytes.len() as u64;
                            let t = term.clone();
                            let _ = tokio::task::spawn_blocking(move || t.write_input(&bytes)).await;
                        }
                    }
                    Ok(ClientMsg::Resize { cols, rows }) => { let _ = term.resize(cols, rows); }
                    Ok(ClientMsg::Ack {}) => {}
                    Ok(ClientMsg::Ping {}) => {
                        let _ = socket.send(Message::Text(json!({ "type": "pong" }).to_string())).await;
                    }
                    Err(_) => {}
                }
            }
        }
    }
    // Terminal input content is never logged (it may contain passwords);
    // only the volume is audited.
    let mut detail = HashMap::new();
    detail.insert("inputBytes", input_bytes);
    ctx.registry.audit(
        Some(dev.id()),
        "terminal.session",
        Some(&term.id),
        "closed",
        Some(&serde_json::to_string(&detail).unwrap_or_default()),
    );
}
