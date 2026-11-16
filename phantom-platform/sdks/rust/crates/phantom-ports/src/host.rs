use crate::SdkFuture;
use phantom_domain::*;
use std::{collections::BTreeMap, path::PathBuf, sync::Arc, time::Duration};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HttpMethod {
    Get,
    Post,
    Put,
    Patch,
    Delete,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum HttpBody {
    Empty,
    Json(Vec<u8>),
    Bytes(Vec<u8>),
}
#[derive(Clone, PartialEq, Eq)]
pub struct HttpIdentity {
    pub user_token: String,
    pub app_id: String,
    pub user_id: String,
    pub device_id: String,
}
impl std::fmt::Debug for HttpIdentity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HttpIdentity")
            .field("user_token", &"[REDACTED]")
            .field("app_id", &self.app_id)
            .field("user_id", &self.user_id)
            .field("device_id", &self.device_id)
            .finish()
    }
}
#[derive(Clone, Debug)]
pub struct HttpRequest {
    /// 静态路由和动态标识都以未编码的单段传入，由默认适配逐段编码。
    pub path: Vec<String>,
    pub query: Vec<(String, String)>,
    pub method: HttpMethod,
    pub body: HttpBody,
    pub identity: Option<HttpIdentity>,
    pub request_id: Option<String>,
}
#[derive(Clone, Debug)]
pub struct HttpResponse {
    pub status: u16,
    pub headers: BTreeMap<String, String>,
    pub body: Vec<u8>,
}
pub trait Http: Send + Sync {
    fn execute(&self, request: HttpRequest) -> SdkFuture<'_, HttpResponse>;
}

pub trait WebSocket: Send + Sync {
    fn send(&self, frame: Vec<u8>) -> SdkFuture<'_, ()>;
    fn receive(&self) -> SdkFuture<'_, Option<Vec<u8>>>;
    fn close(&self) -> SdkFuture<'_, ()>;
}
pub trait WebSocketConnector: Send + Sync {
    fn connect(&self) -> SdkFuture<'_, Arc<dyn WebSocket>>;
}

pub trait Clock: Send + Sync {
    fn now_ms(&self) -> TimestampMs;
    fn monotonic_ms(&self) -> u64;
    fn sleep(&self, duration: Duration) -> SdkFuture<'_, ()>;
}
pub trait Task: Send + Sync {
    fn cancel(&self);
    fn join(&self) -> SdkFuture<'_, ()>;
}
pub trait Scheduler: Send + Sync {
    fn spawn(&self, work: SdkFuture<'static, ()>) -> Arc<dyn Task>;
}

pub trait FileReader: Send + Sync {
    fn size(&self) -> u64;
    fn read_range(&self, offset: u64, length: u32) -> SdkFuture<'_, Vec<u8>>;
}
pub trait DownloadTarget: Send + Sync {
    fn write(&self, bytes: Vec<u8>) -> SdkFuture<'_, ()>;
    fn commit(&self) -> SdkFuture<'_, PathBuf>;
    fn abort(&self) -> SdkFuture<'_, ()>;
}
pub trait Files: Send + Sync {
    fn open(&self, source: FileSource) -> SdkFuture<'_, Arc<dyn FileReader>>;
    /// 临时文件在目标同目录；overwrite=false 的提交必须原子地拒绝覆盖。
    fn download_target(
        &self,
        path: PathBuf,
        overwrite: bool,
    ) -> SdkFuture<'_, Arc<dyn DownloadTarget>>;
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DiagnosticLevel {
    Debug,
    Info,
    Warning,
    Error,
}
/// 不携带 Token/报文体的结构化诊断；宿主不需要解析日志字符串。
#[derive(Clone, Debug)]
pub struct Diagnostic {
    pub level: DiagnosticLevel,
    pub operation: String,
    pub request_id: Option<String>,
    pub operation_id: Option<String>,
    pub client_msg_id: Option<String>,
    pub kind: Option<ErrorKind>,
}
pub trait Diagnostics: Send + Sync {
    fn record(&self, diagnostic: Diagnostic);
}

pub trait Ids: Send + Sync {
    fn generate(&self) -> SdkResult<String>;
}

pub trait Hashing: Send + Sync {
    fn sha256(&self, reader: Arc<dyn FileReader>) -> SdkFuture<'_, String>;
}
pub trait MediaStream: Send + Sync {
    fn next_chunk(&self) -> SdkFuture<'_, Option<Vec<u8>>>;
}
pub struct MediaDownload {
    pub mime_type: String,
    pub content_length: Option<u64>,
    pub stream: Arc<dyn MediaStream>,
}
pub trait MediaAccess: Send + Sync {
    fn open(&self, url: String, identity: HttpIdentity) -> SdkFuture<'_, MediaDownload>;
}
