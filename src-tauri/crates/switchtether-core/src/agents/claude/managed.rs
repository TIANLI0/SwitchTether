//! SwitchTether-managed Claude Code sessions.
//!
//! These are NEW processes started by SwitchTether on explicit user request
//! (never presented as the user's existing session). They use the same
//! mechanism as the official VS Code extension and the Agent SDK:
//! `--input-format stream-json --output-format stream-json
//! --permission-prompt-tool stdio`, with `--setting-sources user,project,local`
//! so the user's plugins, skills, MCP servers and hooks load exactly as in a
//! normal session. Nothing in the user's configuration is modified.
//!
//! Control protocol (stdin/stdout JSON lines):
//! - host → CLI: `{type:"user", message}`; `{type:"control_request", request:{subtype:"initialize"|"interrupt"|…}}`
//! - CLI → host: `control_request` with `subtype:"can_use_tool"` (permission
//!   prompts and AskUserQuestion) and `subtype:"elicitation"` (an MCP server asks the user
//!   something), answered with `control_response`; `control_cancel_request`; regular
//!   transcript-shaped messages.

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, Command};
use tokio::sync::oneshot;

use super::transcript::ClaudeParser;
use crate::agents::model::{EventDraft, Liveness};
use crate::agents::store::SessionLog;
use crate::error::{ApiError, ApiResult};

/// "Rewind files" from the phone (`rewind_files`, needs file checkpoints): built and verified,
/// switched off for now.
pub const REWIND_FILES_ENABLED: bool = false;

#[derive(Debug, Clone)]
pub struct ManagedClaudeOptions {
    pub claude_bin: PathBuf,
    pub cwd: PathBuf,
    pub model: Option<String>,
    pub permission_mode: Option<String>,
    /// Continue from an existing (not live) session's history in a fork.
    pub resume_from: Option<String>,
    /// With `resume_from`: start a fork (new session id) instead of continuing it.
    pub fork: bool,
    /// Extra directories Claude Code may read without asking (`--add-dir`): the folder of files
    /// attached from the phone.
    pub add_dirs: Vec<PathBuf>,
    /// Conversation id for a new conversation (`--session-id`, also for forks): the session is a
    /// normal Claude Code conversation known by its own id from the start, listed by
    /// `claude --resume` and the IDE's history like any other.
    pub session_id: Option<String>,
    /// Effort level (`--effort`: low, medium, high, xhigh, max).
    pub effort: Option<String>,
}

struct Pending {
    tool_name: String,
    input: Value,
    suggestions: Value,
    event_id: String,
    /// An MCP elicitation instead of a tool permission: its parameters in the shape of
    /// `codex::managed::elicitation_to_questions`; answered with an MCP `ElicitResult`.
    elicitation: Option<Value>,
}

pub struct ManagedClaude {
    pub id: String,
    pub options: ManagedClaudeOptions,
    pub log: Arc<Mutex<SessionLog>>,
    parser: Mutex<ClaudeParser>,
    stdin: tokio::sync::Mutex<Option<ChildStdin>>,
    child: Mutex<Option<Child>>,
    /// Pid of the spawned process (managed mode). The process also registers itself in Claude
    /// Code's live registry, where it must not show up as a second, observe-only session.
    pid: Option<u32>,
    pending: Mutex<HashMap<String, Pending>>,
    /// request ids already answered → who answered (for 409 details).
    resolved: Mutex<HashMap<String, String>>,
    replies: Mutex<HashMap<String, oneshot::Sender<Value>>>,
    sent_texts: Mutex<HashSet<String>>,
    state: Mutex<State>,
    next_req: std::sync::atomic::AtomicU64,
    stderr_tail: Mutex<Vec<String>>,
    /// Attached mode: lines are injected through the user's own process via
    /// switchtether-shim instead of a child spawned by SwitchTether.
    remote: Option<tokio::sync::mpsc::UnboundedSender<String>>,
    /// Attached mode: shim verdicts for injected approval answers.
    verdicts: Mutex<HashMap<String, oneshot::Sender<bool>>>,
    /// Attached mode: user texts already shown (from the IDE or the phone);
    /// a replayed copy on stdout is skipped.
    echoed: Mutex<HashSet<String>>,
    /// Permission mode as last set (`--permission-mode`, then `set_permission_mode`).
    permission_mode: Mutex<Option<String>>,
    /// `uuid` of the last message sent from the phone (`cancel_async_message` while queued).
    last_uuid: Mutex<Option<String>>,
    pub origin: &'static str,
    /// Tests: lines for the agent's stdin go here instead of a process.
    #[cfg(test)]
    test_out: Option<tokio::sync::mpsc::UnboundedSender<String>>,
}

#[derive(Debug, Clone)]
struct State {
    status: &'static str,
    liveness: Liveness,
    native_session_id: Option<String>,
    created_at_ms: i64,
    last_active_at_ms: i64,
    /// Model chosen from the phone during the session (`set_model`); wins over the parsed one
    /// until the agent reports a model again.
    model_override: Option<String>,
    effort: Option<String>,
    /// `models` from the initialize response (what this Claude Code offers), if any.
    models: Value,
    /// Slash commands this Claude Code session offers (`commands` of the initialize response,
    /// plus names from `system`/`init`), normalised by [`normalize_commands`].
    commands: Vec<Value>,
}

const COMMAND_NAME_MAX: usize = 80;
const COMMAND_DESCRIPTION_MAX: usize = 300;
const COMMAND_HINT_MAX: usize = 120;
/// More than any real installation offers; bounds what a phone has to hold.
const COMMANDS_MAX: usize = 500;
const COMMAND_ALIASES_MAX: usize = 5;
/// Attached mode: prompts shown before their replay arrived; a bound, not an expected size.
const ECHOED_MAX: usize = 256;

fn clip(text: &str, max: usize) -> String {
    text.trim().chars().take(max).collect()
}

/// A slash command's name without the leading "/"; `None` for anything that is not one line of
/// printable text.
fn command_name(raw: &str) -> Option<String> {
    let name = clip(raw.trim().trim_start_matches('/'), COMMAND_NAME_MAX);
    (!name.is_empty() && !name.chars().any(char::is_control)).then_some(name)
}

/// Claude Code's slash command list as the phone gets it: `{ name, description, argumentHint,
/// aliases? }` (strings only, clipped, texts redacted), first occurrence of a name wins, order
/// kept. Entries may be objects (the initialize response: `{name, description, argumentHint,
/// aliases?, builtin?, plugin?}`, as the VS Code extension 2.1.331 validates it) or bare names.
/// `aliases` (other names of the command, at most 5) is only present when there are any;
/// `builtin` and `plugin` are not passed on.
/// Whether SwitchTether runs as root (Claude Code refuses the skip-permissions flags then).
fn running_as_root() -> bool {
    #[cfg(unix)]
    {
        // SAFETY: geteuid has no preconditions and cannot fail.
        unsafe { libc::geteuid() == 0 }
    }
    #[cfg(not(unix))]
    {
        false
    }
}

/// The running process asks its questions through `can_use_tool`, which already makes the
/// question card (answered there): the parser's card for the same AskUserQuestion result would
/// show it twice.
fn own_card_only(d: &EventDraft) -> bool {
    !(d.kind == "question"
        && d.id
            .starts_with(super::transcript::ANSWERED_QUESTION_PREFIX))
}

pub fn normalize_commands(raw: &Value) -> Vec<Value> {
    let mut out = Vec::new();
    merge_commands(&mut out, raw);
    out
}

/// Adds the commands of `raw` whose names `list` does not hold yet.
pub fn merge_commands(list: &mut Vec<Value>, raw: &Value) {
    let text = |c: &Value, keys: &[&str], max: usize| {
        keys.iter()
            .find_map(|k| c.get(*k).and_then(Value::as_str))
            .map(|t| clip(&crate::redact::redact_text(t), max))
            .unwrap_or_default()
    };
    for c in raw.as_array().into_iter().flatten() {
        if list.len() >= COMMANDS_MAX {
            break;
        }
        let name = match c {
            Value::String(n) => command_name(n),
            Value::Object(_) => c.get("name").and_then(Value::as_str).and_then(command_name),
            _ => None,
        };
        let Some(name) = name else { continue };
        if list.iter().any(|k| k["name"] == name.as_str()) {
            continue;
        }
        let mut aliases: Vec<String> = Vec::new();
        for a in c
            .get("aliases")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            if aliases.len() >= COMMAND_ALIASES_MAX {
                break;
            }
            if let Some(a) = a.as_str().and_then(command_name) {
                if a != name && !aliases.contains(&a) {
                    aliases.push(a);
                }
            }
        }
        let mut entry = json!({
            "name": name,
            "description": text(c, &["description"], COMMAND_DESCRIPTION_MAX),
            "argumentHint": text(c, &["argumentHint", "argument_hint"], COMMAND_HINT_MAX),
        });
        if !aliases.is_empty() {
            entry["aliases"] = json!(aliases);
        }
        list.push(entry);
    }
}

impl ManagedClaude {
    pub fn native_session_id(&self) -> Option<String> {
        self.state.lock().native_session_id.clone()
    }
    pub fn pid(&self) -> Option<u32> {
        self.pid
    }
    pub fn status(&self) -> &'static str {
        self.state.lock().status
    }
    pub fn liveness(&self) -> Liveness {
        self.state.lock().liveness
    }
    pub fn created_at_ms(&self) -> i64 {
        self.state.lock().created_at_ms
    }
    pub fn last_active_at_ms(&self) -> i64 {
        self.state.lock().last_active_at_ms
    }
    pub fn model(&self) -> Option<String> {
        self.state
            .lock()
            .model_override
            .clone()
            .or_else(|| self.parser.lock().model.clone())
            .or(self.options.model.clone())
    }
    pub fn effort(&self) -> Option<String> {
        self.state.lock().effort.clone()
    }
    pub fn models(&self) -> Value {
        self.state.lock().models.clone()
    }
    /// Slash commands the running process reported; empty when it never did or has exited.
    pub fn commands(&self) -> Vec<Value> {
        let st = self.state.lock();
        if st.liveness == Liveness::Live {
            st.commands.clone()
        } else {
            Vec::new()
        }
    }

    /// Switch the model of the running session the way the official SDK does
    /// (`set_model` control request); `None` returns to the default model.
    pub async fn set_model(&self, model: Option<&str>) -> ApiResult<()> {
        self.control_request(
            json!({ "subtype": "set_model", "model": model }),
            Duration::from_secs(20),
        )
        .await?;
        self.state.lock().model_override = model.map(String::from);
        Ok(())
    }

    /// Change the effort level of the running session (`apply_flag_settings` with
    /// `effortLevel`, as the SDK's `applyFlagSettings`); `None` returns to the default.
    pub async fn set_effort(&self, effort: Option<&str>) -> ApiResult<()> {
        self.control_request(
            json!({ "subtype": "apply_flag_settings", "settings": { "effortLevel": effort } }),
            Duration::from_secs(20),
        )
        .await?;
        self.state.lock().effort = effort.map(String::from);
        Ok(())
    }
    pub fn title(&self) -> Option<String> {
        self.parser.lock().title.clone()
    }
    pub fn pending_request_ids(&self) -> Vec<String> {
        self.pending.lock().keys().cloned().collect()
    }
    pub fn stderr_tail(&self) -> Vec<String> {
        self.stderr_tail.lock().clone()
    }

    fn set_status(&self, status: &'static str) {
        let mut st = self.state.lock();
        st.status = status;
        st.last_active_at_ms = crate::util::now_ms();
    }

    pub async fn spawn(
        id: String,
        options: ManagedClaudeOptions,
        log: Arc<Mutex<SessionLog>>,
    ) -> anyhow::Result<Arc<Self>> {
        let mut cmd = Command::new(&options.claude_bin);
        cmd.args([
            "--output-format",
            "stream-json",
            "--verbose",
            "--input-format",
            "stream-json",
            "--permission-prompt-tool",
            "stdio",
            "--setting-sources",
            "user,project,local",
            "--include-partial-messages",
            "--replay-user-messages",
        ]);
        if let Some(m) = &options.model {
            cmd.args(["--model", m]);
        }
        if let Some(e) = &options.effort {
            cmd.args(["--effort", e]);
        }
        if let Some(pm) = &options.permission_mode {
            cmd.args(["--permission-mode", pm]);
        }
        // Lets the phone switch this session to `bypassPermissions` later (`set_permission_mode`,
        // the VS Code extension's "allow dangerously skip permissions"); nothing is skipped until
        // that mode is chosen, which the API gates behind the terminal step-up. Claude Code refuses
        // the flag for root, so it is left out there (bypass then only works from the start).
        if !running_as_root() {
            cmd.arg("--allow-dangerously-skip-permissions");
        }
        if let Some(r) = &options.resume_from {
            // Same conversation by default ("恢复会话"); the hub refuses that while the
            // original process is busy / waiting, so two writers never interleave in one
            // transcript. A fork (new id, same history) is the opt-in alternative.
            cmd.args(["--resume", r]);
            if options.fork {
                cmd.arg("--fork-session");
            }
        }
        if let Some(sid) = options.session_id.as_deref().filter(|_| {
            // `--session-id` names a new conversation: never together with an in-place resume.
            options.resume_from.is_none() || options.fork
        }) {
            cmd.args(["--session-id", sid]);
        }
        for d in options.add_dirs.iter().filter(|d| d.is_dir()) {
            cmd.arg("--add-dir").arg(d);
        }
        // No CLAUDE_CODE_ENTRYPOINT of our own: Claude Code names a stream-json session `sdk-cli`
        // itself (like the official Agent SDK); unknown names are not an official entrypoint.
        cmd.current_dir(&options.cwd)
            .env_remove("CLAUDE_CODE_ENTRYPOINT")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        if REWIND_FILES_ENABLED {
            // File checkpoints, as the Agent SDK's `enableFileCheckpointing`: lets the phone
            // rewind file changes (`rewind_files`). Claude Code's own data; nothing of the
            // user's configuration changes.
            cmd.env("CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING", "1");
        }
        let initial_mode = options.permission_mode.clone();
        #[cfg(windows)]
        {
            cmd.creation_flags(0x0800_0000);
        }
        let mut child = cmd.spawn()?;
        let stdin = child.stdin.take();
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| anyhow::anyhow!("no stdout"))?;
        let stderr = child.stderr.take();
        let pid = child.id();
        let now = crate::util::now_ms();
        // Continuing in place keeps the conversation id, so it is known before the first turn:
        // lists and the live registry match the session at once instead of after the next prompt.
        let native_session_id = options
            .resume_from
            .clone()
            .filter(|_| !options.fork)
            .or_else(|| options.session_id.clone());
        let effort = options.effort.clone();
        let me = Arc::new(Self {
            id,
            options,
            log,
            parser: Mutex::new(ClaudeParser::new()),
            stdin: tokio::sync::Mutex::new(stdin),
            child: Mutex::new(Some(child)),
            pid,
            pending: Mutex::new(HashMap::new()),
            resolved: Mutex::new(HashMap::new()),
            replies: Mutex::new(HashMap::new()),
            sent_texts: Mutex::new(HashSet::new()),
            state: Mutex::new(State {
                status: "idle",
                liveness: Liveness::Live,
                native_session_id,
                created_at_ms: now,
                last_active_at_ms: now,
                model_override: None,
                effort,
                models: Value::Null,
                commands: Vec::new(),
            }),
            next_req: std::sync::atomic::AtomicU64::new(1),
            stderr_tail: Mutex::new(Vec::new()),
            remote: None,
            verdicts: Mutex::new(HashMap::new()),
            echoed: Mutex::new(HashSet::new()),
            permission_mode: Mutex::new(initial_mode),
            last_uuid: Mutex::new(None),
            origin: "managed",
            #[cfg(test)]
            test_out: None,
        });

        let reader = me.clone();
        tokio::spawn(async move {
            let mut lines = BufReader::new(stdout).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if let Ok(v) = serde_json::from_str::<Value>(&line) {
                    reader.on_message(v).await;
                }
            }
            reader.on_exit().await;
        });
        if let Some(stderr) = stderr {
            let tail = me.clone();
            tokio::spawn(async move {
                let mut lines = BufReader::new(stderr).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    let mut t = tail.stderr_tail.lock();
                    t.push(crate::redact::redact_text(&line));
                    if t.len() > 50 {
                        t.remove(0);
                    }
                }
            });
        }

        // Initialize the control protocol (no hooks / SDK MCP servers registered).
        let init = me
            .control_request(
                json!({ "subtype": "initialize", "hooks": null }),
                Duration::from_secs(60),
            )
            .await;
        match init {
            Err(e) => {
                me.kill();
                anyhow::bail!("claude did not initialize: {e}");
            }
            Ok(v) => {
                let models = v
                    .pointer("/response/models")
                    .or_else(|| v.get("models"))
                    .cloned()
                    .unwrap_or(Value::Null);
                me.state.lock().models = models;
            }
        }
        Ok(me)
    }

    /// Attach to a Claude Code process the user started (VS Code extension
    /// with the SwitchTether process wrapper enabled). Nothing is spawned or
    /// initialised: the IDE already owns the session.
    pub fn attached(
        id: String,
        cwd: PathBuf,
        log: Arc<Mutex<SessionLog>>,
        tx: tokio::sync::mpsc::UnboundedSender<String>,
        origin: &'static str,
    ) -> Arc<Self> {
        Self::attached_known(id, cwd, log, tx, origin, None, None)
    }

    /// [`Self::attached`] with what the shim's hello already tells: the Claude Code process's pid
    /// and the conversation it continues. Both identify the process in Claude Code's live registry,
    /// so it is not listed a second time as an observed session while it has not spoken yet.
    pub fn attached_known(
        id: String,
        cwd: PathBuf,
        log: Arc<Mutex<SessionLog>>,
        tx: tokio::sync::mpsc::UnboundedSender<String>,
        origin: &'static str,
        pid: Option<u32>,
        native_session_id: Option<String>,
    ) -> Arc<Self> {
        let now = crate::util::now_ms();
        Arc::new(Self {
            id,
            options: ManagedClaudeOptions {
                claude_bin: PathBuf::new(),
                cwd,
                model: None,
                permission_mode: None,
                resume_from: None,
                fork: false,
                add_dirs: Vec::new(),
                session_id: None,
                effort: None,
            },
            log,
            parser: Mutex::new(ClaudeParser::new()),
            stdin: tokio::sync::Mutex::new(None),
            child: Mutex::new(None),
            pid,
            pending: Mutex::new(HashMap::new()),
            resolved: Mutex::new(HashMap::new()),
            replies: Mutex::new(HashMap::new()),
            sent_texts: Mutex::new(HashSet::new()),
            state: Mutex::new(State {
                status: "idle",
                liveness: Liveness::Live,
                native_session_id,
                created_at_ms: now,
                last_active_at_ms: now,
                model_override: None,
                effort: None,
                models: Value::Null,
                commands: Vec::new(),
            }),
            next_req: std::sync::atomic::AtomicU64::new(1),
            stderr_tail: Mutex::new(Vec::new()),
            remote: Some(tx),
            verdicts: Mutex::new(HashMap::new()),
            echoed: Mutex::new(HashSet::new()),
            permission_mode: Mutex::new(None),
            last_uuid: Mutex::new(None),
            origin,
            #[cfg(test)]
            test_out: None,
        })
    }

    pub fn is_attached(&self) -> bool {
        self.remote.is_some()
    }

    /// Frames from switchtether-shim (attached mode).
    pub async fn on_remote_frame(&self, frame: Value) {
        let parse = |f: &Value| {
            f.get("line")
                .and_then(|l| l.as_str())
                .and_then(|l| serde_json::from_str::<Value>(l).ok())
        };
        match frame.get("t").and_then(|t| t.as_str()) {
            Some("out") => {
                if let Some(v) = parse(&frame) {
                    self.on_message(v).await;
                }
            }
            Some("in") => {
                let Some(v) = parse(&frame) else { return };
                if v.get("type").and_then(|t| t.as_str()) == Some("user") {
                    // A prompt typed in the IDE: show it right away.
                    self.echo_user(&v, Some("desktop"));
                    self.set_status("busy");
                    self.publish_status("running", None);
                    return;
                }
                // The IDE changed model / effort / permission mode itself: what the phone shows
                // follows the window (a later change from the phone goes the same way).
                if v.get("type").and_then(|t| t.as_str()) == Some("control_request") {
                    self.observe_host_control(v.get("request").unwrap_or(&Value::Null));
                    return;
                }
                // The IDE answered a permission request itself.
                if v.get("type").and_then(|t| t.as_str()) == Some("control_response") {
                    let rid = v
                        .get("response")
                        .and_then(|r| r.get("request_id"))
                        .and_then(|r| r.as_str())
                        .map(String::from);
                    if let Some(rid) = rid {
                        let removed = self.pending.lock().remove(&rid);
                        if let Some(p) = removed {
                            self.resolved.lock().insert(rid.clone(), "desktop".into());
                            let behavior = v
                                .pointer("/response/response/behavior")
                                .and_then(|b| b.as_str())
                                .unwrap_or("answered")
                                .to_string();
                            // What was chosen in the IDE: AskUserQuestion answers come back in
                            // the allowed input (`updatedInput.answers`), an elicitation's in
                            // `content`.
                            let answers = v
                                .pointer("/response/response/updatedInput/answers")
                                .or_else(|| v.pointer("/response/response/content"))
                                .filter(|a| a.is_object())
                                .cloned();
                            let elicitation_action = v
                                .pointer("/response/response/action")
                                .and_then(Value::as_str)
                                .map(String::from);
                            self.log.lock().update(&p.event_id, true, |e| {
                                e.data["resolved"] = json!(true);
                                e.data["decision"] = json!(match (&answers, &elicitation_action) {
                                    (Some(_), _) if behavior != "deny" => "answer",
                                    (_, Some(a)) if a == "accept" => "answer",
                                    (_, Some(a)) if a == "decline" => "deny",
                                    (_, Some(_)) => "cancel",
                                    _ => behavior.as_str(),
                                });
                                e.data["by"] = json!("desktop");
                                if let Some(a) = &answers {
                                    crate::agents::model::record_answers(&mut e.data, a);
                                }
                            });
                            if self.pending.lock().is_empty() {
                                self.set_status("busy");
                                self.publish_status("running", None);
                            }
                        }
                    }
                }
            }
            Some(kind @ ("accepted" | "rejected")) => {
                if let Some(rid) = parse(&frame).and_then(|v| {
                    v.pointer("/response/request_id")
                        .and_then(|r| r.as_str())
                        .map(String::from)
                }) {
                    if let Some(tx) = self.verdicts.lock().remove(&rid) {
                        let _ = tx.send(kind == "accepted");
                    }
                }
            }
            Some("exit") => self.on_exit().await,
            _ => {}
        }
    }

    /// A control request the IDE sent to the attached process: keep model, effort and
    /// permission mode in step with the window's own picker.
    fn observe_host_control(&self, req: &Value) {
        match req.get("subtype").and_then(Value::as_str) {
            Some("set_model") => {
                self.state.lock().model_override = req
                    .get("model")
                    .and_then(Value::as_str)
                    .filter(|m| !m.is_empty() && *m != "default")
                    .map(String::from);
            }
            Some("apply_flag_settings") => {
                if let Some(e) = req.pointer("/settings/effortLevel") {
                    self.state.lock().effort = e.as_str().map(String::from);
                }
            }
            Some("set_permission_mode") => {
                if let Some(m) = req.get("mode").and_then(Value::as_str) {
                    *self.permission_mode.lock() = Some(m.to_string());
                }
            }
            _ => {}
        }
    }

    /// Plain text of a stream-json user message (None for tool results).
    fn user_text(v: &Value) -> Option<String> {
        let c = v.pointer("/message/content")?;
        if let Some(t) = c.as_str() {
            return Some(t.trim().to_string());
        }
        let arr = c.as_array()?;
        if arr
            .iter()
            .any(|b| b.get("type").and_then(|t| t.as_str()) == Some("tool_result"))
        {
            return None;
        }
        let t: Vec<&str> = arr
            .iter()
            .filter(|b| b.get("type").and_then(|t| t.as_str()) == Some("text"))
            .filter_map(|b| b.get("text").and_then(|t| t.as_str()))
            .collect();
        (!t.is_empty()).then(|| t.join("\n").trim().to_string())
    }

    /// Attached mode: whether a user message on the process's stdout is the replay
    /// (`--replay-user-messages`) of one already shown by [`Self::echo_user`]. A replay keeps the
    /// `uuid` of the message it repeats but not always its text: a slash command comes back as
    /// Claude Code's `<command-name>` record (the VS Code extension matches replays by `uuid`
    /// and replaces the text of its bubble). Without a `uuid` the text decides, a command record
    /// compared as the `/name args` it stands for.
    fn take_echo(&self, v: &Value) -> bool {
        let text = Self::user_text(v);
        let mut echoed = self.echoed.lock();
        if let Some(u) = v.get("uuid").and_then(Value::as_str) {
            if echoed.remove(&format!("uuid:{u}")) {
                if let Some(t) = &text {
                    echoed.remove(&format!("text:{t}"));
                    if let Some(c) = super::transcript::slash_command_text(t) {
                        echoed.remove(&format!("text:{c}"));
                    }
                }
                return true;
            }
        }
        let Some(t) = text else { return false };
        if echoed.remove(&format!("text:{t}")) {
            return true;
        }
        super::transcript::slash_command_text(&t)
            .is_some_and(|c| echoed.remove(&format!("text:{c}")))
    }

    /// Attached mode: record a user message that went into the agent.
    fn echo_user(&self, v: &Value, source: Option<&str>) {
        let Some(text) = Self::user_text(v) else {
            return;
        };
        {
            let mut echoed = self.echoed.lock();
            if let Some(u) = v.get("uuid").and_then(Value::as_str) {
                echoed.insert(format!("uuid:{u}"));
            }
            echoed.insert(format!("text:{text}"));
            // A message the replay never matches must not pile up.
            if echoed.len() > ECHOED_MAX {
                echoed.clear();
            }
        }
        let mut rec = v.clone();
        rec["uuid"] = json!(format!("in:{}", crate::util::new_id()));
        rec["timestamp"] = json!(crate::util::now_rfc3339());
        let drafts = self.parser.lock().feed(&rec);
        let mut log = self.log.lock();
        for mut d in drafts.into_iter().filter(own_card_only) {
            if let Some(src) = source {
                if d.kind == "user_message" {
                    d.data["source"] = json!(src);
                }
            }
            log.upsert(d, true);
        }
    }

    async fn write_line(&self, v: &Value) -> ApiResult<()> {
        #[cfg(test)]
        if let Some(tx) = &self.test_out {
            let _ = tx.send(v.to_string());
            return Ok(());
        }
        if let Some(tx) = &self.remote {
            return tx
                .send(json!({ "t": "inject", "line": v.to_string() }).to_string())
                .map_err(|_| ApiError::conflict("the attached session has disconnected"));
        }
        let mut guard = self.stdin.lock().await;
        let Some(stdin) = guard.as_mut() else {
            return Err(ApiError::conflict("session process has exited"));
        };
        let mut s = v.to_string();
        s.push('\n');
        stdin
            .write_all(s.as_bytes())
            .await
            .map_err(|e| ApiError::internal("claude stdin", e))?;
        stdin
            .flush()
            .await
            .map_err(|e| ApiError::internal("claude stdin", e))
    }

    async fn control_request(&self, request: Value, timeout: Duration) -> ApiResult<Value> {
        let rid = format!(
            "st_{}",
            self.next_req
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        );
        let (tx, rx) = oneshot::channel();
        self.replies.lock().insert(rid.clone(), tx);
        self.write_line(
            &json!({ "type": "control_request", "request_id": rid, "request": request }),
        )
        .await?;
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(v)) => {
                if v.get("subtype").and_then(|s| s.as_str()) == Some("error") {
                    Err(ApiError::bad_request(
                        v.get("error")
                            .and_then(|e| e.as_str())
                            .unwrap_or("control request failed"),
                    ))
                } else {
                    Ok(v)
                }
            }
            _ => {
                self.replies.lock().remove(&rid);
                Err(ApiError::internal("claude control", "timed out"))
            }
        }
    }

    pub async fn send_message(&self, text: &str) -> ApiResult<()> {
        self.send_message_with_images(text, &[]).await
    }

    pub async fn send_message_with_images(
        &self,
        text: &str,
        images: &[crate::agents::managed::ImageAttachment],
    ) -> ApiResult<()> {
        if self.liveness() != Liveness::Live {
            return Err(ApiError::conflict("session process has exited"));
        }
        let mut blocks: Vec<Value> = Vec::new();
        if !text.trim().is_empty() {
            blocks.push(json!({ "type": "text", "text": text }));
        }
        for img in images {
            blocks.push(json!({
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": img.media_type,
                    "data": img.data_base64,
                }
            }));
        }
        let mut msg = json!({
            "type": "user",
            "session_id": "",
            "parent_tool_use_id": null,
            "message": { "role": "user", "content": blocks },
        });
        if self.remote.is_none() {
            self.sent_texts.lock().insert(text.trim().to_string());
        }
        // Our own id: a message queued while the agent works can be cancelled by it.
        let uuid = crate::util::new_id();
        msg["uuid"] = json!(uuid);
        *self.last_uuid.lock() = Some(uuid);
        self.write_line(&msg).await?;
        if self.remote.is_some() {
            self.echo_user(&msg, Some("phone"));
        }
        self.set_status("busy");
        self.publish_status("running", None);
        Ok(())
    }

    pub fn permission_mode(&self) -> Option<String> {
        self.permission_mode.lock().clone()
    }

    pub fn last_message_uuid(&self) -> Option<String> {
        self.last_uuid.lock().clone()
    }

    /// One control request of the IDE's session tools (the caller builds and whitelists it);
    /// returns its `response` object. In an attached session (the opt-in takeover hook) it goes
    /// through switchtether-shim like the phone's messages and approvals; the IDE ignores the
    /// reply to a request it did not send (an unmatched `control_response`).
    pub async fn control(&self, request: Value, timeout: Duration) -> ApiResult<Value> {
        if self.liveness() != Liveness::Live {
            return Err(ApiError::conflict("session process has exited"));
        }
        let subtype = request
            .get("subtype")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let v = self.control_request(request.clone(), timeout).await?;
        let response = v.get("response").cloned().unwrap_or(Value::Null);
        match subtype.as_str() {
            "set_permission_mode" => {
                let mode = response
                    .get("mode")
                    .or_else(|| request.get("mode"))
                    .and_then(Value::as_str)
                    .map(String::from);
                *self.permission_mode.lock() = mode;
            }
            "rename_session" => {
                if let Some(t) = request.get("title").and_then(Value::as_str) {
                    self.parser.lock().title = Some(t.to_string());
                }
            }
            _ => {}
        }
        Ok(response)
    }

    /// Stop one background task or sub-agent (`stop_task`, as the SDK's `stopTask` and the IDE's
    /// "Stop agent"), in a process SwitchTether started or an attached one (opt-in hook).
    pub async fn stop_task(&self, task_id: &str) -> ApiResult<()> {
        self.control_request(
            json!({ "subtype": "stop_task", "task_id": task_id }),
            Duration::from_secs(15),
        )
        .await
        .map(|_| ())
    }

    pub async fn interrupt(&self) -> ApiResult<()> {
        self.control_request(json!({ "subtype": "interrupt" }), Duration::from_secs(15))
            .await
            .map(|_| ())
    }

    /// Answer a pending permission request / question. First answer wins;
    /// later answers get 409 with the resolver.
    pub async fn respond(
        &self,
        request_id: &str,
        decision: &str,
        message: Option<String>,
        updated_input: Option<Value>,
        answers: Option<Value>,
        by: &str,
    ) -> ApiResult<Value> {
        let pending = {
            let mut p = self.pending.lock();
            match p.remove(request_id) {
                Some(x) => x,
                None => {
                    let by = self.resolved.lock().get(request_id).cloned();
                    return Err(match by {
                        Some(b) => ApiError::conflict("this request was already answered")
                            .with_details(json!({ "resolvedBy": b })),
                        None => ApiError::not_found("no such pending request"),
                    });
                }
            }
        };
        self.resolved
            .lock()
            .insert(request_id.to_string(), by.to_string());
        let shown_answers = answers
            .clone()
            .filter(|_| matches!(decision, "allow" | "answer"));
        let response = match (decision, &pending.elicitation) {
            // MCP `ElicitResult`, used as the response payload as it is.
            ("allow" | "answer", Some(params)) => {
                let mut r = json!({ "action": "accept" });
                // A URL elicitation only confirms; a form returns the fields.
                if params.get("mode").and_then(Value::as_str) != Some("url") {
                    r["content"] = crate::agents::codex::managed::build_elicitation_content(
                        params,
                        answers.as_ref(),
                    );
                }
                r
            }
            ("deny", Some(_)) => json!({ "action": "decline" }),
            ("cancel" | "abort", Some(_)) => json!({ "action": "cancel" }),
            ("allow" | "allow_session" | "answer", None) => {
                let mut input = updated_input.unwrap_or_else(|| pending.input.clone());
                if let Some(a) = answers {
                    input["answers"] = a;
                }
                let mut r = json!({ "behavior": "allow", "updatedInput": input });
                if decision == "allow_session" && pending.suggestions.is_array() {
                    r["updatedPermissions"] = pending.suggestions.clone();
                }
                r
            }
            ("deny" | "abort" | "cancel", None) => json!({
                "behavior": "deny",
                "message": message.unwrap_or_else(|| if decision == "cancel" {
                    "Cancelled from SwitchTether on the phone".into()
                } else {
                    "Denied from SwitchTether on the phone".into()
                }),
                "interrupt": decision == "abort",
            }),
            (other, _) => {
                // Put it back: invalid decision must not consume the request.
                self.resolved.lock().remove(request_id);
                self.pending.lock().insert(request_id.to_string(), pending);
                return Err(ApiError::validation(format!("unknown decision '{other}'")));
            }
        };
        let verdict = if self.remote.is_some() {
            let (tx, rx) = oneshot::channel();
            self.verdicts.lock().insert(request_id.to_string(), tx);
            Some(rx)
        } else {
            None
        };
        self.write_line(&json!({
            "type": "control_response",
            "response": { "subtype": "success", "request_id": request_id, "response": response },
        }))
        .await?;
        if let Some(rx) = verdict {
            // The IDE may have answered in the same instant: the shim decides.
            if let Ok(Ok(false)) = tokio::time::timeout(std::time::Duration::from_secs(5), rx).await
            {
                self.resolved
                    .lock()
                    .insert(request_id.to_string(), "desktop".into());
                return Err(ApiError::conflict(
                    "this request was already answered on the computer",
                )
                .with_details(json!({ "resolvedBy": "desktop" })));
            }
        }
        let ev = self.log.lock().update(&pending.event_id, true, |e| {
            e.data["resolved"] = json!(true);
            e.data["decision"] = json!(decision);
            e.data["by"] = json!(by);
            if let Some(a) = &shown_answers {
                crate::agents::model::record_answers(&mut e.data, a);
            }
        });
        if self.pending.lock().is_empty() {
            self.set_status("busy");
            self.publish_status("running", None);
        }
        let _ = pending.tool_name;
        Ok(json!({ "requestId": request_id, "decision": decision, "event": ev }))
    }

    pub fn kill(&self) {
        // Never kill a process the user started.
        if self.remote.is_some() {
            return;
        }
        if let Some(mut c) = self.child.lock().take() {
            let _ = c.start_kill();
        }
        let mut st = self.state.lock();
        if st.liveness == Liveness::Live {
            st.liveness = Liveness::Ended;
            st.status = "completed";
            st.last_active_at_ms = crate::util::now_ms();
        }
    }

    fn publish_status(&self, state: &str, detail: Option<&str>) {
        // Background agents / shells keep working after the turn's `result`; their count
        // tells the phone the session is idle but not finished.
        let background = self.parser.lock().background_tasks;
        let d = EventDraft::new(
            format!("status:{}", crate::util::now_ms()),
            "status",
            crate::util::now_rfc3339(),
            json!({ "state": state, "detail": detail, "backgroundTasks": background }),
        );
        // Status changes replace one rolling status event to keep the log small.
        let mut d = d;
        d.id = "status:current".into();
        self.log.lock().upsert(d, true);
    }

    async fn on_message(&self, v: Value) {
        match v.get("type").and_then(|t| t.as_str()) {
            Some("control_response") => {
                let resp = v.get("response").cloned().unwrap_or(Value::Null);
                // The initialize response lists the session's slash commands (so does a plugin
                // reload). In attached mode the IDE sent the request: its answer passes here too.
                if let Some(c) = resp.pointer("/response/commands").filter(|c| c.is_array()) {
                    let mut list = normalize_commands(c);
                    let mut st = self.state.lock();
                    // Names only known from `system`/`init` stay.
                    let known = std::mem::take(&mut st.commands);
                    merge_commands(&mut list, &Value::Array(known));
                    st.commands = list;
                }
                if let Some(rid) = resp.get("request_id").and_then(|r| r.as_str()) {
                    if let Some(tx) = self.replies.lock().remove(rid) {
                        let _ = tx.send(resp);
                    }
                }
            }
            Some("control_request") => self.on_control_request(v).await,
            Some("control_cancel_request") => {
                if let Some(rid) = v.get("request_id").and_then(|r| r.as_str()) {
                    let removed = self.pending.lock().remove(rid);
                    if let Some(p) = removed {
                        self.resolved.lock().insert(rid.to_string(), "agent".into());
                        self.log.lock().update(&p.event_id, true, |e| {
                            e.data["resolved"] = json!(true);
                            e.data["decision"] = json!("cancelled");
                            e.data["by"] = json!("agent");
                        });
                        // Nothing left to answer: the agent works on (as after an answer).
                        if self.pending.lock().is_empty() && self.status() == "waiting" {
                            self.set_status("busy");
                            self.publish_status("running", None);
                        }
                    }
                }
            }
            Some("user") if self.remote.is_some() && self.take_echo(&v) => {
                // Replay of a prompt already shown.
            }
            Some(_) => {
                let ty = v.get("type").and_then(|t| t.as_str()).unwrap_or("");
                // Claude Code reports its permission mode in `system` messages (`init`, and
                // `status` when it changes, e.g. leaving plan mode by itself): what the phone
                // shows follows the agent, not just the last value set from the phone.
                if let Some(mode) = v
                    .get("permissionMode")
                    .and_then(|m| m.as_str())
                    .filter(|m| ty == "system" && !m.is_empty())
                {
                    *self.permission_mode.lock() = Some(mode.to_string());
                }
                // `system`/`init` names the slash commands (names only): add the unknown ones.
                if let Some(names) = v.get("slash_commands").filter(|n| {
                    ty == "system"
                        && n.is_array()
                        && v.get("subtype").and_then(|s| s.as_str()) == Some("init")
                }) {
                    merge_commands(&mut self.state.lock().commands, names);
                }
                // `system`/`commands_changed` carries the whole new list (skills or plugins
                // loaded mid-session), in the initialize response's shape: it replaces the list,
                // as the VS Code extension 2.1.331 does (`latestCommands = commands`).
                if let Some(c) = v.get("commands").filter(|c| {
                    ty == "system"
                        && c.is_array()
                        && v.get("subtype").and_then(|s| s.as_str()) == Some("commands_changed")
                }) {
                    self.state.lock().commands = normalize_commands(c);
                }
                if let Some(sid) = v
                    .get("session_id")
                    .and_then(|s| s.as_str())
                    .filter(|s| !s.is_empty())
                {
                    self.state.lock().native_session_id = Some(sid.to_string());
                }
                let drafts = self.parser.lock().feed(&v);
                let mut log = self.log.lock();
                for mut d in drafts.into_iter().filter(own_card_only) {
                    if d.kind == "user_message" {
                        let text = d.data["text"].as_str().unwrap_or("").trim().to_string();
                        if self.sent_texts.lock().remove(&text) {
                            d.data["source"] = json!("phone");
                        }
                    }
                    log.upsert(d, true);
                }
                drop(log);
                match ty {
                    "result" => {
                        self.set_status("idle");
                        self.publish_status("idle", None);
                    }
                    "assistant" | "stream_event" if self.status() != "waiting" => {
                        self.set_status("busy");
                    }
                    "system"
                        if v.get("subtype").and_then(|s| s.as_str())
                            == Some("background_tasks_changed")
                            && self.status() == "idle" =>
                    {
                        self.publish_status("idle", None);
                    }
                    _ => {}
                }
            }
            None => {}
        }
    }

    async fn on_control_request(&self, v: Value) {
        let rid = v
            .get("request_id")
            .and_then(|r| r.as_str())
            .unwrap_or("")
            .to_string();
        let req = v.get("request").cloned().unwrap_or(Value::Null);
        match req.get("subtype").and_then(|s| s.as_str()) {
            Some("can_use_tool") => {
                let tool = req
                    .get("tool_name")
                    .and_then(|t| t.as_str())
                    .unwrap_or("")
                    .to_string();
                let input = req.get("input").cloned().unwrap_or(Value::Null);
                let suggestions = req
                    .get("permission_suggestions")
                    .cloned()
                    .unwrap_or(Value::Null);
                let ts = crate::util::now_rfc3339();
                let event_id = format!("req:{rid}");
                let mut draft = if tool == "AskUserQuestion" {
                    let questions: Vec<Value> = input
                        .get("questions")
                        .and_then(|q| q.as_array())
                        .cloned()
                        .unwrap_or_default()
                        .into_iter()
                        .enumerate()
                        .map(|(i, q)| json!({
                            "id": q.get("question").cloned().unwrap_or(json!(i.to_string())),
                            "header": q.get("header"), "question": q.get("question"),
                            "multiSelect": q.get("multiSelect").cloned().unwrap_or(json!(false)),
                            "allowOther": true,
                            "options": q.get("options").cloned().unwrap_or(json!([])),
                        }))
                        .collect();
                    EventDraft::new(
                        &event_id,
                        "question",
                        ts,
                        json!({
                            "requestId": rid, "questions": questions, "resolved": false,
                        }),
                    )
                } else {
                    let category = crate::agents::model::tool_category(&tool);
                    let kind = match category {
                        "shell" => "command",
                        "edit" | "write" => "file_change",
                        "web" => "network",
                        _ => "tool",
                    };
                    let title = crate::agents::model::tool_title(&tool, &input);
                    let mut options = vec![json!({ "id": "allow", "label": "Allow" })];
                    if suggestions.as_array().is_some_and(|a| !a.is_empty()) {
                        options.push(json!({ "id": "allow_session", "label": "Always allow" }));
                    }
                    options.push(json!({ "id": "deny", "label": "Deny" }));
                    EventDraft::new(
                        &event_id,
                        "approval_request",
                        ts,
                        json!({
                            "requestId": rid, "kind": kind, "title": title, "toolName": tool,
                            "detail": req.get("decision_reason").or(req.get("blocked_path")),
                            "command": input.get("command"), "cwd": self.options.cwd.to_string_lossy(),
                            "input": input, "options": options, "resolved": false,
                            "toolUseId": req.get("tool_use_id"),
                        }),
                    )
                };
                if let Some(p) = v
                    .get("parent_tool_use_id")
                    .or_else(|| req.get("parent_tool_use_id"))
                    .and_then(|x| x.as_str())
                {
                    draft.parent_id = Some(p.to_string());
                }
                self.pending.lock().insert(
                    rid,
                    Pending {
                        tool_name: tool,
                        input,
                        suggestions,
                        event_id,
                        elicitation: None,
                    },
                );
                self.log.lock().upsert(draft, true);
                self.set_status("waiting");
                self.publish_status("waiting_approval", None);
            }
            _ if self.remote.is_some() => {
                // Hooks / SDK MCP / elicitation belong to the IDE that owns the process.
            }
            Some("elicitation") => {
                // An MCP server asks the user something. Fields as the VS Code extension
                // (2.1.331) reads them: `mcp_server_name`, `message`, `mode`, `url`,
                // `elicitation_id`, `requested_schema`, `title`, `display_name`, `description`.
                let text = |k: &str| {
                    req.get(k)
                        .and_then(Value::as_str)
                        .map(str::trim)
                        .filter(|t| !t.is_empty())
                };
                // Redacted like the rest of what the agent shows the phone (the URL is not: the
                // user has to open it).
                let message = crate::redact::redact_text(
                    text("message")
                        .or(text("description"))
                        .or(text("title"))
                        .unwrap_or("Input requested"),
                );
                let server = text("display_name").or(text("mcp_server_name"));
                let params = json!({
                    "mode": req.get("mode"), "message": message, "serverName": server,
                    "url": req.get("url"), "requestedSchema": req.get("requested_schema"),
                });
                let mut questions =
                    crate::agents::codex::managed::elicitation_to_questions(&params);
                // Form fields are titled after their property; the server's message is what
                // the user is asked: alone for a single field, above the first one otherwise.
                if params
                    .pointer("/requestedSchema/properties")
                    .and_then(Value::as_object)
                    .is_some_and(|p| !p.is_empty())
                    && params["mode"] != "url"
                {
                    let single = questions.len() == 1;
                    if let Some(q) = questions.first_mut() {
                        let field = q["question"].as_str().unwrap_or("").to_string();
                        q["question"] = json!(if single {
                            message.clone()
                        } else {
                            format!("{message}\n{field}")
                        });
                    }
                }
                let event_id = format!("req:{rid}");
                let mut data = json!({
                    "requestId": rid, "questions": questions, "cancellable": true,
                    "elicitationMode": req.get("mode"), "serverName": server,
                    "message": message, "resolved": false,
                });
                if let Some(u) = crate::agents::codex::managed::elicitation_url(&params) {
                    data["url"] = json!(u);
                }
                let draft =
                    EventDraft::new(&event_id, "question", crate::util::now_rfc3339(), data);
                self.pending.lock().insert(
                    rid,
                    Pending {
                        tool_name: "elicitation".into(),
                        input: Value::Null,
                        suggestions: Value::Null,
                        event_id,
                        elicitation: Some(params),
                    },
                );
                self.log.lock().upsert(draft, true);
                self.set_status("waiting");
                self.publish_status("waiting_input", None);
            }
            _ => {
                // Other host callbacks (hooks, SDK MCP) — we registered none and the phone
                // cannot run them, so decline explicitly instead of hanging.
                let _ = self
                    .write_line(&json!({
                        "type": "control_response",
                        "response": { "subtype": "error", "request_id": rid, "error": "not supported by SwitchTether" },
                    }))
                    .await;
            }
        }
    }

    async fn on_exit(&self) {
        *self.stdin.lock().await = None;
        let code = self
            .child
            .lock()
            .as_mut()
            .and_then(|c| c.try_wait().ok().flatten())
            .and_then(|s| s.code());
        {
            let mut st = self.state.lock();
            st.liveness = Liveness::Ended;
            st.status = "completed";
        }
        let pending: Vec<(String, Pending)> = self.pending.lock().drain().collect();
        for (rid, p) in pending {
            self.resolved.lock().insert(rid, "agent".into());
            self.log.lock().update(&p.event_id, true, |e| {
                e.data["resolved"] = json!(true);
                e.data["decision"] = json!("cancelled");
                e.data["by"] = json!("agent");
            });
        }
        for (_, tx) in self.replies.lock().drain() {
            let _ = tx.send(json!({ "subtype": "error", "error": "process exited" }));
        }
        self.publish_status(
            "completed",
            Some(&match code {
                Some(c) => format!("process exited ({c})"),
                None => "process exited".into(),
            }),
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn attached() -> Arc<ManagedClaude> {
        let (tx, _rx) = tokio::sync::mpsc::unbounded_channel();
        ManagedClaude::attached(
            "cc".into(),
            PathBuf::from("."),
            Arc::new(Mutex::new(SessionLog::new("cc", None))),
            tx,
            "vscode",
        )
    }

    async fn out(m: &ManagedClaude, line: Value) {
        m.on_remote_frame(json!({ "t": "out", "line": line.to_string() }))
            .await;
    }

    fn names(list: &[Value]) -> Vec<&str> {
        list.iter().map(|c| c["name"].as_str().unwrap()).collect()
    }

    #[test]
    fn commands_are_normalised_deduplicated_and_clipped() {
        let long = "x".repeat(1000);
        let list = normalize_commands(&json!([
            {"name": "/compact", "description": " Summarise ", "argumentHint": "<instructions>",
             "aliases": [], "builtin": true},
            {"name": "compact", "description": "again"},
            "/clear",
            "clear",
            {"name": "review", "description": long, "argumentHint": long, "plugin": "my-plugin", "path": "C:/secret",
             "aliases": ["/rv", "rv", "review", long, 7, "a\nb", "", "r3", "r4", "r5", "r6"]},
            {"name": long},
            {"name": "  "},
            {"name": "two\nlines"},
            {"description": "no name"},
            42,
            null
        ]));
        assert_eq!(names(&list), ["compact", "clear", "review", &long[..80]]);
        assert_eq!(
            list[0],
            json!({"name": "compact", "description": "Summarise", "argumentHint": "<instructions>"})
        );
        assert_eq!(
            list[1],
            json!({"name": "clear", "description": "", "argumentHint": ""})
        );
        assert_eq!(list[2]["description"].as_str().unwrap().len(), 300);
        assert_eq!(list[2]["argumentHint"].as_str().unwrap().len(), 120);
        // Aliases: no leading "/", clipped, unique, not the name itself, at most five; the key
        // is absent when there are none.
        assert_eq!(
            list[2]["aliases"],
            json!(["rv", &long[..80], "r3", "r4", "r5"])
        );
        assert!(list[0].get("aliases").is_none());
        // Nothing else the agent says about a command is passed on.
        assert_eq!(list[2].as_object().unwrap().len(), 4);
        assert!(list
            .iter()
            .enumerate()
            .all(|(i, c)| i == 2 || c.as_object().unwrap().len() == 3));
        assert!(!json!(list).to_string().contains("my-plugin"));
        // Absent or malformed lists are empty, not errors.
        assert!(normalize_commands(&Value::Null).is_empty());
        assert!(normalize_commands(&json!({"commands": "x"})).is_empty());
    }

    #[tokio::test]
    async fn initialize_response_and_init_message_fill_the_command_list() {
        let m = attached();
        assert!(m.commands().is_empty());
        // An initialize response without `commands` leaves the list empty.
        out(
            &m,
            json!({"type": "control_response", "response": {"subtype": "success", "request_id": "x1",
                "response": {"models": [{"value": "default"}]}}}),
        )
        .await;
        assert!(m.commands().is_empty());
        // `system`/`init` only has names.
        out(
            &m,
            json!({"type": "system", "subtype": "init", "session_id": "s1",
                "slash_commands": ["compact", "/my-skill"]}),
        )
        .await;
        assert_eq!(names(&m.commands()), ["compact", "my-skill"]);
        assert_eq!(m.commands()[0]["description"], "");
        // The initialize response has the details; names it does not list are kept after it.
        out(
            &m,
            json!({"type": "control_response", "response": {"subtype": "success", "request_id": "x2",
                "response": {"commands": [
                    {"name": "clear", "description": "Start over", "argumentHint": ""},
                    {"name": "compact", "description": "Summarise", "argumentHint": "<instructions>"}
                ]}}}),
        )
        .await;
        let list = m.commands();
        assert_eq!(names(&list), ["clear", "compact", "my-skill"]);
        assert_eq!(list[1]["description"], "Summarise");
        // A later `init` adds only unknown names and keeps the descriptions.
        out(
            &m,
            json!({"type": "system", "subtype": "init", "slash_commands": ["compact", "review"]}),
        )
        .await;
        let list = m.commands();
        assert_eq!(names(&list), ["clear", "compact", "my-skill", "review"]);
        assert_eq!(list[1]["argumentHint"], "<instructions>");
        // Other `system` messages never touch the list.
        out(
            &m,
            json!({"type": "system", "subtype": "status", "slash_commands": ["nope"]}),
        )
        .await;
        assert_eq!(m.commands().len(), 4);
        // Gone with the process.
        m.on_remote_frame(json!({ "t": "exit" })).await;
        assert!(m.commands().is_empty());
    }

    /// A session SwitchTether "started" whose stdin is a channel: the test plays the agent.
    fn fake() -> (
        Arc<ManagedClaude>,
        tokio::sync::mpsc::UnboundedReceiver<String>,
    ) {
        let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
        let (unused, _) = tokio::sync::mpsc::unbounded_channel();
        let log = Arc::new(Mutex::new(SessionLog::new("claude:t", None)));
        let mut me = ManagedClaude::attached(
            "claude:t".into(),
            PathBuf::from("."),
            log,
            unused,
            "managed",
        );
        let m = Arc::get_mut(&mut me).unwrap();
        m.remote = None;
        m.test_out = Some(tx);
        (me, rx)
    }

    fn sent(rx: &mut tokio::sync::mpsc::UnboundedReceiver<String>) -> Value {
        serde_json::from_str(&rx.try_recv().expect("a line for the agent")).unwrap()
    }

    async fn elicit(m: &ManagedClaude, rid: &str, request: Value) -> Value {
        let mut request = request;
        request["subtype"] = json!("elicitation");
        m.on_message(json!({ "type": "control_request", "request_id": rid, "request": request }))
            .await;
        let ev = m.log.lock().get(&format!("req:{rid}")).cloned().unwrap();
        assert_eq!(ev.kind, "question");
        assert_eq!(ev.data["resolved"], false);
        ev.data
    }

    #[tokio::test]
    async fn elicitation_form_is_a_question_answered_with_typed_content() {
        let (m, mut rx) = fake();
        let data = elicit(
            &m,
            "e1",
            json!({
                "mcp_server_name": "deploy", "message": "Deploy to which target?", "mode": "form",
                "elicitation_id": "x",
                "requested_schema": { "type": "object", "required": ["target"], "properties": {
                    "target": { "type": "string", "enum": ["staging", "production"] },
                    "dryRun": { "type": "boolean", "title": "Dry run" },
                    "note": { "type": "string", "title": "Note" },
                } },
            }),
        )
        .await;
        assert_eq!(m.status(), "waiting");
        assert_eq!(data["cancellable"], true);
        assert_eq!(data["serverName"], "deploy");
        let qs = data["questions"].as_array().unwrap();
        assert_eq!(qs.len(), 3);
        let q = |id: &str| qs.iter().find(|q| q["id"] == id).unwrap();
        assert_eq!(q("target")["options"][1]["label"], "production");
        assert_eq!(q("target")["allowOther"], false);
        assert_eq!(q("target")["required"], true);
        assert_eq!(q("dryRun")["options"].as_array().unwrap().len(), 2);
        assert_eq!(q("note")["allowOther"], true, "free text");
        assert!(
            qs[0]["question"]
                .as_str()
                .unwrap()
                .starts_with("Deploy to which target?\n"),
            "the server's message leads: {}",
            qs[0]["question"]
        );

        // An unknown decision does not consume the request.
        assert!(m
            .respond("e1", "allow_session", None, None, None, "phone")
            .await
            .is_err());
        let answers = json!({ "target": "staging", "dryRun": "true", "note": "a, b" });
        m.respond("e1", "answer", None, None, Some(answers), "phone")
            .await
            .unwrap();
        let line = sent(&mut rx);
        assert_eq!(line["type"], "control_response");
        assert_eq!(line["response"]["subtype"], "success");
        assert_eq!(line["response"]["request_id"], "e1");
        assert_eq!(
            line["response"]["response"],
            json!({ "action": "accept",
                "content": { "target": "staging", "dryRun": true, "note": "a, b" } })
        );
        let ev = m.log.lock().get("req:e1").cloned().unwrap();
        assert_eq!(ev.data["resolved"], true);
        assert_eq!(ev.data["answers"]["target"], "staging");
        // First answer wins.
        let again = m.respond("e1", "cancel", None, None, None, "other").await;
        assert_eq!(again.unwrap_err().code, crate::error::ErrorCode::Conflict);
    }

    #[tokio::test]
    async fn elicitation_single_field_url_cancel_and_decline() {
        let (m, mut rx) = fake();
        // One field: the message is the question.
        let data = elicit(
            &m,
            "e1",
            json!({ "mcp_server_name": "s", "message": "Proceed?",
                "requested_schema": { "type": "object",
                    "properties": { "ok": { "type": "boolean" } } } }),
        )
        .await;
        assert_eq!(data["questions"][0]["question"], "Proceed?");
        m.respond("e1", "deny", None, None, None, "phone")
            .await
            .unwrap();
        assert_eq!(
            sent(&mut rx)["response"]["response"],
            json!({ "action": "decline" })
        );

        // A URL to open: shown, confirmed without content.
        let data = elicit(
            &m,
            "e2",
            json!({ "mcp_server_name": "s", "display_name": "Sign-in",
                "message": "Open to sign in", "mode": "url", "url": "https://example.com/auth" }),
        )
        .await;
        assert_eq!(data["url"], "https://example.com/auth");
        assert_eq!(data["elicitationMode"], "url");
        assert_eq!(data["serverName"], "Sign-in");
        assert!(data["questions"][0]["question"]
            .as_str()
            .unwrap()
            .contains("https://example.com/auth"));
        let confirm = json!({ "__confirm__": "Accept" });
        m.respond("e2", "answer", None, None, Some(confirm), "phone")
            .await
            .unwrap();
        assert_eq!(
            sent(&mut rx)["response"]["response"],
            json!({ "action": "accept" })
        );

        // No schema: free text, nothing to fill in; cancel from the phone.
        let data = elicit(&m, "e3", json!({ "message": "Anything else?" })).await;
        assert_eq!(data["questions"][0]["allowOther"], true);
        m.respond("e3", "cancel", None, None, None, "phone")
            .await
            .unwrap();
        assert_eq!(
            sent(&mut rx)["response"]["response"],
            json!({ "action": "cancel" })
        );

        // The agent withdraws one itself: resolved, nothing is sent, a late answer gets 409.
        elicit(&m, "e4", json!({ "message": "Still there?" })).await;
        m.on_message(json!({ "type": "control_cancel_request", "request_id": "e4" }))
            .await;
        let ev = m.log.lock().get("req:e4").cloned().unwrap();
        assert_eq!(ev.data["decision"], "cancelled");
        let late = m.respond("e4", "answer", None, None, None, "phone").await;
        assert_eq!(late.unwrap_err().code, crate::error::ErrorCode::Conflict);
        assert!(rx.try_recv().is_err());
        // Nothing is left to answer: the session is not stuck in "waiting".
        assert_eq!(m.status(), "busy");
        assert_eq!(
            m.log.lock().get("status:current").unwrap().data["state"],
            "running"
        );
    }

    /// A withdrawn request leaves the session waiting while another one is still open.
    #[tokio::test]
    async fn cancelling_one_of_two_requests_keeps_waiting() {
        let (m, _rx) = fake();
        elicit(&m, "a", json!({ "message": "First?" })).await;
        elicit(&m, "b", json!({ "message": "Second?" })).await;
        m.on_message(json!({ "type": "control_cancel_request", "request_id": "a" }))
            .await;
        assert_eq!(m.status(), "waiting");
        assert_eq!(m.pending_request_ids(), ["b"]);
    }

    /// What an MCP server sends is not trusted: its texts are redacted like other agent text, a
    /// URL the phone could open must be http(s), and a form without fields is a confirmation
    /// (not a question card with no questions).
    #[tokio::test]
    async fn elicitation_texts_are_redacted_and_urls_checked() {
        let (m, mut rx) = fake();
        let secret = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789";
        let data = elicit(
            &m,
            "e1",
            json!({ "mcp_server_name": "s", "message": format!("Use key {secret} to continue?"),
                "requested_schema": { "type": "object", "properties": {
                    "a": { "type": "string", "title": format!("Token {secret}"),
                           "description": "token=abc123" },
                    "b": { "type": "string" } } } }),
        )
        .await;
        let shown = data.to_string();
        assert!(!shown.contains(secret), "{shown}");
        assert!(!shown.contains("abc123"), "{shown}");
        assert!(data["message"]
            .as_str()
            .unwrap()
            .starts_with("Use key [REDACTED]"));
        m.respond("e1", "cancel", None, None, None, "phone")
            .await
            .unwrap();
        sent(&mut rx);

        for (rid, url) in [
            ("u1", "javascript:alert(1)"),
            ("u2", "intent://scan/#Intent;scheme=zxing;end"),
            ("u3", "file:///etc/passwd"),
            ("u4", "https://example.com/a b"),
        ] {
            let data = elicit(
                &m,
                rid,
                json!({ "message": "Open this", "mode": "url", "url": url }),
            )
            .await;
            assert!(data.get("url").is_none(), "{url} must not be offered");
            assert!(!data.to_string().contains(url), "{url} shown in {data}");
        }
        let data = elicit(
            &m,
            "u5",
            json!({ "message": "Open this", "mode": "url", "url": " HTTPS://example.com/ok?state=1 " }),
        )
        .await;
        assert_eq!(data["url"], "HTTPS://example.com/ok?state=1");

        let data = elicit(
            &m,
            "f1",
            json!({ "message": "Continue?", "requested_schema": { "type": "object", "properties": {} } }),
        )
        .await;
        let qs = data["questions"].as_array().unwrap();
        assert_eq!(qs.len(), 1);
        assert_eq!(qs[0]["id"], "__confirm__");
        assert_eq!(qs[0]["question"], "Continue?");
        m.respond(
            "f1",
            "answer",
            None,
            None,
            Some(json!({ "__confirm__": "ok" })),
            "phone",
        )
        .await
        .unwrap();
        assert_eq!(
            sent(&mut rx)["response"]["response"],
            json!({ "action": "accept", "content": {} })
        );
    }

    fn user_bubbles(m: &ManagedClaude) -> Vec<String> {
        m.log
            .lock()
            .iter()
            .filter(|e| e.kind == "user_message")
            .map(|e| e.data["text"].as_str().unwrap_or("").to_string())
            .collect()
    }

    /// Attached session: Claude Code replays a slash command under the `uuid` it was sent with
    /// but as its `<command-name>` record (the VS Code extension matches replays by `uuid` and
    /// replaces its bubble's text). One bubble per command, from the phone or the IDE; its output
    /// is a `system` note.
    #[tokio::test]
    async fn attached_slash_command_replay_is_not_a_second_bubble() {
        let (tx, mut shim) = tokio::sync::mpsc::unbounded_channel();
        let m = ManagedClaude::attached(
            "cc".into(),
            PathBuf::from("."),
            Arc::new(Mutex::new(SessionLog::new("cc", None))),
            tx,
            "vscode",
        );
        // From the phone (injected through the shim, shown at once).
        m.send_message("/compact keep the API notes").await.unwrap();
        let uuid = m.last_message_uuid().unwrap();
        // Sent as the text of an ordinary user message, as the VS Code extension does.
        let frame: Value = serde_json::from_str(&shim.try_recv().unwrap()).unwrap();
        let line: Value = serde_json::from_str(frame["line"].as_str().unwrap()).unwrap();
        assert_eq!(
            line["message"]["content"][0]["text"],
            "/compact keep the API notes"
        );
        assert_eq!(line["uuid"], uuid.as_str());
        out(
            &m,
            json!({ "type": "user", "uuid": uuid, "isReplay": true, "session_id": "s",
                "message": { "role": "user", "content":
                "<command-name>/compact</command-name>\n<command-message>compact</command-message>\n<command-args>keep the API notes</command-args>" } }),
        )
        .await;
        assert_eq!(user_bubbles(&m), ["/compact keep the API notes"]);

        // Typed in the IDE: the host's own frame, then the replay.
        m.on_remote_frame(json!({ "t": "in", "line": json!({ "type": "user", "uuid": "ide-1",
            "session_id": "", "parent_tool_use_id": null,
            "message": { "role": "user", "content": [{ "type": "text", "text": "/cost" }] } }).to_string() }))
            .await;
        out(
            &m,
            json!({ "type": "user", "uuid": "ide-1", "isReplay": true,
                "message": { "role": "user", "content": "<command-name>/cost</command-name>\n<command-message>cost</command-message>\n<command-args></command-args>" } }),
        )
        .await;
        // Without a uuid the record still matches the text it stands for.
        m.on_remote_frame(json!({ "t": "in", "line": json!({ "type": "user",
            "message": { "role": "user", "content": "/context" } }).to_string() }))
            .await;
        out(
            &m,
            json!({ "type": "user",
                "message": { "role": "user", "content": "<command-name>/context</command-name>" } }),
        )
        .await;
        // The command's output (a replayed user message of its own) is shown once, as a note.
        out(
            &m,
            json!({ "type": "user", "uuid": "o1", "isReplay": true,
                "message": { "role": "user", "content": "<local-command-stdout>Total cost: $0.12</local-command-stdout>" } }),
        )
        .await;
        assert_eq!(
            user_bubbles(&m),
            ["/compact keep the API notes", "/cost", "/context"]
        );
        let notes: Vec<String> = m
            .log
            .lock()
            .iter()
            .filter(|e| e.kind == "system" && e.data["subtype"] == "command")
            .map(|e| e.data["text"].as_str().unwrap().to_string())
            .collect();
        assert_eq!(notes, ["Total cost: $0.12"]);
        // A slash command never names the conversation.
        assert!(m.title().is_none());
        // Nothing of the matched messages is kept.
        assert!(m.echoed.lock().is_empty());
    }

    /// `system`/`commands_changed` (skills / plugins loaded mid-session) replaces the list.
    #[tokio::test]
    async fn commands_changed_replaces_the_command_list() {
        let m = attached();
        out(
            &m,
            json!({"type": "control_response", "response": {"subtype": "success", "request_id": "i",
            "response": {"commands": [
                {"name": "compact", "description": "Summarise", "argumentHint": ""},
                {"name": "old-skill", "description": "", "argumentHint": ""}
            ]}}}),
        )
        .await;
        out(
            &m,
            json!({"type": "system", "subtype": "commands_changed", "session_id": "s", "commands": [
                {"name": "compact", "description": "Summarise", "argumentHint": "<instructions>"},
                {"name": "new-skill", "description": "Fresh", "argumentHint": "", "plugin": "p"}
            ]}),
        )
        .await;
        let list = m.commands();
        assert_eq!(names(&list), ["compact", "new-skill"]);
        assert_eq!(list[0]["argumentHint"], "<instructions>");
        assert!(list[1].get("plugin").is_none());
        // Malformed: ignored, the list stays.
        out(
            &m,
            json!({"type": "system", "subtype": "commands_changed", "commands": "x"}),
        )
        .await;
        assert_eq!(m.commands().len(), 2);
    }

    #[tokio::test]
    async fn hook_callbacks_are_still_declined() {
        let (m, mut rx) = fake();
        m.on_message(json!({ "type": "control_request", "request_id": "h1",
            "request": { "subtype": "hook_callback", "callback_id": "c" } }))
            .await;
        let line = sent(&mut rx);
        assert_eq!(line["response"]["subtype"], "error");
        assert_eq!(line["response"]["request_id"], "h1");
        assert!(m.pending_request_ids().is_empty());
    }
}
