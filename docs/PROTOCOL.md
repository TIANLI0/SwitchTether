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
| Max request body | 1 MiB for JSON; 64 MiB for upload chunks (`PUT /v1/files/upload/{id}`) |

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
   - Global limit: 20 pairing attempts / minute / source IP → `429`.
   - Re-pairing an already-paired cert replaces the old record (same device id kept).

## 4. Step-up authentication (biometric gate)

High-risk scopes require a short-lived grant proven by a signature from the Keystore
step-up key (`setUserAuthenticationRequired(true)`), i.e. only after BiometricPrompt /
device credential succeeded on the phone. The server never trusts a client-side flag.

- `POST /v1/auth/step-up/challenge` `{ "scope": "terminal" | "files_write" | "config_write" }`
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
  timeout, no ping for 60 s).

Topics: `session.upserted`, `session.removed`, `session.event`, `approval.requested`,
`approval.resolved`, `provider.switched`, `usage.updated`, `terminal.created`,
`terminal.exited`, `device.revoked`, `server.shutdown`, `command.completed`.

## 7. Resources (all require a paired, non-revoked device)

### 7.1 Status
- `GET /v1/status` → server info, epoch/headSeq, network mode, connections, terminals, backend availability, active step-up grants.
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
  upstream service does not expose it — never fabricated).

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
- `POST /v1/sessions` `{commandId, agent, cwd?, prompt?, model?, permissionMode?, resumeFrom?, mode?}` → creates a **SwitchTether-managed** session (clearly labelled `origin: "managed"`).
  With `resumeFrom` (an existing `claude:`/`codex:` session) it **continues that conversation** ("接续会话"):
  `mode: "continue"` (default) keeps the same session/thread id; `mode: "fork"` (Claude only) starts a fork. `cwd` defaults
  to the original session's. The original history is preloaded into the new session's events. Refused with `409 conflict`
  while the original is `busy`/`waiting` on the computer, or (Codex) while the thread is open in another Codex client.
  Session capabilities advertise this as `continueSession: "PARTIAL"`.
- `POST /v1/sessions/{id}/messages` `{commandId, text}` (the `commandId` comes back as `clientId` on the resulting `user_message` event)
- `POST /v1/sessions/{id}/interrupt` `{commandId}`
- `POST /v1/sessions/{id}/approvals/{requestId}` `{commandId, decision: "allow"|"allow_session"|"deny"|"abort", message?, updatedInput?}`
  - `409 conflict` with `details.resolvedBy` if the request was already answered (by the desktop or another device).
- `POST /v1/sessions/{id}/answers/{requestId}` `{commandId, answers}` — Agent questions.
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
- `GET /v1/git/status?repo=` · `GET /v1/git/diff?repo=&path=&staged=` · `GET /v1/git/log?repo=&path=&limit=` · `GET /v1/git/show?repo=&rev=&path=` (file at a commit)

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
| `origin` | `cli`, `vscode`, `desktop_app`, `managed` (started by SwitchTether), `unknown` |
| `managed` | `true` only for sessions SwitchTether started. A session of the user's own process that SwitchTether is attached to through an opt-in takeover hook has its real origin, `managed: false` and `control: "full"` |
| `terminalId` | optional; set for sessions running in the user's terminal window (`st-claude` / `st-codex`). The id of an **external** terminal (`/v1/terminals`, `external: true`) mirroring that window; prompts are answered there |
| `liveness` | `live` (process running), `ended`, `unknown` |
| `status` | `busy`, `idle`, `waiting`, `completed`, `error`, `unknown` |
| `waitingFor` | `permission prompt`, `input needed`, … (as reported by the agent), or `null` |
| `control` | `full` (SwitchTether can send/approve/interrupt), `observe` (read-only live view), `history` (ended / transcript only) |

`GET /v1/sessions/{id}` adds `"capabilities": { "sendMessage": "VERIFIED|PARTIAL|BLOCKED|UNTESTED", "approve": …, "interrupt": …, "answerQuestion": …, "liveEvents": …, "readHistory": … }`
and `"pendingRequests": [AgentEvent]` (open approvals/questions).

Session ids are opaque; the prefix is informative only.

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
- `parentId` links sub-agent events to the sub-agent's `tool_call`.
- Unknown `kind` values must be rendered with the generic fallback.

| kind | data |
|---|---|
| `user_message` | `{ text, images, source: "desktop"|"phone"|"unknown", clientId? }` — `clientId` echoes the `commandId` of the phone's `POST …/messages` that produced it (optimistic UI: replace the local bubble exactly) |
| `assistant_message` | `{ text (Markdown), model? }` |
| `reasoning` | `{ text, summary: bool }` — only when the agent actually exposes it |
| `tool_call` | `{ callId, name, title, category, input, status, server?, tool? }` — `category`: `shell`,`edit`,`write`,`read`,`search`,`web`,`mcp`,`task`,`todo`,`question`,`plan`,`other`; `status`: `pending`,`running`,`completed`,`failed`,`denied`,`interrupted` |
| `tool_result` | `{ callId, isError, text, truncated, bytes, exitCode? }` (`text` ≤ 8 KiB; full output via `…/payload`) |
| `file_change` | `{ callId?, path, change: "add"|"modify"|"delete"|"rename", diff, additions, deletions, truncated }` |
| `plan` | `{ items: [{ text, status: "pending"|"in_progress"|"completed" }] }` |
| `approval_request` | `{ requestId, kind: "command"|"file_change"|"tool"|"network"|"other", title, detail, command?, cwd?, toolName?, input?, options: [{ id, label }] , resolved: false }` |
| `approval_resolved` | `{ requestId, decision, by: "phone"|"desktop"|"other" }` |
| `question` | `{ requestId, questions: [{ id, header, question, multiSelect, options: [{ label, description }] }] }` |
| `subagent` | `{ agentId, description, status }` |
| `status` | `{ state: "idle"|"running"|"waiting_approval"|"waiting_input"|"completed"|"error"|"interrupted", detail? }` |
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
