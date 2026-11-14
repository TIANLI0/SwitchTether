//! Sequenced event bus with bounded replay.
//!
//! Every event gets a strictly increasing `seq` within an `epoch` (random per
//! process start). Reconnecting clients send their last seen `seq`/`epoch`; if
//! the gap is still in the ring buffer they receive the missed events, otherwise
//! a `resync` instruction tells them to refetch state over REST.

use std::collections::VecDeque;
use std::sync::Arc;

use parking_lot::Mutex;
use serde::Serialize;
use serde_json::Value;
use tokio::sync::broadcast;

pub const DEFAULT_CAPACITY: usize = 10_000;

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct EventFrame {
    #[serde(rename = "type")]
    pub frame_type: &'static str,
    pub seq: u64,
    pub epoch: String,
    pub ts: String,
    pub topic: String,
    pub data: Value,
    /// When set, only this device receives the event.
    #[serde(skip)]
    pub device_id: Option<String>,
}

impl EventFrame {
    pub fn visible_to(&self, device_id: &str) -> bool {
        self.device_id.as_deref().is_none_or(|d| d == device_id)
    }
}

pub enum Replay {
    Events(Vec<Arc<EventFrame>>),
    /// The client must refetch state; `head_seq` is the current head.
    Resync {
        head_seq: u64,
        reason: &'static str,
    },
}

struct Inner {
    next_seq: u64,
    ring: VecDeque<Arc<EventFrame>>,
}

pub struct EventBus {
    epoch: String,
    capacity: usize,
    inner: Mutex<Inner>,
    tx: broadcast::Sender<Arc<EventFrame>>,
}

impl EventBus {
    pub fn new(capacity: usize) -> Arc<Self> {
        let (tx, _) = broadcast::channel(1024);
        Arc::new(Self {
            epoch: crate::util::new_id(),
            capacity: capacity.max(16),
            inner: Mutex::new(Inner {
                next_seq: 1,
                ring: VecDeque::new(),
            }),
            tx,
        })
    }

    pub fn epoch(&self) -> &str {
        &self.epoch
    }

    pub fn head_seq(&self) -> u64 {
        self.inner.lock().next_seq - 1
    }

    pub fn publish(&self, topic: impl Into<String>, data: Value) -> u64 {
        self.publish_inner(topic.into(), data, None)
    }

    pub fn publish_to(&self, device_id: &str, topic: impl Into<String>, data: Value) -> u64 {
        self.publish_inner(topic.into(), data, Some(device_id.to_string()))
    }

    fn publish_inner(&self, topic: String, data: Value, device_id: Option<String>) -> u64 {
        // Sequence assignment and broadcast happen under the same lock so the
        // broadcast order always equals seq order.
        let mut inner = self.inner.lock();
        let seq = inner.next_seq;
        inner.next_seq += 1;
        let frame = Arc::new(EventFrame {
            frame_type: "event",
            seq,
            epoch: self.epoch.clone(),
            ts: crate::util::now_rfc3339(),
            topic,
            data,
            device_id,
        });
        inner.ring.push_back(frame.clone());
        while inner.ring.len() > self.capacity {
            inner.ring.pop_front();
        }
        let _ = self.tx.send(frame);
        seq
    }

    pub fn subscribe(&self) -> broadcast::Receiver<Arc<EventFrame>> {
        self.tx.subscribe()
    }

    /// Events after `since` for a client that last saw `epoch`.
    pub fn replay(&self, since: u64, epoch: Option<&str>) -> Replay {
        let inner = self.inner.lock();
        let head = inner.next_seq - 1;
        if let Some(e) = epoch {
            if e != self.epoch {
                return Replay::Resync {
                    head_seq: head,
                    reason: "epoch_changed",
                };
            }
        } else if since > 0 {
            return Replay::Resync {
                head_seq: head,
                reason: "epoch_changed",
            };
        }
        if since >= head {
            return Replay::Events(Vec::new());
        }
        let oldest = inner.ring.front().map(|f| f.seq).unwrap_or(head + 1);
        if since + 1 < oldest {
            return Replay::Resync {
                head_seq: head,
                reason: "gap",
            };
        }
        Replay::Events(
            inner
                .ring
                .iter()
                .filter(|f| f.seq > since)
                .cloned()
                .collect(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn sequence_and_replay() {
        let bus = EventBus::new(16);
        let epoch = bus.epoch().to_string();
        for i in 0..5 {
            bus.publish("t", json!({ "i": i }));
        }
        assert_eq!(bus.head_seq(), 5);
        match bus.replay(2, Some(&epoch)) {
            Replay::Events(ev) => {
                assert_eq!(ev.iter().map(|e| e.seq).collect::<Vec<_>>(), vec![3, 4, 5])
            }
            _ => panic!(),
        }
        assert!(matches!(bus.replay(5, Some(&epoch)), Replay::Events(v) if v.is_empty()));
        assert!(matches!(
            bus.replay(2, Some("other")),
            Replay::Resync {
                reason: "epoch_changed",
                ..
            }
        ));
        // fresh client (since=0, no epoch) gets the retained backlog
        assert!(matches!(bus.replay(0, None), Replay::Events(v) if v.len() == 5));
    }

    #[test]
    fn gap_forces_resync() {
        let bus = EventBus::new(16);
        let epoch = bus.epoch().to_string();
        for _ in 0..40 {
            bus.publish("t", json!(null));
        }
        assert!(matches!(
            bus.replay(3, Some(&epoch)),
            Replay::Resync {
                reason: "gap",
                head_seq: 40
            }
        ));
        assert!(matches!(bus.replay(30, Some(&epoch)), Replay::Events(v) if v.len() == 10));
    }

    #[test]
    fn device_scoped_events() {
        let bus = EventBus::new(16);
        bus.publish_to("a", "cmd", json!(1));
        let Replay::Events(ev) = bus.replay(0, None) else {
            panic!()
        };
        assert!(ev[0].visible_to("a"));
        assert!(!ev[0].visible_to("b"));
    }

    #[tokio::test]
    async fn live_subscription_in_order() {
        let bus = EventBus::new(16);
        let mut rx = bus.subscribe();
        bus.publish("x", json!(1));
        bus.publish("y", json!(2));
        assert_eq!(rx.recv().await.unwrap().seq, 1);
        assert_eq!(rx.recv().await.unwrap().seq, 2);
    }
}
