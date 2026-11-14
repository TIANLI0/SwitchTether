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
}

impl ClaudeObserver {
    pub fn new(claude_dir: PathBuf, claude_bin: Option<PathBuf>) -> Self {
        Self {
            claude_dir,
            use_cli: claude_bin.is_some(),
            claude_bin,
            cache: Mutex::new(None),
        }
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
