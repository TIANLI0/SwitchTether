//! Device registry, audit log and idempotent command log, stored in
//! `<data>/remote/remote.db` — a database owned exclusively by SwitchTether's
//! Remote Core (never the upstream CC Switch database, so upstream schema
//! migrations and SwitchTether's remote state evolve independently).

use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;

use parking_lot::{Mutex, RwLock};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::tls::RevokedSet;
use crate::util::now_ms;

const SCHEMA_VERSION: i64 = 1;
/// Command records older than this are purged.
pub const COMMAND_TTL_MS: i64 = 24 * 60 * 60 * 1000;
/// Audit records older than this are purged.
pub const AUDIT_TTL_MS: i64 = 90 * 24 * 60 * 60 * 1000;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DeviceRecord {
    pub id: String,
    pub name: String,
    pub platform: String,
    pub os_version: Option<String>,
    pub app_version: Option<String>,
    pub cert_fingerprint: String,
    #[serde(skip_serializing)]
    pub step_up_key: Option<Vec<u8>>,
    pub step_up_enabled: bool,
    pub created_at_ms: i64,
    pub last_seen_at_ms: Option<i64>,
    pub last_ip: Option<String>,
    pub revoked_at_ms: Option<i64>,
}

impl DeviceRecord {
    pub fn is_active(&self) -> bool {
        self.revoked_at_ms.is_none()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AuditEntry {
    pub id: i64,
    pub ts_ms: i64,
    pub device_id: Option<String>,
    pub action: String,
    pub target: Option<String>,
    pub outcome: String,
    pub detail: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CommandStatus {
    Pending,
    Succeeded,
    Failed,
    /// The process stopped while the command was running; the real outcome
    /// must be checked against the underlying resource.
    Unknown,
}

impl CommandStatus {
    fn as_str(self) -> &'static str {
        match self {
            CommandStatus::Pending => "pending",
            CommandStatus::Succeeded => "succeeded",
            CommandStatus::Failed => "failed",
            CommandStatus::Unknown => "unknown",
        }
    }
    fn parse(s: &str) -> Self {
        match s {
            "pending" => CommandStatus::Pending,
            "succeeded" => CommandStatus::Succeeded,
            "failed" => CommandStatus::Failed,
            _ => CommandStatus::Unknown,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CommandRecord {
    pub command_id: String,
    pub kind: String,
    pub status: CommandStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<Value>,
    pub created_at_ms: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub completed_at_ms: Option<i64>,
}

pub enum CommandBegin {
    /// First time we see this command id: execute it.
    New,
    /// Already completed: return the stored record, do not execute again.
    Done(CommandRecord),
    /// Still executing (or outcome unknown after a crash).
    InProgress(CommandRecord),
}

pub struct Registry {
    conn: Mutex<Connection>,
    by_fp: RwLock<HashMap<String, DeviceRecord>>,
    revoked: RevokedSet,
}

impl Registry {
    pub fn open(path: &Path) -> anyhow::Result<Arc<Self>> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let conn = Connection::open(path)?;
        Self::init(conn)
    }

    pub fn open_in_memory() -> anyhow::Result<Arc<Self>> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(conn: Connection) -> anyhow::Result<Arc<Self>> {
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        conn.execute_batch(
            "PRAGMA journal_mode=WAL;
             PRAGMA foreign_keys=ON;
             CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
             CREATE TABLE IF NOT EXISTS devices(
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                platform TEXT NOT NULL,
                os_version TEXT,
                app_version TEXT,
                cert_fingerprint TEXT NOT NULL UNIQUE,
                step_up_key BLOB,
                created_at INTEGER NOT NULL,
                last_seen_at INTEGER,
                last_ip TEXT,
                revoked_at INTEGER
             );
             CREATE TABLE IF NOT EXISTS audit_log(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                device_id TEXT,
                action TEXT NOT NULL,
                target TEXT,
                outcome TEXT NOT NULL,
                detail TEXT
             );
             CREATE INDEX IF NOT EXISTS audit_ts ON audit_log(ts);
             CREATE TABLE IF NOT EXISTS commands(
                device_id TEXT NOT NULL,
                command_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                status TEXT NOT NULL,
                result TEXT,
                error TEXT,
                created_at INTEGER NOT NULL,
                completed_at INTEGER,
                PRIMARY KEY(device_id, command_id)
             );",
        )?;
        let version: Option<String> = conn
            .query_row("SELECT value FROM meta WHERE key='schema_version'", [], |r| {
                r.get(0)
            })
            .optional()?;
        match version.as_deref().map(str::parse::<i64>) {
            None => {
                conn.execute(
                    "INSERT INTO meta(key,value) VALUES('schema_version',?1)",
                    params![SCHEMA_VERSION.to_string()],
                )?;
            }
            Some(Ok(v)) if v > SCHEMA_VERSION => {
                anyhow::bail!("remote.db schema v{v} is newer than supported v{SCHEMA_VERSION}");
            }
            _ => {}
        }
        // Commands that were running when the process stopped have an unknown outcome.
        conn.execute(
            "UPDATE commands SET status='unknown' WHERE status='pending'",
            [],
        )?;

        let reg = Arc::new(Self {
            conn: Mutex::new(conn),
            by_fp: RwLock::new(HashMap::new()),
            revoked: RevokedSet::default(),
        });
        reg.reload_cache()?;
        reg.purge_expired()?;
        Ok(reg)
    }

    fn reload_cache(&self) -> anyhow::Result<()> {
        let devices = self.list_devices_db()?;
        let mut map = HashMap::new();
        let mut revoked = std::collections::HashSet::new();
        for d in devices {
            if d.is_active() {
                map.insert(d.cert_fingerprint.clone(), d);
            } else {
                revoked.insert(d.cert_fingerprint.clone());
            }
        }
        *self.by_fp.write() = map;
        *self.revoked.write() = revoked;
        Ok(())
    }

    /// Shared set used by the TLS verifier to reject revoked certificates.
    pub fn revoked_set(&self) -> RevokedSet {
        self.revoked.clone()
    }

    pub fn purge_expired(&self) -> anyhow::Result<()> {
        let now = now_ms();
        let c = self.conn.lock();
        c.execute(
            "DELETE FROM commands WHERE created_at < ?1",
            params![now - COMMAND_TTL_MS],
        )?;
        c.execute(
            "DELETE FROM audit_log WHERE ts < ?1",
            params![now - AUDIT_TTL_MS],
        )?;
        Ok(())
    }

    fn row_to_device(r: &rusqlite::Row<'_>) -> rusqlite::Result<DeviceRecord> {
        let key: Option<Vec<u8>> = r.get("step_up_key")?;
        Ok(DeviceRecord {
            id: r.get("id")?,
            name: r.get("name")?,
            platform: r.get("platform")?,
            os_version: r.get("os_version")?,
            app_version: r.get("app_version")?,
            cert_fingerprint: r.get("cert_fingerprint")?,
            step_up_enabled: key.is_some(),
            step_up_key: key,
            created_at_ms: r.get("created_at")?,
            last_seen_at_ms: r.get("last_seen_at")?,
            last_ip: r.get("last_ip")?,
            revoked_at_ms: r.get("revoked_at")?,
        })
    }

    fn list_devices_db(&self) -> anyhow::Result<Vec<DeviceRecord>> {
        let c = self.conn.lock();
        let mut st = c.prepare("SELECT * FROM devices ORDER BY created_at DESC")?;
        let rows = st
            .query_map([], Self::row_to_device)?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn list_devices(&self) -> anyhow::Result<Vec<DeviceRecord>> {
        self.list_devices_db()
    }

    /// Active (non-revoked) device for a TLS client fingerprint.
    pub fn device_by_fingerprint(&self, fp: &str) -> Option<DeviceRecord> {
        self.by_fp.read().get(fp).cloned()
    }

    pub fn is_revoked_fingerprint(&self, fp: &str) -> bool {
        self.revoked.read().contains(fp)
    }

    pub fn device(&self, id: &str) -> anyhow::Result<Option<DeviceRecord>> {
        let c = self.conn.lock();
        Ok(c.query_row("SELECT * FROM devices WHERE id=?1", params![id], Self::row_to_device)
            .optional()?)
    }

    /// Register (or re-register) the device bound to `cert_fingerprint`.
    pub fn register_device(&self, mut rec: DeviceRecord) -> anyhow::Result<DeviceRecord> {
        if self.is_revoked_fingerprint(&rec.cert_fingerprint) {
            anyhow::bail!("certificate was revoked");
        }
        {
            let c = self.conn.lock();
            let existing: Option<String> = c
                .query_row(
                    "SELECT id FROM devices WHERE cert_fingerprint=?1",
                    params![rec.cert_fingerprint],
                    |r| r.get(0),
                )
                .optional()?;
            if let Some(id) = existing {
                rec.id = id;
            }
            c.execute(
                "INSERT INTO devices(id,name,platform,os_version,app_version,cert_fingerprint,step_up_key,created_at,last_seen_at,last_ip,revoked_at)
                 VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,NULL)
                 ON CONFLICT(id) DO UPDATE SET name=excluded.name, platform=excluded.platform,
                   os_version=excluded.os_version, app_version=excluded.app_version,
                   step_up_key=excluded.step_up_key, last_seen_at=excluded.last_seen_at, last_ip=excluded.last_ip",
                params![
                    rec.id,
                    rec.name,
                    rec.platform,
                    rec.os_version,
                    rec.app_version,
                    rec.cert_fingerprint,
                    rec.step_up_key,
                    rec.created_at_ms,
                    rec.last_seen_at_ms,
                    rec.last_ip
                ],
            )?;
        }
        rec.step_up_enabled = rec.step_up_key.is_some();
        self.by_fp
            .write()
            .insert(rec.cert_fingerprint.clone(), rec.clone());
        Ok(rec)
    }

    pub fn touch(&self, device_id: &str, ip: Option<&str>) {
        let now = now_ms();
        let c = self.conn.lock();
        let _ = c.execute(
            "UPDATE devices SET last_seen_at=?2, last_ip=COALESCE(?3,last_ip) WHERE id=?1",
            params![device_id, now, ip],
        );
        drop(c);
        let mut map = self.by_fp.write();
        if let Some(d) = map.values_mut().find(|d| d.id == device_id) {
            d.last_seen_at_ms = Some(now);
            if let Some(ip) = ip {
                d.last_ip = Some(ip.to_string());
            }
        }
    }

    pub fn rename_device(&self, device_id: &str, name: &str) -> anyhow::Result<()> {
        let name: String = name.trim().chars().take(64).collect();
        if name.is_empty() {
            anyhow::bail!("name must not be empty");
        }
        self.conn
            .lock()
            .execute("UPDATE devices SET name=?2 WHERE id=?1", params![device_id, name])?;
        self.reload_cache()
    }

    /// Revoke a device: its certificate is denied at the TLS layer from now on,
    /// its step-up key and pending commands are removed. The row itself is
    /// kept (without secrets) for the deny-list and audit trail.
    pub fn revoke_device(&self, device_id: &str) -> anyhow::Result<Option<DeviceRecord>> {
        let now = now_ms();
        let rec = {
            let c = self.conn.lock();
            let changed = c.execute(
                "UPDATE devices SET revoked_at=?2, step_up_key=NULL WHERE id=?1 AND revoked_at IS NULL",
                params![device_id, now],
            )?;
            if changed == 0 {
                return Ok(None);
            }
            c.execute("DELETE FROM commands WHERE device_id=?1", params![device_id])?;
            c.query_row(
                "SELECT * FROM devices WHERE id=?1",
                params![device_id],
                Self::row_to_device,
            )?
        };
        // Update the TLS deny-list *before* dropping from the active map so
        // there is no window where the cert is neither active nor revoked.
        self.revoked.write().insert(rec.cert_fingerprint.clone());
        self.by_fp.write().remove(&rec.cert_fingerprint);
        Ok(Some(rec))
    }

    /// Permanently delete a revoked device row (keeps nothing).
    pub fn forget_device(&self, device_id: &str) -> anyhow::Result<bool> {
        let c = self.conn.lock();
        let n = c.execute(
            "DELETE FROM devices WHERE id=?1 AND revoked_at IS NOT NULL",
            params![device_id],
        )?;
        drop(c);
        self.reload_cache()?;
        Ok(n > 0)
    }

    // ---------------- audit ----------------

    pub fn audit(
        &self,
        device_id: Option<&str>,
        action: &str,
        target: Option<&str>,
        outcome: &str,
        detail: Option<&str>,
    ) {
        let target = target.map(crate::redact::redact_text);
        let detail = detail.map(crate::redact::redact_text);
        let c = self.conn.lock();
        if let Err(e) = c.execute(
            "INSERT INTO audit_log(ts,device_id,action,target,outcome,detail) VALUES(?1,?2,?3,?4,?5,?6)",
            params![now_ms(), device_id, action, target, outcome, detail],
        ) {
            log::warn!("[remote] audit write failed: {e}");
        }
    }

    pub fn audit_entries(&self, limit: usize, before_id: Option<i64>) -> anyhow::Result<Vec<AuditEntry>> {
        let c = self.conn.lock();
        let mut st = c.prepare(
            "SELECT id,ts,device_id,action,target,outcome,detail FROM audit_log
             WHERE (?1 IS NULL OR id < ?1) ORDER BY id DESC LIMIT ?2",
        )?;
        let rows = st
            .query_map(params![before_id, limit as i64], |r| {
                Ok(AuditEntry {
                    id: r.get(0)?,
                    ts_ms: r.get(1)?,
                    device_id: r.get(2)?,
                    action: r.get(3)?,
                    target: r.get(4)?,
                    outcome: r.get(5)?,
                    detail: r.get(6)?,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    // ---------------- commands ----------------

    fn row_to_command(r: &rusqlite::Row<'_>) -> rusqlite::Result<CommandRecord> {
        let result: Option<String> = r.get("result")?;
        let error: Option<String> = r.get("error")?;
        let status: String = r.get("status")?;
        Ok(CommandRecord {
            command_id: r.get("command_id")?,
            kind: r.get("kind")?,
            status: CommandStatus::parse(&status),
            result: result.and_then(|s| serde_json::from_str(&s).ok()),
            error: error.and_then(|s| serde_json::from_str(&s).ok()),
            created_at_ms: r.get("created_at")?,
            completed_at_ms: r.get("completed_at")?,
        })
    }

    pub fn command(&self, device_id: &str, command_id: &str) -> anyhow::Result<Option<CommandRecord>> {
        let c = self.conn.lock();
        Ok(c.query_row(
            "SELECT * FROM commands WHERE device_id=?1 AND command_id=?2",
            params![device_id, command_id],
            Self::row_to_command,
        )
        .optional()?)
    }

    /// Atomically claim a command id for execution.
    pub fn begin_command(
        &self,
        device_id: &str,
        command_id: &str,
        kind: &str,
    ) -> anyhow::Result<CommandBegin> {
        let c = self.conn.lock();
        let inserted = c.execute(
            "INSERT OR IGNORE INTO commands(device_id,command_id,kind,status,created_at) VALUES(?1,?2,?3,'pending',?4)",
            params![device_id, command_id, kind, now_ms()],
        )?;
        if inserted == 1 {
            return Ok(CommandBegin::New);
        }
        let rec = c.query_row(
            "SELECT * FROM commands WHERE device_id=?1 AND command_id=?2",
            params![device_id, command_id],
            Self::row_to_command,
        )?;
        if rec.kind != kind {
            anyhow::bail!("commandId reused for a different operation");
        }
        Ok(match rec.status {
            CommandStatus::Succeeded | CommandStatus::Failed => CommandBegin::Done(rec),
            CommandStatus::Pending | CommandStatus::Unknown => CommandBegin::InProgress(rec),
        })
    }

    pub fn complete_command(
        &self,
        device_id: &str,
        command_id: &str,
        outcome: Result<&Value, &Value>,
    ) -> anyhow::Result<()> {
        let (status, result, error) = match outcome {
            Ok(v) => (CommandStatus::Succeeded, Some(v.to_string()), None),
            Err(e) => (CommandStatus::Failed, None, Some(e.to_string())),
        };
        self.conn.lock().execute(
            "UPDATE commands SET status=?3, result=?4, error=?5, completed_at=?6 WHERE device_id=?1 AND command_id=?2",
            params![device_id, command_id, status.as_str(), result, error, now_ms()],
        )?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn dev(fp: &str) -> DeviceRecord {
        DeviceRecord {
            id: crate::util::new_id(),
            name: "Phone".into(),
            platform: "android".into(),
            os_version: Some("15".into()),
            app_version: Some("0.1.0".into()),
            cert_fingerprint: fp.into(),
            step_up_key: Some(vec![1, 2, 3]),
            step_up_enabled: true,
            created_at_ms: now_ms(),
            last_seen_at_ms: None,
            last_ip: None,
            revoked_at_ms: None,
        }
    }

    #[test]
    fn register_lookup_revoke() {
        let r = Registry::open_in_memory().unwrap();
        let d = r.register_device(dev("fp1")).unwrap();
        assert_eq!(r.device_by_fingerprint("fp1").unwrap().id, d.id);
        assert!(!r.is_revoked_fingerprint("fp1"));

        let rev = r.revoke_device(&d.id).unwrap().unwrap();
        assert!(rev.revoked_at_ms.is_some());
        assert!(rev.step_up_key.is_none());
        assert!(r.device_by_fingerprint("fp1").is_none());
        assert!(r.is_revoked_fingerprint("fp1"));
        assert!(r.revoked_set().read().contains("fp1"));
        // revoking twice is a no-op
        assert!(r.revoke_device(&d.id).unwrap().is_none());
        // a revoked certificate can never be registered again
        assert!(r.register_device(dev("fp1")).is_err());
    }

    #[test]
    fn re_register_same_cert_keeps_id() {
        let r = Registry::open_in_memory().unwrap();
        let a = r.register_device(dev("fp2")).unwrap();
        let mut again = dev("fp2");
        again.name = "Renamed".into();
        let b = r.register_device(again).unwrap();
        assert_eq!(a.id, b.id);
        assert_eq!(r.list_devices().unwrap().len(), 1);
        assert_eq!(r.device_by_fingerprint("fp2").unwrap().name, "Renamed");
    }

    #[test]
    fn command_idempotency() {
        let r = Registry::open_in_memory().unwrap();
        assert!(matches!(
            r.begin_command("d", "c1", "provider.switch").unwrap(),
            CommandBegin::New
        ));
        assert!(matches!(
            r.begin_command("d", "c1", "provider.switch").unwrap(),
            CommandBegin::InProgress(_)
        ));
        r.complete_command("d", "c1", Ok(&json!({"ok": true}))).unwrap();
        match r.begin_command("d", "c1", "provider.switch").unwrap() {
            CommandBegin::Done(rec) => {
                assert_eq!(rec.status, CommandStatus::Succeeded);
                assert_eq!(rec.result, Some(json!({"ok": true})));
            }
            _ => panic!("expected Done"),
        }
        // same id, different op → rejected
        assert!(r.begin_command("d", "c1", "fs.delete").is_err());
        // ids are scoped per device
        assert!(matches!(
            r.begin_command("other", "c1", "provider.switch").unwrap(),
            CommandBegin::New
        ));
    }

    #[test]
    fn pending_commands_become_unknown_after_restart() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("remote.db");
        {
            let r = Registry::open(&path).unwrap();
            let _ = r.begin_command("d", "c1", "k").unwrap();
        }
        let r = Registry::open(&path).unwrap();
        assert_eq!(
            r.command("d", "c1").unwrap().unwrap().status,
            CommandStatus::Unknown
        );
    }

    #[test]
    fn audit_is_redacted() {
        let r = Registry::open_in_memory().unwrap();
        r.audit(Some("d"), "provider.credential", Some("token=supersecret"), "ok", None);
        let e = r.audit_entries(10, None).unwrap();
        assert_eq!(e.len(), 1);
        assert!(!e[0].target.as_deref().unwrap().contains("supersecret"));
    }
}
