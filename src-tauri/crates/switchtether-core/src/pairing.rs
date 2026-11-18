//! QR pairing sessions.
//!
//! A pairing session is created on the desktop (explicit user action) and shown
//! as a QR code containing: candidate hosts, port, the server certificate
//! fingerprint (so the phone can pin it before sending anything) and a one-time
//! 256-bit secret. The phone proves possession of the secret over a TLS
//! connection that already authenticates its own client certificate; the
//! device identity is bound to that certificate, not to the secret.
//!
//! Protections: short TTL, single use, per-session failure limit, per-IP rate
//! limit, constant-time secret comparison, at most a few concurrent sessions.
//!
//! Each session also has a 6-digit **pairing code** for pairing without the QR
//! code (the phone found the computer on the LAN or was given its address). The
//! code is weaker than the secret, so wrong codes are counted across all
//! sessions and after [`MAX_CODE_FAILURES`] every active code is disabled (the
//! user shows a new one), on top of the per-IP limit. The phone does not know the
//! certificate in advance on this path; the verification code compared on both
//! screens after pairing is what detects a machine-in-the-middle.

use std::collections::HashMap;
use std::net::IpAddr;
use std::time::{Duration, Instant};

use parking_lot::Mutex;
use serde::Serialize;

use crate::util::{ct_eq, new_id, random_token};

pub const PAIRING_TTL: Duration = Duration::from_secs(5 * 60);
pub const MAX_FAILURES_PER_SESSION: u32 = 5;
pub const MAX_ATTEMPTS_PER_IP_PER_MIN: u32 = 20;
pub const MAX_ACTIVE_SESSIONS: usize = 4;
/// Wrong pairing codes (all sessions together) before every active code is disabled.
pub const MAX_CODE_FAILURES: u32 = 5;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingOffer {
    pub pairing_id: String,
    pub secret: String,
    /// 6-digit pairing code (shown next to the QR code).
    pub code: String,
    pub uri: String,
    pub expires_at: String,
    pub hosts: Vec<String>,
    pub port: u16,
    pub server_fingerprint: String,
}

struct PairingSession {
    secret: String,
    /// None once disabled after too many wrong codes (the QR secret stays valid).
    code: Option<String>,
    created: Instant,
    failures: u32,
}

#[derive(Debug, PartialEq, Eq)]
pub enum PairingCheck {
    Ok,
    /// Unknown, expired, used or destroyed after too many failures.
    Expired,
    WrongSecret,
    RateLimited {
        retry_after_ms: u64,
    },
}

#[derive(Default)]
pub struct PairingManager {
    sessions: Mutex<HashMap<String, PairingSession>>,
    ip_window: Mutex<HashMap<IpAddr, (Instant, u32)>>,
    code_failures: Mutex<u32>,
}

/// Uniform random 6-digit code (rejection sampling, no modulo bias).
fn random_code() -> String {
    loop {
        let n = u32::from_le_bytes(crate::util::random_bytes::<4>());
        // 4_294_000_000 is the largest multiple of 1_000_000 below 2^32.
        if n < 4_294_000_000 {
            return format!("{:06}", n % 1_000_000);
        }
    }
}

/// Keeps only digits, so "123 456" / "123-456" typed by a user match.
pub fn normalize_code(code: &str) -> String {
    code.chars().filter(|c| c.is_ascii_digit()).collect()
}

impl PairingManager {
    pub fn new() -> Self {
        Self::default()
    }

    fn gc(sessions: &mut HashMap<String, PairingSession>) {
        sessions.retain(|_, s| s.created.elapsed() < PAIRING_TTL);
    }

    /// Create a new pairing session. Older sessions beyond the active limit are
    /// dropped (oldest first).
    pub fn create(
        &self,
        hosts: Vec<String>,
        port: u16,
        server_fingerprint: &str,
        server_name: &str,
    ) -> PairingOffer {
        let mut sessions = self.sessions.lock();
        Self::gc(&mut sessions);
        while sessions.len() >= MAX_ACTIVE_SESSIONS {
            if let Some(oldest) = sessions
                .iter()
                .min_by_key(|(_, s)| s.created)
                .map(|(k, _)| k.clone())
            {
                sessions.remove(&oldest);
            }
        }
        let pairing_id = new_id();
        let secret = random_token();
        let code = loop {
            let c = random_code();
            if !sessions
                .values()
                .any(|s| s.code.as_deref() == Some(c.as_str()))
            {
                break c;
            }
        };
        sessions.insert(
            pairing_id.clone(),
            PairingSession {
                secret: secret.clone(),
                code: Some(code.clone()),
                created: Instant::now(),
                failures: 0,
            },
        );
        let expires_at =
            chrono::Utc::now() + chrono::Duration::from_std(PAIRING_TTL).unwrap_or_default();
        // Encode only what is unsafe inside a query value; keep hosts readable.
        const QUERY_VALUE: &percent_encoding::AsciiSet = &percent_encoding::CONTROLS
            .add(b' ')
            .add(b'"')
            .add(b'#')
            .add(b'%')
            .add(b'&')
            .add(b'+')
            .add(b'<')
            .add(b'>')
            .add(b'=')
            .add(b'?')
            .add(b'`')
            .add(b'{')
            .add(b'}')
            .add(b'|')
            .add(b'\\')
            .add(b'^');
        let enc = |s: &str| percent_encoding::utf8_percent_encode(s, QUERY_VALUE).to_string();
        let uri = format!(
            "switchtether://pair?v={}&h={}&p={}&fp={}&id={}&s={}&n={}",
            crate::PROTOCOL_VERSION,
            enc(&hosts.join(",")),
            port,
            server_fingerprint,
            pairing_id,
            secret,
            enc(server_name),
        );
        PairingOffer {
            pairing_id,
            secret,
            code,
            uri,
            expires_at: expires_at.to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
            hosts,
            port,
            server_fingerprint: server_fingerprint.to_string(),
        }
    }

    pub fn cancel(&self, pairing_id: &str) {
        self.sessions.lock().remove(pairing_id);
    }

    pub fn cancel_all(&self) {
        self.sessions.lock().clear();
    }

    pub fn active_count(&self) -> usize {
        let mut s = self.sessions.lock();
        Self::gc(&mut s);
        s.len()
    }

    /// Per-IP sliding-minute limiter for pairing attempts.
    pub fn check_rate(&self, ip: IpAddr) -> Result<(), u64> {
        let mut w = self.ip_window.lock();
        let now = Instant::now();
        let entry = w.entry(ip).or_insert((now, 0));
        if now.duration_since(entry.0) >= Duration::from_secs(60) {
            *entry = (now, 0);
        }
        entry.1 += 1;
        if entry.1 > MAX_ATTEMPTS_PER_IP_PER_MIN {
            let retry = Duration::from_secs(60).saturating_sub(now.duration_since(entry.0));
            return Err(retry.as_millis() as u64);
        }
        if w.len() > 4096 {
            w.retain(|_, (t, _)| now.duration_since(*t) < Duration::from_secs(60));
        }
        Ok(())
    }

    /// Verify and (on success) consume a pairing session.
    pub fn verify_and_consume(&self, ip: IpAddr, pairing_id: &str, secret: &str) -> PairingCheck {
        if let Err(ms) = self.check_rate(ip) {
            return PairingCheck::RateLimited { retry_after_ms: ms };
        }
        let mut sessions = self.sessions.lock();
        Self::gc(&mut sessions);
        let Some(session) = sessions.get_mut(pairing_id) else {
            return PairingCheck::Expired;
        };
        if ct_eq(&session.secret, secret) {
            sessions.remove(pairing_id);
            PairingCheck::Ok
        } else {
            session.failures += 1;
            if session.failures >= MAX_FAILURES_PER_SESSION {
                sessions.remove(pairing_id);
            }
            PairingCheck::WrongSecret
        }
    }
}

impl PairingManager {
    /// Verify a 6-digit pairing code and (on success) consume its session.
    pub fn verify_code_and_consume(&self, ip: IpAddr, code: &str) -> PairingCheck {
        if let Err(ms) = self.check_rate(ip) {
            return PairingCheck::RateLimited { retry_after_ms: ms };
        }
        let code = normalize_code(code);
        let mut sessions = self.sessions.lock();
        Self::gc(&mut sessions);
        if !sessions.values().any(|s| s.code.is_some()) {
            return PairingCheck::Expired;
        }
        // Compare against every active code (constant time per comparison, no early exit).
        let mut hit: Option<String> = None;
        for (id, s) in sessions.iter() {
            if let Some(c) = &s.code {
                if ct_eq(c, &code) {
                    hit = Some(id.clone());
                }
            }
        }
        if let Some(id) = hit {
            sessions.remove(&id);
            *self.code_failures.lock() = 0;
            return PairingCheck::Ok;
        }
        let mut failures = self.code_failures.lock();
        *failures += 1;
        if *failures >= MAX_CODE_FAILURES {
            *failures = 0;
            for s in sessions.values_mut() {
                s.code = None;
            }
        }
        PairingCheck::WrongSecret
    }
}

/// Pairing link with the relay URL and access token appended (docs/RELAY.md §5).
pub fn with_relay(uri: &str, relay: &crate::relay::RelayConfig) -> String {
    let enc = |v: &str| {
        percent_encoding::utf8_percent_encode(v, percent_encoding::NON_ALPHANUMERIC).to_string()
    };
    format!(
        "{uri}&r={}&rt={}&rk={}",
        enc(&relay.url),
        enc(&relay.token),
        enc(&relay.key)
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(n: u8) -> IpAddr {
        IpAddr::from([192, 168, 1, n])
    }

    #[test]
    fn code_pairs_once_and_ignores_separators() {
        let m = PairingManager::new();
        let o = m.create(vec!["10.0.0.2".into()], 1, "ab", "PC");
        assert_eq!(o.code.len(), 6);
        assert!(o.code.chars().all(|c| c.is_ascii_digit()));
        let spaced = format!("{} {}", &o.code[..3], &o.code[3..]);
        assert_eq!(m.verify_code_and_consume(ip(1), &spaced), PairingCheck::Ok);
        assert_eq!(
            m.verify_code_and_consume(ip(1), &o.code),
            PairingCheck::Expired
        );
        // The QR secret of a session paired by code is gone too.
        assert_eq!(
            m.verify_and_consume(ip(1), &o.pairing_id, &o.secret),
            PairingCheck::Expired
        );
    }

    #[test]
    fn wrong_codes_disable_all_codes_but_not_the_qr() {
        let m = PairingManager::new();
        let a = m.create(vec![], 1, "ab", "PC");
        let b = m.create(vec![], 1, "ab", "PC");
        let wrong = (0..1_000_000)
            .map(|n| format!("{n:06}"))
            .find(|c| *c != a.code && *c != b.code)
            .unwrap();
        for i in 0..MAX_CODE_FAILURES {
            // Different IPs: the global counter, not the per-IP limit, stops guessing.
            assert_eq!(
                m.verify_code_and_consume(ip(10 + i as u8), &wrong),
                PairingCheck::WrongSecret
            );
        }
        assert_eq!(
            m.verify_code_and_consume(ip(50), &a.code),
            PairingCheck::Expired
        );
        assert_eq!(
            m.verify_and_consume(ip(50), &b.pairing_id, &b.secret),
            PairingCheck::Ok
        );
    }

    #[test]
    fn single_use() {
        let m = PairingManager::new();
        let o = m.create(vec!["10.0.0.2".into()], 47615, "fp", "Desk");
        assert!(o
            .uri
            .starts_with("switchtether://pair?v=1&h=10.0.0.2&p=47615&fp=fp&id="));
        assert_eq!(
            m.verify_and_consume(ip(1), &o.pairing_id, &o.secret),
            PairingCheck::Ok
        );
        assert_eq!(
            m.verify_and_consume(ip(1), &o.pairing_id, &o.secret),
            PairingCheck::Expired
        );
    }

    #[test]
    fn failures_destroy_session() {
        let m = PairingManager::new();
        let o = m.create(vec![], 1, "fp", "D");
        for _ in 0..MAX_FAILURES_PER_SESSION {
            assert_eq!(
                m.verify_and_consume(ip(2), &o.pairing_id, "wrong"),
                PairingCheck::WrongSecret
            );
        }
        // Correct secret no longer works: the session was destroyed.
        assert_eq!(
            m.verify_and_consume(ip(2), &o.pairing_id, &o.secret),
            PairingCheck::Expired
        );
    }

    #[test]
    fn ip_rate_limit() {
        let m = PairingManager::new();
        for _ in 0..MAX_ATTEMPTS_PER_IP_PER_MIN {
            assert_eq!(
                m.verify_and_consume(ip(3), "nope", "x"),
                PairingCheck::Expired
            );
        }
        assert!(matches!(
            m.verify_and_consume(ip(3), "nope", "x"),
            PairingCheck::RateLimited { .. }
        ));
        // other IPs are unaffected
        assert_eq!(
            m.verify_and_consume(ip(4), "nope", "x"),
            PairingCheck::Expired
        );
    }

    #[test]
    fn active_session_cap() {
        let m = PairingManager::new();
        let first = m.create(vec![], 1, "fp", "D");
        for _ in 0..MAX_ACTIVE_SESSIONS {
            m.create(vec![], 1, "fp", "D");
        }
        assert_eq!(m.active_count(), MAX_ACTIVE_SESSIONS);
        assert_eq!(
            m.verify_and_consume(ip(5), &first.pairing_id, &first.secret),
            PairingCheck::Expired
        );
    }
}
