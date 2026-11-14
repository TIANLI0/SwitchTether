//! Windows updates through the FluentSetup installer (packaging/windows).
//!
//! An installed SwitchTether has `uninstall.exe` + `uninstall.json` next to
//! `switchtether.exe`. `uninstall.exe /check-update` asks the update source
//! (public GitHub releases of TIANLI0/SwitchTether, verified by SHA-256 and
//! app id) and `/install-update /passive /relaunch` downloads, verifies and
//! installs the new Setup.exe, closing and restarting SwitchTether.
//! Portable / development builds have no installer and report `supported: false`.

use std::path::PathBuf;
use std::time::Duration;

use serde_json::{json, Value};

fn uninstaller() -> Option<PathBuf> {
    let dir = std::env::current_exe().ok()?.parent()?.to_path_buf();
    let exe = dir.join("uninstall.exe");
    (exe.is_file() && dir.join("uninstall.json").is_file()).then_some(exe)
}

/// `{ supported, available, installedVersion, latestVersion, notes, error }`
pub async fn check() -> Value {
    let Some(exe) = uninstaller() else {
        return json!({ "supported": false });
    };
    let mut cmd = tokio::process::Command::new(&exe);
    cmd.arg("/check-update")
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true);
    #[cfg(windows)]
    cmd.creation_flags(0x0800_0000);
    let out = match tokio::time::timeout(Duration::from_secs(60), cmd.output()).await {
        Ok(Ok(o)) => o,
        Ok(Err(e)) => return json!({ "supported": true, "error": e.to_string() }),
        Err(_) => return json!({ "supported": true, "error": "update check timed out" }),
    };
    let line = String::from_utf8_lossy(&out.stdout);
    let parsed: Value = line
        .lines()
        .rev()
        .find_map(|l| serde_json::from_str(l.trim()).ok())
        .unwrap_or(Value::Null);
    match out.status.code() {
        Some(0) | Some(18) => json!({
            "supported": true,
            "available": out.status.code() == Some(18),
            "installedVersion": parsed.get("installedVersion"),
            "latestVersion": parsed.get("latestVersion"),
            "notes": parsed.get("notes"),
        }),
        Some(19) => json!({ "supported": true, "error": "the update source could not be reached" }),
        code => json!({ "supported": true, "error": format!("update check failed ({code:?})") }),
    }
}

/// Start the FluentSetup update (it closes and relaunches SwitchTether).
/// `None` when this build was not installed by FluentSetup.
pub fn start_install() -> Option<Result<bool, String>> {
    let exe = uninstaller()?;
    let mut cmd = std::process::Command::new(&exe);
    cmd.args(["/install-update", "/passive", "/relaunch"]);
    Some(
        cmd.spawn()
            .map(|_| true)
            .map_err(|e| format!("could not start the updater: {e}")),
    )
}
