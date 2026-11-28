//! Subscription quota answers kept for a while (`GET /v1/usage/quota/{tool}`, docs/PROTOCOL.md
//! §7.3). Every read on the desktop asks the provider's own usage endpoint (Anthropic, OpenAI,
//! Google), so phones opening Home, the Usage page or reconnecting must not each cause one: an
//! answer is reused while it is younger than the caller's `maxAge` (at least
//! [MIN_MAX_AGE]) and no quota window has reset since it was read. Callers asking at
//! the same time share one read.

use std::collections::HashMap;
use std::future::Future;
use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::Value;

/// Freshness asked for when the phone does not say (older phones).
pub const DEFAULT_MAX_AGE: Duration = Duration::from_secs(60);
/// No caller gets a fresher answer than this: a refresh tapped again and again is still one read.
pub const MIN_MAX_AGE: Duration = Duration::from_secs(30);
/// Longest `maxAge` honoured.
pub const MAX_MAX_AGE: Duration = Duration::from_secs(60 * 60);
/// An unavailable answer (not signed in, provider error) is retried after this at the latest.
pub const FAILURE_TTL: Duration = Duration::from_secs(30);

struct Entry {
    at: Instant,
    at_ms: i64,
    next_reset_ms: Option<i64>,
    value: Value,
    ok: bool,
}

/// Per-tool quota answers; owned by [`crate::api::ApiContext`].
#[derive(Default)]
pub struct QuotaCache {
    slots: parking_lot::Mutex<HashMap<String, Arc<tokio::sync::Mutex<Option<Entry>>>>>,
}

/// `maxAge` in seconds from the query → the freshness used.
pub fn max_age(secs: Option<u64>) -> Duration {
    secs.map(Duration::from_secs)
        .unwrap_or(DEFAULT_MAX_AGE)
        .clamp(MIN_MAX_AGE, MAX_MAX_AGE)
}

impl QuotaCache {
    /// The answer for `tool`, from the cache when it is younger than `max_age` (an unavailable
    /// one at most [FAILURE_TTL]), otherwise from `fetch`. The answer carries `cachedAtMs`: when
    /// the desktop read it from the provider.
    pub async fn get<F, Fut>(&self, tool: &str, max_age: Duration, fetch: F) -> Value
    where
        F: FnOnce() -> Fut,
        Fut: Future<Output = Value>,
    {
        let slot = self
            .slots
            .lock()
            .entry(tool.to_string())
            .or_default()
            .clone();
        // Held across the read: a second caller waits for this read instead of starting its own.
        let mut entry = slot.lock().await;
        if let Some(e) = entry.as_ref() {
            let ttl = if e.ok {
                max_age
            } else {
                max_age.min(FAILURE_TTL)
            };
            if e.at.elapsed() < ttl
                && e.next_reset_ms
                    .is_none_or(|reset| crate::util::now_ms() < reset)
            {
                return with_time(e.value.clone(), e.at_ms);
            }
        }
        let value = fetch().await;
        let ok = value
            .get("available")
            .and_then(Value::as_bool)
            .or_else(|| value.get("success").and_then(Value::as_bool))
            != Some(false);
        let at_ms = crate::util::now_ms();
        let next_reset_ms = ok.then(|| next_reset_ms(&value, at_ms)).flatten();
        *entry = Some(Entry {
            at: Instant::now(),
            at_ms,
            next_reset_ms,
            value: value.clone(),
            ok,
        });
        with_time(value, at_ms)
    }
}

fn next_reset_ms(value: &Value, fetched_at: i64) -> Option<i64> {
    value
        .get("tiers")?
        .as_array()?
        .iter()
        .filter_map(|tier| reset_ms(tier.get("resetsAt")?))
        .filter(|&at| at > fetched_at)
        .min()
}

fn reset_ms(value: &Value) -> Option<i64> {
    let raw = value.as_str().map(str::trim);
    if let Some(n) = value.as_i64().or_else(|| raw?.parse::<i64>().ok()) {
        return if n >= 1_000_000_000_000 {
            Some(n)
        } else {
            n.checked_mul(1_000)
        };
    }
    let raw = raw?;
    chrono::DateTime::parse_from_rfc3339(raw)
        .map(|at| at.timestamp_millis())
        .or_else(|_| {
            chrono::NaiveDateTime::parse_from_str(&raw.replace(' ', "T"), "%Y-%m-%dT%H:%M:%S%.f")
                .map(|at| at.and_utc().timestamp_millis())
        })
        .ok()
}

fn with_time(mut v: Value, at_ms: i64) -> Value {
    if let Some(o) = v.as_object_mut() {
        o.insert("cachedAtMs".into(), Value::from(at_ms));
    }
    v
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[tokio::test]
    async fn answers_are_reused_while_fresh_and_shared_between_callers() {
        let cache = Arc::new(QuotaCache::default());
        let reads = Arc::new(AtomicUsize::new(0));
        let fetch = |reads: Arc<AtomicUsize>| async move {
            reads.fetch_add(1, Ordering::SeqCst);
            tokio::time::sleep(Duration::from_millis(50)).await;
            json!({ "tool": "claude", "available": true, "tiers": [] })
        };
        // Three callers at once: one read.
        let (a, b, c) = tokio::join!(
            cache.get("claude", max_age(Some(300)), || fetch(reads.clone())),
            cache.get("claude", max_age(Some(300)), || fetch(reads.clone())),
            cache.get("claude", max_age(Some(300)), || fetch(reads.clone())),
        );
        assert_eq!(reads.load(Ordering::SeqCst), 1);
        assert_eq!(a["cachedAtMs"], b["cachedAtMs"]);
        assert_eq!(b["cachedAtMs"], c["cachedAtMs"]);
        // Later, still fresh: no read; another tool reads on its own.
        cache
            .get("claude", max_age(Some(300)), || fetch(reads.clone()))
            .await;
        cache
            .get("codex", max_age(Some(300)), || fetch(reads.clone()))
            .await;
        assert_eq!(reads.load(Ordering::SeqCst), 2);
    }

    #[tokio::test]
    async fn stale_and_failed_answers_are_read_again() {
        let cache = QuotaCache::default();
        let reads = AtomicUsize::new(0);
        let failing = || async {
            reads.fetch_add(1, Ordering::SeqCst);
            json!({ "tool": "codex", "available": false, "reason": "not signed in" })
        };
        cache.get("codex", MAX_MAX_AGE, failing).await;
        // Expire the entry by hand (FAILURE_TTL is 30 s).
        {
            let slot = cache.slots.lock().get("codex").unwrap().clone();
            slot.lock().await.as_mut().unwrap().at = Instant::now() - FAILURE_TTL;
        }
        cache.get("codex", MAX_MAX_AGE, failing).await;
        assert_eq!(reads.load(Ordering::SeqCst), 2);
    }

    #[tokio::test]
    async fn a_reset_invalidates_even_a_young_answer_once() {
        let cache = QuotaCache::default();
        let now = crate::util::now_ms();
        let old = json!({
            "available": true,
            "tiers": [{ "resetsAt": (now + 60_000).to_string(), "utilization": 100 }]
        });
        cache.get("claude", MAX_MAX_AGE, || async { old }).await;
        let slot = cache.slots.lock().get("claude").unwrap().clone();
        {
            let mut entry = slot.lock().await;
            let entry = entry.as_mut().unwrap();
            assert_eq!(entry.next_reset_ms, Some(now + 60_000));
            entry.next_reset_ms = Some(now);
        }
        let fresh = cache
            .get("claude", MAX_MAX_AGE, || async {
                json!({ "tiers": [{ "resetsAt": now.to_string(), "utilization": 0 }] })
            })
            .await;
        assert_eq!(fresh["tiers"][0]["utilization"], 0);
        let cached = cache
            .get("claude", MAX_MAX_AGE, || async {
                panic!("the new answer should be reused")
            })
            .await;
        assert_eq!(fresh, cached);
    }

    #[test]
    fn max_age_is_clamped() {
        assert_eq!(max_age(None), DEFAULT_MAX_AGE);
        assert_eq!(max_age(Some(0)), MIN_MAX_AGE);
        assert_eq!(max_age(Some(300)), Duration::from_secs(300));
        assert_eq!(max_age(Some(10_000_000)), MAX_MAX_AGE);
    }
}
