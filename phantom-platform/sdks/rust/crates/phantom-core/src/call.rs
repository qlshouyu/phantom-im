use phantom_domain::*;
use phantom_ports::*;
use std::{
    collections::BTreeMap,
    future::{Future, poll_fn},
    pin::Pin,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    task::{Context, Poll, Waker},
    time::Duration,
};

#[derive(Default)]
struct CancelState {
    cancelled: AtomicBool,
    next: AtomicU64,
    waiters: Mutex<BTreeMap<u64, Waker>>,
}
/// 可克隆、不可重置的取消信号；不依赖调用者的异步运行时。
#[derive(Clone, Default)]
pub struct CancellationToken(Arc<CancelState>);
impl std::fmt::Debug for CancellationToken {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CancellationToken")
            .field("cancelled", &self.is_cancelled())
            .finish()
    }
}
impl CancellationToken {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn cancel(&self) {
        if self.0.cancelled.swap(true, Ordering::AcqRel) {
            return;
        }
        let waiters =
            std::mem::take(&mut *self.0.waiters.lock().unwrap_or_else(|e| e.into_inner()));
        for (_, waker) in waiters {
            waker.wake();
        }
    }
    pub fn is_cancelled(&self) -> bool {
        self.0.cancelled.load(Ordering::Acquire)
    }
    pub fn cancelled(&self) -> Cancelled {
        Cancelled {
            token: self.clone(),
            id: self.0.next.fetch_add(1, Ordering::Relaxed),
        }
    }
}
pub struct Cancelled {
    token: CancellationToken,
    id: u64,
}
impl Future for Cancelled {
    type Output = ();
    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<()> {
        let mut waiters = self
            .token
            .0
            .waiters
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if self.token.is_cancelled() {
            waiters.remove(&self.id);
            Poll::Ready(())
        } else {
            waiters.insert(self.id, cx.waker().clone());
            Poll::Pending
        }
    }
}
impl Drop for Cancelled {
    fn drop(&mut self) {
        self.token
            .0
            .waiters
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&self.id);
    }
}
#[derive(Clone, Debug, Default)]
pub struct CallOptions {
    pub timeout_ms: Option<u64>,
    pub cancellation: Option<CancellationToken>,
}

#[derive(Clone)]
pub struct CallContext {
    clock: Arc<dyn Clock>,
    deadline: Option<u64>,
    cancellation: CancellationToken,
    submitted: Arc<AtomicBool>,
}
impl CallContext {
    pub fn new(
        options: CallOptions,
        default_ms: Option<u64>,
        clock: Arc<dyn Clock>,
    ) -> SdkResult<Self> {
        let duration = options.timeout_ms.or(default_ms);
        if duration == Some(0) {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "调用期限必须大于零",
            ));
        }
        let deadline = duration
            .map(|ms| {
                clock
                    .monotonic_ms()
                    .checked_add(ms)
                    .ok_or_else(|| SdkError::new(ErrorKind::InvalidArgument, "调用期限超出范围"))
            })
            .transpose()?;
        Ok(Self {
            clock,
            deadline,
            cancellation: options.cancellation.unwrap_or_default(),
            submitted: Arc::new(AtomicBool::new(false)),
        })
    }
    pub fn with_cancellation(&self, cancellation: CancellationToken) -> Self {
        Self {
            cancellation,
            ..self.clone()
        }
    }
    pub fn mark_submitted(&self) {
        self.submitted.store(true, Ordering::Release);
    }
    pub fn submitted(&self) -> bool {
        self.submitted.load(Ordering::Acquire)
    }
    pub fn cancellation(&self) -> CancellationToken {
        self.cancellation.clone()
    }
    pub fn stop_error(&self, kind: ErrorKind) -> SdkError {
        let mut error = SdkError::new(
            kind,
            if kind == ErrorKind::Cancelled {
                "调用已取消"
            } else {
                "调用整体期限已到"
            },
        );
        if self.submitted() {
            error.outcome = OperationOutcome::Unknown;
        }
        error
    }
    pub fn check(&self) -> SdkResult<()> {
        if self.cancellation.is_cancelled() {
            return Err(self.stop_error(ErrorKind::Cancelled));
        }
        if self
            .deadline
            .is_some_and(|d| self.clock.monotonic_ms() >= d)
        {
            return Err(self.stop_error(ErrorKind::Timeout));
        }
        Ok(())
    }
    /// 同一上下文贯穿子步骤；超时不重新开始。成功响应和取消同时可用时保留成功。
    pub async fn run<T>(&self, work: impl Future<Output = SdkResult<T>> + Send) -> SdkResult<T> {
        self.check()?;
        let mut work = std::pin::pin!(work);
        let mut cancel = std::pin::pin!(self.cancellation.cancelled());
        let duration = self
            .deadline
            .map(|d| Duration::from_millis(d.saturating_sub(self.clock.monotonic_ms())));
        let mut timer = duration.map(|d| self.clock.sleep(d));
        poll_fn(|cx| {
            if let Poll::Ready(result) = work.as_mut().poll(cx) {
                return Poll::Ready(result);
            }
            if cancel.as_mut().poll(cx).is_ready() {
                return Poll::Ready(Err(self.stop_error(ErrorKind::Cancelled)));
            }
            if let Some(timer) = &mut timer
                && let Poll::Ready(result) = timer.as_mut().poll(cx)
            {
                return Poll::Ready(match result {
                    Ok(()) => Err(self.stop_error(ErrorKind::Timeout)),
                    Err(error) => Err(error),
                });
            }
            Poll::Pending
        })
        .await
    }
}
