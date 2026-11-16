use crate::*;

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct UserProfile {
    pub user_id: String,
    pub app_id: String,
    pub nickname: String,
    pub avatar_url: Option<String>,
    pub ext: StringMap,
    pub status: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ProfileUpdate {
    pub nickname: Option<String>,
    pub avatar_url: Option<String>,
    pub ext: StringMap,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct RelationUser {
    pub user_id: String,
    pub nickname: String,
    pub avatar_url: Option<String>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct Friend {
    pub user_id: String,
    pub nickname: String,
    pub avatar_url: Option<String>,
    pub display_name: String,
    pub remark: String,
    pub pinned: bool,
    pub group_ids: Vec<String>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct FriendPatch {
    pub remark: Option<String>,
    pub pinned: Option<bool>,
    pub group_ids: Option<Vec<String>>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct FriendRequestInput {
    pub to_user_id: String,
    pub verification_message: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum FriendRequestAction {
    Accept,
    Reject,
    Ignore,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct FriendRequestResult {
    pub request_id: String,
    pub status: String,
    pub auto_accepted: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct FriendRequest {
    pub request_id: String,
    pub from_user_id: String,
    pub to_user_id: String,
    pub peer: RelationUser,
    pub verification_message: String,
    pub status: String,
    pub direction: String,
    pub created_at: TimestampMs,
    pub handled_at: Option<TimestampMs>,
    pub unread: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct FriendRequestPage {
    pub items: Vec<FriendRequest>,
    pub unread_count: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct FriendGroup {
    pub group_id: String,
    pub name: String,
    pub member_count: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct BlacklistEntry {
    pub user_id: String,
    pub nickname: String,
    pub avatar_url: Option<String>,
    pub created_at: TimestampMs,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct QrCard {
    pub card_id: String,
    pub token: String,
    pub card_url: String,
    pub expires_at: Option<TimestampMs>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct UserCard {
    pub user_id: String,
    pub nickname: String,
    pub avatar_url: Option<String>,
    pub remark: String,
    pub friend: bool,
    pub blocked_by_me: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct QrCardScanResult {
    pub user: UserCard,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct RelationSettings {
    pub auto_accept_requests: bool,
    pub friend_limit: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct RelationSettingsPatch {
    pub auto_accept_requests: bool,
}
