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
    pub git_branch: Option<String>,
    pub pid: Option<u32>,
    pub managed: bool,
    #[serde(skip)]
    pub source_path: Option<String>,
}

impl SessionSummary {
    pub fn project_from_cwd(cwd: &str) -> Option<String> {
        std::path::Path::new(cwd)
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
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
        | "exec_command" | "PowerShell" | "unified_exec" | "write_stdin" => "shell",
        "Edit" | "MultiEdit" | "NotebookEdit" | "apply_patch" => "edit",
        "Write" => "write",
        "Read" | "view_image" => "read",
        "Grep" | "Glob" | "LS" | "ToolSearch" => "search",
        "WebFetch" | "WebSearch" | "web_search" => "web",
        "Task" | "Agent" => "task",
        "TodoWrite" | "update_plan" => "todo",
        "AskUserQuestion" => "question",
        "ExitPlanMode" | "EnterPlanMode" => "plan",
        _ => "other",
    }
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
        "shell" | "exec_command" | "local_shell" => input
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

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

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
