use crate::{CallContext, CallOptions};
use phantom_domain::*;
use phantom_ports::*;
use std::{
    collections::{BTreeMap, VecDeque},
    future::poll_fn,
    sync::{
        Arc, Mutex, Weak,
        atomic::{AtomicBool, Ordering},
    },
    task::{Poll, Waker},
};

#[derive(Default)]
struct Queue {
    events: VecDeque<SdkEvent>,
    error: Option<SdkError>,
    closed: bool,
    waiter: Option<Waker>,
}
struct Subscriber {
    capacity: usize,
    queue: Mutex<Queue>,
    consuming: AtomicBool,
}
#[derive(Default)]
struct Hub {
    next_id: u64,
    next_subscriber: u64,
    released: bool,
    subscribers: BTreeMap<u64, Weak<Subscriber>>,
}
#[derive(Clone, Default)]
pub struct EventHub(Arc<Mutex<Hub>>);
pub struct EventSubscription {
    inner: Arc<Subscriber>,
    hub: Weak<Mutex<Hub>>,
    id: u64,
    clock: Arc<dyn Clock>,
}
impl EventHub {
    pub fn subscribe(
        &self,
        options: SubscriptionOptions,
        clock: Arc<dyn Clock>,
    ) -> SdkResult<EventSubscription> {
        if options.capacity == 0 {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "事件队列容量必须大于零",
            ));
        }
        let mut hub = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if hub.released {
            return Err(SdkError::new(ErrorKind::Released, "SDK 已释放"));
        }
        let id = hub
            .next_subscriber
            .checked_add(1)
            .ok_or_else(|| SdkError::new(ErrorKind::Conflict, "订阅编号耗尽"))?;
        hub.next_subscriber = id;
        let inner = Arc::new(Subscriber {
            capacity: options.capacity as usize,
            queue: Mutex::new(Queue::default()),
            consuming: AtomicBool::new(false),
        });
        hub.subscribers.insert(id, Arc::downgrade(&inner));
        Ok(EventSubscription {
            inner,
            hub: Arc::downgrade(&self.0),
            id,
            clock,
        })
    }
    pub fn publish(&self, session: Option<SessionInfo>, kind: EventKind) -> SdkResult<()> {
        let mut wake = Vec::new();
        {
            let mut hub = self.0.lock().unwrap_or_else(|e| e.into_inner());
            if hub.released {
                return Ok(());
            }
            hub.next_id = hub
                .next_id
                .checked_add(1)
                .ok_or_else(|| SdkError::new(ErrorKind::Conflict, "事件编号耗尽"))?;
            let event = SdkEvent {
                event_id: hub.next_id,
                session,
                kind,
            };
            hub.subscribers.retain(|_, weak| {
                let Some(sub) = weak.upgrade() else {
                    return false;
                };
                let mut q = sub.queue.lock().unwrap_or_else(|e| e.into_inner());
                if q.closed {
                    return false;
                }
                if q.events.len() == sub.capacity {
                    q.events.clear();
                    q.closed = true;
                    q.error = Some(SdkError::new(
                        ErrorKind::EventLagged,
                        "事件队列溢出；请重新读取快照并订阅",
                    ));
                } else {
                    q.events.push_back(event.clone());
                }
                if let Some(w) = q.waiter.take() {
                    wake.push(w);
                }
                !q.closed
            });
        }
        for w in wake {
            w.wake();
        }
        Ok(())
    }
    /// 账号退出后清除已有队列；释放还会终止等待中的消费者。
    pub fn clear(&self, released: bool) {
        let mut wake = Vec::new();
        {
            let mut hub = self.0.lock().unwrap_or_else(|e| e.into_inner());
            hub.released |= released;
            for sub in hub.subscribers.values().filter_map(Weak::upgrade) {
                let mut q = sub.queue.lock().unwrap_or_else(|e| e.into_inner());
                q.events.clear();
                if released {
                    q.closed = true;
                }
                if let Some(w) = q.waiter.take() {
                    wake.push(w);
                }
            }
            if released {
                hub.subscribers.clear();
            }
        }
        for w in wake {
            w.wake();
        }
    }
}
struct ConsumerGuard<'a>(&'a AtomicBool);
impl Drop for ConsumerGuard<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}
impl EventSubscription {
    pub async fn next(&self, call: CallOptions) -> SdkResult<Option<SdkEvent>> {
        if self.inner.consuming.swap(true, Ordering::AcqRel) {
            return Err(SdkError::new(ErrorKind::Busy, "同一订阅已有 next 消费者"));
        }
        let _guard = ConsumerGuard(&self.inner.consuming);
        let context = CallContext::new(call, None, self.clock.clone())?;
        context
            .run(poll_fn(|cx| {
                let mut q = self.inner.queue.lock().unwrap_or_else(|e| e.into_inner());
                if let Some(error) = q.error.take() {
                    return Poll::Ready(Err(error));
                }
                if let Some(event) = q.events.pop_front() {
                    return Poll::Ready(Ok(Some(event)));
                }
                if q.closed {
                    return Poll::Ready(Ok(None));
                }
                q.waiter = Some(cx.waker().clone());
                Poll::Pending
            }))
            .await
    }
    pub fn close(&self) {
        if let Some(hub) = self.hub.upgrade() {
            hub.lock()
                .unwrap_or_else(|e| e.into_inner())
                .subscribers
                .remove(&self.id);
        }
        let wake = {
            let mut q = self.inner.queue.lock().unwrap_or_else(|e| e.into_inner());
            q.closed = true;
            q.events.clear();
            q.error = None;
            q.waiter.take()
        };
        if let Some(w) = wake {
            w.wake();
        }
    }
}
impl Drop for EventSubscription {
    fn drop(&mut self) {
        self.close();
    }
}
