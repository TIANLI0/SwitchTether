# Working on SwitchTether (humans and coding agents)

SwitchTether is a long-lived fork of CC Switch plus a Remote Core and a native Android app.
Three repositories: this one (desktop, Remote Core, wire protocol, releases),
[SwitchTether-Android](https://github.com/TIANLI0/SwitchTether-Android) (the app) and [SwitchTether-Relay](https://github.com/TIANLI0/SwitchTether-Relay) (optional relay).
Read `docs/ARCHITECTURE.md`, `docs/PROTOCOL.md` and `docs/DEVELOPMENT_STATUS.md` first.

## Hard rules

1. Never change how users launch Claude Code or Codex, never write their agent configuration,
   hooks, plugins or MCP settings to gain control, never inject into or restart their
   processes. Externally started sessions are observe-only (docs/AGENT_COMPATIBILITY.md).
   The single exception is the takeover hooks in `src-tauri/src/switchtether/takeover.rs`
   (§6b of AGENT_COMPATIBILITY): they are turned on once by default at first run (marker
   `~/.switchtether/remote/takeover-defaults.json`; skipped when the target is missing or the
   user already set a value), back up first, set only values that are unset, are removed
   exactly when turned off, and a switch the user turns off stays off — never re-enable it.
2. Never present a SwitchTether-managed session as the user's existing session.
3. Never write to `~/.cc-switch`. SwitchTether data lives in `~/.switchtether`
   (`crate::config::get_app_home_dir()`); route any new path through it.
4. Never return secrets through the Remote API; reuse the redaction helpers.
5. Never mark a capability VERIFIED without real evidence; never fake test results.
6. Do not commit keys, keystores, tokens or certificates.

## Where code goes

| Change | Location |
|---|---|
| Remote protocol / server / agents / terminal / files | `src-tauri/crates/switchtether-core` (no Tauri dependency) |
| Desktop glue (bridge to CC Switch services, lifecycle, Tauri commands) | `src-tauri/src/switchtether/` |
| Upstream CC Switch code | change only at documented hook points (docs/UPSTREAM_SYNC.md) |
| Desktop Remote settings UI | `src/components/settings/sections/RemoteSection.tsx`, `src/lib/api/remote.ts` |
| Android | TIANLI0/SwitchTether-Android (its APK is still released here by `release.yml`) |
| Relay server | TIANLI0/SwitchTether-Relay (spec: `docs/RELAY.md` here) |
| Wire format changes | update `docs/PROTOCOL.md`, `protocol/fixtures/` and Rust here first, then sync the fixtures and Kotlin models in SwitchTether-Android (`scripts/sync-protocol-fixtures.sh`); relay protocol changes go through `docs/RELAY.md`, `protocol/fixtures/relay/` and SwitchTether-Relay |

## Checks before committing

```bash
cd src-tauri
cargo fmt --all
cargo clippy -p switchtether-core --all-targets -- -D warnings
cargo clippy -- -D warnings
cargo test -p switchtether-core
cargo test --lib switchtether
cd .. && pnpm typecheck && pnpm vitest run tests/config
```

Android and relay checks are listed in the AGENTS.md of their repositories.

New UI strings must be added to all eight desktop locales (`tests/config/localeParity.test.ts`).
Product name in upstream strings is rebranded at runtime by the i18n post-processor in
`src/i18n/index.ts`; do not mass-edit upstream locale files.

## Testing against real agents

Use sandboxes: `CC_SWITCH_TEST_HOME` + `SWITCHTETHER_TEST_APP_DIR=1` for the desktop app,
an isolated `CODEX_HOME` for Codex experiments, a scratch directory as `cwd` for managed
sessions. See docs/TESTING.md.
