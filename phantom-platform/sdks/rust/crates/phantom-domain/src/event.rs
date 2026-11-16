use crate::*;

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct SubscriptionOptions {
    pub capacity: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct SdkEvent {
    pub event_id: u64,
    pub session: Option<SessionInfo>,
    pub kind: EventKind,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum EventKind {
    StateChanged(ConnectionState),
    Connected(LoginResult),
    Disconnected(DisconnectInfo),
    Kicked(KickInfo),
    TokenExpired,
    SyncProgress(SyncStatus),
    Ready(SyncStatus),
    MessageChanged(ChatMessage),
    MessageRemoved(MessageRemoved),
    Sync(SyncEvent),
    Presence(Presence),
    Typing(TypingEvent),
    TransferProgress(TransferProgress),
    NotificationClick(NotificationClick),
    Error(SdkError),
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct DisconnectInfo {
    pub reason: String,
    pub will_reconnect: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct KickInfo {
    pub reason: KickReason,
    pub detail: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum KickReason {
    SameTypeLogin,
    RemoteLogout,
    TokenRevoked,
    Banned,
    Unknown(i32),
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct MessageRemoved {
    pub conv_id: String,
    pub msg_ids: Vec<String>,
    pub all: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct SyncEvent {
    pub user_seq: Seq,
    pub event_time: TimestampMs,
    pub payload: SyncPayload,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[expect(
    clippy::large_enum_variant,
    reason = "公开接口契约要求直接承载事件 DTO，保持既定类型签名"
)]
pub enum SyncPayload {
    NewMessage(NewMessageEvent),
    Recall(RecallEvent),
    ReadPosition(ReadPositionEvent),
    ReadReceipt(ReadReceiptEvent),
    ConversationState(ConversationStateEvent),
    Draft(ConversationDraft),
    Relation(RelationEvent),
    Group(GroupEvent),
    Profile(ProfileEvent),
    RestrictedDelivery(RestrictedDeliveryEvent),
    MessageStatus(MessageStatusEvent),
    Report(ReportEvent),
    Unknown {
        field_number: u32,
        raw_bytes: Vec<u8>,
    },
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct NewMessageEvent {
    pub msg: Option<ChatMessage>,
    pub conv_id: String,
    pub conv_seq: Seq,
    pub msg_id: String,
    pub is_request: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct RecallEvent {
    pub msg_id: String,
    pub conv_id: String,
    pub operator_id: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ReadPositionEvent {
    pub conv_id: String,
    pub read_conv_seq: Seq,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ReadReceiptEvent {
    pub conv_id: String,
    pub read_conv_seq: Seq,
    pub reader_id: String,
    pub read_count: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum ConversationStateField {
    Pinned,
    Muted,
    Deleted,
    MarkedRead,
    ReadOnly,
    Unknown(i32),
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ConversationStateEvent {
    pub conv_id: String,
    pub field: ConversationStateField,
    pub bool_value: bool,
    pub op_time: TimestampMs,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum RelationEventType {
    ApplyReceived,
    ApplyAccepted,
    ApplyRejected,
    FriendDeleted,
    BlacklistAdded,
    BlacklistRemoved,
    ApplyIgnored,
    FriendSettingChanged,
    GroupChanged,
    ApplySent,
    Unknown(i32),
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct RelationEvent {
    pub kind: RelationEventType,
    pub peer_id: String,
    pub peer: Option<RelationUser>,
    pub request_id: Option<String>,
    pub verification_message: String,
    pub created_at: TimestampMs,
    pub group_ids: Vec<String>,
    pub pinned: bool,
    pub remark: String,
    pub group_id: Option<String>,
    pub group_name: String,
    pub group_deleted: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum GroupEventType {
    MemberJoined,
    MemberLeft,
    MemberKicked,
    RoleChanged,
    MuteChanged,
    Announcement,
    ProfileChanged,
    Dissolved,
    NicknameChanged,
    Unknown(i32),
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct GroupEvent {
    pub kind: GroupEventType,
    pub group_id: String,
    pub target_user_ids: Vec<String>,
    pub operator_id: String,
    pub params: StringMap,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum ProfileEventType {
    MyProfile,
    MySettings,
    FriendRemark,
    Unknown(i32),
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ProfileEvent {
    pub kind: ProfileEventType,
    pub target_id: String,
    pub changed: StringMap,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct RestrictedDeliveryEvent {
    pub conv_id: String,
    pub msg_id: String,
    pub template_id: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct MessageStatusEvent {
    pub msg_id: String,
    pub conv_id: String,
    pub status: MessageState,
    pub status_time: TimestampMs,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ReportEvent {
    pub report_id: String,
    pub target_type: String,
    pub target_id: String,
    pub status: String,
    pub template_id: String,
    pub updated_at: TimestampMs,
}
