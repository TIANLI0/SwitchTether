//! Claude Code adapter: live-session discovery, transcript observation and
//! SwitchTether-managed sessions.

pub mod live;
pub mod managed;
pub mod transcript;

use std::path::PathBuf;
use std::time::{Duration, Instant};

use parking_lot::Mutex;

pub use live::LiveClaudeSession;

pub struct ClaudeObserver {
    pub claude_dir: PathBuf,
    pub claude_bin: Option<PathBuf>,
    cache: Mutex<Option<(Instant, Vec<LiveClaudeSession>)>>,
    use_cli: bool,
    /// Fingerprint of the registry directory at the last [`Self::registry_changed`] call.
    registry_stamp: Mutex<Option<live::RegistryStamp>>,
}

impl ClaudeObserver {
    pub fn new(claude_dir: PathBuf, claude_bin: Option<PathBuf>) -> Self {
        Self {
            claude_dir,
            use_cli: claude_bin.is_some(),
            claude_bin,
            cache: Mutex::new(None),
            registry_stamp: Mutex::new(None),
        }
    }

    /// True when a registry file was added, removed or rewritten since the previous call. Only
    /// directory metadata is read (no file contents, no process), so it can run every half second:
    /// Claude Code rewrites its file on every status change (busy / idle / waiting).
    pub fn registry_changed(&self) -> bool {
        let now = live::registry_stamp(&self.claude_dir);
        let mut last = self.registry_stamp.lock();
        let changed = last.as_ref() != Some(&now);
        *last = Some(now);
        changed
    }

    /// Live sessions with statuses read from the registry right now: the last full list (see
    /// [`Self::live_sessions`]) updated per process from the registry files. Used when the
    /// registry changed, so a turn shorter than the full refresh interval is still seen.
    pub async fn live_sessions_now(&self) -> Vec<LiveClaudeSession> {
        let base = self.cache.lock().as_ref().map(|(_, v)| v.clone());
        let base = match base {
            Some(v) => v,
            None => return self.live_sessions().await,
        };
        let merged = live::merge_registry(base, live::read_registry(&self.claude_dir));
        // Keep the full list's timestamp: the next full refresh still happens on schedule.
        if let Some((_, v)) = self.cache.lock().as_mut() {
            *v = merged.clone();
        }
        merged
    }

    pub fn detect() -> Self {
        Self::new(live::claude_dir(), live::find_claude_bin())
    }

    /// Live sessions (cached for 3 s to bound process spawning).
    pub async fn live_sessions(&self) -> Vec<LiveClaudeSession> {
        if let Some((at, v)) = self.cache.lock().as_ref() {
            if at.elapsed() < Duration::from_secs(3) {
                return v.clone();
            }
        }
        let mut sessions = None;
        if self.use_cli {
            if let Some(bin) = &self.claude_bin {
                sessions = live::agents_json(bin).await;
            }
        }
        let registry = live::read_registry(&self.claude_dir);
        let sessions = match sessions {
            // `agents --json` omits `entrypoint`; take it from the registry.
            Some(mut v) => {
                for s in &mut v {
                    if s.entrypoint.is_none() {
                        s.entrypoint = registry
                            .iter()
                            .find(|r| r.session_id == s.session_id)
                            .and_then(|r| r.entrypoint.clone());
                    }
                }
                v
            }
            None => registry,
        };
        *self.cache.lock() = Some((Instant::now(), sessions.clone()));
        sessions
    }

    pub fn transcript_path(&self, cwd: Option<&str>, session_id: &str) -> Option<PathBuf> {
        live::transcript_path(&self.claude_dir, cwd, session_id)
    }
}
