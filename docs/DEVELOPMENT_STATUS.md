# Development status

Last updated: 2026-11-17. Statuses: **DONE** (implemented and covered by passing automated
tests or recorded live verification), **PARTIAL**, **BLOCKED** (upstream limitation, evidence
in docs/research), **UNTESTED**, **TODO**.

## Repository / fork

| Item | Status | Evidence |
|---|---|---|
| CC Switch v4.0.31 merged with full history; `upstream` remote; `upstream-base` branch; `UPSTREAM_VERSION` | DONE | git history |
| Rebrand (name, identifier, binary, deep link, icons, tray icon, UI strings via i18n post-processor, links) | DONE | sandboxed app run shows SwitchTether branding |
| Own updater endpoint; upstream key removed; fail-closed placeholder key | DONE | updater logs 404/placeholder, no crash |
| Data isolation `~/.switchtether` + non-destructive first-run import of `~/.cc-switch` | DONE | 5 unit tests |
| Warning when upstream CC Switch runs simultaneously | DONE | observed in sandbox run |
| Upstream sync procedure + workflow; trial merge of upstream main | DONE | docs/UPSTREAM_SYNC.md (trial record) |

## Desktop Remote Core (`src-tauri/crates/switchtether-core`)

| Area | Status | Tests |
|---|---|---|
| TLS 1.3 + mandatory client certs, server pinning | DONE | e2e |
| Device registry / revocation / audit / command log | DONE | unit + e2e |
| QR pairing | DONE | unit + e2e (race) + real app via CLI client |
| Biometric step-up grants | DONE | unit + e2e + real app (terminal) |
| Event bus + WS replay/resync | DONE | unit + e2e |
| Idempotent commands | DONE | e2e + real app (provider switch replay) |
| CC Switch backend bridge | DONE | all management endpoints exercised against the real app (sandbox DB); provider switch wrote only sandbox config |
| Terminals | DONE | unit (real shell) + e2e + real app |
| Files / Git | DONE | unit + e2e |
| Claude Code observation (live discovery, transcript parsing, tailing) | DONE | unit + real data |
| Claude Code managed sessions (message, tool call, approval, 409 second answer, interrupt) | DONE | live run with model `haiku` |
| Codex managed sessions | PARTIAL | protocol to `turn/start` verified in an isolated home; approvals/interrupt untested live |
| In-place control of externally started Claude Code / Codex sessions (no hook) | BLOCKED | docs/AGENT_COMPATIBILITY.md |
| Opt-in takeover: shim + attach broker (VS Code wrapper, Codex Desktop `CODEX_CLI_PATH`, `st-claude`/`st-codex`) | PARTIAL | shim unit tests (router, resolve, args); `tests/attach_broker.rs`; live: real claude via simulated VS Code host, Desktop-bundled codex 0.167 via simulated Desktop, real claude TUI mirrored. Real VS Code / Codex Desktop launches untested |
| Codex managed/attached sessions, approvals | PARTIAL → approvals and phone turns verified live through the attach path with codex 0.167 | live harness |

## Desktop app

| Item | Status | Evidence |
|---|---|---|
| Remote runtime lifecycle, off-by-default config | DONE | sandboxed run |
| Settings → Remote page (8 locales) | DONE | 6 component tests; rendered in the real app |
| Takeover switches (backup, refuse foreign values, exact removal) | PARTIAL | settings.json round-trip unit tests; Windows env/PATH edits not exercised (would change the user's real environment) |
| `switchtether-shim` shipped as Tauri sidecar (`scripts/build-shim.mjs`) | PARTIAL | dev build verified; installer bundle not rebuilt yet |
| Quit confirmation / exit cleanup | PARTIAL | implemented; not exercised manually |
| Windows installers (NSIS + MSI, unsigned) | DONE — `pnpm tauri build --bundles nsis,msi` on Windows 11 (built from `1bb7f502`) |

## Android (`android/`)

| Item | Status | Evidence |
|---|---|---|
| Project, pinned toolchain, coui17 vendored at a51f957 (hidden-API bypass patched out) | DONE | builds offline |
| Pairing (QR / link / deep link), Keystore mTLS identity, pinning | DONE | 49 core:remote tests incl. real TLS; emulator pairing |
| Home, Sessions, native agent renderer, managed sessions + approvals | DONE | emulator E2E (approvals created files on the PC) |
| Manage: providers (secretFields, credential update), usage, quota, MCP/skills/prompts | PARTIAL | real backend data displayed; switch/test/credential and toggles not exercised on device |
| coui17 design pass, blurred BottomFloatingNavigationBar | DONE | screenshots light/dark |
| Terminal (renderer, keys, step-up) | DONE | emulator: PowerShell `echo hi` |
| Files & Git (editor, conflict screen, upload/download) | PARTIAL | conflict + keep-copy verified on emulator; upload/download/rename/delete/merge not exercised |
| Background service + notifications (channels, rules, quiet hours, lock-screen redaction) | DONE | emulator: approval notification while backgrounded |
| Unit / Robolectric tests | DONE | 73 tests, 0 failures (re-run by lead) |
| Debug APK | DONE | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Release APK signing | TODO | needs a release keystore (secrets) |

## Release engineering

| Item | Status |
|---|---|
| CI (Rust workspace on 3 OSes, frontend, Android) | DONE (not yet run on GitHub — repository not pushed) |
| Release workflow (draft releases, optional signing) | DONE (not yet run) |
| Upstream-sync workflow | DONE (not yet run) |

## How to resume

1. `cd src-tauri && cargo test -p switchtether-core` (unit + e2e).
2. `cargo run -p switchtether-core --example dev_server` (headless server) / `dev_client`.
3. `pnpm vitest run tests/components/RemoteSection.test.tsx tests/config`.
4. `cd android && ./gradlew testDebugUnitTest :app:assembleDebug`.


## Android task fixes (2026-11-17)

Implemented in the working tree, including existing uncommitted file-viewer/title work:

- Native/indexed task titles and first-prompt fallback, including initial Codex thread metadata and
  rename updates; live Claude updates no longer send registry slugs as task titles.
- Codex subagent source spellings, nested streaming output and rollout spawn/wait groups; corrected
  callId-alias depth; child outcomes cannot end the parent task's notification.
- Codex choice/free-text questions, optional fields, secret input, multi-select arrays, option values,
  cancel, busy/complete gating. Answer capability is PARTIAL, never marked VERIFIED for this pass.
- COUI question preferences, centered standard approval confirmation, opaque dialog theme defaults,
  animated running badges and selection states, readable long-command preview/full wrapping/copy.
- Source links route to the desktop file reader (relative cwd, file://, line anchors); common code
  files are previewed in memory. Existing media/download management remains integrated.
- Startup replay is ignored for notifications; restored inactive Live Update pins are cleared silently.

Validation on Windows with JDK 21:

| Check | Result |
|---|---|
| `cargo test -p switchtether-core` | 86 unit + 3 attach integration + 13 TLS/API integration passed |
| `cargo clippy -p switchtether-core --all-targets -- -D warnings` | Passed |
| `:core:remote:test testDebugUnitTest :app:assembleDebug` | 179 passed, 1 existing skipped test; Debug APK built |
| New Compose interaction cases | 5 passed: light/dark custom answers and long commands; Codex multi-select |
| Visual review | API 32 Robolectric non-shader light/dark captures, synthetic task data only |

No phone/emulator was connected during this pass. Real-device rendering, background notification
arrival and live Codex question replies remain untested end-to-end; automated evidence is not a
VERIFIED claim for those capabilities. No agent process was restarted and no agent configuration
was modified. No commit or release publication was made.


## Local 0.1.2 packages (2026-11-17)

Product versions updated together in package.json, tauri.conf.json, FluentSetup defaults and Android
(versionCode 3). Cargo's upstream compatibility version remains 4.0.33 by design.

- Windows x64: `node scripts/package-windows.mjs` succeeded, FluentSetup inspect/verify passed for
  `SwitchTether-0.1.2-Setup.exe` (19,270,264 bytes, unsigned). Installer manifest version: 0.1.2.
- Android: `:app:assembleRelease :app:assembleDebug :core:remote:test testDebugUnitTest` succeeded.
  Signed `SwitchTether-0.1.2-release.apk` (8,438,274 bytes), versionName 0.1.2 / versionCode 3.
  apksigner verification passed; SHA-256 signing-certificate digest matches the existing 0.1.1 APK.
- `pnpm typecheck` and `pnpm vitest run tests/config` passed (1,219 configuration tests).
- Both packages and `SwitchTether-0.1.2-SHA256SUMS.txt` copied to the Windows Downloads known folder
  (`D:/Downloads`); copied-file SHA-256 checks passed. Previous downloads preserved.
- No installation, app/agent restart, Git commit or public release publication was performed.

## Local 0.1.3 packages (2026-11-17)

Changes since 0.1.2: COUI17 dialog buttons visible in light theme without blur (coui patch 0003);
Claude managed/attached session titles read from the transcript (`custom-title` > `ai-title`);
background sub-agents updated by `<task-notification>` (status/summary/result/stats) instead of
showing raw XML as a user message; IDE/harness blocks stripped from user text (`context` field);
quota resets in the phone's time zone with countdown and readable tier names; request log no
longer stuck on "loading"; no transient "ended" sessions from registry flicker or empty start-up
sessions (2-tick grace, untranscribed idle sessions not announced).

- Windows x64: `node scripts/package-windows.mjs` succeeded; FluentSetup verify OK for
  `SwitchTether-0.1.3-Setup.exe` (19,282,448 bytes, unsigned).
- Android: `:app:assembleRelease :app:assembleDebug :core:remote:test testDebugUnitTest` succeeded.
  Signed `SwitchTether-0.1.3-release.apk` (8,457,406 bytes), versionName 0.1.3 / versionCode 4;
  apksigner verifies (v3); signing-certificate digest equals 0.1.2.
- Rust: core clippy/tests and desktop clippy / `test --lib switchtether` passed. `pnpm typecheck`
  and `pnpm vitest run tests/config` (1,219 tests) passed.
- Copied with `SwitchTether-0.1.3-SHA256SUMS.txt` to `D:/Downloads`; checksums verified; earlier
  versions kept. Not installed on a device; no commit or public release.
