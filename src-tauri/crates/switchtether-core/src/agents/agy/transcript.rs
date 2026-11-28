//! Rich parser for Antigravity JSONL transcripts (`transcript_full.jsonl` and `transcript.jsonl`).
//!
//! Antigravity writes one JSON object per step under
//! `<root>/brain/<id>/.system_generated/logs/transcript_full.jsonl` (and a compact variant next to
//! it). A step carries an integer `step_index` and may be rewritten as a new line with the same
//! `step_index` when its status moves from `RUNNING` to `DONE`. Deterministic event ids per
//! `step_index` let [`crate::agents::store::SessionLog::upsert`] update running steps in place
//! while tailing live.

use std::collections::{HashMap, VecDeque};

use serde_json::{json, Value};

use super::STATUS_ID;
use crate::agents::claude::transcript::clean_title;
use crate::agents::model::{preview, EventDraft, PREVIEW_LIMIT};
use crate::redact::{redact_json, redact_text};

fn classify_shell_command(cmd: &str) -> Option<&'static str> {
    let first = cmd
        .trim()
        .trim_start_matches(['&', '(', ' '])
        .split_whitespace()
        .next()
        .unwrap_or("");
    let prog = std::path::Path::new(first)
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or(first);
    if prog.eq_ignore_ascii_case("rg")
        || prog.eq_ignore_ascii_case("grep")
        || prog.eq_ignore_ascii_case("find")
        || prog.eq_ignore_ascii_case("fd")
        || prog.eq_ignore_ascii_case("Get-ChildItem")
        || prog.eq_ignore_ascii_case("Select-String")
    {
        return Some("search");
    }
    if prog.eq_ignore_ascii_case("cat")
        || prog.eq_ignore_ascii_case("head")
        || prog.eq_ignore_ascii_case("tail")
        || prog.eq_ignore_ascii_case("Get-Content")
    {
        return Some("read");
    }
    if prog.eq_ignore_ascii_case("git") {
        let sub = cmd.split_whitespace().nth(1).unwrap_or("");
        if sub == "grep" {
            return Some("search");
        }
    }
    None
}

const SUBAGENT_RESULT_LIMIT: usize = 16 * 1024;

#[derive(Debug, Clone)]
struct TrackedCall {
    event_id: String,
    name: String,
    ts: String,
    turn_id: Option<String>,
    data: Value,
    args: Value,
}

#[derive(Debug, Clone)]
struct SubagentEntry {
    turn_id: Option<String>,
    ts: String,
    data: Value,
}

#[derive(Default)]
pub struct AgyParser {
    pub title: Option<String>,
    pub model: Option<String>,
    pub cwd: Option<String>,
    pub first_ts: Option<String>,
    pub last_ts: Option<String>,
    pub total_tokens: Option<u64>,

    synthetic_step: u64,
    current_turn: Option<String>,
    /// FIFO queue of open tool call ids from the latest `PLANNER_RESPONSE`.
    pending_calls: VecDeque<String>,
    /// Maps a result step's `step_index` to the `call_id` it paired with, so a rewritten step
    /// updates the same tool call instead of consuming the next pending call.
    step_call_map: HashMap<u64, String>,
    tools: HashMap<String, TrackedCall>,
    /// Background task id (`<conv>/task-N` and `task-N`) -> `call_id`.
    bg_tasks: HashMap<String, String>,
    /// Subagent `conversationId` -> `call_id`.
    subagent_calls: HashMap<String, String>,
    /// `call_id` -> subagent row state.
    subagents: HashMap<String, SubagentEntry>,
}

fn s(v: &Value, key: &str) -> Option<String> {
    v.get(key)
        .and_then(Value::as_str)
        .filter(|x| !x.is_empty())
        .map(String::from)
}

impl AgyParser {
    pub fn new() -> Self {
        Self::default()
    }

    fn step_index(&mut self, rec: &Value) -> u64 {
        if let Some(n) = rec.get("step_index").and_then(Value::as_u64) {
            self.synthetic_step = self.synthetic_step.max(n + 1);
            n
        } else {
            let n = self.synthetic_step;
            self.synthetic_step += 1;
            n
        }
    }

    fn running_bg_count(&self) -> usize {
        let running_shells = self
            .tools
            .values()
            .filter(|tc| {
                tc.data.get("background").and_then(Value::as_bool) == Some(true)
                    && tc.data.get("status").and_then(Value::as_str) == Some("running")
            })
            .count();
        let running_subagents = self
            .subagents
            .values()
            .filter(|sa| sa.data.get("status").and_then(Value::as_str) == Some("running"))
            .count();
        running_shells + running_subagents
    }

    fn status_draft(&self, state: &str, detail: Option<&str>, ts: &str) -> EventDraft {
        let mut data = json!({
            "state": state,
            "backgroundTasks": self.running_bg_count(),
        });
        if let Some(d) = detail.filter(|d| !d.is_empty()) {
            data["detail"] = json!(redact_text(d));
        }
        EventDraft::new(STATUS_ID, "status", ts, data)
    }

    /// Feed one JSON line from `transcript_full.jsonl` or `transcript.jsonl`.
    pub fn feed(&mut self, rec: &Value) -> Vec<EventDraft> {
        let ts = s(rec, "created_at")
            .or_else(|| s(rec, "timestamp"))
            .unwrap_or_else(crate::util::now_rfc3339);
        if rec.get("created_at").is_some() || rec.get("timestamp").is_some() {
            if self.first_ts.is_none() {
                self.first_ts = Some(ts.clone());
            }
            self.last_ts = Some(ts.clone());
        }
        let idx = self.step_index(rec);
        match rec.get("type").and_then(Value::as_str) {
            Some("USER_INPUT") => self.user_input(idx, rec, &ts),
            Some("PLANNER_RESPONSE") => self.planner_response(idx, rec, &ts),
            Some("GENERIC") => self.generic_result(idx, rec, &ts),
            Some("SYSTEM_MESSAGE") => self.system_message(idx, rec, &ts),
            Some("CHECKPOINT") => self.checkpoint(idx, rec, &ts),
            Some("ERROR_MESSAGE") => self.error_message(idx, rec, &ts),
            _ => Vec::new(),
        }
    }

    fn user_input(&mut self, idx: u64, rec: &Value, ts: &str) -> Vec<EventDraft> {
        let raw = s(rec, "content").unwrap_or_default();
        if let Some(m) = model_from_settings_change(&raw) {
            self.model = Some(m);
        }
        if self.cwd.is_none() {
            self.cwd = workspace_from_metadata(&raw);
        }
        let cleaned = extract_user_request(&raw);
        let (text, attachments, context) =
            if let Some((user_text, files)) = crate::agents::attachments::split(&cleaned) {
                (user_text, Some(files), Vec::new())
            } else {
                let (t, ctx) = strip_attachment_trailer(&cleaned);
                (t, None, ctx)
            };
        let image_items = extract_media_items(rec.get("media"));
        let images = image_items.len();

        if text.is_empty()
            && images == 0
            && context.is_empty()
            && attachments.as_ref().is_none_or(Vec::is_empty)
        {
            return Vec::new();
        }
        if self.title.is_none() && !text.is_empty() && !text.trim_start().starts_with('/') {
            self.title = clean_title(Some(preview(&redact_text(&text), 120).0));
        }

        let turn_id = format!("step-{idx}:user");
        self.current_turn = Some(turn_id.clone());
        self.pending_calls.clear();

        let redacted = redact_text(&text);
        let (p, truncated) = preview(&redacted, 64 * 1024);
        let mut data = json!({
            "text": p,
            "images": images,
            "imageItems": image_items,
            "context": context,
            "source": "unknown",
            "truncated": truncated,
        });
        if let Some(files) = attachments {
            data["attachments"] = json!(files);
        }
        let mut u = EventDraft::new(turn_id.clone(), "user_message", ts, data);
        u.turn_id = Some(turn_id);
        vec![u, self.status_draft("running", None, ts)]
    }

    fn planner_response(&mut self, idx: u64, rec: &Value, ts: &str) -> Vec<EventDraft> {
        let mut out = Vec::new();
        if let Some(m) = s(rec, "model") {
            self.model = Some(m);
        }
        let step_status = s(rec, "status").unwrap_or_else(|| "DONE".into());
        let step_done = step_status == "DONE" || step_status == "COMPLETED";

        if let Some(think) = s(rec, "thinking").or_else(|| s(rec, "thought")) {
            let redacted = redact_text(think.trim());
            if !redacted.is_empty() {
                let (p, truncated) = preview(&redacted, PREVIEW_LIMIT);
                let mut d = EventDraft::new(
                    format!("step-{idx}:think"),
                    "reasoning",
                    ts,
                    json!({ "text": p, "truncated": truncated }),
                );
                d.turn_id = self.current_turn.clone();
                out.push(d);
            }
        }

        if let Some(content) = s(rec, "content") {
            let redacted = redact_text(content.trim());
            if !redacted.is_empty() {
                let (p, truncated) = preview(&redacted, 64 * 1024);
                let mut d = EventDraft::new(
                    format!("step-{idx}:text"),
                    "assistant_message",
                    ts,
                    json!({
                        "text": p,
                        "model": self.model,
                        "truncated": truncated,
                    }),
                );
                d.turn_id = self.current_turn.clone();
                out.push(d);
            }
        }

        let tool_calls = rec
            .get("tool_calls")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        self.pending_calls.clear();
        for (k, tc) in tool_calls.iter().enumerate() {
            let Some(name) = s(tc, "name") else { continue };
            let call_id = s(tc, "id").unwrap_or_else(|| format!("step-{idx}:call-{k}"));
            let args = tc.get("args").cloned().unwrap_or_else(|| json!({}));
            let already_resolved = self
                .tools
                .get(&call_id)
                .and_then(|prev| prev.data.get("status").and_then(Value::as_str))
                .is_some_and(|st| st != "running" && st != "pending");
            let status = if already_resolved {
                self.tools[&call_id]
                    .data
                    .get("status")
                    .and_then(Value::as_str)
                    .unwrap_or("completed")
            } else {
                "running"
            };
            let (mut data, extras) = build_tool_call(&call_id, &name, &args, status, ts);
            if already_resolved {
                if let Some(prev) = self.tools.get(&call_id) {
                    for keep in [
                        "background",
                        "taskId",
                        "outputFile",
                        "exitCode",
                        "finishedAt",
                        "backgroundSummary",
                    ] {
                        if let Some(v) = prev.data.get(keep) {
                            data[keep] = v.clone();
                        }
                    }
                }
            } else {
                self.pending_calls.push_back(call_id.clone());
            }

            let mut d = EventDraft::new(call_id.clone(), "tool_call", ts, data.clone());
            d.turn_id = self.current_turn.clone();
            out.push(d);
            for mut extra in extras {
                if extra.turn_id.is_none() {
                    extra.turn_id = self.current_turn.clone();
                }
                out.push(extra);
            }
            self.tools.insert(
                call_id,
                TrackedCall {
                    event_id: s(tc, "id").unwrap_or_else(|| format!("step-{idx}:call-{k}")),
                    name,
                    ts: ts.to_string(),
                    turn_id: self.current_turn.clone(),
                    data,
                    args,
                },
            );
        }

        if let Some(usage) = self.usage_draft(&format!("step-{idx}:usage"), rec, ts) {
            out.push(usage);
        }

        let next_state = if !self.pending_calls.is_empty() || !step_done {
            "running"
        } else {
            "idle"
        };
        out.push(self.status_draft(next_state, None, ts));
        out
    }

    fn generic_result(&mut self, idx: u64, rec: &Value, ts: &str) -> Vec<EventDraft> {
        let raw_text = s(rec, "content")
            .or_else(|| s(rec, "output"))
            .unwrap_or_default();
        let step_status = s(rec, "status").unwrap_or_else(|| "DONE".into());

        let call_id = if let Some(existing) = self.step_call_map.get(&idx).cloned() {
            existing
        } else if let Some(next) = self.pending_calls.pop_front() {
            self.step_call_map.insert(idx, next.clone());
            next
        } else {
            // Standalone generic output without a matching tool call.
            return Vec::new();
        };

        let mut out = Vec::new();
        let exit_code = parse_exit_code(&raw_text);
        let is_error = matches!(
            step_status.as_str(),
            "ERROR" | "FAILED" | "CANCELED" | "CANCELLED"
        ) || exit_code.is_some_and(|c| c != 0)
            || raw_text.starts_with("Encountered error in tool execution:")
            || raw_text.contains("\nEncountered error in tool execution:");

        let bg_task = parse_background_task_id(&raw_text);
        let output_file = parse_step_output_file(&raw_text);
        let subagent_id = parse_created_subagent_id(&raw_text);

        let cleaned = strip_timestamps_header(&raw_text);
        let redacted = redact_text(&cleaned);

        let mut turn = self.current_turn.clone();
        let mut tool_name = String::new();
        let mut tool_args = Value::Null;

        if let Some(tc) = self.tools.get_mut(&call_id) {
            turn = tc.turn_id.clone();
            tool_name = tc.name.clone();
            tool_args = tc.args.clone();

            if let Some((full_id, short_id)) = &bg_task {
                tc.data["status"] = json!("running");
                tc.data["background"] = json!(true);
                tc.data["taskId"] = json!(short_id);
                self.bg_tasks.insert(full_id.clone(), call_id.clone());
                self.bg_tasks.insert(short_id.clone(), call_id.clone());
            } else if step_status == "RUNNING" {
                tc.data["status"] = json!("running");
            } else if is_error {
                tc.data["status"] = json!("failed");
            } else {
                tc.data["status"] = json!("completed");
            }
            if let Some(code) = exit_code {
                tc.data["exitCode"] = json!(code);
            }
            if let Some(path) = &output_file {
                tc.data["outputFile"] = json!(path);
            }
            let mut d = EventDraft::new(
                tc.event_id.clone(),
                "tool_call",
                tc.ts.clone(),
                tc.data.clone(),
            );
            d.turn_id = turn.clone();
            out.push(d);
        }

        let (p, truncated) = preview(&redacted, PREVIEW_LIMIT);
        let mut res = EventDraft::new(
            format!("res:{call_id}"),
            "tool_result",
            ts,
            json!({
                "callId": call_id,
                "isError": is_error,
                "text": p,
                "truncated": truncated,
                "bytes": redacted.len(),
                "exitCode": exit_code,
            }),
        );
        if truncated {
            res.payload = Some(redacted.clone());
        }
        res.turn_id = turn.clone();
        out.push(res);

        // `ask_question` resolved once its result step lands.
        if tool_name == "ask_question" {
            if let Some(mut q) =
                build_question_event(&call_id, &tool_args, true, Some(&redacted), ts)
            {
                q.turn_id = turn.clone();
                out.push(q);
            }
        }

        // `invoke_subagent` creates a child conversation; keep a `subagent` row linked under it.
        if tool_name == "invoke_subagent" {
            if let Some(agent_id) = subagent_id {
                let role = s(&tool_args, "Role")
                    .or_else(|| s(&tool_args, "TypeName"))
                    .unwrap_or_else(|| "Subagent".into());
                let agent_type = s(&tool_args, "TypeName").unwrap_or_else(|| "subagent".into());
                let status = if is_error { "failed" } else { "running" };
                let data = json!({
                    "agentId": agent_id,
                    "description": redact_text(&role),
                    "agentType": agent_type,
                    "model": s(&tool_args, "Model").filter(|m| m != "inherit"),
                    "background": true,
                    "status": status,
                    "callId": call_id,
                });
                self.subagent_calls
                    .insert(agent_id.clone(), call_id.clone());
                self.subagents.insert(
                    call_id.clone(),
                    SubagentEntry {
                        turn_id: turn.clone(),
                        ts: ts.to_string(),
                        data: data.clone(),
                    },
                );
                let mut sa = EventDraft::new(format!("sa:{call_id}"), "subagent", ts, data);
                sa.turn_id = turn.clone();
                sa.parent_id = Some(call_id);
                out.push(sa);
            }
        } else if tool_name == "manage_subagents" && !is_error {
            let action = s(&tool_args, "Action").unwrap_or_default();
            let targets: Vec<String> = if action == "kill_all" {
                self.subagents
                    .iter()
                    .filter(|(_, sa)| {
                        sa.data.get("status").and_then(Value::as_str) == Some("running")
                    })
                    .map(|(cid, _)| cid.clone())
                    .collect()
            } else if action == "kill" {
                tool_args
                    .get("ConversationIds")
                    .and_then(Value::as_array)
                    .into_iter()
                    .flatten()
                    .filter_map(Value::as_str)
                    .filter_map(|aid| self.subagent_calls.get(aid).cloned())
                    .collect()
            } else {
                Vec::new()
            };
            for target_call in targets {
                if let Some(sa) = self.subagents.get_mut(&target_call) {
                    if sa.data.get("status").and_then(Value::as_str) == Some("running") {
                        sa.data["status"] = json!("interrupted");
                        sa.data["finishedAt"] = json!(ts);
                        let mut d = EventDraft::new(
                            format!("sa:{target_call}"),
                            "subagent",
                            sa.ts.clone(),
                            sa.data.clone(),
                        );
                        d.turn_id = sa.turn_id.clone();
                        d.parent_id = Some(target_call);
                        out.push(d);
                    }
                }
            }
        } else if tool_name == "send_message" && !is_error {
            if let Some(recipient) = s(&tool_args, "Recipient") {
                if let Some(target_call) = self.subagent_calls.get(&recipient).cloned() {
                    if let Some(sa) = self.subagents.get_mut(&target_call) {
                        if sa.data.get("status").and_then(Value::as_str) != Some("running") {
                            sa.data["status"] = json!("running");
                            if let Some(obj) = sa.data.as_object_mut() {
                                obj.remove("finishedAt");
                            }
                            let mut d = EventDraft::new(
                                format!("sa:{target_call}"),
                                "subagent",
                                sa.ts.clone(),
                                sa.data.clone(),
                            );
                            d.turn_id = sa.turn_id.clone();
                            d.parent_id = Some(target_call);
                            out.push(d);
                        }
                    }
                }
            }
        }

        out.push(self.status_draft("running", None, ts));
        out
    }

    fn system_message(&mut self, idx: u64, rec: &Value, ts: &str) -> Vec<EventDraft> {
        let raw = s(rec, "content")
            .or_else(|| s(rec, "message"))
            .unwrap_or_default();
        let Some((sender, body)) = parse_system_mail(&raw) else {
            // Internal `<SYSTEM_MESSAGE>` reminders ("work efficiently", ephemeral prompts) are
            // harness instructions, never conversation events.
            return Vec::new();
        };

        let mut out = Vec::new();
        let short_sender = sender
            .rsplit_once('/')
            .map(|(_, tail)| tail)
            .unwrap_or(sender.as_str());

        // 1. Background shell task completion wakeup.
        let bg_call = self
            .bg_tasks
            .get(&sender)
            .or_else(|| self.bg_tasks.get(short_sender))
            .cloned();
        if let Some(call_id) = bg_call {
            let exit_code = parse_exit_code(&body);
            let failed = exit_code.is_some_and(|c| c != 0)
                || body.contains("failed with error")
                || body.contains("terminated")
                || body.contains("killed");
            let cleaned = strip_task_finished_prefix(&body);
            let redacted = redact_text(&cleaned);
            let mut turn = self.current_turn.clone();
            if let Some(tc) = self.tools.get_mut(&call_id) {
                turn = tc.turn_id.clone();
                tc.data["status"] = json!(if failed { "failed" } else { "completed" });
                tc.data["finishedAt"] = json!(ts);
                if let Some(code) = exit_code {
                    tc.data["exitCode"] = json!(code);
                }
                let first_line = redacted.lines().find(|l| !l.trim().is_empty());
                if let Some(sum) = first_line {
                    tc.data["backgroundSummary"] = json!(preview(sum.trim(), 200).0);
                }
                if let Some(path) = parse_step_output_file(&body) {
                    tc.data["outputFile"] = json!(path);
                }
                let mut d = EventDraft::new(
                    tc.event_id.clone(),
                    "tool_call",
                    tc.ts.clone(),
                    tc.data.clone(),
                );
                d.turn_id = turn.clone();
                out.push(d);
            }
            let (p, truncated) = preview(&redacted, PREVIEW_LIMIT);
            let mut res = EventDraft::new(
                format!("res:{call_id}"),
                "tool_result",
                ts,
                json!({
                    "callId": call_id,
                    "isError": failed,
                    "text": p,
                    "truncated": truncated,
                    "bytes": redacted.len(),
                    "exitCode": exit_code,
                }),
            );
            if truncated {
                res.payload = Some(redacted);
            }
            res.turn_id = turn;
            out.push(res);
            out.push(self.status_draft("running", None, ts));
            return out;
        }

        // 2. Sub-agent message / completion wakeup.
        if let Some(call_id) = self.subagent_calls.get(&sender).cloned() {
            if let Some(sa) = self.subagents.get_mut(&call_id) {
                let redacted = redact_text(body.trim());
                let (p, truncated) = preview(&redacted, SUBAGENT_RESULT_LIMIT);
                sa.data["status"] = json!("completed");
                sa.data["finishedAt"] = json!(ts);
                if let Some(sum) = first_summary_line(&redacted) {
                    sa.data["summary"] = json!(sum);
                }
                if !p.is_empty() {
                    sa.data["result"] = json!(p);
                    sa.data["resultTruncated"] = json!(truncated);
                }
                let mut d = EventDraft::new(
                    format!("sa:{call_id}"),
                    "subagent",
                    sa.ts.clone(),
                    sa.data.clone(),
                );
                d.turn_id = sa.turn_id.clone();
                d.parent_id = Some(call_id);
                out.push(d);
                out.push(self.status_draft("running", None, ts));
                return out;
            }
        }

        // 3. System notices about sub-agent failures or server restarts stopping background work.
        if sender.eq_ignore_ascii_case("system") {
            let redacted = redact_text(body.trim());
            if let Some(agent_id) = parse_subagent_error_id(&body) {
                if let Some(call_id) = self.subagent_calls.get(&agent_id).cloned() {
                    if let Some(sa) = self.subagents.get_mut(&call_id) {
                        let (p, truncated) = preview(&redacted, SUBAGENT_RESULT_LIMIT);
                        sa.data["status"] = json!("failed");
                        sa.data["finishedAt"] = json!(ts);
                        if let Some(sum) = first_summary_line(&redacted) {
                            sa.data["summary"] = json!(sum);
                        }
                        if !p.is_empty() {
                            sa.data["result"] = json!(p);
                            sa.data["resultTruncated"] = json!(truncated);
                        }
                        let mut d = EventDraft::new(
                            format!("sa:{call_id}"),
                            "subagent",
                            sa.ts.clone(),
                            sa.data.clone(),
                        );
                        d.turn_id = sa.turn_id.clone();
                        d.parent_id = Some(call_id);
                        out.push(d);
                        out.push(self.status_draft("running", None, ts));
                        return out;
                    }
                }
            }
            if body.contains("All your subagents and background tasks have been stopped") {
                let mut any = false;
                let summary = first_summary_line(&redacted);
                for (call_id, sa) in self.subagents.iter_mut() {
                    if sa.data.get("status").and_then(Value::as_str) == Some("running") {
                        any = true;
                        sa.data["status"] = json!("interrupted");
                        sa.data["finishedAt"] = json!(ts);
                        if let Some(sum) = &summary {
                            sa.data["summary"] = json!(sum);
                        }
                        let mut d = EventDraft::new(
                            format!("sa:{call_id}"),
                            "subagent",
                            sa.ts.clone(),
                            sa.data.clone(),
                        );
                        d.turn_id = sa.turn_id.clone();
                        d.parent_id = Some(call_id.clone());
                        out.push(d);
                    }
                }
                for tc in self.tools.values_mut() {
                    if tc.data.get("background").and_then(Value::as_bool) == Some(true)
                        && tc.data.get("status").and_then(Value::as_str) == Some("running")
                    {
                        any = true;
                        tc.data["status"] = json!("interrupted");
                        tc.data["finishedAt"] = json!(ts);
                        if let Some(sum) = &summary {
                            tc.data["backgroundSummary"] = json!(sum);
                        }
                        let mut d = EventDraft::new(
                            tc.event_id.clone(),
                            "tool_call",
                            tc.ts.clone(),
                            tc.data.clone(),
                        );
                        d.turn_id = tc.turn_id.clone();
                        out.push(d);
                    }
                }
                if any {
                    out.push(self.status_draft("idle", None, ts));
                }
                return out;
            }
            return out;
        }

        // 4. Parent / peer agent message delivered into a sub-agent's own transcript (e.g. its
        // initial task prompt at step 0 or a follow-up `send_message`).
        if looks_like_conversation_id(&sender) {
            let redacted = redact_text(body.trim());
            if !redacted.is_empty() {
                if self.title.is_none() {
                    self.title = clean_title(Some(preview(&redacted, 120).0));
                }
                let turn_id = format!("step-{idx}:mail");
                self.current_turn = Some(turn_id.clone());
                self.pending_calls.clear();
                let (p, truncated) = preview(&redacted, 64 * 1024);
                let mut u = EventDraft::new(
                    turn_id.clone(),
                    "user_message",
                    ts,
                    json!({
                        "text": p,
                        "images": 0,
                        "imageItems": [],
                        "context": [],
                        "source": "agent",
                        "truncated": truncated,
                    }),
                );
                u.turn_id = Some(turn_id);
                out.push(u);
                out.push(self.status_draft("running", None, ts));
            }
        }

        out
    }

    fn checkpoint(&mut self, idx: u64, rec: &Value, ts: &str) -> Vec<EventDraft> {
        let mut out = Vec::new();
        let text = redact_text(&s(rec, "content").unwrap_or_default());
        if !text.trim().is_empty() {
            let (p, _) = preview(text.trim(), PREVIEW_LIMIT);
            let mut d = EventDraft::new(
                format!("step-{idx}:checkpoint"),
                "system",
                ts,
                json!({ "subtype": "compact_summary", "text": p }),
            );
            d.turn_id = self.current_turn.clone();
            out.push(d);
        }
        if let Some(usage) = self.usage_draft(&format!("step-{idx}:usage"), rec, ts) {
            out.push(usage);
        }
        out
    }

    fn error_message(&mut self, idx: u64, rec: &Value, ts: &str) -> Vec<EventDraft> {
        let raw = s(rec, "error")
            .or_else(|| s(rec, "message"))
            .or_else(|| s(rec, "content"))
            .unwrap_or_else(|| "Antigravity reported an error".into());
        let msg = redact_text(raw.trim());
        let lower = msg.to_ascii_lowercase();
        let interrupted = lower.contains("interrupt")
            || lower.contains("cancel")
            || lower.contains("aborted by user");

        let mut out = Vec::new();
        while let Some(call_id) = self.pending_calls.pop_front() {
            if let Some(tc) = self.tools.get_mut(&call_id) {
                tc.data["status"] = json!(if interrupted { "interrupted" } else { "failed" });
                let mut d = EventDraft::new(
                    tc.event_id.clone(),
                    "tool_call",
                    tc.ts.clone(),
                    tc.data.clone(),
                );
                d.turn_id = tc.turn_id.clone();
                out.push(d);
            }
        }

        if !interrupted {
            let mut e = EventDraft::new(
                format!("step-{idx}:error"),
                "error",
                ts,
                json!({ "message": preview(&msg, PREVIEW_LIMIT).0 }),
            );
            e.turn_id = self.current_turn.clone();
            out.push(e);
        }
        out.push(self.status_draft(
            if interrupted { "interrupted" } else { "error" },
            Some(&msg),
            ts,
        ));
        out
    }

    fn usage_draft(&mut self, id: &str, rec: &Value, ts: &str) -> Option<EventDraft> {
        let input = rec.get("input_tokens").and_then(Value::as_u64).unwrap_or(0);
        let output = rec
            .get("output_tokens")
            .and_then(Value::as_u64)
            .unwrap_or(0);
        let cache_r = rec
            .get("cache_read_tokens")
            .and_then(Value::as_u64)
            .unwrap_or(0);
        let cache_w = rec
            .get("cache_write_tokens")
            .and_then(Value::as_u64)
            .unwrap_or(0);
        let thought = rec
            .get("reasoning_tokens")
            .or_else(|| rec.get("thought_tokens"))
            .and_then(Value::as_u64)
            .unwrap_or(0);
        let total = input + output + cache_r + cache_w;
        if total == 0 && thought == 0 {
            return None;
        }
        if total > 0 {
            self.total_tokens = Some(total);
        }
        let mut d = EventDraft::new(
            id,
            "usage",
            ts,
            json!({
                "model": self.model,
                "inputTokens": input,
                "outputTokens": output,
                "cacheReadTokens": cache_r,
                "cacheWriteTokens": cache_w,
                "reasoningTokens": thought,
            }),
        );
        d.turn_id = self.current_turn.clone();
        Some(d)
    }
}

/// Maps an Antigravity tool call (`PLANNER_RESPONSE.tool_calls[]` or ACP `tool_call`) to its
/// `tool_call` payload plus any derived events (`plan`, `file_change`, `question`).
pub(crate) fn build_tool_call(
    call_id: &str,
    name: &str,
    args: &Value,
    status: &str,
    ts: &str,
) -> (Value, Vec<EventDraft>) {
    let mut extras = Vec::new();
    let mut files = Vec::new();
    let mut server: Option<String> = None;
    let mut tool: Option<String> = None;

    let path_arg = || {
        s(args, "Path")
            .or_else(|| s(args, "TargetFile"))
            .or_else(|| s(args, "AbsolutePath"))
            .or_else(|| s(args, "SearchPath"))
            .or_else(|| s(args, "DirectoryPath"))
            .or_else(|| s(args, "File"))
            .or_else(|| s(args, "path"))
    };

    let (category, title) = match name {
        "read_file" | "view_file" | "view_file_outline" | "view_code_item" => {
            let p = path_arg().unwrap_or_default();
            if !p.is_empty() {
                let mut f = json!({ "path": p });
                if let Some(st) = args.get("StartLine").and_then(Value::as_u64) {
                    f["offset"] = json!(st);
                    if let Some(end) = args.get("EndLine").and_then(Value::as_u64) {
                        if end >= st {
                            f["limit"] = json!(end - st + 1);
                        }
                    }
                }
                files.push(f);
            }
            ("read", p)
        }
        "edit_file" | "replace_file_content" | "multi_replace_file_content" => {
            let p = path_arg().unwrap_or_default();
            if !p.is_empty() {
                files.push(json!({ "path": p }));
                if let Some(fc) = edit_file_change_draft(call_id, name, &p, args, ts) {
                    extras.push(fc);
                }
            }
            ("edit", p)
        }
        "write_file" | "write_to_file" => {
            let p = path_arg().unwrap_or_default();
            if !p.is_empty() {
                files.push(json!({ "path": p }));
                if let Some(fc) = write_file_change_draft(call_id, &p, args, ts) {
                    extras.push(fc);
                }
            }
            ("write", p)
        }
        "run_command" | "run_shell_command" => {
            let cmd = s(args, "CommandLine")
                .or_else(|| s(args, "command"))
                .unwrap_or_default();
            let cat = classify_shell_command(&cmd).unwrap_or("shell");
            let label = s(args, "toolSummary")
                .or_else(|| (!cmd.is_empty()).then(|| preview(&cmd, 140).0))
                .unwrap_or_default();
            (cat, label)
        }
        "grep_search" | "find_by_name" | "list_dir" => {
            let label = s(args, "Query")
                .or_else(|| s(args, "Pattern"))
                .or_else(&path_arg)
                .unwrap_or_default();
            ("search", label)
        }
        "search_web" | "read_url_content" | "webview_browser" | "browser_subagent" => {
            let label = s(args, "query")
                .or_else(|| s(args, "Url"))
                .or_else(|| s(args, "url"))
                .or_else(|| s(args, "TaskName"))
                .unwrap_or_default();
            ("web", label)
        }
        "update_steps" | "task_boundary" => {
            if let Some(plan) = plan_draft_from_steps(args, ts) {
                extras.push(plan);
            }
            let label = s(args, "TaskName")
                .or_else(|| s(args, "TaskStatus"))
                .unwrap_or_else(|| "Update steps".into());
            ("plan", label)
        }
        "ask_question" => {
            if status == "running" || status == "pending" {
                if let Some(q) = build_question_event(call_id, args, false, None, ts) {
                    extras.push(q);
                }
            }
            ("question", "Ask question".into())
        }
        "invoke_subagent" => {
            let label = s(args, "Role")
                .or_else(|| s(args, "TypeName"))
                .unwrap_or_else(|| "Subagent".into());
            ("task", label)
        }
        "define_subagent" => {
            let label = s(args, "Name").unwrap_or_else(|| "Define subagent".into());
            ("task", label)
        }
        "swarm" => {
            let action = s(args, "action").unwrap_or_else(|| "swarm".into());
            let target = s(args, "name").or_else(|| s(args, "title"));
            let label = match target {
                Some(t) => format!("swarm {action}: {t}"),
                None => format!("swarm {action}"),
            };
            ("task", label)
        }
        "send_message" => {
            let label = s(args, "Message")
                .and_then(|m| first_summary_line(&m))
                .map(|l| preview(&l, 80).0)
                .or_else(|| s(args, "Recipient"))
                .unwrap_or_else(|| "Send message".into());
            ("task", label)
        }
        "manage_task" | "manage_subagents" | "schedule" | "run_workflow" => {
            let label = s(args, "Action")
                .or_else(|| s(args, "Description"))
                .or_else(|| s(args, "Recipient"))
                .unwrap_or_else(|| name.to_string());
            ("task", label)
        }
        other if other.starts_with("mcp_") || other == "call_mcp_tool" => {
            if other == "call_mcp_tool" {
                server = s(args, "server").or_else(|| s(args, "serverName"));
                tool = s(args, "tool").or_else(|| s(args, "toolName"));
            } else if let Some(rest) = other.strip_prefix("mcp_") {
                if let Some((srv, tl)) = rest.split_once('_') {
                    server = Some(srv.to_string());
                    tool = Some(tl.to_string());
                }
            }
            let label = tool.clone().unwrap_or_else(|| other.to_string());
            ("mcp", label)
        }
        "list_resources" | "read_resource" => {
            server = s(args, "ServerName").or_else(|| s(args, "server"));
            let label = s(args, "Uri")
                .or_else(|| server.clone())
                .unwrap_or_default();
            ("mcp", label)
        }
        other => ("other", path_arg().unwrap_or_else(|| other.to_string())),
    };

    // Normalize `CommandLine` -> `command` on shell inputs so Android's command card displays the
    // exact shell command just like Claude Code's `Bash` tool.
    let mut input = args.clone();
    redact_json(&mut input);
    if matches!(name, "run_command" | "run_shell_command") {
        if let Some(obj) = input.as_object_mut() {
            if !obj.contains_key("command") {
                if let Some(cmd) = obj.get("CommandLine").cloned() {
                    obj.insert("command".into(), cmd);
                }
            }
        }
    }
    if name == "invoke_subagent" {
        if let Some(obj) = input.as_object_mut() {
            if !obj.contains_key("prompt") {
                if let Some(p) = obj.get("Prompt").cloned() {
                    obj.insert("prompt".into(), p);
                }
            }
        }
    }

    let data = json!({
        "callId": call_id,
        "name": name,
        "category": category,
        "title": redact_text(&title),
        "status": status,
        "input": input,
        "files": files,
        "server": server,
        "tool": tool,
    });
    (data, extras)
}

pub(crate) fn plan_draft_from_steps(args: &Value, ts: &str) -> Option<EventDraft> {
    let steps = args.get("steps")?.as_array()?;
    let items: Vec<Value> = steps
        .iter()
        .filter_map(|item| {
            let text = s(item, "title").or_else(|| s(item, "content"))?;
            let raw_status = s(item, "status").unwrap_or_else(|| "pending".into());
            let status = match raw_status.as_str() {
                "done" | "completed" | "skipped" => "completed",
                "in_progress" | "running" => "in_progress",
                "failed" => "failed",
                _ => "pending",
            };
            Some(json!({
                "text": redact_text(&text),
                "status": status,
            }))
        })
        .collect();
    if items.is_empty() {
        return None;
    }
    Some(EventDraft::new(
        "plan",
        "plan",
        ts,
        json!({ "items": items }),
    ))
}

pub(crate) fn edit_file_change_draft(
    call_id: &str,
    name: &str,
    path: &str,
    args: &Value,
    ts: &str,
) -> Option<EventDraft> {
    let mut hunks = String::new();
    let mut additions = 0u32;
    let mut deletions = 0u32;

    if name == "multi_replace_file_content" {
        let chunks = args.get("ReplacementChunks").and_then(Value::as_array)?;
        for c in chunks {
            let old = c
                .get("TargetContent")
                .and_then(Value::as_str)
                .unwrap_or_default();
            let new = c
                .get("ReplacementContent")
                .and_then(Value::as_str)
                .unwrap_or_default();
            append_diff_hunk(&mut hunks, &mut additions, &mut deletions, old, new);
        }
    } else {
        let old = args
            .get("Target")
            .or_else(|| args.get("TargetContent"))
            .or_else(|| args.get("oldText"))
            .and_then(Value::as_str)?;
        let new = args
            .get("Replacement")
            .or_else(|| args.get("ReplacementContent"))
            .or_else(|| args.get("newText"))
            .and_then(Value::as_str)
            .unwrap_or_default();
        append_diff_hunk(&mut hunks, &mut additions, &mut deletions, old, new);
    }

    if hunks.is_empty() {
        return None;
    }
    let clean_path = redact_text(path);
    let full = format!("--- a/{clean_path}\n+++ b/{clean_path}\n{hunks}");
    let redacted = redact_text(&full);
    let (dp, truncated) = preview(&redacted, 256 * 1024);
    Some(EventDraft::new(
        format!("fc:{call_id}"),
        "file_change",
        ts,
        json!({
            "callId": call_id,
            "path": clean_path,
            "change": "modify",
            "diff": dp,
            "additions": additions,
            "deletions": deletions,
            "truncated": truncated,
        }),
    ))
}

pub(crate) fn write_file_change_draft(
    call_id: &str,
    path: &str,
    args: &Value,
    ts: &str,
) -> Option<EventDraft> {
    let content = args
        .get("Content")
        .or_else(|| args.get("CodeContent"))
        .or_else(|| args.get("content"))
        .and_then(Value::as_str)?;
    let lines: Vec<&str> = content.lines().collect();
    let count = lines.len();
    let mut body = String::new();
    for l in &lines {
        body.push('+');
        body.push_str(l);
        body.push('\n');
    }
    let clean_path = redact_text(path);
    let change = if args.get("Append").and_then(Value::as_bool) == Some(true) {
        "modify"
    } else {
        "add"
    };
    let full = format!("--- /dev/null\n+++ b/{clean_path}\n@@ -0,0 +1,{count} @@\n{body}");
    let redacted = redact_text(&full);
    let (dp, truncated) = preview(&redacted, 256 * 1024);
    Some(EventDraft::new(
        format!("fc:{call_id}"),
        "file_change",
        ts,
        json!({
            "callId": call_id,
            "path": clean_path,
            "change": change,
            "diff": dp,
            "additions": count as u32,
            "deletions": 0,
            "truncated": truncated,
        }),
    ))
}

fn append_diff_hunk(
    out: &mut String,
    additions: &mut u32,
    deletions: &mut u32,
    old: &str,
    new: &str,
) {
    if old == new {
        return;
    }
    let (diff, add, del) = crate::agents::model::unified_diff("file", Some(old), new);
    if diff.is_empty() {
        return;
    }
    *additions += add;
    *deletions += del;
    // Strip the synthetic `--- a/file\n+++ b/file\n` header; the caller writes the real file header.
    if let Some(hunk_start) = diff.find("@@ ") {
        out.push_str(&diff[hunk_start..]);
    }
}

fn build_question_event(
    call_id: &str,
    args: &Value,
    resolved: bool,
    answer_text: Option<&str>,
    ts: &str,
) -> Option<EventDraft> {
    let qs = args.get("questions")?.as_array()?;
    let normalized: Vec<Value> = qs
        .iter()
        .filter_map(|q| {
            let question = s(q, "question")?;
            let multi = q
                .get("is_multi_select")
                .or_else(|| q.get("multiSelect"))
                .and_then(Value::as_bool)
                .unwrap_or(false);
            let opts: Vec<Value> = q
                .get("options")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(|o| {
                    o.as_str()
                        .map(|lbl| json!({ "label": redact_text(lbl), "description": "" }))
                        .or_else(|| {
                            s(o, "label").map(|lbl| {
                                json!({
                                    "label": redact_text(&lbl),
                                    "description": redact_text(&s(o, "description").unwrap_or_default()),
                                })
                            })
                        })
                })
                .collect();
            Some(json!({
                "question": redact_text(&question),
                "header": "",
                "multiSelect": multi,
                "options": opts,
            }))
        })
        .collect();
    if normalized.is_empty() {
        return None;
    }
    let mut data = json!({
        "requestId": call_id,
        "questions": normalized,
        "resolved": resolved,
    });
    if resolved {
        data["decision"] = json!("answer");
        data["by"] = json!("desktop");
        if let Some(ans) = answer_text.map(str::trim).filter(|a| !a.is_empty()) {
            data["answerSummary"] = json!(preview(ans, 400).0);
        }
    }
    Some(EventDraft::new(
        format!("q:{call_id}"),
        "question",
        ts,
        data,
    ))
}

/// Extracts the user's actual prompt from Antigravity's `<USER_REQUEST>…</USER_REQUEST>` wrapper,
/// stripping `<ADDITIONAL_METADATA>`, `<USER_SETTINGS_CHANGE>` and `<t3_code_instructions>`.
pub fn extract_user_request(raw: &str) -> String {
    if let Some((_, rest)) = raw.split_once("<USER_REQUEST>") {
        let body = match rest.rsplit_once("</USER_REQUEST>") {
            Some((inner, _)) => inner,
            None => rest,
        };
        return body.trim().to_string();
    }
    let mut out = raw.to_string();
    for (open, close) in [
        ("<ADDITIONAL_METADATA>", "</ADDITIONAL_METADATA>"),
        ("<USER_SETTINGS_CHANGE>", "</USER_SETTINGS_CHANGE>"),
        ("<t3_code_instructions>", "</t3_code_instructions>"),
        ("<EPHEMERAL_MESSAGE>", "</EPHEMERAL_MESSAGE>"),
    ] {
        while let Some(start) = out.find(open) {
            if let Some(end_rel) = out[start..].find(close) {
                out.replace_range(start..start + end_rel + close.len(), "");
            } else {
                out.truncate(start);
                break;
            }
        }
    }
    out.trim().to_string()
}

fn model_from_settings_change(raw: &str) -> Option<String> {
    let (_, rest) = raw.split_once("<USER_SETTINGS_CHANGE>")?;
    let (block, _) = rest.split_once("</USER_SETTINGS_CHANGE>")?;
    for line in block.lines() {
        if let Some((_, val)) = line.split_once("Model Selection") {
            let model = val
                .trim()
                .trim_start_matches([':', '-', '>', ' '])
                .rsplit("->")
                .next()?
                .trim()
                .trim_matches(['`', '"', '\'']);
            if !model.is_empty() {
                return Some(model.to_string());
            }
        }
    }
    None
}

fn workspace_from_metadata(raw: &str) -> Option<String> {
    let (_, rest) = raw.split_once("Active Workspaces:")?;
    for line in rest.lines().skip(1).take(8) {
        let trimmed = line.trim();
        let Some(p) = trimmed.strip_prefix("- ") else {
            if !trimmed.is_empty() {
                break;
            }
            continue;
        };
        let p = p.trim();
        if !p.is_empty()
            && !p
                .replace('\\', "/")
                .contains("/.switchtether/remote/attachments")
        {
            return Some(p.to_string());
        }
    }
    None
}

fn strip_attachment_trailer(text: &str) -> (String, Vec<Value>) {
    const MARKER: &str = "[SwitchTether attachments:";
    let Some(idx) = text.rfind(MARKER) else {
        return (text.to_string(), Vec::new());
    };
    let trailer = &text[idx + MARKER.len()..];
    let Some(end) = trailer.find(']') else {
        return (text.to_string(), Vec::new());
    };
    let mut ctx = Vec::new();
    for item in trailer[..end].split(',') {
        let p = item.trim();
        if !p.is_empty() {
            // Either separator: a Windows desktop writes `C:\…` paths, and `Path::file_name`
            // on Unix would keep the whole string.
            let name = p.rsplit(['/', '\\']).find(|n| !n.is_empty()).unwrap_or(p);
            ctx.push(json!({ "kind": "file", "name": name, "path": p }));
        }
    }
    let clean = text[..idx].trim_end().to_string();
    (clean, ctx)
}

fn extract_media_items(media: Option<&Value>) -> Vec<Value> {
    let Some(arr) = media.and_then(Value::as_array) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for m in arr.iter().take(4) {
        let mime = s(m, "mime_type")
            .or_else(|| s(m, "mimeType"))
            .unwrap_or_else(|| "image/png".into());
        if !mime.starts_with("image/") {
            continue;
        }
        if let Some(data) = s(m, "inline_data").or_else(|| s(m, "data")) {
            if data.len() <= 2 * 1024 * 1024 {
                out.push(json!({ "mediaType": mime, "data": data }));
                continue;
            }
        }
        if let Some(uri) = s(m, "uri").or_else(|| s(m, "local_path")) {
            let path = uri.strip_prefix("file:///").map_or(uri.as_str(), |p| {
                if cfg!(windows) {
                    p
                } else {
                    uri.strip_prefix("file://").unwrap_or(p)
                }
            });
            if let Ok(bytes) = std::fs::read(path) {
                if bytes.len() <= 1536 * 1024 {
                    use base64::Engine;
                    let b64 = base64::engine::general_purpose::STANDARD.encode(bytes);
                    out.push(json!({ "mediaType": mime, "data": b64 }));
                }
            }
        }
    }
    out
}

pub(crate) fn parse_exit_code(text: &str) -> Option<i64> {
    for line in text.lines().take(12) {
        let t = line.trim();
        if let Some(rest) = t.strip_prefix("Command exited with code ") {
            let digits = rest.trim_end_matches('.');
            if let Ok(code) = digits.parse::<i64>() {
                return Some(code);
            }
        }
        if t == "The command completed successfully." {
            return Some(0);
        }
    }
    None
}

/// Parses `"Tool is running as a background task with task id: <conv>/task-491"` into
/// `(full_id, short_id)` where `short_id` (`task-491`) satisfies `valid_agent_id`.
pub(crate) fn parse_background_task_id(text: &str) -> Option<(String, String)> {
    const PREFIX: &str = "Tool is running as a background task with task id:";
    for line in text.lines().take(8) {
        if let Some(rest) = line.trim().strip_prefix(PREFIX) {
            let full = rest.trim().to_string();
            if full.is_empty() {
                continue;
            }
            let short = full
                .rsplit_once('/')
                .map(|(_, tail)| tail.to_string())
                .unwrap_or_else(|| full.clone());
            if short
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
            {
                return Some((full, short));
            }
        }
    }
    None
}

/// Whitelisted step output overflow file written by Antigravity:
/// `file:///.../brain/<conv>/.system_generated/steps/<N>/output.txt`.
pub(crate) fn parse_step_output_file(text: &str) -> Option<String> {
    const MARKER: &str = "You can find the full output in a file at file://";
    let idx = text.find(MARKER)?;
    let rest = &text[idx + "You can find the full output in a file at ".len()..];
    let uri_end = rest
        .find(|c: char| c.is_whitespace() || c == '>' || c == ')')
        .unwrap_or(rest.len());
    let uri = rest[..uri_end].trim_end_matches('.');
    let path = file_uri_to_path(uri)?;
    is_valid_agy_step_output_path(std::path::Path::new(&path)).then_some(path)
}

pub(crate) fn is_valid_agy_step_output_path(path: &std::path::Path) -> bool {
    let norm = path.to_string_lossy().replace('\\', "/");
    let Some((prefix, step_and_file)) = norm.rsplit_once("/.system_generated/steps/") else {
        return false;
    };
    let Some(step_dir) = step_and_file.strip_suffix("/output.txt") else {
        return false;
    };
    !prefix.is_empty()
        && prefix.contains("/brain/")
        && !step_dir.is_empty()
        && step_dir.chars().all(|c| c.is_ascii_digit())
}

pub(crate) fn file_uri_to_path(uri: &str) -> Option<String> {
    let rest = uri.strip_prefix("file://")?;
    let decoded = percent_encoding::percent_decode_str(rest)
        .decode_utf8()
        .ok()?;
    let s = decoded.as_ref();
    // On Windows `file:///d:/develop/...` has a leading `/` before `d:`.
    if let Some(stripped) = s.strip_prefix('/') {
        if stripped.as_bytes().get(1) == Some(&b':') {
            return Some(stripped.to_string());
        }
    }
    Some(s.to_string())
}

pub(crate) fn parse_created_subagent_id(text: &str) -> Option<String> {
    let (_, rest) = text.split_once("Created the following subagent:")?;
    let brace = rest.find('{')?;
    let v: Value = serde_json::Deserializer::from_str(&rest[brace..])
        .into_iter::<Value>()
        .next()?
        .ok()?;
    let id = s(&v, "conversationId")?;
    (id.len() <= 64 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')).then_some(id)
}

/// First non-empty line stripped of leading markdown heading/list markers, clipped to 200 bytes.
pub(crate) fn first_summary_line(text: &str) -> Option<String> {
    let line = text
        .lines()
        .map(str::trim)
        .find(|l| !l.is_empty())?
        .trim_start_matches(['#', '-', '*', '>', ' '])
        .trim();
    if line.is_empty() {
        return None;
    }
    Some(preview(line, 200).0)
}

/// Parses `The subagent ... with id "<uuid>" encountered an error ...` into `<uuid>`.
fn parse_subagent_error_id(body: &str) -> Option<String> {
    let (_, rest) = body.split_once("with id \"")?;
    let (id, _) = rest.split_once('"')?;
    let id = id.trim();
    (looks_like_conversation_id(id)).then(|| id.to_string())
}

fn looks_like_conversation_id(sender: &str) -> bool {
    !sender.is_empty()
        && sender.len() <= 64
        && !sender.eq_ignore_ascii_case("system")
        && !sender.contains('/')
        && sender.contains('-')
        && sender
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-')
}

fn strip_timestamps_header(text: &str) -> String {
    let mut lines = text.lines().peekable();
    while let Some(l) = lines.peek() {
        let t = l.trim();
        if t.starts_with("Created At: ") || t.starts_with("Completed At: ") {
            lines.next();
        } else {
            break;
        }
    }
    let out: Vec<&str> = lines.collect();
    out.join("\n")
}

fn strip_task_finished_prefix(text: &str) -> String {
    if let Some((_, rest)) = text.split_once("finished with result:\n") {
        return rest.to_string();
    }
    text.to_string()
}

/// Parses `<SYSTEM_MESSAGE>\n[Message] timestamp=... sender=<id> priority=... content=<body...>`
/// into `(sender, body)`.
///
/// Antigravity prefixes every real system message with prose mentioning `<SYSTEM_MESSAGE>`
/// ("The following is a <SYSTEM_MESSAGE> not actually sent by the user..."), so we locate the
/// `[Message]` line directly instead of splitting on the first `<SYSTEM_MESSAGE>`.
fn parse_system_mail(raw: &str) -> Option<(String, String)> {
    let start = raw.find("\n[Message]").map(|i| i + 1).or_else(|| {
        raw.trim_start()
            .starts_with("[Message]")
            .then(|| raw.find("[Message]"))
            .flatten()
    })?;
    let mut inner = &raw[start..];
    if let Some((before, _)) = inner.rsplit_once("</SYSTEM_MESSAGE>") {
        inner = before;
    }
    let rest = inner.trim().strip_prefix("[Message]")?.trim_start();
    let sender_idx = rest.find("sender=")?;
    let after_sender = &rest[sender_idx + "sender=".len()..];
    let sender_end = after_sender
        .find(char::is_whitespace)
        .unwrap_or(after_sender.len());
    let sender = after_sender[..sender_end].trim().to_string();
    if sender.is_empty() {
        return None;
    }
    let content_idx = after_sender.find("content=")?;
    let body = after_sender[content_idx + "content=".len()..].to_string();
    Some((sender, body))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agents::store::SessionLog;

    #[test]
    fn parses_user_planner_tools_diffs_plans_background_and_subagents() {
        let mut p = AgyParser::new();
        let mut log = SessionLog::new("agy:test", None);
        fn feed(p: &mut AgyParser, log: &mut SessionLog, v: Value) {
            for d in p.feed(&v) {
                log.upsert(d, false);
            }
        }

        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 0,
                "type": "USER_INPUT",
                "status": "DONE",
                "created_at": "2026-11-28T06:00:00Z",
                "content": "<ADDITIONAL_METADATA>\nActive Workspaces:\n- C:\\Users\\u\\.switchtether\\remote\\attachments\n- d:\\develop\\SwitchTether\n</ADDITIONAL_METADATA>\n<USER_SETTINGS_CHANGE>\nModel Selection: -> gemini-4-carbon-high\n</USER_SETTINGS_CHANGE>\n<USER_REQUEST>\nFix the login bug\n\n[SwitchTether attachments: C:\\tmp\\note.txt]\n</USER_REQUEST>"
            }),
        );
        assert_eq!(p.title.as_deref(), Some("Fix the login bug"));
        assert_eq!(p.cwd.as_deref(), Some("d:\\develop\\SwitchTether"));
        assert_eq!(p.model.as_deref(), Some("gemini-4-carbon-high"));
        let u = log.get("step-0:user").unwrap();
        assert_eq!(u.data["text"], "Fix the login bug");
        assert_eq!(u.data["context"][0]["name"], "note.txt");

        // Running planner step rewritten when done -> updates in place without duplicating.
        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 1,
                "type": "PLANNER_RESPONSE",
                "status": "RUNNING",
                "created_at": "2026-11-28T06:00:01Z",
                "model": "gemini-4-carbon-high",
                "thinking": "Checking files...",
            }),
        );
        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 1,
                "type": "PLANNER_RESPONSE",
                "status": "DONE",
                "created_at": "2026-11-28T06:00:02Z",
                "model": "gemini-4-carbon-high",
                "thinking": "Checking files and running background build.",
                "content": "Let me update the plan, edit the file, run a build, and spawn a researcher.",
                "input_tokens": 1200,
                "cache_read_tokens": 800,
                "output_tokens": 150,
                "tool_calls": [
                    {
                        "name": "update_steps",
                        "args": { "steps": [{ "title": "Inspect login", "status": "done" }, { "title": "Patch auth", "status": "in_progress" }] }
                    },
                    {
                        "name": "edit_file",
                        "args": { "Path": "src/auth.rs", "Target": "let ok = false;", "Replacement": "let ok = true;" }
                    },
                    {
                        "name": "run_command",
                        "args": { "CommandLine": "cargo test", "toolSummary": "Run tests" }
                    },
                    {
                        "name": "invoke_subagent",
                        "args": { "TypeName": "research", "Role": "Auth Researcher", "Prompt": "Check token expiry" }
                    }
                ]
            }),
        );
        assert_eq!(
            log.get("plan").unwrap().data["items"][0]["status"],
            "completed"
        );
        assert_eq!(
            log.get("plan").unwrap().data["items"][1]["status"],
            "in_progress"
        );
        assert_eq!(
            log.get("fc:step-1:call-1").unwrap().data["path"],
            "src/auth.rs"
        );
        assert_eq!(log.get("fc:step-1:call-1").unwrap().data["additions"], 1);
        assert_eq!(
            log.get("step-1:call-2").unwrap().data["input"]["command"],
            "cargo test"
        );

        // Results for the 4 tool calls:
        feed(
            &mut p,
            &mut log,
            json!({ "step_index": 2, "type": "GENERIC", "status": "DONE", "created_at": "2026-11-28T06:00:03Z", "content": "OK" }),
        );
        feed(
            &mut p,
            &mut log,
            json!({ "step_index": 3, "type": "GENERIC", "status": "DONE", "created_at": "2026-11-28T06:00:03Z", "content": "Edited src/auth.rs" }),
        );
        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 4,
                "type": "GENERIC",
                "status": "DONE",
                "created_at": "2026-11-28T06:00:04Z",
                "content": "Created At: 2026-11-28T06:00:04Z\nTool is running as a background task with task id: conv-1/task-4\nTask Description: cargo test"
            }),
        );
        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 5,
                "type": "GENERIC",
                "status": "DONE",
                "created_at": "2026-11-28T06:00:05Z",
                "content": "Created the following subagent:\n{\n  \"conversationId\": \"11111111-2222-3333-4444-555555555555\"\n}"
            }),
        );

        assert_eq!(log.get("step-1:call-2").unwrap().data["background"], true);
        assert_eq!(log.get("step-1:call-2").unwrap().data["taskId"], "task-4");
        assert_eq!(log.get("step-1:call-2").unwrap().data["status"], "running");
        assert_eq!(
            log.get("sa:step-1:call-3").unwrap().data["status"],
            "running"
        );
        assert_eq!(
            log.get("sa:step-1:call-3").unwrap().data["agentId"],
            "11111111-2222-3333-4444-555555555555"
        );
        assert_eq!(log.get(STATUS_ID).unwrap().data["backgroundTasks"], 2);

        // Background task finishes via SYSTEM_MESSAGE.
        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 6,
                "type": "SYSTEM_MESSAGE",
                "source": "SYSTEM",
                "status": "DONE",
                "created_at": "2026-11-28T06:00:10Z",
                "content": "<SYSTEM_MESSAGE>\n[Message] timestamp=2026-11-28T06:00:10Z sender=conv-1/task-4 priority=HIGH content=Task id \"conv-1/task-4\" finished with result:\nCommand exited with code 0.\n\nCommand output:\nall passed\n</SYSTEM_MESSAGE>"
            }),
        );
        assert_eq!(
            log.get("step-1:call-2").unwrap().data["status"],
            "completed"
        );
        assert_eq!(log.get("step-1:call-2").unwrap().data["exitCode"], 0);

        // Subagent finishes via SYSTEM_MESSAGE.
        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 7,
                "type": "SYSTEM_MESSAGE",
                "source": "SYSTEM",
                "status": "DONE",
                "created_at": "2026-11-28T06:00:12Z",
                "content": "<SYSTEM_MESSAGE>\n[Message] timestamp=2026-11-28T06:00:12Z sender=11111111-2222-3333-4444-555555555555 priority=HIGH content=Token expiry is 15 minutes.\n</SYSTEM_MESSAGE>"
            }),
        );
        assert_eq!(
            log.get("sa:step-1:call-3").unwrap().data["status"],
            "completed"
        );
        assert_eq!(
            log.get("sa:step-1:call-3").unwrap().data["result"],
            "Token expiry is 15 minutes."
        );
        assert_eq!(
            log.get("sa:step-1:call-3").unwrap().data["summary"],
            "Token expiry is 15 minutes."
        );
        assert_eq!(log.get(STATUS_ID).unwrap().data["backgroundTasks"], 0);
    }

    #[test]
    fn handles_subagent_errors_restart_kill_wakeup_and_child_prompt() {
        // parse_created_subagent_id tolerates trailing braces in prose.
        assert_eq!(
            parse_created_subagent_id(
                "Created the following subagent:\n{\"conversationId\":\"aaaa1111-2222-3333-4444-555566667777\"}\nNote: use {json} carefully."
            )
            .as_deref(),
            Some("aaaa1111-2222-3333-4444-555566667777")
        );

        let mut p = AgyParser::new();
        let mut log = SessionLog::new("agy:parent", None);
        fn feed(p: &mut AgyParser, log: &mut SessionLog, v: Value) {
            for d in p.feed(&v) {
                log.upsert(d, false);
            }
        }

        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 1,
                "type": "PLANNER_RESPONSE",
                "status": "DONE",
                "created_at": "2026-11-28T06:00:01Z",
                "tool_calls": [
                    { "name": "invoke_subagent", "args": { "TypeName": "research", "Role": "UX Auditor", "Prompt": "Audit UI" } },
                    { "name": "invoke_subagent", "args": { "TypeName": "research", "Role": "Perf Auditor", "Prompt": "Audit perf" } },
                    { "name": "swarm", "args": { "action": "add", "name": "worker-1", "brief": "Do work" } }
                ]
            }),
        );
        assert_eq!(log.get("step-1:call-2").unwrap().data["category"], "task");
        assert_eq!(
            log.get("step-1:call-2").unwrap().data["title"],
            "swarm add: worker-1"
        );
        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 2,
                "type": "GENERIC",
                "status": "DONE",
                "created_at": "2026-11-28T06:00:02Z",
                "content": "Created the following subagent:\n{\"conversationId\":\"5cdf7d0f-4e03-443c-891d-57881aae903c\"}"
            }),
        );
        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 3,
                "type": "GENERIC",
                "status": "DONE",
                "created_at": "2026-11-28T06:00:02Z",
                "content": "Created the following subagent:\n{\"conversationId\":\"3bb1705d-4171-4775-9ed5-449801d72c9c\"}"
            }),
        );
        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 4,
                "type": "GENERIC",
                "status": "DONE",
                "created_at": "2026-11-28T06:00:02Z",
                "content": "Added worker-1"
            }),
        );

        // Subagent 1 fails via sender=system notice.
        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 5,
                "type": "SYSTEM_MESSAGE",
                "source": "SYSTEM",
                "status": "DONE",
                "created_at": "2026-11-28T06:01:00Z",
                "content": "<SYSTEM_MESSAGE>\n[Message] timestamp=2026-11-28T06:01:00Z sender=system priority=MESSAGE_PRIORITY_HIGH content=The subagent UX Auditor (research) with id \"5cdf7d0f-4e03-443c-891d-57881aae903c\" encountered an error and has either stopped or failed to start execution: RESOURCE_EXHAUSTED (code 429)\n</SYSTEM_MESSAGE>"
            }),
        );
        assert_eq!(
            log.get("sa:step-1:call-0").unwrap().data["status"],
            "failed"
        );
        assert!(log.get("sa:step-1:call-0").unwrap().data["result"]
            .as_str()
            .unwrap()
            .contains("RESOURCE_EXHAUSTED"));

        // Server restart stops remaining running subagents.
        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 6,
                "type": "SYSTEM_MESSAGE",
                "source": "SYSTEM",
                "status": "DONE",
                "created_at": "2026-11-28T06:02:00Z",
                "content": "<SYSTEM_MESSAGE>\n[Message] timestamp=2026-11-28T06:02:00Z sender=system priority=MESSAGE_PRIORITY_LOW content=[Notice] All your subagents and background tasks have been stopped due to server restart.\n</SYSTEM_MESSAGE>"
            }),
        );
        assert_eq!(
            log.get("sa:step-1:call-1").unwrap().data["status"],
            "interrupted"
        );
        assert_eq!(log.get(STATUS_ID).unwrap().data["backgroundTasks"], 0);

        // Sending a message to a stopped subagent revives it to running, then manage_subagents kill stops it again.
        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 7,
                "type": "PLANNER_RESPONSE",
                "status": "DONE",
                "created_at": "2026-11-28T06:03:00Z",
                "tool_calls": [
                    { "name": "send_message", "args": { "Recipient": "3bb1705d-4171-4775-9ed5-449801d72c9c", "Message": "Please continue checking." } }
                ]
            }),
        );
        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 8,
                "type": "GENERIC",
                "status": "DONE",
                "created_at": "2026-11-28T06:03:01Z",
                "content": "Message sent"
            }),
        );
        assert_eq!(
            log.get("sa:step-1:call-1").unwrap().data["status"],
            "running"
        );

        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 9,
                "type": "PLANNER_RESPONSE",
                "status": "DONE",
                "created_at": "2026-11-28T06:04:00Z",
                "tool_calls": [
                    { "name": "manage_subagents", "args": { "Action": "kill", "ConversationIds": ["3bb1705d-4171-4775-9ed5-449801d72c9c"] } }
                ]
            }),
        );
        feed(
            &mut p,
            &mut log,
            json!({
                "step_index": 10,
                "type": "GENERIC",
                "status": "DONE",
                "created_at": "2026-11-28T06:04:01Z",
                "content": "Killed"
            }),
        );
        assert_eq!(
            log.get("sa:step-1:call-1").unwrap().data["status"],
            "interrupted"
        );

        // Child subagent's own transcript starts with SYSTEM_MESSAGE from parent conversation id.
        let mut child = AgyParser::new();
        let mut child_log = SessionLog::new("agy:child", None);
        for d in child.feed(&json!({
            "step_index": 0,
            "type": "SYSTEM_MESSAGE",
            "source": "SYSTEM",
            "status": "DONE",
            "created_at": "2026-11-28T06:00:05Z",
            "content": "The following is a <SYSTEM_MESSAGE> not actually sent by the user.\n\n<SYSTEM_MESSAGE>\n[Message] timestamp=2026-11-28T06:00:05Z sender=797335f5-5971-4f04-b31f-f69dbc8296a2 priority=MESSAGE_PRIORITY_HIGH content=Audit the Android codebase for Compose performance.\n</SYSTEM_MESSAGE>"
        })) {
            child_log.upsert(d, false);
        }
        let u = child_log.get("step-0:mail").unwrap();
        assert_eq!(u.kind, "user_message");
        assert_eq!(u.data["source"], "agent");
        assert_eq!(
            u.data["text"],
            "Audit the Android codebase for Compose performance."
        );
        assert_eq!(
            child.title.as_deref(),
            Some("Audit the Android codebase for Compose performance.")
        );
    }

    #[test]
    fn parses_standard_switchtether_attachment_block_without_corrupting_context() {
        let mut p = AgyParser::new();
        let mut log = SessionLog::new("agy:att", None);
        let raw = format!(
            "<USER_REQUEST>\nFix this\n\n{}\n- C:\\Users\\u\\.switchtether\\remote\\attachments\\2026-11-29\\id1\\shot.jpg\n</USER_REQUEST>",
            crate::agents::attachments::BLOCK_HEADER
        );
        for d in p.feed(&json!({
            "step_index": 0,
            "type": "USER_INPUT",
            "status": "DONE",
            "created_at": "2026-11-29T00:11:46Z",
            "content": raw,
        })) {
            log.upsert(d, false);
        }
        let u = log.get("step-0:user").unwrap();
        assert_eq!(u.data["text"], "Fix this");
        assert_eq!(u.data["context"], json!([]));
        assert_eq!(u.data["attachments"][0]["name"], "shot.jpg");
    }
}
