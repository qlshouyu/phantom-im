use crate::{Scheduler, SdkFuture};
use phantom_domain::*;
use std::sync::Arc;
#[derive(Clone, Debug)]
pub enum GatewayRequest {
    Authenticate {
        app_id: String,
        input: LoginOptions,
        session: SessionInfo,
    },
    Send(Box<ChatMessage>),
    Recall(String),
    Sync {
        since: Seq,
        limit: u32,
    },
    Ping,
    Logout,
    Typing {
        conv_id: String,
        typing: bool,
    },
    SubscribePresence(Vec<String>),
    UnsubscribePresence(Vec<String>),
    AppState(bool),
}
#[derive(Clone, Debug, PartialEq)]
pub struct MessageConfirmation {
    pub msg_id: String,
    pub conv_seq: Seq,
    pub server_time: TimestampMs,
}
#[derive(Clone, Debug)]
pub struct SyncBatch {
    pub events: Vec<SyncEvent>,
    pub max_user_seq: Seq,
    pub has_more: bool,
    pub full_resync: bool,
}
#[derive(Clone, Debug)]
pub enum GatewayResponse {
    Authenticated(LoginResult),
    Sent(MessageConfirmation),
    Recalled(RecallResult),
    Sync(SyncBatch),
    Presence(Vec<Presence>),
    Done,
}
#[derive(Clone, Debug)]
pub enum GatewayPush {
    Sync {
        event: Box<SyncEvent>,
        ack_required: bool,
    },
    Confirmation {
        client_msg_id: String,
        result: SdkResult<MessageConfirmation>,
    },
    Presence(Presence),
    Typing(TypingEvent),
    Kicked(KickInfo),
}
pub trait Gateway: Send + Sync {
    fn request(&self, input: GatewayRequest) -> SdkFuture<'_, GatewayResponse>;
    fn next_push(&self) -> SdkFuture<'_, Option<GatewayPush>>;
    fn acknowledge(&self, user_seq: Seq) -> SdkFuture<'_, ()>;
    fn close(&self) -> SdkFuture<'_, ()>;
}
pub trait GatewayFactory: Send + Sync {
    fn connect(&self, scheduler: Arc<dyn Scheduler>) -> SdkFuture<'_, Arc<dyn Gateway>>;
}
