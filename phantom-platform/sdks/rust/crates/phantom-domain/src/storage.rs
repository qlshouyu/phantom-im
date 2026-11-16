//! 与具体数据库无关的账号存储记录。原始认证凭据不属于存储模型。
use crate::*;

pub const STORAGE_CONTRACT_VERSION: u32 = 1;

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(
    Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, serde::Serialize, serde::Deserialize,
)]
pub struct StorageNamespace {
    /// 由传输配置规范化后的 HTTP 服务地址，包含部署前缀。
    pub service_url: String,
    pub app_id: String,
    pub user_id: String,
}
impl StorageNamespace {
    pub fn validate(&self) -> SdkResult<()> {
        validate_id(&self.service_url, "service_url")?;
        validate_id(&self.app_id, "app_id")?;
        validate_id(&self.user_id, "user_id")
    }
}

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum StorageDurability {
    Persistent,
    Volatile,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct StorageCapabilities {
    pub contract_version: u32,
    pub durability: StorageDurability,
    pub atomic_changes: bool,
    pub exclusive_writer: bool,
}
impl StorageCapabilities {
    pub fn validate(&self) -> SdkResult<()> {
        if self.contract_version != STORAGE_CONTRACT_VERSION
            || !self.atomic_changes
            || !self.exclusive_writer
        {
            return Err(SdkError::new(
                ErrorKind::Storage,
                "存储契约版本或原子提交/账号写入租约能力不兼容",
            ));
        }
        Ok(())
    }
}

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum InboxPayload {
    /// 已投影的事件只记录种类；不能在 Inbox 隐藏保存后续撤回/焚毁的原文。
    Applied { field_number: u32 },
    Unknown {
        field_number: u32,
        raw_bytes: Vec<u8>,
    },
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct InboxRecord {
    pub user_seq: Seq,
    pub event_time: TimestampMs,
    pub payload: InboxPayload,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct OutboxRecord {
    pub client_msg_id: String,
    pub message: ChatMessage,
    pub outcome: OperationOutcome,
    pub submitted: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum TombstoneReason {
    Deleted,
    Recalled,
    Burned,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct MessageTombstone {
    pub msg_id: String,
    pub client_msg_id: Option<String>,
    /// 未缓存消息的删除响应没有会话字段，保留真实缺失值。
    pub conv_id: Option<String>,
    pub reason: TombstoneReason,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ConversationTombstone {
    pub conv_id: String,
    pub through_conv_seq: Seq,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct DisplayDeadline {
    pub msg_id: String,
    pub first_displayed_at: TimestampMs,
    pub deadline_at: TimestampMs,
}

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(
    Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, serde::Serialize, serde::Deserialize,
)]
pub enum ProjectionKey {
    UserProfile(String),
    Friends,
    FriendRequests,
    Blacklist,
    FriendGroups,
    RelationSettings,
    Groups,
    GroupDetail(String),
    GroupMembers(String),
    Devices,
    PushSettings,
    PrivacyLinks,
    Reports,
    Appeals,
    AccountExports,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub enum DomainProjection {
    UserProfile(UserProfile),
    Friends(Vec<Friend>),
    FriendRequests(FriendRequestPage),
    Blacklist(Vec<BlacklistEntry>),
    FriendGroups(Vec<FriendGroup>),
    RelationSettings(RelationSettings),
    Groups(Vec<Group>),
    GroupDetail(GroupDetail),
    GroupMembers {
        group_id: String,
        members: Vec<GroupMember>,
    },
    Devices(Vec<DeviceInfo>),
    PushSettings(PushSettings),
    PrivacyLinks(PushSettings),
    Reports(Vec<Report>),
    Appeals(Vec<Appeal>),
    AccountExports(Vec<AccountExport>),
}
impl DomainProjection {
    pub fn key(&self) -> ProjectionKey {
        use DomainProjection::*;
        match self {
            UserProfile(v) => ProjectionKey::UserProfile(v.user_id.clone()),
            Friends(_) => ProjectionKey::Friends,
            FriendRequests(_) => ProjectionKey::FriendRequests,
            Blacklist(_) => ProjectionKey::Blacklist,
            FriendGroups(_) => ProjectionKey::FriendGroups,
            RelationSettings(_) => ProjectionKey::RelationSettings,
            Groups(_) => ProjectionKey::Groups,
            GroupDetail(v) => ProjectionKey::GroupDetail(v.summary.group_id.clone()),
            GroupMembers { group_id, .. } => ProjectionKey::GroupMembers(group_id.clone()),
            Devices(_) => ProjectionKey::Devices,
            PushSettings(_) => ProjectionKey::PushSettings,
            PrivacyLinks(_) => ProjectionKey::PrivacyLinks,
            Reports(_) => ProjectionKey::Reports,
            Appeals(_) => ProjectionKey::Appeals,
            AccountExports(_) => ProjectionKey::AccountExports,
        }
    }
}

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Debug, Default, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct StorageSnapshot {
    pub revision: u64,
    pub user_seq: Seq,
    pub messages: Vec<ChatMessage>,
    pub conversations: Vec<Conversation>,
    pub drafts: Vec<ConversationDraft>,
    pub inbox: Vec<InboxRecord>,
    pub outbox: Vec<OutboxRecord>,
    pub message_tombstones: Vec<MessageTombstone>,
    pub conversation_tombstones: Vec<ConversationTombstone>,
    pub display_deadlines: Vec<DisplayDeadline>,
    pub projections: Vec<DomainProjection>,
    pub invalidated: Vec<ProjectionKey>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub enum StorageMutation {
    PutMessage(ChatMessage),
    RemoveMessage { client_msg_id: String },
    PutConversation(Conversation),
    RemoveConversation { conv_id: String },
    PutDraft(ConversationDraft),
    PutInbox(InboxRecord),
    PutOutbox(OutboxRecord),
    RemoveOutbox { client_msg_id: String },
    PutMessageTombstone(MessageTombstone),
    PutConversationTombstone(ConversationTombstone),
    PutDisplayDeadline(DisplayDeadline),
    RemoveDisplayDeadline { msg_id: String },
    PutProjection(DomainProjection),
    InvalidateProjection(ProjectionKey),
    SetUserSeq(Seq),
    ReplaceState(Box<StorageSnapshot>),
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct StorageChangeSet {
    pub expected_revision: u64,
    pub mutations: Vec<StorageMutation>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct StorageCommit {
    pub revision: u64,
    pub user_seq: Seq,
}
