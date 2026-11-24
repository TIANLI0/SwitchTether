//! Push wake (docs/PROTOCOL.md §7.12): a content-free Web Push message that makes a phone
//! reconnect while it has no live event stream.
//!
//! The phone registers a UnifiedPush endpoint (an RFC 8030 push resource of the user's own
//! distributor, e.g. ntfy) together with RFC 8291 receiver keys. When a session starts waiting for
//! the user, finishes a turn or fails, the desktop POSTs an encrypted message whose plaintext is
//! only `{"v":1}` — no session id, title or text. The push server therefore learns when something
//! happened, never what. The phone treats any message as "connect now" and fetches the real events
//! over its pinned mTLS connection.
//!
//! Sending is coalesced per device (at most one message per [`MIN_INTERVAL`], plus one trailing
//! message for changes in between), skipped while the device has a live event WebSocket, backed
//! off after failures, and a `404` / `410` from the push server drops the subscription.

use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine as _;
use parking_lot::Mutex;
use ring::rand::{SecureRandom, SystemRandom};
use ring::signature::{EcdsaKeyPair, KeyPair, ECDSA_P256_SHA256_FIXED_SIGNING};
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

use crate::events::EventBus;
use crate::registry::{PushSubscription, Registry};

/// Longest endpoint accepted (UnifiedPush AND_3: at most 1000 bytes).
pub const MAX_ENDPOINT_LEN: usize = 1000;
/// At most one push per device within this interval; changes in between yield one trailing push.
pub const MIN_INTERVAL: Duration = Duration::from_secs(10);
/// Longest back-off after repeated failures.
pub const MAX_BACKOFF: Duration = Duration::from_secs(15 * 60);
/// The whole plaintext of every push message.
pub const WAKE_PLAINTEXT: &[u8] = br#"{"v":1}"#;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);
/// Push servers keep an undelivered wake this long (seconds); a later one supersedes it anyway.
const TTL_SECONDS: u32 = 600;
const VAPID_META_KEY: &str = "push_vapid_pkcs8";
const VAPID_SUBJECT: &str = "https://github.com/TIANLI0/SwitchTether";

// ---------------------------------------------------------------------------------------------
// Subscription validation
// ---------------------------------------------------------------------------------------------

fn b64(s: &str) -> Option<Vec<u8>> {
    URL_SAFE_NO_PAD.decode(s.trim().trim_end_matches('=')).ok()
}

/// Validate what a phone sends with `PUT /v1/push`.
pub fn validate_subscription(
    endpoint: &str,
    p256dh: &str,
    auth: &str,
) -> Result<PushSubscription, &'static str> {
    let endpoint = endpoint.trim();
    if endpoint.is_empty() || endpoint.len() > MAX_ENDPOINT_LEN {
        return Err("endpoint must be 1..1000 bytes");
    }
    let uri: http::Uri = endpoint.parse().map_err(|_| "endpoint is not a URL")?;
    if !matches!(uri.scheme_str(), Some("http") | Some("https")) {
        return Err("endpoint must be an http(s) URL");
    }
    let Some(authority) = uri.authority() else {
        return Err("endpoint has no host");
    };
    if authority.as_str().contains('@') || uri.host().is_none_or(str::is_empty) {
        return Err("endpoint must not contain credentials");
    }
    match b64(p256dh) {
        Some(k) if k.len() == 65 && k[0] == 0x04 => {}
        _ => return Err("p256dh must be an uncompressed P-256 public key (base64url)"),
    }
    match b64(auth) {
        Some(a) if a.len() == 16 => {}
        _ => return Err("auth must be 16 bytes (base64url)"),
    }
    Ok(PushSubscription {
        endpoint: endpoint.to_string(),
        p256dh: p256dh.trim().trim_end_matches('=').to_string(),
        auth: auth.trim().trim_end_matches('=').to_string(),
    })
}

// ---------------------------------------------------------------------------------------------
// RFC 8291 message encryption (aes128gcm, one record)
// ---------------------------------------------------------------------------------------------

struct Len(usize);
impl ring::hkdf::KeyType for Len {
    fn len(&self) -> usize {
        self.0
    }
}

fn hkdf(
    salt: &[u8],
    ikm: &[u8],
    info: &[&[u8]],
    len: usize,
) -> Result<Vec<u8>, ring::error::Unspecified> {
    let prk = ring::hkdf::Salt::new(ring::hkdf::HKDF_SHA256, salt).extract(ikm);
    let okm = prk.expand(info, Len(len))?;
    let mut out = vec![0u8; len];
    okm.fill(&mut out)?;
    Ok(out)
}

/// Content key and nonce of RFC 8291 §3.3 / §3.4 from the ECDH secret.
fn derive(
    ecdh_secret: &[u8],
    auth: &[u8],
    ua_public: &[u8],
    as_public: &[u8],
    salt: &[u8],
) -> Result<(Vec<u8>, Vec<u8>), ring::error::Unspecified> {
    let ikm = hkdf(
        auth,
        ecdh_secret,
        &[b"WebPush: info\0", ua_public, as_public],
        32,
    )?;
    let cek = hkdf(salt, &ikm, &[b"Content-Encoding: aes128gcm\0"], 16)?;
    let nonce = hkdf(salt, &ikm, &[b"Content-Encoding: nonce\0"], 12)?;
    Ok((cek, nonce))
}

/// Encrypt `plaintext` for a subscription (RFC 8291, single aes128gcm record of RFC 8188).
pub fn encrypt(
    ua_public: &[u8],
    auth: &[u8],
    plaintext: &[u8],
) -> Result<Vec<u8>, ring::error::Unspecified> {
    use ring::agreement::{agree_ephemeral, EphemeralPrivateKey, UnparsedPublicKey, ECDH_P256};
    let rng = SystemRandom::new();
    let as_private = EphemeralPrivateKey::generate(&ECDH_P256, &rng)?;
    let as_public = as_private.compute_public_key()?.as_ref().to_vec();
    let mut salt = [0u8; 16];
    rng.fill(&mut salt)?;
    let ecdh = agree_ephemeral(
        as_private,
        &UnparsedPublicKey::new(&ECDH_P256, ua_public),
        |s| s.to_vec(),
    )?;
    let (cek, nonce) = derive(&ecdh, auth, ua_public, &as_public, &salt)?;
    let key =
        ring::aead::LessSafeKey::new(ring::aead::UnboundKey::new(&ring::aead::AES_128_GCM, &cek)?);
    let mut record = plaintext.to_vec();
    record.push(0x02); // last-record delimiter, no padding
    key.seal_in_place_append_tag(
        ring::aead::Nonce::try_assume_unique_for_key(&nonce)?,
        ring::aead::Aad::empty(),
        &mut record,
    )?;
    let mut out = Vec::with_capacity(16 + 4 + 1 + as_public.len() + record.len());
    out.extend_from_slice(&salt);
    out.extend_from_slice(&4096u32.to_be_bytes());
    out.push(as_public.len() as u8);
    out.extend_from_slice(&as_public);
    out.extend_from_slice(&record);
    Ok(out)
}

// ---------------------------------------------------------------------------------------------
// VAPID (RFC 8292)
// ---------------------------------------------------------------------------------------------

/// The desktop's VAPID key pair, created once and kept in `remote.db` (`meta`). It only
/// authenticates push requests to push servers that ask for it; it protects no data.
pub struct Vapid {
    key: EcdsaKeyPair,
    public_b64: String,
}

impl Vapid {
    pub fn load_or_create(registry: &Registry) -> anyhow::Result<Self> {
        let rng = SystemRandom::new();
        let stored = registry
            .meta(VAPID_META_KEY)
            .and_then(|s| URL_SAFE_NO_PAD.decode(s).ok())
            .and_then(|der| {
                EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, &der, &rng).ok()
            });
        let key = match stored {
            Some(k) => k,
            None => {
                let der = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, &rng)
                    .map_err(|_| anyhow::anyhow!("cannot create VAPID key"))?;
                registry.set_meta(VAPID_META_KEY, &URL_SAFE_NO_PAD.encode(der.as_ref()))?;
                EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, der.as_ref(), &rng)
                    .map_err(|_| anyhow::anyhow!("cannot load VAPID key"))?
            }
        };
        let public_b64 = URL_SAFE_NO_PAD.encode(key.public_key().as_ref());
        Ok(Self { key, public_b64 })
    }

    /// Uncompressed P-256 public key, base64url (the 87-character `vapid` of UnifiedPush).
    pub fn public_key(&self) -> &str {
        &self.public_b64
    }

    /// `Authorization` header value for a push to `endpoint`.
    pub fn authorization(&self, endpoint: &str, now_s: i64) -> Option<String> {
        let uri: http::Uri = endpoint.parse().ok()?;
        let aud = format!("{}://{}", uri.scheme_str()?, uri.authority()?.as_str());
        let header = URL_SAFE_NO_PAD.encode(br#"{"typ":"JWT","alg":"ES256"}"#);
        let claims =
            serde_json::json!({ "aud": aud, "exp": now_s + 12 * 3600, "sub": VAPID_SUBJECT });
        let claims = URL_SAFE_NO_PAD.encode(claims.to_string());
        let signing_input = format!("{header}.{claims}");
        let sig = self
            .key
            .sign(&SystemRandom::new(), signing_input.as_bytes())
            .ok()?;
        Some(format!(
            "vapid t={signing_input}.{}, k={}",
            URL_SAFE_NO_PAD.encode(sig.as_ref()),
            self.public_b64
        ))
    }
}

// ---------------------------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------------------------

/// One push request (already encrypted).
#[derive(Debug, Clone)]
pub struct PushRequest {
    pub endpoint: String,
    pub headers: Vec<(&'static str, String)>,
    pub body: Vec<u8>,
}

#[async_trait::async_trait]
pub trait PushSender: Send + Sync {
    /// POST the request; returns the HTTP status, or an error for transport failures.
    async fn post(&self, req: PushRequest) -> Result<u16, String>;
}

/// Minimal HTTP/1.1 client (hyper + rustls with the Mozilla root store).
pub struct HttpSender {
    tls: Arc<rustls::ClientConfig>,
}

impl Default for HttpSender {
    fn default() -> Self {
        let roots =
            rustls::RootCertStore::from_iter(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let tls = rustls::ClientConfig::builder_with_provider(crate::tls::provider())
            .with_safe_default_protocol_versions()
            .expect("ring supports the default TLS versions")
            .with_root_certificates(roots)
            .with_no_client_auth();
        Self { tls: Arc::new(tls) }
    }
}

#[async_trait::async_trait]
impl PushSender for HttpSender {
    async fn post(&self, req: PushRequest) -> Result<u16, String> {
        use http_body_util::Full;
        use hyper_util::rt::TokioIo;

        let uri: http::Uri = req
            .endpoint
            .parse()
            .map_err(|_| "invalid endpoint".to_string())?;
        let https = uri.scheme_str() == Some("https");
        let authority = uri.authority().ok_or("no host")?.clone();
        let host = authority
            .host()
            .trim_start_matches('[')
            .trim_end_matches(']')
            .to_string();
        let port = authority.port_u16().unwrap_or(if https { 443 } else { 80 });
        let path = uri
            .path_and_query()
            .map(|p| p.as_str())
            .unwrap_or("/")
            .to_string();

        let tcp = tokio::time::timeout(
            CONNECT_TIMEOUT,
            tokio::net::TcpStream::connect((host.as_str(), port)),
        )
        .await
        .map_err(|_| "connect timed out".to_string())?
        .map_err(|e| format!("connect: {e}"))?;
        let mut builder = http::Request::builder()
            .method(http::Method::POST)
            .uri(path)
            .header(http::header::HOST, authority.as_str())
            .header(http::header::CONTENT_LENGTH, req.body.len());
        for (k, v) in &req.headers {
            builder = builder.header(*k, v);
        }
        let request = builder
            .body(Full::new(axum::body::Bytes::from(req.body)))
            .map_err(|e| e.to_string())?;

        let send = async move {
            if https {
                let name = rustls::pki_types::ServerName::try_from(host)
                    .map_err(|_| "invalid host name".to_string())?;
                let tls = tokio_rustls::TlsConnector::from(self.tls.clone())
                    .connect(name, tcp)
                    .await
                    .map_err(|e| format!("tls: {e}"))?;
                let (mut sender, conn) = hyper::client::conn::http1::handshake(TokioIo::new(tls))
                    .await
                    .map_err(|e| e.to_string())?;
                tokio::spawn(conn);
                sender
                    .send_request(request)
                    .await
                    .map_err(|e| e.to_string())
            } else {
                let (mut sender, conn) = hyper::client::conn::http1::handshake(TokioIo::new(tcp))
                    .await
                    .map_err(|e| e.to_string())?;
                tokio::spawn(conn);
                sender
                    .send_request(request)
                    .await
                    .map_err(|e| e.to_string())
            }
        };
        let resp = tokio::time::timeout(REQUEST_TIMEOUT, send)
            .await
            .map_err(|_| "request timed out".to_string())??;
        Ok(resp.status().as_u16())
    }
}

// ---------------------------------------------------------------------------------------------
// When to wake
// ---------------------------------------------------------------------------------------------

/// Whether a session status change deserves a wake: it starts waiting for the user, a turn
/// finished (busy → idle / completed), or it failed.
pub fn is_attention(prev: Option<&str>, next: &str) -> bool {
    match next {
        "waiting" => prev != Some("waiting"),
        "error" => prev.is_some_and(|p| p != "error"),
        "idle" | "completed" => prev == Some("busy"),
        _ => false,
    }
}

fn backoff(failures: u32) -> Duration {
    let secs = MIN_INTERVAL
        .as_secs()
        .saturating_mul(1u64 << failures.saturating_sub(1).min(16));
    Duration::from_secs(secs).min(MAX_BACKOFF)
}

#[derive(Default)]
struct Slot {
    last_sent: Option<Instant>,
    trailing: bool,
    failures: u32,
    blocked_until: Option<Instant>,
}

/// Per-device push state; owned by [`crate::api::ApiContext`].
pub struct PushWaker {
    registry: Arc<Registry>,
    vapid: Option<Vapid>,
    sender: Arc<dyn PushSender>,
    live: Mutex<HashMap<String, usize>>,
    slots: Mutex<HashMap<String, Slot>>,
    statuses: Mutex<HashMap<String, String>>,
    subscribers: AtomicUsize,
}

/// Marks a device as having a live event stream while alive.
pub struct LiveGuard {
    waker: Arc<PushWaker>,
    device_id: String,
}

impl Drop for LiveGuard {
    fn drop(&mut self) {
        let mut live = self.waker.live.lock();
        if let Some(n) = live.get_mut(&self.device_id) {
            *n = n.saturating_sub(1);
            if *n == 0 {
                live.remove(&self.device_id);
            }
        }
    }
}

impl PushWaker {
    pub fn new(registry: Arc<Registry>, sender: Arc<dyn PushSender>) -> Arc<Self> {
        let vapid = match Vapid::load_or_create(&registry) {
            Ok(v) => Some(v),
            Err(e) => {
                log::warn!("[push] VAPID key unavailable: {e}");
                None
            }
        };
        let w = Arc::new(Self {
            registry,
            vapid,
            sender,
            live: Mutex::new(HashMap::new()),
            slots: Mutex::new(HashMap::new()),
            statuses: Mutex::new(HashMap::new()),
            subscribers: AtomicUsize::new(0),
        });
        w.refresh_subscribers();
        w
    }

    pub fn vapid_public_key(&self) -> Option<&str> {
        self.vapid.as_ref().map(Vapid::public_key)
    }

    /// Re-count stored subscriptions (after register / remove / revoke).
    pub fn refresh_subscribers(&self) {
        self.subscribers
            .store(self.registry.push_subscriptions().len(), Ordering::Relaxed);
    }

    /// Whether any active device asked for push wakes (keeps the live-session watcher running).
    pub fn has_subscribers(&self) -> bool {
        self.subscribers.load(Ordering::Relaxed) > 0
    }

    /// Called by the event WebSocket for the lifetime of a connection.
    pub fn connected(self: &Arc<Self>, device_id: &str) -> LiveGuard {
        *self.live.lock().entry(device_id.to_string()).or_insert(0) += 1;
        LiveGuard {
            waker: self.clone(),
            device_id: device_id.to_string(),
        }
    }

    fn is_live(&self, device_id: &str) -> bool {
        self.live.lock().get(device_id).copied().unwrap_or(0) > 0
    }

    /// Track a `session.upserted` / `session.removed` frame; true when it deserves a wake.
    pub fn observe(&self, topic: &str, data: &serde_json::Value) -> bool {
        let Some(id) = data.get("id").and_then(|v| v.as_str()) else {
            return false;
        };
        let mut st = self.statuses.lock();
        if topic == "session.removed" {
            st.remove(id);
            return false;
        }
        if topic != "session.upserted" {
            return false;
        }
        let Some(next) = data.get("status").and_then(|v| v.as_str()) else {
            return false;
        };
        if st.len() > 5_000 && !st.contains_key(id) {
            st.clear();
        }
        let prev = st.insert(id.to_string(), next.to_string());
        is_attention(prev.as_deref(), next)
    }

    /// Ask for a wake of `device_id` (coalesced; nothing while it is connected).
    pub fn wake(self: &Arc<Self>, device_id: &str) {
        if self.is_live(device_id) {
            return;
        }
        let now = Instant::now();
        let mut slots = self.slots.lock();
        let slot = slots.entry(device_id.to_string()).or_default();
        if slot.trailing {
            return;
        }
        let mut earliest = slot.last_sent.map(|t| t + MIN_INTERVAL).unwrap_or(now);
        if let Some(b) = slot.blocked_until {
            earliest = earliest.max(b);
        }
        let this = self.clone();
        let device = device_id.to_string();
        if earliest <= now {
            slot.last_sent = Some(now);
            drop(slots);
            tokio::spawn(async move { this.send(&device).await });
        } else {
            slot.trailing = true;
            drop(slots);
            tokio::spawn(async move {
                tokio::time::sleep_until(earliest).await;
                {
                    let mut slots = this.slots.lock();
                    let slot = slots.entry(device.clone()).or_default();
                    slot.trailing = false;
                    if this.is_live(&device) {
                        return;
                    }
                    slot.last_sent = Some(Instant::now());
                }
                this.send(&device).await;
            });
        }
    }

    /// One wake now, bypassing coalescing and the connected check (settings "test").
    pub async fn send_test(&self, device_id: &str) -> Result<u16, String> {
        let sub = self
            .registry
            .push_subscription(device_id)
            .ok_or("no push subscription for this device")?;
        let req = self
            .request(&sub)
            .ok_or("cannot encrypt for this subscription")?;
        self.sender.post(req).await
    }

    async fn send(self: &Arc<Self>, device_id: &str) {
        // One indexed row of a local database: cheap enough to read inline.
        let Some(sub) = self.registry.push_subscription(device_id) else {
            return;
        };
        let Some(req) = self.request(&sub) else {
            log::warn!("[push] cannot build a push message for {device_id}");
            return;
        };
        let outcome = self.sender.post(req).await;
        let mut drop_sub = false;
        {
            let mut slots = self.slots.lock();
            let slot = slots.entry(device_id.to_string()).or_default();
            match outcome {
                Ok(s) if (200..300).contains(&s) => {
                    slot.failures = 0;
                    slot.blocked_until = None;
                }
                Ok(404) | Ok(410) => drop_sub = true,
                other => {
                    slot.failures = slot.failures.saturating_add(1);
                    let wait = backoff(slot.failures);
                    slot.blocked_until = Some(Instant::now() + wait);
                    log::info!(
                        "[push] wake for {device_id} failed ({other:?}); next attempt not before {wait:?}"
                    );
                }
            }
        }
        if drop_sub {
            let _ = self.registry.remove_push_subscription(device_id);
            self.registry.audit(
                Some(device_id),
                "push.expired",
                None,
                "ok",
                Some("push server: subscription gone"),
            );
            self.slots.lock().remove(device_id);
            self.refresh_subscribers();
        }
    }

    fn request(&self, sub: &PushSubscription) -> Option<PushRequest> {
        let body = encrypt(&b64(&sub.p256dh)?, &b64(&sub.auth)?, WAKE_PLAINTEXT).ok()?;
        let mut headers = vec![
            ("content-encoding", "aes128gcm".to_string()),
            ("content-type", "application/octet-stream".to_string()),
            ("ttl", TTL_SECONDS.to_string()),
            ("urgency", "high".to_string()),
        ];
        if let Some(auth) = self
            .vapid
            .as_ref()
            .and_then(|v| v.authorization(&sub.endpoint, crate::util::now_ms() / 1000))
        {
            headers.push(("authorization", auth));
        }
        Some(PushRequest {
            endpoint: sub.endpoint.clone(),
            headers,
            body,
        })
    }

    /// Watch the event bus until `cancel`: attention-worthy session changes wake every subscribed
    /// device that is not connected.
    pub fn start(self: &Arc<Self>, events: Arc<EventBus>, cancel: CancellationToken) {
        let this = self.clone();
        let mut rx = events.subscribe();
        tokio::spawn(async move {
            loop {
                let frame = tokio::select! {
                    _ = cancel.cancelled() => return,
                    f = rx.recv() => f,
                };
                let frame = match frame {
                    Ok(f) => f,
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => return,
                };
                if !this.has_subscribers() || !this.observe(&frame.topic, &frame.data) {
                    continue;
                }
                let reg = this.registry.clone();
                let subs = tokio::task::spawn_blocking(move || reg.push_subscriptions())
                    .await
                    .unwrap_or_default();
                for (device, _) in subs {
                    if frame.device_id.as_deref().is_none_or(|d| d == device) {
                        this.wake(&device);
                    }
                }
            }
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicU16;

    #[derive(Default)]
    struct FakeSender {
        sent: Mutex<Vec<(Instant, PushRequest)>>,
        status: AtomicU16,
    }

    #[async_trait::async_trait]
    impl PushSender for FakeSender {
        async fn post(&self, req: PushRequest) -> Result<u16, String> {
            self.sent.lock().push((Instant::now(), req));
            match self.status.load(Ordering::Relaxed) {
                0 => Err("down".into()),
                s => Ok(s),
            }
        }
    }

    fn receiver() -> (ring::agreement::EphemeralPrivateKey, Vec<u8>, [u8; 16]) {
        let rng = SystemRandom::new();
        let k = ring::agreement::EphemeralPrivateKey::generate(&ring::agreement::ECDH_P256, &rng)
            .unwrap();
        let p = k.compute_public_key().unwrap().as_ref().to_vec();
        let mut auth = [0u8; 16];
        rng.fill(&mut auth).unwrap();
        (k, p, auth)
    }

    fn decrypt(
        key: ring::agreement::EphemeralPrivateKey,
        ua_public: &[u8],
        auth: &[u8],
        msg: &[u8],
    ) -> Vec<u8> {
        let salt = &msg[..16];
        assert_eq!(u32::from_be_bytes(msg[16..20].try_into().unwrap()), 4096);
        let idlen = msg[20] as usize;
        let as_public = &msg[21..21 + idlen];
        let ecdh = ring::agreement::agree_ephemeral(
            key,
            &ring::agreement::UnparsedPublicKey::new(&ring::agreement::ECDH_P256, as_public),
            |s| s.to_vec(),
        )
        .unwrap();
        let (cek, nonce) = derive(&ecdh, auth, ua_public, as_public, salt).unwrap();
        let key = ring::aead::LessSafeKey::new(
            ring::aead::UnboundKey::new(&ring::aead::AES_128_GCM, &cek).unwrap(),
        );
        let mut ct = msg[21 + idlen..].to_vec();
        let pt = key
            .open_in_place(
                ring::aead::Nonce::try_assume_unique_for_key(&nonce).unwrap(),
                ring::aead::Aad::empty(),
                &mut ct,
            )
            .unwrap()
            .to_vec();
        assert_eq!(pt.last(), Some(&0x02));
        pt[..pt.len() - 1].to_vec()
    }

    fn sub_for(p: &[u8], auth: &[u8]) -> PushSubscription {
        validate_subscription(
            "https://push.example/up/AbCdEf0123456789",
            &URL_SAFE_NO_PAD.encode(p),
            &URL_SAFE_NO_PAD.encode(auth),
        )
        .unwrap()
    }

    #[test]
    fn encryption_round_trips_and_carries_no_content() {
        let (k, p, auth) = receiver();
        let msg = encrypt(&p, &auth, WAKE_PLAINTEXT).unwrap();
        assert_eq!(decrypt(k, &p, &auth, &msg), WAKE_PLAINTEXT);
        assert!(msg.len() <= 4096);
    }

    #[test]
    fn subscriptions_are_validated() {
        let (_, p, auth) = receiver();
        let (pk, au) = (URL_SAFE_NO_PAD.encode(&p), URL_SAFE_NO_PAD.encode(auth));
        assert!(validate_subscription("https://ntfy.sh/upAbc?up=1", &pk, &au).is_ok());
        assert!(validate_subscription("http://192.168.1.5:8080/upAbc", &pk, &au).is_ok());
        for bad in [
            "",
            "ftp://x/y",
            "file:///etc/passwd",
            "https://user:pw@ntfy.sh/x",
            "not a url",
            "/relative",
        ] {
            assert!(validate_subscription(bad, &pk, &au).is_err(), "{bad}");
        }
        let long = format!("https://x/{}", "a".repeat(MAX_ENDPOINT_LEN));
        assert!(validate_subscription(&long, &pk, &au).is_err());
        assert!(validate_subscription("https://x/y", "AAAA", &au).is_err());
        assert!(validate_subscription("https://x/y", &pk, "AAAA").is_err());
    }

    #[test]
    fn vapid_header_is_a_valid_es256_jwt() {
        let reg = Registry::open_in_memory().unwrap();
        let v = Vapid::load_or_create(&reg).unwrap();
        assert_eq!(v.public_key().len(), 87);
        // The same key is loaded again.
        assert_eq!(
            Vapid::load_or_create(&reg).unwrap().public_key(),
            v.public_key()
        );
        let h = v
            .authorization("https://push.example:8443/x/y", 1_700_000_000)
            .unwrap();
        let t = h
            .strip_prefix("vapid t=")
            .unwrap()
            .split(", k=")
            .next()
            .unwrap();
        let parts: Vec<&str> = t.split('.').collect();
        assert_eq!(parts.len(), 3);
        let claims: serde_json::Value =
            serde_json::from_slice(&URL_SAFE_NO_PAD.decode(parts[1]).unwrap()).unwrap();
        assert_eq!(claims["aud"], "https://push.example:8443");
        assert_eq!(claims["exp"], 1_700_000_000 + 12 * 3600);
        let pk = URL_SAFE_NO_PAD.decode(v.public_key()).unwrap();
        ring::signature::UnparsedPublicKey::new(&ring::signature::ECDSA_P256_SHA256_FIXED, pk)
            .verify(
                format!("{}.{}", parts[0], parts[1]).as_bytes(),
                &URL_SAFE_NO_PAD.decode(parts[2]).unwrap(),
            )
            .unwrap();
    }

    fn fixture(name: &str) -> serde_json::Value {
        let root =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../protocol/fixtures");
        serde_json::from_str(&std::fs::read_to_string(root.join(name)).unwrap()).unwrap()
    }

    #[test]
    fn fixture_subscription_is_accepted() {
        let f = fixture("push_wake.json");
        let r = &f["register"];
        let sub = validate_subscription(
            r["endpoint"].as_str().unwrap(),
            r["p256dh"].as_str().unwrap(),
            r["auth"].as_str().unwrap(),
        )
        .unwrap();
        // The same receiver keys encrypt (fresh salt / key each time) to a decodable message.
        let msg = encrypt(
            &b64(&sub.p256dh).unwrap(),
            &b64(&sub.auth).unwrap(),
            WAKE_PLAINTEXT,
        )
        .unwrap();
        assert_eq!(&msg[16..20], &4096u32.to_be_bytes());
        assert_eq!(f["plaintext"].as_str().unwrap().as_bytes(), WAKE_PLAINTEXT);
        assert_eq!(f["info"]["vapidPublicKey"].as_str().unwrap().len(), 87);
    }

    #[test]
    fn attention_transitions() {
        assert!(is_attention(None, "waiting"));
        assert!(is_attention(Some("busy"), "waiting"));
        assert!(!is_attention(Some("waiting"), "waiting"));
        assert!(is_attention(Some("busy"), "idle"));
        assert!(is_attention(Some("busy"), "completed"));
        assert!(!is_attention(Some("idle"), "idle"));
        assert!(!is_attention(None, "idle"));
        assert!(is_attention(Some("busy"), "error"));
        assert!(!is_attention(None, "error"));
        assert!(!is_attention(Some("idle"), "busy"));
        assert_eq!(backoff(1), Duration::from_secs(10));
        assert_eq!(backoff(2), Duration::from_secs(20));
        assert_eq!(backoff(30), MAX_BACKOFF);
    }

    fn setup(
        status: u16,
    ) -> (
        Arc<PushWaker>,
        Arc<FakeSender>,
        String,
        Vec<u8>,
        [u8; 16],
        ring::agreement::EphemeralPrivateKey,
    ) {
        let reg = Registry::open_in_memory().unwrap();
        let d = reg
            .register_device(crate::registry::DeviceRecord {
                id: crate::util::new_id(),
                name: "Phone".into(),
                platform: "android".into(),
                os_version: None,
                app_version: None,
                cert_fingerprint: crate::util::new_id(),
                step_up_key: None,
                step_up_enabled: false,
                created_at_ms: 0,
                last_seen_at_ms: None,
                last_ip: None,
                revoked_at_ms: None,
            })
            .unwrap();
        let (k, p, auth) = receiver();
        reg.set_push_subscription(&d.id, &sub_for(&p, &auth))
            .unwrap();
        let sender = Arc::new(FakeSender::default());
        sender.status.store(status, Ordering::Relaxed);
        let w = PushWaker::new(reg, sender.clone());
        (w, sender, d.id, p, auth, k)
    }

    async fn settle() {
        for _ in 0..20 {
            tokio::task::yield_now().await;
        }
    }

    #[tokio::test(start_paused = true)]
    async fn wakes_are_coalesced_and_skipped_while_connected() {
        let (w, sender, dev, p, auth, k) = setup(201);
        assert!(w.has_subscribers());
        w.wake(&dev);
        settle().await;
        assert_eq!(sender.sent.lock().len(), 1);
        // Message is encrypted for the phone and has the expected headers.
        {
            let sent = sender.sent.lock();
            let req = &sent[0].1;
            assert_eq!(decrypt(k, &p, &auth, &req.body), WAKE_PLAINTEXT);
            assert!(req
                .headers
                .iter()
                .any(|(h, v)| *h == "content-encoding" && v == "aes128gcm"));
            assert!(req
                .headers
                .iter()
                .any(|(h, v)| *h == "authorization" && v.starts_with("vapid t=")));
        }
        // A burst within the interval yields exactly one trailing push.
        for _ in 0..5 {
            w.wake(&dev);
        }
        settle().await;
        assert_eq!(sender.sent.lock().len(), 1);
        tokio::time::sleep(MIN_INTERVAL).await;
        settle().await;
        assert_eq!(sender.sent.lock().len(), 2);
        let gap = {
            let s = sender.sent.lock();
            s[1].0 - s[0].0
        };
        assert!(gap >= MIN_INTERVAL);
        // Connected: nothing is sent, not even after the interval.
        let guard = w.connected(&dev);
        tokio::time::sleep(MIN_INTERVAL * 2).await;
        w.wake(&dev);
        settle().await;
        assert_eq!(sender.sent.lock().len(), 2);
        drop(guard);
        w.wake(&dev);
        settle().await;
        assert_eq!(sender.sent.lock().len(), 3);
    }

    #[tokio::test(start_paused = true)]
    async fn failures_back_off_and_gone_endpoints_are_dropped() {
        let (w, sender, dev, ..) = setup(0);
        w.wake(&dev);
        settle().await;
        assert_eq!(sender.sent.lock().len(), 1);
        // Second failure would be allowed after MIN_INTERVAL normally; back-off(1) = 10 s too.
        tokio::time::sleep(MIN_INTERVAL).await;
        w.wake(&dev);
        settle().await;
        assert_eq!(sender.sent.lock().len(), 2);
        // Now failures = 2 → 20 s back-off: a wake after 10 s waits for the trailing slot.
        tokio::time::sleep(MIN_INTERVAL).await;
        w.wake(&dev);
        settle().await;
        assert_eq!(sender.sent.lock().len(), 2);
        sender.status.store(410, Ordering::Relaxed);
        tokio::time::sleep(MIN_INTERVAL).await;
        settle().await;
        assert_eq!(sender.sent.lock().len(), 3);
        settle().await;
        assert!(!w.has_subscribers());
        // No subscription any more: nothing is posted.
        tokio::time::sleep(MIN_INTERVAL * 3).await;
        w.wake(&dev);
        settle().await;
        assert_eq!(sender.sent.lock().len(), 3);
    }

    #[tokio::test]
    async fn bus_events_trigger_wakes_for_attention_only() {
        let (w, sender, _dev, ..) = setup(201);
        let bus = EventBus::new(100);
        let cancel = CancellationToken::new();
        w.start(bus.clone(), cancel.clone());
        tokio::task::yield_now().await;
        bus.publish(
            "session.upserted",
            serde_json::json!({ "id": "s1", "status": "busy" }),
        );
        bus.publish(
            "session.event",
            serde_json::json!({ "id": "e1", "status": "waiting" }),
        );
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert_eq!(sender.sent.lock().len(), 0);
        bus.publish(
            "session.upserted",
            serde_json::json!({ "id": "s1", "status": "idle" }),
        );
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while sender.sent.lock().is_empty() && std::time::Instant::now() < deadline {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        assert_eq!(sender.sent.lock().len(), 1);
        cancel.cancel();
    }
}
