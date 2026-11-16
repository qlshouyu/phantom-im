use crate::*;

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum MediaType {
    Image,
    Voice,
    Video,
    File,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum MediaVariant {
    Original,
    Thumb,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum FileSource {
    Path(PathBuf),
    Bytes(Vec<u8>),
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct UploadMetadata {
    pub file_name: String,
    pub size_bytes: u64,
    pub sha256: String,
    pub mime_type: String,
    pub media_type: MediaType,
    pub duration_seconds: Option<u32>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub burn_after_read: bool,
    pub burn_after_read_ttl_seconds: Option<u32>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct UploadInput {
    pub operation_id: String,
    pub source: FileSource,
    pub metadata: UploadMetadata,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct UploadSession {
    pub upload_id: String,
    pub part_size: u32,
    pub size_bytes: u64,
    pub total_parts: u32,
    pub uploaded_parts: Vec<u32>,
    pub instant: bool,
    pub media_id: Option<String>,
    pub url: Option<String>,
    pub thumb_url: Option<String>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct UploadPartInput {
    pub upload_id: String,
    pub part_number: u32,
    pub bytes: Vec<u8>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct UploadPartResult {
    pub upload_id: String,
    pub part_number: u32,
    pub etag: String,
    pub size_bytes: u64,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct MediaInfo {
    pub media_id: String,
    pub media_type: MediaType,
    pub mime_type: String,
    pub size_bytes: u64,
    pub url: Option<String>,
    pub thumb_url: Option<String>,
    pub duration_seconds: Option<u32>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub file_name: Option<String>,
    pub status: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct UploadedMedia {
    pub session: UploadSession,
    pub media: MediaInfo,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct MediaContent {
    pub bytes: Vec<u8>,
    pub mime_type: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct DownloadInput {
    pub operation_id: String,
    pub media_id: String,
    pub variant: MediaVariant,
    pub destination: PathBuf,
    pub overwrite: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct DownloadedMedia {
    pub path: PathBuf,
    pub size_bytes: u64,
    pub mime_type: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct SendMediaInput {
    pub conv_id: String,
    pub conv_type: ConversationType,
    pub media_id: String,
    pub reply_to_msg_id: Option<String>,
    pub mentions: Vec<String>,
    pub client_msg_id: Option<String>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct TransferProgress {
    pub operation_id: String,
    pub upload_id: Option<String>,
    pub media_id: Option<String>,
    pub transferred_bytes: u64,
    pub total_bytes: u64,
    pub stage: TransferStage,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum TransferStage {
    Preparing,
    Transferring,
    Completing,
    Completed,
    Failed,
    Cancelled,
}
