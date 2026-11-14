//! `switchtether.exe --replace-cc-switch`: run by the SwitchTether installer
//! (FluentSetup action, option "replace CC Switch") after SwitchTether has
//! been installed. It makes SwitchTether take CC Switch's place:
//!
//! 1. stops a running CC Switch,
//! 2. carries over "start with Windows" (CC Switch's Run value → SwitchTether's),
//! 3. uninstalls the CC Switch program (NSIS uninstaller `/S` or `msiexec /x`).
//!
//! CC Switch's data (`~/.cc-switch`) is never touched: uninstallers do not
//! remove it, and SwitchTether imports a copy of it on its first start
//! (upstream_import.rs). Every step is logged to
//! `~/.switchtether/logs/replace-cc-switch.log`; failures are reported but do
//! not undo the SwitchTether installation.

#[cfg(windows)]
use std::io::Write;
use std::process::ExitCode;

#[cfg(windows)]
const UPSTREAM_RUN_VALUE: &str = "CC Switch";
pub const AUTOSTART_NAME: &str = "SwitchTether";

#[cfg(windows)]
struct Log(Option<std::fs::File>);

#[cfg(windows)]
impl Log {
    fn open() -> Self {
        let dir = crate::config::get_app_home_dir().join("logs");
        let _ = std::fs::create_dir_all(&dir);
        Log(std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(dir.join("replace-cc-switch.log"))
            .ok())
    }
    fn line(&mut self, msg: &str) {
        if let Some(f) = self.0.as_mut() {
            let _ = writeln!(f, "{} {msg}", chrono::Local::now().to_rfc3339());
        }
    }
}

#[cfg(windows)]
mod win {
    use winreg::enums::{
        HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE, KEY_READ, KEY_WOW64_32KEY, KEY_WOW64_64KEY,
        KEY_WRITE,
    };
    use winreg::RegKey;

    pub struct Install {
        pub key: String,
        pub version: Option<String>,
        pub uninstall: String,
        pub quiet_uninstall: Option<String>,
        pub location: Option<String>,
    }

    const UNINSTALL: &str = r"Software\Microsoft\Windows\CurrentVersion\Uninstall";

    pub fn find_cc_switch() -> Vec<Install> {
        let mut out = Vec::new();
        let roots = [
            (HKEY_CURRENT_USER, KEY_READ),
            (HKEY_LOCAL_MACHINE, KEY_READ | KEY_WOW64_64KEY),
            (HKEY_LOCAL_MACHINE, KEY_READ | KEY_WOW64_32KEY),
        ];
        for (hive, flags) in roots {
            let Ok(root) = RegKey::predef(hive).open_subkey_with_flags(UNINSTALL, flags) else {
                continue;
            };
            for name in root.enum_keys().flatten() {
                let Ok(k) = root.open_subkey_with_flags(&name, flags) else {
                    continue;
                };
                let display: String = k.get_value("DisplayName").unwrap_or_default();
                if display.trim() != "CC Switch" {
                    continue;
                }
                let uninstall: String = k.get_value("UninstallString").unwrap_or_default();
                if uninstall.is_empty() || out.iter().any(|i: &Install| i.key == name) {
                    continue;
                }
                out.push(Install {
                    key: name,
                    version: k.get_value("DisplayVersion").ok(),
                    uninstall,
                    quiet_uninstall: k.get_value("QuietUninstallString").ok(),
                    location: k.get_value("InstallLocation").ok(),
                });
            }
        }
        out
    }

    fn run_key() -> std::io::Result<RegKey> {
        RegKey::predef(HKEY_CURRENT_USER).open_subkey_with_flags(
            r"Software\Microsoft\Windows\CurrentVersion\Run",
            KEY_READ | KEY_WRITE,
        )
    }

    pub fn run_value(name: &str) -> Option<String> {
        run_key().ok()?.get_value(name).ok()
    }

    pub fn set_run_value(name: &str, value: &str) -> std::io::Result<()> {
        run_key()?.set_value(name, &value)
    }

    pub fn delete_run_value(name: &str) -> std::io::Result<()> {
        run_key()?.delete_value(name)
    }

    /// `ccswitch://` links (provider imports from websites) → SwitchTether.
    /// Written after CC Switch's uninstaller ran, which removes its own handler.
    pub fn register_ccswitch_scheme(exe: &std::path::Path) -> std::io::Result<()> {
        let classes = RegKey::predef(HKEY_CURRENT_USER)
            .open_subkey_with_flags(r"Software\Classes", KEY_READ | KEY_WRITE)?;
        let (key, _) = classes.create_subkey("ccswitch")?;
        key.set_value("", &"URL:ccswitch")?;
        key.set_value("URL Protocol", &"")?;
        let (icon, _) = key.create_subkey("DefaultIcon")?;
        icon.set_value("", &format!("\"{}\",0", exe.display()))?;
        let (cmd, _) = key.create_subkey(r"shell\open\command")?;
        cmd.set_value("", &format!("\"{}\" \"%1\"", exe.display()))?;
        Ok(())
    }
}

#[cfg(windows)]
fn stop_cc_switch(log: &mut Log) {
    use std::os::windows::process::CommandExt;
    let out = std::process::Command::new("taskkill")
        .args(["/IM", "cc-switch.exe", "/F"])
        .creation_flags(0x0800_0000)
        .output();
    if let Ok(o) = out {
        log.line(&format!("stop cc-switch.exe: exit {:?}", o.status.code()));
    }
}

/// Split `"C:\path\uninstall.exe" /args` or `MsiExec.exe /X{GUID}`.
#[cfg(any(windows, test))]
fn split_command(cmd: &str) -> (String, Vec<String>) {
    let cmd = cmd.trim();
    if let Some(rest) = cmd.strip_prefix('"') {
        if let Some(end) = rest.find('"') {
            let program = rest[..end].to_string();
            let args = rest[end + 1..]
                .split_whitespace()
                .map(String::from)
                .collect();
            return (program, args);
        }
    }
    let mut parts = cmd.split_whitespace();
    let program = parts.next().unwrap_or_default().to_string();
    (program, parts.map(String::from).collect())
}

/// Silent uninstall command for one CC Switch installation.
#[cfg(any(windows, test))]
fn uninstall_command(uninstall: &str, quiet: Option<&str>) -> (String, Vec<String>) {
    let lower = uninstall.to_ascii_lowercase();
    if lower.contains("msiexec") {
        // MSI (CC Switch's .msi): product code from /X{GUID} or /I{GUID}.
        let guid = uninstall
            .find('{')
            .and_then(|s| {
                uninstall[s..]
                    .find('}')
                    .map(|e| uninstall[s..s + e + 1].to_string())
            })
            .unwrap_or_default();
        return (
            "msiexec.exe".into(),
            vec!["/x".into(), guid, "/passive".into(), "/norestart".into()],
        );
    }
    if let Some(q) = quiet.filter(|q| !q.trim().is_empty()) {
        return split_command(q);
    }
    // NSIS (CC Switch's -setup.exe): `/S` = silent.
    let (program, mut args) = split_command(uninstall);
    if !args.iter().any(|a| a.eq_ignore_ascii_case("/S")) {
        args.push("/S".into());
    }
    (program, args)
}

#[cfg(windows)]
pub fn run() -> ExitCode {
    use std::os::windows::process::CommandExt;
    let mut log = Log::open();
    log.line("replace CC Switch: start");
    let installs = win::find_cc_switch();
    if installs.is_empty() {
        log.line("CC Switch is not installed; nothing to do");
        return ExitCode::SUCCESS;
    }
    stop_cc_switch(&mut log);

    // Start with Windows: keep the user's choice.
    if let Some(v) = win::run_value(UPSTREAM_RUN_VALUE) {
        let points_to_cc_switch = v.to_ascii_lowercase().contains("cc-switch");
        if points_to_cc_switch {
            if let Ok(exe) = std::env::current_exe() {
                let value = format!("\"{}\"", exe.display());
                match win::set_run_value(AUTOSTART_NAME, &value) {
                    Ok(()) => log.line("autostart carried over to SwitchTether"),
                    Err(e) => log.line(&format!("could not enable SwitchTether autostart: {e}")),
                }
            }
            let _ = win::delete_run_value(UPSTREAM_RUN_VALUE);
        }
    }

    let mut failed = false;
    for inst in installs {
        let (program, args) = uninstall_command(&inst.uninstall, inst.quiet_uninstall.as_deref());
        log.line(&format!(
            "uninstall CC Switch {} ({}) at {:?}: {program} {}",
            inst.version.as_deref().unwrap_or("?"),
            inst.key,
            inst.location,
            args.join(" ")
        ));
        let status = std::process::Command::new(&program)
            .args(&args)
            .creation_flags(0x0800_0000)
            .status();
        match status {
            Ok(s) if s.success() || s.code() == Some(3010) => log.line("uninstalled"),
            Ok(s) => {
                failed = true;
                log.line(&format!("uninstaller exited with {:?}", s.code()));
            }
            Err(e) => {
                failed = true;
                log.line(&format!("could not start the uninstaller: {e}"));
            }
        }
    }
    if !failed {
        if let Ok(exe) = std::env::current_exe() {
            match win::register_ccswitch_scheme(&exe) {
                Ok(()) => log.line("ccswitch:// links now open SwitchTether"),
                Err(e) => log.line(&format!("could not register ccswitch://: {e}")),
            }
        }
    }
    log.line("replace CC Switch: done");
    if failed {
        ExitCode::from(1)
    } else {
        ExitCode::SUCCESS
    }
}

#[cfg(not(windows))]
pub fn run() -> ExitCode {
    eprintln!("--replace-cc-switch is only used by the Windows installer");
    ExitCode::from(2)
}

/// Earlier SwitchTether builds registered autostart under CC Switch's Run
/// value name. Move it to SwitchTether's own name (only if it points to us).
pub fn migrate_autostart_name() {
    #[cfg(windows)]
    {
        let Some(v) = win::run_value(UPSTREAM_RUN_VALUE) else {
            return;
        };
        if v.to_ascii_lowercase().contains("switchtether")
            && win::set_run_value(AUTOSTART_NAME, &v).is_ok()
        {
            let _ = win::delete_run_value(UPSTREAM_RUN_VALUE);
            log::info!("[switchtether] moved the autostart entry to its own name");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uninstall_commands() {
        assert_eq!(
            uninstall_command("MsiExec.exe /X{F03AC4C8-5D56-48DA-A33A-44E1CCA05CE8}", None),
            (
                "msiexec.exe".to_string(),
                vec![
                    "/x".to_string(),
                    "{F03AC4C8-5D56-48DA-A33A-44E1CCA05CE8}".to_string(),
                    "/passive".to_string(),
                    "/norestart".to_string()
                ]
            )
        );
        assert_eq!(
            uninstall_command(
                r#""C:\Users\u\AppData\Local\CC Switch\uninstall.exe""#,
                None
            ),
            (
                r"C:\Users\u\AppData\Local\CC Switch\uninstall.exe".to_string(),
                vec!["/S".to_string()]
            )
        );
        assert_eq!(
            uninstall_command(
                r#""C:\x\uninstall.exe""#,
                Some(r#""C:\x\uninstall.exe" /S /keep"#)
            )
            .1,
            vec!["/S".to_string(), "/keep".to_string()]
        );
    }
}
