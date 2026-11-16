mod support;
use phantom_sdk::*;
use std::{
    sync::{
        Arc, Mutex,
        atomic::{AtomicI64, AtomicU64, Ordering},
    },
    task::Waker,
    time::Duration,
};
#[derive(Default)]
struct Time {
    wall: AtomicI64,
    mono: AtomicU64,
    waiters: Mutex<Vec<Waker>>,
}
impl Time {
    fn advance(&self, ms: u64) {
        self.mono.fetch_add(ms, Ordering::AcqRel);
        for w in std::mem::take(&mut *self.waiters.lock().unwrap()) {
            w.wake();
        }
    }
}
impl Clock for Time {
    fn now_ms(&self) -> i64 {
        self.wall.load(Ordering::Acquire)
    }
    fn monotonic_ms(&self) -> u64 {
        self.mono.load(Ordering::Acquire)
    }
    fn sleep(&self, duration: Duration) -> SdkFuture<'_, ()> {
        let end = self
            .monotonic_ms()
            .saturating_add(duration.as_millis() as u64);
        Box::pin(std::future::poll_fn(move |cx| {
            let mut waiters = self.waiters.lock().unwrap();
            if self.monotonic_ms() >= end {
                std::task::Poll::Ready(Ok(()))
            } else {
                waiters.push(cx.waker().clone());
                std::task::Poll::Pending
            }
        }))
    }
}
async fn until(mut condition: impl FnMut() -> bool) {
    tokio::time::timeout(Duration::from_secs(3), async {
        while !condition() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
}
fn cached(sdk: &PhantomClient, id: &str) -> ChatMessage {
    sdk.get_messages(MessageCacheQuery {
        conv_id: "gr_group".into(),
        before_conv_seq: None,
        limit: 100,
    })
    .unwrap()
    .into_iter()
    .find(|m| m.client_msg_id == id)
    .unwrap()
}
#[tokio::test]
async fn display_uses_monotonic_deadline_despite_wall_clock_changes_and_history_cannot_revive() {
    let server = Arc::new(support::Server::default());
    let time = Arc::new(Time::default());
    time.wall.store(100_000, Ordering::Release);
    let mut host = support::host(&server, Arc::new(MemoryStoreFactory::new()));
    host.clock = time.clone();
    let sdk =
        PhantomClient::with_host(ClientOptions::new("https://example.test", "app"), host).unwrap();
    support::ready(&sdk).await;
    let mut burn = support::message("burn", 1);
    burn.burn_after_read_ttl_seconds = 1;
    let no_ttl = support::message("ordinary", 2);
    *server.history.lock().unwrap() = vec![burn, no_ttl];
    sdk.history(
        HistoryQuery {
            conv_id: "gr_group".into(),
            before_conv_seq: None,
            limit: 100,
        },
        CallOptions::default(),
    )
    .await
    .unwrap();
    assert_eq!(
        sdk.mark_message_displayed("missing".into())
            .unwrap_err()
            .kind,
        ErrorKind::NotFound
    );
    sdk.mark_message_displayed("msg-ordinary".into()).unwrap();
    sdk.mark_message_displayed("msg-burn".into()).unwrap();
    sdk.flush_storage(CallOptions::default()).await.unwrap();
    time.wall.store(0, Ordering::Release);
    time.advance(600);
    sdk.mark_message_displayed("msg-burn".into()).unwrap();
    sdk.flush_storage(CallOptions::default()).await.unwrap();
    assert_ne!(cached(&sdk, "burn").state, MessageState::Burned);
    until(|| !time.waiters.lock().unwrap().is_empty()).await;
    time.advance(500);
    until(|| cached(&sdk, "burn").state == MessageState::Burned).await;
    assert!(cached(&sdk, "burn").content.is_none());
    assert!(cached(&sdk, "burn").text.is_empty());
    sdk.history(
        HistoryQuery {
            conv_id: "gr_group".into(),
            before_conv_seq: None,
            limit: 100,
        },
        CallOptions::default(),
    )
    .await
    .unwrap();
    assert_eq!(cached(&sdk, "burn").state, MessageState::Burned);
    assert!(cached(&sdk, "burn").content.is_none());
    assert_eq!(cached(&sdk, "ordinary").state, MessageState::Sent);
    sdk.shutdown(CallOptions::default()).await.unwrap();
}

#[path = "support/fault_store.rs"]
mod fault_store;
#[tokio::test]
async fn failed_display_commit_is_reported_and_retry_keeps_first_monotonic_deadline() {
    let server = Arc::new(support::Server::default());
    let time = Arc::new(Time::default());
    time.wall.store(100_000, Ordering::Release);
    let fail = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let factory = Arc::new(fault_store::FaultFactory {
        inner: Arc::new(MemoryStoreFactory::new()),
        fail: fail.clone(),
    });
    let mut host = support::host(&server, factory);
    host.clock = time.clone();
    let sdk =
        PhantomClient::with_host(ClientOptions::new("https://example.test", "app"), host).unwrap();
    support::ready(&sdk).await;
    let mut message = support::message("retry-display", 1);
    message.burn_after_read_ttl_seconds = 1;
    server.history.lock().unwrap().push(message);
    sdk.history(
        HistoryQuery {
            conv_id: "gr_group".into(),
            before_conv_seq: None,
            limit: 100,
        },
        CallOptions::default(),
    )
    .await
    .unwrap();
    fail.store(true, Ordering::Release);
    let events = sdk.subscribe(SubscriptionOptions { capacity: 32 }).unwrap();
    sdk.mark_message_displayed("msg-retry-display".into())
        .unwrap();
    loop {
        if let EventKind::Error(error) = events
            .next(CallOptions::default())
            .await
            .unwrap()
            .unwrap()
            .kind
        {
            assert_eq!(error.kind, ErrorKind::Storage);
            break;
        }
    }
    assert_eq!(
        sdk.flush_storage(CallOptions::default())
            .await
            .unwrap_err()
            .kind,
        ErrorKind::Storage
    );
    time.advance(1100);
    time.wall.store(0, Ordering::Release);
    sdk.mark_message_displayed("msg-retry-display".into())
        .unwrap();
    fail.store(false, Ordering::Release);
    sdk.flush_storage(CallOptions::default()).await.unwrap();
    until(|| !time.waiters.lock().unwrap().is_empty()).await;
    time.advance(100);
    until(|| cached(&sdk, "retry-display").state == MessageState::Burned).await;
    sdk.shutdown(CallOptions::default()).await.unwrap();
}
