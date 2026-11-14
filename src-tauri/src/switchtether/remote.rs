//! Desktop lifecycle of the Remote Core: configuration, start/stop, pairing
//! QR codes, device management, event forwarding to the desktop UI and the
//! quit flow.

use std::path::PathBuf;
use std::sync::Arc;

use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use switchtether_core::agents::claude::ClaudeObserver;
use switchtether_core::agents::AgentHub;
use switchtether_core::api::ApiContext;
use switchtether_core::events::EventBus;
use switchtether_core::identity::ServerIdentity;
use switchtether_core::net::{candidate_hosts, NetworkMode};
use switchtether_core::registry::Registry;
use switchtether_core::secret_store::SecretBackendKind;
use switchtether_core::terminal::TerminalManager;
use switchtether_core::{RemoteServer, RemoteServerConfig, RemoteServerHandle};
use tauri::Emitter;
use tokio_util::sync::CancellationToken;

pub const UI_EVENT: &str = "switchtether-remote-event";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct RemoteConfig {
    /// Off by default: the remote server never starts without explicit consent.
    pub enabled: bool,
    pub port: u16,
    pub network_mode: NetworkMode,
    pub server_name: Option<String>,
}

impl Default for RemoteConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            port: switchtether_core::DEFAULT_PORT,
            network_mode: NetworkMode::Lan,
            server_name: None,
        }
    }
}

pub fn remote_dir() -> PathBuf {
    // Device-local (not affected by the configurable/synced config dir).
    crate::config::get_app_home_dir().join("remote")
}

fn config_path() -> PathBuf {
    remote_dir().join("config.json")
}

fn load_config() -> RemoteConfig {
    std::fs::read_to_string(config_path())
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn save_config(c: &RemoteConfig) -> Result<(), String> {
    std::fs::create_dir_all(remote_dir()).map_err(|e| e.to_string())?;
    let s = serde_json::to_string_pretty(c).map_err(|e| e.to_string())?;
    crate::config::atomic_write_private(&config_path(), s.as_bytes()).map_err(|e| e.to_string())
}

fn hostname() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .ok()
        .filter(|h| !h.is_empty())
        .unwrap_or_else(|| "Desktop".into())
}

fn server_id(dir: &std::path::Path) -> String {
    let p = dir.join("server-id");
    if let Ok(s) = std::fs::read_to_string(&p) {
        let s = s.trim().to_string();
        if !s.is_empty() {
            return s;
        }
    }
    let id = switchtether_core::util::new_id();
    let _ = std::fs::write(&p, &id);
    id
}

pub struct RemoteRuntime {
    pub ctx: Arc<ApiContext>,
    pub secret_backend: SecretBackendKind,
    pub identity_created: bool,
    config: Mutex<RemoteConfig>,
    handle: tokio::sync::Mutex<Option<RemoteServerHandle>>,
    last_error: Mutex<Option<String>>,
    cancel: CancellationToken,
}

impl RemoteRuntime {
    pub async fn init(app: tauri::AppHandle) -> anyhow::Result<Arc<Self>> {
        let dir = remote_dir();
        std::fs::create_dir_all(&dir)?;
        let config = load_config();
        let (identity, created, backend_kind, registry) = {
            let dir = dir.clone();
            tokio::task::spawn_blocking(move || -> anyhow::Result<_> {
                let store = switchtether_core::secret_store::default_store(&dir);
                let kind = store.kind();
                let (identity, created) =
                    ServerIdentity::load_or_create(&dir, store.as_ref(), "SwitchTether Desktop")?;
                let registry = Registry::open(&dir.join("remote.db"))?;
                Ok((identity, created, kind, registry))
            })
            .await??
        };
        let bus = EventBus::new(10_000);
        let backend = Arc::new(super::bridge::CcSwitchBridge::new(app.clone()));
        let hub = AgentHub::new(bus.clone(), backend.clone(), ClaudeObserver::detect());
        let terminals = TerminalManager::new(bus.clone(), Some(dir.join("terminal-logs")));
        let ctx = ApiContext::new(
            RemoteServerConfig {
                data_dir: dir.clone(),
                port: config.port,
                network_mode: config.network_mode,
                server_name: config.server_name.clone().unwrap_or_else(hostname),
                server_version: app.package_info().version.to_string(),
            },
            server_id(&dir),
            identity,
            registry,
            bus.clone(),
            backend,
            terminals,
            hub.clone(),
        );
        let cancel = CancellationToken::new();
        hub.start_tailer(cancel.clone());

        // Forward selected events to the desktop UI.
        let mut rx = bus.subscribe();
        let app2 = app.clone();
        let c2 = cancel.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = c2.cancelled() => break,
                    ev = rx.recv() => match ev {
                        Ok(ev) => {
                            if matches!(ev.topic.as_str(),
                                "device.paired" | "device.revoked" | "terminal.created" | "terminal.exited"
                                | "session.upserted" | "approval.resolved" | "provider.switched") {
                                let _ = app2.emit(UI_EVENT, json!({ "topic": ev.topic, "data": ev.data }));
                            }
                        }
                        Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {}
                        Err(_) => break,
                    }
                }
            }
        });

        let rt = Arc::new(Self {
            ctx,
            secret_backend: backend_kind,
            identity_created: created,
            config: Mutex::new(config.clone()),
            handle: tokio::sync::Mutex::new(None),
            last_error: Mutex::new(None),
            cancel,
        });
        if config.enabled {
            if let Err(e) = rt.start().await {
                log::warn!("[switchtether] remote server failed to start: {e}");
            }
        }
        Ok(rt)
    }

    pub async fn start(&self) -> Result<(), String> {
        let mut h = self.handle.lock().await;
        if h.is_some() {
            return Ok(());
        }
        {
            let c = self.config.lock().clone();
            let mut cfg = self.ctx.config.write();
            cfg.port = c.port;
            cfg.network_mode = c.network_mode;
            cfg.server_name = c.server_name.clone().unwrap_or_else(hostname);
        }
        match RemoteServer::start(self.ctx.clone()).await {
            Ok(handle) => {
                *h = Some(handle);
                *self.last_error.lock() = None;
                Ok(())
            }
            Err(e) => {
                let msg = e.to_string();
                *self.last_error.lock() = Some(msg.clone());
                Err(msg)
            }
        }
    }

    pub async fn stop(&self) {
        if let Some(h) = self.handle.lock().await.take() {
            self.ctx.pairing.cancel_all();
            h.shutdown().await;
        }
    }

    pub async fn running_port(&self) -> Option<u16> {
        self.handle.lock().await.as_ref().map(|h| h.port())
    }

    pub async fn set_config(&self, mut new: RemoteConfig) -> Result<Value, String> {
        if new.port < 1024 {
            return Err("port must be >= 1024".into());
        }
        if let Some(n) = &new.server_name {
            let trimmed: String = n.trim().chars().take(64).collect();
            new.server_name = (!trimmed.is_empty()).then_some(trimmed);
        }
        let old = self.config.lock().clone();
        save_config(&new)?;
        *self.config.lock() = new.clone();
        let restart = old.port != new.port || old.network_mode != new.network_mode;
        if !new.enabled {
            self.stop().await;
        } else if restart || self.running_port().await.is_none() {
            self.stop().await;
            self.start().await?;
        }
        if let Some(name) = &new.server_name {
            self.ctx.config.write().server_name = name.clone();
        }
        Ok(self.status().await)
    }

    pub async fn status(&self) -> Value {
        let cfg = self.config.lock().clone();
        let port = self.running_port().await;
        let hosts: Vec<Value> = candidate_hosts(cfg.network_mode)
            .into_iter()
            .map(|h| json!(h))
            .collect();
        let devices = self.ctx.registry.list_devices().unwrap_or_default();
        json!({
            "config": cfg,
            "running": port.is_some(),
            "port": port,
            "error": self.last_error.lock().clone(),
            "fingerprint": self.ctx.identity.fingerprint,
            "fingerprintDisplay": switchtether_core::util::format_fingerprint(&self.ctx.identity.fingerprint),
            "serverName": self.ctx.config.read().server_name,
            "secretBackend": self.secret_backend,
            "identityCreatedThisLaunch": self.identity_created,
            "hosts": hosts,
            "connections": self.ctx.connections.list(&self.ctx.registry),
            "pairedDevices": devices.iter().filter(|d| d.is_active()).count(),
            "terminals": self.ctx.terminals.count(),
            "managedSessions": self.ctx.agents.managed_count(),
        })
    }

    pub async fn create_pairing(&self) -> Result<Value, String> {
        let Some(port) = self.running_port().await else {
            return Err("enable the remote server first".into());
        };
        let mode = self.config.lock().network_mode;
        let hosts: Vec<String> = candidate_hosts(mode)
            .into_iter()
            .map(|h| h.address)
            .collect();
        if hosts.is_empty() {
            return Err(
                "no usable network address found (connect to Wi-Fi/LAN or Tailscale)".into(),
            );
        }
        let name = self.ctx.config.read().server_name.clone();
        let offer = self
            .ctx
            .pairing
            .create(hosts, port, &self.ctx.identity.fingerprint, &name);
        let svg =
            qrcode::QrCode::with_error_correction_level(offer.uri.as_bytes(), qrcode::EcLevel::M)
                .map_err(|e| e.to_string())?
                .render::<qrcode::render::svg::Color>()
                .min_dimensions(260, 260)
                .quiet_zone(true)
                .build();
        Ok(json!({
            "pairingId": offer.pairing_id,
            "uri": offer.uri,
            "expiresAt": offer.expires_at,
            "qrSvg": svg,
            "hosts": offer.hosts,
            "port": offer.port,
            "fingerprintDisplay": switchtether_core::util::format_fingerprint(&offer.server_fingerprint),
        }))
    }

    /// What would be interrupted by quitting SwitchTether.
    pub async fn quit_impact(&self) -> Value {
        let terminals: Vec<Value> = self
            .ctx
            .terminals
            .list()
            .into_iter()
            .filter(|t| !t.exited)
            .map(|t| json!({ "id": t.id, "title": t.title, "shell": t.shell, "cwd": t.cwd }))
            .collect();
        json!({
            "terminals": terminals,
            "connections": self.ctx.connections.len(),
            "managedSessions": self.ctx.agents.managed_count(),
        })
    }

    /// Called when SwitchTether really exits: end PTYs and managed agent
    /// processes it owns, close connections. Externally started Claude Code /
    /// Codex processes are never touched.
    pub async fn shutdown_for_exit(&self) {
        let n = self.ctx.terminals.kill_all();
        self.ctx.agents.shutdown();
        self.stop().await;
        self.cancel.cancel();
        log::info!("[switchtether] remote shutdown: ended {n} terminal(s)");
    }
}

/// Managed state holder; filled asynchronously after startup.
#[derive(Default)]
pub struct RemoteState(pub tokio::sync::OnceCell<Arc<RemoteRuntime>>);

impl RemoteState {
    pub fn get(&self) -> Result<Arc<RemoteRuntime>, String> {
        self.0
            .get()
            .cloned()
            .ok_or_else(|| "remote service is still initialising".to_string())
    }
}
