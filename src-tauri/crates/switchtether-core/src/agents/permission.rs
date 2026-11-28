//! One vocabulary of permission modes for every agent the phone can start or control.
//!
//! The ids are Claude Code's own (`default`, `acceptEdits`, `plan`, `auto`, `dontAsk`,
//! `bypassPermissions`); Codex modes map onto its approval policy, sandbox, approvals reviewer
//! and collaboration mode (the presets Codex Desktop and the TUI's `/permissions` offer), plus
//! the two that have no Claude Code counterpart (`readOnly`, `untrusted`). The phone shows the
//! list the desktop serves (`permissionModes` in `GET /v1/agents/{agent}/models` and in a
//! session's detail), so both agents are picked the same way.

use serde_json::{json, Value};

/// The mode that runs everything without asking (Claude Code `bypassPermissions`, Codex
/// "full access"). Choosing it from the phone needs the same biometric step-up as a remote
/// terminal: it is as powerful as one.
pub const FULL_ACCESS: &str = "bypassPermissions";

struct Mode {
    id: &'static str,
    label: &'static str,
    description: &'static str,
}

const CLAUDE: &[Mode] = &[
    Mode {
        id: "default",
        label: "Ask before edits",
        description: "Asks before editing files and running commands.",
    },
    Mode {
        id: "acceptEdits",
        label: "Accept edits",
        description: "Edits files without asking; asks before running commands.",
    },
    Mode {
        id: "plan",
        label: "Plan",
        description: "Explores and plans without changing anything.",
    },
    Mode {
        id: "auto",
        label: "Auto",
        description: "A classifier approves safe actions; risky ones still ask.",
    },
    Mode {
        id: "dontAsk",
        label: "Don't ask",
        description: "Never asks; anything not allowed beforehand is refused.",
    },
    Mode {
        id: FULL_ACCESS,
        label: "Bypass permissions",
        description: "Runs everything without asking. Like a remote terminal.",
    },
];

const CODEX: &[Mode] = &[
    Mode {
        id: "default",
        label: "Default",
        description:
            "Works in the folder freely; asks to go beyond it (workspace-write, on-request).",
    },
    Mode {
        id: "plan",
        label: "Plan",
        description: "Plan mode: researches and proposes a plan before changing anything.",
    },
    Mode {
        id: "auto",
        label: "Auto review",
        description: "A reviewer agent decides the approval requests instead of you.",
    },
    Mode {
        id: "readOnly",
        label: "Read only",
        description: "Reads files only; asks for anything else (read-only sandbox).",
    },
    Mode {
        id: "untrusted",
        label: "Untrusted",
        description: "Asks before any command that is not known to be safe.",
    },
    Mode {
        id: FULL_ACCESS,
        label: "Full access",
        description: "No sandbox and no approvals (danger-full-access). Like a remote terminal.",
    },
];

/// Google Antigravity's ACP session modes (`agy acp`, `session/set_mode`) plus full access
/// (`--dangerously-skip-permissions`, gated by the terminal biometric step-up).
const AGY: &[Mode] = &[
    Mode {
        id: "default",
        label: "Default",
        description: "Asks before edits and commands, per your Antigravity permission settings.",
    },
    Mode {
        id: "acceptEdits",
        label: "Accept edits",
        description: "Approves file edits in the workspace without asking.",
    },
    Mode {
        id: "auto",
        label: "Auto",
        description: "Approves edits and safe commands; asks before irreversible actions.",
    },
    Mode {
        id: "plan",
        label: "Plan",
        description: "Plans carefully before executing a task.",
    },
    Mode {
        id: FULL_ACCESS,
        label: "Bypass permissions",
        description:
            "Runs everything without asking (--dangerously-skip-permissions). Like a remote terminal.",
    },
];

fn table(agent: &str) -> &'static [Mode] {
    match agent {
        "claude_code" => CLAUDE,
        "codex" => CODEX,
        "agy" => AGY,
        _ => &[],
    }
}

/// The modes the phone offers for `agent` (`[]`: the agent has no modes SwitchTether can set).
pub fn modes(agent: &str) -> Value {
    Value::Array(
        table(agent)
            .iter()
            .map(|m| {
                json!({
                    "id": m.id, "label": m.label, "description": m.description,
                    "dangerous": m.id == FULL_ACCESS,
                })
            })
            .collect(),
    )
}

/// The canonical id of `mode` for `agent`, or `None` when the agent does not have it. Older
/// phones and API clients sent Codex approval policies (`on-request`, `untrusted`); a launcher
/// that remembers one mode across agents may send a Claude Code mode for Codex: `acceptEdits`
/// is Codex's default (it edits inside the workspace without asking) and `dontAsk` has no
/// Codex counterpart.
pub fn normalize(agent: &str, mode: &str) -> Option<&'static str> {
    let mode = mode.trim();
    let mode = match (agent, mode) {
        ("codex", "on-request" | "acceptEdits") => "default",
        ("codex", "read-only") => "readOnly",
        ("codex", "never" | "danger-full-access") => FULL_ACCESS,
        ("claude_code", "manual") => "default",
        ("agy", "accept-edits") => "acceptEdits",
        ("agy", "dangerously-skip-permissions" | "yolo") => FULL_ACCESS,
        (_, m) => m,
    };
    table(agent).iter().find(|m| m.id == mode).map(|m| m.id)
}

/// Whether choosing `mode` (any agent's spelling) needs the biometric step-up (see
/// [`FULL_ACCESS`]).
pub fn needs_step_up(mode: &str) -> bool {
    ["claude_code", "codex", "agy"]
        .iter()
        .any(|a| normalize(a, mode) == Some(FULL_ACCESS))
}

/// Codex settings of a mode: `(approvalPolicy, sandbox mode, approvalsReviewer, collaboration
/// mode kind)`. `thread/start` / `thread/resume` take the sandbox mode string; `turn/start` takes
/// a sandbox policy object (see `codex::managed`).
pub fn codex_settings(mode: &str) -> (&'static str, &'static str, &'static str, &'static str) {
    match mode {
        "plan" => ("on-request", "workspace-write", "user", "plan"),
        "auto" => ("on-request", "workspace-write", "auto_review", "default"),
        "readOnly" => ("on-request", "read-only", "user", "default"),
        "untrusted" => ("untrusted", "workspace-write", "user", "default"),
        FULL_ACCESS => ("never", "danger-full-access", "user", "default"),
        _ => ("on-request", "workspace-write", "user", "default"),
    }
}

/// The Codex mode a thread's own settings amount to (`thread/settings/updated`, a resume
/// response), so a change made in Codex Desktop shows on the phone.
pub fn codex_mode_of(
    approval: Option<&Value>,
    sandbox: Option<&Value>,
    reviewer: Option<&str>,
    collaboration: Option<&str>,
) -> &'static str {
    let sandbox = sandbox
        .and_then(|s| s.get("type").and_then(Value::as_str).or(s.as_str()))
        .unwrap_or("");
    let approval = approval.and_then(Value::as_str).unwrap_or("");
    if collaboration == Some("plan") {
        "plan"
    } else if matches!(sandbox, "dangerFullAccess" | "danger-full-access") && approval == "never" {
        FULL_ACCESS
    } else if matches!(sandbox, "readOnly" | "read-only") {
        "readOnly"
    } else if approval == "untrusted" {
        "untrusted"
    } else if matches!(reviewer, Some("auto_review" | "guardian_subagent")) {
        "auto"
    } else {
        "default"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn modes_are_listed_per_agent_and_full_access_is_flagged() {
        let c = modes("claude_code");
        let ids: Vec<&str> = c
            .as_array()
            .unwrap()
            .iter()
            .map(|m| m["id"].as_str().unwrap())
            .collect();
        assert_eq!(
            ids,
            [
                "default",
                "acceptEdits",
                "plan",
                "auto",
                "dontAsk",
                "bypassPermissions"
            ]
        );
        let x = modes("codex");
        let dangerous: Vec<&str> = x
            .as_array()
            .unwrap()
            .iter()
            .filter(|m| m["dangerous"] == true)
            .map(|m| m["id"].as_str().unwrap())
            .collect();
        assert_eq!(dangerous, ["bypassPermissions"]);
        assert_eq!(modes("dsh"), json!([]));
    }

    #[test]
    fn modes_normalize_across_agents() {
        assert_eq!(normalize("codex", "on-request"), Some("default"));
        assert_eq!(normalize("codex", "acceptEdits"), Some("default"));
        assert_eq!(normalize("codex", "untrusted"), Some("untrusted"));
        assert_eq!(normalize("codex", "dontAsk"), None);
        assert_eq!(normalize("claude_code", "readOnly"), None);
        assert_eq!(normalize("claude_code", "auto"), Some("auto"));
        assert_eq!(normalize("claude_code", "manual"), Some("default"));
        assert_eq!(normalize("dsh", "default"), None);
        assert_eq!(normalize("agy", "accept-edits"), Some("acceptEdits"));
        assert_eq!(normalize("agy", "plan"), Some("plan"));
        assert_eq!(normalize("agy", "bypassPermissions"), Some(FULL_ACCESS));
        assert_eq!(
            normalize("agy", "dangerously-skip-permissions"),
            Some(FULL_ACCESS)
        );
        assert!(needs_step_up("bypassPermissions"));
        assert!(needs_step_up("danger-full-access"));
        assert!(needs_step_up("dangerously-skip-permissions"));
        assert!(!needs_step_up("auto"));
    }

    #[test]
    fn codex_settings_round_trip() {
        for m in [
            "default",
            "plan",
            "auto",
            "readOnly",
            "untrusted",
            FULL_ACCESS,
        ] {
            let (a, s, r, c) = codex_settings(m);
            let back = codex_mode_of(Some(&json!(a)), Some(&json!(s)), Some(r), Some(c));
            assert_eq!(back, m);
        }
        // The thread settings' sandbox is a policy object.
        assert_eq!(
            codex_mode_of(
                Some(&json!("never")),
                Some(&json!({"type": "dangerFullAccess"})),
                Some("user"),
                Some("default")
            ),
            FULL_ACCESS
        );
    }
}
