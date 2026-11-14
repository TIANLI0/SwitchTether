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
| A session you started yourself (terminal CLI, VS Code panel, Codex Desktop) | `cli` / `vscode` / `desktop_app` | **Observe**: discover, read history, near-real-time events. Control is **BLOCKED** upstream. |
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
| Real-time events | PARTIAL | Transcript tailing (≈0.8 s). Completed records only — no token streaming, no visibility of an open permission dialog except `status: waiting` / `waitingFor` from `claude agents --json`. |
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
| Resume a Desktop thread in SwitchTether | BLOCKED by design | Would lock Desktop out of its own thread (single writer). The API refuses `resumeFrom` for Codex. |

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
| Continue an old history | PARTIAL — `--resume <id> --fork-session` (always a fork, never two writers) | BLOCKED (see §5) |

Live evidence command: `cargo run -p switchtether-core --example managed_smoke -- claude haiku`
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
