use crate::*;

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct SearchQuery {
    pub query: String,
    pub limit: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ConversationSearchQuery {
    pub conv_id: String,
    pub query: String,
    pub limit: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ContactHit {
    pub user_id: String,
    pub nickname: String,
    pub remark: String,
    pub avatar_url: Option<String>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct GroupHit {
    pub group_id: String,
    pub conv_id: String,
    pub name: String,
    pub avatar_url: Option<String>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct MessageHit {
    pub msg_id: String,
    pub conv_id: String,
    pub conv_title: String,
    pub sender_id: String,
    pub digest: String,
    pub conv_seq: Seq,
    pub server_time: TimestampMs,
    pub recalled: bool,
    pub burned: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct SearchResult {
    pub contacts: Vec<ContactHit>,
    pub groups: Vec<GroupHit>,
    pub messages: Vec<MessageHit>,
    pub contact_total: u32,
    pub group_total: u32,
    pub message_total: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct SearchSuggestions {
    pub contacts: Vec<ContactHit>,
    pub groups: Vec<GroupHit>,
    pub total: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ConversationSearch {
    pub conv_id: String,
    pub messages: Vec<MessageHit>,
    pub total: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum ReportTarget {
    Message,
    User,
    Group,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ReportInput {
    pub target_type: ReportTarget,
    pub target_id: String,
    pub reason: String,
    pub description: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct Report {
    pub report_id: String,
    pub app_id: String,
    pub reporter_id: String,
    pub target_type: String,
    pub target_id: String,
    pub reason: String,
    pub description: String,
    pub snapshot_json: String,
    pub status: String,
    pub created_at: TimestampMs,
    pub updated_at: TimestampMs,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct AppealInput {
    pub sanction_id: String,
    pub content: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct Appeal {
    pub appeal_id: String,
    pub sanction_id: String,
    pub user_id: String,
    pub content: String,
    pub status: String,
    pub result: Option<String>,
    pub created_at: TimestampMs,
    pub updated_at: TimestampMs,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct AccountExport {
    pub export_id: String,
    pub app_id: String,
    pub user_id: String,
    pub status: String,
    pub payload_json: String,
    pub created_at: TimestampMs,
    pub updated_at: TimestampMs,
}
