//! Per-session event log: assigns per-session `seq`, supports in-place updates
//! by event id, keeps full payloads (bounded), and publishes changes to the
//! global event bus as `session.event`.

use std::collections::HashMap;
use std::sync::Arc;

use serde_json::json;

use super::model::{AgentEvent, EventDraft};
use crate::events::EventBus;

/// Max bytes of out-of-band payload kept per session.
pub const PAYLOAD_BUDGET: usize = 32 * 1024 * 1024;

pub struct SessionLog {
    pub session_id: String,
    events: Vec<AgentEvent>,
    index: HashMap<String, usize>,
    payloads: HashMap<String, String>,
    payload_bytes: usize,
    bus: Option<Arc<EventBus>>,
    /// Messages sent from a phone, waiting for their `user_message` event:
    /// (normalized text, client id). The event gets `data.clientId` so the
    /// phone can replace its optimistic bubble exactly.
    expected: Vec<(String, String, std::time::Instant)>,
}

fn normalize(text: &str) -> String {
    text.split_whitespace().collect::<Vec<_>>().join(" ")
}

impl SessionLog {
    pub fn new(session_id: impl Into<String>, bus: Option<Arc<EventBus>>) -> Self {
        Self {
            session_id: session_id.into(),
            events: Vec::new(),
            index: HashMap::new(),
            payloads: HashMap::new(),
            payload_bytes: 0,
            bus,
            expected: Vec::new(),
        }
    }

    /// Remember that `text` was just sent with `client_id` (the command id).
    pub fn expect_user_message(&mut self, text: &str, client_id: &str) {
        self.expected
            .retain(|(_, _, at)| at.elapsed() < std::time::Duration::from_secs(600));
        self.expected.push((
            normalize(text),
            client_id.to_string(),
            std::time::Instant::now(),
        ));
    }

    fn tag_client_id(&mut self, d: &mut EventDraft) {
        if d.kind != "user_message" || self.expected.is_empty() || d.data.get("clientId").is_some()
        {
            return;
        }
        let text = normalize(d.data.get("text").and_then(|t| t.as_str()).unwrap_or(""));
        if let Some(i) = self.expected.iter().position(|(t, _, _)| *t == text) {
            let (_, id, _) = self.expected.remove(i);
            d.data["clientId"] = json!(id);
        }
    }

    pub fn len(&self) -> usize {
        self.events.len()
    }

    pub fn is_empty(&self) -> bool {
        self.events.is_empty()
    }

    pub fn last_seq(&self) -> u64 {
        self.events.last().map(|e| e.seq).unwrap_or(0)
    }

    /// Insert a new event or replace the existing one with the same id
    /// (keeping its seq). Returns the stored event.
    pub fn upsert(&mut self, mut d: EventDraft, publish: bool) -> AgentEvent {
        if !self.index.contains_key(&d.id) {
            self.tag_client_id(&mut d);
        }
        if d.kind == "user_message" {
            split_attachment_block(&mut d);
        }
        if let Some(p) = d.payload.clone() {
            self.store_payload(&d.id, p);
        }
        let ev = if let Some(&i) = self.index.get(&d.id) {
            let existing = &mut self.events[i];
            existing.ts = d.ts;
            existing.kind = d.kind.to_string();
            existing.turn_id = d.turn_id.or(existing.turn_id.take());
            existing.parent_id = d.parent_id.or(existing.parent_id.take());
            existing.streaming = d.streaming;
            existing.data = d.data;
            existing.clone()
        } else {
            let seq = self.last_seq() + 1;
            let ev = AgentEvent {
                id: d.id.clone(),
                seq,
                session_id: self.session_id.clone(),
                turn_id: d.turn_id,
                ts: d.ts,
                kind: d.kind.to_string(),
                parent_id: d.parent_id,
                streaming: d.streaming,
                data: d.data,
            };
            self.index.insert(d.id, self.events.len());
            self.events.push(ev.clone());
            ev
        };
        if publish {
            if let Some(bus) = &self.bus {
                bus.publish("session.event", json!(ev));
            }
        }
        ev
    }

    /// Mutate an existing event's data in place.
    pub fn update<F: FnOnce(&mut AgentEvent)>(
        &mut self,
        id: &str,
        publish: bool,
        f: F,
    ) -> Option<AgentEvent> {
        let i = *self.index.get(id)?;
        f(&mut self.events[i]);
        let ev = self.events[i].clone();
        if publish {
            if let Some(bus) = &self.bus {
                bus.publish("session.event", json!(ev));
            }
        }
        Some(ev)
    }

    pub fn get(&self, id: &str) -> Option<&AgentEvent> {
        self.index.get(id).map(|&i| &self.events[i])
    }

    fn store_payload(&mut self, id: &str, text: String) {
        if self.payload_bytes + text.len() > PAYLOAD_BUDGET {
            return; // preview only; `truncated` stays true in the event
        }
        self.payload_bytes += text.len();
        if let Some(old) = self.payloads.insert(id.to_string(), text) {
            self.payload_bytes -= old.len();
        }
    }

    pub fn payload(&self, id: &str) -> Option<&str> {
        self.payloads.get(id).map(|s| s.as_str())
    }

    /// Page of events. `before`/`after` are exclusive seq bounds; default is
    /// the newest `limit` events. Returned oldest-first.
    pub fn page(
        &self,
        before: Option<u64>,
        after: Option<u64>,
        limit: usize,
    ) -> (Vec<AgentEvent>, bool) {
        let limit = limit.clamp(1, 500);
        if let Some(a) = after {
            let start = self.events.partition_point(|e| e.seq <= a);
            let end = (start + limit).min(self.events.len());
            return (self.events[start..end].to_vec(), end < self.events.len());
        }
        let end = match before {
            Some(b) => self.events.partition_point(|e| e.seq < b),
            None => self.events.len(),
        };
        let start = end.saturating_sub(limit);
        (self.events[start..end].to_vec(), start > 0)
    }

    pub fn iter(&self) -> impl Iterator<Item = &AgentEvent> {
        self.events.iter()
    }
}

/// A message sent with phone attachments carries a block of paths (see `attachments::compose`);
/// the timeline shows the user's own text and the files as `data.attachments` instead.
fn split_attachment_block(d: &mut EventDraft) {
    let Some(text) = d.data.get("text").and_then(|t| t.as_str()) else {
        return;
    };
    if let Some((user, files)) = super::attachments::split(text) {
        d.data["text"] = json!(user);
        d.data["attachments"] = json!(files);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn d(id: &str, kind: &'static str) -> EventDraft {
        EventDraft::new(id, kind, "t", json!({ "id": id }))
    }

    #[test]
    fn upsert_keeps_seq() {
        let mut log = SessionLog::new("s", None);
        log.upsert(d("a", "user_message"), false);
        log.upsert(d("b", "tool_call"), false);
        let mut b2 = d("b", "tool_call");
        b2.data = json!({ "status": "completed" });
        let ev = log.upsert(b2, false);
        assert_eq!(ev.seq, 2);
        assert_eq!(log.len(), 2);
        assert_eq!(log.get("b").unwrap().data["status"], "completed");
    }

    #[test]
    fn paging() {
        let mut log = SessionLog::new("s", None);
        for i in 0..10 {
            log.upsert(d(&format!("e{i}"), "system"), false);
        }
        let (p, more) = log.page(None, None, 3);
        assert_eq!(p.iter().map(|e| e.seq).collect::<Vec<_>>(), vec![8, 9, 10]);
        assert!(more);
        let (p, more) = log.page(Some(8), None, 3);
        assert_eq!(p.iter().map(|e| e.seq).collect::<Vec<_>>(), vec![5, 6, 7]);
        assert!(more);
        let (p, more) = log.page(None, Some(8), 10);
        assert_eq!(p.iter().map(|e| e.seq).collect::<Vec<_>>(), vec![9, 10]);
        assert!(!more);
        let (p, more) = log.page(Some(2), None, 10);
        assert_eq!(p.len(), 1);
        assert!(!more);
    }

    #[test]
    fn publishes_to_bus() {
        let bus = EventBus::new(16);
        let mut log = SessionLog::new("s", Some(bus.clone()));
        log.upsert(d("a", "user_message"), true);
        assert_eq!(bus.head_seq(), 1);
    }
}
