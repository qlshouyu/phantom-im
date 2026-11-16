//! 默认网络适配与连接请求管理。
mod pending;
mod url;
pub use pending::*;
pub use url::*;
mod http;
pub use http::*;

mod websocket;
pub use websocket::*;

mod gateway;
pub use gateway::*;

mod api;
pub use api::*;
