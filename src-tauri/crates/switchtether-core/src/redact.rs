//! Secret redaction helpers used for logs, error messages and API views.
//!
//! These are deliberately conservative: anything that *looks* like a credential
//! is masked. They complement (not replace) the structural redaction done on
//! provider configs in the backend bridge.

/// Key names that indicate a secret value (case-insensitive substring match).
const SENSITIVE_KEY_PARTS: &[&str] = &[
    "key",
    "token",
    "secret",
    "password",
    "passwd",
    "auth",
    "credential",
    "cookie",
    "session",
    "bearer",
    "private",
];

/// Key names that contain a sensitive substring but are known to be safe
/// metadata (exact, case-insensitive).
const SAFE_KEYS: &[&str] = &[
    "keytype",
    "authtype",
    "authmode",
    "auth_method",
    "authmethod",
    "requires_openai_auth",
    "requiresopenaiauth",
    "sessionid",
    "session_id",
    "tokencount",
    "max_tokens",
    "maxtokens",
    "max_output_tokens",
    "input_tokens",
    "output_tokens",
    "tokens",
    "apikeyconfigured",
    "hasapikey",
    "keyboard",
    // Metadata lists produced by SwitchTether itself (no secret values inside).
    "secretfields",
    "credentialstatus",
    "credentialmessage",
];

pub fn is_sensitive_key(key: &str) -> bool {
    let k = key.to_ascii_lowercase();
    if SAFE_KEYS.contains(&k.as_str()) {
        return false;
    }
    if k.ends_with("_url") || k.ends_with("url") || k.ends_with("_tokens") {
        return false;
    }
    SENSITIVE_KEY_PARTS.iter().any(|p| k.contains(p))
}

/// Mask a secret for display: keep only the last 4 characters when the secret
/// is long enough that this does not meaningfully reduce its entropy.
pub fn mask_secret(value: &str) -> Option<String> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return None;
    }
    let chars: Vec<char> = trimmed.chars().collect();
    if chars.len() >= 16 {
        let tail: String = chars[chars.len() - 4..].iter().collect();
        Some(format!("…{tail}"))
    } else {
        Some("…".to_string())
    }
}

/// Redact credential-looking substrings in free text (error messages, logs).
pub fn redact_text(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for (i, word) in input.split_inclusive(char::is_whitespace).enumerate() {
        let _ = i;
        let core = word.trim_end();
        let trailing = &word[core.len()..];
        if looks_like_secret(core) {
            out.push_str("[REDACTED]");
        } else {
            out.push_str(core);
        }
        out.push_str(trailing);
    }
    out
}

fn looks_like_secret(token: &str) -> bool {
    let t = token.trim_matches(|c: char| c == '"' || c == '\'' || c == ',' || c == ';');
    // Bearer-style/known prefixes
    const PREFIXES: &[&str] = &[
        "sk-",
        "sk_",
        "pk-",
        "rk-",
        "ghp_",
        "gho_",
        "ghs_",
        "github_pat_",
        "xoxb-",
        "xoxp-",
        "AKIA",
        "AIza",
        "eyJ",
        "cr_",
        "glpat-",
    ];
    if PREFIXES.iter().any(|p| t.starts_with(p)) && t.len() >= 12 {
        return true;
    }
    if let Some((k, v)) = t.split_once('=') {
        if is_sensitive_key(k) && !v.is_empty() {
            return true;
        }
    }
    // Long opaque high-entropy tokens
    if t.len() >= 32
        && t.chars().all(|c| {
            c.is_ascii_alphanumeric()
                || c == '-'
                || c == '_'
                || c == '.'
                || c == '/'
                || c == '+'
                || c == '='
        })
        && t.chars().any(|c| c.is_ascii_digit())
        && t.chars().any(|c| c.is_ascii_alphabetic())
        && !t.contains('/')
    {
        return true;
    }
    false
}

/// Recursively redact a JSON value: every value whose key is sensitive is
/// replaced by `{ "configured": bool, "hint": "…abcd" }`.
pub fn redact_json(value: &mut serde_json::Value) {
    use serde_json::Value;
    match value {
        Value::Object(map) => {
            for (k, v) in map.iter_mut() {
                if is_sensitive_key(k) {
                    match v {
                        Value::String(s) => {
                            *v = serde_json::json!({
                                "redacted": true,
                                "configured": !s.trim().is_empty(),
                                "hint": mask_secret(s),
                            });
                        }
                        Value::Null => {}
                        Value::Bool(_) | Value::Number(_) => {}
                        Value::Object(_) | Value::Array(_) => {
                            // e.g. "headers": {...} under an auth key → mask everything
                            *v = serde_json::json!({ "redacted": true, "configured": true, "hint": null });
                        }
                    }
                } else {
                    redact_json(v);
                }
            }
        }
        Value::Array(items) => items.iter_mut().for_each(redact_json),
        // Inline TOML/ENV blobs (e.g. Codex `config` string) may contain secrets.
        Value::String(s) if s.contains('\n') && (s.contains('=') || s.contains(':')) => {
            *s = redact_config_text(s);
        }
        _ => {}
    }
}

/// Redact `key = "value"` / `KEY=value` / `key: value` lines whose key is sensitive.
pub fn redact_config_text(text: &str) -> String {
    text.lines()
        .map(|line| {
            let trimmed = line.trim_start();
            for sep in ['=', ':'] {
                if let Some((k, _v)) = trimmed.split_once(sep) {
                    let key = k.trim().trim_matches('"');
                    if !key.is_empty() && !key.contains(' ') && is_sensitive_key(key) {
                        let indent = &line[..line.len() - trimmed.len()];
                        return format!("{indent}{} {sep} \"[REDACTED]\"", k.trim());
                    }
                }
            }
            line.to_string()
        })
        .collect::<Vec<_>>()
        .join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn sensitive_keys() {
        assert!(is_sensitive_key("ANTHROPIC_AUTH_TOKEN"));
        assert!(is_sensitive_key("apiKey"));
        assert!(is_sensitive_key("OPENAI_API_KEY"));
        assert!(is_sensitive_key("experimental_bearer_token"));
        assert!(!is_sensitive_key("ANTHROPIC_BASE_URL"));
        assert!(!is_sensitive_key("model"));
        assert!(!is_sensitive_key("max_tokens"));
        assert!(!is_sensitive_key("requires_openai_auth"));
    }

    #[test]
    fn redacts_nested_json_and_toml_strings() {
        let mut v = json!({
            "name": "x",
            "settingsConfig": {
                "env": { "ANTHROPIC_AUTH_TOKEN": "sk-ant-abcdefghijklmnop1234", "ANTHROPIC_BASE_URL": "https://a" },
                "auth": { "OPENAI_API_KEY": "short" },
                "config": "model = \"gpt\"\nexperimental_bearer_token = \"abc\"\n"
            }
        });
        redact_json(&mut v);
        let s = v.to_string();
        assert!(!s.contains("sk-ant-abcdefghijklmnop1234"));
        assert!(!s.contains("\"short\""));
        assert!(!s.contains("\"abc\""));
        assert!(s.contains("https://a"));
        assert_eq!(
            v["settingsConfig"]["env"]["ANTHROPIC_AUTH_TOKEN"]["hint"],
            json!("…1234")
        );
        // "auth" itself is a sensitive key → whole object masked
        assert_eq!(v["settingsConfig"]["auth"]["redacted"], json!(true));
    }

    #[test]
    fn secret_field_metadata_survives_redaction() {
        let mut v = json!({ "secretFields": [{ "field": "/env/TOKEN", "name": "TOKEN", "configured": true, "hint": "…abcd" }] });
        redact_json(&mut v);
        assert_eq!(v["secretFields"][0]["name"], "TOKEN");
        assert_eq!(v["secretFields"][0]["hint"], "…abcd");
    }

    #[test]
    fn redacts_text() {
        let s = redact_text("request failed: key sk-abcdefghijklmnopqrstu rejected; token=abc123");
        assert!(!s.contains("sk-abcdefghijklmnopqrstu"));
        assert!(!s.contains("abc123"));
        assert!(s.contains("request failed"));
    }

    #[test]
    fn mask() {
        assert_eq!(mask_secret("  "), None);
        assert_eq!(mask_secret("abc").as_deref(), Some("…"));
        assert_eq!(
            mask_secret("0123456789abcdefWXYZ").as_deref(),
            Some("…WXYZ")
        );
    }
}
