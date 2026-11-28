//! Agent adapters and the session hub.
//!
//! Three kinds of sessions are surfaced, and the API never blurs them:
//! - **Observed live sessions** the user started normally (Claude Code CLI /
//!   VS Code extension). Discovered read-only; their transcript is tailed for
//!   near-real-time events. In-place control is BLOCKED by upstream (no public
//!   interface) — see docs/AGENT_COMPATIBILITY.md.
//! - **History** from CC Switch's session index (all supported agents).
//! - **Managed sessions** explicitly created by SwitchTether (clearly labelled
//!   `origin: managed`), which are fully controllable.

pub mod agy;
pub mod attached_tty;
pub mod attachments;
pub mod claude;
pub mod codex;
pub mod dsh;
pub mod managed;
pub mod model;
pub mod model_probe;
pub mod owned_pids;
pub mod permission;
pub mod search;
pub mod store;

use std::collections::HashMap;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use parking_lot::{Mutex, RwLock};
use serde::Deserialize;
use serde_json::{json, Value};
use tokio_util::sync::CancellationToken;

use crate::backend::{CcSwitchBackend, IndexedSession};
use crate::error::{ApiError, ApiResult};
use crate::events::EventBus;
use crate::protocol::CapStatus;
use claude::managed::{ManagedClaude, ManagedClaudeOptions};
use claude::transcript::ClaudeParser;
use claude::ClaudeObserver;
use codex::managed::{ManagedCodex, ManagedCodexOptions};
use codex::rollout::CodexParser;
use dsh::DshParser;
use managed::ManagedSession;
use model::{Capabilities, Control, EventDraft, Liveness, Origin, SessionSummary};
use store::SessionLog;

/// (mtime, length, title) of a transcript when its title was last read.
type TitleCacheEntry = (Option<std::time::SystemTime>, u64, Option<String>);

pub const MAX_LOADED_SESSIONS: usize = 16;
pub const MAX_TRANSCRIPT_BYTES: u64 = 96 * 1024 * 1024;
const TAIL_INTERVAL: Duration = Duration::from_millis(800);
/// Metadata check of the Claude registry between full refreshes: a safety net when OS change
/// notifications work, the only fast path when they do not.
fn fallback_poll(watching: bool) -> Duration {
    if watching {
        Duration::from_secs(2)
    } else {
        Duration::from_millis(500)
    }
}

/// Next registry change notification; never resolves without a watch.
async fn recv_change(
    watch: &mut Option<(
        notify::RecommendedWatcher,
        tokio::sync::mpsc::UnboundedReceiver<()>,
    )>,
) -> Option<()> {
    match watch.as_mut() {
        Some((_, rx)) => rx.recv().await,
        None => std::future::pending().await,
    }
}

/// How long a published live session may be missing from the registry before it is reported
/// ended (a read can land in the middle of a rewrite).
const MISSING_GRACE: Duration = Duration::from_secs(5);

pub(crate) trait LineParser: Send {
    fn feed(&mut self, v: &Value) -> Vec<EventDraft>;
}
impl LineParser for ClaudeParser {
    fn feed(&mut self, v: &Value) -> Vec<EventDraft> {
        ClaudeParser::feed(self, v)
    }
}
impl LineParser for CodexParser {
    fn feed(&mut self, v: &Value) -> Vec<EventDraft> {
        CodexParser::feed(self, v)
    }
}

impl LineParser for DshParser {
    fn feed(&mut self, v: &Value) -> Vec<EventDraft> {
        DshParser::feed(self, v)
    }
}

impl LineParser for agy::transcript::AgyParser {
    fn feed(&mut self, v: &Value) -> Vec<EventDraft> {
        agy::transcript::AgyParser::feed(self, v)
    }
}

/// Last turn state of a DeepSeek Harness log, read without building events.
#[derive(Debug, Clone, Default, PartialEq)]
pub(crate) struct DshTurnState {
    /// A turn started and has not ended.
    pub open: bool,
    /// How the last ended turn ended (see `dsh::turn_end_kind`).
    pub last_end: Option<String>,
}

struct DshTurnProbe(Arc<Mutex<DshTurnState>>);

type DshTurnReader = (Tail, Arc<Mutex<DshTurnState>>);

impl LineParser for DshTurnProbe {
    fn feed(&mut self, v: &Value) -> Vec<EventDraft> {
        match v.get("type").and_then(Value::as_str) {
            Some("turn/start") => self.0.lock().open = true,
            Some("turn/end") => {
                let mut st = self.0.lock();
                st.open = false;
                st.last_end = Some(dsh::turn_end_kind(v.get("data").unwrap_or(&Value::Null)));
            }
            _ => {}
        }
        Vec::new()
    }
}

/// Live state of an observed DeepSeek Harness session from its log's last turn and last write:
/// a turn that is still open is working (a long tool call may write nothing for minutes); a
/// finished one is idle shortly after, then the session counts as ended. `None`: not live.
fn dsh_observed_status(turn: &DshTurnState, since_write: Duration) -> Option<&'static str> {
    if turn.open {
        (since_write < Duration::from_secs(10 * 60)).then_some("busy")
    } else if since_write < Duration::from_secs(120) {
        Some(if turn.last_end.as_deref() == Some("error") {
            "error"
        } else {
            "idle"
        })
    } else {
        None
    }
}

pub(crate) struct Tail {
    path: PathBuf,
    /// Bytes of the file consumed so far (compressed bytes for a zstd log).
    offset: u64,
    partial: Vec<u8>,
    parser: Box<dyn LineParser>,
    /// The file is a sequence of zstd frames (DeepSeek Harness `*.jsonl.zstd`).
    zstd: bool,
    /// Compressed bytes of a frame still being written.
    raw: Vec<u8>,
}

struct Loaded {
    log: Arc<Mutex<SessionLog>>,
    tail: Option<Tail>,
    last_access: Instant,
}

struct OwnedClaude {
    native: Vec<String>,
    pids: Vec<u32>,
}

impl OwnedClaude {
    fn covers(&self, l: &claude::live::LiveClaudeSession) -> bool {
        self.native.contains(&l.session_id) || l.pid.is_some_and(|p| self.pids.contains(&p))
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionQuery {
    pub agent: Option<String>,
    pub project: Option<String>,
    pub q: Option<String>,
    pub live_only: Option<bool>,
    pub cursor: Option<String>,
    pub limit: Option<usize>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateSession {
    pub agent: String,
    /// Working directory; defaults to the original session's cwd when continuing.
    #[serde(default)]
    pub cwd: String,
    pub prompt: Option<String>,
    pub model: Option<String>,
    pub permission_mode: Option<String>,
    /// Existing session (e.g. `claude:<id>`, `codex:<id>`) to continue.
    pub resume_from: Option<String>,
    /// `continue` (default; same conversation id) or `fork` (Claude only: new id
    /// with the same history).
    pub mode: Option<String>,
    /// Effort / reasoning level (Claude Code: low, medium, high, xhigh, max; Codex: none,
    /// minimal, low, medium, high, xhigh).
    #[serde(default)]
    pub effort: Option<String>,
}

/// Public id of a conversation SwitchTether runs: the agent's own conversation id with the usual
/// prefix (`claude:<id>`, `codex:<thread>`, `dsh:<id>`). The same id names the conversation in
/// history after the process ends or the desktop restarts, so the phone never loses it.
fn public_id(agent: &str, native: &str) -> String {
    let prefix = match agent {
        "claude_code" => "claude",
        other => other,
    };
    format!("{prefix}:{native}")
}

/// Model names are passed to the agent as one argument; keep them plain.
fn valid_model(m: &str) -> bool {
    !m.is_empty()
        && m.len() <= 100
        && m.chars()
            .all(|c| c.is_ascii_alphanumeric() || "-_.:/[]@".contains(c))
}

fn valid_effort(agent: &str, e: &str) -> bool {
    match agent {
        "claude_code" => matches!(e, "low" | "medium" | "high" | "xhigh" | "max"),
        "codex" => matches!(e, "none" | "minimal" | "low" | "medium" | "high" | "xhigh"),
        "agy" => agy::LEVELS.contains(&e),
        _ => false,
    }
}

/// Options for the next start of a conversation that is not running (see `AgentHub::next_start`),
/// and the options a conversation last ran with (`AgentHub::remembered`).
#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
struct NextStart {
    model: Option<String>,
    effort: Option<String>,
    permission_mode: Option<String>,
    /// Chosen on the phone while not running (`None` above then means "back to the default",
    /// not "unchanged"), so the previous run's choice must not fill it in.
    #[serde(skip)]
    model_set: bool,
    #[serde(skip)]
    effort_set: bool,
    #[serde(skip)]
    permission_mode_set: bool,
}

impl NextStart {
    /// What a session runs with now (model, effort, permission mode).
    fn of(m: &dyn ManagedSession) -> Self {
        Self {
            model: m.model(),
            effort: m.effort(),
            permission_mode: m.permission_mode().filter(|p| p != "default"),
            ..Self::default()
        }
    }

    /// Fill what the phone did not choose from [`previous`].
    fn or_previous(mut self, previous: NextStart) -> Self {
        if !self.model_set {
            self.model = self.model.or(previous.model);
        }
        if !self.effort_set {
            self.effort = self.effort.or(previous.effort);
        }
        if !self.permission_mode_set {
            self.permission_mode = self.permission_mode.or(previous.permission_mode);
        }
        self
    }
}

pub struct AgentHub {
    bus: Arc<EventBus>,
    backend: Arc<dyn CcSwitchBackend>,
    pub claude: ClaudeObserver,
    pub agy: agy::live::AgyObserver,
    managed: RwLock<HashMap<String, Arc<dyn ManagedSession>>>,
    codex_bin: Option<PathBuf>,
    dsh_bin: Option<PathBuf>,
    agy_bin: Option<PathBuf>,
    loaded: Mutex<HashMap<String, Loaded>>,
    /// DeepSeek Harness log path -> (len, is a sub-agent session) from its header.
    dsh_children: Mutex<HashMap<String, (u64, bool)>>,
    /// DeepSeek Harness log path -> incremental reader of its turn state (recently written logs).
    dsh_turns: Mutex<HashMap<PathBuf, DshTurnReader>>,
    /// Sink for readers that produce no events (`DshTurnProbe`).
    probe_log: Arc<Mutex<SessionLog>>,
    /// Claude Code sub-agent transcripts opened from the phone ("查看过程"), keyed
    /// `<session id>|<agent id>`; tailed on each request, dropped when idle.
    subagent_logs: Mutex<HashMap<String, Loaded>>,
    /// Model / effort / permission mode chosen on the phone for a conversation that is not
    /// running: applied when sending a message resumes it (session id -> options).
    next_start: Mutex<HashMap<String, NextStart>>,
    /// The model / effort / permission mode each conversation last ran with under SwitchTether
    /// (session id -> options), kept in `<data dir>/session-options.json` so a conversation
    /// resumed after its process ended — even after the desktop restarted — keeps what the phone
    /// chose instead of falling back to the agent's defaults.
    remembered: Mutex<HashMap<String, NextStart>>,
    remembered_path: RwLock<Option<PathBuf>>,
    /// The agents' own model lists (Claude Code initialize, Codex `model/list`), read by a short
    /// SwitchTether-owned probe process and cached (agent -> (when, list)).
    model_lists: Mutex<HashMap<String, (Instant, Value)>>,
    index_cache: Mutex<Option<(Instant, Arc<Vec<IndexedSession>>)>>,
    /// Claude transcript path -> (mtime, len, title); avoids re-reading unchanged transcripts.
    title_cache: Mutex<HashMap<PathBuf, TitleCacheEntry>>,
    ttys: RwLock<HashMap<String, Arc<attached_tty::AttachedTty>>>,
    /// Files attached from the phone (set by the server with its data directory).
    attachments: RwLock<Option<Arc<attachments::AttachmentStore>>>,
}

fn origin_of(name: &str) -> Origin {
    match name {
        "managed" => Origin::Managed,
        "vscode" => Origin::Vscode,
        "desktop_app" => Origin::DesktopApp,
        "cli" => Origin::Cli,
        _ => Origin::Unknown,
    }
}

fn agent_of_provider(provider_id: &str) -> String {
    match provider_id {
        "claude" | "claude_code" | "claude-code" => "claude_code".into(),
        other => other.to_string(),
    }
}

impl AgentHub {
    pub fn new(
        bus: Arc<EventBus>,
        backend: Arc<dyn CcSwitchBackend>,
        claude: ClaudeObserver,
    ) -> Arc<Self> {
        let sandboxed = cfg!(test) || claude.claude_dir != claude::live::claude_dir();
        Arc::new(Self {
            bus,
            backend,
            agy: agy::live::AgyObserver::new(if sandboxed {
                Vec::new()
            } else {
                agy::live::AgyObserver::default_roots()
            }),
            claude,
            managed: RwLock::new(HashMap::new()),
            codex_bin: codex::managed::find_codex_bin(),
            dsh_bin: dsh::managed::find_dsh_bin(),
            // Unit and sandboxed integration tests never depend on (or start) an agy on the machine.
            agy_bin: if sandboxed { None } else { agy::find_agy_bin() },
            loaded: Mutex::new(HashMap::new()),
            subagent_logs: Mutex::new(HashMap::new()),
            next_start: Mutex::new(HashMap::new()),
            remembered: Mutex::new(HashMap::new()),
            remembered_path: RwLock::new(None),
            model_lists: Mutex::new(HashMap::new()),
            dsh_children: Mutex::new(HashMap::new()),
            dsh_turns: Mutex::new(HashMap::new()),
            probe_log: Arc::new(Mutex::new(SessionLog::new("probe", None))),
            index_cache: Mutex::new(None),
            title_cache: Mutex::new(HashMap::new()),
            ttys: RwLock::new(HashMap::new()),
            attachments: RwLock::new(None),
        })
    }

    /// Where files attached from the phone are stored (`<data dir>/attachments`).
    pub fn set_attachment_root(&self, root: PathBuf) {
        let _ = std::fs::create_dir_all(&root);
        *self.attachments.write() = Some(Arc::new(attachments::AttachmentStore::new(root)));
    }

    /// Where the options conversations last ran with are kept (`<data dir>/session-options.json`).
    pub fn set_session_options_file(&self, path: PathBuf) {
        if let Some(map) = std::fs::read_to_string(&path)
            .ok()
            .and_then(|t| serde_json::from_str::<HashMap<String, NextStart>>(&t).ok())
        {
            *self.remembered.lock() = map;
        }
        *self.remembered_path.write() = Some(path);
    }

    /// Record what session [`id`] runs with now, for a later resume.
    fn remember_options(&self, id: &str) {
        let Some((_, m)) = self.find_managed(id) else {
            return;
        };
        let options = NextStart::of(m.as_ref());
        let text = {
            let mut r = self.remembered.lock();
            r.insert(id.to_string(), options);
            serde_json::to_string(&*r).ok()
        };
        if let (Some(path), Some(text)) = (self.remembered_path.read().clone(), text) {
            let tmp = path.with_extension("json.tmp");
            if std::fs::write(&tmp, text).is_ok() {
                let _ = std::fs::rename(&tmp, &path);
            }
        }
    }

    pub fn attachments(&self) -> ApiResult<Arc<attachments::AttachmentStore>> {
        self.attachments.read().clone().ok_or_else(|| {
            ApiError::unavailable_capability("BLOCKED", "attachments are not set up")
        })
    }

    pub fn bus(&self) -> Arc<EventBus> {
        self.bus.clone()
    }

    /// Register a session of a user-started process SwitchTether is attached to
    /// (switchtether-shim). Ended attached sessions beyond a small history are pruned.
    pub fn register_attached(&self, id: String, session: Arc<dyn ManagedSession>) {
        {
            let mut m = self.managed.write();
            let mut ended: Vec<(String, i64)> = m
                .iter()
                .filter(|(_, s)| s.origin() != "managed" && s.liveness() != Liveness::Live)
                .map(|(k, s)| (k.clone(), s.last_active_at_ms()))
                .collect();
            if ended.len() > 20 {
                ended.sort_by_key(|(_, t)| *t);
                for (k, _) in ended.iter().take(ended.len() - 20) {
                    m.remove(k);
                    self.ttys.write().remove(k);
                }
            }
            m.insert(id.clone(), session.clone());
        }
        // Keyed by its conversation (`claude:<uuid>`), it replaces a history view of the same
        // conversation, which must not tail the transcript a second time.
        self.loaded.lock().remove(&id);
        self.publish_session(&id);
    }

    pub fn register_tty(&self, id: String, tty: Arc<attached_tty::AttachedTty>) {
        self.ttys.write().insert(id.clone(), tty.clone());
        self.register_attached(id, tty);
    }

    pub fn publish_session(&self, id: &str) {
        let m = self.managed.read().get(id).cloned();
        if let Some(m) = m {
            let summary = self.managed_summary(id, m.as_ref());
            self.bus.publish("session.upserted", json!(summary));
        }
    }

    /// Load the existing conversation of an attached session (resumed in the
    /// IDE / opened in Codex Desktop) so the phone sees the whole history.
    pub async fn preload_history(
        &self,
        agent: &str,
        native_id: &str,
        cwd: Option<&str>,
        log: &Arc<Mutex<SessionLog>>,
    ) -> bool {
        self.history_tail(agent, native_id, cwd, log)
            .await
            .is_some()
    }

    async fn history_tail(
        &self,
        agent: &str,
        native_id: &str,
        cwd: Option<&str>,
        log: &Arc<Mutex<SessionLog>>,
    ) -> Option<Tail> {
        let path = match agent {
            "claude_code" => self.claude.transcript_path(cwd, native_id),
            "codex" => {
                *self.index_cache.lock() = None;
                self.index()
                    .await
                    .iter()
                    .find(|s| s.provider_id == "codex" && s.session_id == native_id)
                    .and_then(|s| s.source_path.clone())
                    .map(PathBuf::from)
                    .filter(|p| is_rollout_file(p) && p.exists())
            }
            "agy" => self.agy.transcript_path(native_id),
            _ => None,
        }?;
        let parser: Box<dyn LineParser> = match agent {
            "codex" => Box::new(CodexParser::new()),
            "agy" => Box::new(agy::transcript::AgyParser::new()),
            _ => Box::new(ClaudeParser::new()),
        };
        match load_file(&path, parser, log) {
            Ok(t) => Some(t),
            Err(e) => {
                log::warn!("[agents] could not preload history: {}", e.message);
                None
            }
        }
    }

    /// Terminal sessions: find the agent's own session id (Claude: live
    /// registry by pid, else by cwd/start time; Codex: newest rollout for the
    /// cwd), then follow its transcript; refresh status from the registry.
    pub async fn refresh_tty(&self, tty: &attached_tty::AttachedTty) {
        let cwd = tty.cwd().to_string_lossy().into_owned();
        if tty.agent() == "claude_code" {
            let live = self.claude.live_sessions().await;
            let claimed: Vec<String> = self
                .managed
                .read()
                .iter()
                .filter(|(k, _)| **k != tty.id)
                .filter_map(|(_, m)| m.native_session_id())
                .collect();
            let mine = live
                .iter()
                .find(|l| l.pid.is_some() && l.pid == tty.pid)
                .or_else(|| {
                    tty.native_session_id()
                        .and_then(|n| live.iter().find(|l| l.session_id == n))
                })
                .or_else(|| {
                    if !tty.needs_native_id() {
                        return None;
                    }
                    live.iter().find(|l| {
                        !claimed.contains(&l.session_id)
                            && l.cwd.as_deref().map(norm_path) == Some(norm_path(&cwd))
                            && l.started_at_ms.unwrap_or(0) >= tty.created_at_ms() - 10_000
                    })
                });
            if let Some(l) = mine {
                if tty.native_session_id().as_deref() != Some(l.session_id.as_str()) {
                    let tail = self
                        .history_tail("claude_code", &l.session_id, Some(&cwd), &tty.log())
                        .await;
                    tty.set_native(l.session_id.clone(), l.name.clone(), tail);
                }
                tty.update_live(l.status.clone(), l.waiting_for.clone());
            }
        } else if tty.agent() == "agy" {
            let claimed: Vec<String> = self
                .managed
                .read()
                .iter()
                .filter(|(k, _)| **k != tty.id)
                .filter_map(|(_, m)| m.native_session_id())
                .collect();
            let rows = self.agy.rows();
            let mine = tty
                .native_session_id()
                .and_then(|n| rows.get(&n).cloned())
                .or_else(|| {
                    if !tty.needs_native_id() {
                        return None;
                    }
                    rows.values()
                        .filter(|r| !r.is_subagent() && !claimed.contains(&r.conversation_id))
                        .filter(|r| r.cwd.as_deref().map(norm_path) == Some(norm_path(&cwd)))
                        .filter(|r| r.last_modified_ms.unwrap_or(0) >= tty.created_at_ms() - 10_000)
                        .max_by_key(|r| r.last_modified_ms.unwrap_or(0))
                        .cloned()
                });
            if let Some(r) = mine {
                if tty.native_session_id().as_deref() != Some(r.conversation_id.as_str()) {
                    let tail = self
                        .history_tail("agy", &r.conversation_id, Some(&cwd), &tty.log())
                        .await;
                    tty.set_native(r.conversation_id.clone(), r.title.clone(), tail);
                }
                let st = r
                    .observed_live(crate::util::now_ms())
                    .map(|(s, _)| s.to_string());
                tty.update_live(st, None);
            }
        } else if tty.needs_native_id() {
            *self.index_cache.lock() = None;
            let found = self
                .index()
                .await
                .iter()
                .filter(|s| s.provider_id == "codex")
                .filter(|s| s.project_dir.as_deref().map(norm_path) == Some(norm_path(&cwd)))
                .filter(|s| s.created_at_ms.unwrap_or(0) >= tty.created_at_ms() - 10_000)
                .max_by_key(|s| s.created_at_ms.unwrap_or(0))
                .map(|s| (s.session_id.clone(), s.title.clone()));
            if let Some((native, title)) = found {
                let tail = self
                    .history_tail("codex", &native, Some(&cwd), &tty.log())
                    .await;
                tty.set_native(native, title, tail);
            }
        }
        tty.tail_now();
    }

    /// Managed or attached session by id; `claude:<id>` / `codex:<id>` also
    /// resolve to the attached session controlling that conversation.
    fn find_managed(&self, id: &str) -> Option<(String, Arc<dyn ManagedSession>)> {
        let m = self.managed.read();
        if let Some(s) = m.get(id) {
            return Some((id.to_string(), s.clone()));
        }
        let (prefix, native) = id.split_once(':')?;
        let agent = match prefix {
            "claude" => "claude_code",
            "codex" => "codex",
            "dsh" => "dsh",
            "agy" => "agy",
            _ => return None,
        };
        m.iter()
            .filter(|(_, s)| s.agent() == agent)
            .filter(|(_, s)| s.liveness() == Liveness::Live)
            .find(|(_, s)| {
                s.native_session_id().as_deref().map(dsh::norm_id) == Some(dsh::norm_id(native))
            })
            .map(|(k, s)| (k.clone(), s.clone()))
    }

    /// Static capability matrix (docs/AGENT_COMPATIBILITY.md).
    pub fn capability_matrix(&self) -> Value {
        let attached = |terminal: bool| {
            let s = SessionSummary {
                id: String::new(),
                agent: String::new(),
                origin: Origin::Unknown,
                native_id: String::new(),
                title: None,
                cwd: None,
                project: None,
                liveness: Liveness::Live,
                status: String::new(),
                waiting_for: None,
                control: Control::Full,
                created_at_ms: None,
                last_active_at_ms: None,
                model: None,
                effort: None,
                git_branch: None,
                pid: None,
                managed: false,
                terminal_id: terminal.then(String::new),
                active_tasks: None,
                source_path: None,
            };
            attached_caps(&s)
        };
        json!([
            { "agent": "claude_code", "origin": "cli", "capabilities": observed_caps("claude_code"), "notes": "Started with plain `claude`: discovered via `claude agents --json`, transcript tailed read-only. Can be continued in a SwitchTether process." },
            { "agent": "claude_code", "origin": "cli", "takeover": "st-claude", "capabilities": attached(true), "notes": "Started with `st-claude` (opt-in launcher): the TUI is mirrored as a terminal; messages are typed into it; prompts are answered in the terminal view." },
            { "agent": "claude_code", "origin": "vscode", "capabilities": observed_caps("claude_code"), "notes": "Without the opt-in wrapper: observation only." },
            { "agent": "claude_code", "origin": "vscode", "takeover": "claudeProcessWrapper", "capabilities": attached(false), "notes": "With the opt-in claudeCode.claudeProcessWrapper hook: messages, approvals (first answer wins; the IDE dialog is dismissed) and interrupt from the phone." },
            { "agent": "claude_code", "origin": "managed", "capabilities": managed_caps(), "notes": "New sessions started by SwitchTether via the Agent SDK stream-json protocol with the user's normal settings, plugins, MCP and hooks." },
            { "agent": "codex", "origin": "cli", "capabilities": observed_caps("codex"), "notes": "Rollout files tailed read-only; `st-codex` mirrors the TUI as a terminal." },
            { "agent": "codex", "origin": "managed", "capabilities": managed_caps(), "notes": "New threads on SwitchTether's own codex app-server (stdio), using the normal Codex config and login." },
            { "agent": "codex", "origin": "desktop_app", "capabilities": observed_caps("codex"), "notes": "Without the opt-in hook: observation of rollouts only." },
            { "agent": "codex", "origin": "desktop_app", "takeover": "CODEX_CLI_PATH", "capabilities": attached(false), "notes": "With the opt-in CODEX_CLI_PATH hook: every thread loaded in Codex Desktop can be messaged, approved and interrupted from the phone." },
            { "agent": "dsh", "origin": "cli", "capabilities": observed_caps("dsh"), "notes": "DeepSeek Harness session logs ($DSH_HOME/sessions, zstd frames) tailed read-only; a log written in the last two minutes counts as live. Sub-agents are their own sessions (parentSession)." },
            { "agent": "agy", "origin": "cli", "capabilities": observed_caps("agy"), "notes": "Google Antigravity (agy CLI / IDE) conversations discovered via conversation_summaries.db and tailed live from transcript_full.jsonl (read-only). \"Continue on phone\" resumes the conversation on SwitchTether's own `agy acp` process." },
            { "agent": "agy", "origin": "cli", "takeover": "st-agy", "capabilities": attached(true), "notes": "Started with `st-agy` (opt-in launcher): the TUI is mirrored as a terminal; messages are typed into it; prompts are answered in the terminal view." },
            { "agent": "agy", "origin": "managed", "capabilities": agy_managed_caps(), "notes": "New or resumed conversations on SwitchTether's own `agy acp` process (Agent Client Protocol v1 over stdio) with the user's normal agy login and settings: prompt, cancel, permission requests, model / thinking level, session tools and agy's modes (default, accept edits, auto, plan, bypass permissions)." },
            { "agent": "dsh", "origin": "managed", "capabilities": managed_caps(), "notes": "New or resumed sessions on SwitchTether's own `dsh --profile acp` process (ACP v1 over stdio): prompt, cancel and permission requests; the timeline is read from the persisted session log." },
        ])
    }

    pub async fn health(&self) -> Value {
        json!({
            "claudeBin": self.claude.claude_bin.as_ref().map(|p| p.to_string_lossy().into_owned()),
            "claudeDir": self.claude.claude_dir.exists(),
            "codexHome": codex::codex_home().exists(),
            "managedSessions": self.managed.read().len(),
            "codexBin": self.codex_bin.as_ref().map(|p| p.to_string_lossy().into_owned()),
            "dshBin": self.dsh_bin.as_ref().map(|p| p.to_string_lossy().into_owned()),
            "dshHome": dsh::dsh_home().exists(),
            "agyBin": self.agy_bin.as_ref().map(|p| p.to_string_lossy().into_owned()),
            "loadedSessions": self.loaded.lock().len(),
        })
    }

    /// Conversation title of a Claude transcript (`/rename` > generated), cached per file version.
    fn claude_title(&self, path: &Path) -> Option<String> {
        let meta = std::fs::metadata(path).ok()?;
        let key = (meta.modified().ok(), meta.len());
        if let Some((m, l, t)) = self.title_cache.lock().get(path) {
            if (*m, *l) == key {
                return t.clone();
            }
        }
        let title =
            claude::transcript::tail_title(path).map(|title| crate::redact::redact_text(&title));
        let mut cache = self.title_cache.lock();
        if cache.len() > 512 {
            cache.clear();
        }
        cache.insert(path.to_path_buf(), (key.0, key.1, title.clone()));
        title
    }

    /// The session index (cached for 15 s), e.g. to name the sessions usage belongs to.
    pub async fn session_index(&self) -> Arc<Vec<IndexedSession>> {
        self.index().await
    }

    async fn index(&self) -> Arc<Vec<IndexedSession>> {
        if let Some((at, v)) = self.index_cache.lock().as_ref() {
            if at.elapsed() < Duration::from_secs(15) {
                return v.clone();
            }
        }
        let v = Arc::new(self.backend.session_index().await.unwrap_or_default());
        *self.index_cache.lock() = Some((Instant::now(), v.clone()));
        v
    }

    fn managed_summary(&self, id: &str, m: &dyn ManagedSession) -> SessionSummary {
        let cwd = m.cwd().to_string_lossy().into_owned();
        // Claude Code writes `custom-title` (`/rename`) and the generated `ai-title` only to the
        // transcript, never to stream-json or the terminal, so the parser of a managed or attached
        // session only ever knows the first prompt. The transcript title wins.
        let transcript_title = if m.agent() == "claude_code" {
            m.native_session_id()
                .and_then(|n| self.claude.transcript_path(Some(&cwd), &n))
                .and_then(|p| self.claude_title(&p))
        } else {
            None
        };
        SessionSummary {
            id: id.to_string(),
            agent: m.agent().into(),
            origin: origin_of(m.origin()),
            native_id: m.native_session_id().unwrap_or_default(),
            title: transcript_title
                .or_else(|| m.title())
                .or_else(|| {
                    (m.agent() == "agy")
                        .then(|| m.native_session_id())
                        .flatten()
                        .and_then(|cid| self.agy.row(&cid))
                        .and_then(|r| r.title)
                })
                .or_else(|| {
                    // Codex Desktop names its threads in its session index.
                    (m.agent() == "codex")
                        .then(|| m.native_session_id())
                        .flatten()
                        .and_then(|t| codex::indexed_thread_name(&t))
                })
                .or_else(|| {
                    self.index_cache.lock().as_ref().and_then(|(_, items)| {
                        items
                            .iter()
                            .find(|s| {
                                Some(s.session_id.as_str()) == m.native_session_id().as_deref()
                            })
                            .and_then(|s| s.title.clone())
                    })
                })
                .or_else(|| {
                    m.log()
                        .lock()
                        .iter()
                        .find(|e| e.kind == "user_message" && e.parent_id.is_none())
                        .and_then(|e| e.data.get("text").and_then(Value::as_str))
                        .and_then(|t| {
                            claude::transcript::clean_title(Some(model::preview(t, 120).0))
                        })
                }),
            project: SessionSummary::project_from_cwd(&cwd),
            cwd: Some(cwd),
            liveness: m.liveness(),
            status: m.status().into(),
            waiting_for: if m.has_pending_requests() {
                Some(
                    self.ttys
                        .read()
                        .get(id)
                        .and_then(|t| t.waiting_for())
                        .unwrap_or_else(|| "permission prompt".into()),
                )
            } else {
                None
            },
            control: if m.liveness() == Liveness::Live {
                Control::Full
            } else {
                Control::History
            },
            created_at_ms: Some(m.created_at_ms()),
            last_active_at_ms: Some(m.last_active_at_ms()),
            model: m.model(),
            effort: m.effort(),
            git_branch: None,
            pid: None,
            managed: m.origin() == "managed",
            terminal_id: m.terminal_id(),
            active_tasks: (m.liveness() == Liveness::Live)
                .then(|| active_task_count(&m.log().lock())),
            source_path: None,
        }
    }

    /// Claude sessions SwitchTether runs itself (managed or attached): their processes also appear
    /// in Claude Code's live registry and must never be reported a second time as observed.
    fn owned_claude(&self) -> OwnedClaude {
        let m = self.managed.read();
        OwnedClaude {
            native: m.values().filter_map(|s| s.native_session_id()).collect(),
            pids: m
                .values()
                .filter(|s| s.liveness() == Liveness::Live)
                .filter_map(|s| s.pid())
                .collect(),
        }
    }

    /// Summary of a live Claude Code session started outside SwitchTether. `None` for a process
    /// that has not been used yet (no transcript, idle): it often switches to a resumed
    /// conversation right after starting, so there is nothing to show yet.
    fn observed_live_summary(&self, l: &claude::live::LiveClaudeSession) -> Option<SessionSummary> {
        // A helper SwitchTether started itself (`claude -p /usage`, model probe): not a session.
        if l.pid.is_some_and(owned_pids::is_owned) {
            return None;
        }
        let cwd = l.cwd.clone();
        // The registry `name` is an auto-generated project slug ("myapp-3f"), not the task
        // title; the real title (`/rename` or Claude's generated one) lives in the transcript.
        let transcript = self.claude.transcript_path(cwd.as_deref(), &l.session_id);
        if transcript.is_none()
            && !matches!(l.status.as_deref(), Some("busy" | "waiting"))
            && l.waiting_for.is_none()
        {
            return None;
        }
        // Last activity = the transcript's last write. "Now" made every live session read
        // "just now" and left their order arbitrary.
        let last_active = transcript
            .as_deref()
            .and_then(|p| std::fs::metadata(p).ok())
            .and_then(|m| m.modified().ok())
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as i64)
            .unwrap_or_else(crate::util::now_ms);
        let title = transcript.as_deref().and_then(|p| self.claude_title(p));
        // Only from a log that is already loaded and followed (open on the phone, or its Agent
        // map polled): a transcript is never parsed just to list sessions.
        let id = format!("claude:{}", l.session_id);
        let loaded_log = self
            .loaded
            .lock()
            .get(&id)
            .filter(|x| x.tail.is_some())
            .map(|x| x.log.clone());
        let active_tasks = loaded_log.map(|log| active_task_count(&log.lock()));
        Some(SessionSummary {
            id,
            agent: "claude_code".into(),
            origin: l.origin(),
            native_id: l.session_id.clone(),
            title,
            project: cwd.as_deref().and_then(SessionSummary::project_from_cwd),
            cwd,
            liveness: Liveness::Live,
            status: l.status.clone().unwrap_or_else(|| "unknown".into()),
            waiting_for: l.waiting_for.clone(),
            control: Control::Observe,
            created_at_ms: l.started_at_ms,
            last_active_at_ms: Some(last_active),
            model: None,
            effort: None,
            git_branch: None,
            pid: l.pid,
            managed: false,
            terminal_id: None,
            active_tasks,
            source_path: transcript.map(|p| p.to_string_lossy().into_owned()),
        })
    }

    fn observed_agy_summary(&self, r: &agy::live::AgySummaryRow) -> Option<SessionSummary> {
        let now_ms = crate::util::now_ms();
        let (status, bg_hint) = r.observed_live(now_ms)?;
        let transcript = r.transcript_path();
        let id = format!("agy:{}", r.conversation_id);
        let loaded_log = self
            .loaded
            .lock()
            .get(&id)
            .filter(|x| x.tail.is_some())
            .map(|x| x.log.clone());
        let from_log = loaded_log.map(|log| active_task_count(&log.lock()));
        let active_tasks = match (from_log, bg_hint && status != "busy") {
            (Some(n), true) => Some(n.max(1)),
            (Some(n), false) => Some(n),
            (None, true) => Some(1),
            (None, false) => None,
        };
        let cwd = r.cwd.clone();
        Some(SessionSummary {
            id,
            agent: "agy".into(),
            origin: r.origin,
            native_id: r.conversation_id.clone(),
            title: r.title.clone(),
            project: cwd.as_deref().and_then(SessionSummary::project_from_cwd),
            cwd,
            liveness: Liveness::Live,
            status: status.into(),
            waiting_for: None,
            control: Control::Observe,
            created_at_ms: r.last_user_input_ms,
            last_active_at_ms: r.last_modified_ms.or(Some(now_ms)),
            model: None,
            effort: None,
            git_branch: None,
            pid: None,
            managed: false,
            terminal_id: None,
            active_tasks,
            source_path: transcript.map(|p| p.to_string_lossy().into_owned()),
        })
    }

    pub async fn list(&self, q: &SessionQuery) -> (Vec<SessionSummary>, Option<String>) {
        let indexed = self.index().await;
        let mut out: Vec<SessionSummary> = Vec::new();
        let managed: Vec<(String, Arc<dyn ManagedSession>)> = self
            .managed
            .read()
            .iter()
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect();
        let managed_native: Vec<String> = managed
            .iter()
            .filter_map(|(_, m)| m.native_session_id())
            .collect();
        for (id, m) in &managed {
            out.push(self.managed_summary(id, m.as_ref()));
        }
        let live = self.claude.live_sessions().await;
        let mut live_ids = std::collections::HashSet::new();
        let owned = self.owned_claude();
        for l in &live {
            if owned.covers(l) {
                continue;
            }
            live_ids.insert(l.session_id.clone());
            if let Some(summary) = self.observed_live_summary(l) {
                out.push(summary);
            }
        }
        let agy_live = self.agy.live_sessions();
        let mut agy_live_ids = std::collections::HashSet::new();
        for r in &agy_live {
            if managed_native.contains(&r.conversation_id) {
                continue;
            }
            if let Some(summary) = self.observed_agy_summary(r) {
                agy_live_ids.insert(r.conversation_id.clone());
                out.push(summary);
            }
        }
        for s in indexed.iter() {
            let agent = agent_of_provider(&s.provider_id);
            if (agent == "claude_code" && live_ids.contains(&s.session_id))
                || (agent == "agy" && agy_live_ids.contains(&s.session_id))
                || managed_native.contains(&s.session_id)
            {
                continue;
            }
            // Sub-agents are sessions of their own in DeepSeek Harness and Antigravity; they are
            // shown in their parent's branch view and Agent map, never as top-level sessions.
            if agent == "dsh" && self.dsh_is_child(s.source_path.as_deref()) {
                continue;
            }
            if agent == "agy" && self.agy.is_subagent(&s.session_id) {
                continue;
            }
            // A recently written DeepSeek Harness log belongs to a session that is open right now:
            // observe it live (read-only). Busy / idle comes from its last turn, so a stopped or
            // finished turn is not reported as working.
            let dsh_status = if agent == "dsh" {
                s.source_path
                    .as_deref()
                    .and_then(|p| self.dsh_live_status(Path::new(p)))
            } else {
                None
            };
            let dsh_live = dsh_status.is_some();
            let prefix = if agent == "claude_code" {
                "claude"
            } else {
                s.provider_id.as_str()
            };
            let title = if agent == "claude_code" {
                s.source_path
                    .as_deref()
                    .and_then(|p| self.claude_title(Path::new(p)))
            } else if agent == "agy" {
                self.agy.row(&s.session_id).and_then(|r| r.title)
            } else {
                None
            }
            .or_else(|| s.title.clone())
            .or_else(|| s.summary.clone())
            .and_then(|t| claude::transcript::clean_title(Some(t)));
            out.push(SessionSummary {
                id: format!("{prefix}:{}", s.session_id),
                agent,
                origin: Origin::Unknown,
                native_id: s.session_id.clone(),
                title,
                project: s
                    .project_dir
                    .as_deref()
                    .and_then(SessionSummary::project_from_cwd),
                cwd: s.project_dir.clone(),
                liveness: if dsh_live {
                    Liveness::Live
                } else {
                    Liveness::Ended
                },
                status: dsh_status.unwrap_or("ended").into(),
                waiting_for: None,
                control: if dsh_live {
                    Control::Observe
                } else {
                    Control::History
                },
                created_at_ms: s.created_at_ms,
                last_active_at_ms: s.last_active_at_ms,
                model: None,
                effort: None,
                git_branch: None,
                pid: None,
                managed: false,
                terminal_id: None,
                active_tasks: None,
                source_path: s.source_path.clone(),
            });
        }
        // Filters
        let needle =
            q.q.as_deref()
                .map(|s| s.to_lowercase())
                .filter(|s| !s.is_empty());
        out.retain(|s| {
            q.agent.as_deref().is_none_or(|a| s.agent == a)
                && q.project
                    .as_deref()
                    .is_none_or(|p| s.project.as_deref() == Some(p) || s.cwd.as_deref() == Some(p))
                && (!q.live_only.unwrap_or(false) || s.liveness == Liveness::Live)
                && needle.as_ref().is_none_or(|n| {
                    s.title.as_deref().unwrap_or("").to_lowercase().contains(n)
                        || s.cwd.as_deref().unwrap_or("").to_lowercase().contains(n)
                        || s.native_id.to_lowercase().contains(n)
                })
        });
        out.sort_by(|a, b| {
            (b.liveness == Liveness::Live)
                .cmp(&(a.liveness == Liveness::Live))
                .then(
                    b.last_active_at_ms
                        .unwrap_or(0)
                        .cmp(&a.last_active_at_ms.unwrap_or(0)),
                )
        });
        let offset: usize = q
            .cursor
            .as_deref()
            .and_then(|c| c.parse().ok())
            .unwrap_or(0);
        let limit = q.limit.unwrap_or(50).clamp(1, 200);
        let next = (offset + limit < out.len()).then(|| (offset + limit).to_string());
        let page = out.into_iter().skip(offset).take(limit).collect();
        (page, next)
    }

    /// Full-text search over what was said in sessions (docs/PROTOCOL.md §7.13): newest sessions
    /// first, bounded by `budget`. Returns the hits and whether the scan stopped early.
    pub async fn search(
        &self,
        query: &str,
        agent: Option<&str>,
        limit: usize,
        budget: Duration,
    ) -> (Vec<search::SearchResult>, bool) {
        let folded = search::fold(query.trim());
        if folded.is_empty() {
            return (Vec::new(), false);
        }
        let mut all = Vec::new();
        let mut cursor = None;
        loop {
            let (page, next) = self
                .list(&SessionQuery {
                    agent: agent.map(str::to_string),
                    cursor: cursor.clone(),
                    limit: Some(200),
                    ..Default::default()
                })
                .await;
            all.extend(page);
            match next {
                Some(n) if all.len() < 20_000 => cursor = Some(n),
                _ => break,
            }
        }
        all.sort_by_key(|s| std::cmp::Reverse(s.last_active_at_ms.unwrap_or(0)));
        // Antigravity JSONL transcripts are searched directly via `AgyParser`; legacy trajectory
        // databases without a JSONL transcript fall back to CC Switch's parser below.
        let agy_jsonl = |s: &SessionSummary| -> Option<PathBuf> {
            s.source_path
                .as_deref()
                .map(PathBuf::from)
                .filter(|p| p.extension().is_some_and(|e| e == "jsonl") && p.is_file())
                .or_else(|| self.agy.transcript_path(&s.native_id))
        };
        let (via_backend, all): (Vec<SessionSummary>, Vec<SessionSummary>) = all
            .into_iter()
            .partition(|s| s.agent == "agy" && agy_jsonl(s).is_none() && s.source_path.is_some());
        let mut seen = std::collections::HashSet::new();
        let targets: Vec<(SessionSummary, PathBuf)> = all
            .into_iter()
            .filter_map(|s| {
                let path = match s.agent.as_str() {
                    "claude_code" => s
                        .source_path
                        .clone()
                        .map(PathBuf::from)
                        .filter(|p| p.extension().is_some_and(|e| e == "jsonl"))
                        .or_else(|| self.claude.transcript_path(s.cwd.as_deref(), &s.native_id)),
                    "codex" => s
                        .source_path
                        .clone()
                        .map(PathBuf::from)
                        .filter(|p| is_rollout_file(p) && p.exists())
                        .or_else(|| find_codex_rollout(&s.native_id)),
                    "dsh" => dsh_log_path(&s),
                    "agy" => agy_jsonl(&s),
                    _ => None,
                }?;
                seen.insert(path.clone()).then_some((s, path))
            })
            .collect();
        let limit = limit.clamp(1, 50);
        let deadline = Instant::now() + budget;
        let folded_files = folded.clone();
        let (mut out, mut partial) = tokio::task::spawn_blocking(move || {
            let folded = folded_files;
            let mut out = Vec::new();
            let mut partial = false;
            for (s, path) in targets {
                if Instant::now() > deadline {
                    partial = true;
                    break;
                }
                let parser: Box<dyn LineParser> = match s.agent.as_str() {
                    "claude_code" => Box::new(ClaudeParser::new()),
                    "codex" => Box::new(CodexParser::new()),
                    "agy" => Box::new(agy::transcript::AgyParser::new()),
                    _ => Box::new(DshParser::new()),
                };
                let Ok(lines) = search::file_lines(&path) else {
                    continue;
                };
                let mut timed_out = false;
                let hit = search::search_lines(lines, parser, &folded, deadline, &mut timed_out);
                partial |= timed_out;
                if let Some(h) = hit {
                    out.push(search::SearchResult {
                        session_id: s.id,
                        agent: s.agent,
                        title: s.title,
                        cwd: s.cwd,
                        time_ms: s.last_active_at_ms,
                        snippet: h.snippet,
                        match_count: h.count,
                    });
                    if out.len() >= limit {
                        break;
                    }
                }
            }
            (out, partial)
        })
        .await
        .unwrap_or_default();
        for s in via_backend {
            // Newest first: once the page is full, an older session cannot make it in.
            let oldest = out.iter().map(|r| r.time_ms.unwrap_or(0)).min();
            if out.len() >= limit && s.last_active_at_ms.unwrap_or(0) <= oldest.unwrap_or(0) {
                break;
            }
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                partial = true;
                break;
            }
            let Some(src) = s.source_path.as_deref() else {
                continue;
            };
            let messages = match tokio::time::timeout(
                left,
                self.backend.session_messages(&s.agent, src),
            )
            .await
            {
                Ok(Ok(v)) => v,
                Ok(Err(_)) => continue,
                Err(_) => {
                    partial = true;
                    break;
                }
            };
            if let Some(h) = search::search_messages(&messages, &folded) {
                out.push(search::SearchResult {
                    session_id: s.id,
                    agent: s.agent,
                    title: s.title,
                    cwd: s.cwd,
                    time_ms: s.last_active_at_ms,
                    snippet: h.snippet,
                    match_count: h.count,
                });
            }
        }
        out.sort_by_key(|r| std::cmp::Reverse(r.time_ms.unwrap_or(0)));
        out.truncate(limit);
        (out, partial)
    }

    /// Fallback for Claude sessions that are not (yet) in CC Switch's index:
    /// locate the transcript by session id and read its first records.
    fn claude_summary_from_transcript(&self, id: &str) -> Option<SessionSummary> {
        let native = id.strip_prefix("claude:")?;
        if native.is_empty()
            || !native
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '-')
        {
            return None;
        }
        let path = self.claude.transcript_path(None, native)?;
        let meta = std::fs::metadata(&path).ok()?;
        let mut parser = ClaudeParser::new();
        if let Ok(f) = std::fs::File::open(&path) {
            use std::io::BufRead;
            for line in std::io::BufReader::new(f)
                .lines()
                .take(200)
                .map_while(Result::ok)
            {
                if let Ok(v) = serde_json::from_str::<Value>(&line) {
                    parser.feed(&v);
                }
            }
        }
        let modified = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as i64);
        let cwd = parser.cwd.clone();
        Some(SessionSummary {
            id: id.to_string(),
            agent: "claude_code".into(),
            origin: Origin::Unknown,
            native_id: native.to_string(),
            title: parser.title.clone(),
            project: cwd.as_deref().and_then(SessionSummary::project_from_cwd),
            cwd,
            liveness: Liveness::Ended,
            status: "ended".into(),
            waiting_for: None,
            control: Control::History,
            created_at_ms: None,
            last_active_at_ms: modified,
            model: parser.model.clone(),
            effort: None,
            git_branch: parser.git_branch.clone(),
            pid: None,
            managed: false,
            terminal_id: None,
            active_tasks: None,
            source_path: Some(path.to_string_lossy().into_owned()),
        })
    }

    fn agy_summary_from_transcript(&self, id: &str) -> Option<SessionSummary> {
        let native = id.strip_prefix("agy:")?;
        if !valid_agent_id(native) {
            return None;
        }
        if let Some(r) = self.agy.row(native) {
            if let Some(live) = self.observed_agy_summary(&r) {
                return Some(live);
            }
            let cwd = r.cwd.clone();
            return Some(SessionSummary {
                id: id.to_string(),
                agent: "agy".into(),
                origin: r.origin,
                native_id: native.to_string(),
                title: r.title.clone(),
                project: cwd.as_deref().and_then(SessionSummary::project_from_cwd),
                cwd,
                liveness: Liveness::Ended,
                status: "ended".into(),
                waiting_for: None,
                control: Control::History,
                created_at_ms: r.last_user_input_ms,
                last_active_at_ms: r.last_modified_ms,
                model: None,
                effort: None,
                git_branch: None,
                pid: None,
                managed: false,
                terminal_id: None,
                active_tasks: None,
                source_path: r
                    .transcript_path()
                    .map(|p| p.to_string_lossy().into_owned()),
            });
        }
        let path = self.agy.transcript_path(native)?;
        let meta = std::fs::metadata(&path).ok()?;
        let mut parser = agy::transcript::AgyParser::new();
        if let Ok(f) = std::fs::File::open(&path) {
            use std::io::BufRead;
            for line in std::io::BufReader::new(f)
                .lines()
                .take(200)
                .map_while(Result::ok)
            {
                if let Ok(v) = serde_json::from_str::<Value>(&line) {
                    parser.feed(&v);
                }
            }
        }
        let modified = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as i64);
        let cwd = parser.cwd.clone();
        Some(SessionSummary {
            id: id.to_string(),
            agent: "agy".into(),
            origin: Origin::Unknown,
            native_id: native.to_string(),
            title: parser.title.clone(),
            project: cwd.as_deref().and_then(SessionSummary::project_from_cwd),
            cwd,
            liveness: Liveness::Ended,
            status: "ended".into(),
            waiting_for: None,
            control: Control::History,
            created_at_ms: None,
            last_active_at_ms: modified,
            model: parser.model.clone(),
            effort: None,
            git_branch: None,
            pid: None,
            managed: false,
            terminal_id: None,
            active_tasks: None,
            source_path: Some(path.to_string_lossy().into_owned()),
        })
    }

    pub async fn summary(&self, id: &str) -> Option<SessionSummary> {
        if let Some((key, m)) = self.find_managed(id) {
            return Some(self.managed_summary(&key, m.as_ref()));
        }
        let q = SessionQuery {
            limit: Some(200),
            ..Default::default()
        };
        let mut cursor = None;
        loop {
            let (page, next) = self
                .list(&SessionQuery {
                    cursor: cursor.clone(),
                    ..q.clone()
                })
                .await;
            if let Some(s) = page.into_iter().find(|s| s.id == id) {
                return Some(s);
            }
            match next {
                Some(n) => cursor = Some(n),
                None => {
                    return self
                        .claude_summary_from_transcript(id)
                        .or_else(|| self.agy_summary_from_transcript(id))
                        .or_else(|| dsh_summary_from_log(id))
                }
            }
        }
    }

    fn capabilities_for(&self, s: &SessionSummary) -> Capabilities {
        if !s.managed && s.control == Control::Full {
            let mut c = attached_caps(s);
            // Model / effort of a session attached through an opt-in hook belong to the window
            // that owns it: say so instead of offering a control that `set_options` refuses.
            if s.terminal_id.is_none() && s.liveness == Liveness::Live {
                let switchable = self
                    .find_managed(&s.id)
                    .is_some_and(|(_, m)| m.can_change_model());
                if switchable {
                    c.change_model = Some(CapStatus::Partial);
                } else {
                    c.change_model = Some(CapStatus::Blocked);
                    c.reason.get_or_insert_with(|| ATTACHED_MODEL_REASON.into());
                }
            }
            return c;
        }
        let live = s.liveness == Liveness::Live;
        if s.managed && live {
            let mut c = if s.agent == "agy" {
                agy_managed_caps()
            } else {
                managed_caps()
            };
            let switchable = self
                .find_managed(&s.id)
                .is_some_and(|(_, m)| m.can_change_model());
            c.change_model = switchable.then_some(CapStatus::Partial);
            return c;
        }
        if !live && self.can_resume(s) {
            // History (or a run that ended, e.g. with a desktop restart): the next message resumes
            // the same conversation the official way and keeps its id.
            return resumable_caps(&s.agent);
        }
        if s.managed {
            let mut c = managed_caps();
            c.send_message = Some(CapStatus::Blocked);
            c.approve = Some(CapStatus::Blocked);
            c.interrupt = Some(CapStatus::Blocked);
            c.answer_question = Some(CapStatus::Blocked);
            c.reason = Some("The process has exited.".into());
            return c;
        }
        let mut c = observed_caps(&s.agent);
        if !live {
            c.live_events = None;
        }
        c
    }

    /// A session just became live: a loaded log that cannot follow its transcript (loaded from
    /// CC Switch's parser, or before the transcript existed) is dropped so the next read loads
    /// it again with a tail.
    fn follow_loaded(&self, s: &SessionSummary) {
        let mut loaded = self.loaded.lock();
        if loaded.get(&s.id).is_some_and(|l| l.tail.is_none()) {
            loaded.remove(&s.id);
        }
    }

    /// Ensure the session log is loaded (parsing its transcript if needed).
    async fn ensure_loaded(&self, s: &SessionSummary) -> ApiResult<Arc<Mutex<SessionLog>>> {
        if let Some((_, m)) = self.find_managed(&s.id) {
            return Ok(m.log());
        }
        if let Some(l) = self.loaded.lock().get_mut(&s.id) {
            l.last_access = Instant::now();
            return Ok(l.log.clone());
        }
        let log = Arc::new(Mutex::new(SessionLog::new(
            s.id.clone(),
            Some(self.bus.clone()),
        )));
        let tail = match s.agent.as_str() {
            "claude_code" => {
                let path = s
                    .source_path
                    .clone()
                    .map(PathBuf::from)
                    .filter(|p| p.extension().is_some_and(|e| e == "jsonl"))
                    .or_else(|| self.claude.transcript_path(s.cwd.as_deref(), &s.native_id));
                match path {
                    Some(p) => {
                        let tail = load_file(&p, Box::new(ClaudeParser::new()), &log)?;
                        add_running_subagents(&p, &log, false);
                        Some(tail)
                    }
                    None => None,
                }
            }
            // Codex compresses older rollouts (`rollout-*.jsonl.zst`): those are history too.
            "codex" => match s
                .source_path
                .clone()
                .map(PathBuf::from)
                .filter(|p| is_rollout_file(p) && p.exists())
                .or_else(|| find_codex_rollout(&s.native_id))
            {
                Some(p) => Some(load_file(&p, Box::new(CodexParser::new()), &log)?),
                None => None,
            },
            "dsh" => match dsh_log_path(s) {
                Some(p) => Some(load_file(&p, Box::new(DshParser::new()), &log)?),
                None => None,
            },
            "agy" => {
                let path = s
                    .source_path
                    .clone()
                    .map(PathBuf::from)
                    .filter(|p| p.extension().is_some_and(|e| e == "jsonl") && p.is_file())
                    .or_else(|| self.agy.transcript_path(&s.native_id));
                match path {
                    Some(p) => Some(load_file(
                        &p,
                        Box::new(agy::transcript::AgyParser::new()),
                        &log,
                    )?),
                    None => None,
                }
            }
            _ => None,
        };
        if tail.is_none() {
            // Plain transcript through CC Switch's own session parser.
            if let Some(src) = &s.source_path {
                let provider = s.id.split(':').next().unwrap_or("");
                let provider = if provider == "claude" {
                    "claude"
                } else {
                    provider
                };
                if let Ok(v) = self.backend.session_messages(provider, src).await {
                    backend_messages_into_log(&v, &log);
                }
            }
        }
        // Ended transcripts are followed too: a conversation can be resumed on the computer at any
        // time (`claude --resume`, `/resume`, `codex resume`), and a log opened as history must then
        // show the new turns at once. A tail of an unchanged file costs one `stat` per tick.
        let mut loaded = self.loaded.lock();
        if loaded.len() >= MAX_LOADED_SESSIONS {
            if let Some(oldest) = loaded
                .iter()
                .min_by_key(|(_, l)| l.last_access)
                .map(|(k, _)| k.clone())
            {
                loaded.remove(&oldest);
            }
        }
        loaded.insert(
            s.id.clone(),
            Loaded {
                log: log.clone(),
                tail,
                last_access: Instant::now(),
            },
        );
        Ok(log)
    }

    pub async fn detail(&self, id: &str) -> ApiResult<Value> {
        let s = self
            .summary(id)
            .await
            .ok_or_else(|| ApiError::not_found("unknown session"))?;
        let log = self.ensure_loaded(&s).await?;
        let pending: Vec<Value> = {
            let lg = log.lock();
            lg.iter()
                .filter(|e| {
                    (e.kind == "approval_request" || e.kind == "question")
                        && e.data["resolved"] == json!(false)
                })
                .map(|e| json!(e))
                .collect()
        };
        let mut v = json!(s);
        v["capabilities"] = json!(self.capabilities_for(&s));
        v["pendingRequests"] = json!(pending);
        v["eventCount"] = json!(log.lock().len());
        if let Some((_, m)) = self
            .find_managed(id)
            .filter(|(_, m)| m.liveness() == Liveness::Live)
        {
            // Claude Code session tools (permission mode, context, plan, …) for a process
            // SwitchTether started; see `claude_tool`.
            if m.supports_claude_tools() {
                v["claudeTools"] = json!(true);
            }
            // Other agents' session tools (Codex): the actions `claude_tool` answers for them.
            if !m.session_tools().is_empty() {
                v["sessionTools"] = json!(m.session_tools());
            }
            if m.can_change_permission_mode() {
                v["permissionMode"] = json!(m.permission_mode());
                v["permissionModes"] = permission::modes(m.agent());
            }
        }
        if v.get("claudeTools").is_none()
            && v.get("sessionTools").is_none()
            && matches!(s.agent.as_str(), "claude_code" | "agy")
        {
            // Observed / history session: read-only tools (task output, plan, export, plus MCP
            // status for Antigravity) only.
            v["claudeReadTools"] = json!(true);
            if s.agent == "agy" {
                v["sessionTools"] = json!(["task_output", "plan", "mcp_status", "export"]);
            }
        }
        if v.get("claudeTools").is_none() {
            // Not running anywhere: a message resumes it under SwitchTether, with the model,
            // effort and permission mode chosen here beforehand (`next_start`).
            let resumable = self.resumable(id).await.is_some();
            v["resumable"] = json!(resumable);
            if resumable {
                let modes = permission::modes(&s.agent);
                if modes.as_array().is_some_and(|a| !a.is_empty()) {
                    v["permissionModes"] = modes;
                }
                if let Some(n) = self.next_start.lock().get(id) {
                    v["nextStart"] = json!({
                        "model": n.model, "effort": n.effort, "permissionMode": n.permission_mode,
                    });
                }
            }
        }
        Ok(v)
    }

    pub async fn events(
        &self,
        id: &str,
        before: Option<u64>,
        after: Option<u64>,
        limit: usize,
    ) -> ApiResult<Value> {
        let s = self
            .summary(id)
            .await
            .ok_or_else(|| ApiError::not_found("unknown session"))?;
        let log = self.ensure_loaded(&s).await?;
        let lg = log.lock();
        let (events, more) = lg.page(before, after, limit);
        Ok(json!({ "sessionId": id, "events": events, "hasMore": more, "lastSeq": lg.last_seq() }))
    }

    /// Steps of one Claude Code sub-agent (read-only). Claude Code keeps each sub-agent's own
    /// transcript next to the session's: `<session>.jsonl` → `<session>/subagents/agent-<id>.jsonl`
    /// (+ `agent-<id>.meta.json`). Parsed with the normal transcript parser; tailed on every
    /// request so a running sub-agent can be followed by polling.
    pub async fn subagent_events(
        &self,
        id: &str,
        agent_id: &str,
        before: Option<u64>,
        after: Option<u64>,
        limit: usize,
    ) -> ApiResult<Value> {
        if !valid_agent_id(agent_id) {
            return Err(ApiError::validation("invalid sub-agent id"));
        }
        let s = self
            .summary(id)
            .await
            .ok_or_else(|| ApiError::not_found("unknown session"))?;
        if s.agent == "dsh" {
            return self.dsh_subagent_events(&s, agent_id, before, after, limit);
        }
        if s.agent == "agy" {
            return self
                .agy_subagent_events(&s, agent_id, before, after, limit)
                .await;
        }
        if s.agent != "claude_code" {
            return self.log_subagent_events(&s, agent_id, limit).await;
        }
        let dir = self.subagent_dir(id).await?;
        let path = dir.join(format!("agent-{agent_id}.jsonl"));
        if !path.is_file() {
            return Err(ApiError::not_found("no transcript for this sub-agent"));
        }
        let key = format!("{id}|{agent_id}");
        let log = {
            let mut map = self.subagent_logs.lock();
            map.retain(|_, l| l.last_access.elapsed() < Duration::from_secs(10 * 60));
            match map.get_mut(&key) {
                Some(l) => {
                    l.last_access = Instant::now();
                    if let Some(t) = l.tail.as_mut() {
                        let _ = tail_file(t, &l.log, false);
                    }
                    l.log.clone()
                }
                None => {
                    let log = Arc::new(Mutex::new(SessionLog::new(key.clone(), None)));
                    let tail = load_file(&path, Box::new(ClaudeParser::new()), &log)?;
                    if map.len() >= 16 {
                        if let Some(oldest) = map
                            .iter()
                            .min_by_key(|(_, l)| l.last_access)
                            .map(|(k, _)| k.clone())
                        {
                            map.remove(&oldest);
                        }
                    }
                    map.insert(
                        key.clone(),
                        Loaded {
                            log: log.clone(),
                            tail: Some(tail),
                            last_access: Instant::now(),
                        },
                    );
                    log
                }
            }
        };
        let meta = subagent_meta(&dir, agent_id);
        let lg = log.lock();
        let (events, more) = lg.page(before, after, limit);
        Ok(json!({
            "sessionId": id,
            "agentId": agent_id,
            "events": events,
            "hasMore": more,
            "lastSeq": lg.last_seq(),
            "meta": meta,
        }))
    }

    /// The Agent map of a session: every sub-agent as a tree node (see [`Self::subagent_nodes`])
    /// with its live state from the session log (status, waiting for a permission, counters,
    /// current activity, model, prompt, result), the session's background shell tasks, and
    /// whether the phone may stop them (a process SwitchTether started).
    pub async fn subagent_tree(&self, id: &str) -> ApiResult<Value> {
        let mut tree = self.subagent_nodes(id).await?;
        let Some(s) = self.summary(id).await else {
            return Ok(tree);
        };
        let Ok(log) = self.ensure_loaded(&s).await else {
            return Ok(tree);
        };
        let (live, tasks) = agent_map_state(&log.lock());
        if let Some(nodes) = tree.get_mut("agents").and_then(Value::as_array_mut) {
            for n in nodes {
                let call = n
                    .get("toolUseId")
                    .and_then(Value::as_str)
                    .map(String::from)
                    .or_else(|| {
                        let a = n.get("agentId").and_then(Value::as_str)?;
                        live.iter()
                            .find(|(_, v)| v.get("agentId").and_then(Value::as_str) == Some(a))
                            .map(|(k, _)| k.clone())
                    });
                if let (Some(o), Some(extra)) = (n.as_object_mut(), call.and_then(|c| live.get(&c)))
                {
                    for (k, v) in extra.as_object().into_iter().flatten() {
                        if !v.is_null() && (k != "agentId" || !o.contains_key(k)) {
                            o.insert(k.clone(), v.clone());
                        }
                    }
                }
            }
        }
        let stoppable = self
            .find_managed(id)
            .is_some_and(|(_, m)| m.agent() == "claude_code" && m.supports_claude_tools());
        tree["backgroundTasks"] = json!(tasks);
        tree["stoppable"] = json!(stoppable);
        Ok(tree)
    }

    /// The Claude Code IDE's session tools for a process SwitchTether started. Each action is one
    /// whitelisted control request; responses are reduced to the fields the phone shows (never
    /// MCP configuration, which can hold secrets) and texts are redacted.
    pub async fn claude_tool(&self, id: &str, action: &str, p: &Value) -> ApiResult<Value> {
        // Codex / Antigravity (and any other agent with session tools of its own): same paths and replies.
        if let Some((key, m)) = self.find_managed(id).filter(|(_, m)| {
            !m.supports_claude_tools()
                && m.liveness() == Liveness::Live
                && m.session_tools().contains(&action)
        }) {
            let r = m.session_tool(action, p).await?;
            if matches!(action, "rename" | "permission_mode") {
                self.publish_session(&key);
            }
            if action == "permission_mode" {
                self.remember_options(id);
            }
            return Ok(match action {
                "commands" => command_catalog(claude::managed::normalize_commands(&r)),
                _ => r,
            });
        }
        let live = self
            .find_managed(id)
            .map(|(_, m)| m)
            .filter(|m| m.supports_claude_tools());
        let Some(m) = live else {
            return self.observed_claude_tool(id, action, p).await;
        };
        if !m.supports_claude_tools() {
            return Err(ApiError::unavailable_capability(
                "BLOCKED",
                "This session does not offer Claude Code session tools.",
            ));
        }
        let arg = |k: &str| -> ApiResult<String> {
            p.get(k)
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|v| !v.is_empty() && v.len() <= 4000)
                .map(String::from)
                .ok_or_else(|| ApiError::validation(format!("'{k}' is required")))
        };
        let short = Duration::from_secs(20);
        let redact = |v: Option<&Value>| {
            v.and_then(Value::as_str)
                .map(|t| json!(crate::redact::redact_text(t)))
                .unwrap_or(Value::Null)
        };
        match action {
            // What the process itself reported (initialize response, `system`/`init`): nothing
            // is sent. A command is run by sending "/name args" as an ordinary message.
            "commands" => Ok(command_catalog(m.claude_commands())),
            "permission_mode" => {
                let mode = m.set_permission_mode(&arg("mode")?).await?;
                self.remember_options(id);
                Ok(json!({ "mode": mode }))
            }
            "context_usage" => {
                let r = m
                    .claude_control(json!({ "subtype": "get_context_usage" }), short)
                    .await?;
                let cats: Vec<Value> = r
                    .get("categories")
                    .and_then(Value::as_array)
                    .into_iter()
                    .flatten()
                    .map(|c| {
                        json!({ "id": c.get("id"), "name": c.get("name"), "tokens": c.get("tokens"), "kind": c.get("kind") })
                    })
                    .collect();
                Ok(json!({
                    "totalTokens": r.get("totalTokens"), "maxTokens": r.get("maxTokens"),
                    "percentage": r.get("percentage"), "categories": cats,
                }))
            }
            "task_output" => {
                let task = arg("taskId")?;
                if !valid_agent_id(&task) {
                    return Err(ApiError::validation("invalid task id"));
                }
                let r = m
                    .claude_control(
                        json!({ "subtype": "get_task_output", "task_id": task }),
                        short,
                    )
                    .await?;
                // The newest 64 KiB are what a phone shows.
                let out = r.get("output").and_then(Value::as_str).unwrap_or("");
                let cut = out.len().saturating_sub(64 * 1024);
                let cut = (cut..=out.len())
                    .find(|i| out.is_char_boundary(*i))
                    .unwrap_or(out.len());
                Ok(json!({
                    "output": crate::redact::redact_text(&out[cut..]),
                    "totalBytes": r.get("total_bytes"),
                    "truncated": cut > 0 || r.get("truncated") == Some(&json!(true)),
                }))
            }
            "plan" => {
                let r = m
                    .claude_control(json!({ "subtype": "get_plan" }), short)
                    .await?;
                let text = ["content", "plan", "text", "markdown"]
                    .iter()
                    .find_map(|k| r.get(*k).filter(|v| v.is_string()));
                Ok(json!({
                    "exists": r.get("exists").and_then(Value::as_bool).unwrap_or(text.is_some()),
                    "content": redact(text),
                    "path": r.get("path").filter(|v| v.is_string()),
                }))
            }
            "mcp_status" => {
                let r = m
                    .claude_control(json!({ "subtype": "mcp_status" }), short)
                    .await?;
                let servers: Vec<Value> = r
                    .get("mcpServers")
                    .and_then(Value::as_array)
                    .into_iter()
                    .flatten()
                    .map(|s| {
                        json!({
                            "name": s.get("name"), "status": s.get("status"),
                            "title": s.pointer("/serverInfo/title"),
                            "version": s.pointer("/serverInfo/version"),
                            "transport": s.get("transport"), "scope": s.get("scope"),
                            "tools": s.get("tools").and_then(Value::as_array).map(Vec::len),
                            "error": redact(s.get("error")),
                        })
                    })
                    .collect();
                Ok(json!({ "servers": servers }))
            }
            "mcp_reconnect" => {
                let server = arg("server")?;
                m.claude_control(
                    json!({ "subtype": "mcp_reconnect", "serverName": server }),
                    Duration::from_secs(60),
                )
                .await?;
                Ok(json!({ "reconnected": true }))
            }
            "run_in_background" => {
                let call = arg("toolUseId")?;
                if !valid_agent_id(&call) {
                    return Err(ApiError::validation("invalid tool use id"));
                }
                let r = m
                    .claude_control(
                        json!({ "subtype": "background_tasks", "tool_use_id": call }),
                        short,
                    )
                    .await?;
                Ok(
                    json!({ "backgrounded": r.get("backgrounded").and_then(Value::as_bool).unwrap_or(false) }),
                )
            }
            "cancel_message" => {
                let uuid = arg("messageUuid")?;
                if uuid::Uuid::parse_str(&uuid).is_err() {
                    return Err(ApiError::validation("invalid message uuid"));
                }
                let r = m
                    .claude_control(
                        json!({ "subtype": "cancel_async_message", "message_uuid": uuid }),
                        short,
                    )
                    .await?;
                Ok(
                    json!({ "cancelled": r.get("cancelled").and_then(Value::as_bool).unwrap_or(false) }),
                )
            }
            "side_question" => {
                let q = arg("question")?;
                let r = m
                    .claude_control(
                        json!({ "subtype": "side_question", "question": q }),
                        Duration::from_secs(180),
                    )
                    .await?;
                Ok(json!({ "answer": redact(r.get("response")) }))
            }
            "rename" => {
                let title = arg("title")?;
                if title.chars().count() > 200 {
                    return Err(ApiError::validation("title too long"));
                }
                m.claude_control(
                    json!({ "subtype": "rename_session", "title": title, "source": "host" }),
                    short,
                )
                .await?;
                Ok(json!({ "title": title }))
            }
            // Built and verified, but switched off for now: managed processes run without file
            // checkpoints (see `REWIND_FILES_ENABLED` in claude::managed).
            "rewind" if !claude::managed::REWIND_FILES_ENABLED => Err(
                ApiError::unavailable_capability("BLOCKED", "Rewinding files is not enabled."),
            ),
            "rewind" => {
                let msg = arg("userMessageId")?;
                if uuid::Uuid::parse_str(&msg).is_err() {
                    return Err(ApiError::validation("invalid message id"));
                }
                let dry = p.get("dryRun").and_then(Value::as_bool).unwrap_or(true);
                let r = m
                    .claude_control(
                        json!({ "subtype": "rewind_files", "user_message_id": msg, "dry_run": dry }),
                        Duration::from_secs(60),
                    )
                    .await?;
                let cwd = m.cwd();
                let files: Vec<Value> = r
                    .get("filesChanged")
                    .and_then(Value::as_array)
                    .into_iter()
                    .flatten()
                    .filter_map(Value::as_str)
                    .map(|f| {
                        let path = Path::new(f);
                        json!(path.strip_prefix(&cwd).unwrap_or(path).to_string_lossy())
                    })
                    .collect();
                Ok(json!({
                    "dryRun": dry,
                    "canRewind": r.get("canRewind").and_then(Value::as_bool).unwrap_or(false),
                    "error": redact(r.get("error")),
                    "filesChanged": files,
                    "insertions": r.get("insertions"), "deletions": r.get("deletions"),
                }))
            }
            "export" => {
                let r = m
                    .claude_control(
                        json!({ "subtype": "export_conversation" }),
                        Duration::from_secs(60),
                    )
                    .await?;
                Ok(json!({ "text": redact(r.get("text")), "filename": r.get("default_filename") }))
            }
            _ => Err(ApiError::not_found("unknown session tool")),
        }
    }

    /// Session tools for a Claude Code session started outside SwitchTether (observe-only: hard
    /// rule 1). Only what its files already hold is offered, read-only: a background task's output
    /// file, the latest plan, and the conversation as text. Everything that would need the
    /// process answers `capability_unavailable` naming the opt-in ways to get it.
    async fn observed_claude_tool(&self, id: &str, action: &str, p: &Value) -> ApiResult<Value> {
        let s = self
            .summary(id)
            .await
            .ok_or_else(|| ApiError::not_found("unknown session"))?;
        let blocked = || {
            let msg = if s.agent == "agy" {
                "This Antigravity session was started on the computer and can only be watched. Start a terminal with `st-agy`, or use \"Continue on phone\"."
            } else {
                "This Claude Code session was started on the computer and can only be watched. Turn on the takeover hook in Settings → Remote for new VS Code panels, or use \"Continue on phone\"."
            };
            ApiError::unavailable_capability("BLOCKED", msg)
        };
        // Not running, but a message from the phone would resume it under SwitchTether: no list
        // yet, the phone falls back to its built-in one (or Antigravity's built-ins).
        if action == "commands"
            && matches!(s.agent.as_str(), "claude_code" | "codex" | "agy")
            && self.resumable(id).await.is_some()
        {
            let cmds = if s.agent == "agy" {
                agy::default_commands()
            } else {
                Vec::new()
            };
            return Ok(command_catalog(cmds));
        }
        // Not running: the permission mode is kept for the resume a message starts.
        if action == "permission_mode" && self.resumable(id).await.is_some() {
            let mode = p
                .get("mode")
                .and_then(Value::as_str)
                .and_then(|m| permission::normalize(&s.agent, m))
                .ok_or_else(|| ApiError::validation("unknown permission mode"))?;
            {
                let mut pending = self.next_start.lock();
                let entry = pending.entry(id.to_string()).or_default();
                entry.permission_mode = Some(mode.to_string()).filter(|m| m != "default");
                entry.permission_mode_set = true;
            }
            return Ok(json!({ "mode": mode, "pending": true }));
        }
        if s.agent == "agy" {
            return match action {
                "mcp_status" => Ok(agy::live::read_mcp_status(s.cwd.as_deref().map(Path::new))),
                "task_output" => {
                    let task = p
                        .get("taskId")
                        .and_then(Value::as_str)
                        .filter(|t| valid_agent_id(t))
                        .ok_or_else(|| ApiError::validation("invalid task id"))?;
                    let log = self.ensure_loaded(&s).await?;
                    let lg = log.lock();
                    agy::task_output(&lg, task)
                }
                "plan" => {
                    let log = self.ensure_loaded(&s).await?;
                    let brain = self.agy.brain_dir(&s.native_id);
                    let lg = log.lock();
                    Ok(agy::plan_view(brain.as_deref(), &lg))
                }
                "export" => {
                    let log = self.ensure_loaded(&s).await?;
                    let text = crate::redact::redact_text(&export_text(&log.lock()));
                    let date = chrono::Local::now().format("%Y-%m-%d-%H%M%S");
                    Ok(json!({ "text": text, "filename": format!("{date}-{}.txt", s.native_id) }))
                }
                _ => Err(blocked()),
            };
        }
        if s.agent != "claude_code" || !matches!(action, "task_output" | "plan" | "export") {
            return Err(blocked());
        }
        let log = self.ensure_loaded(&s).await?;
        let lg = log.lock();
        match action {
            "task_output" => {
                let task = p
                    .get("taskId")
                    .and_then(Value::as_str)
                    .filter(|t| valid_agent_id(t))
                    .ok_or_else(|| ApiError::validation("invalid task id"))?;
                let path = observed_task_output_path(&lg, task)
                    .ok_or_else(|| ApiError::not_found("no output file for this task"))?;
                drop(lg);
                read_task_output(&path)
            }
            "plan" => {
                let plan = lg
                    .iter()
                    .filter(|e| {
                        e.kind == "tool_call"
                            && e.data.get("name").and_then(Value::as_str) == Some("ExitPlanMode")
                    })
                    .filter_map(|e| e.data.pointer("/input/plan").and_then(Value::as_str))
                    .last()
                    .map(crate::redact::redact_text);
                Ok(json!({ "exists": plan.is_some(), "content": plan }))
            }
            _ => {
                let text = crate::redact::redact_text(&export_text(&lg));
                let date = chrono::Local::now().format("%Y-%m-%d-%H%M%S");
                Ok(json!({ "text": text, "filename": format!("{date}-{}.txt", s.native_id) }))
            }
        }
    }

    /// Agent map "Stop": stops one sub-agent or background task (its task id: a sub-agent's
    /// `agentId`, a shell's `taskId`) of a session SwitchTether started.
    pub async fn stop_subagent(&self, id: &str, task_id: &str) -> ApiResult<Value> {
        if !valid_agent_id(task_id) {
            return Err(ApiError::validation("invalid task id"));
        }
        let m = self.managed_or_blocked(id)?;
        m.stop_task(task_id).await?;
        Ok(json!({ "stopped": true, "taskId": task_id }))
    }

    /// All sub-agents of a session as a tree (`parentAgentId` = null for those the session
    /// started itself). Claude Code: from the sidecar metadata written next to each sub-agent
    /// transcript; others: from the log's `subagent` rows. Empty when the session has none.
    async fn subagent_nodes(&self, id: &str) -> ApiResult<Value> {
        let s = self
            .summary(id)
            .await
            .ok_or_else(|| ApiError::not_found("unknown session"))?;
        if s.agent == "dsh" {
            return Ok(json!({ "sessionId": id, "agents": dsh_subagent_nodes(&s) }));
        }
        if s.agent == "agy" {
            let log = self.ensure_loaded(&s).await?;
            let from_log = log_subagent_nodes(&log.lock());
            let mut by_id: HashMap<String, Value> = HashMap::new();
            for n in from_log {
                if let Some(aid) = n.get("agentId").and_then(Value::as_str) {
                    by_id.insert(aid.to_string(), n);
                }
            }
            for c in self.agy.descendants(&s.native_id) {
                let finished = c.killed || (!c.is_running() && !c.not_fully_idle);
                let parent_agent = c
                    .parent_conversation_id
                    .as_deref()
                    .filter(|p| !p.is_empty() && *p != s.native_id)
                    .map(String::from);
                match by_id
                    .get_mut(&c.conversation_id)
                    .and_then(Value::as_object_mut)
                {
                    Some(existing) => {
                        if existing.get("parentAgentId").is_none_or(Value::is_null) {
                            existing.insert("parentAgentId".into(), json!(parent_agent));
                        }
                        if existing.get("agentType").is_none_or(Value::is_null) {
                            existing.insert("agentType".into(), json!(c.agent_name));
                        }
                        existing.insert("spawnDepth".into(), json!(c.nesting_depth));
                        existing.insert("updatedAtMs".into(), json!(c.last_modified_ms));
                        if finished {
                            existing.insert("finished".into(), json!(true));
                        }
                    }
                    None => {
                        by_id.insert(
                            c.conversation_id.clone(),
                            json!({
                                "agentId": c.conversation_id,
                                "description": c.title.or_else(|| c.agent_name.clone()),
                                "agentType": c.agent_name.unwrap_or_else(|| "subagent".into()),
                                "parentAgentId": parent_agent,
                                "spawnDepth": c.nesting_depth,
                                "finished": finished,
                                "updatedAtMs": c.last_modified_ms,
                            }),
                        );
                    }
                }
            }
            let mut agents: Vec<Value> = by_id.into_values().collect();
            agents.sort_by_key(|a| a.get("updatedAtMs").and_then(Value::as_i64).unwrap_or(0));
            return Ok(json!({ "sessionId": id, "agents": agents }));
        }
        if s.agent != "claude_code" {
            // Codex and other adapters: the `subagent` rows of the session's own log.
            let log = self.ensure_loaded(&s).await?;
            let agents = log_subagent_nodes(&log.lock());
            return Ok(json!({ "sessionId": id, "agents": agents }));
        }
        let dir = match self.subagent_dir(id).await {
            Ok(d) => d,
            Err(e) if e.code == crate::error::ErrorCode::NotFound => {
                return Ok(json!({ "sessionId": id, "agents": [] }))
            }
            Err(e) => return Err(e),
        };
        let mut agents: Vec<Value> = Vec::new();
        if let Ok(rd) = std::fs::read_dir(&dir) {
            for e in rd.flatten() {
                let name = e.file_name().to_string_lossy().to_string();
                let Some(agent_id) = name
                    .strip_prefix("agent-")
                    .and_then(|n| n.strip_suffix(".jsonl"))
                else {
                    continue;
                };
                if !valid_agent_id(agent_id) {
                    continue;
                }
                let updated = e
                    .metadata()
                    .ok()
                    .and_then(|m| m.modified().ok())
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map(|d| d.as_millis() as u64);
                let mut o = match subagent_meta(&dir, agent_id) {
                    Some(Value::Object(m)) => m,
                    _ => serde_json::Map::new(),
                };
                o.insert("agentId".into(), json!(agent_id));
                o.insert("updatedAtMs".into(), json!(updated));
                agents.push(Value::Object(o));
                if agents.len() >= 500 {
                    break;
                }
            }
        }
        agents.sort_by_key(|a| a.get("updatedAtMs").and_then(Value::as_u64).unwrap_or(0));
        Ok(json!({ "sessionId": id, "agents": agents }))
    }

    /// A sub-agent of a non-Claude session: the events nested under its `subagent` row in the
    /// session log (Codex managed sessions stream child threads there). When the row has no
    /// nested events and the agent is Codex, the child thread's own rollout
    /// (`$CODEX_HOME/sessions/**/rollout-*-<thread id>.jsonl`) is read instead, if present.
    async fn log_subagent_events(
        &self,
        s: &SessionSummary,
        agent_id: &str,
        limit: usize,
    ) -> ApiResult<Value> {
        let log = self.ensure_loaded(s).await?;
        let (row, mut events) = {
            let lg = log.lock();
            let row = lg
                .iter()
                .find(|e| {
                    e.kind == "subagent"
                        && e.data.get("agentId").and_then(Value::as_str) == Some(agent_id)
                })
                .cloned()
                .ok_or_else(|| ApiError::not_found("unknown sub-agent"))?;
            (row.clone(), nested_under(&lg, &row.id))
        };
        if events.is_empty() && s.agent == "codex" {
            if let Some(path) = find_codex_rollout(agent_id) {
                let child = Arc::new(Mutex::new(SessionLog::new(
                    format!("{}|{agent_id}", s.id),
                    None,
                )));
                load_file(&path, Box::new(CodexParser::new()), &child)?;
                events = child.lock().iter().cloned().collect();
            }
        }
        let more = events.len() > limit;
        if more {
            events.drain(..events.len() - limit);
        }
        let finished = matches!(
            row.data.get("status").and_then(Value::as_str),
            Some("completed" | "failed" | "interrupted")
        );
        let meta = json!({
            "agentType": row.data.get("agentType").filter(|v| v.is_string()),
            "description": row.data.get("description").filter(|v| v.is_string()),
            "toolUseId": row.data.get("callId").filter(|v| v.is_string()),
            "finished": finished,
        });
        let last = events.iter().map(|e| e.seq).max().unwrap_or(0);
        Ok(json!({
            "sessionId": s.id,
            "agentId": agent_id,
            "events": events,
            "hasMore": more,
            "lastSeq": last,
            "meta": meta,
        }))
    }

    async fn agy_subagent_events(
        &self,
        s: &SessionSummary,
        agent_id: &str,
        before: Option<u64>,
        after: Option<u64>,
        limit: usize,
    ) -> ApiResult<Value> {
        let log = self.ensure_loaded(s).await?;
        let log_row = log
            .lock()
            .iter()
            .find(|e| {
                e.kind == "subagent"
                    && e.data.get("agentId").and_then(Value::as_str) == Some(agent_id)
            })
            .cloned();
        let db_row = self
            .agy
            .descendants(&s.native_id)
            .into_iter()
            .find(|c| c.conversation_id == agent_id);
        if log_row.is_none() && db_row.is_none() {
            return Err(ApiError::not_found("unknown sub-agent"));
        }
        let path = db_row
            .as_ref()
            .and_then(|r| r.transcript_path())
            .or_else(|| self.agy.transcript_path(agent_id))
            .ok_or_else(|| ApiError::not_found("no transcript for this sub-agent"))?;

        let key = format!("{}|{agent_id}", s.id);
        let child_log = {
            let mut map = self.subagent_logs.lock();
            map.retain(|_, l| l.last_access.elapsed() < Duration::from_secs(10 * 60));
            match map.get_mut(&key) {
                Some(l) => {
                    l.last_access = Instant::now();
                    if let Some(t) = l.tail.as_mut() {
                        let _ = tail_file(t, &l.log, false);
                    }
                    l.log.clone()
                }
                None => {
                    let lg = Arc::new(Mutex::new(SessionLog::new(key.clone(), None)));
                    let tail = load_file(&path, Box::new(agy::transcript::AgyParser::new()), &lg)?;
                    if map.len() >= 16 {
                        if let Some(oldest) = map
                            .iter()
                            .min_by_key(|(_, l)| l.last_access)
                            .map(|(k, _)| k.clone())
                        {
                            map.remove(&oldest);
                        }
                    }
                    map.insert(
                        key,
                        Loaded {
                            log: lg.clone(),
                            tail: Some(tail),
                            last_access: Instant::now(),
                        },
                    );
                    lg
                }
            }
        };

        let finished = db_row
            .as_ref()
            .map(|c| c.killed || (!c.is_running() && !c.not_fully_idle))
            .or_else(|| {
                log_row.as_ref().map(|r| {
                    matches!(
                        r.data.get("status").and_then(Value::as_str),
                        Some("completed" | "failed" | "interrupted")
                    )
                })
            })
            .unwrap_or(false);
        let parent_agent = db_row
            .as_ref()
            .and_then(|c| c.parent_conversation_id.as_deref())
            .filter(|p| !p.is_empty() && *p != s.native_id);
        let meta = json!({
            "agentType": log_row
                .as_ref()
                .and_then(|r| r.data.get("agentType").and_then(Value::as_str))
                .or_else(|| db_row.as_ref().and_then(|c| c.agent_name.as_deref()))
                .unwrap_or("subagent"),
            "description": log_row
                .as_ref()
                .and_then(|r| r.data.get("description").and_then(Value::as_str))
                .or_else(|| db_row.as_ref().and_then(|c| c.title.as_deref())),
            "toolUseId": log_row
                .as_ref()
                .and_then(|r| r.data.get("callId").and_then(Value::as_str)),
            "parentAgentId": parent_agent,
            "spawnDepth": db_row.as_ref().map(|c| c.nesting_depth),
            "finished": finished,
        });
        let lg = child_log.lock();
        let (events, more) = lg.page(before, after, limit);
        Ok(json!({
            "sessionId": s.id,
            "agentId": agent_id,
            "events": events,
            "hasMore": more,
            "lastSeq": lg.last_seq(),
            "meta": meta,
        }))
    }

    /// A DeepSeek Harness sub-agent is a session of its own (header `parentSession`): its log is
    /// parsed and tailed like the session itself.
    fn dsh_subagent_events(
        &self,
        s: &SessionSummary,
        agent_id: &str,
        before: Option<u64>,
        after: Option<u64>,
        limit: usize,
    ) -> ApiResult<Value> {
        let path = dsh_log_path(s)
            .and_then(|p| p.parent().map(Path::to_path_buf))
            .map(|dir| dsh::descendants(&dir, &s.native_id))
            .and_then(|kids| kids.into_iter().find(|c| c.id == dsh::norm_id(agent_id)))
            .map(|c| c.path)
            .ok_or_else(|| ApiError::not_found("unknown sub-agent"))?;
        let key = format!("{}|{agent_id}", s.id);
        let log = {
            let mut map = self.subagent_logs.lock();
            map.retain(|_, l| l.last_access.elapsed() < Duration::from_secs(10 * 60));
            match map.get_mut(&key) {
                Some(l) => {
                    l.last_access = Instant::now();
                    if let Some(t) = l.tail.as_mut() {
                        let _ = tail_file(t, &l.log, false);
                    }
                    l.log.clone()
                }
                None => {
                    let log = Arc::new(Mutex::new(SessionLog::new(key.clone(), None)));
                    let tail = load_file(&path, Box::new(DshParser::new()), &log)?;
                    map.insert(
                        key.clone(),
                        Loaded {
                            log: log.clone(),
                            tail: Some(tail),
                            last_access: Instant::now(),
                        },
                    );
                    log
                }
            }
        };
        let node = dsh_subagent_nodes(s)
            .into_iter()
            .find(|n| n.get("agentId").and_then(Value::as_str) == Some(agent_id));
        let lg = log.lock();
        let (events, more) = lg.page(before, after, limit);
        Ok(json!({
            "sessionId": s.id,
            "agentId": agent_id,
            "events": events,
            "hasMore": more,
            "lastSeq": lg.last_seq(),
            "meta": node,
        }))
    }

    /// Whether a DeepSeek Harness log belongs to a sub-agent (header `parentSession`), cached.
    /// Live status of an observed DeepSeek Harness log (see `dsh_observed_status`). Only logs
    /// written in the last ten minutes are read, incrementally from where the last call stopped.
    fn dsh_live_status(&self, path: &Path) -> Option<&'static str> {
        let since_write = std::fs::metadata(path)
            .ok()?
            .modified()
            .ok()?
            .elapsed()
            .unwrap_or_default();
        if since_write >= Duration::from_secs(10 * 60) {
            self.dsh_turns.lock().remove(path);
            return None;
        }
        let state = {
            let mut turns = self.dsh_turns.lock();
            if turns.len() > 64 {
                turns.clear();
            }
            let (tail, state) = turns.entry(path.to_path_buf()).or_insert_with(|| {
                let st = Arc::new(Mutex::new(DshTurnState::default()));
                (
                    Tail::new(path.to_path_buf(), Box::new(DshTurnProbe(st.clone()))),
                    st,
                )
            });
            if let Err(e) = tail_file(tail, &self.probe_log, false) {
                log::debug!("[dsh] turn state of {}: {e}", path.display());
            }
            let s = state.lock().clone();
            s
        };
        dsh_observed_status(&state, since_write)
    }

    fn dsh_is_child(&self, path: Option<&str>) -> bool {
        let Some(path) = path else { return false };
        let len = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
        if let Some((l, child)) = self.dsh_children.lock().get(path) {
            if *l == len || *l > 0 {
                return *child; // the header never changes once written
            }
        }
        let child = dsh::read_header(Path::new(path))
            .is_some_and(|h| h.get("parentSession").and_then(Value::as_str).is_some());
        let mut c = self.dsh_children.lock();
        if c.len() > 20_000 {
            c.clear();
        }
        c.insert(path.to_string(), (len, child));
        child
    }

    async fn subagent_dir(&self, id: &str) -> ApiResult<PathBuf> {
        let s = self
            .summary(id)
            .await
            .ok_or_else(|| ApiError::not_found("unknown session"))?;
        if s.agent != "claude_code" {
            return Err(ApiError::not_found(
                "sub-agent transcripts exist only for Claude Code",
            ));
        }
        let main = s
            .source_path
            .clone()
            .map(PathBuf::from)
            .filter(|p| p.extension().is_some_and(|e| e == "jsonl"))
            .or_else(|| self.claude.transcript_path(s.cwd.as_deref(), &s.native_id))
            .ok_or_else(|| ApiError::not_found("no transcript for this session"))?;
        Ok(main.with_extension("").join("subagents"))
    }

    pub async fn payload(
        &self,
        id: &str,
        event_id: &str,
        offset: usize,
        limit: usize,
    ) -> ApiResult<Value> {
        let s = self
            .summary(id)
            .await
            .ok_or_else(|| ApiError::not_found("unknown session"))?;
        let log = self.ensure_loaded(&s).await?;
        let lg = log.lock();
        let text = lg
            .payload(event_id)
            .ok_or_else(|| ApiError::not_found("no stored payload for this event"))?;
        let limit = limit.clamp(1024, 1024 * 1024);
        let mut start = offset.min(text.len());
        while !text.is_char_boundary(start) {
            start -= 1;
        }
        let mut end = (start + limit).min(text.len());
        while !text.is_char_boundary(end) {
            end -= 1;
        }
        Ok(
            json!({ "eventId": event_id, "offset": start, "text": &text[start..end], "total": text.len(), "done": end >= text.len() }),
        )
    }

    pub async fn create_managed(&self, req: CreateSession) -> ApiResult<SessionSummary> {
        let mut req = req;
        req.model = req
            .model
            .map(|m| m.trim().to_string())
            .filter(|m| !m.is_empty() && m != "default");
        if req.model.as_deref().is_some_and(|m| !valid_model(m)) {
            return Err(ApiError::validation("invalid model name"));
        }
        req.effort = req.effort.filter(|e| !e.is_empty() && e != "default");
        if req
            .effort
            .as_deref()
            .is_some_and(|e| !valid_effort(&req.agent, e))
        {
            return Err(ApiError::validation("invalid effort level for this agent"));
        }
        // Continuing a conversation: the agent's own resume (`claude --resume`, `thread/resume`,
        // `session/resume`) in a process SwitchTether starts. The conversation keeps its id, so it
        // stays one entry everywhere (phone, `claude --resume`, the IDE's history).
        let mut continue_from: Option<(SessionSummary, bool)> = None;
        if let Some(target) = req.resume_from.clone() {
            let fork = match req.mode.as_deref() {
                None | Some("continue") => false,
                Some("fork") => true,
                Some(_) => return Err(ApiError::validation("mode must be continue or fork")),
            };
            if let Some((id, m)) = self.find_managed(&target) {
                if m.liveness() == Liveness::Live {
                    if m.origin() != "managed" {
                        return Err(ApiError::validation(
                            "this session is already controlled by SwitchTether",
                        ));
                    }
                    if !fork {
                        // Already running here (a second tap, or a retry after a lost reply):
                        // follow that session instead of starting a second writer.
                        return Ok(self.managed_summary(&id, m.as_ref()));
                    }
                }
            }
            let s = self
                .summary(&target)
                .await
                .ok_or_else(|| ApiError::not_found("unknown session to continue"))?;
            if s.agent != req.agent {
                return Err(ApiError::validation("agent does not match the session"));
            }
            if !fork
                && s.liveness == Liveness::Live
                && matches!(s.status.as_str(), "busy" | "waiting")
            {
                return Err(ApiError::conflict(
                    "The session is running on the computer right now (busy or waiting for input). Continue it when it is idle, or fork it instead.",
                )
                .with_details(json!({ "status": s.status, "waitingFor": s.waiting_for })));
            }
            if req.cwd.trim().is_empty() {
                req.cwd = s.cwd.clone().unwrap_or_default();
            }
            continue_from = Some((s, fork));
        }
        let cwd = PathBuf::from(&req.cwd);
        if !cwd.is_absolute() || !cwd.is_dir() {
            return Err(ApiError::validation(
                "cwd must be an existing absolute directory",
            ));
        }
        if crate::files::policy::classify(&cwd) == crate::files::Classification::Denied {
            return Err(ApiError::forbidden("this directory is blocked by policy"));
        }
        // The conversation id, when it is known before the process starts: always for Claude Code
        // (`--session-id` for new conversations and forks), and when continuing in place.
        let claude_session = (req.agent == "claude_code").then(|| match &continue_from {
            Some((s, false)) => s.native_id.clone(),
            _ => crate::util::new_id(),
        });
        let known_native = claude_session.clone().or_else(|| match &continue_from {
            Some((s, false)) => Some(s.native_id.clone()),
            _ => None,
        });
        // Continuing (not forking) the conversation of a window that was closed (a VS Code panel
        // or Codex Desktop attached through the opt-in hook, keyed `attached:…`): the new process
        // takes over that key, so the page the phone has open keeps following the conversation.
        let reuse_key = match &continue_from {
            Some((s, false)) => self
                .find_managed(&s.id)
                .filter(|(k, m)| {
                    k == &s.id && m.origin() != "managed" && self.resumes_on_send(m.as_ref())
                })
                .map(|(k, _)| k),
            _ => None,
        };
        let id = match (&reuse_key, &known_native) {
            (Some(k), _) => k.clone(),
            (None, Some(n)) => public_id(&req.agent, n),
            // Codex / dsh name a new conversation when it starts; the session is re-keyed then.
            (None, None) => format!("starting:{}", crate::util::new_id()),
        };
        let log = Arc::new(Mutex::new(SessionLog::new(
            id.clone(),
            Some(self.bus.clone()),
        )));
        if let Some((s, _)) = &continue_from {
            // Show the whole conversation on the phone, not just the new turns.
            let history = match s.agent.as_str() {
                "claude_code" => s
                    .source_path
                    .clone()
                    .map(PathBuf::from)
                    .filter(|p| p.extension().is_some_and(|e| e == "jsonl"))
                    .or_else(|| self.claude.transcript_path(s.cwd.as_deref(), &s.native_id))
                    .map(|p| (p, Box::new(ClaudeParser::new()) as Box<dyn LineParser>)),
                "codex" => s
                    .source_path
                    .clone()
                    .map(PathBuf::from)
                    .filter(|p| is_rollout_file(p) && p.exists())
                    .or_else(|| find_codex_rollout(&s.native_id))
                    .map(|p| (p, Box::new(CodexParser::new()) as Box<dyn LineParser>)),
                "agy" => s
                    .source_path
                    .clone()
                    .map(PathBuf::from)
                    .filter(|p| p.extension().is_some_and(|e| e == "jsonl") && p.is_file())
                    .or_else(|| self.agy.transcript_path(&s.native_id))
                    .map(|p| {
                        (
                            p,
                            Box::new(agy::transcript::AgyParser::new()) as Box<dyn LineParser>,
                        )
                    }),
                _ => None,
            };
            let loaded_rich = if let Some((path, parser)) = history {
                match load_file(&path, parser, &log) {
                    Ok(_) => true,
                    Err(e) => {
                        log::warn!("[agents] could not preload history: {e}");
                        false
                    }
                }
            } else {
                false
            };
            if s.agent == "agy" {
                if !loaded_rich {
                    // Legacy trajectory database without a JSONL transcript: fall back to CC Switch's parser.
                    if let Some(src) = &s.source_path {
                        match self.backend.session_messages("agy", src).await {
                            Ok(v) => backend_messages_into_log(&v, &log),
                            Err(e) => log::warn!("[agents] could not preload agy history: {e}"),
                        }
                    }
                }
                log.lock().upsert(
                    EventDraft::new(
                        "continued",
                        "system",
                        crate::util::now_rfc3339(),
                        json!({
                            "subtype": "continued",
                            "text": "Continued from the phone in SwitchTether's own Antigravity process (agy acp). The conversation stays in Antigravity's history; an Antigravity window that still has it open does not show these turns.",
                            "from": s.id,
                        }),
                    ),
                    false,
                );
            }
            if s.liveness == Liveness::Live {
                // The original window is still open on the computer: it will not show these turns.
                log.lock().upsert(
                    EventDraft::new(
                        "continued",
                        "system",
                        crate::util::now_rfc3339(),
                        json!({
                            "subtype": "continued",
                            "text": "Continued from the phone. New turns run in a new process on the computer; the window that is still open there does not show them.",
                            "from": s.id,
                        }),
                    ),
                    false,
                );
            }
        }
        let session: Arc<dyn ManagedSession> = match req.agent.as_str() {
            "claude_code" => {
                let bin = self.claude.claude_bin.clone().ok_or_else(|| {
                    ApiError::validation("Claude Code (`claude`) was not found on this computer")
                })?;
                let permission_mode = match req.permission_mode.as_deref() {
                    None => None,
                    Some(m) => match permission::normalize("claude_code", m) {
                        Some("default") => None,
                        Some(m) => Some(m.to_string()),
                        None => {
                            return Err(ApiError::validation(
                                "unknown permissionMode for Claude Code",
                            ))
                        }
                    },
                };
                // Files attached from the phone are readable without a permission prompt.
                let add_dirs: Vec<PathBuf> = self
                    .attachments
                    .read()
                    .as_ref()
                    .map(|a| vec![a.root().to_path_buf()])
                    .unwrap_or_default();
                let (resume_from, fork) = match &continue_from {
                    Some((s, fork)) => (Some(s.native_id.clone()), *fork),
                    None => (None, false),
                };
                ManagedClaude::spawn(
                    id.clone(),
                    ManagedClaudeOptions {
                        claude_bin: bin,
                        cwd,
                        model: req.model.clone(),
                        permission_mode,
                        resume_from,
                        fork,
                        add_dirs,
                        session_id: claude_session.clone(),
                        effort: req.effort.clone(),
                    },
                    log.clone(),
                )
                .await
                .map_err(|e| ApiError::internal("start claude", e))?
            }
            "codex" => {
                let resume_thread = match &continue_from {
                    Some((_, true)) => {
                        return Err(ApiError::validation("fork is not supported for Codex"))
                    }
                    Some((s, false)) => Some(s.native_id.clone()),
                    None => None,
                };
                let bin = self.codex_bin.clone().ok_or_else(|| {
                    ApiError::validation("Codex (`codex`) was not found on this computer")
                })?;
                let permission_mode = permission::normalize(
                    "codex",
                    req.permission_mode.as_deref().unwrap_or("default"),
                )
                .ok_or_else(|| ApiError::validation("unknown permissionMode for Codex"))?;
                ManagedCodex::spawn(
                    id.clone(),
                    ManagedCodexOptions {
                        codex_bin: bin,
                        cwd,
                        model: req.model.clone(),
                        permission_mode: permission_mode.into(),
                        resume_thread,
                        effort: req.effort.clone(),
                    },
                    log.clone(),
                )
                .await
                .map_err(|e| {
                    let msg = format!("{e:#}");
                    if msg.contains("active writer") {
                        // Codex enforces a single writer per thread.
                        ApiError::conflict("This Codex thread is open in another Codex client (Codex Desktop or a terminal). Close it there, then continue it here.")
                    } else {
                        ApiError::internal("start codex", msg)
                    }
                })?
            }
            "dsh" => {
                if matches!(&continue_from, Some((_, true))) {
                    return Err(ApiError::validation(
                        "fork is not supported for DeepSeek Harness",
                    ));
                }
                let bin = self.dsh_bin.clone().ok_or_else(|| {
                    ApiError::validation("DeepSeek Harness (`dsh`) was not found on this computer")
                })?;
                dsh::managed::ManagedDsh::spawn(
                    id.clone(),
                    dsh::managed::ManagedDshOptions {
                        dsh_bin: bin,
                        cwd,
                        resume_session: continue_from.as_ref().map(|(s, _)| s.native_id.clone()),
                    },
                    log.clone(),
                )
                .await
                .map_err(|e| ApiError::internal("start dsh", format!("{e:#}")))?
            }
            "agy" => {
                if matches!(&continue_from, Some((_, true))) {
                    return Err(ApiError::validation(
                        "fork is not supported for Antigravity",
                    ));
                }
                let bin = self.agy_bin.clone().ok_or_else(|| {
                    ApiError::validation(
                        "Google Antigravity (`agy`) was not found on this computer",
                    )
                })?;
                let permission_mode = match req.permission_mode.as_deref() {
                    None => None,
                    Some(m) => Some(
                        permission::normalize("agy", m)
                            .ok_or_else(|| {
                                ApiError::validation("unknown permissionMode for Antigravity")
                            })?
                            .to_string(),
                    ),
                };
                let add_dirs: Vec<PathBuf> = self
                    .attachments
                    .read()
                    .as_ref()
                    .map(|a| vec![a.root().to_path_buf()])
                    .unwrap_or_default();
                agy::managed::ManagedAgy::spawn(
                    id.clone(),
                    agy::managed::ManagedAgyOptions {
                        agy_bin: bin,
                        cwd,
                        resume_session: continue_from.as_ref().map(|(s, _)| s.native_id.clone()),
                        model: req.model.clone(),
                        effort: req.effort.clone(),
                        permission_mode,
                        add_dirs,
                    },
                    log.clone(),
                )
                .await
                .map_err(|e| ApiError::internal("start agy", format!("{e:#}")))?
            }
            other => {
                return Err(ApiError::unavailable_capability(
                    "UNTESTED",
                    format!("managed sessions are not available for '{other}'"),
                ))
            }
        };
        let id = match (&known_native, session.native_session_id()) {
            (None, Some(n)) => {
                let key = public_id(&req.agent, &n);
                log.lock().rekey(&key);
                key
            }
            _ => id,
        };
        {
            let mut m = self.managed.write();
            // An ended run of the same conversation is replaced by the new process.
            m.remove(&id);
            m.insert(id.clone(), session.clone());
        }
        // A history view of this conversation must not tail the transcript a second time.
        self.loaded.lock().remove(&id);
        self.remember_options(&id);
        if let Some(p) = req.prompt.as_deref().filter(|p| !p.trim().is_empty()) {
            session.send_message(p).await?;
        }
        let summary = self.managed_summary(&id, session.as_ref());
        self.bus.publish("session.upserted", json!(summary));
        Ok(summary)
    }

    /// The session the phone's message goes to. A conversation that is not running (history, or a
    /// run that ended, e.g. after the desktop restarted) is resumed the official way first
    /// (`claude --resume <id>`, `thread/resume`, `session/resume`) and keeps its id.
    async fn session_for_send(&self, id: &str) -> ApiResult<Arc<dyn ManagedSession>> {
        if let Some((_, m)) = self.find_managed(id) {
            if m.liveness() == Liveness::Live || !self.resumes_on_send(m.as_ref()) {
                return Ok(m);
            }
        }
        if let Some(s) = self.resumable(id).await {
            // What the phone chose while it was not running, then what the conversation last ran
            // with: the run that ended here, or — after the desktop restarted — the remembered
            // options. `claude --resume`, `thread/resume` and `session/resume` restore the history
            // but start on the agent's defaults, so a model, effort or mode switched mid-session
            // was otherwise lost on the next message.
            let previous = self
                .find_managed(id)
                .map(|(_, m)| NextStart::of(m.as_ref()))
                .or_else(|| self.remembered.lock().get(id).cloned())
                .unwrap_or_default();
            let next = self
                .next_start
                .lock()
                .remove(id)
                .unwrap_or_default()
                .or_previous(previous);
            let summary = self
                .create_managed(CreateSession {
                    agent: s.agent.clone(),
                    cwd: s.cwd.clone().unwrap_or_default(),
                    prompt: None,
                    model: next.model,
                    permission_mode: next.permission_mode,
                    resume_from: Some(s.id.clone()),
                    mode: Some("continue".into()),
                    effort: next.effort,
                })
                .await?;
            if let Some((_, m)) = self.find_managed(&summary.id) {
                return Ok(m);
            }
        }
        self.managed_or_blocked(id)
    }

    /// An ended session whose conversation a message resumes in a process SwitchTether starts:
    /// its own managed runs, and sessions of a window that was closed (a VS Code panel or Codex
    /// Desktop attached through the opt-in hook) once the conversation has an id. A mirrored
    /// terminal is not resumed here: its conversation id may never have been matched.
    fn resumes_on_send(&self, m: &dyn ManagedSession) -> bool {
        m.liveness() != Liveness::Live
            && m.terminal_id().is_none()
            && m.native_session_id().is_some_and(|n| !n.is_empty())
    }

    /// History of an agent SwitchTether can resume: not running anywhere, agent installed.
    async fn resumable(&self, id: &str) -> Option<SessionSummary> {
        let s = self.summary(id).await?;
        (s.liveness != Liveness::Live && self.can_resume(&s)).then_some(s)
    }

    fn can_resume(&self, s: &SessionSummary) -> bool {
        let installed = match s.agent.as_str() {
            "claude_code" => self.claude.claude_bin.is_some(),
            "codex" => self.codex_bin.is_some(),
            "dsh" => self.dsh_bin.is_some(),
            "agy" => self.agy_bin.is_some(),
            _ => false,
        };
        installed && !s.native_id.is_empty()
    }

    /// Change model and / or effort of a running session (`""` or `default`: back to the default).
    pub async fn set_options(
        &self,
        id: &str,
        model: Option<String>,
        effort: Option<String>,
    ) -> ApiResult<SessionSummary> {
        self.set_session_options(id, model, effort, None).await
    }

    /// [`Self::set_options`] with a permission mode too (`crate::agents::permission` ids).
    pub async fn set_session_options(
        &self,
        id: &str,
        model: Option<String>,
        effort: Option<String>,
        permission_mode: Option<String>,
    ) -> ApiResult<SessionSummary> {
        let live = self
            .find_managed(id)
            .filter(|(_, m)| m.liveness() == Liveness::Live);
        let Some((key, m)) = live else {
            // Not running: the choice is kept and applied when a message resumes it.
            let Some(mut s) = self.resumable(id).await else {
                return Err(match self.summary(id).await {
                    None => ApiError::not_found("unknown session"),
                    Some(x) if x.liveness == Liveness::Live => ApiError::unavailable_capability(
                        "BLOCKED",
                        "This conversation is running in a window on the computer that SwitchTether does not control; change its model there, or turn on the takeover hook in Settings → Remote.",
                    ),
                    Some(_) => ApiError::conflict(
                        "This conversation cannot be resumed here (its agent is not installed on the computer).",
                    ),
                });
            };
            // Absent: unchanged; "" or "default": back to the agent's default.
            let clean =
                |v: &str| Some(v.trim().to_string()).filter(|x| !x.is_empty() && x != "default");
            let model = model.as_deref().map(clean);
            let effort = effort.as_deref().map(clean);
            if model
                .as_ref()
                .and_then(|m| m.as_deref())
                .is_some_and(|x| !valid_model(x))
            {
                return Err(ApiError::validation("invalid model name"));
            }
            if effort
                .as_ref()
                .and_then(|e| e.as_deref())
                .is_some_and(|e| !valid_effort(&s.agent, e))
            {
                return Err(ApiError::validation("invalid effort level for this agent"));
            }
            let mode = match permission_mode.as_deref() {
                None => None,
                Some(m) => Some(
                    permission::normalize(&s.agent, m)
                        .ok_or_else(|| ApiError::validation("unknown permission mode"))?,
                ),
            };
            let mut pending = self.next_start.lock();
            let entry = pending.entry(id.to_string()).or_default();
            if let Some(m) = model {
                entry.model = m;
                entry.model_set = true;
            }
            if let Some(e) = effort {
                entry.effort = e;
                entry.effort_set = true;
            }
            if let Some(m) = mode {
                entry.permission_mode = Some(m.to_string()).filter(|m| m != "default");
                entry.permission_mode_set = true;
            }
            s.model = entry.model.clone().or(s.model);
            s.effort = entry.effort.clone();
            return Ok(s);
        };
        if let Some(mode) = permission_mode.as_deref() {
            if !m.can_change_permission_mode() {
                return Err(ApiError::unavailable_capability(
                    "BLOCKED",
                    "This session cannot change its permission mode from the phone.",
                ));
            }
            m.set_permission_mode(mode).await?;
        }
        if (model.is_some() || effort.is_some()) && !m.can_change_model() {
            return Err(ApiError::unavailable_capability(
                "BLOCKED",
                ATTACHED_MODEL_REASON,
            ));
        }
        if let Some(model) = model.as_deref().map(str::trim) {
            let model = Some(model).filter(|x| !x.is_empty() && *x != "default");
            if model.is_some_and(|x| !valid_model(x)) {
                return Err(ApiError::validation("invalid model name"));
            }
            m.set_model(model).await?;
        }
        if let Some(effort) = effort.as_deref() {
            let effort = Some(effort).filter(|x| !x.is_empty() && *x != "default");
            if effort.is_some_and(|e| !valid_effort(m.agent(), e)) {
                return Err(ApiError::validation("invalid effort level for this agent"));
            }
            m.set_effort(effort).await?;
        }
        self.remember_options(id);
        let summary = self.managed_summary(&key, m.as_ref());
        self.bus.publish("session.upserted", json!(summary));
        Ok(summary)
    }

    /// Models and effort levels the phone can offer for an agent (and a running session).
    pub async fn model_options(&self, agent: &str, session: Option<&str>) -> Value {
        let live = session.and_then(|id| {
            self.find_managed(id)
                .filter(|(_, m)| m.liveness() == Liveness::Live)
                .map(|(_, m)| m)
        });
        let mut reported = match &live {
            Some(m) => m.models().await,
            None => Value::Null,
        };
        if model_list(&reported).is_empty() {
            // No running session to ask (new session, a conversation not running): the agent's
            // own list, read by a short probe and cached.
            reported = self.agent_model_list(agent).await;
        }
        let mut v = model_catalog(agent, &reported);
        // The permission modes the launcher offers for this agent (same ids for every agent).
        v["permissionModes"] = permission::modes(agent);
        v
    }

    /// The agent's official model list (see `model_probe`), cached for 30 minutes; a failed probe
    /// keeps the last list it had.
    async fn agent_model_list(&self, agent: &str) -> Value {
        const TTL: Duration = Duration::from_secs(30 * 60);
        if let Some((at, v)) = self.model_lists.lock().get(agent) {
            if at.elapsed() < TTL {
                return v.clone();
            }
        }
        let fresh = match agent {
            "claude_code" => match &self.claude.claude_bin {
                Some(b) => model_probe::claude_models(b).await,
                None => None,
            },
            "codex" => match &self.codex_bin {
                Some(b) => model_probe::codex_models(b).await,
                None => None,
            },
            // Agents this crate does not launch: the desktop asks them (e.g. `agy models`).
            // The signed-in CLI's own list (`agy models`), its tiered ids grouped into models
            // with thinking levels; without a binary here, the desktop's list.
            "agy" => {
                let own = match &self.agy_bin {
                    Some(b) => agy::list_models(b).await,
                    None => None,
                };
                match own {
                    Some(pairs) => Some(agy::group_models(&pairs)),
                    None => self
                        .backend
                        .agent_models(agent)
                        .await
                        .ok()
                        .filter(|v| !model_list(v).is_empty()),
                }
            }
            _ => None,
        };
        let mut lists = self.model_lists.lock();
        match fresh {
            Some(v) => {
                lists.insert(agent.to_string(), (Instant::now(), v.clone()));
                v
            }
            None => lists
                .get(agent)
                .map(|(_, v)| v.clone())
                .unwrap_or(Value::Null),
        }
    }

    /// The phone may send messages (and attachments) to this session: it runs under
    /// SwitchTether's control, or it is a conversation SwitchTether resumes on the first message.
    pub async fn ensure_can_send(&self, id: &str) -> ApiResult<()> {
        if let Some((_, m)) = self.find_managed(id) {
            if m.liveness() == Liveness::Live || !self.resumes_on_send(m.as_ref()) {
                return Ok(());
            }
        }
        if self.resumable(id).await.is_some() {
            return Ok(());
        }
        self.managed_or_blocked(id).map(|_| ())
    }

    fn managed_or_blocked(&self, id: &str) -> ApiResult<Arc<dyn ManagedSession>> {
        if let Some((_, m)) = self.find_managed(id) {
            return Ok(m);
        }
        Err(ApiError::unavailable_capability(
            "BLOCKED",
            "This session was started outside SwitchTether. Claude Code and Codex expose no supported interface to send input, approve or interrupt an existing session from another program. Start a managed session (optionally continuing this history) to control it from the phone.",
        ))
    }

    /// `client_id` (the phone's command id) is echoed as `data.clientId` on the
    /// resulting `user_message` event (optimistic UI on the phone).
    pub async fn send_message(
        &self,
        id: &str,
        text: &str,
        client_id: Option<&str>,
    ) -> ApiResult<Value> {
        self.send_message_with_images(id, text, &[], client_id)
            .await
    }

    pub async fn send_message_with_images(
        &self,
        id: &str,
        text: &str,
        images: &[managed::ImageAttachment],
        client_id: Option<&str>,
    ) -> ApiResult<Value> {
        self.send_message_full(id, text, images, &[], "", client_id)
            .await
    }

    /// A message from the phone: text, inline images (older phones) and uploaded attachments
    /// (`attachment_ids`, see [`attachments`]). Attachments reach every agent as absolute paths
    /// appended to the text; images among them are also inlined where the agent accepts images.
    pub async fn send_message_full(
        &self,
        id: &str,
        text: &str,
        images: &[managed::ImageAttachment],
        attachment_ids: &[String],
        device_id: &str,
        client_id: Option<&str>,
    ) -> ApiResult<Value> {
        if (text.trim().is_empty() && images.is_empty() && attachment_ids.is_empty())
            || text.len() > 200_000
        {
            return Err(ApiError::validation(
                "message must be 1..200000 bytes (or include images or attachments)",
            ));
        }
        let atts = if attachment_ids.is_empty() {
            Vec::new()
        } else {
            self.attachments()?.resolve(device_id, id, attachment_ids)?
        };
        let m = self.session_for_send(id).await?;
        if (text.trim().is_empty() && images.is_empty() && atts.is_empty()) || text.len() > 200_000
        {
            return Err(ApiError::validation(
                "message must be 1..200000 bytes (or include images or attachments)",
            ));
        }
        let mut images = images.to_vec();
        if m.supports_images() {
            use base64::Engine;
            for a in atts
                .iter()
                .filter(|a| a.is_image() && a.size <= 3 * 1024 * 1024)
            {
                if images.len() >= 4 {
                    break;
                }
                if let Ok(bytes) = std::fs::read(&a.path) {
                    images.push(managed::ImageAttachment {
                        media_type: a.media_type.clone(),
                        data_base64: base64::engine::general_purpose::STANDARD.encode(bytes),
                        name: Some(a.name.clone()),
                    });
                }
            }
        }
        let text = attachments::compose(text, &atts);
        let text = text.as_str();
        let images = images.as_slice();
        if images.len() > 4 {
            return Err(ApiError::validation("at most 4 images per message"));
        }
        let mut total_b64 = 0usize;
        for img in images {
            if !matches!(
                img.media_type.as_str(),
                "image/png" | "image/jpeg" | "image/webp" | "image/gif"
            ) {
                return Err(ApiError::validation(format!(
                    "unsupported image mediaType '{}'",
                    img.media_type
                )));
            }
            if img.data_base64.is_empty() || img.data_base64.len() > 4 * 1024 * 1024 {
                return Err(ApiError::validation("image payload too large"));
            }
            total_b64 += img.data_base64.len();
        }
        if total_b64 > 8 * 1024 * 1024 {
            return Err(ApiError::validation("total image payload exceeds limit"));
        }
        if !images.is_empty() && !m.supports_images() {
            return Err(ApiError::unavailable_capability(
                "BLOCKED",
                "This session does not support image input.",
            ));
        }
        if let Some(c) = client_id.filter(|c| !c.is_empty() && c.len() <= 128) {
            m.log().lock().expect_user_message(text, c);
        }
        m.send_message_with_images(text, images).await?;
        // A Claude Code message SwitchTether sent carries this id: cancel it while it is queued.
        Ok(json!({ "accepted": true, "messageUuid": m.last_message_uuid() }))
    }

    pub async fn interrupt(&self, id: &str) -> ApiResult<Value> {
        let m = self.managed_or_blocked(id)?;
        m.interrupt().await?;
        Ok(json!({ "interrupted": true }))
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn respond(
        &self,
        id: &str,
        request_id: &str,
        decision: &str,
        message: Option<String>,
        updated_input: Option<Value>,
        answers: Option<Value>,
        by: &str,
    ) -> ApiResult<Value> {
        let m = self.managed_or_blocked(id)?;
        m.respond(request_id, decision, message, updated_input, answers, by)
            .await
    }

    /// Stop a managed session's process (history stays readable).
    pub async fn stop(&self, id: &str) -> ApiResult<Value> {
        let m = self.managed_or_blocked(id)?;
        m.kill();
        Ok(json!({ "stopped": true }))
    }

    /// Watch live external sessions and publish changes (status, waitingFor,
    /// appearance/disappearance) so phones can notify about e.g. a terminal
    /// session waiting for a permission prompt. Claude Code rewrites its registry file on each
    /// status change; an OS change notification for the registry directory triggers an immediate
    /// re-read, so short turns are not missed and nothing runs while nothing changes. A metadata
    /// check (every 2 s, or 0.5 s when no OS watch is available) catches lost notifications, and a
    /// full refresh runs every 4 s. `active()` gates all of it while no phone is connected.
    pub fn start_live_watcher(
        self: &Arc<Self>,
        cancel: CancellationToken,
        active: Arc<dyn Fn() -> bool + Send + Sync>,
    ) {
        let hub = Arc::downgrade(self);
        tokio::spawn(async move {
            type ObservedState = (Option<String>, Option<String>, Option<String>, Option<u32>);
            let mut prev: HashMap<String, ObservedState> = HashMap::new();
            // Since when a published session has been missing. Registry files are rewritten often and
            // a read can land mid-write, so one miss is not an exit (it showed up on phones as a
            // burst of "ended" sessions right after Claude Code started).
            let mut missing: HashMap<String, Instant> = HashMap::new();
            // Last pushed title of each managed/attached Claude session. Claude Code writes the
            // generated title to the transcript after the turn's last event, so no event-driven
            // upsert ever carries it; it is picked up here.
            let mut managed_titles: HashMap<String, Option<String>> = HashMap::new();
            let claude_dir = match hub.upgrade() {
                Some(h) => h.claude.claude_dir.clone(),
                None => return,
            };
            let mut watch = claude::live::watch_registry(&claude_dir);
            let mut agy_watch = hub.upgrade().and_then(|h| h.agy.watch());
            let mut prev_agy: HashMap<String, ObservedState> = HashMap::new();
            let mut missing_agy: HashMap<String, Instant> = HashMap::new();
            let mut full = tokio::time::interval(Duration::from_secs(4));
            let mut fast = tokio::time::interval(fallback_poll(watch.is_some()));
            full.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            fast.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                let (full_tick, notified, agy_notified) = tokio::select! {
                    _ = cancel.cancelled() => break,
                    _ = full.tick() => (true, false, false),
                    _ = fast.tick() => (false, false, false),
                    Some(()) = recv_change(&mut watch) => (false, true, false),
                    Some(()) = recv_change(&mut agy_watch) => (false, false, true),
                };
                if notified || agy_notified {
                    // One status change is several writes: settle briefly, then read once.
                    tokio::time::sleep(Duration::from_millis(80)).await;
                    if let Some((_, rx)) = watch.as_mut() {
                        while rx.try_recv().is_ok() {}
                    }
                    if let Some((_, rx)) = agy_watch.as_mut() {
                        while rx.try_recv().is_ok() {}
                    }
                }
                if full_tick && watch.is_none() {
                    // The directory may appear after Claude Code's first run.
                    watch = claude::live::watch_registry(&claude_dir);
                    if watch.is_some() {
                        fast = tokio::time::interval(fallback_poll(true));
                        fast.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
                    }
                }
                if full_tick && agy_watch.is_none() {
                    agy_watch = hub.upgrade().and_then(|h| h.agy.watch());
                }
                if !active() {
                    continue;
                }
                let Some(hub) = hub.upgrade() else { break };
                let agy_changed = full_tick || agy_notified || hub.agy.changed();
                if agy_changed {
                    let managed_native: Vec<String> = hub
                        .managed
                        .read()
                        .values()
                        .filter_map(|m| m.native_session_id())
                        .collect();
                    let mut now_agy = HashMap::new();
                    for r in hub
                        .agy
                        .live_sessions()
                        .iter()
                        .filter(|r| !managed_native.contains(&r.conversation_id))
                    {
                        let Some(summary) = hub.observed_agy_summary(r) else {
                            continue;
                        };
                        let id = summary.id.clone();
                        let state = (
                            Some(summary.status.clone()),
                            summary.waiting_for.clone(),
                            summary.title.clone(),
                            summary.active_tasks,
                        );
                        if prev_agy.get(&id) != Some(&state) {
                            if !prev_agy.contains_key(&id) {
                                hub.follow_loaded(&summary);
                            }
                            hub.bus.publish("session.upserted", json!(summary));
                        }
                        now_agy.insert(id, state);
                    }
                    missing_agy.retain(|id, _| !now_agy.contains_key(id));
                    let gone_agy: Vec<(String, ObservedState)> = prev_agy
                        .into_iter()
                        .filter(|(k, _)| !now_agy.contains_key(k))
                        .collect();
                    for (id, state) in gone_agy {
                        let since = *missing_agy.entry(id.clone()).or_insert_with(Instant::now);
                        if since.elapsed() >= MISSING_GRACE {
                            missing_agy.remove(&id);
                            hub.bus.publish(
                                "session.removed",
                                json!({ "id": id, "liveness": "ended" }),
                            );
                        } else {
                            now_agy.insert(id, state);
                        }
                    }
                    prev_agy = now_agy;
                }

                let changed = hub.claude.registry_changed();
                let live = if full_tick {
                    hub.claude.live_sessions().await
                } else if changed || notified {
                    hub.claude.live_sessions_now().await
                } else {
                    continue;
                };
                // A managed session's own process also appears in the Claude registry;
                // it is reported once, under its `managed:` id, never as a second session.
                let owned = hub.owned_claude();
                let mut now = HashMap::new();
                for l in live.iter().filter(|l| !owned.covers(l)) {
                    let id = format!("claude:{}", l.session_id);
                    let Some(summary) = hub.observed_live_summary(l) else {
                        // Not announced yet (fresh process, see `observed_live_summary`); one that
                        // was already announced stays until it really leaves the registry.
                        if let Some(state) = prev.get(&id) {
                            now.insert(id, state.clone());
                        }
                        continue;
                    };
                    let state = (
                        l.status.clone(),
                        l.waiting_for.clone(),
                        summary.title.clone(),
                        summary.active_tasks,
                    );
                    if prev.get(&id) != Some(&state) {
                        if !prev.contains_key(&id) {
                            // Started or resumed on the computer: a log opened earlier as history
                            // follows the transcript from now on.
                            hub.follow_loaded(&summary);
                        }
                        hub.bus.publish("session.upserted", json!(summary));
                    }
                    now.insert(id, state);
                }
                // Seen this tick: no longer missing.
                missing.retain(|id, _| !now.contains_key(id));
                let gone: Vec<(String, ObservedState)> = prev
                    .into_iter()
                    .filter(|(k, _)| !now.contains_key(k))
                    .collect();
                for (id, state) in gone {
                    let since = *missing.entry(id.clone()).or_insert_with(Instant::now);
                    if since.elapsed() >= MISSING_GRACE {
                        missing.remove(&id);
                        hub.bus
                            .publish("session.removed", json!({ "id": id, "liveness": "ended" }));
                    } else {
                        // Kept a little longer; reappearing with the same state publishes nothing.
                        now.insert(id, state);
                    }
                }
                prev = now;
                if !full_tick {
                    continue;
                }

                let claude_managed: Vec<(String, Arc<dyn ManagedSession>)> = hub
                    .managed
                    .read()
                    .iter()
                    .filter(|(_, m)| matches!(m.agent(), "claude_code" | "codex" | "agy"))
                    .map(|(k, m)| (k.clone(), m.clone()))
                    .collect();
                managed_titles.retain(|id, _| claude_managed.iter().any(|(k, _)| k == id));
                for (id, m) in claude_managed {
                    let summary = hub.managed_summary(&id, m.as_ref());
                    match managed_titles.get(&id) {
                        Some(t) if *t == summary.title => {}
                        seen => {
                            let first = seen.is_none();
                            managed_titles.insert(id.clone(), summary.title.clone());
                            // The first sighting only records the baseline; lists already have it.
                            if !first {
                                hub.bus.publish("session.upserted", json!(summary));
                            }
                        }
                    }
                }
            }
        });
    }

    pub fn managed_count(&self) -> usize {
        // Attached sessions belong to the user's processes and survive SwitchTether.
        self.managed
            .read()
            .values()
            .filter(|m| m.origin() == "managed" && m.liveness() == Liveness::Live)
            .count()
    }

    /// Kill every managed process (called when SwitchTether really quits) and
    /// honestly mark them as ended so connected phones don't treat them as live.
    pub fn shutdown(&self) {
        let managed_ids: Vec<String> = self
            .managed
            .read()
            .iter()
            .filter(|(_, m)| m.origin() == "managed")
            .map(|(id, m)| {
                m.kill();
                id.clone()
            })
            .collect();
        for id in managed_ids {
            self.publish_session(&id);
        }
    }

    /// Status events of managed / attached sessions also refresh their
    /// summary (`session.upserted`), so lists show busy / waiting / idle live.
    fn start_status_relay(self: &Arc<Self>, cancel: CancellationToken) {
        let hub = Arc::downgrade(self);
        let mut rx = self.bus.subscribe();
        tokio::spawn(async move {
            type RelayKey = (String, Option<String>, Option<String>, Option<u32>);
            let mut last: HashMap<String, RelayKey> = HashMap::new();
            loop {
                let frame = tokio::select! {
                    _ = cancel.cancelled() => break,
                    f = rx.recv() => f,
                };
                let frame = match frame {
                    Ok(f) => f,
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => break,
                };
                // Sub-agent rows and background shells change `activeTasks`.
                let background_call =
                    frame.data.pointer("/data/background") == Some(&Value::Bool(true));
                if frame.topic != "session.event"
                    || !(matches!(
                        frame.data.get("kind").and_then(|k| k.as_str()),
                        Some(
                            "status"
                                | "approval_request"
                                | "question"
                                | "user_message"
                                | "system"
                                | "subagent"
                        )
                    ) || background_call)
                {
                    continue;
                }
                let Some(id) = frame.data.get("sessionId").and_then(|s| s.as_str()) else {
                    continue;
                };
                let Some(hub) = hub.upgrade() else { break };
                let m = hub.managed.read().get(id).cloned();
                if m.is_none()
                    && (id.starts_with("dsh:") || id.starts_with("agy:"))
                    && frame.data.get("kind").and_then(|k| k.as_str()) == Some("status")
                {
                    // An observed DeepSeek Harness session open on the phone: its tailed log just
                    // started or ended a turn; refresh its summary (busy / idle) at once.
                    if let Some(summary) = hub.summary(id).await {
                        let key = (
                            summary.status.clone(),
                            summary.waiting_for.clone(),
                            summary.title.clone(),
                            summary.active_tasks,
                        );
                        if last.get(id) != Some(&key) {
                            last.insert(id.to_string(), key);
                            hub.bus.publish("session.upserted", json!(summary));
                        }
                    }
                    continue;
                }
                if let Some(m) = m {
                    let summary = hub.managed_summary(id, m.as_ref());
                    let key = (
                        summary.status.clone(),
                        summary.waiting_for.clone(),
                        summary.title.clone(),
                        summary.active_tasks,
                    );
                    if last.get(id) != Some(&key) {
                        last.insert(id.to_string(), key);
                        hub.bus.publish("session.upserted", json!(summary));
                    }
                }
            }
        });
    }

    /// Tail live transcripts in the background.
    pub fn start_tailer(self: &Arc<Self>, cancel: CancellationToken) {
        self.start_status_relay(cancel.clone());
        let hub = Arc::downgrade(self);
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(TAIL_INTERVAL);
            loop {
                tokio::select! {
                    _ = cancel.cancelled() => break,
                    _ = tick.tick() => {}
                }
                let Some(hub) = hub.upgrade() else { break };
                let h = hub.clone();
                let _ = tokio::task::spawn_blocking(move || h.tail_once()).await;
            }
        });
    }

    fn tail_once(&self) {
        let mut loaded = self.loaded.lock();
        let stale: Vec<String> = loaded
            .iter()
            .filter(|(_, l)| l.last_access.elapsed() > Duration::from_secs(30 * 60))
            .map(|(k, _)| k.clone())
            .collect();
        for k in stale {
            loaded.remove(&k);
        }
        for l in loaded.values_mut() {
            if let Some(t) = l.tail.as_mut() {
                if let Err(e) = tail_file(t, &l.log, true) {
                    log::debug!("[agents] tail error: {e}");
                }
                add_running_subagents(&t.path, &l.log, true);
            }
        }
        drop(loaded);
        let ttys: Vec<_> = self.ttys.read().values().cloned().collect();
        for t in ttys {
            t.tail_now();
        }
    }
}

/// Capabilities of a history-only agent's (not live) session, as `capabilities_for` reports it.
#[allow(dead_code)]
fn history_caps(agent: &str) -> Capabilities {
    let mut c = observed_caps(agent);
    c.live_events = None;
    c
}

fn observed_caps(agent: &str) -> Capabilities {
    let rich = matches!(agent, "claude_code" | "codex" | "agy");
    let live = matches!(agent, "claude_code" | "codex" | "dsh" | "agy");
    let continuable = matches!(agent, "claude_code" | "codex" | "dsh" | "agy");
    Capabilities {
        read_history: Some(if rich { CapStatus::Verified } else { CapStatus::Partial }),
        live_events: Some(if live { CapStatus::Partial } else { CapStatus::Blocked }),
        send_message: Some(CapStatus::Blocked),
        approve: Some(CapStatus::Blocked),
        answer_question: Some(CapStatus::Blocked),
        interrupt: Some(CapStatus::Blocked),
        continue_session: continuable.then_some(CapStatus::Partial),
        change_model: None,
        reason: Some(match agent {
            "claude_code" => "The original Claude Code process (CLI / VS Code) cannot be controlled from another program. Use \"Continue on phone\": the same conversation continues in a SwitchTether process (only while the original is idle; the original window will not show the new turns).".into(),
            "codex" => "The original Codex process (CLI / Desktop) cannot be controlled from another program. Use \"Continue on phone\": the thread continues on SwitchTether's own app-server, which Codex allows only when the thread is not open elsewhere.".into(),
            "dsh" => "A DeepSeek Harness window (web / desktop / terminal) cannot be controlled from another program without the opt-in plugin. Use \"Continue on phone\": the session is resumed on SwitchTether's own `dsh --profile acp` process once it is no longer active elsewhere.".into(),
            "agy" => "The original Antigravity process (agy CLI / IDE) cannot be controlled from another program unless started with `st-agy`. Its transcript is tailed live read-only; use \"Continue on phone\" (once idle) to resume the conversation on SwitchTether's own `agy acp` process.".into(),
            _ => "History only.".into(),
        }),
    }
}

/// Why `changeModel` is `BLOCKED` for a session of a user-started process.
pub(crate) const ATTACHED_MODEL_REASON: &str =
    "This session runs in a window on the computer; change its model there.";

/// Sessions of user-started processes controlled through switchtether-shim.
fn attached_caps(s: &SessionSummary) -> Capabilities {
    let live = s.liveness == Liveness::Live;
    let on = |c: CapStatus| Some(if live { c } else { CapStatus::Blocked });
    if s.terminal_id.is_some() {
        return Capabilities {
            read_history: Some(CapStatus::Partial),
            live_events: on(CapStatus::Partial),
            send_message: on(CapStatus::Partial),
            approve: Some(CapStatus::Partial),
            answer_question: Some(CapStatus::Partial),
            interrupt: on(CapStatus::Partial),
            continue_session: None,
            change_model: None,
            reason: Some(
                "Runs in a terminal window on the computer: prompts are answered in the mirrored terminal view.".into(),
            ),
        };
    }
    Capabilities {
        read_history: Some(CapStatus::Verified),
        live_events: on(CapStatus::Partial),
        send_message: on(CapStatus::Partial),
        approve: on(CapStatus::Partial),
        answer_question: on(CapStatus::Partial),
        interrupt: on(CapStatus::Partial),
        continue_session: None,
        change_model: None,
        reason: (!live).then(|| "The process on the computer has exited.".into()),
    }
}

fn norm_path(p: &str) -> String {
    let p = p.trim_end_matches(['/', '\\']).replace('\\', "/");
    if cfg!(windows) {
        p.to_lowercase()
    } else {
        p
    }
}

/// A conversation that is not running anywhere and can be resumed by sending a message.
fn resumable_caps(agent: &str) -> Capabilities {
    let how = match agent {
        "claude_code" => "claude --resume",
        "codex" => "codex resume",
        "agy" => "agy acp, session/resume",
        _ => "dsh resume",
    };
    Capabilities {
        read_history: Some(CapStatus::Verified),
        live_events: None,
        send_message: Some(CapStatus::Partial),
        approve: Some(CapStatus::Blocked),
        answer_question: Some(CapStatus::Blocked),
        interrupt: Some(CapStatus::Blocked),
        continue_session: Some(CapStatus::Partial),
        // Kept and applied when a message resumes it (see AgentHub::next_start).
        change_model: Some(CapStatus::Partial),
        reason: Some(format!(
            "Not running. Sending a message resumes this conversation on the computer ({how}); it keeps its id and stays in the usual history."
        )),
    }
}

/// Models and effort levels offered on the phone. The agent's own list (Claude Code's initialize
/// response, Codex `model/list`) is used when a running session reported one; otherwise the
/// aliases every installation accepts.
/// The model entries of a reported list: Claude Code's `models` array, Codex's `data`, or the
/// array itself.
fn model_list(reported: &Value) -> Vec<Value> {
    reported
        .get("data")
        .or_else(|| reported.get("models"))
        .unwrap_or(reported)
        .as_array()
        .cloned()
        .unwrap_or_default()
}

/// Answer of the `commands` session tool: the slash commands a Claude Code process reported
/// (`source: "agent"`), or an empty list with `source: "none"` when there is no such list (the
/// phone then offers its built-in commands).
pub fn command_catalog(commands: Vec<Value>) -> Value {
    let source = if commands.is_empty() { "none" } else { "agent" };
    json!({ "commands": commands, "source": source })
}

pub fn model_catalog(agent: &str, reported: &Value) -> Value {
    let mut models: Vec<Value> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    let mut push = |id: &str, label: &str, description: Option<&str>, efforts: Option<Value>| {
        if id.is_empty() || !seen.insert(id.to_string()) {
            return;
        }
        let mut m = json!({ "id": id, "label": label });
        if let Some(d) = description.filter(|d| !d.is_empty()) {
            m["description"] = json!(d);
        }
        if let Some(e) = efforts {
            m["efforts"] = e;
        }
        models.push(m);
    };
    let list = model_list(reported);
    let official = !list.is_empty();
    for r in list
        .iter()
        .filter(|r| r.get("hidden").and_then(Value::as_bool) != Some(true))
    {
        let id = r
            .get("value")
            .or_else(|| r.get("model"))
            .or_else(|| r.get("id"))
            .and_then(Value::as_str)
            .unwrap_or_default();
        let label = r
            .get("displayName")
            .or_else(|| r.get("display_name"))
            .and_then(Value::as_str)
            .unwrap_or(id);
        let efforts = r
            .get("supportedReasoningEfforts")
            .or_else(|| r.get("supportedEffortLevels"))
            .and_then(Value::as_array)
            .map(|a| {
                json!(a
                    .iter()
                    .filter_map(|e| e
                        .as_str()
                        .or_else(|| e.get("reasoningEffort").and_then(Value::as_str))
                        .or_else(|| e.get("effort").and_then(Value::as_str)))
                    .collect::<Vec<_>>())
            })
            // A model the agent says takes no effort level gets an empty list (the phone then
            // hides the effort control instead of offering the generic levels).
            .or_else(|| {
                (r.get("supportsEffort").and_then(Value::as_bool) == Some(false)).then(|| json!([]))
            });
        push(
            id,
            label,
            r.get("description").and_then(Value::as_str),
            efforts,
        );
    }
    let efforts: &[&str] = match agent {
        "claude_code" => {
            // The aliases every installation accepts, only when the agent's own list is unknown.
            for (id, label) in if official {
                &[][..]
            } else {
                &[
                    ("default", "Default"),
                    ("opus", "Opus"),
                    ("sonnet", "Sonnet"),
                    ("haiku", "Haiku"),
                    ("opusplan", "Opus Plan"),
                ][..]
            } {
                push(id, label, None, None);
            }
            &["low", "medium", "high", "xhigh", "max"]
        }
        "codex" => &["minimal", "low", "medium", "high", "xhigh"],
        "agy" => &agy::LEVELS,
        _ => &[],
    };
    json!({
        "agent": agent,
        "models": models,
        "efforts": efforts,
        "source": if official { "agent" } else { "builtin" },
    })
}

/// Messages read by CC Switch's own session parser (`[{role, content, ts}]`) as history events.
fn backend_messages_into_log(v: &Value, log: &Mutex<SessionLog>) {
    let mut lg = log.lock();
    for (i, m) in v.as_array().into_iter().flatten().enumerate() {
        let role = m
            .get("role")
            .and_then(|r| r.as_str())
            .unwrap_or("assistant");
        let text = m
            .get("content")
            .and_then(|c| c.as_str())
            .unwrap_or("")
            .to_string();
        let kind = if role == "user" {
            "user_message"
        } else if role == "tool" {
            "tool_result"
        } else {
            "assistant_message"
        };
        let ts = m
            .get("ts")
            .and_then(|t| t.as_i64())
            .map(crate::util::ms_to_rfc3339)
            .unwrap_or_default();
        lg.upsert(
            EventDraft::new(
                format!("m{i}"),
                kind,
                ts,
                json!({ "text": text, "source": "unknown" }),
            ),
            false,
        );
    }
}

/// A conversation on SwitchTether's own `agy acp` process (docs/AGY_REMOTE.md, verification).
fn agy_managed_caps() -> Capabilities {
    Capabilities {
        read_history: Some(CapStatus::Verified),
        live_events: Some(CapStatus::Verified),
        send_message: Some(CapStatus::Verified),
        approve: Some(CapStatus::Verified),
        // ACP has no question request; agy asks in its answer text.
        answer_question: Some(CapStatus::Blocked),
        interrupt: Some(CapStatus::Verified),
        continue_session: None,
        change_model: None,
        reason: None,
    }
}

fn managed_caps() -> Capabilities {
    Capabilities {
        read_history: Some(CapStatus::Verified),
        live_events: Some(CapStatus::Verified),
        send_message: Some(CapStatus::Verified),
        approve: Some(CapStatus::Verified),
        answer_question: Some(CapStatus::Partial),
        interrupt: Some(CapStatus::Verified),
        continue_session: None,
        change_model: None,
        reason: None,
    }
}

/// Output file of a background task in an observed Claude Code session. Claude Code names it in
/// the task's start result ("Output is being written to: …/tasks/<id>.output", or the agent
/// result's `outputFile`). Only a file named exactly `<task id>.output` is accepted, so a
/// transcript cannot point the desktop at any other file.
fn observed_task_output_path(lg: &SessionLog, task: &str) -> Option<PathBuf> {
    let want = format!("{task}.output");
    // The parser keeps the path on the task's row: a background shell's `tool_call`
    // (`taskId`) or a sub-agent's row (`agentId`).
    lg.iter()
        .filter(|e| {
            let d = &e.data;
            (e.kind == "tool_call" && d.get("taskId").and_then(Value::as_str) == Some(task))
                || (e.kind == "subagent" && d.get("agentId").and_then(Value::as_str) == Some(task))
        })
        .filter_map(|e| e.data.get("outputFile").and_then(Value::as_str))
        .map(PathBuf::from)
        .find(|p| p.file_name().and_then(|n| n.to_str()) == Some(want.as_str()) && p.is_file())
}

/// The newest 64 KiB of a task output file, redacted.
pub(crate) fn read_task_output(path: &Path) -> ApiResult<Value> {
    use std::io::{Read, Seek, SeekFrom};
    const KEEP: u64 = 64 * 1024;
    let mut f = std::fs::File::open(path).map_err(|e| ApiError::internal("task output", e))?;
    let len = f.metadata().map(|m| m.len()).unwrap_or(0);
    let start = len.saturating_sub(KEEP);
    f.seek(SeekFrom::Start(start))
        .map_err(|e| ApiError::internal("task output", e))?;
    let mut buf = Vec::new();
    f.read_to_end(&mut buf)
        .map_err(|e| ApiError::internal("task output", e))?;
    let text = String::from_utf8_lossy(&buf);
    Ok(json!({
        "output": crate::redact::redact_text(&text),
        "totalBytes": len,
        "truncated": start > 0,
    }))
}

/// A session log as plain text, like Claude Code's own export: prompts (`❯`), answers (`●`) and
/// one line per tool call.
pub(crate) fn export_text(lg: &SessionLog) -> String {
    let mut out = String::new();
    for e in lg.iter().filter(|e| e.parent_id.is_none()) {
        let text = e
            .data
            .get("text")
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim();
        match e.kind.as_str() {
            "user_message" if !text.is_empty() => {
                out.push_str("\n❯ ");
                out.push_str(&text.replace('\n', "\n  "));
                out.push('\n');
            }
            "assistant_message" if !text.is_empty() => {
                out.push_str("\n● ");
                out.push_str(&text.replace('\n', "\n  "));
                out.push('\n');
            }
            "tool_call" => {
                let name = e.data.get("name").and_then(Value::as_str).unwrap_or("");
                let title = e.data.get("title").and_then(Value::as_str).unwrap_or("");
                out.push_str(&format!("  ⎿ {name}({title})\n"));
            }
            _ => {}
        }
    }
    out
}

/// Live Agent map state from a session log: per spawning call id (`callId` of each `subagent`
/// row) the fields the map shows, and the background shell tasks. A sub-agent is `waiting`
/// while a permission request or question raised inside it (parent chain reaches its call or
/// its row) is unanswered.
fn agent_map_state(lg: &SessionLog) -> (HashMap<String, Value>, Vec<Value>) {
    let by_id: HashMap<&str, &model::AgentEvent> = lg.iter().map(|e| (e.id.as_str(), e)).collect();
    let mut waiting: std::collections::HashSet<String> = std::collections::HashSet::new();
    for e in lg.iter().filter(|e| {
        matches!(e.kind.as_str(), "approval_request" | "question")
            && e.data.get("resolved").and_then(Value::as_bool) != Some(true)
    }) {
        let mut cur = e.parent_id.as_deref();
        let mut hops = 0;
        while let Some(p) = cur {
            waiting.insert(p.to_string());
            cur = by_id.get(p).and_then(|x| x.parent_id.as_deref());
            hops += 1;
            if hops > 64 {
                break;
            }
        }
        // Claude Code names the call the request belongs to.
        if let Some(t) = e.data.get("toolUseId").and_then(Value::as_str) {
            waiting.insert(t.to_string());
        }
    }
    let mut live = HashMap::new();
    for e in lg.iter().filter(|e| e.kind == "subagent") {
        let d = &e.data;
        let Some(call) = d
            .get("callId")
            .and_then(Value::as_str)
            .map(String::from)
            .or_else(|| e.parent_id.clone())
        else {
            continue;
        };
        let pick = |k: &str| d.get(k).cloned().unwrap_or(Value::Null);
        let preview_of = |k: &str| {
            d.get(k)
                .and_then(Value::as_str)
                .map(|t| json!(model::preview(t, 600).0))
                .unwrap_or(Value::Null)
        };
        let prompt = by_id
            .values()
            .find(|c| {
                c.kind == "tool_call"
                    && c.data.get("callId").and_then(Value::as_str) == Some(call.as_str())
            })
            .and_then(|c| c.data.pointer("/input/prompt").and_then(Value::as_str))
            .map(|p| json!(model::preview(p, 2000).0))
            .unwrap_or(Value::Null);
        let running = d.get("status").and_then(Value::as_str) == Some("running");
        live.insert(
            call.clone(),
            json!({
                "agentId": pick("agentId"),
                "status": pick("status"),
                "waiting": running && (waiting.contains(&call) || waiting.contains(&e.id)),
                "background": pick("background"),
                "model": pick("model"),
                "tokens": pick("tokens"),
                "toolUses": pick("toolUses"),
                "durationMs": pick("durationMs"),
                "activity": pick("activity"),
                "summary": preview_of("summary"),
                "result": preview_of("result"),
                "prompt": prompt,
                "startedAt": e.ts,
                "finishedAt": pick("finishedAt"),
                "toolUseId": call,
            }),
        );
    }
    let tasks = lg
        .iter()
        .filter(|e| {
            e.kind == "tool_call" && e.data.get("background").and_then(Value::as_bool) == Some(true)
        })
        .map(|e| {
            let d = &e.data;
            json!({
                "taskId": d.get("taskId"),
                "callId": d.get("callId"),
                "title": d.get("title"),
                "status": d.get("status"),
                "summary": d.get("backgroundSummary"),
                "exitCode": d.get("exitCode"),
                "startedAt": e.ts,
                "finishedAt": d.get("finishedAt"),
            })
        })
        .collect();
    (live, tasks)
}

/// Work still running in a session log: sub-agents whose row is `running` and background shells
/// (`tool_call` with `background`) not finished yet. A managed Claude Code process also reports
/// its background tasks (agents and shells) in the rolling status event (`backgroundTasks`, from
/// stream-json `background_tasks_changed`); the larger of the two counts wins, as the same
/// background agent shows up in both.
fn active_task_count(lg: &SessionLog) -> u32 {
    let running =
        |e: &model::AgentEvent| e.data.get("status").and_then(Value::as_str) == Some("running");
    let from_rows = lg
        .iter()
        .filter(|e| {
            (e.kind == "subagent"
                || (e.kind == "tool_call"
                    && e.data.get("background").and_then(Value::as_bool) == Some(true)))
                && running(e)
        })
        .count();
    let reported = lg
        .get("status:current")
        .and_then(|e| e.data.get("backgroundTasks"))
        .and_then(Value::as_u64)
        .unwrap_or(0) as usize;
    u32::try_from(from_rows.max(reported)).unwrap_or(u32::MAX)
}

/// `subagent` rows of a session log as tree nodes; the parent is the nearest enclosing
/// `subagent` row (a sub-agent started by a sub-agent).
fn log_subagent_nodes(lg: &SessionLog) -> Vec<Value> {
    let by_id: HashMap<&str, &model::AgentEvent> = lg.iter().map(|e| (e.id.as_str(), e)).collect();
    let mut out = Vec::new();
    for e in lg.iter().filter(|e| e.kind == "subagent") {
        let Some(agent_id) = e.data.get("agentId").and_then(Value::as_str) else {
            continue;
        };
        let mut parent_agent: Option<String> = None;
        let mut cur = e.parent_id.as_deref();
        let mut hops = 0;
        while let Some(pid) = cur {
            let Some(p) = by_id.get(pid) else { break };
            if p.kind == "subagent" {
                parent_agent = p
                    .data
                    .get("agentId")
                    .and_then(Value::as_str)
                    .map(String::from);
                break;
            }
            cur = p.parent_id.as_deref();
            hops += 1;
            if hops > 64 {
                break;
            }
        }
        let status = e.data.get("status").and_then(Value::as_str);
        let updated = chrono::DateTime::parse_from_rfc3339(&e.ts)
            .ok()
            .map(|t| t.timestamp_millis());
        out.push(json!({
            "agentId": agent_id,
            "description": e.data.get("description").filter(|v| v.is_string()),
            "agentType": e.data.get("agentType").filter(|v| v.is_string()),
            "toolUseId": e.data.get("callId").filter(|v| v.is_string()),
            "parentAgentId": parent_agent,
            "finished": matches!(status, Some("completed" | "failed" | "interrupted")),
            "updatedAtMs": updated,
        }));
    }
    out
}

/// Events whose parent chain reaches `root` (not `root` itself); direct children lose their
/// parent link so they are top-level on the sub-agent's own page.
fn nested_under(lg: &SessionLog, root: &str) -> Vec<model::AgentEvent> {
    let by_id: HashMap<&str, &model::AgentEvent> = lg.iter().map(|e| (e.id.as_str(), e)).collect();
    lg.iter()
        .filter(|e| {
            let mut cur = e.parent_id.as_deref();
            let mut hops = 0;
            while let Some(pid) = cur {
                if pid == root {
                    return true;
                }
                cur = by_id.get(pid).and_then(|p| p.parent_id.as_deref());
                hops += 1;
                if hops > 64 {
                    break;
                }
            }
            false
        })
        .map(|e| {
            let mut e = e.clone();
            if e.parent_id.as_deref() == Some(root) {
                e.parent_id = None;
            }
            e
        })
        .collect()
}

/// A transcript / rollout file SwitchTether can read: plain JSONL, or zstd-compressed (Codex
/// compresses older rollouts to `.jsonl.zst`; DeepSeek Harness writes `.jsonl.zstd`).
pub(crate) fn is_rollout_file(p: &Path) -> bool {
    let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
    name.ends_with(".jsonl") || name.ends_with(".jsonl.zst") || name.ends_with(".jsonl.zstd")
}

/// Whether a log is zstd-compressed (read from its start, frame by frame).
pub(crate) fn is_zstd_file(p: &Path) -> bool {
    p.extension().is_some_and(|e| e == "zstd" || e == "zst")
}

/// A Codex thread's own rollout (`rollout-<time>-<thread id>.jsonl`, or `.jsonl.zst` once Codex
/// compressed it), searched under `$CODEX_HOME/sessions` and `archived_sessions`.
fn find_codex_rollout(thread_id: &str) -> Option<PathBuf> {
    if thread_id.is_empty()
        || !thread_id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-')
    {
        return None;
    }
    let home = codex::codex_home();
    find_rollout_in(&home.join("sessions"), thread_id)
        .or_else(|| find_rollout_in(&home.join("archived_sessions"), thread_id))
}

fn find_rollout_in(root: &Path, thread_id: &str) -> Option<PathBuf> {
    let root = root.to_path_buf();
    let plain = format!("-{thread_id}.jsonl");
    let packed = format!("-{thread_id}.jsonl.zst");
    // sessions/YYYY/MM/DD/: newest days first, bounded walk.
    let mut dirs = vec![root];
    let mut seen = 0;
    while let Some(d) = dirs.pop() {
        let Ok(rd) = std::fs::read_dir(&d) else {
            continue;
        };
        let mut subdirs = Vec::new();
        for e in rd.flatten() {
            seen += 1;
            if seen > 20_000 {
                return None;
            }
            let p = e.path();
            if p.is_dir() {
                subdirs.push(p);
            } else if p.file_name().and_then(|n| n.to_str()).is_some_and(|n| {
                n.starts_with("rollout-") && (n.ends_with(&plain) || n.ends_with(&packed))
            }) {
                return Some(p);
            }
        }
        subdirs.sort();
        dirs.extend(subdirs); // pop() takes the newest first
    }
    None
}

/// A DeepSeek Harness session that is not (yet) in CC Switch's index, read from its own log.
fn dsh_summary_from_log(id: &str) -> Option<SessionSummary> {
    let native = id.strip_prefix("dsh:")?;
    let path = dsh::find_session_dir(native).and_then(|d| dsh::session_file(&d))?;
    let header = dsh::read_header(&path)?;
    let cwd = header
        .get("cwd")
        .and_then(Value::as_str)
        .map(str::to_string);
    let modified = std::fs::metadata(&path)
        .ok()
        .and_then(|m| m.modified().ok());
    let live = modified
        .and_then(|t| t.elapsed().ok())
        .is_some_and(|e| e < Duration::from_secs(120));
    let (title, _) = dsh::child_summary(&path);
    Some(SessionSummary {
        id: id.to_string(),
        agent: "dsh".into(),
        origin: Origin::Unknown,
        native_id: native.to_string(),
        title,
        project: cwd.as_deref().and_then(SessionSummary::project_from_cwd),
        cwd,
        liveness: if live {
            Liveness::Live
        } else {
            Liveness::Ended
        },
        status: if live { "busy" } else { "ended" }.into(),
        waiting_for: None,
        control: if live {
            Control::Observe
        } else {
            Control::History
        },
        created_at_ms: header.get("createdAt").and_then(Value::as_i64),
        last_active_at_ms: modified
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as i64),
        model: None,
        effort: None,
        git_branch: None,
        pid: None,
        managed: false,
        terminal_id: None,
        active_tasks: None,
        source_path: Some(path.to_string_lossy().into_owned()),
    })
}

/// Log file of a DeepSeek Harness session: the indexed path, else its directory by id.
fn dsh_log_path(s: &SessionSummary) -> Option<PathBuf> {
    s.source_path
        .clone()
        .map(PathBuf::from)
        .filter(|p| p.is_file())
        .or_else(|| dsh::find_session_dir(&s.native_id).and_then(|d| dsh::session_file(&d)))
}

/// Sub-agent tree of a DeepSeek Harness session from the child sessions' headers.
fn dsh_subagent_nodes(s: &SessionSummary) -> Vec<Value> {
    let Some(dir) = dsh_log_path(s).and_then(|p| p.parent().map(Path::to_path_buf)) else {
        return Vec::new();
    };
    let kids = dsh::descendants(&dir, &s.native_id);
    kids.iter()
        .map(|c| {
            // Label / model of the child from its own log (`subagent/descriptor`, title).
            let (label, finished) = dsh::child_summary(&c.path);
            json!({
                "agentId": c.id,
                "description": label,
                "agentType": "subagent",
                "parentAgentId": (c.parent != dsh::norm_id(&s.native_id)).then(|| c.parent.clone()),
                "spawnDepth": c.depth,
                "finished": finished,
                "updatedAtMs": c.modified_ms,
            })
        })
        .collect()
}

pub(crate) fn valid_agent_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

/// Display fields of a sub-agent's sidecar metadata (`agent-<id>.meta.json`), whitelisted:
/// never the prompt or anything else Claude Code may add later.
fn subagent_meta(dir: &Path, agent_id: &str) -> Option<Value> {
    let text = std::fs::read_to_string(dir.join(format!("agent-{agent_id}.meta.json"))).ok()?;
    let m: Value = serde_json::from_str(&text).ok()?;
    let mut o = serde_json::Map::new();
    for k in ["agentType", "description", "toolUseId", "parentAgentId"] {
        if let Some(v) = m.get(k).filter(|v| v.is_string()) {
            o.insert(k.to_string(), v.clone());
        }
    }
    if let Some(v) = m.get("finished").filter(|v| v.is_boolean()) {
        o.insert("finished".into(), v.clone());
    }
    if let Some(v) = m.get("spawnDepth").filter(|v| v.is_u64()) {
        o.insert("spawnDepth".into(), v.clone());
    }
    Some(Value::Object(o))
}

fn load_file(
    path: &Path,
    parser: Box<dyn LineParser>,
    log: &Arc<Mutex<SessionLog>>,
) -> ApiResult<Tail> {
    let meta =
        std::fs::metadata(path).map_err(|_| ApiError::not_found("transcript file not found"))?;
    let zstd = is_zstd_file(path);
    // A zstd log can only be read from a frame boundary: always from the start.
    let start = if zstd {
        0
    } else {
        meta.len().saturating_sub(MAX_TRANSCRIPT_BYTES)
    };
    let mut t = Tail {
        path: path.to_path_buf(),
        offset: start,
        partial: Vec::new(),
        parser,
        zstd,
        raw: Vec::new(),
    };
    if start > 0 {
        log.lock().upsert(
            EventDraft::new("trunc", "system", crate::util::now_rfc3339(), json!({ "subtype": "truncated", "text": "Older history omitted (transcript is very large)." })),
            false,
        );
    }
    tail_file(&mut t, log, false).map_err(|e| ApiError::internal("read transcript", e))?;
    Ok(t)
}

impl Tail {
    /// Follows `path` from its start (a zstd log is decoded frame by frame).
    pub(crate) fn new(path: PathBuf, parser: Box<dyn LineParser>) -> Self {
        let zstd = is_zstd_file(&path);
        Self {
            path,
            offset: 0,
            partial: Vec::new(),
            parser,
            zstd,
            raw: Vec::new(),
        }
    }
}

/// Claude Code transcripts: rows for sub-agents still running (see
/// [`claude::running_subagent_rows`]). Other logs have no Agent/Task calls and cost one scan.
fn add_running_subagents(path: &Path, log: &Arc<Mutex<SessionLog>>, publish: bool) {
    if path.extension().is_none_or(|e| e != "jsonl") {
        return;
    }
    let rows = claude::running_subagent_rows(&log.lock(), path);
    if !rows.is_empty() {
        let mut lg = log.lock();
        for d in rows {
            lg.upsert(d, publish);
        }
    }
}

pub(crate) fn tail_file(
    t: &mut Tail,
    log: &Arc<Mutex<SessionLog>>,
    publish: bool,
) -> std::io::Result<()> {
    let len = std::fs::metadata(&t.path)?.len();
    if len < t.offset {
        // Truncated/rewritten: start over from the beginning of the new file.
        t.offset = 0;
        t.partial.clear();
        t.raw.clear();
    }
    if len == t.offset {
        return Ok(());
    }
    let mut f = std::fs::File::open(&t.path)?;
    f.seek(SeekFrom::Start(t.offset))?;
    let mut buf = Vec::with_capacity((len - t.offset).min(MAX_TRANSCRIPT_BYTES) as usize);
    f.take(MAX_TRANSCRIPT_BYTES).read_to_end(&mut buf)?;
    t.offset += buf.len() as u64;
    if t.zstd {
        // Only complete frames are decoded; a half-written last frame waits in `raw`.
        t.raw.extend_from_slice(&buf);
        let (decoded, used) = dsh::decode_frames(&t.raw);
        t.raw.drain(..used);
        t.partial.extend_from_slice(&decoded);
    } else {
        t.partial.extend_from_slice(&buf);
    }
    let mut lg = log.lock();
    let mut start = 0;
    while let Some(nl) = t.partial[start..].iter().position(|&b| b == b'\n') {
        let line = &t.partial[start..start + nl];
        start += nl + 1;
        if line.iter().all(|b| b.is_ascii_whitespace()) {
            continue;
        }
        if let Ok(v) = serde_json::from_slice::<Value>(line) {
            for d in t.parser.feed(&v) {
                lg.upsert(d, publish);
            }
        }
    }
    t.partial.drain(..start);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::backend::UnavailableBackend;

    #[test]
    fn resume_options_fill_unchosen_from_the_previous_run() {
        let previous = NextStart {
            model: Some("opus".into()),
            effort: Some("high".into()),
            permission_mode: Some("acceptEdits".into()),
            ..NextStart::default()
        };
        // Nothing chosen while the conversation was not running: the previous run's options.
        let next = NextStart::default().or_previous(previous.clone());
        assert_eq!(next.model.as_deref(), Some("opus"));
        assert_eq!(next.effort.as_deref(), Some("high"));
        assert_eq!(next.permission_mode.as_deref(), Some("acceptEdits"));
        // Chosen on the phone: a new model wins, "back to the default" stays the default.
        let chosen = NextStart {
            model: Some("sonnet".into()),
            model_set: true,
            effort: None,
            effort_set: true,
            ..NextStart::default()
        };
        let next = chosen.or_previous(previous);
        assert_eq!(next.model.as_deref(), Some("sonnet"));
        assert_eq!(next.effort, None);
        assert_eq!(next.permission_mode.as_deref(), Some("acceptEdits"));
    }

    #[test]
    fn remembered_options_survive_a_restart() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("session-options.json");
        let mut map = HashMap::new();
        map.insert(
            "s1".to_string(),
            NextStart {
                model: Some("opus".into()),
                effort: Some("max".into()),
                permission_mode: None,
                ..NextStart::default()
            },
        );
        std::fs::write(&path, serde_json::to_string(&map).unwrap()).unwrap();
        let hub = AgentHub::new(
            EventBus::new(8),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(tmp.path().to_path_buf(), None),
        );
        hub.set_session_options_file(path);
        let got = hub.remembered.lock().get("s1").cloned().unwrap();
        assert_eq!(got.model.as_deref(), Some("opus"));
        assert_eq!(got.effort.as_deref(), Some("max"));
        // Loaded options are previous-run values, never phone choices.
        assert!(!got.model_set && !got.effort_set && !got.permission_mode_set);
    }

    #[tokio::test]
    async fn transcript_tail_appends_live_events() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("s.jsonl");
        std::fs::write(&path, "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"content\":\"hi\"}}\n{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"content\":[{\"type\":\"text\",\"text\":\"he").unwrap();
        let bus = EventBus::new(64);
        let log = Arc::new(Mutex::new(SessionLog::new("claude:s", Some(bus.clone()))));
        let mut t = load_file(&path, Box::new(ClaudeParser::new()), &log).unwrap();
        assert_eq!(log.lock().len(), 1);
        // complete the partial line + append a new one
        use std::io::Write;
        let mut f = std::fs::OpenOptions::new()
            .append(true)
            .open(&path)
            .unwrap();
        f.write_all(b"llo\"}]}}\n").unwrap();
        tail_file(&mut t, &log, true).unwrap();
        assert_eq!(log.lock().len(), 2);
        assert_eq!(bus.head_seq(), 1, "tail events are published");
    }

    #[tokio::test]
    async fn history_opened_before_a_resume_follows_new_turns() {
        let tmp = tempfile::tempdir().unwrap();
        let proj = tmp.path().join("projects").join("p");
        std::fs::create_dir_all(&proj).unwrap();
        let path = proj.join("old-1.jsonl");
        std::fs::write(
            &path,
            "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"content\":\"hi\"}}
",
        )
        .unwrap();
        let bus = EventBus::new(64);
        let hub = AgentHub::new(
            bus.clone(),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(tmp.path().to_path_buf(), None),
        );
        let s = hub.summary("claude:old-1").await.unwrap();
        assert_eq!(s.liveness, Liveness::Ended);
        let log = hub.ensure_loaded(&s).await.unwrap();
        assert_eq!(log.lock().len(), 1);
        // `claude --resume old-1` on the computer appends to the same transcript.
        use std::io::Write;
        let mut f = std::fs::OpenOptions::new()
            .append(true)
            .open(&path)
            .unwrap();
        f.write_all(
            b"{\"type\":\"user\",\"uuid\":\"u2\",\"message\":{\"content\":\"again\"}}
",
        )
        .unwrap();
        hub.tail_once();
        assert_eq!(log.lock().len(), 2, "the resumed turn reaches the open log");
        assert!(bus.head_seq() >= 1, "and is published live");
    }

    #[tokio::test]
    async fn subagent_transcripts_are_listed_as_a_tree_and_tailed() {
        let tmp = tempfile::tempdir().unwrap();
        let proj = tmp.path().join("projects").join("-tmp-x");
        let sub = proj.join("s-1").join("subagents");
        std::fs::create_dir_all(&sub).unwrap();
        std::fs::write(
            proj.join("s-1.jsonl"),
            "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"content\":\"hi\"}}\n",
        )
        .unwrap();
        let rec = |uuid: &str, text: &str| {
            format!("{{\"type\":\"user\",\"isSidechain\":true,\"agentId\":\"a1\",\"uuid\":\"{uuid}\",\"message\":{{\"content\":\"{text}\"}}}}\n")
        };
        std::fs::write(sub.join("agent-a1.jsonl"), rec("c1", "look around")).unwrap();
        std::fs::write(
            sub.join("agent-a1.meta.json"),
            r#"{"agentType":"Explore","description":"Find files","toolUseId":"toolu_1","spawnDepth":1,"secretPrompt":"x"}"#,
        )
        .unwrap();
        std::fs::write(sub.join("agent-a2.jsonl"), "").unwrap();
        std::fs::write(
            sub.join("agent-a2.meta.json"),
            r#"{"description":"Nested","parentAgentId":"a1","spawnDepth":2,"finished":true}"#,
        )
        .unwrap();
        let hub = AgentHub::new(
            EventBus::new(64),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(tmp.path().to_path_buf(), None),
        );
        let tree = hub.subagent_tree("claude:s-1").await.unwrap();
        let agents = tree["agents"].as_array().unwrap();
        assert_eq!(agents.len(), 2);
        let nested = agents.iter().find(|a| a["agentId"] == "a2").unwrap();
        assert_eq!(nested["parentAgentId"], "a1");
        assert_eq!(nested["spawnDepth"], 2);
        let top = agents.iter().find(|a| a["agentId"] == "a1").unwrap();
        assert!(top.get("secretPrompt").is_none(), "metadata is whitelisted");

        let v = hub
            .subagent_events("claude:s-1", "a1", None, None, 50)
            .await
            .unwrap();
        assert_eq!(v["events"].as_array().unwrap().len(), 1);
        assert_eq!(v["meta"]["agentType"], "Explore");
        // A running sub-agent appends; the next request sees it.
        use std::io::Write;
        std::fs::OpenOptions::new()
            .append(true)
            .open(sub.join("agent-a1.jsonl"))
            .unwrap()
            .write_all(rec("c2", "more").as_bytes())
            .unwrap();
        let v = hub
            .subagent_events("claude:s-1", "a1", None, None, 50)
            .await
            .unwrap();
        assert_eq!(v["events"].as_array().unwrap().len(), 2);
        // Path traversal and unknown agents are refused.
        assert!(hub
            .subagent_events("claude:s-1", "../s-1", None, None, 50)
            .await
            .is_err());
        assert!(hub
            .subagent_events("claude:s-1", "nope", None, None, 50)
            .await
            .is_err());
    }

    /// Observed sessions: a background task's output is read only from the file Claude Code named
    /// for exactly that task; export renders the log as text.
    #[test]
    fn observed_task_output_and_export() {
        let tmp = tempfile::tempdir().unwrap();
        let out = tmp.path().join("tasks").join("b1.output");
        std::fs::create_dir_all(out.parent().unwrap()).unwrap();
        std::fs::write(&out, "line 1\nbuild ok\n").unwrap();
        let mut lg = SessionLog::new("claude:o", None);
        let mut p = ClaudeParser::new();
        for r in [
            json!({"type":"user","message":{"content":"Build it"}}),
            json!({"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"cargo build","description":"Build","run_in_background":true}}]}}),
            json!({"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":
                format!("Command running in background with ID: b1. Output is being written to: {}. You will be notified when it completes.", out.display())}]},
                "toolUseResult":{"stdout":"","backgroundTaskId":"b1"}}),
            json!({"type":"assistant","message":{"id":"m2","content":[{"type":"text","text":"Started."}]}}),
        ] {
            for d in p.feed(&r) {
                lg.upsert(d, false);
            }
        }
        let path = observed_task_output_path(&lg, "b1").expect("output path");
        assert_eq!(path, out);
        let v = read_task_output(&path).unwrap();
        assert_eq!(v["output"], "line 1\nbuild ok\n");
        // Another task id never resolves to this file.
        assert!(observed_task_output_path(&lg, "b2").is_none());
        let text = export_text(&lg);
        assert!(text.contains("❯ Build it"));
        assert!(text.contains("⎿ Bash(Build)"));
        assert!(text.contains("● Started."));
    }

    /// Session tools and Stop only act on a process SwitchTether started: an observed session is
    /// refused before anything is sent.
    #[tokio::test]
    async fn session_tools_are_blocked_for_observed_sessions() {
        let tmp = tempfile::tempdir().unwrap();
        let proj = tmp.path().join("projects").join("p");
        std::fs::create_dir_all(&proj).unwrap();
        std::fs::write(
            proj.join("observed-1.jsonl"),
            "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"content\":\"hi\"}}\n",
        )
        .unwrap();
        let hub = AgentHub::new(
            EventBus::new(8),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(tmp.path().to_path_buf(), None),
        );
        // Anything that needs the process is refused; nothing is sent anywhere.
        for action in [
            "permission_mode",
            "rewind",
            "cancel_message",
            "rename",
            "context_usage",
            "commands",
        ] {
            let e = hub
                .claude_tool("claude:observed-1", action, &json!({ "mode": "plan" }))
                .await
                .unwrap_err();
            assert_eq!(
                e.code,
                crate::error::ErrorCode::CapabilityUnavailable,
                "{action}"
            );
        }
        // What the files hold is offered read-only.
        let export = hub
            .claude_tool("claude:observed-1", "export", &json!({}))
            .await
            .unwrap();
        assert!(export["text"].as_str().unwrap().contains("❯ hi"));
        let plan = hub
            .claude_tool("claude:observed-1", "plan", &json!({}))
            .await
            .unwrap();
        assert_eq!(plan["exists"], false);
        let e = hub
            .stop_subagent("claude:observed-1", "a1")
            .await
            .unwrap_err();
        assert_eq!(e.code, crate::error::ErrorCode::CapabilityUnavailable);
        let e = hub
            .stop_subagent("claude:observed-1", "../x")
            .await
            .unwrap_err();
        assert_eq!(e.code, crate::error::ErrorCode::ValidationFailed);
    }

    #[test]
    fn agent_map_state_reads_live_rows_waiting_and_tasks() {
        let mut lg = SessionLog::new("claude:m", None);
        let mut p = ClaudeParser::new();
        let recs = [
            json!({"type":"assistant","message":{"id":"m1","content":[
                {"type":"tool_use","id":"toolu_a","name":"Agent","input":{"description":"Scan","prompt":"Look at src","subagent_type":"Explore"}},
                {"type":"tool_use","id":"toolu_b","name":"Bash","input":{"command":"cargo build","run_in_background":true}}]}}),
            json!({"type":"system","subtype":"task_started","task_id":"a1","tool_use_id":"toolu_a","task_type":"local_agent","is_backgrounded":false}),
            json!({"type":"system","subtype":"task_progress","task_id":"a1","tool_use_id":"toolu_a","description":"Reading main.rs","usage":{"total_tokens":50,"tool_uses":2,"duration_ms":3000}}),
            json!({"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_b","content":"started"}]},"tool_use_result":{"stdout":"","backgroundTaskId":"b9"}}),
        ];
        for r in &recs {
            for d in p.feed(r) {
                lg.upsert(d, false);
            }
        }
        // A permission prompt raised inside the sub-agent.
        let mut req = EventDraft::new(
            "req:1",
            "approval_request",
            "2026-11-21T00:00:00Z",
            json!({"resolved":false,"toolUseId":"toolu_in"}),
        );
        req.parent_id = Some("toolu_a".into());
        lg.upsert(req, false);
        let (live, tasks) = agent_map_state(&lg);
        let a = &live["toolu_a"];
        assert_eq!(a["agentId"], "a1");
        assert_eq!(a["status"], "running");
        assert_eq!(a["waiting"], true);
        assert_eq!(a["activity"], "Reading main.rs");
        assert_eq!(a["toolUses"], 2);
        assert_eq!(a["prompt"], "Look at src");
        assert_eq!(tasks.len(), 1);
        assert_eq!(tasks[0]["taskId"], "b9");
        assert_eq!(tasks[0]["status"], "running");
    }

    #[test]
    fn active_task_count_counts_running_subagents_and_background_shells() {
        let mut lg = SessionLog::new("claude:m", None);
        let mut p = ClaudeParser::new();
        let recs = [
            json!({"type":"assistant","message":{"id":"m1","content":[
                {"type":"tool_use","id":"toolu_a","name":"Agent","input":{"description":"Scan","prompt":"Look","subagent_type":"Explore","run_in_background":true}},
                {"type":"tool_use","id":"toolu_b","name":"Bash","input":{"command":"cargo build","run_in_background":true}},
                {"type":"tool_use","id":"toolu_c","name":"Bash","input":{"command":"ls"}}]}}),
            json!({"type":"system","subtype":"task_started","task_id":"a1","tool_use_id":"toolu_a","task_type":"local_agent","is_backgrounded":true}),
            json!({"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_b","content":"started"}]},"tool_use_result":{"stdout":"","backgroundTaskId":"b9"}}),
            json!({"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_c","content":"a.txt"}]}}),
        ];
        for r in &recs {
            for d in p.feed(r) {
                lg.upsert(d, false);
            }
        }
        // One background agent, one background shell; the finished foreground Bash is not work.
        assert_eq!(active_task_count(&lg), 2);
        // The shell reports back.
        for d in p.feed(
            &json!({"type":"user","message":{"content":"<task-notification>
<task-id>b9</task-id>
<tool-use-id>toolu_b</tool-use-id>
<status>completed</status>
<summary>Background command \"cargo build\" completed (exit code 0)</summary>
</task-notification>"}}),
        ) {
            lg.upsert(d, false);
        }
        assert_eq!(active_task_count(&lg), 1);
        // A managed process reporting more background tasks (stream-json) than the log shows.
        lg.upsert(
            EventDraft::new(
                "status:current",
                "status",
                "2026-11-27T00:00:00Z",
                json!({"state":"idle","backgroundTasks":3}),
            ),
            false,
        );
        assert_eq!(active_task_count(&lg), 3);
    }

    #[test]
    fn codex_subagents_come_from_the_session_log() {
        let mut lg = SessionLog::new("codex:t1", None);
        let mk = |id: &str, kind: &'static str, parent: Option<&str>, data: Value| {
            let mut d = EventDraft::new(id, kind, "2026-11-18T10:00:00Z".to_string(), data);
            d.parent_id = parent.map(String::from);
            d
        };
        lg.upsert(
            mk("call", "tool_call", None, json!({"name":"spawn_agent"})),
            false,
        );
        lg.upsert(
            mk(
                "cx-sa-A",
                "subagent",
                Some("call"),
                json!({"agentId":"A","description":"Child","status":"running"}),
            ),
            false,
        );
        lg.upsert(
            mk(
                "a1",
                "assistant_message",
                Some("cx-sa-A"),
                json!({"text":"working"}),
            ),
            false,
        );
        lg.upsert(
            mk("a2", "tool_call", Some("cx-sa-A"), json!({"name":"shell"})),
            false,
        );
        lg.upsert(
            mk("a2r", "tool_result", Some("a2"), json!({"output":"ok"})),
            false,
        );
        lg.upsert(
            mk(
                "cx-sa-B",
                "subagent",
                Some("a2"),
                json!({"agentId":"B","status":"completed"}),
            ),
            false,
        );
        lg.upsert(
            mk("main", "assistant_message", None, json!({"text":"parent"})),
            false,
        );
        let nodes = log_subagent_nodes(&lg);
        assert_eq!(nodes.len(), 2);
        assert_eq!(nodes[0]["parentAgentId"], Value::Null);
        assert_eq!(nodes[1]["parentAgentId"], "A");
        assert_eq!(nodes[1]["finished"], true);
        let under = nested_under(&lg, "cx-sa-A");
        let ids: Vec<_> = under.iter().map(|e| e.id.as_str()).collect();
        assert_eq!(ids, vec!["a1", "a2", "a2r", "cx-sa-B"]);
        assert_eq!(under[0].parent_id, None, "direct children become top-level");
        assert_eq!(under[2].parent_id.as_deref(), Some("a2"));
    }

    #[tokio::test]
    async fn a_short_turn_between_full_refreshes_is_published() {
        let tmp = tempfile::tempdir().unwrap();
        let proj = tmp.path().join("projects").join("p");
        std::fs::create_dir_all(&proj).unwrap();
        std::fs::write(
            proj.join("short-1.jsonl"),
            "{}
",
        )
        .unwrap();
        let reg = tmp.path().join("sessions");
        std::fs::create_dir_all(&reg).unwrap();
        let pid = std::process::id();
        let write = |status: &str| {
            std::fs::write(
                reg.join(format!("{pid}.json")),
                format!(r#"{{"pid":{pid},"sessionId":"short-1","status":"{status}","entrypoint":"cli"}}"#),
            )
            .unwrap();
        };
        write("idle");
        let bus = EventBus::new(256);
        let mut rx = bus.subscribe();
        let hub = Arc::new(AgentHub::new(
            bus.clone(),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(tmp.path().to_path_buf(), None),
        ));
        let cancel = CancellationToken::new();
        hub.start_live_watcher(cancel.clone(), Arc::new(|| true));
        // Let the first full refresh announce the idle session, then run a 0.3 s turn that falls
        // between every polling tick (0 s / 2 s / 4 s): only the change notification can see it.
        tokio::time::sleep(Duration::from_millis(1000)).await;
        write("busy");
        tokio::time::sleep(Duration::from_millis(300)).await;
        write("idle");
        tokio::time::sleep(Duration::from_millis(500)).await;
        cancel.cancel();
        let mut statuses = Vec::new();
        while let Ok(f) = rx.try_recv() {
            if f.topic == "session.upserted" && f.data["id"] == "claude:short-1" {
                statuses.push(f.data["status"].as_str().unwrap_or("").to_string());
            }
        }
        assert_eq!(
            statuses,
            ["idle", "busy", "idle"],
            "each change is pushed as it happens"
        );
    }

    #[test]
    fn observed_dsh_status_follows_the_last_turn() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("session.v4.jsonl.zstd");
        let frame = |line: &str| {
            zstd::encode_all(
                format!(
                    "{line}
"
                )
                .as_bytes(),
                3,
            )
            .unwrap()
        };
        let append = |bytes: Vec<u8>| {
            use std::io::Write;
            std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(&path)
                .unwrap()
                .write_all(&bytes)
                .unwrap();
        };
        let hub = AgentHub::new(
            EventBus::new(16),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(PathBuf::from("/nonexistent"), None),
        );
        append(frame(r#"{"type":"session","id":"s1","cwd":"/p"}"#));
        append(frame(r#"{"type":"turn/start","seq":2,"time":1}"#));
        assert_eq!(hub.dsh_live_status(&path), Some("busy"));
        // Stopped by the user: idle at once, read incrementally from the last offset.
        append(frame(
            r#"{"type":"turn/end","seq":3,"time":2,"data":{"turn":1,"reason":{"kind":"aborted","reason":{"kind":"user"}}}}"#,
        ));
        assert_eq!(hub.dsh_live_status(&path), Some("idle"));
        append(frame(r#"{"type":"turn/start","seq":4,"time":3}"#));
        append(frame(
            r#"{"type":"turn/end","seq":5,"time":4,"data":{"turn":2,"reason":{"kind":"error","error":{"message":"x"}}}}"#,
        ));
        assert_eq!(hub.dsh_live_status(&path), Some("error"));

        let open = DshTurnState {
            open: true,
            last_end: None,
        };
        let done = DshTurnState {
            open: false,
            last_end: Some("completed".into()),
        };
        // A long tool call writes nothing for minutes but is still working.
        assert_eq!(
            dsh_observed_status(&open, Duration::from_secs(5 * 60)),
            Some("busy")
        );
        assert_eq!(
            dsh_observed_status(&open, Duration::from_secs(11 * 60)),
            None
        );
        assert_eq!(
            dsh_observed_status(&done, Duration::from_secs(30)),
            Some("idle")
        );
        assert_eq!(
            dsh_observed_status(&done, Duration::from_secs(3 * 60)),
            None
        );
    }

    /// A controllable session that records what the agent would receive.
    struct FakeAgent {
        images: bool,
        log: Arc<Mutex<SessionLog>>,
        sent: Mutex<Vec<(String, usize)>>,
    }

    #[async_trait::async_trait]
    impl ManagedSession for FakeAgent {
        fn agent(&self) -> &'static str {
            if self.images {
                "claude_code"
            } else {
                "dsh"
            }
        }
        fn log(&self) -> Arc<Mutex<SessionLog>> {
            self.log.clone()
        }
        fn cwd(&self) -> PathBuf {
            PathBuf::from("/p")
        }
        fn native_session_id(&self) -> Option<String> {
            None
        }
        fn status(&self) -> &'static str {
            "idle"
        }
        fn liveness(&self) -> Liveness {
            Liveness::Live
        }
        fn created_at_ms(&self) -> i64 {
            0
        }
        fn last_active_at_ms(&self) -> i64 {
            0
        }
        fn model(&self) -> Option<String> {
            None
        }
        fn title(&self) -> Option<String> {
            None
        }
        fn has_pending_requests(&self) -> bool {
            false
        }
        fn supports_images(&self) -> bool {
            self.images
        }
        async fn send_message(&self, text: &str) -> ApiResult<()> {
            self.send_message_with_images(text, &[]).await
        }
        async fn send_message_with_images(
            &self,
            text: &str,
            images: &[managed::ImageAttachment],
        ) -> ApiResult<()> {
            self.sent.lock().push((text.to_string(), images.len()));
            // The agent echoes the prompt into its transcript like Claude Code / dsh do.
            self.log.lock().upsert(
                EventDraft::new("echo", "user_message", "t", json!({ "text": text })),
                true,
            );
            Ok(())
        }
        async fn interrupt(&self) -> ApiResult<()> {
            Ok(())
        }
        async fn respond(
            &self,
            _: &str,
            _: &str,
            _: Option<String>,
            _: Option<Value>,
            _: Option<Value>,
            _: &str,
        ) -> ApiResult<Value> {
            Ok(Value::Null)
        }
        fn kill(&self) {}
        fn origin(&self) -> &'static str {
            "vscode"
        }
    }

    #[tokio::test]
    async fn attachments_reach_the_agent_as_paths_and_images_inline() {
        let tmp = tempfile::tempdir().unwrap();
        let hub = AgentHub::new(
            EventBus::new(64),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(PathBuf::from("/nonexistent"), None),
        );
        hub.set_attachment_root(tmp.path().join("attachments"));
        for (id, images) in [("cc", true), ("dsh", false)] {
            let fake = Arc::new(FakeAgent {
                images,
                log: Arc::new(Mutex::new(SessionLog::new(id, None))),
                sent: Mutex::new(Vec::new()),
            });
            hub.register_attached(id.into(), fake.clone());
            let store = hub.attachments().unwrap();
            let up = |name: &str, bytes: &[u8]| {
                let v = store
                    .begin("dev", id, name, bytes.len() as u64, None)
                    .unwrap();
                store.chunk("dev", id, &v.attachment_id, 0, bytes).unwrap();
                v.attachment_id
            };
            let ids = vec![up("photo.png", b"PNG fake"), up("build.log", b"error: x")];
            // Another device cannot use these uploads.
            assert!(hub
                .send_message_full(id, "看看", &[], &ids, "other", None)
                .await
                .is_err());
            hub.send_message_full(id, "看看", &[], &ids, "dev", Some("c1"))
                .await
                .unwrap();
            let (text, inline) = fake.sent.lock()[0].clone();
            assert!(
                text.starts_with(
                    "看看

[SwitchTether attachments:"
                ),
                "{text}"
            );
            let root = tmp.path().join("attachments");
            for name in ["photo.png", "build.log"] {
                let line = text.lines().find(|l| l.ends_with(name)).expect(name);
                let path = Path::new(line.trim_start_matches("- "));
                assert!(path.is_absolute() && path.starts_with(&root) && path.is_file());
            }
            assert_eq!(
                inline,
                if images { 1 } else { 0 },
                "only image-capable agents get the image inline"
            );
            // The timeline shows the user's text and the files, matched to the phone's bubble.
            let log = fake.log.clone();
            let ev = log.lock().get("echo").cloned().unwrap();
            assert_eq!(ev.data["text"], "看看");
            assert_eq!(ev.data["attachments"].as_array().unwrap().len(), 2);
            assert_eq!(ev.data["attachments"][1]["name"], "build.log");
            assert_eq!(ev.data["clientId"], "c1");
        }
    }

    #[test]
    fn processes_owned_by_switchtether_are_not_observed_twice() {
        let entry = |sid: &str, pid: Option<u32>| claude::live::LiveClaudeSession {
            pid,
            session_id: sid.into(),
            cwd: None,
            started_at_ms: None,
            kind: None,
            entrypoint: None,
            status: None,
            waiting_for: None,
            name: None,
        };
        let owned = OwnedClaude {
            native: vec!["continued".into()],
            pids: vec![42],
        };
        assert!(owned.covers(&entry("continued", Some(7))));
        // A fork has a new id that is only known after its first turn; the pid matches at once.
        assert!(owned.covers(&entry("fresh-fork", Some(42))));
        assert!(!owned.covers(&entry("users-own", Some(7))));
        assert!(!owned.covers(&entry("users-own", None)));
    }

    #[tokio::test]
    async fn continuing_a_busy_external_session_is_refused() {
        let tmp = tempfile::tempdir().unwrap();
        let sessions = tmp.path().join("sessions");
        std::fs::create_dir_all(&sessions).unwrap();
        let pid = std::process::id();
        std::fs::write(
            sessions.join(format!("{pid}.json")),
            format!(r#"{{"pid":{pid},"sessionId":"busy-1","cwd":"{}","status":"busy","entrypoint":"cli"}}"#,
                tmp.path().to_string_lossy().replace('\\', "/")),
        )
        .unwrap();
        let hub = AgentHub::new(
            EventBus::new(16),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(tmp.path().to_path_buf(), None),
        );
        let s = hub.summary("claude:busy-1").await.unwrap();
        assert_eq!(s.liveness, Liveness::Live);
        assert_eq!(
            hub.capabilities_for(&s).continue_session,
            Some(CapStatus::Partial)
        );
        let e = hub
            .create_managed(CreateSession {
                agent: "claude_code".into(),
                cwd: String::new(),
                prompt: None,
                model: None,
                permission_mode: None,
                resume_from: Some("claude:busy-1".into()),
                mode: None,
                effort: None,
            })
            .await
            .unwrap_err();
        assert_eq!(e.code, crate::error::ErrorCode::Conflict);
        // wrong agent is a validation error, not a takeover
        let e = hub
            .create_managed(CreateSession {
                agent: "codex".into(),
                cwd: String::new(),
                prompt: None,
                model: None,
                permission_mode: None,
                resume_from: Some("claude:busy-1".into()),
                mode: None,
                effort: None,
            })
            .await
            .unwrap_err();
        assert_eq!(e.code, crate::error::ErrorCode::ValidationFailed);
    }

    #[tokio::test]
    async fn external_sessions_are_blocked_for_control() {
        let hub = AgentHub::new(
            EventBus::new(16),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(PathBuf::from("/nonexistent"), None),
        );
        let e = hub
            .send_message("claude:abc", "hi", None)
            .await
            .unwrap_err();
        assert_eq!(e.code, crate::error::ErrorCode::CapabilityUnavailable);
        assert_eq!(e.details.unwrap()["status"], "BLOCKED");
        assert!(hub.interrupt("codex:x").await.is_err());
    }

    /// A conversation that is not running (history, or a run that ended with a desktop restart)
    /// takes messages: sending resumes it the official way under the same id. Here the agent binary
    /// does not exist, so the resume itself fails, which proves the send went that way instead of
    /// being refused as "started outside SwitchTether".
    #[tokio::test]
    async fn history_is_resumed_on_send_under_the_same_id() {
        let tmp = tempfile::tempdir().unwrap();
        let cwd = tmp.path().join("work");
        std::fs::create_dir_all(&cwd).unwrap();
        let proj = tmp.path().join("projects").join("proj");
        std::fs::create_dir_all(&proj).unwrap();
        let cwd_s = cwd.to_string_lossy().replace('\\', "/");
        std::fs::write(
            proj.join("hist-1.jsonl"),
            format!(
                r#"{{"type":"user","uuid":"u1","sessionId":"hist-1","cwd":"{cwd_s}","timestamp":"2026-11-20T10:00:00Z","message":{{"role":"user","content":"hello"}}}}"#
            ) + "\n",
        )
        .unwrap();
        let hub = AgentHub::new(
            EventBus::new(16),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(
                tmp.path().to_path_buf(),
                Some(tmp.path().join("no-such-claude")),
            ),
        );
        let s = hub.summary("claude:hist-1").await.unwrap();
        assert_eq!(s.liveness, Liveness::Ended);
        let caps = hub.capabilities_for(&s);
        assert_eq!(caps.send_message, Some(CapStatus::Partial));
        assert_eq!(caps.approve, Some(CapStatus::Blocked));
        assert!(hub.ensure_can_send("claude:hist-1").await.is_ok());
        let e = hub
            .send_message("claude:hist-1", "hi again", None)
            .await
            .unwrap_err();
        assert_eq!(e.code, crate::error::ErrorCode::Internal, "{e:?}");
        // Without the agent installed the history stays read-only.
        let hub = AgentHub::new(
            EventBus::new(16),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(tmp.path().to_path_buf(), None),
        );
        let s = hub.summary("claude:hist-1").await.unwrap();
        assert_eq!(
            hub.capabilities_for(&s).send_message,
            Some(CapStatus::Blocked)
        );
    }

    #[tokio::test]
    async fn invalid_model_and_effort_are_refused_before_starting() {
        let hub = AgentHub::new(
            EventBus::new(16),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(PathBuf::from("/nonexistent"), None),
        );
        let req = |model: Option<&str>, effort: Option<&str>| CreateSession {
            agent: "claude_code".into(),
            cwd: std::env::temp_dir().to_string_lossy().into_owned(),
            prompt: None,
            model: model.map(String::from),
            permission_mode: None,
            resume_from: None,
            mode: None,
            effort: effort.map(String::from),
        };
        let e = hub
            .create_managed(req(Some("opus --dangerously"), None))
            .await
            .unwrap_err();
        assert_eq!(e.code, crate::error::ErrorCode::ValidationFailed);
        let e = hub
            .create_managed(req(None, Some("minimal")))
            .await
            .unwrap_err();
        assert_eq!(e.code, crate::error::ErrorCode::ValidationFailed);
        // Not a known conversation at all (a resumable one keeps the choice for its resume).
        let e = hub
            .set_options("claude:nothing-running", Some("opus".into()), None)
            .await
            .unwrap_err();
        assert_eq!(e.code, crate::error::ErrorCode::NotFound);
    }

    /// A conversation that is not running keeps model, effort and permission mode for the resume
    /// that the next message starts; the detail shows them.
    #[tokio::test]
    async fn options_of_a_stopped_conversation_wait_for_its_resume() {
        let tmp = tempfile::tempdir().unwrap();
        let proj = tmp.path().join("projects").join("p");
        std::fs::create_dir_all(&proj).unwrap();
        std::fs::write(
            proj.join("old-2.jsonl"),
            "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"content\":\"hi\"}}\n",
        )
        .unwrap();
        let hub = AgentHub::new(
            EventBus::new(8),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(tmp.path().to_path_buf(), Some(PathBuf::from("claude"))),
        );
        let s = hub
            .set_options("claude:old-2", Some("fable".into()), Some("high".into()))
            .await
            .unwrap();
        assert_eq!(s.model.as_deref(), Some("fable"));
        assert_eq!(s.effort.as_deref(), Some("high"));
        let r = hub
            .claude_tool(
                "claude:old-2",
                "permission_mode",
                &json!({ "mode": "plan" }),
            )
            .await
            .unwrap();
        assert_eq!(r["pending"], true);
        let d = hub.detail("claude:old-2").await.unwrap();
        assert_eq!(d["resumable"], true);
        assert_eq!(d["nextStart"]["model"], "fable");
        assert_eq!(d["nextStart"]["permissionMode"], "plan");
        // Effort back to the default; the model stays.
        hub.set_options("claude:old-2", None, Some("default".into()))
            .await
            .unwrap();
        let d = hub.detail("claude:old-2").await.unwrap();
        assert_eq!(d["nextStart"]["effort"], Value::Null);
        assert_eq!(d["nextStart"]["model"], "fable");
    }

    /// The `commands` session tool answers what the process reported, for a session the phone
    /// controls; no list is `source: "none"` (HTTP 200), never an error.
    #[tokio::test]
    async fn commands_tool_lists_what_the_agent_reported() {
        let hub = AgentHub::new(
            EventBus::new(64),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(PathBuf::from("/nonexistent"), None),
        );
        let (tx, _rx) = tokio::sync::mpsc::unbounded_channel();
        let m = claude::managed::ManagedClaude::attached(
            "cc".into(),
            PathBuf::from("."),
            Arc::new(Mutex::new(SessionLog::new("cc", None))),
            tx,
            "vscode",
        );
        hub.register_attached("cc".into(), m.clone());
        // Nothing reported yet.
        let r = hub.claude_tool("cc", "commands", &json!({})).await.unwrap();
        assert_eq!(r, json!({ "commands": [], "source": "none" }));
        // The initialize response as it passes on the process's stdout.
        let line = json!({"type": "control_response", "response": {
        "subtype": "success", "request_id": "ide_1", "response": {
            "commands": [
                {"name": "compact", "description": "Clear conversation history but keep a summary in context",
                 "argumentHint": "<optional custom summarization instructions>"},
                {"name": "/review", "description": "Review a pull request", "argumentHint": "",
                 "aliases": ["rv"], "builtin": false, "plugin": "team-tools"},
                {"name": "compact", "description": "duplicate"},
                "my-plugin:deploy"
            ],
            "models": [],
            "account": {"email": "someone@example.com", "apiKeySource": "user"}
        }}});
        m.on_remote_frame(json!({ "t": "out", "line": line.to_string() }))
            .await;
        let r = hub.claude_tool("cc", "commands", &json!({})).await.unwrap();
        let fixture: Value = serde_json::from_str(
            &std::fs::read_to_string(
                std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                    .join("../../../protocol/fixtures/claude_commands.json"),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(r, fixture);
        // Only name, description, argumentHint and aliases leave the desktop.
        assert!(!r.to_string().contains("example.com"));
        assert!(!r.to_string().contains("team-tools"));
        // After the process exited there is no list any more.
        m.on_remote_frame(json!({ "t": "exit" })).await;
        let r = hub.claude_tool("cc", "commands", &json!({})).await.unwrap();
        assert_eq!(r["source"], "none");
    }

    /// A conversation that is not running but would be resumed by a message has no list yet.
    #[tokio::test]
    async fn commands_tool_answers_none_for_a_resumable_conversation() {
        let tmp = tempfile::tempdir().unwrap();
        let proj = tmp.path().join("projects").join("p");
        std::fs::create_dir_all(&proj).unwrap();
        std::fs::write(
            proj.join("old-3.jsonl"),
            "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"content\":\"hi\"}}\n",
        )
        .unwrap();
        let hub = AgentHub::new(
            EventBus::new(8),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(tmp.path().to_path_buf(), Some(PathBuf::from("claude"))),
        );
        let r = hub
            .claude_tool("claude:old-3", "commands", &json!({}))
            .await
            .unwrap();
        assert_eq!(r, json!({ "commands": [], "source": "none" }));
    }

    #[test]
    fn model_catalog_merges_agent_lists_with_aliases() {
        let c = model_catalog("claude_code", &Value::Null);
        assert_eq!(c["source"], "builtin");
        assert!(c["models"]
            .as_array()
            .unwrap()
            .iter()
            .any(|m| m["id"] == "opus"));
        assert_eq!(c["efforts"].as_array().unwrap().len(), 5);
        // Claude Code initialize response shape.
        let reported = json!([
            {"value": "default", "displayName": "Default (recommended)", "description": "Opus"},
            {"value": "claude-sonnet-5-5", "displayName": "Sonnet 5.5", "supportedEffortLevels": ["low", "high"]}
        ]);
        let c = model_catalog("claude_code", &reported);
        let models = c["models"].as_array().unwrap();
        assert_eq!(models[0]["label"], "Default (recommended)");
        assert_eq!(models[1]["efforts"], json!(["low", "high"]));
        assert_eq!(models.iter().filter(|m| m["id"] == "default").count(), 1);
        // Codex `model/list` shape.
        let reported = json!({"data": [
            {"id": "gpt-5", "model": "gpt-5", "displayName": "GPT-5",
             "supportedReasoningEfforts": [{"reasoningEffort": "low"}, {"reasoningEffort": "high"}]}
        ]});
        let c = model_catalog("codex", &reported);
        assert_eq!(c["models"][0]["id"], "gpt-5");
        assert_eq!(c["models"][0]["efforts"], json!(["low", "high"]));
    }

    /// Manual check against the real Codex history of this machine (read-only):
    /// `cargo test -p switchtether-core --lib real_codex_history -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn real_codex_history_reads() {
        let home = codex::codex_home();
        let mut files = Vec::new();
        let mut dirs = vec![home.join("sessions"), home.join("archived_sessions")];
        while let Some(d) = dirs.pop() {
            for e in std::fs::read_dir(&d).into_iter().flatten().flatten() {
                let p = e.path();
                if p.is_dir() {
                    dirs.push(p);
                } else if is_rollout_file(&p) {
                    files.push(p);
                }
            }
        }
        let (mut ok, mut empty, mut failed, mut zst) = (0, 0, 0, 0);
        for f in &files {
            zst += usize::from(is_zstd_file(f));
            let log = Arc::new(Mutex::new(SessionLog::new("codex:x", None)));
            match load_file(f, Box::new(CodexParser::new()), &log) {
                Ok(_) if log.lock().iter().any(|e| e.kind == "user_message") => ok += 1,
                Ok(_) => empty += 1,
                Err(e) => {
                    failed += 1;
                    println!("FAILED {}: {e}", f.display());
                }
            }
        }
        println!("rollouts={} (zst={zst}) with messages={ok} without user messages={empty} failed={failed}", files.len());
        assert_eq!(failed, 0);
    }

    /// Codex compresses older rollouts (`.jsonl.zst`): they are found by thread id and read like
    /// plain ones, so old Codex Desktop history opens on the phone too.
    #[test]
    fn compressed_codex_rollouts_are_found_and_read() {
        let dir = tempfile::tempdir().unwrap();
        let day = dir.path().join("2026").join("05").join("07");
        std::fs::create_dir_all(&day).unwrap();
        let id = "019e019e-e16e-7f00-9e83-0cc33caa5dfe";
        let text = concat!(
            r#"{"timestamp":"2026-05-07T08:47:30Z","type":"session_meta","payload":{"id":"019e019e-e16e-7f00-9e83-0cc33caa5dfe","cwd":"D:/w"}}"#,
            "
",
            r#"{"timestamp":"2026-05-07T08:47:31Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"old question"}]}}"#,
            "
",
            r#"{"timestamp":"2026-05-07T08:47:35Z","type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"old answer"}]}}"#,
            "
",
        );
        let path = day.join(format!("rollout-2026-05-07T16-47-30-{id}.jsonl.zst"));
        std::fs::write(&path, zstd::stream::encode_all(text.as_bytes(), 3).unwrap()).unwrap();
        assert!(is_rollout_file(&path) && is_zstd_file(&path));
        assert_eq!(find_rollout_in(dir.path(), id), Some(path.clone()));
        let log = Arc::new(Mutex::new(SessionLog::new("codex:x", None)));
        load_file(&path, Box::new(CodexParser::new()), &log).unwrap();
        let texts: Vec<String> = log
            .lock()
            .iter()
            .filter(|e| e.kind == "user_message" || e.kind == "assistant_message")
            .map(|e| e.data["text"].as_str().unwrap_or("").to_string())
            .collect();
        assert_eq!(texts, ["old question", "old answer"]);
    }

    #[test]
    fn rekeyed_logs_relabel_their_events() {
        let mut lg = SessionLog::new("starting:1", None);
        lg.upsert(
            EventDraft::new("a", "system", "t".to_string(), json!({})),
            false,
        );
        lg.rekey("codex:th-1");
        assert!(lg.iter().all(|e| e.session_id == "codex:th-1"));
        assert_eq!(public_id("claude_code", "x"), "claude:x");
        assert_eq!(public_id("codex", "th"), "codex:th");
    }

    /// Antigravity sessions only exist in CC Switch's index; the backend reads them.
    struct AgyBackend;

    type R = crate::backend::BackendResult<Value>;
    type Q = crate::backend::UsageQuery;

    #[async_trait::async_trait]
    impl CcSwitchBackend for AgyBackend {
        async fn app_types(&self) -> R {
            Ok(json!(["agy"]))
        }
        async fn providers(&self, a: &str) -> R {
            UnavailableBackend.providers(a).await
        }
        async fn switch_provider(&self, a: &str, i: &str) -> R {
            UnavailableBackend.switch_provider(a, i).await
        }
        async fn test_provider(&self, a: &str, i: &str) -> R {
            UnavailableBackend.test_provider(a, i).await
        }
        async fn set_provider_credential(&self, a: &str, i: &str, f: &str, v: &str) -> R {
            UnavailableBackend.set_provider_credential(a, i, f, v).await
        }
        async fn usage_summary(&self, q: Q) -> R {
            UnavailableBackend.usage_summary(q).await
        }
        async fn usage_trends(&self, q: Q) -> R {
            UnavailableBackend.usage_trends(q).await
        }
        async fn usage_models(&self, q: Q) -> R {
            UnavailableBackend.usage_models(q).await
        }
        async fn usage_providers(&self, q: Q) -> R {
            UnavailableBackend.usage_providers(q).await
        }
        async fn usage_log(&self, id: &str) -> R {
            UnavailableBackend.usage_log(id).await
        }
        async fn usage_first_date(&self, q: Q) -> R {
            UnavailableBackend.usage_first_date(q).await
        }
        async fn usage_last_sync(&self) -> R {
            UnavailableBackend.usage_last_sync().await
        }
        async fn usage_logs(&self, q: Q, s: Option<u16>, p: u32, ps: u32) -> R {
            UnavailableBackend.usage_logs(q, s, p, ps).await
        }
        async fn quota(&self, t: &str) -> R {
            UnavailableBackend.quota(t).await
        }
        async fn mcp_servers(&self) -> R {
            UnavailableBackend.mcp_servers().await
        }
        async fn mcp_toggle(&self, i: &str, a: &str, e: bool) -> R {
            UnavailableBackend.mcp_toggle(i, a, e).await
        }
        async fn skills(&self) -> R {
            UnavailableBackend.skills().await
        }
        async fn skill_toggle(&self, i: &str, a: &str, e: bool) -> R {
            UnavailableBackend.skill_toggle(i, a, e).await
        }
        async fn prompts(&self, a: &str) -> R {
            UnavailableBackend.prompts(a).await
        }
        async fn prompt_enable(&self, a: &str, i: &str) -> R {
            UnavailableBackend.prompt_enable(a, i).await
        }
        async fn settings(&self) -> R {
            UnavailableBackend.settings().await
        }
        async fn proxy_status(&self) -> R {
            UnavailableBackend.proxy_status().await
        }
        async fn health(&self) -> R {
            UnavailableBackend.health().await
        }
        async fn session_index(&self) -> crate::backend::BackendResult<Vec<IndexedSession>> {
            let s = |id: &str, at: i64| IndexedSession {
                provider_id: "agy".into(),
                session_id: id.into(),
                title: Some(format!("Conversation {id}")),
                summary: None,
                project_dir: Some("/w".into()),
                created_at_ms: Some(at),
                last_active_at_ms: Some(at),
                source_path: Some(format!("/agy/{id}.jsonl")),
                resume_command: None,
            };
            Ok(vec![s("a1", 1_000), s("a2", 2_000)])
        }
        async fn session_messages(&self, p: &str, src: &str) -> R {
            assert_eq!(p, "agy");
            Ok(match src {
                "/agy/a1.jsonl" => json!([
                    {"role": "user", "content": "Plan the Orbit release"},
                    {"role": "tool", "content": "orbit in tool output only"},
                    {"role": "assistant", "content": "Orbit is ready"},
                ]),
                _ => json!([{"role": "user", "content": "something else"}]),
            })
        }
        async fn agent_models(&self, agent: &str) -> R {
            Ok(match agent {
                "agy" => json!([{"id": "Gemini 3.5 Pro", "displayName": "Gemini 3.5 Pro"}]),
                _ => Value::Null,
            })
        }
    }

    #[tokio::test]
    async fn agy_history_is_searchable_and_honestly_read_only() {
        let tmp = tempfile::tempdir().unwrap();
        let hub = AgentHub::new(
            EventBus::new(64),
            Arc::new(AgyBackend),
            ClaudeObserver::new(tmp.path().to_path_buf(), None),
        );
        let (hits, partial) = hub
            .search("orbit", Some("agy"), 20, Duration::from_secs(3))
            .await;
        assert!(!partial);
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].session_id, "agy:a1");
        assert_eq!(hits[0].agent, "agy");
        // Tool output is not searched: one user and one assistant match.
        assert_eq!(hits[0].match_count, 2);
        assert!(hits[0].snippet.contains("Orbit is ready"));

        // History of a window on the computer: rich JSONL transcripts (with CC Switch fallback for
        // legacy trajectory DBs), no control in place; it can be continued in SwitchTether's own
        // `agy acp` process (resumable once agy is installed).
        let s = hub.summary("agy:a1").await.unwrap();
        let caps = hub.capabilities_for(&s);
        assert_eq!(caps.read_history, Some(CapStatus::Verified));
        assert_eq!(caps.live_events, None);
        assert_eq!(caps.send_message, Some(CapStatus::Blocked));
        assert_eq!(caps.continue_session, Some(CapStatus::Partial));
        let matrix = hub.capability_matrix();
        let agy = matrix
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["agent"] == "agy" && e.get("takeover").is_none() && e["origin"] == "cli")
            .unwrap();
        assert_eq!(agy["capabilities"]["readHistory"], "VERIFIED");
        assert_eq!(agy["capabilities"]["liveEvents"], "PARTIAL");

        assert!(matrix
            .as_array()
            .unwrap()
            .iter()
            .any(|e| e["agent"] == "agy" && e["takeover"] == "st-agy"));
        assert!(matrix
            .as_array()
            .unwrap()
            .iter()
            .any(|e| e["agent"] == "agy" && e["origin"] == "managed"));

        // Without agy on this computer: the desktop's list; agy's thinking levels and modes.
        let m = hub.model_options("agy", None).await;
        assert_eq!(m["source"], "agent");
        assert_eq!(m["models"][0]["id"], "Gemini 3.5 Pro");
        assert_eq!(m["efforts"], json!(["low", "medium", "high", "xhigh"]));
        let modes: Vec<&str> = m["permissionModes"]
            .as_array()
            .unwrap()
            .iter()
            .map(|x| x["id"].as_str().unwrap())
            .collect();
        assert_eq!(
            modes,
            [
                "default",
                "acceptEdits",
                "auto",
                "plan",
                "bypassPermissions"
            ]
        );
    }

    #[tokio::test]
    async fn agy_live_sessions_tail_transcripts_subagents_and_read_tools() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("antigravity-cli");
        let parent_logs = root
            .join("brain")
            .join("parent-1")
            .join(".system_generated")
            .join("logs");
        let child_logs = root
            .join("brain")
            .join("child-1")
            .join(".system_generated")
            .join("logs");
        std::fs::create_dir_all(&parent_logs).unwrap();
        std::fs::create_dir_all(&child_logs).unwrap();

        let db = root.join("conversation_summaries.db");
        let conn = rusqlite::Connection::open(&db).unwrap();
        conn.execute_batch(
            "CREATE TABLE conversation_summaries (
                conversation_id TEXT PRIMARY KEY,
                title TEXT,
                preview TEXT,
                workspace_uris TEXT,
                status TEXT,
                not_fully_idle INTEGER,
                killed INTEGER,
                parent_conversation_id TEXT,
                nesting_depth INTEGER,
                agent_name TEXT,
                last_modified_time TEXT,
                last_user_input_time TEXT
            );",
        )
        .unwrap();
        let now = chrono::Utc::now().to_rfc3339();
        conn.execute(
            "INSERT INTO conversation_summaries VALUES ('parent-1', 'Live Agy Task', '', '[\"file:///d:/proj\"]', 'CASCADE_RUN_STATUS_RUNNING', 1, 0, '', 0, '', ?1, ?1)",
            [&now],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO conversation_summaries VALUES ('child-1', '', '', '[]', 'CASCADE_RUN_STATUS_IDLE', 0, 0, 'parent-1', 1, 'research', ?1, '')",
            [&now],
        )
        .unwrap();
        drop(conn);

        std::fs::write(
            parent_logs.join("transcript_full.jsonl"),
            format!(
                "{}\n{}\n",
                json!({ "step_index": 0, "type": "USER_INPUT", "status": "DONE", "created_at": &now, "content": "<USER_REQUEST>\nAudit auth\n</USER_REQUEST>" }),
                json!({
                    "step_index": 1,
                    "type": "PLANNER_RESPONSE",
                    "status": "DONE",
                    "created_at": &now,
                    "model": "gemini-4-carbon-high",
                    "content": "Spawning subagent.",
                    "tool_calls": [
                        { "name": "update_steps", "args": { "steps": [{ "title": "Audit tokens", "status": "in_progress" }] } },
                        { "name": "invoke_subagent", "args": { "TypeName": "research", "Role": "Token Auditor", "Prompt": "Check JWT" } }
                    ]
                })
            ),
        )
        .unwrap();
        std::fs::write(
            child_logs.join("transcript_full.jsonl"),
            format!(
                "{}\n{}\n",
                json!({ "step_index": 0, "type": "USER_INPUT", "status": "DONE", "created_at": &now, "content": "Check JWT" }),
                json!({ "step_index": 1, "type": "PLANNER_RESPONSE", "status": "DONE", "created_at": &now, "content": "JWT uses EdDSA." })
            ),
        )
        .unwrap();

        let bus = EventBus::new(64);
        let hub = Arc::new(AgentHub {
            bus: bus.clone(),
            backend: Arc::new(UnavailableBackend),
            claude: ClaudeObserver::new(tmp.path().join("claude"), None),
            agy: agy::live::AgyObserver::new(vec![root]),
            managed: RwLock::new(HashMap::new()),
            codex_bin: None,
            dsh_bin: None,
            agy_bin: None,
            loaded: Mutex::new(HashMap::new()),
            dsh_children: Mutex::new(HashMap::new()),
            dsh_turns: Mutex::new(HashMap::new()),
            probe_log: Arc::new(Mutex::new(SessionLog::new("probe", None))),
            subagent_logs: Mutex::new(HashMap::new()),
            next_start: Mutex::new(HashMap::new()),
            remembered: Mutex::new(HashMap::new()),
            remembered_path: RwLock::new(None),
            model_lists: Mutex::new(HashMap::new()),
            index_cache: Mutex::new(None),
            title_cache: Mutex::new(HashMap::new()),
            ttys: RwLock::new(HashMap::new()),
            attachments: RwLock::new(None),
        });

        let (items, _) = hub
            .list(&SessionQuery {
                agent: Some("agy".into()),
                ..Default::default()
            })
            .await;
        assert_eq!(
            items.len(),
            1,
            "child subagent is excluded from top-level list"
        );
        assert_eq!(items[0].id, "agy:parent-1");
        assert_eq!(items[0].liveness, Liveness::Live);
        assert_eq!(items[0].status, "busy");
        assert_eq!(items[0].control, Control::Observe);

        // Continuing a busy external Antigravity session is refused.
        let busy_err = hub
            .create_managed(CreateSession {
                agent: "agy".into(),
                cwd: String::new(),
                prompt: None,
                model: None,
                permission_mode: None,
                resume_from: Some("agy:parent-1".into()),
                mode: Some("continue".into()),
                effort: None,
            })
            .await
            .unwrap_err();
        assert_eq!(busy_err.code, crate::error::ErrorCode::Conflict);

        // Read-only session tools work on observed Antigravity sessions.
        let plan = hub
            .claude_tool("agy:parent-1", "plan", &json!({}))
            .await
            .unwrap();
        assert_eq!(plan["exists"], true);
        assert!(plan["content"].as_str().unwrap().contains("Audit tokens"));

        let exported = hub
            .claude_tool("agy:parent-1", "export", &json!({}))
            .await
            .unwrap();
        assert!(exported["text"].as_str().unwrap().contains("Audit auth"));

        // Subagent tree & child events endpoint.
        let tree = hub.subagent_tree("agy:parent-1").await.unwrap();
        assert_eq!(tree["agents"][0]["agentId"], "child-1");
        assert_eq!(tree["agents"][0]["agentType"], "research");

        let sub_ev = hub
            .subagent_events("agy:parent-1", "child-1", None, None, 50)
            .await
            .unwrap();
        assert!(sub_ev["events"]
            .as_array()
            .unwrap()
            .iter()
            .any(|e| e["kind"] == "assistant_message" && e["data"]["text"] == "JWT uses EdDSA."));
    }
}
