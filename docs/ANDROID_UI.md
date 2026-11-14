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
| Shell | `BottomFloatingNavigationBar` (floating glass capsule over a `LayerBackdrop` of the page, `expansion` "+" melt-ball on the Sessions tab → New session, hidden with animation on detail pages) + `BottomFloatingNavigationBarItem`; `NavigationRail` + `NavigationRailItem` (≥ 600 dp); `COUIIcons` |
| Home | `Scaffold`, `TopAppBar` (large, subtitle = desktop), `Card` groups, `BasicComponent` (connection, session rows, states), `ArrowPreference` + icons (quick links), `SmallTitle`, `TextButton` |
| Sessions | `TopAppBar`, `TextField`, `TabRowWithContour` (Live/History/All), `ChipTabGroup` (agents), `PullToRefresh`, `BasicComponent` rows in group cards, `EmptyState` (New session = capsule expansion) |
| Session detail | `SmallTopAppBar`, `IconButton`s, `Card` surfaces for tool/approval/diff cards, `Button`/`TextButton`, `Checkbox`/`RadioButton` (questions), `LinearProgressIndicator`, `WindowDialog` (approval confirm), `WindowAlertDialog` (commands, links, interrupt), `TextField` composer — the timeline/code/diff/tool output stay monospace and dense |
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
- coui17's `WindowBottomSheet` shows a hard-coded Chinese close label (关闭).
- Terminal cell width assumes a monospace font; CJK double-width glyphs may drift slightly.
- Re-pairing while already paired via a deep link is not supported (unpair first).
- `ui` component registry has only two built-in components.
