//! Port forwarding (docs/PROTOCOL.md §7.11): the phone reaches a TCP port that listens on the
//! desktop's loopback interface, like `ssh -L`.
//!
//! - A **lease** (`POST /v1/forwards`, step-up `port_forward`) authorises one paired device to
//!   reach one desktop port until it expires (default 2 h, at most 12 h) or is stopped. Leases live
//!   in memory only; a desktop restart or a revoked device ends them.
//! - One WebSocket per lease (`GET /v1/forwards/{id}/tunnel`) carries every TCP connection the
//!   phone opens to that port, multiplexed with small binary frames and per-stream flow control.
//!   One tunnel per forward keeps the relay usable (it allows 32 tunnels per desktop) and avoids a
//!   TLS handshake per browser connection.
//! - The desktop only ever connects to `127.0.0.1:<port>` (then `[::1]:<port>`); the target
//!   address is not a parameter, so a lease cannot be used to reach other machines.
//!
//! Frame layout (one binary WebSocket message each):
//! `[type: u8][stream: u32 big-endian][payload]`, see [`FrameType`].

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use serde::Serialize;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, Semaphore};
use tokio_util::sync::CancellationToken;

/// Default and maximum lease lifetimes.
pub const DEFAULT_TTL: Duration = Duration::from_secs(2 * 60 * 60);
pub const MAX_TTL: Duration = Duration::from_secs(12 * 60 * 60);
/// Leases per device (each one is a port).
pub const MAX_LEASES_PER_DEVICE: usize = 8;
/// Concurrent TCP connections inside one tunnel.
pub const MAX_STREAMS: usize = 128;
/// Bytes each side may send on a stream before the peer returns credit with `WINDOW`.
pub const INITIAL_WINDOW: usize = 256 * 1024;
/// Largest `DATA` payload.
pub const MAX_DATA: usize = 32 * 1024;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

/// Mux frame types (first byte of every binary message).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum FrameType {
    /// phone → desktop: open stream `id` to the lease's port. The phone may send `DATA` right
    /// after it, without waiting for `OPENED` (saves a round trip over the relay).
    Open = 1,
    /// desktop → phone: the desktop connected to the port.
    Opened = 2,
    /// Bytes for the stream (≤ [`MAX_DATA`]), counted against the receiver's window.
    Data = 3,
    /// The sender will send no more data on this stream (half-close).
    Fin = 4,
    /// Abort the stream in both directions. Payload: ASCII reason (`refused`, `limit`,
    /// `window`, `error`, `closed`).
    Reset = 5,
    /// Payload: u32 big-endian number of bytes the sender has consumed (more credit).
    Window = 6,
}

impl FrameType {
    fn from_u8(v: u8) -> Option<Self> {
        Some(match v {
            1 => Self::Open,
            2 => Self::Opened,
            3 => Self::Data,
            4 => Self::Fin,
            5 => Self::Reset,
            6 => Self::Window,
            _ => return None,
        })
    }
}

pub fn encode(kind: FrameType, stream: u32, payload: &[u8]) -> Vec<u8> {
    let mut v = Vec::with_capacity(5 + payload.len());
    v.push(kind as u8);
    v.extend_from_slice(&stream.to_be_bytes());
    v.extend_from_slice(payload);
    v
}

pub fn decode(frame: &[u8]) -> Option<(FrameType, u32, &[u8])> {
    if frame.len() < 5 {
        return None;
    }
    let kind = FrameType::from_u8(frame[0])?;
    let stream = u32::from_be_bytes([frame[1], frame[2], frame[3], frame[4]]);
    Some((kind, stream, &frame[5..]))
}

/// One authorised forward.
pub struct Lease {
    pub id: String,
    pub device_id: String,
    pub port: u16,
    pub created_at_ms: i64,
    expires_at_ms: Mutex<i64>,
    /// Ends the lease (stop, expiry, revocation): closes its tunnel.
    pub cancel: CancellationToken,
    /// The tunnel currently serving this lease; a new tunnel replaces (cancels) the old one.
    tunnel: Mutex<Option<CancellationToken>>,
    pub active_streams: AtomicUsize,
    pub total_streams: AtomicU64,
    /// Bytes desktop → phone / phone → desktop.
    pub bytes_down: AtomicU64,
    pub bytes_up: AtomicU64,
}

impl Lease {
    pub fn expires_at_ms(&self) -> i64 {
        *self.expires_at_ms.lock()
    }

    pub fn expired(&self) -> bool {
        crate::util::now_ms() >= self.expires_at_ms()
    }

    /// Registers a new tunnel for this lease and returns its token (child of the lease's token).
    /// A previous tunnel (e.g. half-dead after the phone changed networks) is closed.
    pub fn take_tunnel(&self) -> CancellationToken {
        let token = self.cancel.child_token();
        if let Some(old) = self.tunnel.lock().replace(token.clone()) {
            old.cancel();
        }
        token
    }

    pub fn view(&self) -> LeaseView {
        LeaseView {
            id: self.id.clone(),
            port: self.port,
            created_at_ms: self.created_at_ms,
            expires_at_ms: self.expires_at_ms(),
            connected: self
                .tunnel
                .lock()
                .as_ref()
                .is_some_and(|t| !t.is_cancelled()),
            active_streams: self.active_streams.load(Ordering::Relaxed),
            total_streams: self.total_streams.load(Ordering::Relaxed),
            bytes_down: self.bytes_down.load(Ordering::Relaxed),
            bytes_up: self.bytes_up.load(Ordering::Relaxed),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LeaseView {
    pub id: String,
    pub port: u16,
    pub created_at_ms: i64,
    pub expires_at_ms: i64,
    /// A tunnel is open for this lease right now.
    pub connected: bool,
    pub active_streams: usize,
    pub total_streams: u64,
    pub bytes_down: u64,
    pub bytes_up: u64,
}

/// Desktop-wide view for the settings page (which device forwards which port).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LeaseSummary {
    pub device_id: String,
    #[serde(flatten)]
    pub lease: LeaseView,
}

#[derive(Debug)]
pub enum LeaseError {
    TooMany,
}

#[derive(Default)]
pub struct ForwardManager {
    leases: Mutex<HashMap<String, Arc<Lease>>>,
}

impl ForwardManager {
    fn prune(map: &mut HashMap<String, Arc<Lease>>) {
        map.retain(|_, l| {
            if l.expired() {
                l.cancel.cancel();
                false
            } else {
                true
            }
        });
    }

    /// Creates a lease, or renews the device's existing lease for the same port.
    pub fn create(
        &self,
        device_id: &str,
        port: u16,
        ttl: Duration,
    ) -> Result<(Arc<Lease>, bool), LeaseError> {
        let ttl = ttl.min(MAX_TTL);
        let expires = crate::util::now_ms() + ttl.as_millis() as i64;
        let mut map = self.leases.lock();
        Self::prune(&mut map);
        if let Some(l) = map
            .values()
            .find(|l| l.device_id == device_id && l.port == port)
        {
            *l.expires_at_ms.lock() = expires;
            return Ok((l.clone(), false));
        }
        if map.values().filter(|l| l.device_id == device_id).count() >= MAX_LEASES_PER_DEVICE {
            return Err(LeaseError::TooMany);
        }
        let lease = Arc::new(Lease {
            id: crate::util::new_id(),
            device_id: device_id.to_string(),
            port,
            created_at_ms: crate::util::now_ms(),
            expires_at_ms: Mutex::new(expires),
            cancel: CancellationToken::new(),
            tunnel: Mutex::new(None),
            active_streams: AtomicUsize::new(0),
            total_streams: AtomicU64::new(0),
            bytes_down: AtomicU64::new(0),
            bytes_up: AtomicU64::new(0),
        });
        map.insert(lease.id.clone(), lease.clone());
        Ok((lease, true))
    }

    /// The device's live lease `id`.
    pub fn get(&self, device_id: &str, id: &str) -> Option<Arc<Lease>> {
        let mut map = self.leases.lock();
        Self::prune(&mut map);
        map.get(id).filter(|l| l.device_id == device_id).cloned()
    }

    pub fn list(&self, device_id: &str) -> Vec<LeaseView> {
        let mut map = self.leases.lock();
        Self::prune(&mut map);
        let mut v: Vec<LeaseView> = map
            .values()
            .filter(|l| l.device_id == device_id)
            .map(|l| l.view())
            .collect();
        v.sort_by_key(|l| l.port);
        v
    }

    pub fn list_all(&self) -> Vec<LeaseSummary> {
        let mut map = self.leases.lock();
        Self::prune(&mut map);
        let mut v: Vec<LeaseSummary> = map
            .values()
            .map(|l| LeaseSummary {
                device_id: l.device_id.clone(),
                lease: l.view(),
            })
            .collect();
        v.sort_by_key(|l| l.lease.port);
        v
    }

    /// Stops a lease (`device_id` None: the desktop user stops any lease).
    pub fn remove(&self, device_id: Option<&str>, id: &str) -> Option<Arc<Lease>> {
        let mut map = self.leases.lock();
        let ok = map
            .get(id)
            .is_some_and(|l| device_id.is_none_or(|d| l.device_id == d));
        if !ok {
            return None;
        }
        let l = map.remove(id)?;
        l.cancel.cancel();
        Some(l)
    }

    /// Ends every lease of a device (revocation / unpair).
    pub fn release_device(&self, device_id: &str) -> usize {
        let mut map = self.leases.lock();
        let before = map.len();
        map.retain(|_, l| {
            if l.device_id == device_id {
                l.cancel.cancel();
                false
            } else {
                true
            }
        });
        before - map.len()
    }

    /// Ends every lease (server stop).
    pub fn clear(&self) {
        for (_, l) in self.leases.lock().drain() {
            l.cancel.cancel();
        }
    }
}

/// Why a tunnel ended.
#[derive(Debug, PartialEq, Eq)]
pub enum TunnelEnd {
    /// The phone closed the WebSocket.
    PeerClosed,
    /// The lease was stopped, expired, or replaced by a newer tunnel.
    Cancelled,
    /// A frame violated the protocol.
    Protocol,
}

enum Inbound {
    Data(Vec<u8>),
    Fin,
}

struct StreamHandle {
    tx: mpsc::UnboundedSender<Inbound>,
    /// Desktop → phone credit (bytes the phone can still buffer).
    credit: Arc<Semaphore>,
    /// Phone → desktop bytes received but not yet written to the port.
    queued: Arc<AtomicUsize>,
    task: tokio::task::AbortHandle,
}

/// Runs the desktop side of one tunnel. `incoming` yields binary frames from the phone (None when
/// the WebSocket closed); every frame for the phone is sent on `outgoing`. Returns when the phone
/// disconnects, `cancel` fires, or the phone violates the protocol.
pub async fn serve_tunnel(
    lease: Arc<Lease>,
    mut incoming: mpsc::Receiver<Vec<u8>>,
    outgoing: mpsc::Sender<Vec<u8>>,
    cancel: CancellationToken,
) -> TunnelEnd {
    serve_tunnel_to(
        lease,
        &loopback_targets_for,
        &mut incoming,
        outgoing,
        cancel,
    )
    .await
}

fn loopback_targets_for(port: u16) -> Vec<SocketAddr> {
    vec![
        SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port),
        SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), port),
    ]
}

async fn serve_tunnel_to(
    lease: Arc<Lease>,
    targets: &(dyn Fn(u16) -> Vec<SocketAddr> + Sync),
    incoming: &mut mpsc::Receiver<Vec<u8>>,
    outgoing: mpsc::Sender<Vec<u8>>,
    cancel: CancellationToken,
) -> TunnelEnd {
    let mut streams: HashMap<u32, StreamHandle> = HashMap::new();
    let (done_tx, mut done_rx) = mpsc::unbounded_channel::<u32>();
    let mut expiry = tokio::time::interval(Duration::from_secs(5));
    let end = loop {
        tokio::select! {
            _ = cancel.cancelled() => break TunnelEnd::Cancelled,
            _ = expiry.tick() => {
                if lease.expired() {
                    lease.cancel.cancel();
                    break TunnelEnd::Cancelled;
                }
            }
            Some(id) = done_rx.recv() => {
                if streams.remove(&id).is_some() {
                    lease.active_streams.fetch_sub(1, Ordering::Relaxed);
                }
            }
            frame = incoming.recv() => {
                let Some(frame) = frame else { break TunnelEnd::PeerClosed };
                let Some((kind, id, payload)) = decode(&frame) else { break TunnelEnd::Protocol };
                match kind {
                    FrameType::Open => {
                        if id == 0 || streams.contains_key(&id) {
                            break TunnelEnd::Protocol;
                        }
                        if streams.len() >= MAX_STREAMS {
                            let _ = outgoing.send(encode(FrameType::Reset, id, b"limit")).await;
                            continue;
                        }
                        let (tx, rx) = mpsc::unbounded_channel();
                        let credit = Arc::new(Semaphore::new(INITIAL_WINDOW));
                        let queued = Arc::new(AtomicUsize::new(0));
                        let task = tokio::spawn(run_stream(
                            lease.clone(),
                            id,
                            targets(lease.port),
                            rx,
                            credit.clone(),
                            queued.clone(),
                            outgoing.clone(),
                            done_tx.clone(),
                        ));
                        lease.active_streams.fetch_add(1, Ordering::Relaxed);
                        lease.total_streams.fetch_add(1, Ordering::Relaxed);
                        streams.insert(id, StreamHandle { tx, credit, queued, task: task.abort_handle() });
                    }
                    FrameType::Data => {
                        if payload.len() > MAX_DATA {
                            break TunnelEnd::Protocol;
                        }
                        let over = {
                            let Some(s) = streams.get(&id) else { continue };
                            let after = s.queued.fetch_add(payload.len(), Ordering::AcqRel) + payload.len();
                            if after <= INITIAL_WINDOW {
                                lease.bytes_up.fetch_add(payload.len() as u64, Ordering::Relaxed);
                                let _ = s.tx.send(Inbound::Data(payload.to_vec()));
                            }
                            after > INITIAL_WINDOW
                        };
                        if over {
                            close_stream(&mut streams, &lease, id);
                            let _ = outgoing.send(encode(FrameType::Reset, id, b"window")).await;
                        }
                    }
                    FrameType::Fin => {
                        if let Some(s) = streams.get(&id) {
                            let _ = s.tx.send(Inbound::Fin);
                        }
                    }
                    FrameType::Reset => close_stream(&mut streams, &lease, id),
                    FrameType::Window => {
                        let Some(s) = streams.get(&id) else { continue };
                        if payload.len() != 4 {
                            break TunnelEnd::Protocol;
                        }
                        let inc = u32::from_be_bytes([payload[0], payload[1], payload[2], payload[3]]) as usize;
                        // Never more outstanding credit than the initial window.
                        let room = INITIAL_WINDOW.saturating_sub(s.credit.available_permits());
                        s.credit.add_permits(inc.min(room));
                    }
                    FrameType::Opened => break TunnelEnd::Protocol,
                }
            }
        }
    };
    for (_, s) in streams.drain() {
        s.task.abort();
        lease.active_streams.fetch_sub(1, Ordering::Relaxed);
    }
    end
}

fn close_stream(streams: &mut HashMap<u32, StreamHandle>, lease: &Lease, id: u32) {
    if let Some(s) = streams.remove(&id) {
        s.task.abort();
        lease.active_streams.fetch_sub(1, Ordering::Relaxed);
    }
}

/// Tries every address at once (a dev server may listen on `::1` only, and a refused connect to
/// `127.0.0.1` takes about 2 s on Windows); the first success wins.
async fn connect_any(targets: &[SocketAddr]) -> Option<TcpStream> {
    if targets.is_empty() {
        return None;
    }
    let attempts = targets.iter().map(|a| {
        let a = *a;
        Box::pin(async move {
            match tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(a)).await {
                Ok(Ok(s)) => Ok(s),
                _ => Err(()),
            }
        })
    });
    let (s, _) = futures::future::select_ok(attempts).await.ok()?;
    let _ = s.set_nodelay(true);
    Some(s)
}

#[allow(clippy::too_many_arguments)]
async fn run_stream(
    lease: Arc<Lease>,
    id: u32,
    targets: Vec<SocketAddr>,
    mut rx: mpsc::UnboundedReceiver<Inbound>,
    credit: Arc<Semaphore>,
    queued: Arc<AtomicUsize>,
    out: mpsc::Sender<Vec<u8>>,
    done: mpsc::UnboundedSender<u32>,
) {
    let Some(sock) = connect_any(&targets).await else {
        let _ = out.send(encode(FrameType::Reset, id, b"refused")).await;
        let _ = done.send(id);
        return;
    };
    if out.send(encode(FrameType::Opened, id, &[])).await.is_err() {
        let _ = done.send(id);
        return;
    }
    let (mut rd, mut wr) = sock.into_split();
    let out_w = out.clone();
    let writer = async move {
        while let Some(m) = rx.recv().await {
            match m {
                Inbound::Data(bytes) => {
                    wr.write_all(&bytes).await?;
                    queued.fetch_sub(bytes.len(), Ordering::AcqRel);
                    let inc = (bytes.len() as u32).to_be_bytes();
                    if out_w
                        .send(encode(FrameType::Window, id, &inc))
                        .await
                        .is_err()
                    {
                        break;
                    }
                }
                Inbound::Fin => {
                    let _ = wr.shutdown().await;
                    // Keep draining nothing: a FIN is final for this direction.
                    break;
                }
            }
        }
        Ok::<(), std::io::Error>(())
    };
    let lease_r = lease.clone();
    let out_r = out.clone();
    let reader = async move {
        let mut buf = vec![0u8; MAX_DATA];
        loop {
            let n = rd.read(&mut buf).await?;
            if n == 0 {
                let _ = out_r.send(encode(FrameType::Fin, id, &[])).await;
                return Ok::<(), std::io::Error>(());
            }
            match credit.acquire_many(n as u32).await {
                Ok(p) => p.forget(),
                Err(_) => return Ok(()),
            }
            lease_r.bytes_down.fetch_add(n as u64, Ordering::Relaxed);
            if out_r
                .send(encode(FrameType::Data, id, &buf[..n]))
                .await
                .is_err()
            {
                return Ok(());
            }
        }
    };
    let (w, r) = tokio::join!(writer, reader);
    if w.is_err() || r.is_err() {
        let _ = out.send(encode(FrameType::Reset, id, b"error")).await;
    }
    let _ = done.send(id);
}

/// A TCP port listening on the desktop that a forward can reach (loopback or all interfaces).
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ListeningPort {
    pub port: u16,
    /// Executable name of the listening process, where the OS tells (Windows, macOS).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub process: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pid: Option<u32>,
}

/// Ports commonly used by development servers; probed where the OS listing is unavailable.
pub const COMMON_DEV_PORTS: &[u16] = &[
    80, 1313, 3000, 3001, 3002, 4000, 4173, 4200, 4321, 5000, 5001, 5173, 5174, 5500, 6006, 7860,
    8000, 8001, 8080, 8081, 8088, 8501, 8787, 8888, 9000, 9090, 19006,
];

/// Background services that are never what the user wants to preview.
const SYSTEM_PROCESSES: &[&str] = &[
    "system",
    "svchost.exe",
    "lsass.exe",
    "services.exe",
    "wininit.exe",
    "spoolsv.exe",
    "smss.exe",
    "csrss.exe",
    // macOS: AirPlay Receiver (ControlCenter) holds *:5000 and *:7000; the others are
    // Continuity / Handoff and system daemons.
    "ControlCenter",
    "rapportd",
    "sharingd",
    "launchd",
    "mDNSResponder",
    "identityservicesd",
];

/// Every listener the OS reports on loopback or the any-address, unfiltered (common development
/// ports probed where the OS listing is unavailable).
pub fn all_listening_ports() -> Vec<ListeningPort> {
    os_listening_ports().unwrap_or_else(probe_common_ports)
}

/// Listening TCP ports reachable on loopback, excluding `exclude` (the Remote server itself),
/// system services and the dynamic range (≥ 49152, where Windows RPC listeners live).
pub fn listening_ports(exclude: &[u16]) -> Vec<ListeningPort> {
    let mut v = all_listening_ports();
    // On Windows a process name is known for everything the user runs; a nameless listener is
    // a protected system service (DNS, RPC, …). Below 1024 those are never development servers.
    let os_named = cfg!(windows);
    v.retain(|p| {
        p.port != 0
            && p.port < 49152
            && !exclude.contains(&p.port)
            && !(os_named && p.port < 1024 && p.process.is_none())
            && !p
                .process
                .as_deref()
                .is_some_and(|n| SYSTEM_PROCESSES.iter().any(|s| s.eq_ignore_ascii_case(n)))
    });
    v.sort_by_key(|p| p.port);
    v.dedup_by_key(|p| p.port);
    // Likely development servers first (stable: by port within each group).
    v.sort_by_key(|p| !looks_like_dev_server(p));
    v
}

/// Runtimes and tools that usually serve what a developer wants to preview.
const DEV_PROCESSES: &[&str] = &[
    "node",
    "bun",
    "deno",
    "python",
    "python3",
    "pythonw",
    "py",
    "uvicorn",
    "gunicorn",
    "java",
    "ruby",
    "php",
    "dotnet",
    "go",
    "hugo",
    "caddy",
    "nginx",
    "httpd",
    "ollama",
    "jupyter",
    "com.docker.backend",
    "wslrelay",
    "vite",
    "esbuild",
];

fn looks_like_dev_server(p: &ListeningPort) -> bool {
    if let Some(name) = p.process.as_deref() {
        let stem = name
            .rsplit_once('.')
            .filter(|(_, ext)| ext.eq_ignore_ascii_case("exe"))
            .map_or(name, |(s, _)| s);
        if DEV_PROCESSES.iter().any(|d| d.eq_ignore_ascii_case(stem)) {
            return true;
        }
    }
    COMMON_DEV_PORTS.contains(&p.port)
}

/// Whether something accepts connections on the desktop's loopback at `port` (blocking).
pub fn is_listening(port: u16) -> bool {
    probe(port, Duration::from_millis(300))
}

fn probe(port: u16, timeout: Duration) -> bool {
    loopback_targets_for(port)
        .iter()
        .any(|a| std::net::TcpStream::connect_timeout(a, timeout).is_ok())
}

fn probe_common_ports() -> Vec<ListeningPort> {
    COMMON_DEV_PORTS
        .iter()
        .filter(|&&port| probe(port, Duration::from_millis(150)))
        .map(|&port| ListeningPort {
            port,
            process: None,
            pid: None,
        })
        .collect()
}

#[cfg(target_os = "linux")]
fn os_listening_ports() -> Option<Vec<ListeningPort>> {
    let mut out = Vec::new();
    let mut any = false;
    for file in ["/proc/net/tcp", "/proc/net/tcp6"] {
        let Ok(text) = std::fs::read_to_string(file) else {
            continue;
        };
        any = true;
        out.extend(parse_proc_net_tcp(&text));
    }
    any.then_some(out)
}

/// Parses `/proc/net/tcp{,6}`: listeners (state `0A`) on loopback or the any-address.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
fn parse_proc_net_tcp(text: &str) -> Vec<ListeningPort> {
    let mut out = Vec::new();
    for line in text.lines().skip(1) {
        let cols: Vec<&str> = line.split_whitespace().collect();
        if cols.len() < 4 || cols[3] != "0A" {
            continue;
        }
        let Some((addr, port)) = cols[1].split_once(':') else {
            continue;
        };
        let Ok(port) = u16::from_str_radix(port, 16) else {
            continue;
        };
        let reachable = match addr.len() {
            // IPv4 in host (little-endian) byte order: 127.x.x.x ends with 7F.
            8 => addr == "00000000" || addr.ends_with("7F"),
            32 => {
                addr.chars().all(|c| c == '0')
                    || addr == "00000000000000000000000001000000"
                    // ::ffff:127.0.0.1 / ::ffff:0.0.0.0
                    || (addr.starts_with("0000000000000000FFFF0000")
                        && (addr.ends_with("7F") || addr.ends_with("00000000")))
            }
            _ => false,
        };
        if reachable {
            out.push(ListeningPort {
                port,
                process: None,
                pid: None,
            });
        }
    }
    out
}

#[cfg(windows)]
fn os_listening_ports() -> Option<Vec<ListeningPort>> {
    use windows_sys::Win32::NetworkManagement::IpHelper::{
        GetExtendedTcpTable, MIB_TCP6ROW_OWNER_PID, MIB_TCPROW_OWNER_PID,
        TCP_TABLE_OWNER_PID_LISTENER,
    };
    use windows_sys::Win32::Networking::WinSock::{AF_INET, AF_INET6};

    fn table(af: u16) -> Option<Vec<u8>> {
        let mut size: u32 = 0;
        // SAFETY: size query with a null buffer, as documented.
        unsafe {
            GetExtendedTcpTable(
                std::ptr::null_mut(),
                &mut size,
                0,
                af as u32,
                TCP_TABLE_OWNER_PID_LISTENER,
                0,
            )
        };
        for _ in 0..3 {
            let mut buf = vec![0u8; size as usize + 4096];
            let mut len = buf.len() as u32;
            // SAFETY: `buf` is `len` bytes long.
            let rc = unsafe {
                GetExtendedTcpTable(
                    buf.as_mut_ptr().cast(),
                    &mut len,
                    0,
                    af as u32,
                    TCP_TABLE_OWNER_PID_LISTENER,
                    0,
                )
            };
            if rc == 0 {
                return Some(buf);
            }
            size = len;
        }
        None
    }

    fn rows<T: Copy>(buf: &[u8]) -> Vec<T> {
        if buf.len() < 4 {
            return Vec::new();
        }
        let n = u32::from_ne_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
        // Rows start after dwNumEntries, aligned to the row's alignment (4 for both row types).
        let start = 4usize.max(std::mem::align_of::<T>());
        let size = std::mem::size_of::<T>();
        (0..n)
            .filter_map(|i| {
                let off = start + i * size;
                (off + size <= buf.len()).then(|| {
                    // SAFETY: bounds checked; read_unaligned copes with any alignment.
                    unsafe { std::ptr::read_unaligned(buf[off..].as_ptr().cast::<T>()) }
                })
            })
            .collect()
    }

    let port_of = |dw: u32| u16::from_be(dw as u16);
    let mut out: Vec<ListeningPort> = Vec::new();
    let mut names: HashMap<u32, Option<String>> = HashMap::new();
    let mut push = |port: u16, pid: u32, out: &mut Vec<ListeningPort>| {
        if pid == 0 || pid == 4 {
            out.push(ListeningPort {
                port,
                process: Some("System".into()),
                pid: Some(pid),
            });
            return;
        }
        let name = names
            .entry(pid)
            .or_insert_with(|| process_name(pid))
            .clone();
        out.push(ListeningPort {
            port,
            process: name,
            pid: Some(pid),
        });
    };
    let v4 = table(AF_INET)?;
    for r in rows::<MIB_TCPROW_OWNER_PID>(&v4) {
        let ip = Ipv4Addr::from(u32::from_be(r.dwLocalAddr));
        if ip.is_unspecified() || ip.is_loopback() {
            push(port_of(r.dwLocalPort), r.dwOwningPid, &mut out);
        }
    }
    if let Some(v6) = table(AF_INET6) {
        for r in rows::<MIB_TCP6ROW_OWNER_PID>(&v6) {
            let ip = Ipv6Addr::from(r.ucLocalAddr);
            if ip.is_unspecified() || ip.is_loopback() {
                push(port_of(r.dwLocalPort), r.dwOwningPid, &mut out);
            }
        }
    }
    Some(out)
}

#[cfg(windows)]
fn process_name(pid: u32) -> Option<String> {
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::Threading::{
        OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION,
    };
    // SAFETY: plain Win32 calls; the handle is closed below.
    unsafe {
        let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if h.is_null() {
            return None;
        }
        let mut buf = [0u16; 1024];
        let mut len = buf.len() as u32;
        let ok = QueryFullProcessImageNameW(h, 0, buf.as_mut_ptr(), &mut len);
        CloseHandle(h);
        if ok == 0 {
            return None;
        }
        let path = String::from_utf16_lossy(&buf[..len as usize]);
        path.rsplit(['\\', '/'])
            .next()
            .map(|s| s.to_string())
            .filter(|s| !s.is_empty())
    }
}

/// macOS: `lsof` (part of the base system) lists the TCP listeners of the user's own
/// processes with pid and command name. System daemons owned by other users are not visible
/// without root, which suits a list of development servers.
#[cfg(target_os = "macos")]
fn os_listening_ports() -> Option<Vec<ListeningPort>> {
    let mut cmd = std::process::Command::new("/usr/sbin/lsof");
    // -nP: numeric hosts and ports; +c 0: full command names; -F pcn: machine-readable
    // pid / command / name fields; -w: no warnings.
    cmd.args(["-nP", "-w", "+c", "0", "-iTCP", "-sTCP:LISTEN", "-F", "pcn"])
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null());
    let out = cmd.output().ok()?;
    match out.status.code() {
        Some(0) => Some(parse_lsof_listeners(&String::from_utf8_lossy(&out.stdout))),
        // lsof exits 1 when nothing matches: an empty list, not a failure.
        Some(1) if out.stdout.is_empty() => Some(Vec::new()),
        _ => None,
    }
}

/// Parses `lsof -F pcn` output: `p<pid>` starts a process, `c<command>` names it, every
/// `n<address>:<port>` is one listening socket. Keeps loopback and any-address listeners.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
fn parse_lsof_listeners(text: &str) -> Vec<ListeningPort> {
    let mut out = Vec::new();
    let mut pid: Option<u32> = None;
    let mut command: Option<String> = None;
    for line in text.lines() {
        let Some(tag) = line.chars().next() else {
            continue;
        };
        let value = &line[tag.len_utf8()..];
        match tag {
            'p' => {
                pid = value.trim().parse().ok();
                command = None;
            }
            'c' => command = Some(value.to_string()).filter(|c| !c.is_empty()),
            'n' => {
                // `*:3000`, `127.0.0.1:5173`, `[::1]:8080`, `[::]:80`; never `a->b` for a listener.
                if value.contains("->") {
                    continue;
                }
                let Some((addr, port)) = value.rsplit_once(':') else {
                    continue;
                };
                let Ok(port) = port.parse::<u16>() else {
                    continue;
                };
                let addr = addr.trim_start_matches('[').trim_end_matches(']');
                let reachable = match addr {
                    "*" | "0.0.0.0" | "::" | "::1" | "localhost" => true,
                    a => a
                        .parse::<std::net::IpAddr>()
                        .is_ok_and(|ip| ip.is_loopback() || ip.is_unspecified()),
                };
                if reachable {
                    out.push(ListeningPort {
                        port,
                        process: command.clone(),
                        pid,
                    });
                }
            }
            _ => {}
        }
    }
    out
}

#[cfg(not(any(windows, target_os = "linux", target_os = "macos")))]
fn os_listening_ports() -> Option<Vec<ListeningPort>> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lsof_listeners_keep_loopback_and_any_address_with_process() {
        let text = [
            "p501",
            "f12",
            "cnode",
            "n*:5173",
            "f13",
            "n[::1]:3000", //
            "p777",
            "cControlCenter",
            "n*:5000", //
            "p900",
            "cpostgres",
            "n192.168.1.4:5432",
            "n127.0.0.1:5433",
            "n[::]:8080", //
            "p901",
            "cweird",
            "nfoo->bar",
            "nno-port",
        ]
        .join(
            "
",
        );
        let v = parse_lsof_listeners(&text);
        let got: Vec<(u16, Option<&str>, Option<u32>)> = v
            .iter()
            .map(|p| (p.port, p.process.as_deref(), p.pid))
            .collect();
        assert_eq!(
            got,
            vec![
                (5173, Some("node"), Some(501)),
                (3000, Some("node"), Some(501)),
                (5000, Some("ControlCenter"), Some(777)),
                (5433, Some("postgres"), Some(900)),
                (8080, Some("postgres"), Some(900)),
            ]
        );
    }
    use tokio::net::TcpListener;

    #[test]
    fn frames_round_trip() {
        let f = encode(FrameType::Data, 0x0102_0304, b"hi");
        assert_eq!(f, vec![3, 1, 2, 3, 4, b'h', b'i']);
        assert_eq!(decode(&f), Some((FrameType::Data, 0x0102_0304, &b"hi"[..])));
        assert_eq!(decode(&[9, 0, 0, 0, 1]), None);
        assert_eq!(decode(&[3, 0, 0]), None);
    }

    fn fixture(name: &str) -> serde_json::Value {
        let root =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../protocol/fixtures");
        serde_json::from_str(&std::fs::read_to_string(root.join(name)).unwrap()).unwrap()
    }

    #[test]
    fn wire_format_matches_the_shared_fixtures() {
        for f in fixture("forward_frames.json")["frames"].as_array().unwrap() {
            let kind = FrameType::from_u8(f["type"].as_u64().unwrap() as u8).unwrap();
            let stream = f["stream"].as_u64().unwrap() as u32;
            let payload = hex::decode(f["payloadHex"].as_str().unwrap()).unwrap();
            let wire = hex::decode(f["hex"].as_str().unwrap()).unwrap();
            assert_eq!(encode(kind, stream, &payload), wire, "{}", f["name"]);
            assert_eq!(decode(&wire), Some((kind, stream, &payload[..])));
        }
        let view = LeaseView {
            id: "6f1d2c3b-4a5e-4f60-8a9b-0c1d2e3f4a5b".into(),
            port: 5173,
            created_at_ms: 1_763_452_800_000,
            expires_at_ms: 1_763_460_000_000,
            connected: true,
            active_streams: 3,
            total_streams: 42,
            bytes_down: 1_048_576,
            bytes_up: 8192,
        };
        assert_eq!(
            serde_json::to_value(view).unwrap(),
            fixture("forward_view.json")
        );
    }

    #[test]
    fn development_servers_are_recognised() {
        let p = |port: u16, process: Option<&str>| ListeningPort {
            port,
            process: process.map(str::to_string),
            pid: None,
        };
        assert!(looks_like_dev_server(&p(41234, Some("node.exe"))));
        assert!(looks_like_dev_server(&p(41234, Some("Python.EXE"))));
        assert!(looks_like_dev_server(&p(41234, Some("python3"))));
        assert!(looks_like_dev_server(&p(5173, None)));
        assert!(!looks_like_dev_server(&p(41234, Some("QQ.exe"))));
        assert!(!looks_like_dev_server(&p(41234, Some("node-helper.exe"))));
        assert!(!looks_like_dev_server(&p(7890, None)));
    }

    #[test]
    fn proc_net_tcp_listeners_on_loopback_or_any() {
        let v4 = "  sl  local_address rem_address   st\n\
   0: 0100007F:1435 00000000:0000 0A\n\
   1: 00000000:1F90 00000000:0000 0A\n\
   2: 0101A8C0:0016 00000000:0000 0A\n\
   3: 0100007F:9C40 0100007F:1435 01\n";
        let ports: Vec<u16> = parse_proc_net_tcp(v4).iter().map(|p| p.port).collect();
        assert_eq!(ports, vec![5173, 8080]);
        let v6 = "  sl  local_address rem_address st\n\
   0: 00000000000000000000000001000000:0BB8 00000000000000000000000000000000:0000 0A\n\
   1: 000080FE00000000FF005450B6AD1DFE:0BB9 00000000000000000000000000000000:0000 0A\n";
        let ports: Vec<u16> = parse_proc_net_tcp(v6).iter().map(|p| p.port).collect();
        assert_eq!(ports, vec![3000]);
    }

    #[test]
    fn leases_are_per_device_renewed_and_released() {
        let m = ForwardManager::default();
        let (a, new_a) = m.create("d1", 5173, Duration::from_secs(60)).unwrap();
        assert!(new_a);
        let (a2, new_a2) = m.create("d1", 5173, Duration::from_secs(120)).unwrap();
        assert!(!new_a2);
        assert_eq!(a.id, a2.id);
        assert!(m.get("d2", &a.id).is_none(), "other devices cannot use it");
        assert!(m.remove(Some("d2"), &a.id).is_none());
        let (b, _) = m.create("d1", 3000, MAX_TTL * 2).unwrap();
        assert!(b.expires_at_ms() - b.created_at_ms <= MAX_TTL.as_millis() as i64 + 5);
        assert_eq!(m.list("d1").len(), 2);
        assert_eq!(m.release_device("d1"), 2);
        assert!(a.cancel.is_cancelled() && b.cancel.is_cancelled());
        for port in 0..MAX_LEASES_PER_DEVICE as u16 {
            m.create("d3", 4000 + port, DEFAULT_TTL).unwrap();
        }
        assert!(matches!(
            m.create("d3", 9999, DEFAULT_TTL),
            Err(LeaseError::TooMany)
        ));
        // An expired lease is gone and its tunnel cancelled.
        let (c, _) = m.create("d4", 1234, Duration::ZERO).unwrap();
        assert!(m.get("d4", &c.id).is_none());
        assert!(c.cancel.is_cancelled());
    }

    struct Phone {
        to_desktop: mpsc::Sender<Vec<u8>>,
        from_desktop: mpsc::Receiver<Vec<u8>>,
    }

    impl Phone {
        async fn send(&self, kind: FrameType, id: u32, payload: &[u8]) {
            self.to_desktop
                .send(encode(kind, id, payload))
                .await
                .unwrap();
        }
        async fn next(&mut self) -> (FrameType, u32, Vec<u8>) {
            let f = tokio::time::timeout(Duration::from_secs(10), self.from_desktop.recv())
                .await
                .expect("frame in time")
                .expect("tunnel open");
            let (k, id, p) = decode(&f).unwrap();
            (k, id, p.to_vec())
        }
    }

    fn tunnel(
        port: u16,
        targets: &'static (dyn Fn(u16) -> Vec<SocketAddr> + Sync),
    ) -> (Phone, Arc<Lease>, tokio::task::JoinHandle<TunnelEnd>) {
        let m = ForwardManager::default();
        let (lease, _) = m.create("d", port, DEFAULT_TTL).unwrap();
        let (to_desktop, mut incoming) = mpsc::channel(64);
        let (outgoing, from_desktop) = mpsc::channel(64);
        let cancel = lease.take_tunnel();
        let l = lease.clone();
        let task = tokio::spawn(async move {
            serve_tunnel_to(l, targets, &mut incoming, outgoing, cancel).await
        });
        (
            Phone {
                to_desktop,
                from_desktop,
            },
            lease,
            task,
        )
    }

    #[tokio::test]
    async fn streams_are_multiplexed_with_half_close_and_flow_control() {
        // An upper-casing echo server that answers after the client's FIN.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            loop {
                let (mut s, _) = listener.accept().await.unwrap();
                tokio::spawn(async move {
                    let mut all = Vec::new();
                    s.read_to_end(&mut all).await.unwrap();
                    s.write_all(&all.to_ascii_uppercase()).await.unwrap();
                });
            }
        });
        let (mut phone, lease, task) = tunnel(port, &loopback_targets_for);
        // Two interleaved streams; stream 3 sends before OPENED arrives.
        phone.send(FrameType::Open, 1, &[]).await;
        phone.send(FrameType::Open, 3, &[]).await;
        phone.send(FrameType::Data, 3, b"second ").await;
        phone.send(FrameType::Data, 1, b"first").await;
        phone.send(FrameType::Data, 3, b"stream").await;
        phone.send(FrameType::Fin, 1, &[]).await;
        phone.send(FrameType::Fin, 3, &[]).await;
        let mut got: HashMap<u32, Vec<u8>> = HashMap::new();
        let mut fins = 0;
        let mut windows = 0usize;
        while fins < 2 {
            let (k, id, p) = phone.next().await;
            match k {
                FrameType::Opened => {}
                FrameType::Data => got.entry(id).or_default().extend(p),
                FrameType::Window => {
                    windows += u32::from_be_bytes([p[0], p[1], p[2], p[3]]) as usize
                }
                FrameType::Fin => fins += 1,
                other => panic!("unexpected {other:?}"),
            }
        }
        assert_eq!(got[&1], b"FIRST");
        assert_eq!(got[&3], b"SECOND STREAM");
        // Credit comes back once written; it can arrive after the FIN, so drain a little.
        while windows < 18 {
            let (k, _, p) = phone.next().await;
            if k == FrameType::Window {
                windows += u32::from_be_bytes([p[0], p[1], p[2], p[3]]) as usize;
            }
        }
        assert_eq!(lease.total_streams.load(Ordering::Relaxed), 2);
        lease.cancel.cancel();
        assert_eq!(task.await.unwrap(), TunnelEnd::Cancelled);
    }

    #[tokio::test]
    async fn desktop_waits_for_window_credit() {
        // A server that sends 3 windows' worth of data at once.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let total = INITIAL_WINDOW * 3;
        tokio::spawn(async move {
            let (mut s, _) = listener.accept().await.unwrap();
            s.write_all(&vec![7u8; total]).await.unwrap();
        });
        let (mut phone, _lease, _task) = tunnel(port, &loopback_targets_for);
        phone.send(FrameType::Open, 1, &[]).await;
        let mut received = 0usize;
        // Without credit the desktop stops within one window. It may stop short of it: a chunk
        // it has read waits for credit for the whole chunk rather than being split.
        loop {
            match tokio::time::timeout(Duration::from_millis(300), phone.next()).await {
                Ok((FrameType::Data, _, p)) => {
                    received += p.len();
                    assert!(p.len() <= MAX_DATA);
                    assert!(received <= INITIAL_WINDOW, "desktop sent beyond the window");
                }
                Ok(_) => {}
                Err(_) => break,
            }
        }
        assert!(
            received > INITIAL_WINDOW - MAX_DATA,
            "desktop stalled early: {received}"
        );
        // Return the credit for what arrived; from then on credit flows back per chunk.
        // Everything arrives, then FIN.
        phone
            .send(FrameType::Window, 1, &(received as u32).to_be_bytes())
            .await;
        loop {
            let (k, _, p) = phone.next().await;
            match k {
                FrameType::Data => {
                    received += p.len();
                    // Return credit as a phone would.
                    phone
                        .send(FrameType::Window, 1, &(p.len() as u32).to_be_bytes())
                        .await;
                }
                FrameType::Fin => break,
                _ => {}
            }
        }
        assert_eq!(received, total);
    }

    #[tokio::test]
    async fn refused_ports_and_protocol_errors() {
        static NOWHERE: fn(u16) -> Vec<SocketAddr> =
            |_| vec![SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 1)];
        let (mut phone, _lease, task) = tunnel(1, &NOWHERE);
        phone.send(FrameType::Open, 5, &[]).await;
        let (k, id, p) = phone.next().await;
        assert_eq!(
            (k, id, p.as_slice()),
            (FrameType::Reset, 5, &b"refused"[..])
        );
        // Opening the same id twice is a protocol error that ends the tunnel.
        phone.send(FrameType::Open, 7, &[]).await;
        phone.send(FrameType::Open, 7, &[]).await;
        assert_eq!(task.await.unwrap(), TunnelEnd::Protocol);
    }

    #[tokio::test]
    async fn too_much_unacknowledged_data_resets_the_stream() {
        // A server that never reads.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            let (_s, _) = listener.accept().await.unwrap();
            tokio::time::sleep(Duration::from_secs(30)).await;
        });
        let (mut phone, _lease, _task) = tunnel(port, &loopback_targets_for);
        phone.send(FrameType::Open, 1, &[]).await;
        let chunk = vec![1u8; MAX_DATA];
        // Far more than the window and the kernel buffers.
        for _ in 0..(INITIAL_WINDOW / MAX_DATA) * 40 {
            if phone
                .to_desktop
                .send(encode(FrameType::Data, 1, &chunk))
                .await
                .is_err()
            {
                break;
            }
        }
        let mut reset = false;
        for _ in 0..1000 {
            let (k, _, p) = phone.next().await;
            if k == FrameType::Reset {
                assert_eq!(p, b"window");
                reset = true;
                break;
            }
        }
        assert!(reset);
    }

    #[test]
    fn listing_excludes_the_given_port() {
        // A port below the dynamic range (those are filtered by design).
        let (_l, port) = (39_000u16..39_500)
            .find_map(|p| {
                std::net::TcpListener::bind(("127.0.0.1", p))
                    .ok()
                    .map(|l| (l, p))
            })
            .expect("a free test port");
        let all = listening_ports(&[]);
        let excluded = listening_ports(&[port]);
        if cfg!(any(windows, target_os = "linux", target_os = "macos")) {
            let me = all.iter().find(|p| p.port == port).expect("own listener");
            if cfg!(any(windows, target_os = "macos")) {
                assert_eq!(me.pid, Some(std::process::id()));
                assert!(me
                    .process
                    .as_deref()
                    .is_some_and(|n| n.contains("switchtether_core")));
            }
        }
        assert!(!excluded.iter().any(|p| p.port == port));
    }
}
