//! Locating the real `codex` when the shim is installed as Codex Desktop's
//! `CODEX_CLI_PATH`: Desktop's own bundled binary must be used (its protocol
//! version must match Desktop), so it is found next to the parent process:
//! Windows `<app>\Codex.exe` → `<app>\resources\codex.exe`;
//! macOS `Codex.app/Contents/MacOS/Codex` → `Contents/Resources/codex`.

use std::path::{Path, PathBuf};

fn me() -> Option<PathBuf> {
    std::env::current_exe().ok()?.canonicalize().ok()
}

fn not_me(p: PathBuf) -> Option<PathBuf> {
    let canon = p.canonicalize().ok()?;
    if Some(&canon) == me().as_ref() {
        None
    } else {
        Some(p)
    }
}

pub fn real_codex() -> Option<PathBuf> {
    if let Some(p) = std::env::var_os("SWITCHTETHER_REAL_CODEX") {
        return not_me(PathBuf::from(p));
    }
    if let Some(parent) = parent_exe() {
        if let Some(found) = codex_near(&parent) {
            return not_me(found);
        }
    }
    // Last resort: a codex on PATH that is not the shim itself.
    let path = std::env::var_os("PATH")?;
    let names: &[&str] = if cfg!(windows) {
        &["codex.exe", "codex.cmd"]
    } else {
        &["codex"]
    };
    for dir in std::env::split_paths(&path) {
        for n in names {
            let p = dir.join(n);
            if p.is_file() {
                if let Some(ok) = not_me(p) {
                    return Some(ok);
                }
            }
        }
    }
    None
}

pub fn codex_near(parent_exe: &Path) -> Option<PathBuf> {
    let dir = parent_exe.parent()?;
    let candidates = if cfg!(windows) {
        vec![
            dir.join("resources").join("codex.exe"),
            dir.join("codex.exe"),
        ]
    } else {
        vec![
            dir.join("..").join("Resources").join("codex"),
            dir.join("resources").join("codex"),
            dir.join("codex"),
        ]
    };
    candidates.into_iter().find(|p| p.is_file())
}

#[cfg(windows)]
fn parent_exe() -> Option<PathBuf> {
    use windows_sys::Win32::Foundation::{CloseHandle, INVALID_HANDLE_VALUE};
    use windows_sys::Win32::System::Diagnostics::ToolHelp::{
        CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
        TH32CS_SNAPPROCESS,
    };
    use windows_sys::Win32::System::Threading::{
        OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION,
    };
    let my_pid = std::process::id();
    unsafe {
        let snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if snap == INVALID_HANDLE_VALUE {
            return None;
        }
        let mut entry: PROCESSENTRY32W = std::mem::zeroed();
        entry.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
        let mut ppid = None;
        if Process32FirstW(snap, &mut entry) != 0 {
            loop {
                if entry.th32ProcessID == my_pid {
                    ppid = Some(entry.th32ParentProcessID);
                    break;
                }
                if Process32NextW(snap, &mut entry) == 0 {
                    break;
                }
            }
        }
        CloseHandle(snap);
        let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, ppid?);
        if h.is_null() {
            return None;
        }
        let mut buf = vec![0u16; 1024];
        let mut len = buf.len() as u32;
        let ok = QueryFullProcessImageNameW(h, 0, buf.as_mut_ptr(), &mut len) != 0;
        CloseHandle(h);
        if !ok {
            return None;
        }
        Some(PathBuf::from(String::from_utf16_lossy(
            &buf[..len as usize],
        )))
    }
}

#[cfg(target_os = "linux")]
fn parent_exe() -> Option<PathBuf> {
    let ppid = unsafe { libc::getppid() };
    std::fs::read_link(format!("/proc/{ppid}/exe")).ok()
}

#[cfg(target_os = "macos")]
fn parent_exe() -> Option<PathBuf> {
    let ppid = unsafe { libc::getppid() };
    let out = std::process::Command::new("ps")
        .args(["-o", "comm=", "-p", &ppid.to_string()])
        .output()
        .ok()?;
    let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
    (!s.is_empty()).then(|| PathBuf::from(s))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_bundled_codex_next_to_parent() {
        let tmp = tempfile::tempdir().unwrap();
        let app = tmp.path().join("app");
        std::fs::create_dir_all(app.join("resources")).unwrap();
        let exe = if cfg!(windows) { "codex.exe" } else { "codex" };
        std::fs::write(app.join("resources").join(exe), b"x").unwrap();
        let parent = app.join(if cfg!(windows) { "Codex.exe" } else { "Codex" });
        if cfg!(windows) {
            assert_eq!(codex_near(&parent), Some(app.join("resources").join(exe)));
        } else {
            assert!(codex_near(&parent).is_some());
        }
    }
}
