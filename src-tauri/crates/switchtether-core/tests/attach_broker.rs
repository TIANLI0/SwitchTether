//! Attach broker with a simulated switchtether-shim (no real agent).

use std::sync::Arc;
use std::time::Duration;

use serde_json::{json, Value};
use switchtether_core::agents::claude::ClaudeObserver;
use switchtether_core::agents::{AgentHub, SessionQuery};
use switchtether_core::attach::AttachBroker;
use switchtether_core::backend::UnavailableBackend;
use switchtether_core::events::EventBus;
use switchtether_core::terminal::TerminalManager;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio_util::sync::CancellationToken;

struct Env {
    hub: Arc<AgentHub>,
    terms: Arc<TerminalManager>,
    port: u16,
    token: String,
    _broker: AttachBroker,
    _dir: tempfile::TempDir,
}

async fn setup() -> Env {
    let dir = tempfile::tempdir().unwrap();
    let bus = EventBus::new(1000);
    let hub = AgentHub::new(
        bus.clone(),
        Arc::new(UnavailableBackend),
        ClaudeObserver::new(dir.path().join("claude"), None),
    );
    let terms = TerminalManager::new(bus, None);
    let file = dir.path().join("attach.json");
    let broker = AttachBroker::start(
        hub.clone(),
        terms.clone(),
        file.clone(),
        CancellationToken::new(),
    )
    .await
    .unwrap();
    let info: Value = serde_json::from_str(&std::fs::read_to_string(&file).unwrap()).unwrap();
    Env {
        hub,
        terms,
        port: info["port"].as_u64().unwrap() as u16,
        token: info["token"].as_str().unwrap().to_string(),
        _broker: broker,
        _dir: dir,
    }
}

struct Shim {
    rd: BufReader<tokio::net::tcp::OwnedReadHalf>,
    wr: tokio::net::tcp::OwnedWriteHalf,
}

impl Shim {
    async fn connect(port: u16, hello: Value) -> (Self, Option<Value>) {
        let s = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
        let (rd, wr) = s.into_split();
        let mut me = Shim {
            rd: BufReader::new(rd),
            wr,
        };
        me.send(hello).await;
        let ack = me.recv().await;
        (me, ack)
    }
    async fn send(&mut self, v: Value) {
        let mut l = v.to_string();
        l.push('\n');
        self.wr.write_all(l.as_bytes()).await.unwrap();
    }
    async fn recv(&mut self) -> Option<Value> {
        let mut l = String::new();
        match tokio::time::timeout(Duration::from_secs(5), self.rd.read_line(&mut l)).await {
            Ok(Ok(n)) if n > 0 => serde_json::from_str(l.trim()).ok(),
            _ => None,
        }
    }
}

async fn wait_for<F: Fn() -> bool>(f: F) -> bool {
    for _ in 0..100 {
        if f() {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    false
}

#[tokio::test]
async fn wrong_token_is_rejected() {
    let env = setup().await;
    let (_s, ack) = Shim::connect(
        env.port,
        json!({ "t": "hello", "v": 1, "token": "nope", "kind": "stream", "dialect": "claude" }),
    )
    .await;
    assert!(ack.is_none());
    let (items, _) = env.hub.list(&SessionQuery::default()).await;
    assert!(items.is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn claude_stream_session_is_controllable() {
    let env = setup().await;
    let cwd = std::env::temp_dir().to_string_lossy().into_owned();
    let (mut shim, ack) = Shim::connect(
        env.port,
        json!({ "t": "hello", "v": 1, "token": env.token, "kind": "stream", "dialect": "claude",
                "agent": "claude_code", "origin": "vscode", "cwd": cwd }),
    )
    .await;
    assert_eq!(ack.unwrap()["t"], "welcome");
    let id = {
        let mut found = None;
        for _ in 0..100 {
            let (items, _) = env.hub.list(&SessionQuery::default()).await;
            if let Some(s) = items.into_iter().find(|s| s.id.starts_with("attached:")) {
                found = Some(s);
                break;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        let s = found.expect("attached session listed");
        assert_eq!(serde_json::to_value(s.origin).unwrap(), "vscode");
        assert_eq!(serde_json::to_value(s.control).unwrap(), "full");
        assert!(!s.managed);
        s.id
    };

    // Agent output with a session id, then a permission prompt.
    let out = |v: Value| json!({ "t": "out", "line": v.to_string() });
    shim.send(out(
        json!({ "type": "system", "subtype": "init", "session_id": "sess-1" }),
    ))
    .await;
    shim.send(out(json!({ "type": "control_request", "request_id": "r1",
        "request": { "subtype": "can_use_tool", "tool_name": "Bash", "input": { "command": "ls" } } })))
        .await;
    let hub = env.hub.clone();
    let idc = id.clone();
    assert!(
        wait_for(|| {
            futures_lite_block(hub.summary(&idc))
                .map(|s| s.waiting_for.is_some())
                .unwrap_or(false)
        })
        .await
    );
    // The observed id of the same conversation resolves to the attached session.
    assert_eq!(env.hub.summary("claude:sess-1").await.unwrap().id, id);

    // Phone answers; the shim confirms.
    let hub = env.hub.clone();
    let idc = id.clone();
    let answer = tokio::spawn(async move {
        hub.respond(&idc, "r1", "allow", None, None, None, "phone")
            .await
    });
    let inject = shim.recv().await.unwrap();
    assert_eq!(inject["t"], "inject");
    let line: Value = serde_json::from_str(inject["line"].as_str().unwrap()).unwrap();
    assert_eq!(line["response"]["request_id"], "r1");
    assert_eq!(line["response"]["response"]["behavior"], "allow");
    shim.send(json!({ "t": "accepted", "line": inject["line"] }))
        .await;
    assert!(answer.await.unwrap().is_ok());

    // Second prompt answered in the IDE first: the phone gets 409.
    shim.send(out(json!({ "type": "control_request", "request_id": "r2",
        "request": { "subtype": "can_use_tool", "tool_name": "Bash", "input": { "command": "pwd" } } })))
        .await;
    shim.send(json!({ "t": "in", "line": json!({ "type": "control_response",
        "response": { "subtype": "success", "request_id": "r2", "response": { "behavior": "deny" } } }).to_string() }))
        .await;
    tokio::time::sleep(Duration::from_millis(200)).await;
    let err = env
        .hub
        .respond(&id, "r2", "allow", None, None, None, "phone")
        .await
        .unwrap_err();
    assert_eq!(serde_json::to_value(err.code).unwrap(), "conflict");

    // A message from the phone is injected as a stream-json user message.
    env.hub
        .send_message(&id, "hello  from phone", Some("cmd-42"))
        .await
        .unwrap();
    let inject = shim.recv().await.unwrap();
    let line: Value = serde_json::from_str(inject["line"].as_str().unwrap()).unwrap();
    assert_eq!(line["type"], "user");
    // The phone's command id is echoed on the user_message event (optimistic UI).
    let page = env.hub.events(&id, None, None, 500).await.unwrap();
    let um = page["events"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["kind"] == "user_message")
        .cloned()
        .unwrap();
    assert_eq!(um["data"]["clientId"], "cmd-42");

    // The IDE closes: the session ends, nothing is killed.
    shim.send(json!({ "t": "exit", "code": 0 })).await;
    drop(shim);
    let hub = env.hub.clone();
    let idc = id.clone();
    assert!(
        wait_for(|| {
            futures_lite_block(hub.summary(&idc))
                .map(|s| serde_json::to_value(s.liveness).unwrap() == "ended")
                .unwrap_or(false)
        })
        .await
    );
    assert_eq!(env.hub.managed_count(), 0);
}

/// What a taken-over session advertises about its model and what `POST …/options` does agree:
/// the IDE owns model and effort, so the capability is BLOCKED, the request is refused with
/// `capability_unavailable`, and nothing is injected into the user's process.
#[tokio::test(flavor = "multi_thread")]
async fn attached_claude_session_capability_matches_model_change() {
    let env = setup().await;
    let cwd = std::env::temp_dir().to_string_lossy().into_owned();
    let (mut shim, ack) = Shim::connect(
        env.port,
        json!({ "t": "hello", "v": 1, "token": env.token, "kind": "stream", "dialect": "claude",
                "agent": "claude_code", "origin": "vscode", "cwd": cwd }),
    )
    .await;
    assert_eq!(ack.unwrap()["t"], "welcome");
    let mut id = None;
    for _ in 0..100 {
        let (items, _) = env.hub.list(&SessionQuery::default()).await;
        if let Some(s) = items.into_iter().find(|s| s.id.starts_with("attached:")) {
            id = Some(s.id);
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    let id = id.expect("attached session listed");

    let detail = env.hub.detail(&id).await.unwrap();
    let caps = &detail["capabilities"];
    assert_eq!(caps["sendMessage"], "PARTIAL", "still controllable");
    assert_eq!(caps["changeModel"], "BLOCKED");
    let reason = caps["reason"].as_str().expect("a reason is given");

    for (model, effort) in [
        (Some("sonnet".to_string()), None),
        (None, Some("high".to_string())),
    ] {
        let err = env.hub.set_options(&id, model, effort).await.unwrap_err();
        assert_eq!(
            serde_json::to_value(err.code).unwrap(),
            "capability_unavailable"
        );
        let details = err.details.expect("status and reason");
        assert_eq!(details["status"], "BLOCKED");
        assert_eq!(details["reason"], reason, "same reason as advertised");
    }
    // Nothing reached the user's process.
    let mut l = String::new();
    assert!(
        tokio::time::timeout(Duration::from_millis(300), shim.rd.read_line(&mut l))
            .await
            .is_err(),
        "unexpected frame for the shim: {l}"
    );
}

#[tokio::test]
async fn tty_is_mirrored_as_external_terminal() {
    let env = setup().await;
    let cwd = std::env::temp_dir().to_string_lossy().into_owned();
    let (mut shim, ack) = Shim::connect(
        env.port,
        json!({ "t": "hello", "v": 1, "token": env.token, "kind": "tty", "agent": "claude_code",
                "origin": "cli", "cwd": cwd, "cols": 100, "rows": 30 }),
    )
    .await;
    assert_eq!(ack.unwrap()["t"], "welcome");
    let terms = env.terms.clone();
    assert!(wait_for(|| !terms.list().is_empty()).await);
    let info = env.terms.list().remove(0);
    assert!(info.external);
    assert_eq!((info.cols, info.rows), (100, 30));
    // Shutting SwitchTether down never touches the user's terminal.
    assert_eq!(env.terms.count(), 0);

    use base64::Engine as _;
    let b64 = base64::engine::general_purpose::STANDARD.encode("\x1b[2J\x1b[HWelcome to the TUI");
    shim.send(json!({ "t": "pty", "b64": b64 })).await;
    let term = env.terms.get(&info.id).unwrap();
    assert!(
        wait_for(|| {
            term.snapshot()
                .lines
                .iter()
                .any(|l| l.runs.iter().any(|r| r.t.contains("Welcome to the TUI")))
        })
        .await
    );

    // Session summary links the terminal; a message is typed into the TUI.
    let (items, _) = env.hub.list(&SessionQuery::default()).await;
    let s = items.into_iter().find(|s| s.terminal_id.is_some()).unwrap();
    assert_eq!(s.terminal_id.as_deref(), Some(info.id.as_str()));
    assert_eq!(serde_json::to_value(s.origin).unwrap(), "cli");
    assert!(env
        .hub
        .send_message(&s.id, "!rm -rf /", None)
        .await
        .is_err());
    env.hub.send_message(&s.id, "hi", None).await.unwrap();
    let mut typed = Vec::new();
    for _ in 0..2 {
        let f = shim.recv().await.unwrap();
        assert_eq!(f["t"], "input");
        typed.extend(
            base64::engine::general_purpose::STANDARD
                .decode(f["b64"].as_str().unwrap())
                .unwrap(),
        );
    }
    assert_eq!(typed, b"\x1b[200~hi\x1b[201~\r");

    // Resize follows the user's window; the phone cannot resize it.
    term.resize(40, 10).unwrap();
    assert_eq!(term.info().cols, 100);
    shim.send(json!({ "t": "size", "cols": 90, "rows": 20 }))
        .await;
    assert!(wait_for(|| term.info().cols == 90).await);

    shim.send(json!({ "t": "exit", "code": 3 })).await;
    assert!(wait_for(|| term.info().exited).await);
    assert_eq!(term.info().exit_code, Some(3));
}

/// Poll a future to completion from a sync closure (test helper).
fn futures_lite_block<F: std::future::Future>(f: F) -> F::Output {
    tokio::task::block_in_place(|| tokio::runtime::Handle::current().block_on(f))
}

#[tokio::test(flavor = "multi_thread")]
async fn dsh_bridge_plugin_sessions_are_controllable() {
    let env = setup().await;
    let (mut plugin, ack) = Shim::connect(
        env.port,
        json!({"t":"hello","v":1,"token":env.token,"kind":"plugin","dialect":"dsh","pid":1,"version":"t"}),
    )
    .await;
    assert_eq!(ack.unwrap()["t"], "welcome");
    plugin
        .send(json!({"t":"session","sessionId":"session-abc"}))
        .await;
    let hub = env.hub.clone();
    assert!(
        wait_for(|| {
            let (items, _) = futures_lite_block(hub.list(&SessionQuery::default()));
            items.iter().any(|s| s.agent == "dsh")
        })
        .await
    );
    let (items, _) = env.hub.list(&SessionQuery::default()).await;
    let s = items.iter().find(|s| s.agent == "dsh").unwrap().clone();
    // The user's dsh session is reachable by its dsh id too.
    assert_eq!(env.hub.summary("dsh:abc").await.unwrap().id, s.id);

    // A message from the phone becomes a prompt frame; the plugin's result completes it.
    let hub = env.hub.clone();
    let id = s.id.clone();
    let send = tokio::spawn(async move { hub.send_message(&id, "hello dsh", None).await });
    let prompt = plugin.recv().await.unwrap();
    assert_eq!(prompt["t"], "prompt");
    assert_eq!(prompt["sessionId"], "session-abc");
    assert_eq!(prompt["text"], "hello dsh");
    plugin
        .send(json!({"t":"result","id":prompt["id"],"ok":true}))
        .await;
    send.await.unwrap().unwrap();

    // An approval is shown on the phone; answering it sends the outcome to the plugin.
    plugin.send(json!({"t":"approval","requestId":"dsh-ap-1","sessionId":"session-abc","toolName":"pwsh","reason":"Run cargo test","callId":"c1"})).await;
    let hub = env.hub.clone();
    let idc = s.id.clone();
    assert!(
        wait_for(|| futures_lite_block(hub.summary(&idc)).is_some_and(|s| s.status == "waiting"))
            .await
    );
    env.hub
        .respond(&s.id, "dsh-ap-1", "allow", None, None, None, "phone")
        .await
        .unwrap();
    let approve = plugin.recv().await.unwrap();
    assert_eq!(
        approve,
        json!({"t":"approve","requestId":"dsh-ap-1","outcome":"allowed-once"})
    );

    // Answered in the dsh UI first: the card resolves as answered on the computer.
    plugin.send(json!({"t":"approval","requestId":"dsh-ap-2","sessionId":"session-abc","toolName":"edit"})).await;
    plugin.send(json!({"t":"approval-done","requestId":"dsh-ap-2","sessionId":"session-abc","outcome":"rejected","by":"desktop"})).await;
    let hub = env.hub.clone();
    let idc = s.id.clone();
    assert!(
        wait_for(|| futures_lite_block(
            hub.respond(&idc, "dsh-ap-2", "allow", None, None, None, "phone")
        )
        .is_err())
        .await
    );

    // Disconnect: the session is no longer controllable (the user's dsh keeps running).
    drop(plugin);
    let hub = env.hub.clone();
    let idc = s.id.clone();
    assert!(
        wait_for(|| futures_lite_block(hub.summary(&idc))
            .is_some_and(|s| s.liveness != switchtether_core::agents::model::Liveness::Live))
        .await
    );
}
