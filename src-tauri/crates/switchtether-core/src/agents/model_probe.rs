//! The agents' own model lists, read by a short SwitchTether-owned process (never the user's):
//! Claude Code's `initialize` response (`models`) and Codex app-server's `model/list`. Nothing
//! is sent to a model and no conversation is created: the process is started, asked, and ended.

use std::path::Path;
use std::process::Stdio;
use std::time::Duration;

use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::Command;

const PROBE_TIMEOUT: Duration = Duration::from_secs(25);

fn command(bin: &Path) -> Command {
    let mut cmd = Command::new(bin);
    if let Some(path) = crate::util::child_path() {
        cmd.env("PATH", path);
    }
    cmd.current_dir(std::env::temp_dir())
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true);
    #[cfg(windows)]
    {
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    cmd
}

/// Claude Code's model choices (`[{value, displayName, description, supportedEffortLevels, …}]`).
/// The user's settings are read (they can restrict models) but no MCP server is started.
pub async fn claude_models(bin: &Path) -> Option<Value> {
    let mut cmd = command(bin);
    cmd.args([
        "--output-format",
        "stream-json",
        "--verbose",
        "--input-format",
        "stream-json",
        "--setting-sources",
        "user",
        "--strict-mcp-config",
        "--mcp-config",
        r#"{"mcpServers":{}}"#,
    ])
    .env_remove("CLAUDE_CODE_ENTRYPOINT");
    let run = async {
        let mut child = cmd.spawn().ok()?;
        let mut stdin = child.stdin.take()?;
        let mut lines = BufReader::new(child.stdout.take()?).lines();
        let req = json!({ "type": "control_request", "request_id": "st_models",
            "request": { "subtype": "initialize", "hooks": null } });
        stdin.write_all(format!("{req}\n").as_bytes()).await.ok()?;
        stdin.flush().await.ok()?;
        while let Ok(Some(line)) = lines.next_line().await {
            let Ok(v) = serde_json::from_str::<Value>(&line) else {
                continue;
            };
            if v.get("type").and_then(Value::as_str) == Some("control_response")
                && v.pointer("/response/request_id").and_then(Value::as_str) == Some("st_models")
            {
                let _ = child.start_kill();
                return v
                    .pointer("/response/response/models")
                    .filter(|m| m.as_array().is_some_and(|a| !a.is_empty()))
                    .cloned();
            }
        }
        let _ = child.start_kill();
        None
    };
    tokio::time::timeout(PROBE_TIMEOUT, run)
        .await
        .ok()
        .flatten()
}

/// Codex's model list (`model/list` → `{data: [{id, model, displayName, hidden, …}]}`).
pub async fn codex_models(bin: &Path) -> Option<Value> {
    let mut cmd = command(bin);
    cmd.arg("app-server");
    let run = async {
        let mut child = cmd.spawn().ok()?;
        let mut stdin = child.stdin.take()?;
        let mut lines = BufReader::new(child.stdout.take()?).lines();
        let version = env!("CARGO_PKG_VERSION");
        for msg in [
            json!({ "id": 1, "method": "initialize",
                "params": { "clientInfo": { "name": "switchtether", "version": version } } }),
            json!({ "method": "initialized" }),
            json!({ "id": 2, "method": "model/list", "params": {} }),
        ] {
            stdin.write_all(format!("{msg}\n").as_bytes()).await.ok()?;
        }
        stdin.flush().await.ok()?;
        while let Ok(Some(line)) = lines.next_line().await {
            let Ok(v) = serde_json::from_str::<Value>(&line) else {
                continue;
            };
            if v.get("id").and_then(Value::as_i64) == Some(2) {
                let _ = child.start_kill();
                return v
                    .pointer("/result/data")
                    .filter(|m| m.as_array().is_some_and(|a| !a.is_empty()))
                    .cloned();
            }
        }
        let _ = child.start_kill();
        None
    };
    tokio::time::timeout(PROBE_TIMEOUT, run)
        .await
        .ok()
        .flatten()
}
