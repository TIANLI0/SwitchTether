//! Remote server: listener, TLS accept loop and connection tracking.
//!
//! Each accepted connection must (1) come from an address allowed by the
//! [`NetworkMode`], (2) complete a TLS 1.3 handshake with a client certificate
//! (revoked certificates fail here), then (3) every HTTP request is authorized
//! by the API layer against the device registry. Connections are tracked per
//! client-certificate fingerprint so that revoking a device closes all of its
//! sockets — including upgraded WebSockets — immediately.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use hyper_util::rt::{TokioIo, TokioTimer};
use parking_lot::Mutex;
use serde::Serialize;
use tokio::net::TcpListener;
use tokio_rustls::TlsAcceptor;
use tokio_util::sync::CancellationToken;

use crate::api::{self, ApiContext};
use crate::net::{peer_allowed, NetworkMode};

pub const MAX_CONNECTIONS: usize = 64;
pub const TLS_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, Clone)]
pub struct RemoteServerConfig {
    /// `~/.switchtether/remote` — identity cert, remote.db, logs.
    pub data_dir: PathBuf,
    pub port: u16,
    pub network_mode: NetworkMode,
    pub server_name: String,
    pub server_version: String,
    /// Optional relay (docs/RELAY.md); off unless the user configured one.
    pub relay: Option<crate::relay::RelayConfig>,
}

/// Identity of the TLS peer of one connection, injected into every request.
#[derive(Debug, Clone)]
pub struct PeerInfo {
    pub conn_id: u64,
    pub fingerprint: String,
    pub addr: SocketAddr,
    pub cancel: CancellationToken,
}

impl PeerInfo {
    pub fn ip(&self) -> IpAddr {
        self.addr.ip()
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionView {
    pub conn_id: u64,
    pub device_id: Option<String>,
    pub peer: String,
    pub connected_at_ms: i64,
}

struct ConnEntry {
    fingerprint: String,
    addr: SocketAddr,
    cancel: CancellationToken,
    connected_at_ms: i64,
}

#[derive(Default)]
pub struct ConnectionTracker {
    next: AtomicU64,
    conns: Mutex<HashMap<u64, ConnEntry>>,
}

impl ConnectionTracker {
    fn add(&self, fingerprint: String, addr: SocketAddr, cancel: CancellationToken) -> u64 {
        let id = self.next.fetch_add(1, Ordering::Relaxed) + 1;
        self.conns.lock().insert(
            id,
            ConnEntry {
                fingerprint,
                addr,
                cancel,
                connected_at_ms: crate::util::now_ms(),
            },
        );
        id
    }

    fn remove(&self, id: u64) {
        self.conns.lock().remove(&id);
    }

    pub fn len(&self) -> usize {
        self.conns.lock().len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Close every connection whose client certificate has this fingerprint.
    pub fn disconnect_fingerprint(&self, fp: &str) -> usize {
        let conns = self.conns.lock();
        let mut n = 0;
        for c in conns.values().filter(|c| c.fingerprint == fp) {
            c.cancel.cancel();
            n += 1;
        }
        n
    }

    pub fn fingerprints(&self) -> Vec<String> {
        self.conns
            .lock()
            .values()
            .map(|c| c.fingerprint.clone())
            .collect()
    }

    pub fn list(&self, registry: &crate::registry::Registry) -> Vec<ConnectionView> {
        self.conns
            .lock()
            .iter()
            .map(|(id, c)| ConnectionView {
                conn_id: *id,
                device_id: registry.device_by_fingerprint(&c.fingerprint).map(|d| d.id),
                peer: c.addr.to_string(),
                connected_at_ms: c.connected_at_ms,
            })
            .collect()
    }
}

pub struct RemoteServer;

pub struct RemoteServerHandle {
    pub ctx: Arc<ApiContext>,
    pub local_addrs: Vec<SocketAddr>,
    shutdown: CancellationToken,
    tasks: Vec<tokio::task::JoinHandle<()>>,
    _advertiser: Option<crate::discovery::Advertiser>,
}

impl RemoteServerHandle {
    /// Stop accepting, close all connections and wait (bounded) for tasks.
    pub async fn shutdown(self) {
        self.ctx
            .events
            .publish("server.shutdown", serde_json::json!({}));
        // Give WebSocket writers a moment to flush the shutdown event.
        tokio::time::sleep(Duration::from_millis(100)).await;
        self.shutdown.cancel();
        for t in self.tasks {
            let _ = tokio::time::timeout(Duration::from_secs(3), t).await;
        }
    }

    pub fn port(&self) -> u16 {
        self.local_addrs.first().map(|a| a.port()).unwrap_or(0)
    }
}

impl RemoteServer {
    /// Bind listeners and start serving `ctx`.
    pub async fn start(ctx: Arc<ApiContext>) -> anyhow::Result<RemoteServerHandle> {
        let cfg = ctx.config.read().clone();
        let tls_cfg = crate::tls::server_config(&ctx.identity, ctx.registry.revoked_set())?;
        let acceptor = TlsAcceptor::from(tls_cfg);
        let shutdown = CancellationToken::new();

        let mut listeners = Vec::new();
        match cfg.network_mode {
            NetworkMode::LocalhostOnly => {
                listeners.push(TcpListener::bind(("127.0.0.1", cfg.port)).await?);
            }
            _ => {
                listeners.push(TcpListener::bind(("0.0.0.0", cfg.port)).await?);
                let port = listeners[0].local_addr()?.port();
                match bind_v6_only(port) {
                    Ok(l) => listeners.push(l),
                    Err(e) => log::info!("[remote] IPv6 listener not available: {e}"),
                }
            }
        }
        let local_addrs = listeners
            .iter()
            .filter_map(|l| l.local_addr().ok())
            .collect::<Vec<_>>();
        log::info!(
            "[remote] listening on {local_addrs:?} (mode {:?})",
            cfg.network_mode
        );

        let router = api::router(ctx.clone());
        let mut tasks = Vec::new();
        for listener in listeners {
            tasks.push(tokio::spawn(accept_loop(
                ctx.clone(),
                listener,
                acceptor.clone(),
                router.clone(),
                shutdown.clone(),
            )));
        }
        // LAN discovery (mDNS) only while listening on the LAN.
        let advertiser = if matches!(cfg.network_mode, NetworkMode::Lan | NetworkMode::Any) {
            let port = local_addrs.first().map(|a| a.port()).unwrap_or(cfg.port);
            match crate::discovery::Advertiser::start(
                &cfg.server_name,
                &ctx.server_id,
                &ctx.identity.fingerprint,
                port,
            ) {
                Ok(a) => Some(a),
                Err(e) => {
                    log::warn!("[remote] LAN discovery unavailable: {e}");
                    None
                }
            }
        } else {
            None
        };

        if let Some(relay_cfg) = cfg.relay.clone() {
            let ctx2 = ctx.clone();
            let acceptor2 = acceptor.clone();
            let router2 = router.clone();
            let shutdown2 = shutdown.clone();
            let on_tunnel: crate::relay::OnTunnel = std::sync::Arc::new(move |stream, addr| {
                if ctx2.connections.len() >= MAX_CONNECTIONS {
                    log::warn!("[remote] connection limit reached; dropping relay tunnel");
                    return;
                }
                tokio::spawn(serve_connection(
                    ctx2.clone(),
                    acceptor2.clone(),
                    router2.clone(),
                    stream,
                    addr,
                    shutdown2.clone(),
                ));
            });
            crate::relay::spawn(
                relay_cfg,
                ctx.identity.clone(),
                ctx.relay_status.clone(),
                on_tunnel,
                shutdown.child_token(),
            );
        } else {
            *ctx.relay_status.lock() = crate::relay::RelayStatus {
                state: "off".into(),
                ..Default::default()
            };
        }

        tasks.push(tokio::spawn(maintenance_loop(
            ctx.clone(),
            shutdown.clone(),
        )));
        // Push live-session status changes only while a phone is connected, or while a phone
        // that is away asked to be woken by push (docs/PROTOCOL.md §7.12).
        let weak = Arc::downgrade(&ctx);
        ctx.agents.start_live_watcher(
            shutdown.child_token(),
            Arc::new(move || {
                weak.upgrade()
                    .is_some_and(|c| !c.connections.is_empty() || c.push.has_subscribers())
            }),
        );
        ctx.push.start(ctx.events.clone(), shutdown.child_token());

        Ok(RemoteServerHandle {
            ctx,
            local_addrs,
            shutdown,
            tasks,
            _advertiser: advertiser,
        })
    }
}

fn bind_v6_only(port: u16) -> std::io::Result<TcpListener> {
    use socket2::{Domain, Protocol, Socket, Type};
    let sock = Socket::new(Domain::IPV6, Type::STREAM, Some(Protocol::TCP))?;
    sock.set_only_v6(true)?;
    sock.set_reuse_address(true)?;
    sock.set_nonblocking(true)?;
    let addr: SocketAddr = (std::net::Ipv6Addr::UNSPECIFIED, port).into();
    sock.bind(&addr.into())?;
    sock.listen(128)?;
    TcpListener::from_std(sock.into())
}

async fn maintenance_loop(ctx: Arc<ApiContext>, shutdown: CancellationToken) {
    let mut tick = tokio::time::interval(Duration::from_secs(600));
    loop {
        tokio::select! {
            _ = shutdown.cancelled() => break,
            _ = tick.tick() => {
                let reg = ctx.registry.clone();
                let _ = tokio::task::spawn_blocking(move || reg.purge_expired()).await;
            }
        }
    }
}

async fn accept_loop(
    ctx: Arc<ApiContext>,
    listener: TcpListener,
    acceptor: TlsAcceptor,
    router: axum::Router,
    shutdown: CancellationToken,
) {
    loop {
        let accepted = tokio::select! {
            _ = shutdown.cancelled() => break,
            a = listener.accept() => a,
        };
        let (tcp, addr) = match accepted {
            Ok(v) => v,
            Err(e) => {
                log::warn!("[remote] accept error: {e}");
                tokio::time::sleep(Duration::from_millis(50)).await;
                continue;
            }
        };
        let mode = ctx.config.read().network_mode;
        if !peer_allowed(mode, addr.ip()) {
            log::debug!("[remote] dropped connection from disallowed address {addr}");
            drop(tcp);
            continue;
        }
        if ctx.connections.len() >= MAX_CONNECTIONS {
            log::warn!("[remote] connection limit reached; dropping {addr}");
            drop(tcp);
            continue;
        }
        let _ = tcp.set_nodelay(true);
        let ctx = ctx.clone();
        let acceptor = acceptor.clone();
        let router = router.clone();
        let shutdown = shutdown.clone();
        tokio::spawn(async move {
            serve_connection(ctx, acceptor, router, tcp, addr, shutdown).await;
        });
    }
}

async fn serve_connection<S>(
    ctx: Arc<ApiContext>,
    acceptor: TlsAcceptor,
    router: axum::Router,
    tcp: S,
    addr: SocketAddr,
    shutdown: CancellationToken,
) where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let tls = match tokio::time::timeout(TLS_HANDSHAKE_TIMEOUT, acceptor.accept(tcp)).await {
        Ok(Ok(s)) => s,
        Ok(Err(e)) => {
            log::debug!("[remote] TLS handshake with {addr} failed: {e}");
            return;
        }
        Err(_) => {
            log::debug!("[remote] TLS handshake with {addr} timed out");
            return;
        }
    };
    let fingerprint = {
        let (_, session) = tls.get_ref();
        match session.peer_certificates().and_then(|c| c.first()) {
            Some(cert) => crate::util::sha256_hex(cert.as_ref()),
            None => return, // client auth is mandatory; defensive
        }
    };
    // Revocation race: the verifier already rejects revoked certificates, but
    // re-check after the handshake in case revocation happened concurrently.
    if ctx.registry.is_revoked_fingerprint(&fingerprint) {
        return;
    }

    let cancel = shutdown.child_token();
    let conn_id = ctx
        .connections
        .add(fingerprint.clone(), addr, cancel.clone());
    let peer = PeerInfo {
        conn_id,
        fingerprint,
        addr,
        cancel: cancel.clone(),
    };

    let svc = router.layer(axum::Extension(peer));
    let hyper_svc = hyper_util::service::TowerToHyperService::new(svc);
    let conn = hyper::server::conn::http1::Builder::new()
        .timer(TokioTimer::new())
        .header_read_timeout(Duration::from_secs(30))
        .keep_alive(true)
        .serve_connection(TokioIo::new(tls), hyper_svc)
        .with_upgrades();
    tokio::pin!(conn);
    tokio::select! {
        res = conn.as_mut() => {
            if let Err(e) = res {
                log::debug!("[remote] connection {conn_id} ended: {e}");
            }
        }
        _ = cancel.cancelled() => {
            conn.as_mut().graceful_shutdown();
            let _ = tokio::time::timeout(Duration::from_secs(2), conn.as_mut()).await;
        }
    }
    ctx.connections.remove(conn_id);
}
