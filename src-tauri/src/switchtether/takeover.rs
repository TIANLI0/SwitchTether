//! Takeover hooks ("接管"): the only place where SwitchTether changes anything
//! outside its own data directory. They are turned on once by default at first
//! run ([`apply_first_run_defaults`], guarded by a marker so it never runs again)
//! and are then under the user's control in Settings → Remote: a switch the user
//! turns off stays off. Each hook:
//! - points the user's program at `switchtether-shim` (a pass-through wrapper
//!   when SwitchTether is not running),
//! - backs up what it changes into `~/.switchtether/backups/`,
//! - refuses to replace a value the user set themselves,
//! - is removed exactly by turning it off again.
//!
//! Hooks:
//! - `vscode`: `claudeCode.claudeProcessWrapper` in the VS Code-family user
//!   settings (VS Code, Insiders, Cursor, Windsurf, VSCodium). New Claude
//!   Code panels start through the shim; open panels are not affected.
//! - `codex_desktop` (Windows): user environment variable `CODEX_CLI_PATH`.
//!   Codex Desktop picks it up after it is restarted.
//! - `cli`: `st-claude` / `st-codex` launchers in `~/.switchtether/bin`
//!   (Windows: that folder is added to the user PATH). Plain `claude` /
//!   `codex` are never redirected.
//! - `dsh`: the SwitchTether bridge plugin in DeepSeek Harness's `web` profile, installed with
//!   dsh's own `dsh plugin --profile web add link:<~/.switchtether/dsh-plugin>` and removed with
//!   `dsh plugin --profile web remove`. The profile's manifest, patch and lock files are backed up
//!   first. dsh refuses CLI plugin management for its Desktop (Electron) profile, so only
//!   `dsh web` / CLI hosts are covered. Takes effect when dsh web is next started.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

const WRAPPER_KEY: &str = "claudeCode.claudeProcessWrapper";

fn home() -> PathBuf {
    crate::config::get_app_home_dir()
}

fn bin_dir() -> PathBuf {
    home().join("bin")
}

fn shim_name() -> &'static str {
    if cfg!(windows) {
        "switchtether-shim.exe"
    } else {
        "switchtether-shim"
    }
}

/// Stable copy of the shim that hooks point to (survives app moves/updates).
pub fn installed_shim() -> PathBuf {
    bin_dir().join(shim_name())
}

fn state_file() -> PathBuf {
    home().join("remote").join("takeover.json")
}

#[derive(Debug, Default, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TakeoverState {
    /// settings.json files we added the wrapper key to.
    #[serde(default)]
    vscode_files: Vec<String>,
    #[serde(default)]
    codex_env: bool,
    #[serde(default)]
    cli: bool,
    #[serde(default)]
    cli_path_added: bool,
    #[serde(default)]
    dsh: bool,
    /// Backup of the dsh web profile's package.json taken when the bridge was enabled.
    #[serde(default)]
    dsh_manifest_backup: Option<String>,
}

fn load_state() -> TakeoverState {
    std::fs::read_to_string(state_file())
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn save_state(s: &TakeoverState) -> Result<(), String> {
    let p = state_file();
    if let Some(d) = p.parent() {
        std::fs::create_dir_all(d).map_err(|e| e.to_string())?;
    }
    std::fs::write(&p, serde_json::to_vec_pretty(s).unwrap()).map_err(|e| e.to_string())
}

fn backup_to(path: &Path, dir: &Path, tag: &str) -> Result<PathBuf, String> {
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let stamp = chrono::Local::now().format("%Y%m%d-%H%M%S%.3f");
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_else(|| "file".into());
    let dst = dir.join(format!("{tag}-{stamp}-{name}"));
    std::fs::copy(path, &dst).map_err(|e| format!("backup failed: {e}"))?;
    Ok(dst)
}

/// The shim shipped with this build: next to the app executable (Tauri
/// sidecar), or `SWITCHTETHER_SHIM` for development.
fn bundled_shim() -> Option<PathBuf> {
    if let Some(p) = std::env::var_os("SWITCHTETHER_SHIM") {
        let p = PathBuf::from(p);
        if p.is_file() {
            return Some(p);
        }
    }
    let exe = std::env::current_exe().ok()?;
    let p = exe.parent()?.join(shim_name());
    p.is_file().then_some(p)
}

fn file_hash(p: &Path) -> Option<String> {
    use sha2::Digest;
    let data = std::fs::read(p).ok()?;
    Some(format!("{:x}", sha2::Sha256::digest(&data)))
}

/// Copy the bundled shim to `~/.switchtether/bin` if missing or outdated. A
/// running copy is renamed out of the way first (allowed on Windows).
pub fn install_shim() -> Result<PathBuf, String> {
    let src = bundled_shim().ok_or_else(|| {
        "switchtether-shim was not found next to SwitchTether; reinstall SwitchTether".to_string()
    })?;
    let dst = installed_shim();
    std::fs::create_dir_all(bin_dir()).map_err(|e| e.to_string())?;
    if dst.is_file() && file_hash(&dst) == file_hash(&src) {
        return Ok(dst);
    }
    if dst.is_file() && std::fs::remove_file(&dst).is_err() {
        let old = dst.with_extension(format!("old-{}", chrono::Local::now().timestamp()));
        std::fs::rename(&dst, &old).map_err(|e| format!("cannot replace shim: {e}"))?;
    }
    std::fs::copy(&src, &dst).map_err(|e| format!("cannot install shim: {e}"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&dst, std::fs::Permissions::from_mode(0o755));
    }
    // Clean up renamed old copies that are no longer in use.
    if let Ok(rd) = std::fs::read_dir(bin_dir()) {
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().into_owned();
            if n.starts_with("switchtether-shim.old-") {
                let _ = std::fs::remove_file(e.path());
            }
        }
    }
    Ok(dst)
}

/// Refresh the installed shim at startup when any hook is on.
pub fn refresh_installed_shim() {
    let st = load_state();
    if !st.vscode_files.is_empty() || st.codex_env || st.cli {
        if let Err(e) = install_shim() {
            log::warn!("[switchtether] could not refresh shim: {e}");
        }
    }
}

// ---------------------------------------------------------------- VS Code

fn editor_settings_files() -> Vec<(String, PathBuf)> {
    let base = if cfg!(windows) {
        dirs::config_dir() // %APPDATA%
    } else if cfg!(target_os = "macos") {
        dirs::home_dir().map(|h| h.join("Library").join("Application Support"))
    } else {
        dirs::config_dir()
    };
    let Some(base) = base else { return vec![] };
    ["Code", "Code - Insiders", "Cursor", "Windsurf", "VSCodium"]
        .iter()
        .map(|n| {
            (
                n.to_string(),
                base.join(n).join("User").join("settings.json"),
            )
        })
        .filter(|(_, p)| p.parent().is_some_and(|d| d.is_dir()))
        .collect()
}

/// Any value of the wrapper key (detection).
fn key_regex() -> regex::Regex {
    regex::Regex::new(r#""claudeCode\.claudeProcessWrapper"\s*:\s*("(?:[^"\\]|\\.)*"|null)"#)
        .unwrap()
}

/// The exact line SwitchTether inserts, including its leading newline (removal).
fn own_line_regex() -> regex::Regex {
    regex::Regex::new(
        r#"\r?\n[ \t]*"claudeCode\.claudeProcessWrapper"[ \t]*:[ \t]*"(?:[^"\\]|\\.)*"[ \t]*,?"#,
    )
    .unwrap()
}

/// Current wrapper value in a settings.json text (None = not set).
fn wrapper_value(text: &str) -> Option<String> {
    let caps = key_regex().captures(text)?;
    serde_json::from_str::<Value>(caps.get(1)?.as_str())
        .ok()?
        .as_str()
        .map(String::from)
}

fn insert_wrapper(text: &str, shim: &str) -> Option<String> {
    let value = serde_json::to_string(shim).ok()?;
    let nl = if text.contains("\r\n") { "\r\n" } else { "\n" };
    let open = text.find('{')?;
    let rest = &text[open + 1..];
    let empty = rest.trim_start().starts_with('}');
    let line = if empty {
        format!("{nl}  \"{WRAPPER_KEY}\": {value}{nl}")
    } else {
        format!("{nl}  \"{WRAPPER_KEY}\": {value},")
    };
    let mut out = String::with_capacity(text.len() + line.len());
    out.push_str(&text[..open + 1]);
    out.push_str(&line);
    out.push_str(rest);
    Some(out)
}

fn remove_wrapper(text: &str) -> String {
    own_line_regex().replace(text, "").into_owned()
}

/// Add our wrapper to one settings file: `enabled`, `already` or `skipped`
/// (a different wrapper is configured: never replaced). Backs up first.
fn enable_in_file(path: &Path, shim: &str, backups: &Path) -> Result<&'static str, String> {
    let text = std::fs::read_to_string(path).unwrap_or_else(|_| "{}\n".into());
    match wrapper_value(&text) {
        Some(v) if v == shim => return Ok("already"),
        Some(_) => return Ok("skipped"),
        None if key_regex().is_match(&text) => return Ok("skipped"),
        None => {}
    }
    if path.is_file() {
        backup_to(path, backups, "settings")?;
    }
    let new_text = insert_wrapper(&text, shim)
        .ok_or_else(|| "settings.json is not a JSON object".to_string())?;
    std::fs::write(path, new_text).map_err(|e| e.to_string())?;
    Ok("enabled")
}

/// Remove our wrapper (only while it is still ours). Returns whether it changed.
fn disable_in_file(path: &Path, shim: &str, backups: &Path) -> Result<bool, String> {
    let Ok(text) = std::fs::read_to_string(path) else {
        return Ok(false);
    };
    if wrapper_value(&text).as_deref() != Some(shim) {
        return Ok(false);
    }
    backup_to(path, backups, "settings")?;
    std::fs::write(path, remove_wrapper(&text)).map_err(|e| e.to_string())?;
    Ok(true)
}

fn vscode_set(enable: bool, st: &mut TakeoverState) -> Result<Vec<Value>, String> {
    let backups = home().join("backups");
    let mut report = Vec::new();
    if enable {
        let shim_s = install_shim()?.to_string_lossy().into_owned();
        let files = editor_settings_files();
        if files.is_empty() {
            return Err("No VS Code-family editor settings were found on this computer.".into());
        }
        for (editor, path) in files {
            let result =
                enable_in_file(&path, &shim_s, &backups).map_err(|e| format!("{editor}: {e}"))?;
            let p = path.to_string_lossy().into_owned();
            if result != "skipped" && !st.vscode_files.contains(&p) {
                st.vscode_files.push(p);
            }
            report.push(json!({ "editor": editor, "result": result }));
        }
    } else {
        let shim_s = installed_shim().to_string_lossy().into_owned();
        for f in std::mem::take(&mut st.vscode_files) {
            if disable_in_file(Path::new(&f), &shim_s, &backups)? {
                report.push(json!({ "file": f, "result": "disabled" }));
            }
        }
    }
    Ok(report)
}

// ------------------------------------------------------- Windows user env

#[cfg(windows)]
mod winenv {
    use winreg::enums::{HKEY_CURRENT_USER, KEY_READ, KEY_WRITE};
    use winreg::RegKey;

    fn env() -> std::io::Result<RegKey> {
        RegKey::predef(HKEY_CURRENT_USER)
            .open_subkey_with_flags("Environment", KEY_READ | KEY_WRITE)
    }

    pub fn get(name: &str) -> Option<String> {
        env().ok()?.get_value::<String, _>(name).ok()
    }

    pub fn set(name: &str, value: &str) -> std::io::Result<()> {
        // Keep REG_EXPAND_SZ for Path so %VARS% in it keep working.
        let key = env()?;
        if name.eq_ignore_ascii_case("Path") {
            let v = winreg::RegValue {
                bytes: value
                    .encode_utf16()
                    .chain(std::iter::once(0))
                    .flat_map(|c| c.to_le_bytes())
                    .collect(),
                vtype: winreg::enums::RegType::REG_EXPAND_SZ,
            };
            key.set_raw_value(name, &v)?;
        } else {
            key.set_value(name, &value)?;
        }
        broadcast();
        Ok(())
    }

    pub fn delete(name: &str) -> std::io::Result<()> {
        env()?.delete_value(name)?;
        broadcast();
        Ok(())
    }

    /// Tell Explorer (and so newly started programs) that the environment changed.
    fn broadcast() {
        use windows_sys::Win32::UI::WindowsAndMessaging::{
            SendMessageTimeoutW, HWND_BROADCAST, SMTO_ABORTIFHUNG, WM_SETTINGCHANGE,
        };
        let s: Vec<u16> = "Environment\0".encode_utf16().collect();
        let mut res = 0usize;
        unsafe {
            SendMessageTimeoutW(
                HWND_BROADCAST,
                WM_SETTINGCHANGE,
                0,
                s.as_ptr() as isize,
                SMTO_ABORTIFHUNG,
                2000,
                &mut res,
            );
        }
    }
}

fn codex_desktop_installed() -> bool {
    #[cfg(windows)]
    {
        dirs::data_local_dir()
            .map(|d| d.join("Packages"))
            .and_then(|p| std::fs::read_dir(p).ok())
            .map(|rd| {
                rd.flatten()
                    .any(|e| e.file_name().to_string_lossy().starts_with("OpenAI.Codex_"))
            })
            .unwrap_or(false)
    }
    #[cfg(not(windows))]
    {
        false
    }
}

fn codex_set(enable: bool, st: &mut TakeoverState) -> Result<Vec<Value>, String> {
    #[cfg(windows)]
    {
        let shim_s = installed_shim().to_string_lossy().into_owned();
        if enable {
            let shim = install_shim()?.to_string_lossy().into_owned();
            match winenv::get("CODEX_CLI_PATH") {
                Some(v) if v == shim => {}
                Some(v) => {
                    return Err(format!(
                        "CODEX_CLI_PATH is already set to {v}; SwitchTether will not replace it."
                    ))
                }
                None => winenv::set("CODEX_CLI_PATH", &shim).map_err(|e| e.to_string())?,
            }
            st.codex_env = true;
            Ok(vec![
                json!({ "result": "enabled", "restartRequired": "Codex Desktop" }),
            ])
        } else {
            if winenv::get("CODEX_CLI_PATH").as_deref() == Some(shim_s.as_str()) {
                winenv::delete("CODEX_CLI_PATH").map_err(|e| e.to_string())?;
            }
            st.codex_env = false;
            Ok(vec![
                json!({ "result": "disabled", "restartRequired": "Codex Desktop" }),
            ])
        }
    }
    #[cfg(not(windows))]
    {
        let _ = (enable, st);
        Err("Codex Desktop takeover is only available on Windows in this version.".into())
    }
}

// ------------------------------------------------------------- launchers

fn launcher_paths() -> Vec<(PathBuf, String)> {
    let shim = installed_shim();
    let s = shim.to_string_lossy();
    ["claude", "codex", "agy"]
        .iter()
        .map(|agent| {
            if cfg!(windows) {
                (
                    bin_dir().join(format!("st-{agent}.cmd")),
                    format!("@echo off\r\n\"{s}\" tty {agent} %*\r\n"),
                )
            } else {
                (
                    bin_dir().join(format!("st-{agent}")),
                    format!("#!/bin/sh\nexec \"{s}\" tty {agent} \"$@\"\n"),
                )
            }
        })
        .collect()
}

fn cli_set(enable: bool, st: &mut TakeoverState) -> Result<Vec<Value>, String> {
    let dir = bin_dir().to_string_lossy().into_owned();
    if enable {
        install_shim()?;
        for (p, body) in launcher_paths() {
            std::fs::write(&p, body).map_err(|e| e.to_string())?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let _ = std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o755));
            }
        }
        #[cfg(windows)]
        {
            let path = winenv::get("Path").unwrap_or_default();
            let present = path
                .split(';')
                .any(|p| p.trim_end_matches('\\').eq_ignore_ascii_case(&dir));
            if !present {
                let new = if path.is_empty() {
                    dir.clone()
                } else {
                    format!("{};{dir}", path.trim_end_matches(';'))
                };
                winenv::set("Path", &new).map_err(|e| e.to_string())?;
                st.cli_path_added = true;
            }
        }
        st.cli = true;
        Ok(vec![json!({ "result": "enabled", "binDir": dir })])
    } else {
        for (p, _) in launcher_paths() {
            let _ = std::fs::remove_file(p);
        }
        #[cfg(windows)]
        if st.cli_path_added {
            if let Some(path) = winenv::get("Path") {
                let kept: Vec<&str> = path
                    .split(';')
                    .filter(|p| !p.trim_end_matches('\\').eq_ignore_ascii_case(&dir))
                    .collect();
                winenv::set("Path", &kept.join(";")).map_err(|e| e.to_string())?;
            }
        }
        st.cli = false;
        st.cli_path_added = false;
        Ok(vec![json!({ "result": "disabled" })])
    }
}

// ------------------------------------------------------------ DeepSeek Harness

const DSH_PLUGIN: &str = "switchtether-dsh-bridge";
const DSH_PROFILE: &str = "web";

fn dsh_plugin_dir() -> PathBuf {
    home().join("dsh-plugin")
}

fn dsh_profile_dir() -> PathBuf {
    switchtether_core::agents::dsh::dsh_home()
        .join("profiles")
        .join(DSH_PROFILE)
}

/// Writes the plugin package (SwitchTether's own directory); the attach file path is baked in.
fn write_dsh_plugin() -> Result<PathBuf, String> {
    let dir = dsh_plugin_dir();
    let lib = dir.join("lib");
    std::fs::create_dir_all(&lib).map_err(|e| e.to_string())?;
    let version = env!("CARGO_PKG_VERSION");
    let attach = crate::switchtether::remote::remote_dir().join("attach.json");
    let attach = serde_json::to_string(&attach.to_string_lossy()).unwrap();
    let js = include_str!("dsh_plugin/index.js")
        .replace("__ATTACH_FILE__", &attach)
        .replace("__VERSION__", &serde_json::to_string(version).unwrap());
    let pkg = include_str!("dsh_plugin/package.json").replace("__VERSION__", version);
    std::fs::write(lib.join("index.js"), js).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("package.json"), pkg).map_err(|e| e.to_string())?;
    std::fs::write(
        dir.join("cordis.patch.yml"),
        include_str!("dsh_plugin/cordis.patch.yml"),
    )
    .map_err(|e| e.to_string())?;
    Ok(dir)
}

/// Whether the profile manifest lists the bridge as a dependency and as an enabled bundle.
fn dsh_plugin_installed() -> bool {
    let Ok(text) = std::fs::read_to_string(dsh_profile_dir().join("package.json")) else {
        return false;
    };
    let Ok(v) = serde_json::from_str::<Value>(&text) else {
        return false;
    };
    let dep = v.pointer(&format!("/dependencies/{DSH_PLUGIN}")).is_some();
    let bundle = v
        .pointer("/dsh/profile/bundles")
        .and_then(Value::as_array)
        .is_some_and(|b| b.iter().any(|x| x.as_str() == Some(DSH_PLUGIN)));
    dep && bundle
}

fn run_dsh_plugin(bin: &Path, args: &[&str]) -> Result<String, String> {
    let mut cmd = std::process::Command::new(bin);
    if let Some(path) = switchtether_core::util::child_path() {
        cmd.env("PATH", path);
    }
    cmd.args(["plugin", "--profile", DSH_PROFILE])
        .args(args)
        .current_dir(dsh_profile_dir())
        .stdin(std::process::Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000);
    }
    let out = cmd
        .output()
        .map_err(|e| format!("could not run dsh: {e}"))?;
    let text = format!(
        "{}{}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr)
    );
    if out.status.success() {
        Ok(text)
    } else {
        Err(format!(
            "`dsh plugin --profile {DSH_PROFILE} {}` failed: {}",
            args.join(" "),
            text.trim().chars().take(2000).collect::<String>()
        ))
    }
}

/// Same JSON once empty `dependencies` / `devDependencies` objects are ignored.
fn manifest_equivalent(a: &Path, b: &Path) -> bool {
    let read = |p: &Path| -> Option<Value> {
        let mut v: Value = serde_json::from_str(&std::fs::read_to_string(p).ok()?).ok()?;
        if let Some(o) = v.as_object_mut() {
            for k in ["dependencies", "devDependencies"] {
                if o.get(k)
                    .and_then(Value::as_object)
                    .is_some_and(|m| m.is_empty())
                {
                    o.remove(k);
                }
            }
        }
        Some(v)
    };
    matches!((read(a), read(b)), (Some(x), Some(y)) if x == y)
}

fn dsh_set(enable: bool, st: &mut TakeoverState) -> Result<Vec<Value>, String> {
    let bin = switchtether_core::agents::dsh::managed::find_dsh_bin()
        .ok_or("DeepSeek Harness (`dsh`) was not found on this computer")?;
    let profile = dsh_profile_dir();
    if enable {
        if !profile.join("package.json").is_file() {
            return Err(
                "The DeepSeek Harness web profile is not initialised yet: run `dsh web` once, then try again"
                    .into(),
            );
        }
        if dsh_plugin_installed() {
            st.dsh = true;
            return Ok(vec![json!({ "result": "already" })]);
        }
        let backups = home().join("backups").join("dsh");
        let mut saved = Vec::new();
        for f in [
            "package.json",
            "cordis.patch.yml",
            "pnpm-lock.yaml",
            "compatibility.json",
        ] {
            let p = profile.join(f);
            if p.is_file() {
                let b = backup_to(&p, &backups, DSH_PROFILE)?;
                if f == "package.json" {
                    st.dsh_manifest_backup = Some(b.to_string_lossy().into_owned());
                }
                saved.push(b.to_string_lossy().into_owned());
            }
        }
        let dir = write_dsh_plugin()?;
        let spec = format!("link:{}", dir.to_string_lossy().replace('\\', "/"));
        let log = run_dsh_plugin(&bin, &["add", &spec])?;
        if !dsh_plugin_installed() {
            return Err(format!(
                "dsh did not enable the SwitchTether bundle: {}",
                log.trim().chars().take(2000).collect::<String>()
            ));
        }
        st.dsh = true;
        Ok(vec![
            json!({ "result": "enabled", "profile": DSH_PROFILE, "backups": saved,
            "note": "Restart DeepSeek Harness web (`dsh web`) to load the bridge." }),
        ])
    } else {
        if dsh_plugin_installed() || st.dsh {
            if let Err(e) = run_dsh_plugin(&bin, &["remove", DSH_PLUGIN]) {
                if dsh_plugin_installed() {
                    return Err(e);
                }
            }
        }
        if dsh_plugin_installed() {
            return Err(
                "dsh still lists the SwitchTether bundle; remove it in dsh's plugin page".into(),
            );
        }
        // pnpm drops an emptied `dependencies` object: when nothing else differs from the backup,
        // put the original bytes back so the manifest is exactly as before.
        let manifest = profile.join("package.json");
        let restored = st
            .dsh_manifest_backup
            .as_deref()
            .map(PathBuf::from)
            .filter(|b| manifest_equivalent(b, &manifest))
            .map(|b| std::fs::copy(&b, &manifest).is_ok())
            .unwrap_or(false);
        st.dsh = false;
        st.dsh_manifest_backup = None;
        Ok(vec![
            json!({ "result": "disabled", "manifestRestored": restored,
            "note": "Restart DeepSeek Harness web to unload the bridge." }),
        ])
    }
}

// ------------------------------------------------------------------ API

pub fn status() -> Value {
    let st = load_state();
    let shim_s = installed_shim().to_string_lossy().into_owned();
    let editors: Vec<Value> = editor_settings_files()
        .into_iter()
        .map(|(editor, path)| {
            let v = std::fs::read_to_string(&path)
                .ok()
                .and_then(|t| wrapper_value(&t));
            json!({
                "editor": editor,
                "enabled": v.as_deref() == Some(shim_s.as_str()),
                "foreignWrapper": v.filter(|v| *v != shim_s),
            })
        })
        .collect();
    #[cfg(windows)]
    let codex_env = winenv::get("CODEX_CLI_PATH");
    #[cfg(not(windows))]
    let codex_env: Option<String> = None;
    if st.cli && installed_shim().is_file() {
        let paths = launcher_paths();
        if paths.iter().any(|(p, _)| p.is_file()) && !paths.iter().all(|(p, _)| p.is_file()) {
            for (p, body) in &paths {
                if !p.is_file() {
                    let _ = std::fs::write(p, body);
                    #[cfg(unix)]
                    {
                        use std::os::unix::fs::PermissionsExt;
                        let _ = std::fs::set_permissions(p, std::fs::Permissions::from_mode(0o755));
                    }
                }
            }
        }
    }
    let cli_enabled = st.cli && launcher_paths().iter().all(|(p, _)| p.is_file());
    json!({
        "shimAvailable": bundled_shim().is_some(),
        "vscode": {
            "available": !editors.is_empty(),
            "enabled": editors.iter().any(|e| e["enabled"] == true),
            "editors": editors,
        },
        "codexDesktop": {
            "available": cfg!(windows) && codex_desktop_installed(),
            "enabled": codex_env.as_deref() == Some(shim_s.as_str()),
            "foreignValue": codex_env.filter(|v| *v != shim_s),
        },
        "dsh": {
            "available": switchtether_core::agents::dsh::managed::find_dsh_bin().is_some(),
            "profileReady": dsh_profile_dir().join("package.json").is_file(),
            "enabled": dsh_plugin_installed(),
            "profile": DSH_PROFILE,
        },
        "cli": {
            "available": true,
            "enabled": cli_enabled,
            "binDir": bin_dir().to_string_lossy(),
            "pathAdded": st.cli_path_added,
        },
    })
}

/// Serialises changes to the hooks (the first-run defaults run in the background
/// while the user may already be flipping switches).
static SET_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

pub fn set(hook: &str, enabled: bool) -> Result<Value, String> {
    let _guard = SET_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let mut st = load_state();
    let res = match hook {
        "vscode" => vscode_set(enabled, &mut st),
        "codex_desktop" => codex_set(enabled, &mut st),
        "cli" => cli_set(enabled, &mut st),
        "dsh" => dsh_set(enabled, &mut st),
        other => Err(format!("unknown hook '{other}'")),
    };
    save_state(&st)?;
    let report = res?;
    log::info!("[switchtether] takeover hook {hook} -> {enabled}");
    Ok(json!({ "report": report, "status": status() }))
}

// ------------------------------------------------------ first-run defaults

/// Written once when the first-run defaults have been applied; while it exists
/// they never run again, so a switch the user turns off stays off.
fn defaults_marker() -> PathBuf {
    home().join("remote").join("takeover-defaults.json")
}

/// Hooks the first-run default turns on, from [`status`]: only targets that are
/// present and not already configured by the user (a foreign wrapper or
/// `CODEX_CLI_PATH` value is left alone) and not already on.
fn default_hooks(status: &Value) -> Vec<&'static str> {
    let mut hooks = Vec::new();
    if status["shimAvailable"] != true {
        return hooks;
    }
    let vscode = &status["vscode"];
    let free_editor = vscode["editors"].as_array().is_some_and(|eds| {
        eds.iter()
            .any(|e| e["enabled"] != true && e["foreignWrapper"].is_null())
    });
    if vscode["available"] == true && free_editor {
        hooks.push("vscode");
    }
    let codex = &status["codexDesktop"];
    if codex["available"] == true && codex["enabled"] != true && codex["foreignValue"].is_null() {
        hooks.push("codex_desktop");
    }
    let cli = &status["cli"];
    if cli["available"] == true && cli["enabled"] != true {
        hooks.push("cli");
    }
    let dsh = &status["dsh"];
    if dsh["available"] == true && dsh["profileReady"] == true && dsh["enabled"] != true {
        hooks.push("dsh");
    }
    hooks
}

/// One-time default: turn the takeover hooks on through the same code paths as
/// the switches (backups first, values the user set are never replaced). Runs
/// once per install, for new and existing installs alike, unless the user has
/// already used the switches (`takeover.json` exists: their choice is kept).
/// Unavailable or foreign targets are skipped. Never runs under the test home
/// override, whose editor settings and user environment would be the real ones.
pub fn apply_first_run_defaults() {
    if std::env::var_os("CC_SWITCH_TEST_HOME").is_some_and(|v| !v.is_empty()) {
        log::info!("[switchtether] takeover defaults skipped under CC_SWITCH_TEST_HOME");
        return;
    }
    let marker = defaults_marker();
    if marker.exists() {
        return;
    }
    let st = status();
    if st["shimAvailable"] != true {
        // Broken install (or a development build without the shim): try again next launch.
        log::warn!("[switchtether] takeover defaults postponed: switchtether-shim not bundled");
        return;
    }
    let user_chose = state_file().exists();
    let write_marker = |results: Value| -> Result<(), String> {
        if let Some(d) = marker.parent() {
            std::fs::create_dir_all(d).map_err(|e| e.to_string())?;
        }
        let body = json!({
            "appliedAt": chrono::Utc::now().to_rfc3339(),
            "keptUserChoice": user_chose,
            "results": results,
        });
        std::fs::write(&marker, serde_json::to_vec_pretty(&body).unwrap())
            .map_err(|e| e.to_string())
    };
    // The marker goes first: whatever happens below, this never runs twice.
    if let Err(e) = write_marker(Value::Null) {
        log::warn!("[switchtether] takeover defaults not applied (marker: {e})");
        return;
    }
    if user_chose {
        log::info!("[switchtether] takeover defaults: keeping the user's existing switches");
        return;
    }
    let mut results = serde_json::Map::new();
    for hook in default_hooks(&st) {
        let r = match set(hook, true) {
            Ok(v) => json!({ "result": "enabled", "report": v["report"] }),
            Err(e) => json!({ "result": "skipped", "error": e }),
        };
        log::info!("[switchtether] takeover default {hook}: {r}");
        results.insert(hook.into(), r);
    }
    let _ = write_marker(Value::Object(results));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_run_defaults_skip_missing_foreign_and_enabled_targets() {
        let none =
            json!({ "shimAvailable": false, "cli": { "available": true, "enabled": false } });
        assert!(
            default_hooks(&none).is_empty(),
            "no shim: nothing is turned on"
        );

        let st = json!({
            "shimAvailable": true,
            "vscode": { "available": true, "enabled": false, "editors": [
                { "editor": "Code", "enabled": false, "foreignWrapper": "/usr/bin/mine" },
            ]},
            "codexDesktop": { "available": true, "enabled": false, "foreignValue": "C:/x/codex.exe" },
            "dsh": { "available": true, "profileReady": false, "enabled": false },
            "cli": { "available": true, "enabled": false },
        });
        assert_eq!(default_hooks(&st), vec!["cli"]);

        let st = json!({
            "shimAvailable": true,
            "vscode": { "available": true, "enabled": false, "editors": [
                { "editor": "Code", "enabled": false, "foreignWrapper": "/usr/bin/mine" },
                { "editor": "Cursor", "enabled": false, "foreignWrapper": null },
            ]},
            "codexDesktop": { "available": true, "enabled": false, "foreignValue": null },
            "dsh": { "available": true, "profileReady": true, "enabled": true },
            "cli": { "available": true, "enabled": true },
        });
        assert_eq!(default_hooks(&st), vec!["vscode", "codex_desktop"]);

        let st = json!({
            "shimAvailable": true,
            "vscode": { "available": false, "enabled": false, "editors": [] },
            "codexDesktop": { "available": false, "enabled": false, "foreignValue": null },
            "dsh": { "available": false, "profileReady": false, "enabled": false },
            "cli": { "available": true, "enabled": false },
        });
        assert_eq!(default_hooks(&st), vec!["cli"]);
    }

    #[test]
    fn dsh_manifest_equivalence_ignores_only_empty_dependency_objects() {
        let tmp = tempfile::tempdir().unwrap();
        let a = tmp.path().join("a.json");
        let b = tmp.path().join("b.json");
        std::fs::write(
            &a,
            r#"{"name":"p","dependencies":{},"dsh":{"profile":{"bundles":["x"]}}}"#,
        )
        .unwrap();
        std::fs::write(
            &b,
            "{
  \"name\": \"p\",
  \"dsh\": {\"profile\": {\"bundles\": [\"x\"]}}
}
",
        )
        .unwrap();
        assert!(manifest_equivalent(&a, &b));
        std::fs::write(
            &b,
            r#"{"name":"p","dsh":{"profile":{"bundles":["x","y"]}}}"#,
        )
        .unwrap();
        assert!(
            !manifest_equivalent(&a, &b),
            "a bundle the user added is kept"
        );
        std::fs::write(
            &b,
            r#"{"name":"p","dependencies":{"z":"1"},"dsh":{"profile":{"bundles":["x"]}}}"#,
        )
        .unwrap();
        assert!(!manifest_equivalent(&a, &b));
    }

    #[test]
    fn wrapper_key_insert_and_remove_preserve_the_rest() {
        let original = "{\n  // my settings\n  \"editor.fontSize\": 14,\n}\n";
        let with = insert_wrapper(original, "C:\\x\\shim.exe").unwrap();
        assert_eq!(wrapper_value(&with).as_deref(), Some("C:\\x\\shim.exe"));
        assert!(with.contains("// my settings"));
        assert_eq!(remove_wrapper(&with), original);

        let empty = insert_wrapper("{}", "/s").unwrap();
        assert!(serde_json::from_str::<Value>(&empty).is_ok());
        assert_eq!(wrapper_value(&empty).as_deref(), Some("/s"));
        assert_eq!(wrapper_value("{\"a\":1}"), None);
    }

    #[test]
    fn settings_file_round_trip_is_exact_and_foreign_wrappers_are_kept() {
        let tmp = tempfile::tempdir().unwrap();
        let backups = tmp.path().join("backups");
        let f = tmp.path().join("settings.json");
        let original = "{\r\n  // comment\r\n  \"a\": [1, 2],\r\n  \"b\": true\r\n}\r\n";
        std::fs::write(&f, original).unwrap();
        let shim = r"C:\st\shim.exe";
        assert_eq!(enable_in_file(&f, shim, &backups).unwrap(), "enabled");
        assert_eq!(enable_in_file(&f, shim, &backups).unwrap(), "already");
        let with = std::fs::read_to_string(&f).unwrap();
        assert_eq!(wrapper_value(&with).as_deref(), Some(shim));
        assert_eq!(std::fs::read_dir(&backups).unwrap().count(), 1);
        assert!(disable_in_file(&f, shim, &backups).unwrap());
        assert_eq!(std::fs::read_to_string(&f).unwrap(), original);

        // A wrapper the user configured is neither replaced nor removed.
        let foreign = "{ \"claudeCode.claudeProcessWrapper\": \"/usr/bin/mine\", \"x\": 1 }\n";
        std::fs::write(&f, foreign).unwrap();
        assert_eq!(enable_in_file(&f, "/st/shim", &backups).unwrap(), "skipped");
        assert!(!disable_in_file(&f, "/st/shim", &backups).unwrap());
        assert_eq!(std::fs::read_to_string(&f).unwrap(), foreign);

        // Missing file: created with only our key, valid JSON.
        let g = tmp.path().join("new.json");
        assert_eq!(enable_in_file(&g, "/st/shim", &backups).unwrap(), "enabled");
        assert!(serde_json::from_str::<Value>(&std::fs::read_to_string(&g).unwrap()).is_ok());
    }
}
