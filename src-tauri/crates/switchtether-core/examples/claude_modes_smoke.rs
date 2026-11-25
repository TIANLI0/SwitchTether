//! Live check of permission modes in a SwitchTether-managed Claude Code session (spends a few
//! model tokens; run manually, never in CI):
//!
//!   cargo run -p switchtether-core --example claude_modes_smoke -- [model]
//!
//! Starts in `default` in a scratch folder, asks for a shell command (an approval must come),
//! switches to `bypassPermissions` mid-session (no approval any more), then to `auto` and back to
//! `default` (approvals again).

use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use switchtether_core::agents::claude::ClaudeObserver;
use switchtether_core::agents::{AgentHub, CreateSession};
use switchtether_core::backend::UnavailableBackend;
use switchtether_core::events::EventBus;

async fn turn(
    hub: &Arc<AgentHub>,
    rx: &mut tokio::sync::broadcast::Receiver<Arc<switchtether_core::events::EventFrame>>,
    id: &str,
    text: &str,
) -> u32 {
    println!("\n>>> {text}");
    hub.send_message(id, text, None).await.expect("send");
    let mut approvals = 0;
    let mut running = false;
    let t0 = Instant::now();
    while t0.elapsed() < Duration::from_secs(180) {
        let Ok(Ok(ev)) = tokio::time::timeout(Duration::from_secs(5), rx.recv()).await else {
            continue;
        };
        if ev.topic != "session.event" || ev.data["sessionId"] != json!(id) {
            continue;
        }
        let d = &ev.data;
        match d["kind"].as_str() {
            Some("approval_request") if d["data"]["resolved"] == Value::Bool(false) => {
                approvals += 1;
                let rid = d["data"]["requestId"].as_str().unwrap().to_string();
                println!("  approval: {}", d["data"]["title"]);
                let _ = hub
                    .respond(id, &rid, "allow", None, None, None, "smoke")
                    .await;
            }
            Some("tool_call") if d["streaming"] != Value::Bool(true) => {
                println!("  tool {} [{}]", d["data"]["name"], d["data"]["status"])
            }
            Some("assistant_message") if d["streaming"] != Value::Bool(true) => println!(
                "  assistant: {}",
                d["data"]["text"]
                    .as_str()
                    .unwrap_or("")
                    .chars()
                    .take(80)
                    .collect::<String>()
            ),
            Some("status") => match d["data"]["state"].as_str() {
                Some("running") => running = true,
                Some("idle" | "error" | "interrupted") if running => break,
                _ => {}
            },
            _ => {}
        }
    }
    approvals
}

fn check(label: &str, ok: bool) {
    println!("CHECK {:<50} {}", label, if ok { "PASS" } else { "FAIL" });
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let model = std::env::args().nth(1).unwrap_or_else(|| "haiku".into());
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
            agent: "claude_code".into(),
            cwd: cwd.clone(),
            prompt: None,
            model: Some(model),
            permission_mode: None,
            resume_from: None,
            mode: None,
            effort: None,
        })
        .await
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    let id = s.id.clone();
    println!("created {id} in {cwd}");
    let d = hub.detail(&id).await.unwrap();
    println!(
        "modes: {}",
        d["permissionModes"]
            .as_array()
            .map(|a| a.len())
            .unwrap_or(0)
    );

    let a = turn(
        &hub,
        &mut rx,
        &id,
        "Use the Bash tool to run exactly: mkdir one_dir . Then reply DONE.",
    )
    .await;
    check("default: approval requested", a > 0);

    let r = hub
        .claude_tool(
            &id,
            "permission_mode",
            &json!({ "mode": "bypassPermissions" }),
        )
        .await;
    println!("switch to bypass: {r:?}");
    check("switch to bypassPermissions mid-session", r.is_ok());
    let d = hub.detail(&id).await.unwrap();
    check(
        "detail says bypassPermissions",
        d["permissionMode"] == "bypassPermissions",
    );
    let a = turn(
        &hub,
        &mut rx,
        &id,
        "Use the Bash tool to run exactly: mkdir two_dir . Then reply DONE.",
    )
    .await;
    check("bypass: no approval", a == 0);
    check("bypass: command ran", dir.path().join("two_dir").is_dir());

    let r = hub
        .claude_tool(&id, "permission_mode", &json!({ "mode": "auto" }))
        .await;
    println!("switch to auto: {r:?}");
    check("switch to auto", r.is_ok());
    let r = hub
        .claude_tool(&id, "permission_mode", &json!({ "mode": "default" }))
        .await;
    check("switch back to default", r.is_ok());
    let a = turn(
        &hub,
        &mut rx,
        &id,
        "Use the Bash tool to run exactly: mkdir three_dir . Then reply DONE.",
    )
    .await;
    check("default again: approval requested", a > 0);
    let _ = hub.stop(&id).await;
    tokio::time::sleep(Duration::from_millis(500)).await;
    Ok(())
}
