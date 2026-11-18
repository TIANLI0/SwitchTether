//! DeepSeek Harness (`dsh`) sessions, read-only.
//!
//! Layout: `$DSH_HOME/sessions/<project>/<session dir>/session.vN.jsonl[.zstd]`; the highest
//! version in a directory wins. Compressed logs are sequences of independent zstd frames, so a
//! file can be tailed frame by frame (a half-written last frame waits for the next read).
//! The first record is a header `{type:"session", id, cwd, parentSession?, delegationDepth?,
//! origin?}`; a sub-agent is a session of its own whose header points to its parent.
//!
//! Records are mapped to the normal agent events (docs/PROTOCOL.md §10) the same way the
//! Claude Code and Codex parsers do, so the phone renders them with the same components.
//! Copies with `surfaceOp.op == "replace"` only rewrite what the model sees (compaction) and are
//! skipped, as is model-only injected context.

use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};

use serde_json::{json, Value};

use super::model::EventDraft;

pub mod attached;
pub mod managed;

/// Upper bound of decompressed bytes read from one session log.
pub const MAX_DECODED: usize = 64 * 1024 * 1024;

pub fn dsh_home() -> PathBuf {
    if let Some(d) = std::env::var_os("DSH_HOME") {
        return PathBuf::from(d);
    }
    dirs::home_dir().unwrap_or_default().join(".dsh")
}

pub fn sessions_root() -> PathBuf {
    dsh_home().join("sessions")
}

/// Decodes the complete zstd frames at the start of `buf`; returns the decoded bytes and how
/// many input bytes they used (a trailing partial frame is left for later).
pub fn decode_frames(buf: &[u8]) -> (Vec<u8>, usize) {
    let mut out = Vec::new();
    let mut offset = 0;
    while offset < buf.len() {
        let frame = &buf[offset..];
        let Ok(size) = zstd::zstd_safe::find_frame_compressed_size(frame) else {
            break; // incomplete (still being written) or not zstd
        };
        if size == 0 || size > frame.len() {
            break;
        }
        match zstd::stream::read::Decoder::new(&frame[..size]) {
            Ok(d) => {
                let room = MAX_DECODED.saturating_sub(out.len()) as u64;
                if d.take(room).read_to_end(&mut out).is_err() {
                    break;
                }
            }
            Err(_) => break,
        }
        offset += size;
        if out.len() >= MAX_DECODED {
            break;
        }
    }
    (out, offset)
}

/// The log of a session directory: highest `session.vN.jsonl[.zstd]`, else `session.jsonl[.zstd]`.
pub fn session_file(dir: &Path) -> Option<PathBuf> {
    let mut best: Option<(u32, PathBuf)> = None;
    for e in std::fs::read_dir(dir).ok()?.flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        let Some(rest) = name.strip_prefix("session") else {
            continue;
        };
        let stem = rest
            .strip_suffix(".jsonl.zstd")
            .or_else(|| rest.strip_suffix(".jsonl"));
        let Some(stem) = stem else { continue };
        let v = if stem.is_empty() {
            0
        } else if let Some(n) = stem.strip_prefix(".v").and_then(|n| n.parse::<u32>().ok()) {
            n
        } else {
            continue;
        };
        if best.as_ref().is_none_or(|(bv, _)| v > *bv) {
            best = Some((v, e.path()));
        }
    }
    best.map(|(_, p)| p)
}

/// First record of a session log (the header), decoding only what is needed.
pub fn read_header(path: &Path) -> Option<Value> {
    let mut f = std::fs::File::open(path).ok()?;
    let mut buf = vec![0u8; 256 * 1024];
    let n = f.read(&mut buf).ok()?;
    buf.truncate(n);
    let text = if path.extension().is_some_and(|e| e == "zstd") {
        let mut d = zstd::stream::read::Decoder::new(&buf[..]).ok()?;
        let mut out = vec![0u8; 64 * 1024];
        let mut got = 0;
        while got < out.len() {
            match d.read(&mut out[got..]) {
                Ok(0) => break,
                Ok(k) => {
                    got += k;
                    if out[..got].contains(&b'\n') {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
        out.truncate(got);
        out
    } else {
        buf
    };
    let line = text.split(|b| *b == b'\n').next()?;
    let v: Value = serde_json::from_slice(line).ok()?;
    (v.get("type").and_then(Value::as_str) == Some("session")).then_some(v)
}

/// Session directory of a session id (directories are named `<id>` or `session-<id>`).
pub fn find_session_dir(id: &str) -> Option<PathBuf> {
    if id.is_empty() || id.contains(['/', '\\']) || id.contains("..") {
        return None;
    }
    for project in std::fs::read_dir(sessions_root()).ok()?.flatten() {
        for name in [id.to_string(), format!("session-{id}")] {
            let d = project.path().join(&name);
            if d.is_dir() {
                return Some(d);
            }
        }
    }
    None
}

/// Session ids appear both as `<uuid>` and `session-<uuid>` (directory names, `parentSession`).
pub fn norm_id(id: &str) -> &str {
    id.strip_prefix("session-").unwrap_or(id)
}

/// A dsh sub-agent: a session whose header names its parent.
#[derive(Debug, Clone)]
pub struct DshChild {
    pub id: String,
    pub parent: String,
    pub path: PathBuf,
    pub depth: u64,
    pub modified_ms: Option<u64>,
}

/// Every sub-agent session under the same project directory as the session in `dir`
/// (descendants at any depth; the tree is built from `parent`).
pub fn descendants(dir: &Path, root_id: &str) -> Vec<DshChild> {
    let Some(project) = dir.parent() else {
        return Vec::new();
    };
    let mut all = Vec::new();
    let Ok(rd) = std::fs::read_dir(project) else {
        return Vec::new();
    };
    for e in rd.flatten().take(5_000) {
        let p = e.path();
        if !p.is_dir() || p == dir {
            continue;
        }
        let Some(file) = session_file(&p) else {
            continue;
        };
        let Some(h) = read_header(&file) else {
            continue;
        };
        let Some(parent) = h.get("parentSession").and_then(Value::as_str) else {
            continue;
        };
        let Some(id) = h.get("id").and_then(Value::as_str) else {
            continue;
        };
        let modified_ms = std::fs::metadata(&file)
            .ok()
            .and_then(|m| m.modified().ok())
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as u64);
        all.push(DshChild {
            id: norm_id(id).to_string(),
            parent: norm_id(parent).to_string(),
            path: file,
            depth: h
                .get("delegationDepth")
                .and_then(Value::as_u64)
                .unwrap_or(1),
            modified_ms,
        });
    }
    // Keep only the descendants of `root_id`.
    let mut keep: Vec<DshChild> = Vec::new();
    let mut frontier = vec![norm_id(root_id).to_string()];
    while let Some(p) = frontier.pop() {
        for c in all.iter().filter(|c| c.parent == p) {
            if keep.iter().any(|k| k.id == c.id) {
                continue;
            }
            frontier.push(c.id.clone());
            keep.push(c.clone());
        }
    }
    keep
}

/// Label of a sub-agent session (its `subagent/descriptor` label, else its title) and whether
/// its last turn has ended. Reads the whole (bounded) log.
pub fn child_summary(path: &Path) -> (Option<String>, bool) {
    let Ok(bytes) = std::fs::read(path) else {
        return (None, false);
    };
    let text = if path.extension().is_some_and(|e| e == "zstd") {
        decode_frames(&bytes).0
    } else {
        bytes
    };
    let mut label = None;
    let mut title = None;
    let mut open_turn = false;
    for line in text.split(|b| *b == b'\n') {
        let Ok(r) = serde_json::from_slice::<Value>(line) else {
            continue;
        };
        match r.get("type").and_then(Value::as_str) {
            Some("subagent/descriptor") => {
                label = r
                    .pointer("/data/label")
                    .and_then(Value::as_str)
                    .map(str::to_string)
            }
            Some("session/title") => {
                title = r
                    .pointer("/data/title")
                    .and_then(Value::as_str)
                    .map(str::to_string)
            }
            Some("turn/start") => open_turn = true,
            Some("turn/end") => open_turn = false,
            _ => {}
        }
    }
    (label.or(title), !open_turn)
}

pub(crate) fn tool_category_of(name: &str) -> &'static str {
    tool_category(name)
}

fn tool_category(name: &str) -> &'static str {
    match name {
        "pwsh" | "bash" | "shell" | "terminal" | "run_code" | "job_output" | "job_kill" => "shell",
        "edit" | "str_replace" | "str_replace_editor" | "apply_patch" => "edit",
        "write" => "write",
        "read" | "read_image" | "present" => "read",
        "grep" | "glob" | "ls" | "session_query" => "search",
        "web_search" | "web_fetch" => "web",
        "ask_user_question" => "question",
        "todo" | "todo_write" | "create_goal" | "update_goal" | "get_goal" => "todo",
        n if n.starts_with("subagent")
            || matches!(
                n,
                "send_message" | "list_agents" | "wait_agents" | "close_agent"
            ) =>
        {
            "task"
        }
        n if n.starts_with("mcp") => "mcp",
        _ => "other",
    }
}

fn parse_args(raw: &Value) -> Value {
    match raw {
        Value::String(s) => serde_json::from_str(s).unwrap_or_else(|_| json!({ "raw": s })),
        other => other.clone(),
    }
}

fn tool_title(name: &str, input: &Value) -> String {
    let s = |k: &str| input.get(k).and_then(Value::as_str).map(str::to_string);
    let t = match name {
        "pwsh" | "bash" | "shell" | "terminal" => s("command").or_else(|| s("description")),
        "run_code" => s("description")
            .or_else(|| s("label"))
            .or_else(|| s("code").map(|c| c.lines().next().unwrap_or_default().to_string())),
        "read" | "edit" | "write" | "read_image" | "str_replace_editor" => {
            s("path").or_else(|| s("file_path")).or_else(|| s("file"))
        }
        "grep" => s("pattern").map(|p| format!("grep {p}")),
        "glob" => s("pattern"),
        "web_search" => s("query"),
        "web_fetch" => s("url"),
        _ => s("label")
            .or_else(|| s("description"))
            .or_else(|| s("title"))
            .or_else(|| s("message")),
    };
    let t = t.unwrap_or_else(|| name.to_string());
    let one = t.lines().next().unwrap_or_default();
    let mut out: String = one.chars().take(160).collect();
    if one.chars().count() > 160 || t.lines().nth(1).is_some() {
        out.push('…');
    }
    out
}

fn content_text(content: &Value) -> String {
    match content {
        Value::String(s) => s.clone(),
        Value::Array(parts) => parts
            .iter()
            .filter_map(|p| match p {
                Value::String(s) => Some(s.clone()),
                Value::Object(_) if p.get("type").and_then(Value::as_str) == Some("text") => {
                    p.get("text").and_then(Value::as_str).map(str::to_string)
                }
                _ => None,
            })
            .collect::<Vec<_>>()
            .join("\n"),
        _ => String::new(),
    }
}

fn ts_of(rec: &Value) -> String {
    rec.get("time")
        .or_else(|| rec.get("time0"))
        .and_then(Value::as_i64)
        .and_then(chrono::DateTime::from_timestamp_millis)
        .map(|t| t.to_rfc3339_opts(chrono::SecondsFormat::Millis, true))
        .unwrap_or_else(crate::util::now_rfc3339)
}

fn clip(text: &str, max: usize) -> (String, bool) {
    if text.len() <= max {
        return (text.to_string(), false);
    }
    let mut end = max;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    (text[..end].to_string(), true)
}

/// Started-sub-agent ids in a dispatch result ("started subagent <uuid>").
fn started_child(text: &str) -> Option<String> {
    let rest = text.split("subagent ").nth(1)?;
    let id: String = rest
        .chars()
        .take_while(|c| c.is_ascii_alphanumeric() || *c == '-')
        .collect();
    (id.len() >= 8).then_some(id)
}

#[derive(Default)]
pub struct DshParser {
    seen_messages: std::collections::HashSet<String>,
    /// callId -> (event id, data) of tool calls, to update their status.
    calls: HashMap<String, (String, Value)>,
    /// child session id -> (event id, data) of sub-agent rows.
    subagents: HashMap<String, (String, Value)>,
    pub title: Option<String>,
    pub cwd: Option<String>,
    pub session_id: Option<String>,
    pub parent_session: Option<String>,
    pub model: Option<String>,
    /// A turn started and has not ended (the session is working).
    pub busy: bool,
    /// How the last turn ended (`completed`, `interrupted`, `aborted`, `error`).
    pub last_end: Option<String>,
}

/// Rolling status event of a session (hidden from the timeline, drives busy / idle).
pub const ROLLING_STATUS_ID: &str = "status:current";

/// How a `turn/end` record ended the turn: `completed`, `interrupted`, `aborted` (stopped by the
/// user: `{"kind":"aborted","reason":{"kind":"user"}}`) or `error` (e.g. a 402 quota error).
pub fn turn_end_kind(data: &Value) -> String {
    data.pointer("/reason/kind")
        .and_then(Value::as_str)
        .unwrap_or("completed")
        .to_string()
}

/// The session log's rolling status says a turn is running (see [`DshParser`]).
pub fn log_turn_running(log: &parking_lot::Mutex<crate::agents::store::SessionLog>) -> bool {
    log.lock()
        .get(ROLLING_STATUS_ID)
        .and_then(|e| e.data.get("state").and_then(Value::as_str))
        == Some("running")
}

/// A stop by the user or the host, as opposed to a finished or failed turn.
pub fn is_stopped(kind: &str) -> bool {
    matches!(kind, "interrupted" | "aborted" | "cancelled")
}

impl DshParser {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn feed(&mut self, rec: &Value) -> Vec<EventDraft> {
        if rec.pointer("/surfaceOp/op").and_then(Value::as_str) == Some("replace") {
            return vec![];
        }
        let ts = ts_of(rec);
        let data = rec.get("data").cloned().unwrap_or(Value::Null);
        match rec.get("type").and_then(Value::as_str).unwrap_or_default() {
            "session" => {
                self.session_id = rec.get("id").and_then(Value::as_str).map(str::to_string);
                self.cwd = rec.get("cwd").and_then(Value::as_str).map(str::to_string);
                self.parent_session = rec
                    .get("parentSession")
                    .and_then(Value::as_str)
                    .map(str::to_string);
                vec![]
            }
            "session/title" => {
                if let Some(t) = data.get("title").and_then(Value::as_str) {
                    self.title = Some(t.to_string());
                }
                vec![]
            }
            "turn/start" => {
                self.busy = true;
                vec![EventDraft::new(
                    ROLLING_STATUS_ID,
                    "status",
                    ts,
                    json!({ "state": "running" }),
                )]
            }
            "turn/end" => {
                self.busy = false;
                let kind = turn_end_kind(&data);
                let seq = rec.get("seq").and_then(Value::as_u64).unwrap_or(0);
                let turn = data.get("turn").and_then(Value::as_u64);
                let mut out = vec![EventDraft::new(
                    ROLLING_STATUS_ID,
                    "status",
                    ts.clone(),
                    json!({ "state": "idle", "endedAs": kind }),
                )];
                if is_stopped(&kind) {
                    out.push(EventDraft::new(
                        format!("turn-end:{seq}"),
                        "status",
                        ts,
                        json!({ "state": "interrupted", "turn": turn }),
                    ));
                } else if kind == "error" {
                    let message = data
                        .pointer("/reason/error/message")
                        .and_then(Value::as_str)
                        .unwrap_or("the turn failed")
                        .to_string();
                    out.push(EventDraft::new(
                        format!("turn-end:{seq}"),
                        "error",
                        ts,
                        json!({
                            "message": crate::redact::redact_text(&message),
                            "code": data.pointer("/reason/error/code"),
                            "turn": turn,
                        }),
                    ));
                }
                self.last_end = Some(kind);
                out
            }
            "user/message" => self.user_message(&data, &ts),
            "assistant/message" => self.assistant_message(&data, &ts),
            "tool/call" => self.tool_call(
                data.get("callId")
                    .and_then(Value::as_str)
                    .unwrap_or_default(),
                data.get("name").and_then(Value::as_str).unwrap_or_default(),
                data.get("arguments").unwrap_or(&Value::Null),
                None,
                &ts,
            ),
            "tool/ptc-dispatch-start" => self.tool_call(
                data.get("subCallId")
                    .and_then(Value::as_str)
                    .unwrap_or_default(),
                data.get("name").and_then(Value::as_str).unwrap_or_default(),
                data.get("arguments").unwrap_or(&Value::Null),
                data.get("parentCallId").and_then(Value::as_str),
                &ts,
            ),
            "tool/result" => {
                let m = data.get("message").cloned().unwrap_or(Value::Null);
                let call = m
                    .get("toolCallId")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string();
                let err = m.get("isError").and_then(Value::as_bool).unwrap_or(false);
                self.tool_result(
                    &call,
                    err,
                    &content_text(m.get("content").unwrap_or(&Value::Null)),
                    &ts,
                )
            }
            "tool/ptc-dispatch" => {
                let sub = data
                    .get("subCallId")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string();
                let name = data
                    .get("name")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string();
                let err = data
                    .get("isError")
                    .and_then(Value::as_bool)
                    .unwrap_or(false);
                let text = content_text(data.get("content").unwrap_or(&Value::Null));
                let mut out = Vec::new();
                if !self.calls.contains_key(&sub) {
                    // No `-start` record (older logs): create the call now.
                    out.extend(self.tool_call(
                        &sub,
                        &name,
                        data.get("arguments").unwrap_or(&Value::Null),
                        data.get("parentCallId").and_then(Value::as_str),
                        &ts,
                    ));
                }
                out.extend(self.tool_result(&sub, err, &text, &ts));
                if name.starts_with("subagent") && !err {
                    if let Some(child) = started_child(&text) {
                        out.extend(self.subagent_row(&child, Some(&sub), None, &ts));
                    }
                }
                out
            }
            "subagent/catalog" => {
                let child = data
                    .get("childId")
                    .and_then(Value::as_str)
                    .unwrap_or_default();
                if child.is_empty() {
                    return vec![];
                }
                let label = data.get("label").and_then(Value::as_str);
                self.subagent_row(child, None, label, &ts)
            }
            _ => vec![],
        }
    }

    fn user_message(&mut self, data: &Value, ts: &str) -> Vec<EventDraft> {
        let id = data
            .get("id")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        if !id.is_empty() && !self.seen_messages.insert(id.clone()) {
            return vec![];
        }
        let text = content_text(data.get("content").unwrap_or(&Value::Null));
        let kind = data
            .pointer("/source/kind")
            .and_then(Value::as_str)
            .unwrap_or("user");
        let id = if id.is_empty() {
            crate::util::new_id()
        } else {
            id
        };
        match kind {
            "user" if !text.trim().is_empty() => {
                if self.title.is_none() {
                    self.title = Some(clip(&crate::redact::redact_text(&text), 120).0);
                }
                let (p, truncated) = clip(&text, 64 * 1024);
                vec![EventDraft::new(
                    id,
                    "user_message",
                    ts,
                    json!({ "text": p, "truncated": truncated }),
                )]
            }
            "agent-message" => {
                let (p, _) = clip(&text, 4 * 1024);
                vec![EventDraft::new(
                    id,
                    "system",
                    ts,
                    json!({ "subtype": "agent_message", "text": p,
                        "from": data.pointer("/source/senderSessionId") }),
                )]
            }
            // Context the harness injects for the model (time, runtime, skills, goal, …).
            _ => vec![],
        }
    }

    fn assistant_message(&mut self, data: &Value, ts: &str) -> Vec<EventDraft> {
        let m = data.get("message").cloned().unwrap_or(Value::Null);
        let id = m
            .get("id")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        if !id.is_empty() && !self.seen_messages.insert(id.clone()) {
            return vec![];
        }
        let id = if id.is_empty() {
            crate::util::new_id()
        } else {
            id
        };
        if let Some(model) = m.pointer("/source/model").and_then(Value::as_str) {
            self.model = Some(model.to_string());
        }
        let mut out = Vec::new();
        for (i, part) in m
            .get("content")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .enumerate()
        {
            let text = part.get("text").and_then(Value::as_str).unwrap_or_default();
            match part.get("type").and_then(Value::as_str) {
                Some("text") if !text.trim().is_empty() => {
                    let (p, _) = clip(text, 64 * 1024);
                    out.push(EventDraft::new(
                        format!("{id}:{i}"),
                        "assistant_message",
                        ts,
                        json!({ "text": p, "model": self.model }),
                    ));
                }
                Some("reasoning" | "thinking") if !text.trim().is_empty() => {
                    let (p, _) = clip(text, 32 * 1024);
                    out.push(EventDraft::new(
                        format!("{id}:{i}"),
                        "reasoning",
                        ts,
                        json!({ "text": p }),
                    ));
                }
                _ => {} // tool calls come from their own `tool/call` records
            }
        }
        out
    }

    fn tool_call(
        &mut self,
        call_id: &str,
        name: &str,
        raw_args: &Value,
        parent_call: Option<&str>,
        ts: &str,
    ) -> Vec<EventDraft> {
        if call_id.is_empty() {
            return vec![];
        }
        let input = parse_args(raw_args);
        let data = json!({
            "callId": call_id,
            "name": name,
            "title": tool_title(name, &input),
            "category": tool_category(name),
            "input": input,
            "status": "running",
        });
        let id = format!("tc:{call_id}");
        self.calls
            .insert(call_id.to_string(), (id.clone(), data.clone()));
        let mut d = EventDraft::new(id, "tool_call", ts.to_string(), data);
        d.parent_id = parent_call.map(|p| format!("tc:{p}"));
        vec![d]
    }

    fn tool_result(
        &mut self,
        call_id: &str,
        is_error: bool,
        text: &str,
        ts: &str,
    ) -> Vec<EventDraft> {
        if call_id.is_empty() {
            return vec![];
        }
        let mut out = Vec::new();
        let parent = self.calls.get(call_id).map(|(id, _)| id.clone());
        if let Some((id, data)) = self.calls.get_mut(call_id) {
            data["status"] = json!(if is_error { "failed" } else { "completed" });
            let d = EventDraft::new(id.clone(), "tool_call", ts.to_string(), data.clone());
            // Keep the original nesting: re-emitting must not lose the parent link.
            out.push(d);
        }
        let (p, truncated) = clip(text, 16 * 1024);
        let mut r = EventDraft::new(
            format!("res:{call_id}"),
            "tool_result",
            ts.to_string(),
            json!({ "callId": call_id, "isError": is_error, "text": p, "truncated": truncated, "bytes": text.len() }),
        );
        if truncated {
            r.payload = Some(text.to_string());
        }
        r.parent_id = parent;
        out.push(r);
        out
    }

    fn subagent_row(
        &mut self,
        child: &str,
        call: Option<&str>,
        label: Option<&str>,
        ts: &str,
    ) -> Vec<EventDraft> {
        let id = format!("dsh-sa-{child}");
        let entry = self.subagents.entry(child.to_string()).or_insert_with(|| {
            (
                id.clone(),
                json!({ "agentId": child, "status": "running", "background": true }),
            )
        });
        if let Some(l) = label {
            entry.1["description"] = json!(l);
        }
        if let Some(c) = call {
            entry.1["callId"] = json!(c);
        }
        let mut d = EventDraft::new(entry.0.clone(), "subagent", ts.to_string(), entry.1.clone());
        d.parent_id = entry
            .1
            .get("callId")
            .and_then(Value::as_str)
            .map(|c| format!("tc:{c}"));
        vec![d]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn feed_all(p: &mut DshParser, recs: &[Value]) -> Vec<EventDraft> {
        recs.iter().flat_map(|r| p.feed(r)).collect()
    }

    #[test]
    fn records_map_to_agent_events() {
        let mut p = DshParser::new();
        let ev = feed_all(
            &mut p,
            &[
                json!({"type":"session","version":4,"id":"s1","cwd":"D:\\p","createdAt":1}),
                json!({"type":"user/message","seq":1,"time":1792878642440u64,"data":{"id":"u1","role":"user","source":{"kind":"time-context"},"content":[{"type":"text","text":"now is …"}]}}),
                json!({"type":"user/message","seq":2,"time":1792878642441u64,"data":{"id":"u2","role":"user","source":{"kind":"user"},"content":[{"type":"text","text":"Fix the build"}]}}),
                json!({"type":"assistant/message","seq":3,"time":1792878644166u64,"data":{"message":{"id":"a1","role":"assistant","source":{"model":"deepseek-flash"},"content":[{"type":"reasoning","text":"think"},{"type":"text","text":"On it."},{"type":"tool-call","id":"c1","name":"run_code","arguments":"{}"}]}}}),
                json!({"type":"tool/call","seq":4,"time":1792878644167u64,"data":{"callId":"c1","name":"run_code","arguments":"{\"description\":\"build\"}"}}),
                json!({"type":"tool/ptc-dispatch-start","seq":5,"time":1792878644168u64,"data":{"rootCallId":"c1","parentCallId":"c1","subCallId":"c1:ptc:1","name":"pwsh","arguments":{"command":"cargo build"}}}),
                json!({"type":"tool/ptc-dispatch","seq":6,"time":1792878644169u64,"data":{"rootCallId":"c1","parentCallId":"c1","subCallId":"c1:ptc:1","name":"pwsh","arguments":{"command":"cargo build"},"isError":false,"content":[{"type":"text","text":"ok"}]}}),
                json!({"type":"tool/ptc-dispatch","seq":7,"time":1792878644170u64,"data":{"rootCallId":"c1","parentCallId":"c1","subCallId":"c1:ptc:2","name":"subagent_fork","arguments":"{}","isError":false,"content":[{"type":"text","text":"started subagent fcb82824-0293-4586-a9f3-202088ccc35a"}]}}),
                json!({"type":"subagent/catalog","seq":8,"time":1792878644171u64,"data":{"childId":"fcb82824-0293-4586-a9f3-202088ccc35a","label":"Fix LE Audio","mode":"continuable"}}),
                json!({"type":"tool/result","seq":9,"time":1792878645629u64,"data":{"message":{"role":"tool","toolCallId":"c1","content":[{"type":"text","text":"done"}],"isError":false,"id":"r1"}}}),
                json!({"type":"assistant/message","seq":10,"time":1,"surfaceOp":{"op":"replace","startSeq":1,"endSeq":3},"data":{"message":{"id":"x","content":[{"type":"text","text":"summary"}]}}}),
            ],
        );
        let kinds: Vec<_> = ev.iter().map(|e| e.kind).collect();
        assert_eq!(
            kinds.iter().filter(|k| **k == "user_message").count(),
            1,
            "injected context is hidden"
        );
        assert!(kinds.contains(&"reasoning") && kinds.contains(&"assistant_message"));
        let nested = ev.iter().find(|e| e.id == "tc:c1:ptc:1").unwrap();
        assert_eq!(nested.parent_id.as_deref(), Some("tc:c1"));
        assert_eq!(nested.data["category"], "shell");
        let sa: Vec<_> = ev.iter().filter(|e| e.kind == "subagent").collect();
        let last = sa.last().unwrap();
        assert_eq!(last.data["agentId"], "fcb82824-0293-4586-a9f3-202088ccc35a");
        assert_eq!(last.data["description"], "Fix LE Audio");
        assert_eq!(last.parent_id.as_deref(), Some("tc:c1:ptc:2"));
        let done = ev.iter().rev().find(|e| e.id == "tc:c1").unwrap();
        assert_eq!(done.data["status"], "completed");
        assert!(
            !ev.iter()
                .any(|e| e.data.get("text").and_then(Value::as_str) == Some("summary")),
            "replace copies skipped"
        );
        assert_eq!(p.title.as_deref(), Some("Fix the build"));
    }

    #[test]
    fn every_turn_end_kind_is_reported() {
        let mut p = DshParser::new();
        let start = json!({"type":"turn/start","seq":1,"time":1});
        let end = |seq: u64, reason: Value| json!({"type":"turn/end","seq":seq,"time":2,"data":{"turn":1,"reason":reason}});
        let rolling = |d: &[EventDraft]| {
            d.iter()
                .find(|e| e.id == ROLLING_STATUS_ID)
                .map(|e| e.data["state"].as_str().unwrap().to_string())
        };
        assert_eq!(rolling(&p.feed(&start)).as_deref(), Some("running"));
        assert!(p.busy);
        // Stopped by the user in dsh ("aborted"), the case that used to stay "running".
        let d = p.feed(&end(2, json!({"kind":"aborted","reason":{"kind":"user"}})));
        assert!(!p.busy);
        assert_eq!(rolling(&d).as_deref(), Some("idle"));
        assert!(d
            .iter()
            .any(|e| e.kind == "status" && e.data["state"] == "interrupted"));
        p.feed(&start);
        let d = p.feed(&end(3, json!({"kind":"interrupted"})));
        assert!(d
            .iter()
            .any(|e| e.kind == "status" && e.data["state"] == "interrupted"));
        p.feed(&start);
        let d = p.feed(&end(4, json!({"kind":"error","error":{"message":"Insufficient Balance","code":"QUOTA","status":402}})));
        let err = d.iter().find(|e| e.kind == "error").expect("error row");
        assert_eq!(err.data["message"], "Insufficient Balance");
        assert_eq!(p.last_end.as_deref(), Some("error"));
        p.feed(&start);
        let d = p.feed(&end(5, json!({"kind":"completed"})));
        assert_eq!(
            d.len(),
            1,
            "a completed turn adds no row, only the rolling status"
        );
        assert_eq!(rolling(&d).as_deref(), Some("idle"));
    }

    #[test]
    fn frames_decode_and_partial_tail_waits() {
        let a = zstd::encode_all(&b"{\"a\":1}\n"[..], 3).unwrap();
        let b = zstd::encode_all(&b"{\"b\":2}\n"[..], 3).unwrap();
        let mut all = a.clone();
        all.extend_from_slice(&b[..b.len() - 3]); // second frame half written
        let (out, used) = decode_frames(&all);
        assert_eq!(out, b"{\"a\":1}\n");
        assert_eq!(used, a.len());
        all.extend_from_slice(&b[b.len() - 3..]);
        let (out, used) = decode_frames(&all[a.len()..]);
        assert_eq!(out, b"{\"b\":2}\n");
        assert_eq!(used, b.len());
    }

    #[test]
    fn newest_log_version_wins_and_header_is_read() {
        let tmp = tempfile::tempdir().unwrap();
        let d = tmp.path();
        std::fs::write(d.join("session.v3.jsonl"), "{}\n").unwrap();
        let v4 = zstd::encode_all(
            &b"{\"type\":\"session\",\"id\":\"s\",\"parentSession\":\"p\"}\n"[..],
            3,
        )
        .unwrap();
        std::fs::write(d.join("session.v4.jsonl.zstd"), v4).unwrap();
        let f = session_file(d).unwrap();
        assert!(f.ends_with("session.v4.jsonl.zstd"));
        assert_eq!(read_header(&f).unwrap()["parentSession"], "p");
    }
}
