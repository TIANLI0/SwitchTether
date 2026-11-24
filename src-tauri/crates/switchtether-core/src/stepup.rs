//! Step-up authentication: short-lived grants for high-risk scopes (terminal,
//! file writes, config writes), proven by an ECDSA P-256 signature from a phone
//! Keystore key that can only be used after BiometricPrompt / device-credential
//! authentication. The server never trusts a client-side "authenticated" flag.

use std::collections::HashMap;
use std::time::{Duration, Instant};

use parking_lot::Mutex;
use serde::{Deserialize, Serialize};

use crate::util::{new_id, random_token};

pub const CHALLENGE_TTL: Duration = Duration::from_secs(60);
pub const DEFAULT_GRANT_TTL: Duration = Duration::from_secs(15 * 60);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Scope {
    Terminal,
    FilesWrite,
    ConfigWrite,
    /// Starting a port forward (docs/PROTOCOL.md §7.11).
    PortForward,
    /// Changing a Git repository: stage, discard, commit, push, pull, checkout (PROTOCOL.md §7.7).
    GitWrite,
}

impl Scope {
    pub fn as_str(self) -> &'static str {
        match self {
            Scope::Terminal => "terminal",
            Scope::FilesWrite => "files_write",
            Scope::ConfigWrite => "config_write",
            Scope::PortForward => "port_forward",
            Scope::GitWrite => "git_write",
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Challenge {
    pub challenge_id: String,
    pub nonce: String,
    pub expires_at: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GrantView {
    pub scope: Scope,
    pub expires_at: String,
}

struct PendingChallenge {
    device_id: String,
    scope: Scope,
    nonce: String,
    created: Instant,
}

struct Grant {
    expires: Instant,
    expires_wall: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum VerifyError {
    UnknownChallenge,
    WrongDevice,
    BadSignature,
    NoKey,
}

pub struct StepUpManager {
    challenges: Mutex<HashMap<String, PendingChallenge>>,
    grants: Mutex<HashMap<(String, Scope), Grant>>,
    grant_ttl: Duration,
}

impl Default for StepUpManager {
    fn default() -> Self {
        Self::new(DEFAULT_GRANT_TTL)
    }
}

/// The exact byte string the phone signs.
pub fn signing_payload(device_id: &str, challenge_id: &str, nonce: &str, scope: Scope) -> Vec<u8> {
    format!(
        "switchtether-stepup-v1\n{device_id}\n{challenge_id}\n{nonce}\n{}",
        scope.as_str()
    )
    .into_bytes()
}

/// Verify an ASN.1 DER ECDSA P-256/SHA-256 signature against an SPKI DER public key.
pub fn verify_p256_spki(spki_der: &[u8], msg: &[u8], sig_der: &[u8]) -> bool {
    let Some(point) = spki_to_uncompressed_point(spki_der) else {
        return false;
    };
    ring::signature::UnparsedPublicKey::new(&ring::signature::ECDSA_P256_SHA256_ASN1, point)
        .verify(msg, sig_der)
        .is_ok()
}

/// Extract the 65-byte uncompressed EC point from a P-256 SubjectPublicKeyInfo.
/// Accepts either a full SPKI (as produced by Java `PublicKey.getEncoded()`)
/// or an already-raw uncompressed point.
pub fn spki_to_uncompressed_point(der: &[u8]) -> Option<&[u8]> {
    if der.len() == 65 && der[0] == 0x04 {
        return Some(der);
    }
    // P-256 SPKI is 91 bytes: 26-byte header + 65-byte BIT STRING payload.
    // Validate the fixed header (SEQUENCE{SEQUENCE{ecPublicKey, prime256v1}, BIT STRING}).
    const HEADER: [u8; 26] = [
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08,
        0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    ];
    if der.len() == 91 && der[..26] == HEADER && der[26] == 0x04 {
        return Some(&der[26..]);
    }
    None
}

impl StepUpManager {
    pub fn new(grant_ttl: Duration) -> Self {
        Self {
            challenges: Mutex::new(HashMap::new()),
            grants: Mutex::new(HashMap::new()),
            grant_ttl,
        }
    }

    pub fn challenge(&self, device_id: &str, scope: Scope) -> Challenge {
        let mut map = self.challenges.lock();
        map.retain(|_, c| c.created.elapsed() < CHALLENGE_TTL);
        // Bound memory: at most 16 pending challenges per device.
        let count = map.values().filter(|c| c.device_id == device_id).count();
        if count >= 16 {
            if let Some(k) = map
                .iter()
                .filter(|(_, c)| c.device_id == device_id)
                .min_by_key(|(_, c)| c.created)
                .map(|(k, _)| k.clone())
            {
                map.remove(&k);
            }
        }
        let id = new_id();
        let nonce = random_token();
        map.insert(
            id.clone(),
            PendingChallenge {
                device_id: device_id.to_string(),
                scope,
                nonce: nonce.clone(),
                created: Instant::now(),
            },
        );
        let expires =
            chrono::Utc::now() + chrono::Duration::from_std(CHALLENGE_TTL).unwrap_or_default();
        Challenge {
            challenge_id: id,
            nonce,
            expires_at: expires.to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
        }
    }

    /// Verify a signed challenge. Challenges are single-use regardless of outcome.
    pub fn verify(
        &self,
        device_id: &str,
        step_up_key: Option<&[u8]>,
        challenge_id: &str,
        signature_der: &[u8],
    ) -> Result<GrantView, VerifyError> {
        let pending = {
            let mut map = self.challenges.lock();
            map.retain(|_, c| c.created.elapsed() < CHALLENGE_TTL);
            map.remove(challenge_id)
                .ok_or(VerifyError::UnknownChallenge)?
        };
        if pending.device_id != device_id {
            return Err(VerifyError::WrongDevice);
        }
        let key = step_up_key.ok_or(VerifyError::NoKey)?;
        let msg = signing_payload(device_id, challenge_id, &pending.nonce, pending.scope);
        if !verify_p256_spki(key, &msg, signature_der) {
            return Err(VerifyError::BadSignature);
        }
        let wall =
            chrono::Utc::now() + chrono::Duration::from_std(self.grant_ttl).unwrap_or_default();
        self.grants.lock().insert(
            (device_id.to_string(), pending.scope),
            Grant {
                expires: Instant::now() + self.grant_ttl,
                expires_wall: wall,
            },
        );
        Ok(GrantView {
            scope: pending.scope,
            expires_at: wall.to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
        })
    }

    pub fn has_grant(&self, device_id: &str, scope: Scope) -> bool {
        let mut g = self.grants.lock();
        match g.get(&(device_id.to_string(), scope)) {
            Some(grant) if grant.expires > Instant::now() => true,
            Some(_) => {
                g.remove(&(device_id.to_string(), scope));
                false
            }
            None => false,
        }
    }

    pub fn grants(&self, device_id: &str) -> Vec<GrantView> {
        let now = Instant::now();
        self.grants
            .lock()
            .iter()
            .filter(|((d, _), g)| d == device_id && g.expires > now)
            .map(|((_, s), g)| GrantView {
                scope: *s,
                expires_at: g
                    .expires_wall
                    .to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
            })
            .collect()
    }

    /// Drop every grant and challenge of a device (revocation, re-pairing).
    pub fn clear_device(&self, device_id: &str) {
        self.grants.lock().retain(|(d, _), _| d != device_id);
        self.challenges
            .lock()
            .retain(|_, c| c.device_id != device_id);
    }

    pub fn revoke_grant(&self, device_id: &str, scope: Scope) {
        self.grants.lock().remove(&(device_id.to_string(), scope));
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use ring::rand::SystemRandom;
    use ring::signature::{EcdsaKeyPair, KeyPair, ECDSA_P256_SHA256_ASN1_SIGNING};

    /// Test helper: a P-256 key pair with its SPKI encoding (like Android's
    /// `PublicKey.getEncoded()`).
    pub(crate) fn test_key() -> (EcdsaKeyPair, Vec<u8>) {
        let rng = SystemRandom::new();
        let pkcs8 = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, &rng).unwrap();
        let kp = EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, pkcs8.as_ref(), &rng)
            .unwrap();
        let point = kp.public_key().as_ref().to_vec();
        let mut spki = vec![
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06,
            0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
        ];
        spki.extend_from_slice(&point);
        (kp, spki)
    }

    pub(crate) fn sign(kp: &EcdsaKeyPair, msg: &[u8]) -> Vec<u8> {
        kp.sign(&SystemRandom::new(), msg)
            .unwrap()
            .as_ref()
            .to_vec()
    }

    #[test]
    fn full_flow() {
        let (kp, spki) = test_key();
        let m = StepUpManager::default();
        assert!(!m.has_grant("d1", Scope::Terminal));
        let c = m.challenge("d1", Scope::Terminal);
        let sig = sign(
            &kp,
            &signing_payload("d1", &c.challenge_id, &c.nonce, Scope::Terminal),
        );
        let g = m.verify("d1", Some(&spki), &c.challenge_id, &sig).unwrap();
        assert_eq!(g.scope, Scope::Terminal);
        assert!(m.has_grant("d1", Scope::Terminal));
        assert!(!m.has_grant("d1", Scope::FilesWrite));
        assert!(!m.has_grant("d2", Scope::Terminal));
        // replay of the same challenge is rejected
        assert_eq!(
            m.verify("d1", Some(&spki), &c.challenge_id, &sig)
                .unwrap_err(),
            VerifyError::UnknownChallenge
        );
        m.clear_device("d1");
        assert!(!m.has_grant("d1", Scope::Terminal));
    }

    #[test]
    fn rejects_wrong_signature_scope_and_device() {
        let (kp, spki) = test_key();
        let (other, _) = test_key();
        let m = StepUpManager::default();

        let c = m.challenge("d1", Scope::Terminal);
        let sig = sign(
            &other,
            &signing_payload("d1", &c.challenge_id, &c.nonce, Scope::Terminal),
        );
        assert_eq!(
            m.verify("d1", Some(&spki), &c.challenge_id, &sig)
                .unwrap_err(),
            VerifyError::BadSignature
        );

        // signature over a different scope does not upgrade
        let c = m.challenge("d1", Scope::Terminal);
        let sig = sign(
            &kp,
            &signing_payload("d1", &c.challenge_id, &c.nonce, Scope::FilesWrite),
        );
        assert_eq!(
            m.verify("d1", Some(&spki), &c.challenge_id, &sig)
                .unwrap_err(),
            VerifyError::BadSignature
        );

        // another device cannot redeem my challenge
        let c = m.challenge("d1", Scope::Terminal);
        let sig = sign(
            &kp,
            &signing_payload("d1", &c.challenge_id, &c.nonce, Scope::Terminal),
        );
        assert_eq!(
            m.verify("d2", Some(&spki), &c.challenge_id, &sig)
                .unwrap_err(),
            VerifyError::WrongDevice
        );

        let c = m.challenge("d1", Scope::Terminal);
        assert_eq!(
            m.verify("d1", None, &c.challenge_id, b"x").unwrap_err(),
            VerifyError::NoKey
        );
    }

    #[test]
    fn grants_expire() {
        let (kp, spki) = test_key();
        let m = StepUpManager::new(Duration::from_millis(30));
        let c = m.challenge("d1", Scope::FilesWrite);
        let sig = sign(
            &kp,
            &signing_payload("d1", &c.challenge_id, &c.nonce, Scope::FilesWrite),
        );
        m.verify("d1", Some(&spki), &c.challenge_id, &sig).unwrap();
        assert!(m.has_grant("d1", Scope::FilesWrite));
        std::thread::sleep(Duration::from_millis(60));
        assert!(!m.has_grant("d1", Scope::FilesWrite));
    }
}
