//! Live check of SwitchTether-managed Codex sessions against the real `codex app-server`
//! (spends model tokens; run manually, never in CI):
//!
//!   cargo run -p switchtether-core --example codex_smoke -- [model]
//!
//! Runs in a scratch git folder with the user's normal Codex home (exactly what a session
//! started from the phone does; Codex records the thread in its own history). Steps:
//! approval in `untrusted` mode, streaming, `/status`, `/mcp`, `/compact`, plan mode, full
//! access without approvals, effort change, `/review`, interrupt, and resuming the thread after
//! its process ended (as after a desktop restart).

use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use switchtether_core::agents::claude::ClaudeObserver;
use switchtether_core::agents::{AgentHub, CreateSession};
use switchtether_core::backend::UnavailableBackend;
use switchtether_core::events::{EventBus, EventFrame};
use tokio::sync::broadcast::Receiver;

struct Run {
    hub: Arc<AgentHub>,
    rx: Receiver<Arc<EventFrame>>,
    id: String,
    t0: Instant,
}

impl Run {
    /// Send `text` and follow the session until the turn ends; approvals are answered
    /// `allow` (counted). Returns the kinds seen and whether an approval came.
    async fn turn(&mut self, text: &str, interrupt_after: Option<Duration>) -> (Vec<String>, u32) {
        println!("\n>>> {text}");
        if let Err(e) = self.hub.send_message(&self.id, text, None).await {
            println!("  SEND FAILED: {e}");
            return (vec![], 0);
        }
        let mut kinds = Vec::new();
        let mut approvals = 0;
        let sent = Instant::now();
        let mut interrupted = false;
        let mut running = false;
        loop {
            if let (Some(after), false) = (interrupt_after, interrupted) {
                if sent.elapsed() > after {
                    let r = self.hub.interrupt(&self.id).await;
                    println!("  -> interrupt: {:?}", r.map(|_| "ok"));
                    interrupted = true;
                }
            }
            if sent.elapsed() > Duration::from_secs(300) {
                println!("  (gave up after 300 s)");
                break;
            }
            let ev = match tokio::time::timeout(Duration::from_secs(2), self.rx.recv()).await {
                Ok(Ok(ev)) => ev,
                Ok(Err(_)) => continue,
                Err(_) => {
                    // Commands that start no turn end without a status change.
                    if !running && sent.elapsed() > Duration::from_secs(8) {
                        break;
                    }
                    continue;
                }
            };
            if ev.topic != "session.event" || ev.data["sessionId"] != json!(self.id) {
                continue;
            }
            let d = &ev.data;
            let kind = d["kind"].as_str().unwrap_or("").to_string();
            let streaming = d["streaming"] == Value::Bool(true);
            let line = match kind.as_str() {
                "assistant_message" | "user_message" | "reasoning" | "system" => d["data"]["text"]
                    .as_str()
                    .unwrap_or("")
                    .chars()
                    .take(90)
                    .collect::<String>(),
                "tool_call" => format!(
                    "{} [{}] {}",
                    d["data"]["name"], d["data"]["status"], d["data"]["title"]
                ),
                "tool_result" => d["data"]["text"]
                    .as_str()
                    .unwrap_or("")
                    .chars()
                    .take(60)
                    .collect(),
                "status" => d["data"]["state"].to_string(),
                "approval_request" => {
                    format!("{} resolved={}", d["data"]["title"], d["data"]["resolved"])
                }
                "error" => d["data"]["message"].to_string(),
                "usage" => d["data"].to_string(),
                "plan" => d["data"]["items"].to_string().chars().take(90).collect(),
                _ => String::new(),
            };
            // Streaming rows repeat for every delta: print the first and the final one.
            let first_delta = streaming && !kinds.contains(&format!("{kind}~"));
            if !streaming || first_delta {
                println!(
                    "  [{:>6.1}s] {:<17} {}{}",
                    self.t0.elapsed().as_secs_f32(),
                    kind,
                    if streaming { "(streaming) " } else { "" },
                    line.replace('\n', " ")
                );
            }
            if streaming && first_delta {
                kinds.push(format!("{kind}~"));
            }
            if !kinds.contains(&kind) {
                kinds.push(kind.clone());
            }
            if kind == "approval_request" && d["data"]["resolved"] == Value::Bool(false) {
                let rid = d["data"]["requestId"].as_str().unwrap().to_string();
                let r = self
                    .hub
                    .respond(&self.id, &rid, "allow", None, None, None, "smoke")
                    .await;
                approvals += 1;
                println!("  -> approve: {:?}", r.map(|_| "ok"));
            }
            if kind == "status" {
                match d["data"]["state"].as_str() {
                    Some("running") => running = true,
                    Some("idle" | "interrupted" | "error" | "completed") if running => break,
                    _ => {}
                }
            }
        }
        (kinds, approvals)
    }

    async fn tool(&self, action: &str, p: Value) {
        match self.hub.claude_tool(&self.id, action, &p).await {
            Ok(v) => println!(
                "  tool {action}: {}",
                v.to_string().chars().take(300).collect::<String>()
            ),
            Err(e) => println!("  tool {action} FAILED: {e}"),
        }
    }
}

fn check(label: &str, ok: bool) {
    println!("CHECK {:<48} {}", label, if ok { "PASS" } else { "FAIL" });
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let model = std::env::args().nth(1);
    let bus = EventBus::new(50_000);
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
    std::fs::write(dir.path().join("notes.txt"), "hello\n")?;
    let git = |args: &[&str]| {
        std::process::Command::new("git")
            .args(args)
            .current_dir(dir.path())
            .output()
            .ok();
    };
    git(&["init", "-q"]);
    git(&[
        "-c",
        "user.email=smoke@example.invalid",
        "-c",
        "user.name=smoke",
        "add",
        ".",
    ]);
    git(&[
        "-c",
        "user.email=smoke@example.invalid",
        "-c",
        "user.name=smoke",
        "commit",
        "-qm",
        "init",
    ]);
    let opts = hub.model_options("codex", None).await;
    println!(
        "models: {}",
        opts["models"]
            .as_array()
            .map(|a| a
                .iter()
                .filter_map(|m| m["id"].as_str())
                .collect::<Vec<_>>()
                .join(", "))
            .unwrap_or_default()
    );
    println!("permission modes: {}", opts["permissionModes"]);

    let s = hub
        .create_managed(CreateSession {
            agent: "codex".into(),
            cwd: cwd.clone(),
            prompt: None,
            model: model.clone(),
            permission_mode: Some("untrusted".into()),
            resume_from: None,
            mode: None,
            effort: Some("low".into()),
        })
        .await;
    let s = match s {
        Ok(s) => s,
        Err(e) => {
            println!("CREATE FAILED: {e}");
            return Ok(());
        }
    };
    println!("created {} model={:?} in {cwd}", s.id, s.model);
    let mut run = Run {
        hub: hub.clone(),
        rx: bus.subscribe(),
        id: s.id.clone(),
        t0: Instant::now(),
    };
    let d = hub.detail(&run.id).await.unwrap();
    check(
        "detail: permission mode untrusted",
        d["permissionMode"] == "untrusted",
    );
    check(
        "detail: codex session tools",
        d["sessionTools"]
            .as_array()
            .is_some_and(|a| a.contains(&json!("commands"))),
    );

    // 1. untrusted: a command that writes asks first.
    let (k, a) = run
        .turn(
            "Use the shell to create a directory named st_dir, then reply with the single word ONE.",
            None,
        )
        .await;
    check("untrusted: approval requested and answered", a > 0);
    check(
        "streaming assistant text",
        k.contains(&"assistant_message~".to_string()),
    );
    check("command ran", dir.path().join("st_dir").is_dir());
    check("usage reported", k.contains(&"usage".to_string()));

    // 2. Codex commands.
    run.tool("commands", json!({})).await;
    run.tool("context_usage", json!({})).await;
    run.tool("mcp_status", json!({})).await;
    let (k, _) = run.turn("/status", None).await;
    check("/status prints", k.contains(&"system".to_string()));
    let (k, _) = run.turn("/compact", None).await;
    check(
        "/compact ran (compaction row or turn)",
        k.contains(&"system".to_string()) || k.contains(&"status".to_string()),
    );

    // 3. plan mode.
    hub.set_session_options(&run.id, None, None, Some("plan".into()))
        .await
        .map_err(|e| anyhow::anyhow!("{e}"))?;
    let (k, a) = run
        .turn(
            "Plan how you would add a README.md describing notes.txt. Do not change any files.",
            None,
        )
        .await;
    check(
        "plan mode turn ran without error",
        !k.contains(&"error".to_string()),
    );
    check("plan mode: no approvals", a == 0);
    check(
        "plan mode: README not written",
        !dir.path().join("README.md").exists(),
    );

    // 4. full access: no approvals.
    hub.set_session_options(
        &run.id,
        None,
        Some("medium".into()),
        Some("bypassPermissions".into()),
    )
    .await
    .map_err(|e| anyhow::anyhow!("{e}"))?;
    let (k, a) = run
        .turn(
            "Leave plan mode. Use the shell to write the text FULL into a new file full.txt, then reply DONE.",
            None,
        )
        .await;
    check("full access: no approval asked", a == 0);
    check(
        "full access: file written",
        dir.path().join("full.txt").exists(),
    );
    check("full access: no error", !k.contains(&"error".to_string()));
    let d = hub.detail(&run.id).await.unwrap();
    check(
        "detail: mode bypassPermissions, effort medium",
        d["permissionMode"] == "bypassPermissions" && d["effort"] == "medium",
    );

    // 5. review of the uncommitted changes.
    let (k, _) = run.turn("/review", None).await;
    check(
        "/review produced output",
        k.contains(&"assistant_message".to_string()) || k.contains(&"system".to_string()),
    );

    // 6. interrupt.
    let (k, _) = run
        .turn(
            "Run the shell command `ping -n 30 127.0.0.1` and report the result.",
            Some(Duration::from_secs(8)),
        )
        .await;
    check(
        "interrupt ended the turn",
        k.contains(&"status".to_string()),
    );

    // 7. The process ends (desktop restart); a message resumes the same thread.
    let id = run.id.clone();
    let _ = hub.stop(&id).await;
    tokio::time::sleep(Duration::from_secs(1)).await;
    let (k, _) = run
        .turn(
            "What was the name of the directory you created at the start? Reply with just the name.",
            None,
        )
        .await;
    let remembers = hub
        .events(&id, None, None, 2000)
        .await
        .ok()
        .and_then(|p| {
            p["events"].as_array().map(|a| {
                a.iter().rev().any(|e| {
                    e["kind"] == "assistant_message"
                        && e["data"]["text"]
                            .as_str()
                            .is_some_and(|t| t.contains("st_dir"))
                })
            })
        })
        .unwrap_or(false);
    check(
        "resumed after the process ended",
        k.contains(&"assistant_message".to_string()),
    );
    check("resumed thread remembers", remembers);
    let s = hub.summary(&id).await.unwrap();
    check("same id after resume", s.id == id);
    let _ = hub.stop(&id).await;
    tokio::time::sleep(Duration::from_millis(500)).await;
    Ok(())
}
