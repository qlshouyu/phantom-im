use phantom_domain::*;
use phantom_ports::*;
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, Ordering},
};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StorageHandle(pub String);
#[derive(Clone, Debug)]
pub struct StorageCallbackContext {
    pub namespace: StorageNamespace,
    pub handle: StorageHandle,
}
pub type StorageCallback<T> =
    Arc<dyn Fn(StorageCallbackContext) -> SdkFuture<'static, T> + Send + Sync>;
pub type StorageInputCallback<I, T> =
    Arc<dyn Fn(StorageCallbackContext, I) -> SdkFuture<'static, T> + Send + Sync>;
pub type StorageOpenCallback =
    Arc<dyn Fn(StorageNamespace) -> SdkFuture<'static, StorageHandle> + Send + Sync>;

/// 所有回调必填。SDK 只传递本实例句柄；宿主保有共享数据库/连接池的所有权。
#[derive(Default)]
pub struct StorageCallbacks {
    pub capabilities: Option<StorageCapabilities>,
    pub open: Option<StorageOpenCallback>,
    pub load: Option<StorageCallback<StorageSnapshot>>,
    pub query_messages: Option<StorageInputCallback<MessageCacheQuery, Vec<ChatMessage>>>,
    pub get_conversation: Option<StorageInputCallback<String, Option<Conversation>>>,
    pub get_projection: Option<StorageInputCallback<ProjectionKey, Option<DomainProjection>>>,
    pub apply: Option<StorageInputCallback<StorageChangeSet, StorageCommit>>,
    pub flush: Option<StorageCallback<()>>,
    pub clear: Option<StorageCallback<()>>,
    pub close: Option<StorageCallback<()>>,
}
struct Validated {
    capabilities: StorageCapabilities,
    open: StorageOpenCallback,
    load: StorageCallback<StorageSnapshot>,
    query_messages: StorageInputCallback<MessageCacheQuery, Vec<ChatMessage>>,
    get_conversation: StorageInputCallback<String, Option<Conversation>>,
    get_projection: StorageInputCallback<ProjectionKey, Option<DomainProjection>>,
    apply: StorageInputCallback<StorageChangeSet, StorageCommit>,
    flush: StorageCallback<()>,
    clear: StorageCallback<()>,
    close: StorageCallback<()>,
}
#[derive(Clone)]
pub struct CallbackStoreFactory {
    callbacks: Arc<Validated>,
}
pub struct CallbackStore {
    context: StorageCallbackContext,
    callbacks: Arc<Validated>,
    closed: AtomicBool,
    closing: Mutex<Closing>,
    close_signal: Arc<CloseSignal>,
}
#[derive(Default)]
struct Closing {
    future: Option<SdkFuture<'static, ()>>,
    result: Option<SdkResult<()>>,
}
#[derive(Default)]
struct CloseSignal(Mutex<Vec<std::task::Waker>>);
impl std::task::Wake for CloseSignal {
    fn wake(self: Arc<Self>) {
        self.wake_by_ref();
    }
    fn wake_by_ref(self: &Arc<Self>) {
        let waiters = std::mem::take(&mut *self.0.lock().unwrap_or_else(|e| e.into_inner()));
        for waker in waiters {
            waker.wake();
        }
    }
}
fn required<T>(value: Option<T>, name: &str) -> SdkResult<T> {
    value.ok_or_else(|| {
        SdkError::new(
            ErrorKind::InvalidArgument,
            format!("存储回调缺少必需项 {name}"),
        )
    })
}
fn failure(mut error: SdkError) -> SdkError {
    if error.kind != ErrorKind::Conflict {
        error.kind = ErrorKind::Storage;
    }
    error
}
impl CallbackStoreFactory {
    pub fn new(callbacks: StorageCallbacks) -> SdkResult<Self> {
        let capabilities = required(callbacks.capabilities, "capabilities")?;
        capabilities.validate()?;
        Ok(Self {
            callbacks: Arc::new(Validated {
                capabilities,
                open: required(callbacks.open, "open")?,
                load: required(callbacks.load, "load")?,
                query_messages: required(callbacks.query_messages, "query_messages")?,
                get_conversation: required(callbacks.get_conversation, "get_conversation")?,
                get_projection: required(callbacks.get_projection, "get_projection")?,
                apply: required(callbacks.apply, "apply")?,
                flush: required(callbacks.flush, "flush")?,
                clear: required(callbacks.clear, "clear")?,
                close: required(callbacks.close, "close")?,
            }),
        })
    }
}
impl StoreFactory for CallbackStoreFactory {
    fn capabilities(&self) -> StorageCapabilities {
        self.callbacks.capabilities
    }
    fn open(&self, namespace: StorageNamespace) -> SdkFuture<'_, Arc<dyn Store>> {
        Box::pin(async move {
            namespace.validate()?;
            let handle = (self.callbacks.open)(namespace.clone())
                .await
                .map_err(failure)?;
            Ok(Arc::new(CallbackStore {
                context: StorageCallbackContext { namespace, handle },
                callbacks: self.callbacks.clone(),
                closed: AtomicBool::new(false),
                closing: Mutex::new(Closing::default()),
                close_signal: Arc::default(),
            }) as Arc<dyn Store>)
        })
    }
}
impl CallbackStore {
    fn check(&self) -> SdkResult<()> {
        if self.closed.load(Ordering::Acquire) {
            Err(SdkError::new(ErrorKind::Storage, "回调存储句柄已关闭"))
        } else {
            Ok(())
        }
    }
}
impl Store for CallbackStore {
    fn namespace(&self) -> &StorageNamespace {
        &self.context.namespace
    }
    fn capabilities(&self) -> StorageCapabilities {
        self.callbacks.capabilities
    }
    fn load(&self) -> SdkFuture<'_, StorageSnapshot> {
        Box::pin(async move {
            self.check()?;
            (self.callbacks.load)(self.context.clone())
                .await
                .map_err(failure)
        })
    }
    fn query_messages(&self, query: MessageCacheQuery) -> SdkFuture<'_, Vec<ChatMessage>> {
        Box::pin(async move {
            self.check()?;
            (self.callbacks.query_messages)(self.context.clone(), query)
                .await
                .map_err(failure)
        })
    }
    fn get_conversation(&self, id: String) -> SdkFuture<'_, Option<Conversation>> {
        Box::pin(async move {
            self.check()?;
            (self.callbacks.get_conversation)(self.context.clone(), id)
                .await
                .map_err(failure)
        })
    }
    fn get_projection(&self, key: ProjectionKey) -> SdkFuture<'_, Option<DomainProjection>> {
        Box::pin(async move {
            self.check()?;
            (self.callbacks.get_projection)(self.context.clone(), key)
                .await
                .map_err(failure)
        })
    }
    fn apply(&self, changes: StorageChangeSet) -> SdkFuture<'_, StorageCommit> {
        Box::pin(async move {
            self.check()?;
            (self.callbacks.apply)(self.context.clone(), changes)
                .await
                .map_err(failure)
        })
    }
    fn flush(&self) -> SdkFuture<'_, ()> {
        Box::pin(async move {
            self.check()?;
            (self.callbacks.flush)(self.context.clone())
                .await
                .map_err(failure)
        })
    }
    fn clear(&self) -> SdkFuture<'_, ()> {
        Box::pin(async move {
            self.check()?;
            (self.callbacks.clear)(self.context.clone())
                .await
                .map_err(failure)
        })
    }
    fn close(&self) -> SdkFuture<'_, ()> {
        Box::pin(std::future::poll_fn(move |cx| {
            let mut state = self.closing.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(result) = &state.result {
                return std::task::Poll::Ready(result.clone());
            }
            {
                let mut waiters = self
                    .close_signal
                    .0
                    .lock()
                    .unwrap_or_else(|e| e.into_inner());
                if !waiters.iter().any(|w| w.will_wake(cx.waker())) {
                    waiters.push(cx.waker().clone());
                }
            }
            self.closed.store(true, Ordering::Release);
            if state.future.is_none() {
                state.future = Some((self.callbacks.close)(self.context.clone()));
            }
            let Some(future) = state.future.as_mut() else {
                unreachable!("关闭回调已初始化")
            };
            let waker = std::task::Waker::from(self.close_signal.clone());
            match future
                .as_mut()
                .poll(&mut std::task::Context::from_waker(&waker))
            {
                std::task::Poll::Pending => std::task::Poll::Pending,
                std::task::Poll::Ready(result) => {
                    let result = result.map_err(failure);
                    state.future = None;
                    state.result = Some(result.clone());
                    drop(state);
                    std::task::Wake::wake_by_ref(&self.close_signal);
                    std::task::Poll::Ready(result)
                }
            }
        }))
    }
}
