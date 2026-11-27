//! SwitchTether-specific desktop integration.
//!
//! Everything that is not part of upstream CC Switch lives under this module
//! (and in the `switchtether-core` crate) so that upstream syncs only touch a
//! handful of well-known hook points in `lib.rs`. See docs/UPSTREAM_SYNC.md.

pub mod bridge;
pub mod commands;
pub mod remote;
pub mod replace_cc_switch;
pub mod takeover;
pub mod update;
pub mod upstream_import;

use tauri::Manager;

/// Called at the very start of Tauri `setup`, after logging is initialised and
/// before the database or settings are opened.
pub fn on_setup_before_database() {
    replace_cc_switch::migrate_autostart_name();
    if crate::app_store::get_app_config_dir_override().is_some() {
        // The user pointed the data dir somewhere explicit; never import into it.
        return;
    }
    let source = crate::config::get_upstream_app_home_dir();
    let target = crate::config::get_app_home_dir();
    if source == target {
        return;
    }
    if let Some(receipt) = upstream_import::import_if_first_run(&source, &target) {
        log::info!(
            "[switchtether] CC Switch data import: {:?} (schema {:?}, copied {:?}){}",
            receipt.outcome,
            receipt.source_schema_version,
            receipt.copied,
            receipt
                .error
                .as_deref()
                .map(|e| format!(", error: {e}"))
                .unwrap_or_default()
        );
    }
}

/// Register remote state and initialise the Remote Core in the background
/// (keychain access may be slow or prompt on macOS).
/// Product name in upstream (CC Switch) strings shown by native UI (tray menu,
/// tooltips). The web UI is rebranded by the i18n post-processor instead.
pub fn brand(text: &str) -> std::borrow::Cow<'_, str> {
    if text.contains("CC Switch") {
        std::borrow::Cow::Owned(text.replace("CC Switch", "SwitchTether"))
    } else {
        std::borrow::Cow::Borrowed(text)
    }
}

/// CC Switch release this build is based on (`UPSTREAM_VERSION`).
const UPSTREAM_VERSION: &str = include_str!("../../../UPSTREAM_VERSION");

pub fn upstream_version() -> &'static str {
    UPSTREAM_VERSION.trim().trim_start_matches('v')
}

/// macOS App Nap throttles timers and network of an app whose windows are hidden, which is
/// SwitchTether's normal state (tray only) while a phone drives it. Declare an ongoing
/// user-initiated activity for the app's lifetime. Idle system sleep stays allowed: SwitchTether
/// never changes the computer's power behaviour.
#[cfg(target_os = "macos")]
fn keep_responsive() {
    use objc2_foundation::{NSActivityOptions, NSProcessInfo, NSString};
    let activity = NSProcessInfo::processInfo().beginActivityWithOptions_reason(
        NSActivityOptions::UserInitiatedAllowingIdleSystemSleep,
        &NSString::from_str("SwitchTether Remote serves paired phones in the background"),
    );
    // Held until the process exits.
    std::mem::forget(activity);
}

pub fn setup_remote(app: &tauri::App) {
    #[cfg(target_os = "macos")]
    keep_responsive();
    app.manage(remote::RemoteState::default());
    let handle = app.handle().clone();
    tauri::async_runtime::spawn(async move {
        // A GUI app opened from Finder / the Dock / a launcher has a minimal PATH; give the
        // Remote Core the user's login-shell PATH before it looks for claude / codex / dsh.
        #[cfg(not(windows))]
        {
            let login = tokio::task::spawn_blocking(crate::commands::cached_login_shell_path)
                .await
                .ok()
                .flatten();
            match login {
                Some(path) => switchtether_core::util::set_login_path(&path),
                None => log::warn!(
                    "[switchtether] login shell PATH not resolved; using the inherited PATH"
                ),
            }
        }
        // Keep the shim used by enabled takeover hooks up to date.
        let _ = tokio::task::spawn_blocking(takeover::refresh_installed_shim).await;
        // One-time default of the takeover hooks (marker-guarded; may run `dsh plugin`).
        tokio::task::spawn_blocking(takeover::apply_first_run_defaults);
        match remote::RemoteRuntime::init(handle.clone()).await {
            Ok(rt) => {
                if let Some(state) = handle.try_state::<remote::RemoteState>() {
                    let _ = state.0.set(rt);
                }
            }
            Err(e) => log::error!("[switchtether] remote core init failed: {e:#}"),
        }
    });
}

fn zh() -> bool {
    sys_locale::get_locale()
        .map(|l| l.to_lowercase().starts_with("zh"))
        .unwrap_or(false)
}

/// "Really quit" entry point (tray Quit / close-to-exit). Warns when terminals,
/// phone connections or managed agent sessions would be interrupted and lets
/// the user cancel (SwitchTether keeps running in the tray).
pub fn request_quit(app: &tauri::AppHandle) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let impact = match app
            .try_state::<remote::RemoteState>()
            .and_then(|s| s.get().ok())
        {
            Some(rt) => rt.quit_impact().await,
            None => serde_json::json!({}),
        };
        let terminals = impact["terminals"].as_array().cloned().unwrap_or_default();
        let conns = impact["connections"].as_u64().unwrap_or(0);
        let managed = impact["managedSessions"].as_u64().unwrap_or(0);
        if terminals.is_empty() && conns == 0 && managed == 0 {
            app.exit(0);
            return;
        }
        use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};
        let zh = zh();
        let mut lines = Vec::new();
        if !terminals.is_empty() {
            lines.push(if zh {
                format!("以下 {} 个远程终端将被结束：", terminals.len())
            } else {
                format!(
                    "These {} remote terminal(s) will be ended:",
                    terminals.len()
                )
            });
            for t in terminals.iter().take(8) {
                lines.push(format!(
                    "  • {} — {}",
                    t["title"].as_str().unwrap_or(""),
                    t["cwd"].as_str().unwrap_or("")
                ));
            }
        }
        if managed > 0 {
            lines.push(if zh {
                format!("{managed} 个由 SwitchTether 启动的 Agent 会话将停止。")
            } else {
                format!("{managed} agent session(s) started by SwitchTether will stop.")
            });
        }
        if conns > 0 {
            lines.push(if zh {
                format!("{conns} 个手机连接将断开。")
            } else {
                format!("{conns} phone connection(s) will be closed.")
            });
        }
        lines.push(String::new());
        lines.push(if zh {
            "你自己启动的 Claude Code / Codex 不受影响。".to_string()
        } else {
            "Claude Code / Codex sessions you started yourself are not affected.".to_string()
        });
        let (title, quit, keep) = if zh {
            ("退出 SwitchTether？", "退出", "保持在托盘运行")
        } else {
            ("Quit SwitchTether?", "Quit", "Keep running in tray")
        };
        let app2 = app.clone();
        app.dialog()
            .message(lines.join("\n"))
            .title(title)
            .kind(MessageDialogKind::Warning)
            .buttons(MessageDialogButtons::OkCancelCustom(
                quit.into(),
                keep.into(),
            ))
            .show(move |confirmed| {
                if confirmed {
                    app2.exit(0);
                } else {
                    log::info!("[switchtether] quit cancelled; staying in tray");
                }
            });
    });
}

/// Whether an upstream CC Switch process is running (best effort).
fn upstream_cc_switch_running() -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        std::process::Command::new("tasklist")
            .args(["/FI", "IMAGENAME eq cc-switch.exe", "/NH", "/FO", "CSV"])
            .creation_flags(0x0800_0000)
            .output()
            .map(|o| {
                String::from_utf8_lossy(&o.stdout)
                    .to_lowercase()
                    .contains("cc-switch.exe")
            })
            .unwrap_or(false)
    }
    #[cfg(not(windows))]
    {
        std::process::Command::new("pgrep")
            .args(["-x", "cc-switch"])
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }
}

/// Both apps write the same Claude Code / Codex / Gemini config files, so
/// running them side by side can make them overwrite each other's switches.
/// Warn once per launch; never touch the other process.
pub fn warn_if_upstream_running(app: &tauri::AppHandle) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let running = tokio::task::spawn_blocking(upstream_cc_switch_running)
            .await
            .unwrap_or(false);
        if !running {
            return;
        }
        log::warn!("[switchtether] upstream CC Switch is running at the same time");
        // Sandboxed test runs never touch the real agent configs; no dialog there.
        if std::env::var_os("CC_SWITCH_TEST_HOME").is_some() {
            return;
        }
        use tauri_plugin_dialog::{DialogExt, MessageDialogKind};
        let (title, body) = if zh() {
            (
                "CC Switch 正在运行",
                "检测到 CC Switch 也在运行。SwitchTether 与 CC Switch 管理同一批 Claude Code / Codex 配置文件，同时在两边切换供应商会互相覆盖。\n\n建议只在其中一个应用里管理供应商（SwitchTether 已在首次启动时复制了 CC Switch 的数据，原数据未被修改）。",
            )
        } else {
            (
                "CC Switch is running",
                "CC Switch is running at the same time. SwitchTether and CC Switch manage the same Claude Code / Codex configuration files, so switching providers in both apps makes them overwrite each other.\n\nManage providers in only one of them. (SwitchTether copied CC Switch's data on first launch; the original data was not modified.)",
            )
        };
        app.dialog()
            .message(body)
            .title(title)
            .kind(MessageDialogKind::Warning)
            .show(|_| {});
    });
}

/// Exit cleanup (called from `cleanup_before_exit`).
pub async fn on_exit(app: &tauri::AppHandle) {
    if let Some(rt) = app
        .try_state::<remote::RemoteState>()
        .and_then(|s| s.get().ok())
    {
        rt.shutdown_for_exit().await;
    }
}

#[cfg(test)]
mod path_tests {
    use serial_test::serial;

    /// Production data dir name must be `.switchtether`, never upstream's.
    #[test]
    #[serial]
    fn production_dir_name_is_isolated_from_upstream() {
        assert_eq!(crate::config::APP_DIR_NAME, ".switchtether");
        assert_ne!(
            crate::config::APP_DIR_NAME,
            crate::config::UPSTREAM_APP_DIR_NAME
        );
        let tmp = tempfile::tempdir().unwrap();
        let prev_home = std::env::var_os("CC_SWITCH_TEST_HOME");
        let prev_flag = std::env::var_os("SWITCHTETHER_TEST_APP_DIR");
        std::env::set_var("CC_SWITCH_TEST_HOME", tmp.path());
        std::env::set_var("SWITCHTETHER_TEST_APP_DIR", "1");
        let dir = crate::config::get_app_home_dir();
        match prev_home {
            Some(v) => std::env::set_var("CC_SWITCH_TEST_HOME", v),
            None => std::env::remove_var("CC_SWITCH_TEST_HOME"),
        }
        match prev_flag {
            Some(v) => std::env::set_var("SWITCHTETHER_TEST_APP_DIR", v),
            None => std::env::remove_var("SWITCHTETHER_TEST_APP_DIR"),
        }
        assert_eq!(dir, tmp.path().join(".switchtether"));
    }
}
