use crate::*;

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ConversationQuery {
    pub cursor: Option<String>,
    pub limit: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ConversationPage {
    pub items: Vec<Conversation>,
    pub next_cursor: Option<String>,
    pub has_more: bool,
    pub max_user_seq: Seq,
    pub badge_count: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct Conversation {
    pub conv_id: String,
    pub conv_type: ConversationType,
    pub target_id: String,
    pub title: String,
    pub avatar_url: Option<String>,
    pub last_message_digest: String,
    pub last_message_at: TimestampMs,
    pub unread_count: u32,
    pub mention_me: bool,
    pub pinned: bool,
    pub pinned_at: TimestampMs,
    pub muted: bool,
    pub draft: String,
    pub draft_edited_at: TimestampMs,
    pub max_conv_seq: Seq,
    pub read_conv_seq: Seq,
    pub read_only: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ConversationPatch {
    pub pinned: Option<bool>,
    pub muted: Option<bool>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ReadPosition {
    pub conv_id: String,
    pub user_id: String,
    pub read_conv_seq: Seq,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct DraftInput {
    pub conv_id: String,
    pub draft: String,
    pub edited_at: Option<TimestampMs>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ConversationDraft {
    pub conv_id: String,
    pub draft: String,
    pub edited_at: TimestampMs,
}
