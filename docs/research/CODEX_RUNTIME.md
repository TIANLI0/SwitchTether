# Codex runtime research: attaching SwitchTether to running Codex sessions

- Date: 2026-11-14
- Machine: Windows 11 Pro for Workstations 10.0.26340
- Question: can SwitchTether attach to an **already running** OpenAI Codex session (CLI TUI or Codex Desktop) without changing how the user launches it? What structured data is available?

Status labels:

| Label | Meaning |
|---|---|
| **VERIFIED** | Observed on this machine by a command or experiment. |
| **PARTIAL** | Works with stated limits, works only via heuristics, or is verified only in isolation. |
| **BLOCKED** | Not possible by design (evidence given). |
| **UNTESTED** | Derived from source or docs only. |

Safety notes for this research:
- `~/.codex/config.toml` and `auth.json` were never modified. The only read of `config.toml` was a key-name scan.
- No user process was killed or restarted, and no prompt was sent to a model.
- All live protocol experiments ran against separate, short-lived `codex app-server` processes. These used an **isolated `CODEX_HOME`** in the session scratch directory, holding copies of two rollout files and no auth, config or state DB.
- Those processes, the isolated home and the temporary socket directory were all deleted afterwards. Codex Desktop's own app-server (pid 49072) kept running untouched.
- Conversation content is not reproduced here; only structure and field names are.

---

## 0. TL;DR

1. **What is installed here.**
   - The `codex` on PATH is **CLI 0.137.0**, at `~/.bun/bin/codex.exe` (also present as an npm shim).
   - **Codex Desktop is installed and running.** It is MSIX `OpenAI.Codex` 26.1106.6628.0, process `ChatGPT.exe`, and it bundles **codex.exe 0.167.0-alpha.4.1**.
   - Every rollout on disk was written by the Desktop app (source `vscode`, originator `Codex Desktop`). The state DB has no `cli`-source threads.
2. **Codex Desktop cannot be attached in place on Windows (BLOCKED).**
   - It spawns a private `codex.exe app-server` child over **stdio**, with no `--listen`.
   - The process owns no TCP listener, and stdio mode is single-client by design.
   - The Desktop bundle only uses the shared daemon socket on non-Windows, and only with the opt-in `CODEX_APP_SERVER_USE_LOCAL_DAEMON=1`.
3. **CLI 0.137 on Windows cannot be attached in place (BLOCKED).**
   - The 0.137 TUI runs an embedded, in-process app-server (`AppServerTarget::Embedded`).
   - `codex app-server daemon …` fails with "daemon lifecycle is only supported on Unix platforms".
4. **A second app-server cannot take over a live thread (BLOCKED, verified live).**
   - A separately started app-server that calls `thread/resume` on a thread live in another process gets JSON-RPC error **`-32600 "thread <id> already has an active writer"`**.
   - The guard is per-thread OS file locks under `~/.codex/thread-writer-locks/<threadId>.lock`.
   - The second process does **not** create a parallel runtime. The TUI falls back to read-only viewing in this case.
5. **The supported path to real multi-client attach is a shared app-server (VERIFIED mechanics, isolated).**
   - One app-server listening on `ws://127.0.0.1:PORT` or on the control socket (`unix://`, a WebSocket over AF_UNIX, which works on Windows with 0.167) accepts many clients.
   - A second client calling `thread/resume` on a running thread **rejoins** it: it gets subscribed, its overrides are ignored, pending approvals are replayed to it, and it receives thread notifications.
   - Newer CLIs make this the default:
     - Windows daemon support arrived in ≈0.154.
     - `daemon_auto_start` became stable and default-on in ≈0.157.
     - From then on, an interactive `codex` TUI auto-starts or attaches to the shared daemon at `~/.codex/app-server-control/app-server-control.sock`, unless excluded (for example `-c` overrides, `--profile`, `--oss`, an elevated terminal, or a socket path over 108 bytes).
   - **This is the only route to in-place attach without changing how the user launches Codex.** It needs a CLI ≥ ~0.157, not the 0.137 installed here.
6. **Reading the on-disk record works for every client, as observation only (VERIFIED).**
   - Data sources: rollout JSONL, `state_5.sqlite` (`threads` table) and Desktop's own `sqlite/codex.db` (`active_chat_turns`, and others).
   - None of this exposes pending approvals or streaming deltas, and none of it can control the session.

---

## A. CLI surface and app-server protocol

### A.1 Commands run (trimmed output)

```text
> codex --version
codex-cli 0.137.0

> codex --help            (relevant lines)
  app-server      [experimental] Run the app server or related tooling
  remote-control  [experimental] Manage the app-server daemon with remote control enabled
  app             Launch the Codex desktop app (opens the app installer if missing)
  resume / fork / archive / unarchive / exec / exec-server ...
  --remote <ADDR>   Connect the TUI to a remote app server endpoint.
                    Accepted forms: `ws://host:port`, `wss://host:port`, `unix://`, or `unix://PATH`.
  --remote-auth-token-env <ENV_VAR>

> codex resume --help     -> also has --remote / --remote-auth-token-env, [SESSION_ID], --last, --all

> codex app-server --help
Commands: daemon | proxy | generate-ts | generate-json-schema
  --listen <URL>  Transport endpoint URL. Supported values: `stdio://` (default), `unix://`,
                  `unix://PATH`, `ws://IP:PORT`, `off`
  --ws-auth <MODE> [capability-token, signed-bearer-token]  (+ --ws-token-file, --ws-token-sha256,
  --ws-shared-secret-file, --ws-issuer, --ws-audience, --ws-max-clock-skew-seconds)
  --analytics-default-enabled

> codex app-server daemon --help
  bootstrap | start | restart | enable-remote-control | disable-remote-control | stop | version
> codex app-server proxy --help
  Proxy stdio bytes to the running app-server control socket   [--sock <SOCKET_PATH>]
> codex remote-control --help
  start | stop          (0.167 adds: pair)

> codex app-server daemon version                       (CLI 0.137)
Error: codex app-server daemon lifecycle is only supported on Unix platforms

> <Desktop-bundled codex.exe 0.167.0-alpha.4.1> app-server daemon version
Error: failed to connect to %USERPROFILE%\.codex\app-server-control\app-server-control.sock
  (os error 10050)      <- daemon supported on Windows in 0.167, simply not running
> <bundled 0.167> --help  ->  additionally has: --no-daemon
> <bundled 0.167> features list | grep daemon  ->  daemon_auto_start   removed   true
```

(The `daemon version` probe created nothing; `~/.codex/app-server-control` still does not exist.)

### A.2 Schema generation

```text
codex app-server generate-json-schema --out <scratch>/research/codex-schema          (exit 0)
codex app-server generate-json-schema --experimental --out <scratch>/research/codex-schema-exp
codex app-server generate-ts --out <scratch>/research/codex-schema-ts                (exit 0)
```

- **Size:** the JSON schema bundle is 256 files, about **2.50 MB**, so it was **not** copied into the repo (the limit is 2 MB). The method list is below.
- **TypeScript bindings:** 547 files, about 285 KB, kept in scratch only.
- **Regenerate** with the commands above, using 0.137 or a newer CLI to match the target version.
- **Wire format:** JSON-RPC without the `"jsonrpc"` field: `{id, method, params}`, then `{id, result}` or `{id, error}`, plus notifications `{method, params}`.
  - Over stdio, messages are newline-delimited JSON.
  - Over ws and unix, each message is one text frame.
- **Handshake:** `initialize` → response `{userAgent, codexHome, platformFamily, platformOs}` → client notification `initialized`.
  - `InitializeParams` = `{clientInfo{name,title?,version}, capabilities?{experimentalApi, optOutNotificationMethods[], requestAttestation}}`.

### A.3 Client → server requests (CLI 0.137, stable: 82)

The methods SwitchTether needs are in **bold**.

```text
initialize
thread/start  thread/resume  thread/fork  thread/archive  thread/unsubscribe  thread/name/set
thread/goal/set  thread/goal/get  thread/goal/clear  thread/metadata/update  thread/unarchive
thread/compact/start  thread/shellCommand  thread/approveGuardianDeniedAction  thread/rollback
thread/list  thread/loaded/list  thread/read  thread/inject_items
skills/list  skills/extraRoots/set  hooks/list  marketplace/add|remove|upgrade
plugin/list|installed|read|skill/read|share/save|share/updateTargets|share/list|share/checkout|share/delete
app/list  fs/readFile|writeFile|createDirectory|getMetadata|readDirectory|remove|copy|watch|unwatch
skills/config/write  plugin/install  plugin/uninstall
turn/start  turn/steer  turn/interrupt  review/start
model/list  modelProvider/capabilities/read  experimentalFeature/list  permissionProfile/list
experimentalFeature/enablement/set  mcpServer/oauth/login  config/mcpServer/reload  mcpServerStatus/list
mcpServer/resource/read  mcpServer/tool/call  windowsSandbox/setupStart  windowsSandbox/readiness
account/login/start|cancel  account/logout  account/rateLimits/read  account/sendAddCreditsNudgeEmail
feedback/upload  command/exec  command/exec/write|terminate|resize  config/read
externalAgentConfig/detect|import  config/value/write  config/batchWrite  configRequirements/read
account/read  fuzzyFileSearch
```

Bold subset: **initialize, thread/list, thread/loaded/list, thread/read, thread/resume, thread/start, thread/unsubscribe, turn/start, turn/steer, turn/interrupt**. Also useful: thread/name/set, thread/fork, thread/rollback, thread/compact/start, model/list, account/rateLimits/read.

These need `--experimental` or `capabilities.experimentalApi: true` (30 extra):

```text
thread/turns/list  thread/turns/items/list  thread/search  thread/settings/update  thread/memoryMode/set
thread/backgroundTerminals/clean  thread/increment_elicitation  thread/decrement_elicitation
thread/realtime/start|stop|appendAudio|appendText|listVoices
remoteControl/enable|disable|status/read|pairing/start|client/list|client/revoke
process/spawn|kill|writeStdin|resizePty  collaborationMode/list  environment/add  memory/reset
fuzzyFileSearch/sessionStart|sessionUpdate|sessionStop  mock/experimentalMethod
```

### A.4 Server → client requests (10): approvals and questions

| Method | Params (key fields) | Response |
|---|---|---|
| **`item/commandExecution/requestApproval`** | threadId, turnId, itemId, startedAtMs, command?, cwd?, commandActions?, reason?, availableDecisions?, approvalId?, networkApprovalContext?, proposedExecpolicyAmendment?, proposedNetworkPolicyAmendments?, additionalPermissions? | `{decision}`: `accept` \| `acceptForSession` \| `{acceptWithExecpolicyAmendment}` \| `{applyNetworkPolicyAmendment}` \| `decline` (turn continues) \| `cancel` (turn interrupted) |
| **`item/fileChange/requestApproval`** | threadId, turnId, itemId, startedAtMs, reason?, grantRoot? | `{decision}`: `accept` \| `acceptForSession` \| `decline` \| `cancel` |
| **`item/permissions/requestApproval`** | threadId, turnId, itemId, startedAtMs, cwd, permissions, reason?, environmentId? | `{permissions, scope?, strictAutoReview?}` |
| **`item/tool/requestUserInput`** (EXPERIMENTAL) | threadId, turnId, itemId, questions[{id, header, question, options[{label,description}], isOther, isSecret}] | `{answers: {<questionId>: {answers: [...]}}}` |
| **`mcpServer/elicitation/request`** | MCP elicitation form | `{action, content?, _meta?}` |
| `item/tool/call` | dynamic tool call (client-provided tools) | `{contentItems, success}` |
| `account/chatgptAuthTokens/refresh` | only for externally managed auth | n/a |
| `attestation/generate` | only if `requestAttestation` | n/a |
| `applyPatchApproval`, `execCommandApproval` | legacy v1 (ignore) | n/a |

### A.5 Server → client notifications (64)

The ones SwitchTether needs are in **bold**.

```text
error  **thread/started**  **thread/status/changed**  thread/archived  thread/unarchived  **thread/closed**
skills/changed  **thread/name/updated**  thread/goal/updated  thread/goal/cleared  thread/settings/updated
**thread/tokenUsage/updated**  **turn/started**  hook/started  **turn/completed**  hook/completed
**turn/diff/updated**  **turn/plan/updated**  **item/started**  item/autoApprovalReview/started
item/autoApprovalReview/completed  **item/completed**  **item/agentMessage/delta**  item/plan/delta
command/exec/outputDelta  process/outputDelta  process/exited  **item/commandExecution/outputDelta**
item/commandExecution/terminalInteraction  **item/fileChange/outputDelta**  item/fileChange/patchUpdated
**serverRequest/resolved**  item/mcpToolCall/progress  mcpServer/oauthLogin/completed
mcpServer/startupStatus/updated  account/updated  **account/rateLimits/updated**  app/list/updated
remoteControl/status/changed  externalAgentConfig/import/completed  fs/changed
item/reasoning/summaryTextDelta  item/reasoning/summaryPartAdded  item/reasoning/textDelta
thread/compacted  model/rerouted  model/verification  warning  guardianWarning  deprecationNotice
configWarning  fuzzyFileSearch/sessionUpdated  fuzzyFileSearch/sessionCompleted
thread/realtime/started|itemAdded|transcript/delta|transcript/done|outputAudio/delta|sdp|error|closed
windows/worldWritableWarning  windowsSandbox/setupCompleted  account/login/completed
```

The client sends one notification of its own: `initialized`.

### A.6 Key data shapes

- **`Thread`** fields:
  - Identity and origin: `id`, `sessionId`, `source` (`cli|vscode|exec|appServer|{custom}|{subAgent}`), `threadSource?`, `cliVersion`.
  - Timestamps and location: `createdAt`, `updatedAt` (seconds), `cwd`, `path?` (UNSTABLE rollout path), `gitInfo?`.
  - Lifecycle: `status`, `ephemeral`, `turns[]`.
  - Labels and lineage: `name?`, `preview`, `modelProvider`, `forkedFromId?`, `parentThreadId?`, `agentNickname?`, `agentRole?`.
  - `turns` is only populated by resume, rollback, fork, or read with `includeTurns`.
- **`ThreadStatus`:** `notLoaded` | `idle` | `systemError` | `active{activeFlags: [waitingOnApproval|waitingOnUserInput]}`. Useful for the phone's "needs attention" badge.
- **`Turn`:** `id`, `status` (`completed|interrupted|failed|inProgress`), `items[]`, `itemsView`, `startedAt`, `completedAt`, `durationMs`, `error?`.
- **`ThreadItem.type`:** `userMessage`, `hookPrompt`, `agentMessage`, `plan`, `reasoning`, `commandExecution`, `fileChange`, `mcpToolCall`, `dynamicToolCall`, `collabAgentToolCall`, `webSearch`, `imageView`, `imageGeneration`, `enteredReviewMode`, `exitedReviewMode`, `contextCompaction`.
- **`thread/list` params:** `cursor`, `limit`, `sortKey` (`created_at|updated_at`), `sortDirection`, `sourceKinds[]`, `modelProviders[]`, `cwd`, `archived`, `searchTerm`, `useStateDbOnly`.
  - With `sourceKinds` omitted, only "interactive sources" are returned. **Pass `sourceKinds` explicitly** (Desktop threads are `vscode`).
  - Without `useStateDbOnly: true`, the server scans rollouts and repairs state-DB metadata, which is a write.
- **`thread/resume` params:** `threadId`, plus optional overrides (model, cwd, approvalPolicy, sandbox, permissions, …), `excludeTurns`, and `initialTurnsPage`.
  - Schema doc: *"If thread_id identifies a running thread, app-server rejoins that thread and treats a non-empty path as a consistency check against the active rollout path."*
- **`turn/start`** `{threadId, input: UserInput[] (text|image|localImage|skill|mention), …overrides}`.
- **`turn/steer`** `{threadId, expectedTurnId, input}` adds input to the in-flight turn.
- **`turn/interrupt`** `{threadId, turnId}` returns `{}`; the turn ends `interrupted`.

---

## B. Live protocol experiments (separate app-servers, isolated `CODEX_HOME`)

Harness: a Node 24 script (scratch only).
- **Isolated home:** two Desktop-written rollout files copied into a fresh `CODEX_HOME`, with no `auth.json`, `config.toml` or state DB.
- **Binaries:** CLI 0.137 (`codex.exe` on PATH) and the Desktop-bundled 0.167.0-alpha.4.1.
- **Safety:** `turn/start` was never sent. The probe printed only ids, statuses, counts and keys.

### B.1 stdio, read-only (initialize → thread/list → thread/loaded/list → thread/read)

| Step | CLI 0.137 | bundled 0.167 |
|---|---|---|
| initialize | OK, `platformFamily=windows`; notification `remoteControl/status/changed status=disabled` | same |
| `thread/list` (default) | **OK, n=0**. It does not list the 0.167-written rollouts. | **OK, n=2**, both `source: vscode`, `status: notLoaded`, `cliVersion 0.167.0-alpha.3.2` |
| `thread/list sourceKinds=[cli]` | 0 | 0 |
| `thread/loaded/list` | `[]` | `[]` |
| `thread/read includeTurns=false` | OK (found by id/filename) | OK |
| `thread/read includeTurns=true` | OK, 2 turns but **0 items** (format skew) | **ERROR -32601 "list_turns is not supported yet"** |
| `thread/turns/list` | OK, 2 turns, 0 items | same -32601 error |
| close stdin | process exits 0 (stdio = single client; exits on EOF) | same |

What this shows:
- **Discovery is per-`CODEX_HOME`, not per-process.** Any app-server sees threads created by other clients (here, Desktop-created threads) because it reads the shared rollouts and state DB.
- **History reading is version-sensitive.**
  - The older 0.137 cannot list 0.167 rollouts or render their items.
  - The 0.167 build could not page turns of `history_mode: paginated` rollouts in the isolated home, which has no `thread_history_1.sqlite` projection. On `main`, `LocalThreadStore::list_turns` is implemented, so this looks build-specific.
  - The same reads were **deliberately not run against the real `~/.codex`**. A 0.167 app-server there would share the Desktop's remote-control enrollment and state DB.

### B.2 One app-server on `--listen ws://127.0.0.1:PORT`, three clients (A, B, C)

```text
A thread/start {ephemeral:false, approvalPolicy:untrusted}  -> OK thread status idle
  thread/started  -> delivered to A, B and C (broadcast, B/C not subscribed)
A thread/inject_items (1 synthetic user message; no model call) -> {}
B thread/loaded/list                -> [<new thread id>]            (cross-client visibility in one server)
B thread/resume {model:"switchtether-override-test", approvalPolicy:"never"}
                                    -> OK status idle, model=<original>, approvalPolicy=untrusted
                                       (REJOIN: overrides ignored)
  thread/goal/cleared (thread-scoped) -> delivered to A and B
A thread/name/set                   -> thread/name/updated delivered to A and B (and C)
B turn/interrupt {turnId:"bogus"}   -> ERROR -32600 "no active turn to interrupt"
B turn/steer  {expectedTurnId:"bogus"} -> ERROR -32600 "no active turn to steer"
B thread/unsubscribe -> {"status":"unsubscribed"}; again -> {"status":"notSubscribed"}
after last unsubscribe: thread stays loaded; ~60.3 s later every connection (incl. never-subscribed C)
  receives thread/status/changed {notLoaded} then thread/closed; thread/loaded/list -> []
```

Two quirks were observed:
- `thread/resume` on a thread that was started but has no materialized rollout (no turn and no injected item) fails with `-32600 "no rollout found for thread id …"`.
- A failed `thread/resume` (the `list_turns` error) still left the thread **loaded** with no subscriber, and the writer lock held, until the idle unload.

### B.3 Cross-process: second app-server vs a thread live in the first

While the thread was loaded in the ws server above, a **separate** stdio app-server on the same `CODEX_HOME` ran:

```text
proc2 thread/loaded/list            -> []                         (no cross-process view of live threads)
proc2 thread/read <live id>         -> OK status {"type":"notLoaded"}   (status is per-process; misleading)
proc2 thread/resume <live id>       -> ERROR -32600 "thread <id> already has an active writer"
```

This was reproduced twice: once with a copied Desktop rollout thread, once with a freshly started thread.

**Conclusion:**
- A second app-server cannot take over, or even co-drive, a thread that is live in another process.
- It also cannot tell that the thread is live, other than by getting this error.
- The writer lock prevents the parallel-runtime / rollout write-conflict scenario.

### B.4 Control socket on Windows (`--listen unix://PATH`) through `codex app-server proxy` (0.167)

```text
app-server --listen unix://<long scratch path>  -> Error: path must be shorter than SUN_LEN
app-server --listen unix://%TEMP%\studs\cs.sock -> Error: socket directory is not private to the current user
  (after making that dir owner-only via icacls)  -> listening
app-server proxy --sock … + raw JSON lines      -> proxy exit 1 "failed to copy data from stdin to socket (os error 10054)"
app-server proxy --sock … + RFC6455 handshake/frames over the proxy's stdio:
  X,Y: "HTTP/1.1 101 Switching Protocols"; initialize OK (platformOs=windows)
  X thread/start + inject_items; Y thread/loaded/list -> [id]; Y thread/resume -> OK (rejoin)
  X thread/name/set -> thread/name/updated delivered to X and Y
```

So the control socket (the shared daemon endpoint):
- **Works on Windows in 0.167.**
- Speaks **WebSocket over AF_UNIX**.
- Requires a short path (108 bytes or less) in a private directory.
- `codex app-server proxy` is a plain byte pipe, so the client must speak WebSocket through it. `tokio-tungstenite::client_async` over the child's stdio would do this in Rust.

Side observation: after a `thread/resume` and `thread/start`, the server tried to open a **Responses WebSocket** (prewarm). This failed because the isolated home had no auth. No prompt was sent.

---

## C. Session storage (`~/.codex`, read-only inspection; names only)

### C.1 Layout highlights

| Path | Notes |
|---|---|
| `sessions/YYYY/MM/DD/rollout-<ISO-ts>-<threadId>.jsonl` | 9 files present; 0 in `archived_sessions` |
| `session_index.jsonl` | 179 lines, keys `{id, thread_name, updated_at}` (no `history.jsonl` present) |
| `state_5.sqlite` (+wal/shm) | main thread catalog (below) |
| `thread_history_1.sqlite` (~458 MB) | paginated history projection (`thread_turns`, `thread_items`, …) |
| `goals_1.sqlite`, `memories_1.sqlite`, `queue_1.sqlite`, `logs_2.sqlite` | goals, memories, queue, logs |
| `thread-writer-locks/.coordination.lock`, `<threadId>.lock` | **per-thread exclusive writer locks** (OS file lock). A file existing does not mean the lock is held. |
| `process_manager/chat_processes.json` | 46 entries `{id, conversationId, turnId, itemId, command, cwd, startedAtMs, updatedAtMs, osPid, processId, chatTitle}` (Desktop background processes) |
| `sqlite/codex.db` | **Codex Desktop's own DB** (below) |
| `app-server-control/` | **absent**, so no daemon has ever run here |

### C.2 Rollout JSONL (written by Desktop's 0.167; `history_mode: paginated`)

Every line has the keys `{timestamp, type, payload, ordinal, metadata}`. The first line is always `session_meta`.

| `type` (/ `payload.type`) | payload keys |
|---|---|
| `session_meta` | id, session_id, timestamp, cwd, originator (`Codex Desktop`), cli_version, source (`vscode`), thread_source, model_provider, base_instructions, git, history_mode, runtime_workspace_roots, context_window, account_scoped, creator_user_id, creator_account_id |
| `turn_context` | turn_id, root_turn_id, cwd, model, effort, summary, approval_policy, approvals_reviewer, sandbox_policy, permission_profile, active_permission_profile, collaboration_mode, realtime_active, disabled_plugin_ids, multi_agent_version, comp_hash, cyber_access_program |
| `response_item / message`, `reasoning`, `function_call`, `function_call_output`, `custom_tool_call`, `custom_tool_call_output`, `additional_tools`, `configuration_update` | raw Responses API items (id, role, content, call_id, name, arguments/input, output, encrypted_content, status, phase, …) |
| `event_msg / task_started` | turn_id, root_turn_id, started_at, model_context_window, collaboration_mode_kind, turn_attribution |
| `event_msg / task_complete` | turn_id, completed_at, duration_ms, error, last_agent_message, time_to_first_token_ms |
| `event_msg / turn_aborted` | turn_id, reason, started_at, completed_at, duration_ms |
| `event_msg / item_completed` | thread_id, turn_id, started_at_ms, completed_at_ms, item. Item types: CommandExecution, Reasoning, AgentMessage, FileChange, UserMessage, Extension, ImageView, McpToolCall, ContextCompaction |
| `event_msg / token_count` | info, rate_limits |
| `event_msg / thread_settings_applied` | thread_id, thread_settings |
| `token_usage_record` | response_id, thread_id, turn_id, root_turn_id, session_id, usage, turn_token_usage, thread_token_usage |
| `world_state` | full, state |
| `compacted` | window_id, replacement_history, retained_context, … |

Line-level `metadata` keys: client_authored, user_input_order, retained_source, mcp_attribution, harness_authored_configuration, fallback_token_limit_override.

Not observed in any rollout: approval-request events or streaming deltas. Rollouts record completed items only, so **pending approvals and questions are invisible to file tailing**. Older CLIs, such as 0.137 writing its own sessions, use a different event vocabulary. No CLI-written rollout exists on this machine to compare against, so that format is UNTESTED here.

### C.3 SQLite tables (opened `immutable=1`, schema only)

- **`state_5.sqlite`:**
  - `threads`: id, rollout_path, created_at(_ms), updated_at(_ms), source, model_provider, cwd, title, name, preview, first_user_message, sandbox_policy, approval_mode, tokens_used, has_user_event, archived, cli_version, model, reasoning_effort, history_mode, originator, project_id, is_pinned, git_*, agent_*, recency_at(_ms), …
    - Rows by source: vscode 186, exec 20, subagent 17, **cli 0**.
  - Other tables: `thread_spawn_edges`, `thread_read_receipts`, `thread_dynamic_tools`, `thread_attachments`, `thread_sections`, `projects`, `project_roots`, `guardian_review_feedback`, `rollout_migration_*`, `backfill_state`.
  - `remote_control_enrollments` (websocket_url, server_id, environment_id, remote_control_enabled, …) holds **1 row, enabled**.
- **`thread_history_1.sqlite`:** `thread_turns` (thread_id, turn_id, status, started_at, completed_at, duration_ms, first_user_item_id, final_agent_item_id, rollout offsets), `thread_items` (thread_id, turn_id, item_id, item_type, item_json, timestamps), `thread_realtime_items`, `thread_history_projection_state`.
- **`sqlite/codex.db` (Desktop):** **`active_chat_turns`** (profile, account_id, user_id, thread_id, launch_id, observed_at_ms; 0 rows at inspection time), `local_thread_catalog*`, `thread_timeline_ledger`, `inbox_items`, `automations`, `automation_runs`, `local_app_server_feature_enablement`, …

### C.4 Is there a registry of live processes?

There is **no supported registry** of live sessions. These are the heuristic signals:
- **`thread-writer-locks/<id>.lock`:** an exclusive OS lock is held while a process owns the thread. Testing it means trying to take the lock, which is intrusive and could block the owner, so SwitchTether **must not** probe it.
- **Desktop `codex.db.active_chat_turns`:** active turns in Desktop (undocumented internal).
- **Rollout mtime / `threads.updated_at_ms`.**
- **Process list:** `codex.exe … app-server` children of `ChatGPT.exe`, or `codex[.exe] [resume <id>]` TUI command lines.
- **On a shared app-server only:** `thread/loaded/list` plus `thread/status/changed` give an authoritative live view. In-process TUIs and Desktop's private server do not expose this.

---

## D. Upstream source findings (openai/codex @ main, 2026-11-14; plus release tags)

Findings were collected with `gh api` and spot-checked against the fetched files.

1. **Transports.**
   - `stdio://`, `unix://[PATH]`, `ws://IP:PORT` and `off` are available.
   - Stdio is single-client: `let single_client_mode = matches!(&transport, AppServerTransport::Stdio);`, and the server stops on `"stdio_connection_closed"` (`app-server/src/lib.rs` ~831/1240).
   - Unix and ws accept many connections.
   - The Unix socket does a WebSocket handshake (`accept_hdr_async_with_config` → `run_websocket_connection`) at `CODEX_HOME/app-server-control/app-server-control.sock`.
   - The 0.137 README labels the WebSocket transport "**experimental / unsupported**".
2. **Routing.**
   - Thread events go to that thread's subscribed connections (`subscribed_connection_ids`).
   - Global notifications (`thread/started`, `thread/name/updated`, `thread/closed`, …) are broadcast to every initialized connection that has not opted out. This matched the live test.
3. **Rejoin.**
   - *"SendThreadResumeResponse is used to resume an already running thread by sending the thread's history to the client and atomically subscribing for new updates"* (`thread_state.rs:67`).
   - The joining connection gets pending server requests replayed (`replay_requests_to_connection_for_thread`, `thread_lifecycle.rs` ~1284).
   - Overrides are ignored: *"thread/resume overrides ignored for loaded thread"*.
   - Resuming with history while running → error. A stale path → error.
4. **Unsubscribe / unload.**
   - `thread/unsubscribe` returns `notLoaded | notSubscribed | unsubscribed`.
   - The last subscriber leaving starts an idle unload (`thread_unload_delay_secs`, default 60 s on main; the 0.137 README says 30 min).
   - On unload, pending requests are cancelled and `thread/closed` is broadcast.
5. **Approval concurrency.**
   - Each subscribed connection receives the request.
   - **First response wins**: `take_connection_callback` → `callbacks.remove_entry(id)`, and later responses only log `"could not find callback"`.
   - `serverRequest/resolved {threadId, requestId}` tells every subscriber the request is gone. It is also emitted when turn start, complete or interrupt clears it.
   - There is no controller/lease concept, except `humanCommand` approvals (only connections that opted into `requiredHumanApproval`) and a single-owner verification ceremony.
   - INFERRED: any subscribed connection may answer an ordinary approval.
6. **Cross-process writer locks.**
   - `rollout/src/writer_lock.rs` holds `thread-writer-locks/{thread_id}.lock`. `try_lock` → `WouldBlock` → *"thread {thread_id} already has an active writer"*.
   - The test `thread_resume_rejects_paginated_writer_owned_by_another_process` asserts `-32600`, and asserts that resume succeeds once the primary process exits.
   - The TUI handles it with `is_active_writer_error` → `read_thread_for_viewing` → `read_only` (`tui/src/app/resume_preparation.rs` ~169).
7. **Daemon.**
   - `app-server daemon start` launches `app-server [--remote-control] --listen unix:// … --analytics-default-enabled`. State lives in `CODEX_HOME/app-server-daemon/`.
   - Daemon README: *"The daemon supports Linux, macOS, and Windows… Windows startup requires a non-elevated terminal"*.
   - On Windows the process is spawned `DETACHED_PROCESS | CREATE_BREAKAWAY_FROM_JOB`, and the daemon has an auto-updater.
   - TUI: `daemon_auto_start` (`Stage::Stable, default_enabled: true` on main). The TUI auto-starts or attaches unless `--no-daemon`, `--remote`, `--oss`, `-c` overrides, `--profile`, `CODEX_EXEC_SERVER_URL`, `--strict-config`, an elevated terminal, …
   - If an implicitly found daemon cannot initialize, *"the TUI starts an embedded server instead"*.
   - Shared clients inherit the **daemon's** environment.
8. **`--remote`.**
   - The TUI connects to `ws://`, `wss://` or `unix://`, and `codex resume <id> --remote …` is accepted.
   - *"Persistent app-server resumes may attach to an already-running thread, where resume config overrides are ignored."*
   - `--remote-auth-token-env` requires `wss://` or loopback `ws://`.
9. **WebSocket auth.**
   - Loopback needs no auth.
   - Non-loopback is refused without `--ws-auth` (main; 0.120 still allowed it "during rollout").
   - Capability token (SHA-256 compared) or HS256 JWT, sent as `Authorization: Bearer`, checked only at upgrade.
   - **Any request carrying an `Origin` header is rejected with 403**, so browsers cannot connect directly.
10. **remote-control** is an outbound relay to `wss://chatgpt.com/backend-api/wham/remote/control/server` with pairing and enroll endpoints, used for ChatGPT mobile/web control. The relay envelope is internal, with **no public third-party protocol**.
11. **Desktop / VS Code.**
    - The app-server README says it powers the VS Code extension (`clientInfo.name: "codex_vscode"`).
    - `initialize_processor.rs` special-cases `(ConnectionOrigin::Stdio, "Codex Desktop")`.
    - There is no Desktop source in the repo.
12. **Version map** (stable tags, by file/string bisection):

    | Feature | Since |
    |---|---|
    | `--listen ws://` | 0.100.0 |
    | `thread/unsubscribe` | 0.106.0 |
    | `--ws-auth` | 0.117.0 |
    | `app-server proxy` | 0.125.0 |
    | daemon crate (Unix) | 0.131.0 |
    | **Windows daemon** | **0.154.0** |
    | `--no-daemon` | 0.156.0 |
    | **`daemon_auto_start` stable, default on** | **0.157.0** |

### D.1 Codex Desktop specifics (this machine)

- **Processes:** `ChatGPT.exe` (pid 37012) → `codex.exe -c features.code_mode_host=true app-server --analytics-default-enabled -c features.prefer_mxc=true -c plugins.codex-app-tools@openai-bundled.mcp_servers.codex_app.enabled=true -c features.windows_sandbox_service=true`.
  - That is **stdio**, with **no TCP listener** owned by any of the 30 Desktop processes.
  - A second child is `codex.exe exec-server --remote https://codex-cloud-environments.chatgpt.com/api …`.
- **Named pipes:**
  - `\\.\pipe\codex-ipc` is a private IPC router.
  - In OSS it is only documented for `ide-context` (`tui/src/ide_context/ipc.rs`: frames `request|response|broadcast|client-discovery-*`, `sourceClientId`).
  - It is undocumented and was **not probed**.
  - Other pipes: `codex-app-tools-*`, `codex-browser-use-*`, `codex-computer-use-*`, `OpenAI.CodexSandbox.*`.
- **Desktop bundle (`app.asar`) gating for using the local daemon instead of its own server:**
  ```js
  "win32"!==process.platform && !e?.length && "local"===this.options.hostConfig.kind
    && "1"===process.env.CODEX_APP_SERVER_USE_LOCAL_DAEMON && "1"!==process.env.CODEX_APP_SERVER_FORCE_CLI
    && !process.env.CODEX_CLI_PATH?.trim() && null==this.options.hostConfig.codex_cli_command && <daemon version check>
  ```
  So on Windows, Desktop **never** shares its runtime through the daemon in this build.
  - The bundle also contains an SSH remote-host flow that boots `app-server-control` on a remote machine.

---

## E. Capability matrices

### E.1 Codex CLI

Two cases are scored separately:
- **(a)** CLI 0.137 as installed here (embedded in-process app-server).
- **(b)** CLI ≥ 0.157 launched normally, which auto-attaches the TUI to the shared daemon.

| Capability | (a) CLI 0.137, user launches `codex` normally | (b) CLI ≥0.157 default daemon (no launch change) |
|---|---|---|
| Discover live sessions | **PARTIAL**: heuristics only (process list, rollout mtime, `threads.updated_at_ms`); no API; writer lock not safely probeable | **UNTESTED** (mechanics VERIFIED isolated): `thread/loaded/list` + `thread/status/changed` on the daemon socket |
| Read full history | **PARTIAL**: rollout JSONL / `state_5.sqlite` read-only (VERIFIED structure); format is version-dependent; app-server `thread/read` from a different CLI version gave 0 items | **UNTESTED**: `thread/resume` rejoin returns history; `thread/turns/list` (experimental) |
| Real-time events | **PARTIAL**: tail rollout = completed items only, no deltas, no pending approvals | **UNTESTED** (VERIFIED isolated): full `item/*`, `turn/*`, deltas after rejoin |
| Send message into existing live session | **BLOCKED**: in-process server; second app-server gets `-32600 already has an active writer` (VERIFIED) | **UNTESTED**: `turn/start` (idle) / `turn/steer` (in-flight) on the rejoined thread |
| Answer questions (`requestUserInput`) | **BLOCKED** | **UNTESTED**: request is replayed to joining clients; first answer wins |
| Approve | **BLOCKED** | **UNTESTED**: same, `serverRequest/resolved` notifies the TUI |
| Interrupt | **BLOCKED** | **UNTESTED**: `turn/interrupt {threadId, turnId}` (error path VERIFIED: "no active turn to interrupt") |
| Concurrency control | n/a | **PARTIAL**: no lease/controller; first-response-wins plus `serverRequest/resolved`; `turn/steer.expectedTurnId` as an optimistic precondition |
| SwitchTether-owned app-server session | **VERIFIED**: `codex app-server` (stdio or `--listen ws://127.0.0.1:P`) starts, initializes, lists threads; new threads are fully controllable. Must be labeled as SwitchTether's own session. | same |
| Opt-in shared mode (user changes launch) | **PARTIAL** (UNTESTED end-to-end): SwitchTether runs `codex app-server --listen ws://127.0.0.1:P` and the user launches `codex --remote ws://127.0.0.1:P` / `codex resume <id> --remote …`. Both are then clients of one server, giving real shared control. | n/a (default) |

### E.2 Codex Desktop (26.1106.6628.0, bundled codex 0.167.0-alpha.4.1, Windows)

| Capability | Status |
|---|---|
| Discover live sessions | **PARTIAL**: `state_5.sqlite.threads` / `session_index.jsonl` (all threads) plus Desktop-internal `sqlite/codex.db.active_chat_turns` (active turns) and rollout mtime. Undocumented internals; read-only. |
| Read full history | **PARTIAL**: rollout JSONL (VERIFIED structure) / `thread_history_1.sqlite`. A separate app-server's `thread/read includeTurns` failed in isolation (`list_turns is not supported yet`); not run on the real home by choice. |
| Real-time events | **PARTIAL**: rollout tail only (completed items, no deltas, no approvals) |
| Send message into existing live session | **BLOCKED**: private stdio app-server; separate app-server `thread/resume` → `-32600 already has an active writer` (VERIFIED) |
| Answer questions | **BLOCKED** |
| Approve | **BLOCKED** |
| Interrupt | **BLOCKED** |
| Concurrency control | **BLOCKED** (no shared runtime) |
| SwitchTether-owned app-server session | **VERIFIED** (as for the CLI). It may cold-resume a Desktop thread only when Desktop is **not** holding it; doing so takes the writer lock, and Desktop then cannot reopen that thread until SwitchTether unloads it. |
| ChatGPT-mobile "remote control" | Exists (`remote_control_enrollments` row enabled) but is a first-party chatgpt.com relay; **BLOCKED** for third parties (no public protocol) |

---

## F. Recommendations for SwitchTether

1. **Build one app-server JSON-RPC client in Rust.**
   - Use `tokio-tungstenite` with three transports:
     - **ws** `ws://127.0.0.1:PORT`;
     - **stdio** (a spawned `codex app-server`, newline-delimited JSON);
     - **ws-over-proxy** (spawn `codex app-server proxy [--sock P]` and run `client_async` over its stdio). This reaches the daemon control socket on Windows.
   - Send `initialize` with `clientInfo.name = "switchtether"` and `capabilities.experimentalApi = true`. Use `optOutNotificationMethods` to drop `item/reasoning/textDelta`, the `thread/realtime/*` notifications and similar if bandwidth matters.
2. **Implement against:**
   - Requests: `initialize`/`initialized`, `thread/list` (explicit `sourceKinds`, `useStateDbOnly:true` for no-write listing), `thread/loaded/list`, `thread/read`, `thread/resume`, `thread/start`, `thread/unsubscribe`, `turn/start`, `turn/steer`, `turn/interrupt`, and optionally `thread/turns/list`, `thread/name/set`, `model/list`, `account/rateLimits/read`.
   - Server requests: `item/commandExecution/requestApproval`, `item/fileChange/requestApproval`, `item/permissions/requestApproval`, `item/tool/requestUserInput`, `mcpServer/elicitation/request`.
   - Notifications: `thread/started`, `thread/status/changed`, `thread/closed`, `thread/name/updated`, `thread/tokenUsage/updated`, `turn/started`, `turn/completed`, `turn/diff/updated`, `turn/plan/updated`, `item/started`, `item/completed`, `item/agentMessage/delta`, `item/commandExecution/outputDelta`, `item/fileChange/outputDelta`, `serverRequest/resolved`, `account/rateLimits/updated`, `error`, `warning`.
3. **Attach modes, in order of honesty:**
   - **Shared daemon attach.** Use it when `~/.codex/app-server-control/app-server-control.sock` exists and `codex app-server daemon version` reports a running server (CLI ≥0.157). This is real in-place attach with no launch change. Always handle `serverRequest/resolved`, since the TUI may answer first.
   - **SwitchTether-owned session.** Label it clearly in the UI.
   - **Opt-in shared launch.** For 0.137, document `codex --remote ws://127.0.0.1:P`.
   - **Observe-only.** Tail rollouts and the state DB for CLI-embedded and Desktop sessions. Label this "read-only mirror".
4. **Do not:**
   - try to acquire `thread-writer-locks/*` (it can block the owner);
   - cold-`thread/resume` a thread the user may be using in Desktop or a TUI without a clear warning;
   - start the Windows daemon on the user's behalf (it is detached and auto-updates) without consent;
   - rely on `thread/read` status across processes (it reports `notLoaded` for threads live elsewhere).
5. **Version-gate.** Read `initialize.userAgent` and the rollout `session_meta.cli_version`. Older app-servers mis-read newer rollouts (0.137 vs 0.167 here).
6. **Security.**
   - Loopback ws has no auth by default, so any local process can drive Codex. When SwitchTether spawns its own listener, use `--ws-auth capability-token --ws-token-file <file>`.
   - The phone must reach Codex only through SwitchTether's authenticated relay, never by exposing the app-server port. Browser clients are blocked anyway by the `Origin` rejection.

## G. Artifacts

These are scratch-only and not committed:
- `…/scratchpad/research/codex-schema{,-exp,-ts}/`: generated schema (2.5 MB, over the 2 MB limit, so not copied to `docs/research/codex-app-server-schema/`).
- `…/scratchpad/research/exp/probe.mjs` and `*.log`: experiment harness and logs.
- `…/scratchpad/research/gh/`: fetched upstream sources.
