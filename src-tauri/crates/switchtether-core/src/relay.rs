//! Desktop side of the optional SwitchTether relay (docs/RELAY.md).
//!
//! The desktop keeps one authenticated control connection to the relay. When a
//! phone asks for it, the desktop checks the relay access token, opens a data
//! connection and hands the byte stream to the normal TLS server — the phone's
//! mutual-TLS session runs end-to-end through the relay, unchanged.

use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use futures::{SinkExt, StreamExt};
use parking_lot::Mutex;
use serde::Serialize;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio_util::sync::CancellationToken;

use crate::identity::ServerIdentity;

pub const PROTOCOL: &str = "ST-RELAY/1";
const MAX_LINE: usize = 8 * 1024;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
/// The relay pings every 30 s; silence for this long means the link is dead.
const CONTROL_IDLE: Duration = Duration::from_secs(90);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RelayConfig {
    /// `tcp://host:port` or `ws(s)://host/v1/tunnel`
    pub url: String,
    /// Access token phones must present (from the pairing link).
    pub token: String,
    /// Relay key from the relay operator (base64url, 32 bytes): proves to the
    /// relay that this desktop may use it (docs/RELAY.md §2.0).
    pub key: String,
}

/// Decode and check a relay key (base64url without padding, 32 bytes).
pub fn decode_key(key: &str) -> anyhow::Result<Vec<u8>> {
    use base64::Engine as _;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(key.trim().trim_end_matches('='))
        .map_err(|_| anyhow::anyhow!("relay key is not valid base64url"))?;
    anyhow::ensure!(bytes.len() == 32, "relay key must be 32 bytes");
    Ok(bytes)
}

/// Relay-key MAC (docs/RELAY.md §2.0).
pub fn key_mac(key: &[u8], role: &str, nonce: &str, fp: &str) -> String {
    let k = ring::hmac::Key::new(ring::hmac::HMAC_SHA256, key);
    let tag = ring::hmac::sign(
        &k,
        format!("switchtether-relay-v1 {role}\n{nonce}\n{fp}").as_bytes(),
    );
    b64(tag.as_ref())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RelayUrl {
    Tcp { host: String, port: u16 },
    Ws(String),
}

pub fn parse_url(url: &str) -> anyhow::Result<RelayUrl> {
    let url = url.trim();
    if let Some(rest) = url.strip_prefix("tcp://") {
        let rest = rest.trim_end_matches('/');
        let (host, port) = match rest.rsplit_once(':') {
            // `[v6]` without a port
            Some(_) if rest.ends_with(']') => (rest, 47700),
            Some((h, p)) => (h, p.parse::<u16>()?),
            None => (rest, 47700),
        };
        anyhow::ensure!(!host.is_empty(), "relay host missing");
        let host = host
            .trim_start_matches('[')
            .trim_end_matches(']')
            .to_string();
        return Ok(RelayUrl::Tcp { host, port });
    }
    if url.starts_with("wss://") || url.starts_with("ws://") {
        let after = url.split_once("://").map(|(_, r)| r).unwrap_or("");
        let host = after.split(['/', '?']).next().unwrap_or("");
        anyhow::ensure!(!host.is_empty(), "relay host missing");
        return Ok(RelayUrl::Ws(url.to_string()));
    }
    anyhow::bail!("relay URL must start with tcp://, wss:// or ws://")
}

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RelayStatus {
    /// `off`, `connecting`, `connected`, `error`
    pub state: String,
    pub error: Option<String>,
    pub tunnels: u64,
}

pub trait ByteStream: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> ByteStream for T {}
pub type BoxStream = Pin<Box<dyn ByteStream>>;

/// Called with each accepted tunnel (raw TLS bytes from a phone).
pub type OnTunnel = Arc<dyn Fn(BoxStream, SocketAddr) + Send + Sync>;

async fn open(url: &RelayUrl) -> anyhow::Result<(BoxStream, SocketAddr)> {
    match url {
        RelayUrl::Tcp { host, port } => {
            let s = tokio::time::timeout(
                HANDSHAKE_TIMEOUT,
                tokio::net::TcpStream::connect((host.as_str(), *port)),
            )
            .await??;
            let _ = s.set_nodelay(true);
            let addr = s.peer_addr()?;
            Ok((Box::pin(s), addr))
        }
        RelayUrl::Ws(u) => {
            let (ws, _) = tokio::time::timeout(
                HANDSHAKE_TIMEOUT,
                tokio_tungstenite::connect_async(u.as_str()),
            )
            .await??;
            let addr = match ws.get_ref() {
                tokio_tungstenite::MaybeTlsStream::Plain(s) => s.peer_addr().ok(),
                tokio_tungstenite::MaybeTlsStream::Rustls(s) => s.get_ref().0.peer_addr().ok(),
                _ => None,
            }
            .unwrap_or_else(|| SocketAddr::from(([0, 0, 0, 0], 0)));
            Ok((Box::pin(ws_bridge(ws)), addr))
        }
    }
}

/// Expose a WebSocket (binary messages) as a byte stream.
fn ws_bridge<S>(ws: tokio_tungstenite::WebSocketStream<S>) -> tokio::io::DuplexStream
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    use tokio_tungstenite::tungstenite::Message;
    let (ours, theirs) = tokio::io::duplex(64 * 1024);
    let (mut sink, mut stream) = ws.split();
    let (mut rd, mut wr) = tokio::io::split(theirs);
    tokio::spawn(async move {
        let mut buf = vec![0u8; 16 * 1024];
        loop {
            match rd.read(&mut buf).await {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    if sink.send(Message::Binary(buf[..n].to_vec())).await.is_err() {
                        break;
                    }
                }
            }
        }
        let _ = sink.close().await;
    });
    tokio::spawn(async move {
        while let Some(Ok(msg)) = stream.next().await {
            match msg {
                Message::Binary(b) if wr.write_all(&b).await.is_err() => break,
                Message::Close(_) => break,
                _ => {}
            }
        }
        let _ = wr.shutdown().await;
    });
    ours
}

/// Read one `\n`-terminated line without reading past it (raw bytes follow).
async fn read_line<S: AsyncRead + Unpin + ?Sized>(s: &mut S) -> anyhow::Result<String> {
    let mut out = Vec::with_capacity(64);
    let mut b = [0u8; 1];
    loop {
        let n = s.read(&mut b).await?;
        anyhow::ensure!(n == 1, "relay closed the connection");
        if b[0] == b'\n' {
            break;
        }
        out.push(b[0]);
        anyhow::ensure!(out.len() <= MAX_LINE, "relay line too long");
    }
    if out.last() == Some(&b'\r') {
        out.pop();
    }
    Ok(String::from_utf8(out)?)
}

async fn write_line<S: AsyncWrite + Unpin + ?Sized>(s: &mut S, line: &str) -> anyhow::Result<()> {
    s.write_all(format!("{line}\n").as_bytes()).await?;
    s.flush().await?;
    Ok(())
}

fn expect_ok(line: &str) -> anyhow::Result<()> {
    if line == "OK" {
        return Ok(());
    }
    match line.strip_prefix("ERR ") {
        Some(e) => anyhow::bail!("relay refused: {e}"),
        None => anyhow::bail!("unexpected relay reply: {line}"),
    }
}

/// Message signed to prove ownership of `fp` (docs/RELAY.md §2.1).
pub fn auth_message(nonce: &str, fp: &str) -> Vec<u8> {
    format!("switchtether-relay-v1\n{nonce}\n{fp}").into_bytes()
}

fn b64(data: &[u8]) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(data)
}

async fn control_session(
    url: &RelayUrl,
    cfg: &RelayConfig,
    identity: &ServerIdentity,
    status: &Mutex<RelayStatus>,
    on_tunnel: &OnTunnel,
    cancel: &CancellationToken,
) -> anyhow::Result<()> {
    let (mut s, _) = open(url).await?;
    let fp = &identity.fingerprint;
    tokio::time::timeout(HANDSHAKE_TIMEOUT, async {
        write_line(&mut s, &format!("{PROTOCOL} LISTEN {fp}")).await?;
        let line = read_line(&mut s).await?;
        let nonce = line
            .strip_prefix("CHALLENGE ")
            .ok_or_else(|| anyhow::anyhow!("unexpected relay reply: {line}"))?;
        let sig = identity.sign(&auth_message(nonce, fp))?;
        write_line(
            &mut s,
            &format!(
                "AUTH {} {} {}",
                b64(&identity.cert_der),
                b64(&sig),
                key_mac(&decode_key(&cfg.key)?, "listen", nonce, fp)
            ),
        )
        .await?;
        expect_ok(&read_line(&mut s).await?)
    })
    .await??;
    {
        let mut st = status.lock();
        st.state = "connected".into();
        st.error = None;
    }
    log::info!("[relay] connected to {}", cfg.url);
    loop {
        let line = tokio::select! {
            _ = cancel.cancelled() => return Ok(()),
            l = tokio::time::timeout(CONTROL_IDLE, read_line(&mut s)) => l??,
        };
        let mut parts = line.split(' ');
        match parts.next() {
            Some("PING") => write_line(&mut s, "PONG").await?,
            Some("PONG") => {}
            Some("INCOMING") => {
                let (Some(conn), Some(token)) = (parts.next(), parts.next()) else {
                    continue;
                };
                if !crate::util::ct_eq(token, &cfg.token) {
                    log::info!("[relay] rejected a tunnel with a wrong access token");
                    write_line(&mut s, &format!("REJECT {conn}")).await?;
                    continue;
                }
                let (url, fp, conn) = (url.clone(), fp.clone(), conn.to_string());
                let on_tunnel = on_tunnel.clone();
                tokio::spawn(async move {
                    match accept(&url, &fp, &conn).await {
                        Ok((stream, addr)) => on_tunnel(stream, addr),
                        Err(e) => log::warn!("[relay] could not accept a tunnel: {e}"),
                    }
                });
                status.lock().tunnels += 1;
            }
            _ => log::debug!("[relay] ignored control line"),
        }
    }
}

async fn accept(url: &RelayUrl, fp: &str, conn: &str) -> anyhow::Result<(BoxStream, SocketAddr)> {
    let (mut s, addr) = open(url).await?;
    tokio::time::timeout(HANDSHAKE_TIMEOUT, async {
        write_line(&mut s, &format!("{PROTOCOL} ACCEPT {fp} {conn}")).await?;
        expect_ok(&read_line(&mut s).await?)
    })
    .await??;
    Ok((s, addr))
}

/// Keep a control connection to the relay until `cancel`, reconnecting with
/// backoff. Status is kept in `status` for the settings page.
pub fn spawn(
    cfg: RelayConfig,
    identity: ServerIdentity,
    status: Arc<Mutex<RelayStatus>>,
    on_tunnel: OnTunnel,
    cancel: CancellationToken,
) {
    tokio::spawn(async move {
        let url = match parse_url(&cfg.url) {
            Ok(u) => u,
            Err(e) => {
                *status.lock() = RelayStatus {
                    state: "error".into(),
                    error: Some(e.to_string()),
                    tunnels: 0,
                };
                return;
            }
        };
        let mut backoff = Duration::from_secs(1);
        loop {
            status.lock().state = "connecting".into();
            let res = control_session(&url, &cfg, &identity, &status, &on_tunnel, &cancel).await;
            if cancel.is_cancelled() {
                break;
            }
            let connected_before = status.lock().state == "connected";
            if let Err(e) = res {
                log::warn!("[relay] {e}");
                let mut st = status.lock();
                st.state = "error".into();
                st.error = Some(e.to_string());
            }
            if connected_before {
                backoff = Duration::from_secs(1);
            }
            tokio::select! {
                _ = cancel.cancelled() => break,
                _ = tokio::time::sleep(backoff) => {}
            }
            backoff = (backoff * 2).min(Duration::from_secs(60));
        }
        status.lock().state = "off".into();
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn urls() {
        assert_eq!(
            parse_url("tcp://relay.example.com:4000").unwrap(),
            RelayUrl::Tcp {
                host: "relay.example.com".into(),
                port: 4000
            }
        );
        assert_eq!(
            parse_url("tcp://relay.example.com").unwrap(),
            RelayUrl::Tcp {
                host: "relay.example.com".into(),
                port: 47700
            }
        );
        assert_eq!(
            parse_url("tcp://[::1]:9").unwrap(),
            RelayUrl::Tcp {
                host: "::1".into(),
                port: 9
            }
        );
        assert!(matches!(
            parse_url("wss://r.example/v1/tunnel").unwrap(),
            RelayUrl::Ws(_)
        ));
        assert!(parse_url("http://x").is_err());
        assert!(parse_url("tcp://:80").is_err());
    }

    #[test]
    fn relay_keys_and_macs() {
        assert!(decode_key("short").is_err());
        let key = b64(&[7u8; 32]);
        let raw = decode_key(&key).unwrap();
        assert_eq!(raw.len(), 32);
        let a = key_mac(&raw, "connect", "bm9uY2U", "ab");
        assert_eq!(a, key_mac(&raw, "connect", "bm9uY2U", "ab"));
        assert_ne!(a, key_mac(&raw, "listen", "bm9uY2U", "ab"));
        assert_ne!(a, key_mac(&raw, "connect", "other", "ab"));
    }

    /// Same MACs as the Go relay (relay/internal/relay/testdata/relay_key_vector.json).
    #[test]
    fn relay_key_mac_matches_the_go_relay_vector() {
        let v: serde_json::Value = serde_json::from_str(include_str!(
            "../../../../relay/internal/relay/testdata/relay_key_vector.json"
        ))
        .unwrap();
        let key = decode_key(v["key"].as_str().unwrap()).unwrap();
        for c in v["cases"].as_array().unwrap() {
            assert_eq!(
                key_mac(
                    &key,
                    c["role"].as_str().unwrap(),
                    v["nonce"].as_str().unwrap(),
                    v["fp"].as_str().unwrap()
                ),
                c["mac"].as_str().unwrap()
            );
        }
    }

    #[test]
    fn signature_verifies_with_the_certificate_key() {
        let id = ServerIdentity::generate("t").unwrap();
        let msg = auth_message("bm9uY2U", &id.fingerprint);
        let sig = id.sign(&msg).unwrap();
        // Verify with the public key from the certificate (what the relay does).
        let (_, cert) = x509_parser_lite(&id.cert_der);
        let pk =
            ring::signature::UnparsedPublicKey::new(&ring::signature::ECDSA_P256_SHA256_ASN1, cert);
        pk.verify(&msg, &sig).unwrap();
    }

    /// Extract the uncompressed P-256 point from a certificate (test helper):
    /// the SubjectPublicKeyInfo bit string is the only `04 || X || Y` run of 65 bytes
    /// preceded by `03 42 00`.
    fn x509_parser_lite(der: &[u8]) -> ((), Vec<u8>) {
        let pos = der
            .windows(4)
            .position(|w| w == [0x03, 0x42, 0x00, 0x04])
            .expect("p-256 key");
        ((), der[pos + 3..pos + 3 + 65].to_vec())
    }
}
