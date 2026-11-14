//! Tauri commands for the desktop "Remote (SwitchTether)" settings page.

use serde_json::{json, Value};
use tauri::State;

use super::remote::{RemoteConfig, RemoteState};

#[tauri::command]
pub async fn remote_status(state: State<'_, RemoteState>) -> Result<Value, String> {
    Ok(state.get()?.status().await)
}

#[tauri::command]
pub async fn remote_set_config(
    state: State<'_, RemoteState>,
    config: RemoteConfig,
) -> Result<Value, String> {
    state.get()?.set_config(config).await
}

#[tauri::command]
pub async fn remote_create_pairing(state: State<'_, RemoteState>) -> Result<Value, String> {
    state.get()?.create_pairing().await
}

#[tauri::command]
pub async fn remote_cancel_pairing(
    state: State<'_, RemoteState>,
    pairing_id: String,
) -> Result<(), String> {
    state.get()?.ctx.pairing.cancel(&pairing_id);
    Ok(())
}

#[tauri::command]
pub async fn remote_list_devices(state: State<'_, RemoteState>) -> Result<Value, String> {
    let rt = state.get()?;
    let reg = rt.ctx.registry.clone();
    let devices = tokio::task::spawn_blocking(move || reg.list_devices())
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())?;
    let connected = rt.ctx.connections.fingerprints();
    let out: Vec<Value> = devices
        .into_iter()
        .map(|d| {
            let online = connected.contains(&d.cert_fingerprint);
            let mut v = json!(d);
            v["online"] = json!(online);
            v["fingerprintShort"] = json!(&d.cert_fingerprint[..16.min(d.cert_fingerprint.len())]);
            v
        })
        .collect();
    Ok(json!(out))
}

#[tauri::command]
pub async fn remote_revoke_device(
    state: State<'_, RemoteState>,
    device_id: String,
) -> Result<bool, String> {
    let rt = state.get()?;
    let ctx = rt.ctx.clone();
    tokio::task::spawn_blocking(move || ctx.revoke_device(&device_id, "desktop"))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn remote_forget_device(
    state: State<'_, RemoteState>,
    device_id: String,
) -> Result<bool, String> {
    let rt = state.get()?;
    rt.ctx
        .registry
        .forget_device(&device_id)
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn remote_rename_device(
    state: State<'_, RemoteState>,
    device_id: String,
    name: String,
) -> Result<(), String> {
    state
        .get()?
        .ctx
        .registry
        .rename_device(&device_id, &name)
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn remote_list_terminals(state: State<'_, RemoteState>) -> Result<Value, String> {
    Ok(json!(state.get()?.ctx.terminals.list()))
}

#[tauri::command]
pub async fn remote_kill_terminal(
    state: State<'_, RemoteState>,
    id: String,
) -> Result<bool, String> {
    let rt = state.get()?;
    let ok = rt.ctx.terminals.remove(&id);
    rt.ctx
        .registry
        .audit(None, "terminal.kill", Some(&id), "ok", Some("by desktop"));
    Ok(ok)
}

#[tauri::command]
pub async fn remote_audit_log(
    state: State<'_, RemoteState>,
    limit: Option<usize>,
    before_id: Option<i64>,
) -> Result<Value, String> {
    let rt = state.get()?;
    let entries = rt
        .ctx
        .registry
        .audit_entries(limit.unwrap_or(100).min(500), before_id)
        .map_err(|e| e.to_string())?;
    Ok(json!(entries))
}

#[tauri::command]
pub async fn remote_quit_impact(state: State<'_, RemoteState>) -> Result<Value, String> {
    Ok(state.get()?.quit_impact().await)
}

/// Opt-in takeover hooks (VS Code wrapper, Codex Desktop, terminal launchers).
#[tauri::command]
pub async fn remote_takeover_status() -> Result<Value, String> {
    tokio::task::spawn_blocking(super::takeover::status)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn remote_set_takeover(hook: String, enabled: bool) -> Result<Value, String> {
    tokio::task::spawn_blocking(move || super::takeover::set(&hook, enabled))
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn remote_reset_relay_token(state: State<'_, RemoteState>) -> Result<Value, String> {
    state.get()?.reset_relay_token().await
}

/// Update check through the FluentSetup installer (Windows installs).
#[tauri::command]
pub async fn st_update_check() -> Result<Value, String> {
    Ok(super::update::check().await)
}
