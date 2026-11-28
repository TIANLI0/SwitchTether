# Testing

All results below were produced on the development machine (Windows 11, 2026-11-14) unless
marked otherwise. CI runs the same suites on Ubuntu, macOS and Windows.

## Suites

| Suite | Command | Result (dev machine) |
|---|---|---|
| Remote Core unit tests | `cd src-tauri && cargo test -p switchtether-core --lib` | 71 passed |
| Remote Core end-to-end (real TLS 1.3 + mTLS server on loopback) | `cargo test -p switchtether-core --test e2e_server` | 13 passed |
| SwitchTether desktop glue (import, data dir, bridge redaction) | `cargo test --lib switchtether` | 6 passed |
| Upstream CC Switch Rust tests | `cargo test --workspace` (CI uses `cargo nextest run --workspace`) | run in CI |
| Clippy | `cargo clippy -p switchtether-core --all-targets -- -D warnings`; `cargo clippy -- -D warnings` | clean (lib/bin). Two pre-existing upstream warnings appear only with `--tests` on Windows (`workbuddy_account/tests.rs`, `agy_account/keychain.rs`). |
| Frontend typecheck | `pnpm typecheck` | pass |
| Frontend unit tests | `pnpm test:unit` | 3972+ pass; 2 locale-sensitive `UsageTrendChart` tests fail on Node 24/ICU here and on the pristine upstream baseline |
| Android unit + Robolectric tests | `./gradlew testDebugUnitTest` in [SwitchTether-Android](https://github.com/TIANLI0/SwitchTether-Android) | its CI |

## End-to-end scenarios

| # | Scenario | How it was verified | Status |
|---|---|---|---|
| 1 | Start desktop | `pnpm tauri dev` / built binary in a sandboxed home (`CC_SWITCH_TEST_HOME`) — remote server listening, identity persisted across restarts | VERIFIED |
| 2 | First pairing | `dev_client pair` against the real desktop app (QR link) and the e2e suite; Android emulator pairing — see ANDROID_UI.md | VERIFIED (CLI client) |
| 3 | Secure connection | e2e: pinning, mandatory client certs, TLS 1.3 only | VERIFIED |
| 4 | Session sync | real Claude Code transcripts (1086 events), live discovery of running sessions | VERIFIED |
| 5 | Usage | `/v1/usage/*` against the real backend (sandbox DB) | VERIFIED |
| 6 | Control request | managed Claude session: message, approval, second-answer 409, interrupt (`examples/managed_smoke.rs`, live model) | VERIFIED |
| 7 | Disconnect recovery | e2e event replay + resync on stale epoch | VERIFIED (protocol); Android reconnect see ANDROID_UI.md |
| 8 | Device revocation | e2e: WS closed with 4403, cert refused, survives restart | VERIFIED |
| 9 | Remote terminal | e2e: step-up required, real PowerShell output streamed | VERIFIED |
| 10 | File browsing | e2e + unit (listing policy, read, search) | VERIFIED |
| 11 | File conflict | e2e: IDE save between read and write → 409 with current text, desktop file intact | VERIFIED |
| 12 | Android in background | — | see ANDROID_UI.md |
| 13 | Minimize to tray | upstream behaviour unchanged | UNTESTED manually in this cycle |
| 14 | Quit prompt | implemented (`switchtether::request_quit`) | UNTESTED manually |
| 15 | Original agent unaffected | SwitchTether never writes `~/.claude`/`~/.codex`; managed sessions are new processes; sandboxed GUI runs wrote only to the sandbox | VERIFIED (by inspection + sandbox) |

## Live tests that spend model tokens (manual only)

```bash
cd src-tauri
cargo run -p switchtether-core --example managed_smoke -- claude haiku
SMOKE_INTERRUPT=1 SMOKE_PROMPT="Use the Bash tool to run exactly: sleep 40 && mkdir late_dir . Then reply DONE." \
  cargo run -p switchtether-core --example managed_smoke -- claude haiku
CODEX_HOME=/tmp/isolated-codex cargo run -p switchtether-core --example managed_smoke -- codex
# Google Antigravity (signed-in agy): new / options / stop + resume / interrupt through the hub;
# with AGY_SMOKE_APPROVAL_BIN (a wrapper running `agy.exe %* --gemini_dir=<empty scratch dir>`) also an approval.
cargo run -p switchtether-core --example agy_smoke -- claude-sonnet-5-5 low
```

Never point a test Codex app-server at a `CODEX_HOME` used by a running Codex Desktop of a
different version (shared state database).

## Sandboxed desktop run

```bash
SB=/tmp/st-sandbox; mkdir -p $SB/.switchtether/remote
echo '{"enabled":true,"port":47616,"networkMode":"lan"}' > $SB/.switchtether/remote/config.json
CC_SWITCH_TEST_HOME=$SB SWITCHTETHER_TEST_APP_DIR=1 SWITCHTETHER_SECRET_BACKEND=file \
SWITCHTETHER_DEV_LOG_PAIRING=1 SWITCHTETHER_SKIP_UPSTREAM_IMPORT=1 pnpm tauri dev
# pairing link appears in $SB/.switchtether/logs/switchtether.log
```

## Builds performed

| Artifact | Command | Result |
|---|---|---|
| Windows NSIS + MSI installers (x64, unsigned) | `pnpm tauri build --bundles nsis,msi` | built: `SwitchTether_0.1.0_x64-setup.exe` (15 MiB), `SwitchTether_0.1.0_x64_en-US.msi` (21 MiB) |
| macOS / Linux bundles | release workflow | not built locally (no macOS/Linux host) |
| Android debug APK | `./gradlew :app:assembleDebug` | built (see ANDROID_UI.md) |
