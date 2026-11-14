//! Desktop-managed terminals.
//!
//! The desktop owns the real PTY (ConPTY on Windows, Unix PTY elsewhere, via
//! `portable-pty`) and runs a VT state machine (`vt100`) over its output. Phones
//! receive *screen state* (snapshot + changed rows), mosh-style, so a dropped
//! mobile connection only needs the latest snapshot on reconnect and memory is
//! bounded by the scrollback size, not by output volume.
//!
//! Terminals keep running when the phone disconnects. They end when the shell
//! exits, when killed from the phone, when the owning device is revoked, or
//! when SwitchTether really quits (the quit flow warns about this first).
//!
//! Security note: a remote shell has the full privileges of the logged-in
//! desktop user. Access requires a fresh biometric step-up grant (scope
//! `terminal`); no command filtering is attempted because string filtering
//! cannot meaningfully restrict a shell.

mod screen;

use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use parking_lot::{Mutex, RwLock};
use portable_pty::{native_pty_system, ChildKiller, CommandBuilder, MasterPty, PtySize};
use serde::{Deserialize, Serialize};
use serde_json::json;
use tokio::sync::watch;

use screen::{new_parser, nz, size_of, VtParser};
pub use screen::{Cursor, Line, Run, ScreenSnapshot};

use crate::events::EventBus;

pub const MAX_TERMINALS: usize = 12;
pub const SCROLLBACK_LINES: usize = 5000;
pub const DEFAULT_LOG_LIMIT_BYTES: u64 = 10 * 1024 * 1024;
pub const LOG_RETENTION_DAYS: i64 = 7;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ShellInfo {
    pub id: String,
    pub name: String,
    pub path: String,
}

use crate::util::which;

/// Shells available on this desktop, default first.
pub fn available_shells() -> Vec<ShellInfo> {
    let mut out = Vec::new();
    let mut push = |id: &str, name: &str, path: PathBuf| {
        if !out.iter().any(|s: &ShellInfo| s.id == id) {
            out.push(ShellInfo {
                id: id.into(),
                name: name.into(),
                path: path.to_string_lossy().into_owned(),
            });
        }
    };
    if cfg!(windows) {
        if let Some(p) = which("pwsh") {
            push("pwsh", "PowerShell 7", p);
        }
        if let Some(p) = which("powershell") {
            push("powershell", "Windows PowerShell", p);
        }
        let cmd = std::env::var_os("ComSpec")
            .map(PathBuf::from)
            .or_else(|| which("cmd"));
        if let Some(p) = cmd {
            push("cmd", "Command Prompt", p);
        }
        if let Some(p) = which("wsl") {
            push("wsl", "WSL", p);
        }
    } else {
        if let Some(sh) = std::env::var_os("SHELL").map(PathBuf::from) {
            if sh.is_file() {
                let name = sh
                    .file_name()
                    .map(|n| n.to_string_lossy().into_owned())
                    .unwrap_or_else(|| "shell".into());
                push(&name.clone(), &name, sh);
            }
        }
        for (id, p) in [
            ("zsh", "/bin/zsh"),
            ("bash", "/bin/bash"),
            ("fish", "/usr/bin/fish"),
            ("sh", "/bin/sh"),
        ] {
            let pb = PathBuf::from(p);
            if pb.is_file() {
                push(id, id, pb);
            }
        }
    }
    out
}

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct CreateTerminal {
    pub shell: Option<String>,
    pub cwd: Option<String>,
    pub cols: Option<u16>,
    pub rows: Option<u16>,
    #[serde(default)]
    pub persist_log: bool,
    pub title: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TerminalInfo {
    pub id: String,
    pub shell: String,
    pub cwd: String,
    pub title: String,
    pub cols: u16,
    pub rows: u16,
    pub created_at_ms: i64,
    pub owner_device_id: Option<String>,
    pub exited: bool,
    pub exit_code: Option<i32>,
    pub persist_log: bool,
    pub version: u64,
}

struct TermState {
    parser: VtParser,
    exited: bool,
    exit_code: Option<i32>,
    custom_title: Option<String>,
}

pub struct Terminal {
    pub id: String,
    pub shell: String,
    pub cwd: String,
    pub created_at_ms: i64,
    pub owner_device_id: Option<String>,
    persist_log: bool,
    state: Mutex<TermState>,
    writer: Mutex<Option<Box<dyn Write + Send>>>,
    /// Dropped when the shell exits: on Windows this closes the ConPTY, which
    /// is what finally unblocks the output reader.
    master: Mutex<Option<Box<dyn MasterPty + Send>>>,
    killer: Mutex<Box<dyn ChildKiller + Send + Sync>>,
    version_tx: watch::Sender<u64>,
}

impl Terminal {
    pub fn info(&self) -> TerminalInfo {
        let st = self.state.lock();
        let (rows, cols) = size_of(st.parser.screen());
        let title = st
            .custom_title
            .clone()
            .filter(|t| !t.is_empty())
            .unwrap_or_else(|| {
                let t = &st.parser.callbacks().title;
                if t.is_empty() {
                    self.shell.clone()
                } else {
                    t.clone()
                }
            });
        TerminalInfo {
            id: self.id.clone(),
            shell: self.shell.clone(),
            cwd: self.cwd.clone(),
            title,
            cols,
            rows,
            created_at_ms: self.created_at_ms,
            owner_device_id: self.owner_device_id.clone(),
            exited: st.exited,
            exit_code: st.exit_code,
            persist_log: self.persist_log,
            version: *self.version_tx.borrow(),
        }
    }

    pub fn subscribe(&self) -> watch::Receiver<u64> {
        self.version_tx.subscribe()
    }

    pub fn snapshot(&self) -> ScreenSnapshot {
        let st = self.state.lock();
        screen::snapshot(
            &st.parser,
            *self.version_tx.borrow(),
            st.exited,
            st.exit_code,
        )
    }

    /// Lines from the scrollback, `offset` lines above the top of the screen.
    pub fn scrollback(&self, offset: usize, limit: usize) -> (Vec<Line>, usize) {
        let mut st = self.state.lock();
        screen::scrollback(&mut st.parser, offset, limit)
    }

    pub fn write_input(&self, data: &[u8]) -> std::io::Result<()> {
        if self.state.lock().exited {
            return Err(std::io::Error::new(
                std::io::ErrorKind::BrokenPipe,
                "terminal exited",
            ));
        }
        let mut w = self.writer.lock();
        let w = w.as_mut().ok_or_else(|| {
            std::io::Error::new(std::io::ErrorKind::BrokenPipe, "terminal exited")
        })?;
        w.write_all(data)?;
        w.flush()
    }

    pub fn resize(&self, cols: u16, rows: u16) -> anyhow::Result<()> {
        let cols = cols.clamp(10, 500);
        let rows = rows.clamp(3, 300);
        if let Some(m) = self.master.lock().as_ref() {
            m.resize(PtySize {
                rows,
                cols,
                pixel_width: 0,
                pixel_height: 0,
            })?;
        }
        self.state.lock().parser.set_size(nz(rows), nz(cols));
        self.bump();
        Ok(())
    }

    pub fn kill(&self) {
        let _ = self.killer.lock().kill();
    }

    fn bump(&self) {
        self.version_tx.send_modify(|v| *v += 1);
    }
}

pub struct TerminalManager {
    terms: RwLock<HashMap<String, Arc<Terminal>>>,
    events: Arc<EventBus>,
    log_dir: Option<PathBuf>,
}

impl TerminalManager {
    pub fn new(events: Arc<EventBus>, log_dir: Option<PathBuf>) -> Arc<Self> {
        if let Some(dir) = &log_dir {
            cleanup_logs(dir, LOG_RETENTION_DAYS);
        }
        Arc::new(Self {
            terms: RwLock::new(HashMap::new()),
            events,
            log_dir,
        })
    }

    pub fn count(&self) -> usize {
        self.terms
            .read()
            .values()
            .filter(|t| !t.state.lock().exited)
            .count()
    }

    pub fn list(&self) -> Vec<TerminalInfo> {
        let mut v: Vec<_> = self.terms.read().values().map(|t| t.info()).collect();
        v.sort_by_key(|t| t.created_at_ms);
        v
    }

    pub fn get(&self, id: &str) -> Option<Arc<Terminal>> {
        self.terms.read().get(id).cloned()
    }

    pub fn create(
        self: &Arc<Self>,
        req: CreateTerminal,
        owner_device_id: Option<String>,
    ) -> anyhow::Result<Arc<Terminal>> {
        {
            let mut terms = self.terms.write();
            // Drop exited terminals from the table before enforcing the limit.
            terms.retain(|_, t| !t.state.lock().exited);
            if terms.len() >= MAX_TERMINALS {
                anyhow::bail!("too many terminals (max {MAX_TERMINALS})");
            }
        }
        let shells = available_shells();
        let shell = match req.shell.as_deref() {
            None | Some("") => shells
                .first()
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("no shell available"))?,
            Some(id) => shells
                .iter()
                .find(|s| s.id == id)
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("unknown shell '{id}'"))?,
        };
        let cwd = match req.cwd.as_deref().filter(|c| !c.trim().is_empty()) {
            Some(c) => {
                let p = PathBuf::from(c);
                if !p.is_dir() {
                    anyhow::bail!("working directory does not exist");
                }
                p
            }
            None => dirs::home_dir().unwrap_or_else(|| PathBuf::from(".")),
        };
        let cols = req.cols.unwrap_or(80).clamp(10, 500);
        let rows = req.rows.unwrap_or(24).clamp(3, 300);

        let pty = native_pty_system();
        let pair = pty.openpty(PtySize {
            rows,
            cols,
            pixel_width: 0,
            pixel_height: 0,
        })?;
        let mut cmd = CommandBuilder::new(&shell.path);
        if shell.id == "pwsh" || shell.id == "powershell" {
            cmd.arg("-NoLogo");
        }
        cmd.cwd(&cwd);
        cmd.env("TERM", "xterm-256color");
        cmd.env("COLORTERM", "truecolor");
        cmd.env("SWITCHTETHER_TERMINAL", "1");
        if !cfg!(windows) && std::env::var_os("LANG").is_none() {
            cmd.env("LANG", "en_US.UTF-8");
        }
        let mut child = pair.slave.spawn_command(cmd)?;
        let killer = child.clone_killer();
        drop(pair.slave);
        let reader = pair.master.try_clone_reader()?;
        let writer = pair.master.take_writer()?;

        let id = crate::util::new_id();
        let (version_tx, _) = watch::channel(0u64);
        let term = Arc::new(Terminal {
            id: id.clone(),
            shell: shell.id.clone(),
            cwd: cwd.to_string_lossy().into_owned(),
            created_at_ms: crate::util::now_ms(),
            owner_device_id,
            persist_log: req.persist_log && self.log_dir.is_some(),
            state: Mutex::new(TermState {
                parser: new_parser(rows, cols, SCROLLBACK_LINES),
                exited: false,
                exit_code: None,
                custom_title: req.title.map(|t| t.chars().take(80).collect()),
            }),
            writer: Mutex::new(Some(writer)),
            master: Mutex::new(Some(pair.master)),
            killer: Mutex::new(killer),
            version_tx,
        });
        self.terms.write().insert(id.clone(), term.clone());

        let log = if term.persist_log {
            self.log_dir
                .as_ref()
                .and_then(|d| TermLog::open(d, &id, DEFAULT_LOG_LIMIT_BYTES).ok())
        } else {
            None
        };
        let mgr = Arc::downgrade(self);
        let t2 = term.clone();
        std::thread::Builder::new()
            .name(format!("term-{}", &id[..8]))
            .spawn(move || reader_loop(t2, reader, log))?;
        let t3 = term.clone();
        std::thread::Builder::new()
            .name(format!("term-wait-{}", &id[..8]))
            .spawn(move || {
                let code = child.wait().ok().map(|s| s.exit_code() as i32);
                // Give the reader a moment to drain the final output.
                std::thread::sleep(std::time::Duration::from_millis(150));
                {
                    let mut st = t3.state.lock();
                    st.exited = true;
                    st.exit_code = code;
                }
                t3.writer.lock().take();
                t3.master.lock().take();
                t3.bump();
                if let Some(m) = mgr.upgrade() {
                    m.events
                        .publish("terminal.exited", json!({ "id": t3.id, "exitCode": code }));
                }
            })?;

        self.events.publish("terminal.created", json!(term.info()));
        Ok(term)
    }

    pub fn kill(&self, id: &str) -> bool {
        match self.get(id) {
            Some(t) => {
                t.kill();
                true
            }
            None => false,
        }
    }

    /// Remove an exited terminal from the table.
    pub fn remove(&self, id: &str) -> bool {
        let mut terms = self.terms.write();
        match terms.get(id) {
            Some(t) if t.state.lock().exited => {
                terms.remove(id);
                true
            }
            Some(t) => {
                t.kill();
                terms.remove(id);
                true
            }
            None => false,
        }
    }

    /// Kill terminals created by a device (called on revocation).
    pub fn release_device(&self, device_id: &str) {
        for t in self.terms.read().values() {
            if t.owner_device_id.as_deref() == Some(device_id) {
                t.kill();
            }
        }
    }

    /// Kill every terminal (used when SwitchTether really quits).
    pub fn kill_all(&self) -> usize {
        let terms = self.terms.read();
        let mut n = 0;
        for t in terms.values() {
            if !t.state.lock().exited {
                t.kill();
                n += 1;
            }
        }
        n
    }
}

/// Minimal terminal query responses (DSR cursor position, DA1) so that
/// programs which probe the terminal do not hang.
fn query_responses(data: &[u8], parser: &VtParser) -> Vec<u8> {
    let mut out = Vec::new();
    let find = |needle: &[u8]| data.windows(needle.len()).any(|w| w == needle);
    if find(b"\x1b[6n") {
        let (r, c) = parser.screen().cursor_position();
        out.extend_from_slice(format!("\x1b[{};{}R", r + 1, c + 1).as_bytes());
    }
    if find(b"\x1b[c") || find(b"\x1b[0c") {
        out.extend_from_slice(b"\x1b[?1;2c");
    }
    if find(b"\x1b[5n") {
        out.extend_from_slice(b"\x1b[0n");
    }
    out
}

fn reader_loop(term: Arc<Terminal>, mut reader: Box<dyn Read + Send>, mut log: Option<TermLog>) {
    let mut buf = vec![0u8; 16 * 1024];
    loop {
        match reader.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                let chunk = &buf[..n];
                if let Some(l) = log.as_mut() {
                    l.append(chunk);
                }
                let reply = {
                    let mut st = term.state.lock();
                    st.parser.process(chunk);
                    query_responses(chunk, &st.parser)
                };
                if !reply.is_empty() {
                    if let Some(w) = term.writer.lock().as_mut() {
                        let _ = w.write_all(&reply);
                    }
                }
                term.bump();
            }
        }
    }
}

/// Optional persisted terminal output (off by default; per terminal).
struct TermLog {
    file: std::fs::File,
    written: u64,
    limit: u64,
}

impl TermLog {
    fn open(dir: &Path, id: &str, limit: u64) -> std::io::Result<Self> {
        std::fs::create_dir_all(dir)?;
        let path = dir.join(format!("{id}.log"));
        crate::secret_store::write_private_file(&path, b"")?;
        let file = std::fs::OpenOptions::new().append(true).open(path)?;
        Ok(Self {
            file,
            written: 0,
            limit,
        })
    }
    fn append(&mut self, data: &[u8]) {
        if self.written >= self.limit {
            return;
        }
        let take = ((self.limit - self.written) as usize).min(data.len());
        if self.file.write_all(&data[..take]).is_ok() {
            self.written += take as u64;
        }
    }
}

fn cleanup_logs(dir: &Path, days: i64) {
    let Ok(rd) = std::fs::read_dir(dir) else {
        return;
    };
    let cutoff = std::time::SystemTime::now()
        - std::time::Duration::from_secs((days.max(0) as u64) * 86_400);
    for e in rd.flatten() {
        if let Ok(m) = e.metadata() {
            if m.modified().map(|t| t < cutoff).unwrap_or(false) {
                let _ = std::fs::remove_file(e.path());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    fn wait_for(term: &Terminal, needle: &str, timeout: Duration) -> bool {
        let start = Instant::now();
        while start.elapsed() < timeout {
            let snap = term.snapshot();
            let text: String = snap
                .lines
                .iter()
                .map(|l| l.text())
                .collect::<Vec<_>>()
                .join("\n");
            if text.contains(needle) {
                return true;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        false
    }

    #[test]
    fn shells_are_detected() {
        assert!(!available_shells().is_empty());
    }

    #[test]
    fn run_command_and_exit() {
        let bus = EventBus::new(64);
        let mgr = TerminalManager::new(bus, None);
        let t = mgr
            .create(
                CreateTerminal {
                    shell: None,
                    cols: Some(100),
                    rows: Some(30),
                    ..Default::default()
                },
                Some("dev".into()),
            )
            .unwrap();
        let cmd = if cfg!(windows) {
            "echo SWITCHTETHER_OK_$((6*7))\r"
        } else {
            "echo SWITCHTETHER_OK_$((6*7))\n"
        };
        // cmd.exe does not evaluate $((…)); pwsh/powershell do not either, so
        // only assert on the stable prefix.
        t.write_input(cmd.as_bytes()).unwrap();
        assert!(
            wait_for(&t, "SWITCHTETHER_OK_", Duration::from_secs(20)),
            "output not seen: {:?}",
            t.snapshot()
                .lines
                .iter()
                .map(|l| l.text())
                .collect::<Vec<_>>()
        );
        t.resize(120, 40).unwrap();
        assert_eq!(t.info().cols, 120);
        assert_eq!(mgr.count(), 1);
        mgr.release_device("dev");
        let start = Instant::now();
        while !t.info().exited && start.elapsed() < Duration::from_secs(10) {
            std::thread::sleep(Duration::from_millis(50));
        }
        assert!(t.info().exited);
        assert_eq!(mgr.count(), 0);
    }

    #[test]
    fn query_response_dsr() {
        let mut p = new_parser(24, 80, 0);
        p.process(b"abc");
        let r = query_responses(b"\x1b[6n", &p);
        assert_eq!(r, b"\x1b[1;4R");
    }
}
