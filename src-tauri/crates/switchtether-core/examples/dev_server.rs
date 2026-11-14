//! Headless Remote Core for development and Android end-to-end testing.
//!
//! Serves the real API (pairing, sessions from this machine's Claude Code /
//! Codex data, terminals, files, git). CC Switch management endpoints answer
//! `503 backend_unavailable` because no CC Switch database is attached — use
//! the desktop app for those.
//!
//!   cargo run -p switchtether-core --example dev_server -- [--port 47615] [--data-dir DIR] [--host 10.0.2.2]
//!
//! Prints a `switchtether://pair?...` link (paste it into the app; `10.0.2.2`
//! is the host loopback as seen from the Android emulator).

use std::path::PathBuf;
use std::sync::Arc;

use switchtether_core::agents::claude::ClaudeObserver;
use switchtether_core::agents::AgentHub;
use switchtether_core::api::ApiContext;
use switchtether_core::backend::UnavailableBackend;
use switchtether_core::events::EventBus;
use switchtether_core::identity::ServerIdentity;
use switchtether_core::net::{candidate_hosts, NetworkMode};
use switchtether_core::registry::Registry;
use switchtether_core::secret_store::FileStore;
use switchtether_core::terminal::TerminalManager;
use switchtether_core::{RemoteServer, RemoteServerConfig};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let arg = |name: &str| {
        args.iter()
            .position(|a| a == name)
            .and_then(|i| args.get(i + 1))
            .cloned()
    };
    let port: u16 = arg("--port").and_then(|p| p.parse().ok()).unwrap_or(47615);
    let data_dir = arg("--data-dir")
        .map(PathBuf::from)
        .unwrap_or_else(|| std::env::temp_dir().join("switchtether-dev-server"));
    std::fs::create_dir_all(&data_dir)?;

    let store = FileStore::new(data_dir.join("secrets"));
    let (identity, _) =
        ServerIdentity::load_or_create(&data_dir, &store, "SwitchTether dev server")?;
    let registry = Registry::open(&data_dir.join("remote.db"))?;
    let bus = EventBus::new(10_000);
    let backend = Arc::new(UnavailableBackend);
    let hub = AgentHub::new(bus.clone(), backend.clone(), ClaudeObserver::detect());
    let terminals = TerminalManager::new(bus.clone(), Some(data_dir.join("terminal-logs")));
    let ctx = ApiContext::new(
        RemoteServerConfig {
            data_dir: data_dir.clone(),
            port,
            network_mode: NetworkMode::Lan,
            server_name: format!("{} (dev)", hostname()),
            server_version: env!("CARGO_PKG_VERSION").into(),
        },
        "dev-server".into(),
        identity.clone(),
        registry,
        bus,
        backend,
        terminals,
        hub.clone(),
    );
    let cancel = tokio_util::sync::CancellationToken::new();
    hub.start_tailer(cancel.clone());
    let handle = RemoteServer::start(ctx.clone()).await?;

    let mut hosts: Vec<String> = arg("--host").into_iter().collect();
    hosts.push("10.0.2.2".into());
    hosts.extend(
        candidate_hosts(NetworkMode::Lan)
            .into_iter()
            .map(|h| h.address),
    );
    hosts.dedup();
    let offer = ctx.pairing.create(
        hosts,
        handle.port(),
        &identity.fingerprint,
        &ctx.config.read().server_name,
    );
    println!(
        "SwitchTether dev server listening on port {}",
        handle.port()
    );
    println!(
        "Server fingerprint: {}",
        switchtether_core::util::format_fingerprint(&identity.fingerprint)
    );
    println!("Data dir: {}", data_dir.display());
    println!("Pairing link (valid 5 minutes, single use):\n{}", offer.uri);
    println!("Press Ctrl+C to stop. Type 'pair' + Enter for a new pairing link.");

    let ctx2 = ctx.clone();
    let fp = identity.fingerprint.clone();
    let port_used = handle.port();
    std::thread::spawn(move || {
        let stdin = std::io::stdin();
        let mut line = String::new();
        while stdin.read_line(&mut line).is_ok() {
            if line.trim() == "pair" {
                let mut hosts = vec!["10.0.2.2".to_string()];
                hosts.extend(
                    candidate_hosts(NetworkMode::Lan)
                        .into_iter()
                        .map(|h| h.address),
                );
                let o = ctx2
                    .pairing
                    .create(hosts, port_used, &fp, &ctx2.config.read().server_name);
                println!("{}", o.uri);
            }
            line.clear();
        }
    });

    let mut rx = ctx.events.subscribe();
    loop {
        tokio::select! {
            _ = tokio::signal::ctrl_c() => break,
            ev = rx.recv() => {
                if let Ok(ev) = ev {
                    if ev.topic != "session.event" {
                        println!("[event {}] {} {}", ev.seq, ev.topic, ev.data);
                    }
                }
            }
        }
    }
    cancel.cancel();
    handle.shutdown().await;
    Ok(())
}

fn hostname() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "desktop".into())
}
