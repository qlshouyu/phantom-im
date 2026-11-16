use crate::*;

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct Group {
    pub group_id: String,
    pub conv_id: String,
    pub name: String,
    pub avatar_url: Option<String>,
    pub member_count: u32,
    pub owner_id: String,
    pub status: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct GroupDetail {
    pub summary: Group,
    pub announcement: Option<Announcement>,
    pub member_invite_enabled: bool,
    pub announcement_unread: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct CreateGroupInput {
    pub name: String,
    pub member_ids: Vec<String>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct GroupPatch {
    pub name: Option<String>,
    pub avatar_url: Option<String>,
    pub member_invite_enabled: Option<bool>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum GroupRole {
    Owner,
    Admin,
    Member,
    Unknown(String),
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum AssignableGroupRole {
    Admin,
    Member,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct GroupMember {
    pub group_id: String,
    pub user_id: String,
    pub role: GroupRole,
    pub group_nickname: String,
    pub muted_until: Option<TimestampMs>,
    pub muted_all: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct TransferGroupInput {
    pub group_id: String,
    pub user_id: String,
    pub old_owner_role: AssignableGroupRole,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum MuteTarget {
    All,
    User(String),
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum MuteDuration {
    TenMinutes,
    OneHour,
    OneDay,
    Permanent,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct MuteInput {
    pub group_id: String,
    pub target: MuteTarget,
    pub duration: MuteDuration,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct Announcement {
    pub group_id: String,
    pub content: String,
    pub operator_id: String,
    pub version: i64,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct GroupInvite {
    pub invite_id: String,
    pub group_id: String,
    pub invite_type: String,
    pub token: String,
    pub expires_at: Option<TimestampMs>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct JoinGroupResult {
    pub group_id: String,
    pub request_id: Option<String>,
    pub status: String,
}
