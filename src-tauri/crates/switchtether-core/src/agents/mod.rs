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
    pub cwd: String,
    pub prompt: Option<String>,
    pub model: Option<String>,
    pub permission_mode: Option<String>,
    /// Session id (e.g. `claude:<id>`) whose history to continue in a fork.
    pub resume_from: Option<String>,
}

pub struct AgentHub {
    bus: Arc<EventBus>,
    backend: Arc<dyn CcSwitchBackend>,
    pub claude: ClaudeObserver,
    managed: RwLock<HashMap<String, Arc<dyn ManagedSession>>>,
    codex_bin: Option<PathBuf>,
    loaded: Mutex<HashMap<String, Loaded>>,
    index_cache: Mutex<Option<(Instant, Arc<Vec<IndexedSession>>)>>,
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
        })
    }

    /// Static capability matrix (docs/AGENT_COMPATIBILITY.md).
    pub fn capability_matrix(&self) -> Value {
        json!([
            { "agent": "claude_code", "origin": "cli", "capabilities": observed_caps("claude_code"), "notes": "Live sessions discovered via `claude agents --json`; transcript tailed read-only. No supported interface to send messages, approve or interrupt an externally started session." },
            { "agent": "claude_code", "origin": "vscode", "capabilities": observed_caps("claude_code"), "notes": "The extension drives claude over private stdio pipes; no public extension API. Observation only." },
            { "agent": "claude_code", "origin": "managed", "capabilities": managed_caps(), "notes": "New sessions started by SwitchTether via the Agent SDK stream-json protocol with the user's normal settings, plugins, MCP and hooks." },
            { "agent": "codex", "origin": "cli", "capabilities": observed_caps("codex"), "notes": "Rollout files tailed read-only. A second app-server cannot attach to a live thread (Codex enforces a single writer)." },
            { "agent": "codex", "origin": "managed", "capabilities": managed_caps(), "notes": "New threads on SwitchTether's own codex app-server (stdio), using the normal Codex config and login." },
            { "agent": "codex", "origin": "desktop_app", "capabilities": observed_caps("codex"), "notes": "Codex Desktop runs its own app-server; no external attach. Observation of rollouts only." },
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
        SessionSummary {
            id: id.to_string(),
            agent: m.agent().into(),
            origin: Origin::Managed,
            native_id: m.native_session_id().unwrap_or_default(),
            title: m.title(),
            project: SessionSummary::project_from_cwd(&cwd),
            cwd: Some(cwd),
            liveness: m.liveness(),
            status: m.status().into(),
            waiting_for: if m.has_pending_requests() {
                Some("permission prompt".into())
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
            managed: true,
            source_path: None,
        }
    }

    pub async fn list(&self, q: &SessionQuery) -> (Vec<SessionSummary>, Option<String>) {
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
            out.push(SessionSummary {
                id: format!("claude:{}", l.session_id),
                agent: "claude_code".into(),
                origin: l.origin(),
                native_id: l.session_id.clone(),
                title: l.name.clone(),
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
                source_path: None,
            });
        }
        for s in self.index().await.iter() {
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
            out.push(SessionSummary {
                id: format!("{prefix}:{}", s.session_id),
                agent,
                origin: Origin::Unknown,
                native_id: s.session_id.clone(),
                title: s.title.clone().or(s.summary.clone()),
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

    pub async fn summary(&self, id: &str) -> Option<SessionSummary> {
        if let Some(m) = self.managed.read().get(id).cloned() {
            return Some(self.managed_summary(id, m.as_ref()));
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
                None => return None,
            }
        }
    }

    fn capabilities_for(&self, s: &SessionSummary) -> Capabilities {
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
        if let Some(m) = self.managed.read().get(&s.id) {
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
                let resume_from = match req.resume_from.as_deref() {
                    Some(r) => Some(
                        r.strip_prefix("claude:")
                            .ok_or_else(|| {
                                ApiError::validation("resumeFrom must be a claude session")
                            })?
                            .to_string(),
                    ),
                    None => None,
                };
                ManagedClaude::spawn(
                    id.clone(),
                    ManagedClaudeOptions {
                        claude_bin: bin,
                        cwd,
                        model: req.model.clone(),
                        permission_mode,
                        resume_from,
                    },
                    log,
                )
                .await
                .map_err(|e| ApiError::internal("start claude", e))?
            }
            "codex" => {
                if req.resume_from.is_some() {
                    return Err(ApiError::unavailable_capability(
                        "BLOCKED",
                        "Continuing an existing Codex thread from SwitchTether is disabled: Codex refuses a second writer for a thread that is open elsewhere, and taking it over would lock the original client out.",
                    ));
                }
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
                    },
                    log,
                )
                .await
                .map_err(|e| ApiError::internal("start codex", e))?
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
        if let Some(m) = self.managed.read().get(id).cloned() {
            return Ok(m);
        }
        Err(ApiError::unavailable_capability(
            "BLOCKED",
            "This session was started outside SwitchTether. Claude Code and Codex expose no supported interface to send input, approve or interrupt an existing session from another program. Start a managed session (optionally continuing this history) to control it from the phone.",
        ))
    }

    pub async fn send_message(&self, id: &str, text: &str) -> ApiResult<Value> {
        let m = self.managed_or_blocked(id)?;
        if text.trim().is_empty() || text.len() > 200_000 {
            return Err(ApiError::validation("message must be 1..200000 bytes"));
        }
        m.send_message(text).await?;
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

    pub fn managed_count(&self) -> usize {
        self.managed
            .read()
            .values()
            .filter(|m| m.liveness() == Liveness::Live)
            .count()
    }

    /// Kill every managed process (called when SwitchTether really quits).
    pub fn shutdown(&self) {
        for m in self.managed.read().values() {
            m.kill();
        }
    }

    /// Tail live transcripts in the background.
    pub fn start_tailer(self: &Arc<Self>, cancel: CancellationToken) {
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
        reason: Some(match agent {
            "claude_code" => "Externally started Claude Code sessions (CLI / VS Code) can only be observed: the official runtime offers no supported interface for another program to send input, answer prompts or interrupt them.".into(),
            "codex" => "Externally started Codex sessions (CLI / Desktop) can only be observed: their app-server runs in-process over private stdio.".into(),
            _ => "History only.".into(),
        }),
    }
}

fn managed_caps() -> Capabilities {
    Capabilities {
        read_history: Some(CapStatus::Verified),
        live_events: Some(CapStatus::Verified),
        send_message: Some(CapStatus::Verified),
        approve: Some(CapStatus::Verified),
        answer_question: Some(CapStatus::Untested),
        interrupt: Some(CapStatus::Verified),
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
    async fn external_sessions_are_blocked_for_control() {
        let hub = AgentHub::new(
            EventBus::new(16),
            Arc::new(UnavailableBackend),
            ClaudeObserver::new(PathBuf::from("/nonexistent"), None),
        );
        let e = hub.send_message("claude:abc", "hi").await.unwrap_err();
        assert_eq!(e.code, crate::error::ErrorCode::CapabilityUnavailable);
        assert_eq!(e.details.unwrap()["status"], "BLOCKED");
        assert!(hub.interrupt("codex:x").await.is_err());
    }
}
