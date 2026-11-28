//! What the paired phones did through SwitchTether (`GET /v1/stats/remote`, docs/PROTOCOL.md
//! §7.15): tasks started, messages sent, approvals and questions answered, how long the agents
//! worked on turns a phone started, and how long the app was in the foreground.
//!
//! Counters are kept per desktop-local day and device in `remote.db` (`stats_daily`, kept for
//! good); `stats_sessions` remembers which sessions a phone drove so their cost can be summed from
//! the usage logs. No message text, path or secret is stored here.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::Arc;

use chrono::{Duration as ChronoDuration, Local, NaiveDate, TimeZone};
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio_util::sync::CancellationToken;

use crate::events::EventBus;
use crate::registry::{DeviceRecord, Registry};
use crate::usage_projects::{SessionUsageRow, UsageTotals};
use crate::util::now_ms;

pub const M_TASKS: &str = "tasks";
pub const M_MESSAGES: &str = "messages";
pub const M_APPROVALS_ALLOWED: &str = "approvals_allowed";
pub const M_APPROVALS_DENIED: &str = "approvals_denied";
pub const M_ANSWERS: &str = "answers";
pub const M_TASK_MS: &str = "task_ms";
pub const M_FOREGROUND_MS: &str = "foreground_ms";

/// A turn whose session reports no activity this long after the phone's message has ended
/// (the agent answered before any `busy` status was published, or the status was missed).
const START_GRACE_MS: i64 = 30_000;
/// One stretch of work counts at most this long (a status change that never came).
const MAX_SEGMENT_MS: i64 = 6 * 60 * 60 * 1000;
/// Turns without any status change for this long are forgotten.
const STALE_TURN_MS: i64 = 24 * 60 * 60 * 1000;
/// Days are listed one by one (zeros included) up to this many; longer ranges list days with data.
const MAX_FILLED_DAYS: i64 = 92;
/// Foreground intervals accepted per report, and how old they may be.
pub const MAX_FOREGROUND_INTERVALS: usize = 200;
const MAX_FOREGROUND_AGE_MS: i64 = 35 * 24 * 60 * 60 * 1000;
const MAX_FOREGROUND_INTERVAL_MS: i64 = 24 * 60 * 60 * 1000;
const CLOCK_SKEW_MS: i64 = 5 * 60 * 1000;

/// The counter an approval decision goes to.
pub fn approval_metric(decision: &str) -> &'static str {
    if decision.starts_with("allow") {
        M_APPROVALS_ALLOWED
    } else {
        M_APPROVALS_DENIED
    }
}

fn local_date(ms: i64) -> NaiveDate {
    Local
        .timestamp_millis_opt(ms)
        .earliest()
        .map(|d| d.date_naive())
        .unwrap_or_else(|| {
            chrono::DateTime::from_timestamp_millis(ms)
                .unwrap_or_default()
                .date_naive()
        })
}

/// Desktop-local day of an epoch-ms instant, `YYYY-MM-DD`.
pub fn local_day(ms: i64) -> String {
    local_date(ms).format("%Y-%m-%d").to_string()
}

/// Epoch ms of the desktop-local start of `date` (the first valid instant on DST days).
fn day_start_ms(date: NaiveDate) -> i64 {
    let midnight = date.and_hms_opt(0, 0, 0).unwrap_or_default();
    match Local.from_local_datetime(&midnight).earliest() {
        Some(t) => t.timestamp_millis(),
        // Midnight skipped by a DST change: the day starts an hour later.
        None => Local
            .from_local_datetime(&(midnight + ChronoDuration::hours(1)))
            .earliest()
            .map(|t| t.timestamp_millis())
            .unwrap_or_else(|| midnight.and_utc().timestamp_millis()),
    }
}

/// Splits `[start_ms, end_ms)` at desktop-local midnights: `(day, ms in that day)`.
pub fn split_by_day(start_ms: i64, end_ms: i64) -> Vec<(String, i64)> {
    let mut out = Vec::new();
    let mut t = start_ms;
    while t < end_ms {
        let date = local_date(t);
        let next = date
            .succ_opt()
            .map(day_start_ms)
            .unwrap_or(end_ms)
            .max(t + 1);
        let stop = next.min(end_ms);
        out.push((date.format("%Y-%m-%d").to_string(), stop - t));
        t = stop;
    }
    out
}

struct Turn {
    device_id: String,
    sent_ms: i64,
    /// Start of the stretch the agent is working right now.
    open_since: Option<i64>,
    /// A `busy` / `waiting` status was seen since the message.
    seen_active: bool,
    /// The session was idle (not yet picked the message up, or already done) since then.
    idle_since: Option<i64>,
    last_change_ms: i64,
}

fn is_active(status: &str) -> bool {
    matches!(status, "busy" | "waiting")
}

/// Counts phone actions as they happen and times the turns phones start.
pub struct StatsTracker {
    registry: Arc<Registry>,
    turns: Mutex<HashMap<String, Turn>>,
    /// Last published status of every session (the phone's request can be answered after the
    /// agent already reported `busy`).
    statuses: Mutex<HashMap<String, String>>,
}

impl StatsTracker {
    pub fn new(registry: Arc<Registry>) -> Arc<Self> {
        Arc::new(Self {
            registry,
            turns: Mutex::new(HashMap::new()),
            statuses: Mutex::new(HashMap::new()),
        })
    }

    fn add(&self, device_id: &str, metric: &str, value: i64, at_ms: i64) {
        self.registry
            .stats_add(&local_day(at_ms), device_id, metric, value);
    }

    fn add_time(&self, device_id: &str, metric: &str, start_ms: i64, end_ms: i64) {
        for (day, ms) in split_by_day(start_ms, end_ms) {
            self.registry.stats_add(&day, device_id, metric, ms);
        }
    }

    /// A phone started (or continued) a session; `prompt` when it also sent the first message.
    pub fn task_started(&self, device_id: &str, session_id: &str, prompt: bool) {
        let now = now_ms();
        self.add(device_id, M_TASKS, 1, now);
        self.registry
            .stats_touch_session(session_id, device_id, now);
        if prompt {
            self.add(device_id, M_MESSAGES, 1, now);
            self.begin_turn(device_id, session_id, now);
        }
    }

    /// A phone sent a message to a session.
    pub fn message_sent(&self, device_id: &str, session_id: &str) {
        let now = now_ms();
        self.add(device_id, M_MESSAGES, 1, now);
        self.registry
            .stats_touch_session(session_id, device_id, now);
        self.begin_turn(device_id, session_id, now);
    }

    /// A phone answered an approval request.
    pub fn approval(&self, device_id: &str, session_id: &str, decision: &str) {
        let now = now_ms();
        self.add(device_id, approval_metric(decision), 1, now);
        self.registry
            .stats_touch_session(session_id, device_id, now);
    }

    /// A phone answered (or declined) an agent's question.
    pub fn answered(&self, device_id: &str, session_id: &str) {
        let now = now_ms();
        self.add(device_id, M_ANSWERS, 1, now);
        self.registry
            .stats_touch_session(session_id, device_id, now);
    }

    /// Adds the app's foreground intervals (`[start, end)` epoch ms); returns the ms counted.
    /// Intervals in the future, too old, too long or overlapping one another are trimmed or skipped.
    pub fn foreground(&self, device_id: &str, intervals: &[(i64, i64)], now: i64) -> i64 {
        let mut iv: Vec<(i64, i64)> = intervals
            .iter()
            .map(|&(s, e)| {
                (
                    s.max(now - MAX_FOREGROUND_AGE_MS),
                    e.min(now + CLOCK_SKEW_MS),
                )
            })
            .filter(|&(s, e)| e > s && e - s <= MAX_FOREGROUND_INTERVAL_MS)
            .collect();
        iv.sort_unstable();
        let mut total = 0;
        let mut covered = i64::MIN;
        for (s, e) in iv {
            let s = s.max(covered);
            if e <= s {
                continue;
            }
            self.add_time(device_id, M_FOREGROUND_MS, s, e);
            total += e - s;
            covered = e;
        }
        total
    }

    fn begin_turn(&self, device_id: &str, session_id: &str, now: i64) {
        let active = self
            .statuses
            .lock()
            .get(session_id)
            .is_some_and(|st| is_active(st));
        let mut turns = self.turns.lock();
        let t = turns.entry(session_id.to_string()).or_insert_with(|| Turn {
            device_id: device_id.to_string(),
            sent_ms: now,
            open_since: None,
            seen_active: false,
            idle_since: None,
            last_change_ms: now,
        });
        if t.open_since.is_none() {
            t.open_since = Some(now);
        }
        t.sent_ms = now;
        t.seen_active = active;
        t.idle_since = (!active).then_some(now);
        t.last_change_ms = now;
    }

    /// Adds the stretch the agent is working on now, up to `now`.
    fn close(&self, t: &mut Turn, now: i64) {
        if let Some(s) = t.open_since.take() {
            let end = now.min(s + MAX_SEGMENT_MS);
            if end > s {
                self.add_time(&t.device_id, M_TASK_MS, s, end);
            }
        }
    }

    fn finish(&self, id: &str, mut t: Turn, end: i64) {
        self.close(&mut t, end);
        self.registry.stats_touch_session(id, &t.device_id, end);
    }

    /// Follows `session.upserted` / `session.removed` frames.
    pub fn observe(&self, topic: &str, data: &Value, now: i64) {
        let Some(id) = data.get("id").and_then(Value::as_str) else {
            return;
        };
        let status = data.get("status").and_then(Value::as_str).unwrap_or("");
        {
            let mut st = self.statuses.lock();
            if topic == "session.removed" {
                st.remove(id);
            } else if topic == "session.upserted" && !status.is_empty() {
                if st.len() > 5_000 && !st.contains_key(id) {
                    st.clear();
                }
                st.insert(id.to_string(), status.to_string());
            }
        }
        let mut turns = self.turns.lock();
        let Some(t) = turns.get_mut(id) else {
            return;
        };
        let finished = match topic {
            "session.removed" => true,
            "session.upserted" => {
                let ended = data.get("liveness").and_then(Value::as_str) == Some("ended");
                match status {
                    _ if ended => true,
                    "busy" => {
                        t.seen_active = true;
                        t.idle_since = None;
                        t.open_since.get_or_insert(now);
                        t.last_change_ms = now;
                        false
                    }
                    "waiting" => {
                        t.seen_active = true;
                        t.idle_since = None;
                        self.close(t, now);
                        t.last_change_ms = now;
                        false
                    }
                    // A status from before the agent picked the message up; the sweep ends the
                    // turn if nothing else follows.
                    _ if !t.seen_active && now - t.sent_ms < START_GRACE_MS => {
                        t.idle_since.get_or_insert(now);
                        false
                    }
                    _ => true,
                }
            }
            _ => false,
        };
        if finished {
            if let Some(t) = turns.remove(id) {
                self.finish(id, t, now);
            }
        }
    }

    /// Ends turns that stayed idle through the grace period after the message (the agent answered
    /// before any `busy` was seen), and forgets turns without news for a day.
    pub fn sweep(&self, now: i64) {
        let mut turns = self.turns.lock();
        let done: Vec<String> = turns
            .iter()
            .filter(|(_, t)| {
                (!t.seen_active && t.idle_since.is_some() && now - t.sent_ms >= START_GRACE_MS)
                    || now - t.last_change_ms >= STALE_TURN_MS
            })
            .map(|(k, _)| k.clone())
            .collect();
        for id in done {
            if let Some(t) = turns.remove(&id) {
                let end = t.idle_since.unwrap_or(now).min(now);
                self.finish(&id, t, end);
            }
        }
    }

    /// Watch the event bus until `cancel`.
    pub fn start(self: &Arc<Self>, events: Arc<EventBus>, cancel: CancellationToken) {
        let this = self.clone();
        let mut rx = events.subscribe();
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(std::time::Duration::from_secs(10));
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            loop {
                let frame = tokio::select! {
                    _ = cancel.cancelled() => return,
                    _ = tick.tick() => {
                        let this = this.clone();
                        let _ = tokio::task::spawn_blocking(move || this.sweep(now_ms())).await;
                        continue;
                    }
                    f = rx.recv() => f,
                };
                let frame = match frame {
                    Ok(f) => f,
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => return,
                };
                if matches!(frame.topic.as_str(), "session.upserted" | "session.removed") {
                    let this = this.clone();
                    let _ = tokio::task::spawn_blocking(move || {
                        this.observe(&frame.topic, &frame.data, now_ms())
                    })
                    .await;
                }
            }
        });
    }
}

// ---------------------------------------------------------------------------------------------
// The view
// ---------------------------------------------------------------------------------------------

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct StatsCounters {
    pub tasks: i64,
    pub messages: i64,
    pub approvals_allowed: i64,
    pub approvals_denied: i64,
    pub answers: i64,
    pub task_ms: i64,
    pub foreground_ms: i64,
}

impl StatsCounters {
    fn add(&mut self, metric: &str, v: i64) {
        let v = v.max(0);
        match metric {
            M_TASKS => self.tasks += v,
            M_MESSAGES => self.messages += v,
            M_APPROVALS_ALLOWED => self.approvals_allowed += v,
            M_APPROVALS_DENIED => self.approvals_denied += v,
            M_ANSWERS => self.answers += v,
            M_TASK_MS => self.task_ms += v,
            M_FOREGROUND_MS => self.foreground_ms += v,
            _ => {}
        }
    }

    fn is_empty(&self) -> bool {
        *self == Self::default()
    }
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DayStats {
    /// Desktop-local day, `YYYY-MM-DD`.
    pub date: String,
    /// Epoch ms of the day's desktop-local start.
    pub start_ms: i64,
    #[serde(flatten)]
    pub counters: StatsCounters,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DeviceStats {
    pub device_id: String,
    /// Null for a device that was removed from the registry.
    pub name: Option<String>,
    pub platform: Option<String>,
    pub revoked: bool,
    #[serde(flatten)]
    pub counters: StatsCounters,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RemoteStatsView {
    pub time_zone: Option<String>,
    pub utc_offset_minutes: i32,
    /// First day with any statistics (null: none yet).
    pub since: Option<String>,
    pub from_date: String,
    pub to_date: String,
    pub totals: StatsCounters,
    /// Every day of the range up to 92 days (zeros included), otherwise only days with data.
    pub days: Vec<DayStats>,
    /// Devices with activity in the range, most tasks first.
    pub devices: Vec<DeviceStats>,
    /// Sessions phones drove in the range.
    pub sessions: usize,
    /// Usage (tokens, cost) of those sessions in the range; null when usage is unavailable.
    pub cost: Option<UsageTotals>,
}

/// The day range of `from` / `to` (epoch seconds, inclusive; open ends: first day with data /
/// today) and its epoch-ms bounds.
pub fn resolve_range(
    from_s: Option<i64>,
    to_s: Option<i64>,
    first_day: Option<&str>,
    now: i64,
) -> (NaiveDate, NaiveDate) {
    let today = local_date(now);
    let to = to_s
        .map(|s| local_date(s.saturating_mul(1000)))
        .unwrap_or(today);
    let from = match from_s {
        Some(s) => local_date(s.saturating_mul(1000)),
        None => first_day
            .and_then(|d| NaiveDate::parse_from_str(d, "%Y-%m-%d").ok())
            .unwrap_or(today),
    };
    if from > to {
        (to, to)
    } else {
        (from, to)
    }
}

/// Epoch ms covering `[from, to]` days.
pub fn range_ms(from: NaiveDate, to: NaiveDate) -> (i64, i64) {
    let end = to.succ_opt().map(day_start_ms).unwrap_or(i64::MAX) - 1;
    (day_start_ms(from), end)
}

/// Builds the view from the counters of `[from, to]`.
pub fn build_view(
    rows: &[(String, String, String, i64)],
    devices: &[DeviceRecord],
    from: NaiveDate,
    to: NaiveDate,
    since: Option<String>,
) -> RemoteStatsView {
    let mut totals = StatsCounters::default();
    let mut by_day: BTreeMap<String, StatsCounters> = BTreeMap::new();
    let mut by_device: HashMap<String, StatsCounters> = HashMap::new();
    for (day, device, metric, v) in rows {
        totals.add(metric, *v);
        by_day.entry(day.clone()).or_default().add(metric, *v);
        by_device.entry(device.clone()).or_default().add(metric, *v);
    }
    let fill = (to - from).num_days() < MAX_FILLED_DAYS;
    let days = if fill {
        from.iter_days()
            .take_while(|d| *d <= to)
            .map(|d| {
                let date = d.format("%Y-%m-%d").to_string();
                DayStats {
                    counters: by_day.remove(&date).unwrap_or_default(),
                    date,
                    start_ms: day_start_ms(d),
                }
            })
            .collect()
    } else {
        by_day
            .into_iter()
            .filter(|(_, c)| !c.is_empty())
            .map(|(date, counters)| DayStats {
                start_ms: NaiveDate::parse_from_str(&date, "%Y-%m-%d")
                    .map(day_start_ms)
                    .unwrap_or_default(),
                date,
                counters,
            })
            .collect()
    };
    let known: HashMap<&str, &DeviceRecord> = devices.iter().map(|d| (d.id.as_str(), d)).collect();
    let mut devices: Vec<DeviceStats> = by_device
        .into_iter()
        .filter(|(_, c)| !c.is_empty())
        .map(|(id, counters)| {
            let d = known.get(id.as_str());
            DeviceStats {
                name: d.map(|d| d.name.clone()),
                platform: d.map(|d| d.platform.clone()),
                revoked: d.is_some_and(|d| !d.is_active()),
                device_id: id,
                counters,
            }
        })
        .collect();
    devices.sort_by(|a, b| {
        b.counters
            .tasks
            .cmp(&a.counters.tasks)
            .then(b.counters.messages.cmp(&a.counters.messages))
            .then(a.device_id.cmp(&b.device_id))
    });
    let (time_zone, utc_offset_minutes) = crate::util::desktop_time_zone();
    RemoteStatsView {
        time_zone,
        utc_offset_minutes,
        since,
        from_date: from.format("%Y-%m-%d").to_string(),
        to_date: to.format("%Y-%m-%d").to_string(),
        totals,
        days,
        devices,
        sessions: 0,
        cost: None,
    }
}

/// Sums the usage rows of the sessions in `ids` (session list ids, `claude:<uuid>`).
pub fn phone_cost(rows: &[SessionUsageRow], ids: &HashSet<String>) -> UsageTotals {
    let mut t = UsageTotals::default();
    for r in rows {
        let Some(sid) = r.session_id.as_deref().filter(|s| !s.is_empty()) else {
            continue;
        };
        if ids.contains(&format!("{}:{sid}", r.app)) {
            t.add(r);
        }
    }
    t
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn tracker() -> (Arc<Registry>, Arc<StatsTracker>) {
        let r = Registry::open_in_memory().unwrap();
        (r.clone(), StatsTracker::new(r))
    }

    fn total(r: &Registry, metric: &str) -> i64 {
        r.stats_daily("0000-00-00", "9999-99-99")
            .unwrap()
            .into_iter()
            .filter(|(_, _, m, _)| m == metric)
            .map(|(_, _, _, v)| v)
            .sum()
    }

    #[test]
    fn days_split_at_local_midnight() {
        let d = NaiveDate::from_ymd_opt(2026, 3, 10).unwrap();
        let start = day_start_ms(d);
        let parts = split_by_day(start - 1_000, start + 2_000);
        assert_eq!(parts.len(), 2);
        assert_eq!(parts[0], ("2026-03-09".to_string(), 1_000));
        assert_eq!(parts[1], ("2026-03-10".to_string(), 2_000));
        assert!(split_by_day(5, 5).is_empty());
    }

    #[test]
    fn a_phone_turn_counts_busy_time_only() {
        let (r, s) = tracker();
        s.begin_turn("d1", "claude:a", 1_000);
        // Idle right after the message (before the agent picked it up) does not end the turn.
        s.observe(
            "session.upserted",
            &json!({"id": "claude:a", "status": "idle"}),
            2_000,
        );
        s.observe(
            "session.upserted",
            &json!({"id": "claude:a", "status": "busy"}),
            3_000,
        );
        // Waiting for the user pauses the clock …
        s.observe(
            "session.upserted",
            &json!({"id": "claude:a", "status": "waiting"}),
            11_000,
        );
        s.observe(
            "session.upserted",
            &json!({"id": "claude:a", "status": "busy"}),
            61_000,
        );
        s.observe(
            "session.upserted",
            &json!({"id": "claude:a", "status": "idle"}),
            66_000,
        );
        assert_eq!(total(&r, M_TASK_MS), 10_000 + 5_000);
        // … and other sessions are not timed.
        s.observe(
            "session.upserted",
            &json!({"id": "claude:b", "status": "busy"}),
            70_000,
        );
        s.observe(
            "session.upserted",
            &json!({"id": "claude:b", "status": "idle"}),
            90_000,
        );
        assert_eq!(total(&r, M_TASK_MS), 15_000);
        assert!(s.turns.lock().is_empty());
    }

    #[test]
    fn a_turn_without_status_ends_after_the_grace_period() {
        let (r, s) = tracker();
        s.begin_turn("d1", "codex:t", 0);
        s.observe(
            "session.upserted",
            &json!({"id": "codex:t", "status": "idle"}),
            40_000,
        );
        assert_eq!(total(&r, M_TASK_MS), 40_000);
        s.begin_turn("d1", "codex:t", 100_000);
        s.observe("session.removed", &json!({"id": "codex:t"}), 104_000);
        assert_eq!(total(&r, M_TASK_MS), 44_000);
        // Busy and idle both published before the phone's request was answered: the sweep ends
        // the turn at the message.
        s.observe(
            "session.upserted",
            &json!({"id": "codex:u", "status": "busy"}),
            200_000,
        );
        s.observe(
            "session.upserted",
            &json!({"id": "codex:u", "status": "idle"}),
            200_500,
        );
        s.begin_turn("d1", "codex:u", 201_000);
        s.sweep(210_000);
        assert_eq!(s.turns.lock().len(), 1);
        s.sweep(240_000);
        assert!(s.turns.lock().is_empty());
        assert_eq!(total(&r, M_TASK_MS), 44_000);
    }

    #[test]
    fn a_turn_already_busy_when_registered_ends_on_idle() {
        let (r, s) = tracker();
        s.observe(
            "session.upserted",
            &json!({"id": "claude:c", "status": "busy"}),
            0,
        );
        s.begin_turn("d1", "claude:c", 500);
        s.observe(
            "session.upserted",
            &json!({"id": "claude:c", "status": "idle"}),
            3_500,
        );
        assert_eq!(total(&r, M_TASK_MS), 3_000);
        assert!(s.turns.lock().is_empty());
    }

    #[test]
    fn counters_and_sessions() {
        let (r, s) = tracker();
        s.task_started("d1", "claude:a", true);
        s.message_sent("d1", "claude:a");
        s.approval("d1", "claude:a", "allow_session");
        s.approval("d1", "claude:a", "deny");
        s.answered("d2", "codex:b");
        assert_eq!(total(&r, M_TASKS), 1);
        assert_eq!(total(&r, M_MESSAGES), 2);
        assert_eq!(total(&r, M_APPROVALS_ALLOWED), 1);
        assert_eq!(total(&r, M_APPROVALS_DENIED), 1);
        assert_eq!(total(&r, M_ANSWERS), 1);
        let mut ids = r.stats_session_ids(0, i64::MAX).unwrap();
        ids.sort();
        assert_eq!(ids, vec!["claude:a", "codex:b"]);
    }

    #[test]
    fn foreground_intervals_are_clipped_and_deduplicated() {
        let (r, s) = tracker();
        let now = 10 * 24 * 60 * 60 * 1000;
        let counted = s.foreground(
            "d1",
            &[
                (now - 60_000, now - 30_000),
                (now - 40_000, now - 20_000), // overlaps the first by 10 s
                (now - 10_000, now - 20_000), // reversed
                (now, now + 60 * 60 * 1000),  // future beyond the allowed skew
            ],
            now,
        );
        assert_eq!(counted, 40_000 + CLOCK_SKEW_MS);
        assert_eq!(total(&r, M_FOREGROUND_MS), counted);
    }

    #[test]
    fn view_fills_days_and_sums_devices() {
        let from = NaiveDate::from_ymd_opt(2026, 11, 23).unwrap();
        let to = NaiveDate::from_ymd_opt(2026, 11, 29).unwrap();
        let rows = vec![
            ("2026-11-24".into(), "d1".into(), M_TASKS.into(), 2),
            ("2026-11-24".into(), "d2".into(), M_TASKS.into(), 1),
            ("2026-11-25".into(), "d1".into(), M_TASK_MS.into(), 5_000),
        ];
        let v = build_view(&rows, &[], from, to, Some("2026-11-24".into()));
        assert_eq!(v.days.len(), 7);
        assert_eq!(v.days[1].counters.tasks, 3);
        assert_eq!(v.days[2].counters.task_ms, 5_000);
        assert_eq!(v.totals.tasks, 3);
        assert_eq!(v.devices[0].device_id, "d1");
        assert_eq!(v.devices[0].name, None);
    }

    #[test]
    fn view_matches_the_fixture_shape() {
        fn keys(v: &Value) -> Vec<String> {
            let mut k: Vec<String> = v.as_object().unwrap().keys().cloned().collect();
            k.sort();
            k
        }
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../protocol/fixtures/remote_stats.json");
        let fixture: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        let day = NaiveDate::from_ymd_opt(2026, 11, 23).unwrap();
        let rows = vec![("2026-11-23".into(), "d1".into(), M_TASKS.into(), 1)];
        let mut v = build_view(&rows, &[], day, day, None);
        v.cost = Some(UsageTotals {
            last_ms: Some(1),
            ..Default::default()
        });
        let ours = serde_json::to_value(&v).unwrap();
        assert_eq!(keys(&ours), keys(&fixture));
        assert_eq!(keys(&ours["totals"]), keys(&fixture["totals"]));
        assert_eq!(keys(&ours["days"][0]), keys(&fixture["days"][0]));
        assert_eq!(keys(&ours["devices"][0]), keys(&fixture["devices"][0]));
        assert_eq!(keys(&ours["cost"]), keys(&fixture["cost"]));
        let totals: StatsCounters = serde_json::from_value(fixture["totals"].clone()).unwrap();
        assert_eq!(totals.task_ms, 5_400_000);
    }

    #[test]
    fn cost_is_summed_for_phone_sessions_only() {
        let rows = vec![
            SessionUsageRow {
                app: "claude".into(),
                session_id: Some("a".into()),
                requests: 2,
                cost_usd: 1.5,
                ..Default::default()
            },
            SessionUsageRow {
                app: "claude".into(),
                session_id: Some("x".into()),
                requests: 9,
                cost_usd: 9.0,
                ..Default::default()
            },
        ];
        let ids: HashSet<String> = ["claude:a".to_string()].into();
        let t = phone_cost(&rows, &ids);
        assert_eq!(t.requests, 2);
        assert!((t.cost_usd - 1.5).abs() < 1e-9);
    }

    #[test]
    fn backfill_counts_audited_phone_actions_once() {
        let r = Registry::open_in_memory().unwrap();
        // The in-memory registry has already backfilled (an empty log); run it again by hand.
        r.audit(Some("d1"), "session.message", Some("claude:a"), "ok", None);
        r.audit(
            Some("d1"),
            "session.approval",
            Some("claude:a"),
            "allow",
            Some("r1"),
        );
        r.audit(
            Some("d1"),
            "session.create",
            Some("/tmp"),
            "ok",
            Some("claude:a"),
        );
        r.delete_meta("stats_backfilled");
        r.stats_backfill().unwrap();
        r.stats_backfill().unwrap();
        assert_eq!(total(&r, M_MESSAGES), 1);
        assert_eq!(total(&r, M_APPROVALS_ALLOWED), 1);
        assert_eq!(total(&r, M_TASKS), 1);
    }
}
