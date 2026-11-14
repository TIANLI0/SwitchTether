//! Attach broker: the desktop end of `switchtether-shim`.
//!
//! When the user opts in (VS Code process wrapper, Codex Desktop
//! `CODEX_CLI_PATH`, or `st-claude` / `st-codex` in a terminal), the shim sits
//! between the user's own program and the agent process and connects here.
//! The broker listens on 127.0.0.1 only, on a random port, and requires a
//! random token that is written to an owner-only file
//! (`~/.switchtether/remote/attach.json`). Nothing in the user's agent
//! configuration is touched by the broker itself.
//!
//! Wire format: newline-delimited JSON frames. The shim's first frame is
//! `{"t":"hello","v":1,"token":…,"kind":"stream"|"tty",…}`; the broker replies
//! `{"t":"welcome"}` or closes the connection.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use base64::Engine as _;
use parking_lot::Mutex;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use crate::agents::attached_tty::AttachedTty;
use crate::agents::claude::managed::ManagedClaude;
use crate::agents::codex::managed::{CodexConn, ManagedCodex};
use crate::agents::managed::ManagedSession;
use crate::agents::model::Liveness;
use crate::agents::store::SessionLog;
use crate::agents::AgentHub;
use crate::terminal::TerminalManager;

/// Largest accepted frame (a single stream-json line can carry file contents).
const MAX_FRAME: usize = 32 * 1024 * 1024;

pub struct AttachBroker {
    pub port: u16,
    file: PathBuf,
    cancel: CancellationToken,
}

impl AttachBroker {
    /// Bind, write the attach file and start accepting shim connections.
    pub async fn start(
        hub: Arc<AgentHub>,
        terms: Arc<TerminalManager>,
        file: PathBuf,
        cancel: CancellationToken,
    ) -> anyhow::Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
        let port = listener.local_addr()?.port();
        let token = crate::util::random_token();
        let info = json!({ "v": 1, "port": port, "token": token, "pid": std::process::id() });
        crate::secret_store::write_private_file(&file, info.to_string().as_bytes())?;
        let token = Arc::new(token);
        let c2 = cancel.clone();
        tokio::spawn(async move {
            loop {
                let accepted = tokio::select! {
                    _ = c2.cancelled() => break,
                    a = listener.accept() => a,
                };
                let Ok((stream, peer)) = accepted else {
                    continue;
                };
                if !peer.ip().is_loopback() {
                    continue;
                }
                let (hub, terms, token, cancel) =
                    (hub.clone(), terms.clone(), token.clone(), c2.clone());
                tokio::spawn(async move {
                    if let Err(e) = serve(stream, hub, terms, &token, cancel).await {
                        log::debug!("[attach] connection ended: {e}");
                    }
                });
            }
        });
        log::info!("[attach] broker listening on 127.0.0.1:{port}");
        Ok(Self { port, file, cancel })
    }

    pub fn file(&self) -> &Path {
        &self.file
    }
}

impl Drop for AttachBroker {
    fn drop(&mut self) {
        self.cancel.cancel();
        // Only remove the file if it is still ours (another instance may have replaced it).
        if let Ok(text) = std::fs::read_to_string(&self.file) {
            let ours = serde_json::from_str::<Value>(&text)
                .ok()
                .and_then(|v| v.get("port").and_then(|p| p.as_u64()))
                == Some(self.port as u64);
            if ours {
                let _ = std::fs::remove_file(&self.file);
            }
        }
    }
}

fn origin_name(s: Option<&str>) -> &'static str {
    match s {
        Some("vscode") => "vscode",
        Some("desktop_app") => "desktop_app",
        Some("cli") => "cli",
        _ => "unknown",
    }
}

async fn read_frame(
    r: &mut BufReader<tokio::net::tcp::OwnedReadHalf>,
    buf: &mut String,
) -> std::io::Result<Option<Value>> {
    loop {
        buf.clear();
        let n = r.read_line(buf).await?;
        if n == 0 {
            return Ok(None);
        }
        if buf.len() > MAX_FRAME {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "frame too large",
            ));
        }
        if let Ok(v) = serde_json::from_str::<Value>(buf.trim_end()) {
            return Ok(Some(v));
        }
    }
}

async fn serve(
    stream: TcpStream,
    hub: Arc<AgentHub>,
    terms: Arc<TerminalManager>,
    token: &str,
    cancel: CancellationToken,
) -> anyhow::Result<()> {
    let _ = stream.set_nodelay(true);
    let (rd, mut wr) = stream.into_split();
    let mut rd = BufReader::new(rd);
    let mut buf = String::new();
    let hello = tokio::time::timeout(Duration::from_secs(5), read_frame(&mut rd, &mut buf))
        .await??
        .ok_or_else(|| anyhow::anyhow!("no hello"))?;
    let ok = hello.get("t").and_then(|t| t.as_str()) == Some("hello")
        && hello.get("v").and_then(|v| v.as_u64()) == Some(1)
        && hello
            .get("token")
            .and_then(|t| t.as_str())
            .is_some_and(|t| crate::util::ct_eq(t, token));
    if !ok {
        anyhow::bail!("rejected attach hello");
    }
    wr.write_all(b"{\"t\":\"welcome\"}\n").await?;

    // Outbound frames (inject / input) → shim.
    let (tx, mut rx) = mpsc::unbounded_channel::<String>();
    tokio::spawn(async move {
        while let Some(mut line) = rx.recv().await {
            line.push('\n');
            if wr.write_all(line.as_bytes()).await.is_err() {
                break;
            }
        }
    });

    let kind = hello.get("kind").and_then(|k| k.as_str()).unwrap_or("");
    let cwd = hello
        .get("cwd")
        .and_then(|c| c.as_str())
        .map(PathBuf::from)
        .unwrap_or_default();
    let origin = origin_name(hello.get("origin").and_then(|o| o.as_str()));
    let agent = match hello.get("agent").and_then(|a| a.as_str()) {
        Some("codex") => "codex",
        _ => "claude_code",
    };
    let new_log = |id: &str| Arc::new(Mutex::new(SessionLog::new(id, Some(hub.bus()))));

    match (kind, hello.get("dialect").and_then(|d| d.as_str())) {
        ("stream", Some("claude")) => {
            let id = format!("attached:{}", crate::util::new_id());
            let log = new_log(&id);
            if let Some(resume) = hello.get("resume").and_then(|r| r.as_str()) {
                hub.preload_history("claude_code", resume, Some(&cwd.to_string_lossy()), &log)
                    .await;
            }
            let session = ManagedClaude::attached(id.clone(), cwd, log, tx, origin);
            hub.register_attached(id.clone(), session.clone());
            loop {
                let frame = tokio::select! {
                    _ = cancel.cancelled() => None,
                    f = read_frame(&mut rd, &mut buf) => f.ok().flatten(),
                };
                let Some(frame) = frame else { break };
                let before = (session.native_session_id(), session.status());
                session.on_remote_frame(frame).await;
                if (session.native_session_id(), session.status()) != before {
                    hub.publish_session(&id);
                }
            }
            if session.liveness() == Liveness::Live {
                session.on_remote_frame(json!({ "t": "exit" })).await;
            }
            hub.publish_session(&id);
        }
        ("stream", Some("jsonrpc")) => {
            let conn = CodexConn::new(tx, origin, cwd.clone());
            loop {
                let frame = tokio::select! {
                    _ = cancel.cancelled() => None,
                    f = read_frame(&mut rd, &mut buf) => f.ok().flatten(),
                };
                let Some(frame) = frame else { break };
                for (thread, tcwd) in conn.discover(&frame) {
                    let id = format!("attached:{}", crate::util::new_id());
                    let log = new_log(&id);
                    let tcwd = tcwd.map(PathBuf::from).unwrap_or_else(|| cwd.clone());
                    hub.preload_history("codex", &thread, Some(&tcwd.to_string_lossy()), &log)
                        .await;
                    let session =
                        ManagedCodex::attached(id.clone(), conn.clone(), thread.clone(), tcwd, log);
                    conn.threads.lock().insert(thread, session.clone());
                    hub.register_attached(id, session);
                }
                let before: Vec<_> = conn
                    .threads
                    .lock()
                    .values()
                    .map(|t| (t.id.clone(), t.status()))
                    .collect();
                conn.on_frame(frame).await;
                let threads: Vec<_> = conn.threads.lock().values().cloned().collect();
                for t in threads {
                    if !before.contains(&(t.id.clone(), t.status())) {
                        hub.publish_session(&t.id);
                    }
                }
            }
            conn.on_disconnect();
            let ids: Vec<String> = conn.threads.lock().values().map(|t| t.id.clone()).collect();
            for id in ids {
                hub.publish_session(&id);
            }
        }
        ("tty", _) => {
            let cols = hello.get("cols").and_then(|c| c.as_u64()).unwrap_or(120) as u16;
            let rows = hello.get("rows").and_then(|c| c.as_u64()).unwrap_or(32) as u16;
            let title = match agent {
                "codex" => "Codex (terminal)",
                _ => "Claude Code (terminal)",
            };
            let term = terms.create_external(
                title.into(),
                cwd.to_string_lossy().into_owned(),
                cols,
                rows,
                tx,
            );
            let id = format!("attached:{}", crate::util::new_id());
            let pid = hello.get("pid").and_then(|p| p.as_u64()).map(|p| p as u32);
            let tty = AttachedTty::new(id.clone(), agent, cwd, pid, new_log(&id), term.clone());
            hub.register_tty(id.clone(), tty.clone());
            let mut refresh = tokio::time::interval(Duration::from_secs(2));
            let mut exit_code = None;
            loop {
                let frame = tokio::select! {
                    _ = cancel.cancelled() => None,
                    _ = refresh.tick() => {
                        let before = (tty.native_session_id(), tty.status(), tty.waiting_for());
                        hub.refresh_tty(&tty).await;
                        if (tty.native_session_id(), tty.status(), tty.waiting_for()) != before {
                            hub.publish_session(&id);
                        }
                        continue;
                    }
                    f = read_frame(&mut rd, &mut buf) => f.ok().flatten(),
                };
                let Some(frame) = frame else { break };
                match frame.get("t").and_then(|t| t.as_str()) {
                    Some("pty") => {
                        if let Some(b) = frame.get("b64").and_then(|b| b.as_str()) {
                            if let Ok(bytes) = base64::engine::general_purpose::STANDARD.decode(b) {
                                term.feed_output(&bytes);
                            }
                        }
                    }
                    Some("size") => {
                        let c = frame.get("cols").and_then(|c| c.as_u64()).unwrap_or(0) as u16;
                        let r = frame.get("rows").and_then(|c| c.as_u64()).unwrap_or(0) as u16;
                        if c > 0 && r > 0 {
                            term.set_external_size(c, r);
                        }
                    }
                    Some("exit") => {
                        exit_code = frame.get("code").and_then(|c| c.as_i64()).map(|c| c as i32);
                        break;
                    }
                    _ => {}
                }
            }
            hub.refresh_tty(&tty).await;
            tty.mark_ended();
            terms.external_exited(&term.id, exit_code);
            hub.publish_session(&id);
        }
        _ => anyhow::bail!("unknown attach kind"),
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn origins_are_whitelisted() {
        assert_eq!(origin_name(Some("vscode")), "vscode");
        assert_eq!(origin_name(Some("managed")), "unknown");
        assert_eq!(origin_name(None), "unknown");
    }
}
