use crate::*;
use phantom_core::{CallContext, EventHub, HostServices, Kernel, KernelView};
use phantom_runtime::{
    DefaultClock, DefaultFiles, DefaultHashing, DefaultIds, DefaultScheduler, shared_runtime,
};
use phantom_transport::{
    BaseUrl, DefaultGatewayFactory, DefaultWebSocketConnector, HttpAdapter, HttpPlatformApi,
};
use std::{
    collections::{BTreeMap, BTreeSet},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
};
use tokio::sync::{mpsc, oneshot};

enum Dispatch<T> {
    Ready(T),
    Detached(phantom_core::Detached<T>),
}

pub(crate) type Work = Box<dyn for<'a> FnOnce(&'a mut Kernel) -> SdkFuture<'a, ()> + Send>;
pub(crate) struct Close {
    clear: bool,
    released: bool,
    epoch: u64,
    done: oneshot::Sender<SdkResult<()>>,
    closing: Arc<AtomicBool>,
}
pub(crate) struct Inner {
    pub(crate) view: Arc<Mutex<KernelView>>,
    pub(crate) fence: Arc<AtomicU64>,
    pub(crate) events: EventHub,
    pub(crate) host: HostServices,
    pub(crate) timeout: u64,
    pub(crate) sender: mpsc::Sender<Work>,
    closing: Arc<AtomicBool>,
    closer: mpsc::UnboundedSender<Close>,
    contexts: Arc<Mutex<BTreeMap<String, CancellationToken>>>,
    session_cancel: Mutex<CancellationToken>,
    pub(crate) displayed: Mutex<BTreeMap<(u64, String), DisplayDeadline>>,
    pub(crate) transfers: Arc<Mutex<BTreeSet<String>>>,
    pub(crate) session_busy: Arc<AtomicBool>,
    pub(crate) sends: Arc<Mutex<BTreeSet<String>>>,
    backgrounds: Mutex<Vec<Arc<dyn Task>>>,
}
#[derive(Clone)]
pub struct PhantomClient {
    pub(crate) inner: Arc<Inner>,
}
struct CancelGuard(Option<CancellationToken>);
impl Drop for CancelGuard {
    fn drop(&mut self) {
        if let Some(token) = &self.0 {
            token.cancel();
        }
    }
}
pub(crate) enum Permit {
    Session(Arc<AtomicBool>),
    Send(Arc<Mutex<BTreeSet<String>>>, String),
}
impl Drop for Permit {
    fn drop(&mut self) {
        match self {
            Self::Session(busy) => busy.store(false, Ordering::Release),
            Self::Send(ids, id) => {
                ids.lock().unwrap_or_else(|e| e.into_inner()).remove(id);
            }
        }
    }
}
struct Registration {
    contexts: Arc<Mutex<BTreeMap<String, CancellationToken>>>,
    id: String,
}
impl Drop for Registration {
    fn drop(&mut self) {
        self.contexts
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&self.id);
    }
}
impl PhantomClient {
    pub fn new(options: ClientOptions) -> SdkResult<Self> {
        let base = BaseUrl::parse(&options.base_url)?;
        let store: Arc<dyn StoreFactory> = match options.storage.clone() {
            None => Arc::new(SqliteStoreFactory::new()),
            Some(StorageOptions::Memory) => Arc::new(MemoryStoreFactory::new()),
            Some(StorageOptions::Directory(path)) => Arc::new(SqliteStoreFactory::directory(path)?),
            Some(StorageOptions::Custom(factory)) => factory,
        };
        let http = Arc::new(HttpAdapter::new(base.clone(), options.request_timeout_ms)?);
        let host = HostServices {
            store,
            api: Arc::new(HttpPlatformApi::new(http.clone())),
            gateway: Arc::new(DefaultGatewayFactory::new(Arc::new(
                DefaultWebSocketConnector::new(base),
            ))),
            clock: Arc::new(DefaultClock),
            scheduler: Arc::new(DefaultScheduler),
            ids: Arc::new(DefaultIds),
            files: Arc::new(DefaultFiles),
            hashing: Arc::new(DefaultHashing),
            media: http,
        };
        Self::with_host(options, host)
    }
    /// 显式宿主能力注入；options.storage=Some 时优先使用该存储配置，None 使用 host.store。
    /// 生产与确定性测试经过相同 Kernel 和存储契约。
    pub fn with_host(options: ClientOptions, mut host: HostServices) -> SdkResult<Self> {
        let base = BaseUrl::parse(&options.base_url)?;
        validate_id(&options.app_id, "app_id")?;
        if options.request_timeout_ms == 0 {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "请求期限必须大于零",
            ));
        }
        if let Some(storage) = options.storage.clone() {
            host.store = match storage {
                StorageOptions::Memory => Arc::new(MemoryStoreFactory::new()),
                StorageOptions::Directory(path) => Arc::new(SqliteStoreFactory::directory(path)?),
                StorageOptions::Custom(factory) => factory,
            };
        }
        host.store.capabilities().validate()?;
        let (sender, mut receiver) = mpsc::channel::<Work>(256);
        let (closer, mut closes) = mpsc::unbounded_channel::<Close>();
        let view = Arc::new(Mutex::new(KernelView::default()));
        let events = EventHub::default();
        let fence = Arc::new(AtomicU64::new(0));
        let inner = Arc::new(Inner {
            view: view.clone(),
            fence: fence.clone(),
            events: events.clone(),
            host: host.clone(),
            timeout: options.request_timeout_ms,
            sender,
            closer,
            closing: Arc::new(AtomicBool::new(false)),
            contexts: Arc::default(),
            session_busy: Arc::new(AtomicBool::new(false)),
            sends: Arc::default(),
            transfers: Arc::default(),
            backgrounds: Mutex::default(),
            displayed: Mutex::default(),
            session_cancel: Mutex::new(CancellationToken::new()),
        });
        let mut kernel = Kernel::new(base.normalized(), options.app_id, host, events, view, fence);
        shared_runtime().spawn(async move {
            loop {
                tokio::select! { biased;
                    close = closes.recv() => match close {
                        Some(close) => { while receiver.try_recv().is_ok() {} let result = kernel.close(close.clear,close.released,close.epoch).await; close.closing.store(false,Ordering::Release); let _ = close.done.send(result); if close.released { break; } },
                        None => break,
                    },
                    work = receiver.recv() => match work {
                        Some(work) => {
                            // 退出/释放可以中断等待网络或宿主回调的命令，先释放 &mut Kernel 再清理。
                            let interrupted = {
                                let work = work(&mut kernel); tokio::pin!(work);
                                tokio::select! { biased; close = closes.recv() => Some(close), _ = &mut work => None }
                            };
                            if let Some(close) = interrupted {
                                let Some(close) = close else { break; };
                                while receiver.try_recv().is_ok() {}
                                let result = kernel.close(close.clear,close.released,close.epoch).await;
                                close.closing.store(false,Ordering::Release); let _ = close.done.send(result); if close.released { break; }
                            }
                        },
                        None => break,
                    },
                }
            }
        });
        Ok(Self { inner })
    }
    pub(crate) fn read(&self) -> SdkResult<KernelView> {
        let view = self
            .inner
            .view
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        if view.state == ConnectionState::Released {
            return Err(SdkError::new(ErrorKind::Released, "SDK 已释放"));
        }
        Ok(view)
    }
    pub fn get_state(&self) -> SdkResult<ConnectionState> {
        Ok(self
            .inner
            .view
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .state
            .clone())
    }
    pub fn get_current_user_id(&self) -> SdkResult<Option<String>> {
        Ok(self.read()?.session.map(|s| s.user_id))
    }
    pub fn get_app_config(&self) -> SdkResult<Option<AppConfig>> {
        Ok(self.read()?.config)
    }
    pub fn get_sync_status(&self) -> SdkResult<SyncStatus> {
        Ok(self.read()?.sync)
    }
    pub fn get_messages(&self, query: MessageCacheQuery) -> SdkResult<Vec<ChatMessage>> {
        let view = self.read()?;
        if view.session.is_none() {
            return Err(SdkError::new(ErrorKind::NotLoggedIn, "尚未登录"));
        }
        view.storage.query_messages(&query)
    }
    pub fn get_conversation_state(&self, conv_id: String) -> SdkResult<Option<Conversation>> {
        let view = self.read()?;
        validate_id(&conv_id, "conv_id")?;
        if view.session.is_none() {
            return Err(SdkError::new(ErrorKind::NotLoggedIn, "尚未登录"));
        }
        Ok(view
            .storage
            .conversations
            .into_iter()
            .find(|c| c.conv_id == conv_id))
    }
    pub fn subscribe(&self, options: SubscriptionOptions) -> SdkResult<EventSubscription> {
        self.read()?;
        self.inner
            .events
            .subscribe(options, self.inner.host.clock.clone())
    }
    pub(crate) fn session_permit(&self) -> SdkResult<Permit> {
        self.read()?;
        if self.inner.closing.load(Ordering::Acquire) {
            return Err(SdkError::new(ErrorKind::Busy, "账号正在退出"));
        }
        if self.inner.session_busy.swap(true, Ordering::AcqRel) {
            return Err(SdkError::new(
                ErrorKind::Busy,
                "已有登录、重新认证或重连操作",
            ));
        }
        Ok(Permit::Session(self.inner.session_busy.clone()))
    }
    pub(crate) async fn invoke<T: Send + 'static>(
        &self,
        call: CallOptions,
        permit: Option<Permit>,
        operation: impl for<'a> FnOnce(&'a mut Kernel, CallContext) -> SdkFuture<'a, T> + Send + 'static,
    ) -> SdkResult<T> {
        self.invoke_plan(call, permit, move |kernel, context| {
            Box::pin(async move { operation(kernel, context).await.map(Dispatch::Ready) })
        })
        .await
    }
    pub(crate) async fn invoke_detached<T: Send + 'static>(
        &self,
        call: CallOptions,
        permit: Option<Permit>,
        operation: impl for<'a> FnOnce(
            &'a mut Kernel,
            CallContext,
        ) -> SdkFuture<'a, phantom_core::Detached<T>>
        + Send
        + 'static,
    ) -> SdkResult<T> {
        self.invoke_plan(call, permit, move |kernel, context| {
            Box::pin(async move { operation(kernel, context).await.map(Dispatch::Detached) })
        })
        .await
    }
    pub(crate) async fn invoke_api(
        &self,
        request: ApiRequest,
        call: CallOptions,
    ) -> SdkResult<ApiResponse> {
        self.invoke_detached(call, None, move |kernel, context| {
            Box::pin(async move { kernel.prepare_api(request, &context).await })
        })
        .await
    }
    async fn invoke_plan<T: Send + 'static>(
        &self,
        call: CallOptions,
        permit: Option<Permit>,
        operation: impl for<'a> FnOnce(&'a mut Kernel, CallContext) -> SdkFuture<'a, Dispatch<T>>
        + Send
        + 'static,
    ) -> SdkResult<T> {
        self.read()?;
        let outer = CallContext::new(
            call,
            Some(self.inner.timeout),
            self.inner.host.clock.clone(),
        )?;
        outer.check()?;
        let token = CancellationToken::new();
        let mut cancel = CancelGuard(Some(token.clone()));
        let context = outer.with_cancellation(token.clone());
        let id = self.inner.host.ids.generate()?;
        {
            let mut contexts = self
                .inner
                .contexts
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            if contexts.len() >= 1024 {
                return Err(SdkError::new(ErrorKind::Busy, "SDK 未完成请求已达上限"));
            }
            contexts.insert(id.clone(), token);
        }
        let registration = Registration {
            contexts: self.inner.contexts.clone(),
            id,
        };
        let epoch = self.inner.fence.load(Ordering::Acquire);
        let (done, ready) = oneshot::channel();
        let sender = self.inner.sender.clone();
        let scheduler = self.inner.host.scheduler.clone();
        let work: Work = Box::new(move |kernel| {
            Box::pin(async move {
                let result = if epoch != kernel.fence.load(Ordering::Acquire) {
                    Err(SdkError::new(
                        ErrorKind::Cancelled,
                        "请求所属账号会话已结束",
                    ))
                } else {
                    match context.check() {
                        Ok(()) => operation(kernel, context).await,
                        Err(e) => Err(e),
                    }
                };
                match result {
                    Ok(Dispatch::Detached(plan)) => {
                        scheduler.spawn(Box::pin(async move {
                            let mut plan = plan;
                            let result = loop {
                                let settle = match plan.work.await {
                                    Ok(settle) => settle,
                                    Err(error) => break Err(error),
                                };
                                let (settled, next) = oneshot::channel();
                                let completion: Work = Box::new(move |kernel| {
                                    Box::pin(async move {
                                        let _ = settled.send(settle(kernel).await);
                                        Ok(())
                                    })
                                });
                                if sender.send(completion).await.is_err() {
                                    break Err(SdkError::new(
                                        ErrorKind::Cancelled,
                                        "请求因会话结束而取消",
                                    ));
                                }
                                match next.await {
                                    Ok(Ok(phantom_core::DetachedStep::Ready(value))) => {
                                        break Ok(value);
                                    }
                                    Ok(Ok(phantom_core::DetachedStep::Continue(next))) => {
                                        plan = next
                                    }
                                    Ok(Err(error)) => break Err(error),
                                    Err(_) => {
                                        break Err(SdkError::new(
                                            ErrorKind::Cancelled,
                                            "请求因会话结束而取消",
                                        ));
                                    }
                                }
                            };
                            drop(permit);
                            drop(registration);
                            let _ = done.send(result);
                            Ok(())
                        }));
                    }
                    result => {
                        drop(permit);
                        drop(registration);
                        let _ = done.send(result.map(|v| match v {
                            Dispatch::Ready(value) => value,
                            Dispatch::Detached(_) => unreachable!(),
                        }));
                    }
                }
                Ok(())
            })
        });
        self.inner
            .sender
            .try_send(work)
            .map_err(|_| SdkError::new(ErrorKind::Busy, "SDK 命令队列已满或结束"))?;
        let result = outer
            .run(async {
                ready
                    .await
                    .map_err(|_| SdkError::new(ErrorKind::Cancelled, "请求因会话结束而取消"))?
            })
            .await
            .map_err(|mut error| {
                if outer.submitted()
                    && error.outcome == OperationOutcome::NotSubmitted
                    && matches!(error.kind, ErrorKind::Cancelled | ErrorKind::Timeout)
                {
                    error.outcome = OperationOutcome::Unknown;
                }
                error
            });
        if result
            .as_ref()
            .is_err_and(|error| error.kind == ErrorKind::Timeout)
        {
            // 工作阶段持有相同 deadline，由它报告超时并完成清理；避免 Drop 的取消
            // 将已到期上传错误误报为 Cancelled，丢失 Failed 终态进度。
            cancel.0 = None;
        }
        result
    }
    pub(crate) async fn end(
        &self,
        clear: bool,
        released: bool,
        call: CallOptions,
    ) -> SdkResult<()> {
        if self.get_state()? == ConnectionState::Released {
            return if released {
                Ok(())
            } else {
                Err(SdkError::new(ErrorKind::Released, "SDK 已释放"))
            };
        }
        if self.inner.closing.swap(true, Ordering::AcqRel) && !released {
            return Err(SdkError::new(ErrorKind::Busy, "账号正在退出"));
        }
        let epoch = self
            .inner
            .fence
            .fetch_add(1, Ordering::AcqRel)
            .saturating_add(1);
        self.inner
            .session_cancel
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .cancel();
        for token in self
            .inner
            .contexts
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .values()
        {
            token.cancel();
        }
        for task in self
            .inner
            .backgrounds
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .drain(..)
        {
            task.cancel();
        }
        self.inner
            .displayed
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();
        self.inner.events.clear(released);
        *self.inner.view.lock().unwrap_or_else(|e| e.into_inner()) = KernelView {
            state: if released {
                ConnectionState::Released
            } else {
                ConnectionState::Closed
            },
            ..KernelView::default()
        };
        let (done, ready) = oneshot::channel();
        self.inner
            .closer
            .send(Close {
                clear,
                released,
                epoch,
                done,
                closing: self.inner.closing.clone(),
            })
            .map_err(|_| SdkError::new(ErrorKind::Released, "SDK 执行队列已结束"))?;
        let context = CallContext::new(
            call,
            Some(self.inner.timeout),
            self.inner.host.clock.clone(),
        )?;
        context
            .run(async {
                ready
                    .await
                    .map_err(|_| SdkError::new(ErrorKind::Released, "SDK 关闭任务已结束"))?
            })
            .await
    }
    pub async fn logout(&self, input: LogoutOptions, call: CallOptions) -> SdkResult<()> {
        self.end(input.clear_cache, false, call).await
    }
    pub async fn shutdown(&self, call: CallOptions) -> SdkResult<()> {
        self.end(false, true, call).await
    }
    pub async fn flush_storage(&self, call: CallOptions) -> SdkResult<()> {
        self.invoke(call, None, |k, c| {
            Box::pin(async move { k.flush(&c).await })
        })
        .await
    }
    pub async fn login(&self, input: LoginOptions, call: CallOptions) -> SdkResult<LoginResult> {
        let permit = self.session_permit()?;
        if self.read()?.session.is_some() {
            return Err(SdkError::new(ErrorKind::Conflict, "已有登录会话"));
        }
        let epoch = self.inner.fence.load(Ordering::Acquire);
        let weak = Arc::downgrade(&self.inner);
        self.invoke(call, Some(permit), move |k, c| {
            Box::pin(async move {
                let result = k.login(input, &c, epoch).await?;
                k.live()?;
                if let Some(inner) = weak.upgrade() {
                    {
                        let mut cancel = inner
                            .session_cancel
                            .lock()
                            .unwrap_or_else(|e| e.into_inner());
                        if inner.fence.load(Ordering::Acquire) == k.epoch {
                            *cancel = CancellationToken::new();
                        }
                    }
                    start_background(&inner, k);
                    queue_sync(&inner, k.epoch, k.generation);
                }
                Ok(result)
            })
        })
        .await
    }
    pub async fn update_token(
        &self,
        input: TokenUpdate,
        call: CallOptions,
    ) -> SdkResult<LoginResult> {
        self.reauth(Some(input), call).await
    }
    pub async fn reconnect(&self, call: CallOptions) -> SdkResult<LoginResult> {
        self.reauth(None, call).await
    }
    async fn reauth(
        &self,
        token: Option<TokenUpdate>,
        call: CallOptions,
    ) -> SdkResult<LoginResult> {
        let permit = self.session_permit()?;
        let weak = Arc::downgrade(&self.inner);
        self.invoke(call, Some(permit), move |k, c| {
            Box::pin(async move {
                let result = k.reauthenticate(token, &c).await?;
                if let Some(inner) = weak.upgrade() {
                    start_background(&inner, k);
                    queue_sync(&inner, k.epoch, k.generation);
                }
                Ok(result)
            })
        })
        .await
    }
    pub async fn wait_ready(&self, call: CallOptions) -> SdkResult<SyncStatus> {
        let context = CallContext::new(
            call,
            Some(self.inner.timeout),
            self.inner.host.clock.clone(),
        )?;
        let mut subscription = self.subscribe(SubscriptionOptions { capacity: 128 })?;
        context
            .run(async {
                loop {
                    let view = self.read()?;
                    if view.session.is_none() {
                        return Err(SdkError::new(ErrorKind::NotLoggedIn, "尚未登录"));
                    }
                    if view.sync.phase == SyncPhase::Ready {
                        return Ok(view.sync);
                    }
                    if let Some(error) = view.failure {
                        return Err(error);
                    }
                    match subscription.next(CallOptions::default()).await {
                        Ok(Some(_)) => {}
                        Ok(None) => {
                            return Err(SdkError::new(ErrorKind::Released, "SDK 已释放"));
                        }
                        Err(error) if error.kind == ErrorKind::EventLagged => {
                            // 此订阅只用于唤醒；先重新订阅，再读取权威快照，避免错过 Ready。
                            subscription = self.subscribe(SubscriptionOptions { capacity: 128 })?;
                        }
                        Err(error) => return Err(error),
                    }
                }
            })
            .await
    }
}
fn queue_sync(inner: &Arc<Inner>, epoch: u64, generation: u64) {
    let clock = inner.host.clock.clone();
    let timeout = inner.timeout;
    let cancellation = inner
        .session_cancel
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    let _ = inner.sender.try_send(Box::new(move |k| {
        Box::pin(async move {
            if k.epoch != epoch || k.generation != generation || k.live().is_err() {
                return Ok(());
            }
            let context = CallContext::new(
                CallOptions {
                    cancellation: Some(cancellation),
                    timeout_ms: None,
                },
                Some(timeout),
                clock,
            )?;
            if let Err(error) = k.synchronize(&context).await {
                k.failed(error);
            }
            Ok(())
        })
    }));
}
fn start_background(inner: &Arc<Inner>, kernel: &Kernel) {
    let mut backgrounds = inner.backgrounds.lock().unwrap_or_else(|e| e.into_inner());
    if inner.fence.load(Ordering::Acquire) != kernel.epoch {
        return;
    }
    for task in backgrounds.drain(..) {
        task.cancel();
    }
    let Some(connection) = kernel.connection.clone() else {
        return;
    };
    let epoch = kernel.epoch;
    let generation = kernel.generation;
    let weak = Arc::downgrade(inner);
    let socket = connection.clone();
    let reader = inner.host.scheduler.spawn(Box::pin(async move {
        loop {
            let push = socket.next_push().await;
            let Some(inner) = weak.upgrade() else {
                break;
            };
            let ended = matches!(&push, Ok(None) | Err(_));
            let clock = inner.host.clock.clone();
            let timeout = inner.timeout;
            let cancellation = inner
                .session_cancel
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone();
            if inner
                .sender
                .try_send(Box::new(move |k| {
                    Box::pin(async move {
                        if k.epoch != epoch || k.generation != generation || k.live().is_err() {
                            return Ok(());
                        }
                        let context = CallContext::new(
                            CallOptions {
                                cancellation: Some(cancellation),
                                timeout_ms: None,
                            },
                            Some(timeout),
                            clock,
                        )?;
                        if k.kicked || k.current.state != ConnectionState::Online {
                            return Ok(());
                        }
                        match push {
                            Ok(Some(push)) => {
                                if let Err(error) = k.push(push, &context).await {
                                    k.failed(error);
                                }
                            }
                            Ok(None) => {
                                k.connection_lost(SdkError::new(
                                    ErrorKind::Network,
                                    "平台连接已关闭",
                                ))
                                .await
                            }
                            Err(error) => k.connection_lost(error).await,
                        }
                        Ok(())
                    })
                }))
                .is_err()
            {
                break;
            }
            if ended {
                break;
            }
        }
        Ok(())
    }));
    let weak = Arc::downgrade(inner);
    let clock = inner.host.clock.clone();
    let ticker = inner.host.scheduler.spawn(Box::pin(async move {
        loop {
            clock.sleep(std::time::Duration::from_millis(100)).await?;
            let Some(inner) = weak.upgrade() else {
                break;
            };
            let clock = inner.host.clock.clone();
            let timeout = inner.timeout;
            let owner = Arc::downgrade(&inner);
            let cancellation = inner
                .session_cancel
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone();
            if inner
                .sender
                .try_send(Box::new(move |k| {
                    Box::pin(async move {
                        if k.epoch != epoch || k.generation != generation || k.live().is_err() {
                            return Ok(());
                        }
                        if let Err(error) = k.burn_expired().await {
                            k.failed(error);
                        }
                        let now = clock.monotonic_ms();
                        if k.current.state == ConnectionState::Online && now >= k.heartbeat_at {
                            let context = CallContext::new(
                                CallOptions {
                                    cancellation: Some(cancellation.clone()),
                                    timeout_ms: None,
                                },
                                Some(k.heartbeat_ms.min(timeout).max(1)),
                                clock.clone(),
                            )?;
                            match k.gateway_request(GatewayRequest::Ping, &context).await {
                                Ok(_) => {
                                    k.heartbeat_at =
                                        clock.monotonic_ms().saturating_add(k.heartbeat_ms)
                                }
                                Err(error) => k.connection_lost(error).await,
                            }
                        }
                        if k.current.state == ConnectionState::Reconnecting
                            && !k.kicked
                            && !k.token_invalid
                            && now >= k.retry_at
                        {
                            let context = CallContext::new(
                                CallOptions {
                                    cancellation: Some(cancellation),
                                    timeout_ms: None,
                                },
                                Some(timeout),
                                clock,
                            )?;
                            match k.reauthenticate(None, &context).await {
                                Ok(_) => {
                                    if let Some(inner) = owner.upgrade() {
                                        start_background(&inner, k);
                                        queue_sync(&inner, k.epoch, k.generation);
                                    }
                                }
                                Err(error) => {
                                    if error.kind == ErrorKind::Authentication {
                                        k.connection_lost(error).await;
                                    } else {
                                        k.schedule_retry();
                                        k.failed(error);
                                    }
                                }
                            }
                        }
                        Ok(())
                    })
                }))
                .is_err()
            {
                break;
            }
        }
        Ok(())
    }));
    backgrounds.extend([reader, ticker]);
}
impl Drop for Inner {
    fn drop(&mut self) {
        let epoch = self.fence.fetch_add(1, Ordering::AcqRel).saturating_add(1);
        self.session_cancel
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .cancel();
        for token in self
            .contexts
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .values()
        {
            token.cancel();
        }
        for task in self
            .backgrounds
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .drain(..)
        {
            task.cancel();
        }
        self.events.clear(true);
        let (done, _) = oneshot::channel();
        let _ = self.closer.send(Close {
            clear: false,
            released: true,
            epoch,
            done,
            closing: self.closing.clone(),
        });
    }
}

#[cfg(test)]
mod composition_tests {
    use super::*;
    #[test]
    fn omitted_storage_is_persistent_and_explicit_memory_is_volatile_without_opening_accounts() {
        let persistent =
            PhantomClient::new(ClientOptions::new("https://example.test", "app")).unwrap();
        assert_eq!(
            persistent.inner.host.store.capabilities().durability,
            StorageDurability::Persistent
        );
        let mut options = ClientOptions::new("https://example.test", "app");
        options.storage = Some(StorageOptions::Memory);
        let volatile = PhantomClient::new(options).unwrap();
        assert_eq!(
            volatile.inner.host.store.capabilities().durability,
            StorageDurability::Volatile
        );
        fn send_sync<T: Send + Sync>() {}
        send_sync::<PhantomClient>();
        fn send<T: Send>(_: T) {}
        send(persistent.flush_storage(CallOptions::default()));
    }
}
