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
    if let Some(path) = crate::util::child_path() {
        c.env("PATH", path);
    }
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

#[derive(Debug, Clone, Serialize, PartialEq, Default)]
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
    /// Lines added / removed in the worktree (`git diff --numstat`); absent for binary files,
    /// untracked files and when nothing changed there.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub added: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub removed: Option<i64>,
    /// Lines added / removed in the index (`git diff --cached --numstat`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub staged_added: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub staged_removed: Option<i64>,
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
    /// A merge stopped on conflicts (`MERGE_HEAD` exists): resolve and commit, or abort.
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub merging: bool,
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
        merging: false,
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
                        ..Default::default()
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
                        ..Default::default()
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
                        ..Default::default()
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
                ..Default::default()
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
    let mut st = parse_status_v2(&root.to_string_lossy(), &raw);
    // Line counts are a nicety: a failure (e.g. no commit yet for --cached) leaves them out.
    let numstat = |cached: bool| {
        let root = root.clone();
        async move {
            let mut args = vec![
                "diff",
                "--numstat",
                "-z",
                "-M",
                "--no-ext-diff",
                "--no-textconv",
            ];
            if cached {
                args.push("--cached");
            }
            run(&root, &args, 4 * 1024 * 1024)
                .await
                .map(|(raw, _)| parse_numstat(&raw))
                .unwrap_or_default()
        }
    };
    let (work, index) = (numstat(false).await, numstat(true).await);
    for e in &mut st.entries {
        if let Some(&(a, r)) = work.get(&e.path) {
            e.added = a;
            e.removed = r;
        }
        if let Some(&(a, r)) = index.get(&e.path) {
            e.staged_added = a;
            e.staged_removed = r;
        }
    }
    st.merging = run(&root, &["rev-parse", "-q", "--verify", "MERGE_HEAD"], 1024)
        .await
        .is_ok();
    Ok(st)
}

/// `git diff --numstat -z`: path (the new path of a rename) → (added, removed); `None` for binary.
pub fn parse_numstat(raw: &str) -> std::collections::HashMap<String, (Option<i64>, Option<i64>)> {
    let mut out = std::collections::HashMap::new();
    let mut it = raw.split('\0');
    while let Some(rec) = it.next() {
        let rec = rec.trim_start_matches('\n');
        if rec.is_empty() {
            continue;
        }
        let mut f = rec.splitn(3, '\t');
        let (Some(a), Some(d), Some(path)) = (f.next(), f.next(), f.next()) else {
            continue;
        };
        let path = if path.is_empty() {
            // Rename / copy: "a\td\t\0old\0new\0".
            let _old = it.next();
            it.next().unwrap_or_default().to_string()
        } else {
            path.to_string()
        };
        out.insert(path, (a.parse().ok(), d.parse().ok()));
    }
    out
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

pub async fn log(
    dir: &Path,
    path: Option<&str>,
    limit: usize,
    skip: usize,
) -> Result<Vec<Commit>, GitError> {
    let root = repo_root(dir).await?;
    let n = format!("--max-count={}", limit.clamp(1, 500));
    let sk = format!("--skip={skip}");
    let mut args = vec![
        "log",
        n.as_str(),
        sk.as_str(),
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

fn validate_rev(rev: &str) -> Result<(), GitError> {
    if !rev.chars().all(|c| c.is_ascii_hexdigit()) || rev.len() < 4 || rev.len() > 64 {
        return Err(GitError::Failed("invalid revision".into()));
    }
    Ok(())
}

/// One file a commit changed (against its first parent).
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CommitFile {
    pub path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub orig_path: Option<String>,
    /// `A` / `M` / `D` / `R` / `C` / `T` (git's letter, score dropped).
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub added: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub removed: Option<i64>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CommitDetail {
    pub hash: String,
    pub author: String,
    pub email: String,
    pub time_ms: i64,
    pub subject: String,
    /// The message after the subject line (trimmed).
    pub body: String,
    pub parents: Vec<String>,
    pub files: Vec<CommitFile>,
}

/// `diff-tree --name-status -z` records: status, path (and the new path of a rename / copy).
pub fn parse_name_status(raw: &str) -> Vec<CommitFile> {
    let mut files = Vec::new();
    let mut it = raw.split('\0').map(|t| t.trim_start_matches('\n'));
    while let Some(st) = it.next() {
        if st.is_empty() {
            continue;
        }
        let letter: String = st.chars().take(1).collect();
        let Some(first) = it.next() else { break };
        let (path, orig_path) = if letter == "R" || letter == "C" {
            (
                it.next().unwrap_or_default().to_string(),
                Some(first.to_string()),
            )
        } else {
            (first.to_string(), None)
        };
        files.push(CommitFile {
            path,
            orig_path,
            status: letter,
            added: None,
            removed: None,
        });
    }
    files
}

/// The first parent of [rev], or None for a root commit.
async fn first_parent(root: &Path, rev: &str) -> Option<String> {
    let spec = format!("{rev}^1");
    run(root, &["rev-parse", "-q", "--verify", &spec], 1024)
        .await
        .ok()
        .map(|(h, _)| h.trim().to_string())
        .filter(|h| !h.is_empty())
}

/// A commit's message, parents and changed files with line counts (against its first parent;
/// a root commit against the empty tree). At most 2000 files.
pub async fn commit_detail(dir: &Path, rev: &str) -> Result<CommitDetail, GitError> {
    validate_rev(rev)?;
    let root = repo_root(dir).await?;
    let (meta, _) = run(
        &root,
        &[
            "log",
            "-1",
            "--format=%H%x1f%an%x1f%ae%x1f%at%x1f%P%x1f%s%x1f%b",
            rev,
            "--",
        ],
        256 * 1024,
    )
    .await?;
    let f: Vec<&str> = meta.splitn(7, '\x1f').collect();
    if f.len() < 7 {
        return Err(GitError::Failed("unknown revision".into()));
    }
    let parent = first_parent(&root, rev).await;
    let range: Vec<&str> = match &parent {
        Some(p) => vec![p.as_str(), rev],
        None => vec!["--root", rev],
    };
    let mut ns = vec![
        "diff-tree",
        "-r",
        "-M",
        "-z",
        "--no-commit-id",
        "--name-status",
    ];
    ns.extend(&range);
    let (raw, _) = run(&root, &ns, 4 * 1024 * 1024).await?;
    let mut files = parse_name_status(&raw);
    files.truncate(2000);
    let mut nm = vec!["diff-tree", "-r", "-M", "-z", "--no-commit-id", "--numstat"];
    nm.extend(&range);
    if let Ok((raw, _)) = run(&root, &nm, 4 * 1024 * 1024).await {
        let counts = parse_numstat(&raw);
        for file in &mut files {
            if let Some(&(a, r)) = counts.get(&file.path) {
                file.added = a;
                file.removed = r;
            }
        }
    }
    Ok(CommitDetail {
        hash: f[0].trim().to_string(),
        author: f[1].to_string(),
        email: f[2].to_string(),
        time_ms: f[3].trim().parse::<i64>().unwrap_or(0) * 1000,
        parents: f[4].split_whitespace().map(str::to_string).collect(),
        subject: f[5].trim_end().to_string(),
        body: f[6].trim().to_string(),
        files,
    })
}

/// The diff one commit made to [path] (and [orig_path] for a rename), against its first parent.
pub async fn commit_diff(
    dir: &Path,
    rev: &str,
    path: &str,
    orig_path: Option<&str>,
) -> Result<DiffResult, GitError> {
    validate_rev(rev)?;
    validate_rel_path(path)?;
    if let Some(o) = orig_path {
        validate_rel_path(o)?;
    }
    let root = repo_root(dir).await?;
    let parent = first_parent(&root, rev).await;
    let mut args = vec![
        "diff-tree",
        "-p",
        "-M",
        "--no-color",
        "--no-ext-diff",
        "--no-textconv",
        "--no-commit-id",
    ];
    match &parent {
        Some(p) => args.extend([p.as_str(), rev]),
        None => args.extend(["--root", rev]),
    }
    args.push("--");
    if let Some(o) = orig_path {
        args.push(o);
    }
    args.push(path);
    let (diff, truncated) = run(&root, &args, MAX_DIFF_BYTES).await?;
    Ok(DiffResult {
        root: root.to_string_lossy().into_owned(),
        diff,
        truncated,
    })
}

/// File content at a revision (for history view). Returns None for binary.
pub async fn show_file(dir: &Path, rev: &str, path: &str) -> Result<Option<String>, GitError> {
    validate_rev(rev)?;
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
    run_write_cmd(write_command(dir), args, timeout).await
}

async fn run_write_cmd(
    mut cmd: Command,
    args: &[&str],
    timeout: Duration,
) -> Result<String, GitError> {
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

pub async fn commit(dir: &Path, message: &str, amend: bool) -> Result<WriteResult, GitError> {
    let msg = message.trim();
    if msg.is_empty() {
        return Err(GitError::Failed("commit message is empty".into()));
    }
    if msg.len() > MAX_MESSAGE_BYTES || msg.contains('\0') {
        return Err(GitError::Failed("commit message is too long".into()));
    }
    let root = repo_root(dir).await?;
    let args: Vec<&str> = if amend {
        if !has_head(&root).await {
            return Err(GitError::Failed("nothing to amend: no commit yet".into()));
        }
        vec!["commit", "--amend", "-m", msg]
    } else {
        vec!["commit", "-m", msg]
    };
    let out = run_write(&root, &args, COMMIT_TIMEOUT).await?;
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

/// Delete a local branch: `branch -d` (git refuses an unmerged one; `force` → `-D`). The
/// current branch is never deleted.
pub async fn delete_branch(dir: &Path, branch: &str, force: bool) -> Result<WriteResult, GitError> {
    validate_branch_name(branch)?;
    let root = repo_root(dir).await?;
    if status(&root).await?.branch.as_deref() == Some(branch) {
        return Err(GitError::Failed(
            "the current branch cannot be deleted".into(),
        ));
    }
    let flag = if force { "-D" } else { "-d" };
    let out = run_write(&root, &["branch", flag, branch], LOCAL_TIMEOUT).await?;
    finish(&root, out, false).await
}

/// Merge [branch] (local or `remote/branch`) into the current branch, fast-forward when
/// possible, never opening an editor. On conflicts git stops with `MERGE_HEAD` set: the
/// error carries git's message and the status shows `merging` plus the conflicted files.
pub async fn merge(dir: &Path, branch: &str) -> Result<WriteResult, GitError> {
    validate_branch_name(branch)?;
    let root = repo_root(dir).await?;
    let st = status(&root).await?;
    if st.merging {
        return Err(GitError::Failed("a merge is already in progress".into()));
    }
    if st.branch.as_deref() == Some(branch) {
        return Err(GitError::Failed("cannot merge a branch into itself".into()));
    }
    let out = run_write(&root, &["merge", "--no-edit", branch], COMMIT_TIMEOUT).await?;
    finish(&root, out, true).await
}

/// `merge --abort`: back to the state before the merge.
pub async fn merge_abort(dir: &Path) -> Result<WriteResult, GitError> {
    let root = repo_root(dir).await?;
    let out = run_write(&root, &["merge", "--abort"], LOCAL_TIMEOUT).await?;
    finish(&root, out, true).await
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Stash {
    /// `0` for `stash@{0}`.
    pub index: u32,
    pub message: String,
    pub time_ms: i64,
}

pub fn parse_stashes(raw: &str) -> Vec<Stash> {
    raw.lines()
        .filter_map(|l| {
            let f: Vec<&str> = l.split('\x1f').collect();
            if f.len() < 3 {
                return None;
            }
            let index = f[0]
                .strip_prefix("stash@{")?
                .strip_suffix('}')?
                .parse()
                .ok()?;
            Some(Stash {
                index,
                time_ms: f[1].trim().parse::<i64>().unwrap_or(0) * 1000,
                message: f[2].to_string(),
            })
        })
        .collect()
}

pub async fn stashes(dir: &Path) -> Result<Vec<Stash>, GitError> {
    let root = repo_root(dir).await?;
    let (raw, _) = run(
        &root,
        &["stash", "list", "--format=%gd%x1f%ct%x1f%gs"],
        1024 * 1024,
    )
    .await?;
    Ok(parse_stashes(&raw))
}

/// `stash push --include-untracked [-m message]`.
pub async fn stash_push(dir: &Path, message: Option<&str>) -> Result<WriteResult, GitError> {
    let root = repo_root(dir).await?;
    let mut args = vec!["stash", "push", "--include-untracked"];
    let msg = message.map(str::trim).filter(|m| !m.is_empty());
    if let Some(m) = msg {
        if m.len() > MAX_MESSAGE_BYTES || m.contains('\0') {
            return Err(GitError::Failed("stash message is too long".into()));
        }
        args.extend(["-m", m]);
    }
    // No user paths here, and literal pathspecs keep `--include-untracked` from stashing
    // untracked files.
    let mut cmd = write_command(&root);
    cmd.env_remove("GIT_LITERAL_PATHSPECS");
    let out = run_write_cmd(cmd, &args, LOCAL_TIMEOUT).await?;
    finish(&root, out, false).await
}

/// `stash pop stash@{index}`; git keeps the stash when the pop conflicts.
pub async fn stash_pop(dir: &Path, index: u32) -> Result<WriteResult, GitError> {
    let root = repo_root(dir).await?;
    let spec = format!("stash@{{{index}}}");
    let out = run_write(&root, &["stash", "pop", &spec], LOCAL_TIMEOUT).await?;
    finish(&root, out, false).await
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Branch {
    pub name: String,
    pub current: bool,
    pub upstream: Option<String>,
    pub time_ms: i64,
    /// Commits ahead of / behind the upstream (local branches with one).
    #[serde(skip_serializing_if = "is_zero")]
    pub ahead: i64,
    #[serde(skip_serializing_if = "is_zero")]
    pub behind: i64,
    /// The upstream branch was deleted on the remote.
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub gone: bool,
}

fn is_zero(n: &i64) -> bool {
    *n == 0
}

/// `%(upstream:track,nobracket)`: "ahead 1, behind 2", "gone" or "".
fn parse_track(t: &str) -> (i64, i64, bool) {
    let (mut ahead, mut behind) = (0, 0);
    for part in t.split(',') {
        let part = part.trim();
        if let Some(n) = part.strip_prefix("ahead ") {
            ahead = n.trim().parse().unwrap_or(0);
        } else if let Some(n) = part.strip_prefix("behind ") {
            behind = n.trim().parse().unwrap_or(0);
        }
    }
    (ahead, behind, t.trim() == "gone")
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
        let (ahead, behind, gone) = parse_track(f.get(4).copied().unwrap_or(""));
        if let Some(name) = refname.strip_prefix("refs/heads/") {
            local.push(Branch {
                name: name.to_string(),
                current: head.trim() == "*",
                upstream: (!upstream.is_empty()).then(|| upstream.to_string()),
                time_ms,
                ahead,
                behind,
                gone,
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
                ahead: 0,
                behind: 0,
                gone: false,
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
            "--format=%(refname)%1f%(upstream:short)%1f%(HEAD)%1f%(committerdate:unix)%1f%(upstream:track,nobracket)",
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
        let lg = log(d, Some("a.txt"), 10, 0).await.unwrap();
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
                merging: false,
                entries: vec![StatusEntry {
                    path: "src/lib.rs".into(),
                    orig_path: None,
                    index: '.',
                    worktree: 'M',
                    untracked: false,
                    conflicted: false,
                    ..Default::default()
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
        let detail = CommitDetail {
            hash: "3f2a1c9d0e8b7a6f5e4d3c2b1a0f9e8d7c6b5a49".into(),
            author: "Ada".into(),
            email: "ada@example.com".into(),
            time_ms: 1_700_000_000_000,
            subject: "fix: thing".into(),
            body: "Longer explanation.".into(),
            parents: vec!["0a1b2c3d4e5f60718293a4b5c6d7e8f901234567".into()],
            files: vec![
                CommitFile {
                    path: "src/lib.rs".into(),
                    orig_path: None,
                    status: "M".into(),
                    added: Some(3),
                    removed: Some(1),
                },
                CommitFile {
                    path: "docs/new.md".into(),
                    orig_path: Some("docs/old.md".into()),
                    status: "R".into(),
                    added: Some(0),
                    removed: Some(0),
                },
                CommitFile {
                    path: "img.png".into(),
                    orig_path: None,
                    status: "A".into(),
                    added: None,
                    removed: None,
                },
            ],
        };
        assert_eq!(
            serde_json::to_value(&detail).unwrap(),
            read("git_commit_detail.json")
        );
        let stashes = parse_stashes("stash@{0}\x1f1700000000\x1fOn main: wip\n");
        assert_eq!(
            serde_json::json!({ "stashes": stashes }),
            read("git_stashes.json")
        );
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
        let r = commit(d, "first", false).await.unwrap();
        assert!(r.status.entries.is_empty());
        assert_eq!(r.head.as_deref().map(str::len), Some(40));
        assert!(commit(d, "   ", false).await.is_err());

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
        commit(d, "feat change", false).await.unwrap();
        std::fs::write(d.join("a.txt"), "dirty\n").unwrap();
        let err = checkout(d, "main", false).await.unwrap_err();
        assert!(matches!(err, GitError::Failed(_)), "{err}");
        assert_eq!(std::fs::read_to_string(d.join("a.txt")).unwrap(), "dirty\n");
    }

    #[test]
    fn parses_numstat_name_status_stashes_and_tracking() {
        let n = parse_numstat("3\t1\tsrc/a.rs\0-\t-\timg.png\0\x32\t0\t\0old.rs\0new.rs\0");
        assert_eq!(n.get("src/a.rs"), Some(&(Some(3), Some(1))));
        assert_eq!(n.get("img.png"), Some(&(None, None)));
        assert_eq!(n.get("new.rs"), Some(&(Some(2), Some(0))));
        let f = parse_name_status("M\0src/a.rs\0R087\0old.rs\0new.rs\0A\0b.txt\0");
        assert_eq!(f.len(), 3);
        assert_eq!(
            (
                f[1].status.as_str(),
                f[1].path.as_str(),
                f[1].orig_path.as_deref()
            ),
            ("R", "new.rs", Some("old.rs"))
        );
        assert_eq!(f[2].status, "A");
        let st = parse_stashes(
            "stash@{0}\x1f1700000000\x1fOn main: wip
stash@{1}\x1f1690000000\x1fWIP on main: x
",
        );
        assert_eq!(st.len(), 2);
        assert_eq!(
            (st[1].index, st[0].message.as_str(), st[0].time_ms),
            (1, "On main: wip", 1_700_000_000_000)
        );
        assert_eq!(parse_track("ahead 2, behind 3"), (2, 3, false));
        assert_eq!(parse_track("behind 1"), (0, 1, false));
        assert_eq!(parse_track("gone"), (0, 0, true));
        assert_eq!(parse_track(""), (0, 0, false));
    }

    #[tokio::test]
    async fn manage_roundtrip_counts_detail_amend_delete_merge_stash() {
        if !git_available() {
            return;
        }
        let tmp = tempfile::tempdir().unwrap();
        let d = tmp.path();
        init_repo(d);
        std::fs::write(
            d.join("a.txt"),
            "one
",
        )
        .unwrap();
        stage_all(d).await.unwrap();
        let first = commit(d, "first", false).await.unwrap().head.unwrap();
        // Per-file line counts, worktree and index.
        std::fs::write(
            d.join("a.txt"),
            "one
two
three
",
        )
        .unwrap();
        let st = status(d).await.unwrap();
        let e = entry(&st, "a.txt").unwrap();
        assert_eq!(
            (e.added, e.removed, e.staged_added),
            (Some(2), Some(0), None)
        );
        stage_all(d).await.unwrap();
        let st = status(d).await.unwrap();
        assert_eq!(entry(&st, "a.txt").unwrap().staged_added, Some(2));
        let second = commit(d, "second", false).await.unwrap().head.unwrap();
        // Amend replaces the last commit's message.
        let amended = commit(d, "second, amended", true)
            .await
            .unwrap()
            .head
            .unwrap();
        assert_ne!(amended, second);
        let lg = log(d, None, 10, 0).await.unwrap();
        assert_eq!(lg[0].subject, "second, amended");
        assert_eq!(log(d, None, 10, 1).await.unwrap()[0].hash, first);
        // Commit detail and the file's diff in that commit; the root commit too.
        let det = commit_detail(d, &amended).await.unwrap();
        assert_eq!(det.parents, vec![first.clone()]);
        assert_eq!(det.files.len(), 1);
        assert_eq!(
            (det.files[0].status.as_str(), det.files[0].added),
            ("M", Some(2))
        );
        let diff = commit_diff(d, &amended, "a.txt", None).await.unwrap();
        assert!(diff.diff.contains("+three"), "{}", diff.diff);
        let root = commit_detail(d, &first).await.unwrap();
        assert!(root.parents.is_empty());
        assert_eq!(root.files[0].status, "A");
        assert!(commit_diff(d, &first, "a.txt", None)
            .await
            .unwrap()
            .diff
            .contains("+one"));
        assert!(commit_detail(d, "not-a-rev").await.is_err());
        // Branch delete: never the current one; an unmerged one needs force.
        checkout(d, "side", true).await.unwrap();
        std::fs::write(
            d.join("a.txt"),
            "side
",
        )
        .unwrap();
        stage_all(d).await.unwrap();
        commit(d, "side change", false).await.unwrap();
        assert!(delete_branch(d, "side", false).await.is_err());
        checkout(d, "main", false).await.unwrap();
        assert!(
            delete_branch(d, "side", false).await.is_err(),
            "unmerged branch deleted without force"
        );
        // Merge with a conflict stops in a merge; abort restores main.
        std::fs::write(
            d.join("a.txt"),
            "main
",
        )
        .unwrap();
        stage_all(d).await.unwrap();
        commit(d, "main change", false).await.unwrap();
        assert!(merge(d, "side").await.is_err());
        let st = status(d).await.unwrap();
        assert!(st.merging && st.entries.iter().any(|e| e.conflicted));
        let r = merge_abort(d).await.unwrap();
        assert!(!r.status.merging && r.status.entries.is_empty());
        assert!(
            merge(d, "main").await.is_err(),
            "merged a branch into itself"
        );
        // A merged branch is deleted without force.
        checkout(d, "done", true).await.unwrap();
        checkout(d, "main", false).await.unwrap();
        delete_branch(d, "done", false).await.unwrap();
        delete_branch(d, "side", true).await.unwrap();
        assert!(!branches(d)
            .await
            .unwrap()
            .local
            .iter()
            .any(|b| b.name == "side" || b.name == "done"));
        // Stash push / list / pop, untracked files included.
        std::fs::write(
            d.join("a.txt"),
            "wip
",
        )
        .unwrap();
        std::fs::write(
            d.join("new.txt"),
            "new
",
        )
        .unwrap();
        let r = stash_push(d, Some("wip")).await.unwrap();
        assert!(r.status.entries.is_empty(), "{:?}", r.status.entries);
        let list = stashes(d).await.unwrap();
        assert_eq!(list.len(), 1);
        assert!(list[0].message.contains("wip"));
        let r = stash_pop(d, 0).await.unwrap();
        assert!(entry(&r.status, "new.txt").is_some());
        assert!(stashes(d).await.unwrap().is_empty());
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
        commit(&a, "one", false).await.unwrap();
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
        let r = commit(&a, "two", false).await.unwrap();
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
        commit(&a, "a side", false).await.unwrap();
        push(&a, false, None).await.unwrap();
        std::fs::write(b.join("g.txt"), "b\n").unwrap();
        stage_all(&b).await.unwrap();
        commit(&b, "b side", false).await.unwrap();
        assert!(pull(&b).await.is_err());
    }
}
