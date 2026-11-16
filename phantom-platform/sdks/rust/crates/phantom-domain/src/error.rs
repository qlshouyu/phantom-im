use crate::*;

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct SdkError {
    pub kind: ErrorKind,
    pub message: String,
    pub server_code: Option<i32>,
    pub http_status: Option<u16>,
    pub request_id: Option<String>,
    pub operation_id: Option<String>,
    pub client_msg_id: Option<String>,
    pub retryable: bool,
    pub outcome: OperationOutcome,
}

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum ErrorKind {
    InvalidArgument,
    NotLoggedIn,
    NotConnected,
    Authentication,
    Business,
    Network,
    Timeout,
    Cancelled,
    Storage,
    Protocol,
    Unsupported,
    NotFound,
    Conflict,
    Busy,
    Released,
    EventLagged,
}

#[cfg_attr(feature = "json-schema", derive(schemars::JsonSchema))]
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum OperationOutcome {
    NotSubmitted,
    Rejected,
    Unknown,
}

impl SdkError {
    pub fn new(kind: ErrorKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
            server_code: None,
            http_status: None,
            request_id: None,
            operation_id: None,
            client_msg_id: None,
            retryable: false,
            outcome: OperationOutcome::NotSubmitted,
        }
    }
}
impl std::fmt::Display for SdkError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}: {}", self.kind, self.message)
    }
}
impl std::error::Error for SdkError {}

impl std::fmt::Debug for LoginOptions {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LoginOptions")
            .field("user_token", &"[REDACTED]")
            .field("user_id", &self.user_id)
            .field("device_id", &self.device_id)
            .field("device_type", &self.device_type)
            .field("platform", &self.platform)
            .field("client_version", &self.client_version)
            .finish()
    }
}
impl std::fmt::Debug for TokenUpdate {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TokenUpdate")
            .field("user_token", &"[REDACTED]")
            .finish()
    }
}
impl std::fmt::Debug for PushTokenInput {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PushTokenInput")
            .field("token", &"[REDACTED]")
            .field("device_type", &self.device_type)
            .field("provider", &self.provider)
            .finish()
    }
}
