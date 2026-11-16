//! Terminal takeover: run `claude` / `codex` (interactive TUI) inside a PTY
//! owned by the shim, pass it through to the user's terminal unchanged, and
//! mirror the raw output to SwitchTether. Input from the phone is written into
//! the same PTY, so both the user at the computer and the phone drive the very
//! same live TUI. The PTY size follows the user's local terminal.

use std::collections::VecDeque;
use std::ffi::OsString;
use std::io::{Read, Write};
use std::process::ExitCode;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use base64::Engine as _;
use portable_pty::{native_pty_system, CommandBuilder, PtySize};
use serde_json::{json, Value};

use crate::broker::Broker;

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    match m.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    }
}

pub fn run(mut argv: Vec<OsString>) -> ExitCode {
    if argv.is_empty() {
        eprintln!("usage: switchtether-shim tty <claude|codex> [args…]");
        return ExitCode::from(2);
    }
    let program = argv.remove(0);
    let prog_s = program.to_string_lossy().to_ascii_lowercase();
    let agent = if prog_s.contains("codex") {
        "codex"
    } else {
        "claude_code"
    };
    let local_size = local::size();
    // Without a real terminal on our side nobody answers ConPTY's/the TUI's
    // cursor-position query, which would stall start-up: answer it ourselves.
    let headless = local_size.is_none();
    let (cols, rows) = local_size.unwrap_or((120, 32));

    let pty = native_pty_system();
    let pair = match pty.openpty(PtySize {
        rows,
        cols,
        pixel_width: 0,
        pixel_height: 0,
    }) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("switchtether-shim: no pty: {e}");
            return ExitCode::from(1);
        }
    };
    let resolved = resolve_program(&program);
    let mut cmd = CommandBuilder::new(&resolved);
    cmd.args(&argv);
    if let Ok(cwd) = std::env::current_dir() {
        cmd.cwd(cwd);
    }
    let mut child = match pair.slave.spawn_command(cmd) {
        Ok(c) => c,
        Err(e) => {
            eprintln!(
                "switchtether-shim: cannot start {}: {e}",
                resolved.to_string_lossy()
            );
            return ExitCode::from(127);
        }
    };
    drop(pair.slave);
    let mut reader = pair.master.try_clone_reader().expect("pty reader");
    let writer = Arc::new(Mutex::new(pair.master.take_writer().expect("pty writer")));
    let master = Arc::new(Mutex::new(pair.master));

    let child_pid = child.process_id();
    let cwd_s = std::env::current_dir()
        .ok()
        .map(|p| p.to_string_lossy().into_owned());
    let prog_name = program.to_string_lossy().into_owned();
    let cur_size = Arc::new(Mutex::new((cols, rows)));
    let pty_ring: Arc<Mutex<VecDeque<u8>>> =
        Arc::new(Mutex::new(VecDeque::with_capacity(16 * 1024)));
    let active_broker: Arc<Mutex<Option<Broker>>> = Arc::new(Mutex::new(None));
    let done = Arc::new(AtomicBool::new(false));

    let make_hello = {
        let cur_size = cur_size.clone();
        let cwd_s = cwd_s.clone();
        let prog_name = prog_name.clone();
        move |reattached: bool| {
            let (c, r) = *lock(&cur_size);
            json!({
                "kind": "tty",
                "agent": agent,
                "origin": "cli",
                "pid": child_pid,
                "shimPid": std::process::id(),
                "cwd": cwd_s,
                "cols": c,
                "rows": r,
                "command": prog_name,
                "reattached": reattached,
            })
        }
    };

    let initial = Broker::connect(make_hello(false));
    if let Some((b, _)) = &initial {
        *lock(&active_broker) = Some(b.clone());
    }
    let raw = local::RawMode::enable();

    // phone → pty supervisor (handles desktop restarts and late desktop starts)
    {
        let writer = writer.clone();
        let active_broker = active_broker.clone();
        let pty_ring = pty_ring.clone();
        let done = done.clone();
        std::thread::spawn(move || {
            use std::io::BufRead;
            let mut current = initial;
            let mut reattached = false;
            while !done.load(Ordering::Relaxed) {
                if let Some((broker, frames)) = current.take() {
                    *lock(&active_broker) = Some(broker.clone());
                    if reattached {
                        let snap: Vec<u8> = lock(&pty_ring).iter().copied().collect();
                        if !snap.is_empty() {
                            broker.send(&json!({
                                "t": "pty",
                                "b64": base64::engine::general_purpose::STANDARD.encode(&snap),
                            }));
                        }
                    }
                    if let Ok(raw_stream) = frames.get_ref().try_clone() {
                        let _ = raw_stream.set_read_timeout(None);
                        for f in std::io::BufReader::new(raw_stream).lines() {
                            if done.load(Ordering::Relaxed) {
                                break;
                            }
                            let Ok(f) = f else { break };
                            let Ok(v) = serde_json::from_str::<Value>(&f) else {
                                continue;
                            };
                            if v.get("t").and_then(|t| t.as_str()) == Some("input") {
                                let bytes = match (
                                    v.get("b64").and_then(|b| b.as_str()),
                                    v.get("data").and_then(|d| d.as_str()),
                                ) {
                                    (Some(b), _) => base64::engine::general_purpose::STANDARD
                                        .decode(b)
                                        .unwrap_or_default(),
                                    (None, Some(d)) => d.as_bytes().to_vec(),
                                    _ => continue,
                                };
                                let mut w = lock(&writer);
                                let _ = w.write_all(&bytes);
                                let _ = w.flush();
                            }
                        }
                    }
                    *lock(&active_broker) = None;
                }
                if done.load(Ordering::Relaxed) {
                    break;
                }
                std::thread::sleep(Duration::from_millis(1500));
                if done.load(Ordering::Relaxed) {
                    break;
                }
                reattached = true;
                current = Broker::connect(make_hello(true));
            }
        });
    }

    // local keyboard → pty
    {
        let writer = writer.clone();
        std::thread::spawn(move || {
            let mut stdin = std::io::stdin();
            let mut buf = [0u8; 4096];
            loop {
                match stdin.read(&mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        let mut w = lock(&writer);
                        let _ = w.write_all(&buf[..n]);
                        let _ = w.flush();
                    }
                }
            }
        });
    }

    // local terminal resize → pty (+ tell the desktop)
    {
        let master = master.clone();
        let active_broker = active_broker.clone();
        let cur_size = cur_size.clone();
        let done = done.clone();
        std::thread::spawn(move || {
            let mut last = (cols, rows);
            while !done.load(Ordering::Relaxed) {
                std::thread::sleep(Duration::from_millis(300));
                if let Some(now) = local::size() {
                    if now != last && now.0 > 0 && now.1 > 0 {
                        last = now;
                        *lock(&cur_size) = now;
                        let _ = lock(&master).resize(PtySize {
                            rows: now.1,
                            cols: now.0,
                            pixel_width: 0,
                            pixel_height: 0,
                        });
                        if let Some(b) = lock(&active_broker).clone() {
                            b.send(&json!({ "t": "size", "cols": now.0, "rows": now.1 }));
                        }
                    }
                }
            }
        });
    }

    // pty → local terminal + desktop (main thread)
    let mut out = std::io::stdout();
    let mut buf = vec![0u8; 16 * 1024];
    let waiter = std::thread::spawn(move || child.wait().ok().map(|s| s.exit_code() as i32));
    loop {
        match reader.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                let _ = out.write_all(&buf[..n]);
                let _ = out.flush();
                {
                    let mut ring = lock(&pty_ring);
                    for &byte in &buf[..n] {
                        if ring.len() >= 16 * 1024 {
                            ring.pop_front();
                        }
                        ring.push_back(byte);
                    }
                }
                if headless && buf[..n].windows(4).any(|w| w == b"[6n") {
                    let mut w = lock(&writer);
                    let _ = w.write_all(b"[1;1R");
                    let _ = w.flush();
                }
                if let Some(b) = lock(&active_broker).clone() {
                    b.send(&json!({ "t": "pty", "b64": base64::engine::general_purpose::STANDARD.encode(&buf[..n]) }));
                }
            }
        }
        if waiter.is_finished() {
            // Windows ConPTY keeps the pipe open after exit; drain then stop.
            std::thread::sleep(Duration::from_millis(100));
            break;
        }
    }
    let code = waiter.join().ok().flatten().unwrap_or(0);
    done.store(true, Ordering::Relaxed);
    drop(raw);
    if let Some(b) = lock(&active_broker).clone() {
        b.send(&json!({ "t": "exit", "code": code }));
    }
    ExitCode::from(code.clamp(0, 255) as u8)
}

/// Resolve `claude` / `codex` on PATH (including `.cmd` shims on Windows).
fn resolve_program(p: &OsString) -> std::path::PathBuf {
    let pb = std::path::PathBuf::from(p);
    if pb.components().count() > 1 || pb.is_file() {
        return pb;
    }
    let exts: &[&str] = if cfg!(windows) {
        &[".exe", ".cmd", ".bat", ""]
    } else {
        &[""]
    };
    if let Some(path) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&path) {
            for e in exts {
                let c = dir.join(format!("{}{e}", p.to_string_lossy()));
                if c.is_file() {
                    return c;
                }
            }
        }
    }
    pb
}

#[cfg(unix)]
mod local {
    pub fn size() -> Option<(u16, u16)> {
        unsafe {
            let mut ws: libc::winsize = std::mem::zeroed();
            if libc::ioctl(libc::STDOUT_FILENO, libc::TIOCGWINSZ, &mut ws) == 0 && ws.ws_col > 0 {
                Some((ws.ws_col, ws.ws_row))
            } else {
                None
            }
        }
    }

    pub struct RawMode(Option<libc::termios>);

    impl RawMode {
        pub fn enable() -> Self {
            unsafe {
                let mut t: libc::termios = std::mem::zeroed();
                if libc::tcgetattr(libc::STDIN_FILENO, &mut t) != 0 {
                    return RawMode(None);
                }
                let orig = t;
                libc::cfmakeraw(&mut t);
                libc::tcsetattr(libc::STDIN_FILENO, libc::TCSANOW, &t);
                RawMode(Some(orig))
            }
        }
    }

    impl Drop for RawMode {
        fn drop(&mut self) {
            if let Some(orig) = self.0 {
                unsafe {
                    libc::tcsetattr(libc::STDIN_FILENO, libc::TCSANOW, &orig);
                }
            }
        }
    }
}

#[cfg(windows)]
mod local {
    use windows_sys::Win32::System::Console::{
        GetConsoleMode, GetConsoleScreenBufferInfo, GetStdHandle, SetConsoleMode,
        CONSOLE_SCREEN_BUFFER_INFO, ENABLE_ECHO_INPUT, ENABLE_LINE_INPUT, ENABLE_PROCESSED_INPUT,
        ENABLE_VIRTUAL_TERMINAL_INPUT, ENABLE_VIRTUAL_TERMINAL_PROCESSING, STD_INPUT_HANDLE,
        STD_OUTPUT_HANDLE,
    };

    pub fn size() -> Option<(u16, u16)> {
        unsafe {
            let h = GetStdHandle(STD_OUTPUT_HANDLE);
            let mut info: CONSOLE_SCREEN_BUFFER_INFO = std::mem::zeroed();
            if GetConsoleScreenBufferInfo(h, &mut info) == 0 {
                return None;
            }
            let cols = (info.srWindow.Right - info.srWindow.Left + 1) as u16;
            let rows = (info.srWindow.Bottom - info.srWindow.Top + 1) as u16;
            Some((cols, rows))
        }
    }

    pub struct RawMode(Option<(u32, u32)>);

    impl RawMode {
        pub fn enable() -> Self {
            unsafe {
                let hin = GetStdHandle(STD_INPUT_HANDLE);
                let hout = GetStdHandle(STD_OUTPUT_HANDLE);
                let (mut min, mut mout) = (0u32, 0u32);
                if GetConsoleMode(hin, &mut min) == 0 || GetConsoleMode(hout, &mut mout) == 0 {
                    return RawMode(None);
                }
                let new_in = (min
                    & !(ENABLE_ECHO_INPUT | ENABLE_LINE_INPUT | ENABLE_PROCESSED_INPUT))
                    | ENABLE_VIRTUAL_TERMINAL_INPUT;
                SetConsoleMode(hin, new_in);
                SetConsoleMode(hout, mout | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
                RawMode(Some((min, mout)))
            }
        }
    }

    impl Drop for RawMode {
        fn drop(&mut self) {
            if let Some((min, mout)) = self.0 {
                unsafe {
                    SetConsoleMode(GetStdHandle(STD_INPUT_HANDLE), min);
                    SetConsoleMode(GetStdHandle(STD_OUTPUT_HANDLE), mout);
                }
            }
        }
    }
}
