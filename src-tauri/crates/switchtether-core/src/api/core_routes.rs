//! Hello, pairing, status, step-up and command-status endpoints.

use std::sync::Arc;

use axum::extract::{Path, State};
use axum::routing::{get, post};
use axum::{Json, Router};
use base64::Engine as _;
use serde::Deserialize;
use serde_json::{json, Value};

use super::{ApiContext, AuthDevice, Peer};
use crate::error::{ApiError, ApiResult, ErrorCode};
use crate::pairing::PairingCheck;
use crate::protocol::{Hello, PairRequest, PairResponse};
use crate::registry::DeviceRecord;
use crate::stepup::{Scope, VerifyError};

pub fn routes() -> Router<Arc<ApiContext>> {
    Router::new()
        .route("/v1/hello", get(hello))
        .route("/v1/pair", post(pair))
        .route("/v1/status", get(status))
        .route("/v1/relay", get(relay_info))
        .route("/v1/capabilities", get(capabilities))
        .route("/v1/device", get(device_self).delete(unpair_self))
        .route("/v1/auth/step-up", get(step_up_grants))
        .route("/v1/auth/step-up/challenge", post(step_up_challenge))
        .route("/v1/auth/step-up/verify", post(step_up_verify))
        .route("/v1/commands/:command_id", get(command_status))
}

async fn hello(State(ctx): State<Arc<ApiContext>>, peer: Peer) -> Json<Hello> {
    let device = ctx.registry.device_by_fingerprint(&peer.fingerprint);
    let cfg = ctx.config.read();
    Json(Hello {
        protocol_version: crate::PROTOCOL_VERSION,
        min_protocol_version: crate::MIN_PROTOCOL_VERSION,
        server_id: ctx.server_id.clone(),
        server_name: cfg.server_name.clone(),
        server_version: cfg.server_version.clone(),
        paired: device.is_some(),
        device_id: device.map(|d| d.id),
        epoch: ctx.events.epoch().to_string(),
    })
}

fn clean_text(s: &str, max: usize) -> String {
    s.chars()
        .filter(|c| !c.is_control())
        .take(max)
        .collect::<String>()
        .trim()
        .to_string()
}

async fn pair(
    State(ctx): State<Arc<ApiContext>>,
    peer: Peer,
    Json(req): Json<PairRequest>,
) -> ApiResult<Json<PairResponse>> {
    if req.protocol_version < crate::MIN_PROTOCOL_VERSION {
        return Err(ApiError::validation("client protocol version is too old"));
    }
    if ctx.registry.is_revoked_fingerprint(&peer.fingerprint) {
        return Err(ApiError::new(
            ErrorCode::DeviceRevoked,
            "this client certificate was revoked; reset the app identity and pair again",
        ));
    }
    let check = match req.code.as_deref().filter(|c| !c.trim().is_empty()) {
        Some(code) => ctx.pairing.verify_code_and_consume(peer.ip(), code),
        None => ctx
            .pairing
            .verify_and_consume(peer.ip(), &req.pairing_id, &req.secret),
    };
    match check {
        PairingCheck::Ok => {}
        PairingCheck::Expired => {
            ctx.registry
                .audit(None, "pair", Some(&peer.ip().to_string()), "expired", None);
            return Err(ApiError::new(
                ErrorCode::PairingExpired,
                "pairing code expired or already used; show a new QR code on the desktop",
            ));
        }
        PairingCheck::WrongSecret => {
            ctx.registry.audit(
                None,
                "pair",
                Some(&peer.ip().to_string()),
                "wrong_secret",
                None,
            );
            return Err(ApiError::forbidden("pairing secret mismatch"));
        }
        PairingCheck::RateLimited { retry_after_ms } => {
            return Err(
                ApiError::new(ErrorCode::RateLimited, "too many pairing attempts")
                    .with_details(json!({ "retryAfterMs": retry_after_ms })),
            );
        }
    }

    let step_up_key = match req.step_up_public_key.as_deref() {
        Some(k) if !k.is_empty() => {
            let der = base64::engine::general_purpose::STANDARD
                .decode(k)
                .map_err(|_| ApiError::validation("stepUpPublicKey is not base64"))?;
            if crate::stepup::spki_to_uncompressed_point(&der).is_none() {
                return Err(ApiError::validation(
                    "stepUpPublicKey must be a P-256 SubjectPublicKeyInfo",
                ));
            }
            Some(der)
        }
        _ => None,
    };
    let name = clean_text(&req.device_name, 64);
    let rec = DeviceRecord {
        id: crate::util::new_id(),
        name: if name.is_empty() {
            "Android".into()
        } else {
            name
        },
        platform: clean_text(&req.platform, 16),
        os_version: req.os_version.as_deref().map(|s| clean_text(s, 32)),
        app_version: req.app_version.as_deref().map(|s| clean_text(s, 32)),
        cert_fingerprint: peer.fingerprint.clone(),
        step_up_enabled: step_up_key.is_some(),
        step_up_key,
        created_at_ms: crate::util::now_ms(),
        last_seen_at_ms: Some(crate::util::now_ms()),
        last_ip: Some(peer.ip().to_string()),
        revoked_at_ms: None,
    };
    let reg = ctx.registry.clone();
    let rec = tokio::task::spawn_blocking(move || reg.register_device(rec))
        .await?
        .map_err(|e| ApiError::internal("register device", e))?;
    // A re-pair replaces any previous grants of this device.
    ctx.stepup.clear_device(&rec.id);
    let code = crate::util::verification_code(&ctx.identity.fingerprint, &peer.fingerprint);
    ctx.registry.audit(
        Some(&rec.id),
        "pair",
        Some(&rec.name),
        "ok",
        Some(&peer.ip().to_string()),
    );
    ctx.events.publish(
        "device.paired",
        json!({ "deviceId": rec.id, "name": rec.name, "verificationCode": code }),
    );
    Ok(Json(PairResponse {
        device_id: rec.id,
        server_id: ctx.server_id.clone(),
        server_name: ctx.config.read().server_name.clone(),
        server_fingerprint: ctx.identity.fingerprint.clone(),
        step_up_enabled: rec.step_up_enabled,
        verification_code: code,
    }))
}

async fn status(State(ctx): State<Arc<ApiContext>>, dev: AuthDevice) -> Json<Value> {
    let cfg = ctx.config.read().clone();
    let (tz, offset_min) = crate::util::desktop_time_zone();
    Json(json!({
        "timeZone": tz,
        "utcOffsetMinutes": offset_min,
        "serverId": ctx.server_id,
        "serverName": cfg.server_name,
        "serverVersion": cfg.server_version,
        "protocolVersion": crate::PROTOCOL_VERSION,
        "epoch": ctx.events.epoch(),
        "headSeq": ctx.events.head_seq(),
        "networkMode": cfg.network_mode,
        "backendAvailable": ctx.backend.available(),
        "connections": ctx.connections.len(),
        "terminals": ctx.terminals.count(),
        "platform": std::env::consts::OS,
        "deviceId": dev.id(),
        "stepUpGrants": ctx.stepup.grants(dev.id()),
        // Wake-on-LAN targets (MAC + subnet broadcast of each LAN interface), kept by the phone.
        "wake": tokio::task::spawn_blocking(crate::wake::wake_targets).await.unwrap_or_default(),
        // Current addresses (same list as the pairing QR code): the phone merges them into
        // its stored candidates, so a changed IP heals on any successful connection.
        "hosts": crate::net::candidate_hosts(cfg.network_mode)
            .into_iter()
            .map(|h| h.address)
            .collect::<Vec<_>>(),
    }))
}

async fn capabilities(State(ctx): State<Arc<ApiContext>>, _dev: AuthDevice) -> Json<Value> {
    Json(json!({
        "protocolVersion": crate::PROTOCOL_VERSION,
        "agents": ctx.agents.capability_matrix(),
        "features": [
            "providers", "usage", "mcp", "skills", "prompts", "settings", "proxy",
            "sessions", "managed_sessions", "terminal", "files", "git", "step_up", "attachments", "port_forward",
            "session_options", "resume_on_send", "git_write", "push_wake", "session_search"
        ],
        "terminalShells": crate::terminal::available_shells(),
    }))
}

async fn device_self(_ctx: State<Arc<ApiContext>>, dev: AuthDevice) -> Json<DeviceRecord> {
    Json(dev.device)
}

/// The phone can unpair itself (e.g. "forget this computer").
async fn unpair_self(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
) -> ApiResult<Json<Value>> {
    let id = dev.id().to_string();
    let c = ctx.clone();
    let revoked = tokio::task::spawn_blocking(move || c.revoke_device(&id, "self"))
        .await?
        .map_err(|e| ApiError::internal("revoke", e))?;
    Ok(Json(json!({ "revoked": revoked })))
}

#[derive(Deserialize)]
struct ChallengeReq {
    scope: Scope,
}

async fn step_up_challenge(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Json(req): Json<ChallengeReq>,
) -> ApiResult<Json<Value>> {
    if !dev.device.step_up_enabled {
        return Err(ApiError::new(
            ErrorCode::StepUpRequired,
            "this device has no biometric step-up key",
        )
        .with_details(json!({ "reason": "no_step_up_key" })));
    }
    Ok(Json(
        serde_json::to_value(ctx.stepup.challenge(dev.id(), req.scope)).unwrap_or_default(),
    ))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct VerifyReq {
    challenge_id: String,
    signature: String,
}

async fn step_up_verify(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Json(req): Json<VerifyReq>,
) -> ApiResult<Json<Value>> {
    let sig = base64::engine::general_purpose::STANDARD
        .decode(req.signature.trim())
        .or_else(|_| base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(req.signature.trim()))
        .map_err(|_| ApiError::validation("signature is not base64"))?;
    match ctx.stepup.verify(
        dev.id(),
        dev.device.step_up_key.as_deref(),
        &req.challenge_id,
        &sig,
    ) {
        Ok(grant) => {
            ctx.registry.audit(
                Some(dev.id()),
                "step_up.grant",
                Some(grant.scope.as_str()),
                "ok",
                None,
            );
            Ok(Json(serde_json::to_value(grant).unwrap_or_default()))
        }
        Err(e) => {
            ctx.registry.audit(
                Some(dev.id()),
                "step_up.grant",
                None,
                "denied",
                Some(&format!("{e:?}")),
            );
            let msg = match e {
                VerifyError::UnknownChallenge => "challenge expired or already used",
                VerifyError::WrongDevice => "challenge belongs to another device",
                VerifyError::BadSignature => "signature verification failed",
                VerifyError::NoKey => "device has no step-up key",
            };
            Err(ApiError::forbidden(msg))
        }
    }
}

async fn step_up_grants(State(ctx): State<Arc<ApiContext>>, dev: AuthDevice) -> Json<Value> {
    Json(json!({ "grants": ctx.stepup.grants(dev.id()) }))
}

async fn command_status(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(command_id): Path<String>,
) -> ApiResult<Json<Value>> {
    let reg = ctx.registry.clone();
    let id = dev.id().to_string();
    let rec = tokio::task::spawn_blocking(move || reg.command(&id, &command_id))
        .await?
        .map_err(|e| ApiError::internal("command status", e))?
        .ok_or_else(|| ApiError::not_found("unknown commandId"))?;
    Ok(Json(serde_json::to_value(rec).unwrap_or_default()))
}

/// Relay URL and access token for an already paired phone (docs/RELAY.md §5),
/// so a token reset does not require re-pairing phones that are connected.
async fn relay_info(
    axum::extract::State(ctx): axum::extract::State<std::sync::Arc<super::ApiContext>>,
    _dev: super::AuthDevice,
) -> axum::Json<serde_json::Value> {
    let relay = ctx.config.read().relay.clone();
    axum::Json(match relay {
        Some(r) => {
            serde_json::json!({ "enabled": true, "url": r.url, "token": r.token, "key": r.key })
        }
        None => serde_json::json!({ "enabled": false }),
    })
}
