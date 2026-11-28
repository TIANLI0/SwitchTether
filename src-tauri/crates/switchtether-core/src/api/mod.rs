//! HTTP API (axum). Only an explicit whitelist of business operations is
//! exposed — Tauri IPC commands are never mapped wholesale.

mod cc;
mod core_routes;
mod dsh_web;
mod events_ws;
mod files;
mod forward;
mod push;
mod sessions;
mod terminals;

use std::collections::HashMap;
use std::future::Future;
use std::sync::Arc;
use std::time::{Duration, Instant};

use axum::extract::{FromRequestParts, Request};
use axum::http::request::Parts;
use axum::http::{HeaderName, HeaderValue};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use axum::{Extension, Json, Router};
use parking_lot::{Mutex, RwLock};
use serde_json::Value;

use crate::agents::AgentHub;
use crate::backend::CcSwitchBackend;
use crate::error::{ApiError, ApiResult, ErrorCode};
use crate::events::EventBus;
use crate::files::FileService;
use crate::identity::ServerIdentity;
use crate::pairing::PairingManager;
use crate::registry::{CommandBegin, DeviceRecord, Registry};
use crate::server::{ConnectionTracker, PeerInfo, RemoteServerConfig};
use crate::stepup::{Scope, StepUpManager};
use crate::terminal::TerminalManager;

pub const MAX_JSON_BODY: usize = 1024 * 1024;
const X_REQUEST_ID: HeaderName = HeaderName::from_static("x-request-id");
const X_COMMAND_REPLAYED: HeaderName = HeaderName::from_static("x-command-replayed");

/// Everything the API handlers need. Owned by the desktop app for the
/// lifetime of the process; the server can be stopped/restarted around it.
pub struct ApiContext {
    pub config: RwLock<RemoteServerConfig>,
    pub server_id: String,
    pub identity: ServerIdentity,
    pub registry: Arc<Registry>,
    pub pairing: PairingManager,
    pub stepup: StepUpManager,
    pub events: Arc<EventBus>,
    /// Relay link state (docs/RELAY.md), shown on the settings page.
    pub relay_status: Arc<parking_lot::Mutex<crate::relay::RelayStatus>>,
    pub connections: ConnectionTracker,
    pub backend: Arc<dyn CcSwitchBackend>,
    pub terminals: Arc<TerminalManager>,
    pub agents: Arc<AgentHub>,
    pub files: FileService,
    /// Port forwarding leases (docs/PROTOCOL.md §7.11).
    pub forwards: crate::forward::ForwardManager,
    /// DeepSeek Harness Web started / found for the phone (docs/PROTOCOL.md §7.14).
    pub dsh_web: Arc<crate::dsh_web::DshWeb>,
    /// Push wake subscriptions and sending (docs/PROTOCOL.md §7.12).
    pub push: Arc<crate::push::PushWaker>,
    limiter: RateLimiter,
}

impl ApiContext {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        config: RemoteServerConfig,
        server_id: String,
        identity: ServerIdentity,
        registry: Arc<Registry>,
        events: Arc<EventBus>,
        backend: Arc<dyn CcSwitchBackend>,
        terminals: Arc<TerminalManager>,
        agents: Arc<AgentHub>,
    ) -> Arc<Self> {
        // Files attached from the phone live in SwitchTether's own data directory.
        agents.set_attachment_root(config.data_dir.join("attachments"));
        agents.set_session_options_file(config.data_dir.join("session-options.json"));
        let push = crate::push::PushWaker::new(
            registry.clone(),
            Arc::new(crate::push::HttpSender::default()),
        );
        Arc::new(Self {
            config: RwLock::new(config),
            server_id,
            identity,
            registry,
            pairing: PairingManager::new(),
            stepup: StepUpManager::default(),
            events,
            relay_status: Arc::new(parking_lot::Mutex::new(crate::relay::RelayStatus {
                state: "off".into(),
                ..Default::default()
            })),
            connections: ConnectionTracker::default(),
            backend,
            terminals,
            agents,
            files: FileService::new(),
            forwards: Default::default(),
            dsh_web: Default::default(),
            push,
            limiter: RateLimiter::default(),
        })
    }

    /// Revoke a device: deny its certificate, drop grants, close sockets,
    /// emit events and write an audit record.
    pub fn revoke_device(&self, device_id: &str, actor: &str) -> anyhow::Result<bool> {
        let Some(rec) = self.registry.revoke_device(device_id)? else {
            return Ok(false);
        };
        self.stepup.clear_device(device_id);
        self.terminals.release_device(device_id);
        self.forwards.release_device(device_id);
        self.push.refresh_subscribers();
        let closed = self
            .connections
            .disconnect_fingerprint(&rec.cert_fingerprint);
        self.events.publish(
            "device.revoked",
            serde_json::json!({ "deviceId": device_id }),
        );
        self.registry.audit(
            Some(device_id),
            "device.revoke",
            Some(&rec.name),
            "ok",
            Some(&format!("by {actor}; closed {closed} connection(s)")),
        );
        Ok(true)
    }
}

/// Per-device token bucket: 30 req/s sustained, burst 120.
#[derive(Default)]
struct RateLimiter {
    buckets: Mutex<HashMap<String, (f64, Instant)>>,
}

impl RateLimiter {
    const RATE: f64 = 30.0;
    const BURST: f64 = 120.0;

    fn check(&self, key: &str) -> Result<(), u64> {
        let mut b = self.buckets.lock();
        let now = Instant::now();
        let (tokens, last) = b.entry(key.to_string()).or_insert((Self::BURST, now));
        let elapsed = now.duration_since(*last).as_secs_f64();
        *tokens = (*tokens + elapsed * Self::RATE).min(Self::BURST);
        *last = now;
        if *tokens >= 1.0 {
            *tokens -= 1.0;
            Ok(())
        } else {
            Err(((1.0 - *tokens) / Self::RATE * 1000.0).ceil() as u64)
        }
    }
}

/// An authenticated, paired, non-revoked device.
#[derive(Clone)]
pub struct AuthDevice {
    pub device: DeviceRecord,
    pub peer: PeerInfo,
}

impl AuthDevice {
    pub fn id(&self) -> &str {
        &self.device.id
    }
}

#[async_trait::async_trait]
impl FromRequestParts<Arc<ApiContext>> for AuthDevice {
    type Rejection = ApiError;

    async fn from_request_parts(
        parts: &mut Parts,
        ctx: &Arc<ApiContext>,
    ) -> Result<Self, Self::Rejection> {
        let peer = parts
            .extensions
            .get::<PeerInfo>()
            .cloned()
            .ok_or_else(|| ApiError::new(ErrorCode::Unpaired, "no TLS client identity"))?;
        let Some(device) = ctx.registry.device_by_fingerprint(&peer.fingerprint) else {
            if ctx.registry.is_revoked_fingerprint(&peer.fingerprint) {
                peer.cancel.cancel();
                return Err(ApiError::new(
                    ErrorCode::DeviceRevoked,
                    "this device has been revoked; pair again",
                ));
            }
            return Err(ApiError::new(
                ErrorCode::Unpaired,
                "this device is not paired with the desktop",
            ));
        };
        if let Err(retry_after_ms) = ctx.limiter.check(&device.id) {
            return Err(ApiError::new(ErrorCode::RateLimited, "too many requests")
                .with_details(serde_json::json!({ "retryAfterMs": retry_after_ms })));
        }
        let stale = device
            .last_seen_at_ms
            .is_none_or(|t| crate::util::now_ms() - t > 60_000);
        if stale {
            let reg = ctx.registry.clone();
            let (id, ip) = (device.id.clone(), peer.ip().to_string());
            tokio::task::spawn_blocking(move || reg.touch(&id, Some(&ip)));
        }
        Ok(AuthDevice { device, peer })
    }
}

/// Require a valid step-up grant for `scope`.
pub fn require_step_up(ctx: &ApiContext, dev: &AuthDevice, scope: Scope) -> ApiResult<()> {
    if !dev.device.step_up_enabled {
        return Err(ApiError::new(
            ErrorCode::StepUpRequired,
            "this device has no biometric step-up key; re-pair on a phone with a secure lock screen",
        )
        .with_details(serde_json::json!({ "scope": scope.as_str(), "reason": "no_step_up_key" })));
    }
    if ctx.stepup.has_grant(dev.id(), scope) {
        Ok(())
    } else {
        Err(ApiError::new(
            ErrorCode::StepUpRequired,
            format!(
                "{} requires biometric confirmation on the phone",
                scope.as_str()
            ),
        )
        .with_details(serde_json::json!({ "scope": scope.as_str() })))
    }
}

/// Run a side-effecting operation exactly once per `(device, commandId)`.
///
/// - First call: executes `op`, stores the outcome, publishes
///   `command.completed` to the device.
/// - Repeat after completion: returns the stored outcome without executing
///   (`X-Command-Replayed: true`).
/// - Repeat while running / after a crash: `409 command_in_progress` — the
///   client must query `GET /v1/commands/{id}` and let the user decide.
pub async fn idempotent<F, Fut>(
    ctx: &Arc<ApiContext>,
    device_id: String,
    command_id: Option<String>,
    kind: &'static str,
    op: F,
) -> Response
where
    F: FnOnce() -> Fut,
    Fut: Future<Output = ApiResult<Value>>,
{
    let Some(command_id) = command_id.filter(|c| !c.trim().is_empty() && c.len() <= 128) else {
        return ApiError::validation("commandId is required for this operation").into_response();
    };
    let begin = {
        let reg = ctx.registry.clone();
        let (d, c) = (device_id.clone(), command_id.clone());
        match tokio::task::spawn_blocking(move || reg.begin_command(&d, &c, kind)).await {
            Ok(Ok(b)) => b,
            Ok(Err(e)) => return ApiError::conflict(e.to_string()).into_response(),
            Err(e) => return ApiError::from(e).into_response(),
        }
    };
    match begin {
        CommandBegin::Done(rec) => {
            let mut resp = match (rec.result, rec.error) {
                (Some(v), _) => Json(v).into_response(),
                (None, Some(err)) => stored_error(&err).into_response(),
                (None, None) => Json(Value::Null).into_response(),
            };
            resp.headers_mut()
                .insert(X_COMMAND_REPLAYED, HeaderValue::from_static("true"));
            resp
        }
        CommandBegin::InProgress(rec) => ApiError::new(
            ErrorCode::CommandInProgress,
            "this command is still running or its outcome is unknown",
        )
        .with_details(serde_json::to_value(&rec).unwrap_or_default())
        .into_response(),
        CommandBegin::New => {
            let outcome = op().await;
            let stored = match &outcome {
                Ok(v) => Ok(v.clone()),
                Err(e) => Err(serde_json::json!({
                    "code": e.code, "message": e.message, "details": e.details
                })),
            };
            {
                let reg = ctx.registry.clone();
                let (d, c) = (device_id.clone(), command_id.clone());
                let st = stored.clone();
                let _ =
                    tokio::task::spawn_blocking(move || reg.complete_command(&d, &c, st.as_ref()))
                        .await;
            }
            ctx.events.publish_to(
                &device_id,
                "command.completed",
                serde_json::json!({ "commandId": command_id, "kind": kind, "ok": outcome.is_ok() }),
            );
            match outcome {
                Ok(v) => Json(v).into_response(),
                Err(e) => e.into_response(),
            }
        }
    }
}

fn stored_error(err: &Value) -> ApiError {
    let code: ErrorCode =
        serde_json::from_value(err["code"].clone()).unwrap_or(ErrorCode::Internal);
    let mut e = ApiError::new(code, err["message"].as_str().unwrap_or("failed"));
    if !err["details"].is_null() {
        e.details = Some(err["details"].clone());
    }
    e
}

/// Map a backend (CC Switch service) error.
pub(crate) fn backend_err(ctx: &ApiContext, e: String) -> ApiError {
    if !ctx.backend.available() {
        return ApiError::new(
            ErrorCode::BackendUnavailable,
            "CC Switch backend is not ready",
        );
    }
    ApiError::backend(e)
}

async fn request_id_layer(req: Request, next: Next) -> Response {
    let rid = req
        .headers()
        .get(&X_REQUEST_ID)
        .and_then(|v| v.to_str().ok())
        .filter(|v| v.len() <= 64 && v.chars().all(|c| c.is_ascii_alphanumeric() || c == '-'))
        .map(|s| s.to_string())
        .unwrap_or_else(crate::util::new_id);
    let started = Instant::now();
    let method = req.method().clone();
    let path = req.uri().path().to_string();
    let mut resp = next.run(req).await;
    if let Ok(v) = HeaderValue::from_str(&rid) {
        resp.headers_mut().insert(X_REQUEST_ID, v);
    }
    let status = resp.status();
    if started.elapsed() > Duration::from_secs(2) || status.is_server_error() {
        log::info!(
            "[remote] {method} {path} -> {status} in {:?} (rid {rid})",
            started.elapsed()
        );
    }
    resp
}

pub fn router(ctx: Arc<ApiContext>) -> Router {
    Router::new()
        .merge(core_routes::routes())
        .merge(events_ws::routes())
        .merge(cc::routes())
        .merge(sessions::routes())
        .merge(terminals::routes())
        .merge(files::routes())
        .merge(forward::routes())
        .merge(dsh_web::routes())
        .merge(push::routes())
        .layer(axum::extract::DefaultBodyLimit::max(MAX_JSON_BODY))
        .layer(axum::middleware::from_fn(request_id_layer))
        .fallback(|| async { ApiError::not_found("no such endpoint") })
        .with_state(ctx)
}

/// Peer identity for endpoints that are allowed before pairing.
pub type Peer = Extension<PeerInfo>;
