#![allow(dead_code)]
use phantom_runtime::{DefaultClock, DefaultFiles, DefaultHashing, DefaultIds, DefaultScheduler};
use phantom_sdk::*;
use std::{
    collections::VecDeque,
    sync::{
        Arc, Mutex,
        atomic::{AtomicI64, AtomicU64, Ordering},
    },
};
use tokio::sync::{Mutex as AsyncMutex, Notify, mpsc};
#[derive(Default)]
pub struct Server {
    pub gateway_requests: Mutex<Vec<GatewayRequest>>,
    pub signal_response: Mutex<Option<GatewayResponse>>,
    pub token_override: Mutex<Option<String>>,
    pub corrupt_auth: std::sync::atomic::AtomicBool,
    pub ping_error: Mutex<Option<SdkError>>,
    pub identities: Mutex<Vec<HttpIdentity>>,
    pub history: Mutex<Vec<ChatMessage>>,
    pub scripts: Mutex<VecDeque<SyncBatch>>,
    pub target: AtomicI64,
    pub connects: AtomicU64,
    pub requests: Mutex<Vec<String>>,
    pub release: Notify,
    pub block_auth: std::sync::atomic::AtomicBool,
    pub pushes: Mutex<Vec<mpsc::UnboundedSender<GatewayPush>>>,
    pub acks: Mutex<Vec<Seq>>,
    pub block_send: std::sync::atomic::AtomicBool,
    pub block_sync: std::sync::atomic::AtomicBool,
}
impl Server {
    pub fn host(self: &Arc<Self>, store: Arc<dyn StoreFactory>) -> HostServices {
        HostServices {
            store,
            gateway: Arc::new(Factory(self.clone())),
            api: self.clone(),
            clock: Arc::new(DefaultClock),
            scheduler: Arc::new(DefaultScheduler),
            ids: Arc::new(DefaultIds),
            files: Arc::new(DefaultFiles),
            hashing: Arc::new(DefaultHashing),
            media: self.clone(),
        }
    }
    pub fn client(self: &Arc<Self>, store: Arc<dyn StoreFactory>) -> PhantomClient {
        PhantomClient::with_host(
            ClientOptions::new("https://example.test/prefix", "app"),
            self.host(store),
        )
        .unwrap()
    }
    pub fn push(&self, index: usize, push: GatewayPush) {
        self.pushes.lock().unwrap()[index].send(push).unwrap();
    }
}
pub fn login(user: &str) -> LoginOptions {
    LoginOptions {
        user_token: format!("token-{user}"),
        user_id: user.into(),
        device_id: format!("device-{user}"),
        device_type: DeviceType::Desktop,
        platform: "test".into(),
        client_version: None,
    }
}
pub fn input(id: &str, text: &str) -> SendMessageInput {
    SendMessageInput {
        conv_id: "gr_group".into(),
        conv_type: ConversationType::Group,
        content: MessageContent::Text(TextContent { text: text.into() }),
        reply_to_msg_id: None,
        mentions: Vec::new(),
        client_msg_id: Some(id.into()),
    }
}
pub fn message(id: &str, seq: Seq) -> ChatMessage {
    ChatMessage {
        msg_id: Some(format!("msg-{id}")),
        client_msg_id: id.into(),
        conv_id: "gr_group".into(),
        conv_type: ConversationType::Group,
        sender_id: "user".into(),
        kind: MessageKind::Text,
        content: Some(MessageContent::Text(TextContent {
            text: "正文".into(),
        })),
        text: "正文".into(),
        conv_seq: Some(seq),
        server_time: Some(10),
        state: MessageState::Sent,
        recalled: false,
        restricted: false,
        is_request: false,
        reply_to_msg_id: None,
        reply_snapshot: None,
        mentions: Some(Vec::new()),
        burn_after_read_ttl_seconds: 0,
        error: None,
    }
}
pub fn event(id: &str, seq: Seq) -> SyncEvent {
    let message = message(id, seq);
    SyncEvent {
        user_seq: seq,
        event_time: 10,
        payload: SyncPayload::NewMessage(NewMessageEvent {
            conv_id: message.conv_id.clone(),
            conv_seq: seq,
            msg_id: message.msg_id.clone().unwrap(),
            is_request: false,
            msg: Some(message),
        }),
    }
}
struct Connection {
    server: Arc<Server>,
    user: Mutex<Option<String>>,
    receiver: AsyncMutex<mpsc::UnboundedReceiver<GatewayPush>>,
}
struct Factory(Arc<Server>);
impl GatewayFactory for Factory {
    fn connect(&self, _: Arc<dyn Scheduler>) -> SdkFuture<'_, Arc<dyn Gateway>> {
        Box::pin(async {
            self.0.connects.fetch_add(1, Ordering::Relaxed);
            let (sender, receiver) = mpsc::unbounded_channel();
            self.0.pushes.lock().unwrap().push(sender);
            Ok(Arc::new(Connection {
                server: self.0.clone(),
                user: Mutex::default(),
                receiver: AsyncMutex::new(receiver),
            }) as Arc<dyn Gateway>)
        })
    }
}
impl Gateway for Connection {
    fn request(&self, input: GatewayRequest) -> SdkFuture<'_, GatewayResponse> {
        Box::pin(async move {
            self.server
                .gateway_requests
                .lock()
                .unwrap()
                .push(input.clone());
            if matches!(
                input,
                GatewayRequest::Typing { .. } | GatewayRequest::AppState(_)
            ) && let Some(response) = self.server.signal_response.lock().unwrap().clone()
            {
                return Ok(response);
            }
            self.server.requests.lock().unwrap().push(
                match &input {
                    GatewayRequest::Authenticate { .. } => "auth",
                    GatewayRequest::Send(_) => "send",
                    GatewayRequest::Sync { .. } => "sync",
                    GatewayRequest::Ping => "ping",
                    GatewayRequest::Logout => "logout",
                    _ => "other",
                }
                .into(),
            );
            Ok(match input {
                GatewayRequest::Authenticate { input, session, .. } => {
                    if self.server.block_auth.load(Ordering::Acquire) {
                        self.server.release.notified().await;
                    }
                    if input.user_token != format!("token-{}", input.user_id)
                        && self.server.token_override.lock().unwrap().as_ref()
                            != Some(&input.user_token)
                    {
                        let mut error =
                            SdkError::new(ErrorKind::Authentication, "测试服务器验签拒绝");
                        error.server_code = Some(2003);
                        error.outcome = OperationOutcome::Rejected;
                        return Err(error);
                    }
                    *self.user.lock().unwrap() = Some(input.user_id);
                    let mut result = LoginResult {
                        session,
                        server_time: DefaultClock.now_ms(),
                        max_user_seq: self.server.target.load(Ordering::Relaxed),
                        heartbeat_interval_s: 60,
                        app_config: AppConfig {
                            burn_after_read: BurnAfterReadConfig {
                                enabled: true,
                                ttl_seconds: 1,
                            },
                            read_receipt_enabled: true,
                            relation_hosted: false,
                            sdk_profile_write_enabled: true,
                            max_devices: 5,
                        },
                    };
                    if self.server.corrupt_auth.load(Ordering::Acquire) {
                        result.session.user_id = "wrong-identity".into();
                    }
                    GatewayResponse::Authenticated(result)
                }
                GatewayRequest::Sync { since, .. } => {
                    if self.server.block_sync.load(Ordering::Acquire) {
                        self.server.release.notified().await;
                    }
                    let scripted = self.server.scripts.lock().unwrap().pop_front();
                    GatewayResponse::Sync(scripted.unwrap_or(SyncBatch {
                        events: Vec::new(),
                        max_user_seq: since.max(self.server.target.load(Ordering::Relaxed)),
                        has_more: false,
                        full_resync: false,
                    }))
                }
                GatewayRequest::Send(message) => {
                    let mut message = *message;
                    if self.server.block_send.load(Ordering::Acquire) {
                        self.server.release.notified().await;
                    }
                    let mut history = self.server.history.lock().unwrap();
                    let existing = history
                        .iter()
                        .find(|m| m.client_msg_id == message.client_msg_id)
                        .cloned();
                    let result = if let Some(old) = existing {
                        old
                    } else {
                        message.msg_id = Some(format!("msg-{}", message.client_msg_id));
                        message.conv_seq = Some(history.len() as i64 + 1);
                        message.server_time = Some(DefaultClock.now_ms());
                        message.state = MessageState::Sent;
                        history.push(message.clone());
                        message
                    };
                    GatewayResponse::Sent(MessageConfirmation {
                        msg_id: result.msg_id.unwrap(),
                        conv_seq: result.conv_seq.unwrap(),
                        server_time: result.server_time.unwrap(),
                    })
                }
                GatewayRequest::Ping => {
                    if let Some(error) = self.server.ping_error.lock().unwrap().clone() {
                        return Err(error);
                    }
                    GatewayResponse::Done
                }
                GatewayRequest::Recall(msg_id) => {
                    GatewayResponse::Recalled(RecallResult { msg_id })
                }
                GatewayRequest::SubscribePresence(ids) => GatewayResponse::Presence(
                    ids.into_iter()
                        .map(|user_id| Presence {
                            user_id,
                            status: PresenceStatus::Online,
                            last_active_time: None,
                        })
                        .collect(),
                ),
                _ => GatewayResponse::Done,
            })
        })
    }
    fn next_push(&self) -> SdkFuture<'_, Option<GatewayPush>> {
        Box::pin(async { Ok(self.receiver.lock().await.recv().await) })
    }
    fn acknowledge(&self, seq: Seq) -> SdkFuture<'_, ()> {
        Box::pin(async move {
            self.server.acks.lock().unwrap().push(seq);
            Ok(())
        })
    }
    fn close(&self) -> SdkFuture<'_, ()> {
        Box::pin(async { Ok(()) })
    }
}
impl PlatformApi for Server {
    fn execute(
        &self,
        request: ApiRequest,
        identity: HttpIdentity,
        _: String,
    ) -> SdkFuture<'_, ApiResponse> {
        Box::pin(async move {
            self.identities.lock().unwrap().push(identity.clone());
            self.requests
                .lock()
                .unwrap()
                .push(request.operation().into());
            Ok(match request {
                ApiRequest::ListConversations { .. } => {
                    ApiResponse::ListConversations(Box::new(ConversationPage {
                        items: vec![],
                        next_cursor: None,
                        has_more: false,
                        max_user_seq: 0,
                        badge_count: 0,
                    }))
                }
                ApiRequest::GetUserProfile { .. } => {
                    ApiResponse::GetUserProfile(Box::new(UserProfile {
                        user_id: identity.user_id,
                        app_id: identity.app_id,
                        nickname: "昵称".into(),
                        avatar_url: None,
                        ext: Default::default(),
                        status: "ACTIVE".into(),
                    }))
                }
                ApiRequest::ListGroups => ApiResponse::ListGroups(Box::default()),
                ApiRequest::History { query } => {
                    let mut items: Vec<_> = self
                        .history
                        .lock()
                        .unwrap()
                        .iter()
                        .filter(|m| {
                            m.conv_id == query.conv_id
                                && query
                                    .before_conv_seq
                                    .is_none_or(|n| m.conv_seq.is_some_and(|seq| seq < n))
                        })
                        .cloned()
                        .collect();
                    items.sort_by_key(|m| m.conv_seq);
                    let has_more = items.len() > query.limit as usize;
                    items = items.into_iter().rev().take(query.limit as usize).collect();
                    items.reverse();
                    let next_cursor = if has_more {
                        items.first().and_then(|m| m.conv_seq)
                    } else {
                        None
                    };
                    ApiResponse::History(Box::new(MessagePage {
                        items,
                        next_cursor,
                        has_more,
                    }))
                }
                ApiRequest::DeleteLocal { msg_id } => {
                    self.history
                        .lock()
                        .unwrap()
                        .retain(|m| m.msg_id.as_ref() != Some(&msg_id));
                    ApiResponse::DeleteLocal
                }
                ApiRequest::ListFriends => ApiResponse::ListFriends(Box::default()),
                _ => {
                    return Err(SdkError::new(
                        ErrorKind::Protocol,
                        "该测试未提供此领域 fixture",
                    ));
                }
            })
        })
    }
}
pub fn host(server: &Arc<Server>, store: Arc<dyn StoreFactory>) -> HostServices {
    let mut host = server.host(store);
    host.gateway = Arc::new(Factory(server.clone()));
    host
}
pub fn client(server: &Arc<Server>, store: Arc<dyn StoreFactory>) -> PhantomClient {
    PhantomClient::with_host(
        ClientOptions::new("https://example.test/prefix", "app"),
        host(server, store),
    )
    .unwrap()
}
pub async fn ready(client: &PhantomClient) {
    client
        .login(login("user"), CallOptions::default())
        .await
        .unwrap();
    client.wait_ready(CallOptions::default()).await.unwrap();
}

impl MediaAccess for Server {
    fn open(&self, _: String, _: HttpIdentity) -> SdkFuture<'_, MediaDownload> {
        Box::pin(async { Err(SdkError::new(ErrorKind::Protocol, "测试未配置媒体 fixture")) })
    }
}
