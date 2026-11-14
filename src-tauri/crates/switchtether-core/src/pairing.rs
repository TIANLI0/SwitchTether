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

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingOffer {
    pub pairing_id: String,
    pub secret: String,
    pub uri: String,
    pub expires_at: String,
    pub hosts: Vec<String>,
    pub port: u16,
    pub server_fingerprint: String,
}

struct PairingSession {
    secret: String,
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
        sessions.insert(
            pairing_id.clone(),
            PairingSession {
                secret: secret.clone(),
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
