//! Files the phone attaches to a message (any type: images, PDFs, logs, archives…).
//!
//! The phone uploads each file before sending (`POST /v1/sessions/{id}/attachments`, then raw
//! chunks); the message then names the attachment ids. Every agent (Claude Code, Codex,
//! DeepSeek Harness, managed or taken over) receives the files the same way: as absolute paths
//! in a marked block appended to the message text, so it reads them with its own tools. Images
//! are additionally inlined for agents that accept image input.
//!
//! Files live under SwitchTether's own data directory (`<data dir>/attachments/<date>/<id>/<name>`),
//! never in the user's project, and are removed after [`KEEP_DAYS`] days or when the total
//! exceeds [`MAX_TOTAL_BYTES`] (oldest days first). They are not removed when a session ends: the
//! conversation can refer to them again later.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use parking_lot::Mutex;
use serde::Serialize;
use serde_json::{json, Value};

use crate::error::{ApiError, ApiResult, ErrorCode};

/// One attachment: 1 GiB (a video, a disk image, a large log). Uploads stream to disk chunk by
/// chunk, so the size never sits in memory.
pub const MAX_ATTACHMENT_BYTES: u64 = 1024 * 1024 * 1024;
pub const MAX_PER_MESSAGE: usize = 10;
pub const MAX_CHUNK_BYTES: usize = 8 * 1024 * 1024;
const KEEP_DAYS: i64 = 7;
const MAX_TOTAL_BYTES: u64 = 4 * 1024 * 1024 * 1024;
/// Uploaded attachments stay addressable by id this long (a send can be retried).
const ID_TTL: Duration = Duration::from_secs(24 * 3600);
/// An upload that is not finished within this time is discarded. Long enough for 1 GiB over a
/// slow relay (about 50 KB/s).
const UPLOAD_TTL: Duration = Duration::from_secs(6 * 3600);

/// First line of the block appended to a message. It tells the agent what the paths are and lets
/// the timeline turn the block back into attachment chips.
pub const BLOCK_HEADER: &str =
    "[SwitchTether attachments: files the user sent from the phone, saved on this computer. Read them from these paths.]";

#[derive(Debug, Clone)]
pub struct Attachment {
    pub id: String,
    pub name: String,
    pub path: PathBuf,
    pub size: u64,
    pub media_type: String,
}

impl Attachment {
    pub fn is_image(&self) -> bool {
        matches!(
            self.media_type.as_str(),
            "image/png" | "image/jpeg" | "image/webp" | "image/gif"
        )
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AttachmentView {
    pub attachment_id: String,
    pub name: String,
    pub size: u64,
    pub received: u64,
    pub complete: bool,
    pub media_type: String,
}

struct Upload {
    device_id: String,
    session_id: String,
    att: Attachment,
    temp: PathBuf,
    received: u64,
    complete: bool,
    created: Instant,
}

impl Upload {
    fn view(&self) -> AttachmentView {
        AttachmentView {
            attachment_id: self.att.id.clone(),
            name: self.att.name.clone(),
            size: self.att.size,
            received: self.received,
            complete: self.complete,
            media_type: self.att.media_type.clone(),
        }
    }
}

pub struct AttachmentStore {
    root: PathBuf,
    uploads: Mutex<HashMap<String, Upload>>,
}

impl AttachmentStore {
    pub fn new(root: PathBuf) -> Self {
        Self {
            root,
            uploads: Mutex::new(HashMap::new()),
        }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    /// Starts an upload of `size` bytes for `session_id` by `device_id`.
    pub fn begin(
        &self,
        device_id: &str,
        session_id: &str,
        name: &str,
        size: u64,
        media_type: Option<&str>,
    ) -> ApiResult<AttachmentView> {
        if size == 0 {
            return Err(ApiError::validation("the file is empty"));
        }
        if size > MAX_ATTACHMENT_BYTES {
            return Err(ApiError::new(
                ErrorCode::PayloadTooLarge,
                format!(
                    "an attachment may be at most {} MiB",
                    MAX_ATTACHMENT_BYTES >> 20
                ),
            ));
        }
        self.expire();
        let id = crate::util::new_id();
        let name = safe_name(name);
        let dir = self
            .root
            .join(chrono::Local::now().format("%Y-%m-%d").to_string())
            .join(&id[..13]);
        std::fs::create_dir_all(&dir).map_err(|e| ApiError::internal("attachment dir", e))?;
        let temp = dir.join(".upload");
        std::fs::File::create(&temp).map_err(|e| ApiError::internal("attachment file", e))?;
        let att = Attachment {
            id: id.clone(),
            path: dir.join(&name),
            media_type: media_type
                .filter(|m| m.len() <= 100 && m.contains('/'))
                .map(str::to_string)
                .unwrap_or_else(|| guess_media_type(&name).to_string()),
            name,
            size,
        };
        let up = Upload {
            device_id: device_id.to_string(),
            session_id: session_id.to_string(),
            att,
            temp,
            received: 0,
            complete: false,
            created: Instant::now(),
        };
        let view = up.view();
        self.uploads.lock().insert(id, up);
        Ok(view)
    }

    /// Writes one chunk at `offset` (chunks arrive in order); the last one completes the file.
    pub fn chunk(
        &self,
        device_id: &str,
        session_id: &str,
        id: &str,
        offset: u64,
        data: &[u8],
    ) -> ApiResult<AttachmentView> {
        use std::io::{Seek, SeekFrom, Write};
        if data.len() > MAX_CHUNK_BYTES {
            return Err(ApiError::new(ErrorCode::PayloadTooLarge, "chunk too large"));
        }
        let mut ups = self.uploads.lock();
        let up = ups
            .get_mut(id)
            .filter(|u| u.device_id == device_id && u.session_id == session_id)
            .ok_or_else(|| ApiError::not_found("unknown attachment upload"))?;
        if up.complete {
            return Ok(up.view());
        }
        if offset != up.received {
            return Err(
                ApiError::conflict(format!("expected offset {}", up.received))
                    .with_details(json!({ "received": up.received })),
            );
        }
        if offset + data.len() as u64 > up.att.size {
            return Err(ApiError::validation("more data than the announced size"));
        }
        let mut f = std::fs::OpenOptions::new()
            .write(true)
            .open(&up.temp)
            .map_err(|e| ApiError::internal("attachment write", e))?;
        f.seek(SeekFrom::Start(offset))
            .and_then(|_| f.write_all(data))
            .map_err(|e| ApiError::internal("attachment write", e))?;
        up.received += data.len() as u64;
        if up.received == up.att.size {
            drop(f);
            std::fs::rename(&up.temp, &up.att.path)
                .map_err(|e| ApiError::internal("attachment finish", e))?;
            up.complete = true;
        }
        Ok(up.view())
    }

    /// Discards an upload (finished or not) of this device and session.
    pub fn cancel(&self, device_id: &str, session_id: &str, id: &str) {
        let mut ups = self.uploads.lock();
        if ups
            .get(id)
            .is_some_and(|u| u.device_id == device_id && u.session_id == session_id)
        {
            if let Some(u) = ups.remove(id) {
                if let Some(dir) = u.att.path.parent() {
                    let _ = std::fs::remove_dir_all(dir);
                }
            }
        }
    }

    /// Finished attachments named by a message, in order. Every id must be complete and belong
    /// to the same device and session.
    pub fn resolve(
        &self,
        device_id: &str,
        session_id: &str,
        ids: &[String],
    ) -> ApiResult<Vec<Attachment>> {
        if ids.len() > MAX_PER_MESSAGE {
            return Err(ApiError::validation(format!(
                "at most {MAX_PER_MESSAGE} attachments per message"
            )));
        }
        let ups = self.uploads.lock();
        ids.iter()
            .map(|id| {
                ups.get(id)
                    .filter(|u| u.device_id == device_id && u.session_id == session_id)
                    .filter(|u| u.complete && u.att.path.is_file())
                    .map(|u| u.att.clone())
                    .ok_or_else(|| {
                        ApiError::validation(format!("attachment {id} is unknown or not uploaded"))
                    })
            })
            .collect()
    }

    /// Drops stale uploads and ids; removes day folders older than [`KEEP_DAYS`] and, above
    /// [`MAX_TOTAL_BYTES`], the oldest days.
    fn expire(&self) {
        self.uploads.lock().retain(|_, u| {
            let keep = if u.complete {
                u.created.elapsed() < ID_TTL
            } else {
                u.created.elapsed() < UPLOAD_TTL
            };
            if !keep && !u.complete {
                if let Some(dir) = u.att.path.parent() {
                    let _ = std::fs::remove_dir_all(dir);
                }
            }
            keep
        });
        prune_days(
            &self.root,
            chrono::Local::now().date_naive(),
            KEEP_DAYS,
            MAX_TOTAL_BYTES,
        );
    }
}

/// Removes `<root>/<YYYY-MM-DD>` folders older than `keep_days`, then the oldest ones while the
/// total is above `max_total`. Today's folder is never removed. Anything else in `root` is left.
fn prune_days(root: &Path, today: chrono::NaiveDate, keep_days: i64, max_total: u64) {
    let Ok(rd) = std::fs::read_dir(root) else {
        return;
    };
    let mut days: Vec<(chrono::NaiveDate, PathBuf)> = rd
        .flatten()
        .filter(|e| e.file_type().is_ok_and(|t| t.is_dir()))
        .filter_map(|e| {
            let d = chrono::NaiveDate::parse_from_str(&e.file_name().to_string_lossy(), "%Y-%m-%d")
                .ok()?;
            Some((d, e.path()))
        })
        .collect();
    days.sort();
    days.retain(|(d, p)| {
        if (today - *d).num_days() > keep_days {
            let _ = std::fs::remove_dir_all(p);
            false
        } else {
            true
        }
    });
    let mut sizes: Vec<u64> = days.iter().map(|(_, p)| dir_size(p)).collect();
    let mut total: u64 = sizes.iter().sum();
    let mut i = 0;
    while total > max_total && i < days.len() && days[i].0 < today {
        let _ = std::fs::remove_dir_all(&days[i].1);
        total -= sizes[i];
        sizes[i] = 0;
        i += 1;
    }
}

fn dir_size(p: &Path) -> u64 {
    let Ok(rd) = std::fs::read_dir(p) else {
        return 0;
    };
    rd.flatten()
        .map(|e| match e.file_type() {
            Ok(t) if t.is_dir() => dir_size(&e.path()),
            Ok(_) => e.metadata().map(|m| m.len()).unwrap_or(0),
            Err(_) => 0,
        })
        .sum()
}

/// A file name that is safe on every platform and keeps the original's look and extension.
pub fn safe_name(raw: &str) -> String {
    let base = raw.rsplit(['/', '\\']).next().unwrap_or("").trim();
    let mut out: String = base
        .chars()
        .map(|c| match c {
            '<' | '>' | ':' | '"' | '|' | '?' | '*' => '_',
            c if c.is_control() => '_',
            c => c,
        })
        .collect();
    out = out.trim_matches(|c: char| c == '.' || c == ' ').to_string();
    let stem_upper = out.split('.').next().unwrap_or("").to_ascii_uppercase();
    let reserved = matches!(stem_upper.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || ((stem_upper.starts_with("COM") || stem_upper.starts_with("LPT"))
            && stem_upper.len() == 4
            && stem_upper.ends_with(|c: char| c.is_ascii_digit()));
    if out.is_empty() || reserved {
        out = format!(
            "file{}",
            if out.is_empty() {
                String::new()
            } else {
                format!("-{out}")
            }
        );
    }
    if out.len() > 120 {
        let ext = Path::new(&out)
            .extension()
            .map(|e| e.to_string_lossy().into_owned())
            .filter(|e| e.len() <= 16)
            .unwrap_or_default();
        let mut cut = 120 - ext.len() - 1;
        while !out.is_char_boundary(cut) {
            cut -= 1;
        }
        out = if ext.is_empty() {
            out[..cut].to_string()
        } else {
            format!("{}.{ext}", &out[..cut])
        };
    }
    out
}

fn guess_media_type(name: &str) -> &'static str {
    let ext = Path::new(name)
        .extension()
        .map(|e| e.to_string_lossy().to_ascii_lowercase())
        .unwrap_or_default();
    match ext.as_str() {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "webp" => "image/webp",
        "gif" => "image/gif",
        "pdf" => "application/pdf",
        "txt" | "log" | "md" => "text/plain",
        "json" => "application/json",
        "zip" => "application/zip",
        _ => "application/octet-stream",
    }
}

/// The message text an agent receives: the user's text, then the marked block of paths.
pub fn compose(text: &str, atts: &[Attachment]) -> String {
    if atts.is_empty() {
        return text.to_string();
    }
    let mut s = text.trim_end().to_string();
    if !s.is_empty() {
        s.push_str("\n\n");
    }
    s.push_str(BLOCK_HEADER);
    for a in atts {
        s.push_str("\n- ");
        s.push_str(&a.path.to_string_lossy());
    }
    s
}

/// Splits a message written by [`compose`] back into the user's text and `[{name, path}]`.
/// `None` when the text has no attachment block.
pub fn split(text: &str) -> Option<(String, Vec<Value>)> {
    let at = text.rfind(BLOCK_HEADER)?;
    let (before, block) = text.split_at(at);
    let files: Vec<Value> = block[BLOCK_HEADER.len()..]
        .lines()
        .filter_map(|l| l.trim().strip_prefix("- "))
        .map(str::trim)
        .filter(|p| !p.is_empty())
        .map(|p| {
            let name = p.rsplit(['/', '\\']).next().unwrap_or(p);
            json!({ "name": name, "path": p })
        })
        .collect();
    Some((before.trim_end().to_string(), files))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn upload_in_chunks_then_resolve_once_complete() {
        let tmp = tempfile::tempdir().unwrap();
        let store = AttachmentStore::new(tmp.path().to_path_buf());
        let v = store
            .begin("dev1", "claude:s", "../../报告 v2.pdf", 10, None)
            .unwrap();
        assert_eq!(v.name, "报告 v2.pdf", "path parts are dropped");
        assert_eq!(v.media_type, "application/pdf");
        let id = v.attachment_id.clone();
        assert!(
            store
                .resolve("dev1", "claude:s", std::slice::from_ref(&id))
                .is_err(),
            "not complete yet"
        );
        assert!(
            store.chunk("dev2", "claude:s", &id, 0, b"01234").is_err(),
            "other device"
        );
        assert!(
            store.chunk("dev1", "codex:x", &id, 0, b"01234").is_err(),
            "other session"
        );
        store.chunk("dev1", "claude:s", &id, 0, b"01234").unwrap();
        assert!(
            store.chunk("dev1", "claude:s", &id, 0, b"01234").is_err(),
            "wrong offset"
        );
        let done = store.chunk("dev1", "claude:s", &id, 5, b"56789").unwrap();
        assert!(done.complete);
        let atts = store
            .resolve("dev1", "claude:s", std::slice::from_ref(&id))
            .unwrap();
        assert_eq!(std::fs::read(&atts[0].path).unwrap(), b"0123456789");
        assert!(atts[0].path.starts_with(tmp.path()));
        assert!(store
            .resolve("dev1", "dsh:other", std::slice::from_ref(&id))
            .is_err());
        store.cancel("dev1", "claude:s", &id);
        assert!(!atts[0].path.exists());
        assert!(store
            .begin("dev1", "s", "big.bin", MAX_ATTACHMENT_BYTES + 1, None)
            .is_err());
        assert!(store.begin("dev1", "s", "empty.txt", 0, None).is_err());
    }

    #[test]
    fn compose_and_split_round_trip() {
        let a = Attachment {
            id: "1".into(),
            name: "log.txt".into(),
            path: PathBuf::from(
                r"C:\Users\me\.switchtether\remote\attachments\2026-11-18\abc\log.txt",
            ),
            size: 3,
            media_type: "text/plain".into(),
        };
        let text = compose("看看这个日志\n", std::slice::from_ref(&a));
        assert!(text.starts_with("看看这个日志\n\n[SwitchTether attachments:"));
        assert!(text.ends_with(r"\abc\log.txt"));
        let (user, files) = split(&text).unwrap();
        assert_eq!(user, "看看这个日志");
        assert_eq!(files[0]["name"], "log.txt");
        assert_eq!(compose("hi", &[]), "hi");
        assert!(split("no block").is_none());
        let only = compose("", &[a]);
        assert_eq!(split(&only).unwrap().0, "");
    }

    #[test]
    fn names_are_safe() {
        assert_eq!(safe_name("a<b>:c?.txt"), "a_b__c_.txt");
        assert_eq!(safe_name("CON.txt"), "file-CON.txt");
        assert_eq!(safe_name(" ... "), "file");
        let long = format!("{}.png", "x".repeat(300));
        let s = safe_name(&long);
        assert!(s.len() <= 120 && s.ends_with(".png"));
    }

    #[test]
    fn old_and_excess_days_are_pruned() {
        let tmp = tempfile::tempdir().unwrap();
        let today = chrono::NaiveDate::from_ymd_opt(2026, 11, 18).unwrap();
        for (d, bytes) in [
            ("2026-11-01", 10),
            ("2026-11-15", 50),
            ("2026-11-16", 50),
            ("2026-11-18", 50),
        ] {
            let dir = tmp.path().join(d).join("x");
            std::fs::create_dir_all(&dir).unwrap();
            std::fs::write(dir.join("f"), vec![0u8; bytes]).unwrap();
        }
        std::fs::create_dir_all(tmp.path().join("not-a-day")).unwrap();
        prune_days(tmp.path(), today, 7, 120);
        assert!(!tmp.path().join("2026-11-01").exists(), "older than a week");
        assert!(
            !tmp.path().join("2026-11-15").exists(),
            "oldest day over the cap"
        );
        assert!(tmp.path().join("2026-11-16").exists());
        assert!(tmp.path().join("2026-11-18").exists(), "today is kept");
        assert!(tmp.path().join("not-a-day").exists());
    }
}
