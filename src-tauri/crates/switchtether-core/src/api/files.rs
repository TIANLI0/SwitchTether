//! File and Git endpoints. Reads need a paired device; file writes need the
//! `files_write` step-up grant, Git writes the `git_write` grant.

use std::path::PathBuf;
use std::sync::Arc;

use axum::body::{Body, Bytes};
use axum::extract::{DefaultBodyLimit, Path, Query, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};

use super::{idempotent, require_step_up, ApiContext, AuthDevice};
use crate::error::{ApiError, ApiResult, ErrorCode};
use crate::files::{FileService, FsError, PathError, WriteRequest};
use crate::stepup::Scope;

pub const MAX_CHUNK: usize = 8 * 1024 * 1024;

pub fn routes() -> Router<Arc<ApiContext>> {
    Router::new()
        .route("/v1/fs/roots", get(roots))
        .route("/v1/fs/list", get(list))
        .route("/v1/fs/read", get(read))
        .route("/v1/fs/search", get(search))
        .route("/v1/fs/download", get(download))
        .route("/v1/fs/write", put(write))
        .route("/v1/fs/mkdir", post(mkdir))
        .route("/v1/fs/rename", post(rename))
        .route("/v1/fs/delete", post(delete))
        .route("/v1/fs/upload", post(begin_upload))
        .route(
            "/v1/fs/upload/:id",
            put(upload_chunk)
                .delete(cancel_upload)
                .layer(DefaultBodyLimit::max(MAX_CHUNK + 1024)),
        )
        .route("/v1/git/status", get(git_status))
        .route("/v1/git/diff", get(git_diff))
        .route("/v1/git/log", get(git_log))
        .route("/v1/git/show", get(git_show))
        .route("/v1/git/branches", get(git_branches))
        .route("/v1/git/stage", post(git_stage))
        .route("/v1/git/unstage", post(git_unstage))
        .route("/v1/git/discard", post(git_discard))
        .route("/v1/git/commit", post(git_commit))
        .route("/v1/git/push", post(git_push))
        .route("/v1/git/pull", post(git_pull))
        .route("/v1/git/fetch", post(git_fetch))
        .route("/v1/git/checkout", post(git_checkout))
}

pub(crate) fn fs_err(e: FsError) -> ApiError {
    match e {
        FsError::Path(PathError::Denied) => ApiError::forbidden(PathError::Denied.to_string()),
        FsError::Path(PathError::NotFound) => ApiError::not_found("path does not exist"),
        FsError::Path(p @ (PathError::NotAbsolute | PathError::Invalid)) => ApiError::validation(p.to_string()),
        FsError::Path(PathError::Io(m)) => ApiError::forbidden(crate::redact::redact_text(&m)),
        FsError::Conflict { current_sha256, current_text, suggested_copy } => {
            ApiError::conflict("the file changed on the desktop since it was opened").with_details(json!({
                "currentSha256": current_sha256, "currentText": current_text, "suggestedCopyPath": suggested_copy,
            }))
        }
        FsError::TooLarge => ApiError::new(ErrorCode::PayloadTooLarge, "file too large"),
        FsError::Invalid(m) => ApiError::validation(m),
        FsError::Io(e) => match e.kind() {
            std::io::ErrorKind::PermissionDenied => ApiError::forbidden("permission denied by the operating system"),
            std::io::ErrorKind::NotFound => ApiError::not_found("path does not exist"),
            std::io::ErrorKind::AlreadyExists => ApiError::conflict("already exists"),
            _ => ApiError::internal("file io", e),
        },
    }
}

async fn blocking<T: Send + 'static>(
    f: impl FnOnce() -> Result<T, FsError> + Send + 'static,
) -> ApiResult<T> {
    tokio::task::spawn_blocking(f).await?.map_err(fs_err)
}

async fn roots(State(ctx): State<Arc<ApiContext>>, _d: AuthDevice) -> Json<Value> {
    // Suggest the working directories of known sessions first.
    let (sessions, _) = ctx
        .agents
        .list(&crate::agents::SessionQuery {
            limit: Some(20),
            ..Default::default()
        })
        .await;
    let mut extra: Vec<String> = sessions.into_iter().filter_map(|s| s.cwd).collect();
    extra.dedup();
    Json(json!({ "roots": FileService::roots(&extra) }))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ListQuery {
    path: String,
    #[serde(default)]
    show_hidden: bool,
}

async fn list(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(q): Query<ListQuery>,
) -> ApiResult<Json<Value>> {
    let c = ctx.clone();
    blocking(move || c.files.list(&q.path, q.show_hidden).map(|l| json!(l)))
        .await
        .map(Json)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReadQuery {
    path: String,
    max_bytes: Option<u64>,
    #[serde(default)]
    allow_sensitive: bool,
    encoding: Option<String>,
}

async fn read(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Query(q): Query<ReadQuery>,
) -> ApiResult<Json<Value>> {
    let c = ctx.clone();
    let path = q.path.clone();
    let r = blocking(move || {
        c.files
            .read(
                &q.path,
                q.max_bytes,
                q.allow_sensitive,
                q.encoding.as_deref(),
            )
            .map(|r| json!(r))
    })
    .await;
    if r.as_ref().is_ok_and(|v| v["sensitive"] == json!(true)) {
        ctx.registry
            .audit(Some(dev.id()), "fs.read_sensitive", Some(&path), "ok", None);
    }
    r.map(Json)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SearchQuery {
    root: String,
    q: String,
    #[serde(default)]
    content: bool,
    limit: Option<usize>,
}

async fn search(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(q): Query<SearchQuery>,
) -> ApiResult<Json<Value>> {
    let c = ctx.clone();
    blocking(move || {
        c.files
            .search(&q.root, &q.q, q.content, q.limit.unwrap_or(100))
            .map(|r| json!({ "results": r }))
    })
    .await
    .map(Json)
}

#[derive(Deserialize)]
struct DownloadQuery {
    path: String,
}

async fn download(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    headers: HeaderMap,
    Query(q): Query<DownloadQuery>,
) -> Response {
    let res = (|| -> Result<(PathBuf, u64), FsError> {
        let (p, class) = crate::files::policy::resolve_existing(&q.path)?;
        if class == crate::files::Classification::Sensitive {
            return Err(FsError::Path(PathError::Io(
                "sensitive files cannot be downloaded".into(),
            )));
        }
        let m = std::fs::metadata(&p)?;
        if !m.is_file() {
            return Err(FsError::Invalid("not a regular file".into()));
        }
        Ok((p, m.len()))
    })();
    let (path, size) = match res {
        Ok(v) => v,
        Err(e) => return fs_err(e).into_response(),
    };
    // Single range support: bytes=start-[end]
    let range = headers
        .get(header::RANGE)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("bytes="))
        .and_then(|r| {
            let (a, b) = r.split_once('-')?;
            let start: u64 = a.parse().ok()?;
            let end: u64 = if b.is_empty() {
                size.saturating_sub(1)
            } else {
                b.parse().ok()?
            };
            (start <= end && end < size).then_some((start, end))
        });
    let file = match tokio::fs::File::open(&path).await {
        Ok(f) => f,
        Err(e) => return fs_err(FsError::Io(e)).into_response(),
    };
    ctx.registry.audit(
        Some(dev.id()),
        "fs.download",
        Some(&q.path),
        "ok",
        Some(&format!("{size} bytes")),
    );
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let disposition = format!(
        "attachment; filename*=UTF-8''{}",
        percent_encoding::utf8_percent_encode(&name, percent_encoding::NON_ALPHANUMERIC)
    );
    use tokio::io::{AsyncReadExt, AsyncSeekExt};
    match range {
        Some((start, end)) => {
            let mut f = file;
            if f.seek(std::io::SeekFrom::Start(start)).await.is_err() {
                return ApiError::internal("seek", "failed").into_response();
            }
            let len = end - start + 1;
            let stream = tokio_util::io::ReaderStream::new(f.take(len));
            Response::builder()
                .status(StatusCode::PARTIAL_CONTENT)
                .header(header::CONTENT_TYPE, "application/octet-stream")
                .header(header::CONTENT_LENGTH, len)
                .header(header::CONTENT_RANGE, format!("bytes {start}-{end}/{size}"))
                .header(header::ACCEPT_RANGES, "bytes")
                .header(header::CONTENT_DISPOSITION, disposition)
                .body(Body::from_stream(stream))
                .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
        }
        None => Response::builder()
            .header(header::CONTENT_TYPE, "application/octet-stream")
            .header(header::CONTENT_LENGTH, size)
            .header(header::ACCEPT_RANGES, "bytes")
            .header(header::CONTENT_DISPOSITION, disposition)
            .body(Body::from_stream(tokio_util::io::ReaderStream::new(file)))
            .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response()),
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WriteBody {
    command_id: Option<String>,
    #[serde(flatten)]
    req: WriteRequest,
}

async fn write(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Json(body): Json<WriteBody>,
) -> Response {
    if let Err(e) = require_step_up(&ctx, &dev, Scope::FilesWrite) {
        return e.into_response();
    }
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "fs.write",
        || async move {
            let path = body.req.path.clone();
            let r = c.files.write(body.req).await;
            c.registry.audit(
                Some(dev.id()),
                "fs.write",
                Some(&path),
                if r.is_ok() { "ok" } else { "error" },
                None,
            );
            r.map(|o| json!(o)).map_err(fs_err)
        },
    )
    .await
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PathBody {
    command_id: Option<String>,
    path: String,
    base_sha256: Option<String>,
}

async fn mkdir(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Json(b): Json<PathBody>,
) -> Response {
    if let Err(e) = require_step_up(&ctx, &dev, Scope::FilesWrite) {
        return e.into_response();
    }
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        b.command_id,
        "fs.mkdir",
        || async move {
            let c2 = c.clone();
            let p = b.path.clone();
            let r = blocking(move || c2.files.mkdir(&p)).await?;
            c.registry
                .audit(Some(dev.id()), "fs.mkdir", Some(&b.path), "ok", None);
            Ok(json!({ "path": r }))
        },
    )
    .await
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RenameBody {
    command_id: Option<String>,
    from: String,
    to: String,
    #[serde(default)]
    overwrite: bool,
}

async fn rename(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Json(b): Json<RenameBody>,
) -> Response {
    if let Err(e) = require_step_up(&ctx, &dev, Scope::FilesWrite) {
        return e.into_response();
    }
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        b.command_id,
        "fs.rename",
        || async move {
            let c2 = c.clone();
            let (from, to) = (b.from.clone(), b.to.clone());
            let r = blocking(move || c2.files.rename(&from, &to, b.overwrite)).await?;
            c.registry.audit(
                Some(dev.id()),
                "fs.rename",
                Some(&format!("{} -> {}", b.from, b.to)),
                "ok",
                None,
            );
            Ok(json!({ "path": r }))
        },
    )
    .await
}

async fn delete(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Json(b): Json<PathBody>,
) -> Response {
    if let Err(e) = require_step_up(&ctx, &dev, Scope::FilesWrite) {
        return e.into_response();
    }
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        b.command_id,
        "fs.delete",
        || async move {
            let c2 = c.clone();
            let (p, base) = (b.path.clone(), b.base_sha256.clone());
            blocking(move || c2.files.delete(&p, base.as_deref())).await?;
            c.registry.audit(
                Some(dev.id()),
                "fs.delete",
                Some(&b.path),
                "ok",
                Some("moved to trash"),
            );
            Ok(json!({ "trashed": true }))
        },
    )
    .await
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct BeginUpload {
    command_id: Option<String>,
    path: String,
    size: u64,
    sha256: Option<String>,
    #[serde(default)]
    overwrite: bool,
}

async fn begin_upload(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Json(b): Json<BeginUpload>,
) -> Response {
    if let Err(e) = require_step_up(&ctx, &dev, Scope::FilesWrite) {
        return e.into_response();
    }
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        b.command_id,
        "fs.upload",
        || async move {
            let c2 = c.clone();
            let id = dev.id().to_string();
            let (p, sha) = (b.path.clone(), b.sha256.clone());
            let v =
                blocking(move || c2.files.begin_upload(&id, &p, b.size, sha, b.overwrite)).await?;
            c.registry.audit(
                Some(dev.id()),
                "fs.upload",
                Some(&b.path),
                "started",
                Some(&format!("{} bytes", b.size)),
            );
            Ok(json!(v))
        },
    )
    .await
}

#[derive(Deserialize)]
struct ChunkQuery {
    offset: u64,
}

async fn upload_chunk(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(id): Path<String>,
    Query(q): Query<ChunkQuery>,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    require_step_up(&ctx, &dev, Scope::FilesWrite)?;
    if body.len() > MAX_CHUNK {
        return Err(ApiError::new(ErrorCode::PayloadTooLarge, "chunk too large"));
    }
    let c = ctx.clone();
    let dev_id = dev.id().to_string();
    let v = blocking(move || c.files.upload_chunk(&dev_id, &id, q.offset, &body)).await?;
    if v.complete {
        ctx.registry.audit(
            Some(dev.id()),
            "fs.upload",
            Some(&v.path),
            "completed",
            None,
        );
    }
    Ok(Json(json!(v)))
}

async fn cancel_upload(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Path(id): Path<String>,
) -> Json<Value> {
    ctx.files.cancel_upload(dev.id(), &id);
    Json(json!({ "cancelled": true }))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GitQuery {
    repo: String,
    path: Option<String>,
    #[serde(default)]
    staged: bool,
    limit: Option<usize>,
    rev: Option<String>,
}

fn git_dir(raw: &str) -> ApiResult<PathBuf> {
    let (p, _) =
        crate::files::policy::resolve_existing(raw).map_err(|e| fs_err(FsError::Path(e)))?;
    Ok(p)
}

fn git_err(e: crate::git::GitError) -> ApiError {
    match e {
        crate::git::GitError::NotARepo => ApiError::not_found("not a git repository"),
        crate::git::GitError::NotInstalled => {
            ApiError::validation("git is not installed on the desktop")
        }
        crate::git::GitError::Timeout => ApiError::internal("git", "timeout"),
        crate::git::GitError::Failed(m) => ApiError::bad_request(crate::redact::redact_text(&m)),
    }
}

fn safe_rel(p: &Option<String>) -> ApiResult<()> {
    if let Some(p) = p {
        if p.contains("..") || p.starts_with('-') || p.contains('\0') {
            return Err(ApiError::validation("invalid path"));
        }
    }
    Ok(())
}

async fn git_status(
    State(_c): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(q): Query<GitQuery>,
) -> ApiResult<Json<Value>> {
    let dir = git_dir(&q.repo)?;
    crate::git::status(&dir)
        .await
        .map(|s| Json(json!(s)))
        .map_err(git_err)
}

async fn git_diff(
    State(_c): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(q): Query<GitQuery>,
) -> ApiResult<Json<Value>> {
    safe_rel(&q.path)?;
    let dir = git_dir(&q.repo)?;
    crate::git::diff(&dir, q.path.as_deref(), q.staged)
        .await
        .map(|s| Json(json!(s)))
        .map_err(git_err)
}

async fn git_log(
    State(_c): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(q): Query<GitQuery>,
) -> ApiResult<Json<Value>> {
    safe_rel(&q.path)?;
    let dir = git_dir(&q.repo)?;
    crate::git::log(&dir, q.path.as_deref(), q.limit.unwrap_or(50))
        .await
        .map(|c| Json(json!({ "commits": c })))
        .map_err(git_err)
}

async fn git_show(
    State(_c): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(q): Query<GitQuery>,
) -> ApiResult<Json<Value>> {
    safe_rel(&q.path)?;
    let dir = git_dir(&q.repo)?;
    let rev = q
        .rev
        .ok_or_else(|| ApiError::validation("rev is required"))?;
    let path = q
        .path
        .ok_or_else(|| ApiError::validation("path is required"))?;
    crate::git::show_file(&dir, &rev, &path)
        .await
        .map(|t| Json(json!({ "text": t, "binary": t.is_none() })))
        .map_err(git_err)
}

async fn git_branches(
    State(_c): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(q): Query<GitQuery>,
) -> ApiResult<Json<Value>> {
    let dir = git_dir(&q.repo)?;
    crate::git::branches(&dir)
        .await
        .map(|b| Json(json!(b)))
        .map_err(git_err)
}

/// Body of every Git write (`POST /v1/git/*`). Fields not used by an
/// operation are ignored.
#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct GitWriteBody {
    command_id: Option<String>,
    repo: String,
    #[serde(default)]
    paths: Vec<String>,
    /// stage: stage every change (`git add -A`) instead of `paths`.
    #[serde(default)]
    all: bool,
    message: Option<String>,
    #[serde(default)]
    set_upstream: bool,
    remote: Option<String>,
    branch: Option<String>,
    #[serde(default)]
    create: bool,
}

#[derive(Clone, Copy)]
enum GitOp {
    Stage,
    Unstage,
    Discard,
    Commit,
    Push,
    Pull,
    Fetch,
    Checkout,
}

impl GitOp {
    fn kind(self) -> &'static str {
        match self {
            GitOp::Stage => "git.stage",
            GitOp::Unstage => "git.unstage",
            GitOp::Discard => "git.discard",
            GitOp::Commit => "git.commit",
            GitOp::Push => "git.push",
            GitOp::Pull => "git.pull",
            GitOp::Fetch => "git.fetch",
            GitOp::Checkout => "git.checkout",
        }
    }
}

async fn git_write_op(
    ctx: Arc<ApiContext>,
    dev: AuthDevice,
    b: GitWriteBody,
    op: GitOp,
) -> Response {
    if let Err(e) = require_step_up(&ctx, &dev, Scope::GitWrite) {
        return e.into_response();
    }
    let c = ctx.clone();
    let command_id = b.command_id.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        command_id,
        op.kind(),
        || async move {
            let dir = git_dir(&b.repo)?;
            let res = match op {
                GitOp::Stage if b.all => crate::git::stage_all(&dir).await,
                GitOp::Stage => crate::git::stage(&dir, &b.paths).await,
                GitOp::Unstage => crate::git::unstage(&dir, &b.paths).await,
                GitOp::Discard => crate::git::discard(&dir, &b.paths).await,
                GitOp::Commit => crate::git::commit(&dir, b.message.as_deref().unwrap_or("")).await,
                GitOp::Push => crate::git::push(&dir, b.set_upstream, b.remote.as_deref()).await,
                GitOp::Pull => crate::git::pull(&dir).await,
                GitOp::Fetch => crate::git::fetch(&dir).await,
                GitOp::Checkout => {
                    let branch = b
                        .branch
                        .as_deref()
                        .ok_or_else(|| ApiError::validation("branch is required"))?;
                    crate::git::checkout(&dir, branch, b.create).await
                }
            };
            let detail = match op {
                GitOp::Stage if b.all => "all".to_string(),
                GitOp::Stage | GitOp::Unstage | GitOp::Discard => {
                    format!("{} path(s)", b.paths.len())
                }
                GitOp::Checkout => b.branch.clone().unwrap_or_default(),
                _ => String::new(),
            };
            match res {
                Ok(mut r) => {
                    r.output = crate::redact::redact_text(&r.output);
                    c.registry.audit(
                        Some(dev.id()),
                        op.kind(),
                        Some(&b.repo),
                        "ok",
                        Some(&detail),
                    );
                    Ok(json!(r))
                }
                Err(e) => {
                    c.registry.audit(
                        Some(dev.id()),
                        op.kind(),
                        Some(&b.repo),
                        "error",
                        Some(&detail),
                    );
                    Err(git_err(e))
                }
            }
        },
    )
    .await
}

macro_rules! git_write_handler {
    ($name:ident, $op:expr) => {
        async fn $name(
            State(ctx): State<Arc<ApiContext>>,
            dev: AuthDevice,
            Json(b): Json<GitWriteBody>,
        ) -> Response {
            git_write_op(ctx, dev, b, $op).await
        }
    };
}

git_write_handler!(git_stage, GitOp::Stage);
git_write_handler!(git_unstage, GitOp::Unstage);
git_write_handler!(git_discard, GitOp::Discard);
git_write_handler!(git_commit, GitOp::Commit);
git_write_handler!(git_push, GitOp::Push);
git_write_handler!(git_pull, GitOp::Pull);
git_write_handler!(git_fetch, GitOp::Fetch);
git_write_handler!(git_checkout, GitOp::Checkout);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn git_write_request_fixture_parses() {
        let root =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../protocol/fixtures");
        let b: GitWriteBody = serde_json::from_str(
            &std::fs::read_to_string(root.join("git_write_request.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(b.repo, "/home/u/project");
        assert_eq!(b.paths, vec!["src/lib.rs", "docs/new file.md"]);
        assert_eq!(b.message.as_deref(), Some("fix: thing"));
        assert!(!b.all && !b.set_upstream && !b.create);
        // Optional fields may be left out.
        let min: GitWriteBody = serde_json::from_str(r#"{"commandId":"x","repo":"/r"}"#).unwrap();
        assert!(min.paths.is_empty() && min.branch.is_none());
    }
}
