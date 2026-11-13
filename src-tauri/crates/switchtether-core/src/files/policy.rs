//! Path policy for the remote file browser.
//!
//! Two tiers:
//! - **Denied**: credential stores, key material, keychains, browser profiles and
//!   SwitchTether/CC Switch's own data. Never readable/writable through the file
//!   API (the remote shell still has the user's real permissions — this policy
//!   limits accidental exposure through the browser, it is not a sandbox).
//! - **Sensitive**: files that commonly hold project secrets (`.env`, `*.pem`…).
//!   Listed with a `sensitive` flag; reading requires an explicit
//!   `allowSensitive=true` from the client (the app shows a warning first).
//!
//! All checks run on the canonicalized path, so symlinks cannot be used to
//! reach a denied location.

use std::path::{Component, Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Classification {
    Normal,
    Sensitive,
    Denied,
}

fn home() -> Option<PathBuf> {
    dirs::home_dir().map(|h| normalize_case(&h))
}

/// Lower-cases on case-insensitive platforms so prefix checks are reliable.
fn normalize_case(p: &Path) -> PathBuf {
    let s = p.to_string_lossy();
    let s = s.strip_prefix(r"\\?\").unwrap_or(&s).to_string();
    if cfg!(any(windows, target_os = "macos")) {
        PathBuf::from(s.to_lowercase())
    } else {
        PathBuf::from(s)
    }
}

/// Directories (relative to home) that are always denied.
const DENIED_HOME_DIRS: &[&str] = &[
    ".ssh",
    ".gnupg",
    ".aws",
    ".azure",
    ".kube",
    ".docker",
    ".config/gcloud",
    ".config/gh",
    ".config/hub",
    ".password-store",
    ".switchtether",
    ".cc-switch",
    ".local/share/keyrings",
    "Library/Keychains",
    "Library/Cookies",
    "Library/Application Support/Google/Chrome",
    "Library/Application Support/Firefox",
    "Library/Application Support/Microsoft Edge",
    ".mozilla",
    ".config/google-chrome",
    ".config/chromium",
    "AppData/Roaming/Microsoft/Credentials",
    "AppData/Local/Microsoft/Credentials",
    "AppData/Roaming/Microsoft/Protect",
    "AppData/Local/Google/Chrome/User Data",
    "AppData/Local/Microsoft/Edge/User Data",
    "AppData/Roaming/Mozilla/Firefox/Profiles",
];

/// Files (relative to home) that are always denied.
const DENIED_HOME_FILES: &[&str] = &[
    ".netrc",
    ".git-credentials",
    ".pypirc",
    ".claude/.credentials.json",
    ".codex/auth.json",
    ".gemini/oauth_creds.json",
    ".config/gh/hosts.yml",
];

/// Absolute system locations that are always denied.
const DENIED_ABSOLUTE: &[&str] = &[
    "/etc/shadow",
    "/etc/gshadow",
    "/etc/sudoers",
    "/etc/ssh",
    "/private/etc/sudoers",
    "/var/db/sudo",
    r"c:\windows\system32\config",
];

/// File names that are always denied (private key material).
const DENIED_NAMES: &[&str] = &[
    "id_rsa",
    "id_dsa",
    "id_ecdsa",
    "id_ed25519",
    "id_ecdsa_sk",
    "id_ed25519_sk",
];

/// File names / extensions that are sensitive (warn, require opt-in).
const SENSITIVE_NAMES: &[&str] = &[".env", ".npmrc", ".yarnrc.yml", "credentials", "secrets.json", ".envrc"];
const SENSITIVE_EXTS: &[&str] = &["pem", "key", "p12", "pfx", "jks", "keystore", "kdbx", "ppk", "asc", "gpg"];

fn rel_matches(path: &Path, base: &Path, rel: &str) -> bool {
    let target = normalize_case(&base.join(rel));
    path.starts_with(&target)
}

pub fn classify(canonical: &Path) -> Classification {
    let p = normalize_case(canonical);
    if let Some(h) = home() {
        if DENIED_HOME_DIRS.iter().any(|d| rel_matches(&p, &h, d))
            || DENIED_HOME_FILES.iter().any(|f| p == normalize_case(&h.join(f)))
        {
            return Classification::Denied;
        }
    }
    if DENIED_ABSOLUTE
        .iter()
        .any(|a| p.starts_with(normalize_case(Path::new(a))))
    {
        return Classification::Denied;
    }
    let name = p
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_default();
    if DENIED_NAMES.contains(&name.as_str()) {
        return Classification::Denied;
    }
    if SENSITIVE_NAMES.contains(&name.as_str())
        || name.starts_with(".env.")
        || p.extension()
            .map(|e| SENSITIVE_EXTS.contains(&e.to_string_lossy().as_ref()))
            .unwrap_or(false)
    {
        return Classification::Sensitive;
    }
    Classification::Normal
}

#[derive(Debug, thiserror::Error)]
pub enum PathError {
    #[error("path must be absolute")]
    NotAbsolute,
    #[error("path contains invalid components")]
    Invalid,
    #[error("path does not exist")]
    NotFound,
    #[error("access to this location is blocked by SwitchTether's file policy")]
    Denied,
    #[error("{0}")]
    Io(String),
}

fn check_raw(raw: &str) -> Result<PathBuf, PathError> {
    if raw.is_empty() || raw.len() > 4096 || raw.contains('\0') {
        return Err(PathError::Invalid);
    }
    let p = PathBuf::from(raw);
    if !p.is_absolute() {
        return Err(PathError::NotAbsolute);
    }
    // Reject explicit parent traversal even before canonicalization, so that
    // policy decisions never depend on how `..` would resolve.
    if p.components().any(|c| matches!(c, Component::ParentDir)) {
        return Err(PathError::Invalid);
    }
    Ok(p)
}

/// Resolve an existing path (following symlinks) and apply the deny policy.
pub fn resolve_existing(raw: &str) -> Result<(PathBuf, Classification), PathError> {
    let p = check_raw(raw)?;
    let canon = dunce_canonicalize(&p).map_err(|e| match e.kind() {
        std::io::ErrorKind::NotFound => PathError::NotFound,
        _ => PathError::Io(e.to_string()),
    })?;
    let class = classify(&canon);
    // The *requested* path is checked too (a denied symlink name).
    if class == Classification::Denied || classify(&p) == Classification::Denied {
        return Err(PathError::Denied);
    }
    Ok((canon, class))
}

/// Resolve a path that may not exist yet: its parent must exist.
pub fn resolve_new(raw: &str) -> Result<(PathBuf, Classification), PathError> {
    let p = check_raw(raw)?;
    let name = p.file_name().ok_or(PathError::Invalid)?.to_owned();
    let parent = p.parent().ok_or(PathError::Invalid)?;
    let (canon_parent, _) = resolve_existing(&parent.to_string_lossy())?;
    let full = canon_parent.join(name);
    let class = classify(&full);
    if class == Classification::Denied {
        return Err(PathError::Denied);
    }
    Ok((full, class))
}

/// `std::fs::canonicalize` without the `\\?\` verbatim prefix on Windows.
pub fn dunce_canonicalize(p: &Path) -> std::io::Result<PathBuf> {
    let c = std::fs::canonicalize(p)?;
    #[cfg(windows)]
    {
        let s = c.to_string_lossy();
        if let Some(rest) = s.strip_prefix(r"\\?\") {
            if !rest.starts_with("UNC\\") {
                return Ok(PathBuf::from(rest));
            }
            return Ok(PathBuf::from(format!(r"\\{}", &rest[4..])));
        }
    }
    Ok(c)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn denies_credentials() {
        let h = dirs::home_dir().unwrap();
        assert_eq!(classify(&h.join(".ssh").join("config")), Classification::Denied);
        assert_eq!(classify(&h.join(".codex").join("auth.json")), Classification::Denied);
        assert_eq!(classify(&h.join(".switchtether").join("x")), Classification::Denied);
        assert_eq!(classify(&h.join("proj").join("id_ed25519")), Classification::Denied);
        assert_eq!(classify(&h.join(".codex").join("config.toml")), Classification::Normal);
    }

    #[test]
    fn sensitive_files() {
        let h = dirs::home_dir().unwrap();
        assert_eq!(classify(&h.join("p").join(".env")), Classification::Sensitive);
        assert_eq!(classify(&h.join("p").join(".env.local")), Classification::Sensitive);
        assert_eq!(classify(&h.join("p").join("server.pem")), Classification::Sensitive);
        assert_eq!(classify(&h.join("p").join("main.rs")), Classification::Normal);
    }

    #[test]
    fn rejects_relative_and_traversal() {
        assert!(matches!(resolve_existing("relative/path"), Err(PathError::NotAbsolute)));
        let tmp = tempfile::tempdir().unwrap();
        let trav = format!("{}/../x", tmp.path().display());
        assert!(matches!(resolve_existing(&trav), Err(PathError::Invalid)));
        assert!(matches!(resolve_existing("\0"), Err(PathError::Invalid)));
    }

    #[cfg(unix)]
    #[test]
    fn symlink_into_denied_dir_is_blocked() {
        let tmp = tempfile::tempdir().unwrap();
        let ssh = dirs::home_dir().unwrap().join(".ssh");
        if !ssh.exists() {
            return;
        }
        let link = tmp.path().join("innocent");
        std::os::unix::fs::symlink(&ssh, &link).unwrap();
        assert!(matches!(
            resolve_existing(&link.to_string_lossy()),
            Err(PathError::Denied)
        ));
    }
}
