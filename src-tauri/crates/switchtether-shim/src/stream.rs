//! Line-oriented stdio proxy between the host (IDE / Codex Desktop), the agent
//! child process and the SwitchTether broker.

use std::io::{BufRead, BufReader, Write};
use std::process::{Command, ExitCode, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::{json, Value};

use crate::broker::Broker;
use crate::router::{Actions, Dialect, Router};
use crate::Target;

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    match m.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    }
}

/// Optional troubleshooting log (`SWITCHTETHER_SHIM_DEBUG=<file>`): frame
/// kinds and sizes only, never message contents.
fn debug(msg: &str) {
    if let Some(p) = std::env::var_os("SWITCHTETHER_SHIM_DEBUG") {
        if let Ok(mut f) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(p)
        {
            let _ = writeln!(f, "{} {msg}", std::process::id());
        }
    }
}

pub fn run(target: Target, dialect: Dialect) -> ExitCode {
    let mut child = match Command::new(&target.program)
        .args(&target.args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
    {
        Ok(c) => c,
        Err(e) => {
            eprintln!(
                "switchtether-shim: cannot start {}: {e}",
                target.program.display()
            );
            return ExitCode::from(127);
        }
    };
    let child_stdin = Arc::new(Mutex::new(child.stdin.take()));
    let child_stdout = child.stdout.take().expect("piped stdout");
    let host_out = Arc::new(Mutex::new(std::io::stdout()));
    let router = Arc::new(Mutex::new(Router::new(dialect)));

    let resume_initial = resume_arg(&target.args);
    let cwd = std::env::current_dir()
        .ok()
        .map(|p| p.to_string_lossy().into_owned());
    let child_pid = child.id();
    let active_broker: Arc<Mutex<Option<Broker>>> = Arc::new(Mutex::new(None));
    let done = Arc::new(AtomicBool::new(false));

    let make_hello = {
        let router = router.clone();
        let cwd = cwd.clone();
        let resume_initial = resume_initial.clone();
        move |reattached: bool| {
            let resume = lock(&router)
                .claude_session_id()
                .map(String::from)
                .or_else(|| resume_initial.clone());
            json!({
                "kind": "stream",
                "dialect": match dialect { Dialect::ClaudeStream => "claude", Dialect::JsonRpc => "jsonrpc" },
                "agent": target.agent,
                "origin": target.origin,
                "pid": child_pid,
                "shimPid": std::process::id(),
                "cwd": cwd,
                "resume": resume,
                "reattached": reattached,
            })
        }
    };

    // Try initial connection synchronously so sessions started while desktop is running
    // are registered immediately before the first agent output arrives.
    let initial = Broker::connect(make_hello(false));
    debug(&format!("broker connected: {}", initial.is_some()));
    if let Some((b, _)) = &initial {
        *lock(&active_broker) = Some(b.clone());
    }

    let apply = {
        let child_stdin = child_stdin.clone();
        let host_out = host_out.clone();
        let active_broker = active_broker.clone();
        move |a: Actions| {
            if !a.to_agent.is_empty() {
                let mut g = lock(&child_stdin);
                if let Some(w) = g.as_mut() {
                    for l in &a.to_agent {
                        let _ = w.write_all(l.as_bytes());
                        let _ = w.write_all(b"\n");
                    }
                    let _ = w.flush();
                }
            }
            if !a.to_host.is_empty() {
                let mut o = lock(&host_out);
                for l in &a.to_host {
                    let _ = o.write_all(l.as_bytes());
                    let _ = o.write_all(b"\n");
                }
                let _ = o.flush();
            }
            if !a.to_desktop.is_empty() {
                let b = lock(&active_broker).clone();
                if let Some(b) = b {
                    for f in &a.to_desktop {
                        b.send(f);
                    }
                }
            }
        }
    };
    let apply = Arc::new(apply);

    // host → agent
    {
        let router = router.clone();
        let apply = apply.clone();
        let child_stdin = child_stdin.clone();
        std::thread::spawn(move || {
            let mut r = BufReader::new(std::io::stdin().lock());
            let mut line = String::new();
            loop {
                line.clear();
                match r.read_line(&mut line) {
                    Ok(0) | Err(_) => break,
                    Ok(_) => {
                        let l = line.trim_end_matches(['\n', '\r']);
                        let a = lock(&router).on_host(l);
                        apply(a);
                    }
                }
            }
            // Host closed its pipe: let the agent see EOF.
            lock(&child_stdin).take();
        });
    }

    // desktop ↔ shim supervisor (handles desktop restarts and late desktop starts)
    {
        let router = router.clone();
        let apply = apply.clone();
        let active_broker = active_broker.clone();
        let done = done.clone();
        std::thread::spawn(move || {
            let mut current = initial;
            let mut reattached = false;
            while !done.load(Ordering::Relaxed) {
                if let Some((broker, reader)) = current.take() {
                    *lock(&active_broker) = Some(broker.clone());
                    if reattached {
                        let replay = lock(&router).reconnect_frames();
                        for f in replay {
                            broker.send(&f);
                        }
                    }
                    for frame in reader.lines() {
                        if done.load(Ordering::Relaxed) {
                            break;
                        }
                        let Ok(frame) = frame else {
                            debug("desktop read error");
                            break;
                        };
                        debug(&format!("desktop frame {} bytes", frame.len()));
                        let Ok(v) = serde_json::from_str::<Value>(&frame) else {
                            continue;
                        };
                        if v.get("t").and_then(|t| t.as_str()) == Some("inject") {
                            if let Some(l) = v.get("line").and_then(|l| l.as_str()) {
                                let a = lock(&router).on_desktop(l);
                                debug(&format!(
                                    "inject: to_agent={} to_host={} to_desktop={}",
                                    a.to_agent.len(),
                                    a.to_host.len(),
                                    a.to_desktop.len()
                                ));
                                apply(a);
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
                if current.is_some() {
                    debug("broker reconnected");
                }
            }
        });
    }

    // agent → host (main thread)
    {
        let mut r = BufReader::new(child_stdout);
        let mut line = String::new();
        loop {
            line.clear();
            match r.read_line(&mut line) {
                Ok(0) | Err(_) => break,
                Ok(_) => {
                    let l = line.trim_end_matches(['\n', '\r']);
                    let a = lock(&router).on_agent(l);
                    apply(a);
                }
            }
        }
    }
    let code = child.wait().ok().and_then(|s| s.code()).unwrap_or(1);
    done.store(true, Ordering::Relaxed);
    apply(Actions {
        to_desktop: vec![json!({ "t": "exit", "code": code })],
        ..Default::default()
    });
    ExitCode::from(code.clamp(0, 255) as u8)
}

/// `--resume <id>` / `--resume=<id>` (the IDE reopening an existing conversation).
fn resume_arg(args: &[std::ffi::OsString]) -> Option<String> {
    let mut it = args.iter().map(|a| a.to_string_lossy().into_owned());
    while let Some(a) = it.next() {
        if a == "--resume" || a == "-r" {
            return it.next().filter(|v| !v.starts_with('-'));
        }
        if let Some(v) = a.strip_prefix("--resume=") {
            return Some(v.to_string());
        }
    }
    None
}

#[cfg(test)]
mod tests {
    #[test]
    fn resume_arg_is_found() {
        let a = |v: &[&str]| v.iter().map(std::ffi::OsString::from).collect::<Vec<_>>();
        assert_eq!(
            super::resume_arg(&a(&["--verbose", "--resume", "abc"])).as_deref(),
            Some("abc")
        );
        assert_eq!(
            super::resume_arg(&a(&["--resume=x1"])).as_deref(),
            Some("x1")
        );
        assert_eq!(super::resume_arg(&a(&["--print"])), None);
    }
}
