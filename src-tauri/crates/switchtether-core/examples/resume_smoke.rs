//! Live check of official-id sessions (spends a few model tokens; run manually, never in CI):
//!
//!   cargo run -p switchtether-core --example resume_smoke -- [model] [switch-to-model]
//!
//! 1. Starts a NEW Claude Code session in a scratch directory: its id must be `claude:<uuid>` and
//!    the transcript must be Claude Code's own `~/.claude/projects/<dir>/<uuid>.jsonl`.
//! 2. Switches model and effort while it runs (`set_model`, `apply_flag_settings`).
//! 3. Drops the hub (what a desktop restart does to the process) and sends to the same id from a
//!    fresh hub: the conversation is resumed with `claude --resume <uuid>` and remembers the
//!    first turn.

use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::Value;
use switchtether_core::agents::claude::ClaudeObserver;
use switchtether_core::agents::{AgentHub, CreateSession};
use switchtether_core::backend::UnavailableBackend;
use switchtether_core::events::EventBus;

async fn wait_idle(
    rx: &mut tokio::sync::broadcast::Receiver<Arc<switchtether_core::events::EventFrame>>,
    id: &str,
    secs: u64,
) -> Vec<String> {
    let mut texts = Vec::new();
    let deadline = Instant::now() + Duration::from_secs(secs);
    while Instant::now() < deadline {
        let Ok(Ok(ev)) = tokio::time::timeout(Duration::from_secs(5), rx.recv()).await else {
            continue;
        };
        if ev.topic != "session.event" || ev.data["sessionId"] != id {
            continue;
        }
        let d = &ev.data;
        match d["kind"].as_str() {
            Some("assistant_message") if d["streaming"] != Value::Bool(true) => {
                let t = d["data"]["text"].as_str().unwrap_or("").to_string();
                println!(
                    "  assistant ({}): {}",
                    d["data"]["model"].as_str().unwrap_or("?"),
                    t
                );
                texts.push(t);
            }
            Some("error") => println!("  error: {}", d["data"]),
            Some("status")
                if matches!(d["data"]["state"].as_str(), Some("idle" | "completed"))
                    && !texts.is_empty() =>
            {
                return texts;
            }
            _ => {}
        }
    }
    println!("  (timed out)");
    texts
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let model = args.first().cloned().unwrap_or_else(|| "haiku".into());
    let switch_to = args.get(1).cloned().unwrap_or_else(|| "sonnet".into());
    let dir = tempfile::tempdir()?;
    let cwd = std::fs::canonicalize(dir.path())?
        .to_string_lossy()
        .trim_start_matches(r"\\?\")
        .to_string();

    let bus = EventBus::new(10_000);
    let hub = AgentHub::new(
        bus.clone(),
        Arc::new(UnavailableBackend),
        ClaudeObserver::detect(),
    );
    let mut rx = bus.subscribe();
    let s = hub
        .create_managed(CreateSession {
            agent: "claude_code".into(),
            cwd: cwd.clone(),
            prompt: Some(
                "Remember the code word ZEBRA-42. Reply with the single word ALPHA.".into(),
            ),
            model: Some(model.clone()),
            permission_mode: None,
            resume_from: None,
            mode: None,
            effort: Some("low".into()),
        })
        .await?;
    println!(
        "1. created id={} native={} effort={:?}",
        s.id, s.native_id, s.effort
    );
    assert_eq!(s.id, format!("claude:{}", s.native_id));
    wait_idle(&mut rx, &s.id, 180).await;
    let transcript = hub.claude.transcript_path(Some(&cwd), &s.native_id);
    println!("   official transcript: {transcript:?}");
    let opts = hub.model_options("claude_code", Some(&s.id)).await;
    println!(
        "   model options (source {}): {}",
        opts["source"],
        opts["models"]
            .as_array()
            .map(|a| a
                .iter()
                .map(|m| m["id"].as_str().unwrap_or("?").to_string())
                .collect::<Vec<_>>()
                .join(", "))
            .unwrap_or_default()
    );

    let r = hub
        .set_options(&s.id, Some(switch_to.clone()), Some("medium".into()))
        .await;
    match &r {
        Ok(x) => println!("2. switched: model={:?} effort={:?}", x.model, x.effort),
        Err(e) => println!("2. switch FAILED: {e}"),
    }
    hub.send_message(&s.id, "Reply with the single word BETA.", None)
        .await?;
    wait_idle(&mut rx, &s.id, 180).await;

    hub.shutdown();
    drop(hub);
    tokio::time::sleep(Duration::from_secs(2)).await;

    let bus2 = EventBus::new(10_000);
    let hub2 = AgentHub::new(
        bus2.clone(),
        Arc::new(UnavailableBackend),
        ClaudeObserver::detect(),
    );
    let mut rx2 = bus2.subscribe();
    let d = hub2.detail(&s.id).await?;
    println!(
        "3. after restart: liveness={} sendMessage={} eventCount={}",
        d["liveness"], d["capabilities"]["sendMessage"], d["eventCount"]
    );
    hub2.send_message(
        &s.id,
        "What was the code word? Reply with only the code word.",
        None,
    )
    .await?;
    let texts = wait_idle(&mut rx2, &s.id, 180).await;
    let d = hub2.detail(&s.id).await?;
    println!(
        "   resumed: id={} liveness={} remembers={}",
        d["id"],
        d["liveness"],
        texts.iter().any(|t| t.contains("ZEBRA-42"))
    );
    let _ = hub2.stop(&s.id).await;
    tokio::time::sleep(Duration::from_millis(500)).await;
    Ok(())
}
