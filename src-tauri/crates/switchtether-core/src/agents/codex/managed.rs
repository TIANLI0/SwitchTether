//! SwitchTether-managed Codex sessions over a private `codex app-server`
//! (JSON-RPC, one JSON object per line on stdio).
//!
//! The app-server is SwitchTether's own process; the thread it creates is a
//! NEW thread (labelled `origin: managed`). It reads the user's normal
//! `~/.codex` config/auth but never modifies configuration. Live threads of
//! other Codex processes are never resumed here (Codex itself refuses with
//! "thread already has an active writer" — docs/research/CODEX_RUNTIME.md).

use std::collections::HashMap;
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use parking_lot::Mutex;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, Command};
use tokio::sync::oneshot;

use crate::agents::managed::ManagedSession;
use crate::agents::model::{diff_stats, preview, EventDraft, Liveness, PREVIEW_LIMIT};
use crate::agents::store::SessionLog;
use crate::error::{ApiError, ApiResult};

#[derive(Debug, Clone)]
pub struct ManagedCodexOptions {
    pub codex_bin: PathBuf,
    pub cwd: PathBuf,
    pub model: Option<String>,
    /// Permission mode (`crate::agents::permission`, canonical Codex id: `default`, `plan`,
    /// `auto`, `readOnly`, `untrusted`, `bypassPermissions`).
    pub permission_mode: String,
    /// Continue an existing thread (`thread/resume`) instead of starting one.
    pub resume_thread: Option<String>,
    /// Reasoning effort for turns (`turn/start` `effort`).
    pub effort: Option<String>,
}

struct PendingApproval {
    rpc_id: Value,
    method: String,
    params: Value,
    event_id: String,
}

struct State {
    status: &'static str,
    liveness: Liveness,
    thread_id: Option<String>,
    turn_id: Option<String>,
    model: Option<String>,
    /// Model / effort chosen from the phone: sent with the next `turn/start` (Codex applies them
    /// to that turn and the ones after it).
    next_model: Option<String>,
    effort: Option<String>,
    title: Option<String>,
    created_at_ms: i64,
    last_active_at_ms: i64,
    /// Permission mode in effect (`None`: unknown, an attached thread before its settings were
    /// seen) and one chosen from the phone, sent with the next `turn/start`.
    mode: Option<String>,
    next_mode: Option<String>,
    /// The thread's workspace-write sandbox policy as Codex reported it (keeps the user's
    /// configured network access and writable roots when the phone returns to it).
    workspace_sandbox: Option<Value>,
    /// Last `thread/tokenUsage/updated`: tokens of the last request and the context window.
    context_tokens: Option<u64>,
    context_window: Option<u64>,
    /// Skills Codex reported for the session's folder (`skills/list`); `None`: not read yet.
    skills: Option<Vec<Value>>,
}

pub struct ManagedCodex {
    pub id: String,
    pub options: ManagedCodexOptions,
    log: Arc<Mutex<SessionLog>>,
    stdin: tokio::sync::Mutex<Option<ChildStdin>>,
    child: Mutex<Option<Child>>,
    next_id: AtomicU64,
    replies: Mutex<HashMap<u64, oneshot::Sender<Result<Value, Value>>>>,
    pending: Mutex<HashMap<String, PendingApproval>>,
    resolved: Mutex<HashMap<String, String>>,
    output: Mutex<HashMap<String, String>>,
    state: Mutex<State>,
    /// Attached mode: the thread lives in a Codex process the user started
    /// (Codex Desktop via switchtether-shim); requests go through `conn`.
    remote: Option<Arc<CodexConn>>,
    /// The ephemeral thread generating this conversation's title, while it runs.
    title_job: Mutex<Option<TitleJob>>,
    /// A title has been asked for (once per process).
    title_requested: std::sync::atomic::AtomicBool,
}

/// Collects the answer of the ephemeral title thread (see [`ManagedCodex::generate_title`]).
struct TitleJob {
    thread_id: Option<String>,
    text: String,
    done: Option<oneshot::Sender<String>>,
}

/// Codex Desktop's title model (`ephemeralGenerationModel`, a server-side setting whose default in
/// Codex Desktop 26.1117 is `gpt-6-luna`).
const TITLE_MODEL: &str = "gpt-6-luna";

/// The model for a title, from `model/list`: Desktop's title model when this account has it;
/// otherwise, as Desktop falls back, a listed general model that takes `low` effort — a small one
/// (`mini`, `nano`, `luna`) first, then the default. `None`: keep the session's model.
pub(crate) fn title_model(list: &Value) -> Option<String> {
    let models: Vec<&Value> = list
        .get("data")
        .or_else(|| list.get("models"))
        .and_then(Value::as_array)?
        .iter()
        .collect();
    let id = |m: &Value| s(m, "model").or_else(|| s(m, "id"));
    if models.iter().any(|m| id(m).as_deref() == Some(TITLE_MODEL)) {
        return Some(TITLE_MODEL.to_string());
    }
    let usable: Vec<&Value> = models
        .into_iter()
        .filter(|m| m.get("hidden").and_then(Value::as_bool) != Some(true))
        .filter(|m| m.get("modelSpecialty").is_none_or(Value::is_null))
        .filter(|m| {
            m.get("supportedReasoningEfforts")
                .and_then(Value::as_array)
                .is_none_or(|e| {
                    e.iter().any(|x| {
                        s(x, "reasoningEffort").as_deref() == Some("low")
                            || x.as_str() == Some("low")
                    })
                })
        })
        .collect();
    let small = |m: &Value| {
        id(m).is_some_and(|i| {
            let i = i.to_ascii_lowercase();
            i.contains("mini") || i.contains("nano") || i.contains("luna")
        })
    };
    usable
        .iter()
        .find(|m| small(m))
        .or_else(|| {
            usable
                .iter()
                .find(|m| m.get("isDefault").and_then(Value::as_bool) == Some(true))
        })
        .or(usable.first())
        .and_then(|m| id(m))
}

/// Codex Desktop's switches for its one-shot generation threads: no environment context,
/// AGENTS.md, apps, plugins, skills, memories or tools — the turn reads the prompt and answers.
fn title_thread_config() -> Value {
    json!({
        "include_permissions_instructions": false,
        "include_apps_instructions": false,
        "include_environment_context": false,
        "project_doc_max_bytes": 0,
        "apps": { "_default": { "enabled": false, "destructive_enabled": false, "open_world_enabled": false } },
        "skills": { "bundled": { "enabled": false }, "config": [] },
        "memories": { "use_memories": false, "generate_memories": false },
        "features.apps": false,
        "features.plugins": false,
        "features.tool_suggest": false,
        "features.shell_tool": false,
        "features.unified_exec": false,
        "features.js_repl": false,
        "features.code_mode": false,
        "features.multi_agent": false,
        "features.memories": false,
        "features.image_generation": false,
        "model_reasoning_effort": "low",
    })
}

/// Longest title Codex Desktop asks for (`MJuY.f`'s default), and the cap it enforces.
const TITLE_CHARS: usize = 36;
const TITLE_MAX_CHARS: usize = 60;

/// Codex Desktop's title instructions (26.1117, without the parts about app tools), then the
/// user's first prompt.
fn title_prompt(first_prompt: &str) -> String {
    format!(
        "You are a helpful assistant. You will be presented with a user prompt, and your job is to \
provide a short title for a task that will be created from that prompt.\n\
The tasks typically have to do with coding-related tasks, for example requests for bug fixes or \
questions about a codebase. The title you generate will be shown in the UI to represent the prompt.\n\
Generate a concise UI title (up to {TITLE_CHARS} characters) for this task.\n\
Fill the structured title field with plain text.\n\
Do not include quotes, markdown, formatting characters, or trailing punctuation.\n\
If the task includes a ticket reference (e.g. ABC-123), include it verbatim.\n\n\
How to write a good title:\n\
Generate a single-line title that captures the question or core change requested.\n\
- Use an imperative verb first: \"Add\", \"Fix\", \"Update\", \"Refactor\", \"Remove\", \"Locate\", \"Find\", etc.\n\
- Keep it under {TITLE_CHARS} characters and under 5 words where possible.\n\
- If the user's prompt is already a short clear title, reuse it verbatim.\n\
- Capitalize only the first word (unless locale requires otherwise).\n\
- Write the title in the user's locale.\n\
- Do not use punctuation at the end.\n\
- Leave code terms in English unless a widely adopted translation exists.\n\
- If the user provides a title explicitly, reuse it and skip generation logic.\n\
- Make it clear when the user is requesting changes (\"Fix\", \"Add\") vs asking a question (\"Find\", \"Locate\").\n\
- Make 0 tool calls.\n\n\
User prompt:\n{first_prompt}"
    )
}

/// The title from the turn's answer: the structured `{"title": …}` (or the plain text an older
/// app-server returns), one line, without quotes or trailing punctuation, at most
/// [`TITLE_MAX_CHARS`].
pub(crate) fn parse_title(answer: &str) -> Option<String> {
    let raw = serde_json::from_str::<Value>(answer.trim())
        .ok()
        .and_then(|v| s(&v, "title"))
        .unwrap_or_else(|| answer.to_string());
    let line = raw.lines().map(str::trim).find(|l| !l.is_empty())?;
    let t = line
        .trim_matches(|c| {
            matches!(
                c,
                '"' | '\'' | '`' | '\u{201c}' | '\u{201d}' | '\u{300c}' | '\u{300d}'
            )
        })
        .trim_end_matches(|c: char| {
            matches!(
                c,
                '.' | '\u{3002}' | '!' | '\u{ff01}' | '?' | '\u{ff1f}' | ':' | '\u{ff1a}'
            )
        })
        .trim();
    let t: String = t.chars().take(TITLE_MAX_CHARS).collect();
    crate::agents::claude::transcript::clean_title(Some(t))
}

/// Canonical JSON-RPC id key (numbers and strings never collide).
pub fn rpc_id_key(v: &Value) -> Option<String> {
    match v {
        Value::String(s) => Some(format!("s:{s}")),
        Value::Number(n) => Some(format!("n:{n}")),
        _ => None,
    }
}

/// One shim connection in front of a user-started `codex app-server`
/// (Codex Desktop). Several threads can be loaded on it at once.
pub struct CodexConn {
    tx: tokio::sync::mpsc::UnboundedSender<String>,
    tag: String,
    next: AtomicU64,
    replies: Mutex<HashMap<String, oneshot::Sender<Result<Value, Value>>>>,
    verdicts: Mutex<HashMap<String, oneshot::Sender<bool>>>,
    pub threads: Mutex<HashMap<String, Arc<ManagedCodex>>>,
    /// Subagent threadId -> parent threadId (routed into parent session, never listed as top-level).
    pub subagent_parents: Mutex<HashMap<String, String>>,
    pub origin: &'static str,
    pub cwd: PathBuf,
}

impl CodexConn {
    pub fn new(
        tx: tokio::sync::mpsc::UnboundedSender<String>,
        origin: &'static str,
        cwd: PathBuf,
    ) -> Arc<Self> {
        Arc::new(Self {
            tx,
            tag: crate::util::new_id().chars().take(8).collect(),
            next: AtomicU64::new(1),
            replies: Mutex::new(HashMap::new()),
            verdicts: Mutex::new(HashMap::new()),
            threads: Mutex::new(HashMap::new()),
            subagent_parents: Mutex::new(HashMap::new()),
            origin,
            cwd,
        })
    }

    fn inject(&self, v: &Value) -> ApiResult<()> {
        self.tx
            .send(json!({ "t": "inject", "line": v.to_string() }).to_string())
            .map_err(|_| ApiError::conflict("the attached Codex process has disconnected"))
    }

    async fn request(&self, method: &str, params: Value, timeout: Duration) -> ApiResult<Value> {
        let id = format!(
            "st-{}-{}",
            self.tag,
            self.next.fetch_add(1, Ordering::Relaxed)
        );
        let (tx, rx) = oneshot::channel();
        self.replies.lock().insert(id.clone(), tx);
        self.inject(&json!({ "id": id, "method": method, "params": params }))?;
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(Ok(v))) => Ok(v),
            Ok(Ok(Err(e))) => Err(ApiError::bad_request(
                e.get("message")
                    .and_then(|m| m.as_str())
                    .unwrap_or("codex request failed")
                    .to_string(),
            )),
            _ => {
                self.replies.lock().remove(&id);
                Err(ApiError::internal(
                    "codex request",
                    format!("{method} timed out"),
                ))
            }
        }
    }

    fn parse(frame: &Value) -> Option<Value> {
        frame
            .get("line")
            .and_then(|l| l.as_str())
            .and_then(|l| serde_json::from_str::<Value>(l).ok())
    }

    fn subagent_parent_id(t: &Value) -> Option<String> {
        s(t, "parentThreadId").or_else(|| {
            t.pointer("/source/subagent/thread_spawn/parent_thread_id")
                .or_else(|| t.pointer("/source/subAgent/thread_spawn/parent_thread_id"))
                .and_then(|x| x.as_str())
                .map(String::from)
        })
    }

    /// Threads that a frame reveals: `(threadId, cwd)`.
    /// Subagent threads (`parentThreadId` set) are linked to their parent thread instead of
    /// creating separate top-level sessions.
    pub fn discover(&self, frame: &Value) -> Vec<(String, Option<String>)> {
        if frame.get("t").and_then(|t| t.as_str()) != Some("out") {
            return vec![];
        }
        let Some(v) = Self::parse(frame) else {
            return vec![];
        };
        let known = |id: &str| {
            self.threads.lock().contains_key(id) || self.subagent_parents.lock().contains_key(id)
        };
        let mut out = Vec::new();
        // Multi-agent v2 announces a child only as a `subAgentActivity` item of its parent (no
        // `thread/started`): link it before any of its own events arrive, so it is never taken
        // for a new top-level thread.
        if let (Some(parent), Some(item)) = (
            v.pointer("/params/threadId").and_then(Value::as_str),
            v.pointer("/params/item")
                .filter(|i| s(i, "type").as_deref() == Some("subAgentActivity")),
        ) {
            if let Some(child) = s(item, "agentThreadId").filter(|c| c != parent) {
                if known(parent) && s(item, "kind").as_deref() == Some("started") {
                    self.subagent_parents
                        .lock()
                        .entry(child)
                        .or_insert_with(|| parent.to_string());
                }
            }
        }
        // Replies to thread/start | thread/resume | thread/fork (host or ours).
        if let Some(t) = v.pointer("/result/thread") {
            if let Some(id) = s(t, "id") {
                if let Some(parent) = Self::subagent_parent_id(t) {
                    self.subagent_parents.lock().insert(id, parent);
                } else if !known(&id) {
                    out.push((id, s(t, "cwd").or(s(v.get("result").unwrap(), "cwd"))));
                }
            }
        }
        if s(&v, "method").as_deref() == Some("thread/started") {
            if let Some(t) = v.pointer("/params/thread") {
                if let Some(id) = s(t, "id") {
                    if let Some(parent) = Self::subagent_parent_id(t) {
                        self.subagent_parents
                            .lock()
                            .insert(id.clone(), parent.clone());
                        if let Some(parent_session) = self.threads.lock().get(&parent).cloned() {
                            parent_session.on_subagent_started(&id, t);
                        }
                    } else if !known(&id) {
                        out.push((id, s(t, "cwd")));
                    }
                }
            }
        } else if let Some(id) = v.pointer("/params/threadId").and_then(|x| x.as_str()) {
            if !known(id) && !out.iter().any(|(t, _)| t == id) {
                out.push((id.to_string(), None));
            }
        }
        out
    }

    fn thread_of(&self, v: &Value) -> Option<(Arc<ManagedCodex>, Option<String>)> {
        let id = v.pointer("/params/threadId").and_then(|x| x.as_str())?;
        if let Some(t) = self.threads.lock().get(id).cloned() {
            return Some((t, None));
        }
        let mut cur = id.to_string();
        let mut hops = 0;
        let parents = self.subagent_parents.lock();
        while let Some(p) = parents.get(&cur) {
            hops += 1;
            if hops > 8 {
                break;
            }
            if let Some(t) = self.threads.lock().get(p).cloned() {
                return Some((t, Some(id.to_string())));
            }
            cur = p.clone();
        }
        None
    }

    pub async fn on_frame(&self, frame: Value) {
        match frame.get("t").and_then(|t| t.as_str()) {
            Some("out") => {
                let Some(v) = Self::parse(&frame) else { return };
                let method = s(&v, "method");
                match (v.get("id"), method) {
                    (Some(id), None) => {
                        if let Some(thread) = v.pointer("/result/thread") {
                            if let Some(t) =
                                s(thread, "id").and_then(|id| self.threads.lock().get(&id).cloned())
                            {
                                t.on_thread_metadata(thread);
                            }
                        }
                        // Reply to one of our requests.
                        if let Some(id) = id.as_str() {
                            if let Some(tx) = self.replies.lock().remove(id) {
                                let r = match v.get("error") {
                                    Some(e) => Err(e.clone()),
                                    None => Ok(v.get("result").cloned().unwrap_or(Value::Null)),
                                };
                                let _ = tx.send(r);
                            }
                        }
                    }
                    (Some(id), Some(m)) => {
                        if let Some((t, subagent_tid)) = self.thread_of(&v) {
                            t.on_server_request_with_parent(
                                id.clone(),
                                &m,
                                v.get("params").cloned().unwrap_or(Value::Null),
                                subagent_tid.map(|tid| format!("cx-sa-{tid}")),
                            )
                            .await;
                        }
                    }
                    (None, Some(m)) => {
                        if m == "thread/started" {
                            if let Some(thread) = v.pointer("/params/thread") {
                                if let Some(t) = s(thread, "id")
                                    .and_then(|id| self.threads.lock().get(&id).cloned())
                                {
                                    t.on_thread_metadata(thread);
                                }
                            }
                        }
                        if let Some((t, subagent_tid)) = self.thread_of(&v) {
                            if let Some(tid) = subagent_tid {
                                t.on_subagent_notification(
                                    &tid,
                                    &m,
                                    v.get("params").cloned().unwrap_or(Value::Null),
                                );
                            } else {
                                t.on_notification(
                                    &m,
                                    v.get("params").cloned().unwrap_or(Value::Null),
                                );
                            }
                        }
                    }
                    _ => {}
                }
            }
            Some("in") => {
                // Codex Desktop answered an approval itself.
                let Some(v) = Self::parse(&frame) else { return };
                if v.get("method").is_none()
                    && (v.get("result").is_some() || v.get("error").is_some())
                {
                    if let Some(id) = v.get("id") {
                        let rid = format!(
                            "cx-{}",
                            match id {
                                Value::String(x) => x.clone(),
                                other => other.to_string(),
                            }
                        );
                        let threads: Vec<_> = self.threads.lock().values().cloned().collect();
                        for t in threads {
                            t.resolved_on_desktop(&rid, &v);
                        }
                    }
                }
            }
            Some(kind @ ("accepted" | "rejected")) => {
                if let Some(key) =
                    Self::parse(&frame).and_then(|v| v.get("id").and_then(rpc_id_key))
                {
                    if let Some(tx) = self.verdicts.lock().remove(&key) {
                        let _ = tx.send(kind == "accepted");
                    }
                }
            }
            Some("exit") => self.on_disconnect(),
            _ => {}
        }
    }

    pub fn on_disconnect(&self) {
        let threads: Vec<_> = self.threads.lock().values().cloned().collect();
        for t in threads {
            if t.liveness() == Liveness::Live {
                t.on_exit();
            }
        }
        for (_, tx) in self.replies.lock().drain() {
            let _ = tx.send(Err(json!({ "message": "process exited" })));
        }
    }
}

/// Questions for an MCP elicitation (`{mode, message, serverName, url, requestedSchema}`): one
/// per schema property, or one confirmation. Also used for Claude Code's `elicitation` request.
/// The URL of a URL elicitation when the phone may offer to open it: `http(s)` only, one line.
/// Anything else (`javascript:`, `intent:`, `file:` …) from an MCP server is dropped.
pub(crate) fn elicitation_url(params: &Value) -> Option<String> {
    let url = s(params, "url")?;
    let url = url.trim();
    let lower = url.to_ascii_lowercase();
    ((lower.starts_with("https://") || lower.starts_with("http://"))
        && url.len() <= 8192
        && !url.chars().any(|c| c.is_control() || c.is_whitespace()))
    .then(|| url.to_string())
}

/// The texts an MCP server writes (message, field titles and descriptions) are redacted like
/// other agent text that reaches the phone; the URL of a URL elicitation is not (the user has to
/// open it), nor are option labels / values (they are what the answer sends back).
pub(crate) fn elicitation_to_questions(params: &Value) -> Vec<Value> {
    let mode = s(params, "mode").unwrap_or_default();
    let msg = crate::redact::redact_text(
        &s(params, "message").unwrap_or_else(|| "Input requested".into()),
    );
    let server = s(params, "serverName").unwrap_or_default();
    if mode == "url" {
        let url = elicitation_url(params).unwrap_or_default();
        return vec![json!({
            "id": "__confirm__",
            "header": if server.is_empty() { Value::Null } else { json!(server) },
            "question": if url.is_empty() { msg } else { format!("{msg}\n{url}") },
            "multiSelect": false,
            "allowOther": false,
            "options": [
                { "label": "Accept", "description": "Confirm and continue" }
            ],
        })];
    }
    // No fields to fill in (no schema, or `properties: {}`): one confirmation.
    let Some(props) = params
        .pointer("/requestedSchema/properties")
        .and_then(|p| p.as_object())
        .filter(|p| !p.is_empty())
    else {
        return vec![json!({
            "id": "__confirm__",
            "header": if server.is_empty() { Value::Null } else { json!(server) },
            "question": msg,
            "multiSelect": false,
            "allowOther": true,
            "options": [],
        })];
    };
    props
        .iter()
        .map(|(k, schema)| {
            let title =
                crate::redact::redact_text(&s(schema, "title").unwrap_or_else(|| k.clone()));
            let desc = s(schema, "description").map(|d| crate::redact::redact_text(&d));
            let ty = s(schema, "type").unwrap_or_else(|| "string".into());
            let mut multi = false;
            let mut options: Vec<Value> = Vec::new();
            if let Some(en) = schema.get("enum").and_then(|e| e.as_array()) {
                for v in en {
                    if let Some(lbl) = v.as_str() {
                        options.push(json!({ "label": lbl, "description": Value::Null }));
                    }
                }
            } else if let Some(one_of) = schema
                .get("oneOf")
                .or_else(|| schema.get("anyOf"))
                .and_then(|e| e.as_array())
            {
                for v in one_of {
                    if let Some(c) = v.get("const").and_then(|x| x.as_str()) {
                        let t = s(v, "title").unwrap_or_else(|| c.to_string());
                        options.push(json!({ "label": t, "value": c, "description": Value::Null }));
                    }
                }
            } else if ty == "array" {
                multi = true;
                if let Some(en) = schema.pointer("/items/enum").and_then(|e| e.as_array()) {
                    for v in en {
                        if let Some(lbl) = v.as_str() {
                            options.push(json!({ "label": lbl, "description": Value::Null }));
                        }
                    }
                } else if let Some(any_of) =
                    schema.pointer("/items/anyOf").and_then(|e| e.as_array())
                {
                    for v in any_of {
                        if let Some(c) = v.get("const").and_then(|x| x.as_str()) {
                            let t = s(v, "title").unwrap_or_else(|| c.to_string());
                            options.push(
                                json!({ "label": t, "value": c, "description": Value::Null }),
                            );
                        }
                    }
                }
            } else if ty == "boolean" {
                options.push(json!({ "label": "true", "description": "Yes" }));
                options.push(json!({ "label": "false", "description": "No" }));
            }
            let allow_other = options.is_empty();
            json!({
                "id": k,
                "header": if server.is_empty() { Value::Null } else { json!(server) },
                "question": if let Some(d) = desc { format!("{title}\n{d}") } else { title },
                "multiSelect": multi,
                "allowOther": allow_other,
                "isSecret": false,
                "required": params.pointer("/requestedSchema/required").and_then(Value::as_array)
                    .is_some_and(|fields| fields.iter().any(|field| field.as_str() == Some(k))),
                "options": options,
                "schemaType": ty,
            })
        })
        .collect()
}

/// The `content` of an accepted elicitation: the phone's answers typed after the schema.
pub(crate) fn build_elicitation_content(params: &Value, answers: Option<&Value>) -> Value {
    let Some(ans_map) = answers.and_then(|a| a.as_object()) else {
        return json!({});
    };
    let props = params
        .pointer("/requestedSchema/properties")
        .and_then(|p| p.as_object());
    let mut out = serde_json::Map::new();
    for (k, v) in ans_map {
        if k == "__confirm__" {
            continue;
        }
        let schema_ty = props
            .and_then(|p| p.get(k))
            .and_then(|s| s.get("type"))
            .and_then(|t| t.as_str())
            .unwrap_or("string");
        let val = match (schema_ty, v) {
            ("boolean", Value::String(s)) => json!(s == "true"),
            ("integer", Value::String(s)) => s
                .parse::<i64>()
                .map(|n| json!(n))
                .unwrap_or_else(|_| v.clone()),
            ("number", Value::String(s)) => s
                .parse::<f64>()
                .map(|n| json!(n))
                .unwrap_or_else(|_| v.clone()),
            ("array", Value::String(s)) => {
                let arr: Vec<&str> = s
                    .split(',')
                    .map(|x| x.trim())
                    .filter(|x| !x.is_empty())
                    .collect();
                json!(arr)
            }
            _ => v.clone(),
        };
        out.insert(k.clone(), val);
    }
    Value::Object(out)
}

/// Title of an edit step: the file, or how many files.
pub(crate) fn edit_title(paths: &[String]) -> String {
    match paths {
        [one] => one.clone(),
        many => format!("{} files", many.len()),
    }
}

/// Plan step status as Claude Code spells it (`in_progress`; app-server v2 says `inProgress`).
pub(crate) fn plan_status(v: Option<&Value>) -> Value {
    match v.and_then(Value::as_str) {
        Some("inProgress" | "in_progress") => json!("in_progress"),
        Some("completed") => json!("completed"),
        _ => json!("pending"),
    }
}

/// What an MCP tool returned, as text: its text content (like Claude Code shows it), else JSON.
fn mcp_result_text(item: &Value) -> String {
    if let Some(e) = item.get("error").filter(|e| !e.is_null()) {
        return s(e, "message").unwrap_or_else(|| e.to_string());
    }
    let Some(r) = item.get("result").filter(|r| !r.is_null()) else {
        return String::new();
    };
    let texts: Vec<String> = r
        .get("content")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(|c| s(c, "text")).collect())
        .unwrap_or_default();
    if !texts.is_empty() {
        return texts.join(
            "
",
        );
    }
    match r.get("structuredContent").filter(|v| !v.is_null()) {
        Some(sc) => serde_json::to_string_pretty(sc).unwrap_or_default(),
        None => r.to_string(),
    }
}

fn s(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(|x| x.as_str()).map(|x| x.to_string())
}

/// Event id of a child thread's item in its parent's log. Codex item ids are per-thread
/// counters, so a child's `item-3` must not replace the parent's. Sub-agent rows
/// (`cx-sa-<thread id>`) are already unique and keep their id.
fn scope_child_id(id: &str, tid: &str) -> String {
    if id.starts_with("cx-sa-") || id.ends_with(&format!("@{tid}")) {
        id.to_string()
    } else {
        format!("{id}@{tid}")
    }
}

impl ManagedCodex {
    pub async fn spawn(
        id: String,
        options: ManagedCodexOptions,
        log: Arc<Mutex<SessionLog>>,
    ) -> anyhow::Result<Arc<Self>> {
        let mut cmd = Command::new(&options.codex_bin);
        cmd.arg("app-server")
            .current_dir(&options.cwd)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true);
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
        let now = crate::util::now_ms();
        let me = Arc::new(Self {
            id,
            log,
            stdin: tokio::sync::Mutex::new(stdin),
            child: Mutex::new(Some(child)),
            next_id: AtomicU64::new(1),
            replies: Mutex::new(HashMap::new()),
            pending: Mutex::new(HashMap::new()),
            resolved: Mutex::new(HashMap::new()),
            output: Mutex::new(HashMap::new()),
            state: Mutex::new(State {
                status: "idle",
                liveness: Liveness::Live,
                thread_id: None,
                turn_id: None,
                model: options.model.clone(),
                next_model: None,
                effort: options.effort.clone(),
                title: None,
                created_at_ms: now,
                last_active_at_ms: now,
                mode: Some(options.permission_mode.clone()),
                next_mode: None,
                workspace_sandbox: None,
                context_tokens: None,
                context_window: None,
                skills: None,
            }),
            options,
            remote: None,
            title_job: Mutex::new(None),
            title_requested: std::sync::atomic::AtomicBool::new(false),
        });
        let reader = me.clone();
        tokio::spawn(async move {
            let mut lines = BufReader::new(stdout).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if let Ok(v) = serde_json::from_str::<Value>(&line) {
                    let ended_turn = s(&v, "method").as_deref() == Some("turn/completed")
                        && v.pointer("/params/threadId").and_then(Value::as_str)
                            == reader.state.lock().thread_id.as_deref();
                    reader.on_message(v).await;
                    if ended_turn && reader.wants_title() {
                        let me = reader.clone();
                        tokio::spawn(async move { me.generate_title().await });
                    }
                }
            }
            reader.on_exit();
        });

        let init = me
            .request(
                "initialize",
                json!({
                    "clientInfo": { "name": "switchtether", "title": "SwitchTether", "version": env!("CARGO_PKG_VERSION") },
                    "capabilities": { "experimentalApi": true, "requestAttestation": false }
                }),
                Duration::from_secs(30),
            )
            .await;
        if let Err(e) = init {
            me.kill();
            anyhow::bail!("codex app-server did not initialize: {e}");
        }
        me.notify("initialized", None).await.ok();
        let (approval, sandbox, reviewer, _) =
            crate::agents::permission::codex_settings(&me.options.permission_mode);
        let mut params = json!({
            "cwd": me.options.cwd.to_string_lossy(),
            "approvalPolicy": approval,
            "sandbox": sandbox,
        });
        if reviewer != "user" {
            // Only when chosen: otherwise the user's own `approvals_reviewer` setting applies.
            params["approvalsReviewer"] = json!(reviewer);
        }
        if let Some(m) = &me.options.model {
            params["model"] = json!(m);
        }
        let (method, params) = match &me.options.resume_thread {
            Some(t) => {
                let mut p = params;
                p["threadId"] = json!(t);
                // The history comes from the rollout file (preloaded); full-history hydration
                // is deprecated for paginated threads.
                p["excludeTurns"] = json!(true);
                ("thread/resume", p)
            }
            None => ("thread/start", params),
        };
        let started = match me.request(method, params, Duration::from_secs(60)).await {
            Ok(v) => v,
            Err(e) => {
                me.kill();
                anyhow::bail!("{method} failed: {}", e.message);
            }
        };
        {
            let mut st = me.state.lock();
            st.thread_id = started.get("thread").and_then(|t| s(t, "id"));
            st.title = started.get("thread").and_then(|t| s(t, "name"));
            if let Some(m) = s(&started, "model") {
                st.model = Some(m);
            }
            if st.effort.is_none() {
                st.effort = s(&started, "reasoningEffort");
            }
            if let Some(sb) = started
                .get("sandbox")
                .filter(|x| x.get("type").and_then(Value::as_str) == Some("workspaceWrite"))
            {
                st.workspace_sandbox = Some(sb.clone());
            }
        }
        me.system(&format!(
            "Codex thread started{}",
            me.state
                .lock()
                .model
                .as_deref()
                .map(|m| format!(" · {m}"))
                .unwrap_or_default()
        ));
        Ok(me)
    }

    /// A thread loaded in a user-started Codex process (attached mode).
    pub fn attached(
        id: String,
        conn: Arc<CodexConn>,
        thread_id: String,
        cwd: PathBuf,
        log: Arc<Mutex<SessionLog>>,
    ) -> Arc<Self> {
        let now = crate::util::now_ms();
        Arc::new(Self {
            id,
            options: ManagedCodexOptions {
                codex_bin: PathBuf::new(),
                cwd,
                model: None,
                permission_mode: String::new(),
                resume_thread: None,
                effort: None,
            },
            log,
            stdin: tokio::sync::Mutex::new(None),
            child: Mutex::new(None),
            next_id: AtomicU64::new(1),
            replies: Mutex::new(HashMap::new()),
            pending: Mutex::new(HashMap::new()),
            resolved: Mutex::new(HashMap::new()),
            output: Mutex::new(HashMap::new()),
            state: Mutex::new(State {
                status: "idle",
                liveness: Liveness::Live,
                thread_id: Some(thread_id),
                turn_id: None,
                model: None,
                next_model: None,
                effort: None,
                title: None,
                created_at_ms: now,
                last_active_at_ms: now,
                mode: None,
                next_mode: None,
                workspace_sandbox: None,
                context_tokens: None,
                context_window: None,
                skills: None,
            }),
            remote: Some(conn),
            title_job: Mutex::new(None),
            title_requested: std::sync::atomic::AtomicBool::new(false),
        })
    }

    pub fn origin_name(&self) -> &'static str {
        self.remote.as_ref().map(|c| c.origin).unwrap_or("managed")
    }

    /// Codex Desktop answered a request itself: the phone's card shows what was chosen there
    /// (the decision, the answers), not just that it is gone.
    fn resolved_on_desktop(&self, rid: &str, reply: &Value) {
        let removed = self.pending.lock().remove(rid);
        let Some(pa) = removed else { return };
        self.resolved
            .lock()
            .insert(rid.to_string(), "desktop".into());
        let result = reply.get("result").filter(|_| reply.get("error").is_none());
        let (decision, answers) = desktop_choice(&pa.method, result);
        self.log.lock().update(&pa.event_id, true, |e| {
            e.data["resolved"] = json!(true);
            e.data["decision"] = json!(decision);
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

    fn system(&self, text: &str) {
        self.log.lock().upsert(
            EventDraft::new(
                format!("sys:{}", crate::util::new_id()),
                "system",
                crate::util::now_rfc3339(),
                json!({ "subtype": "info", "text": text }),
            ),
            true,
        );
    }

    fn publish_status(&self, state: &str, detail: Option<&str>) {
        let mut d = EventDraft::new(
            "status:current",
            "status",
            crate::util::now_rfc3339(),
            json!({ "state": state, "detail": detail }),
        );
        d.id = "status:current".into();
        self.log.lock().upsert(d, true);
    }

    fn set_status(&self, status: &'static str) {
        let mut st = self.state.lock();
        st.status = status;
        st.last_active_at_ms = crate::util::now_ms();
    }

    async fn write(&self, v: &Value) -> ApiResult<()> {
        if let Some(c) = &self.remote {
            return c.inject(v);
        }
        let mut g = self.stdin.lock().await;
        let Some(stdin) = g.as_mut() else {
            return Err(ApiError::conflict("codex app-server has exited"));
        };
        let mut line = v.to_string();
        line.push('\n');
        stdin
            .write_all(line.as_bytes())
            .await
            .map_err(|e| ApiError::internal("codex stdin", e))?;
        stdin
            .flush()
            .await
            .map_err(|e| ApiError::internal("codex stdin", e))
    }

    async fn notify(&self, method: &str, params: Option<Value>) -> ApiResult<()> {
        let mut msg = json!({ "method": method });
        if let Some(p) = params {
            msg["params"] = p;
        }
        self.write(&msg).await
    }

    async fn request(&self, method: &str, params: Value, timeout: Duration) -> ApiResult<Value> {
        if let Some(c) = &self.remote {
            return c.request(method, params, timeout).await;
        }
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = oneshot::channel();
        self.replies.lock().insert(id, tx);
        self.write(&json!({ "id": id, "method": method, "params": params }))
            .await?;
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(Ok(v))) => Ok(v),
            Ok(Ok(Err(e))) => Err(ApiError::bad_request(
                e.get("message")
                    .and_then(|m| m.as_str())
                    .unwrap_or("codex request failed")
                    .to_string(),
            )),
            _ => {
                self.replies.lock().remove(&id);
                Err(ApiError::internal(
                    "codex request",
                    format!("{method} timed out"),
                ))
            }
        }
    }

    /// Codex names threads only from its own front ends (the TUI's and Codex Desktop's title
    /// suggestion); `codex app-server` started by SwitchTether leaves them unnamed, so a session
    /// started from the phone kept its first prompt as the title. Asked once, after the first turn
    /// of a thread this process started (a resumed one already has its name, attached ones are
    /// named by Codex Desktop), when Codex has not named it.
    fn wants_title(&self) -> bool {
        if self.remote.is_some() || self.options.resume_thread.is_some() {
            return false;
        }
        if self.state.lock().title.is_some() {
            return false;
        }
        !self
            .title_requested
            .swap(true, std::sync::atomic::Ordering::SeqCst)
    }

    /// Events of the ephemeral title thread: the answer is collected, nothing reaches the log.
    fn on_title_thread(&self, thread_id: &str, method: &str, p: &Value) -> bool {
        let mut job = self.title_job.lock();
        let Some(j) = job.as_mut() else { return false };
        if j.thread_id.as_deref() != Some(thread_id) {
            return false;
        }
        match method {
            "item/completed" => {
                if let Some(item) = p
                    .get("item")
                    .filter(|i| s(i, "type").as_deref() == Some("agentMessage"))
                {
                    if let Some(t) = s(item, "text") {
                        j.text = t;
                    }
                }
            }
            "turn/completed" => {
                if let Some(tx) = j.done.take() {
                    let _ = tx.send(std::mem::take(&mut j.text));
                }
            }
            _ => {}
        }
        true
    }

    /// Generates the title the way Codex Desktop does (its `ThreadMetadataGenerationService`): one
    /// structured-output turn on an ephemeral, read-only thread without approvals, with the
    /// environment context, AGENTS.md, apps, skills, memories and tools switched off, on a small
    /// model ([`title_model`]) at low effort, from the first prompt (at most 2000 characters),
    /// with Desktop's title instructions. The result goes to `thread/name/set`, which Codex records
    /// in its session index and announces with `thread/name/updated`. Done once per conversation:
    /// a title the user set meanwhile is kept, and nothing is asked again later. Failure leaves the
    /// first-prompt title.
    async fn generate_title(&self) {
        let Ok(own) = self.thread_id() else { return };
        let prompt = self
            .log
            .lock()
            .iter()
            .find(|e| e.kind == "user_message" && e.parent_id.is_none())
            .and_then(|e| e.data.get("text").and_then(Value::as_str).map(String::from));
        let Some(prompt) = prompt.filter(|p| !p.trim().is_empty()) else {
            return;
        };
        let request = title_prompt(&crate::agents::model::preview(prompt.trim(), 2000).0);
        let listed = self
            .request(
                "model/list",
                json!({ "includeHidden": true }),
                Duration::from_secs(15),
            )
            .await
            .ok();
        let session_model = self.state.lock().model.clone();
        let model = listed.as_ref().and_then(title_model).or(session_model);
        let (tx, rx) = oneshot::channel();
        *self.title_job.lock() = Some(TitleJob {
            thread_id: None,
            text: String::new(),
            done: Some(tx),
        });
        let mut params = json!({
            "cwd": self.options.cwd.to_string_lossy(),
            "approvalPolicy": "never",
            "sandbox": "read-only",
            "ephemeral": true,
            "config": title_thread_config(),
        });
        if let Some(m) = &model {
            params["model"] = json!(m);
        }
        let mut started = self
            .request("thread/start", params.clone(), Duration::from_secs(30))
            .await;
        if started.is_err() {
            // An app-server that does not know one of the switches: start it plainly.
            if let Some(o) = params.as_object_mut() {
                o.remove("config");
            }
            started = self
                .request("thread/start", params, Duration::from_secs(30))
                .await;
        }
        let Some(tid) = started
            .ok()
            .and_then(|v| v.get("thread").and_then(|t| s(t, "id")))
        else {
            *self.title_job.lock() = None;
            return;
        };
        if let Some(j) = self.title_job.lock().as_mut() {
            j.thread_id = Some(tid.clone());
        }
        let input = json!([{ "type": "text", "text": request, "text_elements": [] }]);
        let mut turn = json!({
            "threadId": tid,
            "input": input,
            "effort": "low",
            "outputSchema": {
                "type": "object",
                "properties": { "title": { "type": "string" } },
                "required": ["title"],
                "additionalProperties": false,
            },
        });
        let mut sent = self
            .request("turn/start", turn.clone(), Duration::from_secs(30))
            .await
            .is_ok();
        if !sent {
            // Older app-servers: no structured output / no such effort level.
            if let Some(o) = turn.as_object_mut() {
                o.remove("outputSchema");
                o.remove("effort");
            }
            sent = self
                .request("turn/start", turn, Duration::from_secs(30))
                .await
                .is_ok();
        }
        let text = if sent {
            tokio::time::timeout(Duration::from_secs(60), rx)
                .await
                .ok()
                .and_then(Result::ok)
        } else {
            None
        };
        *self.title_job.lock() = None;
        let _ = self
            .request(
                "thread/unsubscribe",
                json!({ "threadId": tid }),
                Duration::from_secs(10),
            )
            .await;
        let Some(title) = text.as_deref().and_then(parse_title) else {
            return;
        };
        if self.state.lock().title.is_some() {
            return; // named meanwhile (/rename from the phone, or by Codex)
        }
        if self
            .request(
                "thread/name/set",
                json!({ "threadId": own, "name": title }),
                Duration::from_secs(20),
            )
            .await
            .is_ok()
        {
            self.state.lock().title = Some(title);
        }
    }

    pub fn effort(&self) -> Option<String> {
        self.state.lock().effort.clone()
    }

    /// Model for the following turns (`turn/start` `model`); `None` keeps the thread's model.
    pub fn set_model(&self, model: Option<&str>) {
        let mut st = self.state.lock();
        st.next_model = model.map(String::from);
        if let Some(m) = model {
            st.model = Some(m.to_string());
        }
    }

    /// Reasoning effort for the following turns (`turn/start` `effort`).
    pub fn set_effort(&self, effort: Option<&str>) {
        self.state.lock().effort = effort.map(String::from);
    }

    /// Models this Codex offers (`model/list`), as returned by the app-server.
    pub async fn list_models(&self) -> ApiResult<Value> {
        self.request("model/list", json!({}), Duration::from_secs(15))
            .await
    }

    fn thread_id(&self) -> ApiResult<String> {
        self.state
            .lock()
            .thread_id
            .clone()
            .ok_or_else(|| ApiError::conflict("codex thread not started"))
    }

    async fn on_message(&self, v: Value) {
        let has_id = v.get("id").is_some();
        let method = s(&v, "method");
        // The ephemeral title thread shares this process: its events are not this conversation's.
        if let (Some(m), Some(tid)) = (
            method.as_deref(),
            v.pointer("/params/threadId").and_then(Value::as_str),
        ) {
            if self.on_title_thread(tid, m, v.get("params").unwrap_or(&Value::Null)) {
                return;
            }
        }
        match (has_id, method) {
            (true, None) => {
                let id = v.get("id").and_then(|i| i.as_u64()).unwrap_or(0);
                if let Some(tx) = self.replies.lock().remove(&id) {
                    let r = match v.get("error") {
                        Some(e) => Err(e.clone()),
                        None => Ok(v.get("result").cloned().unwrap_or(Value::Null)),
                    };
                    let _ = tx.send(r);
                }
            }
            (true, Some(m)) => {
                self.on_server_request(
                    v.get("id").cloned().unwrap_or(Value::Null),
                    &m,
                    v.get("params").cloned().unwrap_or(Value::Null),
                )
                .await
            }
            (false, Some(m)) => {
                self.on_notification(&m, v.get("params").cloned().unwrap_or(Value::Null))
            }
            _ => {}
        }
    }

    pub(crate) async fn on_server_request(&self, rpc_id: Value, method: &str, params: Value) {
        self.on_server_request_with_parent(rpc_id, method, params, None)
            .await;
    }

    pub(crate) async fn on_server_request_with_parent(
        &self,
        rpc_id: Value,
        method: &str,
        params: Value,
        parent_id: Option<String>,
    ) {
        let ts = crate::util::now_rfc3339();
        let request_id = format!(
            "cx-{}",
            match &rpc_id {
                Value::String(x) => x.clone(),
                other => other.to_string(),
            }
        );
        let event_id = format!("req:{request_id}");
        let mut is_question = false;
        let mut draft = match method {
            "item/commandExecution/requestApproval" => EventDraft::new(
                &event_id,
                "approval_request",
                &ts,
                json!({
                    "requestId": request_id, "kind": "command",
                    "title": s(&params, "command").unwrap_or_else(|| "Run command".into()),
                    "detail": params.get("reason"), "command": params.get("command"), "cwd": params.get("cwd"),
                    "toolName": "shell", "input": params,
                    "options": [
                        { "id": "allow", "label": "Allow" },
                        { "id": "allow_session", "label": "Allow for session" },
                        { "id": "deny", "label": "Deny" },
                        { "id": "abort", "label": "Deny and stop" }
                    ],
                    "resolved": false,
                }),
            ),
            "item/fileChange/requestApproval" => EventDraft::new(
                &event_id,
                "approval_request",
                &ts,
                json!({
                    "requestId": request_id, "kind": "file_change", "title": "Apply file changes",
                    "detail": params.get("reason"), "toolName": "apply_patch", "input": params,
                    "options": [
                        { "id": "allow", "label": "Allow" },
                        { "id": "allow_session", "label": "Allow for session" },
                        { "id": "deny", "label": "Deny" },
                        { "id": "abort", "label": "Deny and stop" }
                    ],
                    "resolved": false,
                }),
            ),
            "item/permissions/requestApproval" => EventDraft::new(
                &event_id,
                "approval_request",
                &ts,
                json!({
                    "requestId": request_id, "kind": "tool", "title": "Grant permissions",
                    "detail": params.get("reason"), "cwd": params.get("cwd"),
                    "toolName": "permissions", "input": params.get("permissions").cloned().unwrap_or(Value::Null),
                    "options": [
                        { "id": "allow", "label": "Allow" },
                        { "id": "allow_session", "label": "Allow for session" },
                        { "id": "deny", "label": "Deny" }
                    ],
                    "resolved": false,
                }),
            ),
            "item/tool/requestUserInput" => {
                is_question = true;
                let questions: Vec<Value> = params
                    .get("questions")
                    .and_then(|q| q.as_array())
                    .cloned()
                    .unwrap_or_default()
                    .into_iter()
                    .enumerate()
                    .map(|(i, q)| {
                        let id = q.get("id").cloned().unwrap_or_else(|| json!(i.to_string()));
                        let opts = q
                            .get("options")
                            .and_then(|o| o.as_array())
                            .cloned()
                            .unwrap_or_default();
                        let is_other = q
                            .get("isOther")
                            .and_then(|b| b.as_bool())
                            .unwrap_or(opts.is_empty());
                        let is_secret =
                            q.get("isSecret").and_then(|b| b.as_bool()).unwrap_or(false);
                        json!({
                            "id": id,
                            "header": q.get("header"),
                            "question": q.get("question"),
                            "multiSelect": q.get("multiSelect").and_then(Value::as_bool).unwrap_or(false),
                            "allowOther": is_other,
                            "isSecret": is_secret,
                            "options": opts,
                        })
                    })
                    .collect();
                EventDraft::new(
                    &event_id,
                    "question",
                    &ts,
                    json!({
                        "requestId": request_id,
                        "questions": questions,
                        "cancellable": true,
                        "resolved": false,
                    }),
                )
            }
            "mcpServer/elicitation/request" => {
                is_question = true;
                let questions = elicitation_to_questions(&params);
                let mut data = json!({
                    "requestId": request_id,
                    "questions": questions,
                    "cancellable": true,
                    "elicitationMode": params.get("mode"),
                    "serverName": params.get("serverName"),
                    "resolved": false,
                });
                if let Some(u) = elicitation_url(&params) {
                    data["url"] = json!(u);
                }
                EventDraft::new(&event_id, "question", &ts, data)
            }
            _ if self.remote.is_some() => {
                // Codex Desktop owns the connection and answers other internal requests itself.
                return;
            }
            _ => {
                // Dynamic tool calls or auth tokens are not supported from the phone:
                // answer explicitly so the turn does not hang.
                let reply = json!({ "id": rpc_id, "error": { "code": -32601, "message": "not supported by SwitchTether" } });
                let _ = self.write(&reply).await;
                self.system(&format!("Declined unsupported request: {method}"));
                return;
            }
        };
        if let Some(pid) = parent_id {
            draft.parent_id = Some(pid);
        }
        self.pending.lock().insert(
            request_id,
            PendingApproval {
                rpc_id,
                method: method.to_string(),
                params,
                event_id,
            },
        );
        self.log.lock().upsert(draft, true);
        self.set_status("waiting");
        self.publish_status(
            if is_question {
                "waiting_input"
            } else {
                "waiting_approval"
            },
            None,
        );
    }

    fn item_events(&self, item: &Value, turn_id: Option<&str>, done: bool) -> Vec<EventDraft> {
        let ts = crate::util::now_rfc3339();
        let id = s(item, "id").unwrap_or_default();
        let eid = format!("cx-item-{id}");
        let mut out = Vec::new();
        // Drafts that keep the turn they already have (a sub-agent row updated by a later call).
        let mut kept_turn: Vec<EventDraft> = Vec::new();
        let mut push = |mut d: EventDraft| {
            d.turn_id = turn_id.map(|t| t.to_string());
            out.push(d);
        };
        match item.get("type").and_then(|t| t.as_str()) {
            Some("userMessage") => {
                let mut text: Vec<String> = Vec::new();
                let mut image_items: Vec<Value> = Vec::new();
                if let Some(arr) = item.get("content").and_then(|c| c.as_array()) {
                    for x in arr {
                        match x.get("type").and_then(|t| t.as_str()) {
                            Some("text") => {
                                if let Some(t) = s(x, "text") {
                                    text.push(t);
                                }
                            }
                            Some("image") => {
                                if let Some(url) = s(x, "url") {
                                    image_items.push(json!({ "kind": "url", "url": url }));
                                }
                            }
                            Some("localImage") => {
                                if let Some(path) = s(x, "path") {
                                    image_items.push(json!({ "kind": "local", "path": path }));
                                }
                            }
                            Some("skill") => {
                                if let Some(n) = s(x, "name") {
                                    text.push(format!("/{n}"));
                                }
                            }
                            Some("mention") => {
                                if let Some(n) = s(x, "name") {
                                    text.push(format!("@{n}"));
                                }
                            }
                            _ => {
                                if let Some(t) = s(x, "text") {
                                    text.push(t);
                                }
                            }
                        }
                    }
                }
                // Context Codex Desktop wraps around the request (mentioned files, IDE state)
                // and injected records are not what the user typed.
                let joined = text.join("\n");
                let shown = super::rollout::user_display_text(&joined);
                if shown.is_none() && image_items.is_empty() {
                    return Vec::new();
                }
                push(EventDraft::new(
                    eid,
                    "user_message",
                    ts,
                    json!({
                        "text": shown.unwrap_or_default(),
                        "images": image_items.len(),
                        "imageItems": image_items,
                        "source": "phone"
                    }),
                ));
            }
            Some("agentMessage") => {
                let mut d = EventDraft::new(
                    eid,
                    "assistant_message",
                    ts,
                    json!({ "text": s(item, "text").unwrap_or_default(), "model": self.state.lock().model }),
                );
                d.streaming = !done;
                push(d);
            }
            Some("reasoning") => {
                let content: Vec<String> = item
                    .get("content")
                    .and_then(|c| c.as_array())
                    .map(|a| {
                        a.iter()
                            .filter_map(|x| x.as_str().map(String::from))
                            .collect()
                    })
                    .unwrap_or_default();
                let summary: Vec<String> = item
                    .get("summary")
                    .and_then(|c| c.as_array())
                    .map(|a| {
                        a.iter()
                            .filter_map(|x| x.as_str().map(String::from))
                            .collect()
                    })
                    .unwrap_or_default();
                let (text, is_summary) = if content.is_empty() {
                    (summary.join("\n\n"), true)
                } else {
                    (content.join("\n\n"), false)
                };
                if !text.trim().is_empty() {
                    let mut d = EventDraft::new(
                        eid,
                        "reasoning",
                        ts,
                        json!({ "text": text, "summary": is_summary }),
                    );
                    d.streaming = !done;
                    push(d);
                } else if done {
                    // Streamed deltas (if any) stay as they were, no longer streaming.
                    if let Some(e) = self.log.lock().get(&eid).cloned() {
                        let mut d = EventDraft::new(eid, "reasoning", e.ts, e.data);
                        d.streaming = false;
                        push(d);
                    }
                }
            }
            // Plan mode's proposed plan: Markdown written by the model (the checklist Codex keeps
            // while it works is `turn/plan/updated`).
            Some("plan") => {
                let text = s(item, "text").unwrap_or_default();
                if !text.trim().is_empty() {
                    let mut d = EventDraft::new(
                        eid,
                        "assistant_message",
                        ts,
                        json!({ "text": text, "proposedPlan": true, "model": self.state.lock().model }),
                    );
                    d.streaming = !done;
                    push(d);
                }
            }
            Some("commandExecution") => {
                let status = match item.get("status").and_then(|x| x.as_str()) {
                    Some("completed") => "completed",
                    Some("failed") => "failed",
                    Some("declined") => "denied",
                    _ => "running",
                };
                let cmd = s(item, "command").unwrap_or_default();
                let mut data = json!({
                    "callId": id, "name": "shell", "title": preview(cmd.lines().next().unwrap_or(""), 200).0,
                    "category": "shell", "input": { "command": cmd, "cwd": item.get("cwd") }, "status": status,
                });
                // A read / listing / search runs through the shell: shown like Claude Code's
                // Read / LS / Grep, with the files it read openable.
                if let Some(c) = super::commands::classify(
                    item.get("commandActions"),
                    &cmd,
                    s(item, "cwd").as_deref(),
                ) {
                    c.apply(&mut data);
                }
                push(EventDraft::new(&eid, "tool_call", &ts, data));
                let streamed = self.output.lock().get(&id).cloned();
                let text = s(item, "aggregatedOutput").or(streamed).unwrap_or_default();
                if done || !text.is_empty() {
                    let (p, truncated) = preview(&text, PREVIEW_LIMIT);
                    let mut d = EventDraft::new(
                        format!("res:{id}"),
                        "tool_result",
                        &ts,
                        json!({
                            "callId": id, "isError": status == "failed", "text": p, "truncated": truncated,
                            "bytes": text.len(), "exitCode": item.get("exitCode"),
                        }),
                    );
                    d.streaming = !done;
                    if truncated {
                        d.payload = Some(text);
                    }
                    push(d);
                }
            }
            Some("fileChange") => {
                let status = match item.get("status").and_then(|x| x.as_str()) {
                    Some("completed") => "completed",
                    Some("failed") => "failed",
                    Some("declined") => "denied",
                    _ => "running",
                };
                let changes = item
                    .get("changes")
                    .and_then(|c| c.as_array())
                    .cloned()
                    .unwrap_or_default();
                let paths: Vec<String> = changes.iter().filter_map(|c| s(c, "path")).collect();
                push(EventDraft::new(
                    &eid,
                    "tool_call",
                    &ts,
                    json!({
                        "callId": id, "name": "apply_patch",
                        "title": edit_title(&paths), "category": "edit",
                        "input": { "files": paths },
                        "files": paths.iter().map(|p| json!({ "path": p })).collect::<Vec<_>>(),
                        "status": status,
                    }),
                ));
                for (i, c) in changes.iter().enumerate() {
                    let diff = s(c, "diff").unwrap_or_default();
                    let (a, d) = diff_stats(&diff);
                    let kind = match c
                        .get("kind")
                        .and_then(|k| k.get("type"))
                        .and_then(|t| t.as_str())
                    {
                        Some("add") => "add",
                        Some("delete") => "delete",
                        _ => "modify",
                    };
                    let (dp, trunc) = preview(&diff, 256 * 1024);
                    push(EventDraft::new(
                        format!("fc:{id}:{i}"),
                        "file_change",
                        &ts,
                        json!({
                            "callId": id, "path": s(c, "path"), "change": kind, "diff": dp,
                            "additions": a, "deletions": d, "truncated": trunc,
                        }),
                    ));
                }
            }
            Some("mcpToolCall") => {
                let status = match item.get("status").and_then(|x| x.as_str()) {
                    Some("completed") => "completed",
                    Some("failed") => "failed",
                    _ => "running",
                };
                let server = s(item, "server").unwrap_or_default();
                let tool = s(item, "tool").unwrap_or_default();
                push(EventDraft::new(
                    &eid,
                    "tool_call",
                    &ts,
                    json!({
                        "callId": id, "name": format!("mcp__{server}__{tool}"), "title": format!("{server} · {tool}"),
                        "category": "mcp", "input": item.get("arguments"), "status": status, "server": server, "tool": tool,
                    }),
                ));
                if done {
                    let text = mcp_result_text(item);
                    let (p, truncated) = preview(&text, PREVIEW_LIMIT);
                    push(EventDraft::new(
                        format!("res:{id}"),
                        "tool_result",
                        &ts,
                        json!({
                            "callId": id, "isError": status == "failed", "text": p, "truncated": truncated, "bytes": text.len(),
                        }),
                    ));
                }
            }
            Some("collabAgentToolCall") => {
                let status = match item.get("status").and_then(|x| x.as_str()) {
                    Some("completed") => "completed",
                    Some("failed") => "failed",
                    _ => "running",
                };
                let tool = s(item, "tool").unwrap_or_else(|| "spawnAgent".into());
                let prompt = s(item, "prompt");
                let title = prompt
                    .as_deref()
                    .and_then(|p| p.lines().next())
                    .map(|l| preview(l, 120).0)
                    .unwrap_or_else(|| tool.clone());
                push(EventDraft::new(
                    &eid,
                    "tool_call",
                    &ts,
                    json!({
                        "callId": id, "name": tool, "title": title,
                        "category": "task", "input": item, "status": status,
                    }),
                ));
                let receivers: Vec<String> = item
                    .get("receiverThreadIds")
                    .and_then(|r| r.as_array())
                    .map(|a| {
                        a.iter()
                            .filter_map(|x| x.as_str().map(String::from))
                            .collect()
                    })
                    .unwrap_or_default();
                let states = item.get("agentsStates").and_then(|s| s.as_object());
                let spawn = tool == "spawnAgent";
                for tid in receivers {
                    let st = states.and_then(|m| m.get(&tid));
                    let sa_status = match st.and_then(|v| s(v, "status")).as_deref() {
                        Some("completed") | Some("shutdown") => Some("completed"),
                        Some("errored") | Some("notFound") => Some("failed"),
                        Some("interrupted") => Some("interrupted"),
                        Some("running") | Some("pendingInit") => Some("running"),
                        _ if spawn && status == "failed" => Some("failed"),
                        _ if spawn => Some("running"),
                        _ => None,
                    };
                    let message = st
                        .and_then(|v| s(v, "message"))
                        .filter(|m| !m.trim().is_empty());
                    let sa_id = format!("cx-sa-{tid}");
                    // Only the spawn defines the row (what it was asked, where it hangs); a later
                    // wait / sendInput / closeAgent on the same child only updates its state.
                    let existing = self.log.lock().get(&sa_id).cloned();
                    let mut data = existing
                        .as_ref()
                        .map(|e| e.data.clone())
                        .unwrap_or_else(|| json!({ "agentId": tid }));
                    if spawn {
                        if prompt.is_some() {
                            data["description"] = json!(prompt);
                        }
                        data["callId"] = json!(id);
                        if let Some(m) = s(item, "model") {
                            data["model"] = json!(m);
                        }
                    }
                    if let Some(st) = sa_status {
                        data["status"] = json!(st);
                    }
                    if let Some(m) = &message {
                        data["message"] = json!(m);
                        if sa_status == Some("completed") {
                            data["result"] = json!(preview(m, 4000).0);
                        }
                    }
                    let mut sa = EventDraft::new(
                        &sa_id,
                        "subagent",
                        existing.as_ref().map_or(ts.clone(), |e| e.ts.clone()),
                        data,
                    );
                    sa.parent_id = match &existing {
                        Some(e) if !spawn || e.parent_id.is_some() => e.parent_id.clone(),
                        _ => Some(id.clone()),
                    };
                    match existing.and_then(|e| e.turn_id) {
                        // Stays in the turn that spawned it (`push` would move it to this one).
                        Some(t) => {
                            sa.turn_id = Some(t);
                            kept_turn.push(sa);
                        }
                        None => push(sa),
                    }
                }
            }
            // Multi-agent v2: the only sign of a child in its parent (spawns make no
            // collabAgentToolCall). Sent as item/started and item/completed: idempotent.
            Some("subAgentActivity") => {
                let (Some(tid), kind) = (s(item, "agentThreadId"), s(item, "kind")) else {
                    return out;
                };
                let status = match kind.as_deref() {
                    Some("started") => Some("running"),
                    Some("completed") => Some("completed"),
                    Some("interrupted") => Some("interrupted"),
                    _ => None, // interacted: mail sent to it
                };
                let sa_id = format!("cx-sa-{tid}");
                let existing = self.log.lock().get(&sa_id).cloned();
                if existing.is_none() && kind.as_deref() != Some("started") {
                    return out;
                }
                let path = s(item, "agentPath");
                let mut data = existing.as_ref().map(|e| e.data.clone()).unwrap_or_else(|| {
                    json!({
                        "agentId": tid,
                        "agentPath": path,
                        "description": path.as_deref().map(|p| p.rsplit('/').next().unwrap_or(p)),
                        "background": true,
                    })
                });
                if let Some(m) = s(item, "model") {
                    data["model"] = json!(m);
                }
                // A late `started` (it can arrive after the child's own completion) never
                // reopens a finished child.
                let finished = matches!(
                    data.get("status").and_then(Value::as_str),
                    Some("completed" | "failed" | "interrupted")
                );
                if let Some(st) =
                    status.filter(|_| !(finished && kind.as_deref() == Some("started")))
                {
                    data["status"] = json!(st);
                }
                let mut sa = EventDraft::new(
                    &sa_id,
                    "subagent",
                    existing.as_ref().map_or(ts.clone(), |e| e.ts.clone()),
                    data,
                );
                match existing {
                    Some(e) => {
                        sa.parent_id = e.parent_id;
                        sa.turn_id = e.turn_id;
                        kept_turn.push(sa);
                    }
                    None => push(sa),
                }
            }
            Some("imageView") => {
                let path = s(item, "path").unwrap_or_default();
                push(EventDraft::new(
                    eid,
                    "assistant_message",
                    ts,
                    json!({
                        "text": format!("Viewed image: `{}`", path),
                        "imageItems": [{ "kind": "local", "path": path }],
                    }),
                ));
            }
            Some("imageGeneration") => {
                let status = s(item, "status").unwrap_or_default();
                let saved = s(item, "savedPath");
                let prompt = s(item, "revisedPrompt").unwrap_or_default();
                let mut imgs = Vec::new();
                if let Some(p) = &saved {
                    imgs.push(json!({ "kind": "local", "path": p }));
                }
                push(EventDraft::new(
                    eid,
                    "assistant_message",
                    ts,
                    json!({
                        "text": if prompt.is_empty() { format!("Generated image ({status})") } else { prompt },
                        "imageItems": imgs,
                    }),
                ));
            }
            Some("webSearch") => push(EventDraft::new(
                eid,
                "tool_call",
                ts,
                json!({
                    "callId": id, "name": "web_search", "title": s(item, "query").unwrap_or_else(|| "web search".into()),
                    "category": "web", "input": item, "status": if done { "completed" } else { "running" },
                }),
            )),
            Some("contextCompaction") => push(EventDraft::new(
                eid,
                "system",
                ts,
                json!({ "subtype": "compacted", "text": "Conversation compacted" }),
            )),
            Some("dynamicToolCall") => {
                let status = match item.get("status").and_then(|x| x.as_str()) {
                    Some("completed") => "completed",
                    Some("failed") => "failed",
                    _ => "running",
                };
                let tool = s(item, "tool").unwrap_or_default();
                let name = match s(item, "namespace") {
                    Some(ns) => format!("{ns}.{tool}"),
                    None => tool.clone(),
                };
                push(EventDraft::new(
                    &eid,
                    "tool_call",
                    &ts,
                    json!({
                        "callId": id, "name": name, "title": tool, "category": "other",
                        "input": item.get("arguments"), "status": status,
                    }),
                ));
                if done {
                    let text = item
                        .get("contentItems")
                        .and_then(Value::as_array)
                        .map(|a| {
                            a.iter()
                                .filter_map(|c| s(c, "text"))
                                .collect::<Vec<_>>()
                                .join("\n")
                        })
                        .unwrap_or_default();
                    let (p, truncated) = preview(&text, PREVIEW_LIMIT);
                    push(EventDraft::new(
                        format!("res:{id}"),
                        "tool_result",
                        &ts,
                        json!({
                            "callId": id, "isError": status == "failed", "text": p,
                            "truncated": truncated, "bytes": text.len(),
                        }),
                    ));
                }
            }
            Some("enteredReviewMode") => push(EventDraft::new(
                eid,
                "system",
                ts,
                json!({ "subtype": "review", "text": format!("Review started: {}", s(item, "review").unwrap_or_default()) }),
            )),
            // The review's findings, written by the reviewer.
            Some("exitedReviewMode") => push(EventDraft::new(
                eid,
                "assistant_message",
                ts,
                json!({ "text": s(item, "review").unwrap_or_default(), "model": self.state.lock().model }),
            )),
            Some("hookPrompt") => {
                let text = item
                    .get("fragments")
                    .and_then(Value::as_array)
                    .map(|a| {
                        a.iter()
                            .filter_map(|f| s(f, "text"))
                            .collect::<Vec<_>>()
                            .join("\n")
                    })
                    .unwrap_or_default();
                if !text.trim().is_empty() {
                    push(EventDraft::new(
                        eid,
                        "system",
                        ts,
                        json!({ "subtype": "hook", "text": crate::redact::redact_text(&text) }),
                    ));
                }
            }
            // Output of a call shown by its own row; an idle wait has nothing to show.
            Some("functionCallOutput" | "sleep") => {}
            Some(other) => push(EventDraft::new(
                eid,
                "system",
                ts,
                json!({ "subtype": other, "text": format!("[{other}]") }),
            )),
            None => {}
        }
        out.extend(kept_turn);
        out
    }

    pub(crate) fn on_subagent_started(&self, tid: &str, thread: &Value) {
        let desc = s(thread, "agentNickname")
            .or_else(|| s(thread, "agentRole"))
            .or_else(|| s(thread, "name"))
            .or_else(|| s(thread, "preview").filter(|p| !p.is_empty()));
        let mut d = EventDraft::new(
            format!("cx-sa-{tid}"),
            "subagent",
            crate::util::now_rfc3339(),
            json!({
                "agentId": tid,
                "agentType": s(thread, "agentRole").or_else(|| s(thread, "agentNickname")),
                "description": desc,
                "status": "running",
            }),
        );
        // collabAgentToolCall may have created the row already: keep what the spawn said (its
        // prompt, parent, turn) and add what only the thread knows.
        let mut log = self.log.lock();
        if let Some(existing) = log.get(&d.id) {
            let mut data = existing.data.clone();
            for k in ["agentType", "description"] {
                if data.get(k).is_none_or(Value::is_null) {
                    data[k] = d.data[k].clone();
                }
            }
            if data.get("status").is_none_or(Value::is_null) {
                data["status"] = json!("running");
            }
            d.data = data;
            d.parent_id = existing.parent_id.clone();
            d.turn_id = existing.turn_id.clone();
            d.ts = existing.ts.clone();
        }
        log.upsert(d, true);
    }

    pub(crate) fn on_subagent_notification(&self, tid: &str, method: &str, p: Value) {
        let sa_id = format!("cx-sa-{tid}");
        match method {
            "thread/status/changed" => {
                let ty = p.pointer("/status/type").and_then(|t| t.as_str());
                let mapped = match ty {
                    Some("idle") => Some("completed"),
                    Some("systemError") => Some("failed"),
                    Some("active") => Some("running"),
                    _ => None,
                };
                if let Some(st) = mapped {
                    self.log.lock().update(&sa_id, true, |e| {
                        e.data["status"] = json!(st);
                    });
                }
            }
            "turn/completed" => {
                let status = p
                    .get("turn")
                    .and_then(|t| s(t, "status"))
                    .unwrap_or_default();
                let mapped = match status.as_str() {
                    "interrupted" => "interrupted",
                    "failed" => "failed",
                    _ => "completed",
                };
                self.log.lock().update(&sa_id, true, |e| {
                    e.data["status"] = json!(mapped);
                });
            }
            "item/agentMessage/delta" => {
                let (Some(item_id), Some(delta)) = (s(&p, "itemId"), s(&p, "delta")) else {
                    return;
                };
                let eid = scope_child_id(&format!("cx-item-{item_id}"), tid);
                let mut log = self.log.lock();
                let existing = log
                    .get(&eid)
                    .and_then(|e| e.data["text"].as_str())
                    .unwrap_or("");
                let mut draft = EventDraft::new(
                    &eid,
                    "assistant_message",
                    crate::util::now_rfc3339(),
                    json!({ "text": format!("{existing}{delta}") }),
                );
                draft.parent_id = Some(sa_id);
                draft.turn_id = s(&p, "turnId");
                draft.streaming = true;
                log.upsert(draft, true);
            }
            // Item ids are per-thread counters: a child's output delta would land on the
            // parent's item of the same id. The child's completed item carries the output.
            "item/commandExecution/outputDelta" => {}
            "item/started" | "item/completed" => {
                if let Some(item) = p.get("item") {
                    let done = method == "item/completed";
                    let turn_id = s(&p, "turnId");
                    let drafts = self.item_events(item, turn_id.as_deref(), done);
                    let mut log = self.log.lock();
                    for mut d in drafts {
                        d.id = scope_child_id(&d.id, tid);
                        d.parent_id = match d.parent_id.take() {
                            None => Some(sa_id.clone()),
                            Some(p) => Some(scope_child_id(&p, tid)),
                        };
                        log.upsert(d, true);
                    }
                }
            }
            _ => {}
        }
    }

    /// Append streamed text to an event (reasoning), creating it on the first delta.
    fn append_delta(
        &self,
        eid: &str,
        kind: &'static str,
        delta: &str,
        extra: Value,
        turn_id: Option<String>,
    ) {
        let mut log = self.log.lock();
        let mut data = log
            .get(eid)
            .map(|e| e.data.clone())
            .unwrap_or_else(|| json!({ "text": "" }));
        let text = format!("{}{delta}", data["text"].as_str().unwrap_or(""));
        data["text"] = json!(text);
        if let (Some(d), Some(x)) = (data.as_object_mut(), extra.as_object()) {
            for (k, v) in x {
                d.entry(k.clone()).or_insert(v.clone());
            }
        }
        let mut d = EventDraft::new(eid, kind, crate::util::now_rfc3339(), data);
        d.streaming = true;
        d.turn_id = turn_id;
        log.upsert(d, true);
    }

    /// An informational row (warnings, reroutes): redacted.
    fn notice(&self, subtype: &str, text: &str) {
        self.log.lock().upsert(
            EventDraft::new(
                format!("sys:{}", crate::util::new_id()),
                "system",
                crate::util::now_rfc3339(),
                json!({ "subtype": subtype, "text": crate::redact::redact_text(text) }),
            ),
            true,
        );
    }

    /// `thread/settings/updated`: model, effort and permission mode as the thread has them now
    /// (also when Codex Desktop changed them).
    fn on_thread_settings(&self, p: &Value) {
        let Some(t) = p.get("threadSettings") else {
            return;
        };
        let mut st = self.state.lock();
        if let Some(m) = s(t, "model") {
            st.model = Some(m);
        }
        st.effort = s(t, "effort").or(st.effort.take());
        if let Some(sb) = t
            .get("sandboxPolicy")
            .filter(|x| x.get("type").and_then(Value::as_str) == Some("workspaceWrite"))
        {
            st.workspace_sandbox = Some(sb.clone());
        }
        let mode = crate::agents::permission::codex_mode_of(
            t.get("approvalPolicy"),
            t.get("sandboxPolicy"),
            t.get("approvalsReviewer").and_then(Value::as_str),
            t.pointer("/collaborationMode/mode").and_then(Value::as_str),
        );
        st.mode = Some(mode.to_string());
        if st.next_mode.as_deref() == Some(mode) {
            st.next_mode = None;
        }
    }

    fn on_thread_metadata(&self, thread: &Value) {
        if let Some(title) =
            s(thread, "name").and_then(|t| crate::agents::claude::transcript::clean_title(Some(t)))
        {
            self.state.lock().title = Some(title);
            self.system("Conversation title updated");
        }
    }

    pub(crate) fn on_notification(&self, method: &str, p: Value) {
        let turn_id = s(&p, "turnId");
        match method {
            "turn/started" => {
                let tid = p.get("turn").and_then(|t| s(t, "id"));
                self.state.lock().turn_id = tid;
                self.set_status("busy");
                self.publish_status("running", None);
            }
            "turn/completed" => {
                let status = p
                    .get("turn")
                    .and_then(|t| s(t, "status"))
                    .unwrap_or_default();
                self.state.lock().turn_id = None;
                self.set_status("idle");
                self.publish_status(
                    match status.as_str() {
                        "interrupted" => "interrupted",
                        "failed" => "error",
                        _ => "idle",
                    },
                    None,
                );
            }
            "item/started" | "item/completed" => {
                if let Some(item) = p.get("item") {
                    let done = method == "item/completed";
                    let drafts = self.item_events(item, turn_id.as_deref(), done);
                    let mut log = self.log.lock();
                    for d in drafts {
                        log.upsert(d, true);
                    }
                }
            }
            "item/agentMessage/delta" => {
                let (Some(item_id), Some(delta)) = (s(&p, "itemId"), s(&p, "delta")) else {
                    return;
                };
                let eid = format!("cx-item-{item_id}");
                let mut log = self.log.lock();
                let existing = log
                    .get(&eid)
                    .and_then(|e| e.data["text"].as_str().map(String::from))
                    .unwrap_or_default();
                let mut d = EventDraft::new(
                    eid,
                    "assistant_message",
                    crate::util::now_rfc3339(),
                    json!({ "text": existing + &delta }),
                );
                d.streaming = true;
                d.turn_id = turn_id;
                log.upsert(d, true);
            }
            "item/commandExecution/outputDelta" => {
                let (Some(item_id), Some(delta)) = (s(&p, "itemId"), s(&p, "delta")) else {
                    return;
                };
                let mut out = self.output.lock();
                let buf = out.entry(item_id).or_default();
                if buf.len() < 4 * 1024 * 1024 {
                    buf.push_str(&delta);
                }
            }
            "turn/plan/updated" => {
                let items: Vec<Value> = p
                    .get("plan")
                    .and_then(|x| x.as_array())
                    .map(|a| {
                        a.iter()
                            .map(|i| json!({ "text": i.get("step"), "status": plan_status(i.get("status")) }))
                            .collect()
                    })
                    .unwrap_or_default();
                let mut d = EventDraft::new(
                    format!("plan:{}", turn_id.clone().unwrap_or_default()),
                    "plan",
                    crate::util::now_rfc3339(),
                    json!({ "items": items }),
                );
                d.turn_id = turn_id;
                self.log.lock().upsert(d, true);
            }
            "thread/tokenUsage/updated" => {
                let usage = p.get("tokenUsage").cloned().unwrap_or(p.clone());
                let last = usage.get("last").cloned().unwrap_or(Value::Null);
                {
                    let mut st = self.state.lock();
                    if let Some(t) = last.get("totalTokens").and_then(Value::as_u64) {
                        st.context_tokens = Some(t);
                    }
                    if let Some(w) = usage.get("modelContextWindow").and_then(Value::as_u64) {
                        st.context_window = Some(w);
                    }
                }
                let d = EventDraft::new(
                    format!("usage:{}", turn_id.clone().unwrap_or_default()),
                    "usage",
                    crate::util::now_rfc3339(),
                    json!({
                        "inputTokens": last.get("inputTokens"), "outputTokens": last.get("outputTokens"),
                        "cacheReadTokens": last.get("cachedInputTokens"),
                        "cacheWriteTokens": last.get("cacheWriteInputTokens"),
                    }),
                );
                self.log.lock().upsert(d, true);
            }
            "item/reasoning/summaryTextDelta" | "item/reasoning/textDelta" => {
                let (Some(item_id), Some(delta)) = (s(&p, "itemId"), s(&p, "delta")) else {
                    return;
                };
                self.append_delta(
                    &format!("cx-item-{item_id}"),
                    "reasoning",
                    &delta,
                    json!({ "summary": method.ends_with("summaryTextDelta") }),
                    turn_id,
                );
            }
            "item/reasoning/summaryPartAdded" => {
                if let Some(item_id) = s(&p, "itemId") {
                    let eid = format!("cx-item-{item_id}");
                    let has_text = self
                        .log
                        .lock()
                        .get(&eid)
                        .and_then(|e| e.data["text"].as_str().map(|t| !t.is_empty()))
                        .unwrap_or(false);
                    if has_text {
                        self.append_delta(
                            &eid,
                            "reasoning",
                            "\n\n",
                            json!({ "summary": true }),
                            turn_id,
                        );
                    }
                }
            }
            "item/plan/delta" => {
                let (Some(item_id), Some(delta)) = (s(&p, "itemId"), s(&p, "delta")) else {
                    return;
                };
                let eid = format!("cx-item-{item_id}");
                let text = format!(
                    "{}{delta}",
                    self.log
                        .lock()
                        .get(&eid)
                        .and_then(|e| e.data["text"].as_str().map(String::from))
                        .unwrap_or_default()
                );
                let item = json!({ "type": "plan", "id": item_id, "text": text });
                let mut log = self.log.lock();
                for mut d in self.item_events(&item, turn_id.as_deref(), false) {
                    d.streaming = true;
                    log.upsert(d, true);
                }
            }
            "thread/settings/updated" => self.on_thread_settings(&p),
            // Codex reports "cleared" when a thread without a goal is resumed: nothing to say.
            "thread/goal/cleared" if self.log.lock().get("goal:current").is_none() => {}
            "thread/goal/updated" | "thread/goal/cleared" => {
                let text = match p.get("goal") {
                    Some(g) if method == "thread/goal/updated" => format!(
                        "Goal ({}): {}",
                        s(g, "status").unwrap_or_default(),
                        s(g, "objective").unwrap_or_default()
                    ),
                    _ => "Goal cleared".to_string(),
                };
                let d = EventDraft::new(
                    "goal:current",
                    "system",
                    crate::util::now_rfc3339(),
                    json!({ "subtype": "goal", "text": crate::redact::redact_text(&text), "goal": p.get("goal") }),
                );
                self.log.lock().upsert(d, true);
            }
            "model/rerouted" => {
                let text = format!(
                    "Model rerouted: {} → {}",
                    s(&p, "fromModel").unwrap_or_default(),
                    s(&p, "toModel").unwrap_or_default()
                );
                if let Some(m) = s(&p, "toModel") {
                    self.state.lock().model = Some(m);
                }
                self.notice("model_rerouted", &text);
            }
            "warning" | "guardianWarning" => {
                if let Some(m) = s(&p, "message") {
                    self.notice("warning", &m);
                }
            }
            "configWarning" | "deprecationNotice" => {
                let text = [s(&p, "summary"), s(&p, "details")]
                    .into_iter()
                    .flatten()
                    .collect::<Vec<_>>()
                    .join("\n");
                if !text.is_empty() {
                    self.notice("warning", &text);
                }
            }
            "item/autoApprovalReview/completed" => {
                let status = p
                    .pointer("/review/status")
                    .and_then(Value::as_str)
                    .unwrap_or("");
                let rationale = p
                    .pointer("/review/rationale")
                    .and_then(Value::as_str)
                    .unwrap_or("");
                let text = match status {
                    "approved" => format!("Auto review approved. {rationale}"),
                    "denied" => format!("Auto review denied. {rationale}"),
                    other => format!("Auto review: {other}. {rationale}"),
                };
                let id = s(&p, "reviewId").unwrap_or_else(crate::util::new_id);
                let d = EventDraft::new(
                    format!("review:{id}"),
                    "system",
                    crate::util::now_rfc3339(),
                    json!({ "subtype": "auto_review", "text": crate::redact::redact_text(text.trim()), "status": status }),
                );
                self.log.lock().upsert(d, true);
            }
            "skills/changed" => self.state.lock().skills = None,
            "thread/name/updated" => {
                self.state.lock().title = s(&p, "name").or(s(&p, "threadName"));
                self.system("Conversation title updated");
            }
            "serverRequest/resolved" => {
                // Another client (or Codex itself) resolved a request.
                let rid = p.get("requestId").map(|r| match r {
                    Value::String(x) => format!("cx-{x}"),
                    other => format!("cx-{other}"),
                });
                if let Some(rid) = rid {
                    if let Some(pa) = self.pending.lock().remove(&rid) {
                        self.resolved.lock().insert(rid, "other".into());
                        self.log.lock().update(&pa.event_id, true, |e| {
                            e.data["resolved"] = json!(true);
                            e.data["by"] = json!("other");
                        });
                    }
                }
            }
            "error" => {
                let msg = p
                    .get("error")
                    .and_then(|e| s(e, "message"))
                    .or(s(&p, "message"))
                    .unwrap_or_else(|| p.to_string());
                let msg = crate::redact::redact_text(&msg);
                let will_retry = p
                    .get("willRetry")
                    .and_then(|w| w.as_bool())
                    .unwrap_or(false);
                let turn = turn_id.clone().unwrap_or_default();
                let draft = if will_retry {
                    // Transient: one rolling notice per turn instead of an error per attempt.
                    EventDraft::new(
                        format!("retry:{turn}"),
                        "system",
                        crate::util::now_rfc3339(),
                        json!({ "subtype": "retry", "text": msg }),
                    )
                } else {
                    EventDraft::new(
                        format!("err:{}", crate::util::new_id()),
                        "error",
                        crate::util::now_rfc3339(),
                        json!({ "message": msg }),
                    )
                };
                self.log.lock().upsert(draft, true);
            }
            _ => {}
        }
    }

    pub(crate) fn on_exit(&self) {
        {
            let mut st = self.state.lock();
            st.liveness = Liveness::Ended;
            st.status = "completed";
        }
        for (_, tx) in self.replies.lock().drain() {
            let _ = tx.send(Err(json!({ "message": "process exited" })));
        }
        let pending: Vec<(String, PendingApproval)> = self.pending.lock().drain().collect();
        for (rid, p) in pending {
            self.resolved.lock().insert(rid, "agent".into());
            self.log.lock().update(&p.event_id, true, |e| {
                e.data["resolved"] = json!(true);
                e.data["decision"] = json!("cancelled");
                e.data["by"] = json!("agent");
            });
        }
        self.publish_status("completed", Some("codex app-server exited"));
    }
}

#[async_trait]
impl ManagedSession for ManagedCodex {
    fn agent(&self) -> &'static str {
        "codex"
    }
    fn log(&self) -> Arc<Mutex<SessionLog>> {
        self.log.clone()
    }
    fn cwd(&self) -> PathBuf {
        self.options.cwd.clone()
    }
    fn native_session_id(&self) -> Option<String> {
        self.state.lock().thread_id.clone()
    }
    fn status(&self) -> &'static str {
        self.state.lock().status
    }
    fn liveness(&self) -> Liveness {
        self.state.lock().liveness
    }
    fn created_at_ms(&self) -> i64 {
        self.state.lock().created_at_ms
    }
    fn last_active_at_ms(&self) -> i64 {
        self.state.lock().last_active_at_ms
    }
    fn effort(&self) -> Option<String> {
        ManagedCodex::effort(self)
    }
    fn can_change_model(&self) -> bool {
        // Also a thread of Codex Desktop (opt-in hook): the choice goes with the phone's next
        // `turn/start`, which Codex applies to that turn and the following ones (Desktop learns
        // of it through `thread/settings/updated`).
        true
    }
    async fn models(&self) -> Value {
        self.list_models().await.unwrap_or(Value::Null)
    }
    async fn set_model(&self, model: Option<&str>) -> ApiResult<()> {
        ManagedCodex::set_model(self, model);
        Ok(())
    }
    async fn set_effort(&self, effort: Option<&str>) -> ApiResult<()> {
        ManagedCodex::set_effort(self, effort);
        Ok(())
    }
    fn model(&self) -> Option<String> {
        self.state.lock().model.clone()
    }
    fn title(&self) -> Option<String> {
        self.state.lock().title.clone()
    }
    fn has_pending_requests(&self) -> bool {
        !self.pending.lock().is_empty()
    }
    fn supports_images(&self) -> bool {
        true
    }

    async fn send_message(&self, text: &str) -> ApiResult<()> {
        self.send_message_with_images(text, &[]).await
    }

    async fn send_message_with_images(
        &self,
        text: &str,
        images: &[crate::agents::managed::ImageAttachment],
    ) -> ApiResult<()> {
        if self.liveness() != Liveness::Live {
            return Err(ApiError::conflict("codex app-server has exited"));
        }
        let mut inputs: Vec<Value> = Vec::new();
        let mut text = text.to_string();
        match self.slash_command(&text).await {
            Some(PhoneCommand::Done(r)) => return r,
            Some(PhoneCommand::Skill { name, path, args }) => {
                inputs.push(json!({ "type": "skill", "name": name, "path": path }));
                text = args;
            }
            Some(PhoneCommand::Prompt(t)) => text = t,
            None => {}
        }
        self.start_or_steer(&text, inputs, images).await
    }

    async fn interrupt(&self) -> ApiResult<()> {
        ManagedCodex::interrupt(self).await
    }

    async fn respond(
        &self,
        request_id: &str,
        decision: &str,
        message: Option<String>,
        updated_input: Option<Value>,
        answers: Option<Value>,
        by: &str,
    ) -> ApiResult<Value> {
        ManagedCodex::respond(
            self,
            request_id,
            decision,
            message,
            updated_input,
            answers,
            by,
        )
        .await
    }

    fn kill(&self) {
        ManagedCodex::kill(self)
    }
    fn origin(&self) -> &'static str {
        self.origin_name()
    }
    fn permission_mode(&self) -> Option<String> {
        let st = self.state.lock();
        st.next_mode.clone().or_else(|| st.mode.clone())
    }
    fn can_change_permission_mode(&self) -> bool {
        true
    }
    async fn set_permission_mode(&self, mode: &str) -> ApiResult<String> {
        ManagedCodex::set_permission_mode(self, mode)
    }
    fn session_tools(&self) -> &'static [&'static str] {
        &[
            "commands",
            "permission_mode",
            "context_usage",
            "mcp_status",
            "rename",
        ]
    }
    async fn session_tool(&self, action: &str, p: &Value) -> ApiResult<Value> {
        ManagedCodex::session_tool(self, action, p).await
    }
}

/// What Codex Desktop chose for one of its server requests (`result` of its reply; `None` for an
/// error reply): the phone's decision id and, for questions, the answers by question id.
fn desktop_choice(method: &str, result: Option<&Value>) -> (&'static str, Option<Value>) {
    let Some(r) = result else {
        return ("cancel", None);
    };
    match method {
        "item/tool/requestUserInput" => match r.get("answers").and_then(Value::as_object) {
            Some(m) => (
                "answer",
                Some(Value::Object(
                    m.iter()
                        .map(|(k, v)| (k.clone(), v.get("answers").cloned().unwrap_or(v.clone())))
                        .collect(),
                )),
            ),
            None => ("cancel", None),
        },
        "mcpServer/elicitation/request" => match r.get("action").and_then(Value::as_str) {
            Some("accept") => (
                "answer",
                r.get("content").filter(|c| c.is_object()).cloned(),
            ),
            Some("decline") => ("deny", None),
            _ => ("cancel", None),
        },
        "item/permissions/requestApproval" => {
            let granted = r
                .get("permissions")
                .and_then(Value::as_object)
                .is_some_and(|m| !m.is_empty());
            match (granted, r.get("scope").and_then(Value::as_str)) {
                (false, _) => ("deny", None),
                (true, Some("session")) => ("allow_session", None),
                (true, _) => ("allow", None),
            }
        }
        _ => match r.get("decision") {
            Some(Value::String(d)) => (
                match d.as_str() {
                    "accept" => "allow",
                    "acceptForSession" => "allow_session",
                    "decline" => "deny",
                    _ => "abort",
                },
                None,
            ),
            // Accepted together with a rule (exec policy / network amendment).
            Some(Value::Object(_)) => ("allow_session", None),
            _ => ("cancel", None),
        },
    }
}

/// What a phone message starting with `/` turned out to be.
enum PhoneCommand {
    /// A Codex command, already run through its own app-server request.
    Done(ApiResult<()>),
    /// A skill: sent as a `skill` input item followed by the rest of the message.
    Skill {
        name: String,
        path: String,
        args: String,
    },
    /// Text to send as an ordinary message after the command did its part (`/plan <prompt>`).
    Prompt(String),
}

/// The default workspace-write policy for `turn/start`, when Codex has not reported its own.
fn default_workspace_sandbox() -> Value {
    json!({
        "type": "workspaceWrite", "writableRoots": [], "networkAccess": false,
        "excludeTmpdirEnvVar": false, "excludeSlashTmp": false,
    })
}

/// `turn/start` `sandboxPolicy` for a permission mode.
fn sandbox_policy(mode: &str, workspace: Option<&Value>) -> Value {
    match crate::agents::permission::codex_settings(mode).1 {
        "read-only" => json!({ "type": "readOnly", "networkAccess": false }),
        "danger-full-access" => json!({ "type": "dangerFullAccess" }),
        _ => workspace.cloned().unwrap_or_else(default_workspace_sandbox),
    }
}

/// Codex's own slash commands the phone can run (`/name args`, like the TUI). Skills of the
/// session's folder are listed after them.
const CODEX_COMMANDS: &[(&str, &str, &str)] = &[
    ("compact", "Summarize the conversation to free context.", ""),
    (
        "review",
        "Review the uncommitted changes, or follow your own instructions.",
        "[instructions | branch <name> | commit <sha>]",
    ),
    (
        "goal",
        "Set a goal Codex keeps working toward; clear, pause or resume it.",
        "<objective> | clear | pause | resume",
    ),
    (
        "plan",
        "Switch to plan mode (and send a prompt).",
        "[prompt]",
    ),
    ("rename", "Rename the conversation.", "<title>"),
    (
        "status",
        "Model, effort, permission mode and context usage.",
        "",
    ),
    ("mcp", "MCP servers and their state.", ""),
];

impl ManagedCodex {
    /// Start a turn, or steer the running one with more input.
    async fn start_or_steer(
        &self,
        text: &str,
        mut inputs: Vec<Value>,
        images: &[crate::agents::managed::ImageAttachment],
    ) -> ApiResult<()> {
        let thread_id = self.thread_id()?;
        if !text.trim().is_empty() {
            inputs.push(json!({ "type": "text", "text": text, "text_elements": [] }));
        }
        for img in images {
            inputs.push(json!({
                "type": "image",
                "url": format!("data:{};base64,{}", img.media_type, img.data_base64),
            }));
        }
        let input = Value::Array(inputs);
        let current_turn = self.state.lock().turn_id.clone();
        match current_turn {
            // A turn is running: steer it with additional input.
            Some(turn) => {
                self.request(
                    "turn/steer",
                    json!({ "threadId": thread_id, "expectedTurnId": turn, "input": input }),
                    Duration::from_secs(30),
                )
                .await?;
            }
            None => {
                let mut params = json!({ "threadId": thread_id, "input": input });
                let mode_sent = self.turn_overrides(&mut params);
                if let Err(e) = self
                    .request("turn/start", params, Duration::from_secs(60))
                    .await
                {
                    // The chosen mode was not applied: keep it for the next turn.
                    if let Some(m) = mode_sent {
                        let mut st = self.state.lock();
                        st.next_mode.get_or_insert(m);
                    }
                    return Err(e);
                }
                self.set_status("busy");
            }
        }
        Ok(())
    }

    /// What the next `turn/start` carries besides its input: a model / effort / permission mode
    /// chosen from the phone (Codex keeps them for the following turns too) and plan mode, a
    /// collaboration mode set per turn. Returns the permission mode it applied, if any.
    fn turn_overrides(&self, params: &mut Value) -> Option<String> {
        let mut st = self.state.lock();
        if let Some(m) = &st.next_model {
            params["model"] = json!(m);
        }
        if let Some(e) = &st.effort {
            params["effort"] = json!(e);
        }
        let before = st.mode.clone();
        let applied = st.next_mode.take();
        if let Some(mode) = &applied {
            let (approval, _, reviewer, _) = crate::agents::permission::codex_settings(mode);
            params["approvalPolicy"] = json!(approval);
            params["approvalsReviewer"] = json!(reviewer);
            params["sandboxPolicy"] = sandbox_policy(mode, st.workspace_sandbox.as_ref());
            st.mode = Some(mode.clone());
        }
        let plan = st.mode.as_deref() == Some("plan");
        let leaving_plan = applied.is_some() && before.as_deref() == Some("plan") && !plan;
        if let (true, Some(model)) = (plan || leaving_plan, st.model.clone()) {
            params["collaborationMode"] = json!({
                "mode": if plan { "plan" } else { "default" },
                "settings": { "model": model, "reasoning_effort": st.effort, "developer_instructions": null },
            });
        }
        applied
    }

    /// Choose the permission mode for the following turns (applied with the next `turn/start`).
    pub fn set_permission_mode(&self, mode: &str) -> ApiResult<String> {
        let mode = crate::agents::permission::normalize("codex", mode)
            .ok_or_else(|| ApiError::validation("unknown permission mode for Codex"))?;
        let mut st = self.state.lock();
        st.next_mode = (st.mode.as_deref() != Some(mode)).then(|| mode.to_string());
        Ok(mode.to_string())
    }

    async fn interrupt(&self) -> ApiResult<()> {
        let thread_id = self.thread_id()?;
        let Some(turn) = self.state.lock().turn_id.clone() else {
            return Err(ApiError::conflict("no turn is running"));
        };
        self.request(
            "turn/interrupt",
            json!({ "threadId": thread_id, "turnId": turn }),
            Duration::from_secs(15),
        )
        .await
        .map(|_| ())
    }

    async fn respond(
        &self,
        request_id: &str,
        decision: &str,
        _message: Option<String>,
        _updated_input: Option<Value>,
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
        let reply_msg = match pending.method.as_str() {
            "item/tool/requestUserInput" => match decision {
                "answer" | "allow" => {
                    let ans_map = answers.as_ref().and_then(|a| a.as_object());
                    let mut out = serde_json::Map::new();
                    if let Some(m) = ans_map {
                        for (k, v) in m {
                            let arr: Vec<String> = match v {
                                Value::Array(a) => a
                                    .iter()
                                    .filter_map(|x| x.as_str().map(String::from))
                                    .collect(),
                                Value::String(s) => {
                                    if s.is_empty() {
                                        vec![]
                                    } else {
                                        vec![s.clone()]
                                    }
                                }
                                other => vec![other.to_string()],
                            };
                            out.insert(k.clone(), json!({ "answers": arr }));
                        }
                    }
                    json!({ "id": pending.rpc_id, "result": { "answers": Value::Object(out) } })
                }
                "cancel" | "deny" | "abort" => json!({
                    "id": pending.rpc_id,
                    "error": { "code": -32000, "message": "User cancelled" }
                }),
                other => {
                    self.pending.lock().insert(request_id.to_string(), pending);
                    return Err(ApiError::validation(format!("unknown decision '{other}'")));
                }
            },
            "mcpServer/elicitation/request" => match decision {
                "answer" | "allow" => {
                    let mode = s(&pending.params, "mode").unwrap_or_default();
                    let content = if mode == "url" {
                        Value::Null
                    } else {
                        build_elicitation_content(&pending.params, answers.as_ref())
                    };
                    json!({
                        "id": pending.rpc_id,
                        "result": { "action": "accept", "content": content, "_meta": null }
                    })
                }
                "deny" => json!({
                    "id": pending.rpc_id,
                    "result": { "action": "decline", "content": null, "_meta": null }
                }),
                "cancel" | "abort" => json!({
                    "id": pending.rpc_id,
                    "result": { "action": "cancel", "content": null, "_meta": null }
                }),
                other => {
                    self.pending.lock().insert(request_id.to_string(), pending);
                    return Err(ApiError::validation(format!("unknown decision '{other}'")));
                }
            },
            "item/permissions/requestApproval" => match decision {
                "allow" | "allow_session" => json!({
                    "id": pending.rpc_id,
                    "result": {
                        "permissions": pending.params.get("permissions").cloned().unwrap_or(json!({})),
                        "scope": if decision == "allow_session" { "session" } else { "turn" },
                    }
                }),
                "deny" | "abort" | "cancel" => json!({
                    "id": pending.rpc_id,
                    "result": { "permissions": {}, "scope": "turn" }
                }),
                other => {
                    self.pending.lock().insert(request_id.to_string(), pending);
                    return Err(ApiError::validation(format!("unknown decision '{other}'")));
                }
            },
            _ => {
                let codex_decision = match decision {
                    "allow" => "accept",
                    "allow_session" => "acceptForSession",
                    "deny" => "decline",
                    "abort" | "cancel" => "cancel",
                    other => {
                        self.pending.lock().insert(request_id.to_string(), pending);
                        return Err(ApiError::validation(format!("unknown decision '{other}'")));
                    }
                };
                json!({ "id": pending.rpc_id, "result": { "decision": codex_decision } })
            }
        };
        self.resolved
            .lock()
            .insert(request_id.to_string(), by.to_string());
        let verdict = match (&self.remote, rpc_id_key(&pending.rpc_id)) {
            (Some(c), Some(key)) => {
                let (tx, rx) = oneshot::channel();
                c.verdicts.lock().insert(key, tx);
                Some(rx)
            }
            _ => None,
        };
        self.write(&reply_msg).await?;
        if let Some(rx) = verdict {
            // Codex Desktop may have answered at the same moment: the shim decides.
            if let Ok(Ok(false)) = tokio::time::timeout(Duration::from_secs(5), rx).await {
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
            if matches!(decision, "answer" | "allow") {
                if let Some(a) = &answers {
                    crate::agents::model::record_answers(&mut e.data, a);
                }
            }
        });
        if self.pending.lock().is_empty() {
            self.set_status("busy");
            self.publish_status("running", None);
        }
        Ok(json!({ "requestId": request_id, "decision": decision, "event": ev }))
    }

    fn kill(&self) {
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
}

impl ManagedCodex {
    /// Show what the phone typed for a command Codex runs without a turn of its own (it echoes
    /// no `userMessage` for those): the same bubble a Claude Code slash command leaves.
    fn echo_command(&self, text: &str) {
        self.log.lock().upsert(
            EventDraft::new(
                format!("cmd:{}", crate::util::new_id()),
                "user_message",
                crate::util::now_rfc3339(),
                json!({ "text": text.trim(), "images": 0, "source": "phone" }),
            ),
            true,
        );
    }

    fn command_output(&self, text: &str) {
        self.log.lock().upsert(
            EventDraft::new(
                format!("sys:{}", crate::util::new_id()),
                "system",
                crate::util::now_rfc3339(),
                json!({ "subtype": "command", "text": crate::redact::redact_text(text) }),
            ),
            true,
        );
    }

    /// Run `/name args` typed on the phone the way Codex's own clients do: the commands through
    /// their app-server requests, a skill as a `skill` input item. `None`: not one of them; the
    /// text goes to Codex unchanged.
    async fn slash_command(&self, text: &str) -> Option<PhoneCommand> {
        let rest = text.trim().strip_prefix('/')?;
        let (name, args) = match rest.split_once(char::is_whitespace) {
            Some((n, a)) => (n, a.trim()),
            None => (rest, ""),
        };
        let builtin = CODEX_COMMANDS.iter().any(|(n, _, _)| *n == name);
        if !builtin {
            let skill = self
                .skills()
                .await
                .into_iter()
                .find(|s| s.get("name").and_then(Value::as_str) == Some(name))?;
            return Some(PhoneCommand::Skill {
                name: name.to_string(),
                path: s(&skill, "path").unwrap_or_default(),
                args: args.to_string(),
            });
        }
        if name == "plan" {
            if let Err(e) = self.set_permission_mode("plan") {
                return Some(PhoneCommand::Done(Err(e)));
            }
            if args.is_empty() {
                self.echo_command(text);
                self.command_output("Plan mode is on for the next turn.");
                return Some(PhoneCommand::Done(Ok(())));
            }
            return Some(PhoneCommand::Prompt(args.to_string()));
        }
        let thread_id = match self.thread_id() {
            Ok(t) => t,
            Err(e) => return Some(PhoneCommand::Done(Err(e))),
        };
        self.echo_command(text);
        let short = Duration::from_secs(30);
        let r = match name {
            "compact" => self
                .request(
                    "thread/compact/start",
                    json!({ "threadId": thread_id }),
                    short,
                )
                .await
                .map(|_| ()),
            "review" => {
                let target = if args.is_empty() {
                    json!({ "type": "uncommittedChanges" })
                } else if let Some(b) = args.strip_prefix("branch ") {
                    json!({ "type": "baseBranch", "branch": b.trim() })
                } else if let Some(c) = args.strip_prefix("commit ") {
                    json!({ "type": "commit", "sha": c.trim(), "title": null })
                } else {
                    json!({ "type": "custom", "instructions": args })
                };
                self.request(
                    "review/start",
                    json!({ "threadId": thread_id, "target": target, "delivery": "inline" }),
                    short,
                )
                .await
                .map(|_| self.set_status("busy"))
            }
            "goal" => {
                let (method, params) = match args {
                    "" => {
                        return Some(PhoneCommand::Done(Err(ApiError::validation(
                            "usage: /goal <objective> | clear | pause | resume",
                        ))))
                    }
                    "clear" => (
                        "thread/goal/clear",
                        json!({ "threadId": thread_id, "origin": "user" }),
                    ),
                    "pause" => (
                        "thread/goal/set",
                        json!({ "threadId": thread_id, "origin": "user", "status": "paused" }),
                    ),
                    "resume" => (
                        "thread/goal/set",
                        json!({ "threadId": thread_id, "origin": "user", "status": "active" }),
                    ),
                    objective => (
                        "thread/goal/set",
                        json!({ "threadId": thread_id, "origin": "user", "objective": objective, "status": "active" }),
                    ),
                };
                self.request(method, params, short).await.map(|_| ())
            }
            "rename" if args.is_empty() => Err(ApiError::validation("usage: /rename <title>")),
            "rename" => self.rename(args).await.map(|_| ()),
            "status" => {
                let u = self.context_usage();
                let st = self.state.lock();
                let mode = st.next_mode.clone().or_else(|| st.mode.clone());
                let line = |k: &str, v: Option<String>| {
                    format!("{k}: {}", v.unwrap_or_else(|| "default".into()))
                };
                let ctx = match (u["totalTokens"].as_u64(), u["maxTokens"].as_u64()) {
                    (Some(t), Some(m)) => Some(format!("{t} / {m} tokens")),
                    (Some(t), None) => Some(format!("{t} tokens")),
                    _ => None,
                };
                let text = [
                    line("Model", st.model.clone()),
                    line("Effort", st.effort.clone()),
                    line("Permission mode", mode),
                    line("Context", ctx),
                    line("Thread", st.thread_id.clone()),
                ]
                .join("\n");
                drop(st);
                self.command_output(&text);
                Ok(())
            }
            "mcp" => match self.mcp_status().await {
                Ok(v) => {
                    let servers = v["servers"].as_array().cloned().unwrap_or_default();
                    let text = if servers.is_empty() {
                        "No MCP servers.".to_string()
                    } else {
                        servers
                            .iter()
                            .map(|x| {
                                format!(
                                    "{} · {}{}",
                                    x["name"].as_str().unwrap_or("?"),
                                    x["status"].as_str().unwrap_or("unknown"),
                                    x["tools"]
                                        .as_u64()
                                        .map(|n| format!(" · {n} tools"))
                                        .unwrap_or_default()
                                )
                            })
                            .collect::<Vec<_>>()
                            .join("\n")
                    };
                    self.command_output(&text);
                    Ok(())
                }
                Err(e) => Err(e),
            },
            _ => return None,
        };
        if let Err(e) = &r {
            self.command_output(&format!("/{name} failed: {}", e.message));
        }
        Some(PhoneCommand::Done(r))
    }

    /// Skills Codex finds for the session's folder (`skills/list`, cached until Codex reports
    /// `skills/changed`); enabled ones only.
    async fn skills(&self) -> Vec<Value> {
        if let Some(s) = self.state.lock().skills.clone() {
            return s;
        }
        let cwd = self.options.cwd.to_string_lossy().into_owned();
        let Ok(r) = self
            .request(
                "skills/list",
                json!({ "cwds": [cwd] }),
                Duration::from_secs(15),
            )
            .await
        else {
            return Vec::new();
        };
        let skills: Vec<Value> = r
            .get("data")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(|e| e.get("skills").and_then(Value::as_array))
            .flatten()
            .filter(|s| s.get("enabled").and_then(Value::as_bool) != Some(false))
            .cloned()
            .collect();
        self.state.lock().skills = Some(skills.clone());
        skills
    }

    /// The command list the phone offers (`{name, description, argumentHint}`): Codex's own
    /// commands, then the folder's skills (a skill named like a command is left out).
    pub async fn commands(&self) -> Vec<Value> {
        let mut out: Vec<Value> = CODEX_COMMANDS
            .iter()
            .map(|(n, d, a)| json!({ "name": n, "description": d, "argumentHint": a }))
            .collect();
        for sk in self.skills().await {
            let Some(name) = s(&sk, "name") else { continue };
            if CODEX_COMMANDS.iter().any(|(n, _, _)| *n == name) {
                continue;
            }
            let desc = sk
                .pointer("/interface/shortDescription")
                .and_then(Value::as_str)
                .or_else(|| sk.get("shortDescription").and_then(Value::as_str))
                .or_else(|| sk.get("description").and_then(Value::as_str))
                .unwrap_or("");
            out.push(json!({ "name": name, "description": desc, "argumentHint": "" }));
        }
        out
    }

    /// Context window use from the last `thread/tokenUsage/updated`.
    fn context_usage(&self) -> Value {
        let st = self.state.lock();
        let pct = match (st.context_tokens, st.context_window) {
            (Some(t), Some(w)) if w > 0 => Some(((t as f64) * 100.0 / (w as f64)).min(100.0)),
            _ => None,
        };
        json!({
            "totalTokens": st.context_tokens, "maxTokens": st.context_window,
            "percentage": pct, "categories": [],
        })
    }

    /// MCP servers of the session (`mcpServerStatus/list`), reduced like Claude Code's
    /// `mcp_status`: never their configuration.
    async fn mcp_status(&self) -> ApiResult<Value> {
        let thread = self.thread_id().ok();
        let r = self
            .request(
                "mcpServerStatus/list",
                json!({ "threadId": thread, "detail": "toolsAndAuthOnly" }),
                Duration::from_secs(20),
            )
            .await?;
        let servers: Vec<Value> = r
            .get("data")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .map(|x| {
                json!({
                    "name": x.get("name"),
                    "status": x.get("runtimeStatus"),
                    "title": x.pointer("/serverInfo/title"),
                    "version": x.pointer("/serverInfo/version"),
                    "tools": x.get("tools").and_then(Value::as_object).map(|t| t.len()),
                    "auth": x.get("authStatus"),
                    "error": x.get("toolsError").and_then(Value::as_str).map(crate::redact::redact_text),
                })
            })
            .collect();
        Ok(json!({ "servers": servers }))
    }

    async fn rename(&self, title: &str) -> ApiResult<Value> {
        if title.chars().count() > 200 {
            return Err(ApiError::validation("title too long"));
        }
        let thread_id = self.thread_id()?;
        self.request(
            "thread/name/set",
            json!({ "threadId": thread_id, "name": title }),
            Duration::from_secs(20),
        )
        .await?;
        self.state.lock().title = Some(title.to_string());
        Ok(json!({ "title": title }))
    }

    /// The session tools Codex can answer (same paths and reply shapes as Claude Code's,
    /// `POST /v1/sessions/{id}/claude/{action}`).
    pub async fn session_tool(&self, action: &str, p: &Value) -> ApiResult<Value> {
        let arg = |k: &str| -> ApiResult<String> {
            p.get(k)
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|v| !v.is_empty() && v.len() <= 4000)
                .map(String::from)
                .ok_or_else(|| ApiError::validation(format!("'{k}' is required")))
        };
        match action {
            "commands" => Ok(json!(self.commands().await)),
            "permission_mode" => {
                let mode = self.set_permission_mode(&arg("mode")?)?;
                Ok(json!({ "mode": mode }))
            }
            "context_usage" => Ok(self.context_usage()),
            "mcp_status" => self.mcp_status().await,
            "rename" => self.rename(&arg("title")?).await,
            _ => Err(ApiError::unavailable_capability(
                "BLOCKED",
                "Codex does not offer this session tool.",
            )),
        }
    }
}

/// Locate the `codex` executable: the newest of the CLI on PATH (or the usual
/// install locations) and, on Windows, the one bundled with Codex Desktop. An
/// old CLI may not support the models the user's account offers.
pub fn find_codex_bin() -> Option<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Some(p) = crate::util::which("codex") {
        candidates.push(p);
    }
    if let Some(home) = dirs::home_dir() {
        let extra = if cfg!(windows) {
            vec![home
                .join("AppData")
                .join("Roaming")
                .join("npm")
                .join("codex.cmd")]
        } else {
            vec![
                PathBuf::from("/opt/homebrew/bin/codex"),
                PathBuf::from("/usr/local/bin/codex"),
                home.join(".local").join("bin").join("codex"),
            ]
        };
        candidates.extend(extra.into_iter().filter(|p| p.is_file()));
    }
    if let Some(p) = desktop_bundled_codex() {
        candidates.push(p);
    }
    candidates.dedup();
    if candidates.len() <= 1 {
        return candidates.pop();
    }
    candidates
        .into_iter()
        .map(|p| (codex_version(&p), p))
        .max_by(|a, b| a.0.cmp(&b.0))
        .map(|(_, p)| p)
}

/// `codex --version` → comparable (major, minor, patch); unknown sorts lowest.
fn codex_version(p: &std::path::Path) -> (u64, u64, u64) {
    let mut cmd = std::process::Command::new(p);
    cmd.arg("--version")
        .stdin(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000);
    }
    let out = cmd.output().ok();
    let text = out
        .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
        .unwrap_or_default();
    parse_version(&text)
}

fn parse_version(text: &str) -> (u64, u64, u64) {
    let v = text
        .split_whitespace()
        .find(|w| w.chars().next().is_some_and(|c| c.is_ascii_digit()))
        .unwrap_or("");
    let mut it = v.split(['.', '-']).map(|x| x.parse::<u64>().unwrap_or(0));
    (
        it.next().unwrap_or(0),
        it.next().unwrap_or(0),
        it.next().unwrap_or(0),
    )
}

/// Codex Desktop's own `codex.exe` (MSIX package `OpenAI.Codex`).
fn desktop_bundled_codex() -> Option<PathBuf> {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let out = std::process::Command::new("powershell")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "(Get-AppxPackage -Name OpenAI.Codex | Select-Object -First 1).InstallLocation",
            ])
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .creation_flags(0x0800_0000)
            .output()
            .ok()?;
        let dir = String::from_utf8_lossy(&out.stdout).trim().to_string();
        if dir.is_empty() {
            return None;
        }
        let p = PathBuf::from(dir)
            .join("app")
            .join("resources")
            .join("codex.exe");
        p.is_file().then_some(p)
    }
    #[cfg(not(windows))]
    {
        None
    }
}

#[cfg(test)]
mod title_tests {
    use super::{parse_title, title_model};
    use serde_json::json;

    fn m(id: &str, low: bool, extra: serde_json::Value) -> serde_json::Value {
        let mut v = json!({ "id": id, "model": id, "hidden": false, "modelSpecialty": null, "isDefault": false,
            "supportedReasoningEfforts": if low { json!([{ "reasoningEffort": "low" }, { "reasoningEffort": "high" }]) } else { json!([{ "reasoningEffort": "high" }]) } });
        for (k, x) in extra.as_object().unwrap() {
            v[k] = x.clone();
        }
        v
    }

    #[test]
    fn title_model_prefers_desktops_then_a_small_one() {
        // The account's list from codex 0.167 (2026-11-25): no luna, a mini.
        let list = json!({ "data": [
            m("gpt-5.5", true, json!({ "isDefault": true })),
            m("gpt-5.4", true, json!({})),
            m("gpt-5.4-mini", true, json!({})),
            m("gpt-5.3-codex", true, json!({})),
        ] });
        assert_eq!(title_model(&list).as_deref(), Some("gpt-5.4-mini"));
        // Desktop's own title model wins, even hidden.
        let with_luna = json!({ "data": [m("gpt-5.5", true, json!({})), m("gpt-6-luna", true, json!({ "hidden": true }))] });
        assert_eq!(title_model(&with_luna).as_deref(), Some("gpt-6-luna"));
        // No small model: the default among those that take `low`; hidden / specialty / no-low skipped.
        let plain = json!({ "data": [
            m("gpt-x-mini", true, json!({ "hidden": true })),
            m("gpt-y-mini", true, json!({ "modelSpecialty": "review" })),
            m("gpt-z-nano", false, json!({})),
            m("gpt-a", true, json!({})),
            m("gpt-b", true, json!({ "isDefault": true })),
        ] });
        assert_eq!(title_model(&plain).as_deref(), Some("gpt-b"));
        assert_eq!(title_model(&json!({})), None);
    }

    #[test]
    fn titles_are_one_clean_line() {
        assert_eq!(
            parse_title(r#"{"title":"设置页登录按钮改为圆角"}"#).as_deref(),
            Some("设置页登录按钮改为圆角")
        );
        assert_eq!(
            parse_title("\"Fix login button.\"\nsecond line").as_deref(),
            Some("Fix login button")
        );
        assert_eq!(parse_title("修复登录。").as_deref(), Some("修复登录"));
        assert_eq!(
            parse_title(&"x".repeat(100)).map(|t| t.chars().count()),
            Some(60)
        );
        assert_eq!(parse_title("  "), None);
    }
}

#[cfg(test)]
mod version_tests {
    #[test]
    fn versions_compare_numerically() {
        assert_eq!(
            super::parse_version(
                "codex-cli 0.167.0-alpha.4.1
"
            ),
            (0, 167, 0)
        );
        assert!(
            super::parse_version("codex-cli 0.137.0") < super::parse_version("codex-cli 0.167.0")
        );
        assert_eq!(super::parse_version(""), (0, 0, 0));
    }

    #[test]
    fn subagent_source_accepts_both_wire_spellings() {
        for key in ["subagent", "subAgent"] {
            let thread = serde_json::json!({"source": {key: {"thread_spawn": {"parent_thread_id": "parent"}}}});
            assert_eq!(
                super::CodexConn::subagent_parent_id(&thread).as_deref(),
                Some("parent")
            );
        }
    }

    /// A `wait` (or sendInput / closeAgent) on a child spawned earlier updates only its state:
    /// the row keeps the spawn's prompt, parent call and turn, and takes the child's answer.
    #[tokio::test]
    async fn collab_wait_updates_the_spawned_row_in_place() {
        use super::*;
        use crate::agents::store::SessionLog;
        use parking_lot::Mutex;
        use std::sync::Arc;

        let (tx, _rx) = tokio::sync::mpsc::unbounded_channel::<String>();
        let conn = CodexConn::new(tx, "desktop_app", PathBuf::from("."));
        let log = Arc::new(Mutex::new(SessionLog::new("attached:p2", None)));
        let parent = ManagedCodex::attached(
            "attached:p2".into(),
            conn,
            "th-p".into(),
            PathBuf::from("."),
            log.clone(),
        );
        let item = |id: &str, tool: &str, prompt: Option<&str>, states: Value| {
            json!({"threadId":"th-p","turnId":"turn-1","item":{"type":"collabAgentToolCall","id":id,"tool":tool,
                "status":"completed","senderThreadId":"th-p","receiverThreadIds":["th-c"],"prompt":prompt,
                "model":"gpt-x","agentsStates":states}})
        };
        parent.on_notification(
            "item/completed",
            item(
                "spawn-1",
                "spawnAgent",
                Some("Review the login flow"),
                json!({"th-c":{"status":"pendingInit"}}),
            ),
        );
        let row = log.lock().get("cx-sa-th-c").cloned().unwrap();
        assert_eq!(row.parent_id.as_deref(), Some("spawn-1"));
        assert_eq!(row.data["description"], "Review the login flow");
        assert_eq!(row.data["status"], "running");

        // The child thread announces itself: the spawn's prompt stays the description.
        parent.on_subagent_started("th-c", &json!({"id":"th-c","agentNickname":"Scout"}));
        let row = log.lock().get("cx-sa-th-c").cloned().unwrap();
        assert_eq!(row.data["description"], "Review the login flow");
        assert_eq!(row.data["agentType"], "Scout");
        assert_eq!(row.parent_id.as_deref(), Some("spawn-1"));

        let mut later = item(
            "wait-1",
            "wait",
            None,
            json!({"th-c":{"status":"completed","message":"Login is fine."}}),
        );
        later["turnId"] = json!("turn-2");
        parent.on_notification("item/completed", later);
        let row = log.lock().get("cx-sa-th-c").cloned().unwrap();
        assert_eq!(
            row.parent_id.as_deref(),
            Some("spawn-1"),
            "not moved under the wait"
        );
        assert_eq!(row.turn_id.as_deref(), Some("turn-1"));
        assert_eq!(row.data["description"], "Review the login flow");
        assert_eq!(row.data["status"], "completed");
        assert_eq!(row.data["result"], "Login is fine.");
        assert_eq!(row.data["callId"], "spawn-1");
    }

    /// Multi-agent v2 over the app-server: the parent only gets `subAgentActivity` items (no
    /// `thread/started` for the child); the child's events must be routed under its row, with
    /// item ids that cannot collide with the parent's.
    #[tokio::test]
    async fn v2_sub_agent_activity_links_and_scopes_the_child() {
        use super::*;
        use crate::agents::store::SessionLog;
        use parking_lot::Mutex;
        use std::sync::Arc;

        let (tx, _rx) = tokio::sync::mpsc::unbounded_channel::<String>();
        let conn = CodexConn::new(tx, "desktop_app", PathBuf::from("."));
        let log = Arc::new(Mutex::new(SessionLog::new("attached:p3", None)));
        let parent = ManagedCodex::attached(
            "attached:p3".into(),
            conn.clone(),
            "th-p".into(),
            PathBuf::from("."),
            log.clone(),
        );
        conn.threads.lock().insert("th-p".into(), parent.clone());
        let frame = |line: Value| json!({"t":"out","line": line.to_string()});
        let activity = |kind: &str, method: &str| {
            frame(
                json!({"method":method,"params":{"threadId":"th-p","turnId":"t1","item":{
                "type":"subAgentActivity","id":"call-1","kind":kind,"agentThreadId":"th-c","agentPath":"/root/review","model":"gpt-x"}}}),
            )
        };
        for m in ["item/started", "item/completed"] {
            let f = activity("started", m);
            assert!(conn.discover(&f).is_empty());
            conn.on_frame(f).await;
        }
        let row = log.lock().get("cx-sa-th-c").cloned().unwrap();
        assert_eq!(row.data["description"], "review");
        assert_eq!(row.data["status"], "running");
        assert_eq!(
            log.lock().iter().filter(|e| e.kind == "subagent").count(),
            1
        );

        // Same item id in parent and child: two events.
        let item = |tid: &str, text: &str| {
            frame(
                json!({"method":"item/completed","params":{"threadId":tid,"turnId":"t","item":{
                "type":"agentMessage","id":"item-1","text":text}}}),
            )
        };
        let f = item("th-c", "child says");
        assert!(
            conn.discover(&f).is_empty(),
            "the child is not a new top-level thread"
        );
        conn.on_frame(f).await;
        conn.on_frame(item("th-p", "parent says")).await;
        {
            // Scoped (not `drop(lg)`): the lock guard must be gone before the next `.await`.
            let lg = log.lock();
            let child = lg.get("cx-item-item-1@th-c").unwrap();
            assert_eq!(child.parent_id.as_deref(), Some("cx-sa-th-c"));
            assert_eq!(
                lg.get("cx-item-item-1").unwrap().data["text"],
                "parent says"
            );
        }

        conn.on_frame(activity("completed", "item/completed")).await;
        conn.on_frame(activity("started", "item/completed")).await; // late spawn echo
        assert_eq!(
            log.lock().get("cx-sa-th-c").unwrap().data["status"],
            "completed"
        );
    }

    #[tokio::test]
    async fn codex_user_input_elicitation_and_subagent_routing() {
        use super::*;
        use crate::agents::managed::ManagedSession;
        use crate::agents::store::SessionLog;
        use parking_lot::Mutex;
        use std::sync::Arc;

        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<String>();
        let conn = CodexConn::new(tx, "desktop_app", PathBuf::from("."));
        let log = Arc::new(Mutex::new(SessionLog::new("attached:p1", None)));
        let parent = ManagedCodex::attached(
            "attached:p1".into(),
            conn.clone(),
            "th-parent".into(),
            PathBuf::from("."),
            log.clone(),
        );
        conn.threads
            .lock()
            .insert("th-parent".into(), parent.clone());

        conn.on_frame(json!({"t":"out", "line": json!({"id":1,"result":{"thread":{"id":"th-parent","name":"Fix login"}}}).to_string()})).await;
        assert_eq!(parent.title().as_deref(), Some("Fix login"));
        parent.on_notification(
            "thread/name/updated",
            json!({"threadId":"th-parent", "threadName":"Renamed task"}),
        );
        assert_eq!(parent.title().as_deref(), Some("Renamed task"));

        // 1. Subagent thread discovered via thread/started is NOT returned as a top-level thread.
        let sub_frame = json!({
            "t": "out",
            "line": json!({
                "method": "thread/started",
                "params": {
                    "thread": {
                        "id": "th-child",
                        "source": { "subagent": { "thread_spawn": { "parent_thread_id": "th-parent" } } },
                        "agentRole": "explorer",
                        "agentNickname": "Scout"
                    }
                }
            }).to_string()
        });
        assert!(conn.discover(&sub_frame).is_empty());
        assert_eq!(
            conn.subagent_parents
                .lock()
                .get("th-child")
                .map(String::as_str),
            Some("th-parent")
        );
        let sa_ev = log.lock().get("cx-sa-th-child").cloned().unwrap();
        assert_eq!(sa_ev.kind, "subagent");
        assert_eq!(sa_ev.data["status"], "running");

        // Subagent turn/completed updates subagent status to completed.
        conn.on_frame(json!({
            "t": "out",
            "line": json!({
                "method": "turn/completed",
                "params": { "threadId": "th-child", "turn": { "id": "t1", "status": "completed" } }
            }).to_string()
        }))
        .await;
        assert_eq!(
            log.lock().get("cx-sa-th-child").unwrap().data["status"],
            "completed"
        );

        // 2. item/tool/requestUserInput produces a question event and accepts structured answers.
        parent
            .on_server_request(
                json!(42),
                "item/tool/requestUserInput",
                json!({
                    "threadId": "th-parent",
                    "turnId": "t1",
                    "itemId": "i1",
                    "questions": [{
                        "id": "q1",
                        "header": "Target",
                        "question": "Which env?",
                        "isOther": true,
                        "isSecret": false,
                        "options": [{ "label": "staging", "description": "Staging" }]
                    }, {"id":"checks", "question":"Choose checks", "multiSelect":true, "options":[{"label":"Unit, integration"},{"label":"Lint"}]}]
                }),
            )
            .await;
        let q_ev = log.lock().get("req:cx-42").cloned().unwrap();
        assert_eq!(q_ev.kind, "question");
        assert_eq!(q_ev.data["questions"][0]["allowOther"], true);

        // Answer in background (verdict channel waits for shim accepted/rejected in attached mode).
        let p2 = parent.clone();
        let ans_task = tokio::spawn(async move {
            p2.respond(
                "cx-42",
                "answer",
                None,
                None,
                Some(json!({ "q1": "staging", "checks": ["Unit, integration", "Lint"] })),
                "phone",
            )
            .await
        });
        let injected = rx.recv().await.unwrap();
        let inj_v: Value = serde_json::from_str(&injected).unwrap();
        let inner: Value = serde_json::from_str(inj_v["line"].as_str().unwrap()).unwrap();
        assert_eq!(inner["id"], 42);
        assert_eq!(inner["result"]["answers"]["q1"]["answers"][0], "staging");
        assert_eq!(
            inner["result"]["answers"]["checks"]["answers"],
            json!(["Unit, integration", "Lint"])
        );
        conn.on_frame(json!({ "t": "accepted", "line": inj_v["line"] }))
            .await;
        assert!(ans_task.await.unwrap().is_ok());

        // 3. mcpServer/elicitation/request (form) maps schema and cancel sends action=cancel.
        parent
            .on_server_request(
                json!("el-1"),
                "mcpServer/elicitation/request",
                json!({
                    "threadId": "th-parent",
                    "turnId": "t1",
                    "serverName": "github",
                    "mode": "form",
                    "message": "Select options",
                    "requestedSchema": {
                        "type": "object",
                        "properties": {
                            "repo": { "type": "string", "title": "Repository" },
                            "dryRun": { "type": "boolean", "title": "Dry run" }
                        }
                    }
                }),
            )
            .await;
        assert_eq!(log.lock().get("req:cx-el-1").unwrap().kind, "question");
        let p3 = parent.clone();
        let cancel_task = tokio::spawn(async move {
            p3.respond("cx-el-1", "cancel", None, None, None, "phone")
                .await
        });
        let injected2 = rx.recv().await.unwrap();
        let inj_v2: Value = serde_json::from_str(&injected2).unwrap();
        let inner2: Value = serde_json::from_str(inj_v2["line"].as_str().unwrap()).unwrap();
        assert_eq!(inner2["result"]["action"], "cancel");
        conn.on_frame(json!({ "t": "accepted", "line": inj_v2["line"] }))
            .await;
        assert!(cancel_task.await.unwrap().is_ok());
    }

    /// Permission modes, slash commands, skills and the 0.171 notifications, against a fake
    /// app-server behind an attached connection (requests are captured and answered).
    #[tokio::test(flavor = "multi_thread")]
    async fn codex_modes_commands_skills_and_streamed_reasoning() {
        use super::*;
        use crate::agents::managed::ManagedSession;
        use crate::agents::store::SessionLog;
        use parking_lot::Mutex;
        use std::sync::Arc;

        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<String>();
        let conn = CodexConn::new(tx, "desktop_app", PathBuf::from("."));
        let log = Arc::new(Mutex::new(SessionLog::new("attached:m1", None)));
        let th = ManagedCodex::attached(
            "attached:m1".into(),
            conn.clone(),
            "th1".into(),
            PathBuf::from("."),
            log.clone(),
        );
        conn.threads.lock().insert("th1".into(), th.clone());

        // The fake app-server: records every request and answers it.
        let seen: Arc<Mutex<Vec<Value>>> = Arc::new(Mutex::new(Vec::new()));
        {
            let (conn, seen) = (conn.clone(), seen.clone());
            tokio::spawn(async move {
                while let Some(frame) = rx.recv().await {
                    let f: Value = serde_json::from_str(&frame).unwrap();
                    let req: Value = serde_json::from_str(f["line"].as_str().unwrap()).unwrap();
                    let result = match req["method"].as_str() {
                        Some("skills/list") => {
                            json!({ "data": [{ "cwd": ".", "errors": [], "skills": [
                            { "name": "deploy", "description": "Ship it", "path": "/s/deploy/SKILL.md", "enabled": true, "scope": "user", "pluginId": null },
                            { "name": "off", "description": "", "path": "/s/off/SKILL.md", "enabled": false, "scope": "user", "pluginId": null }
                        ] }] })
                        }
                        _ => json!({}),
                    };
                    seen.lock().push(req.clone());
                    conn.on_frame(json!({ "t": "out", "line": json!({ "id": req["id"], "result": result }).to_string() }))
                        .await;
                }
            });
        }
        let last = |method: &str| {
            seen.lock()
                .iter()
                .rev()
                .find(|r| r["method"] == method)
                .cloned()
                .unwrap_or_else(|| panic!("no {method} request"))
        };

        // The thread's settings as Codex reports them (also when Desktop changes them).
        let ws = json!({ "type": "workspaceWrite", "writableRoots": [], "networkAccess": true,
                         "excludeTmpdirEnvVar": false, "excludeSlashTmp": false });
        th.on_notification(
            "thread/settings/updated",
            json!({ "threadId": "th1", "threadSettings": {
                "model": "gpt-6", "effort": "medium", "approvalPolicy": "on-request",
                "approvalsReviewer": "user", "sandboxPolicy": ws,
                "collaborationMode": { "mode": "default", "settings": { "model": "gpt-6", "reasoning_effort": null, "developer_instructions": null } } } }),
        );
        assert_eq!(th.permission_mode().as_deref(), Some("default"));
        assert_eq!(th.model().as_deref(), Some("gpt-6"));

        // Plan mode: a collaboration mode on the turn; the user's workspace sandbox is kept.
        ManagedSession::set_permission_mode(th.as_ref(), "plan")
            .await
            .unwrap();
        th.send_message("look around").await.unwrap();
        let t = last("turn/start");
        assert_eq!(t["params"]["collaborationMode"]["mode"], "plan");
        assert_eq!(
            t["params"]["collaborationMode"]["settings"]["model"],
            "gpt-6"
        );
        assert_eq!(t["params"]["sandboxPolicy"], ws);
        assert_eq!(t["params"]["approvalPolicy"], "on-request");

        // Full access (the API asks for the step-up): no sandbox, no approvals, plan mode left.
        ManagedSession::set_permission_mode(th.as_ref(), "danger-full-access")
            .await
            .unwrap();
        assert_eq!(th.permission_mode().as_deref(), Some("bypassPermissions"));
        th.send_message("go").await.unwrap();
        let t = last("turn/start");
        assert_eq!(t["params"]["approvalPolicy"], "never");
        assert_eq!(t["params"]["sandboxPolicy"]["type"], "dangerFullAccess");
        assert_eq!(t["params"]["collaborationMode"]["mode"], "default");
        // Not sent again once applied.
        th.send_message("again").await.unwrap();
        assert!(last("turn/start")["params"].get("approvalPolicy").is_none());
        assert!(ManagedSession::set_permission_mode(th.as_ref(), "dontAsk")
            .await
            .is_err());

        // Codex's own commands run through their requests and leave the typed bubble.
        th.send_message("/compact").await.unwrap();
        assert_eq!(last("thread/compact/start")["params"]["threadId"], "th1");
        th.send_message("/review").await.unwrap();
        assert_eq!(
            last("review/start")["params"]["target"]["type"],
            "uncommittedChanges"
        );
        th.send_message("/review branch main").await.unwrap();
        assert_eq!(last("review/start")["params"]["target"]["branch"], "main");
        th.send_message("/goal ship the login fix").await.unwrap();
        let g = last("thread/goal/set");
        assert_eq!(g["params"]["objective"], "ship the login fix");
        assert_eq!(g["params"]["origin"], "user");
        th.send_message("/rename Login fix").await.unwrap();
        assert_eq!(last("thread/name/set")["params"]["name"], "Login fix");
        assert_eq!(th.title().as_deref(), Some("Login fix"));
        let bubbles: Vec<String> = log
            .lock()
            .iter()
            .filter(|e| e.kind == "user_message")
            .map(|e| e.data["text"].as_str().unwrap_or("").to_string())
            .collect();
        assert!(bubbles.contains(&"/compact".to_string()));

        // A skill is a `skill` input item followed by the rest of the message.
        th.send_message("/deploy to staging").await.unwrap();
        let t = last("turn/start");
        assert_eq!(
            t["params"]["input"][0],
            json!({ "type": "skill", "name": "deploy", "path": "/s/deploy/SKILL.md" })
        );
        assert_eq!(t["params"]["input"][1]["text"], "to staging");
        // Unknown commands are plain text for Codex.
        th.send_message("/nope x").await.unwrap();
        assert_eq!(last("turn/start")["params"]["input"][0]["text"], "/nope x");
        let cmds = th.commands().await;
        let names: Vec<&str> = cmds.iter().map(|c| c["name"].as_str().unwrap()).collect();
        assert!(names.contains(&"compact") && names.contains(&"deploy"));
        assert!(!names.contains(&"off"), "disabled skills are not offered");

        // Reasoning streams, then the completed item closes the row.
        th.on_notification(
            "item/reasoning/summaryTextDelta",
            json!({ "threadId": "th1", "turnId": "t9", "itemId": "r1", "delta": "Think", "summaryIndex": 0 }),
        );
        th.on_notification(
            "item/reasoning/summaryTextDelta",
            json!({ "threadId": "th1", "turnId": "t9", "itemId": "r1", "delta": "ing", "summaryIndex": 0 }),
        );
        let r = log.lock().get("cx-item-r1").cloned().unwrap();
        assert_eq!(r.kind, "reasoning");
        assert_eq!(r.data["text"], "Thinking");
        assert!(r.streaming);
        th.on_notification(
            "item/completed",
            json!({ "threadId": "th1", "turnId": "t9", "item": { "type": "reasoning", "id": "r1", "summary": ["Thinking done"], "content": [] } }),
        );
        let r = log.lock().get("cx-item-r1").cloned().unwrap();
        assert_eq!(r.data["text"], "Thinking done");
        assert!(!r.streaming);

        // New item kinds of the 0.171 protocol.
        th.on_notification(
            "item/completed",
            json!({ "threadId": "th1", "turnId": "t9", "item": { "type": "exitedReviewMode", "id": "rv", "review": "No issues found." } }),
        );
        assert_eq!(
            log.lock().get("cx-item-rv").unwrap().kind,
            "assistant_message"
        );
        th.on_notification(
            "item/completed",
            json!({ "threadId": "th1", "turnId": "t9", "item": { "type": "sleep" } }),
        );
        th.on_notification(
            "model/rerouted",
            json!({ "threadId": "th1", "turnId": "t9", "fromModel": "gpt-6", "toModel": "gpt-6-mini", "reason": "highRiskCyberActivity" }),
        );
        assert_eq!(th.model().as_deref(), Some("gpt-6-mini"));

        // Context use comes from the token usage notification.
        th.on_notification(
            "thread/tokenUsage/updated",
            json!({ "threadId": "th1", "turnId": "t9", "tokenUsage": {
                "total": { "totalTokens": 9000, "inputTokens": 8000, "cachedInputTokens": 0, "cacheWriteInputTokens": 0, "outputTokens": 1000, "reasoningOutputTokens": 0 },
                "last": { "totalTokens": 50000, "inputTokens": 49000, "cachedInputTokens": 1000, "cacheWriteInputTokens": 10, "outputTokens": 1000, "reasoningOutputTokens": 0 },
                "modelContextWindow": 200000 } }),
        );
        let u = th.session_tool("context_usage", &json!({})).await.unwrap();
        assert_eq!(u["totalTokens"], 50000);
        assert_eq!(u["maxTokens"], 200000);
        assert_eq!(u["percentage"], 25.0);

        // Desktop switched to full access itself.
        th.on_notification(
            "thread/settings/updated",
            json!({ "threadId": "th1", "threadSettings": {
                "model": "gpt-6", "effort": null, "approvalPolicy": "never", "approvalsReviewer": "user",
                "sandboxPolicy": { "type": "dangerFullAccess" },
                "collaborationMode": { "mode": "default", "settings": { "model": "gpt-6", "reasoning_effort": null, "developer_instructions": null } } } }),
        );
        assert_eq!(th.permission_mode().as_deref(), Some("bypassPermissions"));

        // Codex Desktop answers itself: the phone's cards show what was chosen there.
        th.on_server_request(
            json!(77),
            "item/tool/requestUserInput",
            json!({ "threadId": "th1", "turnId": "t9", "itemId": "i9",
                    "questions": [{ "id": "env", "header": "Env", "question": "Where?",
                                    "options": [{ "label": "staging" }, { "label": "prod" }] }] }),
        )
        .await;
        conn.on_frame(json!({ "t": "in", "line": json!({ "id": 77, "result": { "answers": { "env": { "answers": ["staging"] } } } }).to_string() }))
            .await;
        let q = log.lock().get("req:cx-77").cloned().unwrap();
        assert_eq!(q.data["resolved"], true);
        assert_eq!(q.data["by"], "desktop");
        assert_eq!(q.data["decision"], "answer");
        assert_eq!(q.data["answers"]["env"], json!(["staging"]));
        th.on_server_request(
            json!(78),
            "item/commandExecution/requestApproval",
            json!({ "threadId": "th1", "turnId": "t9", "itemId": "c1", "command": "rm -rf build" }),
        )
        .await;
        conn.on_frame(json!({ "t": "in", "line": json!({ "id": 78, "result": { "decision": "acceptForSession" } }).to_string() }))
            .await;
        let a = log.lock().get("req:cx-78").cloned().unwrap();
        assert_eq!(a.data["decision"], "allow_session");
        assert_eq!(a.data["by"], "desktop");
        assert!(!th.has_pending_requests());
    }

    /// Reads, edits, MCP results and plans of a Codex thread look like Claude Code's on the
    /// phone: a read is a `read` step with its file openable, an edit names its file.
    #[tokio::test(flavor = "multi_thread")]
    async fn items_map_like_claude_code_tools() {
        use super::*;
        use crate::agents::store::SessionLog;
        use parking_lot::Mutex;
        use std::sync::Arc;

        let (tx, _rx) = tokio::sync::mpsc::unbounded_channel::<String>();
        let conn = CodexConn::new(tx, "desktop_app", PathBuf::from("."));
        let log = Arc::new(Mutex::new(SessionLog::new("attached:m2", None)));
        let th = ManagedCodex::attached(
            "attached:m2".into(),
            conn.clone(),
            "th2".into(),
            PathBuf::from("."),
            log.clone(),
        );
        let item = |item: Value| json!({ "threadId": "th2", "turnId": "t1", "item": item });
        th.on_notification(
            "item/completed",
            item(json!({ "type": "commandExecution", "id": "c1", "status": "completed",
                "command": "/bin/zsh -lc \"sed -n '10,19p' src/lib.rs\"", "cwd": "/repo",
                "commandActions": [{ "type": "read", "command": "sed -n '10,19p' src/lib.rs", "name": "lib.rs", "path": "/repo/src/lib.rs" }],
                "aggregatedOutput": "fn a() {}\n", "exitCode": 0 })),
        );
        let tc = log.lock().get("cx-item-c1").cloned().unwrap();
        assert_eq!(tc.data["category"], "read");
        assert_eq!(tc.data["title"], "/repo/src/lib.rs");
        assert_eq!(
            tc.data["files"],
            json!([{ "path": "/repo/src/lib.rs", "offset": 10, "limit": 10 }])
        );
        assert_eq!(
            tc.data["input"]["command"],
            "/bin/zsh -lc \"sed -n '10,19p' src/lib.rs\""
        );

        // Codex leaves PowerShell reads `unknown`; they are recognized here.
        th.on_notification(
            "item/completed",
            item(json!({ "type": "commandExecution", "id": "c2", "status": "completed",
                "command": "pwsh.exe -Command 'Get-Content -LiteralPath a.md; cargo build'", "cwd": "C:\\w",
                "commandActions": [{ "type": "unknown", "command": "Get-Content -LiteralPath a.md; cargo build" }] })),
        );
        let tc = log.lock().get("cx-item-c2").cloned().unwrap();
        assert_eq!(tc.data["category"], "shell");
        assert_eq!(tc.data["files"], json!([{ "path": "C:\\w\\a.md" }]));

        th.on_notification(
            "item/completed",
            item(json!({ "type": "commandExecution", "id": "c3", "status": "completed",
                "command": "rg -n foo", "cwd": "/repo",
                "commandActions": [{ "type": "search", "command": "rg -n foo", "query": "foo", "path": null }] })),
        );
        assert_eq!(
            log.lock().get("cx-item-c3").unwrap().data["category"],
            "search"
        );

        th.on_notification(
            "item/completed",
            item(json!({ "type": "fileChange", "id": "f1", "status": "completed",
                "changes": [{ "path": "/repo/src/lib.rs", "kind": { "type": "update" }, "diff": "@@ -1 +1 @@\n-a\n+b\n" }] })),
        );
        let tc = log.lock().get("cx-item-f1").cloned().unwrap();
        assert_eq!(tc.data["title"], "/repo/src/lib.rs");
        assert_eq!(tc.data["files"], json!([{ "path": "/repo/src/lib.rs" }]));

        th.on_notification(
            "item/completed",
            item(json!({ "type": "mcpToolCall", "id": "m1", "status": "completed", "server": "gh", "tool": "issue",
                "arguments": {}, "result": { "content": [{ "type": "text", "text": "Issue #1" }], "structuredContent": null } })),
        );
        assert_eq!(log.lock().get("res:m1").unwrap().data["text"], "Issue #1");

        th.on_notification(
            "turn/plan/updated",
            json!({ "threadId": "th2", "turnId": "t1", "plan": [
                { "step": "a", "status": "completed" }, { "step": "b", "status": "inProgress" }, { "step": "c", "status": "pending" }] }),
        );
        let plan = log.lock().get("plan:t1").cloned().unwrap();
        assert_eq!(plan.data["items"][1]["status"], "in_progress");
    }
}
