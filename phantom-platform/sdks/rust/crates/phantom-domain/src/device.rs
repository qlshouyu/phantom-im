use crate::*;

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct DeviceInfo {
    pub device_id: String,
    pub device_type: DeviceType,
    pub platform: String,
    pub device_name: String,
    pub login_at: TimestampMs,
    pub last_active_at: TimestampMs,
    pub location: Option<String>,
    pub foreground: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PushTokenInput {
    pub token: String,
    pub device_type: DeviceType,
    pub provider: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct PushSettings {
    pub push_enabled: bool,
    pub preview_enabled: bool,
    pub privacy_policy_url: String,
    pub terms_url: String,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct PushSettingsInput {
    pub push_enabled: bool,
    pub preview_enabled: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct PrivacyLinksInput {
    pub privacy_policy_url: Option<String>,
    pub terms_url: Option<String>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
pub enum PresenceStatus {
    Online,
    Away,
    Offline,
    Unknown(i32),
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct Presence {
    pub user_id: String,
    pub status: PresenceStatus,
    pub last_active_time: Option<TimestampMs>,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct TypingEvent {
    pub conv_id: String,
    pub user_id: String,
    pub typing: bool,
}
#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, PartialEq, serde::Serialize, serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct NotificationClick {
    pub data: StringMap,
}
