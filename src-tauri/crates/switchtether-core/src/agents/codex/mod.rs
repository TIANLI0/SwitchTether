//! Codex adapter: rollout observation (read-only).

pub mod rollout;

use std::path::PathBuf;

pub fn codex_home() -> PathBuf {
    if let Some(d) = std::env::var_os("CODEX_HOME") {
        return PathBuf::from(d);
    }
    dirs::home_dir().unwrap_or_default().join(".codex")
}
