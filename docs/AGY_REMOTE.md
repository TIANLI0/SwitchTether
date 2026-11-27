# Google Antigravity (`agy`) on the phone

SwitchTether starts, continues and controls Antigravity conversations from the phone through
agy's own **Agent Client Protocol server** (`agy acp`). This document records what agy offers
(observed with agy 1.5.9 on Windows, 2026-11-27), the design, and what was verified.

## 1. agy's control interfaces

`agy --help` (1.5.9) offers three non-interactive ways in:

| Interface | What it is | Used? |
|---|---|---|
| `agy acp` | "Start the Agent Client Protocol (ACP) stdio server": JSON-RPC 2.0, one message per line on stdin/stdout. Flags: `--add-dir`, `--agent`, `--dangerously-skip-permissions`, `--sandbox`, `--gemini_dir`, `--app_data_dir`, `--process-per-session`, … | **Yes** |
| `agy -p` / `--print` with `--output-format stream-json` and `--input-format stream-json` | One process per prompt or an NDJSON turn stream; `--conversation <id>` / `-c` to resume; `--model`, `--effort low\|medium\|high\|xhigh\|max`, `--mode accept-edits\|auto\|plan`. Print mode never asks for permission (upstream notes: a denied tool ends up in `denied_actions`) | No: no permission requests, and ACP covers everything |
| `agy remote-control start\|status\|stop` | Registers a daemon for Google's own remote-control service | No: a third-party cloud relay, not a local interface |

Everything else (`agy` TUI, `--prompt-interactive`) is interactive; no PTY scraping is used.

### What `agy acp` answered (recorded in `src-tauri/crates/switchtether-core/tests/fixtures/agy/`)

- `initialize` (protocol 1): `agentInfo` "Antigravity ACP Server" 1.5.9; `loadSession: true`;
  `promptCapabilities {image: true, audio: false, embeddedContext: true}`; `sessionCapabilities`
  `resume`, `close`, `load`, `list`, `additionalDirectories`; `authMethods` (Google login, Gemini
  Enterprise, API key, Agent Platform). An already signed-in agy needs no `authenticate` call.
- `session/new {cwd, mcpServers: []}` (≈10–20 s) → `sessionId` (a UUID), `models.availableModels`
  (agy's tiered ids with display names, the `agy models` list), `modes` (`default`,
  `accept-edits`, `auto`, `plan` with agy's descriptions) and `configOptions`: `model` (base models
  such as `gemini-4-neon`, `claude-sonnet-5-5`, `gpt-oss-120b-medium`), `thought_level`
  (`low|medium|high|xhigh`, per model: Gemini 3.8 Flash and the Claude models have no `xhigh`) and
  `mode`. Followed by `available_commands_update` (slash commands and skills) and `usage_update`
  (context used / size).
- `session/set_config_option {configId, value}` → the whole new option list, plus a
  `config_option_update` notification. `session/set_mode {modeId}` → `{}`.
- `session/prompt` → `user_message_chunk` (the prompt echoed), `agent_message_chunk` (streamed text),
  `tool_call` / `tool_call_update` (`toolCallId`, agy tool `name` such as `run_command` /
  `write_file`, ACP `kind` `execute` / `edit` / …, `status` `pending → in_progress → completed`,
  `rawInput`, `rawOutput {output, exit_code}`, `content` with `diff` blocks, `locations`),
  `usage_update`, and finally the reply `{stopReason: "end_turn", usage: {inputTokens, outputTokens,
  cachedReadTokens, cachedWriteTokens, thoughtTokens, totalTokens}}`.
- `session/cancel` (notification) mid-answer → the prompt settles with `stopReason: "cancelled"`.
- `session/request_permission` (agy numbers its own requests with strings: `"id": "1"`) with
  `options [{optionId: "allow", kind: "allow_once"}, {optionId: "deny", kind: "reject_once"}]`,
  sent for `Remove-Item hello.txt` with agy's default permission settings; answering
  `{outcome: {outcome: "selected", optionId: "allow"}}` ran the command. The user's own agy
  configuration on the test machine is `toolPermission: always-proceed`, so with it agy asks nothing.
- `session/resume {sessionId, cwd}` reopens a conversation without replaying it; `session/load`
  replays it as `session/update`s but **leaves out answers** (only prompts, tool calls and error
  texts came back), so it is not used for history. `session/list` lists agy's conversations
  (cwd as `/C:/…` file-URI paths); `session/close` → `{}`.
- Conversations created through ACP are agy's ordinary ones: `brain/<id>/` and
  `conversations/<id>.db` in `~/.gemini/antigravity-cli/`, listed by agy (`session/list`) and by
  CC Switch's session index, so SwitchTether's history shows them too.
- agy does **not** keep the model, thinking level or mode with a conversation: a resumed one starts
  on agy's default model.
- Model calls of the Gemini models failed on the test machine with `FAILED_PRECONDITION (code 400):
  User location is not supported for the API use` (a regional restriction of Google's API, not of
  SwitchTether); agy reports such errors as answer text. Claude Sonnet 5.5 through agy worked.

## 2. Design

- `crates/switchtether-core/src/agents/agy/` (no Tauri): `managed.rs` — `ManagedAgy`, an ACP client
  over `agy acp`'s stdio (or any byte stream, for tests) implementing `ManagedSession`; `mod.rs` —
  `AcpMapper` (ACP updates → SwitchTether event kinds), binary lookup (`find_agy_bin`, same order as
  the desktop's `real_agy_bin`), `agy models` reader, model grouping and resolution.
- Events use the generic kinds, so the phone needs no new renderer: text chunks are joined into
  one streaming `assistant_message` / `reasoning` per block, the echoed prompt is the
  `user_message` (with the phone's `clientId`), tool calls are `tool_call` (`tc:<id>`, updated in
  place, category from ACP `kind`) with a `tool_result` (`res:<id>`, output redacted and clipped),
  diffs are `file_change`, ACP `plan` is a `plan` row, each turn ends with a `usage` row and the
  rolling `status` row (`idle`, or `interrupted` after a cancel).
- Hub: `POST /v1/sessions {agent: "agy"}` (new, or `resumeFrom` an `agy:` conversation →
  `session/resume`), id `agy:<sessionId>`; history of a continued conversation is preloaded from
  CC Switch's parser, and a `system` `continued` row says where the new turns run (an Antigravity
  window cannot be seen from SwitchTether). A message to an agy conversation that is not running
  resumes it (`resume_on_send`); a run that ended here passes on its model, level and mode.
- Models: `GET /v1/agents/agy/models` groups agy's tiered list (`gemini-4-neon-high`, …) into
  models with thinking levels (`efforts`), as agy's own picker does; a running session answers
  from its `availableModels`. Any of the grouped id + effort, a tiered id or a display name is
  resolved against the session's `model` option (`gpt-oss-120b` + `medium` →
  `gpt-oss-120b-medium`). An effort the chosen model does not offer is refused (`422`), not ignored.
- Permission modes: agy's ACP modes as SwitchTether ids — `default`, `acceptEdits`
  (`accept-edits`), `auto`, `plan` — at start, in `POST …/options` and in the `permission_mode`
  session tool. `--dangerously-skip-permissions` is a process flag, not a mode, and is not offered.
- Session tools (`sessionTools`): `commands` (agy's `available_commands_update`; a command is sent as
  `/name args` text), `permission_mode`, `context_usage` (from `usage_update`).
- Approvals: `session/request_permission` → `approval_request` (first answer wins, `409` with
  `resolvedBy` after); interrupt answers open requests `cancelled` as ACP requires.
- Images: sent as ACP `image` blocks when agy advertises `promptCapabilities.image` (not verified).
- Phone attachments: the attachments folder is passed as `--add-dir`.
- Feature flag `managed_agy` in `GET /v1/capabilities`; the phone's launcher shows the Antigravity
  tab only on a desktop that lists it.
- Nothing of the user's agy configuration, login or settings is written; `agy acp` runs with the
  user's normal agy home, as `agy` in a terminal would.

## 3. Verification

Real agy 1.5.9, signed in, scratch directories under `%TEMP%`, model Claude Sonnet 5.5 (low):

- Raw ACP probe (Python, 8 short runs): initialize, `session/new`, `session/load`, `session/resume`,
  `session/list`, `session/close`, `set_config_option`, `set_mode`, prompts with a shell tool call
  and a file write, cancel mid-answer, and a permission request in an isolated `--gemini_dir`
  (fresh settings, login kept). Recorded and redacted as test fixtures.
- `cargo run -p switchtether-core --example agy_smoke` through the hub, exactly the phone's path:
  new session with model + level + `acceptEdits` → "PELICAN"; live model / level change (Gemini 3.8
  Flash medium and back), `permission_mode`, `context_usage`, `commands`; stop; a message resumed
  the same id `agy:<uuid>` in a new process and the answer was "PELICAN" (context kept); a long
  answer interrupted → status `interrupted`; a permission request (isolated `--gemini_dir`)
  approved from the phone path, second answer `409` with `resolvedBy`, the file was deleted. The
  conversation then appeared in agy's own `session/list`.
- Unit tests: `agents::agy::tests` (recorded streams → events, model grouping and resolution),
  `agents::agy::managed::tests` (the adapter against a fake agy on an in-memory pipe replaying the
  recordings: start options, one prompt at a time, `clientId`, cancel, approval once),
  `api::sessions` fixture `model_options_agy.json` (Rust and Kotlin).

Not verified: a successful answer from a Gemini model (region-restricted on the test machine),
image prompts, ACP `plan` and `agent_thought_chunk` updates (none were sent in these runs; mapped
per the ACP schema), the phone UI on a device, macOS / Linux, conversations of CC Switch's API-key
profiles (`--gemini_dir` of a private profile; resuming one fails with agy's error).
