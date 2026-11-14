# Development status

Last updated: 2026-11-14. Statuses: **DONE** (implemented + automated tests pass),
**PARTIAL**, **BLOCKED** (upstream limitation, evidence in docs/research),
**UNTESTED**, **TODO**.

## Repository / fork

| Item | Status | Notes |
|---|---|---|
| CC Switch v4.0.31 merged with full history | DONE | `upstream` remote (main + tags), `upstream-base` branch pinned to v4.0.31 |
| Rebrand (name, identifier, binary, deep link, updater) | DONE | `io.github.tianli0.switchtether`; updater pubkey is a fail-closed placeholder until a maintainer key is configured |
| Data isolation `~/.switchtether` + non-destructive first-run import of `~/.cc-switch` | DONE | 5 unit tests; OAuth account stores intentionally not copied |
| Warning when upstream CC Switch runs simultaneously | DONE | dialog at startup |

## Desktop Remote Core (`src-tauri/crates/switchtether-core`)

| Area | Status | Tests |
|---|---|---|
| TLS 1.3 + mandatory client certs, server pinning | DONE | e2e: wrong pin, no client cert |
| Device registry / revocation / audit / command log (`remote.db`) | DONE | unit + e2e (revocation closes WS, survives restart) |
| QR pairing (single use, TTL, limits) | DONE | unit + e2e race (6 concurrent → 1 wins) |
| Biometric step-up grants (ECDSA P-256) | DONE | unit + e2e (terminal, file write) |
| Event bus + WS replay/resync | DONE | unit + e2e |
| Idempotent commands + unknown-outcome query | DONE | e2e |
| CC Switch backend bridge (providers, usage, quota, MCP, skills, prompts, settings, proxy, sessions) | DONE (compiles, unit test for redaction) | needs GUI smoke test |
| Terminals (ConPTY/PTY + vt100 screen streaming) | DONE | unit (real shell) + e2e |
| Files (policy, conflict detection, atomic write, trash, uploads, search) | DONE | unit + e2e |
| Git (status/diff/log/show via CLI) | DONE | unit with real repo |
| Claude Code: live discovery, transcript parsing/tailing | DONE | unit + real-data smoke (1086 events) |
| Claude Code: managed stream-json sessions | PARTIAL | protocol implemented; live model round-trip pending |
| Claude Code: in-place control of external sessions | BLOCKED | docs/research/CLAUDE_CODE_RUNTIME.md |
| Codex: rollout parsing | DONE | unit |
| Codex: managed app-server sessions | TODO | method list in docs/research/CODEX_RUNTIME.md |
| Codex: in-place control of CLI 0.137 / Desktop | BLOCKED | docs/research/CODEX_RUNTIME.md (verified lock error) |

## Desktop app integration

| Item | Status |
|---|---|
| Remote runtime lifecycle, config (`~/.switchtether/remote/config.json`, off by default) | DONE |
| Settings → Remote page (enable, network mode, port, name, QR pairing, devices, revoke, terminals, audit log), 8 locales | DONE (typecheck + locale parity tests pass) |
| Quit confirmation listing terminals/sessions/connections; keep-in-tray option | DONE (needs GUI smoke test) |
| Exit cleanup (kill owned PTYs + managed agents, never external agents) | DONE |

## Android (`android/`)

Being built; see android/DEV_NOTES.md and docs/ANDROID_UI.md.

## Release engineering

| Item | Status |
|---|---|
| GitHub Actions (CI, release, upstream sync) | TODO |
| Docs set (README, SECURITY, ARCHITECTURE, …) | IN PROGRESS |

## How to resume work

1. `cd src-tauri && cargo test -p switchtether-core` (70 unit + 13 e2e).
2. `cargo run -p switchtether-core --example dev_server` for a headless server; `dev_client` to call it.
3. Read docs/PROTOCOL.md and docs/AGENT_COMPATIBILITY.md before changing behaviour.
