//! Live check: change model and effort of a running managed Codex session from the phone's API
//! (spends model tokens; run manually):
//!
//!   cargo run -p switchtether-core --example codex_model_smoke -- <first model> <second model>
//!
//! Evidence comes from Codex itself: the `turn_context` records of the thread's rollout name the
//! model and effort each turn ran with.

use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use switchtether_core::agents::claude::ClaudeObserver;
use switchtether_core::agents::{AgentHub, CreateSession};
use switchtether_core::backend::UnavailableBackend;
use switchtether_core::events::EventBus;

async fn wait_idle(
    rx: &mut tokio::sync::broadcast::Receiver<Arc<switchtether_core::events::EventFrame>>,
    id: &str,
) {
    let t0 = Instant::now();
    let mut running = false;
    while t0.elapsed() < Duration::from_secs(180) {
        let Ok(Ok(ev)) = tokio::time::timeout(Duration::from_secs(5), rx.recv()).await else {
            continue;
        };
        if ev.topic != "session.event" || ev.data["sessionId"] != json!(id) {
            continue;
        }
        let d = &ev.data;
        if d["kind"] == "error" {
            println!("  error: {}", d["data"]["message"]);
        }
        if d["kind"] == "assistant_message" && d["streaming"] != Value::Bool(true) {
            println!("  assistant: {}", d["data"]["text"]);
        }
        if d["kind"] == "status" {
            match d["data"]["state"].as_str() {
                Some("running") => running = true,
                Some("idle" | "error" | "interrupted") if running => return,
                _ => {}
            }
        }
    }
}

/// `(model, effort)` of every turn in the thread's rollout.
fn turn_contexts(thread: &str) -> Vec<(String, String)> {
    let home = std::env::var("CODEX_HOME")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|_| dirs::home_dir().unwrap().join(".codex"));
    let mut out = Vec::new();
    let mut dirs = vec![home.join("sessions")];
    while let Some(d) = dirs.pop() {
        for e in std::fs::read_dir(&d).into_iter().flatten().flatten() {
            let p = e.path();
            if p.is_dir() {
                dirs.push(p);
            } else if p
                .file_name()
                .and_then(|n| n.to_str())
                .is_some_and(|n| n.ends_with(&format!("{thread}.jsonl")))
            {
                for l in std::fs::read_to_string(&p).unwrap_or_default().lines() {
                    let Ok(v) = serde_json::from_str::<Value>(l) else {
                        continue;
                    };
                    if v["type"] == "turn_context" {
                        out.push((
                            v["payload"]["model"].as_str().unwrap_or("").to_string(),
                            v["payload"]["effort"]
                                .as_str()
                                .or(v["payload"]["reasoning_effort"].as_str())
                                .unwrap_or("")
                                .to_string(),
                        ));
                    }
                }
            }
        }
    }
    out
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (first, second) = (args[0].clone(), args[1].clone());
    let bus = EventBus::new(20_000);
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
    let s = hub
        .create_managed(CreateSession {
            agent: "codex".into(),
            cwd,
            prompt: None,
            model: Some(first.clone()),
            permission_mode: None,
            resume_from: None,
            mode: None,
            effort: Some("low".into()),
        })
        .await
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    let id = s.id.clone();
    let thread = s.native_id.clone();
    println!("created {id} model={:?}", s.model);
    hub.send_message(&id, "Reply with the single word ONE.", None)
        .await
        .unwrap();
    wait_idle(&mut rx, &id).await;
    let r = hub
        .set_options(&id, Some(second.clone()), Some("medium".into()))
        .await
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    println!("switched: model={:?} effort={:?}", r.model, r.effort);
    hub.send_message(&id, "Reply with the single word TWO.", None)
        .await
        .unwrap();
    wait_idle(&mut rx, &id).await;
    let turns = turn_contexts(&thread);
    println!("turn contexts: {turns:?}");
    let ok_first = turns.first().is_some_and(|(m, _)| *m == first);
    let ok_second = turns
        .last()
        .is_some_and(|(m, e)| *m == second && e == "medium");
    println!(
        "CHECK first turn ran on {first:<30} {}",
        if ok_first { "PASS" } else { "FAIL" }
    );
    println!(
        "CHECK second turn ran on {second} / medium {}",
        if ok_second { "PASS" } else { "FAIL" }
    );
    let d = hub.detail(&id).await.unwrap();
    println!("detail model={} effort={}", d["model"], d["effort"]);
    let _ = hub.stop(&id).await;
    tokio::time::sleep(Duration::from_millis(500)).await;
    Ok(())
}
