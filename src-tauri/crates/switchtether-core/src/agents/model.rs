//! Normalized session and agent-event model (docs/PROTOCOL.md §10).

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::protocol::CapStatus;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Origin {
    Cli,
    Vscode,
    DesktopApp,
    Managed,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Liveness {
    Live,
    Ended,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Control {
    Full,
    Observe,
    History,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SessionSummary {
    pub id: String,
    pub agent: String,
    pub origin: Origin,
    pub native_id: String,
    pub title: Option<String>,
    pub cwd: Option<String>,
    pub project: Option<String>,
    pub liveness: Liveness,
    pub status: String,
    pub waiting_for: Option<String>,
    pub control: Control,
    pub created_at_ms: Option<i64>,
    pub last_active_at_ms: Option<i64>,
    pub model: Option<String>,
    /// Effort / reasoning level chosen for a session SwitchTether runs (`low` … `max`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub effort: Option<String>,
    pub git_branch: Option<String>,
    pub pid: Option<u32>,
    pub managed: bool,
    /// Mirrored terminal of a session running in a terminal window (`st-claude`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub terminal_id: Option<String>,
    /// Sub-agents and background tasks (shells, background agents) still running in a live
    /// session. A session whose turn ended (`idle`) with work still going on is not idle for
    /// lists. Absent when unknown (ended sessions, observed sessions whose log is not loaded).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub active_tasks: Option<u32>,
    #[serde(skip)]
    pub source_path: Option<String>,
}

impl SessionSummary {
    /// Project name for a working directory: its folder name, except that agent worktrees
    /// (`<project>/.claude/worktrees/<name>`, `<project>/.worktrees/<name>`) belong to the
    /// project that contains them. Handles `/` and `\` regardless of the host OS.
    pub fn project_from_cwd(cwd: &str) -> Option<String> {
        let parts: Vec<&str> = cwd.split(['/', '\\']).filter(|p| !p.is_empty()).collect();
        let worktree_parent = parts
            .windows(2)
            .position(|w| matches!(w[0], ".claude" | ".codex" | ".cursor") && w[1] == "worktrees");
        let name = match worktree_parent {
            Some(i) if i > 0 => parts[i - 1],
            _ => match parts.iter().position(|p| *p == ".worktrees") {
                Some(i) if i > 0 => parts[i - 1],
                _ => *parts.last()?,
            },
        };
        let name = name.trim_end_matches(':');
        (!name.is_empty()).then(|| name.to_string())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Capabilities {
    pub read_history: Option<CapStatus>,
    pub live_events: Option<CapStatus>,
    pub send_message: Option<CapStatus>,
    pub approve: Option<CapStatus>,
    pub answer_question: Option<CapStatus>,
    pub interrupt: Option<CapStatus>,
    /// Continue this (externally started) conversation in a SwitchTether process.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub continue_session: Option<CapStatus>,
    /// Change model / effort of the running session (`POST /v1/sessions/{id}/options`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub change_model: Option<CapStatus>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AgentEvent {
    pub id: String,
    pub seq: u64,
    pub session_id: String,
    pub turn_id: Option<String>,
    pub ts: String,
    pub kind: String,
    pub parent_id: Option<String>,
    #[serde(default)]
    pub streaming: bool,
    pub data: Value,
}

/// An event before it is placed in a session log (seq/session assigned there).
#[derive(Debug, Clone, PartialEq)]
pub struct EventDraft {
    pub id: String,
    pub turn_id: Option<String>,
    pub ts: String,
    pub kind: &'static str,
    pub parent_id: Option<String>,
    pub streaming: bool,
    pub data: Value,
    /// Full text payload stored out of band (large tool outputs).
    pub payload: Option<String>,
}

impl EventDraft {
    pub fn new(
        id: impl Into<String>,
        kind: &'static str,
        ts: impl Into<String>,
        data: Value,
    ) -> Self {
        Self {
            id: id.into(),
            turn_id: None,
            ts: ts.into(),
            kind,
            parent_id: None,
            streaming: false,
            data,
            payload: None,
        }
    }
}

pub const PREVIEW_LIMIT: usize = 8 * 1024;

/// Truncate text on a char boundary, returning (preview, truncated).
pub fn preview(text: &str, limit: usize) -> (String, bool) {
    if text.len() <= limit {
        return (text.to_string(), false);
    }
    let mut end = limit;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    (text[..end].to_string(), true)
}

/// Category of a tool by name (Claude Code + Codex tool names).
pub fn tool_category(name: &str) -> &'static str {
    if name.starts_with("mcp__") {
        return "mcp";
    }
    match name {
        "Bash" | "BashOutput" | "KillShell" | "KillBash" | "shell" | "local_shell"
        | "exec_command" | "shell_command" | "exec" | "PowerShell" | "unified_exec"
        | "write_stdin" => "shell",
        "Edit" | "MultiEdit" | "NotebookEdit" | "apply_patch" => "edit",
        "Write" => "write",
        "Read" | "view_image" => "read",
        "Grep" | "Glob" | "LS" | "ToolSearch" => "search",
        "WebFetch" | "WebSearch" | "web_search" => "web",
        "Task" | "Agent" | "spawn_agent" | "send_input" | "resume_agent" | "wait"
        | "close_agent" => "task",
        "TodoWrite" | "update_plan" => "todo",
        "AskUserQuestion" | "request_user_input" | "request_user_input_async" => "question",
        "ExitPlanMode" | "EnterPlanMode" => "plan",
        _ => "other",
    }
}

/// Stores what was answered on a question event (`data.answers`), so the card can show it after
/// it is resolved. Answers to secret questions (`isSecret`) are never stored, only a mask.
pub fn record_answers(data: &mut Value, answers: &Value) {
    let Some(map) = answers.as_object() else {
        return;
    };
    let secret: Vec<String> = data
        .get("questions")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter(|q| q.get("isSecret").and_then(Value::as_bool) == Some(true))
        .filter_map(|q| {
            q.get("id")
                .or_else(|| q.get("question"))
                .and_then(Value::as_str)
                .map(String::from)
        })
        .collect();
    let mut out = serde_json::Map::new();
    for (k, v) in map {
        let shown = if secret.iter().any(|s| s == k) {
            Value::String("••••••".into())
        } else {
            match v {
                Value::String(s) => Value::String(crate::redact::redact_text(s)),
                Value::Array(a) => Value::Array(
                    a.iter()
                        .map(|x| match x {
                            Value::String(s) => Value::String(crate::redact::redact_text(s)),
                            other => other.clone(),
                        })
                        .collect(),
                ),
                other => other.clone(),
            }
        };
        out.insert(k.clone(), shown);
    }
    data["answers"] = Value::Object(out);
}

/// Text Codex stores encrypted (a Fernet token, `gAAAA…`, no spaces) instead of the plain text,
/// e.g. a `spawn_agent` message or an inter-agent payload. Never shown.
pub fn is_encrypted_blob(text: &str) -> bool {
    let t = text.trim();
    t.len() > 40 && t.starts_with("gAAAA") && !t.contains(char::is_whitespace)
}

/// Short human title for a tool call.
pub fn tool_title(name: &str, input: &Value) -> String {
    let s = |k: &str| input.get(k).and_then(|v| v.as_str()).map(|s| s.to_string());
    let t = match name {
        "Bash" | "PowerShell" => s("description").or_else(|| s("command")),
        "Edit" | "MultiEdit" | "Write" | "Read" | "NotebookEdit" => {
            s("file_path").or_else(|| s("notebook_path"))
        }
        "Grep" => s("pattern").map(|p| format!("grep {p}")),
        "Glob" => s("pattern"),
        "WebFetch" => s("url"),
        "WebSearch" => s("query"),
        "Task" | "Agent" => s("description"),
        "spawn_agent" => s("message")
            .filter(|m| !is_encrypted_blob(m))
            .or_else(|| s("task_name"))
            .or_else(|| s("agent_type")),
        "send_input" => s("message")
            .filter(|m| !is_encrypted_blob(m))
            .or_else(|| s("id")),
        "resume_agent" | "close_agent" => s("id"),
        "shell" | "exec_command" | "shell_command" | "local_shell" => input
            .get("command")
            .map(|c| match c {
                Value::Array(a) => a
                    .iter()
                    .filter_map(|x| x.as_str())
                    .collect::<Vec<_>>()
                    .join(" "),
                Value::String(s) => s.clone(),
                _ => String::new(),
            })
            .or_else(|| s("cmd")),
        _ => None,
    };
    let t = t.unwrap_or_else(|| {
        if let Some(rest) = name.strip_prefix("mcp__") {
            rest.replacen("__", " · ", 1)
        } else {
            name.to_string()
        }
    });
    let (p, _) = preview(t.lines().next().unwrap_or(""), 200);
    p
}

/// Render a Claude Code `structuredPatch` (array of hunks) as a unified diff.
pub fn structured_patch_to_diff(path: &str, hunks: &Value) -> (String, u32, u32) {
    let mut out = format!("--- a/{path}\n+++ b/{path}\n");
    let (mut add, mut del) = (0u32, 0u32);
    if let Some(hs) = hunks.as_array() {
        for h in hs {
            let g = |k: &str| h.get(k).and_then(|v| v.as_i64()).unwrap_or(0);
            out.push_str(&format!(
                "@@ -{},{} +{},{} @@\n",
                g("oldStart"),
                g("oldLines"),
                g("newStart"),
                g("newLines")
            ));
            if let Some(lines) = h.get("lines").and_then(|l| l.as_array()) {
                for l in lines.iter().filter_map(|l| l.as_str()) {
                    if l.starts_with('+') {
                        add += 1;
                    } else if l.starts_with('-') {
                        del += 1;
                    }
                    out.push_str(l);
                    out.push('\n');
                }
            }
        }
    }
    (out, add, del)
}

/// Count +/- lines of a unified diff.
pub fn diff_stats(diff: &str) -> (u32, u32) {
    let mut a = 0;
    let mut d = 0;
    for l in diff.lines() {
        if l.starts_with("+++") || l.starts_with("---") {
            continue;
        }
        if l.starts_with('+') {
            a += 1;
        } else if l.starts_with('-') {
            d += 1;
        }
    }
    (a, d)
}

#[derive(Clone, Copy)]
enum DiffOp {
    Equal(usize, usize),
    Delete(usize),
    Insert(usize),
}

/// Build a standard unified diff (`--- a/…`, `+++ b/…`, `@@ -a,b +c,d @@`) from `old` and `new`
/// file contents, returning `(diff_text, additions, deletions)`.
///
/// When `old` is `None` (a newly added file), emits a single `+` hunk against `/dev/null`.
/// For modifications, trims unchanged prefix/suffix lines first and computes an LCS diff over
/// the changed window (falling back to a single replace hunk if the changed window is very large),
/// emitting hunks with 3 lines of surrounding context.
pub fn unified_diff(path: &str, old: Option<&str>, new: &str) -> (String, u32, u32) {
    let new_lines: Vec<&str> = new.lines().collect();
    let Some(old_text) = old else {
        if new_lines.is_empty() {
            return (format!("--- /dev/null\n+++ b/{path}\n"), 0, 0);
        }
        let mut out = format!(
            "--- /dev/null\n+++ b/{path}\n@@ -0,0 +1,{} @@\n",
            new_lines.len()
        );
        for l in &new_lines {
            out.push('+');
            out.push_str(l);
            out.push('\n');
        }
        return (out, new_lines.len() as u32, 0);
    };

    let old_lines: Vec<&str> = old_text.lines().collect();
    if old_lines == new_lines {
        return (String::new(), 0, 0);
    }

    let min_len = old_lines.len().min(new_lines.len());
    let mut pre = 0usize;
    while pre < min_len && old_lines[pre] == new_lines[pre] {
        pre += 1;
    }
    let mut suf = 0usize;
    while suf < min_len - pre
        && old_lines[old_lines.len() - 1 - suf] == new_lines[new_lines.len() - 1 - suf]
    {
        suf += 1;
    }

    let a_end = old_lines.len() - suf;
    let b_end = new_lines.len() - suf;
    let a = &old_lines[pre..a_end];
    let b = &new_lines[pre..b_end];

    let mut ops: Vec<DiffOp> = Vec::with_capacity(old_lines.len() + new_lines.len());
    for i in 0..pre {
        ops.push(DiffOp::Equal(i, i));
    }

    const MAX_LCS_CELLS: usize = 500_000;
    if !a.is_empty() && !b.is_empty() && a.len().saturating_mul(b.len()) <= MAX_LCS_CELLS {
        let n = a.len();
        let m = b.len();
        let cols = m + 1;
        let mut dp = vec![0u32; (n + 1) * cols];
        for i in (0..n).rev() {
            let row = i * cols;
            let next_row = (i + 1) * cols;
            for j in (0..m).rev() {
                dp[row + j] = if a[i] == b[j] {
                    dp[next_row + j + 1] + 1
                } else {
                    dp[next_row + j].max(dp[row + j + 1])
                };
            }
        }
        let (mut i, mut j) = (0usize, 0usize);
        while i < n && j < m {
            if a[i] == b[j] {
                ops.push(DiffOp::Equal(pre + i, pre + j));
                i += 1;
                j += 1;
            } else if dp[(i + 1) * cols + j] >= dp[i * cols + j + 1] {
                ops.push(DiffOp::Delete(pre + i));
                i += 1;
            } else {
                ops.push(DiffOp::Insert(pre + j));
                j += 1;
            }
        }
        while i < n {
            ops.push(DiffOp::Delete(pre + i));
            i += 1;
        }
        while j < m {
            ops.push(DiffOp::Insert(pre + j));
            j += 1;
        }
    } else {
        for i in pre..a_end {
            ops.push(DiffOp::Delete(i));
        }
        for j in pre..b_end {
            ops.push(DiffOp::Insert(j));
        }
    }

    for k in 0..suf {
        ops.push(DiffOp::Equal(a_end + k, b_end + k));
    }

    // Group changes into hunks with 3 lines of context.
    const CONTEXT: usize = 3;
    let mut ranges: Vec<(usize, usize)> = Vec::new();
    let mut idx = 0usize;
    while idx < ops.len() {
        if matches!(ops[idx], DiffOp::Equal(_, _)) {
            idx += 1;
            continue;
        }
        let start = idx.saturating_sub(CONTEXT);
        let mut end = idx;
        while end < ops.len() {
            if !matches!(ops[end], DiffOp::Equal(_, _)) {
                end += 1;
                continue;
            }
            // Look ahead: merge if another change appears within 2 * CONTEXT equal lines.
            let eq_start = end;
            while end < ops.len() && matches!(ops[end], DiffOp::Equal(_, _)) {
                end += 1;
            }
            if end < ops.len() && end - eq_start <= 2 * CONTEXT {
                continue;
            }
            end = (eq_start + CONTEXT).min(ops.len());
            break;
        }
        ranges.push((start, end));
        idx = end;
    }

    let mut out = format!("--- a/{path}\n+++ b/{path}\n");
    let mut additions = 0u32;
    let mut deletions = 0u32;

    for (start, end) in ranges {
        let slice = &ops[start..end];
        let mut old_count = 0usize;
        let mut new_count = 0usize;
        let mut first_old: Option<usize> = None;
        let mut first_new: Option<usize> = None;
        for op in slice {
            match *op {
                DiffOp::Equal(oi, ni) => {
                    first_old.get_or_insert(oi);
                    first_new.get_or_insert(ni);
                    old_count += 1;
                    new_count += 1;
                }
                DiffOp::Delete(oi) => {
                    first_old.get_or_insert(oi);
                    old_count += 1;
                }
                DiffOp::Insert(ni) => {
                    first_new.get_or_insert(ni);
                    new_count += 1;
                }
            }
        }
        let old_start = if old_count == 0 {
            first_old.unwrap_or(0)
        } else {
            first_old.unwrap_or(0) + 1
        };
        let new_start = if new_count == 0 {
            first_new.unwrap_or(0)
        } else {
            first_new.unwrap_or(0) + 1
        };
        out.push_str(&format!(
            "@@ -{old_start},{old_count} +{new_start},{new_count} @@\n"
        ));
        for op in slice {
            match *op {
                DiffOp::Equal(oi, _) => {
                    out.push(' ');
                    out.push_str(old_lines[oi]);
                    out.push('\n');
                }
                DiffOp::Delete(oi) => {
                    deletions += 1;
                    out.push('-');
                    out.push_str(old_lines[oi]);
                    out.push('\n');
                }
                DiffOp::Insert(ni) => {
                    additions += 1;
                    out.push('+');
                    out.push_str(new_lines[ni]);
                    out.push('\n');
                }
            }
        }
    }

    (out, additions, deletions)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn session_summary_fixture_active_tasks() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../protocol/fixtures/session_summary.json");
        let v: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        let busy: SessionSummary = serde_json::from_value(v["idleWithTasks"].clone()).unwrap();
        assert_eq!(busy.status, "idle");
        assert_eq!(busy.active_tasks, Some(2));
        // Round trip keeps the field.
        assert_eq!(serde_json::to_value(&busy).unwrap()["activeTasks"], 2);
        // A desktop that does not know the field: absent, and not written back.
        let old: SessionSummary = serde_json::from_value(v["olderDesktop"].clone()).unwrap();
        assert_eq!(old.active_tasks, None);
        assert!(serde_json::to_value(&old)
            .unwrap()
            .get("activeTasks")
            .is_none());
    }

    #[test]
    fn categories_and_titles() {
        assert_eq!(tool_category("Bash"), "shell");
        assert_eq!(tool_category("mcp__github__create_issue"), "mcp");
        assert_eq!(
            tool_title("mcp__github__create_issue", &json!({})),
            "github · create_issue"
        );
        assert_eq!(
            tool_title("Bash", &json!({"command":"ls -la\npwd"})),
            "ls -la"
        );
        assert_eq!(
            tool_title("shell", &json!({"command":["bash","-lc","ls"]})),
            "bash -lc ls"
        );
    }

    #[test]
    fn structured_patch() {
        let (d, a, r) = structured_patch_to_diff(
            "src/a.rs",
            &json!([{ "oldStart": 1, "oldLines": 2, "newStart": 1, "newLines": 2, "lines": [" x", "-y", "+z"] }]),
        );
        assert!(d.contains("@@ -1,2 +1,2 @@\n x\n-y\n+z\n"));
        assert_eq!((a, r), (1, 1));
    }

    #[test]
    fn preview_respects_char_boundaries() {
        let (p, t) = preview("中文中文", 4);
        assert!(t);
        assert_eq!(p, "中");
    }
}

#[cfg(test)]
mod answer_tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn recorded_answers_mask_secret_questions() {
        let mut data = json!({
            "questions": [
                { "id": "env", "question": "Which environment?" },
                { "id": "token", "question": "API token?", "isSecret": true },
                { "question": "Targets?" }
            ]
        });
        record_answers(
            &mut data,
            &json!({ "env": "staging", "token": "sk-live-123456", "Targets?": ["a", "b"] }),
        );
        assert_eq!(data["answers"]["env"], "staging");
        assert_eq!(data["answers"]["token"], "••••••");
        assert_eq!(data["answers"]["Targets?"], json!(["a", "b"]));
    }
}
