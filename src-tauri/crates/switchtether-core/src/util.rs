//! Small shared helpers: randomness, ids, hashing, time.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine as _;
use ring::rand::{SecureRandom, SystemRandom};
use sha2::{Digest, Sha256};

/// Cryptographically secure random bytes.
pub fn random_bytes<const N: usize>() -> [u8; N] {
    let mut buf = [0u8; N];
    SystemRandom::new()
        .fill(&mut buf)
        .expect("system RNG unavailable");
    buf
}

/// 32 random bytes, base64url without padding.
pub fn random_token() -> String {
    URL_SAFE_NO_PAD.encode(random_bytes::<32>())
}

/// Random identifier (UUID v4 string).
pub fn new_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

pub fn sha256_hex(data: &[u8]) -> String {
    hex::encode(Sha256::digest(data))
}

pub fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

pub fn now_rfc3339() -> String {
    chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
}

pub fn ms_to_rfc3339(ms: i64) -> String {
    chrono::DateTime::from_timestamp_millis(ms)
        .unwrap_or_default()
        .to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
}

/// Find an executable on PATH (handles `.exe`/`.cmd`/`.bat` on Windows).
pub fn which(cmd: &str) -> Option<std::path::PathBuf> {
    let path = std::env::var_os("PATH")?;
    let exts: &[&str] = if cfg!(windows) {
        &[".exe", ".cmd", ".bat", ""]
    } else {
        &[""]
    };
    for dir in std::env::split_paths(&path) {
        for ext in exts {
            let p = dir.join(format!("{cmd}{ext}"));
            if p.is_file() {
                return Some(p);
            }
        }
    }
    None
}

/// Constant-time string comparison.
pub fn ct_eq(a: &str, b: &str) -> bool {
    use subtle::ConstantTimeEq;
    a.len() == b.len() && bool::from(a.as_bytes().ct_eq(b.as_bytes()))
}

/// Format a fingerprint for display: `ab12 cd34 …` groups of 4.
pub fn format_fingerprint(fp: &str) -> String {
    fp.as_bytes()
        .chunks(4)
        .map(|c| std::str::from_utf8(c).unwrap_or(""))
        .collect::<Vec<_>>()
        .join(" ")
}

/// Six-digit verification code derived from both fingerprints, shown on
/// both screens during pairing for optional visual comparison.
pub fn verification_code(server_fp: &str, client_fp: &str) -> String {
    let digest = Sha256::digest(format!("switchtether-sas-v1|{server_fp}|{client_fp}").as_bytes());
    let n = u32::from_be_bytes([digest[0], digest[1], digest[2], digest[3]]) % 1_000_000;
    format!("{n:06}")
}

/// Desktop time zone: IANA name (if the OS reports one) and current UTC offset
/// in minutes. Usage buckets and "today" are computed in this zone.
pub fn desktop_time_zone() -> (Option<String>, i32) {
    let offset = chrono::Local::now().offset().local_minus_utc() / 60;
    (iana_time_zone::get_timezone().ok(), offset)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_are_unique_and_urlsafe() {
        let a = random_token();
        let b = random_token();
        assert_ne!(a, b);
        assert_eq!(a.len(), 43);
        assert!(a
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'));
    }

    #[test]
    fn ct_eq_works() {
        assert!(ct_eq("abc", "abc"));
        assert!(!ct_eq("abc", "abd"));
        assert!(!ct_eq("abc", "abcd"));
    }

    #[test]
    fn sas_is_stable() {
        let c1 = verification_code("aa", "bb");
        assert_eq!(c1, verification_code("aa", "bb"));
        assert_ne!(c1, verification_code("aa", "bc"));
        assert_eq!(c1.len(), 6);
    }
}
