use crate::*;

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum ConversationType {
    Single,
    Group,
    Unknown(i32),
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum MessageKind {
    Text,
    Emoji,
    Image,
    Voice,
    Video,
    File,
    Location,
    Card,
    Merged,
    System,
    Unknown(i32),
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum MessageState {
    Sending,
    Sent,
    Delivered,
    Read,
    Failed,
    Recalled,
    Burned,
    Unknown(i32),
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum MessageContent {
    Text(TextContent),
    Emoji(EmojiContent),
    Image(ImageContent),
    Voice(VoiceContent),
    Video(VideoContent),
    File(FileContent),
    Location(LocationContent),
    Card(CardContent),
    Merged(MergedContent),
    System(SystemContent),
    Unknown { kind: i32, raw_json: String },
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct TextContent {
    pub text: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct EmojiContent {
    pub emoji_id: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ImageContent {
    pub media_id: String,
    pub url: Option<String>,
    pub thumb_url: Option<String>,
    pub width: u32,
    pub height: u32,
    pub size_bytes: u64,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct VoiceContent {
    pub media_id: String,
    pub url: Option<String>,
    pub duration_s: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct VideoContent {
    pub media_id: String,
    pub url: Option<String>,
    pub cover_url: Option<String>,
    pub duration_s: u32,
    pub size_bytes: u64,
    pub width: u32,
    pub height: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct FileContent {
    pub media_id: String,
    pub url: Option<String>,
    pub file_name: String,
    pub size_bytes: u64,
    pub ext: Option<String>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct LocationContent {
    pub latitude: f64,
    pub longitude: f64,
    pub poi_name: String,
    pub address: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct CardContent {
    pub card_type: ConversationType,
    pub target_id: String,
    pub snapshot_name: String,
    pub snapshot_avatar: Option<String>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct MergedContent {
    pub title: String,
    pub messages: Vec<ChatMessage>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct SystemContent {
    pub template_id: String,
    pub params: StringMap,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct SendMessageInput {
    pub conv_id: String,
    pub conv_type: ConversationType,
    pub content: MessageContent,
    pub reply_to_msg_id: Option<String>,
    pub mentions: Vec<String>,
    pub client_msg_id: Option<String>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ChatMessage {
    pub msg_id: Option<String>,
    pub client_msg_id: String,
    pub conv_id: String,
    pub conv_type: ConversationType,
    pub sender_id: String,
    pub kind: MessageKind,
    pub content: Option<MessageContent>,
    pub text: String,
    pub conv_seq: Option<Seq>,
    pub server_time: Option<TimestampMs>,
    pub state: MessageState,
    pub recalled: bool,
    pub restricted: bool,
    pub is_request: bool,
    pub reply_to_msg_id: Option<String>,
    pub reply_snapshot: Option<ReplySnapshot>,
    pub mentions: Option<Vec<String>>,
    pub burn_after_read_ttl_seconds: u32,
    pub error: Option<SdkError>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ReplySnapshot {
    pub sender_id: String,
    pub sender_name: String,
    pub msg_type: Option<MessageKind>,
    pub digest: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct HistoryQuery {
    pub conv_id: String,
    pub before_conv_seq: Option<Seq>,
    pub limit: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct MessagePage {
    pub items: Vec<ChatMessage>,
    pub next_cursor: Option<Seq>,
    pub has_more: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct ForwardInput {
    pub msg_ids: Vec<String>,
    pub target_conv_ids: Vec<String>,
    pub merged: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct MessageAction {
    pub msg_id: Option<String>,
    pub target_msg_ids: Vec<String>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct RecallResult {
    pub msg_id: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct MessageCacheQuery {
    pub conv_id: String,
    pub before_conv_seq: Option<Seq>,
    pub limit: u32,
}
