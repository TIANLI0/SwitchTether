//! Read-only discovery of Antigravity (`agy`) conversations, live run status, titles, and
//! sub-agent trees from `<root>/conversation_summaries.db` and `<root>/brain/<id>/`.
//!
//! Hard Rule 1 (`AGENTS.md`): never write to `~/.gemini`, never lock or touch running `agy`
//! processes. SQLite is opened strictly read-only (`SQLITE_OPEN_READ_ONLY`) with a short busy
//! timeout, and results are cached behind `(mtime, len)` stamps of `conversation_summaries.db`
//! and its `-wal` file so unchanged polls cost only `stat` calls.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

use parking_lot::Mutex;
use rusqlite::{Connection, OpenFlags};
use serde_json::{json, Value};

use crate::agents::claude::transcript::clean_title;
use crate::agents::model::Origin;
use crate::redact::redact_text;

/// Conversations in `CASCADE_RUN_STATUS_IDLE` modified within this window are treated as open
/// observe-only sessions (matching DeepSeek Harness's 2-minute idle window).
const IDLE_LIVE_WINDOW_MS: i64 = 120_000;

#[derive(Debug, Clone, PartialEq)]
pub struct AgySummaryRow {
    pub conversation_id: String,
    pub title: Option<String>,
    pub cwd: Option<String>,
    pub status: String,
    pub not_fully_idle: bool,
    pub killed: bool,
    pub parent_conversation_id: Option<String>,
    pub nesting_depth: i64,
    pub agent_name: Option<String>,
    pub step_count: i64,
    pub last_modified_ms: Option<i64>,
    pub last_user_input_ms: Option<i64>,
    pub root_dir: PathBuf,
    pub origin: Origin,
}

impl AgySummaryRow {
    pub fn is_subagent(&self) -> bool {
        self.nesting_depth > 0
            || self
                .parent_conversation_id
                .as_deref()
                .is_some_and(|p| !p.is_empty())
    }

    pub fn is_running(&self) -> bool {
        !self.killed && self.status == "CASCADE_RUN_STATUS_RUNNING"
    }

    /// True when Antigravity has recorded this conversation as stopped or idle with no
    /// background work left. An empty `status` with zero steps is treated as still starting.
    pub fn is_finished(&self) -> bool {
        if self.killed {
            return true;
        }
        if self.is_running() || self.not_fully_idle {
            return false;
        }
        !self.status.is_empty() || self.step_count > 0
    }

    /// `Some((status, background_work_hint))` when this top-level conversation is currently live.
    pub fn observed_live(&self, now_ms: i64) -> Option<(&'static str, bool)> {
        if self.killed || self.is_subagent() {
            return None;
        }
        if self.status == "CASCADE_RUN_STATUS_RUNNING" {
            return Some(("busy", self.not_fully_idle));
        }
        if self.not_fully_idle {
            return Some(("idle", true));
        }
        if self.status == "CASCADE_RUN_STATUS_IDLE" {
            if let Some(m) = self.last_modified_ms {
                if now_ms.saturating_sub(m) <= IDLE_LIVE_WINDOW_MS {
                    return Some(("idle", false));
                }
            }
        }
        None
    }

    pub fn transcript_path(&self) -> Option<PathBuf> {
        transcript_in_root(&self.root_dir, &self.conversation_id)
    }

    pub fn brain_dir(&self) -> PathBuf {
        self.root_dir.join("brain").join(&self.conversation_id)
    }
}

type RootStamp = Vec<(PathBuf, Option<SystemTime>, u64)>;

pub struct AgyObserver {
    roots: Vec<PathBuf>,
    cache: Mutex<(RootStamp, HashMap<String, AgySummaryRow>)>,
}

impl AgyObserver {
    pub fn new(roots: Vec<PathBuf>) -> Self {
        Self {
            roots,
            cache: Mutex::new((Vec::new(), HashMap::new())),
        }
    }

    pub fn default_roots() -> Vec<PathBuf> {
        let base = std::env::var_os("GEMINI_CLI_HOME")
            .filter(|v| !v.is_empty())
            .map(PathBuf::from)
            .or_else(dirs::home_dir)
            .unwrap_or_default()
            .join(".gemini");
        let mut out = vec![
            base.join("antigravity-cli"),
            base.join("antigravity"),
            base.join("antigravity-acp"),
        ];
        // Also cover CC Switch Key-card isolated profiles (`~/.switchtether/agy/*/antigravity-cli`)
        // read-only if any exist on disk.
        if let Some(home) = dirs::home_dir() {
            let profiles = home.join(".switchtether").join("agy");
            if let Ok(rd) = std::fs::read_dir(profiles) {
                for e in rd.flatten().take(64) {
                    let sub = e.path().join(".gemini").join("antigravity-cli");
                    if sub.is_dir() {
                        out.push(sub);
                    }
                    let direct = e.path().join("antigravity-cli");
                    if direct.is_dir() {
                        out.push(direct);
                    }
                }
            }
        }
        out
    }

    pub fn roots(&self) -> &[PathBuf] {
        &self.roots
    }

    fn compute_stamp(&self) -> RootStamp {
        let mut out = Vec::new();
        for r in &self.roots {
            for name in ["conversation_summaries.db", "conversation_summaries.db-wal"] {
                let p = r.join(name);
                if let Ok(meta) = std::fs::metadata(&p) {
                    out.push((p, meta.modified().ok(), meta.len()));
                }
            }
        }
        out
    }

    /// True if any summary database or WAL file changed since the last read.
    pub fn changed(&self) -> bool {
        let now = self.compute_stamp();
        self.cache.lock().0 != now
    }

    /// Snapshot of all indexed Antigravity conversations across roots (newest write wins).
    pub fn rows(&self) -> HashMap<String, AgySummaryRow> {
        let stamp = self.compute_stamp();
        {
            let guard = self.cache.lock();
            if !stamp.is_empty() && guard.0 == stamp {
                return guard.1.clone();
            }
        }
        let mut map: HashMap<String, AgySummaryRow> = HashMap::new();
        for root in &self.roots {
            for row in read_root_summaries(root) {
                let replace = match map.get(&row.conversation_id) {
                    None => true,
                    Some(prev) => {
                        row.is_running() && !prev.is_running()
                            || row.last_modified_ms.unwrap_or(0)
                                >= prev.last_modified_ms.unwrap_or(0)
                    }
                };
                if replace {
                    map.insert(row.conversation_id.clone(), row);
                }
            }
        }
        let mut guard = self.cache.lock();
        *guard = (stamp, map.clone());
        map
    }

    pub fn row(&self, conversation_id: &str) -> Option<AgySummaryRow> {
        self.rows().remove(conversation_id)
    }

    pub fn is_subagent(&self, conversation_id: &str) -> bool {
        self.rows()
            .get(conversation_id)
            .is_some_and(AgySummaryRow::is_subagent)
    }

    /// Locate `transcript_full.jsonl` (preferred) or `transcript.jsonl` for a conversation id.
    pub fn transcript_path(&self, conversation_id: &str) -> Option<PathBuf> {
        if !valid_conv_id(conversation_id) {
            return None;
        }
        if let Some(r) = self.rows().get(conversation_id) {
            if let Some(p) = r.transcript_path() {
                return Some(p);
            }
        }
        for root in &self.roots {
            if let Some(p) = transcript_in_root(root, conversation_id) {
                return Some(p);
            }
        }
        None
    }

    pub fn brain_dir(&self, conversation_id: &str) -> Option<PathBuf> {
        if !valid_conv_id(conversation_id) {
            return None;
        }
        if let Some(r) = self.rows().get(conversation_id) {
            let d = r.brain_dir();
            if d.is_dir() {
                return Some(d);
            }
        }
        for root in &self.roots {
            let d = root.join("brain").join(conversation_id);
            if d.is_dir() {
                return Some(d);
            }
        }
        None
    }

    /// Live top-level sessions (`CASCADE_RUN_STATUS_RUNNING`, `not_fully_idle`, or recently active
    /// `CASCADE_RUN_STATUS_IDLE`).
    pub fn live_sessions(&self) -> Vec<AgySummaryRow> {
        let now_ms = crate::util::now_ms();
        let mut out: Vec<AgySummaryRow> = self
            .rows()
            .into_values()
            .filter(|r| r.observed_live(now_ms).is_some())
            .collect();
        out.sort_by_key(|r| std::cmp::Reverse(r.last_modified_ms.unwrap_or(0)));
        out
    }

    /// All descendant sub-agent conversations of `root_conversation_id`, ordered by modification time.
    pub fn descendants(&self, root_conversation_id: &str) -> Vec<AgySummaryRow> {
        let all = self.rows();
        let mut children_by_parent: HashMap<&str, Vec<&AgySummaryRow>> = HashMap::new();
        for r in all.values() {
            if let Some(p) = r
                .parent_conversation_id
                .as_deref()
                .filter(|p| !p.is_empty())
            {
                children_by_parent.entry(p).or_default().push(r);
            }
        }
        let mut out = Vec::new();
        let mut stack = vec![root_conversation_id];
        let mut visited = std::collections::HashSet::new();
        visited.insert(root_conversation_id.to_string());
        while let Some(cur) = stack.pop() {
            if let Some(kids) = children_by_parent.get(cur) {
                for k in kids {
                    if visited.insert(k.conversation_id.clone()) {
                        out.push((*k).clone());
                        stack.push(k.conversation_id.as_str());
                        if out.len() >= 500 {
                            break;
                        }
                    }
                }
            }
            if out.len() >= 500 {
                break;
            }
        }
        out.sort_by_key(|r| r.last_modified_ms.unwrap_or(0));
        out
    }

    /// Number of currently running descendant sub-agents for `root_conversation_id`.
    pub fn running_subagent_count(&self, root_conversation_id: &str) -> u32 {
        let count = self
            .descendants(root_conversation_id)
            .into_iter()
            .filter(|r| r.is_running() || (!r.killed && r.not_fully_idle))
            .count();
        u32::try_from(count).unwrap_or(u32::MAX)
    }

    /// OS file-system watcher on existing Antigravity roots for `conversation_summaries.db*`.
    pub fn watch(
        &self,
    ) -> Option<(
        notify::RecommendedWatcher,
        tokio::sync::mpsc::UnboundedReceiver<()>,
    )> {
        use notify::Watcher;
        let dirs: Vec<PathBuf> = self.roots.iter().filter(|d| d.is_dir()).cloned().collect();
        if dirs.is_empty() {
            return None;
        }
        let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
        let mut watcher = notify::recommended_watcher(move |res: notify::Result<notify::Event>| {
            let relevant = match res {
                Ok(ev) => ev.paths.iter().any(|p| {
                    p.file_name()
                        .and_then(|n| n.to_str())
                        .is_some_and(|n| n.starts_with("conversation_summaries.db"))
                }),
                Err(_) => true,
            };
            if relevant {
                let _ = tx.send(());
            }
        })
        .ok()?;
        let mut watched = 0usize;
        for d in dirs {
            if watcher
                .watch(&d, notify::RecursiveMode::NonRecursive)
                .is_ok()
            {
                watched += 1;
            }
        }
        (watched > 0).then_some((watcher, rx))
    }
}

/// Read-only MCP server status from Antigravity's `mcp_config.json` (`~/.gemini/antigravity/mcp_config.json`
/// and `<cwd>/.gemini/mcp_config.json`), reduced to `{name, status, transport, scope}` — never
/// exposing commands, args, headers, URLs with tokens, or env vars (Hard Rule 4).
pub fn read_mcp_status(cwd: Option<&Path>) -> Value {
    let mut servers = Vec::new();
    let mut seen = std::collections::HashSet::new();

    let mut candidates: Vec<(PathBuf, &'static str)> = Vec::new();
    if let Some(c) = cwd {
        candidates.push((c.join(".gemini").join("mcp_config.json"), "project"));
    }
    let gemini = std::env::var_os("GEMINI_CLI_HOME")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .or_else(dirs::home_dir)
        .unwrap_or_default()
        .join(".gemini");
    candidates.push((gemini.join("antigravity").join("mcp_config.json"), "user"));
    candidates.push((gemini.join("config").join("mcp_config.json"), "user"));
    candidates.push((
        gemini.join("antigravity-cli").join("mcp_config.json"),
        "user",
    ));

    for (path, scope) in candidates {
        let Ok(raw) = std::fs::read_to_string(&path) else {
            continue;
        };
        if raw.trim().is_empty() {
            continue;
        }
        let Ok(doc) = serde_json::from_str::<Value>(&raw) else {
            continue;
        };
        let Some(map) = doc.get("mcpServers").and_then(Value::as_object) else {
            continue;
        };
        for (name, spec) in map {
            if name.trim().is_empty() || !seen.insert(name.clone()) {
                continue;
            }
            let Some(obj) = spec.as_object() else {
                continue;
            };
            let disabled = obj
                .get("disabled")
                .and_then(Value::as_bool)
                .unwrap_or(false);
            let transport = obj
                .get("type")
                .and_then(Value::as_str)
                .map(String::from)
                .or_else(|| {
                    if obj.contains_key("url") || obj.contains_key("serverUrl") {
                        Some("http".into())
                    } else if obj.contains_key("command") {
                        Some("stdio".into())
                    } else {
                        None
                    }
                });
            servers.push(json!({
                "name": redact_text(name),
                "status": if disabled { "disabled" } else { "connected" },
                "transport": transport,
                "scope": scope,
            }));
        }
    }
    json!({ "servers": servers })
}

/// Latest plan markdown from `<brain_dir>/implementation_plan.md` if Antigravity wrote an artifact
/// plan file for this conversation.
pub fn read_brain_plan(brain_dir: &Path) -> Option<(String, String)> {
    let p = brain_dir.join("implementation_plan.md");
    let text = std::fs::read_to_string(&p).ok()?;
    if text.trim().is_empty() {
        return None;
    }
    Some((redact_text(&text), p.to_string_lossy().into_owned()))
}

fn valid_conv_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

pub fn transcript_in_root(root: &Path, conversation_id: &str) -> Option<PathBuf> {
    if !valid_conv_id(conversation_id) {
        return None;
    }
    let logs = root
        .join("brain")
        .join(conversation_id)
        .join(".system_generated")
        .join("logs");
    let full = logs.join("transcript_full.jsonl");
    if full.is_file() {
        return Some(full);
    }
    let compact = logs.join("transcript.jsonl");
    if compact.is_file() {
        return Some(compact);
    }
    None
}

fn read_root_summaries(root: &Path) -> Vec<AgySummaryRow> {
    let db_path = root.join("conversation_summaries.db");
    if !db_path.is_file() {
        return Vec::new();
    }
    let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    let Ok(conn) = Connection::open_with_flags(&db_path, flags) else {
        return Vec::new();
    };
    let _ = conn.busy_timeout(Duration::from_millis(200));

    let origin = match root.file_name().and_then(|n| n.to_str()) {
        Some("antigravity-cli") => Origin::Cli,
        Some("antigravity") => Origin::DesktopApp,
        _ => Origin::Unknown,
    };

    let sql = "SELECT
        conversation_id,
        COALESCE(title, ''),
        COALESCE(preview, ''),
        COALESCE(workspace_uris, ''),
        COALESCE(status, ''),
        COALESCE(not_fully_idle, 0),
        COALESCE(killed, 0),
        COALESCE(parent_conversation_id, ''),
        COALESCE(nesting_depth, 0),
        COALESCE(agent_name, ''),
        COALESCE(last_modified_time, ''),
        COALESCE(last_user_input_time, ''),
        COALESCE(step_count, 0)
    FROM conversation_summaries";

    let Ok(mut stmt) = conn.prepare(sql) else {
        // Older or synthetic test schemas without `step_count`.
        let fallback_sql = "SELECT
            conversation_id,
            COALESCE(title, ''),
            COALESCE(preview, ''),
            COALESCE(workspace_uris, ''),
            COALESCE(status, ''),
            COALESCE(not_fully_idle, 0),
            COALESCE(killed, 0),
            COALESCE(parent_conversation_id, ''),
            COALESCE(nesting_depth, 0),
            COALESCE(agent_name, ''),
            COALESCE(last_modified_time, ''),
            COALESCE(last_user_input_time, ''),
            0
        FROM conversation_summaries";
        let Ok(mut stmt2) = conn.prepare(fallback_sql) else {
            return Vec::new();
        };
        return collect_summary_rows(&mut stmt2, root, origin);
    };
    collect_summary_rows(&mut stmt, root, origin)
}

fn collect_summary_rows(
    stmt: &mut rusqlite::Statement<'_>,
    root: &Path,
    origin: Origin,
) -> Vec<AgySummaryRow> {
    let Ok(iter) = stmt.query_map([], |r| {
        let cid: String = r.get(0)?;
        let title_raw: String = r.get(1)?;
        let preview_raw: String = r.get(2)?;
        let workspaces_raw: String = r.get(3)?;
        let status: String = r.get(4)?;
        let not_fully_idle: i64 = r.get(5)?;
        let killed: i64 = r.get(6)?;
        let parent: String = r.get(7)?;
        let depth: i64 = r.get(8)?;
        let agent_name: String = r.get(9)?;
        let modified_str: String = r.get(10)?;
        let user_input_str: String = r.get(11)?;
        let step_count: i64 = r.get(12)?;
        Ok((
            cid,
            title_raw,
            preview_raw,
            workspaces_raw,
            status,
            not_fully_idle != 0,
            killed != 0,
            parent,
            depth,
            agent_name,
            modified_str,
            user_input_str,
            step_count,
        ))
    }) else {
        return Vec::new();
    };

    let mut out = Vec::new();
    for item in iter.flatten() {
        let (
            cid,
            title_raw,
            preview_raw,
            workspaces_raw,
            status,
            not_fully_idle,
            killed,
            parent,
            depth,
            agent_name,
            modified_str,
            user_input_str,
            step_count,
        ) = item;
        if !valid_conv_id(&cid) {
            continue;
        }
        let chosen_title = if !title_raw.trim().is_empty() {
            Some(redact_text(title_raw.trim()))
        } else if !preview_raw.trim().is_empty() {
            Some(redact_text(preview_raw.trim()))
        } else {
            None
        }
        .and_then(|t| clean_title(Some(t)));

        out.push(AgySummaryRow {
            conversation_id: cid,
            title: chosen_title,
            cwd: parse_workspace_uris(&workspaces_raw),
            status,
            not_fully_idle,
            killed,
            parent_conversation_id: (!parent.trim().is_empty()).then(|| parent.trim().to_string()),
            nesting_depth: depth,
            agent_name: (!agent_name.trim().is_empty()).then(|| agent_name.trim().to_string()),
            step_count,
            last_modified_ms: parse_sqlite_time(&modified_str),
            last_user_input_ms: parse_sqlite_time(&user_input_str),
            root_dir: root.to_path_buf(),
            origin,
        });
    }
    out
}

fn parse_workspace_uris(raw: &str) -> Option<String> {
    let arr: Vec<String> = serde_json::from_str(raw).ok()?;
    let mut fallback: Option<String> = None;
    for uri in arr {
        let Some(p) = super::transcript::file_uri_to_path(&uri) else {
            continue;
        };
        if p.is_empty() {
            continue;
        }
        if !p
            .replace('\\', "/")
            .contains("/.switchtether/remote/attachments")
        {
            return Some(p);
        }
        if fallback.is_none() {
            fallback = Some(p);
        }
    }
    fallback
}

fn parse_sqlite_time(raw: &str) -> Option<i64> {
    let t = raw.trim();
    if t.is_empty() || t.starts_with("0001-01-01") {
        return None;
    }
    if let Ok(dt) = chrono::DateTime::parse_from_rfc3339(t) {
        return Some(dt.timestamp_millis());
    }
    // Antigravity writes Go `time.Time.String()` / SQLite timestamps like
    // `"2026-11-28 06:48:04.9555421+00:00"` (space separator instead of `T`).
    let iso = t.replacen(' ', "T", 1);
    chrono::DateTime::parse_from_rfc3339(&iso)
        .ok()
        .map(|dt| dt.timestamp_millis())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_summaries_live_state_and_subagent_hierarchy() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("antigravity-cli");
        std::fs::create_dir_all(&root).unwrap();
        let db = root.join("conversation_summaries.db");
        let conn = Connection::open(&db).unwrap();
        conn.execute_batch(
            "CREATE TABLE conversation_summaries (
                conversation_id TEXT PRIMARY KEY,
                title TEXT,
                preview TEXT,
                workspace_uris TEXT,
                status TEXT,
                not_fully_idle INTEGER,
                killed INTEGER,
                parent_conversation_id TEXT,
                nesting_depth INTEGER,
                agent_name TEXT,
                last_modified_time TEXT,
                last_user_input_time TEXT
            );",
        )
        .unwrap();
        let now = chrono::Utc::now().to_rfc3339();
        conn.execute(
            "INSERT INTO conversation_summaries VALUES (?1, ?2, '', ?3, 'CASCADE_RUN_STATUS_RUNNING', 1, 0, '', 0, '', ?4, ?4)",
            (
                "parent-1",
                "Adapt Antigravity",
                r#"["file:///C:/Users/u/.switchtether/remote/attachments","file:///d:/develop/SwitchTether"]"#,
                &now,
            ),
        )
        .unwrap();
        conn.execute(
            "INSERT INTO conversation_summaries VALUES ('child-1', '', '', '[]', 'CASCADE_RUN_STATUS_IDLE', 0, 0, 'parent-1', 1, 'research', ?1, '')",
            [&now],
        )
        .unwrap();
        drop(conn);

        let obs = AgyObserver::new(vec![root]);
        let live = obs.live_sessions();
        assert_eq!(
            live.len(),
            1,
            "subagents are excluded from top-level live list"
        );
        assert_eq!(live[0].conversation_id, "parent-1");
        assert_eq!(live[0].cwd.as_deref(), Some("d:/develop/SwitchTether"));
        assert_eq!(
            live[0].observed_live(crate::util::now_ms()),
            Some(("busy", true))
        );
        assert!(obs.is_subagent("child-1"));

        let kids = obs.descendants("parent-1");
        assert_eq!(kids.len(), 1);
        assert_eq!(kids[0].conversation_id, "child-1");
        assert_eq!(kids[0].agent_name.as_deref(), Some("research"));
    }
}
