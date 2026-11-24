//! End-to-end tests against a real server instance over TLS 1.3 + mTLS on
//! loopback: pairing, authorization, revocation, idempotency, step-up,
//! WebSocket events, terminals and file conflicts.

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use base64::Engine as _;
use http_body_util::{BodyExt, Full};
use hyper::body::Bytes;
use hyper::Request;
use hyper_util::rt::TokioIo;
use serde_json::{json, Value};
use switchtether_core::agents::claude::ClaudeObserver;
use switchtether_core::agents::AgentHub;
use switchtether_core::api::ApiContext;
use switchtether_core::backend::UnavailableBackend;
use switchtether_core::events::EventBus;
use switchtether_core::identity::ServerIdentity;
use switchtether_core::net::NetworkMode;
use switchtether_core::registry::Registry;
use switchtether_core::terminal::TerminalManager;
use switchtether_core::{RemoteServer, RemoteServerConfig, RemoteServerHandle};
use tokio_rustls::TlsConnector;

struct Fixture {
    handle: RemoteServerHandle,
    addr: SocketAddr,
    server_fp: String,
    _tmp: tempfile::TempDir,
}

async fn start() -> Fixture {
    let tmp = tempfile::tempdir().unwrap();
    let identity = ServerIdentity::generate("test-desktop").unwrap();
    let bus = EventBus::new(1000);
    let registry = Registry::open(&tmp.path().join("remote.db")).unwrap();
    let backend = Arc::new(UnavailableBackend);
    let hub = AgentHub::new(
        bus.clone(),
        backend.clone(),
        ClaudeObserver::new(PathBuf::from("/nonexistent-claude"), None),
    );
    let terms = TerminalManager::new(bus.clone(), None);
    let ctx = ApiContext::new(
        RemoteServerConfig {
            data_dir: tmp.path().to_path_buf(),
            port: 0,
            network_mode: NetworkMode::LocalhostOnly,
            server_name: "Test Desktop".into(),
            server_version: "0.0.0-test".into(),
            relay: None,
        },
        "server-1".into(),
        identity.clone(),
        registry,
        bus,
        backend,
        terms,
        hub,
    );
    let handle = RemoteServer::start(ctx).await.unwrap();
    let addr = handle.local_addrs[0];
    Fixture {
        server_fp: identity.fingerprint.clone(),
        handle,
        addr,
        _tmp: tmp,
    }
}

/// A phone: its own client identity + pinned server fingerprint.
struct Client {
    _id: ServerIdentity,
    connector: TlsConnector,
    addr: SocketAddr,
}

impl Client {
    fn new(f: &Fixture) -> Self {
        Self::with_pin(f, &f.server_fp)
    }
    fn with_pin(f: &Fixture, pin: &str) -> Self {
        let id = ServerIdentity::generate("phone").unwrap();
        let cfg = switchtether_core::tls::client::client_config(&id, pin).unwrap();
        Self {
            _id: id,
            connector: TlsConnector::from(cfg),
            addr: f.addr,
        }
    }

    async fn tls(&self) -> std::io::Result<tokio_rustls::client::TlsStream<tokio::net::TcpStream>> {
        let tcp = tokio::net::TcpStream::connect(self.addr).await?;
        let name = rustls::pki_types::ServerName::try_from("switchtether.local").unwrap();
        self.connector.connect(name, tcp).await
    }

    async fn req(
        &self,
        method: &str,
        path: &str,
        body: Option<Value>,
    ) -> Result<(u16, Value, hyper::HeaderMap), String> {
        let tls = self.tls().await.map_err(|e| e.to_string())?;
        let (mut sender, conn) = hyper::client::conn::http1::handshake(TokioIo::new(tls))
            .await
            .map_err(|e| e.to_string())?;
        tokio::spawn(conn);
        let body = body.map(|b| b.to_string()).unwrap_or_default();
        let req = Request::builder()
            .method(method)
            .uri(path)
            .header("host", "switchtether.local")
            .header("content-type", "application/json")
            .body(Full::new(Bytes::from(body)))
            .unwrap();
        let resp = sender.send_request(req).await.map_err(|e| e.to_string())?;
        let status = resp.status().as_u16();
        let headers = resp.headers().clone();
        let bytes = resp
            .into_body()
            .collect()
            .await
            .map_err(|e| e.to_string())?
            .to_bytes();
        let v = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
        Ok((status, v, headers))
    }

    async fn get(&self, path: &str) -> (u16, Value) {
        let (s, v, _) = self.req("GET", path, None).await.unwrap();
        (s, v)
    }

    async fn post(&self, path: &str, body: Value) -> (u16, Value) {
        let (s, v, _) = self.req("POST", path, Some(body)).await.unwrap();
        (s, v)
    }
}

async fn pair(f: &Fixture, c: &Client, with_step_up: Option<&[u8]>) -> String {
    let offer = f.handle.ctx.pairing.create(
        vec!["127.0.0.1".into()],
        f.addr.port(),
        &f.server_fp,
        "Test Desktop",
    );
    let (s, v) = c
        .post(
            "/v1/pair",
            json!({
                "pairingId": offer.pairing_id, "secret": offer.secret, "deviceName": "Phone",
                "platform": "android", "protocolVersion": 1,
                "stepUpPublicKey": with_step_up.map(|k| base64::engine::general_purpose::STANDARD.encode(k)),
            }),
        )
        .await;
    assert_eq!(s, 200, "{v}");
    assert_eq!(v["serverFingerprint"], f.server_fp);
    v["deviceId"].as_str().unwrap().to_string()
}

#[tokio::test]
async fn unpaired_clients_only_reach_pairing() {
    let f = start().await;
    let c = Client::new(&f);
    let (s, v) = c.get("/v1/hello").await;
    assert_eq!(s, 200);
    assert_eq!(v["paired"], false);
    assert_eq!(v["protocolVersion"], 1);
    for path in [
        "/v1/status",
        "/v1/sessions",
        "/v1/terminals",
        "/v1/fs/roots",
        "/v1/providers",
    ] {
        let (s, v) = c.get(path).await;
        assert_eq!(s, 401, "{path}: {v}");
        assert_eq!(v["error"]["code"], "unpaired");
    }
    f.handle.shutdown().await;
}

#[tokio::test]
async fn wrong_server_pin_is_rejected_by_client() {
    let f = start().await;
    let c = Client::with_pin(&f, &"0".repeat(64));
    assert!(c.req("GET", "/v1/hello", None).await.is_err());
    f.handle.shutdown().await;
}

#[tokio::test]
async fn client_without_certificate_is_rejected() {
    let f = start().await;
    let cfg = rustls::ClientConfig::builder_with_provider(switchtether_core::tls::provider())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .unwrap()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(
            switchtether_core::tls::client::PinnedServerVerifier::new(f.server_fp.clone()),
        ))
        .with_no_client_auth();
    let connector = TlsConnector::from(Arc::new(cfg));
    let tcp = tokio::net::TcpStream::connect(f.addr).await.unwrap();
    let name = rustls::pki_types::ServerName::try_from("switchtether.local").unwrap();
    // Handshake may "succeed" client-side in TLS 1.3; the first read must fail.
    match connector.connect(name, tcp).await {
        Err(_) => {}
        Ok(mut s) => {
            use tokio::io::{AsyncReadExt, AsyncWriteExt};
            let _ = s
                .write_all(b"GET /v1/hello HTTP/1.1\r\nhost: x\r\n\r\n")
                .await;
            let mut buf = [0u8; 64];
            let r = tokio::time::timeout(Duration::from_secs(5), s.read(&mut buf))
                .await
                .unwrap();
            assert!(
                r.is_err() || r.unwrap() == 0,
                "server must not answer without client cert"
            );
        }
    }
    f.handle.shutdown().await;
}

#[tokio::test]
async fn pairing_then_revocation() {
    let f = start().await;
    let c = Client::new(&f);
    let device_id = pair(&f, &c, None).await;
    let (s, v) = c.get("/v1/status").await;
    assert_eq!(s, 200, "{v}");
    assert_eq!(v["deviceId"], device_id);
    let (s, v) = c.get("/v1/hello").await;
    assert_eq!((s, v["paired"].clone()), (200, json!(true)));

    // Pairing secret is single-use.
    let offer = f.handle.ctx.pairing.create(vec![], 1, &f.server_fp, "x");
    let other = Client::new(&f);
    let body = json!({"pairingId": offer.pairing_id, "secret": "wrong", "deviceName":"evil","platform":"android","protocolVersion":1});
    let (s, _) = other.post("/v1/pair", body).await;
    assert_eq!(s, 403);

    // Revoke → the device can no longer do anything, even pair again with the same cert.
    assert!(f.handle.ctx.revoke_device(&device_id, "test").unwrap());
    let r = c.req("GET", "/v1/status", None).await;
    assert!(
        r.is_err() || r.unwrap().0 == 403,
        "revoked cert must be refused"
    );
    f.handle.shutdown().await;
}

#[tokio::test]
async fn revocation_closes_event_stream_immediately() {
    let f = start().await;
    let c = Client::new(&f);
    let device_id = pair(&f, &c, None).await;
    let tls = c.tls().await.unwrap();
    let (mut ws, _) =
        tokio_tungstenite::client_async("wss://switchtether.local/v1/events?since=0", tls)
            .await
            .unwrap();
    use futures::{SinkExt, StreamExt};
    f.handle.ctx.events.publish("test.topic", json!({"x": 1}));
    // Drain until we see our event.
    let mut seen = false;
    for _ in 0..20 {
        if let Some(Ok(tokio_tungstenite::tungstenite::Message::Text(t))) =
            tokio::time::timeout(Duration::from_secs(5), ws.next())
                .await
                .unwrap()
        {
            if t.contains("test.topic") {
                seen = true;
                break;
            }
        }
    }
    assert!(seen);
    let _ = ws
        .send(tokio_tungstenite::tungstenite::Message::Text(
            r#"{"type":"ping"}"#.into(),
        ))
        .await;
    f.handle.ctx.revoke_device(&device_id, "test").unwrap();
    let closed = tokio::time::timeout(Duration::from_secs(5), async {
        while let Some(m) = ws.next().await {
            match m {
                Ok(tokio_tungstenite::tungstenite::Message::Close(frame)) => {
                    return frame.map(|f| u16::from(f.code))
                }
                Err(_) => return Some(0),
                _ => {}
            }
        }
        Some(0)
    })
    .await
    .expect("socket must close promptly after revocation");
    assert!(matches!(closed, Some(4403) | Some(0)), "got {closed:?}");
    f.handle.shutdown().await;
}

#[tokio::test]
async fn events_replay_after_reconnect() {
    let f = start().await;
    let c = Client::new(&f);
    pair(&f, &c, None).await;
    let epoch = f.handle.ctx.events.epoch().to_string();
    let head = f.handle.ctx.events.head_seq();
    f.handle.ctx.events.publish("a", json!(1));
    f.handle.ctx.events.publish("b", json!(2));
    let tls = c.tls().await.unwrap();
    let url = format!("wss://switchtether.local/v1/events?since={head}&epoch={epoch}");
    let (mut ws, _) = tokio_tungstenite::client_async(url, tls).await.unwrap();
    use futures::StreamExt;
    let mut topics = vec![];
    while topics.len() < 2 {
        if let Some(Ok(tokio_tungstenite::tungstenite::Message::Text(t))) =
            tokio::time::timeout(Duration::from_secs(5), ws.next())
                .await
                .unwrap()
        {
            let v: Value = serde_json::from_str(&t).unwrap();
            topics.push(v["topic"].as_str().unwrap().to_string());
        }
    }
    assert_eq!(topics, vec!["a", "b"]);
    // Stale epoch → resync instruction.
    let tls = c.tls().await.unwrap();
    let (mut ws, _) = tokio_tungstenite::client_async(
        "wss://switchtether.local/v1/events?since=5&epoch=old",
        tls,
    )
    .await
    .unwrap();
    let first = tokio::time::timeout(Duration::from_secs(5), ws.next())
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert!(first.to_text().unwrap().contains("\"resync\""));
    f.handle.shutdown().await;
}

#[tokio::test]
async fn idempotent_commands_and_unknown_outcome() {
    let f = start().await;
    let c = Client::new(&f);
    pair(&f, &c, None).await;
    // Without commandId → rejected.
    let (s, v) = c.post("/v1/providers/claude/p1/switch", json!({})).await;
    assert_eq!(s, 400, "{v}");
    // With commandId: backend unavailable → 503, stored as failed.
    let (s, v) = c
        .post(
            "/v1/providers/claude/p1/switch",
            json!({"commandId": "cmd-1"}),
        )
        .await;
    assert_eq!(s, 503, "{v}");
    // Replay returns the same stored error without re-executing.
    let (s, v, h) = c
        .req(
            "POST",
            "/v1/providers/claude/p1/switch",
            Some(json!({"commandId": "cmd-1"})),
        )
        .await
        .unwrap();
    assert_eq!(s, 503, "{v}");
    assert_eq!(h.get("x-command-replayed").unwrap(), "true");
    let (s, v) = c.get("/v1/commands/cmd-1").await;
    assert_eq!(s, 200);
    assert_eq!(v["status"], "failed");
    // Reusing an id for another operation is refused.
    let (s, _) = c
        .post(
            "/v1/sessions/claude:x/interrupt",
            json!({"commandId": "cmd-1"}),
        )
        .await;
    assert_eq!(s, 409);
    f.handle.shutdown().await;
}

#[tokio::test]
async fn control_of_external_sessions_is_blocked() {
    let f = start().await;
    let c = Client::new(&f);
    pair(&f, &c, None).await;
    let (s, v) = c
        .post(
            "/v1/sessions/claude:abc/messages",
            json!({"commandId":"m1","text":"hi"}),
        )
        .await;
    assert_eq!(s, 422, "{v}");
    assert_eq!(v["error"]["code"], "capability_unavailable");
    assert_eq!(v["error"]["details"]["status"], "BLOCKED");
    let (s, v) = c.get("/v1/capabilities").await;
    assert_eq!(s, 200);
    assert!(v["agents"].as_array().unwrap().len() >= 4);
    f.handle.shutdown().await;
}

fn step_up_key() -> (ring::signature::EcdsaKeyPair, Vec<u8>) {
    use ring::signature::{EcdsaKeyPair, KeyPair, ECDSA_P256_SHA256_ASN1_SIGNING};
    let rng = ring::rand::SystemRandom::new();
    let pkcs8 = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, &rng).unwrap();
    let kp =
        EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, pkcs8.as_ref(), &rng).unwrap();
    let mut spki = vec![
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08,
        0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    ];
    spki.extend_from_slice(kp.public_key().as_ref());
    (kp, spki)
}

async fn grant(c: &Client, device_id: &str, kp: &ring::signature::EcdsaKeyPair, scope: &str) {
    let (s, ch) = c
        .post("/v1/auth/step-up/challenge", json!({"scope": scope}))
        .await;
    assert_eq!(s, 200, "{ch}");
    let payload = format!(
        "switchtether-stepup-v1\n{device_id}\n{}\n{}\n{scope}",
        ch["challengeId"].as_str().unwrap(),
        ch["nonce"].as_str().unwrap()
    );
    let sig = kp
        .sign(&ring::rand::SystemRandom::new(), payload.as_bytes())
        .unwrap();
    let (s, v) = c
        .post(
            "/v1/auth/step-up/verify",
            json!({"challengeId": ch["challengeId"], "signature": base64::engine::general_purpose::STANDARD.encode(sig.as_ref())}),
        )
        .await;
    assert_eq!(s, 200, "{v}");
}

#[tokio::test]
async fn terminal_requires_step_up_and_streams_output() {
    let f = start().await;
    let c = Client::new(&f);
    let (kp, spki) = step_up_key();
    let device_id = pair(&f, &c, Some(&spki)).await;
    let (s, v) = c
        .post(
            "/v1/terminals",
            json!({"commandId":"t1","cols":80,"rows":24}),
        )
        .await;
    assert_eq!(s, 403, "{v}");
    assert_eq!(v["error"]["code"], "step_up_required");
    grant(&c, &device_id, &kp, "terminal").await;
    let (s, v) = c
        .post(
            "/v1/terminals",
            json!({"commandId":"t2","cols":80,"rows":24}),
        )
        .await;
    assert_eq!(s, 200, "{v}");
    let tid = v["id"].as_str().unwrap().to_string();

    let tls = c.tls().await.unwrap();
    let (mut ws, _) = tokio_tungstenite::client_async(
        format!("wss://switchtether.local/v1/terminals/{tid}/stream"),
        tls,
    )
    .await
    .unwrap();
    use futures::{SinkExt, StreamExt};
    let first = tokio::time::timeout(Duration::from_secs(10), ws.next())
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert!(first.to_text().unwrap().contains("\"snapshot\""));
    let enter = if cfg!(windows) { "\r" } else { "\n" };
    ws.send(tokio_tungstenite::tungstenite::Message::Text(
        json!({"type":"input","data": format!("echo E2E_TERMINAL_OK{enter}")}).to_string(),
    ))
    .await
    .unwrap();
    let mut ok = false;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
    while tokio::time::Instant::now() < deadline {
        if let Ok(Some(Ok(m))) = tokio::time::timeout(Duration::from_secs(5), ws.next()).await {
            {
                let t = m.to_text().unwrap_or("").to_string();
                // the echoed command itself contains the text twice once executed
                if t.matches("E2E_TERMINAL_OK").count() >= 1 && t.contains("patch") {
                    let v: Value = serde_json::from_str(&t).unwrap();
                    let lines: Vec<String> = v["lines"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .map(|l| {
                            l["line"]["runs"]
                                .as_array()
                                .unwrap()
                                .iter()
                                .map(|r| r["t"].as_str().unwrap().to_string())
                                .collect()
                        })
                        .collect();
                    if lines
                        .iter()
                        .filter(|l| l.contains("E2E_TERMINAL_OK"))
                        .count()
                        >= 1
                        && lines.iter().any(|l| l.trim() == "E2E_TERMINAL_OK")
                    {
                        ok = true;
                        break;
                    }
                }
            }
        }
        // also accept by inspecting the server-side screen
        if f.handle
            .ctx
            .terminals
            .get(&tid)
            .unwrap()
            .snapshot()
            .lines
            .iter()
            .any(|l| l.text().trim() == "E2E_TERMINAL_OK")
        {
            ok = true;
            break;
        }
    }
    assert!(ok, "terminal output never arrived");
    let (s, _) = c
        .req("DELETE", &format!("/v1/terminals/{tid}"), None)
        .await
        .map(|(s, v, _)| (s, v))
        .unwrap();
    assert_eq!(s, 200);
    f.handle.shutdown().await;
}

#[tokio::test]
async fn file_write_conflict_over_api() {
    let f = start().await;
    let c = Client::new(&f);
    let (kp, spki) = step_up_key();
    let device_id = pair(&f, &c, Some(&spki)).await;
    let dir = tempfile::tempdir().unwrap();
    let canon = std::fs::canonicalize(dir.path()).unwrap();
    let canon = canon
        .to_string_lossy()
        .trim_start_matches(r"\\?\")
        .to_string();
    let path = format!("{canon}{}notes.txt", std::path::MAIN_SEPARATOR);
    std::fs::write(&path, "v1\n").unwrap();
    let enc: String = url_encode(&path);
    let (s, r) = c.get(&format!("/v1/fs/read?path={enc}")).await;
    assert_eq!(s, 200, "{r}");
    let base = r["sha256"].as_str().unwrap().to_string();
    // Writes need step-up.
    let (s, _) = c
        .req(
            "PUT",
            "/v1/fs/write",
            Some(json!({"commandId":"w0","path":path,"text":"x","baseSha256":base})),
        )
        .await
        .map(|(s, v, _)| (s, v))
        .unwrap();
    assert_eq!(s, 403);
    grant(&c, &device_id, &kp, "files_write").await;
    // IDE saves in the meantime.
    std::fs::write(&path, "desktop\n").unwrap();
    let (s, v, _) = c
        .req(
            "PUT",
            "/v1/fs/write",
            Some(json!({"commandId":"w1","path":path,"text":"phone\n","baseSha256":base})),
        )
        .await
        .unwrap();
    assert_eq!(s, 409, "{v}");
    assert_eq!(v["error"]["details"]["currentText"], "desktop\n");
    assert_eq!(std::fs::read_to_string(&path).unwrap(), "desktop\n");
    // Path traversal & relative paths refused.
    let (s, _) = c.get("/v1/fs/list?path=..%2F..").await;
    assert_eq!(s, 400);
    f.handle.shutdown().await;
}

fn url_encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                (b as char).to_string()
            }
            _ => format!("%{b:02X}"),
        })
        .collect()
}

#[tokio::test]
async fn oversized_body_is_rejected() {
    let f = start().await;
    let c = Client::new(&f);
    pair(&f, &c, None).await;
    let big = "x".repeat(2 * 1024 * 1024);
    let r = c
        .req("POST", "/v1/pair", Some(json!({"pairingId": big})))
        .await;
    // A closed connection is also acceptable.
    if let Ok((s, _, _)) = r {
        assert_eq!(s, 413)
    }
    f.handle.shutdown().await;
}

#[tokio::test]
async fn concurrent_pairing_race_only_one_wins() {
    let f = start().await;
    let offer = f.handle.ctx.pairing.create(vec![], 1, &f.server_fp, "x");
    let mut tasks = vec![];
    for _ in 0..6 {
        let c = Client::new(&f);
        let body = json!({"pairingId": offer.pairing_id, "secret": offer.secret, "deviceName":"P","platform":"android","protocolVersion":1});
        tasks.push(tokio::spawn(
            async move { c.post("/v1/pair", body).await.0 },
        ));
    }
    let mut ok = 0;
    for t in tasks {
        if t.await.unwrap() == 200 {
            ok += 1;
        }
    }
    assert_eq!(ok, 1, "a pairing secret must be usable exactly once");
    f.handle.shutdown().await;
}

#[tokio::test]
async fn server_restart_keeps_devices() {
    let tmp = tempfile::tempdir().unwrap();
    let db = tmp.path().join("remote.db");
    let fp;
    {
        let reg = Registry::open(&db).unwrap();
        let id = ServerIdentity::generate("phone").unwrap();
        fp = id.fingerprint.clone();
        reg.register_device(switchtether_core::registry::DeviceRecord {
            id: "d1".into(),
            name: "P".into(),
            platform: "android".into(),
            os_version: None,
            app_version: None,
            cert_fingerprint: fp.clone(),
            step_up_key: None,
            step_up_enabled: false,
            created_at_ms: 0,
            last_seen_at_ms: None,
            last_ip: None,
            revoked_at_ms: None,
        })
        .unwrap();
        reg.revoke_device("d1").unwrap();
    }
    let reg = Registry::open(&db).unwrap();
    assert!(
        reg.is_revoked_fingerprint(&fp),
        "revocation must survive restart"
    );
}

#[tokio::test]
async fn pairing_with_code_and_status_reports_hosts() {
    let f = start().await;
    let offer = f.handle.ctx.pairing.create(vec![], 1, &f.server_fp, "x");
    let body = |code: &str| json!({"code": code, "deviceName":"P","platform":"android","protocolVersion":1});
    // A wrong code is refused like a wrong secret.
    let wrong = if offer.code == "000000" {
        "000001"
    } else {
        "000000"
    };
    let (s, _) = Client::new(&f).post("/v1/pair", body(wrong)).await;
    assert_eq!(s, 403);
    let c = Client::new(&f);
    let spaced = format!("{} {}", &offer.code[..3], &offer.code[3..]);
    let (s, v) = c.post("/v1/pair", body(&spaced)).await;
    assert_eq!(s, 200, "{v}");
    assert_eq!(v["serverFingerprint"], f.server_fp);
    let (s, v) = c.get("/v1/status").await;
    assert_eq!(s, 200, "{v}");
    assert!(v["hosts"].is_array(), "{v}");
    assert!(v["wake"].is_array(), "{v}");
    // Single use.
    let (s, _) = Client::new(&f).post("/v1/pair", body(&offer.code)).await;
    assert_eq!(s, 410);
    f.handle.shutdown().await;
}

#[tokio::test]
async fn port_forward_tunnel_carries_http_over_mtls() {
    use futures::{SinkExt, StreamExt};
    use switchtether_core::forward::{decode, encode, FrameType};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio_tungstenite::tungstenite::Message;

    // A tiny HTTP server on the desktop's loopback.
    let http = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = http.local_addr().unwrap().port();
    tokio::spawn(async move {
        loop {
            let (mut s, _) = http.accept().await.unwrap();
            tokio::spawn(async move {
                let mut buf = vec![0u8; 4096];
                let n = s.read(&mut buf).await.unwrap();
                let req = String::from_utf8_lossy(&buf[..n]).to_string();
                let body = format!("hello from {}", req.lines().next().unwrap_or(""));
                let resp = format!(
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                s.write_all(resp.as_bytes()).await.unwrap();
            });
        }
    });

    let f = start().await;
    let c = Client::new(&f);
    let (kp, spki) = step_up_key();
    let device_id = pair(&f, &c, Some(&spki)).await;

    // Listing needs no step-up; creating a forward does.
    let (s, v) = c.get("/v1/forwards/ports").await;
    assert_eq!(s, 200, "{v}");
    assert!(v["ports"].is_array());
    let (s, v) = c.post("/v1/forwards", json!({ "port": port })).await;
    assert_eq!(s, 403, "{v}");
    assert_eq!(v["error"]["details"]["scope"], "port_forward");
    grant(&c, &device_id, &kp, "port_forward").await;
    let (s, v) = c.post("/v1/forwards", json!({ "port": 0 })).await;
    assert_eq!(s, 400, "{v}");
    let (s, v) = c
        .post("/v1/forwards", json!({ "port": port, "ttlMinutes": 30 }))
        .await;
    assert_eq!(s, 200, "{v}");
    assert_eq!(v["listening"], true);
    let lease = v["id"].as_str().unwrap().to_string();

    // Another paired phone cannot use this lease.
    let other = Client::new(&f);
    pair(&f, &other, None).await;
    let err = tokio_tungstenite::client_async(
        format!("wss://switchtether.local/v1/forwards/{lease}/tunnel"),
        other.tls().await.unwrap(),
    )
    .await
    .expect_err("foreign lease");
    match err {
        tokio_tungstenite::tungstenite::Error::Http(r) => assert_eq!(r.status(), 404),
        e => panic!("unexpected {e}"),
    }

    let tls = c.tls().await.unwrap();
    let (mut ws, _) = tokio_tungstenite::client_async(
        format!("wss://switchtether.local/v1/forwards/{lease}/tunnel"),
        tls,
    )
    .await
    .unwrap();
    // Two requests on two streams, sent before OPENED (as the phone does).
    for (id, path) in [(1u32, "/one"), (3u32, "/two")] {
        ws.send(Message::Binary(encode(FrameType::Open, id, &[])))
            .await
            .unwrap();
        ws.send(Message::Binary(encode(
            FrameType::Data,
            id,
            format!("GET {path} HTTP/1.1\r\nHost: localhost:{port}\r\n\r\n").as_bytes(),
        )))
        .await
        .unwrap();
    }
    let mut bodies: std::collections::HashMap<u32, Vec<u8>> = Default::default();
    let mut fins = 0;
    while fins < 2 {
        let m = tokio::time::timeout(Duration::from_secs(10), ws.next())
            .await
            .expect("frame in time")
            .unwrap()
            .unwrap();
        let Message::Binary(b) = m else { continue };
        let (k, id, p) = decode(&b).unwrap();
        match k {
            FrameType::Data => bodies.entry(id).or_default().extend_from_slice(p),
            FrameType::Fin => fins += 1,
            FrameType::Reset => panic!("reset {}", String::from_utf8_lossy(p)),
            _ => {}
        }
    }
    assert!(String::from_utf8_lossy(&bodies[&1]).ends_with("hello from GET /one HTTP/1.1"));
    assert!(String::from_utf8_lossy(&bodies[&3]).ends_with("hello from GET /two HTTP/1.1"));

    let (s, v) = c.get("/v1/forwards").await;
    assert_eq!(s, 200);
    assert_eq!(v["forwards"][0]["port"], port);
    assert_eq!(v["forwards"][0]["connected"], true);
    assert_eq!(v["forwards"][0]["totalStreams"], 2);

    // Stopping the forward closes its tunnel with 4410; the lease is gone.
    let (s, _, _) = c
        .req("DELETE", &format!("/v1/forwards/{lease}"), None)
        .await
        .unwrap();
    assert_eq!(s, 200);
    let mut code = None;
    while let Ok(Some(Ok(m))) = tokio::time::timeout(Duration::from_secs(5), ws.next()).await {
        if let Message::Close(Some(cf)) = m {
            code = Some(u16::from(cf.code));
            break;
        }
    }
    assert_eq!(code, Some(4410));
    let (s, v) = c.get("/v1/forwards").await;
    assert_eq!(s, 200);
    assert_eq!(v["forwards"].as_array().unwrap().len(), 0);
    f.handle.shutdown().await;
}

/// A phone with a push subscription and no event stream is woken by an encrypted, content-free
/// POST to its push endpoint when a session starts waiting (docs/PROTOCOL.md §7.12).
#[tokio::test]
async fn push_wake_reaches_the_endpoint_while_the_phone_is_away() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let f = start().await;
    let c = Client::new(&f);
    pair(&f, &c, None).await;

    // A stand-in push server on loopback: records each request and answers 201.
    let push = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let push_port = push.local_addr().unwrap().port();
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<String>();
    tokio::spawn(async move {
        loop {
            let Ok((mut s, _)) = push.accept().await else {
                return;
            };
            let tx = tx.clone();
            tokio::spawn(async move {
                let mut buf = Vec::new();
                let mut chunk = [0u8; 4096];
                loop {
                    let n = s.read(&mut chunk).await.unwrap_or(0);
                    if n == 0 {
                        break;
                    }
                    buf.extend_from_slice(&chunk[..n]);
                    let text = String::from_utf8_lossy(&buf).to_string();
                    if let Some(h) = text.find("\r\n\r\n") {
                        let len = text[..h]
                            .lines()
                            .find_map(|l| {
                                l.to_ascii_lowercase()
                                    .strip_prefix("content-length:")
                                    .map(|v| v.trim().parse::<usize>().unwrap_or(0))
                            })
                            .unwrap_or(0);
                        if buf.len() >= h + 4 + len {
                            let _ = tx.send(text[..h].to_string());
                            let _ = s
                                .write_all(b"HTTP/1.1 201 Created\r\ncontent-length: 0\r\n\r\n")
                                .await;
                            return;
                        }
                    }
                }
            });
        }
    });

    let rng = ring::rand::SystemRandom::new();
    let key =
        ring::agreement::EphemeralPrivateKey::generate(&ring::agreement::ECDH_P256, &rng).unwrap();
    let p256dh = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .encode(key.compute_public_key().unwrap().as_ref());
    let auth = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode([7u8; 16]);
    let endpoint = format!("http://127.0.0.1:{push_port}/up/AbCdEfGhIjKlMnOpQrStUv");

    let (s, v) = c.get("/v1/push").await;
    assert_eq!(s, 200, "{v}");
    assert_eq!(v["registered"], false);
    assert_eq!(v["vapidPublicKey"].as_str().unwrap().len(), 87);
    let (s, _, _) = c
        .req(
            "PUT",
            "/v1/push",
            Some(json!({ "endpoint": "file:///etc/passwd", "p256dh": p256dh, "auth": auth })),
        )
        .await
        .unwrap();
    assert_ne!(s, 200);
    let (s, v, _) = c
        .req(
            "PUT",
            "/v1/push",
            Some(json!({ "endpoint": endpoint, "p256dh": p256dh, "auth": auth })),
        )
        .await
        .unwrap();
    assert_eq!(s, 200, "{v}");
    assert_eq!(c.get("/v1/push").await.1["registered"], true);

    // Capability advertised.
    let (_, caps) = c.get("/v1/capabilities").await;
    let features = caps["features"].as_array().unwrap();
    assert!(features.contains(&json!("push_wake")) && features.contains(&json!("session_search")));

    // No event stream open: a session that starts waiting wakes the phone.
    f.handle.ctx.events.publish(
        "session.upserted",
        json!({ "id": "claude:x", "status": "busy" }),
    );
    f.handle.ctx.events.publish(
        "session.upserted",
        json!({ "id": "claude:x", "status": "waiting" }),
    );
    let head = tokio::time::timeout(Duration::from_secs(10), rx.recv())
        .await
        .expect("push request")
        .unwrap()
        .to_ascii_lowercase();
    assert!(
        head.starts_with("post /up/abcdefghijklmnopqrstuv http/1.1"),
        "{head}"
    );
    assert!(head.contains("content-encoding: aes128gcm"), "{head}");
    assert!(head.contains("authorization: vapid t="), "{head}");
    assert!(head.contains("ttl: "), "{head}");

    // Explicit test push (settings button).
    let (s, v) = c.post("/v1/push/test", json!({})).await;
    assert_eq!(s, 200, "{v}");
    assert_eq!(v["delivered"], true);

    // Search query validation.
    let (s, _) = c.get("/v1/sessions/search?q=").await;
    assert_ne!(s, 200);

    let (s, v, _) = c.req("DELETE", "/v1/push", None).await.unwrap();
    assert_eq!(s, 200, "{v}");
    assert_eq!(v["removed"], true);
    assert!(!f.handle.ctx.push.has_subscribers());
    f.handle.shutdown().await;
}
