# Agent compatibility

Status legend:

- **VERIFIED** — exercised on real software with the evidence listed.
- **PARTIAL** — works with stated limitations.
- **BLOCKED** — not possible through any legitimate, supported interface of the upstream
  client; evidence and reproduction steps are in `docs/research/`.
- **UNTESTED** — implemented or plausible but not exercised end-to-end.

Test machine: Windows 11, Claude Code CLI **2.1.282**, Claude Code VS Code extension
**2.1.323** (bundled CLI 2.1.323), Codex CLI **0.137.0**, Codex Desktop
**26.1106.6628.0** (bundled codex 0.167.0-alpha.4.1). Dates: 2026-11-14.

## 1. Summary

SwitchTether surfaces three kinds of sessions and never presents one as another:

| Kind | Origin label | What the phone can do |
|---|---|---|
| A session you started yourself (terminal CLI, VS Code panel, Codex Desktop) | `cli` / `vscode` / `desktop_app` | **Observe**: discover, read history, near-real-time events. In-place control is **BLOCKED** upstream — unless the opt-in takeover hook for that client is on (§6b). |
| A session you started yourself **with the opt-in takeover hook on** (§6b) | `cli` / `vscode` / `desktop_app`, `control: full`, `managed: false` | Send, approve, interrupt (terminal: through the mirrored terminal) |
| History indexed by CC Switch (all supported agents) | `unknown`, liveness `ended` | Read |
| A **new session started by SwitchTether** on your request (optionally continuing an old Claude history as a fork) | `managed` | Full control: send, approve, answer, interrupt |

The hard requirement "take over an already running session in place" is **not met** for any
of the four clients, because none of them offers a supported external control interface for
an existing session. SwitchTether does not work around this by injecting into processes,
replacing executables, editing hooks/settings, resuming the same session in a second process
or rewriting transcript files.

## 2. Claude Code CLI (interactive terminal session started by the user)

| Capability | Status | Evidence |
|---|---|---|
| Discover live sessions | VERIFIED | `claude agents --json` (documented as "the supported way to read session state from outside Claude Code") + `~/.claude/sessions/<pid>.json` fallback. Live check on this machine via `dev_client get /v1/sessions` (2 live sessions found). Unit tests `agents::claude::live::*`. |
| Read full history | VERIFIED | Transcript `~/.claude/projects/<proj>/<id>.jsonl` parsed into 1086 normalized events for a real session (tool calls paired with results, 17 file diffs). Unit tests `agents::claude::transcript::*`. Format is internal and may change between versions. |
| Real-time events | PARTIAL | Transcript tailing (≈0.8 s). Completed records only — no token streaming, no visibility of an open permission dialog except `status: waiting` / `waitingFor` from `claude agents --json` / the registry. Status changes are pushed as they happen: an OS change notification for the registry directory (`notify`: ReadDirectoryChangesW / inotify / FSEvents) triggers an immediate re-read; a metadata check every 2 s (0.5 s when no OS watch is available) and a full `claude agents --json` refresh every 4 s are the safety nets (unit test `a_short_turn_between_full_refreshes_is_published`: a 0.3 s turn between polling ticks is published). |
| Send message into the live session | BLOCKED | Interactive input is the TTY of the user's terminal. Cross-session messaging (named pipe) delivers a note "from another session", cannot approve anything and is not a user turn. Remote Control is first-party only and disabled by policy on the test account. Channels require launching with `--channels`. See research §A.2–A.4. |
| Answer questions / approve permissions | BLOCKED | Only hooks configured before launch, channels (launch flag) or the Agent SDK of the owning process can answer. Auto-installing hooks is forbidden by the product requirements. Research §D. |
| Interrupt | BLOCKED | Same. |
| Concurrency control | n/a | — |
| Plugins/skills/MCP/hooks unaffected | VERIFIED (by construction) | SwitchTether never writes `~/.claude` (file policy also denies `.claude/.credentials.json`). |

## 3. Claude Code VS Code extension (panel session)

| Capability | Status | Evidence |
|---|---|---|
| Discover live sessions | VERIFIED | Same registry; entrypoint `claude-vscode` → origin `vscode`. |
| Read full history | VERIFIED | Same transcript format (the extension and CLI share history). |
| Real-time events | PARTIAL | As above. Whether `status` flips to `waiting` for panel permission prompts is UNTESTED (both observed panel sessions only showed `busy`). |
| Send / approve / answer / interrupt | BLOCKED | The panel drives `claude.exe` over private stdin/stdout pipes (`--input-format stream-json --permission-prompt-tool=stdio`). The extension exports no API; its URI handler only pre-fills the prompt box. Research §B. |
| Official extension UI parity | PARTIAL | The phone renders the same structured content natively (messages, thinking, tool calls, diffs, todo plans, questions). Extension-private UI state (open tabs, editor diff view) is not exposed by any interface. |

## 4. Codex CLI

| Capability | Status | Evidence |
|---|---|---|
| Discover live sessions | PARTIAL | No supported registry. Rollout mtime/index only; SwitchTether does not probe `thread-writer-locks` (could block the owner). |
| Read full history | VERIFIED (parser) / PARTIAL (formats) | Rollout parser unit tests (`agents::codex::rollout::*`) for message/function_call/apply_patch/update_plan/token_count; Desktop 0.167 "paginated" rollouts use the same item types. CLI-0.137-written rollouts were not available on the test machine. |
| Real-time events | PARTIAL | Rollout tailing; completed items only, pending approvals invisible. |
| Send / approve / interrupt in the live session | BLOCKED | The interactive TUI of 0.137 runs its app-server in-process. A second app-server calling `thread/resume` on the live thread is rejected: `-32600 "thread <id> already has an active writer"` (verified, research §B.3). |
| Shared background app-server (CLI ≥ 0.157) | UNTESTED | Would allow attach without launch changes on newer CLIs (Unix default; opt-in on Windows). Not implemented: the installed CLI is 0.137 and the daemon is not running. |

## 5. Codex Desktop

| Capability | Status | Evidence |
|---|---|---|
| Discover / history / events | PARTIAL | Same as CLI (rollouts written by its bundled 0.167). |
| Send / approve / interrupt | BLOCKED | Desktop runs a private stdio app-server with no network listener; its ChatGPT remote-control relay protocol is not public. Research §D.1. |
| Resume a Desktop thread in SwitchTether | PARTIAL by design | Codex allows a single writer per thread: while Desktop has the thread open, `thread/resume` is refused and the API answers 409; once it is closed there, SwitchTether can continue it (§6a), and Desktop cannot write to it meanwhile. |

## 6. SwitchTether-managed sessions (new sessions started from the phone)

| Capability | Claude Code | Codex |
|---|---|---|
| Start with the user's normal config, plugins, skills, MCP, hooks | VERIFIED — `--setting-sources user,project,local` | VERIFIED (protocol) — own `codex app-server`, normal `~/.codex` |
| Send message | VERIFIED (live run, model `haiku`, 2026-11-14) | PARTIAL — `turn/start` accepted; model call not executed in the isolated test home (no credentials, 401 surfaced as error) |
| Streaming text | VERIFIED (`stream_event` deltas → in-place updates) | UNTESTED live |
| Tool calls + results | VERIFIED | UNTESTED live |
| Approve permission request | VERIFIED (`can_use_tool` → allow → tool ran) | UNTESTED live (`item/commandExecution/requestApproval` mapping implemented) |
| First answer wins (second device gets 409 `resolvedBy`) | VERIFIED | Implemented, UNTESTED live |
| Answer AskUserQuestion | UNTESTED (implemented via `updatedInput.answers`) | n/a (experimental upstream API not enabled) |
| Interrupt | VERIFIED (status `interrupted`) | UNTESTED live (`turn/interrupt`) |
| Files attached from the phone | PARTIAL — `attachments_reach_the_agent_as_paths_and_images_inline` (fake agent); not yet run with a real agent | Same for every agent (Claude Code, Codex, DeepSeek Harness; managed or taken over): absolute paths in a marked block of the message (PROTOCOL §sessions). Claude Code managed: `--add-dir` for the attachments folder; images ≤ 3 MiB also inline for Claude Code / Codex. Taken-over sessions and dsh may ask for permission to read the path (the request reaches the phone). |
| Continue an old history | PARTIAL — `--resume <id>` continues the same conversation (refused while the original is busy / waiting; a second request while it already runs in SwitchTether returns that session); `--fork-session` on request (§6a) | PARTIAL — `thread/resume` (§6a) |

| Official conversation id (`claude:<uuid>` via `--session-id`; Claude Code's own transcript; listed by `claude --resume`) | VERIFIED (live, 2026-11-21, `resume_smoke`) | PARTIAL — id is the thread id from `thread/start`; not run live |
| Change model / effort while running | VERIFIED (live: `set_model` to `sonnet` → next reply from `claude-sonnet-5-5`; `apply_flag_settings {effortLevel}` accepted) | UNTESTED live (`turn/start` `model` / `effort`) |
| MCP elicitation (an MCP server asks the user something) / hook callbacks | BLOCKED — declined with an error (`control_response` `subtype: "error"`): the field names of Claude Code's `elicitation` control request and the shape of its answer are not recorded anywhere in this repository, so nothing is mapped to the phone | Implemented — `mcpServer/elicitation/request` → `question` event → `{action, content}` (fake app-server test `codex_user_input_elicitation_and_subagent_routing`); UNTESTED live |
| Rewind files (`rewind_files`) | BLOCKED — switched off (`REWIND_FILES_ENABLED = false`); needs `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=1` on the managed process; no live evidence recorded | n/a |
| Resume on send after the process ended (desktop restart) | VERIFIED (live: new hub, same id, `claude --resume <uuid>`, remembered the first turn's code word) | PARTIAL — `thread/resume` path, not run live |

Live evidence command: `cargo run -p switchtether-core --example resume_smoke -- haiku sonnet` (new session with effort
`low` → switch to `sonnet` / `medium` → drop the hub → send to the same id from a fresh hub). Output excerpt (2026-11-21,
Claude Code 2.1.325):

```
1. created id=claude:cde1ab97-… native=cde1ab97-… effort=Some("low")
  assistant (claude-haiku-5-5): ALPHA
   official transcript: Some("C:\\Users\\…\\.claude\\projects\\C--Users-…-Temp--tmpvvKmSh\\cde1ab97-….jsonl")
   model options (source "agent"): default, opus, fable, sonnet, haiku, …, opusplan
2. switched: model=Some("sonnet") effort=Some("medium")
  assistant (claude-sonnet-5-5): BETA
3. after restart: liveness="ended" sendMessage="PARTIAL" eventCount=8
  assistant (claude-sonnet-5-5): ZEBRA-42
   resumed: id="claude:cde1ab97-…" liveness="live" remembers=true
```

SwitchTether no longer sets its own `CLAUDE_CODE_ENTRYPOINT`; Claude Code names stream-json sessions `sdk-cli` itself,
as for the official Agent SDK.

Earlier live evidence command: `cargo run -p switchtether-core --example managed_smoke -- claude haiku`
(with `SMOKE_PROMPT` / `SMOKE_INTERRUPT` variants). Output excerpt:

```
tool_call          "Bash" ["running"] "Create directory switchtether_smoke_dir"
approval_request   "Create directory switchtether_smoke_dir" resolved=false
  -> approve: ok
  -> second answer rejected: Conflict {"resolvedBy":"smoke"}
tool_call          "Bash" ["completed"] …
assistant_message  DONE
status             "idle"
```

## 6a. Continuing an externally started session ("接续会话")

Because in-place control of the original process is BLOCKED, SwitchTether offers the strongest
legitimate alternative: the **same conversation** continues in a new SwitchTether-owned process.

| | Claude Code (CLI / VS Code) | Codex (CLI / Desktop) |
|---|---|---|
| Mechanism | `claude --resume <same id>` (no fork) with the user's settings | `thread/resume` on SwitchTether's own `codex app-server` |
| Status | **PARTIAL — VERIFIED live** (2026-11-14): an external `claude -p` session was told a code word; continuing it from SwitchTether answered with that word, and the new turn was appended to the original transcript file | PARTIAL — implemented; not run live (no isolated authenticated Codex home) |
| Safety guard | refused while the original is `busy`/`waiting` (unit test `continuing_a_busy_external_session_is_refused`) | Codex itself refuses while the thread is open elsewhere ("active writer"), surfaced as 409 |
| Limitations | the original window (terminal / VS Code panel) does not show the phone's turns and must not be used at the same time; it is not an in-place takeover | the original client cannot write to the thread while SwitchTether holds it |

## 6b. Opt-in takeover of sessions you start yourself ("接管")

Turned on per client in Settings → Remote → "Take over sessions I start myself". Each switch
points the client at `switchtether-shim`, a pass-through wrapper shipped with SwitchTether
(copied to `~/.switchtether/bin`). The shim starts the real agent, forwards everything between
the client and the agent unchanged, and mirrors the traffic to SwitchTether's local attach
broker; the phone's input is injected into the same stream. If SwitchTether is not running the
shim only passes through. Nothing is injected into running processes: the hook applies to
sessions started after it is turned on.

| Client | Hook (opt-in, backed up, removed exactly) | Phone can | Status / evidence (2026-11-14) |
|---|---|---|---|
| Claude Code in VS Code (and Insiders, Cursor, Windsurf, VSCodium) | `claudeCode.claudeProcessWrapper` in the editor's user `settings.json`; never replaces an existing wrapper | send, approve / deny (first answer wins; the IDE dialog is dismissed with `control_cancel_request`), interrupt; prompts typed in the IDE appear on the phone. Not model / effort: they stay with the IDE (`changeModel: BLOCKED`, `POST …/options` → 422; test `attached_claude_session_capability_matches_model_change`) | **PARTIAL** — verified live with the shim wrapping real `claude` and a test harness speaking the extension's stream-json protocol: phone approval ran the command and dismissed the host's dialog, a phone message produced a turn whose output reached the host, the session ended when the host closed. Inside the real VS Code extension: UNTESTED (needs the user to open a new panel). Interrupt: implemented, not exercised live. |
| Codex Desktop (Windows) | user environment variable `CODEX_CLI_PATH` (refused if already set); restart Codex Desktop | every thread loaded in Desktop: send (`turn/start`, `turn/steer`), approve (Desktop gets `serverRequest/resolved`), interrupt | **PARTIAL** — verified live with Codex Desktop's own bundled `codex.exe` 0.167 behind the shim and a harness playing Desktop: phone approval ran the command, a phone message produced a turn streamed to the host. Real Codex Desktop launching the shim: UNTESTED (needs a Desktop restart). |
| Terminal | `st-claude` / `st-codex` launchers in `~/.switchtether/bin` (Windows: added to the user PATH). Plain `claude` / `codex` are never redirected | the TUI is mirrored as an external terminal (step-up gated like any terminal); messages are typed into the TUI (bracketed paste + Enter; `!` shell lines refused); Esc interrupts; prompts are answered in the terminal view; conversation read from the transcript once the session id is matched (pid / cwd) | **PARTIAL** — real `claude` TUI rendered through the shim's PTY and mirrored (folder-trust screen captured); typing/size/exit covered by `tests/attach_broker.rs`. Typing into a live authenticated TUI: UNTESTED. |

Attached sessions are listed with their real origin (`vscode`, `desktop_app`, `cli`),
`control: "full"` and `managed: false`; they are never labelled `managed`, and quitting
SwitchTether never stops them. The observed id (`claude:<id>` / `codex:<id>`) of the same
conversation resolves to the attached session.

## 7. Other agents (Gemini CLI, OpenCode, …)

History from CC Switch's session index is shown read-only (`readHistory: PARTIAL`, plain
role/text). No live control.

## 8. What would unblock in-place takeover

| Client | Interface that would be needed | Status upstream |
|---|---|---|
| Claude Code | A documented local control socket for interactive sessions (inject user turn, answer `can_use_tool`, interrupt), or third-party access to Remote Control | Not available; channels exist but require a launch flag |
| VS Code extension | An exported extension API (`exports`) for send/approve/interrupt | Not available |
| Codex CLI | Default shared app-server on all platforms (≥ 0.157 has it on Unix) | Available on newer CLIs; SwitchTether could attach via `thread/resume` on the shared server — follow-up work |
| Codex Desktop | Opt-in local app-server sharing (`CODEX_APP_SERVER_USE_LOCAL_DAEMON`) on Windows | Present only on non-Windows behind an opt-in variable |

## DeepSeek Harness (`dsh`)

| Capability | Status | How |
|---|---|---|
| History / live view of the user's own sessions | PARTIAL — checked read-only against real sessions on the development machine | `$DSH_HOME/sessions/<project>/<session>/session.vN.jsonl[.zstd]`, independent zstd frames tailed frame by frame. Live (observe) state comes from the log's last turn, read incrementally: an open turn (`turn/start` without `turn/end`) is busy while the log was written in the last 10 min (long tool calls write nothing for a while); an ended turn is idle (or `error`) for 2 min after the last write, then the session counts as ended. `turn/end` kinds seen in real logs: `completed`, `interrupted`, `aborted` (`reason.kind: "user"`, a stop in the dsh UI) and `error` (e.g. 402 quota) — `interrupted` / `aborted` add an "interrupted" marker, `error` an error row; managed and plugin-attached sessions take busy / idle from the same turn state (unit tests `every_turn_end_kind_is_reported`, `observed_dsh_status_follows_the_last_turn`). Model-only context (`time-context`, `skill-catalog`, …) and `surfaceOp: replace` copies are skipped. |
| Sub-agent branches | PARTIAL — real session with a `subagent_fork` child | Children are sessions of their own (`parentSession`, `delegationDepth`); listed only under their parent. |
| Managed sessions (new / continue) | PARTIAL — live: `session/new` + `session/prompt` through SwitchTether's own `dsh --profile acp`, user message read back from the log; the model call itself failed with "Insufficient Balance" on the freshly initialised `acp` profile (account routing), so a full answer, approvals and cancel are not yet verified | ACP v1 over stdio: `initialize`, `session/new` / `session/resume`, `session/prompt` (one at a time), `session/cancel`, `session/request_permission` → approval cards. The timeline is the persisted log. Uses the user's existing `acp` profile (initialised by dsh on first use; CC Switch adds providers to initialised profiles). |
| Control of the user's own `dsh web` sessions | PARTIAL — opt-in plugin installed and removed in a sandbox `DSH_HOME` with dsh's own plugin manager; it loaded in `dsh web` and connected to the attach broker; plugin logic (prompt, cancel, phone-first and web-first approvals, disconnect fallback) tested with a fake host; broker side covered by `dsh_bridge_plugin_sessions_are_controllable`. A message or approval through a real dsh web session is not yet verified, nor whether the dsh UI withdraws its approval card when the phone answers first | Settings → Remote → "DeepSeek Harness · Web" (§6b): writes `switchtether-dsh-bridge` to `~/.switchtether/dsh-plugin`, backs up the `web` profile's `package.json`, `cordis.patch.yml`, `pnpm-lock.yaml`, `compatibility.json`, runs `dsh plugin --profile web add link:…` (dsh enables the bundle); off runs `dsh plugin --profile web remove` and puts the manifest back byte for byte when nothing else changed. The plugin reports live sessions, races each `approval/request` between the phone and the dsh UI (first answer wins) and calls `sessionController.prompt` / `cancel`. dsh refuses CLI plugin management of its Desktop profile: the desktop app is not covered. |
