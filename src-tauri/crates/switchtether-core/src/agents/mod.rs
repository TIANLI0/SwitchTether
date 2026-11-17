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

pub mod attached_tty;
pub mod claude;
pub mod codex;
pub mod managed;
pub mod model;
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
use managed::ManagedSession;
use model::{Capabilities, Control, EventDraft, Liveness, Origin, SessionSummary};
use store::SessionLog;

/// (mtime, length, title) of a transcript when its title was last read.
type TitleCacheEntry = (Option<std::time::SystemTime>, u64, Option<String>);

pub const MAX_LOADED_SESSIONS: usize = 16;
pub const MAX_TRANSCRIPT_BYTES: u64 = 96 * 1024 * 1024;
const TAIL_INTERVAL: Duration = Duration::from_millis(800);

trait LineParser: Send {
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

struct Tail {
    path: PathBuf,
    offset: u64,
    partial: Vec<u8>,
    parser: Box<dyn LineParser>,
}

struct Loaded {
    log: Arc<Mutex<SessionLog>>,
    tail: Option<Tail>,
    last_access: Instant,
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
}

pub struct AgentHub {
    bus: Arc<EventBus>,
    backend: Arc<dyn CcSwitchBackend>,
    pub claude: ClaudeObserver,
    managed: RwLock<HashMap<String, Arc<dyn ManagedSession>>>,
    codex_bin: Option<PathBuf>,
    loaded: Mutex<HashMap<String, Loaded>>,
    index_cache: Mutex<Option<(Instant, Arc<Vec<IndexedSession>>)>>,
    /// Claude transcript path -> (mtime, len, title); avoids re-reading unchanged transcripts.
    title_cache: Mutex<HashMap<PathBuf, TitleCacheEntry>>,
    ttys: RwLock<HashMap<String, Arc<attached_tty::AttachedTty>>>,
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
        Arc::new(Self {
            bus,
            backend,
            claude,
            managed: RwLock::new(HashMap::new()),
            codex_bin: codex::managed::find_codex_bin(),
            loaded: Mutex::new(HashMap::new()),
            index_cache: Mutex::new(None),
            title_cache: Mutex::new(HashMap::new()),
            ttys: RwLock::new(HashMap::new()),
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
                    .filter(|p| p.extension().is_some_and(|e| e == "jsonl"))
            }
            _ => None,
        }?;
        let parser: Box<dyn LineParser> = if agent == "codex" {
            Box::new(CodexParser::new())
        } else {
            Box::new(ClaudeParser::new())
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
            _ => return None,
        };
        m.iter()
            .filter(|(_, s)| s.origin() != "managed" && s.agent() == agent)
            .filter(|(_, s)| s.liveness() == Liveness::Live)
            .find(|(_, s)| s.native_session_id().as_deref() == Some(native))
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
                git_branch: None,
                pid: None,
                managed: false,
                terminal_id: terminal.then(String::new),
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
        ])
    }

    pub async fn health(&self) -> Value {
        json!({
            "claudeBin": self.claude.claude_bin.as_ref().map(|p| p.to_string_lossy().into_owned()),
            "claudeDir": self.claude.claude_dir.exists(),
            "codexHome": codex::codex_home().exists(),
            "managedSessions": self.managed.read().len(),
            "codexBin": self.codex_bin.as_ref().map(|p| p.to_string_lossy().into_owned()),
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
            git_branch: None,
            pid: None,
            managed: m.origin() == "managed",
            terminal_id: m.terminal_id(),
            source_path: None,
        }
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
        for l in &live {
            if managed_native.contains(&l.session_id) {
                continue;
            }
            live_ids.insert(l.session_id.clone());
            let cwd = l.cwd.clone();
            // The registry `name` is an auto-generated project slug ("myapp-3f"), not the task
            // title; the real title (`/rename` or Claude's generated one) lives in the transcript.
            let transcript = self.claude.transcript_path(cwd.as_deref(), &l.session_id);
            // An idle session without a transcript is a process that has not been used yet
            // (see the live observer): nothing to list.
            if transcript.is_none()
                && !matches!(l.status.as_deref(), Some("busy" | "waiting"))
                && l.waiting_for.is_none()
            {
                continue;
            }
            let title = transcript.and_then(|p| self.claude_title(&p));
            out.push(SessionSummary {
                id: format!("claude:{}", l.session_id),
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
                last_active_at_ms: Some(crate::util::now_ms()),
                model: None,
                git_branch: None,
                pid: l.pid,
                managed: false,
                terminal_id: None,
                source_path: None,
            });
        }
        for s in indexed.iter() {
            let agent = agent_of_provider(&s.provider_id);
            if (agent == "claude_code" && live_ids.contains(&s.session_id))
                || managed_native.contains(&s.session_id)
            {
                continue;
            }
            let prefix = if agent == "claude_code" {
                "claude"
            } else {
                s.provider_id.as_str()
            };
            let title = if agent == "claude_code" {
                s.source_path
                    .as_deref()
                    .and_then(|p| self.claude_title(Path::new(p)))
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
                liveness: Liveness::Ended,
                status: "ended".into(),
                waiting_for: None,
                control: Control::History,
                created_at_ms: s.created_at_ms,
                last_active_at_ms: s.last_active_at_ms,
                model: None,
                git_branch: None,
                pid: None,
                managed: false,
                terminal_id: None,
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
            git_branch: parser.git_branch.clone(),
            pid: None,
            managed: false,
            terminal_id: None,
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
                None => return self.claude_summary_from_transcript(id),
            }
        }
    }

    fn capabilities_for(&self, s: &SessionSummary) -> Capabilities {
        if !s.managed && s.control == Control::Full {
            return attached_caps(s);
        }
        if s.managed {
            let mut c = managed_caps();
            if s.liveness != Liveness::Live {
                c.send_message = Some(CapStatus::Blocked);
                c.approve = Some(CapStatus::Blocked);
                c.interrupt = Some(CapStatus::Blocked);
                c.answer_question = Some(CapStatus::Blocked);
                c.reason =
                    Some("The managed process has exited; start a new session to continue.".into());
            }
            return c;
        }
        let mut c = observed_caps(&s.agent);
        if s.liveness != Liveness::Live {
            c.live_events = None;
        }
        c
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
                    Some(p) => Some(load_file(&p, Box::new(ClaudeParser::new()), &log)?),
                    None => None,
                }
            }
            "codex" => match s.source_path.clone().map(PathBuf::from) {
                Some(p) if p.extension().is_some_and(|e| e == "jsonl") => {
                    Some(load_file(&p, Box::new(CodexParser::new()), &log)?)
                }
                _ => None,
            },
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
                    let mut lg = log.lock();
                    for (i, m) in v.as_array().cloned().unwrap_or_default().iter().enumerate() {
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
            }
        }
        let keep_tail = s.liveness == Liveness::Live;
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
                tail: if keep_tail { tail } else { None },
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
        // Continuing a session started elsewhere ("接续会话"): never in place —
        // a new SwitchTether-owned process continues the same conversation.
        let mut continue_from: Option<(SessionSummary, bool)> = None;
        if let Some(target) = req.resume_from.clone() {
            let s = self
                .summary(&target)
                .await
                .ok_or_else(|| ApiError::not_found("unknown session to continue"))?;
            if s.managed || s.control == Control::Full {
                return Err(ApiError::validation(
                    "this session is already controlled by SwitchTether",
                ));
            }
            if s.agent != req.agent {
                return Err(ApiError::validation("agent does not match the session"));
            }
            let fork = match req.mode.as_deref() {
                None | Some("continue") => false,
                Some("fork") => true,
                Some(_) => return Err(ApiError::validation("mode must be continue or fork")),
            };
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
        let id = format!("managed:{}", crate::util::new_id());
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
                    .filter(|p| p.extension().is_some_and(|e| e == "jsonl"))
                    .map(|p| (p, Box::new(CodexParser::new()) as Box<dyn LineParser>)),
                _ => None,
            };
            if let Some((path, parser)) = history {
                if let Err(e) = load_file(&path, parser, &log) {
                    log::warn!("[agents] could not preload history: {e}");
                }
            }
            log.lock().upsert(
                EventDraft::new(
                    "continued",
                    "system",
                    crate::util::now_rfc3339(),
                    json!({
                        "subtype": "continued",
                        "text": "Continued in SwitchTether. New turns run in a SwitchTether process; the original window on the computer does not show them.",
                        "from": s.id,
                    }),
                ),
                false,
            );
        }
        let session: Arc<dyn ManagedSession> = match req.agent.as_str() {
            "claude_code" => {
                let bin = self.claude.claude_bin.clone().ok_or_else(|| {
                    ApiError::validation("Claude Code (`claude`) was not found on this computer")
                })?;
                let permission_mode = match req.permission_mode.as_deref() {
                    None | Some("default") => None,
                    Some(m @ ("acceptEdits" | "plan")) => Some(m.to_string()),
                    Some(_) => {
                        return Err(ApiError::validation(
                            "permissionMode must be default, acceptEdits or plan",
                        ))
                    }
                };
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
                    },
                    log,
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
                let approval_policy = match req.permission_mode.as_deref() {
                    None | Some("default") | Some("on-request") => "on-request",
                    Some("untrusted") => "untrusted",
                    Some(_) => {
                        return Err(ApiError::validation(
                            "permissionMode for codex must be default, on-request or untrusted",
                        ))
                    }
                };
                ManagedCodex::spawn(
                    id.clone(),
                    ManagedCodexOptions {
                        codex_bin: bin,
                        cwd,
                        model: req.model.clone(),
                        approval_policy: approval_policy.into(),
                        resume_thread,
                    },
                    log,
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
            other => {
                return Err(ApiError::unavailable_capability(
                    "UNTESTED",
                    format!("managed sessions are not available for '{other}'"),
                ))
            }
        };
        self.managed.write().insert(id.clone(), session.clone());
        if let Some(p) = req.prompt.as_deref().filter(|p| !p.trim().is_empty()) {
            session.send_message(p).await?;
        }
        let summary = self.managed_summary(&id, session.as_ref());
        self.bus.publish("session.upserted", json!(summary));
        Ok(summary)
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
        let m = self.managed_or_blocked(id)?;
        if (text.trim().is_empty() && images.is_empty()) || text.len() > 200_000 {
            return Err(ApiError::validation(
                "message must be 1..200000 bytes (or include images)",
            ));
        }
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
        Ok(json!({ "accepted": true }))
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

    /// Poll live external sessions and publish changes (status, waitingFor,
    /// appearance/disappearance) so phones can notify about e.g. a terminal
    /// session waiting for a permission prompt. `active()` gates polling so
    /// nothing is spawned while no phone is connected.
    pub fn start_live_watcher(
        self: &Arc<Self>,
        cancel: CancellationToken,
        active: Arc<dyn Fn() -> bool + Send + Sync>,
    ) {
        let hub = Arc::downgrade(self);
        tokio::spawn(async move {
            type ObservedState = (Option<String>, Option<String>, Option<String>);
            let mut prev: HashMap<String, ObservedState> = HashMap::new();
            // Ticks a published session has been missing. Registry files are rewritten often and a
            // read can land mid-write, so one missed tick is not an exit (it showed up on phones as
            // a burst of "ended" sessions right after Claude Code started).
            let mut missing: HashMap<String, u8> = HashMap::new();
            // Last pushed title of each managed/attached Claude session. Claude Code writes the
            // generated title to the transcript after the turn's last event, so no event-driven
            // upsert ever carries it; it is picked up here.
            let mut managed_titles: HashMap<String, Option<String>> = HashMap::new();
            let mut tick = tokio::time::interval(Duration::from_secs(4));
            loop {
                tokio::select! {
                    _ = cancel.cancelled() => break,
                    _ = tick.tick() => {}
                }
                if !active() {
                    continue;
                }
                let Some(hub) = hub.upgrade() else { break };
                let live = hub.claude.live_sessions().await;
                // A managed session's own process also appears in the Claude registry;
                // it is reported once, under its `managed:` id, never as a second session.
                let managed_native: Vec<String> = hub
                    .managed
                    .read()
                    .values()
                    .filter_map(|m| m.native_session_id())
                    .collect();
                let mut now = HashMap::new();
                for l in live
                    .iter()
                    .filter(|l| !managed_native.contains(&l.session_id))
                {
                    let id = format!("claude:{}", l.session_id);
                    let transcript = hub.claude.transcript_path(l.cwd.as_deref(), &l.session_id);
                    // A process that just started registers an empty session first (no prompt, no
                    // transcript) and often switches to the resumed conversation right after.
                    // Nothing to show yet, so it is not announced until it has a transcript or work.
                    let working = matches!(l.status.as_deref(), Some("busy" | "waiting"))
                        || l.waiting_for.is_some();
                    if transcript.is_none() && !working && !prev.contains_key(&id) {
                        continue;
                    }
                    let title = transcript.and_then(|p| hub.claude_title(&p));
                    let state = (l.status.clone(), l.waiting_for.clone(), title.clone());
                    if prev.get(&id) != Some(&state) {
                        hub.bus.publish(
                            "session.upserted",
                            json!({
                                "id": id, "agent": "claude_code", "origin": l.origin(),
                                "liveness": "live", "status": l.status, "waitingFor": l.waiting_for,
                                "nativeId": l.session_id, "createdAtMs": l.started_at_ms,
                                "cwd": l.cwd, "project": l.cwd.as_deref().and_then(SessionSummary::project_from_cwd),
                                "title": title, "control": "observe",
                            }),
                        );
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
                    let n = missing.entry(id.clone()).or_insert(0);
                    *n += 1;
                    if *n >= 2 {
                        missing.remove(&id);
                        hub.bus
                            .publish("session.removed", json!({ "id": id, "liveness": "ended" }));
                    } else {
                        // Keep it for one more tick; reappearing with the same state publishes nothing.
                        now.insert(id, state);
                    }
                }
                prev = now;

                let claude_managed: Vec<(String, Arc<dyn ManagedSession>)> = hub
                    .managed
                    .read()
                    .iter()
                    .filter(|(_, m)| m.agent() == "claude_code")
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
            let mut last: HashMap<String, (String, Option<String>, Option<String>)> =
                HashMap::new();
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
                if frame.topic != "session.event"
                    || !matches!(
                        frame.data.get("kind").and_then(|k| k.as_str()),
                        Some(
                            "status" | "approval_request" | "question" | "user_message" | "system"
                        )
                    )
                {
                    continue;
                }
                let Some(id) = frame.data.get("sessionId").and_then(|s| s.as_str()) else {
                    continue;
                };
                let Some(hub) = hub.upgrade() else { break };
                let m = hub.managed.read().get(id).cloned();
                if let Some(m) = m {
                    let summary = hub.managed_summary(id, m.as_ref());
                    let key = (
                        summary.status.clone(),
                        summary.waiting_for.clone(),
                        summary.title.clone(),
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
            }
        }
        drop(loaded);
        let ttys: Vec<_> = self.ttys.read().values().cloned().collect();
        for t in ttys {
            t.tail_now();
        }
    }
}

fn observed_caps(agent: &str) -> Capabilities {
    let known = agent == "claude_code" || agent == "codex";
    Capabilities {
        read_history: Some(if known { CapStatus::Verified } else { CapStatus::Partial }),
        live_events: Some(if known { CapStatus::Partial } else { CapStatus::Blocked }),
        send_message: Some(CapStatus::Blocked),
        approve: Some(CapStatus::Blocked),
        answer_question: Some(CapStatus::Blocked),
        interrupt: Some(CapStatus::Blocked),
        continue_session: known.then_some(CapStatus::Partial),
        reason: Some(match agent {
            "claude_code" => "The original Claude Code process (CLI / VS Code) cannot be controlled from another program. Use \"Continue on phone\": the same conversation continues in a SwitchTether process (only while the original is idle; the original window will not show the new turns).".into(),
            "codex" => "The original Codex process (CLI / Desktop) cannot be controlled from another program. Use \"Continue on phone\": the thread continues on SwitchTether's own app-server, which Codex allows only when the thread is not open elsewhere.".into(),
            _ => "History only.".into(),
        }),
    }
}

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

fn managed_caps() -> Capabilities {
    Capabilities {
        read_history: Some(CapStatus::Verified),
        live_events: Some(CapStatus::Verified),
        send_message: Some(CapStatus::Verified),
        approve: Some(CapStatus::Verified),
        answer_question: Some(CapStatus::Partial),
        interrupt: Some(CapStatus::Verified),
        continue_session: None,
        reason: None,
    }
}

fn load_file(
    path: &Path,
    parser: Box<dyn LineParser>,
    log: &Arc<Mutex<SessionLog>>,
) -> ApiResult<Tail> {
    let meta =
        std::fs::metadata(path).map_err(|_| ApiError::not_found("transcript file not found"))?;
    let start = meta.len().saturating_sub(MAX_TRANSCRIPT_BYTES);
    let mut t = Tail {
        path: path.to_path_buf(),
        offset: start,
        partial: Vec::new(),
        parser,
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

fn tail_file(t: &mut Tail, log: &Arc<Mutex<SessionLog>>, publish: bool) -> std::io::Result<()> {
    let len = std::fs::metadata(&t.path)?.len();
    if len < t.offset {
        // Truncated/rewritten: start over from the beginning of the new file.
        t.offset = 0;
        t.partial.clear();
    }
    if len == t.offset {
        return Ok(());
    }
    let mut f = std::fs::File::open(&t.path)?;
    f.seek(SeekFrom::Start(t.offset))?;
    let mut buf = Vec::with_capacity((len - t.offset).min(MAX_TRANSCRIPT_BYTES) as usize);
    f.take(MAX_TRANSCRIPT_BYTES).read_to_end(&mut buf)?;
    t.offset += buf.len() as u64;
    t.partial.extend_from_slice(&buf);
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
}
