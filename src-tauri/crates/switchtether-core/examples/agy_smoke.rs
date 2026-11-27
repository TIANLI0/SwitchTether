//! Live smoke test of SwitchTether-managed Google Antigravity sessions (`agy acp`). Spends a few
//! model turns of the signed-in account; run manually, never in CI:
//!
//!   cargo run -p switchtether-core --example agy_smoke -- [model] [effort]
//!
//! Through the hub, exactly as the phone's requests do, in a scratch directory:
//! 1. new session with model / thinking level / mode `acceptEdits` and a one-word prompt;
//! 2. model and thinking level changed while it runs (`set_options`), mode via the session tool;
//! 3. the process is stopped, then a message resumes the same conversation in a new process
//!    (`session/resume`) and must remember the first answer;
//! 4. a long answer is interrupted;
//! 5. (only with `AGY_SMOKE_APPROVAL_BIN`, a wrapper that runs `agy.exe %* --gemini_dir=<empty
//!    scratch dir>` so agy's default permission settings apply) a shell command needs a
//!    permission request, approved through the hub; a second answer must be rejected.

use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use switchtether_core::agents::claude::ClaudeObserver;
use switchtether_core::agents::{AgentHub, CreateSession};
use switchtether_core::backend::UnavailableBackend;
use switchtether_core::events::EventBus;

async fn wait_state(
    rx: &mut tokio::sync::broadcast::Receiver<Arc<switchtether_core::events::EventFrame>>,
    hub: &AgentHub,
    want: &[&str],
    approve: bool,
    started: Instant,
) -> Vec<Value> {
    let mut seen = Vec::new();
    let deadline = Instant::now() + Duration::from_secs(240);
    while Instant::now() < deadline {
        let ev = match tokio::time::timeout(Duration::from_secs(60), rx.recv()).await {
            Ok(Ok(ev)) => ev,
            Ok(Err(_)) => continue,
            Err(_) => {
                println!("(no events for 60s)");
                break;
            }
        };
        if ev.topic != "session.event" {
            continue;
        }
        let d = ev.data.clone();
        let kind = d["kind"].as_str().unwrap_or("").to_string();
        let streaming = d["streaming"] == Value::Bool(true);
        if !streaming {
            let text = match kind.as_str() {
                "assistant_message" | "user_message" | "reasoning" | "system" => {
                    d["data"]["text"].as_str().unwrap_or("").to_string()
                }
                "tool_call" => format!(
                    "{} [{}] {}",
                    d["data"]["name"], d["data"]["status"], d["data"]["title"]
                ),
                "tool_result" => d["data"]["text"].as_str().unwrap_or("").to_string(),
                "status" => d["data"].to_string(),
                "usage" => d["data"].to_string(),
                "approval_request" => format!(
                    "{} resolved={} decision={}",
                    d["data"]["title"], d["data"]["resolved"], d["data"]["decision"]
                ),
                "error" => d["data"]["message"].to_string(),
                _ => String::new(),
            };
            let text: String = text.replace(['\n', '\r'], " ").chars().take(110).collect();
            println!(
                "[{:>5.1}s] {:<18} {} {}",
                started.elapsed().as_secs_f32(),
                kind,
                d["sessionId"].as_str().unwrap_or(""),
                text
            );
        }
        if approve && kind == "approval_request" && d["data"]["resolved"] == Value::Bool(false) {
            let sid = d["sessionId"].as_str().unwrap_or_default().to_string();
            let rid = d["data"]["requestId"].as_str().unwrap().to_string();
            let r = hub
                .respond(&sid, &rid, "allow", None, None, None, "phone")
                .await;
            println!(
                "  -> approve: {:?}",
                r.as_ref().map(|_| "ok").map_err(|e| e.to_string())
            );
            let again = hub
                .respond(&sid, &rid, "deny", None, None, None, "second-device")
                .await;
            println!(
                "  -> second answer: {}",
                again
                    .err()
                    .map(|e| format!("rejected {:?} {}", e.code, e.details.unwrap_or_default()))
                    .unwrap_or_else(|| "NOT REJECTED (bug)".into())
            );
        }
        let state = d["data"]["state"].as_str().unwrap_or("").to_string();
        seen.push(d);
        if kind == "status" && want.contains(&state.as_str()) {
            break;
        }
    }
    seen
}

fn texts(events: &[Value], kind: &str) -> Vec<String> {
    events
        .iter()
        .filter(|e| e["kind"] == kind && e["streaming"] != Value::Bool(true))
        .filter_map(|e| e["data"]["text"].as_str().map(str::to_string))
        .collect()
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let model = args
        .first()
        .cloned()
        .unwrap_or_else(|| "claude-sonnet-5-5".into());
    let effort = args.get(1).cloned().unwrap_or_else(|| "low".into());
    let bus = EventBus::new(10_000);
    let hub = AgentHub::new(
        bus.clone(),
        Arc::new(UnavailableBackend),
        ClaudeObserver::detect(),
    );
    println!("health: {}", hub.health().await);
    let dir = tempfile::tempdir()?;
    let cwd = std::fs::canonicalize(dir.path())?
        .to_string_lossy()
        .trim_start_matches(r"\\?\")
        .to_string();
    let mut rx = bus.subscribe();
    let started = Instant::now();

    let catalog = hub.model_options("agy", None).await;
    println!(
        "launcher models: {} entries, efforts {}, modes {}",
        catalog["models"].as_array().map_or(0, Vec::len),
        catalog["efforts"],
        catalog["permissionModes"]
            .as_array()
            .map(|a| a.iter().map(|m| m["id"].clone()).collect::<Vec<_>>())
            .unwrap_or_default()
            .len()
    );

    // 1. New session.
    let s = hub
        .create_managed(CreateSession {
            agent: "agy".into(),
            cwd: cwd.clone(),
            prompt: Some("Reply with the single word PELICAN and nothing else.".into()),
            model: Some(model.clone()),
            permission_mode: Some("acceptEdits".into()),
            resume_from: None,
            mode: None,
            effort: Some(effort.clone()),
        })
        .await;
    let s = match s {
        Ok(s) => s,
        Err(e) => {
            println!("CREATE FAILED: {e}");
            return Ok(());
        }
    };
    println!(
        "1. created {} model={:?} effort={:?} managed={} origin={:?}",
        s.id, s.model, s.effort, s.managed, s.origin
    );
    let ev = wait_state(&mut rx, &hub, &["idle", "interrupted"], false, started).await;
    println!("   answers: {:?}", texts(&ev, "assistant_message"));
    let d = hub.detail(&s.id).await?;
    println!(
        "   detail: status={} permissionMode={} sessionTools={} capabilities={}",
        d["status"], d["permissionMode"], d["sessionTools"], d["capabilities"]
    );

    // 2. Options of the running session.
    let live = hub.model_options("agy", Some(&s.id)).await;
    println!(
        "2. live models: {} entries (first {})",
        live["models"].as_array().map_or(0, Vec::len),
        live["models"][0]
    );
    let r = hub
        .set_options(
            &s.id,
            Some("gemini-3.8-flash".into()),
            Some("medium".into()),
        )
        .await;
    println!(
        "   set gemini-3.8-flash/medium: {:?}",
        r.map(|x| (x.model, x.effort)).map_err(|e| e.to_string())
    );
    let r = hub
        .set_options(&s.id, Some(model.clone()), Some(effort.clone()))
        .await;
    println!(
        "   back to {model}/{effort}: {:?}",
        r.map(|x| (x.model, x.effort)).map_err(|e| e.to_string())
    );
    let r = hub
        .claude_tool(&s.id, "permission_mode", &json!({ "mode": "default" }))
        .await;
    println!(
        "   permission_mode default: {:?}",
        r.map_err(|e| e.to_string())
    );
    let r = hub.claude_tool(&s.id, "context_usage", &json!({})).await;
    println!("   context_usage: {:?}", r.map_err(|e| e.to_string()));
    let r = hub.claude_tool(&s.id, "commands", &json!({})).await;
    println!(
        "   commands: {:?}",
        r.map(|v| v["commands"].as_array().map_or(0, Vec::len))
            .map_err(|e| e.to_string())
    );

    // 3. Stop, then a message resumes the same conversation in a new process.
    hub.stop(&s.id).await?;
    tokio::time::sleep(Duration::from_secs(2)).await;
    let gone = hub.summary(&s.id).await;
    println!(
        "3. stopped: liveness={:?} status={:?}",
        gone.as_ref().map(|x| x.liveness),
        gone.as_ref().map(|x| x.status.clone())
    );
    let r = hub
        .send_message(
            &s.id,
            "Which single word did you reply with before? Answer with that word only.",
            Some("cmd-resume"),
        )
        .await;
    println!("   send (resumes): {:?}", r.map_err(|e| e.to_string()));
    let ev = wait_state(&mut rx, &hub, &["idle", "interrupted"], false, started).await;
    let answers = texts(&ev, "assistant_message");
    println!(
        "   answers after resume: {answers:?} -> remembers: {}",
        answers.iter().any(|a| a.to_uppercase().contains("PELICAN"))
    );
    let after = hub.summary(&s.id).await;
    println!(
        "   same id {} live={:?} native={:?}",
        s.id,
        after.as_ref().map(|x| x.liveness),
        after.as_ref().map(|x| x.native_id.clone())
    );

    // 4. Interrupt a long answer.
    hub.send_message(
        &s.id,
        "Write a numbered list of 80 different birds, one per line, each with a sentence about it.",
        None,
    )
    .await?;
    let t0 = Instant::now();
    loop {
        let ev = tokio::time::timeout(Duration::from_secs(60), rx.recv()).await;
        let Ok(Ok(ev)) = ev else { break };
        if ev.topic == "session.event" && ev.data["kind"] == "assistant_message" {
            break;
        }
        if t0.elapsed() > Duration::from_secs(60) {
            break;
        }
    }
    tokio::time::sleep(Duration::from_millis(800)).await;
    println!(
        "4. interrupt: {:?}",
        hub.interrupt(&s.id).await.map_err(|e| e.to_string())
    );
    let ev = wait_state(&mut rx, &hub, &["idle", "interrupted"], false, started).await;
    let last = ev
        .iter()
        .rev()
        .find(|e| e["kind"] == "status")
        .map(|e| e["data"].clone());
    println!("   final status: {last:?}");
    hub.stop(&s.id).await?;

    // 5. Approval through the hub, agy with its default permission settings.
    if let Ok(bin) = std::env::var("AGY_SMOKE_APPROVAL_BIN") {
        std::fs::write(dir.path().join("victim.txt"), "x")?;
        let log = Arc::new(parking_lot::Mutex::new(
            switchtether_core::agents::store::SessionLog::new("agy:approval", Some(bus.clone())),
        ));
        let m = switchtether_core::agents::agy::managed::ManagedAgy::spawn(
            "agy:approval".into(),
            switchtether_core::agents::agy::managed::ManagedAgyOptions {
                agy_bin: bin.into(),
                cwd: cwd.clone().into(),
                resume_session: None,
                model: Some(model.clone()),
                effort: Some(effort.clone()),
                permission_mode: None,
                add_dirs: Vec::new(),
            },
            log,
        )
        .await?;
        use switchtether_core::agents::managed::ManagedSession;
        m.send_message("Use a shell command (Remove-Item) to delete victim.txt in the current directory, then reply DONE.")
            .await?;
        let deadline = Instant::now() + Duration::from_secs(180);
        let mut answered = false;
        while Instant::now() < deadline {
            let Ok(Ok(ev)) = tokio::time::timeout(Duration::from_secs(60), rx.recv()).await else {
                break;
            };
            if ev.topic != "session.event" || ev.data["sessionId"] != "agy:approval" {
                continue;
            }
            let d = &ev.data;
            if d["kind"] == "approval_request" && d["data"]["resolved"] == false && !answered {
                println!(
                    "5. approval request: {} options={}",
                    d["data"]["title"], d["data"]["options"]
                );
                let rid = d["data"]["requestId"].as_str().unwrap().to_string();
                let r = m.respond(&rid, "allow", None, None, None, "phone").await;
                println!(
                    "   approve: {:?}",
                    r.map(|_| "ok").map_err(|e| e.to_string())
                );
                let again = m.respond(&rid, "deny", None, None, None, "other").await;
                println!(
                    "   second answer: {}",
                    again
                        .err()
                        .map(|e| format!("rejected {:?} {}", e.code, e.details.unwrap_or_default()))
                        .unwrap_or_else(|| "NOT REJECTED (bug)".into())
                );
                answered = true;
            }
            if d["kind"] == "status" && d["data"]["state"] != "running" {
                break;
            }
        }
        println!(
            "   victim.txt deleted: {}",
            !dir.path().join("victim.txt").exists()
        );
        m.kill();
    }
    tokio::time::sleep(Duration::from_millis(500)).await;
    println!("done in {:.1}s", started.elapsed().as_secs_f32());
    Ok(())
}
