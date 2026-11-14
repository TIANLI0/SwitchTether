//! Read-only Git queries via the user's own `git` executable (respects their
//! config, credentials are never touched). Hardened against repository-local
//! configuration that could run programs: fsmonitor, external diff drivers and
//! textconv filters are disabled for every invocation.

use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::Serialize;
use tokio::process::Command;

const GIT_TIMEOUT: Duration = Duration::from_secs(15);
pub const MAX_DIFF_BYTES: usize = 2 * 1024 * 1024;

#[derive(Debug, thiserror::Error)]
pub enum GitError {
    #[error("git is not installed or not on PATH")]
    NotInstalled,
    #[error("not a git repository")]
    NotARepo,
    #[error("git timed out")]
    Timeout,
    #[error("git failed: {0}")]
    Failed(String),
}

fn base_command(dir: &Path) -> Command {
    let mut c = Command::new("git");
    c.arg("-C")
        .arg(dir)
        .args([
            "-c",
            "core.fsmonitor=false",
            "-c",
            "core.quotepath=false",
            "-c",
            "color.ui=false",
            "-c",
            "diff.external=",
            "--no-pager",
        ])
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_OPTIONAL_LOCKS", "0")
        .stdin(std::process::Stdio::null())
        .kill_on_drop(true);
    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        c.creation_flags(CREATE_NO_WINDOW);
    }
    c
}

async fn run(dir: &Path, args: &[&str], limit: usize) -> Result<(String, bool), GitError> {
    let mut cmd = base_command(dir);
    cmd.args(args);
    let out = match tokio::time::timeout(GIT_TIMEOUT, cmd.output()).await {
        Err(_) => return Err(GitError::Timeout),
        Ok(Err(e)) if e.kind() == std::io::ErrorKind::NotFound => {
            return Err(GitError::NotInstalled)
        }
        Ok(Err(e)) => return Err(GitError::Failed(e.to_string())),
        Ok(Ok(o)) => o,
    };
    if !out.status.success() {
        let err = String::from_utf8_lossy(&out.stderr);
        if err.contains("not a git repository") {
            return Err(GitError::NotARepo);
        }
        return Err(GitError::Failed(
            err.lines().next().unwrap_or("").to_string(),
        ));
    }
    let truncated = out.stdout.len() > limit;
    let bytes = &out.stdout[..out.stdout.len().min(limit)];
    Ok((String::from_utf8_lossy(bytes).into_owned(), truncated))
}

pub async fn repo_root(dir: &Path) -> Result<PathBuf, GitError> {
    let d = if dir.is_file() {
        dir.parent().unwrap_or(dir).to_path_buf()
    } else {
        dir.to_path_buf()
    };
    let (out, _) = run(&d, &["rev-parse", "--show-toplevel"], 64 * 1024).await?;
    Ok(PathBuf::from(out.trim()))
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct StatusEntry {
    pub path: String,
    pub orig_path: Option<String>,
    /// Index (staged) status letter, `.` if unchanged.
    pub index: char,
    /// Worktree status letter, `.` if unchanged.
    pub worktree: char,
    pub untracked: bool,
    pub conflicted: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RepoStatus {
    pub root: String,
    pub branch: Option<String>,
    pub upstream: Option<String>,
    pub ahead: i64,
    pub behind: i64,
    pub entries: Vec<StatusEntry>,
}

/// Parse `git status --porcelain=v2 --branch -z`.
pub fn parse_status_v2(root: &str, raw: &str) -> RepoStatus {
    let mut st = RepoStatus {
        root: root.to_string(),
        branch: None,
        upstream: None,
        ahead: 0,
        behind: 0,
        entries: Vec::new(),
    };
    let mut fields = raw.split('\0').peekable();
    while let Some(rec) = fields.next() {
        if rec.is_empty() {
            continue;
        }
        if let Some(h) = rec.strip_prefix("# ") {
            if let Some(b) = h.strip_prefix("branch.head ") {
                st.branch = (b != "(detached)").then(|| b.to_string());
            } else if let Some(u) = h.strip_prefix("branch.upstream ") {
                st.upstream = Some(u.to_string());
            } else if let Some(ab) = h.strip_prefix("branch.ab ") {
                for p in ab.split(' ') {
                    if let Some(a) = p.strip_prefix('+') {
                        st.ahead = a.parse().unwrap_or(0);
                    } else if let Some(b) = p.strip_prefix('-') {
                        st.behind = b.parse().unwrap_or(0);
                    }
                }
            }
            continue;
        }
        let mut chars = rec.chars();
        match chars.next() {
            Some('1') => {
                // 1 XY sub mH mI mW hH hI path
                let parts: Vec<&str> = rec.splitn(9, ' ').collect();
                if parts.len() == 9 {
                    let xy: Vec<char> = parts[1].chars().collect();
                    st.entries.push(StatusEntry {
                        path: parts[8].to_string(),
                        orig_path: None,
                        index: xy[0],
                        worktree: xy[1],
                        untracked: false,
                        conflicted: false,
                    });
                }
            }
            Some('2') => {
                // 2 XY sub mH mI mW hH hI Xscore path \0 origPath
                let parts: Vec<&str> = rec.splitn(10, ' ').collect();
                let orig = fields.next().map(|s| s.to_string());
                if parts.len() == 10 {
                    let xy: Vec<char> = parts[1].chars().collect();
                    st.entries.push(StatusEntry {
                        path: parts[9].to_string(),
                        orig_path: orig,
                        index: xy[0],
                        worktree: xy[1],
                        untracked: false,
                        conflicted: false,
                    });
                }
            }
            Some('u') => {
                let parts: Vec<&str> = rec.splitn(11, ' ').collect();
                if parts.len() == 11 {
                    let xy: Vec<char> = parts[1].chars().collect();
                    st.entries.push(StatusEntry {
                        path: parts[10].to_string(),
                        orig_path: None,
                        index: xy[0],
                        worktree: xy[1],
                        untracked: false,
                        conflicted: true,
                    });
                }
            }
            Some('?') => st.entries.push(StatusEntry {
                path: rec[2..].to_string(),
                orig_path: None,
                index: '?',
                worktree: '?',
                untracked: true,
                conflicted: false,
            }),
            _ => {}
        }
    }
    st
}

pub async fn status(dir: &Path) -> Result<RepoStatus, GitError> {
    let root = repo_root(dir).await?;
    let (raw, _) = run(
        &root,
        &[
            "status",
            "--porcelain=v2",
            "--branch",
            "-z",
            "--untracked-files=normal",
        ],
        4 * 1024 * 1024,
    )
    .await?;
    Ok(parse_status_v2(&root.to_string_lossy(), &raw))
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiffResult {
    pub root: String,
    pub diff: String,
    pub truncated: bool,
}

pub async fn diff(dir: &Path, path: Option<&str>, staged: bool) -> Result<DiffResult, GitError> {
    let root = repo_root(dir).await?;
    let mut args = vec!["diff", "--no-color", "--no-ext-diff", "--no-textconv", "-M"];
    if staged {
        args.push("--cached");
    }
    args.push("--");
    if let Some(p) = path {
        args.push(p);
    }
    let (diff, truncated) = run(&root, &args, MAX_DIFF_BYTES).await?;
    Ok(DiffResult {
        root: root.to_string_lossy().into_owned(),
        diff,
        truncated,
    })
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Commit {
    pub hash: String,
    pub author: String,
    pub email: String,
    pub time_ms: i64,
    pub subject: String,
}

pub fn parse_log(raw: &str) -> Vec<Commit> {
    raw.split('\x1e')
        .filter_map(|rec| {
            let rec = rec.trim_start_matches('\n');
            if rec.is_empty() {
                return None;
            }
            let f: Vec<&str> = rec.split('\x1f').collect();
            if f.len() < 5 {
                return None;
            }
            Some(Commit {
                hash: f[0].to_string(),
                author: f[1].to_string(),
                email: f[2].to_string(),
                time_ms: f[3].trim().parse::<i64>().unwrap_or(0) * 1000,
                subject: f[4].trim_end().to_string(),
            })
        })
        .collect()
}

pub async fn log(dir: &Path, path: Option<&str>, limit: usize) -> Result<Vec<Commit>, GitError> {
    let root = repo_root(dir).await?;
    let n = format!("--max-count={}", limit.clamp(1, 500));
    let mut args = vec![
        "log",
        n.as_str(),
        "--format=%H%x1f%an%x1f%ae%x1f%at%x1f%s%x1e",
    ];
    if path.is_some() {
        args.push("--follow");
    }
    args.push("--");
    if let Some(p) = path {
        args.push(p);
    }
    let (raw, _) = run(&root, &args, 4 * 1024 * 1024).await?;
    Ok(parse_log(&raw))
}

/// File content at a revision (for history view). Returns None for binary.
pub async fn show_file(dir: &Path, rev: &str, path: &str) -> Result<Option<String>, GitError> {
    if !rev.chars().all(|c| c.is_ascii_hexdigit()) || rev.len() < 4 || rev.len() > 64 {
        return Err(GitError::Failed("invalid revision".into()));
    }
    let root = repo_root(dir).await?;
    let spec = format!("{rev}:{path}");
    let (out, _) = run(
        &root,
        &["show", "--no-textconv", &spec],
        crate::files::DEFAULT_READ_LIMIT as usize,
    )
    .await?;
    Ok(crate::files::decode_text(out.as_bytes(), None).map(|(_, _, t)| t))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_status() {
        let raw = "# branch.oid abc\0# branch.head main\0# branch.upstream origin/main\0# branch.ab +2 -1\0\
1 .M N... 100644 100644 100644 aaa bbb src/lib.rs\0\
2 R. N... 100644 100644 100644 aaa bbb R100 new name.rs\0old.rs\0\
u UU N... 100644 100644 100644 100644 a b c conflict.txt\0\
? 中文.txt\0";
        let s = parse_status_v2("/r", raw);
        assert_eq!(s.branch.as_deref(), Some("main"));
        assert_eq!(s.upstream.as_deref(), Some("origin/main"));
        assert_eq!((s.ahead, s.behind), (2, 1));
        assert_eq!(s.entries.len(), 4);
        assert_eq!(s.entries[0].path, "src/lib.rs");
        assert_eq!(s.entries[0].worktree, 'M');
        assert_eq!(s.entries[1].path, "new name.rs");
        assert_eq!(s.entries[1].orig_path.as_deref(), Some("old.rs"));
        assert!(s.entries[2].conflicted);
        assert!(s.entries[3].untracked);
        assert_eq!(s.entries[3].path, "中文.txt");
    }

    #[test]
    fn parses_log() {
        let raw = "abc\x1fAlice\x1fa@x\x1f1700000000\x1ffix: thing\x1e\ndef\x1fBob\x1fb@x\x1f1700000100\x1finit\x1e";
        let c = parse_log(raw);
        assert_eq!(c.len(), 2);
        assert_eq!(c[0].subject, "fix: thing");
        assert_eq!(c[1].time_ms, 1_700_000_100_000);
    }

    #[tokio::test]
    async fn real_repo_roundtrip() {
        let tmp = tempfile::tempdir().unwrap();
        let d = tmp.path();
        let git = |args: &[&str]| {
            std::process::Command::new("git")
                .arg("-C")
                .arg(d)
                .args(args)
                .output()
        };
        if git(&["init", "-q"]).is_err() {
            return; // git not installed in this environment
        }
        git(&["config", "user.email", "t@example.com"]).unwrap();
        git(&["config", "user.name", "T"]).unwrap();
        std::fs::write(d.join("a.txt"), "one\n").unwrap();
        git(&["add", "."]).unwrap();
        git(&["commit", "-q", "-m", "first"]).unwrap();
        std::fs::write(d.join("a.txt"), "one\ntwo\n").unwrap();
        std::fs::write(d.join("b.txt"), "new\n").unwrap();

        let st = status(d).await.unwrap();
        assert!(st
            .entries
            .iter()
            .any(|e| e.path == "a.txt" && e.worktree == 'M'));
        assert!(st.entries.iter().any(|e| e.path == "b.txt" && e.untracked));
        let df = diff(d, Some("a.txt"), false).await.unwrap();
        assert!(df.diff.contains("+two"));
        let lg = log(d, Some("a.txt"), 10).await.unwrap();
        assert_eq!(lg.len(), 1);
        assert_eq!(lg[0].subject, "first");
        let shown = show_file(d, &lg[0].hash, "a.txt").await.unwrap();
        assert_eq!(shown.as_deref(), Some("one\n"));
        assert!(matches!(
            status(&d.join("..")).await,
            Err(GitError::NotARepo) | Ok(_)
        ));
    }
}
