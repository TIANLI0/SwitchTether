//! Usage per project (`GET /v1/usage/projects`, docs/PROTOCOL.md §7.3).
//!
//! CC Switch records the agent session id on every request log it reads from a session log
//! (`proxy_request_logs.session_id`). The backend sums those logs per session
//! ([`crate::backend::CcSwitchBackend::usage_sessions`]); here they are joined with the session
//! index (session id → working directory) and grouped by project, the same project name the
//! session list shows and filters by ([`SessionSummary::project_from_cwd`]). Logs without a
//! session id, or whose session is not in the index any more, are summed as `unattributed`.
//!
//! Only titles leave this module as text, and they go through [`crate::redact::redact_text`].

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use crate::agents::model::SessionSummary;
use crate::backend::IndexedSession;

/// Most projects listed (by cost); the rest are summed into `otherProjects`.
pub const MAX_PROJECTS: usize = 50;
/// Sessions listed per project when all projects are asked for.
pub const SESSIONS_PER_PROJECT: usize = 5;
/// Sessions listed when one project is asked for (`?project=`).
pub const SESSIONS_ONE_PROJECT: usize = 100;

/// One row of the backend's per-session sums.
#[derive(Debug, Clone, Default, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct SessionUsageRow {
    /// CC Switch app type (`claude`, `codex`, …) — the session index's `providerId`.
    pub app: String,
    pub session_id: Option<String>,
    pub requests: i64,
    pub cost_usd: f64,
    pub input_tokens: i64,
    pub output_tokens: i64,
    pub cache_read_tokens: i64,
    pub cache_creation_tokens: i64,
    /// Newest request, epoch ms.
    pub last_ms: Option<i64>,
}

#[derive(Debug, Clone, Default, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct UsageTotals {
    pub requests: i64,
    pub cost_usd: f64,
    pub input_tokens: i64,
    pub output_tokens: i64,
    pub cache_read_tokens: i64,
    pub cache_creation_tokens: i64,
    pub last_ms: Option<i64>,
}

impl UsageTotals {
    fn add(&mut self, r: &SessionUsageRow) {
        self.requests += r.requests.max(0);
        if r.cost_usd.is_finite() && r.cost_usd > 0.0 {
            self.cost_usd += r.cost_usd;
        }
        self.input_tokens += r.input_tokens.max(0);
        self.output_tokens += r.output_tokens.max(0);
        self.cache_read_tokens += r.cache_read_tokens.max(0);
        self.cache_creation_tokens += r.cache_creation_tokens.max(0);
        self.last_ms = self.last_ms.max(r.last_ms);
    }

    fn merge(&mut self, o: &UsageTotals) {
        self.requests += o.requests;
        self.cost_usd += o.cost_usd;
        self.input_tokens += o.input_tokens;
        self.output_tokens += o.output_tokens;
        self.cache_read_tokens += o.cache_read_tokens;
        self.cache_creation_tokens += o.cache_creation_tokens;
        self.last_ms = self.last_ms.max(o.last_ms);
    }

    fn is_empty(&self) -> bool {
        self.requests == 0 && self.cost_usd == 0.0
    }
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SessionUsage {
    /// The id the session list uses (`claude:<uuid>`, `codex:<thread>`, …).
    pub id: String,
    pub app: String,
    pub title: Option<String>,
    pub cwd: Option<String>,
    #[serde(flatten)]
    pub totals: UsageTotals,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ProjectUsage {
    /// Project name as the session list shows it (also its `project` filter).
    pub project: String,
    /// The working directory most of the project's cost came from.
    pub cwd: Option<String>,
    pub session_count: usize,
    #[serde(flatten)]
    pub totals: UsageTotals,
    /// Its most expensive sessions, most expensive first.
    pub sessions: Vec<SessionUsage>,
}

#[derive(Debug, Clone, Default, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ProjectUsageView {
    pub projects: Vec<ProjectUsage>,
    /// Projects beyond [`MAX_PROJECTS`], summed.
    pub other_projects: Option<UsageTotals>,
    /// Logs without a session id, or whose session is not in the index.
    pub unattributed: Option<UsageTotals>,
}

fn session_prefix(app: &str) -> &str {
    // Same ids as the session list (agents::AgentHub::list): the provider id is the prefix.
    app
}

fn cost_desc(a: &UsageTotals, b: &UsageTotals) -> std::cmp::Ordering {
    b.cost_usd
        .partial_cmp(&a.cost_usd)
        .unwrap_or(std::cmp::Ordering::Equal)
        .then(b.last_ms.cmp(&a.last_ms))
}

/// Joins per-session sums with the session index and groups them by project. With `only` set,
/// returns that project alone (matched by project name or exact working directory) with up to
/// [`SESSIONS_ONE_PROJECT`] sessions.
pub fn group_by_project(
    rows: &[SessionUsageRow],
    index: &[IndexedSession],
    only: Option<&str>,
) -> ProjectUsageView {
    let by_id: HashMap<(&str, &str), &IndexedSession> = index
        .iter()
        .map(|s| ((s.provider_id.as_str(), s.session_id.as_str()), s))
        .collect();
    let mut unattributed = UsageTotals::default();
    // project name → (sessions, cost per cwd)
    let mut groups: HashMap<String, (Vec<SessionUsage>, HashMap<String, f64>)> = HashMap::new();
    // The same session can come back once per app type spelling; sum per id.
    let mut seen: HashMap<String, (String, usize)> = HashMap::new();
    for r in rows {
        let hit = r
            .session_id
            .as_deref()
            .filter(|s| !s.is_empty())
            .and_then(|sid| by_id.get(&(r.app.as_str(), sid)).map(|ix| (sid, *ix)));
        let Some((sid, ix)) = hit else {
            unattributed.add(r);
            continue;
        };
        let Some(cwd) = ix.project_dir.as_deref().filter(|c| !c.is_empty()) else {
            unattributed.add(r);
            continue;
        };
        let Some(name) = SessionSummary::project_from_cwd(cwd) else {
            unattributed.add(r);
            continue;
        };
        let id = format!("{}:{sid}", session_prefix(&r.app));
        let entry = groups.entry(name.clone()).or_default();
        *entry.1.entry(cwd.to_string()).or_default() += r.cost_usd.max(0.0);
        if let Some((group, i)) = seen.get(&id) {
            if let Some(g) = groups.get_mut(group) {
                g.0[*i].totals.add(r);
            }
            continue;
        }
        let mut totals = UsageTotals::default();
        totals.add(r);
        let title = ix
            .title
            .clone()
            .or_else(|| ix.summary.clone())
            .map(|t| crate::redact::redact_text(&t));
        let entry = groups.get_mut(&name).expect("inserted above");
        seen.insert(id.clone(), (name.clone(), entry.0.len()));
        entry.0.push(SessionUsage {
            id,
            app: r.app.clone(),
            title,
            cwd: Some(cwd.to_string()),
            totals,
        });
    }
    let mut projects: Vec<ProjectUsage> = groups
        .into_iter()
        .map(|(name, (mut sessions, cwds))| {
            let mut totals = UsageTotals::default();
            for s in &sessions {
                totals.merge(&s.totals);
            }
            sessions.sort_by(|a, b| cost_desc(&a.totals, &b.totals));
            let cwd = cwds
                .into_iter()
                .max_by(|a, b| {
                    a.1.partial_cmp(&b.1)
                        .unwrap_or(std::cmp::Ordering::Equal)
                        .then(b.0.cmp(&a.0))
                })
                .map(|(c, _)| c);
            ProjectUsage {
                project: name,
                cwd,
                session_count: sessions.len(),
                totals,
                sessions,
            }
        })
        .collect();
    projects.sort_by(|a, b| cost_desc(&a.totals, &b.totals).then(a.project.cmp(&b.project)));

    if let Some(want) = only {
        projects.retain(|p| {
            p.project == want || p.sessions.iter().any(|s| s.cwd.as_deref() == Some(want))
        });
        projects.truncate(1);
        for p in &mut projects {
            p.sessions.truncate(SESSIONS_ONE_PROJECT);
        }
        return ProjectUsageView {
            projects,
            other_projects: None,
            unattributed: None,
        };
    }

    let mut other = UsageTotals::default();
    for p in projects.iter().skip(MAX_PROJECTS) {
        other.merge(&p.totals);
    }
    projects.truncate(MAX_PROJECTS);
    for p in &mut projects {
        p.sessions.truncate(SESSIONS_PER_PROJECT);
    }
    ProjectUsageView {
        projects,
        other_projects: (!other.is_empty()).then_some(other),
        unattributed: (!unattributed.is_empty()).then_some(unattributed),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ix(provider: &str, id: &str, cwd: &str, title: &str) -> IndexedSession {
        IndexedSession {
            provider_id: provider.into(),
            session_id: id.into(),
            title: Some(title.into()),
            summary: None,
            project_dir: Some(cwd.into()),
            created_at_ms: None,
            last_active_at_ms: None,
            source_path: None,
            resume_command: None,
        }
    }

    fn row(app: &str, sid: Option<&str>, cost: f64, last: i64) -> SessionUsageRow {
        SessionUsageRow {
            app: app.into(),
            session_id: sid.map(Into::into),
            requests: 2,
            cost_usd: cost,
            input_tokens: 100,
            output_tokens: 10,
            cache_read_tokens: 1000,
            cache_creation_tokens: 50,
            last_ms: Some(last),
        }
    }

    #[test]
    fn sessions_are_grouped_by_project_and_sorted_by_cost() {
        let index = vec![
            ix("claude", "a", "/w/app", "Fix login"),
            ix("claude", "b", "/w/app/.claude/worktrees/x", "Worktree task"),
            ix("codex", "c", "/w/docs", "Notes"),
            ix("claude", "d", "", "No cwd"),
        ];
        let rows = vec![
            row("claude", Some("a"), 1.0, 10),
            row("claude", Some("b"), 3.0, 20),
            row("codex", Some("c"), 0.5, 30),
            row("claude", Some("d"), 0.25, 5),
            row("claude", None, 0.75, 6),
            row("claude", Some("gone"), 0.1, 7),
            // Same id as "c" but another app type: not the same session.
            row("claude", Some("c"), 0.2, 8),
        ];
        let v = group_by_project(&rows, &index, None);
        assert_eq!(v.projects.len(), 2);
        let app = &v.projects[0];
        assert_eq!(app.project, "app");
        assert_eq!(app.session_count, 2);
        assert!((app.totals.cost_usd - 4.0).abs() < 1e-9);
        assert_eq!(app.totals.requests, 4);
        assert_eq!(app.totals.cache_read_tokens, 2000);
        assert_eq!(app.totals.last_ms, Some(20));
        // The worktree had most of the cost, so it is the project's directory.
        assert_eq!(app.cwd.as_deref(), Some("/w/app/.claude/worktrees/x"));
        assert_eq!(app.sessions[0].id, "claude:b");
        assert_eq!(app.sessions[1].id, "claude:a");
        assert_eq!(app.sessions[1].title.as_deref(), Some("Fix login"));
        assert_eq!(v.projects[1].project, "docs");
        assert_eq!(v.projects[1].sessions[0].id, "codex:c");
        let un = v.unattributed.unwrap();
        assert!((un.cost_usd - (0.25 + 0.75 + 0.1 + 0.2)).abs() < 1e-9);
        assert_eq!(un.requests, 8);
        assert!(v.other_projects.is_none());
    }

    #[test]
    fn one_project_by_name_or_directory() {
        let index = vec![
            ix("claude", "a", "/w/app", "A"),
            ix("claude", "b", "/w/other", "B"),
        ];
        let rows = vec![
            row("claude", Some("a"), 1.0, 1),
            row("claude", Some("b"), 2.0, 2),
        ];
        let v = group_by_project(&rows, &index, Some("app"));
        assert_eq!(v.projects.len(), 1);
        assert_eq!(v.projects[0].project, "app");
        assert!(v.unattributed.is_none());
        let v = group_by_project(&rows, &index, Some("/w/other"));
        assert_eq!(v.projects[0].project, "other");
        assert!(group_by_project(&rows, &index, Some("nope"))
            .projects
            .is_empty());
    }

    #[test]
    fn many_projects_are_capped_and_summed() {
        let index: Vec<_> = (0..60)
            .map(|i| ix("claude", &format!("s{i}"), &format!("/p/proj{i}"), "t"))
            .collect();
        let rows: Vec<_> = (0..60)
            .map(|i| row("claude", Some(&format!("s{i}")), i as f64, i))
            .collect();
        let v = group_by_project(&rows, &index, None);
        assert_eq!(v.projects.len(), MAX_PROJECTS);
        assert_eq!(v.projects[0].project, "proj59");
        let other = v.other_projects.unwrap();
        // proj0..proj9 = 0+1+…+9
        assert!((other.cost_usd - 45.0).abs() < 1e-9);
    }

    #[test]
    fn titles_are_redacted_and_negative_values_ignored() {
        let index = vec![ix(
            "claude",
            "a",
            "/w/app",
            "use key sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        )];
        let mut r = row("claude", Some("a"), -5.0, 1);
        r.input_tokens = -3;
        let v = group_by_project(&[r], &index, None);
        let s = &v.projects[0].sessions[0];
        assert!(!s.title.as_deref().unwrap().contains("AAAAAAAAAAAAAAAA"));
        assert_eq!(s.totals.cost_usd, 0.0);
        assert_eq!(s.totals.input_tokens, 0);
    }

    #[test]
    fn rows_parse_from_backend_json() {
        let v: Vec<SessionUsageRow> = serde_json::from_value(serde_json::json!([
            {"app":"claude","sessionId":"a","requests":3,"costUsd":0.5,"inputTokens":1,"outputTokens":2,
             "cacheReadTokens":3,"cacheCreationTokens":4,"lastMs":9},
            {"app":"codex","sessionId":null}
        ]))
        .unwrap();
        assert_eq!(v[0].cache_creation_tokens, 4);
        assert_eq!(v[1].session_id, None);
    }
}
