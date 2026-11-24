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

// ---------------------------------------------------------------------------
// Write operations (step-up `git_write`, PROTOCOL.md §7.7). They run the user's
// own git with the same hardening as reads; the user's hooks run as they would
// in a terminal. Nothing here can prompt: terminal prompts, Git Credential
// Manager dialogs and ssh-askpass are disabled, so missing credentials fail
// with git's message instead of waiting on the desktop.
// ---------------------------------------------------------------------------

pub const COMMIT_TIMEOUT: Duration = Duration::from_secs(60);
pub const NETWORK_TIMEOUT: Duration = Duration::from_secs(120);
const LOCAL_TIMEOUT: Duration = Duration::from_secs(30);
pub const MAX_PATHS: usize = 1000;
pub const MAX_MESSAGE_BYTES: usize = 16 * 1024;
/// Output of a write command kept for the phone (git's progress lines are noisy).
const MAX_OUTPUT_CHARS: usize = 4000;

/// Validate a path relative to the repository root: no absolute paths, no
/// `..` components, no option-looking or control characters.
pub fn validate_rel_path(p: &str) -> Result<(), GitError> {
    let bad = || Err(GitError::Failed(format!("invalid path: {p}")));
    if p.is_empty() || p.len() > 4096 || p.starts_with('-') {
        return bad();
    }
    if p.chars().any(|c| c.is_control()) {
        return bad();
    }
    let norm = p.replace('\\', "/");
    if norm.starts_with('/') || (norm.len() >= 2 && norm.as_bytes()[1] == b':') {
        return bad();
    }
    if norm.split('/').any(|seg| seg == "..") {
        return bad();
    }
    Ok(())
}

fn validate_paths(paths: &[String]) -> Result<(), GitError> {
    if paths.is_empty() {
        return Err(GitError::Failed("no paths given".into()));
    }
    if paths.len() > MAX_PATHS {
        return Err(GitError::Failed("too many paths".into()));
    }
    paths.iter().try_for_each(|p| validate_rel_path(p))
}

/// Branch names: what `git check-ref-format --branch` would accept, checked
/// locally (no leading `-`, no `..`, no control / special characters).
pub fn validate_branch_name(name: &str) -> Result<(), GitError> {
    let bad = || Err(GitError::Failed(format!("invalid branch name: {name}")));
    if name.is_empty() || name.len() > 250 || name.starts_with('-') || name.starts_with('/') {
        return bad();
    }
    if name.ends_with('/') || name.ends_with('.') || name.ends_with(".lock") || name == "@" {
        return bad();
    }
    if name.contains("..") || name.contains("//") || name.contains("@{") {
        return bad();
    }
    if name
        .chars()
        .any(|c| c.is_control() || c == ' ' || "~^:?*[\\".contains(c))
    {
        return bad();
    }
    if name.split('/').any(|seg| seg.starts_with('.')) {
        return bad();
    }
    Ok(())
}

fn write_command(dir: &Path) -> Command {
    let mut c = base_command(dir);
    c.env("GIT_LITERAL_PATHSPECS", "1")
        .env("GCM_INTERACTIVE", "never")
        .env("GIT_ASKPASS", "")
        .env("SSH_ASKPASS_REQUIRE", "never")
        .env("GIT_EDITOR", ":")
        .env("GIT_MERGE_AUTOEDIT", "no");
    c
}

fn tail_chars(s: &str, max: usize) -> String {
    let n = s.chars().count();
    if n <= max {
        s.to_string()
    } else {
        let skip = n - max;
        format!("…{}", s.chars().skip(skip).collect::<String>())
    }
}

/// Run a write command; returns combined stdout + stderr (trimmed, tail kept).
async fn run_write(dir: &Path, args: &[&str], timeout: Duration) -> Result<String, GitError> {
    let mut cmd = write_command(dir);
    cmd.args(args);
    let out = match tokio::time::timeout(timeout, cmd.output()).await {
        Err(_) => return Err(GitError::Timeout),
        Ok(Err(e)) if e.kind() == std::io::ErrorKind::NotFound => {
            return Err(GitError::NotInstalled)
        }
        Ok(Err(e)) => return Err(GitError::Failed(e.to_string())),
        Ok(Ok(o)) => o,
    };
    let stdout = String::from_utf8_lossy(&out.stdout);
    let stderr = String::from_utf8_lossy(&out.stderr);
    let mut text = String::new();
    for part in [stdout.trim(), stderr.trim()] {
        if !part.is_empty() {
            if !text.is_empty() {
                text.push('\n');
            }
            text.push_str(part);
        }
    }
    if !out.status.success() {
        if stderr.contains("not a git repository") {
            return Err(GitError::NotARepo);
        }
        let msg = if text.is_empty() {
            format!("git exited with {}", out.status)
        } else {
            tail_chars(&text, MAX_OUTPUT_CHARS)
        };
        return Err(GitError::Failed(msg));
    }
    Ok(tail_chars(&text, MAX_OUTPUT_CHARS))
}

async fn has_head(root: &Path) -> bool {
    run(root, &["rev-parse", "--verify", "-q", "HEAD"], 1024)
        .await
        .is_ok()
}

/// Result of a write operation: the repository's new state plus git's output.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WriteResult {
    pub status: RepoStatus,
    pub output: String,
    /// New HEAD after a commit / pull / checkout.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub head: Option<String>,
}

async fn finish(root: &Path, output: String, with_head: bool) -> Result<WriteResult, GitError> {
    let head = if with_head {
        run(root, &["rev-parse", "HEAD"], 1024)
            .await
            .ok()
            .map(|(h, _)| h.trim().to_string())
    } else {
        None
    };
    Ok(WriteResult {
        status: status(root).await?,
        output,
        head,
    })
}

pub async fn stage(dir: &Path, paths: &[String]) -> Result<WriteResult, GitError> {
    validate_paths(paths)?;
    let root = repo_root(dir).await?;
    let mut args = vec!["add", "-A", "--"];
    args.extend(paths.iter().map(String::as_str));
    let out = run_write(&root, &args, LOCAL_TIMEOUT).await?;
    finish(&root, out, false).await
}

/// Stage every change in the repository (`git add -A`).
pub async fn stage_all(dir: &Path) -> Result<WriteResult, GitError> {
    let root = repo_root(dir).await?;
    let out = run_write(&root, &["add", "-A"], LOCAL_TIMEOUT).await?;
    finish(&root, out, false).await
}

pub async fn unstage(dir: &Path, paths: &[String]) -> Result<WriteResult, GitError> {
    validate_paths(paths)?;
    let root = repo_root(dir).await?;
    let mut args = if has_head(&root).await {
        vec!["restore", "--staged", "--"]
    } else {
        // No commit yet: nothing to restore from, just drop the paths from the index.
        vec!["rm", "--cached", "-r", "-q", "--"]
    };
    args.extend(paths.iter().map(String::as_str));
    let out = run_write(&root, &args, LOCAL_TIMEOUT).await?;
    finish(&root, out, false).await
}

/// Discard unstaged changes. Tracked files are restored from the index (staged
/// changes stay); untracked files and folders are moved to the OS trash, never
/// deleted. Every path must currently appear in `git status`.
pub async fn discard(dir: &Path, paths: &[String]) -> Result<WriteResult, GitError> {
    validate_paths(paths)?;
    let root = repo_root(dir).await?;
    let st = status(&root).await?;
    let norm = |p: &str| p.replace('\\', "/").trim_end_matches('/').to_string();
    let mut tracked: Vec<&str> = Vec::new();
    let mut untracked: Vec<PathBuf> = Vec::new();
    for p in paths {
        let entry = st
            .entries
            .iter()
            .find(|e| norm(&e.path) == norm(p))
            .ok_or_else(|| GitError::Failed(format!("no changes to discard: {p}")))?;
        if entry.conflicted {
            return Err(GitError::Failed(format!(
                "{p} has a merge conflict; resolve it first"
            )));
        }
        if entry.untracked {
            untracked.push(root.join(norm(p)));
        } else if entry.worktree != '.' {
            tracked.push(p.as_str());
        } else {
            return Err(GitError::Failed(format!(
                "{p} has only staged changes; unstage it first"
            )));
        }
    }
    let mut output = String::new();
    if !tracked.is_empty() {
        let mut args = vec!["restore", "--worktree", "--"];
        args.extend(tracked);
        output = run_write(&root, &args, LOCAL_TIMEOUT).await?;
    }
    if !untracked.is_empty() {
        let canon_root =
            std::fs::canonicalize(&root).map_err(|e| GitError::Failed(e.to_string()))?;
        tokio::task::spawn_blocking(move || -> Result<(), GitError> {
            for p in untracked {
                // The resolved path must stay inside the repository (symlinks).
                let parent = p
                    .parent()
                    .and_then(|d| std::fs::canonicalize(d).ok())
                    .ok_or_else(|| GitError::Failed("path does not exist".into()))?;
                if !parent.starts_with(&canon_root) {
                    return Err(GitError::Failed("path is outside the repository".into()));
                }
                let raw = p.to_string_lossy().into_owned();
                let (resolved, _) = crate::files::policy::resolve_existing(&raw)
                    .map_err(|e| GitError::Failed(e.to_string()))?;
                trash::delete(&resolved).map_err(|e| GitError::Failed(e.to_string()))?;
            }
            Ok(())
        })
        .await
        .map_err(|e| GitError::Failed(e.to_string()))??;
    }
    finish(&root, output, false).await
}

pub async fn commit(dir: &Path, message: &str) -> Result<WriteResult, GitError> {
    let msg = message.trim();
    if msg.is_empty() {
        return Err(GitError::Failed("commit message is empty".into()));
    }
    if msg.len() > MAX_MESSAGE_BYTES || msg.contains('\0') {
        return Err(GitError::Failed("commit message is too long".into()));
    }
    let root = repo_root(dir).await?;
    let out = run_write(&root, &["commit", "-m", msg], COMMIT_TIMEOUT).await?;
    finish(&root, out, true).await
}

pub async fn push(
    dir: &Path,
    set_upstream: bool,
    remote: Option<&str>,
) -> Result<WriteResult, GitError> {
    let root = repo_root(dir).await?;
    let out = if set_upstream {
        let st = status(&root).await?;
        let branch = st
            .branch
            .ok_or_else(|| GitError::Failed("not on a branch (detached HEAD)".into()))?;
        let remote = remote.unwrap_or("origin");
        validate_branch_name(remote)?;
        run_write(
            &root,
            &["push", "--porcelain", "-u", remote, &branch],
            NETWORK_TIMEOUT,
        )
        .await?
    } else {
        run_write(&root, &["push", "--porcelain"], NETWORK_TIMEOUT).await?
    };
    finish(&root, out, false).await
}

pub async fn pull(dir: &Path) -> Result<WriteResult, GitError> {
    let root = repo_root(dir).await?;
    let out = run_write(
        &root,
        &["pull", "--ff-only", "--no-rebase"],
        NETWORK_TIMEOUT,
    )
    .await?;
    finish(&root, out, true).await
}

pub async fn fetch(dir: &Path) -> Result<WriteResult, GitError> {
    let root = repo_root(dir).await?;
    let out = run_write(&root, &["fetch", "--prune"], NETWORK_TIMEOUT).await?;
    finish(&root, out, false).await
}

/// Switch to an existing branch, or create one from HEAD (`create`). Git
/// refuses a switch that would overwrite local changes; its message is returned.
pub async fn checkout(dir: &Path, branch: &str, create: bool) -> Result<WriteResult, GitError> {
    validate_branch_name(branch)?;
    let root = repo_root(dir).await?;
    let args: Vec<&str> = if create {
        vec!["switch", "-c", branch]
    } else {
        vec!["switch", branch]
    };
    let out = run_write(&root, &args, LOCAL_TIMEOUT).await?;
    finish(&root, out, true).await
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Branch {
    pub name: String,
    pub current: bool,
    pub upstream: Option<String>,
    pub time_ms: i64,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Branches {
    pub root: String,
    pub current: Option<String>,
    pub local: Vec<Branch>,
    /// Remote-tracking branches (`origin/x`) without a local branch of the same name.
    pub remote: Vec<Branch>,
}

pub fn parse_branches(root: &str, raw: &str) -> Branches {
    let mut local = Vec::new();
    let mut remote = Vec::new();
    for line in raw.lines() {
        let f: Vec<&str> = line.split('\x1f').collect();
        if f.len() < 4 {
            continue;
        }
        let (refname, upstream, head, time) = (f[0], f[1], f[2], f[3]);
        let time_ms = time.trim().parse::<i64>().unwrap_or(0) * 1000;
        if let Some(name) = refname.strip_prefix("refs/heads/") {
            local.push(Branch {
                name: name.to_string(),
                current: head.trim() == "*",
                upstream: (!upstream.is_empty()).then(|| upstream.to_string()),
                time_ms,
            });
        } else if let Some(name) = refname.strip_prefix("refs/remotes/") {
            if name.ends_with("/HEAD") {
                continue;
            }
            remote.push(Branch {
                name: name.to_string(),
                current: false,
                upstream: None,
                time_ms,
            });
        }
    }
    remote.retain(|r| {
        let short = r.name.split_once('/').map(|(_, b)| b).unwrap_or(&r.name);
        !local.iter().any(|l| l.name == short)
    });
    local.sort_by(|a, b| b.current.cmp(&a.current).then(b.time_ms.cmp(&a.time_ms)));
    remote.sort_by_key(|b| std::cmp::Reverse(b.time_ms));
    let current = local.iter().find(|b| b.current).map(|b| b.name.clone());
    Branches {
        root: root.to_string(),
        current,
        local,
        remote,
    }
}

pub async fn branches(dir: &Path) -> Result<Branches, GitError> {
    let root = repo_root(dir).await?;
    let (raw, _) = run(
        &root,
        &[
            "for-each-ref",
            "--format=%(refname)%1f%(upstream:short)%1f%(HEAD)%1f%(committerdate:unix)",
            "refs/heads",
            "refs/remotes",
        ],
        4 * 1024 * 1024,
    )
    .await?;
    Ok(parse_branches(&root.to_string_lossy(), &raw))
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

    #[test]
    fn rejects_paths_that_leave_the_repository() {
        for bad in [
            "../x",
            "a/../../x",
            "a\\..\\x",
            "/etc/passwd",
            "C:\\Windows",
            "c:/x",
            "-x",
            "--force",
            "",
            "a\0b",
            "a\nb",
        ] {
            assert!(validate_rel_path(bad).is_err(), "{bad:?} accepted");
        }
        for ok in [
            "a.txt",
            "src/lib.rs",
            "dir/",
            "a..b.txt",
            "中文/文件.md",
            ".github/x.yml",
        ] {
            assert!(validate_rel_path(ok).is_ok(), "{ok:?} refused");
        }
        assert!(validate_paths(&[]).is_err());
        for bad in [
            "-b", "a..b", "a b", "x.lock", "a/", "@", "a:b", "a~1", ".hidden", "a/.b", "x@{1}",
        ] {
            assert!(validate_branch_name(bad).is_err(), "{bad:?} accepted");
        }
        for ok in ["main", "feat/a", "fix-123", "release/v1.2"] {
            assert!(validate_branch_name(ok).is_ok(), "{ok:?} refused");
        }
    }

    #[test]
    fn wire_format_matches_the_shared_fixtures() {
        let root =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../protocol/fixtures");
        let read = |n: &str| -> serde_json::Value {
            serde_json::from_str(&std::fs::read_to_string(root.join(n)).unwrap()).unwrap()
        };
        let result = WriteResult {
            status: RepoStatus {
                root: "/home/u/project".into(),
                branch: Some("main".into()),
                upstream: Some("origin/main".into()),
                ahead: 1,
                behind: 0,
                entries: vec![StatusEntry {
                    path: "src/lib.rs".into(),
                    orig_path: None,
                    index: '.',
                    worktree: 'M',
                    untracked: false,
                    conflicted: false,
                }],
            },
            output: "[main 3f2a1c9] fix: thing\n 1 file changed, 2 insertions(+)".into(),
            head: Some("3f2a1c9d0e8b7a6f5e4d3c2b1a0f9e8d7c6b5a49".into()),
        };
        assert_eq!(
            serde_json::to_value(&result).unwrap(),
            read("git_write_result.json")
        );
        let b = parse_branches(
            "/home/u/project",
            "refs/heads/main\x1forigin/main\x1f*\x1f1700000100\n\
refs/heads/feat/a\x1f\x1f \x1f1700000200\n\
refs/remotes/origin/main\x1f\x1f \x1f1700000100\n\
refs/remotes/origin/other\x1f\x1f \x1f1700000300\n",
        );
        assert_eq!(serde_json::to_value(&b).unwrap(), read("git_branches.json"));
    }

    #[test]
    fn parses_branches() {
        let raw = "refs/heads/main\x1forigin/main\x1f*\x1f1700000100\n\
refs/heads/feat\x1f\x1f \x1f1700000200\n\
refs/remotes/origin/HEAD\x1f\x1f \x1f1700000000\n\
refs/remotes/origin/main\x1f\x1f \x1f1700000100\n\
refs/remotes/origin/other\x1f\x1f \x1f1700000300\n";
        let b = parse_branches("/r", raw);
        assert_eq!(b.current.as_deref(), Some("main"));
        assert_eq!(b.local.len(), 2);
        assert_eq!(b.local[0].name, "main");
        assert_eq!(b.local[0].upstream.as_deref(), Some("origin/main"));
        assert_eq!(b.local[1].upstream, None);
        assert_eq!(b.remote.len(), 1, "{:?}", b.remote);
        assert_eq!(b.remote[0].name, "origin/other");
    }

    fn sh_git(d: &Path, args: &[&str]) -> std::process::Output {
        let o = std::process::Command::new("git")
            .arg("-C")
            .arg(d)
            .args(args)
            .output()
            .unwrap();
        assert!(
            o.status.success(),
            "git {args:?}: {}",
            String::from_utf8_lossy(&o.stderr)
        );
        o
    }

    fn git_available() -> bool {
        std::process::Command::new("git")
            .arg("--version")
            .output()
            .is_ok()
    }

    fn init_repo(d: &Path) {
        sh_git(d, &["init", "-q", "-b", "main"]);
        sh_git(d, &["config", "user.email", "t@example.com"]);
        sh_git(d, &["config", "user.name", "T"]);
        sh_git(d, &["config", "commit.gpgsign", "false"]);
        sh_git(d, &["config", "core.autocrlf", "false"]);
    }

    fn entry<'a>(st: &'a RepoStatus, p: &str) -> Option<&'a StatusEntry> {
        st.entries.iter().find(|e| e.path == p)
    }

    #[tokio::test]
    async fn write_roundtrip_stage_unstage_discard_commit_branch() {
        if !git_available() {
            return;
        }
        let tmp = tempfile::tempdir().unwrap();
        let d = tmp.path();
        init_repo(d);
        // Unstage before the first commit uses `rm --cached`.
        std::fs::write(d.join("a.txt"), "one\n").unwrap();
        let r = stage(d, &["a.txt".into()]).await.unwrap();
        assert_eq!(entry(&r.status, "a.txt").unwrap().index, 'A');
        let r = unstage(d, &["a.txt".into()]).await.unwrap();
        assert!(entry(&r.status, "a.txt").unwrap().untracked);
        let r = stage_all(d).await.unwrap();
        assert_eq!(entry(&r.status, "a.txt").unwrap().index, 'A');
        let r = commit(d, "first").await.unwrap();
        assert!(r.status.entries.is_empty());
        assert_eq!(r.head.as_deref().map(str::len), Some(40));
        assert!(commit(d, "   ").await.is_err());

        // Modify + stage + unstage with HEAD present.
        std::fs::write(d.join("a.txt"), "one\ntwo\n").unwrap();
        let r = stage(d, &["a.txt".into()]).await.unwrap();
        assert_eq!(entry(&r.status, "a.txt").unwrap().index, 'M');
        let r = unstage(d, &["a.txt".into()]).await.unwrap();
        let e = entry(&r.status, "a.txt").unwrap();
        assert_eq!((e.index, e.worktree), ('.', 'M'));
        // Discard restores the tracked file.
        let r = discard(d, &["a.txt".into()]).await.unwrap();
        assert!(r.status.entries.is_empty());
        assert_eq!(std::fs::read_to_string(d.join("a.txt")).unwrap(), "one\n");
        // Staged-only changes are not discarded silently.
        std::fs::write(d.join("a.txt"), "staged\n").unwrap();
        stage(d, &["a.txt".into()]).await.unwrap();
        assert!(discard(d, &["a.txt".into()]).await.is_err());
        unstage(d, &["a.txt".into()]).await.unwrap();
        discard(d, &["a.txt".into()]).await.unwrap();
        // Unknown / clean paths are refused.
        assert!(discard(d, &["nope.txt".into()]).await.is_err());
        assert!(stage(d, &["../escape".into()]).await.is_err());

        // Untracked files go to the OS trash (when the environment has one).
        std::fs::write(d.join("junk.txt"), "x\n").unwrap();
        match discard(d, &["junk.txt".into()]).await {
            Ok(r) => {
                assert!(!d.join("junk.txt").exists());
                assert!(r.status.entries.is_empty());
            }
            Err(GitError::Failed(m)) => eprintln!("trash unavailable here: {m}"),
            Err(e) => panic!("{e}"),
        }

        // Branches: create, switch back, list.
        let r = checkout(d, "feat/x", true).await.unwrap();
        assert_eq!(r.status.branch.as_deref(), Some("feat/x"));
        let r = checkout(d, "main", false).await.unwrap();
        assert_eq!(r.status.branch.as_deref(), Some("main"));
        let b = branches(d).await.unwrap();
        assert_eq!(b.current.as_deref(), Some("main"));
        assert!(b.local.iter().any(|x| x.name == "feat/x"));
        assert!(checkout(d, "does-not-exist", false).await.is_err());
        assert!(checkout(d, "-f", false).await.is_err());
        // A dirty file that conflicts with the target branch blocks the switch.
        checkout(d, "feat/x", false).await.unwrap();
        std::fs::write(d.join("a.txt"), "feat\n").unwrap();
        stage_all(d).await.unwrap();
        commit(d, "feat change").await.unwrap();
        std::fs::write(d.join("a.txt"), "dirty\n").unwrap();
        let err = checkout(d, "main", false).await.unwrap_err();
        assert!(matches!(err, GitError::Failed(_)), "{err}");
        assert_eq!(std::fs::read_to_string(d.join("a.txt")).unwrap(), "dirty\n");
    }

    #[tokio::test]
    async fn push_pull_fetch_against_a_local_bare_remote() {
        if !git_available() {
            return;
        }
        let tmp = tempfile::tempdir().unwrap();
        let bare = tmp.path().join("remote.git");
        let a = tmp.path().join("a");
        let b = tmp.path().join("b");
        std::fs::create_dir_all(&bare).unwrap();
        std::fs::create_dir_all(&a).unwrap();
        sh_git(&bare, &["init", "-q", "--bare", "-b", "main"]);
        init_repo(&a);
        sh_git(&a, &["remote", "add", "origin", &bare.to_string_lossy()]);
        std::fs::write(a.join("f.txt"), "1\n").unwrap();
        stage_all(&a).await.unwrap();
        commit(&a, "one").await.unwrap();
        // No upstream yet: a plain push fails with git's message, -u works.
        assert!(push(&a, false, None).await.is_err());
        let r = push(&a, true, None).await.unwrap();
        assert_eq!(r.status.upstream.as_deref(), Some("origin/main"));
        assert_eq!(r.status.ahead, 0);

        let o = std::process::Command::new("git")
            .args(["clone", "-q"])
            .arg(&bare)
            .arg(&b)
            .output()
            .unwrap();
        assert!(o.status.success());
        sh_git(&b, &["config", "user.email", "t@example.com"]);
        sh_git(&b, &["config", "user.name", "T"]);
        sh_git(&b, &["config", "commit.gpgsign", "false"]);

        std::fs::write(a.join("f.txt"), "1\n2\n").unwrap();
        stage_all(&a).await.unwrap();
        let r = commit(&a, "two").await.unwrap();
        assert_eq!(r.status.ahead, 1);
        let r = push(&a, false, None).await.unwrap();
        assert_eq!(r.status.ahead, 0);

        let r = fetch(&b).await.unwrap();
        assert_eq!(r.status.behind, 1);
        let r = pull(&b).await.unwrap();
        assert_eq!(r.status.behind, 0);
        assert_eq!(
            std::fs::read_to_string(b.join("f.txt"))
                .unwrap()
                .replace("\r\n", "\n"),
            "1\n2\n"
        );

        // Diverged history: --ff-only refuses instead of merging.
        std::fs::write(a.join("f.txt"), "a\n").unwrap();
        stage_all(&a).await.unwrap();
        commit(&a, "a side").await.unwrap();
        push(&a, false, None).await.unwrap();
        std::fs::write(b.join("g.txt"), "b\n").unwrap();
        stage_all(&b).await.unwrap();
        commit(&b, "b side").await.unwrap();
        assert!(pull(&b).await.is_err());
    }
}
