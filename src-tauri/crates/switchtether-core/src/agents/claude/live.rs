//! Discovery of running Claude Code sessions (read-only).
//!
//! Primary source: `claude agents --json` — documented as "the supported way to
//! read session state from outside Claude Code". Fallback: the per-process
//! registry files `~/.claude/sessions/<pid>.json` (internal format), with a
//! liveness check of the pid. Neither source allows control; see
//! docs/AGENT_COMPATIBILITY.md.

use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::Deserialize;
use serde_json::Value;

use crate::agents::model::Origin;

#[derive(Debug, Clone, PartialEq)]
pub struct LiveClaudeSession {
    pub pid: Option<u32>,
    pub session_id: String,
    pub cwd: Option<String>,
    pub started_at_ms: Option<i64>,
    pub kind: Option<String>,
    pub entrypoint: Option<String>,
    pub status: Option<String>,
    pub waiting_for: Option<String>,
    pub name: Option<String>,
}

impl LiveClaudeSession {
    pub fn origin(&self) -> Origin {
        match self.entrypoint.as_deref() {
            Some("cli") => Origin::Cli,
            Some(e) if e.contains("vscode") => Origin::Vscode,
            Some(e) if e.starts_with("sdk") => Origin::Unknown,
            Some("claude-desktop") => Origin::DesktopApp,
            _ => Origin::Unknown,
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawEntry {
    pid: Option<u32>,
    session_id: Option<String>,
    cwd: Option<String>,
    started_at: Option<Value>,
    kind: Option<String>,
    entrypoint: Option<String>,
    status: Option<String>,
    waiting_for: Option<String>,
    name: Option<String>,
}

fn ts_ms(v: &Option<Value>) -> Option<i64> {
    match v {
        Some(Value::Number(n)) => n.as_i64(),
        Some(Value::String(s)) => chrono::DateTime::parse_from_rfc3339(s)
            .ok()
            .map(|d| d.timestamp_millis()),
        _ => None,
    }
}

fn from_raw(r: RawEntry) -> Option<LiveClaudeSession> {
    Some(LiveClaudeSession {
        session_id: r.session_id?,
        pid: r.pid,
        cwd: r.cwd,
        started_at_ms: ts_ms(&r.started_at),
        kind: r.kind,
        entrypoint: r.entrypoint,
        status: r.status,
        waiting_for: r.waiting_for,
        name: r.name,
    })
}

pub fn parse_agents_json(raw: &str) -> Option<Vec<LiveClaudeSession>> {
    let entries: Vec<RawEntry> = serde_json::from_str(raw.trim()).ok()?;
    Some(entries.into_iter().filter_map(from_raw).collect())
}

/// `claude agents --json` (bounded to a few seconds).
pub async fn agents_json(claude_bin: &Path) -> Option<Vec<LiveClaudeSession>> {
    let mut cmd = tokio::process::Command::new(claude_bin);
    cmd.args(["agents", "--json"])
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true);
    #[cfg(windows)]
    {
        cmd.creation_flags(0x0800_0000);
    }
    let out = tokio::time::timeout(Duration::from_secs(8), cmd.output())
        .await
        .ok()?
        .ok()?;
    if !out.status.success() {
        return None;
    }
    parse_agents_json(&String::from_utf8_lossy(&out.stdout))
}

/// Fallback: `~/.claude/sessions/<pid>.json` registry files.
pub fn read_registry(claude_dir: &Path) -> Vec<LiveClaudeSession> {
    let dir = claude_dir.join("sessions");
    let Ok(rd) = std::fs::read_dir(&dir) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for e in rd.flatten() {
        let p = e.path();
        let name = p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        // `<pid>.json` only; `<pid>.<hash>.key` files hold secrets and are never read.
        if !name.ends_with(".json") || name.matches('.').count() != 1 {
            continue;
        }
        let Ok(text) = std::fs::read_to_string(&p) else { continue };
        let Ok(raw) = serde_json::from_str::<RawEntry>(&text) else { continue };
        if let Some(s) = from_raw(raw) {
            if s.pid.is_none_or(pid_alive) {
                out.push(s);
            }
        }
    }
    out
}

#[cfg(unix)]
pub fn pid_alive(pid: u32) -> bool {
    // Signal 0 checks existence without sending anything.
    let r = unsafe { libc::kill(pid as libc::pid_t, 0) };
    r == 0 || std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
}

#[cfg(windows)]
pub fn pid_alive(pid: u32) -> bool {
    use windows_sys::Win32::Foundation::{CloseHandle, STILL_ACTIVE};
    use windows_sys::Win32::System::Threading::{
        GetExitCodeProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION,
    };
    unsafe {
        let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if h.is_null() {
            return false;
        }
        let mut code: u32 = 0;
        let ok = GetExitCodeProcess(h, &mut code) != 0;
        CloseHandle(h);
        ok && code == STILL_ACTIVE as u32
    }
}

/// `~/.claude/projects/<encoded cwd>/<session>.jsonl`, falling back to a scan.
pub fn transcript_path(claude_dir: &Path, cwd: Option<&str>, session_id: &str) -> Option<PathBuf> {
    let projects = claude_dir.join("projects");
    let file = format!("{session_id}.jsonl");
    if let Some(cwd) = cwd {
        let enc: String = cwd
            .chars()
            .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
            .collect();
        let p = projects.join(&enc).join(&file);
        if p.is_file() {
            return Some(p);
        }
    }
    let rd = std::fs::read_dir(&projects).ok()?;
    for e in rd.flatten() {
        let p = e.path().join(&file);
        if p.is_file() {
            return Some(p);
        }
    }
    None
}

pub fn claude_dir() -> PathBuf {
    if let Some(d) = std::env::var_os("CLAUDE_CONFIG_DIR") {
        return PathBuf::from(d);
    }
    dirs::home_dir().unwrap_or_default().join(".claude")
}

/// Locate the `claude` executable: PATH, then common install locations.
pub fn find_claude_bin() -> Option<PathBuf> {
    if let Some(p) = crate::util::which("claude") {
        return Some(p);
    }
    let home = dirs::home_dir()?;
    let candidates = if cfg!(windows) {
        vec![
            home.join(".local").join("bin").join("claude.exe"),
            home.join("AppData").join("Roaming").join("npm").join("claude.cmd"),
        ]
    } else {
        vec![
            home.join(".local").join("bin").join("claude"),
            home.join(".claude").join("local").join("claude"),
            PathBuf::from("/opt/homebrew/bin/claude"),
            PathBuf::from("/usr/local/bin/claude"),
        ]
    };
    candidates.into_iter().find(|p| p.is_file())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_agents_output() {
        let raw = r#"[{"pid":1,"cwd":"/p","kind":"interactive","startedAt":1700000000000,"sessionId":"s1","name":"x","status":"waiting","waitingFor":"permission prompt","entrypoint":"cli"},{"pid":2}]"#;
        let v = parse_agents_json(raw).unwrap();
        assert_eq!(v.len(), 1);
        assert_eq!(v[0].waiting_for.as_deref(), Some("permission prompt"));
        assert_eq!(v[0].origin(), Origin::Cli);
        assert!(parse_agents_json("not json").is_none());
    }

    #[test]
    fn registry_skips_key_files_and_dead_pids() {
        let tmp = tempfile::tempdir().unwrap();
        let s = tmp.path().join("sessions");
        std::fs::create_dir_all(&s).unwrap();
        let me = std::process::id();
        std::fs::write(s.join(format!("{me}.json")), format!(r#"{{"pid":{me},"sessionId":"live","entrypoint":"claude-vscode","status":"busy"}}"#)).unwrap();
        std::fs::write(s.join("999999.json"), r#"{"pid":4294967000,"sessionId":"dead"}"#).unwrap();
        std::fs::write(s.join(format!("{me}.abcdef.key")), r#"{"peerToken":"secret"}"#).unwrap();
        let v = read_registry(tmp.path());
        assert_eq!(v.len(), 1);
        assert_eq!(v[0].session_id, "live");
        assert_eq!(v[0].origin(), Origin::Vscode);
    }

    #[test]
    fn finds_transcript() {
        let tmp = tempfile::tempdir().unwrap();
        let proj = tmp.path().join("projects").join("d--develop-x");
        std::fs::create_dir_all(&proj).unwrap();
        std::fs::write(proj.join("abc.jsonl"), "").unwrap();
        assert!(transcript_path(tmp.path(), Some("d:\\develop\\x"), "abc").is_some());
        assert!(transcript_path(tmp.path(), Some("/other"), "abc").is_some());
        assert!(transcript_path(tmp.path(), None, "nope").is_none());
    }
}
