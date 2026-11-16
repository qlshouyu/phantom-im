use crate::*;

/// 队列外执行网络，完成后回到实例队列提交；后续阶段复用同一调用上下文。
pub struct Detached<T> {
    pub work: SdkFuture<'static, Settlement<T>>,
}
pub enum DetachedStep<T> {
    Ready(T),
    Continue(Detached<T>),
}
pub type Settlement<T> =
    Box<dyn for<'a> FnOnce(&'a mut Kernel) -> SdkFuture<'a, DetachedStep<T>> + Send>;

impl<T: Send + 'static> Detached<T> {
    pub fn new<R: Send + 'static>(
        work: SdkFuture<'static, R>,
        settle: impl for<'a> FnOnce(&'a mut Kernel, SdkResult<R>) -> SdkFuture<'a, T> + Send + 'static,
    ) -> Self {
        Self::stage(work, move |kernel, result| {
            Box::pin(async move { settle(kernel, result).await.map(DetachedStep::Ready) })
        })
    }
    pub fn stage<R: Send + 'static>(
        work: SdkFuture<'static, R>,
        settle: impl for<'a> FnOnce(&'a mut Kernel, SdkResult<R>) -> SdkFuture<'a, DetachedStep<T>>
        + Send
        + 'static,
    ) -> Self {
        Self {
            work: Box::pin(async move {
                let result = work.await;
                let completion: Settlement<T> = Box::new(move |kernel| settle(kernel, result));
                Ok(completion)
            }),
        }
    }
    pub fn ready(value: T) -> Self {
        Self::new(Box::pin(async move { Ok(value) }), |_, result| {
            Box::pin(async move { result })
        })
    }
    pub async fn resolve(mut self, kernel: &mut Kernel) -> SdkResult<T> {
        loop {
            match self.work.await?(kernel).await? {
                DetachedStep::Ready(value) => return Ok(value),
                DetachedStep::Continue(next) => self = next,
            }
        }
    }
}
