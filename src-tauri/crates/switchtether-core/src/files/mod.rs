//! Remote file operations with conflict detection.
//!
//! Writes are guarded by a content hash precondition (`baseSha256`): the file is
//! re-hashed immediately before writing and a mismatch returns a conflict with
//! the current content instead of silently overwriting what the IDE saved.
//! Writes are atomic (temp file in the same directory + rename) and preserve
//! the original encoding/BOM and Unix permissions.

pub mod policy;

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant, UNIX_EPOCH};

use parking_lot::Mutex;
use serde::{Deserialize, Serialize};

pub use policy::{Classification, PathError};

pub const DEFAULT_READ_LIMIT: u64 = 2 * 1024 * 1024;
pub const MAX_READ_LIMIT: u64 = 16 * 1024 * 1024;
pub const MAX_WRITE_BYTES: usize = 16 * 1024 * 1024;
pub const MAX_UPLOAD_BYTES: u64 = 2 * 1024 * 1024 * 1024;
pub const MAX_LIST_ENTRIES: usize = 5000;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    pub name: String,
    pub path: String,
    pub kind: &'static str,
    pub size: u64,
    pub modified_ms: Option<i64>,
    pub hidden: bool,
    pub sensitive: bool,
    pub symlink: bool,
    pub readonly: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Listing {
    pub path: String,
    pub parent: Option<String>,
    pub entries: Vec<Entry>,
    pub truncated: bool,
    /// Entries hidden because they are blocked by policy.
    pub blocked: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileContent {
    pub path: String,
    pub encoding: String,
    pub bom: bool,
    pub text: Option<String>,
    pub sha256: String,
    pub size: u64,
    pub truncated: bool,
    pub modified_ms: Option<i64>,
    pub line_ending: &'static str,
    pub sensitive: bool,
    pub language: Option<&'static str>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WriteRequest {
    pub path: String,
    pub text: String,
    /// SHA-256 of the content the edit was based on; `None` = create new file.
    pub base_sha256: Option<String>,
    /// Encoding label to write with (default: utf-8). BOM preserved via `bom`.
    #[serde(default)]
    pub encoding: Option<String>,
    #[serde(default)]
    pub bom: bool,
    #[serde(default)]
    pub create_only: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WriteOutcome {
    pub path: String,
    pub sha256: String,
    pub size: u64,
    pub created: bool,
}

#[derive(Debug)]
pub enum FsError {
    Path(PathError),
    Conflict {
        current_sha256: Option<String>,
        current_text: Option<String>,
        suggested_copy: String,
    },
    TooLarge,
    Invalid(String),
    Io(std::io::Error),
}

impl From<PathError> for FsError {
    fn from(e: PathError) -> Self {
        FsError::Path(e)
    }
}
impl From<std::io::Error> for FsError {
    fn from(e: std::io::Error) -> Self {
        FsError::Io(e)
    }
}

fn mtime_ms(m: &std::fs::Metadata) -> Option<i64> {
    m.modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64)
}

fn is_hidden(name: &str, _meta: &std::fs::Metadata) -> bool {
    if name.starts_with('.') {
        return true;
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        const FILE_ATTRIBUTE_HIDDEN: u32 = 0x2;
        if _meta.file_attributes() & FILE_ATTRIBUTE_HIDDEN != 0 {
            return true;
        }
    }
    false
}

pub fn language_for(path: &Path) -> Option<&'static str> {
    let name = path.file_name()?.to_string_lossy().to_lowercase();
    match name.as_str() {
        "dockerfile" => return Some("dockerfile"),
        "makefile" => return Some("makefile"),
        "cargo.lock" => return Some("toml"),
        _ => {}
    }
    let ext = path.extension()?.to_string_lossy().to_lowercase();
    Some(match ext.as_str() {
        "rs" => "rust",
        "kt" | "kts" => "kotlin",
        "java" => "java",
        "ts" | "tsx" | "mts" | "cts" => "typescript",
        "js" | "jsx" | "mjs" | "cjs" => "javascript",
        "py" => "python",
        "go" => "go",
        "c" | "h" => "c",
        "cc" | "cpp" | "cxx" | "hpp" | "hh" => "cpp",
        "cs" => "csharp",
        "swift" => "swift",
        "rb" => "ruby",
        "php" => "php",
        "json" | "jsonc" | "json5" => "json",
        "toml" => "toml",
        "yaml" | "yml" => "yaml",
        "xml" | "xaml" | "svg" | "plist" => "xml",
        "html" | "htm" => "html",
        "css" | "scss" | "less" => "css",
        "md" | "markdown" | "mdx" => "markdown",
        "sh" | "bash" | "zsh" | "fish" => "shell",
        "ps1" | "psm1" | "psd1" => "powershell",
        "bat" | "cmd" => "batch",
        "sql" => "sql",
        "gradle" => "groovy",
        "lua" => "lua",
        "dart" => "dart",
        "vue" => "vue",
        "svelte" => "svelte",
        "ini" | "cfg" | "conf" | "properties" | "env" => "ini",
        "diff" | "patch" => "diff",
        _ => return None,
    })
}

/// Decode bytes into text. Returns (encoding label, had BOM, text) or None for binary.
pub fn decode_text(bytes: &[u8], hint: Option<&str>) -> Option<(String, bool, String)> {
    if let Some(label) = hint.filter(|h| !h.eq_ignore_ascii_case("auto")) {
        let enc = encoding_rs::Encoding::for_label(label.as_bytes())?;
        let (text, _, malformed) = enc.decode(bytes);
        if malformed {
            return None;
        }
        return Some((enc.name().to_lowercase(), false, text.into_owned()));
    }
    if let Some((enc, bom_len)) = encoding_rs::Encoding::for_bom(bytes) {
        let (text, malformed) = enc.decode_without_bom_handling(&bytes[bom_len..]);
        if malformed {
            return None;
        }
        return Some((enc.name().to_lowercase(), true, text.into_owned()));
    }
    let probe = &bytes[..bytes.len().min(8192)];
    if probe.contains(&0) {
        return None;
    }
    match std::str::from_utf8(bytes) {
        Ok(s) => Some(("utf-8".into(), false, s.to_string())),
        Err(e) => {
            // A truncated read may cut a multi-byte character at the end.
            if e.error_len().is_none() && bytes.len() - e.valid_up_to() < 4 {
                let s = std::str::from_utf8(&bytes[..e.valid_up_to()]).ok()?;
                Some(("utf-8".into(), false, s.to_string()))
            } else {
                None
            }
        }
    }
}

fn encode_text(text: &str, label: Option<&str>, bom: bool) -> Result<Vec<u8>, FsError> {
    let enc = match label {
        None => encoding_rs::UTF_8,
        Some(l) => encoding_rs::Encoding::for_label(l.as_bytes())
            .ok_or_else(|| FsError::Invalid(format!("unknown encoding '{l}'")))?,
    };
    let mut out = Vec::new();
    if enc == encoding_rs::UTF_16LE {
        if bom {
            out.extend_from_slice(&[0xFF, 0xFE]);
        }
        for u in text.encode_utf16() {
            out.extend_from_slice(&u.to_le_bytes());
        }
        return Ok(out);
    }
    if enc == encoding_rs::UTF_16BE {
        if bom {
            out.extend_from_slice(&[0xFE, 0xFF]);
        }
        for u in text.encode_utf16() {
            out.extend_from_slice(&u.to_be_bytes());
        }
        return Ok(out);
    }
    if bom && enc == encoding_rs::UTF_8 {
        out.extend_from_slice(&[0xEF, 0xBB, 0xBF]);
    }
    let (bytes, _, unmappable) = enc.encode(text);
    if unmappable {
        return Err(FsError::Invalid(format!(
            "text contains characters that cannot be encoded as {}",
            enc.name()
        )));
    }
    out.extend_from_slice(&bytes);
    Ok(out)
}

fn line_ending(text: &str) -> &'static str {
    let crlf = text.matches("\r\n").count();
    let lf = text.matches('\n').count() - crlf;
    if crlf > lf {
        "crlf"
    } else {
        "lf"
    }
}

fn conflict_copy_name(path: &Path) -> String {
    let stem = path
        .file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_default();
    let ext = path
        .extension()
        .map(|e| format!(".{}", e.to_string_lossy()))
        .unwrap_or_default();
    let stamp = chrono::Local::now().format("%Y%m%d-%H%M%S");
    path.with_file_name(format!("{stem} (phone {stamp}){ext}"))
        .to_string_lossy()
        .into_owned()
}

struct UploadSession {
    device_id: String,
    final_path: PathBuf,
    temp_path: PathBuf,
    size: u64,
    received: u64,
    sha256: Option<String>,
    overwrite: bool,
    created: Instant,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadView {
    pub upload_id: String,
    pub path: String,
    pub size: u64,
    pub received: u64,
    pub complete: bool,
}

pub struct FileService {
    write_locks: Mutex<HashMap<PathBuf, Arc<tokio::sync::Mutex<()>>>>,
    uploads: Mutex<HashMap<String, UploadSession>>,
}

impl Default for FileService {
    fn default() -> Self {
        Self::new()
    }
}

impl FileService {
    pub fn new() -> Self {
        Self {
            write_locks: Mutex::new(HashMap::new()),
            uploads: Mutex::new(HashMap::new()),
        }
    }

    fn lock_for(&self, p: &Path) -> Arc<tokio::sync::Mutex<()>> {
        let mut m = self.write_locks.lock();
        if m.len() > 1024 {
            m.retain(|_, l| Arc::strong_count(l) > 1);
        }
        m.entry(p.to_path_buf()).or_default().clone()
    }

    pub fn roots(extra: &[String]) -> Vec<Entry> {
        let mut out = Vec::new();
        let mut add = |p: PathBuf, label: Option<&str>| {
            if let Ok(m) = std::fs::metadata(&p) {
                if m.is_dir() && !out.iter().any(|e: &Entry| e.path == p.to_string_lossy()) {
                    out.push(Entry {
                        name: label
                            .map(|l| l.to_string())
                            .unwrap_or_else(|| p.to_string_lossy().into_owned()),
                        path: p.to_string_lossy().into_owned(),
                        kind: "dir",
                        size: 0,
                        modified_ms: mtime_ms(&m),
                        hidden: false,
                        sensitive: false,
                        symlink: false,
                        readonly: m.permissions().readonly(),
                    });
                }
            }
        };
        for e in extra {
            add(PathBuf::from(e), None);
        }
        if let Some(h) = dirs::home_dir() {
            add(h.clone(), Some("Home"));
            for d in ["Desktop", "Documents", "Downloads", "Projects", "projects", "code", "src", "dev", "repos", "workspace"] {
                add(h.join(d), Some(d));
            }
        }
        #[cfg(windows)]
        for letter in b'C'..=b'Z' {
            let root = PathBuf::from(format!("{}:\\", letter as char));
            if root.exists() {
                add(root, None);
            }
        }
        #[cfg(not(windows))]
        add(PathBuf::from("/"), Some("/"));
        out
    }

    pub fn list(&self, raw: &str, show_hidden: bool) -> Result<Listing, FsError> {
        let (dir, _) = policy::resolve_existing(raw)?;
        if !dir.is_dir() {
            return Err(FsError::Invalid("not a directory".into()));
        }
        let mut entries = Vec::new();
        let mut blocked = 0;
        let mut truncated = false;
        for e in std::fs::read_dir(&dir)? {
            let Ok(e) = e else { continue };
            let name = e.file_name().to_string_lossy().into_owned();
            let path = e.path();
            let Ok(lmeta) = std::fs::symlink_metadata(&path) else { continue };
            let symlink = lmeta.file_type().is_symlink();
            let meta = if symlink {
                std::fs::metadata(&path).unwrap_or(lmeta.clone())
            } else {
                lmeta.clone()
            };
            let class = if symlink {
                policy::dunce_canonicalize(&path)
                    .map(|c| policy::classify(&c))
                    .unwrap_or(Classification::Normal)
                    .max_with(policy::classify(&path))
            } else {
                policy::classify(&path)
            };
            if class == Classification::Denied {
                blocked += 1;
                continue;
            }
            let hidden = is_hidden(&name, &lmeta);
            if hidden && !show_hidden {
                continue;
            }
            if entries.len() >= MAX_LIST_ENTRIES {
                truncated = true;
                break;
            }
            entries.push(Entry {
                kind: if meta.is_dir() { "dir" } else if meta.is_file() { "file" } else { "other" },
                size: if meta.is_file() { meta.len() } else { 0 },
                modified_ms: mtime_ms(&meta),
                hidden,
                sensitive: class == Classification::Sensitive,
                symlink,
                readonly: meta.permissions().readonly(),
                name,
                path: path.to_string_lossy().into_owned(),
            });
        }
        entries.sort_by(|a, b| {
            (a.kind != "dir")
                .cmp(&(b.kind != "dir"))
                .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
        });
        Ok(Listing {
            parent: dir.parent().map(|p| p.to_string_lossy().into_owned()),
            path: dir.to_string_lossy().into_owned(),
            entries,
            truncated,
            blocked,
        })
    }

    pub fn read(
        &self,
        raw: &str,
        max_bytes: Option<u64>,
        allow_sensitive: bool,
        encoding: Option<&str>,
    ) -> Result<FileContent, FsError> {
        let (path, class) = policy::resolve_existing(raw)?;
        if class == Classification::Sensitive && !allow_sensitive {
            return Err(FsError::Path(PathError::Io(
                "this file may contain secrets; confirm on the phone to open it".into(),
            )));
        }
        let meta = std::fs::metadata(&path)?;
        if !meta.is_file() {
            return Err(FsError::Invalid("not a regular file".into()));
        }
        let limit = max_bytes.unwrap_or(DEFAULT_READ_LIMIT).min(MAX_READ_LIMIT);
        let size = meta.len();
        let bytes = if size <= limit {
            std::fs::read(&path)?
        } else {
            use std::io::Read;
            let mut buf = vec![0u8; limit as usize];
            let mut f = std::fs::File::open(&path)?;
            f.read_exact(&mut buf)?;
            buf
        };
        let truncated = size > limit;
        // The version hash always covers the full file, never the truncated view.
        let sha = if truncated {
            hash_file(&path)?
        } else {
            crate::util::sha256_hex(&bytes)
        };
        let decoded = decode_text(&bytes, encoding);
        let (encoding, bom, text) = match decoded {
            Some((e, b, t)) => (e, b, Some(t)),
            None => ("binary".to_string(), false, None),
        };
        Ok(FileContent {
            path: path.to_string_lossy().into_owned(),
            line_ending: text.as_deref().map(line_ending).unwrap_or("lf"),
            encoding,
            bom,
            text,
            sha256: sha,
            size,
            truncated,
            modified_ms: mtime_ms(&meta),
            sensitive: class == Classification::Sensitive,
            language: language_for(&path),
        })
    }

    pub async fn write(&self, req: WriteRequest) -> Result<WriteOutcome, FsError> {
        if req.text.len() > MAX_WRITE_BYTES {
            return Err(FsError::TooLarge);
        }
        let (path, _) = policy::resolve_new(&req.path)?;
        let lock = self.lock_for(&path);
        let _guard = lock.lock().await;
        let p2 = path.clone();
        let base = req.base_sha256.clone();
        let create_only = req.create_only || base.is_none();
        let bytes = encode_text(&req.text, req.encoding.as_deref(), req.bom)?;
        tokio::task::spawn_blocking(move || write_checked(&p2, &bytes, base.as_deref(), create_only))
            .await
            .map_err(|e| FsError::Io(std::io::Error::other(e)))?
    }

    pub fn mkdir(&self, raw: &str) -> Result<String, FsError> {
        let (path, _) = policy::resolve_new(raw)?;
        std::fs::create_dir(&path)?;
        Ok(path.to_string_lossy().into_owned())
    }

    pub fn rename(&self, from: &str, to: &str, overwrite: bool) -> Result<String, FsError> {
        let (src, _) = policy::resolve_existing(from)?;
        let (dst, _) = policy::resolve_new(to)?;
        if dst.exists() && !overwrite {
            return Err(FsError::Conflict {
                current_sha256: None,
                current_text: None,
                suggested_copy: conflict_copy_name(&dst),
            });
        }
        std::fs::rename(&src, &dst)?;
        Ok(dst.to_string_lossy().into_owned())
    }

    /// Move to the OS trash / recycle bin (recoverable on the desktop).
    pub fn delete(&self, raw: &str, base_sha256: Option<&str>) -> Result<(), FsError> {
        let (path, _) = policy::resolve_existing(raw)?;
        if let Some(base) = base_sha256 {
            if path.is_file() {
                let cur = hash_file(&path)?;
                if cur != base {
                    return Err(FsError::Conflict {
                        current_sha256: Some(cur),
                        current_text: None,
                        suggested_copy: String::new(),
                    });
                }
            }
        }
        trash::delete(&path).map_err(|e| FsError::Io(std::io::Error::other(e.to_string())))
    }

    pub fn search(&self, root: &str, query: &str, content: bool, limit: usize) -> Result<Vec<serde_json::Value>, FsError> {
        let (root, _) = policy::resolve_existing(root)?;
        let q = query.to_lowercase();
        if q.trim().is_empty() {
            return Ok(Vec::new());
        }
        let limit = limit.clamp(1, 500);
        let deadline = Instant::now() + Duration::from_secs(4);
        let mut out = Vec::new();
        let mut stack = vec![(root, 0usize)];
        const SKIP: &[&str] = &[".git", "node_modules", "target", "build", "dist", ".gradle", ".idea", "__pycache__", ".venv", "venv", ".next", "Pods"];
        while let Some((dir, depth)) = stack.pop() {
            if out.len() >= limit || Instant::now() > deadline {
                break;
            }
            let Ok(rd) = std::fs::read_dir(&dir) else { continue };
            for e in rd.flatten() {
                let name = e.file_name().to_string_lossy().into_owned();
                let path = e.path();
                let Ok(ft) = e.file_type() else { continue };
                if policy::classify(&path) == Classification::Denied || ft.is_symlink() {
                    continue;
                }
                if ft.is_dir() {
                    if depth < 12 && !SKIP.contains(&name.as_str()) {
                        stack.push((path.clone(), depth + 1));
                    }
                    if name.to_lowercase().contains(&q) {
                        out.push(serde_json::json!({ "path": path.to_string_lossy(), "kind": "dir", "name": name }));
                    }
                } else if ft.is_file() {
                    if name.to_lowercase().contains(&q) {
                        out.push(serde_json::json!({ "path": path.to_string_lossy(), "kind": "file", "name": name }));
                    } else if content {
                        if let Some(hit) = content_hit(&path, &q) {
                            out.push(serde_json::json!({ "path": path.to_string_lossy(), "kind": "file", "name": name, "line": hit.0, "preview": hit.1 }));
                        }
                    }
                }
                if out.len() >= limit {
                    break;
                }
            }
        }
        Ok(out)
    }

    // ---- uploads ----

    pub fn begin_upload(
        &self,
        device_id: &str,
        raw: &str,
        size: u64,
        sha256: Option<String>,
        overwrite: bool,
    ) -> Result<UploadView, FsError> {
        if size > MAX_UPLOAD_BYTES {
            return Err(FsError::TooLarge);
        }
        let (path, _) = policy::resolve_new(raw)?;
        if path.exists() && !overwrite {
            return Err(FsError::Conflict {
                current_sha256: hash_file(&path).ok(),
                current_text: None,
                suggested_copy: conflict_copy_name(&path),
            });
        }
        let id = crate::util::new_id();
        let temp = path.with_file_name(format!(
            ".{}.switchtether-upload-{}",
            path.file_name().unwrap_or_default().to_string_lossy(),
            &id[..8]
        ));
        std::fs::File::create(&temp)?;
        let mut ups = self.uploads.lock();
        ups.retain(|_, u| {
            let keep = u.created.elapsed() < Duration::from_secs(3600);
            if !keep {
                let _ = std::fs::remove_file(&u.temp_path);
            }
            keep
        });
        ups.insert(
            id.clone(),
            UploadSession {
                device_id: device_id.to_string(),
                final_path: path.clone(),
                temp_path: temp,
                size,
                received: 0,
                sha256,
                overwrite,
                created: Instant::now(),
            },
        );
        Ok(UploadView {
            upload_id: id,
            path: path.to_string_lossy().into_owned(),
            size,
            received: 0,
            complete: size == 0,
        })
    }

    pub fn upload_chunk(
        &self,
        device_id: &str,
        upload_id: &str,
        offset: u64,
        data: &[u8],
    ) -> Result<UploadView, FsError> {
        use std::io::{Seek, SeekFrom, Write};
        let mut ups = self.uploads.lock();
        let u = ups
            .get_mut(upload_id)
            .filter(|u| u.device_id == device_id)
            .ok_or_else(|| FsError::Invalid("unknown upload".into()))?;
        if offset != u.received {
            return Err(FsError::Invalid(format!("expected offset {}", u.received)));
        }
        if offset + data.len() as u64 > u.size {
            return Err(FsError::TooLarge);
        }
        let mut f = std::fs::OpenOptions::new().write(true).open(&u.temp_path)?;
        f.seek(SeekFrom::Start(offset))?;
        f.write_all(data)?;
        u.received += data.len() as u64;
        let mut view = UploadView {
            upload_id: upload_id.to_string(),
            path: u.final_path.to_string_lossy().into_owned(),
            size: u.size,
            received: u.received,
            complete: false,
        };
        if u.received == u.size {
            f.sync_all()?;
            drop(f);
            let u = ups.remove(upload_id).expect("present");
            if let Some(expected) = &u.sha256 {
                let actual = hash_file(&u.temp_path)?;
                if !actual.eq_ignore_ascii_case(expected) {
                    let _ = std::fs::remove_file(&u.temp_path);
                    return Err(FsError::Invalid("checksum mismatch".into()));
                }
            }
            if u.final_path.exists() && !u.overwrite {
                let _ = std::fs::remove_file(&u.temp_path);
                return Err(FsError::Conflict {
                    current_sha256: hash_file(&u.final_path).ok(),
                    current_text: None,
                    suggested_copy: conflict_copy_name(&u.final_path),
                });
            }
            std::fs::rename(&u.temp_path, &u.final_path)?;
            view.complete = true;
        }
        Ok(view)
    }

    pub fn cancel_upload(&self, device_id: &str, upload_id: &str) {
        let mut ups = self.uploads.lock();
        if ups.get(upload_id).is_some_and(|u| u.device_id == device_id) {
            if let Some(u) = ups.remove(upload_id) {
                let _ = std::fs::remove_file(u.temp_path);
            }
        }
    }
}

trait MaxWith {
    fn max_with(self, other: Self) -> Self;
}
impl MaxWith for Classification {
    fn max_with(self, other: Self) -> Self {
        use Classification::*;
        match (self, other) {
            (Denied, _) | (_, Denied) => Denied,
            (Sensitive, _) | (_, Sensitive) => Sensitive,
            _ => Normal,
        }
    }
}

fn content_hit(path: &Path, q: &str) -> Option<(usize, String)> {
    let meta = std::fs::metadata(path).ok()?;
    if meta.len() > 1024 * 1024 || policy::classify(path) != Classification::Normal {
        return None;
    }
    let bytes = std::fs::read(path).ok()?;
    let (_, _, text) = decode_text(&bytes, None)?;
    for (i, line) in text.lines().enumerate() {
        if line.to_lowercase().contains(q) {
            return Some((i + 1, line.chars().take(200).collect()));
        }
    }
    None
}

pub fn hash_file(path: &Path) -> std::io::Result<String> {
    use sha2::{Digest, Sha256};
    use std::io::Read;
    let mut f = std::fs::File::open(path)?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(hex::encode(h.finalize()))
}

fn write_checked(
    path: &Path,
    bytes: &[u8],
    base: Option<&str>,
    create_only: bool,
) -> Result<WriteOutcome, FsError> {
    use std::io::Write;
    let exists = path.exists();
    if exists {
        if !path.is_file() {
            return Err(FsError::Invalid("target is not a regular file".into()));
        }
        let current = hash_file(path)?;
        let matches = base.is_some_and(|b| crate::util::ct_eq(&b.to_ascii_lowercase(), &current));
        if create_only || !matches {
            let current_text = std::fs::metadata(path)
                .ok()
                .filter(|m| m.len() <= DEFAULT_READ_LIMIT)
                .and_then(|_| std::fs::read(path).ok())
                .and_then(|b| decode_text(&b, None).map(|(_, _, t)| t));
            return Err(FsError::Conflict {
                current_sha256: Some(current),
                current_text,
                suggested_copy: conflict_copy_name(path),
            });
        }
    } else if base.is_some() {
        // The file was moved/deleted on the desktop since it was opened.
        return Err(FsError::Conflict {
            current_sha256: None,
            current_text: None,
            suggested_copy: conflict_copy_name(path),
        });
    }
    let dir = path.parent().ok_or_else(|| FsError::Invalid("no parent".into()))?;
    let tmp = dir.join(format!(
        ".{}.switchtether-{}.tmp",
        path.file_name().unwrap_or_default().to_string_lossy(),
        &crate::util::new_id()[..8]
    ));
    let res = (|| -> std::io::Result<()> {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
        drop(f);
        if exists {
            if let Ok(m) = std::fs::metadata(path) {
                let _ = std::fs::set_permissions(&tmp, m.permissions());
            }
        }
        std::fs::rename(&tmp, path)
    })();
    if let Err(e) = res {
        let _ = std::fs::remove_file(&tmp);
        return Err(FsError::Io(e));
    }
    Ok(WriteOutcome {
        path: path.to_string_lossy().into_owned(),
        sha256: crate::util::sha256_hex(bytes),
        size: bytes.len() as u64,
        created: !exists,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(dir: &Path, name: &str) -> String {
        policy::dunce_canonicalize(dir)
            .unwrap()
            .join(name)
            .to_string_lossy()
            .into_owned()
    }

    #[tokio::test]
    async fn write_read_conflict_cycle() {
        let tmp = tempfile::tempdir().unwrap();
        let fs = FileService::new();
        let path = p(tmp.path(), "a.txt");
        // create
        let w = fs
            .write(WriteRequest { path: path.clone(), text: "hello\n".into(), base_sha256: None, encoding: None, bom: false, create_only: true })
            .await
            .unwrap();
        assert!(w.created);
        let r = fs.read(&path, None, false, None).unwrap();
        assert_eq!(r.text.as_deref(), Some("hello\n"));
        assert_eq!(r.sha256, w.sha256);
        // desktop edits the file behind our back
        std::fs::write(&path, "desktop edit\n").unwrap();
        let err = fs
            .write(WriteRequest { path: path.clone(), text: "phone edit\n".into(), base_sha256: Some(r.sha256.clone()), encoding: None, bom: false, create_only: false })
            .await
            .unwrap_err();
        match err {
            FsError::Conflict { current_text, suggested_copy, current_sha256 } => {
                assert_eq!(current_text.as_deref(), Some("desktop edit\n"));
                assert!(suggested_copy.contains("(phone "));
                assert!(current_sha256.is_some());
            }
            e => panic!("expected conflict, got {e:?}"),
        }
        // desktop content untouched
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "desktop edit\n");
        // write with the right base succeeds
        let cur = fs.read(&path, None, false, None).unwrap();
        fs.write(WriteRequest { path: path.clone(), text: "merged\n".into(), base_sha256: Some(cur.sha256), encoding: None, bom: false, create_only: false })
            .await
            .unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "merged\n");
    }

    #[tokio::test]
    async fn create_only_never_overwrites() {
        let tmp = tempfile::tempdir().unwrap();
        let fs = FileService::new();
        let path = p(tmp.path(), "b.txt");
        std::fs::write(&path, "x").unwrap();
        assert!(matches!(
            fs.write(WriteRequest { path: path.clone(), text: "y".into(), base_sha256: None, encoding: None, bom: false, create_only: true }).await,
            Err(FsError::Conflict { .. })
        ));
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "x");
    }

    #[tokio::test]
    async fn deleted_file_is_a_conflict() {
        let tmp = tempfile::tempdir().unwrap();
        let fs = FileService::new();
        let path = p(tmp.path(), "gone.txt");
        assert!(matches!(
            fs.write(WriteRequest { path, text: "y".into(), base_sha256: Some("00".into()), encoding: None, bom: false, create_only: false }).await,
            Err(FsError::Conflict { .. })
        ));
    }

    #[test]
    fn encodings_roundtrip() {
        let utf16 = encode_text("héllo 中文", Some("utf-16le"), true).unwrap();
        let (enc, bom, text) = decode_text(&utf16, None).unwrap();
        assert_eq!((enc.as_str(), bom, text.as_str()), ("utf-16le", true, "héllo 中文"));
        let gbk = encode_text("中文", Some("gbk"), false).unwrap();
        assert!(decode_text(&gbk, None).is_none()); // not valid UTF-8 → binary unless hinted
        let (_, _, t) = decode_text(&gbk, Some("gbk")).unwrap();
        assert_eq!(t, "中文");
        assert!(decode_text(b"\x00\x01\x02", None).is_none());
    }

    #[test]
    fn list_hides_denied_and_hidden() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::write(tmp.path().join("visible.rs"), "fn main(){}").unwrap();
        std::fs::write(tmp.path().join(".hidden"), "").unwrap();
        std::fs::write(tmp.path().join(".env"), "A=1").unwrap();
        std::fs::write(tmp.path().join("id_ed25519"), "key").unwrap();
        let fs = FileService::new();
        let root = p(tmp.path(), "");
        let l = fs.list(&root, false).unwrap();
        let names: Vec<_> = l.entries.iter().map(|e| e.name.as_str()).collect();
        assert_eq!(names, vec!["visible.rs"]);
        assert_eq!(l.blocked, 1);
        let l = fs.list(&root, true).unwrap();
        let env = l.entries.iter().find(|e| e.name == ".env").unwrap();
        assert!(env.sensitive);
        // sensitive read requires opt-in
        assert!(fs.read(&env.path, None, false, None).is_err());
        assert!(fs.read(&env.path, None, true, None).is_ok());
        // denied file is never readable
        assert!(fs.read(&p(tmp.path(), "id_ed25519"), None, true, None).is_err());
    }

    #[test]
    fn chunked_upload_with_checksum() {
        let tmp = tempfile::tempdir().unwrap();
        let fs = FileService::new();
        let path = p(tmp.path(), "up.bin");
        let data = b"0123456789abcdef".repeat(100);
        let sha = crate::util::sha256_hex(&data);
        let u = fs.begin_upload("d", &path, data.len() as u64, Some(sha), false).unwrap();
        let v = fs.upload_chunk("d", &u.upload_id, 0, &data[..800]).unwrap();
        assert!(!v.complete);
        assert!(fs.upload_chunk("other", &u.upload_id, 800, &data[800..]).is_err());
        assert!(fs.upload_chunk("d", &u.upload_id, 0, &data[800..]).is_err());
        let v = fs.upload_chunk("d", &u.upload_id, 800, &data[800..]).unwrap();
        assert!(v.complete);
        assert_eq!(std::fs::read(&path).unwrap(), data);
    }

    #[test]
    fn search_finds_names_and_content() {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(tmp.path().join("src/node_modules")).unwrap();
        std::fs::write(tmp.path().join("src/lib.rs"), "pub fn needle_fn() {}").unwrap();
        std::fs::write(tmp.path().join("src/node_modules/needle.js"), "").unwrap();
        let fs = FileService::new();
        let root = p(tmp.path(), "");
        let r = fs.search(&root, "lib", false, 10).unwrap();
        assert_eq!(r.len(), 1);
        let r = fs.search(&root, "needle_fn", true, 10).unwrap();
        assert_eq!(r[0]["line"], 1);
        // node_modules skipped
        let r = fs.search(&root, "needle.js", false, 10).unwrap();
        assert!(r.is_empty());
    }
}
