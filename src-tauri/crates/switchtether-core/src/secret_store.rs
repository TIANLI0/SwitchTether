//! Storage for long-lived desktop secrets (the TLS identity private key).
//!
//! Preference order:
//! 1. OS secure storage via `keyring` (Windows Credential Manager, macOS
//!    Keychain, Linux Secret Service).
//! 2. A file with owner-only permissions in the SwitchTether data dir — used
//!    when no keychain is available (headless Linux, CI). The backend in use is
//!    reported to the UI so users know which protection applies.
//!
//! Private keys are never exported through any API.

use std::fs;
use std::path::{Path, PathBuf};

use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SecretBackendKind {
    OsKeychain,
    File,
    Memory,
}

pub trait SecretStore: Send + Sync {
    fn kind(&self) -> SecretBackendKind;
    fn get(&self, name: &str) -> anyhow::Result<Option<String>>;
    fn set(&self, name: &str, value: &str) -> anyhow::Result<()>;
    fn delete(&self, name: &str) -> anyhow::Result<()>;
}

const SERVICE: &str = "io.github.tianli0.switchtether";

pub struct KeyringStore {
    /// Account prefix, so tests / multiple profiles never collide.
    prefix: String,
}

impl KeyringStore {
    pub fn new(prefix: impl Into<String>) -> Self {
        Self {
            prefix: prefix.into(),
        }
    }

    fn entry(&self, name: &str) -> anyhow::Result<keyring::Entry> {
        Ok(keyring::Entry::new(
            SERVICE,
            &format!("{}{}", self.prefix, name),
        )?)
    }

    /// Round-trip probe: returns true only when the keychain really works.
    pub fn probe(&self) -> bool {
        let name = "__probe__";
        let ok = (|| -> anyhow::Result<bool> {
            self.set(name, "ok")?;
            let v = self.get(name)?;
            let _ = self.delete(name);
            Ok(v.as_deref() == Some("ok"))
        })();
        matches!(ok, Ok(true))
    }
}

impl SecretStore for KeyringStore {
    fn kind(&self) -> SecretBackendKind {
        SecretBackendKind::OsKeychain
    }
    fn get(&self, name: &str) -> anyhow::Result<Option<String>> {
        match self.entry(name)?.get_password() {
            Ok(v) => Ok(Some(v)),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(e.into()),
        }
    }
    fn set(&self, name: &str, value: &str) -> anyhow::Result<()> {
        self.entry(name)?.set_password(value)?;
        Ok(())
    }
    fn delete(&self, name: &str) -> anyhow::Result<()> {
        match self.entry(name)?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(e) => Err(e.into()),
        }
    }
}

/// Owner-only files under `<dir>/secrets/`.
pub struct FileStore {
    dir: PathBuf,
}

impl FileStore {
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        Self { dir: dir.into() }
    }
    fn path(&self, name: &str) -> PathBuf {
        let safe: String = name
            .chars()
            .map(|c| {
                if c.is_ascii_alphanumeric() || c == '-' || c == '_' {
                    c
                } else {
                    '_'
                }
            })
            .collect();
        self.dir.join(format!("{safe}.secret"))
    }
}

pub(crate) fn write_private_file(path: &Path, data: &[u8]) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("tmp");
    {
        #[cfg(unix)]
        {
            use std::io::Write;
            use std::os::unix::fs::OpenOptionsExt;
            let mut f = fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .mode(0o600)
                .open(&tmp)?;
            f.write_all(data)?;
            f.sync_all()?;
        }
        #[cfg(not(unix))]
        {
            // On Windows the file inherits the user-profile ACL (owner + SYSTEM +
            // Administrators), which is the same protection the rest of the
            // user's home directory has.
            fs::write(&tmp, data)?;
        }
    }
    fs::rename(&tmp, path)
}

impl SecretStore for FileStore {
    fn kind(&self) -> SecretBackendKind {
        SecretBackendKind::File
    }
    fn get(&self, name: &str) -> anyhow::Result<Option<String>> {
        match fs::read_to_string(self.path(name)) {
            Ok(v) => Ok(Some(v)),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(e.into()),
        }
    }
    fn set(&self, name: &str, value: &str) -> anyhow::Result<()> {
        write_private_file(&self.path(name), value.as_bytes())?;
        Ok(())
    }
    fn delete(&self, name: &str) -> anyhow::Result<()> {
        match fs::remove_file(self.path(name)) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(e.into()),
        }
    }
}

/// In-memory store for tests.
#[derive(Default)]
pub struct MemoryStore {
    map: parking_lot::Mutex<std::collections::HashMap<String, String>>,
}

impl SecretStore for MemoryStore {
    fn kind(&self) -> SecretBackendKind {
        SecretBackendKind::Memory
    }
    fn get(&self, name: &str) -> anyhow::Result<Option<String>> {
        Ok(self.map.lock().get(name).cloned())
    }
    fn set(&self, name: &str, value: &str) -> anyhow::Result<()> {
        self.map.lock().insert(name.to_string(), value.to_string());
        Ok(())
    }
    fn delete(&self, name: &str) -> anyhow::Result<()> {
        self.map.lock().remove(name);
        Ok(())
    }
}

/// Pick the best available backend. `data_dir` is used for the file fallback.
/// `SWITCHTETHER_SECRET_BACKEND=file` forces the file backend (CI / headless).
pub fn default_store(data_dir: &Path) -> Box<dyn SecretStore> {
    let forced_file = std::env::var("SWITCHTETHER_SECRET_BACKEND")
        .map(|v| v.eq_ignore_ascii_case("file"))
        .unwrap_or(false);
    if !forced_file {
        let k = KeyringStore::new("remote.");
        if k.probe() {
            return Box::new(k);
        }
        log::warn!("[remote] OS keychain unavailable; falling back to owner-only file storage");
    }
    Box::new(FileStore::new(data_dir.join("secrets")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_store_roundtrip() {
        let tmp = tempfile::tempdir().unwrap();
        let s = FileStore::new(tmp.path());
        assert_eq!(s.get("a/b").unwrap(), None);
        s.set("a/b", "v1").unwrap();
        assert_eq!(s.get("a/b").unwrap().as_deref(), Some("v1"));
        s.set("a/b", "v2").unwrap();
        assert_eq!(s.get("a/b").unwrap().as_deref(), Some("v2"));
        s.delete("a/b").unwrap();
        assert_eq!(s.get("a/b").unwrap(), None);
        s.delete("a/b").unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn file_store_is_owner_only() {
        use std::os::unix::fs::PermissionsExt;
        let tmp = tempfile::tempdir().unwrap();
        let s = FileStore::new(tmp.path());
        s.set("k", "v").unwrap();
        let mode = fs::metadata(s.path("k")).unwrap().permissions().mode();
        assert_eq!(mode & 0o777, 0o600);
    }
}
