//! DeepSeek Harness Web (docs/PROTOCOL.md §7.14): the phone opens `dsh web` — DeepSeek Harness's
//! own browser UI — through a port forward (§7.11) instead of a new tunnel.
//!
//! - **Discovery**: a `dsh web` the user started is found by asking loopback listeners for `/`:
//!   dsh answers an unauthenticated request with `401` and the fixed text
//!   `dsh web authentication required` (dsh-client-connection). Port 3080 (dsh's default) is
//!   tried first, then the desktop's listening ports (on Windows only node / bun / dsh processes).
//! - **Start**: when none runs, SwitchTether starts `dsh web --no-open --host 127.0.0.1 --port 0`
//!   as its own child — the command the user would type, with the user's environment and existing
//!   `web` profile; nothing is written to the profile by SwitchTether. The child prints
//!   `dsh web: http://127.0.0.1:<port>/?token=<launch token>`; the port and the per-process launch
//!   token come from that line. The token is handed only to a step-up-confirmed device in the
//!   response of `POST /v1/dsh-web/open` (never in status, events, logs or the audit trail).
//! - **Stop**: only the instance SwitchTether started, on request or when SwitchTether exits. On
//!   Windows the child runs in a job object that is closed (killing `dsh.exe` *and* its `node`
//!   child) on stop and also when SwitchTether's process dies; on Unix it gets its own process
//!   group, which is signalled as a whole.

use std::path::PathBuf;
use std::process::Stdio;
use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use serde::Serialize;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio::process::Command;
use tokio_util::sync::CancellationToken;

/// dsh's default `dsh web` port (`webserver.port` in the shipped `web` profile).
pub const DEFAULT_PORT: u16 = 3080;
/// How long a starting `dsh web` may take to print its URL (first runs install / initialise).
pub const START_TIMEOUT: Duration = Duration::from_secs(120);
const PROBE_TIMEOUT: Duration = Duration::from_millis(800);
/// The body dsh sends with `401` to a browser without its session cookie.
const AUTH_REQUIRED_TEXT: &str = "dsh web authentication required";
/// Listening processes that can be `dsh web` (Windows reports names).
const DSH_PROCESSES: &[&str] = &["node", "bun", "dsh"];

/// What the phone sees (`GET /v1/dsh-web`, fixture `protocol/fixtures/dsh_web_status.json`).
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DshWebStatus {
    /// `not_installed` | `stopped` | `starting` | `running`.
    pub state: &'static str,
    /// Desktop loopback port of the running instance.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,
    /// SwitchTether started this instance (and may stop it); false for one the user started.
    pub owned: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub started_at_ms: Option<i64>,
    /// Why the last start failed (state `stopped`), without secrets.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

impl DshWebStatus {
    pub fn is_running(&self) -> bool {
        self.state == "running"
    }
}

/// How `dsh web` is started. Replaceable for tests (a fake program printing the URL line).
#[derive(Clone)]
pub struct Launcher {
    /// The `dsh` executable; `None` = not installed.
    pub resolve: Arc<dyn Fn() -> Option<PathBuf> + Send + Sync>,
    pub args: Vec<String>,
    /// Extra environment (tests: a sandbox `DSH_HOME`).
    pub env: Vec<(String, String)>,
    /// Look for a `dsh web` the user started before starting one.
    pub discover: bool,
    pub start_timeout: Duration,
}

impl Default for Launcher {
    fn default() -> Self {
        Self {
            resolve: Arc::new(crate::agents::dsh::managed::find_dsh_bin),
            args: ["web", "--no-open", "--host", "127.0.0.1", "--port", "0"]
                .iter()
                .map(|s| s.to_string())
                .collect(),
            env: Vec::new(),
            discover: true,
            start_timeout: START_TIMEOUT,
        }
    }
}

struct Owned {
    generation: u64,
    port: Option<u16>,
    token: Option<String>,
    started_at_ms: i64,
    stop: CancellationToken,
}

#[derive(Default)]
struct Inner {
    owned: Option<Owned>,
    last_error: Option<String>,
    generation: u64,
}

pub struct DshWeb {
    inner: Arc<Mutex<Inner>>,
    launcher: Mutex<Launcher>,
}

impl Default for DshWeb {
    fn default() -> Self {
        Self::new(Launcher::default())
    }
}

impl DshWeb {
    pub fn new(launcher: Launcher) -> Self {
        Self {
            inner: Arc::new(Mutex::new(Inner::default())),
            launcher: Mutex::new(launcher),
        }
    }

    pub fn set_launcher(&self, launcher: Launcher) {
        *self.launcher.lock() = launcher;
    }

    fn owned_status(&self) -> Option<DshWebStatus> {
        let g = self.inner.lock();
        g.owned.as_ref().map(|o| DshWebStatus {
            state: if o.port.is_some() {
                "running"
            } else {
                "starting"
            },
            port: o.port,
            owned: true,
            started_at_ms: Some(o.started_at_ms),
            error: None,
        })
    }

    /// Current state; `exclude` are ports never probed (the Remote server's own).
    pub async fn status(&self, exclude: &[u16]) -> DshWebStatus {
        if let Some(s) = self.owned_status() {
            return s;
        }
        let launcher = self.launcher.lock().clone();
        if launcher.discover {
            if let Some(port) = find_running(exclude).await {
                return DshWebStatus {
                    state: "running",
                    port: Some(port),
                    owned: false,
                    started_at_ms: None,
                    error: None,
                };
            }
        }
        let installed = tokio::task::spawn_blocking(move || (launcher.resolve)().is_some())
            .await
            .unwrap_or(false);
        DshWebStatus {
            state: if installed {
                "stopped"
            } else {
                "not_installed"
            },
            port: None,
            owned: false,
            started_at_ms: None,
            error: self.inner.lock().last_error.clone(),
        }
    }

    /// Reuses a running instance or starts one (returns at once with `starting`).
    pub async fn start(&self, cwd: PathBuf, exclude: &[u16]) -> DshWebStatus {
        let current = self.status(exclude).await;
        if current.state != "stopped" {
            return current;
        }
        let launcher = self.launcher.lock().clone();
        let Some(bin) = tokio::task::spawn_blocking({
            let l = launcher.clone();
            move || (l.resolve)()
        })
        .await
        .ok()
        .flatten() else {
            return self.status(exclude).await;
        };
        let (generation, stop) = {
            let mut g = self.inner.lock();
            if g.owned.is_some() {
                drop(g);
                return self.owned_status().unwrap_or(current);
            }
            g.generation += 1;
            let stop = CancellationToken::new();
            g.owned = Some(Owned {
                generation: g.generation,
                port: None,
                token: None,
                started_at_ms: crate::util::now_ms(),
                stop: stop.clone(),
            });
            g.last_error = None;
            (g.generation, stop)
        };
        if let Err(e) = spawn_child(self.inner.clone(), generation, stop, &bin, &launcher, cwd) {
            let mut g = self.inner.lock();
            if g.owned.as_ref().is_some_and(|o| o.generation == generation) {
                g.owned = None;
            }
            g.last_error = Some(format!("could not start dsh web: {e}"));
        }
        self.status(exclude).await
    }

    /// `/?token=…` for the instance SwitchTether started (None for one the user started).
    pub fn launch_path(&self) -> Option<String> {
        let g = self.inner.lock();
        let o = g.owned.as_ref()?;
        o.port?;
        o.token.as_ref().map(|t| format!("/?token={t}"))
    }

    /// Stops the instance SwitchTether started. False when it did not start one.
    pub fn stop(&self) -> bool {
        let owned = self.inner.lock().owned.take();
        match owned {
            Some(o) => {
                o.stop.cancel();
                true
            }
            None => false,
        }
    }

    /// SwitchTether exits: end the instance it started.
    pub fn shutdown(&self) {
        self.stop();
    }
}

/// `dsh web: http://127.0.0.1:52166/?token=abc` → (52166, Some("abc")).
pub fn parse_url_line(line: &str) -> Option<(u16, Option<String>)> {
    let url = line.trim().strip_prefix("dsh web:")?.trim();
    let rest = url
        .strip_prefix("http://")
        .or_else(|| url.strip_prefix("https://"))?;
    let (authority, tail) = rest.split_at(rest.find(['/', '?']).unwrap_or(rest.len()));
    let port = authority.rsplit_once(':')?.1.parse::<u16>().ok()?;
    let token = tail
        .split_once('?')
        .map(|(_, q)| q)
        .and_then(|q| {
            q.split('&')
                .find_map(|kv| kv.strip_prefix("token=").map(str::to_string))
        })
        .filter(|t| {
            !t.is_empty()
                && t.chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
        });
    Some((port, token))
}

fn spawn_child(
    inner: Arc<Mutex<Inner>>,
    generation: u64,
    stop: CancellationToken,
    bin: &std::path::Path,
    launcher: &Launcher,
    cwd: PathBuf,
) -> std::io::Result<()> {
    let mut cmd = Command::new(bin);
    cmd.args(&launcher.args)
        .current_dir(cwd)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    for (k, v) in &launcher.env {
        cmd.env(k, v);
    }
    #[cfg(windows)]
    {
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    #[cfg(unix)]
    {
        cmd.process_group(0);
    }
    let mut child = cmd.spawn()?;
    let tree = ProcessTree::attach(&child);
    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    let last_err = Arc::new(Mutex::new(String::new()));

    if let Some(err) = stderr {
        let last_err = last_err.clone();
        tokio::spawn(async move {
            let mut lines = BufReader::new(err).lines();
            while let Ok(Some(l)) = lines.next_line().await {
                let l = l.trim();
                if !l.is_empty() {
                    *last_err.lock() = l.chars().take(300).collect();
                }
            }
        });
    }
    if let Some(out) = stdout {
        let inner = inner.clone();
        tokio::spawn(async move {
            let mut lines = BufReader::new(out).lines();
            while let Ok(Some(l)) = lines.next_line().await {
                if let Some((port, token)) = parse_url_line(&l) {
                    let mut g = inner.lock();
                    if let Some(o) = g.owned.as_mut().filter(|o| o.generation == generation) {
                        if o.port.is_none() {
                            o.port = Some(port);
                            o.token = token;
                            log::info!("[dsh-web] started on 127.0.0.1:{port}");
                        }
                    }
                }
            }
        });
    }

    let timeout = launcher.start_timeout;
    tokio::spawn(async move {
        let started = tokio::time::Instant::now();
        let ready = || {
            inner
                .lock()
                .owned
                .as_ref()
                .is_some_and(|o| o.generation == generation && o.port.is_some())
        };
        let mut tick = tokio::time::interval(Duration::from_millis(250));
        let outcome = loop {
            tokio::select! {
                status = child.wait() => break Ok(status),
                _ = stop.cancelled() => break Err("stopped"),
                _ = tick.tick() => {
                    if !ready() && started.elapsed() > timeout {
                        break Err("timeout");
                    }
                }
            }
        };
        let was_ready = ready();
        if outcome.is_err() {
            tree.kill(&mut child).await;
        }
        let mut g = inner.lock();
        if g.owned.as_ref().is_some_and(|o| o.generation == generation) {
            g.owned = None;
        }
        let detail = last_err.lock().clone();
        g.last_error = match outcome {
            Err("stopped") => None,
            Err(_) => Some(format!(
                "dsh web did not report its address within {} s",
                timeout.as_secs()
            )),
            Ok(status) => {
                let code = status
                    .ok()
                    .and_then(|s| s.code())
                    .map_or_else(|| "?".to_string(), |c| c.to_string());
                let what = if was_ready {
                    "dsh web exited"
                } else {
                    "dsh web exited before it was ready"
                };
                Some(if detail.is_empty() {
                    format!("{what} (exit code {code})")
                } else {
                    format!("{what} (exit code {code}): {detail}")
                })
            }
        };
        if let Some(e) = &g.last_error {
            log::info!("[dsh-web] {e}");
        }
    });
    Ok(())
}

/// The child and everything it starts (dsh.exe → node on Windows).
struct ProcessTree {
    #[cfg(windows)]
    job: Option<job::Job>,
    #[cfg(unix)]
    pgid: Option<i32>,
}

impl ProcessTree {
    fn attach(child: &tokio::process::Child) -> Self {
        #[cfg(windows)]
        {
            Self {
                job: child.raw_handle().and_then(job::Job::assign),
            }
        }
        #[cfg(unix)]
        {
            Self {
                pgid: child.id().map(|p| p as i32),
            }
        }
        #[cfg(not(any(windows, unix)))]
        {
            let _ = child;
            Self {}
        }
    }

    async fn kill(self, child: &mut tokio::process::Child) {
        #[cfg(windows)]
        {
            if let Some(j) = &self.job {
                j.terminate();
            }
        }
        #[cfg(unix)]
        {
            if let Some(pg) = self.pgid {
                // SIGTERM lets node close its listener; the group (dsh → node) goes together.
                unsafe { libc::kill(-pg, libc::SIGTERM) };
                let _ = tokio::time::timeout(Duration::from_secs(3), child.wait()).await;
                // Whatever of the group is still there.
                unsafe { libc::kill(-pg, libc::SIGKILL) };
            }
        }
        let _ = child.start_kill();
        let _ = tokio::time::timeout(Duration::from_secs(3), child.wait()).await;
        drop(self);
    }
}

#[cfg(windows)]
mod job {
    use windows_sys::Win32::Foundation::{CloseHandle, HANDLE};
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
        SetInformationJobObject, TerminateJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };

    /// A job object that kills its processes when closed (also when SwitchTether dies).
    pub struct Job(HANDLE);

    // The handle is only used through the kernel's thread-safe job API.
    unsafe impl Send for Job {}
    unsafe impl Sync for Job {}

    impl Job {
        pub fn assign(process: std::os::windows::io::RawHandle) -> Option<Self> {
            unsafe {
                let h = CreateJobObjectW(std::ptr::null(), std::ptr::null());
                if h.is_null() {
                    return None;
                }
                let job = Job(h);
                let mut info = JOBOBJECT_EXTENDED_LIMIT_INFORMATION::default();
                info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
                let ok = SetInformationJobObject(
                    h,
                    JobObjectExtendedLimitInformation,
                    &info as *const _ as *const core::ffi::c_void,
                    std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
                );
                if ok == 0 || AssignProcessToJobObject(h, process as HANDLE) == 0 {
                    return None;
                }
                Some(job)
            }
        }

        pub fn terminate(&self) {
            unsafe { TerminateJobObject(self.0, 1) };
        }
    }

    impl Drop for Job {
        fn drop(&mut self) {
            unsafe { CloseHandle(self.0) };
        }
    }
}

/// Whether `port` on 127.0.0.1 answers like `dsh web` (401 + dsh's fixed text).
pub async fn probe(port: u16) -> bool {
    let check = async {
        let mut s = TcpStream::connect(("127.0.0.1", port)).await.ok()?;
        let req = format!(
            "GET / HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUser-Agent: SwitchTether\r\nAccept: text/plain\r\nConnection: close\r\n\r\n"
        );
        s.write_all(req.as_bytes()).await.ok()?;
        let mut buf = Vec::with_capacity(1024);
        let mut chunk = [0u8; 1024];
        while buf.len() < 4096 {
            let n = s.read(&mut chunk).await.ok()?;
            if n == 0 {
                break;
            }
            buf.extend_from_slice(&chunk[..n]);
            if looks_like_dsh_web(&buf) {
                return Some(true);
            }
        }
        Some(looks_like_dsh_web(&buf))
    };
    tokio::time::timeout(PROBE_TIMEOUT, check)
        .await
        .ok()
        .flatten()
        .unwrap_or(false)
}

fn looks_like_dsh_web(response: &[u8]) -> bool {
    let t = String::from_utf8_lossy(response);
    t.starts_with("HTTP/1.1 401") && t.contains(AUTH_REQUIRED_TEXT)
}

/// Whether a listener may be `dsh web`: a node / bun / dsh process where the OS names it; where
/// it does not, any port below the dynamic range (≥ 49152 holds many unnamed system listeners).
fn may_be_dsh_web(p: &crate::forward::ListeningPort) -> bool {
    match p.process.as_deref() {
        Some(n) => {
            let stem = n
                .rsplit_once('.')
                .filter(|(_, ext)| ext.eq_ignore_ascii_case("exe"))
                .map_or(n, |(s, _)| s);
            DSH_PROCESSES.iter().any(|d| d.eq_ignore_ascii_case(stem))
        }
        None => p.port < 49152,
    }
}

/// A `dsh web` someone else started: 3080 first, then candidate listeners (probed together).
pub async fn find_running(exclude: &[u16]) -> Option<u16> {
    let listed = tokio::task::spawn_blocking(crate::forward::all_listening_ports)
        .await
        .unwrap_or_default();
    let mut ports: Vec<u16> = vec![DEFAULT_PORT];
    let mut rest: Vec<u16> = listed
        .into_iter()
        .filter(|p| p.port != 0 && may_be_dsh_web(p))
        .map(|p| p.port)
        .collect();
    rest.sort_unstable();
    rest.dedup();
    rest.retain(|p| *p != DEFAULT_PORT);
    ports.extend(rest);
    ports.retain(|p| !exclude.contains(p));
    ports.truncate(64);
    let results = futures::future::join_all(ports.iter().map(|&p| probe(p))).await;
    ports
        .into_iter()
        .zip(results)
        .find_map(|(p, ok)| ok.then_some(p))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;

    #[test]
    fn url_line_gives_port_and_token() {
        assert_eq!(
            parse_url_line("dsh web: http://127.0.0.1:52166/?token=EXAMPLE-tok_1"),
            Some((52166, Some("EXAMPLE-tok_1".into())))
        );
        assert_eq!(
            parse_url_line("dsh web: http://[::1]:3080/"),
            Some((3080, None))
        );
        assert_eq!(parse_url_line("dsh web: opening the default browser"), None);
        assert_eq!(parse_url_line("something else"), None);
        // Odd characters are not passed on as a token.
        assert_eq!(
            parse_url_line("dsh web: http://127.0.0.1:1/?token=a\"b"),
            Some((1, None))
        );
    }

    #[test]
    fn status_matches_the_shared_fixture() {
        let dir =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../protocol/fixtures");
        let f: serde_json::Value = serde_json::from_str(
            &std::fs::read_to_string(dir.join("dsh_web_status.json")).unwrap(),
        )
        .unwrap();
        let running = DshWebStatus {
            state: "running",
            port: Some(52166),
            owned: true,
            started_at_ms: Some(1763452800000),
            error: None,
        };
        assert_eq!(serde_json::to_value(&running).unwrap(), f["running"]);
        let stopped = DshWebStatus {
            state: "stopped",
            port: None,
            owned: false,
            started_at_ms: None,
            error: Some("dsh web exited before it was ready (exit code 1)".into()),
        };
        assert_eq!(serde_json::to_value(&stopped).unwrap(), f["stopped"]);
    }

    async fn fake_server(response: &'static str) -> u16 {
        let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = l.local_addr().unwrap().port();
        tokio::spawn(async move {
            while let Ok((mut s, _)) = l.accept().await {
                let mut b = [0u8; 1024];
                let _ = s.read(&mut b).await;
                let _ = s.write_all(response.as_bytes()).await;
            }
        });
        port
    }

    #[tokio::test]
    async fn probe_recognises_dsh_web_only() {
        let dsh = fake_server("HTTP/1.1 401 Unauthorized\r\ncontent-type: text/plain; charset=utf-8\r\nTransfer-Encoding: chunked\r\n\r\n44\r\ndsh web authentication required; reopen the URL printed by dsh web.\n\r\n0\r\n\r\n").await;
        let other =
            fake_server("HTTP/1.1 401 Unauthorized\r\nContent-Length: 5\r\n\r\nnope\n").await;
        assert!(probe(dsh).await);
        assert!(!probe(other).await);
        let closed = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = closed.local_addr().unwrap().port();
        drop(closed);
        assert!(!probe(port).await);
    }

    /// A program that prints dsh's URL line and keeps running (stands in for `dsh web`).
    pub(crate) fn fake_launcher(line: &str) -> Launcher {
        let (prog, args): (&str, Vec<String>) = if cfg!(windows) {
            (
                "cmd",
                vec![
                    "/C".into(),
                    format!("echo {line}& ping -n 60 127.0.0.1 >NUL"),
                ],
            )
        } else {
            ("sh", vec!["-c".into(), format!("echo '{line}'; sleep 60")])
        };
        let path = crate::util::which(prog).unwrap_or_else(|| PathBuf::from(prog));
        Launcher {
            resolve: Arc::new(move || Some(path.clone())),
            args,
            env: Vec::new(),
            discover: false,
            start_timeout: Duration::from_secs(20),
        }
    }

    async fn wait_for(web: &DshWeb, state: &str) -> DshWebStatus {
        for _ in 0..200 {
            let s = web.status(&[]).await;
            if s.state == state {
                return s;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        panic!("never reached {state}: {:?}", web.status(&[]).await);
    }

    #[tokio::test]
    async fn owned_instance_starts_reports_and_stops() {
        let web = DshWeb::new(fake_launcher(
            "dsh web: http://127.0.0.1:40123/?token=Tok_en-1",
        ));
        let tmp = std::env::temp_dir();
        let first = web.start(tmp.clone(), &[]).await;
        assert!(matches!(first.state, "starting" | "running"), "{first:?}");
        let running = wait_for(&web, "running").await;
        assert_eq!(running.port, Some(40123));
        assert!(running.owned);
        assert_eq!(web.launch_path().as_deref(), Some("/?token=Tok_en-1"));
        // Starting again reuses it.
        assert_eq!(web.start(tmp, &[]).await.port, Some(40123));
        assert!(web.stop());
        assert!(!web.stop(), "nothing left to stop");
        let s = web.status(&[]).await;
        assert_eq!(s.state, "stopped");
        assert_eq!(s.error, None);
        assert_eq!(web.launch_path(), None);
    }

    #[tokio::test]
    async fn early_exit_and_missing_binary_are_reported() {
        let mut l = fake_launcher("x");
        l.args = if cfg!(windows) {
            vec!["/C".into(), "echo boom 1>&2& exit 3".into()]
        } else {
            vec!["-c".into(), "echo boom >&2; exit 3".into()]
        };
        let web = DshWeb::new(l);
        web.start(std::env::temp_dir(), &[]).await;
        let mut s = web.status(&[]).await;
        for _ in 0..200 {
            if s.state == "stopped" {
                break;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
            s = web.status(&[]).await;
        }
        assert_eq!(s.state, "stopped");
        let e = s.error.unwrap();
        assert!(
            e.contains("before it was ready") && e.contains("boom"),
            "{e}"
        );

        let mut none = fake_launcher("x");
        none.resolve = Arc::new(|| None);
        let web = DshWeb::new(none);
        assert_eq!(web.status(&[]).await.state, "not_installed");
        assert_eq!(
            web.start(std::env::temp_dir(), &[]).await.state,
            "not_installed"
        );
    }

    /// Real `dsh web` in a sandbox: `SWITCHTETHER_DSH_SANDBOX=<empty dir> cargo test -p
    /// switchtether-core real_dsh_web -- --ignored`. Never uses the user's `DSH_HOME`.
    #[tokio::test]
    #[ignore]
    async fn real_dsh_web_in_a_sandbox() {
        let Ok(sandbox) = std::env::var("SWITCHTETHER_DSH_SANDBOX") else {
            return;
        };
        let mut l = Launcher {
            discover: false,
            ..Launcher::default()
        };
        l.env.push(("DSH_HOME".into(), sandbox.clone()));
        let web = DshWeb::new(l);
        web.start(PathBuf::from(&sandbox), &[]).await;
        let mut s = web.status(&[]).await;
        for _ in 0..1200 {
            if s.state != "starting" {
                break;
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
            s = web.status(&[]).await;
        }
        assert_eq!(s.state, "running", "{s:?}");
        let port = s.port.unwrap();
        assert!(probe(port).await, "dsh web answers like dsh web");
        assert_eq!(
            find_running(&[DEFAULT_PORT]).await,
            Some(port),
            "found like a dsh web the user started"
        );
        assert!(web.launch_path().unwrap().starts_with("/?token="));
        assert!(web.stop());
        tokio::time::sleep(Duration::from_secs(3)).await;
        assert!(!probe(port).await, "the whole process tree is gone");
    }
}
