use crate::*;

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum DeviceType {
    Mobile,
    Desktop,
    Web,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum ConnectionState {
    Idle,
    Connecting,
    Authenticating,
    Online,
    Reconnecting,
    Closed,
    Released,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LoginOptions {
    pub user_token: String,
    pub user_id: String,
    pub device_id: String,
    pub device_type: DeviceType,
    pub platform: String,
    pub client_version: Option<String>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct SessionInfo {
    pub session_id: String,
    pub user_id: String,
    pub device_id: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct LoginResult {
    pub session: SessionInfo,
    pub server_time: TimestampMs,
    pub max_user_seq: Seq,
    pub heartbeat_interval_s: u32,
    pub app_config: AppConfig,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct AppConfig {
    pub burn_after_read: BurnAfterReadConfig,
    pub read_receipt_enabled: bool,
    pub relation_hosted: bool,
    pub sdk_profile_write_enabled: bool,
    pub max_devices: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct BurnAfterReadConfig {
    pub enabled: bool,
    pub ttl_seconds: u32,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct SyncStatus {
    pub phase: SyncPhase,
    pub applied_user_seq: Seq,
    pub target_user_seq: Seq,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum SyncPhase {
    NotStarted,
    Syncing,
    Rebuilding,
    Ready,
    Failed,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct LogoutOptions {
    pub clear_cache: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TokenUpdate {
    pub user_token: String,
}
