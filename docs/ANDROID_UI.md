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
third_party/coui17/* COUI17 (github.com/TIANLI0/COUI17) as a git submodule (Android-only KMP modules), see its UPSTREAM.md
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
refetches; seq gaps are normal (device-scoped events). Backoff doubles from 1 s and is capped
at 15 s (`MAX_BACKOFF_MS`, deliberately short: a task that finished while offline must not be
reported a minute late), each wait jittered to 50–100 % of its step; network callbacks skip the
wait. Close codes 4401/4403, HTTP `unpaired`/`device_revoked` (from any
screen's REST call), `hello.paired == false` or a `device.revoked` event for our id → keys and
pairing wiped, onboarding shows the reason. Runs only while the app is in the foreground
(ProcessLifecycleOwner); the "background connection" toggle is stored for Phase B.

## 4. Commands (idempotency, unknown outcomes)

Every side-effecting call carries a UUID `commandId` (body + `Idempotency-Key`). `Commands.run`
classifies: `Succeeded` (with `X-Command-Replayed`), `Rejected` (definitive server error, e.g.
422 `capability_unavailable` → "BLOCKED" dialog), `NotSent` (connect failed before the request
was written — tracked with an OkHttp `EventListener`), `Unknown` (written, no answer),
`InProgress` (409). `CommandTracker` never retries by itself: for Unknown / InProgress it offers
"Check status" (`GET /v1/commands/{id}`, again while the answer is still `pending` or the desktop
is unreachable); only then a retry is offered — same id if the desktop never saw it (`not_found`),
a new id if it failed or its status is `unknown` (the desktop restarted mid-command: it may have
taken effect, it will never finish, and the same id is answered 409 for good — the dialog says
so); nothing if it succeeded or is still pending. Chat messages follow the same rules: an
`unknown` (or a 409 whose record is `unknown`) marks the bubble failed with "not confirmed", and
its retry tap sends it under a new id; so does the retry of a rejected / `failed` message (the
desktop would replay the stored failure for the old id). Offline: drafts are kept, nothing is queued.

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

The [COUI17](https://github.com/TIANLI0/COUI17) repository as a git submodule at a pinned commit
(no SNAPSHOT/JitPack), built from source: Android-only KMP modules, `hiddenapibypass` patched out,
Oplus private materials disabled at the theme root, Apache NOTICE + MPL headers kept, MPL source
link in the licenses screen. COUI17 follows `Costben/coui17` by itself (its `upstream-sync`
workflow); here a new version is a submodule bump.

The generic building blocks this document describes were extracted from the app into COUI17's
`coui-patterns` module (`io.github.tianli0.coui17.patterns`) and the app now uses them from there.
Names that changed on the way: `AppBottomSheet` → `PanelSheet`, `AppMenu` → `GlassMenu`,
`Banner` → `NoticeBanner`, `TextGutter` / `SheetTextGutter` → `PageDefaults.TextGutter`; older
sections below still use the old names. Moved unchanged: `Page` and its list helpers, `GroupCard`,
`BackArrow`, `ToolbarIcon`, `SheetButtons`, `ConfirmDialog`, `FullScreenDialog`, `TextInput`,
`SmallButton` / `LinkButton` / `ActionTone`, `StepButtons`, `StatusPill`, `SectionHeader`, the
loading / error / empty states, `KeyValueRow`, `glassSurface` / `glassHeader` /
`LocalGlassBackdrop`, `ExpandBody`, `InputPill` / `ComposerKey` / `RoundIconButton`,
`CollapsibleRow`, `InfoChip`, `StatFigure`, `StatusDot`, `Modifier.segment`, `ChatBubble`,
`AttentionCard`, `TimelineCard`. `SwitchTetherTheme` is `COUI17Theme` with the brand accent plus
`DevColors`. What stays in the app is agent- or product-specific: `AgentAvatar`, `StepStatus` and
its indicator, `SubStepsToggle`, `PageCard`, `FilterChip`, `CommandPromptDialog`, the connection
and permission strips, markdown / diff / code renderers and charts.

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
  `StepUpGate` never waits forever: the pending prompt is held in a StateFlow, so the activity host
  gets it even if it subscribes late; one nobody picks up within 30 s fails ("could not ask"), a
  host that goes away mid-prompt resolves it as cancelled, concurrent requests for one scope share
  one prompt, and grants are cached per scope until 5 s before their expiry.

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
  - **Optional Bluetooth binding (CompanionDeviceManager).** Settings → Connection → "Bind
    computer (Bluetooth)" associates a computer that is paired with the phone over Bluetooth, in
    the system's own dialog (`connection/CompanionLink.kt`). The user sees and confirms every step,
    and nothing changes on the computer or in any agent. While the association exists:
    - Android exempts the app from battery optimization (`REQUEST_COMPANION_RUN_IN_BACKGROUND`).
    - `CompanionPresenceService` is bound when the computer connects or disconnects. It may start
      the `connectedDevice` service from the background
      (`REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`).
    - **"Only while the computer is nearby"** (default off) stops a background-only
      connection when the computer leaves, so the whole process can be reclaimed. The connection
      comes back when the computer returns. Pinned tasks and port forwards always keep the
      connection. `ReconnectWorker` still runs its bounded probe while the computer is away.
  - **Memory:** 60 s after the app goes to the background, and on `onTrimMemory(UI_HIDDEN+)`, the
    app evicts the idle OkHttp connections that all API clients share. The live WebSocket is not
    affected.
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
the command dialog. On success the app opens the returned session (same `claude:<id>` / `codex:<id>` when continuing; older
desktops returned a `managed:` id). The desktop's
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
  notification only when no other request of the session is open; when it was the one shown and
  another is still open, the notification is re-posted quietly for the most recent open request
  (its title, detail and allow / deny or answer buttons — `NotificationEngine.stillOpenRequest`),
  so no button acts on an answered request. Allow / deny from the notification goes through the
  same path. Grouped per session; tapping opens the session (or the terminal).
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
multicast lock. Used as fallback when every stored host fails — also while pairing from a QR code
whose addresses no longer answer; a match must have the paired fingerprint, and the found address
is stored (`lastGoodHost`, merged host list). "附近的电脑" on the pairing screen lists advertised
computers; tapping one opens "输入配对码" (6 digits from the desktop's pairing dialog). "手动输入电脑
地址" opens the same screen with an address field (`host`, `host:port`, `[v6]:port`); the
certificate is recorded on first contact and pinned afterwards. The code screen always shows the
"核对验证码" warning (wrapped text, not a one-line tip). On every connect the stored hosts become:
working host, `/v1/status` `hosts`, older ones (max 8); `wake` targets are kept for "唤醒并重试" on
the offline banner (UDP magic packet to the subnet broadcast and 255.255.255.255, ports 9 and 7).

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
  "从手机发起的任务自动钉住"). One promoted ongoing notification per pin, re-posted only when what
  it shows changed (§21.1); a pin keeps the connection alive (the keep-alive service starts when the first pin is
  added and stops with the last one unless "后台保持连接" is on).
- States: 运行中 (step text + timer), 待确认 (alerts once; 批准 / 拒绝 need the phone unlocked;
  a 409 says "已在电脑上处理"; questions offer 回答), 已完成 / 出错 / 已中断 (outcome for 8 s, then a
  result notification, §21.1), 离线 (only for a turn in flight, after 20 s), session ended (settles
  at once). Swiping the card away is respected until the state changes.
- Automatic follow is per run, not per session id (`AutoFollow` in `notify/LiveUpdates.kt`): a new
  session, the next turn of a known one (idle → busy / waiting) and a conversation resumed after
  its process ended are all followed again, since a follow ends with its task. A manual unpin of a
  session stays respected.
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
(Telephoto zoom, tiled decoding; see §18), PDF (`PdfRenderer`, lazily rendered pages, tap to zoom), video/audio (Media3 ExoPlayer
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

## 14. Usability pass (2026-11-18)

Reviewed on the API 36 emulator against the dev server (observe-only on the desktop's real
Claude data; managed test session in `android/build/e2e-cwd`).

- **Home:** "运行中" now means working (busy); live-but-idle sessions are a separate "空闲" group
  capped at 3 rows, with "查看全部 N 个活动会话" → Sessions tab. Pull-to-refresh.
- **Session times (desktop):** observed live Claude sessions report the transcript's last write
  as `lastActiveAtMs` (was "now", so every row read 刚刚 and the order was arbitrary).
- **Session detail:** long titles scroll as a marquee (coui patch 0004, `LocalTopAppBarTitleMarquee`,
  on for every page; the session info sheet opens from the status chip row); time before model in message headers (the time
  was being cut); copy button on every code block (`LocalCodeCopyLabel`) and "复制回复" per reply;
  image attach uses an image icon; the image viewer closes with ×; the read-only tip, once
  dismissed, stays dismissed (`ui_hints`).
- **Approvals:** 允许 / 拒绝 act on one tap (the card shows exactly what is approved; buttons are
  disabled while a request is in flight). 本会话内始终允许 and 停止智能体 still confirm.
  Verified: one tap → file written, card "已允许（在手机上）".
- **Claude Code background tasks:** background `Bash` rows show "后台", stay running until the
  task notification, then completed / failed (exit code) / interrupted (TaskStop); the finish
  summary is shown when expanded. Notifications and user messages absorbed mid-turn
  (`attachment` → `queued_command`) are parsed; before, both were dropped (the phone never showed
  a message the user typed while the agent was working).
- **New session:** one launcher state for the sheet, the page route and the folder picker (a
  picked folder always reaches the launcher; Home's button opens the sheet). Agent, model per
  agent, permission mode and the folder of the last phone-started session are remembered; recent models are chips. A disabled Start says why.
- **Folder picker:** recent folders + all locations, "所有位置" from inside any folder, system
  back = up → locations → sheet, loading/error states, a filter above 12 folders, lazy rows (no
  300 cap), the button names the folder ("使用“e2e-cwd”").
- **Terminal:** follows output only while at the bottom (scrolling up to read stops it; loading
  earlier output keeps the position); ⏎ key; ⌨/⌨↓ toggles the keyboard; font size persists;
  "load earlier output" disappears when everything is loaded; the page title is the terminal's
  name with its folder.
- **Files:** back from an edited file asks "放弃修改？"; opening a folder never shows a stale file;
  cancelling search clears the results (they used to stay); back closes search first;
  pull-to-refresh; success messages are toasts, errors stay dialogs; invalid names show an error;
  the menu uses a "more" icon.
- **Git:** empty state "尚未选择仓库" with "选择仓库"; commit rows say which file a tap shows (there
  is no per-commit diff endpoint yet).
- **Settings → 通知:** quiet hours use the system time picker (minutes too); muted agents and
  projects are switches (projects from recent sessions) instead of comma-separated ids.
- **Usage:** request-log times use the desktop's time zone like the rest of the page.
- **Onboarding:** "从剪贴板粘贴" when the link field is empty.

### 14.1 Running icon stays static
A frame-animated running icon (step glyph in a turning arc, `animation-list`) was tried: it turned
in the emulator's notification shade but did not work well on the real device, so it was removed.
Running keeps the static step glyphs.

### 14.2 Command blocks and the terminal page
- Tool-call commands: the "横向查看 / 复制完整命令" buttons above the block are gone; the block's
  own header has two small icons, wrap ↔ scroll sideways (`CodeBlockView(wrapToggle = true)`,
  labels via `LocalCodeWrapLabels`) and copy.
- Terminal page: title is the short shell name ("cmd", not `C:\WINDOWS\system32\cmd.exe`) with
  the folder as subtitle; the row of five round buttons is gone (paste + a "more" menu in the page
  toolbar: new terminal, copy screen, refresh, close); tabs only when there are several terminals;
  "load earlier output" is a quiet line instead of a pill over the output.
- A terminal created before its page measured itself (from the Workspace hub) gets a grid
  estimated from the screen and font, so the shell's banner is not wrapped at a stale width.

Not done: per-commit diff (needs a `git show <rev>` endpoint), removing exited terminals from the
hub, search in Providers/MCP/Skills lists.

## 15. Sub-agent branches (Claude Code)

Modelled on Claude Code's own view of sub-agents (the `⎿` lines under a Task, expandable into the
agent's transcript):

- **Inline branch**: under a Claude Code sub-agent row, while it runs, its latest 3 top-level steps
  as tree lines (`├─` / `└─`, monospace) with "… 之前还有 N 步"; refreshed every 3 s (one request per
  agent, throttled to 2.5 s). Finished agents keep the one-line summary. Always a "查看过程 · N 步 ›"
  link (or "查看过程" before the count is known; "没有过程记录" when the desktop has none).
- **Sub-agent page** (`Route.Subagent`): title = description, subtitle = type · state; breadcrumb
  "全部子 agent › 父 agent › 当前" (each crumb opens that level); the agent's steps with the normal
  renderers, read-only (approvals/answers BLOCKED); nested sub-agent rows open the next level; "它启动
  的子 agent" lists direct children as branch rows. Polls every 2 s until `finished` (or 2 min idle).
- **Branch tree** (`Route.SubagentTree`, capsule "N 个子 agent" on the session page): every
  sub-agent depth-first with connector rails (`│  ├─`), status dot (running / done / not finished),
  type and time; tap opens the sub-agent page. Refreshes while any branch is running.
- Codex: the same entry points (`Route.Subagent`/`SubagentTree` carry the agent id); the desktop serves the
  events nested under the sub-agent row, so the nested rows in the main timeline and the page agree.


## 16. Port forwarding (Workspace → 端口转发)

The phone opens a web/dev server that runs on the computer at the **same** address,
`http://localhost:<port>/`, in any browser on the phone (protocol: docs/PROTOCOL.md §7.11;
security: docs/SECURITY.md §7a).

- **Entry**: Workspace has a "端口转发" card (summary: what it does, or the running
  `localhost:<port>` list).
- **Page** (`Route.PortForward`, `PortForwardScreen`):
  - "正在转发": one row per forward: `localhost:<local>`, `→ 电脑 :<port> · <process>`, connections,
    bytes received, "经中继" when the connection uses the relay, expiry; a status pill (已连接 /
    正在连接… / 正在重连…); buttons 打开 · 复制 · 延长 2 小时 · 停止. Tapping the row opens it.
  - "电脑上仍有效的转发": leases the computer still has but this phone does not serve (e.g. after
    the app process ended); tapping resumes without a new biometric prompt.
  - "电脑上正在监听": `GET /v1/forwards/ports` (process name on Windows). Tap → biometric step-up
    `port_forward` → lease → local listener on the same port → the browser opens. Already
    forwarded ports show "已转发" and open instead.
  - "输入端口…": computer port and optional phone port (defaults to the same port so Host / Origin /
    HMR WebSocket URLs / OAuth callbacks match the computer).
  - Footer: the forward lasts 2 h, and other apps on the phone can reach it while it runs.
- **Errors**: phone port in use → dialog, then the manual dialog prefilled with the computer port;
  nothing listening on the computer → notice instead of opening the browser. A browser request
  that reaches a port with nothing behind it gets a small HTML page (502, "电脑的 5173 端口上没有
  正在运行的服务"; 503 while the computer is unreachable) that reloads itself every 3 s, so the page
  appears by itself once the dev server starts.
- **Background**: while any forward runs, the monitor service keeps the connection (like a pinned
  task). Its ongoing notification reads "正在转发电脑上的 N 个端口" with the `localhost:<port>` list
  and a "停止转发" action.
- **Reconnects**: one tunnel WebSocket per forward (pings every 20 s). After a network change or
  a switch between direct and relay paths it reconnects at once when the connection is back
  online, else with backoff (0.5 s … 15 s). Connections inside a dropped tunnel are closed; new
  ones wait up to 10 s for the tunnel. Close 4410 / 404 (stopped, expired, revoked) ends the
  forward on the phone with a toast.
- Implementation: `:core:remote` `forward/PortForward.kt` (frames, `LocalForwarder`: loopback
  listener, streams with 256 KiB windows, half-close, error page), `RemoteApi.openForwardTunnel`;
  app `forward/PortForwards.kt` (step-up, leases, expiry, stats), `ui/workspace/PortForwardScreen.kt`.

## 17. Notification actions and fixes (2026-11-19)

- **Act from the notification** (each button requires the phone to be unlocked;
  `NotifyActionReceiver`, same API and commandId idempotency as the in-app buttons):
  - approval → 允许 / 拒绝 (when the request event, with its id, has arrived);
  - question → the notification text is the question itself; a single single-choice, non-secret
    question gets 回答 with the options as inline choices and free text when the question allows
    it (a chosen label is sent as its option value); several / multi-select / secret questions
    open the app;
  - finished turn or "waiting for you" of a session the phone controls (managed / attached, live)
    → 回复 sends a message.
  The notification is then replaced by the outcome: 已发送 (disappears after 4 s), 已在别处处理
  (409), 没有发送 + reason, or 不确定是否送达 (unknown outcome; never retried silently). While the
  app is in the background the connection is started for the action and released afterwards.
- **One alert per wait**: the request event and the status change describe the same wait; the
  second source no longer alerts (a later request event updates the generic text silently).
- **Live Updates off / card swiped away**: ordinary notifications are suppressed only while a Live
  Update card really shows the session (`LiveUpdates.covers`); with Live Updates off no session
  is auto-pinned. Before, approvals of auto-pinned sessions were dropped without any notice.
- **Connection notification**: one notification replaced in place; "lost" only after 30 s
  unreachable; "reconnected" replaces it quietly and goes away after 8 s.
- **Session page**: allow / deny disabled while a decision is in flight; question cards keep
  choices and typed text when scrolled away, across sub-agent pages and rotation
  (`rememberSaveable`); coming back from a sub-agent page keeps the reading position; a failed
  message has 编辑 (text back into the composer; files must be picked again); sent messages have
  a copy button; small retry / edit / delete targets are 40 dp high.
- **Sessions**: in 实时, sessions waiting for the user come first; project chips appear once more
  than one project is known.
- **Workspace**: the Git diff error's retry reloads it (it used to close the page); keys typed
  while a terminal connects are sent once it opens (≤ 4 KiB), and a disconnected terminal says
  "未连接，按键不会发送" next to 重新连接.
- **Pairing**: retrying a failed typed-address attempt keeps the address editable and pre-filled.

## 18. Files: browsing and viewing (2026-11-21)

Usability pass of Workspace → Files (`ui/workspace/FilesPane.kt`, `ui/files/`, pure logic in `files/FileListing.kt`
and `files/TextDocument.kt`). Viewers reuse maintained open-source libraries instead of hand-rolled ones.

**List**
- Path bar outside the list (never scrolls away): an "up one folder" button and a COUI `BreadcrumbBar` whose
  segments each open that folder; inside a shared root it starts at the root's name (`FileListing.breadcrumbs`).
- The search field filters the current folder as you type (all terms, `*.rs` / `.md` match the extension);
  pressing search, or "Search subfolders for …", runs the desktop's recursive name/content search. Results show
  paths relative to the folder and the line number.
- Sort menu: name (natural order, `file2` before `file10`), date modified, size, type; picking the active key
  flips the direction; folders always first.
- Phosphor file-type icons (folder, code, Markdown, image, PDF, video, audio, archive, document, sheet,
  slides, lock for sensitive/`.env`), size · relative time, folder/file count, empty and no-match states.
- Each row is its own lazy item (folders with thousands of entries scroll smoothly); opening a folder keeps
  the old list with a progress bar instead of a blank spinner; each folder remembers its scroll position.
- Row actions add "Copy path". Back: search results → clear filter → up a folder.

**Text viewer** (`CodeViewer`, shared by the Files page and chat file links)
- Lazy list of lines with a line-number gutter (fast for the 2 MiB `fs/read` limit), tabs expanded,
  lines over 20 000 characters shortened for display only.
- Syntax colours in the app's code palette: SnipMeDev **Highlights** for C/C++/C#/Dart/Go/Java/JS/Kotlin/Perl/
  PHP/Python/Ruby/Rust/Shell/Swift/TS (and Vue/Svelte/Gradle approximations); the built-in tokenizer for
  JSON/YAML/TOML/XML/SQL/CSS…; none above 384 K characters (a note says so).
- Wrap ↔ sideways scroll toggle (one shared horizontal offset, gutter stays put), find-in-file with
  count and previous/next (case-insensitive), go to line, pinch or menu text size, long-press a line to
  copy it, "Copy all".
- Markdown files open **rendered** (Mike Penz's multiplatform-markdown-renderer, GFM tables/task
  lists/alerts, fenced code via Highlights, lazy layout) with a Rendered / Source switch. Web links open the
  browser; relative/absolute file links resolve against the file and open in the viewer; images are not
  fetched.
- Truncated files say how much is shown and offer Download; binaries show name, size and "Preview or open
  with another app" / Download. Edit / save / 409 conflict / discard flows are unchanged.

**Images and PDF**
- Images: **Telephoto** `SubSamplingImage` + `Modifier.zoomable` (pinch, double-tap, fling, bounded pan;
  tiles decoded per zoom level, so huge photos stay sharp without OOM). GIF/AVIF/BMP/ICO fall back to a
  down-sampled `ImageDecoder` bitmap with `Modifier.zoomable`.
- PDF: Android's `PdfRenderer` (no library); tap a page to open a full-screen pager rendered at ~2× width
  with Telephoto zoom, swipe between pages; × or back returns to the same page in the list.

| Library | Version | License | Use |
|---|---|---|---|
| `me.saket.telephoto:zoomable`, `:sub-sampling-image` | 0.19.0 | Apache-2.0 | zoomable images and PDF pages |
| `com.mikepenz:multiplatform-markdown-renderer`, `-code` | 0.46.0 | Apache-2.0 | rendered Markdown (pulls `org.jetbrains:markdown` 0.7.17, Apache-2.0) |
| `dev.snipme:highlights` | 1.2.0 | Apache-2.0 | syntax highlighting |

APK impact (release, R8, arm64, measured on HEAD vs HEAD + this change): 8 565 482 → 9 059 769 bytes,
**+483 KiB (+5.8 %)**.

Rejected: sora-editor (LGPL-2.1, View-based, large with TextMate grammars), WebView-based highlighters
(the app has no WebView), `androidx.pdf` viewer (needs API 31+/SDK extensions; minSdk is 29).

## 19. One session, one id; model and effort; last known state (2026-11-21)

- Sessions started from the phone are ordinary Claude Code / Codex conversations with the agent's own id
  (`claude:<uuid>`…), shown like any other session (origin label "从手机启动", no separate "托管" kind). They are
  listed by `claude --resume` and the IDE history on the computer, and stay the same entry after the desktop restarts.
- History that is not running anywhere keeps the normal composer (`sendMessage: PARTIAL`) with the hint
  "会话未在运行：发送后会在电脑上恢复这个会话"; sending resumes it on the computer under the same id. Claude
  histories can still be forked from the session menu.
- Model and effort: in the new-session launcher (options row → chips from `GET /v1/agents/{agent}/models`, plus a
  custom model field and the models used before) and, for a running session, a "模型 · 强度" chip above the
  composer that opens a sheet (`POST /v1/sessions/{id}/options`; applies from the next reply, like `/model`, `/effort`).
- Last known state: the unfiltered session list and the newest 300 events of the last 40 opened conversations are
  cached per desktop in app-private storage (`filesDir/session-cache`, `SessionCache`) and shown at once on a fresh
  start while connecting; the first successful load replaces them, a failed refresh keeps them. Cleared with
  "clear caches" and on unpair. The sessions page now opens on "全部" (running sessions first).


## 20. Claude Code session tools (2026-11-21)

The Claude Code IDE extension's session features, for Claude Code sessions SwitchTether started (session detail
`claudeTools: true`; hidden otherwise). Calls go through `POST /v1/sessions/{id}/claude/{action}` with a commandId
(`RemoteApi.claudeTool`, longer read timeout for the side question); responses are parsed in `ClaudeTools`
(`:core:remote`, JVM-tested); refusals show the desktop's message.

- **Session tools page** (`Route.SessionTools`, toolbox icon in the session's top bar): permission mode (four
  `RadioButtonPreference`s; bypassPermissions shows the CLI's refusal when the session was not started with it),
  context usage (bar split by category + per-category tokens / %), plan (markdown), MCP servers (status dot, tool
  count, error, Reconnect), side question (/btw, spinner up to ~2 min), rename (prefilled), export (text handed to
  the share sheet with `ACTION_SEND`; nothing is written to storage).
- **Permission capsule** above the composer (next to the model chip): current mode; tap → mode sheet.
- **Run in background**: expanded running foreground `Bash` / `Agent` / `Task` calls (and running foreground
  sub-agent rows) offer "转到后台" (`run_in_background`, `RenderContext.onRunInBackground`).
- **Background task output**: Agent map → background task → "查看输出" (`task_output`, monospace, refreshed every 2 s
  while the task runs).
- **Withdraw a queued message**: a message sent while the session is busy keeps the send response's `messageUuid`;
  the delivered bubble reads "已送达 · 排队中" with "撤回" until the echo arrives (`cancel_message`; withdrawn → bubble
  removed, too late → it stays a normal message).
- **Rewind files**: a small icon next to copy under user messages whose id is a Claude Code UUID: dry run → dialog
  listing the files (+/− lines) that says only files are restored, not the conversation → confirm → toast.

## 21. Live Updates lifecycle, immersive pages, page actions (2026-11-23)

### 21.1 Live Updates: when a card is posted and when it ends
Every re-post of a promoted notification makes Fluid Cloud / the status chip animate, so a post must
mean something on screen (`notify/LiveUpdates.kt`, timings in `LiveUpdates.Timing`):
- **Deduplicated by what is rendered**, not by state changes: the spec (+ subtitle) about to be
  posted is compared with the one showing; identical → nothing is posted (token counts, a row
  updated in place, a status that changes nothing).
- **Tiered coalescing**: a new phase goes out at once; the collapsed line / capsule (chip, step text,
  icon, actions) at most every 3 s; expanded-only details (step count, files, tokens, the agent's
  latest words) at most every 15 s.
- **Connection drops**: shown only after 20 s, and only on a card whose turn is in flight; an idle or
  finished card is never touched by a drop or a reconnect.
- **Stale reads**: a REST read (reconnect / pin refresh) saying `busy` that is not newer than the
  turn's outcome cannot restart a finished card; a tool row seen again (in-place update) is neither a
  new step nor a new turn.

End of a task (iOS Live Activities' "end with final content", on Android terms):
1. The turn finishes → the capsule shows 已完成 / 出错 / 已中断 for **8 s**. It sounds once if the user's
   ordinary "任务完成 / 失败 / 中断" notification would have (type on, agent / project not muted, not
   quiet hours, app not in front) — the card replaces that notification.
2. Then it **settles**: the same notification id becomes an ordinary result ("本轮已完成 · 12 步"):
   not promoted, swipeable, tap opens the session and clears it, no 取消钉住. The pin slot is freed.
3. The result disappears when the user swipes / taps it, opens the session in the app, or the
   session's next turn starts (auto-follow re-pins and the same id becomes live again).
4. The process exits → settles at once (the finished turn's outcome, or 会话已结束 mid-turn).

**Background work** (Claude Code `run_in_background` shells, background agents): the turn's `idle`
is not the end of the job. Counted from `status.backgroundTasks` (managed sessions) or the
`background: true` tool / sub-agent rows still `running` (observed sessions). After the turn the card
reads "本轮已完成 · 后台 2 个任务运行中" (capsule 后台), is demoted after 8 s but **keeps its pin**,
so the agent waking up for a task's result continues on the same card. It settles 10 s after the last
background task ended without a new turn, at the latest 30 min after the turn (a dev server never
ends); its slot is the first one a new task takes. The completion sound plays when the whole job is
over, not at the turn's end.

### 21.2 Immersive pages and system bars
- `Page(immersive = true)`: the body is one scrolling list that starts at the top of the screen and
  scrolls under the status bar and the frosted title bar; `pageListPadding()` adds the bar height
  (`LocalPageTopInset`) so the first item starts below it, and the gesture bar height at the bottom
  of pages without the navigation capsule. Anything fixed above the list (the connection banner)
  moves into it as its first item. Used by Home, Manage, Workspace, notification / computer /
  licenses settings, port forwarding, downloads, session tools, the Agent map and sub-agent pages.
  Pages with fixed tabs / chips / a composer above or below the list stay non-immersive.
- Status / navigation bar icons follow the app's theme setting, not the system's
  (`SwitchTetherTheme`), and the system contrast scrim is off.

### 21.3 Actions inside pages
A filled full-width button belongs at the bottom of a sheet or an onboarding step, not in a page.
Inside a settings-style page an action is a row of the group card (导出对话, 扫描附近的电脑); in a
form or a chat card it is a small (`ButtonSize.Small`) button aligned to the end (提问, approval
options, 取消 / 提交回答; long desktop labels wrap onto the next line). §22 turns this into the
app's button tiers and applies it everywhere.

## 22. Button tiers, half-screen panels, "new …" flows (2026-11-24)

A coui `Button` / `TextButton` with default arguments is COUI's **Large** tier: a 44dp filled
capsule (`TextButton` is filled too, grey). Used for every "retry", "load more" and row action it
made pages read as stacks of slabs. Call sites no longer pick metrics; they pick a tier.

### 22.1 Tiers

| Tier | Component | Looks like | Where |
| :--- | :--- | :--- | :--- |
| Large | coui `Button` / `TextButton`, `SheetButtons`, `StepButtons` | 44dp capsule | The end of a decision panel or of an onboarding step. One filled button per screen; none inside a page. |
| Panel confirm | `AppBottomSheet(confirm = SheetConfirm(…))` | 40dp accent capsule in the panel's toolbar | The action that completes a form panel (新建会话 → 启动). |
| Small | `SmallButton(text, onClick, tone)` (`core:ui` `components/Buttons.kt`) | COUI Small: 28dp capsule, 14sp | An action of a row, a card or a toolbar. Never full width; several sit 8dp apart. |
| Link | `LinkButton(text, onClick)` | borderless accent label, 36dp touch height | A list's footer (加载更多, 查看全部 N 个活动会话), an action next to a line of text (刷新, 管理, 所有位置), a title-bar text action (选择 / 完成). |

`ActionTone`: `Default` (grey capsule, accent label), `Primary` (accent capsule: the one action a
card is about — 允许, 提交回答, 保存, 打开, 切换), `Danger` (grey capsule, red label: 停止, 删除, 解除绑定).
A full-width grey button is not used anywhere.

The filled-button colour (`containerTheme`) is now the brand accent in both themes; it had been left
at COUI's stock `#0080FF` / `#1A8CFF`, a different blue from the switches, radios and links next to it.

What moved (all in `ui/`): session list and request log "load more", Home's "see all" → centred
links; Home's empty row, cache clear, MCP reconnect, Bluetooth bind / unbind, folder-picker retry →
Small; provider card (测试连接 · 切换, end-aligned), agent map (查看过程 · 停止), background task, port
forward row (打开 · 复制 · 延长 · 停止, a wrapping row instead of four equal slabs), file transfer row,
binary-file notice, viewer retry, terminal confirm / reconnect → Small or Link; the file editor's
保存 and "new session" as a page → Small `Primary` in the top bar; tool output "show all / load
full", diff "show more" → links; approval and question buttons → `SmallButton` (14sp label).

### 22.2 Half-screen panels (`AppBottomSheet`)
- The panel is drawn on the **page colour**, with the page's own gutters (the sheet's 16dp inside
  margin is off): a `GroupCard` is a card 16dp from the edge exactly as on a page. Before, cards
  were white on a white panel (invisible) and inset 32–48dp. Text uses `SheetTextGutter`, the
  page's `TextGutter` (§22.6).
- Toolbar: `SheetCloseCapsule`, centred title, and `confirm` for a form.
- A panel that asks for a decision ends with `SheetButtons(primary, secondary, tertiary)`: coui's
  `DialogButtonBar` — two 44dp capsules side by side (the primary filled, on the right), stacked
  when there are three or the labels do not fit. Used by 在手机上继续 (接续 | 以副本继续), the local
  network rationale, the Live Updates explanation and the update sheet. A busy action shows
  "正在加载…" and is disabled (the bar has no spinner).
- Bluetooth binding: its rows are group cards now (they were bare rows), bind / unbind are Small.
- Model / effort: laid out like a settings page (`SmallTitle`, model card, effort slider in its own
  card, "更多版本" as a link); the chooser pads itself, callers add no side padding.

### 22.3 New session
Agent tabs → 工作目录 (one `ArrowPreference` row; recent folders as chips under it) → first message
→ 选项 (summary row whose chevron turns; expands to permission mode and model / effort) → why it
cannot start, when it cannot. **启动** is the toolbar's confirm capsule (`start-session`), so it
stays visible above the keyboard instead of sitting under the fold as a full-width button; the page
variant shows it as a Small `Primary` top-bar action.

### 22.4 Pairing
One filled button per step. Intro: 扫描配对二维码 stays the only Large button; 手动输入电脑地址 is a
link, 从剪贴板粘贴 / 使用此链接 are Small buttons under the link field. Confirm, code, camera
permission and failure steps end with `StepButtons` (取消 | 配对 side by side); the verification
step keeps 数字一致 full width with "数字不一致，取消配对" as a red link under it.

### 22.5 Evidence and limits
`SheetGalleryScreenshotTest` (Robolectric, API 32, zh-CN) draws real panel windows (the test draws
every root view, the dialog included) and asserts that the confirm capsule and the button bar act
and disable: `build/outputs/screenshots/ui-new-session-{light,dark}`, `ui-decision-panel-*`,
`ui-button-tiers-*`, `ui-onboarding-{intro,confirm}`. `PageGalleryScreenshotTest` draws whole pages
(`page-*.png`): Home, Sessions and the session page with synthetic sessions; Settings, Workspace,
notifications, downloads, port forwards, the folder picker, providers and the paired-computer page
in their unpaired state (the test process has no desktop). Not checked on a device or emulator:
glass blur, press motion, and the rows that only exist with a paired desktop (an active port
forward, a provider card, the agent map). A Small button is 28dp high as in COUI; Compose widens
the touch area of a clickable to the 48dp minimum on its own, the layout bounds stay 28dp.

### 22.6 Page details found on the rendered pages
- **One text gutter.** Loose text (a note under a group, an intro, an empty hint, Home's section
  headings) sits at `TextGutter` = 32dp: the 16dp card margin plus the 16dp inside it, in line with
  `SmallTitle` and the row titles. It had been 16, 24, 28 or 32dp depending on the page.
- **Chat rows.** A reply's copy button ends its header row (two equal weights had left it in the
  middle); under the user's bubble the time and the copy / rewind actions share one line (they were
  two). Both copy glyphs end at the content edge; their 36dp touch area reaches into the margin.
  Step cards use the 16dp margin of the approval / diff cards (was 12dp).
- **Session rows** use the list row's own icon gap (16dp) like every other row (was 28dp).
- **Workspace.** Rows that open a page (a terminal, a folder, port forwarding, Git) are
  `ArrowPreference`s; 新建终端 opens a menu and stays without the arrow.
- **Folder picker.** Inside a folder the page is titled with its name and the title bar carries
  使用此文件夹 (Small `Primary`, `use-folder`). The full-width button under the list is gone, and
  with it the disabled slab shown while browsing the locations.

## 23. Session page: floating glass, keyboard, motion; menus; terminal width (2026-11-24)

### 23.1 The conversation runs under its controls
The page is immersive (§21.2): the list starts at the top of the screen and fills it. It scrolls
under the title bar, whose progressive blur is the page's only fade; the notices and capsules
float below the bar and the composer floats at the bottom (Telegram's layout). Nothing is laid
behind the composer: only its keys and field are glass, the conversation stays sharp around them.
The list's content padding is the measured height of the top overlay (title bar, notices,
capsules) and of the composer; the "new messages" pill sits above the composer.

**The title bar's material.** coui paints the bar's gradient glass only when two things hold, and
`Page` provided neither, so every immersive page had an opaque bar over its list:
1. the scaffold is told the content runs under the bar (`Scaffold(edgeToEdge = immersive)`,
   `LocalEdgeToEdge`);
2. the bar finds something to blur in `LocalBackdrop`. The shell publishes its backdrop to the
   navigation capsule only, so `Page` records its own content as a `LayerBackdrop` and provides it
   to the title bar (the bar is the content's sibling, never inside the recording). With the flag
   but without the backdrop the bar draws only its translucent colour ramp and the list shows
   through sharp — the state of the first 0.1.14 build, reported from the device. **The session page paints its own header material** (`Page(ownBarMaterial = true)`, the bar is
transparent). coui's gradient material is thin by design: in the dark theme 40 % colour at the very
top and a ~6dp blur, both gone by the bar's bottom edge. Over list cards that reads as glass; over
a conversation the running text stayed sharp under the title, the subtitle and the capsules and
nothing could be read (seen on the device). `glassHeader(pageColor, holdFraction)` lies behind the
title bar, the notices and the capsules as one band: a 26dp progressive blur at full strength down
to the title bar's bottom edge, tapering to nothing just below the capsules, under the page colour
at the plain bar's strength (60 % dark / 80 % light) fading over the same stretch.

Every other immersive page with a small title bar (Home, the settings sub-pages, port forwarding,
downloads, session tools, the agent map) gets the same material from `Page` itself: a band of the
bar's height plus 16dp behind the transparent bar. A large collapsing bar (Manage, Workspace)
keeps coui's material, which follows the collapse.

A first version laid a page-coloured gradient under the capsules, on top of the title bar's blur:
an opaque fade over a translucent one, visible as a band on the device. A second version put a
progressive blur behind the composer as well; that was removed too. A floating control never gets
a scrim or a blur band of its own.

### 23.2 Frosted glass
`glassSurface(shape, tint)` (`core:ui` `components/Chat.kt`) is the surface of everything that
floats over the conversation: the input pill, the round attach / interrupt buttons, the read-only
bar, the model capsule, the composer's notes and the header capsules. The page records the list as
a coui `LayerBackdrop` (`LocalGlassBackdrop`); the surface blurs it (`couiGlassSurface`, 24dp)
under ColorOS 17's floating-toolbar tint layers (`BottomFloatingToolbarDefaults.BlurColorsLight` /
`BlurColorsDark`: `ColorDodge` + `Luminosity` in light, `Overlay` + `Luminosity` in dark) and a
hairline rim. The `Luminosity` layer sets the surface's brightness whatever is behind it. The first
version tinted with the card colour at 62–72 % alpha: over white message text in the dark theme
that let the brightness through and the composer read as a light-grey slab (seen on the device).
The floating controls are siblings of the recorded layer, never inside it. Without runtime shaders (below Android 13), with system blur off,
on the wide (rail) layout and in tests the surface is the opaque card colour.

**The composer is a ColorOS toolbar.** One row: the attach key, the field, the send key. The keys
are `ComposerKey` = coui's `HighlightedIconButton`, the same 40dp glass disc as the title bar's
back button (halo, press light, touch motion), 10dp apart as toolbar keys are; the field is a 40dp
capsule that grows with the text. The send key fills with the accent when there is something to
send. "Stop" is no longer a fourth key: while a turn runs it is a labelled capsule (red icon) at
the end of the row above the field, opposite the model capsule. The row keeps the page's 16dp
gutters. Before, the field was a 48dp pill with its own send disc inside, between two 48dp discs
of a different make: three styles in one row.

### 23.3 Header capsules
30dp high, 13sp medium, 8dp apart, glass. A state capsule keeps its colour as a soft tint with a
7dp dot; an action capsule (terminal, sub-agents, session tools) has an accent icon. Tapping the row
still opens the session info panel.

### 23.4 Keyboard
The page shrinks with the keyboard (`imePadding`). The list used to keep its top anchored, so the
keyboard covered the newest messages. Now the conversation moves with its bottom edge
(`FollowBottomEffects`): shorter viewport → scroll by the lost height; taller → move by what is
still missing for the row at the edge to keep its distance (the list already follows by itself when
it was at its end). The same happens when the composer grows a line. Reading further up stays
"reading": the pill stays, nothing jumps to the latest. Covered by
`ChatListTest.theKeyboardPushesTheConversationUpInsteadOfCoveringIt`.

### 23.5 Motion
- `ExpandBody(expanded) { … }`: the body under a collapsible row grows out of the row and fades in
  (spring), folds back the same way; the arrow turns with it. Used by thinking, tool calls, tool
  output, diffs, sub-agent rows, usage details, the agent map's cards and New session's options.
- Timeline rows are `animateItem()`: a step group opening or closing, a new message and the
  "replying…" row fade and slide to their place.
- The send button eases between idle and ready (colour, a small spring).

### 23.6 Menus (`AppMenu`)
One component for every dropdown (new terminal's shells, the terminal and Files "more" menus,
sort): a COUI17 list popup whose rows are one group, so there are **no divider bars between rows**,
on frosted glass. The popup is its own window with no page to blur, so `rememberPopupGlass` takes a
PixelCopy of the activity window at 1/8 size when the menu opens, box-blurs it, and hands it to the
popup as its static backdrop; with blur off or below Android 8 the popup keeps COUI's opaque surface.

### 23.7 Terminal width
A terminal sized to the phone's width has 40–50 columns, so programs wrap every long line. The
terminal's "more" menu now offers the width: fit the screen, 80 columns or 120 columns
(`TerminalUiState.columns`, remembered in `terminalPrefs`). With a fixed width the desktop PTY is
resized to it, lines stay whole and the grid scrolls sideways at a stable width.

### 23.8 File previews
- **Keeping a previewed file does not fetch it again.** `FileStore.download` copies the preview
  copy into `Downloads/SwitchTether` when it is still good by `resolve`'s rules (same size and
  modified time, or made without them less than 10 minutes ago) and only otherwise downloads from
  the computer. It used to transfer the file a second time even while it was on screen.
- **Previews opened from the chat run under the system bars.** `FullScreenDialog` (file viewer,
  picture viewer) is an edge-to-edge window: the page colour continues under the status and gesture
  bars, the bar icons keep the page's colours, and there is no dim or window animation. A plain
  `Dialog` stopped at the bars and showed a strip of its own colour.

### 23.9 Run settings follow the agent
- The permission mode Claude Code calls *bypass permissions* is labelled **Bypass** in both
  languages (it was 跳过权限); its description starts with the agent's own term.
- Desktop (`agents/claude/managed.rs`): Claude Code reports its permission mode in `system`
  messages (`init`, and `status` when it changes by itself, e.g. on leaving plan mode). The managed
  session now records it, so the panel shows the agent's mode, not just the last one set from the
  phone. **Needs a desktop release to take effect.**
- Not solved: a session that runs in VS Code or a terminal does not report its mode or effort to
  the desktop, so the panel cannot show them; and an unset effort is shown as 默认 (the agent's own
  default), not as the level Claude Code resolves it to.

### 23.10 Evidence and limits
Robolectric (API 32): `PageGalleryScreenshotTest` `page-session-*` for the layout in its opaque
fallback, `ChatListTest` for the keyboard behaviour, the existing chat / approval / question tests
for the collapsible rows. On the user's device (dark theme, one screenshot of the first version):
the floating layout and the keyboard push work; the glass tint and the doubled top fade were wrong
and were replaced as described above. **Not seen on a device after those fixes:** the toolbar-recipe
glass, the bottom edge, the menus' frosted snapshot, the animations in motion, the IME animation
frame by frame (the list follows one frame behind the inset), the fixed-width terminal against a
real shell, the edge-to-edge preview windows, and the download-from-cache path (no automated test:
it needs MediaStore).

## 24. Slash commands in the composer (2026-11-24)

Typing "/" in the session composer works like in Claude Code: the matching commands appear in a
panel over the input row, and a tap runs one.

### 24.1 When the panel shows
- Only for **Claude Code** sessions whose composer can send (started by SwitchTether, or taken
  over with full control; also a conversation that the next message resumes). Codex and every
  other agent get no panel: the phone does not invent commands for them.
- While the draft is one token that starts with "/" (`slashQuery`): "/", "/co", "/compact". A
  space or a line break ends it ("/compact " is a chosen command with arguments to come), and so
  does any text before the slash. The caret position is not looked at: the draft is a plain string.
- Matches (`filterSlashCommands`): names (or the desktop's `aliases`) that start with what was
  typed, then names that contain it, case-insensitive, at most 8.
- The send key is unchanged: "/compact" typed by hand and sent is an ordinary message.

### 24.2 The panel
`SlashSuggestionPanel` (`ui/sessions/SlashPanel.kt`) sits in the composer directly above the input
row: the composer's glass (`glassSurface(SquircleShape(20.dp), halo = true)`, opaque where blur is
not available), at most 240dp high and scrolling beyond that. A row is at least 44dp: "/name" in
medium weight, the argument hint after it in the summary colour, the description below as a
footnote. Press feedback is clipped to the panel (the first and last rows carry its corners). It
grows out of the input row and fades in (`AnimatedVisibility`: fade + `expandVertically` from the
bottom) and leaves the same way. The conversation does not move for it: the panel's height is
taken out of the composer height the list makes room for.

The field is `DraftInputPill`, a copy of COUI17's `InputPill` (same measures, glass and send key)
that keeps a `TextFieldValue`: when the draft is replaced from outside the keyboard (a picked
command, a failed message put back for editing) the caret goes to its end. `InputPill` takes a
plain string, so the caret would stay inside the inserted "/name ". This belongs in COUI17 as an
`InputPill` overload; the copy goes when the library has it.

### 24.3 Where the list comes from
- The desktop: `POST /v1/sessions/{id}/claude/commands` (the wire format is in docs/PROTOCOL.md),
  asked once per session while the phone drives a running Claude Code process, and again after a
  reconnect. Names come without the slash; entries without a usable name and repeats are dropped.
- The phone's own list (`builtinSlashCommands`), with descriptions in both languages
  (`strings_slash.xml`): compact, clear, context, cost, model, permissions, plan, mcp, rename,
  export, btw, review, init, help. Of these only compact, clear, cost, review and init are
  messages; the rest are the phone's own actions (24.4).
- What Claude Code runs when a command arrives as a message (stream-json, which is how managed
  and taken-over sessions run) comes from its command metadata (2.1.331 binary): `prompt` commands
  and `local` commands with `supportsNonInteractive` (`compact`, `clear`, `context`, `mcp`,
  `model`, `rename`, `usage` alias `cost`, `init`, skills such as `code-review` alias `review`).
  `local-jsx` commands draw a terminal UI and do nothing there (`memory`, `plan`, `export`, `btw`,
  `permissions`, `help`, …), so `memory` is not offered at all. The process's own list (the
  `initialize` commands) is filtered the same way by Claude Code before it reaches the desktop.
- **Fallback rule** (`mergeSlashCommands`): any failure of the call (an older desktop answers
  404 / 422, no connection) is an empty list, silently. With an empty list the built-in one is
  used alone. Otherwise the process's list decides what is a message: a built-in message stays
  only when the process lists it by name or alias, and then shows as the process's entry (its
  name, e.g. `usage` for `cost`; its hint and aliases; its description unless blank, else the
  phone's text). The phone's own actions always stay, with the phone's text and no argument hint
  (they open the phone's screen, not the terminal command). The process's other commands follow
  in its order.

### 24.4 What a tap does (`slashActionFor`)

| Command | Action |
|---|---|
| model | the model / effort sheet |
| permissions | the permission mode sheet |
| context, mcp, plan, export, btw | the session tools page (it has no anchors, so it opens at its top) |
| rename | a dialog asking for the new title (the same `rename` tool as on the tools page) |
| help | a half-screen panel (`PanelSheet`) with every command of this session; a row there does the same as in the "/" panel |
| clear | the draft becomes "/clear ": it throws the context away, so it is never sent by one tap |
| anything else with an argument hint | the draft becomes "/name " and the keyboard stays for the argument |
| anything else | "/name" is sent at once, through the normal send path (optimistic bubble, queueing, retry) |

For the phone's own actions the typed "/…" is cleared and nothing is sent. They need the Claude
Code session tools (`claudeTools`); a conversation that is not running has none, so its panel
lists only the commands that are messages (and `model` when the model can be changed). None of the
built-in commands has an argument hint, so on an older desktop "compact", "cost", "review" and
"init" are sent as they are; arguments can still be typed by hand.

One bubble per command: the desktop sends the message with its own `uuid`, Claude Code's
`--replay-user-messages` echo carries the same `uuid` (the VS Code extension matches replays by
`uuid` and only rewrites the bubble's content), and the desktop's `<command-name>` mapping keeps
that id, so the replayed "/compact" and its command record are one event; the desktop tags it
with the phone's `clientId` (normalized text "/compact") and the optimistic bubble is replaced.

### 24.5 Evidence and limits
JVM / Robolectric (API 32): `SlashCommandsTest` (query, filter order and cap, merge, action
mapping), `SlashCommandsViewModelTest` (older desktop and failing repository fall back to the
built-in list, merge, reload after reconnect, send / insert / phone-side pick, no commands for
Codex and read-only sessions), `SlashPanelTest` (the panel appears for "/", tapping "/compact"
sends "/compact", an inserted command leaves the caret at the end, no panel for Codex), and
`SlashCommandsApiTest` in `core/remote` (path, body, lenient parsing, 404 / 422).
**Not verified:** anything on a device (the glass and halo of the panel, the animation, the panel
together with the IME, the caret with a real keyboard's composing text), and the call against a
real desktop: the desktop side of `claude/commands` was written at the same time and the two were
not run together. Which commands run headless is read from Claude Code's metadata, not from a
live run. Not known without a live run: whether `<command-name>` records the typed alias or the
canonical name ("/cost" typed by hand might echo as "/usage" and then not replace its bubble), and
what `/clear` does to the managed session's id on the desktop (Claude Code starts a new session
id; the stream's `session_id` changes).

### 24.6 Review fixes in the same area (2026-11-24)
- **Session cost** (`SessionCost.kt`): `costUsd` is Claude Code's `total_cost_usd`, a running
  total ("cumulative across turns in streaming-input sessions … read the latest result rather than
  summing across results"; a resume continues from the saved total, `/clear` resets it, crash
  results carry 0). The cost is the latest total of each run, summed over runs (a lower total
  starts a run; zeros are skipped); tokens of a result are per turn and are summed. The usage row
  in the conversation labels the number "session so far", not as the turn's cost.
- **MCP elicitations** (`Question` in `Renderers.kt`): "MCP server X asks" once (not repeated over
  every field), the desktop's `message` when no question already says it, the `url` with an
  "Open" key through the app's link confirmation, Decline (`action: "decline"`) next to Cancel,
  and a `__confirm__` question shown as a confirmation: no option row, no text field, the submit
  key reads "Accept".
- **Plan approval**: the VS Code extension sets the permission mode first (`acceptEdits`, or
  `default` for "manually approve edits", which it also sends as a `setMode` suggestion) and then
  allows `ExitPlanMode`; the phone now does the same order. A refused mode change still approves
  the plan (as the extension does) and is shown as a notice.
- **End session** is also in the session info sheet (same confirmation), so a Codex session
  SwitchTether started, which has no Claude Code tools page, can be ended.

## 25. Git writes and "本轮改动" (2026-11-25)

**Git page** (Workspace → Git, `ui/workspace/GitPane.kt`). With a desktop that reports feature `git_write`:

- Branch card: tapping it opens the branch panel (`PanelSheet`): local branches (current marked), remote branches without
  a local one (tap = `git switch <name>`, which creates the tracking branch), "新建分支" (name + "创建并切换").
- Under it three `SmallButton`s: 获取 · 拉取 ↓n (needs an upstream) · 推送 ↑n, or "发布分支" for a branch without upstream
  (`push -u origin <branch>`). The running one shows its spinner; all writes are disabled while one runs.
- 已暂存（n） with "全部取消暂存" and a per-row unstage key; 未暂存（n） with "全部暂存" and per-row discard (↺) and stage
  (+) keys. Discard always confirms ("已跟踪的文件恢复为暂存时的版本，未跟踪的文件会移到电脑的回收站"). Rows still open the diff.
- Commit: a multi-line "提交说明" field and "提交（n）" (accent `SmallButton`), enabled with staged files and a message.
- Success is a toast ("提交：完成"); a failure is a dialog with git's own message (credentials, hooks, non-fast-forward…).
- Every write asks for the `git_write` step-up once per grant (re-asked once if the desktop says the grant expired).

An older desktop (no `git_write`) keeps the page read-only with a footnote saying so. The staged / unstaged split now
reads porcelain v2's `.` as "unchanged" (`GitStatusEntry.staged` / `.unstaged`); before, every changed file was listed
as staged.

**本轮改动** (session page). A capsule "本轮改动" appears next to the branch capsule when the session's folder is a Git
repository (the desktop reported a branch). It opens the Git page for that folder (`Route.GitReview`) with the files the
latest turn edited — Edit / Write / MultiEdit / NotebookEdit / apply_patch calls and `file_change` events after the last
user message, denied / failed edits excluded (`TurnFiles.latestTurn`, `core:remote`) — marked "本轮", sorted first and,
with the switch "仅本轮" (on by default), the only ones listed; "全部暂存" then stages just those. Absolute paths and paths
relative to the session folder are mapped into the repository (`TurnFiles.relativize`; Windows paths case-insensitive);
an untracked folder counts when it holds a turn file.

Evidence: `TurnFilesAndGitTest` (core:remote: turn extraction, patch headers, relativize, porcelain flags, shared
fixtures), `GitReviewModelTest`, `GitReviewScreenTest` (Robolectric: turn filter and badge, writes offered, commit
disabled without a message, read-only desktop), `GitWriteFlowTest` (JVM, TLS MockWebServer: review → stage → commit,
refused commit keeps the draft and shows git's message). Desktop: `git::tests::*` (temp repositories, bare remote),
e2e `git_writes_need_step_up_and_replay_by_command_id`. **Not verified:** on a device or emulator (layout of the action
rows, the step-up prompt for the new scope, toasts), and against remotes that need credentials.

## 26. Session cost and tokens; usage per project (2026-11-25)

### 26.1 Cost capsule and sheet (`SessionCostSheet.kt`)
- A capsule in the session page's header row (after the branch / terminal / sub-agent capsules,
  before the tools capsule) shows the session's cost — or its tokens when no turn reported a cost
  (observed transcripts, Codex) — with the usage icon. It appears once a loaded turn reported usage.
  Tapping it opens **费用与用量**, a `PanelSheet` (page colour, page gutters, §22.2).
- Hero card like the usage page's: total cost (4 decimals) or total tokens, "平均每轮", then turns ·
  total tokens · cache hit rate.
- **逐轮趋势**: one bar per turn (`sessionUsageBreakdown`, split at user messages). Metric chips
  费用 / Token when a cost exists. Cost bars are the turn's growth of the agent's running total (a
  lower total starts a new run, as in §24.6); token bars stack cache read / cache write / input /
  output (one accent family for the prompt, the warm tone for the reply) with a legend. Bars grow
  in with the expand spring (`dampingRatio 0.9, stiffness 420`, the same as `ExpandBody`) when the
  sheet opens or the metric changes; the picked bar is full colour, the rest dimmed; bars are
  6–22dp wide, spread over the card when few, scroll sideways (starting at the latest turn) when
  many. Tapping a bar picks its turn; its detail box (the heatmap detail's look) cross-fades:
  "第 n 轮", time, the first line of the message that started it, cost · tokens · duration, the mix.
- **Token 构成**: input / output / cache write / cache read rows with an animated share bar, then
  cache hit rate and "最近一次请求的上下文 ≈ N tokens" (the last request's prompt tokens; for Codex,
  whose input includes cached tokens, the input alone).
- Notes under it when only part of the conversation is loaded. Last, an arrow row
  "项目「X」的用量" opens the project page (§25.2); the sheet closes first so back returns to the
  conversation.

### 26.2 Usage per project
- Usage page: **按项目** after 按模型 (`GET /v1/usage/projects`, PROTOCOL §7.3): projects by cost
  with an animated share-of-period bar and an arrow, then "其他项目" and "未关联到会话" as quiet rows,
  and a note that only detail logs keep the session link. Hidden on older desktops (404).
- `Route.ProjectUsage(project)`: the project's cost, directory, requests / tokens / sessions, and its
  sessions most expensive first (agent · tokens · last activity, share of the project's cost);
  range chips 今天 / 7 天 / 30 天 / 全部 (default 30 days); a row opens the session.

### 26.3 Evidence and limits
`SessionUsageBreakdownTest` (running-total deltas and restarts, turn split, dedupe, transcripts,
Codex cache), `CostScreenshotTest` (sheet light / dark / tokens-only, bar tap picks a turn, project
link, capsule opens the sheet, usage-per-project row opens the project, project page row opens the
session, older desktop), fixture `usage_projects.json` (Rust and Kotlin). Robolectric only; nothing
seen on a device. The per-project numbers were not checked against a real CC Switch database
(only the SQL compiles and the join / grouping is unit-tested).

## 27. Tablets and unfolded foldables: list | detail (2026-11-25)
- From a window width of 840dp (`usesTwoPanes`), with the navigation rail that wide windows already
  had: the tab's root (Home, Sessions, Workspace, Manage) stays on the left (40 %, 320–420dp), and
  what it opens shows on the right with the usual push / pop / predictive-back motion. Narrower
  windows — every phone, a folded foldable, split screen — are unchanged (the same code path as
  before).
- With nothing open the right pane shows a hint (`PaneEmptyState`). A row opened from the list
  replaces the detail (`NavViewModel.openFromList`), so back clears the selection instead of
  walking through earlier rows; deeper pages (tools, sub-agents) push as before. The open session's
  row is marked with a soft accent fill (`LocalSelectedSessionId`). The navigation state lives in
  the ViewModel, so rotating or folding keeps the selected session (folded, it shows full screen).
- Files: from 720dp of available width (the tablet's detail pane), an open file or media viewer
  shows beside its folder; back closes the file first.
- Evidence: `TwoPaneTest` (width gate incl. phone and split-screen widths, list replacement and
  back, w1280dp screenshots light / dark with the marked row). Not run on a tablet or foldable.

## 28. Push wake and conversation search (2026-11-25)

### 28.1 Push wake (UnifiedPush)

Settings → Notifications → **推送唤醒 / Push wake**, opt-in. PROTOCOL.md §7.12, SECURITY.md §7b.

- The app implements the UnifiedPush connector protocol (spec AND_3.1) itself, without the
  connector library: `push/PushWake.kt` (state, registration, message handling, desktop hand-over),
  `push/PushReceiver.kt` (exported messaging receiver for `NEW_ENDPOINT` / `MESSAGE` /
  `UNREGISTERED` / `REGISTRATION_FAILED` / `TEMP_UNAVAILABLE`, and the `RAISE_TO_FOREGROUND`
  service a distributor may bind while delivering), manifest `<queries>` for the distributor
  `REGISTER` action. `REGISTER` / `UNREGISTER` / `MESSAGE_ACK` go to the chosen distributor's
  package with `FLAG_SHARE_IDENTITY` (API ≥ 34) or the `pi` PendingIntent (older); `REGISTER`
  carries the computer's VAPID key and "SwitchTether · <computer>" as its description, plus the
  AND_2 `application` extra for older distributors. Every incoming intent must carry the current
  connection token; a failed registration gets a new token next time (spec).
- On `NEW_ENDPOINT` the phone creates RFC 8291 receiver keys (`core:remote`
  `push/WebPushCrypto.kt`) and, on this and every later connection, `PUT /v1/push` hands endpoint +
  keys to the computer unless it already has them (`GET /v1/push`). A computer without the feature
  (404) shows "update SwitchTether on the computer".
- On `MESSAGE` the bytes must decrypt with the phone's keys (else ignored, still acknowledged);
  the content is never used. A genuine wake schedules an expedited `ReconnectWorker` (unique, KEEP):
  connect (≤ 20 s), read the live session list once into the notifier (`Notifier.catchUp`: waits
  that began while away are announced), keep the connection 20 s for the event replay, then release
  it unless the app or the monitor service needs it.
- UI: one switch whose summary is the state (off / no distributor — install e.g. ntfy / waiting for
  the distributor / ready, the computer gets it on next connect / on via <distributor>, last wake /
  computer too old / refused (reason)); radio rows when several distributors are installed; "send a
  test wake" (`POST /v1/push/test`, shows the push server's HTTP status); a footnote on what the push
  server learns. Distributors are re-read on resume.
- Unpair keeps the distributor registration but forgets which computer had the endpoint, so the
  next paired computer receives it.

### 28.2 Search conversation content

On the Sessions page, while the search bar is open, a chip row offers **会话 / 搜索对话内容**.
The second mode replaces the list with `GET /v1/sessions/search` results (PROTOCOL.md §7.13):
`ContentSearch` debounces 400 ms, cancels the request of an older query, follows the agent filter,
and maps 404 to "computer too old". Each result is a row with the session title (else the agent),
"agent · project folder · relative time · N matches", and up to three lines of the snippet with
every match of the query in bold accent (`highlightMatches`, case-insensitive); tapping opens the
session. Notes for "searching…", no match, partial scan ("older sessions were not searched") and
errors. Leaving the search (cancel) returns to the list.

### 28.3 Evidence and limits

- Tests: `core:remote` `PushAndSearchTest` (RFC 8291 Appendix A vector decrypts; a message made by
  the desktop's Rust encryptor decrypts; wrong keys / garbage give null; fixtures
  `push_wake.json`, `session_search.json`); app `PushWakeTest` (Robolectric: no distributor,
  REGISTER extras and target package, foreign tokens ignored, NEW_ENDPOINT + ACK, encrypted
  message wakes / garbage and foreign tokens do not, REGISTRATION_FAILED changes the token,
  UNREGISTER on disable, desktop hand-over once over a fake HTTP desktop, 404 → unsupported);
  `ContentSearchTest` (debounce, only the latest query counts, slow answers cancelled, 404 / 400,
  highlighting incl. CJK).
- Desktop: Rust unit tests for encryption, VAPID, validation, coalescing, back-off, 410 drop and
  bus transitions; e2e `push_wake_reaches_the_endpoint_while_the_phone_is_away` (real server,
  loopback push endpoint). The desktop's encrypted message was also decrypted by an independent
  RFC 8291 implementation (Python `cryptography`) during development.
- **Not verified:** a real distributor (ntfy, Sunup, NextPush…) on a device, delivery while the
  phone dozes, a push server that requires VAPID, the raise-to-foreground binding, the settings UI
  and the search UI on a device, search over a large real history.

## 29. Files: roomier previews, a lightbox, the cache you can open, no dead ends (2026-11-25)

User report: the file preview was cramped, the picture viewer was rough, opening the cache did not
work well, files the app cannot show led nowhere, and a file with a name seen before showed the old
copy. Code in `ui/files/` and `files/`; ANDROID_UI §18 and §23.8 still apply.

### 29.1 Spacing
- **Preview title bar** (`FilePreviewHeader`, shared by the Files page, chat links, the storage
  page and the Workspace text editor, which had a bar of its own): 60dp, back key on the 8dp edge,
  12dp to the title, title 17sp semibold over a 13sp line (folder · size · when), keys 4dp apart
  and 12dp from the edge. It used to be 6dp margins, a 16sp title and a 12sp line.
- **Code viewer**: the code is a rounded card (16dp squircle) 12dp from the screen edges instead of
  a square slab 8dp from them; 12dp above the first line, 12dp between line numbers and code and at
  the end of a line; the tool row and the find bar line up with the card.
- **Markdown**: 24dp side margins and 12dp above the first block (16 / 8 before); the Rendered /
  Source switch and notices have 10dp under them.
- The Workspace editor's raw "utf-8 · LF · size · time · sha" line is gone: size and time are in the
  title bar's second line; encoding and line ending stay as a quiet line for editing.

### 29.2 Lightbox (`ImageLightbox`)
Pictures from chat (attachments, pictures in replies, files the agent looked at) open in one
full-screen viewer:
- black behind the picture; pinch, double-tap zoom and pan with Telephoto, as in the Files viewer;
- the pictures of one message are swiped sideways (`ImagePreview.gallery`), "2 / 3" under the name;
- pull the picture down (or up) to close: it follows the finger, shrinks a little and the black
  fades with the pull; let go short of 120dp and it springs back; a fast fling closes too. Not while
  zoomed in (then a drag pans);
- a tap shows or hides the controls, which also step aside after 4 s: close, name and position on a
  dark gradient at the top; Share, Save and Open with… on a dark gradient at the bottom. They use the
  over-media style of COUI's media bar (white on translucent dark, whatever the theme) and the status
  and navigation bar icons turn light while it is open;
- Save keeps a picture from the computer as a download (`FileStore.download`) and a picture that only
  exists in the message as bytes (`FileStore.saveBytes`); Share / Open with hand over a temporary
  copy (`FileStore.cacheBytes`, keyed by content).
The old viewer closed on any tap (also on the picture), zoomed with a hand-made transform and put a
48dp glass disc and a pill button over the picture.

### 29.3 Files the app cannot show
- An archive, installer, office document or other unknown type opens as a card (`FileHandOffCard`):
  icon, name, size and type, "No built-in viewer…", **Download to phone** and **Open with…**. Nothing
  is fetched until one is chosen; both show their progress in the card; after downloading the card
  offers Open and Manage downloads. It used to fetch the whole file into the preview cache first and
  then only say there was no viewer — and a file over the 200 MB preview cap ended in an error.
- A media file over the preview cap shows the same card with Download (a download has no cap).
- A text link that turns out to be binary now continues to that card ("Preview or open with another
  app"), and the text viewer opened from chat has Download in its bar and in the "only the
  beginning is shown" notice; before, both were missing there.
- "Open with…" of an unknown type asks with `*/*` and a type from Android's `MimeTypeMap` when the
  app's own table does not know the extension: `application/octet-stream` matched almost no app, so
  the chooser came up empty.

### 29.4 The cache can be opened
Downloads & storage lists **Recently previewed** under the cache card (up to 20, newest first:
name, folder, size, when). A row opens the copy inside the app; its menu offers Open, Open with…,
Share, **Keep in Downloads** (copied from the cache, no transfer) and Remove from cache. Every copy
records the remote path it came from (`.source` beside it in its cache folder). Downloads open inside
the app too, text and code included (read from the local copy, so this works offline); before, only
media opened here and everything else went straight to other apps.

### 29.5 Same name, stale copy
Cause: a link from chat carries no size or modified time. Without them `FileStore.resolve` (1) took
any earlier **download** of that path as current, however old ("unknown values do not disqualify"),
and (2) trusted a cache copy made without them for 10 minutes. A file changed on the computer — or
regenerated under the same name — kept showing the old copy.
Now, when the caller has neither, `resolve` and `download` first ask the computer (`statRemote`: one
`fs/list` of the folder, 5 s timeout) and key and validate with the answer; a file that changed is
fetched again. Copies are keyed by desktop, full path, size and modified time (`FileCache.keyFor`),
so the same name in another folder is another file; a new copy of a path replaces the older one in
the cache. Offline, `resolve` shows the newest copy of that path that is on the phone (a download,
else the cache) instead of an error. Reload in the viewer still forces a fresh fetch.

### 29.6 Evidence and limits
JVM tests `FileFreshnessTest` (keys per folder and per version, a new copy replacing the old one,
the source note, listing lookup, parent folders) and the existing `FileCacheTest` /
`DownloadIndexTest`; Robolectric screenshots `files-code-{light,dark}`, `files-handoff-light`,
`files-handoff-progress-dark`, `files-lightbox` (API 32, synthetic files). **Not seen on a device:**
the lightbox gestures (pull to close, swipe between pictures, zoom vs. drag), the light system-bar
icons, sharing / saving from it, opening cached copies with other apps, the stat request against a
real desktop and the offline fallback.

## 30. ColorOS SDK alignment: transitions, composer rim, expand, selection, menu blur (2026-11-25)

Values compared against the COUI SDK bundled in OPPO Health 6.7.17 (com.heytap.health, HeyTap-signed),
read for the numbers only; no SDK code or resources were copied.

- **Page push / pop** (`ui/nav/AnimatedNavHost.kt`) follow `Animation.COUI.Activity` instead of a
  Material shared X axis. Push: the new page slides in over the whole width, opaque, 350ms on
  (0.3, 0.1, 0.3, 1); the page underneath moves to −30% on (0.3, 0.15, 0.3, 1) and dims under a black
  scrim to 0.5 on COUIEase (0.33, 0, 0.67, 1) — it stays visible. Pop runs back: the top page leaves to
  +100% on (0.25, 0.1, 0.3, 1), the page underneath returns from −30% on (0.3, 0.26, 0.4, 1) and the
  scrim clears. Every page now paints the page colour, so a full-width slide never shows two pages
  through each other. The predictive back gesture maps its progress linearly onto the pop (an eased
  seek made the page jump at the start of the drag); release and cancel finish on the same spec.
- **Tab switches** are a 150ms cross-fade without the 0.96 scale-in (a Material fade-through mark);
  the tablet list pane's `Crossfade` uses the same 150ms.
- **Composer rim** (`coui-patterns` `glassSurface`): the 0.5dp solid hairline (dark on the light
  theme) is replaced by the material edge with its inward fade: `TYPE_FRAMEWORK_CIRCLE_1` for round
  keys (light 0.2 / 1.9dp / fade 0.15, dark 0.6 / 1.9dp / 0.1), `TYPE_FRAMEWORK_CAPSULE_1` for the
  field and capsules; the rim follows the control's own corner radius. Without runtime shaders the
  hairline stays.
- **ExpandBody** (`coui-patterns` `States.kt`): 400ms on (0.3, 0, 0.1, 1) both ways for size and fade,
  so alpha tracks the shown height fraction (`ExpandableRecyclerConnector`) and finishes with the
  400ms `ExpandArrow` turn (it used to be a ~300ms spring with a short separate fade).
- **Selected session row** (tablet list | detail): the neutral press layer of a selected COUI card
  row (`couiColorPress`, #1F000000 / #33FFFFFF) instead of a 10% accent fill.
- **Menu snapshot blur** (`coui-patterns` `Menu.kt`): three box passes of radius 3 at 1/8 scale,
  σ ≈ 26px, matching the popup material's 25px (it was ≈ 36px). The snapshot's tint layers live in
  coui-ui's list popup.

Not seen on a device: the transitions on real hardware, the material rims (they need runtime
shaders with a GPU), the predictive back feel.
