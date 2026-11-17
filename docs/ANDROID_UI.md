# SwitchTether Android app — architecture & UI

Native Android client (Kotlin, Jetpack Compose, Coroutines/Flow, Jetpack ViewModel/Lifecycle/
DataStore). It pairs with **one** desktop running SwitchTether and talks to the real Remote API
(docs/PROTOCOL.md). No WebView anywhere — markdown, diffs, code, charts and the QR preview are
native (the camera preview is CameraX's `PreviewView`).

Source: `android/`. Build: see `android/DEV_NOTES.md`.

## 1. Modules

```
:app                 Compose screens, ViewModels, ConnectionManager, DataStore stores, manual DI
 ├─ :core:remote     pure JVM: protocol models, pairing link, pinned mTLS OkHttp, RemoteApi,
 │                   event stream + reducer, AgentEventLog reducer, command outcome logic,
 │                   step-up payload (unit-tested on the JVM incl. real TLS 1.3 handshakes)
 ├─ :core:security   Android Keystore identity (TLS client cert) + step-up key, BiometricPrompt signer
 └─ :core:ui         SwitchTetherTheme (over COUITheme), markdown / diff / code renderers,
                     Canvas bar chart, state views, chips
third_party/coui17/* vendored coui17 @ a51f957 (Android-only KMP modules), see UPSTREAM.md / PATCHES.md
```

Layering: Compose screen (stateless `XContent(state, callbacks)` + thin `XScreen(vm)`) →
ViewModel (`StateFlow` state) → repository interfaces (`SessionRepository`,
`ManagementRepository`, `PairingRepository`) → `RemoteApi` + `ConnectionManager` → security.
Tests use fake repositories; production code contains no mock data.

## 2. Security implementation

| Requirement | Where |
| :--- | :--- |
| Fresh P-256 identity per pairing, non-exportable, PURPOSE_SIGN, SHA-256 (+ NONE, see below); self-signed cert = TLS client cert; deleted on unpair/revoke | `KeystoreKeys.createIdentity` / `AppContainer.wipeLocal` |
| TLS 1.3 only, TLS 1.3 AEAD suites only, HTTP/1.1 (ALPN) | `TlsClientFactory.TLS13_ONLY` |
| Client auth with the Keystore key | `SingleIdentityKeyManager` |
| Only the pinned leaf (SHA-256 of DER, constant-time compare) is trusted | `PinnedServerTrustManager` |
| HostnameVerifier accepts any host | documented in `TlsClientFactory.create`: the exact leaf pin is the trust anchor; IPs/MagicDNS names are not in the self-signed cert |
| Same OkHttpClient for REST + WebSocket; no silent retries (`retryOnConnectionFailure(false)`) | `TlsClientFactory`, `RemoteApi` |
| Step-up key (`setUserAuthenticationRequired`; API 30+ per-use BIOMETRIC_STRONG\|DEVICE_CREDENTIAL via CryptoObject; API 29 30 s validity) | `KeystoreKeys.createStepUpKey`, `BiometricSigner` |
| Step-up payload `switchtether-stepup-v1\n<deviceId>\n<challengeId>\n<nonce>\n<scope>` | `StepUpPayload` (JVM-tested) |
| No secure lock screen → pair without step-up key, warn that terminal/file writes are unavailable | `DefaultPairingRepository`, onboarding banners |
| Pairing persistence without secrets (keys stay in Keystore; secret never stored) | `PairedDesktop` in DataStore |
| Verification code: shown from the server **and** recomputed locally (`switchtether-sas-v1` formula); mismatch blocks "Continue" | `Fingerprints.verificationCode`, `VerificationContent` |
| No transcripts / file contents / credentials on disk; only metadata + drafts | `AgentEventLog` is in memory; drafts in DataStore |
| Backups disabled (`allowBackup=false`, extraction rules exclude all) | `AndroidManifest.xml` |

Identity keys authorize `DIGEST_NONE` in addition to SHA-256 because Conscrypt's TLS
client-auth upcall signs the pre-hashed transcript with `NONEwithECDSA`.

## 3. Connection & event stream

`ConnectionManager` (single desktop): last good host first (3 s), then all host candidates raced
in parallel (`HostRace`) → `GET /v1/hello` (compatibility + `paired`) → `GET /v1/status`
(epoch/headSeq) → `GET /v1/events?since=&epoch=` WebSocket with JSON `ping` every 20 s.
`EventStreamReducer` de-duplicates by `seq`, turns `resync` frames / epoch changes into REST
refetches; seq gaps are normal (device-scoped events). Backoff 1 s…60 s with jitter; network
callbacks skip the wait. Close codes 4401/4403, HTTP `unpaired`/`device_revoked` (from any
screen's REST call), `hello.paired == false` or a `device.revoked` event for our id → keys and
pairing wiped, onboarding shows the reason. Runs only while the app is in the foreground
(ProcessLifecycleOwner); the "background connection" toggle is stored for Phase B.

## 4. Commands (idempotency, unknown outcomes)

Every side-effecting call carries a UUID `commandId` (body + `Idempotency-Key`). `Commands.run`
classifies: `Succeeded` (with `X-Command-Replayed`), `Rejected` (definitive server error, e.g.
422 `capability_unavailable` → "BLOCKED" dialog), `NotSent` (connect failed before the request
was written — tracked with an OkHttp `EventListener`), `Unknown` (written, no answer),
`InProgress` (409). `CommandTracker` never retries by itself: for Unknown it offers "Check
status" (`GET /v1/commands/{id}`); only then a retry is offered — same id if the desktop never saw
it (`not_found`), a new id if it failed; nothing if it succeeded. Offline: drafts are kept,
nothing is queued.

## 5. Native agent renderer

`SessionDetailViewModel` keeps an in-memory `AgentEventLog` (keyed by event id, ordered by seq).
Initial page = newest 300 events (`hasMore` → "load older" via `before=minSeq`); live
`session.event` updates are upserted by id (streaming text grows in place); on reconnect/resync
the newest page is reloaded. `renderItems()` attaches `tool_result` to its `tool_call` (by
`callId`), folds separate `approval_resolved` into the request, computes sub-agent depth from
`parentId`, and supports collapsing sub-agent groups. The rolling `status:current` event is shown
in the header instead of the list. The `LazyColumn` uses `key = event.id` and
`contentType = kind`, so a streaming update recomposes one row.

Registry: `AgentEventRegistry.renderers: Map<kind, @Composable (RenderItem, RenderContext)>`;
unknown kinds → `GenericEventCard`; `ui` events dispatch again on `data.component`
(`UiComponentRegistry` + `BuiltinUiComponents`), falling back to a JSON card.

| kind | Renderer | Status |
| :--- | :--- | :--- |
| `user_message` | tinted bubble, markdown, source label, image count | ✅ |
| `assistant_message` | markdown (native), model label, streaming pill | ✅ |
| `reasoning` | collapsible italic text (first line when collapsed), summary flag | ✅ |
| `tool_call` | dense card: category tag (SH/EDIT/READ/FIND/WEB/MCP/TASK/…), title, status pill, expandable input (command as bash block, else JSON) + attached result, progress while running, sub-agent show/hide | ✅ |
| `tool_result` | monospace block, exit code / error color, 4 KB preview + "show all", "load full output" through `…/payload` (chunked, memory only) | ✅ |
| `file_change` | change pill, path, +/−, native unified-diff view (gutters, colors, hunk headers, show-more) | ✅ |
| `plan` | checklist ☐ ◐ ☑ with done count | ✅ |
| `approval_request` | title/detail/command/cwd/tool/input, option buttons → confirm dialog → command; disabled + explanation when `approve` ≠ VERIFIED or offline; resolved in place (`data.resolved/decision/by`) | ✅ |
| `approval_resolved` | folded into request, standalone row otherwise | ✅ |
| `question` | single (radio) / multi (checkbox) select, submit (answers keyed by question text, multi joined by ", "); disabled + explanation when `answerQuestion` ≠ VERIFIED | ✅ |
| `subagent` | row with description/status, show/hide children; children indented by `parentId` depth | ✅ |
| `status` | compact state pill + detail; `status:current` in header | ✅ |
| `error` | red card, selectable | ✅ |
| `system` | muted divider-style row (`subtype: text`) | ✅ |
| `usage` | Σ in/out/cache tokens + cost | ✅ |
| `ui` | registry by `component`; built-ins `core.progress`, `core.keyValue`; JSON fallback | ✅ (partial: 2 built-ins) |
| unknown | generic card with kind + pretty JSON | ✅ |

Composer: enabled only when `capabilities.sendMessage == VERIFIED` and online; otherwise the
reason is shown and the text field acts as a local draft. Interrupt (toolbar) only when
`interrupt == VERIFIED`, with confirmation. A capability strip shows send/approve/answer/
interrupt status for the session (`VERIFIED/PARTIAL/BLOCKED/UNTESTED`), plus the server's reason.

Markdown: own parser (`MarkdownParser`: ATX headings, paragraphs, fenced/indented code, quotes,
nested/ordered/task lists, pipe tables with alignment, rules) + `InlineMarkdown` (emphasis,
strong, strike, code spans, links/autolinks, escapes; images become `[image: alt]` — nothing is
fetched). Links require a confirmation dialog before opening. Code: lightweight tokenizer
(`SyntaxHighlighter`) for keywords/strings/comments/numbers.

## 6. Screens

- **Onboarding/pairing:** security explanation, QR scan (CameraX + ZXing core), paste link,
  `switchtether://pair` deep link (prefills the confirm screen only), confirm (name, addresses,
  grouped fingerprint), progress, typed failure reasons, verification code.
- **Home:** connection card, live/waiting counts, usage today/month (honest "unavailable" state on
  503), live + recent sessions, quick links (incl. New session).
- **Sessions:** search (debounced, server `q`), Live/History/All tabs, agent/project chips,
  status/waitingFor/control/managed badges, paging; **New session** (toolbar +): "New
  SwitchTether-managed session" — agent (Claude Code/Codex), working-directory picker
  (`/v1/fs/roots`, `/v1/fs/list`, sensitive/blocked shown), optional first message, model,
  Claude permission mode (default/acceptEdits/plan; bypassPermissions intentionally not offered),
  confirm → `POST /v1/sessions` → opens the new session.
- **Manage:** Providers (app tabs, current highlighted, switch with confirm, connection test,
  secrets only as configured/hint), Usage (range tabs, summary, Canvas daily chart with
  accessibility summary, per-model and per-provider bars, quota cards with the server's reason
  when unavailable), MCP / Skills (per-app switches, confirm) / Prompts (enable with confirm).
- **Settings:** paired computer (fingerprint grouped by 4, last host, key storage level), unpair
  (`DELETE /v1/device` then wipe), "verify biometric confirmation" (real step-up round trip),
  appearance (system/light/dark), background-connection toggle (stored; Phase B), notification
  preferences per event type (stored; Phase B delivery), clear cache, version, licenses.
- **Workspace:** Phase B placeholder (terminal, files, git).
- **Navigation:** typed back stack in a ViewModel; bottom `NavigationBar` < 600 dp, `NavigationRail`
  ≥ 600 dp (landscape/tablets); edge-to-edge; predictive back enabled.

## 7. Design

- COUI17 (ColorOS 17) look for normal pages: `COUITheme`, `SmallTopAppBar`, `Card`, preference
  rows, `TabRow`, `NavigationBar/Rail`, `WindowAlertDialog`, `Switch`, `Button`.
- Dense developer layout for code/diff/tool output: monospace 11–12 sp, compact rows, tags
  instead of decorative icons, `DevColors` palette tuned separately for light/dark.
- No gradients or decorative cards; real loading / empty / error / offline / 503 states.
- Accessibility: text labels on every status (never color-only), 40–48 dp touch targets,
  content descriptions on icon buttons, `stateDescription` on collapsibles, live regions for
  connection changes, headings, chart summary for TalkBack, font scaling respected (sp).
- Strings in English and Simplified Chinese (`values`, `values-zh-rCN`).

## 7a. coui17 / ColorOS 17 adoption (per screen)

Page structure follows the coui17 gallery (`example/shared` @ a51f957): coui `Scaffold` on
`AppBarBlurOverlayDefaults.containerColor()` (the ColorOS page colour), a large collapsing
`TopAppBar` + `COUIScrollBehavior` (nested scroll + `overScrollVertical`) on top-level pages,
`SmallTopAppBar` on detail pages, and `LazyColumn`s of `SmallTitle` + group `Card`s
(`CardDefaults.GroupCornerRadius/GroupWeight`) whose rows carry `cardListPositionOf(...)`.
Glass blur is used only for the floating navigation capsule: the shell records page content into a
`rememberLayerBackdrop` layer (sibling of the bar, as in the gallery) and provides `LocalBackdrop` +
`LocalBlurEnabled = true` to the bar only; pages/dialogs get `LocalBlurEnabled = false` and keep opaque
COUI fills. Verified blurred on the API 36 emulator with `-gpu host` (with `swiftshader` the
RenderEffect blur is not rendered and the capsule looks merely translucent);
the accent is the brand blue `#1F6BFF` (dark: `#4A86FF`) fed into `lightColorScheme/darkColorScheme`.

| Screen | coui17 components |
| :--- | :--- |
| Shell | `BottomFloatingNavigationBar` (floating glass capsule over a `LayerBackdrop` of the page, `expansion` "+" melt-ball on the Sessions tab → New session, hidden with animation on detail pages) + `BottomFloatingNavigationBarItem` (`icon`, plus `selectedIcon` where coui-icons has a Fill glyph); `NavigationRail` + `NavigationRailItem` (≥ 600 dp); `COUIIcons` |
| Home | `Scaffold`, `TopAppBar` (large, subtitle = desktop), `Card` groups, `BasicComponent` (connection, session rows, states), `ArrowPreference` + icons (quick links), `SmallTitle`, `TextButton` |
| Sessions | `TopAppBar`, `TextField`, `TabRowWithContour` (Live/History/All), `ChipTabGroup` (agents), `PullToRefresh`, `BasicComponent` rows in group cards, `EmptyState` (New session = capsule expansion) |
| Session detail | Redesigned in Phase C, see §11 |
| New session | `SmallTopAppBar`, `TabRow`, `RadioButtonPreference` (permission mode), `TextField`, `Button`, group cards, `WindowAlertDialog` |
| Manage | `TopAppBar`, `ArrowPreference` with icons in group cards |
| Providers / Usage | `SmallTopAppBar`, `TabRow`, group cards, `TextButton`, `LinearProgressIndicator`, page-level error state |
| MCP / Skills / Prompts | `SwitchPreference` / `RadioButtonPreference` rows with `cardListPosition`, `WindowAlertDialog` confirms |
| Settings | `TopAppBar`, `ArrowPreference`, `RadioButtonPreference` (theme), `SwitchPreference`, `KeyValue` rows in group cards |
| Workspace | `TopAppBar`, `EmptyState` in a group card |
| Onboarding | coui `Card`, `Button`, `TextButton`, `TextField`, `InfiniteProgressIndicator` |

Screenshots (emulator API 36, captured over adb only): `android/docs/screenshots/light/*.png` and
`android/docs/screenshots/dark/*.png` — 01-home, 02-sessions, 03-session-managed,
05-new-session, 06-manage, 07-providers-503, 08-usage-503, 09-workspace, 10-settings.
(04-session-observed was captured but is not committed: it showed a private live transcript.)

Launcher icon: adaptive icon, `#1F6BFF` background + vector foreground converted from
`src/assets/icons/switchtether-logo.svg` (monochrome layer for themed icons).

## 8. coui17 integration

Vendored source at a pinned SHA (no SNAPSHOT/JitPack), Android-only KMP modules with our build
files, `hiddenapibypass` patched out, Oplus private materials disabled at the theme root, Apache
NOTICE + MPL headers kept, MPL source link in the licenses screen. Re-sync with
`third_party/coui17/sync_coui17.py`.

## 10. Phase B — Workspace, background, notifications

### 10.1 Terminals (§7.6)
- `TerminalViewModel` keeps one live stream (the selected terminal). Frames are parsed by
  `TermFrame` and reduced by `TermScreen.apply` (snapshot replaces, patch replaces changed rows,
  stale patches ignored, exit). Reconnect = new snapshot.
- `TerminalPane`:
  - Chrome: coui `ChipTabGroup` tabs; `WindowListPopup` shell picker (from `GET /v1/terminals`
    `shells`).
  - Rendering: monospace rows as `AnnotatedString`s with the 256-colour palette, `#rrggbb`,
    bold/italic/underline/inverse and an inverted cursor cell; font size A-/A+.
  - Sizing: cols/rows are measured from the cell size and sent as `resize` (debounced).
  - Scrollback: paged from `…/scrollback`.
  - Clipboard: copy screen, long-press a line to copy, paste (bracketed when the program enabled it).
- Input:
  - An invisible `BasicTextField` whose edits are diffed, so composing IMEs work; a replacement
    becomes Backspace + text.
  - An extra-key row: Esc, Tab, latching Ctrl/Alt, arrows, Home/End, PgUp/PgDn, `| ~ / -`.
  - `KeyEncoder` handles application-cursor mode (`ESC O A`), CSI modifiers (`ESC[1;5C`) and
    Ctrl→C0 (Ctrl+C = 0x03).
- Step-up `terminal` (`StepUpGate`) runs before create/attach. On expiry (`error step_up_required`
  frame, close 4401, or 403 on upgrade) it asks again instead of failing.

### 10.2 Files & Git (§7.7)
- Browse: roots and directory list (hidden toggle). Sensitive entries show a badge and need a
  confirm before reading with `allowSensitive`.
- Name/content search, a viewer using the existing highlighter, and a monospace editor. Saves send
  `baseSha256`.
- Save, create, rename, trash and upload need step-up `files_write` (asked again once if the
  server says the grant expired).
- **409 conflict** opens `ConflictState`: a native unified diff (desktop → mine, `TextDiff`) plus
  four actions:
  - Keep mine as a copy: `createOnly` to the server's `suggestedCopyPath`.
  - Reload the desktop version.
  - Overwrite: re-reads the current sha, then writes, after an explicit confirm.
  - Manual merge: an editor seeded with conflict markers, saved against the desktop sha.
- Upload: SAF `OpenDocument` → `POST /v1/fs/upload` → chunked `PUT …?offset=` (2 MiB chunks,
  `UploadPlan`). It shows progress and can be cancelled; cancel also deletes the server-side upload.
- Download: streamed into `MediaStore.Downloads/SwitchTether`; no storage permission needed.
- Git: status (branch/upstream/ahead/behind; the default repo is the cwd of the first live
  session), per-file diff (native `DiffView`), recent log, file at revision.

### 10.3 Background connection and notifications
- **Foreground service type: `connectedDevice`.**
  - **Why it fits:** the app's core function is a live network connection to the user's own
    paired computer, an external device. Android lists "network connection to an external device"
    under this type. Its Android 14+ runtime prerequisite is met by the normal-level
    `CHANGE_NETWORK_STATE` permission.
  - **Rejected types:**
    - `dataSync`: on Android 15 it has a 6 h / 24 h budget and is meant for bounded transfers.
    - `remoteMessaging`: meant for continuing a user's text-message conversation across devices.
    - `specialUse`: reserved for cases where no standard type fits, and needs a Play justification.
  - **Start/stop:** the service starts only from the foreground (Android 12+ restrictions),
    controlled by "Stay connected in background" (default **on**, user can turn it off). It stops
    on unpair or when the toggle is turned off. Its ongoing notification is on the `service` channel.
  - **Battery:** Settings → Notifications → Battery optimization opens the system
    ignore-optimization list with an explanation. The app does not request
    `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Play policy).
- **Rules** (`NotificationEngine`, pure and unit-tested):
  - Sources: `session.event` (`approval_request` and `question` while unresolved, `status`
    transitions, `tool_call`, final `assistant_message`) and `session.upserted` (`waiting` /
    `waitingFor`).
  - Also: connection lost/restored, and a long-running reminder (30 min busy).
  - Notifications are de-duplicated by key.
  - Per-type enable, with one Android channel per type, so sound and vibration are set per
    channel in system settings.
  - Muted agents and projects.
  - Quiet hours (may wrap midnight) make notifications silent.
- **Privacy:** `VISIBILITY_PRIVATE` with a redacted public version ("An agent needs your approval").
  Titles and details appear only when unlocked; code or message text never appears on the lock
  screen. Tapping a notification opens the session (`EXTRA_SESSION_ID`, `singleTask`).
- Notifications are posted only while the app is not in the foreground. Drafts are never sent
  automatically. POST_NOTIFICATIONS is requested once when the main UI first appears (Android 13+).

### 10.4 Other Phase B changes
- New managed session is now a coui `WindowBottomSheet` opened from the capsule "+" (the page
  route still exists for deep links).
- Reconnect catch-up: an open session fetches `after=lastSeq` pages until `hasMore=false`
  (max 20 pages). It falls back to the newest page on epoch change.
- Providers:
  - `secretFields: [{field, name, configured, hint}]` are parsed and shown.
  - "Update" sends a write-only credential behind step-up `config_write`.
  - Quota shows `credentialStatus` as the honest reason when there is no explicit reason.
- AskUserQuestion answers are keyed by question text (`{"<question>": "<label(s)>"}`). This is
  **UNTESTED** live.
- Fixes found in E2E:
  - **Sort crash:** the session-sorting comparator mixed Long and Int and crashed when
    `lastActiveAtMs` was null.
  - **Duplicate activities:** a pairing deep link created a second activity; now `singleTask`.
  - **Wrong revoke reason:** a user-initiated unpair showed "revoked by desktop".

| Workspace feature | Status |
| :--- | :--- |
| Terminal create/attach/input/resize/kill, colours, CJK, Ctrl/Alt/arrows, step-up re-prompt | done, E2E (PowerShell, `echo hi` → `hi`) |
| Scrollback paging, copy/paste, font size | done, not E2E-verified in detail |
| Files browse/search/view/edit, sensitive confirm | done, E2E browse/view/edit |
| Conflict flow (diff + keep copy / reload / overwrite / merge) | done, E2E keep-copy; unit test covers keep-copy + overwrite |
| Create/rename/delete(trash), upload (SAF, chunked), download (MediaStore) | done, not E2E-verified |
| Git status/diff/log/show | done, E2E status + log |
| FGS + notifications (approval while backgrounded) | done, E2E (approval notification with redacted public version) |

Screenshots: `android/docs/screenshots/phase-b/` (terminal, file-conflict, notifications).

## 11. Phase C: session UI redesign, continue on phone, zh-CN, icons

### 11.1 Icons: library only (superseded by §12.1)
All icons come from **coui-icons** (`COUIIcons.*`, package `io.github.suqi8.coui.kmp.icon.extended`)
and the basic icons shipped in **coui-ui** (`COUIIcons.Basic.*`). Our modules contain no
hand-written `ImageVector` or path icons: `grep -rE "ImageVector\.Builder|addPathNodes|PathNode|pathData" app core`
finds nothing (third_party excluded). The only vector XMLs are the launcher foreground (the
product logo from `src/assets/icons/switchtether-logo.svg`) and the notification small icon, which
Android requires as drawable resources.

Navigation: Home → `Home`, Sessions → `Messages`, Workspace → `Folder` with `selectedIcon = FolderFill`,
Manage → `Tune`, Settings → `Settings`. coui-icons has no Fill variant for the other four, so only
`icon` is passed and the selected tint of `BottomFloatingNavigationBarItem` shows the state.
Selected is `colorScheme.onSurface` (labelPrimary) and unselected is `labelPrimaryVariant`, as in
the native COUI bar; the app doesn't override the colour. `NavigationRailItem` has no
`selectedIcon`, so the app switches to the Fill glyph itself where one exists.

Tool categories: shell `Tasks`, edit/write `Edit`, read `File`, search `Search`, web `Link`,
mcp `Layers`, task/sub-agent `ContactsCircle`, todo/plan `ListView`, question `Help`, other `Tune`.
Other icons: thinking `MindMap`, approvals `Lock`, info/error `Info`, done `Basic.Check`,
send `Send`, interrupt `Pause`.

### 11.2 Session detail
- Header: `SmallTopAppBar` with the title and agent · model, plus info and refresh actions. Below it
  is one scrollable row of `InfoChip`s: status dot and status, waiting-for, phone control level,
  project and branch. While the agent is working, the rolling status also shows there with a
  spinner. Tapping the chips or the info icon opens a `WindowBottomSheet` **会话信息** with agent,
  model, status, activity, control, origin, project, branch, cwd, last active, and whether each
  capability is available (with the reason).
- Read-only/blocked sessions: a coui `TopTips` explains why (multi-line content slot, can be
  dismissed). The composer becomes a "手机端只读" bar instead of a dead input field.
- Pending approvals/questions: a sticky `TopTips` "N 个请求等待你处理 · 查看" scrolls to the oldest.
- Timeline (`Timeline.kt`, built in the ViewModel, one lazy item per row with a stable key):
  - User turns are right-aligned bubbles tinted with the accent.
  - Assistant turns show the agent avatar, name, model and time, then plain Markdown.
  - Thinking is a subtle collapsible "思考过程" row. It can't be expanded when the agent only
    reports redacted thinking.
  - Each tool call is a compact `CollapsibleRow`: category icon, title, name · time, and a status
    of spinner, check, or labelled pill for failed/denied/interrupted. It expands to show 输入/输出.
  - Two or more consecutive steps in a turn fold into one "N 个步骤" block, collapsed by default.
    The header shows the latest step, plus a spinner while running or "N 个失败". When expanded,
    the steps render as segments of one card.
  - Approval requests, questions, errors, diffs and plans are never folded.
- Diffs: a card with file name, directory, +/- counts, change pill and the native `DiffView`.
  Plans: a checklist card with a progress bar. Done items show a check in a filled circle and the
  item in progress shows a spinner.
- Approval and question cards have an accent border while pending and full-width coui buttons.
  Decision ids are localized (允许 / 本会话内始终允许 / 拒绝 / 停止智能体). Resolved cards read
  "已允许（在手机上）".
- Composer: a full-radius input pill with a round accent send button that spins while sending.
  A round interrupt button appears only while the session is live and not idle. Offline, the
  composer keeps the local draft and says so.

### 11.3 Session list
`BasicComponent` rows in group cards. Each row has a 40 dp agent avatar (the agent's initial on a
disc tinted for that agent), with a status dot for live sessions. Then come the title,
agent · project · branch, and a localized relative time. Pills appear only for waiting-for and for
sessions managed by SwitchTether. The live section comes first in "全部".

### 11.4 Continue on phone (接续会话)
Offered only when `capabilities.continueSession` is present and the phone can't already send. The
desktop sets it to `PARTIAL` for external Claude Code / Codex sessions. The action is the button in
the read-only composer bar. It opens a confirm sheet (`WindowBottomSheet`) that says:
- the conversation continues with the same context in a managed session;
- the original window on the computer won't show turns sent from the phone;
- the desktop refuses while the original is busy or waiting, or while a Codex thread is open
  elsewhere.

The buttons are **接续** (`mode: "continue"`) and, for Claude only, **以副本继续** (`mode: "fork"`).
The request is `POST /v1/sessions {commandId, agent, resumeFrom: <session id>, mode, cwd?}` and goes
through `CommandTracker`, so there is no silent retry and a 409/422 shows the desktop's message in
the command dialog. On success the app opens the returned `managed:` session. The desktop's
"continued" and "init" system rows are shown in the app language.

Verified on the emulator against the sandbox desktop:
- An ended `claude -p` session in a scratch dir → 接续 → a new managed session with the original
  history. Asked for the code word from the first turn, it answered correctly, so the context was
  kept.
- A turn with approval-gated Write/Edit plus `ls`/`git status` rendered as a "2 个步骤" block with
  approval cards.
- 以副本继续 also opened a new managed session with the history.
- The 409 path is covered only by a ViewModel test, because no busy external session was safe to use.

### 11.5 Language
The app is fully translated to Simplified Chinese (`values-zh-rCN`). A script compares the keys and
flags zh values that contain no CJK characters. Strings that code used to build are now resources:
relative times, tool/step states, capability states, decision labels, notification texts (a
`NotifyText` interface injected into `NotificationEngine`), file-operation messages (`UiText`),
expand/collapse state descriptions, and the terminal/camera content descriptions. coui17's sheet
close capsule had a hard-coded "关闭". Patch `0002-localizable-sheet-close-label.patch` adds
`LocalSheetCloseLabel`, and the app provides it from `R.string.action_close`. Data from the desktop
(tool names, model ids, Markdown, server error messages) is shown as received.

Screenshots (zh-CN, light and dark) are in `android/docs/screenshots/phase-c/{light,dark}/`:
c02-nav-workspace (FolderFill selected), c03-session-list, c04-session-detail-reply,
c05-session-detail-steps, c06-session-info, c07-external-readonly, c08-continue-sheet. Every session
shown is a test session in the scratch directory `android/build/e2e-cwd`.

## 12. Phase D: icons, task-first Home, brand avatars, attached sessions

### 12.1 Icon set: Phosphor (replaces §11.1)
All app icons are **Phosphor Icons** (phosphoricons.com, MIT) through the Compose port
`com.adamglin:phosphor-icon:1.0.0` (Maven Central). The app uses Regular weight everywhere and Fill
weight only for the selected navigation item and the send button. `core/ui/.../icons/AppIcons.kt`
is the single list of names the app uses; it only references library icons and draws nothing
itself. Our modules contain no hand-written `ImageVector` or path icons
(`grep -rE "ImageVector\.Builder|addPathNodes|PathNode|pathData" app core` finds nothing).
coui components that bring their own glyphs (switch, arrows, sheet close capsule) keep them.
coui-icons stays a dependency only because coui-ui uses it internally.

| Tab | Unselected (Regular) | Selected (Fill) |
| :--- | :--- | :--- |
| 首页 | `House` | `House` |
| 会话 | `ChatsCircle` | `ChatsCircle` |
| 工作区 | `FolderOpen` | `FolderOpen` |
| 管理 | `SlidersHorizontal` | `SlidersHorizontal` |
| 设置 | `GearSix` | `GearSix` |

Every `BottomFloatingNavigationBarItem` passes both `icon` and `selectedIcon`. The wide-screen
`NavigationRailItem` swaps the glyph itself. The bar's own tint is unchanged (selected
labelPrimary, unselected labelPrimaryVariant).

APK size: the release APK (R8 + resource shrinking, already enabled) went from 6,957,361 bytes before
Phase D to 6,878,289 bytes. Unused icons are stripped, and the coui-icons glyphs the app no longer
references are gone too. The debug APK grows to ~83 MB because debug builds aren't shrunk.

### 12.2 Home: task-first
- No "已连接" card. When the connection is healthy, nothing is shown. Connecting, offline, revoked
  and incompatible states appear as the thin `ConnectionBanner` strip.
- Small top app bar titled with the desktop's name instead of a large collapsing "首页" header.
- Order:
  1. **待处理 · N**: live sessions waiting for an approval, a question or input.
  2. **运行中 · N**: the other live sessions, in compact rows (32 dp avatar, 10 dp vertical padding).
     When there are none, a one-line empty row with a "新建会话" button.
  3. **最近会话**: the 5 most recent ended sessions.
  4. **更多**: one card with today's usage as a single line, Providers and Desktop info.
- Removed: the metric hero card, the usage hero card and the long quick-links list.

### 12.2a Session rows
A session row is at most two lines. Line 1 is the title, line 2 is agent · project · branch, and
the right column holds the relative time above one small tag. The tag uses the regular UI font, not
monospace, and is one of 等待审批 / 已接管 · VS Code / SwitchTether. A session without a title shows its
project or working-directory name, or "未命名会话". Raw ids appear only in the info sheet (会话 ID).

### 12.3 Agent brand marks
Session rows (list and Home), assistant message headers and the session header chip row show the
real agent mark on a softly tinted disc:
- Claude (`#D97757`) for `claude_code`;
- the OpenAI mark for `codex`, monochrome and tinted with `onSurface` so it adapts to dark mode;
- Phosphor `Robot` for any other agent.

The marks are LobeHub Icons (`@lobehub/icons-static-svg` 1.102.0, MIT). The SVG path was copied
verbatim into `ic_agent_claude.xml` and `ic_agent_openai.xml`. Source, checksum, license and the
trademark note are in `android/third_party/brand-icons/NOTICE.md`, `THIRD_PARTY_LICENSES.md` and
Settings → Licenses.

### 12.4 Attached sessions (wire change)
- `SessionSummary.terminalId: String?` is new. `isAttached` means `control == "full"` while the
  session is neither managed nor `origin == "managed"`, i.e. the user's own process attached
  through the opt-in wrapper.
- Full-control sessions always get the normal composer and approval UI. Sending is still enabled
  only when `sendMessage` is VERIFIED. The read-only bar, the read-only TopTips and **接续** are
  never shown for `control == "full"`.
- Attached sessions get a badge "已接管 · VS Code / Codex Desktop / Claude Desktop / 终端" (from
  `origin` + `agent`) in the session list, the header chips and the info sheet.
- When `terminalId` is set, the session toolbar shows **打开终端**. It switches to Workspace →
  Terminals and attaches to that terminal (`TerminalViewModel.select`) through the usual terminal
  step-up.
- Compose tests cover the composer, the badge, that 接续 is absent and the open-terminal callback.
  Not tested on a device yet: no attached session exists on the sandbox desktop.

Screenshots (zh-CN, light and dark) are in `android/docs/screenshots/phase-d/{light,dark}/`:
d00-navbar-home / d00-navbar-workspace (bottom bar crops), d01-home (the user's own session titles
are masked; only test sessions in `android/build/e2e-cwd` are readable), d02-sessions (filtered to
the scratch directory), d03-codex-session, d04-claude-session, d05-nav-workspace, d06-nav-manage.
d03 shows a real desktop error: the sandbox's default Codex model `gpt-6.1-sol` is rejected for a
ChatGPT-account login. That is a desktop/Codex configuration issue, not an app error.

## 13. Phase E: notifications, Live Updates, update, Android 17, relay, chat UX

### 13.1 Task-state notifications
- `NotificationEngine` (core) tracks turns per session: "waiting for approval/input", "turn
  completed", "failed", "interrupted / turn_stopped", "process exited" and "terminal exited",
  for attached sessions exactly as for managed ones. Keys are per turn (`done:<sid>:<n>`), so a
  state is notified once per turn.
- One notification per (session, slot): ATTENTION (approval / input), STATUS (done / failed /
  stopped / exited), OTHER (connection, terminal). A resolved approval cancels its ATTENTION
  notification. Grouped per session; tapping opens the session (or the terminal).
- No echo of the phone's own actions: interrupt / stop / kill terminal are noted as
  `LocalAction` for 60 s and the resulting events are not notified.
- Per-category toggles under Settings → 通知. The stored "enabled" set also records which types
  existed when it was saved (`notify_known_types`); types added later get their default, so an
  upgrade never switches a new category (e.g. Live Updates) off silently.
- The ongoing keep-alive notification says "已连接到你的电脑" or "正在重新连接你的电脑…"
  depending on the connection.

### 13.2 Launcher icon
Adaptive icon (gradient background + white tether mark), monochrome layer for themed icons, the
same mark as the notification small icon (`ic_stat_notify`). Exports in `android/docs/icon/`.

### 13.3 Self-update (GitHub releases)
- `GithubReleases.fetch` with ETag / If-None-Match; every 12 h (WorkManager) and on "检查更新";
  "测试版" includes pre-releases. Asset contract: `SwitchTether-<version>-android.apk` +
  `SHA256SUMS.txt`.
- Before installing: SHA-256 from `SHA256SUMS.txt`, the APK's package name, and its signing
  certificate digest must match the installed app (rotation lineage accepted). Install through
  `PackageInstaller`; without "install unknown apps" the sheet explains and opens the system page.
- Verified end-to-end against a local mock of the GitHub API (debug-only `-PupdateApiBase`):
  bad checksum refused, good update installed. The real repository currently has no releases
  ("尚未发布任何版本").

### 13.4 Usage parity and display currency
- `UsageFormat` mirrors `src/components/usage/format.ts`: tokens (K/M/B), success/hit rate,
  tokens/s, first-token time, range presets (今天 / 24 小时 / 7 / 14 / 30 天 / 全部), hourly buckets
  for ranges ≤ 24 h.
- Costs follow the desktop's **display currency** (`settings.usageDisplay`): `MoneyDisplay.resolve`
  is `resolveMoneyDisplay` (currencies USD/CNY/EUR/JPY/TWD/TRY/KRW, the same default rates, auto vs
  manual rate source), `UsageFormat.money / moneyCompact / moneyShort / moneyTick` are
  `formatMoney…` (same symbols, ¥/₩ digit rules, 2-significant-digit fallback, grouping). Tests
  reuse the desktop's own test cases. The phone never fetches exchange rates.
- Read from `GET /v1/settings` on connect and when the Usage page opens; the last value is kept
  per paired computer (display preference only). Older desktops without `usageDisplay`: USD.
- Ranges and buckets use the **desktop's time zone** (`/v1/status` `timeZone`, else
  `utcOffsetMinutes`, else the phone's zone on older desktops), so "今天" is the computer's day;
  bucket labels come from the desktop-local `date`. "全部" starts at `/v1/usage/first-date`.
- **请求日志**: `/v1/usage/logs` in pages of 20 ("加载更多"), rows with model · provider, time
  (desktop clock), tokens, latency, cost (`money(·, 4)`) and a red status for failures; tapping
  opens the detail sheet (refreshed from `/v1/usage/logs/{id}`; metadata only). Desktops without
  the endpoint (404) hide the section.
- "会话日志同步于 …" from `/v1/usage/last-sync`; apps with `visibleApps: false` are not offered
  as filters.

### 13.5 LAN discovery
`NsdDiscovery` browses `_switchtether._tcp` (TXT `v`, `fp`, `id`, `n`) only while needed, with a
multicast lock. Used as fallback when every stored host fails; a match must have the paired
fingerprint, and the found address is stored (`lastGoodHost`, merged host list). "附近的电脑" on
the pairing screen lists advertised computers.

### 13.6 Relay client (docs/RELAY.md)
- Order: stored hosts → LAN discovery → relay. TCP (`tcp://`, default port 47700) or WebSocket
  (`wss://…/v1/tunnel`) transports are plain `Socket`s under the unchanged TLS 1.3 + pinning +
  client certificate.
- Handshake (§2.3): `ST-RELAY/1 CONNECT <fp> <token>` → `CHALLENGE <nonce>` → `KEY <mac>` → `OK`,
  mac = base64url-no-pad(HMAC-SHA256(relay key, "switchtether-relay-v1 connect\n" + nonce + "\n" + fp)).
- The relay key comes from the pairing link (`rk`) or `GET /v1/relay` (refreshed after every direct
  connect). Token and key are stored only encrypted with a non-exportable AndroidKeyStore
  AES-256-GCM key (`KeystoreRelaySecrets`), deleted on unpair. No key → the relay is not tried.
  `ERR auth_failed` is shown as "中转密钥无效". The relay endpoint is never stored as a host.
- Tests: fake TCP and WebSocket relays (CHALLENGE / MAC checked), an independently computed MAC
  reference value, the connection order test, and an opt-in test against the Go relay
  (`SWITCHTETHER_RELAY_TEST`, `SWITCHTETHER_RELAY_KEY`).

### 13.7 Android 17 (targetSdk 37, minSdk 29)
- **Local network permission** (`ACCESS_LOCAL_NETWORK`): requested with a Chinese rationale sheet at
  pairing (when the link has LAN hosts) and at the first LAN connect. Denied → LAN hosts and
  discovery are skipped (Tailscale / relay still work) and a strip "未授予局域网权限… 去授权" offers
  the fix. `LocalNetwork.isLocal` classifies RFC 1918, link-local, ULA and `.local`.
- Certificate Transparency default: unaffected (the app pins its own leaf certificate). Background
  activity launch hardening: all activity starts come from user actions or notification taps.
- **Home-screen widget** "待处理 / 运行中": counts and up to three waiting sessions; a row opens the
  session. No approve buttons, no bitmaps (RemoteViews memory limit), data in memory only.
  "运行中" counts busy sessions only.
- **Handoff** (API 37): the open session is offered for continuation; the receiving app opens it
  only when it is paired with the same computer. UNTESTED (needs two devices).
- Skipped: `BiometricPrompt.Builder.addFallbackOption` (our prompt already allows the device
  credential as fallback), AppFunctions (needs the Jetpack AppFunctions SDK and privileged callers;
  cannot be verified), `AppWidgetEvent` (engagement statistics only).

### 13.8 Live Updates / Fluid Cloud (钉住任务)
ColorOS 16 shows Android 16 Live Updates in Fluid Cloud (流体云); the app posts standard Live
Updates only (no OPPO SDK).
- Pin up to 3 sessions ("钉到流体云" in the session toolbar, long-press on a row; optional
  "从手机发起的任务自动钉住"). One promoted ongoing notification per pin, updated at most once per
  second; a pin keeps the connection alive (the keep-alive service starts when the first pin is
  added and stops with the last one unless "后台保持连接" is on).
- States: 运行中 (step text + timer), 待确认 (alerts once; 批准 / 拒绝 need the phone unlocked;
  a 409 says "已在电脑上处理"; questions offer 回答), 已完成 / 出错 / 已中断 (promoted for 60 s, then
  demoted), 离线 (promoted only while a turn was running), session ended (final card, then the pin
  is removed). Swiping the card away is respected until the state changes.
- Progress track (one model): one coloured segment per stage, **no points**: 运行 | 待确认 | 完成
  when the turn asked something, else 运行 | 完成 (segments sum to 100). **Always determinate**
  (ColorOS Fluid Cloud draws no bar for an indeterminate style): while running the tracker moves
  inside 运行 with the step count on an asymptotic curve (15 % → < 90 % of the segment, never the end
  before completion), inside 完成 once the turn has passed 待确认; waiting sits in the middle of
  待确认; done is the end; failed / stopped stay where the turn was. The tracker is
  `ic_tracker_claude` / `ic_tracker_codex`, a 24 dp square vector with the mark centred (3 dp
  padding).
- Step text is human, never a tool name: 运行命令 <command>, 编辑 <file>, 读取 <file>, 搜索文件,
  访问网页 <host>, 搜索网页, 子任务, 更新计划, 发送消息, else 处理中; no "第 N 步". Approvals read
  "需要你批准 · tracker.txt" (a bare path shows its file name).
- Visual design guide (OPPO Fluid Cloud template 2.0): capsule = mark + at most 5 characters
  (运行中 / 待确认 / 已完成 / 出错 / 已中断 / 离线); card = title / core text / auxiliary line
  ("Claude Code · 项目"); text-only buttons (2–3); progress with 2–4 stages; lock screen shows only
  "SwitchTether · 待确认" when the user hides sensitive content; never colorized.
- Platform-free parts (`LiveReducer`, `LiveUpdateRenderer`) are unit-tested; the brand marks' path
  data must spell out arc flags (`VectorPathDataTest`: SystemUI's parser rejects `0 01-.1`).

### 13.9 Session chat: optimistic send, follow the latest message
- **Send** inserts the user's bubble immediately ("发送中…", dimmed), clears the composer and jumps
  to the bottom. After the POST: "已送达" + a "正在回复…" row until the agent's first output. The
  desktop's `user_message` replaces the bubble in place: exact match on `data.clientId ==
  commandId`, else (older desktops) same normalized text from the phone, newer than the send.
- Failures: "发送失败 · <reason>" with **重试** (same commandId, so it runs at most once) and **删除**.
  An unknown outcome is first checked with `GET /v1/commands/{id}` (read-only); no automatic resend.
  Offline (session usable when last seen): "等待连接", sent with the same commandId on reconnect, or
  "会话已结束，未发送".
- **Stick to bottom**: at the end of the list, new rows and growing rows keep it pinned (one scroll
  per frame, no animation per delta). Scrolling up stops following; a pill "N 条新消息 ↓" / "回到最新"
  jumps back. Opening a session lands at the bottom; older pages keep the position.
- Tests: ViewModel (optimistic insert, both matching modes, retry, offline queue, unknown outcome)
  and Compose (follow, pill, own bubble, failed bubble, typing row).

### 13.10 Navigation, predictive back, language
- Per-tab back stacks: switching tabs keeps each tab's place; re-selecting a tab returns to its root.
- `AnimatedNavHost`: shared X axis for push / pop, fade-through between tabs, each entry's UI state
  saved while it is in the stack. The system back gesture seeks the pop transition
  (`PredictiveBackHandler` + `SeekableTransitionState`); release commits, cancel springs back. At
  Home with nothing to pop no handler is enabled, so the system "back to home" animation runs.
  `enableOnBackInvokedCallback="true"`; edge-to-edge via `enableEdgeToEdge()`.
- Per-app language: `locales_config` (en, zh-CN) and Settings → 语言 opens the system page
  (Android 13+).

### 13.11 Press feedback: never a square mask
Every clickable gets its indication clipped to its visible shape: coui components (`BasicComponent`
inside cards, `IconButton`, `Card`) already mask their content; our own `clickable` /
`combinedClickable` must have `.clip(shape)` **before** the click modifier (CircleShape for round
buttons, the card's corner radius on the first / last row of a group, rounded rows for chip rows).
Purely gestural targets (terminal lines) use `indication = null`.

### 13.12 Attached Codex sessions in lists
The row tag is just "已接管"; the client moves to the summary line ("Codex Desktop", or
"Claude Code · VS Code"). The wide "已接管 · Codex Desktop" pill had squeezed long Codex thread
slugs into 3–4 lines. `AttachedCodexScreenshotTest` renders rows in light and dark (and asserts the
detail screen: badge, slug title, composer, no read-only tip, no 接续).

Screenshots (zh-CN): `android/docs/screenshots/phase-e/` — 01–04 self-update, 05–06
notifications, 07 usage, 10–12 local network permission, 20–31 Live Updates on the API 37 emulator
(before the stage-track change), 32–34 Live Updates after it (API 36: waiting, running, done),
40 widget (computer name blurred), 41 attached Codex rows, 50–53 optimistic send / follow /
new-message pill, 60 predictive back mid-gesture.

## 9. Known limitations

- Event-stream `subscribe` frames are not sent (the server currently broadcasts all topics).
- Revocation detected only as a TLS alert (handshake refused) is shown as offline unless the
  message names "revoked"; definitive signals (HTTP/close codes/hello) wipe immediately.
- Provider/usage/MCP/skill/prompt views parse CC Switch JSON leniently; field names were taken
  from CC Switch's TypeScript types and need checking against the real desktop backend.
- Question answers are keyed by question text (Claude Code AskUserQuestion format) — UNTESTED live.
- Step-up grants are cached in memory only. After an app restart the phone asks again even if
  the desktop grant is still valid (it could query `GET /v1/auth/step-up`).
- One approval can notify twice when the desktop also lists the managed process as an observed
  `claude:<id>` live session (its `session.upserted` is not filtered the way the session list is).
- Robolectric cannot draw screens whose app bar uses coui's RuntimeShader masks to a bitmap, so
  screenshot tests cover rows/components; full screens are captured on the emulator.
- Terminal cell width assumes a monospace font; CJK double-width glyphs may drift slightly.
- Re-pairing while already paired via a deep link is not supported (unpair first).
- `ui` component registry has only two built-in components.

### 13.13 Local files: viewing, downloads vs cache

Two different things, never mixed (`app/.../files/`):

| | **Cache** (preview) | **Download** (keep) |
|---|---|---|
| Made by | just opening a file to look at it | an explicit "Download" |
| Where | `cacheDir/remote-files/<key>/<name>` | public `Downloads/SwitchTether` (MediaStore, no storage permission) + `filesDir/downloads-index.json` |
| Lifetime | automatic: LRU above 200 MB; reused only while server + path + size + mtime match (a copy made without size/mtime, e.g. from a chat link, is trusted for 10 min); cleared by "Clear cache", unpair, revoke | until the user deletes it in **Downloads & storage**; not touched by clearing the cache or unpairing |
| Shown to the user as | nothing ("temporary preview") | a list entry with open / share / delete |
| Opened from | `FileProvider` (`<cache-path>` only) | its MediaStore `content://` URI |

`FileStore.resolve()` order: existing user download of the same file → fresh cache copy → stream the file into
the cache (never into memory; refuses above the cache cap). Sensitive files are never previewed from a local
copy (the desktop's `fs/download` refuses them); they keep the confirmed in-memory text read.

Viewers (`ui/files/FileViewers.kt`, shared by the Files page, chat links and the downloads list): images
(zoom/pan, decoded down-sampled), PDF (`PdfRenderer`, lazily rendered pages), video/audio (Media3 ExoPlayer
only, no Media3 UI), everything else and text-less binaries via "Open with…" / "Share" through a read-only
`FileProvider` or MediaStore URI (never `file://`). A link in chat that points at a desktop path opens the
viewer; an image's viewer offers Download and Open with.

**Downloads & storage** (Settings → Storage, and the Files page menu / transfer row) lists downloads with
total size, multi-select delete, delete all (confirmed) and cache size with Clear cache. Deleting only touches
URIs recorded in the index (the ones this app created); rows removed elsewhere are pruned on open, and files
the system refuses to delete (e.g. after a reinstall) are reported and dropped from the list.


## Task interaction corrections (2026-11-17)

- Titles: attached/managed summaries use native thread names, indexed titles, then the first actual
  user prompt; Claude live pushes no longer replace a task title with the registry slug.
- Codex children: accept both `source.subagent` and `source.subAgent`; keep streamed child output
  under its parent; rollout spawn/wait results retain child identity. A callId alias counts as one
  ancestor, not two. Child outcomes do not complete the parent's notification / Live Update.
- Questions: COUI RadioButtonPreference / CheckboxPreference rows with animated selection fills,
  optional fields, secret input, custom answers and cancellation. Multi-select answers use arrays
  for Codex and preserve commas / option values. Submit is disabled while an answer is sending.
  Answer capabilities are PARTIAL (protocol round-trip + Compose tests; live agent/device untested).
- Confirmation: approvals use the centered COUI WindowAlertDialog and its standard button bar;
  opaque page/dialog materials are provided at the theme root, including dialogs outside MainShell.
- Long commands: collapsed steps show two lines of actual command after a recognized shell launcher;
  expansion retains the full original command, defaults to line wrapping, offers horizontal scroll
  and Copy full command, and displays the working directory. No command is rewritten for execution.
- Chat source links: relative paths resolve against the desktop session cwd; file:// and source-line
  suffixes work. Text is read through fs/read into memory, media uses the existing preview cache;
  arbitrary application schemes remain inert and the desktop file policy still applies.
- Startup notifications: ignore pre-start transcript event replay and remove restored inactive pins
  without posting a finished card. Waiting requests remain actionable. Running task badges use the
  COUI rotating indicator; selection fills and disclosure arrows animate with Compose duration scale.

Regression evidence for this pass is recorded in docs/DEVELOPMENT_STATUS.md. Screenshot fixtures
use synthetic data only; API 32 software captures exercise the non-shader fallback of COUI.
