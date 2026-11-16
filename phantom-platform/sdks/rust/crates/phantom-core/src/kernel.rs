use crate::*;
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicU64, Ordering},
};

#[derive(Clone)]
pub struct KernelView {
    pub state: ConnectionState,
    pub session: Option<SessionInfo>,
    pub config: Option<AppConfig>,
    pub sync: SyncStatus,
    pub storage: StorageSnapshot,
    pub failure: Option<SdkError>,
}
impl Default for KernelView {
    fn default() -> Self {
        Self {
            state: ConnectionState::Idle,
            session: None,
            config: None,
            sync: SyncStatus {
                phase: SyncPhase::NotStarted,
                applied_user_seq: 0,
                target_user_seq: 0,
            },
            storage: StorageSnapshot::default(),
            failure: None,
        }
    }
}
#[derive(Clone)]
pub struct HostServices {
    pub store: Arc<dyn StoreFactory>,
    pub gateway: Arc<dyn GatewayFactory>,
    pub api: Arc<dyn PlatformApi>,
    pub clock: Arc<dyn Clock>,
    pub scheduler: Arc<dyn Scheduler>,
    pub ids: Arc<dyn Ids>,
    pub files: Arc<dyn Files>,
    pub hashing: Arc<dyn Hashing>,
    pub media: Arc<dyn MediaAccess>,
}
pub(crate) struct PendingConversation {
    pub old: Conversation,
    pub optimistic: Conversation,
    pub parent: (u64, u64),
    pub stamp: (u64, u64),
    pub pinned: bool,
    pub muted: bool,
}
pub struct Kernel {
    pub pending_displays: std::collections::BTreeMap<String, DisplayDeadline>,
    pub(crate) pending_conversations: std::collections::BTreeMap<u64, PendingConversation>,
    pub display_monotonic: std::collections::BTreeMap<String, u64>,
    pub conversation_stamps: std::collections::BTreeMap<String, (u64, u64)>,
    pub change_stamp: u64,
    pub host: HostServices,
    pub service_url: String,
    pub app_id: String,
    pub events: EventHub,
    pub view: Arc<Mutex<KernelView>>,
    pub fence: Arc<AtomicU64>,
    pub epoch: u64,
    pub generation: u64,
    pub current: KernelView,
    pub account: Option<OpenedAccount>,
    pub connection: Option<Arc<dyn Gateway>>,
    pub token_invalid: bool,
    pub heartbeat_ms: u64,
    pub heartbeat_at: u64,
    pub retry_at: u64,
    pub retry_attempt: u32,
    pub server_offset: i64,
    pub credentials: Option<LoginOptions>,
    pub kicked: bool,
    pub typing_at: std::collections::BTreeMap<String, u64>,
}
impl Kernel {
    pub fn new(
        service_url: String,
        app_id: String,
        host: HostServices,
        events: EventHub,
        view: Arc<Mutex<KernelView>>,
        fence: Arc<AtomicU64>,
    ) -> Self {
        Self {
            pending_displays: Default::default(),
            pending_conversations: Default::default(),
            display_monotonic: Default::default(),
            conversation_stamps: Default::default(),
            change_stamp: 0,
            service_url,
            app_id,
            host,
            events,
            view,
            fence,
            epoch: 0,
            generation: 0,
            current: KernelView::default(),
            account: None,
            connection: None,
            credentials: None,
            kicked: false,
            token_invalid: false,
            heartbeat_ms: 0,
            heartbeat_at: 0,
            retry_at: 0,
            retry_attempt: 0,
            server_offset: 0,
            typing_at: Default::default(),
        }
    }
    pub fn touch_conversation(&mut self, id: &str, pinned: bool, muted: bool) {
        self.change_stamp = self.change_stamp.saturating_add(1);
        let entry = self.conversation_stamps.entry(id.into()).or_default();
        if pinned {
            entry.0 = self.change_stamp;
        }
        if muted {
            entry.1 = self.change_stamp;
        }
    }
    pub fn live(&self) -> SdkResult<()> {
        if self.current.state == ConnectionState::Released {
            return Err(SdkError::new(ErrorKind::Released, "SDK 已释放"));
        }
        if self.epoch != self.fence.load(Ordering::Acquire) {
            return Err(SdkError::new(ErrorKind::Cancelled, "账号会话已结束"));
        }
        Ok(())
    }
    pub fn authenticated(&self) -> SdkResult<()> {
        self.live()?;
        if self.current.session.is_none() {
            return Err(SdkError::new(ErrorKind::NotLoggedIn, "尚未登录"));
        }
        Ok(())
    }
    pub fn online(&self) -> SdkResult<()> {
        self.authenticated()?;
        if self.current.state != ConnectionState::Online {
            return Err(SdkError::new(ErrorKind::NotConnected, "当前连接不在线"));
        }
        Ok(())
    }
    pub fn publish_view(&mut self) {
        if self.epoch != self.fence.load(Ordering::Acquire) {
            return;
        }
        if let Some(account) = &self.account {
            self.current.storage = account.snapshot.clone();
            self.current.sync.applied_user_seq = account.snapshot.user_seq;
        }
        *self.view.lock().unwrap_or_else(|e| e.into_inner()) = self.current.clone();
    }
    pub fn emit(&self, kind: EventKind) {
        if self.epoch == self.fence.load(Ordering::Acquire) {
            let _ = self.events.publish(self.current.session.clone(), kind);
        }
    }
    pub fn state(&mut self, state: ConnectionState) {
        self.current.state = state.clone();
        self.publish_view();
        self.emit(EventKind::StateChanged(state));
    }
    pub fn identity(&self) -> SdkResult<HttpIdentity> {
        self.authenticated()?;
        if self.token_invalid {
            return Err(SdkError::new(
                ErrorKind::Authentication,
                "凭据已失效，请更新 Token",
            ));
        }
        let input = self
            .credentials
            .as_ref()
            .ok_or_else(|| SdkError::new(ErrorKind::NotLoggedIn, "当前凭据已清除"))?;
        Ok(HttpIdentity {
            user_token: input.user_token.clone(),
            app_id: self.app_id.clone(),
            user_id: input.user_id.clone(),
            device_id: input.device_id.clone(),
        })
    }
    pub fn snapshot(&self) -> SdkResult<&StorageSnapshot> {
        self.account
            .as_ref()
            .map(|a| &a.snapshot)
            .ok_or_else(|| SdkError::new(ErrorKind::NotLoggedIn, "账号存储尚未打开"))
    }
    pub async fn commit(&mut self, next: StorageSnapshot, full: bool) -> SdkResult<()> {
        self.live()?;
        let account = self
            .account
            .as_mut()
            .ok_or_else(|| SdkError::new(ErrorKind::Storage, "账号存储尚未打开"))?;
        let old = account.snapshot.clone();
        let mutations = if full {
            vec![StorageMutation::ReplaceState(Box::new(next))]
        } else {
            changes_between(&old, &next)
        };
        account.apply(mutations).await?;
        self.publish_view();
        self.live()?;
        let next = self.snapshot()?;
        let changed: Vec<_> = next
            .messages
            .iter()
            .filter(|v| {
                old.messages
                    .iter()
                    .find(|o| o.client_msg_id == v.client_msg_id)
                    != Some(v)
            })
            .cloned()
            .collect();
        for message in changed {
            self.emit(EventKind::MessageChanged(message));
        }
        Ok(())
    }
    pub async fn login(
        &mut self,
        mut input: LoginOptions,
        context: &CallContext,
        epoch: u64,
    ) -> SdkResult<LoginResult> {
        if self.current.state == ConnectionState::Released {
            return Err(SdkError::new(ErrorKind::Released, "SDK 已释放"));
        }
        if self.current.session.is_some() {
            return Err(SdkError::new(ErrorKind::Conflict, "已有登录会话"));
        }
        validate_id(&input.user_token, "user_token")?;
        validate_id(&input.user_id, "user_id")?;
        validate_id(&input.device_id, "device_id")?;
        validate_id(&input.platform, "platform")?;
        context.check()?;
        self.epoch = epoch;
        self.live()?;
        if input.client_version.is_none() {
            input.client_version = Some(env!("CARGO_PKG_VERSION").into());
        }
        self.current.failure = None;
        self.kicked = false;
        self.token_invalid = false;
        self.state(ConnectionState::Connecting);
        let result = async {
            let connection = context
                .run(self.host.gateway.connect(self.host.scheduler.clone()))
                .await?;
            self.connection = Some(connection.clone());
            self.state(ConnectionState::Authenticating);
            let session = SessionInfo {
                session_id: self.host.ids.generate()?,
                user_id: input.user_id.clone(),
                device_id: input.device_id.clone(),
            };
            context.mark_submitted();
            let response = context
                .run(connection.request(GatewayRequest::Authenticate {
                    app_id: self.app_id.clone(),
                    input: input.clone(),
                    session: session.clone(),
                }))
                .await?;
            let GatewayResponse::Authenticated(result) = response else {
                return Err(SdkError::new(ErrorKind::Protocol, "认证未返回登录结果"));
            };
            validate_login(&result, &input, &session)?;
            let namespace = StorageNamespace {
                service_url: self.service_url.clone(),
                app_id: self.app_id.clone(),
                user_id: input.user_id.clone(),
            };
            // 恢复中的持久提交必须完成；调用取消由后续检查返回，不能遗留半打开句柄。
            let account = context
                .run(open_account_guarded(
                    self.host.store.clone(),
                    namespace,
                    self.host.scheduler.clone(),
                ))
                .await?;
            if let Err(error) = context.check().and_then(|_| self.live()) {
                let _ = account.store.close().await;
                return Err(error);
            }
            self.account = Some(account);
            if let Some(account) = &mut self.account {
                let wall = self.host.clock.now_ms();
                account.recover_expired(wall).await?;
                let monotonic = self.host.clock.monotonic_ms();
                self.display_monotonic = account
                    .snapshot
                    .display_deadlines
                    .iter()
                    .map(|d| {
                        let remaining = d.deadline_at.saturating_sub(wall).max(0) as u64;
                        (d.msg_id.clone(), monotonic.saturating_add(remaining))
                    })
                    .collect();
            }
            self.credentials = Some(input);
            self.generation = self
                .generation
                .checked_add(1)
                .ok_or_else(|| SdkError::new(ErrorKind::Conflict, "连接代次耗尽"))?;
            self.accept_connection(&result);
            self.current.session = Some(result.session.clone());
            self.current.config = Some(result.app_config.clone());
            self.current.sync = SyncStatus {
                phase: SyncPhase::Syncing,
                applied_user_seq: self.snapshot()?.user_seq,
                target_user_seq: result.max_user_seq.max(self.snapshot()?.user_seq),
            };
            self.state(ConnectionState::Online);
            self.emit(EventKind::Connected(result.clone()));
            self.emit(EventKind::SyncProgress(self.current.sync.clone()));
            Ok(result)
        }
        .await;
        if let Err(error) = &result {
            if let Some(connection) = self.connection.take() {
                let _ = connection.close().await;
            }
            if let Some(account) = self.account.take() {
                let _ = account.store.close().await;
            }
            self.credentials = None;
            self.current = KernelView {
                state: ConnectionState::Closed,
                failure: Some(error.clone()),
                ..KernelView::default()
            };
            self.publish_view();
            self.emit(EventKind::StateChanged(ConnectionState::Closed));
        }
        result
    }
    pub async fn reauthenticate(
        &mut self,
        token: Option<TokenUpdate>,
        context: &CallContext,
    ) -> SdkResult<LoginResult> {
        self.authenticated()?;
        if self.kicked {
            return Err(SdkError::new(
                ErrorKind::Conflict,
                "当前会话已被踢下线；请先退出后再登录",
            ));
        }
        if token.is_none() && self.token_invalid {
            return Err(SdkError::new(
                ErrorKind::Authentication,
                "旧 Token 已失效，停止重连",
            ));
        }
        let mut input = self
            .credentials
            .clone()
            .ok_or_else(|| SdkError::new(ErrorKind::Authentication, "凭据已失效"))?;
        if let Some(token) = token {
            validate_id(&token.user_token, "user_token")?;
            input.user_token = token.user_token;
        }
        let old_state = self.current.state.clone();
        self.state(ConnectionState::Reconnecting);
        let new_connection = context
            .run(self.host.gateway.connect(self.host.scheduler.clone()))
            .await;
        let connection = match new_connection {
            Ok(c) => c,
            Err(e) => {
                self.state(old_state);
                return Err(e);
            }
        };
        let result = async {
            let session = self
                .current
                .session
                .clone()
                .ok_or_else(|| SdkError::new(ErrorKind::NotLoggedIn, "尚未登录"))?;
            context.mark_submitted();
            let response = context
                .run(connection.request(GatewayRequest::Authenticate {
                    app_id: self.app_id.clone(),
                    input: input.clone(),
                    session: session.clone(),
                }))
                .await?;
            let GatewayResponse::Authenticated(result) = response else {
                return Err(SdkError::new(ErrorKind::Protocol, "重新认证未返回登录结果"));
            };
            validate_login(&result, &input, &session)?;
            context.check()?;
            self.live()?;
            self.generation = self
                .generation
                .checked_add(1)
                .ok_or_else(|| SdkError::new(ErrorKind::Conflict, "连接代次耗尽"))?;
            self.accept_connection(&result);
            let old = self.connection.replace(connection.clone());
            self.credentials = Some(input);
            self.current.config = Some(result.app_config.clone());
            self.current.sync.phase = SyncPhase::Syncing;
            self.current.sync.target_user_seq =
                self.current.sync.target_user_seq.max(result.max_user_seq);
            self.current.failure = None;
            self.state(ConnectionState::Online);
            self.emit(EventKind::Connected(result.clone()));
            if let Some(old) = old {
                let _ = old.close().await;
            }
            Ok(result)
        }
        .await;
        if result.is_err() {
            let _ = connection.close().await;
            self.state(old_state);
        }
        result
    }
    pub async fn close(&mut self, clear: bool, released: bool, epoch: u64) -> SdkResult<()> {
        self.epoch = epoch;
        self.generation = self.generation.saturating_add(1);
        self.credentials = None;
        self.kicked = false;
        self.token_invalid = false;
        self.typing_at.clear();
        self.display_monotonic.clear();
        let pending_displays = std::mem::take(&mut self.pending_displays);
        self.conversation_stamps.clear();
        self.pending_conversations.clear();
        self.current = KernelView {
            state: if released {
                ConnectionState::Released
            } else {
                ConnectionState::Closed
            },
            ..KernelView::default()
        };
        self.events.clear(released);
        self.publish_view();
        let connection = self.connection.take();
        let account = self.account.take();
        let clock = self.host.clock.clone();
        let cleanup = self.host.scheduler.spawn(Box::pin(async move {
            let bounded = || {
                CallContext::new(
                    CallOptions {
                        timeout_ms: Some(1_000),
                        cancellation: None,
                    },
                    None,
                    clock.clone(),
                )
            };
            let mut first = None;
            if let Some(connection) = connection {
                // 已移除可见身份，但仍用当前连接显式注销平台会话；每步失败不跳过后续关闭。
                if let Err(e) = bounded()?
                    .run(connection.request(GatewayRequest::Logout))
                    .await
                {
                    first.get_or_insert(e);
                }
                if let Err(e) = bounded()?.run(connection.close()).await {
                    first.get_or_insert(e);
                }
            }
            if let Some(mut account) = account {
                if !clear && !pending_displays.is_empty() {
                    let mut next = account.snapshot.clone();
                    for pending in pending_displays.into_values() {
                        if !next
                            .display_deadlines
                            .iter()
                            .any(|d| d.msg_id == pending.msg_id)
                            && next.messages.iter().any(|m| {
                                m.msg_id.as_deref() == Some(&pending.msg_id)
                                    && !matches!(
                                        m.state,
                                        MessageState::Recalled | MessageState::Burned
                                    )
                            })
                        {
                            next.display_deadlines.push(pending);
                        }
                    }
                    if let Err(error) = account
                        .apply(changes_between(&account.snapshot, &next))
                        .await
                    {
                        first.get_or_insert(error);
                    }
                }
                if clear && let Err(e) = bounded()?.run(account.store.clear()).await {
                    first.get_or_insert(storage_error(e));
                }
                if let Err(e) = bounded()?.run(account.store.flush()).await {
                    first.get_or_insert(storage_error(e));
                }
                // 关闭由独立任务持有所有权。调用方超时不会丢弃已开始的宿主关闭回调。
                if let Err(e) = account.store.close().await {
                    first.get_or_insert(storage_error(e));
                }
            }
            first.map_or(Ok(()), Err)
        }));
        let context = CallContext::new(
            CallOptions {
                timeout_ms: Some(5_000),
                cancellation: None,
            },
            None,
            self.host.clock.clone(),
        )?;
        let result = context.run(cleanup.join()).await;
        self.emit(EventKind::StateChanged(self.current.state.clone()));
        result
    }
    pub async fn flush(&mut self, context: &CallContext) -> SdkResult<()> {
        if self.current.session.is_some() {
            self.persist_displays().await?;
        }
        self.live()?;
        match &self.account {
            Some(account) => context
                .run(account.store.flush())
                .await
                .map_err(storage_error),
            None => Ok(()),
        }
    }
    pub async fn gateway_request(
        &self,
        request: GatewayRequest,
        context: &CallContext,
    ) -> SdkResult<GatewayResponse> {
        self.online()?;
        context.check()?;
        let connection = self
            .connection
            .as_ref()
            .ok_or_else(|| SdkError::new(ErrorKind::NotConnected, "平台连接不存在"))?;
        context.mark_submitted();
        context.run(connection.request(request)).await
    }
    pub async fn api_request(
        &self,
        request: ApiRequest,
        context: &CallContext,
    ) -> SdkResult<ApiResponse> {
        self.authenticated()?;
        context.check()?;
        let identity = self.identity()?;
        let request_id = self.host.ids.generate()?;
        let operation = request.operation();
        request.validate()?;
        context.mark_submitted();
        let response = context
            .run(self.host.api.execute(request, identity, request_id))
            .await?;
        self.live()?;
        if response.operation() != operation {
            return Err(SdkError::new(
                ErrorKind::Protocol,
                "HTTP 返回类型与领域请求不匹配",
            ));
        }
        Ok(response)
    }
    pub fn failed(&mut self, error: SdkError) {
        self.current.failure = Some(error.clone());
        self.current.sync.phase = SyncPhase::Failed;
        self.publish_view();
        self.emit(EventKind::Error(error));
        self.emit(EventKind::SyncProgress(self.current.sync.clone()));
    }
    fn accept_connection(&mut self, result: &LoginResult) {
        self.token_invalid = false;
        self.retry_attempt = 0;
        self.retry_at = 0;
        self.heartbeat_ms = u64::from(result.heartbeat_interval_s).saturating_mul(1000);
        self.heartbeat_at = self
            .host
            .clock
            .monotonic_ms()
            .saturating_add(self.heartbeat_ms);
        self.server_offset = result.server_time.saturating_sub(self.host.clock.now_ms());
    }
    pub fn calibrated_now(&self) -> i64 {
        self.host
            .clock
            .now_ms()
            .saturating_add(self.server_offset)
            .max(0)
    }
    pub fn schedule_retry(&mut self) {
        let base = 1000u64
            .saturating_mul(1u64.checked_shl(self.retry_attempt.min(6)).unwrap_or(64))
            .min(60_000);
        // 抖动种子不含凭据。虚拟时钟下可重复，区间始终在 ±30% 且不超过 60 秒。
        let seed = self
            .host
            .clock
            .monotonic_ms()
            .wrapping_mul(6364136223846793005)
            .wrapping_add(self.generation.wrapping_mul(1442695040888963407))
            .wrapping_add(u64::from(self.retry_attempt));
        let percent = 70 + seed % 61;
        self.retry_at = self
            .host
            .clock
            .monotonic_ms()
            .saturating_add((base.saturating_mul(percent) / 100).min(60_000));
        self.retry_attempt = self.retry_attempt.saturating_add(1);
    }
    pub async fn connection_lost(&mut self, error: SdkError) {
        if self.kicked || self.current.state == ConnectionState::Released {
            return;
        }
        if error.kind == ErrorKind::Authentication {
            self.token_invalid = true;
            self.state(ConnectionState::Closed);
            if matches!(error.server_code, Some(2001 | 2002)) {
                self.emit(EventKind::TokenExpired);
            }
        } else {
            self.state(ConnectionState::Reconnecting);
            self.schedule_retry();
        }
        self.emit(EventKind::Disconnected(DisconnectInfo {
            reason: error.message.clone(),
            will_reconnect: !self.kicked && !self.token_invalid,
        }));
        self.failed(error);
        if let Some(connection) = self.connection.take() {
            let _ = connection.close().await;
        }
    }
    pub async fn persist_displays(&mut self) -> SdkResult<()> {
        if self.pending_displays.is_empty() {
            return Ok(());
        }
        let mut next = self.snapshot()?.clone();
        for pending in self.pending_displays.values() {
            if !next
                .display_deadlines
                .iter()
                .any(|d| d.msg_id == pending.msg_id)
                && next.messages.iter().any(|m| {
                    m.msg_id.as_deref() == Some(&pending.msg_id)
                        && !matches!(m.state, MessageState::Burned | MessageState::Recalled)
                })
            {
                next.display_deadlines.push(pending.clone());
            }
        }
        self.commit(next, false).await?;
        self.pending_displays.clear();
        Ok(())
    }
    pub async fn burn_expired(&mut self) -> SdkResult<()> {
        self.authenticated()?;
        self.persist_displays().await?;
        let old = self.snapshot()?.clone();
        let account = self
            .account
            .as_mut()
            .ok_or_else(|| SdkError::new(ErrorKind::Storage, "账号存储尚未打开"))?;
        let now = self.host.clock.monotonic_ms();
        let due = self
            .display_monotonic
            .iter()
            .filter(|(_, deadline)| **deadline <= now)
            .map(|(id, _)| id.clone())
            .collect();
        let messages = account.expire_messages(&due).await?;
        self.display_monotonic.retain(|id, _| {
            account
                .snapshot
                .display_deadlines
                .iter()
                .any(|d| &d.msg_id == id)
        });
        if !messages.is_empty() {
            self.publish_view();
            for message in messages {
                self.emit(EventKind::MessageChanged(message));
            }
        }
        let mut next = self.snapshot()?.clone();
        clear_derived_content(&mut next);
        if next != old {
            self.commit(next, false).await?;
        }
        Ok(())
    }
}

fn validate_login(
    result: &LoginResult,
    input: &LoginOptions,
    session: &SessionInfo,
) -> SdkResult<()> {
    if result.session != *session
        || result.session.user_id != input.user_id
        || result.session.device_id != input.device_id
        || result.max_user_seq < 0
        || result.server_time < 0
        || result.heartbeat_interval_s == 0
    {
        return Err(SdkError::new(
            ErrorKind::Protocol,
            "认证响应身份、同步目标或心跳参数无效",
        ));
    }
    Ok(())
}
