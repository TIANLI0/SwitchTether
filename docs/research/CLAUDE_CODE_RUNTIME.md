# Claude Code runtime: can SwitchTether attach to an already-running session?

Research date: 2026-11-14. Host: Windows 11 Pro for Workstations 10.0.26340.

| Component | Version observed |
| --- | --- |
| `claude` on PATH (`~/.local/bin/claude.exe`, native PE32+ build) | 2.1.282 |
| VS Code (`code --version`) | 1.146.0 |
| VS Code extension `anthropic.claude-code` | 2.1.323 (`win32-x64`; an older 2.1.322 folder is also present) |
| CLI bundled inside the extension (`resources/native-binary/claude.exe`) | 2.1.323 |
| Agent SDK bundled in the extension (`CLAUDE_AGENT_SDK_VERSION`) | 0.3.323 |
| npm `@anthropic-ai/claude-agent-sdk` / `@anthropic-ai/claude-code` | 0.3.323 / 2.1.323, license field `SEE LICENSE IN README.md` (proprietary Anthropic terms, not OSS) |

## Safety and method

- **What I did:** `--help` output, reading installed files (`package.json`, `extension.js`, the `webview/index.js` bundle), byte-searching the bundled `claude.exe` for its embedded JS, reading process command lines, and running `claude agents --json`.
- **Reading `~/.claude`:** I read it without changing it, and recorded only structure and field names. No tokens, emails, conversation text or project names are reproduced here.
- **One headless launch:** I ran a single `claude -p --input-format stream-json` probe that sent only an `initialize` control request.
  - It made no model call. I used `--safe-mode`, `--strict-mcp-config` and `--no-session-persistence`.
  - The raw output contained account fields, so I deleted it.
  - Running the CLI may have let Claude Code update its own bookkeeping in `~/.claude.json`. The two live VS Code sessions also write that file continuously. No settings, hooks, plugins or MCP config were changed.
- **Not done:** nothing was injected into either live session. No process was killed. No hooks or plugins were installed. Nothing was written under `~/.claude/ide`.
- **Live sessions at the time:** two VS Code extension sessions. One of them is the session that ran this research. There were no terminal-CLI sessions, so the terminal-CLI-specific rows below rely on the shared code path and the docs, and say so.

---

## TL;DR

1. **No supported or documented way exists to take over an interactive session that someone else started**, either a terminal TUI or the VS Code panel. "Take over" here means injecting a message as the user, answering a permission prompt or question, or interrupting.
   - Every control channel is owned by the process that hosts the session:
     - the terminal TTY;
     - the VS Code extension's private stdin/stdout pipe (`--permission-prompt-tool=stdio`);
     - Anthropic's own Remote Control relay, used by claude.ai/code and the Claude app.
   - Remote Control is also **disabled by org policy on this account** (see A.2).
2. **Read-only observation works well and is partly supported:**
   - `claude agents --json` is documented as *"the supported way to read session state from outside Claude Code."* It lists live interactive sessions, including VS Code ones, with `pid`, `sessionId`, `cwd`, `name`, `status` (`busy` / `idle` / `waiting`) and `waitingFor`.
   - The transcript JSONL under `~/.claude/projects/<cwd>/<sessionId>.jsonl` is appended within about a second and can be tailed. The docs call its format *"internal… changes between versions"*.
   - The Agent SDK's `listSessions()` / `getSessionMessages()` are the documented readers.
3. **The one documented inbound path into a live session is cross-session messaging.**
   - It is a per-session named pipe `\\.\pipe\LOCAL\cc-msg-<32hex>`. An authenticating key sits in `~/.claude/sessions/<pid>.<sha256>.key`.
   - It is on by default in v2.1.234+ on Windows, and the VS Code sessions here have it.
   - A message arrives labeled as coming **from another session, not from the user**. It *"can't approve anything"*, slash commands don't run, and the receiving session's inbound rules can hold or drop it.
   - That makes it at best a "nudge/note" channel, not a takeover. **UNTESTED live:** I deliberately did not inject anything.
4. **Channels**, a research preview, *can* push messages and relay permission prompts (`claude/channel/permission`), but only for sessions **launched** with `--channels …` (allowlisted plugin) or `--dangerously-load-development-channels …`. They do not help with existing sessions or with the VS Code panel.
5. **The robust path is for SwitchTether to launch and own the session.** It does this through the Agent SDK or `claude -p --input-format stream-json --output-format stream-json --permission-prompt-tool stdio`, which is exactly what the VS Code extension does (verified from the live command lines).
   - It gets full control: send messages, `can_use_tool` permission and question round-trips, `interrupt`, `set_permission_mode`, `set_model`, `--resume <id>` / `--fork-session`.
   - It must be labeled as a **new SwitchTether-owned session**, or as a resumed copy of a session that is no longer live.

---

## A. CLI surface and official features

### A.1 `claude --help` (2.1.282): flags relevant to SwitchTether

```text
$ claude --version
2.1.282 (Claude Code)

-p, --print                    Print response and exit
--input-format <format>        (only works with --print): "text" (default), or "stream-json" (realtime streaming input)
--output-format <format>       (only works with --print): "text", "json", or "stream-json"
--include-partial-messages     Include partial message chunks (only with --print and --output-format=stream-json)
--include-hook-events          Include all hook lifecycle events in the output stream (stream-json only)
--replay-user-messages         Re-emit user messages from stdin back on stdout (stream-json in+out)
--permission-prompts <target>  Who answers permission prompts with --print: "host" (the SDK host or --permission-prompt-tool) or "none"
--permission-mode <mode>       acceptEdits, auto, bypassPermissions, manual, dontAsk, plan
-r, --resume [value]           Resume a conversation by session ID, or open interactive picker
--session-id <uuid>            Use a specific session ID for the conversation
--fork-session                 When resuming, create a new session ID instead of reusing the original
-c, --continue                 Continue the most recent conversation in the current directory
--no-session-persistence       Sessions will not be saved to disk (only with --print)
--remote-control [name]        Start an interactive session with Remote Control enabled
--bg, --background             Start the session in the background ... `claude agents` lists them.
                               With --resume <session-id>, continues that session in the background under the
                               same ID, or starts a copy and says so when the session is already running
--ide                          Automatically connect to IDE on startup if exactly one valid IDE is available
--settings <file-or-json>      Path to a settings JSON file or a JSON string to load additional settings from
--safe-mode / --bare / --restricted   (isolation modes)
Commands: agents, attach <id>, logs <id>, stop|kill <id>, respawn, rm <id>, mcp, plugin, project, ...
```

**Hidden flags.** These were found in the binary or the docs and are not in `--help`:

- `--permission-prompt-tool` (`stdio` or an MCP tool name). Visible in the extension's spawn args.
- `--sdk-url <url>`: *"Use remote WebSocket endpoint for SDK I/O streaming (only with -p and stream-json format)"*, which is `.hideHelp()` in the bundled CLI.
- `--channels`.
- `--dangerously-load-development-channels`.
- `--messaging-socket-path`.
- `--session-mirror`, `--await-initialize`, `--resume-session-at`, `--project-config-root`. All of these are launch-time options for a **new** process.

There is no `claude ide` subcommand; `claude ide --help` just prints the root help. IDE connection is the `/ide` slash command or `--ide`.

`claude mcp serve` starts Claude Code's **tools** as an MCP server in a new process. It does not expose or control an existing conversation.

### A.2 Remote Control

**Evidence on this account:**

```text
$ claude remote-control --help
Error: Remote Control is disabled by your organization's policy. Contact your organization admin for access.

~/.claude/policy-limits.json (keys only):
  restrictions.allow_remote_control.allowed = false
  defaults.remote_control_at_startup = false
```

The stream-json `initialize` response nonetheless reported `remote_control_available: true` and `remote_control_auto_enable: false`, so that availability flag does not reflect the org policy.

**Docs (code.claude.com/docs/en/remote-control):**

- **What it is:** it *"connects claude.ai/code or the Claude app for iOS and Android to a Claude Code session running on your machine."*
- **Plans:** *"Pro, Max, Ultra, Team, and Enterprise plans. API keys are not supported. On Team and Enterprise, an Owner must first enable the Remote Control toggle."*
- **Not available** with Bedrock/Vertex/Foundry, or when `ANTHROPIC_BASE_URL` points at a non-Anthropic gateway. The second point matters for CC-Switch users who route through proxies.
- **Opt-in:**
  - Per session: `claude remote-control` (server mode), `claude --remote-control`, or `/remote-control` (`/rc`) inside an existing session. That includes the VS Code extension, where it is *"type `/remote-control` or `/rc` in the prompt box"*.
  - Globally: *"Enable Remote Control for all sessions"* (`remoteControlAtStartup`), a toggle in `/config` and in the VS Code command menu.
  - *"Remote Control only activates when you explicitly run … unless auto-connect is turned on."*
- **Transport:** *"Your local Claude Code session makes outbound HTTPS requests only and never opens inbound ports… registers with the Anthropic API and polls for work… All traffic travels through the Anthropic API over TLS."* The transcript is stored on Anthropic servers while connected.
- **In the extension:** it is toggled through an SDK control request, `{subtype:"remote_control", enabled, name, reattach_session_id, keep_session_on_exit}`. Only the host on the private pipe can send that request.
- **Third-party use:**
  - There is no documented third-party API or protocol. Clients are claude.ai/code and the official Claude app only.
  - SwitchTether cannot be a Remote Control client.
  - The most it can do is tell the user to enable Remote Control and use the official Claude app. That is a different product path, and it is blocked here by policy.

### A.3 Channels (research preview)

Docs: code.claude.com/docs/en/channels and channels-reference.

- **What they are:** *"A channel is an MCP server that pushes events into your running Claude Code session… Channels can be two-way."* *"Channels are in research preview… `--channels` flag syntax and protocol contract may change."*
- **Opt-in:**
  - The session must be **launched** with `claude --channels plugin:<name>@<marketplace>`, where the plugin is on Anthropic's allowlist or the org's `allowedChannelPlugins`.
  - For custom servers, it must be launched with `claude --dangerously-load-development-channels server:<name>` or `plugin:<name>@<mkt>`. That flag shows a confirmation dialog.
  - *"In all cases, no channel runs until a user opts it in for the session with `--channels`."*
  - *"Being in `.mcp.json` isn't enough to push messages: a server also has to be named in `--channels`."*
- **Org gate:** `channelsEnabled`. It is blocked by default on claude.ai Team/Enterprise and allowed for Pro/Max/Ultra users without an org. It requires claude.ai or Console auth, and is not available on Bedrock, Vertex or Foundry.
- **Protocol:**
  - The server declares `capabilities.experimental['claude/channel'] = {}`.
  - It pushes `notifications/claude/channel` with `{content: string, meta?: Record<string,string>}`. The message arrives as `<channel source="…" …>` text. Claude Code does not acknowledge it.
- **Permission relay:**
  - The server declares `capabilities.experimental['claude/channel/permission'] = {}`.
  - Claude Code sends `notifications/claude/channel/permission_request {request_id (5 letters a–z without l), tool_name, description, input_preview}`.
  - The server answers with `notifications/claude/channel/permission {request_id, behavior: 'allow'|'deny'}`.
  - *"The local terminal dialog stays open… Claude Code applies whichever answer arrives first."*
  - Relay covers tool approvals only. *"Project trust and MCP server consent dialogs don't relay."*
- **Surfaces:**
  - The dev flag is ignored in `-p` and Agent SDK mode: *"If you pass it in non-interactive mode with -p or through the Agent SDK, Claude Code ignores the flag."*
  - In `-p` mode, multiple-choice questions and plan approval are disabled.
  - The extension's bundled SDK can pass `--channels` (an `options.channels` array, seen in `extension.js`), but no VS Code setting exposes it.
- **Verdict:**
  - Channels can't attach to a session that is already running.
  - They are a legitimate **opt-in** for terminal-CLI sessions that the user *launches* with a SwitchTether channel plugin.
  - Even then, the plugin needs the dev flag unless Anthropic or an org admin allowlists it.
  - They give message push plus permission relay. They do not give AskUserQuestion answers or interrupt.

### A.4 Cross-session messaging: the only built-in inbound path into a live session

Docs: code.claude.com/docs/en/cross-session-messaging.

**Availability and transport**
- On by default from v2.1.234 on native Windows: *"When a session meets the requirements, messaging is on with nothing to enable."*
- *"On this machine: over a per-session socket on macOS and Linux, or a per-session named pipe on native Windows, never through Anthropic servers."*

**Documented script use**
- The section *"The session's inbox socket"* covers this case: *"…when you want a script or hook to post into a session…"*
- *"On native Windows, it instead requires each connection to authenticate first with a key that only your operating-system user can read."*
- `CLAUDE_CODE_MESSAGING_SOCKET` and `CLAUDE_CODE_MESSAGING_TOKEN` are exported to the session's own hooks and Bash children.

**Semantics** (*"How a session treats an incoming message"*)
- *"It can't approve anything: a message from another session never counts as your consent, so it can't answer a pending permission prompt on your behalf."*
- *"Commands don't run."*
- *"Permission prompts still fire."*
- Delivery happens *"between tool calls during an active turn. When the receiving session is idle, Claude Code starts a new turn with the message."* It counts toward usage.

**Inbound gating** (`crossSessionInbound`: `accept` / `hold` / `refuse`)
- The default depends on permission-mode class.
- A prompting-mode receiver (auto, acceptEdits, default, dontAsk) delivers.
- A bypass-mode receiver holds.
- *"A session in the VS Code extension or the Desktop app can't show the dialog"*, so held messages there expire after `dialogExpiry`, which defaults to 5 min.

**Local evidence:**

```text
# Env of a command run inside the live VS Code session (values redacted):
CLAUDE_CODE_ENTRYPOINT=claude-vscode
CLAUDE_CODE_MESSAGING_SOCKET=\\.\pipe\LOCAL\cc-msg-<32hex>
CLAUDE_CODE_MESSAGING_TOKEN=<redacted>      # "childToken": own-child auth only
CLAUDE_CODE_SESSION_ID=<uuid>
CLAUDE_AGENT_SDK_VERSION=0.3.323

# ~/.claude/sessions/<pid>.<sha256(pipePath)>.key  -> JSON keys: peerToken (32 hex), procStartFt, pidDomain
#   Peers read peerToken from this file and send it as the first line (auth) when connecting.
```

**Wire format.** The bundled CLI prints this recipe in its own debug log (`[uds-messaging] Inject messages (auth line REQUIRED here…)`):

```text
{"type":"auth","token":"<token>"}\n
{"type":"user","message":{"role":"user","content":"hello"}}\n
```

A connection must send a complete line within the first-line deadline, documented as about 30 s.

**Assessment**
- A same-user external process could technically locate a session's pipe from `~/.claude/sessions/<pid>.json` (`messagingSocketPath`) and authenticate with that session's `peerToken`. It could then post a text note.
- That note is labeled as from another session, cannot approve anything, and runs no slash commands.
- It can be held or refused, and could surprise the user, since it starts a model turn and costs usage.
- Reading another session's `peerToken` from a file is relying on internal storage. The docs only describe the env-var token for a session's own children.
- **Classification:** PARTIAL, documented feature with an internal key file. UNTESTED live, by design.
- If SwitchTether ever uses it, it must be an explicit, per-session user action ("send note to session"). It must not be presented as "send as me".

### A.5 Background sessions (`--bg`, `claude agents`, `attach`)

- **Agent view** (docs: agent-view) lists background sessions only.
  - Interactive sessions appear only after the user backgrounds them (`/bg`).
  - `claude agents --json` prints "every live session", including interactive ones. Fields: `cwd`, `kind`, `startedAt`, `pid`, `status` (`busy|waiting|idle`), `waitingFor` (`permission prompt|input needed|sandbox request|worker request|dialog open`), `sessionId`, `name`, plus `id` and `state` for background sessions.
  - *"`claude agents --json` is the supported way to read session state from outside Claude Code."*
- **Sending to a running background session** is documented: `claude --resume <session> "prompt"` *"goes to the session as its next turn"*.
  - Permission prompts still need a TTY `claude attach`: *"Your message waits in the queue. To answer the dialog, attach."*
  - This only applies to sessions started with `--bg` or moved there with `/bg`, which means a change in how the user launches. It does not apply to VS Code panel sessions.

**Live evidence** (PATH CLI 2.1.282 reading sessions registered by the 2.1.323 extension CLI):

```text
$ claude agents --json      # (cwd/name/sessionId values omitted)
[ {"pid":int,"cwd":str,"kind":"interactive","startedAt":int,"sessionId":str,"name":str,"status":"busy"},
  {"pid":int,"cwd":str,"kind":"interactive","startedAt":int,"sessionId":str,"name":str,"status":"busy"} ]
```

---

## B. VS Code extension internals (anthropic.claude-code 2.1.323)

### B.1 Manifest

- `main: ./extension.js`. `activationEvents: ["onStartupFinished", "onWebviewPanel:claudeVSCodePanel"]`. `engines.vscode ^1.94.0`.
- Contributes `configuration`, `commands`, `keybindings`, `viewsContainers`, `views` (webviews `claudeVSCodeSidebar`, `claudeVSCodeSidebarSecondary` and `claudeVSCodeSessionsList`), `walkthroughs` and `menus`.
- **Commands** (all UI-level):
  - `claude-vscode.editor.open`, `.editor.openLast`, `.primaryEditor.open`, `.window.open`, `.sidebar.open`, `.newConversation`, `.reopenClosedSession`, `.focus`, `.blur`, `.focusLastMessage`.
  - `.acceptProposedDiff` / `.rejectProposedDiff` / `.acceptProposedHunk` / `.rejectProposedHunk` (+ `FromBar`), `.insertAtMention`, `.installPlugin`, `.showLogs`, `.toggleFocusView`.
  - `.markSessionUnread`, `.renameSessionTab`, `.addSessionTabToGroup`, `.logout`, `.update`, `.terminal.open`, `.openWalkthrough`.
  - Legacy `claude-code.acceptProposedDiff`, `claude-code.rejectProposedDiff`, `claude-code.insertAtMentioned`.
  - None takes a message to send or a permission decision. The accept/reject diff commands act on the editor's proposed-diff view, not on the chat permission prompt.
- **Notable settings:**
  - `claudeCode.claudeProcessWrapper`: *"Executable path used to launch the Claude process. The bundled binary path is passed as an argument when present."*
  - `claudeCode.useTerminal`, `claudeCode.initialPermissionMode`, `claudeCode.environmentVariables`, `claudeCode.continueAfterReload`.

### B.2 No public extension API

- The `activate` function (`Ywr`) ends with `Xwr(e)}` and returns nothing. `module.exports` exposes only `{activate, deactivate}`.
- So `vscode.extensions.getExtension('anthropic.claude-code').exports` is `undefined`. No `getApi` or exported API object was found.

### B.3 URI handler (documented)

`registerUriHandler` handles two paths:

- `/install-plugin?plugin=&marketplace=` opens the plugin dialog.
- `/open?session=<id>&prompt=<text>` runs `claude-vscode.primaryEditor.open(session, prompt)`.
  - The webview then calls `setInputText(initialPrompt)`, which **pre-fills, does not send**.
  - Docs: *"The prompt is pre-filled but not submitted automatically… If the session is already open in a tab, that tab is focused."*
  - **Classification:** PARTIAL. SwitchTether on the PC could open or focus a session and pre-fill text, but a human must press Enter at the PC. It is useless from a phone alone.

### B.4 How the panel drives the CLI

**Live process command lines** (both VS Code sessions are identical; the parent is the VS Code extension-host `Code.exe` NodeService utility process):

```text
...\anthropic.claude-code-2.1.323-win32-x64\resources\native-binary\claude.exe
  --output-format stream-json --verbose --input-format stream-json --max-thinking-tokens=31999
  --permission-prompt-tool=stdio --setting-sources=user,project,local --permission-mode=auto
  --allow-dangerously-skip-permissions --include-partial-messages --debug --debug-to-stderr
  --enable-auth-status --no-chrome --replay-user-messages
```

**The spawn code.** `extension.js` bundles the Agent SDK `ProcessTransport`:

- It builds `["--output-format","stream-json","--verbose","--input-format","stream-json", …]`.
- When a `canUseTool` callback exists, it pushes `--permission-prompt-tool=stdio` (`"canUseTool callback cannot be used with permissionPromptToolName"`).
- It can also pass `--resume`, `--continue`, `--fork-session`, `--session-id`, `--channels`, `--session-mirror`, `--mcp-config` and so on.

**Control protocol on that private pipe** (subtypes seen in `extension.js`):

- **Host to CLI:** `initialize`, `interrupt`, `set_permission_mode`, `set_model`, `set_max_thinking_tokens`, `remote_control`, `rewind_files`, `stop_task`, `mcp_*`, `get_context_usage`, `rename_session`, `generate_session_title`, `export_conversation`, `reload_plugins`, `reload_skills`, `read_file`, `seed_read_state`, `side_question`, `update_settings`, `get_settings`, `get_usage`, `ui_*`, and more.
- **CLI to host requests:** `can_use_tool` (permission prompts and AskUserQuestion), `elicitation`, `hook_callback`, `request_user_dialog`, `mcp_message`, `oauth_token_refresh`, `host_auth_token_refresh`, `remote_control_work_secret`, `ui_prompt_*`, `ui_read_selection`, `ui_copy`.

**Consequence.** The stdin and stdout of the panel's `claude.exe` are anonymous pipes owned by the extension host. No third-party process can write user messages, answer `can_use_tool`, or send `interrupt` into them. Neither the docs nor the code offer another way in: *"The page does not describe any API or mechanism for other apps to control or send messages to running extension sessions."*

**`claudeProcessWrapper` (opt-in, changes how VS Code launches).** If the user set this to a SwitchTether shim, the shim would sit between the extension and `claude.exe` and could observe or inject on the stream-json/control channel. This is technically a man-in-the-middle, needs a VS Code settings change, and is undocumented as a control point. Some features change when it is set: the "open elsewhere" check is skipped and plan mode is not restored on resume. Classification: possible opt-in, **not recommended**, and not an "attach without changing launch".

### B.5 IDE integration (CLI-in-terminal ↔ extension)

`~/.claude/ide/<port>.lock`: 56 files present, most of them stale because their pid is dead. Structure, with the token redacted:

```json
{"pid": <VS Code pid>, "workspaceFolders": [<str>...], "ideName": "Visual Studio Code",
 "transport": "ws", "runningInWindows": true, "authToken": "<36-char uuid, redacted>"}
```

**Server** (from `extension.js` and the docs):

- A WebSocket MCP server on `127.0.0.1` at a random port (10000–65535), plain `ws://`.
- It requires the header `x-claude-code-ide-authorization: <authToken>`, otherwise it closes with 1008 "Unauthorized".
- **It accepts one client at a time.** A new connection logs `"Disconnecting previous WebSocket client"` and closes the existing one, so a third-party connection would knock the real CLI off.
- Tools: `openDiff`, `getDiagnostics`, `getOpenEditors`, `getWorkspaceFolders`, `getCurrentSelection`, `getLatestSelection`, `checkDocumentDirty`, `saveDocument`, `close_tab`, `closeAllDiffTabs`, `executeCode`, `openFile`.
- Notifications to the CLI: `selection_changed`, `at_mentioned`, `diagnostics_changed`.
- The docs say the model only sees `mcp__ide__getDiagnostics` and `mcp__ide__executeCode`. The rest is *"internal RPC the CLI uses for its own UI"*.

**Direction.** The server is IDE-side tools offered *to* the CLI. It is not a conversation channel. A SwitchTether "fake IDE" would mean writing a lock file into `~/.claude/ide`. The CLI would then call `openDiff` on SwitchTether, which could accept or reject edit diffs. That relies on an undocumented internal RPC, is single-client, and needs the CLI to opt in (`/ide`, `--ide`, or launched from the IDE terminal). **Rejected.**

The panel itself uses a separate in-process SDK MCP server, `claude-vscode`, for example `mcp__claude-vscode__getDiagnostics`. It is not reachable externally.

---

## C. Session files and live-session registry

### C.1 Transcripts

The docs (sessions) say:

- Transcripts live at `~/.claude/projects/<project>/<session-id>.jsonl`, where `<project>` is the cwd with non-alphanumerics replaced by `-`. Here that is `d--develop-SwitchTether` for `d:\develop\SwitchTether`. Names longer than 200 characters are truncated and hashed.
- Default retention is 30 days (`cleanupPeriodDays`).
- *"The entry format is internal to Claude Code and changes between versions, so scripts that parse these files directly can break on any release."*
- *"If you resume the same session in two terminals without forking, messages from both interleave into one transcript."* So never `--resume` a session while it is live without `--fork-session`.
- *"The extension and CLI share the same conversation history."*

**What was surveyed.** I extracted the schema by key names and types only:

- The last 40 transcripts plus 10 subagent transcripts: 58,939 lines.
- Counts and entrypoints across all 226 local transcripts.
- Every local transcript has `entrypoint: "claude-vscode"`. A terminal CLI writes the same records with `entrypoint: "cli"`; that row was not observed here.
- Versions seen: 2.1.29x–2.1.32x.

**Live append (VERIFIED).** The active session's file grew from 806 KB to 1.69 MB during this research. The newest entries were less than 1 s old: `assistant[thinking]`, then `assistant[tool_use]`, then `user[tool_result]`, then `api-request*`.

**Top-level `type` values observed:**

| type | key fields (names only) |
| --- | --- |
| `user` | `uuid`, `parentUuid`, `sessionId`, `timestamp`, `cwd`, `gitBranch`, `version`, `entrypoint`, `userType`, `isSidechain`, `agentId`, `slug`, `message{role,content}`, `toolUseResult`, `sourceToolAssistantUUID`, `promptId`, `permissionMode`, `permissionDecision`, `toolDenialKind`, `isMeta`, `isCompactSummary`, `origin`, `turnOrigin`, `promptSource`, `interruptedByShutdown`, `imagePasteIds`, … |
| `assistant` | same envelope plus `message{id,type:"message",role,model,content,stop_reason(tool_use/end_turn/stop_sequence),stop_sequence,stop_details,usage,container,context_management,diagnostics}`, `requestId`, `apiBlockIndex`, `effort`, `thinkingDurationMs`, `isApiErrorMessage`, `apiError*`, `attributionAgent`, `attributionMcpServer`, `attributionMcpTool`, … |
| `system` | subtypes `compact_boundary` (`compactMetadata`, `logicalParentUuid`), `api_error` (`retryAttempt`, `retryInMs`, `maxRetries`), `informational`; plus `level`, `content` |
| `attachment` | envelope plus `attachment{type,…}` where `type` is one of: `environment`, `model`, `deferred_tools_delta`, `agent_listing_delta`, `mcp_instructions_delta`, `skill_listing`, `auto_mode`, `auto_mode_exit`, `instructions`, `session_context`, `date`, `credential_org`, `remote_session_change`, `prompt_snapshot`, `silent_turn_reminder`, `deferred_tools_record`, `hook_additional_context`, `edited_text_file`, `queued_command`, `read_truncation_notice`, `diagnostics`, `compact_file_reference`, `file`, `thinking_drop`, `task_status` |
| `queue-operation` | `operation` (`enqueue`/`dequeue`/`remove`), `content`, `commandUuid`, `deliveryId`, `reason`, `timestamp` (queued user prompts) |
| `last-prompt` | `lastPrompt`, `leafUuid` |
| `ai-title` | `aiTitle` |
| `mode` | `mode` (permission mode changes) |
| `cost-state` | `totalCostUSD`, `modelUsage`, `totalDuration`, `totalAPIDuration`, `totalLinesAdded`, `totalLinesRemoved`, … |
| `file-history-snapshot` / `file-history-delta` | `messageId`, `snapshot` / `backup`, `trackingPath` |
| `api-request`, `api-request-blob`, `api-request-shape` | wire-level request records (`params`, `shapeHash`, `requestDigest`, `message`) |
| `session-owner` | `accountUuid`, `organizationUuid` |
| `pr-link` | `prNumber`, `prRepository`, `prUrl` |
| misc | `tools-used`, `atis-latch`, `isolation-latch`, `frame-link`, `artifact-comment-monitor`, `artifact-autoreact-ledger` |

There was no legacy `summary` type in any local file. Older CLI versions wrote `{"type":"summary","summary","leafUuid"}`, so parsers should accept it.

**`message.content` block types:**

- `user`: `text`, `image{source}`, `tool_result{tool_use_id, content, is_error?}`, or a plain string.
- `assistant`: `text`, `thinking{thinking, signature}`, `tool_use{id, name, input, caller}`.

**`toolUseResult` shapes** (most common key sets):

- Bash: `{stdout, stderr, interrupted, isImage, noOutputExpected, backgroundTaskId?, returnCodeInterpretation?, gitOperation?, bashEditDiff?}`.
- Edit: `{filePath, oldString, newString, originalFile, structuredPatch, userModified, replaceAll}`.
- Write: `{type, filePath, content, structuredPatch, originalFile}`.
- Read: `{type:"text"|…, file}`.
- Grep/Glob: `{mode, filenames, numFiles, numLines, …}`.
- Agent: `{agentId, status, outputFile, isAsync, resolvedModel, …}`.
- AskUserQuestion: `{questions, answers}`.
- ToolSearch: `{matches, query, total_deferred_tools}`.
- Some results are plain strings or arrays.

**Subagents:** `~/.claude/projects/<proj>/<sessionId>/subagents/agent-<id>.jsonl` and `.meta.json`. Entries have `isSidechain: true` and `agentId`.

**Missing signal.** The transcript has **no explicit "waiting for permission" record**. A pending approval shows up only as a `tool_use` with no `tool_result` yet, and that is ambiguous with a tool that is simply still running.

**Supported alternative.** Agent SDK `listSessions({dir, limit, includeWorktrees})` and `getSessionMessages(sessionId, {dir, limit, offset, includeSystemMessages})` are documented, but UNTESTED here.

### C.2 Live-session registry: `~/.claude/sessions/`

One file per running Claude Code process, written by the CLI's "concurrentSessions" module and deleted on exit. The fields below are copied from the live files and from the writer and reader code in the binary:

```text
<pid>.json:
  pid, sessionId, cwd, startedAt, procStart, version, kind ("interactive"|"bg"|…),
  entrypoint ("claude-vscode" here; "cli" for terminal), pidDomain ("win32:<host>"),
  peerProtocol (1), peerFeatures (["notify_idle"]), messagingSocketPath (\\.\pipe\LOCAL\cc-msg-<32hex>),
  name, nameSource, nameSince, status ("busy"|"idle"|"waiting"), waitingFor, updatedAt, statusUpdatedAt,
  [optional per reader code: hostSessionId, bridgeSessionId, logPath, agent, jobId, parkedJobId, spare,
   state, detail, tempo ("active"|"idle"|"blocked"), needs, tmux, formerNames, asksForConvertedSkills]
<pid>.<sha256(pipePath)>.key:   { peerToken, procStartFt, pidDomain }   # auth key for the messaging pipe (secret)
```

**`waitingFor` values** (code): `"input needed"`, `"permission prompt"` (default for a pending tool dialog), `"sandbox request"`, `"worker request"`, `"dialog open"`. For background sessions, `needs` reads like *"approve <tool>: <description> — attach to respond"*.

**Caveat for VS Code.** The `waiting` computation found in the binary lives in the interactive TUI component (`topDialogWaitingFor`). In VS Code sessions, permission prompts are SDK `can_use_tool` requests answered by the panel. I did not confirm whether those flip the registry `status` to `waiting`; both live sessions only ever showed `busy`. **UNTESTED** for the extension.

**Other files.**
- **Read in this study:**
  - `~/.claude/projects/<proj>/memory/`.
  - `~/.claude/session-env/<sessionId>/`.
  - `~/.claude/file-history/<sessionId>/`.
  - `~/.claude/shell-snapshots/`.
  - `~/.claude/daemon/pipe.key`, the background-supervisor pipe key. Not used.
  - `~/.claude/history.jsonl`, the prompt history.
- **Not present** on this machine: `~/.claude/todos`, `~/.claude/plans` and `~/.claude/tasks`. Older versions wrote todos and plans there, and the current one may write `~/.claude/jobs/<id>/` for background sessions. The docs say the `jobs` files are *"not a stable interface"*.

---

## D. Permission prompts in a running session: who can see and answer them?

| Mechanism | See that a prompt is pending | Answer it | Works on existing session? | Notes |
| --- | --- | --- | --- | --- |
| `claude agents --json` | Yes (`status:"waiting"`, `waitingFor:"permission prompt"`) | No | Yes for terminal sessions per docs; UNTESTED for VS Code | Supported, read-only. Poll it. |
| `~/.claude/sessions/<pid>.json` | Same data | No | Same | Internal file; prefer `agents --json`. |
| JSONL tail | Indirect (unpaired `tool_use`) | No | Yes | Ambiguous. |
| Cross-session message | No | **No** (*"can't approve anything"*) | Yes | Documented. |
| Remote Control | Yes, in claude.ai/Claude app | Yes, first-party app only | Only if user runs `/remote-control` (or auto-connect); **policy-blocked here** | No third-party API. |
| Channels permission relay | Yes (`permission_request`) | Yes (`permission` verdict) | **No**, needs launch with `--channels`/dev flag; terminal interactive only | Research preview; allowlist. |
| Hooks (`PermissionRequest`, `Notification` with `permission_prompt`) | Yes | Yes (`hookSpecificOutput.decision.behavior`) | Only for sessions started after the hook is configured | `type:"http"` hooks exist. Must **not** be auto-installed into `~/.claude/settings.json`; acceptable only via `--settings` on a SwitchTether-launched process, or explicit user opt-in. |
| Agent SDK `canUseTool` / `--permission-prompt-tool stdio` | Yes (`can_use_tool` control request) | Yes (allow/deny, `updatedInput`, `updatedPermissions`; AskUserQuestion answers via `updatedInput.answers`) | **No**, only for the process SwitchTether spawned | This is how the VS Code panel does it. |

---

## E. Capability matrix

Legend:

- **VERIFIED**: observed on this machine.
- **PARTIAL**: works with significant limits, or documented but only partly observed.
- **BLOCKED**: no supported way, or forbidden by policy.
- **UNTESTED**: documented, not exercised.

### E.1 Claude Code CLI (terminal / TUI session the user started normally)

| Capability | Status | Evidence / mechanism |
| --- | --- | --- |
| Discover live sessions | **VERIFIED** (mechanism) | `claude agents --json`, documented as *"the supported way to read session state from outside Claude Code"*, plus `~/.claude/sessions/<pid>.json`. Both live rows observed here were extension-hosted. Terminal sessions use the same registry writer (`kind:"interactive"`, `entrypoint:"cli"`) but none was running to observe. |
| Read full history | **VERIFIED** (read-only) | JSONL schema above (shared with the extension, per the docs). Format is internal and unstable. Supported alternative: SDK `getSessionMessages()` (UNTESTED). |
| Real-time events | **PARTIAL** | JSONL tail at block granularity (<1 s observed), no token streaming. Status via polling `agents --json` (`busy/idle/waiting`). No event push to third parties. |
| Send message into existing live session | **PARTIAL / UNTESTED** | Cross-session messaging pipe: documented, arrives framed as a peer message, can be held or refused, costs a turn. Not "as the user". Remote Control: BLOCKED (policy plus first-party only). Channels: not for existing sessions. `claude --resume <id> "prompt"` works only for `--bg` background sessions. |
| Answer questions (AskUserQuestion) | **BLOCKED** | TTY dialog. Peer messages can't answer. Remote Control first-party only. |
| Approve permissions | **BLOCKED** (detect: PARTIAL) | Detect via `waitingFor:"permission prompt"`. Approve only via TTY, Remote Control, or a channel or hook configured **before launch**. |
| Interrupt | **BLOCKED** | No external interrupt API for TTY sessions. `claude stop` applies to background sessions and stops rather than interrupts. Killing or signaling the process is out of scope. |
| SwitchTether-launched controllable session (NEW session) | **VERIFIED** | `claude -p --input-format stream-json --output-format stream-json --verbose --permission-prompt-tool stdio` answered an `initialize` control request (no model call) with `commands`(58), `models`(12), `agents`(5), `current_permission_mode`, `session_state`, and so on. Full control via SDK `query()`: `streamInput`, `canUseTool`, `interrupt()`, `setPermissionMode()`, `setModel()`, `resume`/`forkSession`. |

### E.2 Claude Code VS Code extension (panel session)

| Capability | Status | Evidence / mechanism |
| --- | --- | --- |
| Discover live sessions | **VERIFIED** | `claude agents --json` listed both panel sessions (`kind:"interactive"`, `status:"busy"`). Registry `entrypoint:"claude-vscode"`. Process command lines show the extension-host parent. |
| Read full history | **VERIFIED** (read-only) | Same JSONL (all 226 local transcripts are `claude-vscode`), including `subagents/`. |
| Real-time events | **PARTIAL** | JSONL tail plus `agents --json` polling. The token stream (`--include-partial-messages`) goes only to the extension's private stdout. |
| Send message into existing live session | **PARTIAL** | (a) URI `vscode://anthropic.claude-code/open?session=<id>&prompt=<text>`: documented, pre-fills only, no send. (b) Cross-session pipe exists in panel sessions (env verified); peer-framed; held messages can't show a dialog in VS Code. UNTESTED live. No message API, no `exports`. |
| Answer questions | **BLOCKED** | `can_use_tool` (AskUserQuestion) is answered by the panel over the private stdio pipe. |
| Approve permissions | **BLOCKED** | Same pipe (`--permission-prompt-tool=stdio`). `waiting` status not confirmed for panel sessions (UNTESTED). Remote Control in VS Code is `/rc`, first-party, policy-blocked here. The `claudeProcessWrapper` shim is a possible but undocumented, settings-changing MITM, not recommended. |
| Interrupt | **BLOCKED** | `interrupt` is a host-only control request on the private pipe. |
| SwitchTether-launched controllable session (NEW session) | **VERIFIED** (same as CLI) | This is literally the extension's own architecture (live command lines). A SwitchTether-owned session does not appear in the VS Code panel as the panel's own process. It can be opened there later via the URI handler only after SwitchTether's process has ended, otherwise there is a concurrent-writer conflict ("open elsewhere"). |

### E.3 Additional rejected or blocked routes

| Route | Why rejected |
| --- | --- |
| Impersonate IDE via `~/.claude/ide/<port>.lock` | Writes into `~/.claude`, uses undocumented internal RPC, and the CLI must opt in to an IDE connection. |
| Connect to the extension's IDE WebSocket | Single client: it would disconnect the real CLI. It is IDE tools, not conversation control. |
| `claude -p --resume <live-session-id>` | Spawns a second writer and interleaves the transcript (documented). Allowed only with `--fork-session`, or for sessions not currently live per `agents --json`. |
| `--sdk-url` | Hidden flag. New-process only. Prefer documented stdio SDK transport. |
| Auto-installing hooks or channel plugins | Violates the "don't change how the user launches" and "don't modify `~/.claude`" constraints. Only an explicit, reversible user opt-in is acceptable. |

---

## F. Implications for SwitchTether design

1. **Observe mode** (zero config, works for CLI and VS Code):
   - Poll `claude agents --json` every 1–2 s to discover sessions, with status and `waitingFor`.
   - Tail `~/.claude/projects/<proj>/<sessionId>.jsonl` and `subagents/*.jsonl` for history and progress.
   - Use a tolerant parser: key on `type`, `uuid`, `parentUuid` and `message.content[].type`, and ignore unknown types. The format is versioned by Claude Code, not by us.
   - Optionally wrap the SDK's `getSessionMessages()` for a supported reader.
   - Push "Claude is waiting for you" notifications to the phone when `status:"waiting"`. The user still answers at the PC, or via the official Claude app if Remote Control is allowed.
2. **Managed mode** (full phone control):
   - "Start new session" or "Continue as SwitchTether session" launches Claude through the Agent SDK or the stream-json CLI. Prefer the user's installed `claude`, or the SDK's bundled binary.
   - Relay `can_use_tool` (permissions and AskUserQuestion), `interrupt`, mode and model changes to the phone.
   - Label these sessions as SwitchTether-owned. To continue an existing conversation, require that it is not live (`agents --json`), or use `--fork-session`.
   - SwitchTether-launched processes may receive a `PermissionRequest` HTTP hook through `--settings` JSON on *that* command line. Never write it into `~/.claude/settings.json`.
3. **Optional opt-ins**, never default:
   - **Channel plugin** with permission relay. For users who launch `claude --dangerously-load-development-channels …`, or `--channels …` once allowlisted. Research preview; terminal CLI only.
   - **"Send note to session"** via cross-session messaging. Clearly labeled as a note from another session, and it cannot approve anything.
   - **"Open in VS Code with this prompt"** via the URI handler. Pre-fill only.
4. **Licensing:**
   - Depend on `@anthropic-ai/claude-agent-sdk` as an npm dependency. Its license is "SEE LICENSE IN README.md", proprietary Anthropic terms.
   - Do not vendor or copy code from the extension bundle or the CLI binary into this MIT repo.
   - The protocol facts in this document are interoperability notes, not copied code.

---

## G. How existing open-source projects attach

The survey was done with the GitHub API. Licenses were re-checked with `gh api repos/<r>`, and the mimi-remote LICENSE text was read directly. Source paths are as reported from each repo tree.

| Repo | License | Attach mechanism | Permission handling | Relay |
| --- | --- | --- | --- | --- |
| slopus/happy | **MIT** (root; `packages/happy-cli`, `happy-server`, `happy-wire` MIT) | **Launch wrapper** `happy claude`. Local mode spawns the real interactive `claude` (`packages/happy-cli/src/claude/claudeLocal.ts`), tails `~/.claude/projects` JSONL (`utils/sessionScanner.ts`), and injects a temporary `--settings` with a SessionStart hook (`utils/generateHookSettings.ts`). Remote mode uses Agent SDK `query()` (`claudeRemote.ts`). Cannot take over a session started outside `happy`. | SDK permission callback forwarded to phone (`utils/permissionHandler.ts`) | Own `happy-server`, E2E encrypted, self-hostable |
| happier-dev/happier | **MIT** (root `LICENCE`; Happy lineage) | Wrapper (`apps/cli/src/backends/claude/claudeLocal.ts`, tmux-oriented attach) plus per-launch `--settings` hooks plus Agent SDK remote mode. Claims to continue "sessions already running on your machine" by browsing and tailing the native JSONL (`directSessions/*`) and **taking over** by resuming in its own process, not by live-injecting into a foreign process. | `PermissionRequest` hook locally (`localPermissions/routeClaudePermissionHook.ts`), SDK `canCallTool` remotely | Own server, E2E, self-host or cloud |
| K9i-0/ccpocket | **MIT** | Bridge server runs sessions via Agent SDK `query()` (`packages/bridge/src/sdk-process.ts`). Reads JSONL for history and resume (`sessions-index.ts`). No live attach. | SDK permission flow (not fully confirmed) | Direct WebSocket over LAN/Tailscale; Firebase only for push |
| gaixianggeng/mimi-remote | **GPL-3.0 + app-store additional permission (COPYLEFT)**; GitHub reports NOASSERTION; `bridges/claude` GPLv3-only per NOTICE | Go `agentd` plus Agent SDK bridge (`bridges/claude-sdk/`), one streaming `query()` per session. Uses the `sessions/<pid>.json` registry for ownership. A session held by another process is read-only, and takeover happens only when that holder is idle, by signalling it. | `canUseTool` mapped to approval requests; handles AskUserQuestion | LAN/Tailscale; optional paid relay |
| xiaoY233/cc-switch-remote | **MIT** (archived) | Not a session controller. CC-Switch fork that manages provider, MCP, prompt and skill config on remote servers over SSH (`src-tauri/src/commands/remote.rs`). | none | SSH |

**Takeaways:**

- None of these projects attaches to and controls a live TUI or VS Code session it did not launch. They all either wrap or launch Claude, or read JSONL and resume or take over afterwards.
- **Do not copy code from mimi-remote (GPL-3.0).** MIT projects (happy, happier, ccpocket, cc-switch-remote) can be studied or reused with attribution.

---

## Appendix: commands run (abridged)

```text
claude --version; claude --help; claude agents --help; claude attach --help; claude logs --help
claude mcp --help; claude remote-control --help (-> org policy error); claude ide --help (-> root help)
claude project --help; claude respawn --help
claude agents --json                                      # read-only listing
Get-CimInstance Win32_Process -Filter "Name='claude.exe'" # command lines
code --version
python (read-only) schema extraction over ~/.claude/projects/*/*.jsonl (key names/types only)
python (read-only) shape dump of ~/.claude/sessions/*.json, *.key (field names only), ~/.claude/ide/*.lock (token redacted)
grep/byte-search of ~/.vscode/extensions/anthropic.claude-code-2.1.323-win32-x64/{package.json,extension.js,webview/index.js}
byte-search of the bundled claude.exe for: cc-msg-, peerToken, peerFeatures, waitingFor, --sdk-url, uds-messaging
claude -p --input-format stream-json --output-format stream-json --verbose --no-session-persistence \
   --safe-mode --strict-mcp-config --permission-prompt-tool stdio   <<< {"type":"control_request","request_id":"req_1","request":{"subtype":"initialize"}}
gh api repos/<owner>/<repo> (license, archived, pushed_at); gh api .../contents/LICENSE
WebFetch: code.claude.com/docs/en/{remote-control,channels,channels-reference,cross-session-messaging,agent-view,
          vs-code,sessions,hooks,agent-sdk/user-input,agent-sdk/typescript}
```
