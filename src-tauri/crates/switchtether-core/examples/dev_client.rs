//! Minimal command-line client for the Remote API (debugging / smoke tests).
//!
//!   dev_client pair '<switchtether://pair?...>'    # pairs and stores identity in --state dir
//!   dev_client get /v1/sessions                    # authenticated GET, prints JSON
//!   dev_client post /v1/sessions '{"commandId":"…",…}'
//!
//! Uses a file-backed client identity (NOT how the phone stores keys; the
//! Android app keeps its key in the Android Keystore).

use std::path::PathBuf;

use http_body_util::{BodyExt, Full};
use hyper::body::Bytes;
use hyper_util::rt::TokioIo;
use serde_json::{json, Value};
use switchtether_core::identity::ServerIdentity;
use switchtether_core::secret_store::FileStore;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let state = std::env::var("ST_CLIENT_STATE")
        .map(PathBuf::from)
        .unwrap_or_else(|_| std::env::temp_dir().join("switchtether-dev-client"));
    std::fs::create_dir_all(&state)?;
    let store = FileStore::new(state.join("secrets"));
    let (me, _) = ServerIdentity::load_or_create(&state, &store, "dev-client")?;
    match args.first().map(|s| s.as_str()) {
        Some("pair") => {
            let uri = url_parse(args.get(1).expect("pairing uri"));
            let host = uri["h"].split(',').next().unwrap().to_string();
            let target = serde_json::json!({ "host": host, "port": uri["p"], "fp": uri["fp"] });
            std::fs::write(state.join("target.json"), target.to_string())?;
            let (_, spki) = step_up_key(&state)?;
            use base64::Engine as _;
            let body = json!({
                "pairingId": uri["id"], "secret": uri["s"], "deviceName": "dev-client",
                "platform": "cli", "protocolVersion": 1,
                "stepUpPublicKey": base64::engine::general_purpose::STANDARD.encode(spki),
            });
            let (s, v) = call(&me, &state, "POST", "/v1/pair", Some(body)).await?;
            println!("{s} {}", serde_json::to_string_pretty(&v)?);
        }
        Some("stepup") => {
            // Test-only equivalent of the phone's biometric-gated Keystore signature.
            let scope = args.get(1).map(|s| s.as_str()).unwrap_or("terminal");
            let (s1, dev) = call(&me, &state, "GET", "/v1/device", None).await?;
            anyhow::ensure!(s1 == 200, "not paired: {dev}");
            let (_, ch) = call(
                &me,
                &state,
                "POST",
                "/v1/auth/step-up/challenge",
                Some(json!({ "scope": scope })),
            )
            .await?;
            let payload = format!(
                "switchtether-stepup-v1\n{}\n{}\n{}\n{scope}",
                dev["id"].as_str().unwrap_or(""),
                ch["challengeId"].as_str().unwrap_or(""),
                ch["nonce"].as_str().unwrap_or("")
            );
            let (kp, _) = step_up_key(&state)?;
            let sig = kp
                .sign(&ring::rand::SystemRandom::new(), payload.as_bytes())
                .map_err(|_| anyhow::anyhow!("sign"))?;
            use base64::Engine as _;
            let (s2, v) = call(&me, &state, "POST", "/v1/auth/step-up/verify", Some(json!({
                "challengeId": ch["challengeId"], "signature": base64::engine::general_purpose::STANDARD.encode(sig.as_ref())
            }))).await?;
            println!("{s2} {}", serde_json::to_string_pretty(&v)?);
        }
        Some(m @ ("get" | "post" | "put" | "delete")) => {
            let path = args.get(1).expect("path");
            let body = args.get(2).map(|b| serde_json::from_str(b)).transpose()?;
            let (s, v) = call(&me, &state, &m.to_uppercase(), path, body).await?;
            println!("{s} {}", serde_json::to_string_pretty(&v)?);
        }
        _ => eprintln!(
            "usage: dev_client pair <uri> | stepup <scope> | get <path> | post <path> <json>"
        ),
    }
    Ok(())
}

/// File-backed P-256 step-up key (the phone uses a biometric-gated Keystore key).
fn step_up_key(
    state: &std::path::Path,
) -> anyhow::Result<(ring::signature::EcdsaKeyPair, Vec<u8>)> {
    use ring::signature::{EcdsaKeyPair, KeyPair, ECDSA_P256_SHA256_ASN1_SIGNING};
    let rng = ring::rand::SystemRandom::new();
    let path = state.join("stepup.pk8");
    let pkcs8 = match std::fs::read(&path) {
        Ok(b) => b,
        Err(_) => {
            let doc = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, &rng)
                .map_err(|_| anyhow::anyhow!("keygen"))?;
            std::fs::write(&path, doc.as_ref())?;
            doc.as_ref().to_vec()
        }
    };
    let kp = EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, &pkcs8, &rng)
        .map_err(|_| anyhow::anyhow!("bad key"))?;
    let mut spki = vec![
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08,
        0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    ];
    spki.extend_from_slice(kp.public_key().as_ref());
    Ok((kp, spki))
}

fn url_parse(uri: &str) -> std::collections::HashMap<String, String> {
    let q = uri.split_once('?').map(|x| x.1).unwrap_or("");
    q.split('&')
        .filter_map(|kv| kv.split_once('='))
        .map(|(k, v)| {
            (
                k.to_string(),
                percent_encoding::percent_decode_str(v)
                    .decode_utf8_lossy()
                    .into_owned(),
            )
        })
        .collect()
}

async fn call(
    me: &ServerIdentity,
    state: &std::path::Path,
    method: &str,
    path: &str,
    body: Option<Value>,
) -> anyhow::Result<(u16, Value)> {
    let target: Value = serde_json::from_str(&std::fs::read_to_string(state.join("target.json"))?)?;
    let host = target["host"]
        .as_str()
        .unwrap()
        .trim_matches(['[', ']'])
        .to_string();
    let host = if host == "10.0.2.2" {
        "127.0.0.1".to_string()
    } else {
        host
    };
    let port: u16 = target["port"]
        .as_str()
        .map(|p| p.parse().unwrap())
        .unwrap_or_else(|| target["port"].as_u64().unwrap() as u16);
    let cfg = switchtether_core::tls::client::client_config(me, target["fp"].as_str().unwrap())?;
    let tcp = tokio::net::TcpStream::connect((host.as_str(), port)).await?;
    let tls = tokio_rustls::TlsConnector::from(cfg)
        .connect(
            rustls::pki_types::ServerName::try_from("switchtether.local")?,
            tcp,
        )
        .await?;
    let (mut sender, conn) = hyper::client::conn::http1::handshake(TokioIo::new(tls)).await?;
    tokio::spawn(conn);
    let req = hyper::Request::builder()
        .method(method)
        .uri(path)
        .header("host", "switchtether.local")
        .header("content-type", "application/json")
        .body(Full::new(Bytes::from(
            body.map(|b| b.to_string()).unwrap_or_default(),
        )))?;
    let resp = sender.send_request(req).await?;
    let s = resp.status().as_u16();
    let bytes = resp.into_body().collect().await?.to_bytes();
    Ok((s, serde_json::from_slice(&bytes).unwrap_or(Value::Null)))
}
