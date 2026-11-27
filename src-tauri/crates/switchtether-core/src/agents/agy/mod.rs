//! Google Antigravity (`agy`) sessions SwitchTether runs on its own `agy acp` process.
//!
//! `agy acp` is Antigravity's Agent Client Protocol server (ACP v1, JSON-RPC over stdio; see
//! docs/AGY_REMOTE.md for what was observed with agy 1.5.9). Conversations it opens are agy's
//! own: they are stored in agy's usual data directory and show up in `agy`'s history, CC Switch's
//! session list and SwitchTether's history like any other. This module holds the parts that do
//! not need a process: the mapping of ACP `session/update` notifications onto SwitchTether's
//! event kinds ([`AcpMapper`]), locating the binary, and the model catalog.

pub mod managed;

use std::collections::HashMap;
use std::path::PathBuf;

use serde_json::{json, Value};

use super::model::EventDraft;
use super::store::SessionLog;

/// Rolling status row of a session (shared with the DeepSeek Harness adapter and the
/// active-task count).
pub const STATUS_ID: &str = super::dsh::ROLLING_STATUS_ID;

/// agy's thinking levels (`thought_level` config option), in order.
pub const LEVELS: [&str; 4] = ["low", "medium", "high", "xhigh"];

/// Locate the real `agy` the way the desktop does (`agy_config::real_agy_bin`): PATH (the
/// login-shell PATH once the desktop has provided it), then the install locations. A wrapper
/// script an old CC Switch test build left in `~/.gemini/antigravity-cli/bin` is skipped.
pub fn find_agy_bin() -> Option<PathBuf> {
    let wrapper = |p: &PathBuf| {
        let s = p.to_string_lossy().replace('\\', "/");
        s.contains("/.gemini/antigravity-cli/bin/")
    };
    if let Some(p) = crate::util::which("agy").filter(|p| !wrapper(p)) {
        return Some(p);
    }
    let home = dirs::home_dir()?;
    let mut candidates =
        vec![home
            .join(".local")
            .join("bin")
            .join(if cfg!(windows) { "agy.exe" } else { "agy" })];
    if cfg!(windows) {
        if let Some(local) = dirs::data_local_dir() {
            candidates.push(local.join("agy").join("bin").join("agy.exe"));
        }
    } else {
        candidates.push(PathBuf::from("/opt/homebrew/bin/agy"));
        candidates.push(PathBuf::from("/usr/local/bin/agy"));
    }
    candidates.into_iter().find(|p| p.is_file())
}

/// `agy models` output (`<alias>\t<display name>` per line) as pairs.
pub fn parse_models_output(out: &str) -> Vec<(String, String)> {
    out.lines()
        .filter_map(|l| l.trim().split_once('\t'))
        .map(|(id, name)| (id.trim().to_string(), name.trim().to_string()))
        .filter(|(id, name)| {
            !id.is_empty()
                && !name.is_empty()
                && id
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || "-_.:/".contains(c))
                && !name.chars().any(char::is_control)
        })
        .collect()
}

/// The signed-in CLI's model list (`agy models`, nothing written, no conversation), 20 s limit.
pub async fn list_models(bin: &std::path::Path) -> Option<Vec<(String, String)>> {
    let mut cmd = tokio::process::Command::new(bin);
    if let Some(path) = crate::util::child_path() {
        cmd.env("PATH", path);
    }
    cmd.arg("models")
        .current_dir(std::env::temp_dir())
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true);
    #[cfg(windows)]
    {
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    let out = tokio::time::timeout(std::time::Duration::from_secs(20), cmd.output())
        .await
        .ok()?
        .ok()?;
    let list = parse_models_output(&String::from_utf8_lossy(&out.stdout));
    (!list.is_empty()).then_some(list)
}

/// Splits agy's tiered model id (`gemini-4-neon-high`, "Gemini 4 Neon (High)") into the model
/// and its thinking level, when both the id and the display name say so.
fn split_tier(id: &str, name: &str) -> Option<(String, String, &'static str)> {
    LEVELS.iter().find_map(|lvl| {
        let base = id.strip_suffix(&format!("-{lvl}"))?;
        let (label, tier) = name.rsplit_once(" (")?;
        let tier = tier.strip_suffix(')')?;
        tier.eq_ignore_ascii_case(lvl)
            .then(|| (base.to_string(), label.trim().to_string(), *lvl))
    })
}

/// Model list in the shape `model_catalog` reads (`[{id, displayName, supportedEffortLevels}]`):
/// agy's tiered entries (`agy models`, ACP `availableModels`) grouped into one model with its
/// thinking levels, as agy's own picker shows them (model + "Thinking Level"). An entry without
/// a level stays as it is, with no levels.
pub fn group_models(pairs: &[(String, String)]) -> Value {
    let mut order: Vec<String> = Vec::new();
    let mut groups: HashMap<String, (String, Vec<&'static str>)> = HashMap::new();
    for (id, name) in pairs {
        let (base, label, lvl) = match split_tier(id, name) {
            Some((b, l, lvl)) => (b, l, Some(lvl)),
            None => (id.clone(), name.clone(), None),
        };
        let entry = groups.entry(base.clone()).or_insert_with(|| {
            order.push(base.clone());
            (label, Vec::new())
        });
        if let Some(l) = lvl {
            if !entry.1.contains(&l) {
                entry.1.push(l);
            }
        }
    }
    Value::Array(
        order
            .iter()
            .filter_map(|id| {
                let (label, mut levels) = groups.remove(id)?;
                levels.sort_by_key(|l| LEVELS.iter().position(|x| x == l));
                Some(json!({ "id": id, "displayName": label, "supportedEffortLevels": levels }))
            })
            .collect(),
    )
}

/// `(modelId, name)` pairs of an ACP `models.availableModels` list.
pub fn available_pairs(models: &Value) -> Vec<(String, String)> {
    models
        .get("availableModels")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|m| {
            let id = m.get("modelId").and_then(Value::as_str)?;
            let name = m.get("name").and_then(Value::as_str).unwrap_or(id);
            Some((id.to_string(), name.to_string()))
        })
        .collect()
}

/// The values a `select` config option (`model`, `thought_level`, `mode`) offers.
pub fn option_values(config: &[Value], id: &str) -> Vec<String> {
    config
        .iter()
        .find(|o| o.get("id").and_then(Value::as_str) == Some(id))
        .and_then(|o| o.get("options").and_then(Value::as_array))
        .into_iter()
        .flatten()
        .filter_map(|o| o.get("value").and_then(Value::as_str).map(str::to_string))
        .collect()
}

/// Current value of a config option.
pub fn option_current(config: &[Value], id: &str) -> Option<String> {
    config
        .iter()
        .find(|o| o.get("id").and_then(Value::as_str) == Some(id))
        .and_then(|o| o.get("currentValue").and_then(Value::as_str))
        .map(str::to_string)
}

/// The `model` config value (and thinking level) a phone choice means. The phone may name a
/// grouped model (`gemini-4-neon` + effort `high`), one of agy's tiered ids
/// (`gemini-4-neon-high`) or its display name ("Gemini 4 Neon (High)"), as older desktops served
/// display names. `None`: agy does not offer that model.
pub fn resolve_model(
    config: &[Value],
    available: &[(String, String)],
    model: &str,
    effort: Option<&str>,
) -> Option<(String, Option<String>)> {
    let model = available
        .iter()
        .find(|(_, name)| name == model)
        .map(|(id, _)| id.as_str())
        .unwrap_or(model);
    let values = option_values(config, "model");
    if values.iter().any(|v| v == model) {
        return Some((model.to_string(), effort.map(str::to_string)));
    }
    if let Some(e) = effort {
        let tiered = format!("{model}-{e}");
        if values.contains(&tiered) {
            return Some((tiered, None));
        }
    }
    LEVELS.iter().find_map(|lvl| {
        let base = model.strip_suffix(&format!("-{lvl}"))?;
        values
            .iter()
            .any(|v| v == base)
            .then(|| (base.to_string(), Some(effort.unwrap_or(lvl).to_string())))
    })
}

/// SwitchTether permission mode id (`crate::agents::permission`) → agy's ACP session mode.
pub fn acp_mode(mode: &str) -> Option<&'static str> {
    match mode {
        "default" => Some("default"),
        "acceptEdits" => Some("accept-edits"),
        "auto" => Some("auto"),
        "plan" => Some("plan"),
        _ => None,
    }
}

/// agy's ACP session mode → SwitchTether permission mode id.
pub fn mode_from_acp(mode: &str) -> Option<&'static str> {
    match mode {
        "default" => Some("default"),
        "accept-edits" => Some("acceptEdits"),
        "auto" => Some("auto"),
        "plan" => Some("plan"),
        _ => None,
    }
}

fn clip(text: &str, max: usize) -> (String, bool) {
    let (p, truncated) = super::model::preview(text, max);
    (p, truncated)
}

/// Tool category of an ACP tool call (`kind`, with agy's tool name as a hint).
fn category(kind: Option<&str>, name: Option<&str>) -> &'static str {
    match (kind, name) {
        (_, Some("write_file" | "create_file")) => "write",
        (Some("execute"), _) => "shell",
        (Some("edit" | "delete" | "move"), _) => "edit",
        (Some("read"), _) => "read",
        (Some("search"), _) => "search",
        (Some("fetch"), _) => "web",
        (Some("think"), _) => "other",
        _ => "other",
    }
}

/// SwitchTether tool status of an ACP one.
fn tool_status(s: Option<&str>) -> &'static str {
    match s {
        Some("pending") => "pending",
        Some("in_progress") => "running",
        Some("completed") => "completed",
        Some("failed") => "failed",
        _ => "running",
    }
}

/// Text of ACP tool call `content` blocks and `rawOutput`.
fn tool_output(call: &Value) -> Option<String> {
    let from_content: Vec<String> = call
        .get("content")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter(|c| c.get("type").and_then(Value::as_str) == Some("content"))
        .filter_map(|c| c.pointer("/content/text").and_then(Value::as_str))
        .map(str::to_string)
        .collect();
    if !from_content.is_empty() {
        return Some(from_content.join("\n"));
    }
    call.pointer("/rawOutput/output")
        .and_then(Value::as_str)
        .map(str::to_string)
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Open {
    User,
    Assistant,
    Reasoning,
}

/// Maps ACP `session/update` notifications of one session onto SwitchTether events.
///
/// Text chunks are joined into one streaming event per block (a user message, an answer, a
/// thought) until another kind of update starts; tool calls become `tool_call` rows (id
/// `tc:<toolCallId>`, updated in place) with a `tool_result` (`res:<toolCallId>`) once they
/// finish, file diffs `file_change` rows, `plan` updates one rolling `plan` row per turn. The end
/// of a prompt (its `stopReason`) closes the turn ([`AcpMapper::end_turn`]).
#[derive(Default)]
pub struct AcpMapper {
    /// Prefix of event ids: one per process run, so a resumed conversation never reuses ids of
    /// history already in the log.
    run: String,
    block: u64,
    turn: u64,
    open: Option<(Open, String, String)>,
    tools: HashMap<String, Value>,
    model: Option<String>,
}

impl AcpMapper {
    pub fn new(run: impl Into<String>) -> Self {
        Self {
            run: run.into(),
            ..Default::default()
        }
    }

    /// Model shown on answers (`model` config value).
    pub fn set_model(&mut self, model: Option<String>) {
        self.model = model;
    }

    fn turn_id(&self) -> String {
        format!("agy-{}-t{}", self.run, self.turn)
    }

    fn close(&mut self, log: &mut SessionLog, publish: bool) {
        // A user block is final from its first chunk (its data carries the phone's clientId).
        if let Some((kind, id, text)) = self.open.take().filter(|(k, _, _)| *k != Open::User) {
            log.upsert(self.text_event(kind, &id, &text, false), publish);
        }
    }

    fn text_event(&self, kind: Open, id: &str, text: &str, streaming: bool) -> EventDraft {
        let (kind, data) = match kind {
            Open::User => ("user_message", json!({ "text": text, "source": "phone" })),
            Open::Assistant => (
                "assistant_message",
                json!({ "text": clip(text, 64 * 1024).0, "model": self.model }),
            ),
            Open::Reasoning => (
                "reasoning",
                json!({ "text": clip(text, 32 * 1024).0, "summary": false }),
            ),
        };
        let mut d = EventDraft::new(id, kind, crate::util::now_rfc3339(), data);
        d.streaming = streaming;
        d.turn_id = Some(self.turn_id());
        d
    }

    fn chunk(&mut self, kind: Open, text: &str, log: &mut SessionLog, publish: bool) {
        if !matches!(&self.open, Some((k, _, _)) if *k == kind) {
            self.close(log, publish);
            if kind == Open::User {
                self.turn += 1;
            }
            self.block += 1;
            let id = format!("agy-{}-b{}", self.run, self.block);
            self.open = Some((kind, id, String::new()));
        }
        let Some((_, id, buf)) = self.open.as_mut() else {
            return;
        };
        buf.push_str(text);
        let (id, buf) = (id.clone(), buf.clone());
        if kind == Open::User && log.get(&id).is_some() {
            // Later chunks of a user message only extend its text.
            log.update(&id, publish, |e| e.data["text"] = json!(buf));
            return;
        }
        // A user message is stored final so the phone's optimistic bubble is matched (clientId)
        // at once; answers and thoughts stream until the block closes.
        let streaming = kind != Open::User;
        log.upsert(self.text_event(kind, &id, &buf, streaming), publish);
    }

    /// One `session/update` (`params.update`).
    pub fn apply(&mut self, update: &Value, log: &mut SessionLog, publish: bool) {
        let text = || {
            update
                .pointer("/content/text")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string()
        };
        match update
            .get("sessionUpdate")
            .and_then(Value::as_str)
            .unwrap_or_default()
        {
            "user_message_chunk" => {
                let t = text();
                if update.pointer("/content/type").and_then(Value::as_str) == Some("text") {
                    self.chunk(Open::User, &t, log, publish);
                }
            }
            "agent_message_chunk" => self.chunk(Open::Assistant, &text(), log, publish),
            "agent_thought_chunk" => self.chunk(Open::Reasoning, &text(), log, publish),
            "tool_call" | "tool_call_update" => {
                self.close(log, publish);
                self.tool(update, log, publish);
            }
            "plan" => {
                self.close(log, publish);
                let items: Vec<Value> = update
                    .get("entries")
                    .and_then(Value::as_array)
                    .into_iter()
                    .flatten()
                    .map(|e| {
                        json!({
                            "text": e.get("content").and_then(Value::as_str).unwrap_or_default(),
                            "status": match e.get("status").and_then(Value::as_str) {
                                Some("in_progress") => "in_progress",
                                Some("completed") => "completed",
                                _ => "pending",
                            },
                        })
                    })
                    .collect();
                let mut d = EventDraft::new(
                    format!("agy-{}-plan{}", self.run, self.turn),
                    "plan",
                    crate::util::now_rfc3339(),
                    json!({ "items": items }),
                );
                d.turn_id = Some(self.turn_id());
                log.upsert(d, publish);
            }
            // Session state (models, modes, commands, context) is kept by the session itself.
            _ => {}
        }
    }

    fn tool(&mut self, update: &Value, log: &mut SessionLog, publish: bool) {
        let Some(call_id) = update
            .get("toolCallId")
            .and_then(Value::as_str)
            .map(str::to_string)
        else {
            return;
        };
        // `tool_call_update` repeats only what changed: merge onto what is known.
        let merged = {
            let entry = self
                .tools
                .entry(call_id.clone())
                .or_insert_with(|| json!({}));
            if let (Some(o), Some(u)) = (entry.as_object_mut(), update.as_object()) {
                for (k, v) in u {
                    if !v.is_null() {
                        o.insert(k.clone(), v.clone());
                    }
                }
            }
            entry.clone()
        };
        let name = merged.get("name").and_then(Value::as_str);
        let kind = merged.get("kind").and_then(Value::as_str);
        let status = tool_status(merged.get("status").and_then(Value::as_str));
        let input = merged
            .get("rawInput")
            .or_else(|| merged.get("args"))
            .cloned()
            .unwrap_or(Value::Null);
        let files: Vec<Value> = merged
            .get("locations")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(|l| l.get("path").and_then(Value::as_str))
            .map(|p| json!({ "path": p }))
            .collect();
        let title = merged
            .get("title")
            .and_then(Value::as_str)
            .or(name)
            .unwrap_or("Tool");
        let mut data = json!({
            "callId": call_id,
            "name": name.unwrap_or("tool"),
            "title": crate::redact::redact_text(title),
            "category": category(kind, name),
            "input": input,
            "status": status,
        });
        if kind == Some("execute") {
            if let Some(cmd) = input
                .get("CommandLine")
                .or_else(|| input.get("command"))
                .and_then(Value::as_str)
            {
                data["input"]["command"] = json!(cmd);
            }
        }
        if !files.is_empty() {
            data["files"] = json!(files);
        }
        if let Some(code) = merged
            .pointer("/rawOutput/exit_code")
            .and_then(Value::as_i64)
        {
            data["exitCode"] = json!(code);
        }
        let mut d = EventDraft::new(
            format!("tc:{call_id}"),
            "tool_call",
            crate::util::now_rfc3339(),
            data,
        );
        d.turn_id = Some(self.turn_id());
        log.upsert(d, publish);
        if !matches!(status, "completed" | "failed") {
            return;
        }
        for (i, diff) in merged
            .get("content")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter(|c| c.get("type").and_then(Value::as_str) == Some("diff"))
            .enumerate()
        {
            let new = diff.get("newText").and_then(Value::as_str).unwrap_or("");
            let old = diff.get("oldText").and_then(Value::as_str);
            let mut text = String::new();
            for l in old.unwrap_or("").lines() {
                text.push_str(&format!("-{l}\n"));
            }
            for l in new.lines() {
                text.push_str(&format!("+{l}\n"));
            }
            let (p, truncated) = clip(&text, 16 * 1024);
            let mut f = EventDraft::new(
                format!("fc:{call_id}:{i}"),
                "file_change",
                crate::util::now_rfc3339(),
                json!({
                    "callId": call_id,
                    "path": diff.get("path"),
                    "change": if old.is_none() { "add" } else { "modify" },
                    "diff": p,
                    "additions": new.lines().count(),
                    "deletions": old.map_or(0, |o| o.lines().count()),
                    "truncated": truncated,
                }),
            );
            f.parent_id = Some(format!("tc:{call_id}"));
            f.turn_id = Some(self.turn_id());
            log.upsert(f, publish);
        }
        if let Some(out) = tool_output(&merged) {
            let out = crate::redact::redact_text(&out);
            let (p, truncated) = clip(&out, 8 * 1024);
            let mut data = json!({
                "callId": call_id, "isError": status == "failed", "text": p,
                "truncated": truncated, "bytes": out.len(),
            });
            if let Some(code) = merged
                .pointer("/rawOutput/exit_code")
                .and_then(Value::as_i64)
            {
                data["exitCode"] = json!(code);
            }
            let mut r = EventDraft::new(
                format!("res:{call_id}"),
                "tool_result",
                crate::util::now_rfc3339(),
                data,
            );
            if truncated {
                r.payload = Some(out);
            }
            r.parent_id = Some(format!("tc:{call_id}"));
            r.turn_id = Some(self.turn_id());
            log.upsert(r, publish);
        }
    }

    /// The prompt settled with `stopReason` (`end_turn`, `cancelled`, `max_tokens`, `refusal`,
    /// …) and its `usage`: open blocks are closed, unfinished tool calls marked interrupted, the
    /// turn's token usage and the rolling status recorded.
    pub fn end_turn(
        &mut self,
        stop_reason: &str,
        usage: Option<&Value>,
        log: &mut SessionLog,
        publish: bool,
    ) {
        self.close(log, publish);
        let cancelled = stop_reason == "cancelled";
        let unfinished: Vec<String> = self
            .tools
            .iter()
            .filter(|(_, t)| {
                !matches!(
                    t.get("status").and_then(Value::as_str),
                    Some("completed" | "failed")
                )
            })
            .map(|(k, _)| k.clone())
            .collect();
        for id in unfinished {
            self.tools.remove(&id);
            log.update(&format!("tc:{id}"), publish, |e| {
                e.data["status"] = json!(if cancelled { "interrupted" } else { "failed" });
            });
        }
        self.tools.clear();
        if let Some(u) = usage.filter(|u| u.is_object()) {
            let n = |k: &str| u.get(k).and_then(Value::as_u64).unwrap_or(0);
            let mut d = EventDraft::new(
                format!("agy-{}-usage{}", self.run, self.turn),
                "usage",
                crate::util::now_rfc3339(),
                json!({
                    "inputTokens": n("inputTokens"),
                    "outputTokens": n("outputTokens") + n("thoughtTokens"),
                    "cacheReadTokens": n("cachedReadTokens"),
                    "cacheWriteTokens": n("cachedWriteTokens"),
                }),
            );
            d.turn_id = Some(self.turn_id());
            log.upsert(d, publish);
        }
        let (state, detail) = match stop_reason {
            "end_turn" => ("idle", None),
            "cancelled" => ("interrupted", None),
            other => ("idle", Some(other.to_string())),
        };
        let mut data = json!({ "state": state });
        if let Some(d) = detail {
            data["detail"] = json!(d);
        }
        log.upsert(
            EventDraft::new(STATUS_ID, "status", crate::util::now_rfc3339(), data),
            publish,
        );
    }

    /// A turn starts (the phone's prompt was sent).
    pub fn start_turn(&mut self, log: &mut SessionLog, publish: bool) {
        log.upsert(
            EventDraft::new(
                STATUS_ID,
                "status",
                crate::util::now_rfc3339(),
                json!({ "state": "running" }),
            ),
            publish,
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Recorded `agy acp` traffic (agy 1.5.9), `{dir: in|out, msg}` per line.
    pub(crate) fn fixture(name: &str) -> Vec<Value> {
        let text = match name {
            "new" => include_str!("../../../tests/fixtures/agy/acp_new_session.jsonl"),
            "turns" => include_str!("../../../tests/fixtures/agy/acp_turns.jsonl"),
            "permission" => include_str!("../../../tests/fixtures/agy/acp_permission.jsonl"),
            _ => include_str!("../../../tests/fixtures/agy/acp_load_replay.jsonl"),
        };
        text.lines()
            .filter(|l| !l.trim().is_empty())
            .map(|l| serde_json::from_str(l).unwrap())
            .collect()
    }

    /// Feeds a recording through the mapper like the session does.
    fn replay(lines: &[Value]) -> SessionLog {
        let mut log = SessionLog::new("agy:x", None);
        let mut m = AcpMapper::new("r1");
        for l in lines.iter().filter(|l| l["dir"] == "in") {
            let msg = &l["msg"];
            if msg["method"] == "session/update" {
                m.apply(&msg["params"]["update"], &mut log, false);
            } else if let Some(r) = msg["result"]["stopReason"].as_str() {
                m.end_turn(r, msg["result"].get("usage"), &mut log, false);
            }
        }
        log
    }

    #[test]
    fn recorded_turns_map_to_messages_tools_and_a_cancelled_turn() {
        let log = replay(&fixture("turns"));
        let kinds: Vec<&str> = log.iter().map(|e| e.kind.as_str()).collect();
        let users: Vec<&str> = log
            .iter()
            .filter(|e| e.kind == "user_message")
            .map(|e| e.data["text"].as_str().unwrap())
            .collect();
        assert_eq!(users.len(), 2, "{kinds:?}");
        assert!(users[0].starts_with("Run the shell command"));
        let call = log
            .iter()
            .find(|e| e.kind == "tool_call")
            .expect("tool call");
        assert_eq!(call.data["category"], "shell");
        assert_eq!(call.data["status"], "completed");
        assert_eq!(call.data["input"]["command"], "echo hi");
        assert_eq!(call.data["exitCode"], 0);
        let res = log.iter().find(|e| e.kind == "tool_result").unwrap();
        assert_eq!(res.parent_id.as_deref(), Some(call.id.as_str()));
        assert!(res.data["text"].as_str().unwrap().contains("hi"));
        let answers: Vec<&str> = log
            .iter()
            .filter(|e| e.kind == "assistant_message")
            .map(|e| e.data["text"].as_str().unwrap())
            .collect();
        assert_eq!(answers[0], "The output was `hi`.");
        // The cancelled answer is one block, closed (not streaming), joined from its chunks.
        let last = log
            .iter()
            .filter(|e| e.kind == "assistant_message")
            .last()
            .unwrap();
        assert!(!last.streaming);
        assert!(last.data["text"]
            .as_str()
            .unwrap()
            .starts_with("1. Apple: Crisp and sweet"));
        assert_eq!(log.get(STATUS_ID).unwrap().data["state"], "interrupted");
        let usage: Vec<_> = log.iter().filter(|e| e.kind == "usage").collect();
        assert_eq!(usage.len(), 2);
        assert_eq!(usage[0].data["cacheReadTokens"], 28806);
    }

    #[test]
    fn recorded_file_write_becomes_a_change_and_permission_turn_completes() {
        let log = replay(&fixture("permission"));
        let call = log.iter().find(|e| e.kind == "tool_call").unwrap();
        assert_eq!(call.data["input"]["command"], "Remove-Item hello.txt");
        assert_eq!(call.data["status"], "completed");
        assert_eq!(log.get(STATUS_ID).unwrap().data["state"], "idle");
        // The write of the earlier recording: a `file_change` under its call.
        let mut log = SessionLog::new("agy:x", None);
        let mut m = AcpMapper::new("r1");
        m.apply(
            &json!({"sessionUpdate":"tool_call_update","toolCallId":"w1","name":"write_file","status":"completed","kind":"edit","title":"File hello.txt","locations":[{"path":"/C:/w/hello.txt"}],"rawInput":{"Content":"hi","Path":"C:\\w\\hello.txt"},"rawOutput":{},"content":[{"type":"diff","path":"/C:/w/hello.txt","newText":"hi"}]}),
            &mut log,
            false,
        );
        let fc = log.iter().find(|e| e.kind == "file_change").unwrap();
        assert_eq!(fc.data["change"], "add");
        assert_eq!(fc.data["additions"], 1);
        assert_eq!(fc.parent_id.as_deref(), Some("tc:w1"));
        let tc = log.get("tc:w1").unwrap();
        assert_eq!(tc.data["category"], "write");
        assert_eq!(tc.data["files"][0]["path"], "/C:/w/hello.txt");
    }

    #[test]
    fn models_group_into_thinking_levels_and_resolve_back() {
        let pairs = parse_models_output(
            "gemini-4-neon-high\tGemini 4 Neon (High)\ngemini-4-neon-low\tGemini 4 Neon (Low)\n\
             gemini-pro-orchestrator\tOrchestrator (Carbon)\ngpt-oss-120b-medium\tGPT-OSS 120B (Medium)\n\
             garbage line\nbad id!\tX\n",
        );
        assert_eq!(pairs.len(), 4);
        let g = group_models(&pairs);
        assert_eq!(
            g[0],
            json!({"id": "gemini-4-neon", "displayName": "Gemini 4 Neon", "supportedEffortLevels": ["low", "high"]})
        );
        assert_eq!(g[1]["id"], "gemini-pro-orchestrator");
        assert_eq!(g[1]["supportedEffortLevels"], json!([]));
        assert_eq!(g[2]["id"], "gpt-oss-120b");
        // The recorded session's own config options and models.
        let new = fixture("new");
        let res = new
            .iter()
            .find(|l| l["msg"]["result"]["configOptions"].is_array())
            .unwrap();
        let config = res["msg"]["result"]["configOptions"]
            .as_array()
            .unwrap()
            .clone();
        let avail = available_pairs(&res["msg"]["result"]["models"]);
        assert_eq!(
            option_current(&config, "model").as_deref(),
            Some("gemini-4-neon")
        );
        assert_eq!(
            option_current(&config, "thought_level").as_deref(),
            Some("high")
        );
        let r = |m: &str, e: Option<&str>| resolve_model(&config, &avail, m, e);
        assert_eq!(
            r("gemini-3.8-flash", Some("low")),
            Some(("gemini-3.8-flash".into(), Some("low".into())))
        );
        assert_eq!(
            r("claude-sonnet-5-5-low", None),
            Some(("claude-sonnet-5-5".into(), Some("low".into())))
        );
        assert_eq!(
            r("Gemini 4 Neon (XHigh)", None),
            Some(("gemini-4-neon".into(), Some("xhigh".into())))
        );
        assert_eq!(
            r("gpt-oss-120b", Some("medium")),
            Some(("gpt-oss-120b-medium".into(), None))
        );
        assert_eq!(
            r("gpt-oss-120b-medium", None),
            Some(("gpt-oss-120b-medium".into(), None))
        );
        assert_eq!(r("no-such-model", None), None);
        let grouped = group_models(&avail);
        assert!(grouped
            .as_array()
            .unwrap()
            .iter()
            .any(|m| m["id"] == "claude-opus-5-5"
                && m["supportedEffortLevels"] == json!(["low", "medium", "high"])));
        assert_eq!(acp_mode("acceptEdits"), Some("accept-edits"));
        assert_eq!(mode_from_acp("accept-edits"), Some("acceptEdits"));
        assert_eq!(acp_mode("bypassPermissions"), None);
    }
}
