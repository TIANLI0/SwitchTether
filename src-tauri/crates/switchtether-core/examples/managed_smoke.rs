//! Live smoke test of SwitchTether-managed agent sessions (spends a few model
//! tokens; run manually, never in CI):
//!
//!   cargo run -p switchtether-core --example managed_smoke -- claude [model]
//!   cargo run -p switchtether-core --example managed_smoke -- codex
//!
//! Claude: starts a NEW session in a scratch directory, asks it to run one
//! shell command, approves the permission request through the same path the
//! phone uses, checks that a second answer is rejected (first answer wins),
//! waits for the turn to finish, then stops the process.

use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::Value;
use switchtether_core::agents::claude::ClaudeObserver;
use switchtether_core::agents::{AgentHub, CreateSession};
use switchtether_core::backend::UnavailableBackend;
use switchtether_core::events::EventBus;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let agent = args.first().map(|s| s.as_str()).unwrap_or("claude");
    let bus = EventBus::new(10_000);
    let hub = AgentHub::new(
        bus.clone(),
        Arc::new(UnavailableBackend),
        ClaudeObserver::detect(),
    );
    let dir = tempfile::tempdir()?;
    let cwd = std::fs::canonicalize(dir.path())?
        .to_string_lossy()
        .trim_start_matches(r"\\?\")
        .to_string();
    let mut rx = bus.subscribe();
    let started = Instant::now();
    let (agent_id, prompt, model) = match agent {
        "codex" => ("codex", "Run the shell command `echo SWITCHTETHER_SMOKE` and then reply DONE.".to_string(), None),
        _ => (
            "claude_code",
            std::env::var("SMOKE_PROMPT").unwrap_or_else(|_| "Use the Bash tool to run exactly: mkdir switchtether_smoke_dir . Then reply with the single word DONE.".to_string()),
            Some(args.get(1).cloned().unwrap_or_else(|| "haiku".into())),
        ),
    };
    let s = hub
        .create_managed(CreateSession {
            agent: agent_id.into(),
            // When continuing, the original session's cwd is used.
            cwd: if std::env::var("SMOKE_RESUME").is_ok() {
                String::new()
            } else {
                cwd.clone()
            },
            prompt: Some(prompt),
            model,
            permission_mode: None,
            resume_from: std::env::var("SMOKE_RESUME").ok(),
            mode: std::env::var("SMOKE_MODE").ok(),
            effort: std::env::var("SMOKE_EFFORT").ok(),
        })
        .await;
    let s = match s {
        Ok(s) => s,
        Err(e) => {
            println!("CREATE FAILED: {e}");
            return Ok(());
        }
    };
    println!("created {} ({}) in {cwd}", s.id, s.agent);
    let mut approved = false;
    let mut kinds: Vec<String> = Vec::new();
    let deadline = Instant::now() + Duration::from_secs(240);
    while Instant::now() < deadline {
        let ev = match tokio::time::timeout(Duration::from_secs(30), rx.recv()).await {
            Ok(Ok(ev)) => ev,
            Ok(Err(_)) => continue,
            Err(_) => {
                println!("(no events for 30s)");
                continue;
            }
        };
        if ev.topic != "session.event" {
            continue;
        }
        let d = &ev.data;
        let kind = d["kind"].as_str().unwrap_or("").to_string();
        let summary = match kind.as_str() {
            "assistant_message" | "user_message" | "reasoning" => d["data"]["text"]
                .as_str()
                .unwrap_or("")
                .chars()
                .take(80)
                .collect::<String>(),
            "tool_call" => format!(
                "{} [{}] {}",
                d["data"]["name"], d["data"]["status"], d["data"]["title"]
            ),
            "tool_result" => d["data"]["text"]
                .as_str()
                .unwrap_or("")
                .chars()
                .take(80)
                .collect(),
            "status" => d["data"]["state"].to_string(),
            "approval_request" => {
                format!("{} resolved={}", d["data"]["title"], d["data"]["resolved"])
            }
            "error" => d["data"]["message"].to_string(),
            _ => String::new(),
        };
        println!(
            "[{:>5.1}s] {:<18} {}{}",
            started.elapsed().as_secs_f32(),
            kind,
            if d["streaming"] == Value::Bool(true) {
                "(streaming) "
            } else {
                ""
            },
            summary.replace('\n', " ")
        );
        if !kinds.contains(&kind) {
            kinds.push(kind.clone());
        }
        if kind == "approval_request" && d["data"]["resolved"] == Value::Bool(false) && !approved {
            let rid = d["data"]["requestId"].as_str().unwrap().to_string();
            let r = hub
                .respond(&s.id, &rid, "allow", None, None, None, "smoke")
                .await;
            println!(
                "  -> approve: {}",
                r.as_ref()
                    .map(|_| "ok".to_string())
                    .unwrap_or_else(|e| e.to_string())
            );
            let again = hub
                .respond(&s.id, &rid, "deny", None, None, None, "second-device")
                .await;
            println!(
                "  -> second answer rejected: {}",
                again
                    .as_ref()
                    .err()
                    .map(|e| format!("{:?} {}", e.code, e.details.clone().unwrap_or_default()))
                    .unwrap_or_else(|| "NOT REJECTED (bug)".into())
            );
            approved = true;
            if std::env::var("SMOKE_INTERRUPT").is_ok() {
                tokio::time::sleep(Duration::from_secs(4)).await;
                let r = hub.interrupt(&s.id).await;
                println!(
                    "  -> interrupt: {}",
                    r.map(|_| "ok".to_string())
                        .unwrap_or_else(|e| e.to_string())
                );
            }
        }
        if kind == "status"
            && d["data"]["state"] == "idle"
            && std::env::var("SMOKE_INTERRUPT").is_ok()
            && approved
        {
            break;
        }
        if kind == "status"
            && (d["data"]["state"] == "idle" || d["data"]["state"] == "completed")
            && approved
        {
            break;
        }
        if kind == "status" && d["data"]["state"] == "idle" && kinds.iter().any(|k| k == "error") {
            break;
        }
    }
    let detail = hub.detail(&s.id).await;
    println!(
        "final: status={} eventCount={} kinds={kinds:?}",
        detail
            .as_ref()
            .map(|v| v["status"].to_string())
            .unwrap_or_default(),
        detail
            .as_ref()
            .map(|v| v["eventCount"].to_string())
            .unwrap_or_default()
    );
    let _ = hub.stop(&s.id).await;
    tokio::time::sleep(Duration::from_millis(500)).await;
    Ok(())
}
