# SwitchTether Remote Protocol — v1

Status: **implemented** (desktop `switchtether-core`, Android `:core:remote`).
Wire types live in `src-tauri/crates/switchtether-core/src/protocol/` (Rust, source of
truth) and `android/core/remote/src/main/kotlin/.../protocol/` (Kotlin mirror).
Shared JSON fixtures in `protocol/fixtures/` are parsed by tests on both sides.

## 1. Transport

| Item | Value |
|---|---|
| Transport | HTTPS (HTTP/1.1) + WebSocket over **TLS 1.3 only** |
| Server auth | Self-signed ECDSA P-256 certificate, **pinned** by SHA-256 of the leaf DER (from QR) |
| Client auth | **Mutual TLS required on every connection.** Client cert = Android Keystore self-signed P-256 cert (non-exportable key). Identity = SHA-256 of client leaf DER |
| Default port | `47615` (configurable) |
| Bind | `0.0.0.0` / `[::]`, but the accept loop drops peers whose source address is not private (RFC1918, loopback, link-local, CGNAT 100.64/10 incl. Tailscale, IPv6 ULA/link-local) unless the user explicitly enabled "allow public addresses". Optional "Tailscale only" and "localhost only" modes. |
| API prefix | `/v1` |
| Encoding | JSON, UTF-8, `camelCase` field names. Timestamps are RFC 3339 strings (UTC) unless the field name ends with `Ms` (unix epoch millis). |
| Max request body | 1 MiB for JSON; 8 MiB for upload chunks (`PUT /v1/files/upload/{id}`, attachment chunks) |

Fingerprints are lowercase hex SHA-256 (64 chars). In the UI they are shown as
groups of 4 (`ab12 cd34 …`), short form = first 16 hex chars.

## 2. Envelopes

Successful responses return the resource JSON directly (no wrapper).

Errors always use:

```json
{ "error": { "code": "device_revoked", "message": "human readable", "retryable": false, "details": {} } }
```

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `bad_request` / `validation_failed` | Malformed input |
| 401 | `unpaired` | Client cert not registered (only pairing endpoints allowed) |
| 403 | `device_revoked` | Client cert belongs to a revoked device (connection is also closed) |
| 403 | `step_up_required` | Operation needs a fresh step-up (biometric) grant, see §4 |
| 403 | `forbidden` | Policy forbids this (e.g. sensitive path) |
| 404 | `not_found` | |
| 409 | `conflict` | Version/hash precondition failed (files), or state conflict |
| 409 | `command_in_progress` | Same `commandId` is still executing |
| 410 | `pairing_expired` | Pairing session expired/used |
| 413 | `payload_too_large` | |
| 422 | `capability_unavailable` | Agent/session does not support the requested control (e.g. BLOCKED in-place takeover) |
| 429 | `rate_limited` | `details.retryAfterMs` |
| 500 | `internal` | Message is sanitized (no paths/secrets) |
| 503 | `backend_unavailable` | CC Switch backend not initialised (e.g. DB migration screen) |

Every response carries an `X-Request-Id` header. Clients may send `X-Request-Id`
(≤ 64 chars, `[A-Za-z0-9-]`) to correlate; the server echoes it.

## 3. Pairing (unpaired client certificate)

1. Desktop creates a pairing session (Settings → Remote → Pair phone). QR encodes:

```
switchtether://pair?v=1&h=192.168.1.20,100.88.1.7&p=47615&fp=<serverCertSha256>&id=<pairingId>&s=<secret>&n=<urlencoded desktop name>
```

   - `secret`: 32 random bytes, base64url (no padding). Single use. TTL 5 minutes.
   - `h`: candidate hosts (LAN IPv4/IPv6, Tailscale IP / MagicDNS name).

2. Phone generates (once per install) its Keystore identity key + self-signed cert, and
   (if the device has a secure lock screen) a **step-up key** that requires user
   authentication. Phone connects to each host candidate, TLS pinned to `fp`, presenting
   its client cert.

3. `GET /v1/hello` (allowed unpaired) →

```json
{ "protocolVersion": 1, "minProtocolVersion": 1, "serverId": "…", "serverName": "DESKTOP-01",
  "serverVersion": "0.1.0", "paired": false, "deviceId": null, "epoch": "…" }
```

4. `POST /v1/pair` (allowed unpaired)

```json
{ "pairingId": "…", "secret": "…", "deviceName": "Pixel 9", "platform": "android",
  "osVersion": "15", "appVersion": "0.1.0", "protocolVersion": 1,
  "stepUpPublicKey": "<base64 SPKI DER, P-256>|null" }
```

→ `200 { "deviceId": "…", "serverId": "…", "serverName": "…", "serverFingerprint": "…", "stepUpEnabled": true }`

   - The device identity is bound to the **TLS client certificate fingerprint of this
     connection** — the secret only proves physical access to the desktop screen.
   - Wrong secret → `403 forbidden`; after 5 failures the pairing session is destroyed.
   - **Pairing code** (no QR code): every pairing session also has a 6-digit `code`, shown on
     the desktop next to the QR code. The phone sends `{ "code": "123456", "deviceName", … }`
     instead of `pairingId` + `secret` (fixture `protocol/fixtures/pair_request_code.json`;
     non-digits are ignored). Wrong code → `403 forbidden`; no active code → `410
     pairing_expired`. Wrong codes are counted across all sessions: after 5, every active code
     is disabled (the QR secrets stay valid) until a new pairing session is shown. The phone
     knows the server certificate in advance only from the LAN advertisement (`fp` TXT) or not
     at all (typed address: trust on first use, then pinned); the verification code compared
     on both screens is the check on this path.
   - Global limit: 20 pairing attempts / minute / source IP → `429`.
   - Re-pairing an already-paired cert replaces the old record (same device id kept).

## 4. Step-up authentication (biometric gate)

High-risk scopes require a short-lived grant proven by a signature from the Keystore
step-up key (`setUserAuthenticationRequired(true)`), i.e. only after BiometricPrompt /
device credential succeeded on the phone. The server never trusts a client-side flag.

- `POST /v1/auth/step-up/challenge` `{ "scope": "terminal" | "files_write" | "config_write" | "port_forward" | "git_write" }`
  → `{ "challengeId": "…", "nonce": "<base64url 32B>", "expiresAt": "…" }` (TTL 60 s)
- Phone signs UTF-8 bytes of
  `"switchtether-stepup-v1\n" + deviceId + "\n" + challengeId + "\n" + nonce + "\n" + scope`
  with ECDSA P-256 / SHA-256 (ASN.1 DER signature).
- `POST /v1/auth/step-up/verify` `{ "challengeId": "…", "signature": "<base64>" }`
  → `{ "scope": "terminal", "expiresAt": "…" }` (default grant 15 min; per device+scope).
- `GET /v1/auth/step-up` → active grants.

| Scope | Required for |
|---|---|
| `terminal` | create terminal, attach input stream |
| `files_write` | write / create / rename / delete / upload files |
| `config_write` | provider credential update, config restore, MCP/skill writes |
| `port_forward` | start (or renew) a port forward lease (§7.11); the lease, not the grant, authorises its tunnel |
| `git_write` | Git stage / unstage / discard / commit / push / pull / fetch / checkout, and with `git_manage` branch delete / merge / merge abort / stash (§7.7) |

Devices paired without a step-up key cannot use these scopes (`step_up_required` with
`details.reason = "no_step_up_key"`).

## 5. Commands, idempotency and unknown outcomes

Every side-effecting request carries a client-generated `commandId` (UUID) — either in
the JSON body or as header `Idempotency-Key`.

- Same `commandId` + same device, completed → the stored result is returned with header
  `X-Command-Replayed: true`. **The side effect is not executed twice.**
- Same `commandId` still running → `409 command_in_progress`.
- `GET /v1/commands/{commandId}` → `{ "commandId", "kind", "status": "pending|succeeded|failed|unknown", "result"?, "error"?, "createdAtMs", "completedAtMs"? }`
  (`unknown` = the desktop restarted while the command was running.)
  Clients that timed out MUST query this before offering a retry, and must offer the user
  the choice rather than auto-retrying side-effecting operations.
- Command records are kept 24 h.

## 6. Event stream

`GET /v1/events?since=<seq>&epoch=<epoch>` → WebSocket. Server → client frames:

```json
{ "type": "event", "seq": 1042, "epoch": "…", "ts": "…", "topic": "session.event", "data": { … } }
{ "type": "resync", "epoch": "…", "headSeq": 1042, "reason": "gap|epoch_changed" }
{ "type": "pong", "ts": "…" }
```

Client → server: `{ "type": "ping" }` (every 20 s; the server answers `pong`). All topics are
delivered to every device; `command.completed` only to the device that issued the command.

- `seq` is a server-global, strictly increasing u64 within an `epoch` (random per desktop
  process start). The server retains the last 10 000 events in memory.
- If `since` is older than the retained window, or `epoch` differs, the server sends one
  `resync` frame: the client must refetch state over REST, then continue with live frames.
- Clients de-duplicate by `seq` and apply in order; out-of-order frames cannot occur on
  one socket, but clients must tolerate duplicates after reconnect.
- Server closes the socket with code `4401` (unpaired), `4403` (revoked), `4408` (idle
  timeout, nothing received for 60 s).
- The server also sends a WebSocket Ping frame every 20 s. The client's Pong (sent by any
  WebSocket stack without application code) counts as traffic, so a socket stays up while the
  phone's CPU sleeps and its own `ping` timer cannot run.
- Clients should not take silence measured across their own sleep as a dead socket: the
  Android client measures silence on a clock that pauses during sleep, and confirms a suspected
  dead socket with one `ping` (10 s to answer) before reconnecting. After a network change it
  checks the existing socket the same way instead of opening a new connection.

Topics: `session.upserted`, `session.removed`, `session.event`, `approval.requested`,
`approval.resolved`, `provider.switched`, `usage.updated`, `terminal.created`,
`terminal.exited`, `device.revoked`, `server.shutdown`, `command.completed`.

## 7. Resources (all require a paired, non-revoked device)

### 7.1 Status
- `GET /v1/status` → server info, epoch/headSeq, network mode, connections, terminals, backend availability, active step-up grants,
  `hosts` (current address candidates, same list as the pairing QR code; the phone merges them into its stored hosts on every connect)
  and `wake` (`[{ mac, broadcast, address }]`, Wake-on-LAN targets of the private, non-Tailscale IPv4 LAN interfaces).
- `GET /v1/capabilities` → per-agent capability matrix (see §8) and available terminal shells.
- `GET /v1/device` → this device's record · `DELETE /v1/device` → the phone unpairs itself (same effect as revocation).

### 7.2 Providers (CC Switch)
- `GET /v1/providers?app=claude|codex|gemini|…` → `{ "app", "currentId", "providers": [ProviderView] }`
- `POST /v1/providers/{app}/{id}/switch` `{commandId}` → `SwitchResultView`
- `POST /v1/providers/{app}/{id}/test` `{commandId}` → stream-check result
- `PUT /v1/providers/{app}/{id}/credential` `{commandId, field, value}` *(step-up `config_write`, write-only)*
- `GET /v1/providers/apps` → supported app types

`ProviderView` never contains secret values. Secret-bearing fields are replaced with
`{ "configured": true|false, "hint": "sk-…a1b2" | null }` (last 4 chars only when the
secret is ≥ 16 chars). The redaction reuses CC Switch's sensitive-key classifier.

### 7.3 Usage
- `GET /v1/usage/summary?from&to&app` · `GET /v1/usage/trends?from&to&app` ·
  `GET /v1/usage/models?…` · `GET /v1/usage/providers?…` ·
  `GET /v1/usage/quota/{tool}` (subscription quota; `available:false` + `reason` when the
  upstream service does not expose it — never fabricated). `tool` is a CC Switch app id: the phone
  asks for `claude`, `codex` and `agy` (Google Antigravity). Antigravity is read the way the
  desktop's own quota footer reads it: from the signed-in account (`AgyAccountManager::live_quota`),
  else through the `agy` CLI. Its tiers are `agy_gemini_five_hour`, `agy_gemini_weekly`,
  `agy_gemini_argon_weekly` (Gemini models), `agy_third_party_five_hour` and
  `agy_third_party_weekly` (Claude / GPT models in Antigravity).
- Usage ranges are epoch seconds; buckets and "today" use the **desktop's time zone**
  (`GET /v1/status` → `timeZone` (IANA, may be null) and `utcOffsetMinutes`). Trend buckets carry
  `date` (RFC 3339, desktop offset), `startMs` and `realTotalTokens`; the response has `timeZone`.
- `GET /v1/usage/logs?from&to&app&provider&model&status&page&pageSize` → `{ data: [RequestLog],
  total, page, pageSize }` (page 0-based, pageSize ≤ 100, default 20). `RequestLog`: `requestId,
  providerName, appType, model, requestModel, inputTokens, outputTokens, cacheReadTokens,
  cacheCreationTokens, inputCostUsd, outputCostUsd, cacheReadCostUsd, cacheCreationCostUsd,
  totalCostUsd, isStreaming, latencyMs, firstTokenMs, durationMs, statusCode, errorMessage,
  createdAt, dataSource` (metadata only — no request or response bodies).
- `GET /v1/usage/logs/{requestId}` → one `RequestLog` (404 if unknown).
- `GET /v1/usage/first-date?app&provider&model` → `{ firstDateMs, firstDate }` (desktop-local
  midnight; both null without data).
- `GET /v1/usage/last-sync` → `{ lastSyncMs }` (last completed session-log scan, or null).
- `GET /v1/usage/projects?from&to&app&project` → usage per project (fixture
  `protocol/fixtures/usage_projects.json`): `{ projects: [ProjectUsage], otherProjects: Totals|null,
  unattributed: Totals|null }`. The request logs CC Switch reads from session logs carry the agent's
  session id; the desktop sums them per session (detail logs only — the daily rollups of older logs
  have no session id) with the same proxy / session-log de-duplication as the other usage queries,
  joins them with the session index (session id → working directory) and groups them by the
  project name the session list uses (`project_from_cwd`, worktrees belong to their project).
  `Totals = { requests, costUsd, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens,
  lastMs }`; `ProjectUsage = Totals + { project, cwd (the directory most of its cost came from),
  sessionCount, sessions: [Totals + { id (session list id, e.g. "claude:<uuid>"), app, title, cwd }] }`,
  most expensive first: 50 projects (the rest summed in `otherProjects`) with their 5 most expensive
  sessions; with `project` (a project name or an exact directory) that project alone with up to 100
  sessions. Logs without a session id, or whose session is not in the index, are `unattributed`.
  Titles go through the redaction helper. 404 on older desktops (the phone hides the section).
- `GET /v1/settings` includes `usageDisplay` (currency, exchangeRates, rateSource,
  autoExchangeRates, …) and `visibleApps`.

### 7.4 MCP / Skills / Prompts / Settings / Proxy / Health
- `GET /v1/mcp/servers` (env/headers redacted) · `POST /v1/mcp/servers/{id}/toggle` `{app, enabled, commandId}`
- `GET /v1/skills` · `POST /v1/skills/{id}/toggle`
- `GET /v1/prompts?app=` · `POST /v1/prompts/{app}/{id}/enable`
- `GET /v1/settings` (whitelisted subset) · `GET /v1/proxy/status` · `GET /v1/health`

### 7.5 Sessions & agents
- `GET /v1/sessions?agent=&project=&q=&liveOnly=&cursor=&limit=` → `{ items: [SessionSummary], nextCursor }` (live first, then most recent)
- `GET /v1/sessions/{sessionId}` → `SessionDetail` (includes `liveness`, `control`, `capabilities`)
- `GET /v1/sessions/{sessionId}/events?before=<eventSeq>&after=<eventSeq>&limit=` → paged `AgentEvent`s
- `GET /v1/sessions/{sessionId}/events/{eventId}/payload?offset&limit` → large tool output chunks
- `POST /v1/sessions` `{commandId, agent, cwd?, prompt?, model?, effort?, permissionMode?, resumeFrom?, mode?}` → starts a session in a process SwitchTether runs (labelled `origin: "managed"`, `managed: true`).
  It is an ordinary conversation of the agent, created the official way, and its **id is the agent's own conversation id**:
  `claude:<uuid>` (Claude Code gets `--session-id <uuid>`; its transcript is Claude Code's own
  `~/.claude/projects/<dir>/<uuid>.jsonl`, listed by `claude --resume` and the IDE's history), `codex:<thread id>`,
  `dsh:<session id>`, `agy:<session id>` (Google Antigravity through its ACP server `agy acp`, feature `managed_agy`;
  see [AGY_REMOTE.md](AGY_REMOTE.md); the conversation is agy's own and stays in its history). The same id names the
  conversation as history after the process ends or the desktop restarts.
  `effort`: Claude Code `low|medium|high|xhigh|max` (`--effort`), Codex `none|minimal|low|medium|high|xhigh`
  (`turn/start` `effort`), Antigravity `low|medium|high|xhigh` (its `thought_level` session option; a level the chosen
  model does not offer is `422`); `model` and `effort` are validated (plain names only).
  With `resumeFrom` (an existing `claude:`/`codex:`/`dsh:`/`agy:` session) it **continues that conversation** ("接续会话"):
  `mode: "continue"` (default) keeps the same session/thread id; `mode: "fork"` (Claude only) starts a fork. `cwd` defaults
  to the original session's. The original history is preloaded into the new session's events. Refused with `409 conflict`
  while the original is `busy`/`waiting` on the computer, or (Codex) while the thread is open in another Codex client.
  Session capabilities advertise this as `continueSession: "PARTIAL"`.
  `permissionMode` (all agents, one vocabulary, `crate::agents::permission`): Claude Code `default | acceptEdits | plan |
  auto | dontAsk | bypassPermissions` (`--permission-mode`; `manual` is read as `default`); Codex `default | plan | auto |
  readOnly | untrusted | bypassPermissions` — `default` = workspace-write + `on-request`, `plan` = collaboration mode
  `plan` on each `turn/start`, `auto` = `approvalsReviewer: "auto_review"`, `readOnly` = read-only sandbox, `untrusted` =
  approval policy `untrusted`, `bypassPermissions` = `danger-full-access` + `never` ("full access"). Codex also accepts
  `on-request` / `acceptEdits` (= `default`), `read-only`, `never` / `danger-full-access`. Antigravity `default |
  acceptEdits | auto | plan` (agy's ACP session modes `default`, `accept-edits`, `auto`, `plan`, `session/set_mode`; agy's
  own spelling `accept-edits` is accepted; no full-access mode). An unknown mode is `422`.
  **`bypassPermissions` (any spelling) needs the `terminal` step-up grant** (`403 step_up_required`, `details.scope:
  "terminal"`) here, in `POST …/options` and in the `permission_mode` session tool: it is as powerful as a remote
  terminal. Managed Claude Code processes start with `--allow-dangerously-skip-permissions` (not as root, which Claude
  Code refuses) so the mode can be chosen mid-session; nothing is skipped until it is.
- `POST /v1/sessions/{id}/messages` `{commandId, text, images?: [{mediaType, dataBase64, name?}], attachments?: [attachmentId]}` (the `commandId` comes back as `clientId` on the resulting `user_message` event; fixture `send_message_attachments.json`).
  Feature `resume_on_send`: a Claude Code / Codex / dsh / Antigravity conversation that is **not running anywhere** (history,
  or a run that ended, e.g. with a desktop restart) takes messages too: the desktop first resumes it the official way (`claude
  --resume <id>`, `thread/resume`, `session/resume`) under the same id, then delivers the message. Antigravity keeps no model,
  level or mode with a conversation: a run that ended in this desktop process passes its own on, otherwise agy's default
  applies. Such sessions advertise
  `sendMessage: "PARTIAL"` with `approve`/`interrupt` `BLOCKED` until they run. A session live in a window on the computer is
  never resumed implicitly (observe-only; `continueSession` explains the explicit way).
- `POST /v1/sessions/{id}/options` `{commandId, model?, effort?, permissionMode?}` (feature `session_options`, fixture
  `session_options.json`) — changes model, effort and / or permission mode of a **running** session the phone controls
  (started by SwitchTether, or attached through a takeover hook: VS Code panel, Codex Desktop); absent =
  unchanged, `""` or `"default"` = the agent's default. Claude Code: `set_model`, `apply_flag_settings {effortLevel}` and
  `set_permission_mode` control requests (as the Agent SDK and the VS Code extension; in an attached panel they go through
  switchtether-shim, and changes made in the IDE are followed); Codex: sent with the next `turn/start` (`model`, `effort`,
  `approvalPolicy`, `sandboxPolicy`, `approvalsReviewer`, `collaborationMode`), which Codex keeps for the following turns;
  `thread/settings/updated` (also from Codex Desktop) updates what the detail shows; Antigravity: ACP
  `session/set_config_option` (`model`, `thought_level`) and `session/set_mode`, applied at once (`""`/`default` keeps the
  current model: agy has no "default" value to return to). Answers the updated `SessionSummary`.
  `409` when the session is not running anywhere and cannot be resumed, `422 capability_unavailable` for sessions owned by
  a window SwitchTether does not control (observed ones, mirrored terminals) and for dsh.
  Advertised per session as `capabilities.changeModel`: `PARTIAL` where this request is accepted, `BLOCKED` (with
  `capabilities.reason`) otherwise for a running attached session, absent otherwise. Capability and endpoint always agree.
  The session detail lists `permissionModes: [{id, label, description, dangerous}]` and the current `permissionMode`
  whenever the mode can be changed (running sessions the phone controls, and conversations a message would resume).
- `GET /v1/agents/{agent}/models?session=` → `{agent, models: [{id, label, description?, efforts?}], efforts, source,
  permissionModes}` (`permissionModes`: what a new session of this agent can start in, same shape as in the detail)
  (fixture `model_options.json`): with a running `session`, the agent's own list (Claude Code's initialize response, Codex
  `model/list`, `source: "agent"`); otherwise the aliases every installation accepts (`source: "builtin"`).
- Attachments (feature `attachments`; any file type, ≤ 1 GiB each, ≤ 10 per message; an unfinished upload is dropped after 6 h; same device and session only): `POST /v1/sessions/{id}/attachments` `{name, size, mediaType?}` → `AttachmentView` `{attachmentId, name, size, received, complete, mediaType}` (fixture `attachment_view.json`), then `PUT /v1/sessions/{id}/attachments/{attachmentId}?offset=` raw chunks ≤ 8 MiB in order (wrong offset → 409 with `details.received`), `DELETE …/{attachmentId}` discards it. Refused for sessions the phone cannot send to. Files are stored under the desktop's data directory (`<data dir>/attachments/<date>/<id>/<name>`, kept 7 days, 4 GiB cap) — never in the project. Every agent receives them the same way: the message text gets a block starting `[SwitchTether attachments: …]` with one absolute path per line; images ≤ 3 MiB are also inlined for agents that accept images (Claude Code, Codex). SwitchTether-started Claude Code sessions get `--add-dir <attachments dir>`; other agents may ask for permission to read the path.
- `POST /v1/sessions/{id}/interrupt` `{commandId}`
- `POST /v1/sessions/{id}/approvals/{requestId}` `{commandId, decision: "allow"|"allow_session"|"deny"|"abort", message?, updatedInput?}`
  - `409 conflict` with `details.resolvedBy` if the request was already answered (by the desktop or another device).
- `POST /v1/sessions/{id}/answers/{requestId}` `{commandId, answers?, action?: "submit"|"cancel"|"decline"}` — Agent questions & elicitations (single-select, multi-select, free text, cancel/decline).
  `answers` is keyed by question `id` (fallback: question text). Codex multi-select values are arrays of strings;
  single-select / free text are strings, with commas preserved. Claude keeps its string answer format.
  Option `value`, when present, is submitted instead of its display label. See `agent_question.json`.
  **MCP elicitations** (an MCP server asks the user something; Codex `mcpServer/elicitation/request`, Claude Code's
  `elicitation` control request in a session SwitchTether started) arrive as the same `question` event with
  `cancellable: true`, `elicitationMode` (`form` | `url` | null), `serverName`, `message` (Claude Code) and, for a URL
  elicitation, `url`. A form has one question per property of the requested schema (`id` = property name; `enum` /
  `oneOf` → options, boolean → `true` / `false`, array of enum → multi-select, anything else free text; `required`
  from the schema); for Claude Code the server's message is the text of a single question, or leads the first of
  several. Without a schema (or with no properties), and for a URL elicitation, there is one question with
  `id: "__confirm__"` (URL: one option `Accept`, the phone shows / opens `url`). What the server wrote (message, field
  titles and descriptions) is redacted like other agent text; `url` is sent only when it is a single-line `http(s)`
  URL (anything else, e.g. `javascript:` or `intent:`, is dropped and the question is a plain confirmation), and is not
  redacted since the user has to open it. Option labels / values are not changed (they are what the answer returns).
  Claude Code may withdraw an elicitation with `control_cancel_request`: the question is resolved with `decision:
  "cancelled"`, `by: "agent"`, and a late answer gets `409`. The answer goes back to the agent as an MCP `ElicitResult`:
  `action: "submit"` (default) → `{action: "accept", content}` with `content` = `answers` typed after the schema
  (`"true"` → `true`, numbers parsed; no `content` for a URL elicitation), `"decline"` → `{action: "decline"}`,
  `"cancel"` → `{action: "cancel"}`. Hook callbacks are never forwarded (declined with an error). In a session
  attached through a takeover hook the IDE answers Claude Code's elicitations; the phone does not see them.
- `POST /v1/sessions/{id}/stop` `{commandId}` — stops a managed session's process (history stays readable).

Control endpoints return `422 capability_unavailable` with `details.status` =
`BLOCKED|UNTESTED` and `details.reason` when the session cannot be controlled
(e.g. an externally launched interactive Claude Code session). See
`docs/AGENT_COMPATIBILITY.md`.

### 7.6 Terminals (step-up `terminal`)
- `GET /v1/terminals` → `{ terminals, shells }` · `POST /v1/terminals` `{commandId, shell?, cwd?, cols, rows, persistLog?, title?}` — `shell` is an id from `shells` (`pwsh`, `powershell`, `cmd`, `wsl`, `zsh`, `bash`, `fish`, `sh`, …); never an arbitrary path
- `GET /v1/terminals/{id}` · `DELETE /v1/terminals/{id}` (kill + remove) · `POST /v1/terminals/{id}/resize` `{cols, rows}`
- `GET /v1/terminals/{id}/stream?sinceVersion=` → WebSocket:
  - server → client: `{ "type":"snapshot", "version", "cols","rows","cursor":{x,y,visible}, "lines":[Line], "scrollbackLines": n, "title", "altScreen" }`,
    `{ "type":"patch", "version", "lines": [{ "y", "line": Line }], "cursor":… }`, `{ "type":"exit", "code" }`
  - client → server: `{ "type":"input", "data": "<utf8>" }`, `{ "type":"input_b64", "data":"…" }`, `{ "type":"resize","cols","rows" }`, `{ "type":"ack","version" }`
  - `Line = { "runs": [{ "t": "text", "fg": "#rrggbb"|idx|null, "bg": …, "b":bool, "i":bool, "u":bool, "inv":bool }], "wrapped": bool }`
- `GET /v1/terminals/{id}/scrollback?offset&limit` → historical lines (same `Line` format).

The desktop runs the terminal emulator (vt100 state machine) and streams screen state
(mosh-style): reconnects only need the latest snapshot, so mobile links can drop freely.

### 7.7 Files & Git
- `GET /v1/fs/roots` → suggested roots (session cwd, projects, home)
- `GET /v1/fs/list?path=&showHidden=` → entries with `kind`, `size`, `modifiedMs`, `hidden`, `sensitive`
- `GET /v1/fs/search?root=&q=&content=&limit=` → `{ results: [{path, kind, name, line?, preview?}] }`
- `GET /v1/fs/read?path=&maxBytes=&allowSensitive=&encoding=` → `{ path, encoding, bom, text|null (binary), sha256 (of the whole file), size, truncated, modifiedMs, lineEnding, sensitive, language }`
- `PUT /v1/fs/write` *(files_write)* `{commandId, path, text, baseSha256|null, encoding?, bom?, createOnly?}` → `200 {path, sha256, size, created}` or `409 conflict` with `details {currentSha256, currentText?, suggestedCopyPath}`
- `POST /v1/fs/rename` `{commandId, from, to, overwrite?}` · `POST /v1/fs/delete` `{commandId, path, baseSha256?}` (moves to the OS trash) · `POST /v1/fs/mkdir` `{commandId, path}` *(files_write)*
- `GET /v1/fs/download?path=` (streamed, single `Range` supported; sensitive files refused) · `POST /v1/fs/upload` `{commandId, path, size, sha256?, overwrite?}` → `{uploadId}`, then `PUT /v1/fs/upload/{id}?offset=` raw chunks ≤ 8 MiB, `DELETE /v1/fs/upload/{id}` cancels *(files_write)*
- `GET /v1/git/status?repo=` → `RepoStatus { root, branch, upstream, ahead, behind, entries: [StatusEntry], merging? }`;
  `StatusEntry { path, origPath, index, worktree, untracked, conflicted, added?, removed?, stagedAdded?, stagedRemoved? }`
  — the line counts come from `diff --numstat` (worktree) and `diff --cached --numstat` (index) and are left out for
  binary / untracked files; `merging: true` while a merge stopped on conflicts (`MERGE_HEAD`).
- `GET /v1/git/diff?repo=&path=&staged=` · with `rev=` (and `origPath=` for a rename): what that commit changed in
  `path`, against its first parent (a root commit against the empty tree) → `{ root, diff, truncated }`
- `GET /v1/git/log?repo=&path=&limit=&skip=` (pagination: `skip` commits first) · `GET /v1/git/show?repo=&rev=&path=` (file at a commit)
- `GET /v1/git/commit-detail?repo=&rev=` *(feature `git_manage`)* → `{ hash, author, email, timeMs, subject, body, parents,
  files: [{ path, origPath?, status, added?, removed? }] }` (fixture `git_commit_detail.json`): `status` is git's letter
  (`A` `M` `D` `R` `C` `T`) against the first parent; at most 2000 files.
- `GET /v1/git/stashes?repo=` *(feature `git_manage`)* → `{ stashes: [{ index, message, timeMs }] }` (fixture `git_stashes.json`)
- `GET /v1/git/branches?repo=` → `{ root, current, local: [Branch], remote: [Branch] }`, `Branch = { name, current,
  upstream, timeMs, ahead?, behind?, gone? }` (ahead / behind the upstream, `gone` when it was deleted on the remote;
  left out when 0 / false); `local` has the current branch first, then by last commit; `remote` lists
  remote-tracking branches (`origin/x`, no `*/HEAD`) without a local branch of the same short name (fixture
  `git_branches.json`).

**Git writes** *(git_write, feature `git_write`)*: `POST /v1/git/{op}` with one body for every op (fixture
`git_write_request.json`; fields an op does not use are ignored):
`{ commandId, repo, paths?: [string], all?: bool, message?, setUpstream?: bool, remote?, branch?, create?: bool,
amend?: bool, force?: bool, index?: number }`. The ops marked *git_manage* exist on desktops that list feature
`git_manage`.

| op | git | notes |
|---|---|---|
| `stage` | `add -A -- <paths>` (`all: true` → `add -A`) | deletions included |
| `unstage` | `restore --staged -- <paths>` (no commit yet: `rm --cached -r`) | |
| `discard` | `restore --worktree -- <paths>`; untracked paths are moved to the OS trash | every path must appear in `git status`; a path with only staged changes or a conflict is refused (unstage / resolve first); never deletes |
| `commit` | `commit -m <message>` (`amend` → `commit --amend -m`) | message trimmed, non-empty, ≤ 16 KiB; amend needs a commit |
| `push` | `push` (`setUpstream` → `push -u <remote or origin> <current branch>`) | |
| `pull` | `pull --ff-only --no-rebase` | a diverged branch fails with git's message, nothing is merged |
| `fetch` | `fetch --prune` | |
| `checkout` | `switch <branch>` / `switch -c <branch>` (`create`) | git refuses a switch that would overwrite local changes; its message is returned |
| `branch-delete` *(git_manage)* | `branch -d <branch>` (`force` → `-D`) | never the current branch; `-d` refuses an unmerged branch |
| `merge` *(git_manage)* | `merge --no-edit <branch>` | fast-forward when possible; refused while a merge is in progress or for the current branch; on conflicts git's message is returned and the status shows `merging` plus the conflicted files |
| `merge-abort` *(git_manage)* | `merge --abort` | |
| `stash-push` *(git_manage)* | `stash push --include-untracked [-m <message>]` | |
| `stash-pop` *(git_manage)* | `stash pop stash@{<index>}` (default 0) | git keeps the stash when the pop conflicts |

→ `200 { status: RepoStatus, output, head? }` (fixture `git_write_result.json`): the repository after the write, git's
stdout + stderr (last 4000 characters, through `redact_text`), the new HEAD after `commit` / `pull` / `checkout` / `merge` / `merge-abort`.
Paths are repository-relative (no absolute paths, no `..` segment, no leading `-`, no control characters) and passed
literally (`GIT_LITERAL_PATHSPECS=1`) after `--`; branch names follow `git check-ref-format --branch` rules. git never
prompts: `GIT_TERMINAL_PROMPT=0`, `GCM_INTERACTIVE=never`, no askpass, `GIT_EDITOR=:`; missing credentials or a signing
key that needs a passphrase fail with git's message (`400 bad_request`). Timeouts: 30 s (index, branch and stash ops), 60 s (`commit`, `merge`,
which runs the user's hooks as a terminal commit would), 120 s (`push` / `pull` / `fetch`). Every write is audited
(`git.<op>`, repository, path count or branch, outcome) and idempotent by `commandId`.

### 7.11 Port forwarding (step-up `port_forward`, feature `port_forward`)

The phone reaches a TCP port listening on the desktop's **loopback**, like `ssh -L`. The desktop
only ever connects to `127.0.0.1:<port>` and `[::1]:<port>` (both tried at once, first success
wins); the target host is not a parameter.

- `GET /v1/forwards/ports` → `{ "ports": [{ "port": 5173, "process": "node.exe", "pid": 4242 }] }`:
  listeners on loopback or the any-address, below 49152, without system services and without
  the Remote port itself. `process`/`pid` only where the OS tells (Windows); Linux lists ports
  from `/proc/net/tcp*`; other systems probe common development ports.
- `POST /v1/forwards` *(port_forward)* `{ "port": 5173, "ttlMinutes": 120 }` → `ForwardView` plus
  `"listening": bool` (whether something accepted a connection just now). A device has at most
  8 leases; posting a port the device already forwards renews that lease (same `id`). Default
  lifetime 2 h, at most 12 h; leases live in desktop memory only. The Remote port itself is
  refused (`403 forbidden`); a 9th port is `409 conflict`.
- `GET /v1/forwards` → `{ "forwards": [ForwardView] }` (this device's leases).
- `DELETE /v1/forwards/{id}` → `{ "removed": true }`; closes the tunnel with 4410.
- `GET /v1/forwards/{id}/tunnel` → WebSocket. Unknown, expired or another device's lease: `404`
  before the upgrade. No step-up grant is needed here: the lease is the authorisation, so a
  browser keeps working for the lease's lifetime. A newer tunnel for the same lease replaces the
  old one (4409 on the old one).

`ForwardView` (fixture `protocol/fixtures/forward_view.json`):
`{ id, port, createdAtMs, expiresAtMs, connected, activeStreams, totalStreams, bytesDown, bytesUp }`.

**Tunnel frames** (fixture `protocol/fixtures/forward_frames.json`). Every binary WebSocket message
is `[type: u8][stream: u32 big-endian][payload]`; text messages are ignored. The phone opens
streams (non-zero ids, odd numbers by convention); one stream = one TCP connection.

| Type | Name | Direction | Payload |
|---|---|---|---|
| 1 | `OPEN` | phone → desktop | empty. The phone may send `DATA` right after it without waiting. |
| 2 | `OPENED` | desktop → phone | empty: connected to the port |
| 3 | `DATA` | both | ≤ 32 KiB of bytes |
| 4 | `FIN` | both | empty: no more data in this direction (half-close) |
| 5 | `RESET` | both | ASCII reason: `refused` (nothing listens), `limit` (> 128 streams), `window`, `error`, `closed` |
| 6 | `WINDOW` | both | u32 big-endian: bytes consumed (credit returned) |

Flow control: each side may have at most 256 KiB of unacknowledged `DATA` per stream; the
receiver sends `WINDOW` after writing bytes to its socket. A phone that exceeds the window gets
`RESET window`. Protocol violations (unknown type, short frame, re-used id, `OPENED` from the
phone) close the tunnel with 1002.

Close codes: `4410` the lease was stopped or expired (start again with step-up), `4409` replaced
by a newer tunnel, `4403` device revoked / connection closed by the desktop. Any other close is
transient: reconnect to the same lease. Through the relay (docs/RELAY.md) a forward uses one
relay tunnel regardless of how many browser connections it carries.

### 7.12 Push wake (feature `push_wake`)

A phone that is not connected learns that something happened through its own UnifiedPush
distributor (e.g. ntfy, self-hosted or not), which delivers a Web Push message (RFC 8030). The
message says nothing but "connect now"; the phone then reconnects over its pinned mTLS channel
and gets the events as usual (§6). Opt-in on the phone (notification settings).

- `GET /v1/push` → `{ "registered": bool, "vapidPublicKey": "<87 chars>" | null }` — whether this
  device has a subscription, and the desktop's VAPID public key (RFC 8292, uncompressed P-256,
  base64url) that the phone passes to its distributor (`vapid` extra of UnifiedPush `REGISTER`).
- `PUT /v1/push` `{ "endpoint", "p256dh", "auth" }` → `{ "registered": true }` — the push endpoint
  (http(s), ≤ 1000 bytes, no credentials in the URL) and the phone's RFC 8291 receiver keys
  (uncompressed P-256 public key and 16-byte auth secret, base64url). Replaces the device's
  previous subscription. Invalid values: `400 validation_failed`.
- `DELETE /v1/push` → `{ "removed": bool }`.
- `POST /v1/push/test` → `{ "status": <push server HTTP status>, "delivered": bool }` — one wake now,
  regardless of the rules below (settings "send a test").

Fixture `protocol/fixtures/push_wake.json` (wire shapes plus a message produced by the desktop's
encryptor, decryptable with the RFC 8291 Appendix A receiver keys it names).

**When the desktop pushes.** On `session.upserted` transitions: a session starts waiting
(`status` → `waiting`), a turn ends (`busy` → `idle` / `completed`), or a session fails (→ `error`).
Each subscribed device that has **no open event WebSocket** is woken; a device that is connected gets
the event itself. Per device at most one push per 10 s; changes in between yield one trailing push.
Failures (network, 429, 5xx, other 4xx) back off exponentially from 10 s up to 15 min; `404` / `410`
from the push server deletes the subscription (audit `push.expired`). While any device has a
subscription the desktop keeps watching live sessions as if a phone were connected.

**The message.** `POST <endpoint>` with `Content-Encoding: aes128gcm`, `TTL: 600`, `Urgency: high`,
`Authorization: vapid t=<ES256 JWT>, k=<vapidPublicKey>` (`aud` = the endpoint's origin, `exp` 12 h,
`sub` the project URL) and one RFC 8291 record whose plaintext is exactly `{"v":1}`. No session id,
title, text or count is ever sent. The phone ignores any message that does not decrypt with its keys
and never uses the content.

Subscriptions live in `remote.db` (`push_subscriptions`), are removed with the device on
revocation, and changes are audited (`push.register` with the endpoint's host only,
`push.unregister`). The VAPID key pair is created once and kept in `remote.db` (`meta`).

### 7.13 Session search (feature `session_search`)

- `GET /v1/sessions/search?q=&agent=&limit=` → `{ "results": [SearchResult], "partial": bool }`

Full-text search over what the user and the agents **said** (the `user_message` /
`assistant_message` text the transcript parsers produce; tool calls and their output are not
searched) in Claude Code, Codex and DeepSeek Harness transcripts known to the desktop (the same set
as `GET /v1/sessions`), and in Antigravity (`agy`) conversations, which are read with CC Switch's
own session parser (JSONL transcripts and trajectory databases; `user` / `assistant` messages).
Antigravity sessions are searched after the transcript files, within the same budget, and the
results are merged newest first. `q`: 1–200 characters, matched case-insensitively character by character (CJK
and other scripts work; no word splitting). `agent` filters like the session list. `limit` 1–50,
default 20. Sessions are scanned newest first (at most the last 64 MiB of each transcript) within a
3 s budget; `partial: true` means the budget ran out before every session was searched. Results come
in that order (newest first) and the scan stops at `limit` hits.

`SearchResult` (fixture `protocol/fixtures/session_search.json`):
`{ sessionId, agent, title|null, cwd|null, timeMs|null, snippet, matchCount }` — `snippet` is up to
40 characters before and 100 after the first match in the newest matching message, whitespace
collapsed, `…` where cut, passed through the secret redactor; `matchCount` counts matches in all
searched messages (capped at 999). Desktops without the feature answer `404` (the path is read as a
session id).

### 7.14 DeepSeek Harness Web (step-up `port_forward`, feature `dsh_web`)

The phone opens `dsh web` — DeepSeek Harness's own browser UI on the desktop — over an ordinary
port forward (§7.11). The desktop finds a running `dsh web` or starts one; the phone serves the
lease on its loopback and shows it in a WebView (docs/ANDROID_UI.md §31).

- `GET /v1/dsh-web` → `DshWebStatus` (no step-up; never contains the launch token).
- `POST /v1/dsh-web/open` *(port_forward)* `{ "start": true, "ttlMinutes": 120 }` (both optional) →
  `{ "status": DshWebStatus, "forward"?: ForwardView + "listening", "launchPath"?: "/?token=…" }`.
  With `start` (default) a `dsh web` is started when none runs. While it starts the answer has only
  `status` (`starting`): poll `GET /v1/dsh-web`, then post again (with `"start": false` or not;
  starting is idempotent). Once it runs, the answer carries a lease to its port, exactly as
  `POST /v1/forwards` would (renewed when this device already forwards that port; counts towards the
  8 leases). `launchPath` only for an instance SwitchTether started (`owned`).
- `POST /v1/dsh-web/stop` *(port_forward)* `{}` → `{ "stopped": true, "status": DshWebStatus }`;
  `409 conflict` when SwitchTether did not start the running instance (one the user started is never
  stopped).

`DshWebStatus` (fixture `protocol/fixtures/dsh_web_status.json`):
`{ state, port?, owned, startedAtMs?, error? }` — `state` is `not_installed` (no `dsh` executable),
`stopped`, `starting` or `running`; `port` is the desktop loopback port of the running instance;
`error` says why the last start failed. Fixture of the open answer: `dsh_web_open.json`.

**Discovery.** dsh answers an unauthenticated `GET /` with `401` and the body
`dsh web authentication required; …`. The desktop sends that request to port 3080 (dsh's default)
and to the listeners the OS reports (on Windows only `node` / `bun` / `dsh` processes; elsewhere
ports below 49152), 800 ms each, in parallel; the first that answers so is the running instance.

**Start.** `dsh web --no-open --host 127.0.0.1 --port 0` in the user's home folder with the
user's environment — what the user would type; dsh uses (and on first use initialises) the existing
`web` profile, SwitchTether writes nothing to it. The child prints
`dsh web: http://127.0.0.1:<port>/?token=<token>`; the port and token come from that line (up to
120 s). It stops on `POST /v1/dsh-web/stop`, when SwitchTether exits, and — Windows job object —
when SwitchTether's process dies.

**Sign-in.** dsh requires its per-process launch token once per browser: `GET /?token=…` sets a
cookie bound to the request's `Host` (signed with a secret in `DSH_HOME`, 30 days) and redirects to
`/`. dsh's `/api` fence accepts any loopback `Host` (`localhost:<phone port>` included) and requires
`Origin` to match `Host`, so the phone keeps one fixed local port (3080, then 3081…3089) and the page
keeps working across restarts of `dsh web`. The token is handed only to a device that passed step-up
`port_forward`, only in the `open` answer; it is never logged, audited or sent in events. For a
`dsh web` the user started the desktop does not know the token: the phone asks the user to paste the
line dsh printed (or works directly when the WebView still holds a valid cookie).

## 8. Capability negotiation

`GET /v1/capabilities`:

```json
{ "protocolVersion": 1,
  "agents": [ { "agent": "claude_code", "origin": "cli", "capabilities": {
      "discoverLive": "VERIFIED", "readHistory": "VERIFIED", "liveEvents": "PARTIAL",
      "sendMessage": "BLOCKED", "approve": "BLOCKED", "interrupt": "BLOCKED", … },
      "notes": "…" } ],
  "features": ["terminal", "files", "git", "providers", "usage", "mcp", "skills", "prompts", "managed_sessions"] }
```

Further features: `push_wake` (§7.12), `session_search` (§7.13), `dsh_web` (§7.14), `managed_agy` (the desktop starts
and continues Google Antigravity sessions, `POST /v1/sessions {agent: "agy"}`; the phone offers Antigravity in its launcher
only when this is listed).

Session-level `capabilities` override the agent defaults (e.g. a managed session has
`sendMessage: "VERIFIED"`).

## 9. Sessions and the Agent Event Schema

### 10.1 SessionSummary

```json
{ "id": "claude:2b1c…", "agent": "claude_code", "origin": "vscode",
  "nativeId": "2b1c…", "title": "Fix login bug", "cwd": "D:\proj\app", "project": "app",
  "liveness": "live", "status": "busy", "waitingFor": null,
  "control": "observe", "createdAtMs": 1731500000000, "lastActiveAtMs": 1731503600000,
  "model": "claude-…", "gitBranch": "main", "pid": 1234, "managed": false }
```

| Field | Values |
|---|---|
| `agent` | `claude_code`, `codex`, or any CC Switch session provider id (`gemini`, `opencode`, …) — history only |
| `title` | The task title: Codex's thread name (including initial resume metadata and subsequent renames), else its indexed title / first prompt. Claude Code's `/rename` title (`custom-title`), else its generated `ai-title`, else the first prompt; `null` when none exists yet. Never the project name and never the registry slug in `~/.claude/sessions/<pid>.json`. Records written by sub-agents are ignored. Sub-agent work is nested under its tool call and is not a session |
| `project` | Folder of `cwd`; an agent worktree (`<project>/.claude/worktrees/<name>`) maps to its containing project |
| `origin` | `cli`, `vscode`, `desktop_app`, `managed` (started by SwitchTether), `unknown` |
| `managed` | `true` only for sessions SwitchTether started. A session of the user's own process that SwitchTether is attached to through a takeover hook has its real origin, `managed: false` and `control: "full"` |
| `effort` | optional; effort / reasoning level chosen for a session SwitchTether runs (`low` … `max`), absent when the agent's default applies |
| `terminalId` | optional; set for sessions running in the user's terminal window (`st-claude` / `st-codex`). The id of an **external** terminal (`/v1/terminals`, `external: true`) mirroring that window; prompts are answered there |
| `liveness` | `live` (process running), `ended`, `unknown` |
| `status` | `busy`, `idle`, `waiting`, `completed`, `error`, `unknown` |
| `waitingFor` | `permission prompt`, `input needed`, … (as reported by the agent), or `null` |
| `control` | `full` (SwitchTether can send/approve/interrupt), `observe` (read-only live view), `history` (ended / transcript only) |
| `activeTasks` | optional integer; sub-agents and background tasks (background shells, background agents) still running in a **live** session. A session whose turn ended (`idle` / `completed`) with `activeTasks > 0` is still working: clients list and label it as running ("2 background tasks running"), not idle. Counted from the session log (`subagent` rows and `background` tool calls still `running`; for a Claude Code process SwitchTether runs, at least the stream-json `backgroundTasks` count). Present for every live managed / attached session (also `0`) and for a live observed Claude Code session whose log the desktop already follows (opened, or its Agent map read); absent otherwise and from older desktops — clients then fall back to their own Agent map poll, else treat the session as idle. A change of the count publishes `session.upserted` (managed / attached at once, observed within the 4 s live refresh). Fixture `protocol/fixtures/session_summary.json` |

`GET /v1/sessions/{id}` adds `"capabilities": { "sendMessage": "VERIFIED|PARTIAL|BLOCKED|UNTESTED", "approve": …, "interrupt": …, "answerQuestion": …, "liveEvents": …, "readHistory": … }`
and `"pendingRequests": [AgentEvent]` (open approvals/questions).

Session ids are opaque; the prefix is informative only. Sessions SwitchTether starts use the agent's own conversation id
(`claude:<uuid>`, `codex:<thread>`, `dsh:<id>`, `agy:<id>`), so one conversation keeps one id across process restarts; desktops before
this change used `managed:<random>` ids that disappeared with the process.

### 10.2 AgentEvent

```json
{ "id": "evt-…", "seq": 57, "sessionId": "claude:2b1c…", "turnId": "…|null",
  "ts": "2026-11-14T03:00:00.000Z", "kind": "tool_call", "parentId": null,
  "streaming": false, "data": { … } }
```

- `seq` is a per-session, strictly increasing integer (paging cursor). Events may be
  **updated in place** (same `id`, same `seq`, new `data`) — e.g. a tool call moving from
  `running` to `completed`, or streaming text growing. Live updates arrive on the event
  stream as topic `session.event` with `data = AgentEvent`; clients replace by `id`.
- **Claude Code sub-agent branches** (read-only). Claude Code keeps each sub-agent's own transcript next to the session's
  (`<session>/subagents/agent-<agentId>.jsonl` + `.meta.json`):
  - `GET /v1/sessions/{id}/subagents` → `{ sessionId, agents: [{ agentId, description?, agentType?, toolUseId?, parentAgentId?,
    spawnDepth?, finished?, updatedAtMs?, …live }], backgroundTasks: [{ taskId, callId, title, status, summary?, exitCode?,
    startedAt, finishedAt? }], stoppable }` — the **Agent map**: every sub-agent of the session, nested ones included;
    `parentAgentId` builds the branch tree (null = started by the session). Sidecar metadata is whitelisted. Agents that have
    a `subagent` row in the session log add their live state (`…live`): `status`, `waiting` (a permission request or
    question raised inside it is open), `background`, `model`, `tokens`, `toolUses`, `durationMs`, `activity` (what it does
    now), `summary`/`result` (≤ 600 chars), `prompt` (the Agent call's own input, ≤ 2000 chars, already part of the session's
    events), `startedAt`, `finishedAt`. `backgroundTasks` are the session's background shells. `stoppable` is true only for a
    Claude Code process SwitchTether started.
  - `POST /v1/sessions/{id}/subagents/{taskId}/stop` `{commandId}` → `{ stopped, taskId }`: stops one sub-agent (`agentId`)
    or background shell (`taskId`) with Claude Code's `stop_task` control request, like the IDE's "Stop agent". Idempotent
    and audited like `interrupt`; any other session → `422 capability_unavailable` (sessions started elsewhere are
    observe-only).
  - **Claude Code session tools** (the IDE extension's session features, for a Claude Code process SwitchTether started
    or one attached through the takeover hook — sent through switchtether-shim like messages and approvals; the
    session detail then has `claudeTools: true` and `permissionMode`; Stop in the Agent map likewise): `POST /v1/sessions/{id}/claude/{action}`
    with the action's parameters and an optional `commandId` (idempotent, audited as `session.claude.<action>`). Each is
    one whitelisted control request; replies are reduced to these fields, texts redacted, MCP configuration never sent:
    `permission_mode {mode}` → `{mode}`; `context_usage` → `{totalTokens, maxTokens, percentage, categories[{id, name,
    tokens, kind}]}`; `task_output {taskId}` → `{output (newest 64 KiB), totalBytes, truncated}`; `plan` → `{exists,
    content?, path?}`; `mcp_status` → `{servers[{name, status, title?, version?, transport?, scope?, tools?, error?}]}`;
    `mcp_reconnect {server}`; `run_in_background {toolUseId}` → `{backgrounded}` (a running foreground Bash / Agent, the
    IDE's Ctrl+B); `cancel_message {messageUuid}` → `{cancelled}`; `side_question {question}` → `{answer}`; `rename
    {title}`; `rewind {userMessageId, dryRun}` → `{dryRun, canRewind, error?, filesChanged (relative), insertions,
    deletions}` (files only; **switched off**: `REWIND_FILES_ENABLED` is `false`, managed processes run without file
    checkpoints and the action answers `422 capability_unavailable` "Rewinding files is not enabled."); `export` → `{text, filename}`;
    `commands` (body `{}`) → `{ "commands": [ { "name": "compact", "description": "…", "argumentHint": "<optional
    custom summarization instructions>", "aliases"?: ["…"] } ], "source": "agent" }` — see **Slash commands** below. Other
    sessions → `422 capability_unavailable`, except that a Claude Code session started on the computer
    (observe-only, detail `claudeReadTools: true`) gets the three tools its files can answer, read-only: `task_output`
    (only the file Claude Code named `<taskId>.output` for that task, kept as `outputFile` on the task's row), `plan`
    (the last `ExitPlanMode` plan in the transcript) and `export` (the log as text). `POST …/messages` answers `messageUuid` for these sessions: the user
    message's id in the log, and the handle to cancel it while queued.
  - **Slash commands** (`POST /v1/sessions/{id}/claude/commands`, fixture `protocol/fixtures/claude_commands.json`): the
    slash commands the session's own Claude Code process reported — built-in ones, custom commands from
    `.claude/commands`, skills and plugin commands — taken from its `initialize` control response (`commands`) and, for
    names it did not list, from `slash_commands` of the stream's `system`/`init` message; a later `system`
    `commands_changed` message (same entry shape, skills / plugins loaded mid-session) replaces the whole list. No
    control request is sent.
    Each entry has three strings: `name` (without the leading `/`, ≤ 80 characters), `description` (≤ 300, `""`
    when unknown) and `argumentHint` (≤ 120, `""` when none), plus an optional `aliases` (other names of the same
    command, same rules as `name`, at most 5; the key is absent when there are none). Unique by `name`, in the
    agent's order; texts redacted, nothing else about a command (`builtin`, `plugin`, paths) is sent. `source` is `agent` when the list is not empty.
    **Fallback rule:** when the process is not running (ended, or a conversation a message would resume) or never
    reported a list, the answer is `200 { "commands": [], "source": "none" }` and the client offers its built-in list.
    A session attached through the takeover hook has a list only if SwitchTether saw the IDE's `initialize` response
    or a `system`/`init` message (names only) pass; otherwise `none`. A session that is running in a window
    SwitchTether does not control → `422 capability_unavailable` like the other tools. **Running a command needs no
    endpoint of its own:** send `/name args` as the text of an ordinary message (`POST …/messages`), as the Agent SDK
    and the VS Code extension do. In the log the command appears as one `user_message` with that text (Claude Code
    replays it under the same `uuid` as its `<command-name>` record; both are the same bubble, and a command never
    becomes the conversation's title), and what a local command prints as a `system` event with `subtype: "command"`
    (in transcripts and in the stream alike Claude Code delivers it as `<local-command-stdout>` /
    `<local-command-stderr>`; a stream-json `system` message with other `content` is shown as it is). What the user
    did not type is never a `user_message`: the local command caveat, a prompt command's expanded text (stream-json
    `isSynthetic`, transcript `isMeta`); the summary a `/compact` leaves is a `system` `compact_summary`.
  - **Models** (`GET /v1/agents/{agent}/models?session=`): the agent's own list — from the running session, else read by a
    short SwitchTether-owned probe (Claude Code `initialize` with no MCP servers, Codex app-server `model/list`; no
    message, no conversation) and cached 30 min. Antigravity (`agy`): the signed-in CLI's own list (`agy models`, nothing
    written; a running session answers from its ACP `availableModels`), its tiered ids (`gemini-4-neon-high` "Gemini 4 Neon
    (High)") grouped into one model per base id with its thinking levels as `efforts` (fixture `model_options_agy.json`);
    `efforts` `low|medium|high|xhigh`, `permissionModes` agy's four modes. When the desktop cannot run `agy` itself the
    desktop's own list is served (display names as ids, no levels). A model is accepted as the grouped id (+ effort), a
    tiered id or a display name. With an official list the built-in aliases are not added, hidden Codex
    models are left out, and a model without effort levels has `efforts: []`. `source`: `agent` | `builtin`.
  - **Options of a conversation that is not running**: `POST …/options` and the `permission_mode` tool are kept and applied
    when a message resumes it (`create_managed` with `--model` / `--effort` / `--permission-mode`, Codex thread settings);
    the detail then has `resumable: true` and `nextStart {model, effort, permissionMode}`, and `changeModel` is `PARTIAL`.
    This includes a session attached through the takeover hook whose window was closed (VS Code panel, Codex Desktop): the
    next message resumes the conversation in a process SwitchTether starts, **under the same session id** (the
    `attached:` key is taken over), so the page the phone has open keeps following it. A session running in a window
    SwitchTether does not control stays observe-only (`capability_unavailable`).
  - **Antigravity session tools** (an `agy acp` session SwitchTether runs; detail `sessionTools: ["commands",
    "permission_mode", "context_usage"]`): `commands` lists what agy reported (`available_commands_update`: its slash
    commands and skills; a command is sent as the text `/name args`), `permission_mode` is `session/set_mode`,
    `context_usage` comes from the last `usage_update` (`used` of `size`, no categories). Approvals are agy's
    `session/request_permission` (options `allow` / `deny`; first answer wins); ACP has no question request.
  - **Codex session tools** (a Codex thread SwitchTether runs or one attached from Codex Desktop; detail `sessionTools:
    ["commands", "permission_mode", "context_usage", "mcp_status", "rename"]`): the same `POST …/claude/{action}` paths
    and reply shapes as Claude Code's. `commands` lists Codex's own commands (`compact`, `review`, `goal`, `plan`,
    `rename`, `status`, `mcp`) and the folder's enabled skills (`skills/list`); `context_usage` comes from the last
    `thread/tokenUsage/updated` (`last.totalTokens` of `modelContextWindow`, no categories); `mcp_status` from
    `mcpServerStatus/list` (names, state, tool count, auth state; never configuration); `rename` is `thread/name/set`.
    A command is sent as the message `/name args`: `/compact` → `thread/compact/start`, `/review [instructions | branch
    <name> | commit <sha>]` → `review/start` (inline), `/goal <objective> | clear | pause | resume` → `thread/goal/set` /
    `thread/goal/clear`, `/plan [prompt]` → plan mode for the next turn, `/rename`, `/status` and `/mcp` answer with a
    `system` `command` row; a skill becomes a `skill` input item followed by the rest of the text. These leave a
    `user_message` with the typed text; any other `/…` goes to Codex as plain text.
  - While a foreground sub-agent runs (before its result exists) its `subagent` row is already there with `status:
    "running"`: from the stream's `task_started` (managed) or from its sidecar `agent-<id>.meta.json` `toolUseId` (observed).
  - `GET /v1/sessions/{id}/subagents/{agentId}/events?before&after&limit` → `{ sessionId, agentId, events, hasMore, lastSeq, meta }`:
    the sub-agent's steps parsed like a session transcript (same event kinds), tailed on every request so a running sub-agent
    can be followed by polling. `agentId` must match `[A-Za-z0-9_-]{1,64}` (`422`), unknown → `404`.
  - **Codex** (and any adapter that emits `subagent` rows): the tree comes from the session log's `subagent` rows
    (parent = nearest enclosing `subagent` row); a sub-agent's events are the events nested under its row (direct
    children re-rooted). If none are nested and the agent is Codex, the child thread's own rollout
    (`$CODEX_HOME/sessions/**/rollout-*-<thread id>.jsonl`) is read when present (UNTESTED: no such file on the
    development machine).
- `parentId` links sub-agent events to their parent tool or subagent event. A child status/completion must not change the parent's task state or emit a parent completion notification.
- Unknown `kind` values must be rendered with the generic fallback.

| kind | data |
|---|---|
| `user_message` | `{ text, images, imageItems?, attachments?: [{name, path}], context?: [{kind: "file"|"selection", path, lines?}], source: "desktop"|"phone"|"unknown", clientId? }` — `attachments` lists files sent from the phone; the path block is removed from `text` — `text` never contains harness/IDE blocks (`<system-reminder>`, `<ide_opened_file>`, `<ide_selection>`, `<browser_instruction>`) or Claude Code's slash command wrappers (`<command-name>` … become `/name args`; `<local-command-stdout>` / `<local-command-stderr>` are `system` events, never user messages); what the IDE blocks said is in `context`. Background-task reports (`<task-notification>`) are never user messages — `clientId` echoes the `commandId` of the phone's `POST …/messages` that produced it (optimistic UI: replace the local bubble exactly) |
| `assistant_message` | `{ text (Markdown), model? }` |
| `reasoning` | `{ text, summary: bool }` — only when the agent actually exposes it |
| `tool_call` | `{ callId, name, title, category, input, status, server?, tool?, files?, background?, taskId?, backgroundSummary?, exitCode?, finishedAt? }` — `files`: `[{ path, offset?, limit? }]`, the files the step is about when `input` does not name them the Claude Code way (`input.file_path`): a Codex shell command that reads files (from Codex's `commandActions` / `parsed_cmd`, its `unknown` entries — most PowerShell — parsed again by the core) is `category: "read"` titled by the file, one that only lists or searches is `search`, anything else stays `shell` and still lists the files it clearly read; a Codex patch (`fileChange`, `apply_patch`) lists the files it changes. `offset` is 1-based, `limit` a line count, as in Claude Code's Read. Plan steps (`plan` items) use Claude Code's `pending` / `in_progress` / `completed` for every agent. — `category`: `shell`,`edit`,`write`,`read`,`search`,`web`,`mcp`,`task`,`todo`,`question`,`plan`,`other`; `status`: `pending`,`running`,`completed`,`failed`,`denied`,`interrupted`. A background shell (Claude Code `Bash` with `run_in_background`; `background: true`, `taskId` = its `backgroundTaskId`) stays `running` after its "started" `tool_result` and is updated in place by its task notification (`completed`, or `failed` for a non-zero exit code, with `backgroundSummary`/`exitCode`), or set `interrupted` when a `TaskStop`/`KillShell` result names it |
| `tool_result` | `{ callId, isError, text, truncated, bytes, exitCode? }` (`text` ≤ 8 KiB; full output via `…/payload`) |
| `file_change` | `{ callId?, path, change: "add"|"modify"|"delete"|"rename", diff, additions, deletions, truncated }` |
| `plan` | `{ items: [{ text, status: "pending"|"in_progress"|"completed" }] }` |
| `approval_request` | `{ requestId, kind: "command"|"file_change"|"tool"|"network"|"other", title, detail, command?, cwd?, toolName?, input?, options: [{ id, label }] , resolved: false }` |
| `approval_resolved` | `{ requestId, decision, by: "phone"|"desktop"|"other" }` |
| `question` | `{ requestId, cancellable?, questions: [{ id, header, question, multiSelect, required?, allowOther?, isSecret?, options: [{ label, description, value? }] }], resolved, decision?, by?, answers? }` — once answered, `answers` maps question id → the chosen value(s) (secrets masked) wherever it was answered: on the phone, in the VS Code panel (its `updatedInput.answers`), in Codex Desktop (its reply to the request), or — read from a Claude Code transcript — the AskUserQuestion result (`toolUseResult.answers`; id `q:<tool use id>`, `decision: "answer"`, next to the tool row). Approvals answered in Codex Desktop carry its decision (`allow`, `allow_session`, `deny`, `abort`) with `by: "desktop"` |
| `subagent` | `{ agentId, description, agentType?, model?, background, status: "running"|"completed"|"failed"|"interrupted", callId, summary?, result?, resultTruncated?, tokens?, toolUses?, durationMs?, activity?, agentPath?, finishedAt? }` — id `sa:<callId>` (Claude Code; Codex: `cx-sa-<child thread id>`), `parentId` = the Agent/Task (spawn) call. A sub-agent row exists from the moment the agent starts and is updated in place: live counters and `activity` from stream-json `task_progress`, the end from its task notification (transcript `<task-notification>` or stream-json `system` `task_notification`) or its result. Codex multi-agent v2 rows come from `SubAgentActivity` (rollout `item_completed` / legacy `sub_agent_activity`, app-server `subAgentActivity` items) and take the child's `FINAL_ANSWER` mail as `result`; a child rollout skips the parent history it inherited as a fork. A notification for anything else (unknown task, monitor) is a `system` event with `subtype: "task_notification"`, `{status?, text}`. Task notifications and messages the user typed while the agent was busy are read both from `user` records and from mid-turn `attachment` records (`queued_command`, `commandMode` `task-notification` / `prompt`; the latter becomes a normal `user_message`) |
| `status` | `{ state: "idle"|"running"|"waiting_approval"|"waiting_input"|"completed"|"error"|"interrupted", detail?, backgroundTasks? }` — `backgroundTasks` (managed Claude Code): background agents / shells still running; a session can be `idle` with work still going on (the summary's `activeTasks`, §10.1) |
| `error` | `{ message }` |
| `system` | `{ subtype, text }` (compaction, retries, informational) |
| `usage` | `{ inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, costUsd? }` |
| `ui` | `{ component: "<namespace>.<name>", version, props }` — extensible component channel; the Android registry maps `component` to a Composable and falls back to a structured JSON card |

Approval `options[].id` values are what `POST …/approvals/{requestId}` accepts as `decision`.

## 10. Versioning rules

- `protocolVersion` integer. Additive changes (new fields, new topics, new event kinds)
  do not bump it; clients must ignore unknown fields and render unknown `AgentEvent.kind`
  with the generic fallback component.
- Breaking changes bump it; the server keeps serving `minProtocolVersion..=protocolVersion`.
- The Android app shows "desktop too old/new" when `hello` ranges do not overlap.
