# SwitchTether Android — running dev notes

Living log so work survives context limits. Newest entries at the bottom of each section.

## Toolchain (decided)
- Gradle wrapper 9.9.2 from services.gradle.org, `distributionSha256Sum=971fcd71…c76b` (official
  `.sha256`); `gradle-wrapper.jar` sha256 `621008af…daf8` (regenerated with `./gradlew wrapper`).
- AGP 9.5.2, Kotlin 2.4.21, Compose MP 1.12.2 (androidx.compose pinned 1.13.0, which needs compileSdk 37.2).
  coui17 @ a51f957 upstream uses AGP 9.3.1 / Kotlin 2.4.10 / CMP 1.11.1; materialkolor stays on 5.0.1
  because 6.x removes `DynamicScheme` used by coui-ui.
- JDK 21 (`JAVA_HOME="C:\Program Files\Java\jdk-21"` per command). compileSdk 37.2, minSdk 29, targetSdk 36.
- `local.properties` has `sdk.dir=D:/AndroidSDK` (gitignored).
- Build: `JAVA_HOME=... ./gradlew :app:assembleDebug testDebugUnitTest :core:remote:test`

## Modules
- `:core:remote` pure JVM: protocol models, pairing link parser, pinned mTLS OkHttp factory, RemoteApi,
  event-stream reducer/client, agent-event log reducer, command outcome helper, step-up payload.
- `:core:security` Android lib: Keystore identity (TLS client cert) + step-up key + BiometricPrompt.
- `:core:ui` Android lib: theme over COUITheme, markdown/diff/code renderers, charts, state views.
- `:app`: screens, ViewModels, ConnectionManager, DataStore, DI container (manual).
- `third_party/coui17/*`: vendored KMP (android-only) modules, see UPSTREAM.md / PATCHES.md.

## Decisions
- coui17 vendored at a51f957 via `third_party/coui17/sync_coui17.py` (git archive, LF forced with
  `-c core.autocrlf=false -c core.eol=lf`; patches re-applied from `patches/`).
- Patch 0001 removes `org.lsposed.hiddenapibypass` (AOSP API 36 `View.setBackdropRenderEffect`
  path). Non-OPPO devices fall back to the Compose blur engine (same as API < 36).
- App theme provides `LocalPlatformAppBarMaterialEnabled/LocalPlatformBottomBarMaterialEnabled = false`
  so the Oplus private-framework reflection paths are not exercised.
- No navigation library: a small typed back stack held in a ViewModel (tabs + detail stack).
- Manual DI (`AppContainer`) instead of Hilt to keep the build small.
- Identity key authorizes DIGEST_NONE in addition to SHA-256: Conscrypt's TLS client-auth upcall
  signs the pre-hashed handshake digest with `NONEwithECDSA`; without it mTLS fails.
- HostnameVerifier accepts any host: exact leaf-cert pin is the trust anchor (IPs / MagicDNS not in cert).
- `retryOnConnectionFailure(false)`: OkHttp must never silently replay a side-effecting request.
- Verification code is computed locally too (same formula as desktop `util::verification_code`) and
  compared with the server's; mismatch is shown as a warning.

## Server facts (from lead, 2026-11-14)
- Dev server: `cargo build -p switchtether-core --example dev_server` then
  `target\debug\examples\dev_server.exe --port 47615 --data-dir <scratch>`; prints a pairing link with
  `h=10.0.2.2,...` (emulator → host loopback). `pair` + Enter on stdin prints a new link.
- CC Switch endpoints return 503 `backend_unavailable` on the headless dev server (expected).
- `GET /v1/sessions/{id}/events?limit=` → `{events:[…], hasMore, lastSeq}`.
- Approval/question events are UPDATED IN PLACE (`data.resolved=true, decision, by`); no separate
  approval_resolved event. Rolling status event has id `status:current`.
- Hosts in the link may be bracketed IPv6 (`[fd62:…]`), `n` percent-encoded.
- Command record fields are `createdAtMs/completedAtMs` (doc says createdAt) — both accepted.

## Status log
- [x] Wrapper + catalog + vendored coui17 compile (`:coui-preference:compileAndroidMain` OK).
- [x] core:remote + tests (38 JVM tests incl. real TLS1.3 mTLS)
- [x] core:security
- [x] core:ui
- [x] app screens (+ New managed session flow) — assembleDebug OK
- [x] Robolectric UI tests (app: 19 tests; total unit tests 61 green)
- [x] emulator E2E against dev server (AVD st_e2e_api36 in scratch ANDROID_AVD_HOME, port 5580; PIN 1234)
  - pairing via pasted link (7 host candidates incl. bracketed IPv6) -> mTLS with Keystore key OK, code 905119 matched desktop
  - Home: live sessions, usage 503 shown honestly; Sessions list; History empty (dev server has no CC Switch index)
  - session detail of real VS Code session: BLOCKED caps shown, transcript rendered
  - step-up (Settings): BiometricPrompt device-credential PIN -> grant config_write OK
  - New managed session (haiku) -> live events, send message, approval Allow from phone -> file written (approved.txt, second.txt)
  - fixed during E2E: NetworkOnMainThread (body read on main), stale keep-alive (pool 20s < server 30s; GET-only retry client),
    noisy system rows hidden, approval confirm dialog, glass blur disabled (LocalBlurEnabled=false, translucent on emulator)
- [x] coui17 adoption pass: Scaffold+collapsing TopAppBar, group cards+cardListPosition, BottomFloatingNavigationBar, TabRowWithContour, ChipTabGroup, PullToRefresh, EmptyState, FAB, Switch/RadioButtonPreference, WindowDialog; brand #1F6BFF; adaptive logo icon; blur off; screenshots in docs/screenshots

## Phase B (2026-11-14): terminal (TerminalViewModel/TerminalPane, core terminal reducer+KeyEncoder), files/git (FilesViewModel/FilesPane, conflict flow, SAF upload, MediaStore download), MonitorService (connectedDevice FGS) + Notifier (NotificationEngine), StepUpGate, NewSessionSheet, catch-up after=, provider credential update. Tests: 73 green.

- Phase B E2E (sandbox app :47617): providers/usage/quota real shapes, terminal echo hi, files conflict keep-copy, git status/log, FGS + approval notification. Fixes: Long/Int sort crash, singleTask deep link, unpair race, trend labels, quota reason, terminal IME diff input, JCE fork for tests.

## Emulator / E2E how-to (2026-11-14)
- AVD `st_e2e_api36` lives in the scratch `ANDROID_AVD_HOME` (…/scratchpad/avd); start with
  `emulator -avd st_e2e_api36 -gpu host -port 5580` (with `-gpu swiftshader_indirect` RenderEffect blur
  is NOT rendered, so the glass capsule only looks translucent). Lock-screen PIN 1234 (needed for step-up).
- Drive only via adb: `uiautomator dump`, `input tap/text`, `exec-out screencap -p`
  (scratch helpers tools/ui.py, tools/capture.sh). Pairing links also open via
  `adb shell am start -a android.intent.action.VIEW -d '<switchtether://pair?...>'`.
- Screenshots: android/docs/screenshots/{light,dark}/01..10.

## Phase C (2026-11-14)
- Icons: library glyphs only (COUIIcons / COUIIcons.Basic). A hand-drawn NavIcons.kt was written,
  then deleted at the lead's request. Workspace uses Folder/FolderFill; the other tabs rely on the
  item's selected tint.
- Session UI redesign:
  - core/ui components/Chat.kt: segment(), AgentAvatar, ChatBubble, StepStatusIndicator,
    CollapsibleRow, AttentionCard, TimelineCard, InputPill, RoundIconButton, InfoChip.
  - app Timeline.kt: step folding and pending keys.
  - Renderers.kt rewritten.
  - SessionDetailScreen: chips, info sheet, TopTips, pending banner, composer pill.
  - SessionRow: avatar with status dot.
- Continue on phone: CreateSessionBody gained resumeFrom/mode (cwd now optional),
  SessionCapabilities gained continueSession, and the VM has continueOnPhone(fork).
  E2E setup: ran `claude -p` in android/build/e2e-cwd, then copied the transcript into the sandbox
  home's .claude/projects so the sandbox desktop lists it in History. Continue and fork both verified.
- zh-CN: every string translated; added NotifyText, UiText and relativeTime; coui patch 0002
  (LocalSheetCloseLabel).
- Typing on the emulator: with the app locale set to zh-CN, Gboard starts in pinyin and composes
  whatever `adb shell input text` sends. Tap the globe key to switch to English before typing.
- Tests: 78 green (core:remote 49, core:ui 4, app 25; new: TimelineTest x3, continue VM tests x2).

## Phase D (2026-11-14)
- Icons:
  - Tried Material icons-extended first; the user found them ugly.
  - Now Phosphor (`com.adamglin:phosphor-icon:1.0.0`, MIT): Regular when unselected, Fill when
    selected, all through `AppIcons`.
  - settings.gradle.kts: the `google()` repository is now limited to `com.android` / `com.google` /
    `androidx` groups. A dl.google.com hiccup was blocking resolution of Maven Central-only groups.
  - Release APK: 6,957,361 → 6,878,289 bytes.
- Home is task-first: no connected card; order is waiting, running (compact rows), recent, then a
  one-card "更多" section.
- Brand marks: LobeHub claude.svg and openai.svg converted to VectorDrawables
  (scratch script svg2vd.py, path copied verbatim). See third_party/brand-icons/NOTICE.md.
- Attached sessions: `SessionSummary.terminalId` / `isAttached`; badge "已接管 · …"; composer kept for
  `control == full`; no 接续; "打开终端" goes through `AppContainer.openTerminalRequest` to Workspace.
- Screenshots: redact.py masks session rows whose project isn't e2e-cwd (Home only). capture_d2.sh
  aborts if an expected test row isn't on screen. One unguarded dark-mode run opened a real session;
  those images were deleted, never copied into the repo.
- Tests: 80 green (2 new Compose tests: attached session + task-first Home).
