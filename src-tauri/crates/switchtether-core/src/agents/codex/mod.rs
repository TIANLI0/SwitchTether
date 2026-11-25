//! Codex adapter: rollout observation (read-only).

pub mod commands;
pub mod managed;
pub mod rollout;

use std::path::PathBuf;

pub fn codex_home() -> PathBuf {
    if let Some(d) = std::env::var_os("CODEX_HOME") {
        return PathBuf::from(d);
    }
    dirs::home_dir().unwrap_or_default().join(".codex")
}

/// The name Codex (Desktop, `thread/name/set`, `/rename`) recorded for a thread in
/// `$CODEX_HOME/session_index.jsonl` (`{"id", "thread_name", "updated_at"}` per line, the last line
/// for an id wins). Desktop names a thread with its own model shortly after the first message and
/// writes it here; a thread taken over from Desktop learns it from this file when the name did not
/// pass through the attached connection. Cached on the file's size and modification time.
pub fn indexed_thread_name(thread_id: &str) -> Option<String> {
    use parking_lot::Mutex;
    use std::collections::HashMap;
    use std::sync::OnceLock;
    type Cache = (
        Option<(std::time::SystemTime, u64)>,
        HashMap<String, String>,
    );
    static CACHE: OnceLock<Mutex<Cache>> = OnceLock::new();
    let path = codex_home().join("session_index.jsonl");
    let meta = std::fs::metadata(&path).ok()?;
    let key = (meta.modified().ok()?, meta.len());
    let cache = CACHE.get_or_init(|| Mutex::new((None, HashMap::new())));
    let mut c = cache.lock();
    if c.0 != Some(key) {
        let text = std::fs::read_to_string(&path).ok()?;
        let mut names = HashMap::new();
        for line in text.lines() {
            let Ok(v) = serde_json::from_str::<serde_json::Value>(line) else {
                continue;
            };
            let (Some(id), Some(name)) = (
                v.get("id").and_then(|x| x.as_str()),
                v.get("thread_name").and_then(|x| x.as_str()),
            ) else {
                continue;
            };
            if let Some(t) = crate::agents::claude::transcript::clean_title(Some(name.to_string()))
            {
                names.insert(id.to_string(), crate::redact::redact_text(&t));
            }
        }
        *c = (Some(key), names);
    }
    c.1.get(thread_id).cloned()
}
