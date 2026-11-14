//! One-time, non-destructive import of an existing CC Switch installation.
//!
//! SwitchTether keeps its data in `~/.switchtether` (see
//! [`crate::config::APP_DIR_NAME`]). On the very first launch — when no
//! SwitchTether database exists yet — we *copy* the upstream CC Switch data so
//! users keep their providers, usage history, MCP servers, prompts and skills.
//!
//! Guarantees:
//! - The upstream `~/.cc-switch` directory is opened read-only and never modified.
//! - The database is copied with SQLite's online backup API (consistent even if
//!   CC Switch is running), integrity-checked, then atomically renamed into place.
//! - An existing SwitchTether database is never overwritten.
//! - Databases whose schema is newer than this build supports are not imported.
//! - OAuth account stores (`*_accounts.json`, `codex_oauth_auth.json`, ...) are
//!   intentionally NOT copied: two apps refreshing the same rotating refresh
//!   token would invalidate each other. Users sign in again in SwitchTether.
//! - A receipt (`upstream-import.json`) records what happened, and its presence
//!   prevents repeated imports. Rollback = remove `~/.switchtether` (the source
//!   is untouched).
//! - `SWITCHTETHER_SKIP_UPSTREAM_IMPORT=1` disables the import.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use rusqlite::{Connection, OpenFlags};
use serde::{Deserialize, Serialize};

pub const RECEIPT_FILE: &str = "upstream-import.json";
const DB_FILE: &str = "cc-switch.db";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ImportOutcome {
    Imported,
    SkippedNoSource,
    SkippedTargetExists,
    SkippedAlreadyAttempted,
    SkippedDisabled,
    SkippedSourceSchemaTooNew,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportReceipt {
    pub outcome: ImportOutcome,
    pub source_dir: String,
    pub imported_at: String,
    pub source_schema_version: Option<i64>,
    pub copied: Vec<String>,
    pub error: Option<String>,
}

/// Items copied besides the database. Directories are copied recursively;
/// symlinks are skipped (never followed) to avoid escaping the source tree.
const EXTRA_ITEMS: &[&str] = &["settings.json", "skills"];

/// Run the import if appropriate. Returns `None` when nothing was attempted
/// (e.g. target already populated), or the receipt that was written.
pub fn import_if_first_run(source_dir: &Path, target_dir: &Path) -> Option<ImportReceipt> {
    if std::env::var_os("SWITCHTETHER_SKIP_UPSTREAM_IMPORT").is_some() {
        return None;
    }
    if target_dir.join(RECEIPT_FILE).exists() {
        return None;
    }
    if target_dir.join(DB_FILE).exists() {
        return None;
    }
    let source_db = source_dir.join(DB_FILE);
    if !source_db.is_file() {
        return None;
    }
    let receipt = run_import(
        source_dir,
        target_dir,
        crate::database::SCHEMA_VERSION as i64,
    );
    if let Err(e) = write_receipt(target_dir, &receipt) {
        log::warn!("[switchtether] failed to write import receipt: {e}");
    }
    Some(receipt)
}

fn now_rfc3339() -> String {
    chrono::Utc::now().to_rfc3339()
}

fn write_receipt(target_dir: &Path, receipt: &ImportReceipt) -> std::io::Result<()> {
    fs::create_dir_all(target_dir)?;
    let json = serde_json::to_vec_pretty(receipt).map_err(std::io::Error::other)?;
    fs::write(target_dir.join(RECEIPT_FILE), json)
}

/// Core import logic (separated for testing; does not consult env vars).
pub fn run_import(source_dir: &Path, target_dir: &Path, supported_schema: i64) -> ImportReceipt {
    let mut receipt = ImportReceipt {
        outcome: ImportOutcome::Failed,
        source_dir: source_dir.display().to_string(),
        imported_at: now_rfc3339(),
        source_schema_version: None,
        copied: Vec::new(),
        error: None,
    };
    if target_dir.join(DB_FILE).exists() {
        receipt.outcome = ImportOutcome::SkippedTargetExists;
        return receipt;
    }
    let source_db = source_dir.join(DB_FILE);
    if !source_db.is_file() {
        receipt.outcome = ImportOutcome::SkippedNoSource;
        return receipt;
    }

    match copy_database(&source_db, target_dir, supported_schema) {
        Ok(version) => {
            receipt.source_schema_version = Some(version);
            receipt.copied.push(DB_FILE.to_string());
        }
        Err(CopyError::SchemaTooNew(version)) => {
            receipt.source_schema_version = Some(version);
            receipt.outcome = ImportOutcome::SkippedSourceSchemaTooNew;
            receipt.error = Some(format!(
                "source schema v{version} is newer than supported v{supported_schema}"
            ));
            return receipt;
        }
        Err(CopyError::Other(e)) => {
            receipt.error = Some(e);
            return receipt;
        }
    }

    for item in EXTRA_ITEMS {
        let src = source_dir.join(item);
        let dst = target_dir.join(item);
        if dst.exists() {
            continue;
        }
        let meta = match fs::symlink_metadata(&src) {
            Ok(m) => m,
            Err(_) => continue,
        };
        let result = if meta.file_type().is_symlink() {
            continue;
        } else if meta.is_dir() {
            copy_dir_no_symlinks(&src, &dst)
        } else {
            fs::copy(&src, &dst).map(|_| ())
        };
        match result {
            Ok(()) => receipt.copied.push((*item).to_string()),
            Err(e) => log::warn!("[switchtether] import: failed to copy {item}: {e}"),
        }
    }

    receipt.outcome = ImportOutcome::Imported;
    receipt
}

enum CopyError {
    SchemaTooNew(i64),
    Other(String),
}

fn copy_database(source_db: &Path, target_dir: &Path, supported: i64) -> Result<i64, CopyError> {
    let src = Connection::open_with_flags(
        source_db,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )
    .map_err(|e| CopyError::Other(format!("open source db: {e}")))?;
    src.busy_timeout(Duration::from_secs(5))
        .map_err(|e| CopyError::Other(e.to_string()))?;
    let version: i64 = src
        .query_row("PRAGMA user_version", [], |r| r.get(0))
        .map_err(|e| CopyError::Other(format!("read user_version: {e}")))?;
    if version > supported {
        return Err(CopyError::SchemaTooNew(version));
    }

    fs::create_dir_all(target_dir).map_err(|e| CopyError::Other(format!("mkdir: {e}")))?;
    let tmp = target_dir.join(format!("{DB_FILE}.importing"));
    let _ = fs::remove_file(&tmp);
    let result = (|| -> Result<(), String> {
        let mut dst = Connection::open(&tmp).map_err(|e| format!("open target: {e}"))?;
        {
            let backup = rusqlite::backup::Backup::new(&src, &mut dst)
                .map_err(|e| format!("backup init: {e}"))?;
            backup
                .run_to_completion(256, Duration::from_millis(10), None)
                .map_err(|e| format!("backup: {e}"))?;
        }
        let check: String = dst
            .query_row("PRAGMA integrity_check", [], |r| r.get(0))
            .map_err(|e| format!("integrity_check: {e}"))?;
        if check != "ok" {
            return Err(format!("integrity_check failed: {check}"));
        }
        drop(dst);
        fs::rename(&tmp, target_dir.join(DB_FILE)).map_err(|e| format!("rename: {e}"))
    })();
    if let Err(e) = result {
        let _ = fs::remove_file(&tmp);
        return Err(CopyError::Other(e));
    }
    Ok(version)
}

fn copy_dir_no_symlinks(src: &Path, dst: &Path) -> std::io::Result<()> {
    fs::create_dir_all(dst)?;
    for entry in fs::read_dir(src)? {
        let entry = entry?;
        let ft = entry.file_type()?;
        let to: PathBuf = dst.join(entry.file_name());
        if ft.is_symlink() {
            continue;
        } else if ft.is_dir() {
            copy_dir_no_symlinks(&entry.path(), &to)?;
        } else if ft.is_file() {
            fs::copy(entry.path(), &to)?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_source(dir: &Path, version: i64) {
        fs::create_dir_all(dir).unwrap();
        let conn = Connection::open(dir.join(DB_FILE)).unwrap();
        conn.execute_batch(&format!(
            "CREATE TABLE providers(id TEXT PRIMARY KEY, name TEXT);
             INSERT INTO providers VALUES('p1','Provider One');
             PRAGMA user_version = {version};"
        ))
        .unwrap();
        fs::write(dir.join("settings.json"), br#"{"language":"zh"}"#).unwrap();
        fs::create_dir_all(dir.join("skills").join("demo")).unwrap();
        fs::write(dir.join("skills").join("demo").join("SKILL.md"), "# demo").unwrap();
        fs::write(dir.join("claude_accounts.json"), "{\"secret\":1}").unwrap();
    }

    #[test]
    fn imports_database_and_extras_without_touching_source() {
        let tmp = tempfile::tempdir().unwrap();
        let src = tmp.path().join(".cc-switch");
        let dst = tmp.path().join(".switchtether");
        make_source(&src, 5);
        let before = fs::read(src.join(DB_FILE)).unwrap();

        let r = run_import(&src, &dst, 26);
        assert_eq!(r.outcome, ImportOutcome::Imported, "{:?}", r.error);
        assert_eq!(r.source_schema_version, Some(5));
        assert!(r.copied.contains(&"cc-switch.db".to_string()));
        assert!(r.copied.contains(&"settings.json".to_string()));
        assert!(r.copied.contains(&"skills".to_string()));

        let conn = Connection::open(dst.join(DB_FILE)).unwrap();
        let name: String = conn
            .query_row("SELECT name FROM providers WHERE id='p1'", [], |r| r.get(0))
            .unwrap();
        assert_eq!(name, "Provider One");
        assert!(dst.join("skills/demo/SKILL.md").is_file());
        // OAuth account stores are never copied.
        assert!(!dst.join("claude_accounts.json").exists());
        // Source untouched.
        assert_eq!(fs::read(src.join(DB_FILE)).unwrap(), before);
        assert!(!dst.join("cc-switch.db.importing").exists());
    }

    #[test]
    fn never_overwrites_existing_target() {
        let tmp = tempfile::tempdir().unwrap();
        let src = tmp.path().join("src");
        let dst = tmp.path().join("dst");
        make_source(&src, 5);
        fs::create_dir_all(&dst).unwrap();
        fs::write(dst.join(DB_FILE), b"existing").unwrap();
        let r = run_import(&src, &dst, 26);
        assert_eq!(r.outcome, ImportOutcome::SkippedTargetExists);
        assert_eq!(fs::read(dst.join(DB_FILE)).unwrap(), b"existing");
    }

    #[test]
    fn refuses_newer_schema() {
        let tmp = tempfile::tempdir().unwrap();
        let src = tmp.path().join("src");
        let dst = tmp.path().join("dst");
        make_source(&src, 99);
        let r = run_import(&src, &dst, 26);
        assert_eq!(r.outcome, ImportOutcome::SkippedSourceSchemaTooNew);
        assert!(!dst.join(DB_FILE).exists());
    }

    #[test]
    fn receipt_prevents_second_attempt() {
        let tmp = tempfile::tempdir().unwrap();
        let src = tmp.path().join("src");
        let dst = tmp.path().join("dst");
        make_source(&src, 5);
        fs::create_dir_all(&dst).unwrap();
        fs::write(dst.join(RECEIPT_FILE), b"{}").unwrap();
        assert!(import_if_first_run(&src, &dst).is_none());
        assert!(!dst.join(DB_FILE).exists());
    }

    /// Opt-in check against a real CC Switch data directory (a COPY):
    /// `SWITCHTETHER_IMPORT_REAL_SOURCE=<home containing .cc-switch> cargo test --lib real_data_import -- --ignored --nocapture`
    /// The source is only read. Runs the import into a fresh test home, then
    /// opens the result with SwitchTether's own database layer.
    #[test]
    #[ignore]
    #[serial_test::serial]
    fn real_data_import() {
        let Some(src_home) = std::env::var_os("SWITCHTETHER_IMPORT_REAL_SOURCE") else {
            return;
        };
        let src = std::path::PathBuf::from(src_home).join(".cc-switch");
        let tmp = tempfile::tempdir().unwrap();
        std::env::set_var("CC_SWITCH_TEST_HOME", tmp.path());
        std::env::set_var("SWITCHTETHER_TEST_APP_DIR", "1");
        let target = crate::config::get_app_home_dir();
        let r = run_import(&src, &target, crate::database::SCHEMA_VERSION as i64);
        println!("receipt: {r:?}");
        assert_eq!(r.outcome, ImportOutcome::Imported);

        let db = crate::database::Database::init().expect("SwitchTether opens imported db");
        for app in ["claude", "claude-desktop", "codex", "gemini"] {
            let p = db.get_all_providers(app).unwrap();
            println!(
                "providers[{app}] = {} {:?}",
                p.len(),
                p.values().map(|x| x.name.clone()).collect::<Vec<_>>()
            );
        }
        println!("mcp servers = {}", db.get_all_mcp_servers().unwrap().len());
        println!("skills = {}", db.get_all_installed_skills().unwrap().len());
        let s = db.get_usage_summary(None, None, None, None, None).unwrap();
        println!("usage summary = {}", serde_json::to_string(&s).unwrap());
        assert!(target.join("settings.json").is_file());
        std::env::remove_var("CC_SWITCH_TEST_HOME");
        std::env::remove_var("SWITCHTETHER_TEST_APP_DIR");
    }

    #[cfg(unix)]
    #[test]
    fn skips_symlinks_in_skills() {
        let tmp = tempfile::tempdir().unwrap();
        let src = tmp.path().join("src");
        let dst = tmp.path().join("dst");
        make_source(&src, 5);
        std::os::unix::fs::symlink("/etc", src.join("skills").join("escape")).unwrap();
        let r = run_import(&src, &dst, 26);
        assert_eq!(r.outcome, ImportOutcome::Imported);
        assert!(!dst.join("skills/escape").exists());
    }
}
