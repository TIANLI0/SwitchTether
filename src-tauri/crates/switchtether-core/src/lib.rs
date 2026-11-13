//! SwitchTether Remote Core.
//!
//! A self-contained crate that implements the remote-control server used by the
//! SwitchTether Android app:
//!
//! - [`identity`] / [`secret_store`]: desktop TLS identity kept in the OS keychain.
//! - [`tls`]: TLS 1.3-only rustls config with mandatory client certificates.
//! - [`registry`]: paired devices, audit log and idempotent command log (`remote.db`).
//! - [`pairing`] / [`stepup`]: QR pairing sessions and biometric step-up grants.
//! - [`server`]: accept loop (peer address policy, per-device connection tracking,
//!   instant revocation) and the axum API router in [`api`].
//! - [`events`]: sequenced event bus with replay for reconnecting clients.
//! - [`backend`]: the trait through which the API reaches CC Switch's existing
//!   services (implemented in the `switchtether` app crate — no business logic is
//!   duplicated here).
//! - [`terminal`], [`files`], [`git`]: desktop-side terminal (PTY + emulator),
//!   file and git services.
//! - [`agents`]: agent adapters (Claude Code, Codex) and the agent event schema.
//!
//! The crate has no Tauri dependency so it can be tested in isolation.

pub mod agents;
pub mod api;
pub mod backend;
pub mod error;
pub mod events;
pub mod files;
pub mod git;
pub mod identity;
pub mod net;
pub mod pairing;
pub mod protocol;
pub mod redact;
pub mod registry;
pub mod secret_store;
pub mod server;
pub mod stepup;
pub mod terminal;
pub mod tls;
pub mod util;

pub use error::{ApiError, ApiResult, ErrorCode};
pub use server::{RemoteServer, RemoteServerConfig, RemoteServerHandle};

/// Wire protocol version implemented by this build (docs/PROTOCOL.md).
pub const PROTOCOL_VERSION: u32 = 1;
/// Oldest protocol version still served.
pub const MIN_PROTOCOL_VERSION: u32 = 1;
/// Default TCP port of the remote server.
pub const DEFAULT_PORT: u16 = 47615;
