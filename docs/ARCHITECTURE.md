# SwitchTether architecture

SwitchTether = **CC Switch (desktop, long-lived fork)** + **Remote Core** (authenticated
remote API inside the desktop process) + **native Android app**. One computer and one phone
are enough; there is no public server, broker, database server or message queue.

```
 Android (Kotlin/Compose/coui17)                 Desktop (Tauri 2 / Rust)
┌──────────────────────────────┐   TLS 1.3 +   ┌──────────────────────────────────────────┐
│ UI (Compose screens)         │   mTLS, pin   │ switchtether app crate (upstream CC Switch)│
│ ViewModels (StateFlow)       │◄────────────► │  ├─ switchtether/ (fork-only glue)         │
│ Repositories                 │  HTTPS + WS   │  │   ├─ bridge.rs  CcSwitchBackend impl ───┼─► upstream services
│ RemoteApi / EventStream      │  LAN /        │  │   ├─ remote.rs  lifecycle, QR, devices  │   (providers, usage,
│ ConnectionManager            │  Tailscale /  │  │   ├─ commands.rs Tauri cmds for UI      │    sessions, MCP, …)
│ Security (Keystore,          │  manual host  │  │   └─ upstream_import.rs                 │
│   BiometricPrompt)           │               │  └─ switchtether-core crate (no Tauri)    │
│ Agent renderer registry      │               │      server · tls · registry · pairing    │
│ Terminal renderer            │               │      stepup · events · api/* · terminal   │
└──────────────────────────────┘               │      files · git · agents/{claude,codex}  │
                                               └──────────────────────────────────────────┘
```

## 1. Repository layout

| Path | Contents |
|---|---|
| `src/`, `src-tauri/src/` | Upstream CC Switch (frontend + backend), plus small hook points |
| `src-tauri/src/switchtether/` | All fork-specific desktop glue |
| `src-tauri/crates/switchtether-core/` | Remote Core library (tests, examples `dev_server`, `dev_client`, `managed_smoke`) |
| `src/components/settings/sections/RemoteSection.tsx`, `src/lib/api/remote.ts` | Desktop "Remote" settings page |
| `android/` | Native Android app (`:app`, `:core:remote`, `:core:security`, `:core:ui`, the COUI17 submodule `third_party/coui17`) |
| `protocol/fixtures/` | JSON fixtures parsed by Rust and Kotlin tests |
| `docs/` | Protocol, security, compatibility, research evidence |

Upstream hook points (kept minimal for merge-ability): `lib.rs` (module, setup calls, quit
paths, exit cleanup, command registration), `tray.rs` (quit), `config.rs` (data dir),
`settings.rs`, `live/engine.rs`, `panic_hook.rs`, `commands/provider.rs` (one `pub(crate)`),
`deeplink/parser.rs`, a few frontend registration lines and locale keys.

## 2. Desktop

### 2.1 Data isolation
`~/.switchtether` replaces `~/.cc-switch` (`config::APP_DIR_NAME`). On first launch an
existing CC Switch database is copied with SQLite's backup API (source opened read-only,
integrity-checked, never overwrites; OAuth account stores excluded). Remote Core state is in
`~/.switchtether/remote/` (`remote.db`, identity certificate, config, terminal logs), separate
from the CC Switch database so upstream schema migrations never interact with it.

### 2.2 Remote Core (switchtether-core)
- `server.rs` — TCP accept loop: peer address policy → TLS handshake (timeout 10 s) → per-
  connection `PeerInfo` (client cert fingerprint, cancellation token) → hyper HTTP/1.1 with
  upgrades serving the axum router. `ConnectionTracker` maps fingerprints to live connections
  for instant revocation.
- `api/` — whitelisted routes; `AuthDevice` extractor (registry lookup + rate limit),
  `require_step_up`, `idempotent()` command executor, WebSocket handlers.
- `registry.rs` — devices, audit log, command log (SQLite WAL).
- `events.rs` — global sequenced bus (epoch + seq, 10 000-event ring, replay or resync).
- `backend.rs` — `CcSwitchBackend` trait; the app crate's `CcSwitchBridge` implements it
  with the upstream services, so the remote API reuses CC Switch's validation, atomic
  config writes, backups and tray refresh rather than re-implementing them.
- `terminal/` — portable-pty (ConPTY/Unix PTY) + vt100 emulator; streams screen snapshots and
  changed rows (≈30 fps coalescing). Scrollback 5000 lines/terminal; optional size-capped
  per-terminal log.
- `files/`, `git.rs` — see SECURITY.md §6 for policy.
- `agents/` — `AgentHub` merges live Claude sessions, managed sessions and CC Switch's session
  index; per-session `SessionLog` (seq, in-place updates, bounded payload store); transcript
  tailers; `ManagedSession` trait with `ManagedClaude` (stream-json control protocol) and
  `ManagedCodex` (own `codex app-server`).

### 2.3 Lifecycle
- Remote access is **on by default** for new installs (no `remote/config.json` yet); turning it
  off in Settings → Remote stops the listener and the saved choice is kept. Pairing still needs
  the QR code and the matching code on both screens.
- Closing the window keeps SwitchTether in the tray (upstream behaviour).
- "Quit" (tray or close-to-exit) lists remote terminals, managed sessions and phone
  connections that would end and lets the user keep running in the tray. Externally started
  Claude Code/Codex processes are never touched. macOS ⌘Q / logout cannot be intercepted;
  cleanup still runs.
- No separate daemon: PTYs and managed agents live in the SwitchTether process and end when it
  exits.

## 3. Protocol

See [PROTOCOL.md](PROTOCOL.md): REST + WebSocket, `commandId` idempotency, event `seq`/`epoch`
replay, AgentEvent schema with in-place updates, capability negotiation with
VERIFIED/PARTIAL/BLOCKED/UNTESTED statuses.

## 4. Android

See [ANDROID_UI.md](ANDROID_UI.md). Layers: Compose UI → ViewModels → repositories →
`RemoteApi` (OkHttp, TLS 1.3, Keystore client key, pinned server) + `EventStream` + 
`ConnectionManager`; `core:security` holds Keystore identity/step-up keys and BiometricPrompt.
The agent timeline is rendered by a registry keyed by `AgentEvent.kind` with a generic
fallback for unknown kinds.

## 5. Synchronisation model

- The agent runtime (or transcript file) is the source of truth; the phone keeps only an
  in-memory view rebuilt from REST pages + live `session.event` updates (replace by event id).
- Global events carry `seq`; reconnects pass `since`+`epoch`; gaps or a new epoch trigger a
  REST refetch (`resync`).
- Side-effecting operations carry a `commandId`; outcomes are stored for 24 h; unknown
  outcomes are surfaced to the user instead of being retried automatically.
- Concurrent approvals: the first answer wins; later answers get 409 with `resolvedBy`.

## 6. Decisions log

| Decision | Reason |
|---|---|
| Remote Core as a separate crate with a backend trait | testable without Tauri; upstream merges rarely touch it; no duplicated CC Switch logic |
| Pinned self-signed certs + mTLS instead of a CA / passwords | no third-party infrastructure; phishing-resistant; per-device revocation at the TLS layer |
| Biometric step-up via Keystore signatures | server-verifiable proof instead of a client-side flag |
| Server-side terminal emulation (mosh-style) | robust on flaky mobile links; bounded memory; simple native renderer |
| Separate `remote.db` | independence from upstream schema versioning |
| `~/.switchtether` data dir + copy-import | never two programs writing one database without coordination |
| Observe-only for external sessions; managed sessions for control | upstream offers no legitimate attach interface; no hooks/process injection (see AGENT_COMPATIBILITY.md) |
| coui17 vendored at a pinned commit (Android-only KMP modules) | the fork publishes only a token-gated rolling SNAPSHOT; vendoring gives reproducible offline builds |
| Git via CLI with hardening flags | respects user config without libgit2; no repo-provided programs run |
