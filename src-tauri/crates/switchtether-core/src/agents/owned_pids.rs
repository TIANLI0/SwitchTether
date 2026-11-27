//! Process ids of short-lived helper processes SwitchTether itself starts (`claude -p /usage`,
//! `claude auth …`, model probes). Claude Code registers every process in
//! `~/.claude/sessions/<pid>.json`, so without this the observer would show such a helper as an
//! external live session ("untitled session" in the home folder). Managed sessions are not
//! listed here: they have their own entries.
//!
//! On Windows a `.cmd` launcher (npm install) makes the spawned pid `cmd.exe` and Claude Code
//! its child, so a pid also counts as owned when one of its nearest ancestors is.

use std::collections::HashSet;
use std::sync::{Mutex, OnceLock};

fn set() -> &'static Mutex<HashSet<u32>> {
    static OWNED: OnceLock<Mutex<HashSet<u32>>> = OnceLock::new();
    OWNED.get_or_init(Default::default)
}

fn lock() -> std::sync::MutexGuard<'static, HashSet<u32>> {
    set().lock().unwrap_or_else(|e| e.into_inner())
}

pub fn register(pid: u32) {
    if pid != 0 {
        lock().insert(pid);
    }
}

pub fn unregister(pid: u32) {
    lock().remove(&pid);
}

/// Exactly this pid was registered.
pub fn contains(pid: u32) -> bool {
    lock().contains(&pid)
}

/// This pid, or (Windows) one of its nearest ancestors, is a SwitchTether helper.
pub fn is_owned(pid: u32) -> bool {
    let owned: HashSet<u32> = {
        let s = lock();
        if s.is_empty() {
            return false;
        }
        s.clone()
    };
    #[cfg(windows)]
    let parent = parent_pid;
    #[cfg(not(windows))]
    let parent = |_: u32| None;
    owned_or_descendant(pid, &owned, parent)
}

/// `pid` or one of its three nearest ancestors (by `parent`) is in `owned`.
fn owned_or_descendant(
    pid: u32,
    owned: &HashSet<u32>,
    parent: impl Fn(u32) -> Option<u32>,
) -> bool {
    let mut p = pid;
    for depth in 0..4 {
        if owned.contains(&p) {
            return true;
        }
        if depth == 3 {
            break;
        }
        match parent(p) {
            Some(pp) if pp != 0 && pp != p => p = pp,
            _ => break,
        }
    }
    false
}

#[cfg(windows)]
fn parent_pid(pid: u32) -> Option<u32> {
    use windows_sys::Win32::Foundation::{CloseHandle, INVALID_HANDLE_VALUE};
    use windows_sys::Win32::System::Diagnostics::ToolHelp::{
        CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
        TH32CS_SNAPPROCESS,
    };
    unsafe {
        let snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if snap == INVALID_HANDLE_VALUE {
            return None;
        }
        let mut entry: PROCESSENTRY32W = std::mem::zeroed();
        entry.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
        let mut found = None;
        let mut ok = Process32FirstW(snap, &mut entry) != 0;
        while ok {
            if entry.th32ProcessID == pid {
                found = Some(entry.th32ParentProcessID);
                break;
            }
            ok = Process32NextW(snap, &mut entry) != 0;
        }
        CloseHandle(snap);
        found
    }
}

/// Registers a helper pid for as long as it lives; unregisters on drop. Drop it only after the
/// process has exited (waited for or killed and reaped).
#[must_use = "the pid is unregistered when the guard is dropped"]
pub struct OwnedPid(u32);

impl OwnedPid {
    pub fn new(pid: u32) -> Self {
        register(pid);
        Self(pid)
    }

    /// For `tokio::process::Child::id()`, which is `None` once the child was reaped.
    pub fn from_option(pid: Option<u32>) -> Option<Self> {
        pid.map(Self::new)
    }

    pub fn pid(&self) -> u32 {
        self.0
    }
}

impl Drop for OwnedPid {
    fn drop(&mut self) {
        unregister(self.0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn guard_registers_until_dropped() {
        // Pids far above anything real so parallel tests cannot collide.
        let pid = 4_000_000_001;
        assert!(!contains(pid));
        {
            let g = OwnedPid::new(pid);
            assert_eq!(g.pid(), pid);
            assert!(contains(pid));
            assert!(is_owned(pid));
        }
        assert!(!contains(pid));
        assert!(!is_owned(pid));
        assert!(OwnedPid::from_option(None).is_none());
        register(0);
        assert!(!contains(0), "pid 0 is never registered");
    }

    #[test]
    fn descendants_of_an_owned_launcher_count() {
        // 30 -> 20 -> 10 (e.g. claude.exe -> cmd.exe -> SwitchTether)
        let parent = |p: u32| match p {
            30 => Some(20),
            20 => Some(10),
            10 => Some(1),
            _ => None,
        };
        let owned: HashSet<u32> = [20].into_iter().collect();
        assert!(owned_or_descendant(30, &owned, parent));
        assert!(owned_or_descendant(20, &owned, parent));
        assert!(
            !owned_or_descendant(10, &owned, parent),
            "the launcher's parent is not owned"
        );
        assert!(!owned_or_descendant(99, &owned, parent));
        // A cycle in a stale snapshot does not loop forever.
        assert!(!owned_or_descendant(5, &owned, |p| Some(if p == 5 {
            6
        } else {
            5
        })));
    }

    #[cfg(windows)]
    #[test]
    fn parent_pid_of_a_spawned_child_is_this_process() {
        let mut child = std::process::Command::new("cmd")
            .args(["/C", "ping -n 2 127.0.0.1 >NUL"])
            .spawn()
            .unwrap();
        let found = parent_pid(child.id());
        let _ = child.kill();
        let _ = child.wait();
        assert_eq!(found, Some(std::process::id()));
    }
}
