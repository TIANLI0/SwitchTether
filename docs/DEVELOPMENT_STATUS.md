# Development status

Last updated: 2026-11-14. Statuses: **DONE** (implemented and covered by passing automated
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
| In-place control of externally started Claude Code / Codex sessions | BLOCKED | docs/AGENT_COMPATIBILITY.md |

## Desktop app

| Item | Status | Evidence |
|---|---|---|
| Remote runtime lifecycle, off-by-default config | DONE | sandboxed run |
| Settings → Remote page (8 locales) | DONE | 5 component tests; rendered in the real app |
| Quit confirmation / exit cleanup | PARTIAL | implemented; not exercised manually |
| Windows installer | see release build notes in TESTING.md |

## Android (`android/`)

| Item | Status |
|---|---|
| Gradle project, version catalog, pinned wrapper, coui17 vendored at a51f957 (hidden-API bypass patched out) | DONE |
| core:remote (protocol models, pairing link, pinned TLS 1.3 mTLS, events, commands) | DONE (38 JVM tests incl. real TLS) |
| core:security (Keystore identity + step-up keys, BiometricPrompt) | DONE (device-only parts need an emulator/device) |
| Screens: onboarding/pairing, home, sessions + native agent renderer, new managed session, management, settings | DONE (assembleDebug) |
| Robolectric UI tests, emulator E2E | IN PROGRESS |
| Terminal renderer, files/Git UI, notifications/foreground service | TODO (Phase B) |

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
