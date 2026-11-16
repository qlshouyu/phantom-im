mod support;
use phantom_core::{EventHub, Kernel, KernelView};
use phantom_runtime::DefaultClock;
use phantom_sdk::*;
use std::{
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    task::Waker,
    time::Duration,
};
#[derive(Default)]
struct Time {
    offset: AtomicU64,
    waiters: Mutex<Vec<Waker>>,
}
impl Time {
    fn advance(&self, ms: u64) {
        self.offset.fetch_add(ms, Ordering::AcqRel);
        for w in std::mem::take(&mut *self.waiters.lock().unwrap()) {
            w.wake();
        }
    }
}
impl Clock for Time {
    fn now_ms(&self) -> i64 {
        DefaultClock.now_ms() + self.monotonic_ms() as i64
    }
    fn monotonic_ms(&self) -> u64 {
        self.offset.load(Ordering::Acquire)
    }
    fn sleep(&self, duration: Duration) -> SdkFuture<'_, ()> {
        let end = self.monotonic_ms() + duration.as_millis() as u64;
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
fn call() -> CallOptions {
    CallOptions {
        timeout_ms: Some(10_000),
        cancellation: None,
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
#[tokio::test]
async fn server_heartbeat_token_expiry_stops_retry_and_updated_credentials_resume_same_identity() {
    let server = Arc::new(support::Server::default());
    let clock = Arc::new(Time::default());
    let mut host = support::host(&server, Arc::new(MemoryStoreFactory::new()));
    host.clock = clock.clone();
    let sdk =
        PhantomClient::with_host(ClientOptions::new("https://example.test", "app"), host).unwrap();
    support::ready(&sdk).await;
    until(|| !clock.waiters.lock().unwrap().is_empty()).await;
    let mut expired = SdkError::new(ErrorKind::Authentication, "凭据已过期");
    expired.server_code = Some(2001);
    *server.ping_error.lock().unwrap() = Some(expired);
    let events = sdk
        .subscribe(SubscriptionOptions { capacity: 128 })
        .unwrap();
    clock.advance(60_000);
    until(|| sdk.get_state().unwrap() == ConnectionState::Closed).await;
    let mut expired = false;
    loop {
        let event = events.next(call()).await.unwrap().unwrap();
        if matches!(event.kind, EventKind::TokenExpired) {
            expired = true;
        }
        if matches!(event.kind, EventKind::Error(_)) {
            break;
        }
    }
    assert!(expired);
    for _ in 0..5 {
        clock.advance(60_000);
        tokio::task::yield_now().await;
    }
    assert_eq!(server.connects.load(Ordering::Acquire), 1);
    assert_eq!(sdk.get_current_user_id().unwrap().as_deref(), Some("user"));
    assert_eq!(
        sdk.reconnect(call()).await.unwrap_err().kind,
        ErrorKind::Authentication
    );
    *server.token_override.lock().unwrap() = Some("updated-credential".into());
    *server.ping_error.lock().unwrap() = None;
    let updated = sdk
        .update_token(
            TokenUpdate {
                user_token: "updated-credential".into(),
            },
            call(),
        )
        .await
        .unwrap();
    assert_eq!(updated.session.user_id, "user");
    sdk.wait_ready(call()).await.unwrap();
    sdk.list_friends(call()).await.unwrap();
    assert_eq!(
        server.identities.lock().unwrap().last().unwrap().user_token,
        "updated-credential"
    );
    // 旧连接已停止读取，其迟到 Kick 不得影响新 generation。
    let _ = server.pushes.lock().unwrap()[0].send(GatewayPush::Kicked(KickInfo {
        reason: KickReason::TokenRevoked,
        detail: "旧连接".into(),
    }));
    sdk.flush_storage(call()).await.unwrap();
    assert_eq!(sdk.get_state().unwrap(), ConnectionState::Online);
    server.push(
        1,
        GatewayPush::Kicked(KickInfo {
            reason: KickReason::RemoteLogout,
            detail: "当前连接".into(),
        }),
    );
    until(|| sdk.get_state().unwrap() == ConnectionState::Closed).await;
    assert_eq!(
        sdk.reconnect(call()).await.unwrap_err().kind,
        ErrorKind::Conflict
    );
    let before = server.connects.load(Ordering::Acquire);
    clock.advance(600_000);
    tokio::task::yield_now().await;
    assert_eq!(server.connects.load(Ordering::Acquire), before);
    sdk.shutdown(call()).await.unwrap();
}
#[test]
fn retry_backoff_is_bounded_with_jitter_using_only_injected_monotonic_time() {
    let server = Arc::new(support::Server::default());
    let clock = Arc::new(Time::default());
    let mut host = support::host(&server, Arc::new(MemoryStoreFactory::new()));
    host.clock = clock.clone();
    let mut kernel = Kernel::new(
        "https://example.test".into(),
        "app".into(),
        host,
        EventHub::default(),
        Arc::new(Mutex::new(KernelView::default())),
        Arc::new(AtomicU64::new(0)),
    );
    for attempt in 0..20 {
        let now = clock.monotonic_ms();
        kernel.schedule_retry();
        let delay = kernel.retry_at - now;
        let base = (1000u64.saturating_mul(1u64 << attempt.min(6))).min(60_000);
        assert!((base * 70 / 100..=(base * 130 / 100).min(60_000)).contains(&delay));
        clock.advance(delay);
    }
}

#[tokio::test]
async fn typing_presence_app_state_and_notification_click_obey_public_contract() {
    let server = Arc::new(support::Server::default());
    let time = Arc::new(Time::default());
    let mut host = support::host(&server, Arc::new(MemoryStoreFactory::new()));
    host.clock = time.clone();
    let sdk =
        PhantomClient::with_host(ClientOptions::new("https://example.test", "app"), host).unwrap();
    support::ready(&sdk).await;
    let count = || {
        server
            .gateway_requests
            .lock()
            .unwrap()
            .iter()
            .filter(|r| matches!(r, GatewayRequest::Typing { .. }))
            .count()
    };
    sdk.send_typing("si_a__b".into(), true, call())
        .await
        .unwrap();
    sdk.send_typing("si_a__b".into(), true, call())
        .await
        .unwrap();
    assert_eq!(count(), 1);
    time.advance(2999);
    sdk.send_typing("si_a__b".into(), true, call())
        .await
        .unwrap();
    assert_eq!(count(), 1);
    time.advance(1);
    sdk.send_typing("si_a__b".into(), true, call())
        .await
        .unwrap();
    assert_eq!(count(), 2);
    sdk.send_typing("si_a__b".into(), false, call())
        .await
        .unwrap();
    sdk.send_typing("si_a__b".into(), false, call())
        .await
        .unwrap();
    assert_eq!(count(), 4);
    assert_eq!(
        sdk.send_typing("gr_group".into(), true, call())
            .await
            .unwrap_err()
            .kind,
        ErrorKind::InvalidArgument
    );
    let presence = sdk
        .subscribe_presence(vec!["a".into(), "a".into(), "b".into()], call())
        .await
        .unwrap();
    assert_eq!(presence.len(), 2);
    sdk.unsubscribe_presence(vec!["a".into(), "a".into()], call())
        .await
        .unwrap();
    assert_eq!(
        sdk.subscribe_presence(vec![], call())
            .await
            .unwrap_err()
            .kind,
        ErrorKind::InvalidArgument
    );
    assert_eq!(
        sdk.subscribe_presence((0..51).map(|i| i.to_string()).collect(), call())
            .await
            .unwrap_err()
            .kind,
        ErrorKind::InvalidArgument
    );
    sdk.set_app_state(false, call()).await.unwrap();
    *server.signal_response.lock().unwrap() = Some(GatewayResponse::Presence(vec![]));
    assert_eq!(
        sdk.set_app_state(true, call()).await.unwrap_err().kind,
        ErrorKind::Protocol
    );
    let subscription = sdk.subscribe(SubscriptionOptions { capacity: 10 }).unwrap();
    let input = NotificationClick {
        data: [("原始".into(), "{'path':'/chat?x=1'}".into())].into(),
    };
    sdk.handle_notification_click(input.clone()).unwrap();
    let event = subscription.next(call()).await.unwrap().unwrap();
    assert!(matches!(event.kind, EventKind::NotificationClick(v) if v == input));
    sdk.shutdown(call()).await.unwrap();
}
