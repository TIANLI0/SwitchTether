//! Claude Code adapter: live-session discovery, transcript observation and
//! SwitchTether-managed sessions.

pub mod live;
pub mod managed;
pub mod transcript;

use std::path::PathBuf;
use std::time::{Duration, Instant};

use parking_lot::Mutex;

pub use live::LiveClaudeSession;

pub struct ClaudeObserver {
    pub claude_dir: PathBuf,
    pub claude_bin: Option<PathBuf>,
    cache: Mutex<Option<(Instant, Vec<LiveClaudeSession>)>>,
    use_cli: bool,
    /// Fingerprint of the registry directory at the last [`Self::registry_changed`] call.
    registry_stamp: Mutex<Option<live::RegistryStamp>>,
}

impl ClaudeObserver {
    pub fn new(claude_dir: PathBuf, claude_bin: Option<PathBuf>) -> Self {
        Self {
            claude_dir,
            use_cli: claude_bin.is_some(),
            claude_bin,
            cache: Mutex::new(None),
            registry_stamp: Mutex::new(None),
        }
    }

    /// True when a registry file was added, removed or rewritten since the previous call. Only
    /// directory metadata is read (no file contents, no process), so it can run every half second:
    /// Claude Code rewrites its file on every status change (busy / idle / waiting).
    pub fn registry_changed(&self) -> bool {
        let now = live::registry_stamp(&self.claude_dir);
        let mut last = self.registry_stamp.lock();
        let changed = last.as_ref() != Some(&now);
        *last = Some(now);
        changed
    }

    /// Live sessions with statuses read from the registry right now: the last full list (see
    /// [`Self::live_sessions`]) updated per process from the registry files. Used when the
    /// registry changed, so a turn shorter than the full refresh interval is still seen.
    pub async fn live_sessions_now(&self) -> Vec<LiveClaudeSession> {
        let base = self.cache.lock().as_ref().map(|(_, v)| v.clone());
        let base = match base {
            Some(v) => v,
            None => return self.live_sessions().await,
        };
        let merged = live::merge_registry(base, live::read_registry(&self.claude_dir));
        // Keep the full list's timestamp: the next full refresh still happens on schedule.
        if let Some((_, v)) = self.cache.lock().as_mut() {
            *v = merged.clone();
        }
        merged
    }

    pub fn detect() -> Self {
        Self::new(live::claude_dir(), live::find_claude_bin())
    }

    /// Live sessions (cached for 3 s to bound process spawning).
    pub async fn live_sessions(&self) -> Vec<LiveClaudeSession> {
        if let Some((at, v)) = self.cache.lock().as_ref() {
            if at.elapsed() < Duration::from_secs(3) {
                return v.clone();
            }
        }
        let mut sessions = None;
        if self.use_cli {
            if let Some(bin) = &self.claude_bin {
                sessions = live::agents_json(bin).await;
            }
        }
        let registry = live::read_registry(&self.claude_dir);
        let sessions = match sessions {
            // `agents --json` omits `entrypoint`; take it from the registry.
            Some(mut v) => {
                for s in &mut v {
                    if s.entrypoint.is_none() {
                        s.entrypoint = registry
                            .iter()
                            .find(|r| r.session_id == s.session_id)
                            .and_then(|r| r.entrypoint.clone());
                    }
                }
                v
            }
            None => registry,
        };
        *self.cache.lock() = Some((Instant::now(), sessions.clone()));
        sessions
    }

    pub fn transcript_path(&self, cwd: Option<&str>, session_id: &str) -> Option<PathBuf> {
        live::transcript_path(&self.claude_dir, cwd, session_id)
    }
}

/// Rows for sub-agents that are still running in an observed session. A transcript records an
/// Agent/Task call's sub-agent only with its result, which for a foreground agent comes when it
/// finishes; meanwhile Claude Code writes the agent's own transcript and a sidecar
/// `<session>/subagents/agent-<id>.meta.json` naming the call (`toolUseId`). For every running
/// Agent/Task call without a `sa:<callId>` row this returns a `running` row (same shape and id as
/// the parser's), so the phone can follow the agent's steps; the result replaces it later.
/// Only the sidecar files are read, and only while such a call is open.
pub fn running_subagent_rows(
    lg: &crate::agents::store::SessionLog,
    transcript: &std::path::Path,
) -> Vec<crate::agents::model::EventDraft> {
    use serde_json::{json, Value};
    let open: Vec<_> = lg
        .iter()
        .filter(|e| {
            e.kind == "tool_call"
                && matches!(
                    e.data.get("name").and_then(Value::as_str),
                    Some("Agent" | "Task")
                )
                && e.data.get("status").and_then(Value::as_str) == Some("running")
        })
        .filter_map(|e| {
            let call = e.data.get("callId").and_then(Value::as_str)?;
            lg.get(&format!("sa:{call}")).is_none().then_some(e)
        })
        .collect();
    if open.is_empty() {
        return Vec::new();
    }
    let dir = transcript.with_extension("").join("subagents");
    let Ok(rd) = std::fs::read_dir(&dir) else {
        return Vec::new();
    };
    let mut metas: Vec<(String, Value)> = Vec::new();
    for e in rd.flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        let Some(agent_id) = name
            .strip_prefix("agent-")
            .and_then(|n| n.strip_suffix(".meta.json"))
        else {
            continue;
        };
        if let Some(v) = std::fs::read(e.path())
            .ok()
            .filter(|b| b.len() < 64 * 1024)
            .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        {
            metas.push((agent_id.to_string(), v));
        }
        if metas.len() > 500 {
            break;
        }
    }
    let mut out = Vec::new();
    for call in open {
        let call_id = call.data["callId"].as_str().unwrap_or_default();
        let Some((agent_id, meta)) = metas
            .iter()
            .find(|(_, m)| m.get("toolUseId").and_then(Value::as_str) == Some(call_id))
        else {
            continue;
        };
        let input = call.data.get("input").cloned().unwrap_or(Value::Null);
        let text = |v: &Value, k: &str| v.get(k).and_then(Value::as_str).map(String::from);
        let mut d = crate::agents::model::EventDraft::new(
            format!("sa:{call_id}"),
            "subagent",
            call.ts.clone(),
            json!({
                "agentId": agent_id,
                "description": text(&input, "description").or_else(|| text(meta, "description")),
                "agentType": text(&input, "subagent_type").or_else(|| text(meta, "agentType")),
                "model": text(meta, "pickedEffortModel"),
                "background": meta.get("requestShape").and_then(Value::as_str) == Some("background")
                    || input.get("run_in_background").and_then(Value::as_bool) == Some(true),
                "status": "running",
                "callId": call_id,
            }),
        );
        d.turn_id = call.turn_id.clone();
        d.parent_id = Some(call_id.to_string());
        out.push(d);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agents::store::SessionLog;
    use serde_json::json;

    #[test]
    fn a_running_foreground_agent_gets_its_row_from_the_sidecar() {
        let tmp = tempfile::tempdir().unwrap();
        let transcript = tmp.path().join("s-1.jsonl");
        let sub = tmp.path().join("s-1").join("subagents");
        std::fs::create_dir_all(&sub).unwrap();
        let mut lg = SessionLog::new("claude:s-1", None);
        let mut p = transcript::ClaudeParser::new();
        for d in p.feed(&json!({"type":"assistant","message":{"id":"m1","content":[
            {"type":"tool_use","id":"toolu_1","name":"Agent","input":{"description":"Scan","subagent_type":"Explore"}}]}}))
        {
            lg.upsert(d, false);
        }
        // No sidecar yet: nothing to add.
        assert!(running_subagent_rows(&lg, &transcript).is_empty());
        std::fs::write(
            sub.join("agent-a42.meta.json"),
            r#"{"agentType":"Explore","description":"Scan","toolUseId":"toolu_1","requestShape":"foreground"}"#,
        )
        .unwrap();
        std::fs::write(
            sub.join("agent-zz.meta.json"),
            r#"{"toolUseId":"toolu_other"}"#,
        )
        .unwrap();
        let rows = running_subagent_rows(&lg, &transcript);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].id, "sa:toolu_1");
        assert_eq!(rows[0].data["agentId"], "a42");
        assert_eq!(rows[0].data["status"], "running");
        assert_eq!(rows[0].data["background"], false);
        assert_eq!(rows[0].parent_id.as_deref(), Some("toolu_1"));
        for d in rows {
            lg.upsert(d, false);
        }
        // Added once; the result replaces it in place.
        assert!(running_subagent_rows(&lg, &transcript).is_empty());
        for d in p.feed(&json!({"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"done"}]},
            "toolUseResult":{"status":"completed","agentId":"a42"}}))
        {
            lg.upsert(d, false);
        }
        let row = lg.get("sa:toolu_1").unwrap();
        assert_eq!(row.data["status"], "completed");
        assert_eq!(lg.iter().filter(|e| e.kind == "subagent").count(), 1);
    }
}
