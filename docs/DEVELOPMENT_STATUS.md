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

## Local 0.1.5 packages (2026-11-18)

Changes since 0.1.4: Android usability pass (docs/ANDROID_UI.md §14): Home running/idle split,
one-tap allow/deny, copy on code blocks and replies, marquee page titles (coui patch 0004), quieter
command blocks (wrap/copy icons), new-session launcher remembers choices and folder, folder picker,
terminal page rework (short title, toolbar menu, follow-at-bottom, ⏎ / keyboard toggle, size
estimate for new terminals), files (discard prompt, search/back fixes, refresh), settings time
pickers and mute switches, "恢复会话" resumes the same session (ended sessions in one tap; a copy
is only a secondary option). Desktop core: live Claude sessions report the transcript's last write
as activity time; background `Bash` tasks run until their task notification / TaskStop; mid-turn
`attachment` records (task notifications, messages typed while the agent works) are parsed.

- Windows x64: `node scripts/package-windows.mjs` succeeded; FluentSetup verify OK for
  `SwitchTether-0.1.5-Setup.exe` (19,393,952 bytes, unsigned).
- Android: signed `SwitchTether-0.1.5-release.apk` (8,492,214 bytes), versionName 0.1.5 /
  versionCode 6; apksigner verifies (v3); signing-certificate digest unchanged.
- Rust core clippy/tests (95 + 3 + 13), desktop clippy / `test --lib switchtether`, Android
  `testDebugUnitTest` (86), `pnpm typecheck`, `pnpm vitest run tests/config` (1,219) passed.
- Copied with `SwitchTether-0.1.5-SHA256SUMS.txt` to `D:/Downloads`; checksums verified.
- Emulator checks (API 36, dev server): pairing, Home grouping and times, one-tap approval writing a
  file, folder picker, terminal, background-task rows, mid-turn message, marquee title. Resuming an
  ended session was not run end to end (the dev server has no history index).
- No installation, agent restart, Git commit or public release publication was performed.

## Resumed sessions are followed at once (2026-11-18, after the 0.1.5 packages)

- A Claude session continued in place from the phone knows its conversation id from the start
  (`--resume` keeps it), so lists, transcript titles and the live registry match it before the
  first new turn; the managed process is also recognised by pid (forks), never listed a second
  time as an observe-only session.
- Continuing a conversation that already runs in a SwitchTether process returns that session
  instead of starting a second writer.
- Logs opened as history keep following their transcript, so a conversation resumed on the
  computer (`claude --resume`, `/resume`, `codex resume`) streams new turns to an open page;
  live-watcher upserts carry the full summary (activity time, pid, transcript path).
- Android: the session page reloads when a session turns live, ends or changes control.
- Core clippy/tests (97 + 3 + 13), desktop clippy / `test --lib switchtether`, Android
  `testDebugUnitTest :app:assembleDebug` passed. Not yet verified end to end against a real
  resumed session on a device.

## Local 0.1.6 packages (2026-11-18)

Changes since 0.1.5: the resumed-session tracking above; on the session page the status capsules
(状态 / 已接管 / 分支 / 终端) float over the top of the conversation (transparent strip, no row
of their own, capsules opaque so text scrolling underneath stays readable; the list leaves 32dp
above the first message); `SmallTopAppBar` subtitles scroll as a marquee too (coui patch 0004).

- Windows x64: `node scripts/package-windows.mjs` succeeded; FluentSetup verify OK for
  `SwitchTether-0.1.6-Setup.exe` (19,375,160 bytes, unsigned).
- Android: signed `SwitchTether-0.1.6-release.apk` (8,475,830 bytes), versionName 0.1.6 /
  versionCode 7; apksigner verifies (v3); signing-certificate digest unchanged.
- Android `testDebugUnitTest` passed. Copied with `SwitchTether-0.1.6-SHA256SUMS.txt` to
  `D:/Downloads`. Not installed on a device; floating capsules and subtitle marquee not yet
  checked on an emulator. No commit or release.

## Fluid Cloud follows every run again (2026-11-18, after the 0.1.6 packages)

- Bug: auto-follow fired only for a session id never seen live on the phone (`knownLive`), while a
  follow ends with its turn. The next turn of a session, or a conversation resumed after its process
  restarted, was therefore never followed again.
- Fix: `AutoFollow.shouldFollow` follows a new session, an idle → busy / waiting transition and a
  session that is live again after `session.removed` / ended; manual unpins stay respected.
  `AutoFollowTest` (4 cases) and the full Android `testDebugUnitTest` pass. The 0.1.6 APK in
  `D:/Downloads` was rebuilt with it (same version; SHA256SUMS updated);
  not yet checked on a device.

## Status changes pushed as they happen (2026-11-18)

- Live watcher: an OS change notification for `~/.claude/sessions` (`notify` 8, CC0-1.0;
  `live::watch_registry`, `.key` files ignored) triggers a re-read after 80 ms of settling; the
  registry is merged per process into the last full list (`live::merge_registry`) and changes are
  published immediately. Nothing runs while nothing changes. Safety nets: a metadata check of the
  `<pid>.json` files every 2 s (0.5 s when no OS watch could be set up; the watch is retried on each
  full refresh) and the full refresh every 4 s. A session missing from the registry is reported ended after 5 s (was two
  4-second ticks). Everything stays gated on a connected phone.
- Tests: `merge_registry`, `registry_stamp`, and `a_short_turn_between_full_refreshes_is_published`
  (a 0.3 s busy turn between all polling ticks yields idle → busy → idle pushes, so the OS watch is
  exercised on Windows; 3/3 runs passed). Core clippy/tests
  (100 + 3 + 13), desktop clippy / `test --lib switchtether` passed. Not yet in a package.
- Docs: AGENT_COMPATIBILITY rows for continuing Claude / Codex histories corrected (continue is
  the default, not always a fork; Codex resume is single-writer, not refused by the API).

## Pairing without fixed IPs, Wake-on-LAN, docs (2026-11-18)

- Pairing code: each pairing session has a 6-digit code (desktop dialog shows it next to the QR
  code, 8 locales). The phone pairs by tapping a computer under "附近的电脑" or typing its address,
  then entering the code; typed addresses use trust on first use and the verification-code check.
  Wrong codes are limited globally (5, then all codes are disabled) on top of the per-IP limit.
- QR pairing no longer depends only on the addresses in the code: when none answers, the phone
  looks for the same certificate on the LAN (NSD) before failing.
- `/v1/status` reports `hosts` and `wake`; the phone refreshes its stored addresses and Wake-on-LAN
  targets on every connection. Offline banner: "唤醒并重试" sends magic packets, then retries.
- Docs: KNOWN_LIMITATIONS (relay, mDNS, code pairing, WoL, Codex single writer), PROTOCOL §3 / §7,
  ANDROID_UI §13.5; fixture `pair_request_code.json` checked by Rust and Kotlin tests.

| Check | Result |
|---|---|
| `cargo test -p switchtether-core` | 105 unit + 3 attach + 14 TLS/API e2e passed (new: code pairing, wake targets, `pairing_with_code_and_status_reports_hosts`) |
| core / desktop clippy `-D warnings`, `cargo test --lib switchtether` | Passed |
| `pnpm typecheck`, `pnpm vitest run tests/config tests/components/RemoteSection.test.tsx` | Passed (1,225 tests) |
| Android `:core:remote:test testDebugUnitTest :app:assembleDebug` | 201 tests, 0 failures, 1 existing skip; Debug APK built |

Emulator (API 36, headless, dev server): typed address `10.0.2.2:47631` + pairing code paired
(TOFU), both sides showed the same verification code; after the first connect the stored hosts
were the desktop's 7 current addresses and 3 wake targets were kept; with the server stopped the
banner offered "Wake and retry", which reconnected once the server was back (no crash). Not
verified: nearby-computer tap on a real LAN (the emulator has no multicast; covered by
Robolectric), a magic packet actually waking a sleeping computer, files upload / download /
rename / delete / merge on a device (the emulator crashed host-side twice, exit 139, before the
file steps), Manage write actions (need the sandboxed desktop app with a CC Switch backend).

## Sub-agent branches (2026-11-18)

- Desktop: `GET /v1/sessions/{id}/subagents` (tree from Claude Code's sidecar metadata, whitelisted)
  and `…/subagents/{agentId}/events` (sub-agent transcript parsed and tailed, cached ≤ 16 / 10 min).
  Unit test `subagent_transcripts_are_listed_as_a_tree_and_tailed` (tree, whitelist, tail, path
  traversal). Checked read-only against real transcripts on this machine through the dev server:
  a session with 14 sub-agents in 3 levels (5 at depth 2, 2 at depth 3) and a sub-agent page of 331
  events (130 tool calls) parsed.
- Android: inline branch lines + "查看过程", sub-agent page with breadcrumb and children, branch tree
  page from a session capsule (docs/ANDROID_UI.md §15). Robolectric `SubagentBranchTest` (tree
  flattening / rails / cycle guard, preview, running vs finished rendering, open callback). Not yet
  seen on a device.
- Codex: tree and pages from the session log (`codex_subagents_come_from_the_session_log`), child rollout
  fallback untested. DeepSeek Harness (dsh) sessions are not adapted by the Remote Core yet (history only
  through CC Switch's reader); its sessions do record sub-agents (`parentSession`, `delegationDepth`,
  `subagent/descriptor`), which a dsh adapter can use.


## Answered question cards show the answer (2026-11-18)

- Desktop: answering a question (Claude Code `AskUserQuestion`, Codex `requestUserInput` /
  elicitation) records the answers on the question event (`data.answers`, keyed like the request;
  answers to `isSecret` questions are stored as a mask, others through `redact_text`). Test
  `recorded_answers_mask_secret_questions`.
- Android: a resolved question card lists the chosen answer(s) under each question (option values
  mapped back to labels); with an older desktop the phone shows what it sent. The bottom line now
  reads "已回答 · 在手机上" / "已取消". `QuestionAnswerTest` (2 cases, light screenshot).

## DeepSeek Harness (2026-11-18)

- Core `agents::dsh`: parser (messages, reasoning, tool calls incl. PTC dispatch nesting, results,
  sub-agent rows from `subagent_fork` / `subagent/catalog`), zstd frame tailing in the shared
  `Tail`, session/child discovery, summary fallback for sessions not in CC Switch's index, live
  (observe) state for recently written logs, sub-agent tree and pages; `agents::dsh::managed`:
  ACP client (`dsh --profile acp`). Tests: 3 parser/frame/header unit tests; real-data checks
  through the dev server (128 nested PTC calls, a forked sub-agent with 400 events).
- Android: DeepSeek Harness in the new-session picker, label, LobeHub DeepSeek mark (arc flags
  expanded for the platform PathParser), notification mute list, sub-agent views enabled.
- Live managed run reached `session/prompt`; the answer failed with the account's
  "Insufficient Balance" on the new `acp` profile — not a SwitchTether error, but a full managed
  turn, approvals and cancel remain unverified. Opt-in plugin takeover: not started.

## DeepSeek Harness bridge plugin (2026-11-18)

- `src-tauri/src/switchtether/dsh_plugin/`: a cordis bundle (`switchtether-dsh-bridge`, Node
  built-ins only) loaded by `dsh web`; attach kind `plugin`/`dsh` in the broker
  (`agents::dsh::attached`); takeover hook `dsh` with backups and exact manifest restore; switch in
  Settings → Remote (8 locales); Android shows the client as "DeepSeek Harness Web".
- Evidence: sandbox `DSH_HOME` install → bundle enabled → `--dump-config` lists it → `dsh web
  --no-open` loaded it and the TCP connection to the broker was established → `dsh plugin remove`
  restored the bundles (pnpm only dropped an empty `dependencies` object, which the hook now
  restores). Fake-host test of every plugin path; broker test with a simulated plugin. Not
  verified: a real dsh web session driven from the phone; whether the dsh UI withdraws its approval
  card when the phone answers first. The user's real `~/.dsh` profiles were not modified.


## DeepSeek Harness stop / error states; attachments as paths (2026-11-18)

- dsh interrupt bug: real logs end turns with `completed`, `interrupted`, `aborted` (`reason.kind:
  "user"`, the stop button in dsh) and `error` (402 quota). Only `interrupted` was recognised, and
  the session list called any log written in the last 2 min "busy", so a stopped turn stayed
  "running" for up to 2 min (plugin-attached: up to 15 s; managed: until another event).
  Now: the parser maps every kind (interrupted marker / error row) and keeps a rolling
  `status:current`; observed sessions read the last turn incrementally per log file (open turn =
  busy ≤ 10 min since the last write, ended = idle / error ≤ 2 min); managed and attached dsh
  sessions take busy / idle from the same state and publish the change; the status relay refreshes
  observed dsh sessions open on the phone. Tests: `every_turn_end_kind_is_reported`,
  `observed_dsh_status_follows_the_last_turn`.
- Attachments: phone uploads any file (≤ 64 MiB, ≤ 10 per message) to
  `<data dir>/attachments/<date>/<id>/<name>` (`~/.switchtether/remote/attachments`, 7 days, 2 GiB
  cap) before sending; the agent gets absolute paths in a marked block (images ≤ 3 MiB also inline
  for Claude Code / Codex); managed Claude Code gets `--add-dir`; the timeline shows the files as
  chips (`data.attachments`). Android: "+" in the composer → photos / files, chips with progress,
  send waits for uploads. Fixtures `send_message_attachments.json`, `attachment_view.json` (Rust and
  Kotlin). Tests: `attachments::*` (4), `attachments_reach_the_agent_as_paths_and_images_inline`,
  `attachmentsUploadFirstThenTravelWithTheMessage`, fixture tests.
- Android: the "显示 / 隐藏" child-steps control on Agent / dispatch rows is a small text toggle
  (`SubStepsToggle`), no longer a full COUI filled button.
- Checks: core clippy / tests (119 + 4 + 14), desktop clippy / `test --lib switchtether`, Android
  `testDebugUnitTest :core:remote:test` (203 tests, 0 failures, 1 existing skip).
- Not verified: a real dsh stop seen on the phone; a real agent reading an attached file; the new
  composer on a device.

## Local 0.1.7 packages, rebuilt (2026-11-18 17:48)

Earlier 0.1.7 files in `D:/Downloads` (17:02–17:07) were deleted and rebuilt with the dsh stop /
error states, attachments and the small child-steps toggle. Windows and Android built in parallel.

- Windows x64: `node scripts/package-windows.mjs` (release build 4 m 54 s); FluentSetup verify OK for
  `SwitchTether-0.1.7-Setup.exe` (19,564,936 bytes, unsigned, sha256 `ae6e7159…0ea0ee4`).
- Android: signed `SwitchTether-0.1.7-release.apk` (8,581,866 bytes), versionName 0.1.7 /
  versionCode 8; apksigner verifies (v3); signing-certificate digest unchanged.
- `SwitchTether-0.1.7-SHA256SUMS.txt` written next to them. Not installed on a device; no commit or release.

## Port forwarding (2026-11-19)

The phone opens the computer's loopback services (dev servers) at the same `localhost:<port>` in
any phone browser, directly or through the relay. PROTOCOL.md §7.11, SECURITY.md §7a,
ANDROID_UI.md §16, KNOWN_LIMITATIONS 10a–10e.

- Core `forward`: leases (step-up `port_forward`, per device and port, 2 h default / 12 h max,
  memory only, ended by stop / expiry / revocation), one multiplexed WebSocket tunnel per lease
  (OPEN / OPENED / DATA / FIN / RESET / WINDOW, 256 KiB per-stream windows, 128 streams), targets
  only `127.0.0.1` / `::1` (parallel connect), listening-port listing (Windows: IP Helper table with
  process names; Linux: `/proc/net/tcp*`; else common-port probe; dev servers ranked first).
  Endpoints `GET/POST /v1/forwards`, `GET /v1/forwards/ports`, `DELETE /v1/forwards/{id}`,
  `GET /v1/forwards/{id}/tunnel`; audit entries; feature `port_forward` in capabilities.
- Desktop: Settings → Remote → 端口转发 lists every phone's forwards with a stop button
  (`remote_list_forwards` / `remote_stop_forward`, 8 locales).
- Android: `:core:remote` `forward/PortForward.kt` (`LocalForwarder`: loopback listener, streams,
  flow control, half-close, reconnect with backoff, self-reloading 502/503 page for browsers),
  `RemoteApi` forward calls + tunnel adapter (20 s pings); app `PortForwards` (step-up, leases,
  resume without step-up, expiry, stats), Workspace card + `PortForwardScreen`, monitor-service
  notification "正在转发 N 个端口" with a stop action.
- Fixtures `forward_view.json`, `forward_frames.json` (Rust and Kotlin).
- `dev_client forward-get <lease> [path] [streams]` (smoke test through a tunnel, honours `ST_RELAY`).

| Check | Result |
|---|---|
| `cargo test -p switchtether-core` | 129 unit + 4 attach + 15 e2e passed (new: `forward::tests::*` ×10, `port_forward_tunnel_carries_http_over_mtls`) |
| core / desktop clippy `-D warnings`, `cargo test --lib switchtether`, `cargo fmt --check` | Passed |
| `pnpm typecheck`, `pnpm vitest run tests/config tests/components/RemoteSection.test.tsx` | Passed (1,226; new RemoteSection forward case) |
| Android `testDebugUnitTest :core:remote:test :app:assembleDebug` | 215 test cases, 0 failures, 1 existing skip; Debug APK built (new: `PortForwardTest` ×5, fixtures) |

Live check on this machine (loopback only, scratch data): the real Go relay (`relay/`, built
locally, `-keys`), `dev_server` registered with it, a Python HTTP server on 39173 standing in for a
dev server, `dev_client` paired, step-up `port_forward`, lease created (`listening: true`).
Through the relay: 8 parallel streams × 5 MiB, every SHA-256 equal to the file, 1.2 s, the relay
log shows **one** relay tunnel carrying 42 MB; direct path identical hashes; a port with nothing
listening returns `RESET refused` through the relay; the port list was also fetched through the
relay; after a desktop restart the old lease's tunnel answers 404 (memory-only leases).

Not verified: the Android app on a device or emulator (the `LocalForwarder` is tested on the JVM
against a fake desktop and the wire format through shared fixtures, but the OkHttp tunnel adapter,
biometric prompt, browser opening, notification action and background behaviour have not run on
Android yet); a real dev server with HMR (Vite) in a phone browser; macOS / Linux port listings on
real machines. No agent configuration was touched; no commit or release was made.

## Android fixes and notification actions (2026-11-19)

From a code audit of the Android app; details in ANDROID_UI.md §17. Fixed: approvals silently not
notified for auto-pinned sessions when Live Updates were off or the card was dismissed; double
alerts per wait; repeated allow / deny taps; question drafts lost on scroll; reading position lost
after a sub-agent page; Git diff retry closing the page; terminal keys dropped while connecting;
typed pairing address locked after a failure; connection notifications piling up. Added: allow /
deny / answer / reply from notifications, question text in the notification, edit for failed
messages, copy for sent messages, waiting-first live list, project filter.

| Check | Result |
|---|---|
| Android `testDebugUnitTest :core:remote:test :app:assembleDebug` | 219 test cases, 0 failures, 1 existing skip; Debug APK built (new: `NotificationTurnTest` ×3, `LiveUpdatesCoverTest`) |

Not verified on a device: notification buttons and inline reply (including the unlock prompt and
the background connection start), the reply spinner being replaced by the outcome, ColorOS
behaviour of RemoteInput choices.

## Local 0.1.8 packages (2026-11-19)

Changes since 0.1.7: port forwarding (direct and through the relay), the Android fixes and
notification actions above. Versions: package.json, tauri.conf.json, FluentSetup 0.1.8; Android
versionName 0.1.8 / versionCode 9.

- Windows x64: `node scripts/package-windows.mjs`; FluentSetup verify OK for
  `SwitchTether-0.1.8-Setup.exe` (19,609,304 bytes, unsigned, sha256 `a124ba93…85d7d8b0`).
- Android: signed `SwitchTether-0.1.8-release.apk` (8,655,630 bytes, sha256 `33763ba5…dadd6688`),
  apksigner verifies (v3); signing-certificate digest unchanged (`214a252b…`).
- `SwitchTether-0.1.8-SHA256SUMS.txt` written next to them in `D:/Downloads`; checksums verified;
  earlier versions kept. Not installed on a device; no commit or public release.

## Official session ids, resume on send, model / effort, request log, cached state (2026-11-21)

User report: request log fails to load; a fresh app start shows nothing from last time; tasks vanish after a desktop
restart; notification order; file viewer hard to use; model / effort only settable (as free text) at creation; sessions
started from the phone are a separate "托管" kind that is hard to find.

- Request log: `GET /v1/usage/logs` answered **400 for every request**: `LogParams` used `#[serde(flatten)]` inside axum's
  `Query`, where serde_urlencoded passes flattened values as strings, so `page=0` never parsed as a number (reproduced
  with the old struct: "Failed to deserialize query string"). Fields inlined; test `request_log_query_parses_numbers`.
- Sessions SwitchTether starts are keyed by the agent's own conversation id (`claude:<uuid>` with `--session-id`,
  `codex:<thread>`, `dsh:<id>`) instead of `managed:<random>`; logs are re-keyed when Codex / dsh name the conversation.
  After the process ends or the desktop restarts the same id is the conversation's history, and **sending to it resumes it**
  (`claude --resume`, `thread/resume`, `session/resume`). No custom `CLAUDE_CODE_ENTRYPOINT` any more.
- `POST /v1/sessions/{id}/options` (model / effort of a running session) and `GET /v1/agents/{agent}/models`; `effort` on
  create and in `SessionSummary`; capability `changeModel`; features `session_options`, `resume_on_send`.
  Fixtures `session_options.json`, `model_options.json` (Rust and Kotlin).
- Android: cached session list + newest events per conversation (`SessionCache`), sessions page opens on "全部";
  model / effort chips in the launcher and a "模型 · 强度" sheet on the session page; resumable history keeps the
  composer; wording no longer presents phone-started sessions as a separate "托管" kind (origin "从手机启动").
- Tests: core `history_is_resumed_on_send_under_the_same_id`, `invalid_model_and_effort_are_refused_before_starting`,
  `model_catalog_merges_agent_lists_with_aliases`, `rekeyed_logs_relabel_their_events`, fixture test; Android
  `SessionCacheTest` (6).
- Live (Claude Code 2.1.325, scratch directory, `haiku`/`sonnet`): see AGENT_COMPATIBILITY §6 `resume_smoke` — official
  id and transcript, model switch effective, resume after a simulated restart remembered the first turn. Codex / dsh
  paths and the Android UI on a device are not verified live.

## Agent map, background tasks, Codex multi-agent v2 (2026-11-21)

- Claude Code managed sessions (stream-json): `tool_use_result` (snake case) and `system` `task_started` /
  `task_progress` / `task_notification` / `background_tasks_changed` are read, so background `Bash` stays running until it
  ends and background / foreground agents get a live row (counters, current activity) from the moment they start.
  Verified with a real managed `claude` 2.1.325 run in a scratch folder (background shell + background agent, idle with
  `backgroundTasks: 1` between turns, no duplicate rows with `--replay-user-messages`).
- Observed Claude Code sessions: a running foreground agent gets its row from the sidecar `meta.json` (`toolUseId`);
  verified against a real 15 s foreground agent (row at ~10 s, result at ~33 s).
- Codex multi-agent v2: rollout `spawn_agent {task_name, message}` + `SubAgentActivity` (both history modes) +
  `agent_message` mail; encrypted messages never shown; child rollouts skip the inherited parent history; app-server
  `subAgentActivity` items, child threads linked without `thread/started`, child item ids scoped per thread; `wait` /
  `sendInput` no longer rewrite the spawned row. Checked against real Codex 0.137 rollouts (parent with three children,
  one child) read-only.
- Agent map (Claude Code IDE "Agent map" for the phone): `GET …/subagents` carries live state, background tasks and
  `stoppable`; `POST …/subagents/{taskId}/stop` (managed Claude Code only, `stop_task`). Android: the sub-agents capsule
  shows a waiting / working / failed dot and opens the map (session root, agent tree with live time and tokens,
  background tasks, in-place cards with activity, result, prompt, "Open transcript", "Stop"). Unit-tested and built;
  not yet run on a device or emulator.
- Notification order (Android): a finished Live Update card no longer flips back to "running" when late transcript steps
  arrive; LiveUpdates state changes and posts are serialised (no lost / out-of-order posts); REST summaries that raced
  newer stream events are dropped; a late reply / tool call no longer replaces "finished"; notifications carry the
  event time (`when`) so re-posts do not reshuffle the shade. Tests `NotificationOrderingTest` (11),
  `LiveUpdatesOrderingTest` (4). Not checked on a device.
- Files (Android): list/viewer rebuilt (sticky path bar, sort, quick filter, icons, lazy list; code viewer with line
  numbers, highlighting, wrap, find, go to line; rendered Markdown; zoomable images / PDF pages) on Telephoto 0.19.0,
  multiplatform-markdown-renderer 0.46.0, Highlights 1.2.0 (all Apache-2.0; release APK +483 KiB). ANDROID_UI §18,
  `FileBrowsingTest` (15). Not checked on a device.
- Full checks after all changes: core clippy / tests (144 + 4 + 15), desktop clippy and `test --lib switchtether`,
  `pnpm typecheck`, `vitest tests/config` (1,219), Android `:core:remote:test testDebugUnitTest :app:assembleDebug`
  (`:app` 122 tests, 0 failures) all passed. No commit.

## Local 0.1.9 packages (2026-11-21)

Agent map, Claude Code background tasks / sub-agents, Codex multi-agent v2, Claude Code session tools (permission
mode, context usage, plan, MCP status, side question, rename, export, run in background, withdraw a queued message,
background task output). "Rewind files" is built but switched off (`REWIND_FILES_ENABLED = false` on both sides;
managed processes run without file checkpoints). Versions: package.json, tauri.conf.json, FluentSetup 0.1.9; Android
versionName 0.1.9 / versionCode 10.

- Windows x64: `node scripts/package-windows.mjs`; FluentSetup verify OK for `SwitchTether-0.1.9-Setup.exe`
  (19,022,576 bytes, unsigned, sha256 `1b164e47…4369d20`).
- Android: signed `SwitchTether-0.1.9-release.apk`, rebuilt the same day after updating vendored coui17 to
  `d2b627e` (patches regenerated, apply strictly; `androidx.dynamicanimation` dropped as upstream did; MPL-2.0 kit
  sources now in commonMain + androidMain, license screen updated). 9,456,218 bytes, sha256 `ce67e0e0…f8436fad4c9b6e1`;
  apksigner verifies (v3); signing-certificate digest unchanged (`214a252b…`); no `org/lsposed` classes in the dex.
  Same version (0.1.9 / 10) as the first 0.1.9 APK, which it replaces in `D:/Downloads`.
- `SwitchTether-0.1.9-SHA256SUMS.txt` next to them in `D:/Downloads`; checksums verified; earlier versions kept. Not
  installed on a device; no commit or public release.

- Android rebuilt again (same 0.1.9 / 10, replaces the APK in `D:/Downloads`): Agent map connectors drawn as lines and
  root "This session", New agent scrolls, file preview redesign (COUI header, Rich text / Code switch, code cards), session
  tools for attached sessions and read-only tools for observed Claude sessions. 9,473,706 bytes, sha256
  `6dc464a6…dde077daf`; apksigner v3, certificate `214a252b…`; no `org/lsposed` classes. Not run on a device.

## Local 0.1.10 packages (2026-11-21)

Official model lists (probe), effort slider, options kept for a resume, session tools for attached sessions and
read-only tools for observed ones, photo thumbnails / previews in the composer and in sent messages; the session page
keeps the original title bar (back, Live Update tracking) and capsule row, with a session-tools capsule added next to
the sub-agents. Versions 0.1.10 (Android versionCode 11).

- Windows x64: `SwitchTether-0.1.10-Setup.exe` (19,040,728 bytes, unsigned, sha256 `22c77247…512c26bd`), FluentSetup
  verify OK; installed on this machine with `/quiet` over 0.1.9 (registered 0.1.10) and relaunched.
- Android: signed `SwitchTether-0.1.10-release.apk` (9,490,362 bytes, sha256 `e1ac2058…a9eb7afd`), apksigner v3,
  certificate `214a252b…`. Not run on a device.
- `SwitchTether-0.1.10-SHA256SUMS.txt` in `D:/Downloads`; checksums verified.

## coui17 → `7135f75` (2026-11-23, uncommitted)

Vendored coui17 synced to upstream `main` `7135f75` (2026-11-22); patches 0001–0004 apply unchanged; no new
dependencies. New in the library: tap the top app bar (and on ColorOS the status bar, via the `com.oplus.clicktop`
broadcast) to scroll to the top, a media progress slider, conic light and a material backdrop API. The app binds its
top-level page lists and scroll bodies (`rememberPageListState()` / `rememberPageScrollState()` in
`ui/common/Page.kt`: home, sessions, workspace, manage, settings, downloads, port forwards, agent map, sub-agents,
session tools, New session, onboarding) plus the file browser, code viewer and PDF page lists; chat and terminal
(bottom-following, chat loads older history near the top) are deliberately not bound.
The video/audio viewer's hand-drawn seek bar is replaced by coui17 `IntentSlider` (COUI 17 `COUIIntentSeekBar`) in its
over-media style: fixed white palette, conic light, thumb while touched/paused, ExoPlayer buffered position as the
secondary track, loading sweep while buffering; a drag seeks once on release. No `LocalMaterialBackdrop` is provided:
the only consumer is that slider, which sits over a `SurfaceView` a `LayerBackdrop` cannot record, so it keeps COUI's
own translucent non-blur fill. Touch motion (press / deform / rubber band) comes with coui buttons and bars as-is.

## Android 0.1.12: button tiers, panels, new-session launcher (2026-11-24)

docs/ANDROID_UI.md §22. In-page actions no longer use COUI's Large (44dp) capsule: `SmallButton` / `LinkButton`
(`core:ui`), panel actions through `AppBottomSheet(confirm = …)` and `SheetButtons` (coui `DialogButtonBar`).
Half-screen panels are drawn on the page colour with the page's gutters; the new-session panel starts from its toolbar.
Filled buttons use the brand accent (`containerTheme`). A second pass on whole rendered pages (§22.6): one 32dp text gutter,
chat-row copy / time alignment, session-row icon gap, arrows on Workspace rows, the folder picker's confirm in its title bar.
Rendered with `SheetGalleryScreenshotTest` and `PageGalleryScreenshotTest` (Robolectric, API 32); not run on a device or emulator.
