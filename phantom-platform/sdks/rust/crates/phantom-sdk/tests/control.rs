use phantom_core::{CallContext, EventHub};
use phantom_runtime::{DefaultClock, DefaultFiles, DefaultScheduler};
use phantom_sdk::*;
use std::{
    future::{Future, pending},
    pin::Pin,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    task::{Context, Poll, Wake, Waker},
    time::Duration,
};

#[derive(Default)]
struct VirtualClock {
    now: AtomicU64,
    waiters: Mutex<Vec<Waker>>,
}
impl VirtualClock {
    fn advance(&self, ms: u64) {
        self.now.fetch_add(ms, Ordering::Relaxed);
        for w in std::mem::take(&mut *self.waiters.lock().unwrap()) {
            w.wake();
        }
    }
}
impl Clock for VirtualClock {
    fn now_ms(&self) -> i64 {
        self.monotonic_ms() as i64
    }
    fn monotonic_ms(&self) -> u64 {
        self.now.load(Ordering::Relaxed)
    }
    fn sleep(&self, duration: Duration) -> SdkFuture<'_, ()> {
        let end = self.monotonic_ms() + duration.as_millis() as u64;
        Box::pin(std::future::poll_fn(move |cx| {
            let mut waiters = self.waiters.lock().unwrap();
            if self.monotonic_ms() >= end {
                Poll::Ready(Ok(()))
            } else {
                waiters.push(cx.waker().clone());
                Poll::Pending
            }
        }))
    }
}
fn poll<F: Future>(future: Pin<&mut F>) -> Poll<F::Output> {
    future.poll(&mut Context::from_waker(Waker::noop()))
}

#[test]
fn cancellation_and_one_deadline_cover_every_substep_and_submission_phase() {
    let clock = Arc::new(VirtualClock::default());
    let token = CancellationToken::new();
    token.cancel();
    let call = CallContext::new(
        CallOptions {
            cancellation: Some(token),
            ..CallOptions::default()
        },
        Some(15),
        clock.clone(),
    )
    .unwrap();
    let submitted = AtomicBool::new(false);
    let mut future = std::pin::pin!(call.run(async {
        submitted.store(true, Ordering::Relaxed);
        Ok(())
    }));
    assert_eq!(
        poll(future.as_mut()).map(|v| v.unwrap_err().outcome),
        Poll::Ready(OperationOutcome::NotSubmitted)
    );
    assert!(!submitted.load(Ordering::Relaxed));
    let token = CancellationToken::new();
    let call = CallContext::new(
        CallOptions {
            cancellation: Some(token.clone()),
            ..CallOptions::default()
        },
        Some(15),
        clock.clone(),
    )
    .unwrap();
    let mut first = std::pin::pin!(call.run(async {
        clock.advance(10);
        Ok(())
    }));
    assert!(matches!(poll(first.as_mut()), Poll::Ready(Ok(()))));
    call.mark_submitted();
    let mut second = std::pin::pin!(call.run(pending::<SdkResult<()>>()));
    assert!(poll(second.as_mut()).is_pending());
    clock.advance(5);
    assert_eq!(
        poll(second.as_mut()).map(|v| (v.as_ref().unwrap_err().kind, v.unwrap_err().outcome)),
        Poll::Ready((ErrorKind::Timeout, OperationOutcome::Unknown))
    );
    let call = CallContext::new(
        CallOptions {
            cancellation: Some(token.clone()),
            ..CallOptions::default()
        },
        None,
        clock.clone(),
    )
    .unwrap();
    let mut race = std::pin::pin!(call.run(async {
        token.cancel();
        Ok(7)
    }));
    assert_eq!(poll(race.as_mut()), Poll::Ready(Ok(7)));
}
#[test]
fn independent_subscriptions_signal_overflow_and_do_not_consume_on_cancellation() {
    let hub = EventHub::default();
    let clock: Arc<dyn Clock> = Arc::new(VirtualClock::default());
    let slow = hub
        .subscribe(SubscriptionOptions { capacity: 1 }, clock.clone())
        .unwrap();
    let fast = hub
        .subscribe(SubscriptionOptions { capacity: 4 }, clock)
        .unwrap();
    let token = CancellationToken::new();
    {
        let mut waiting = std::pin::pin!(fast.next(CallOptions {
            cancellation: Some(token.clone()),
            ..CallOptions::default()
        }));
        assert!(poll(waiting.as_mut()).is_pending());
        let mut duplicate = std::pin::pin!(fast.next(CallOptions::default()));
        assert_eq!(
            poll(duplicate.as_mut()).map(|v| v.unwrap_err().kind),
            Poll::Ready(ErrorKind::Busy)
        );
        token.cancel();
        assert_eq!(
            poll(waiting.as_mut()).map(|v| v.unwrap_err().kind),
            Poll::Ready(ErrorKind::Cancelled)
        );
    }
    for _ in 0..2 {
        hub.publish(None, EventKind::TokenExpired).unwrap();
    }
    let mut lag = std::pin::pin!(slow.next(CallOptions::default()));
    assert_eq!(
        poll(lag.as_mut()).map(|v| v.unwrap_err().kind),
        Poll::Ready(ErrorKind::EventLagged)
    );
    let mut previous = 0;
    for _ in 0..2 {
        let mut next = std::pin::pin!(fast.next(CallOptions::default()));
        let Poll::Ready(Ok(Some(event))) = poll(next.as_mut()) else {
            panic!("缺少事件")
        };
        assert!(event.event_id > previous);
        previous = event.event_id;
    }
    hub.publish(None, EventKind::TokenExpired).unwrap();
    hub.clear(false);
    let mut pending = std::pin::pin!(fast.next(CallOptions::default()));
    assert!(poll(pending.as_mut()).is_pending());
    hub.clear(true);
    assert_eq!(poll(pending.as_mut()), Poll::Ready(Ok(None)));
    assert_eq!(
        hub.subscribe(SubscriptionOptions { capacity: 1 }, Arc::new(DefaultClock))
            .err()
            .unwrap()
            .kind,
        ErrorKind::Released
    );
}

// 纯标准库执行器，验证公开能力不要求宿主处于 Tokio 上下文。
fn run<F: Future>(future: F) -> F::Output {
    struct Thread(std::thread::Thread);
    impl Wake for Thread {
        fn wake(self: Arc<Self>) {
            self.0.unpark();
        }
        fn wake_by_ref(self: &Arc<Self>) {
            self.0.unpark();
        }
    }
    let waker = Waker::from(Arc::new(Thread(std::thread::current())));
    let mut future = std::pin::pin!(future);
    loop {
        match future.as_mut().poll(&mut Context::from_waker(&waker)) {
            Poll::Ready(value) => return value,
            Poll::Pending => std::thread::park(),
        }
    }
}
#[test]
fn shared_scheduler_clock_and_files_work_without_a_tokio_host() {
    run(async {
        let clock = Arc::new(DefaultClock);
        let call = CallContext::new(CallOptions::default(), Some(1000), clock.clone()).unwrap();
        call.run(async { clock.sleep(Duration::from_millis(1)).await })
            .await
            .unwrap();
        let completed = Arc::new(AtomicBool::new(false));
        let flag = completed.clone();
        let task = DefaultScheduler.spawn(Box::pin(async move {
            flag.store(true, Ordering::Release);
            Ok(())
        }));
        task.join().await.unwrap();
        task.join().await.unwrap();
        assert!(completed.load(Ordering::Acquire));
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("result.bin");
        let target = DefaultFiles
            .download_target(path.clone(), false)
            .await
            .unwrap();
        target.write(vec![0, 255, 3]).await.unwrap();
        // 模拟检查与提交之间其他程序创建目标文件，必须拒绝覆盖。
        std::fs::write(&path, b"existing").unwrap();
        assert_eq!(target.commit().await.unwrap_err().kind, ErrorKind::Conflict);
        target.abort().await.unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), b"existing");
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 1);
        let target = DefaultFiles
            .download_target(path.clone(), true)
            .await
            .unwrap();
        target.write(vec![0, 255, 3]).await.unwrap();
        target.commit().await.unwrap();
        let reader = DefaultFiles.open(FileSource::Path(path)).await.unwrap();
        assert_eq!(reader.size(), 3);
        assert_eq!(reader.read_range(1, 2).await.unwrap(), [255, 3]);
        let source = DefaultFiles
            .open(FileSource::Bytes(vec![9, 0, 254]))
            .await
            .unwrap();
        assert_eq!(source.read_range(0, 10).await.unwrap(), [9, 0, 254]);
        assert!(source.read_range(4, 1).await.is_err());
        let target = DefaultFiles
            .download_target(dir.path().join("abort.bin"), false)
            .await
            .unwrap();
        target.write(vec![1]).await.unwrap();
        drop(target);
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 1);
    });
}
