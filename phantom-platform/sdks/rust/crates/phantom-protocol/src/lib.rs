//! 共享 Protobuf 生成类型及边界转换；Facade 不导出生成类型。

pub mod wire {
    include!(concat!(env!("OUT_DIR"), "/phantom.v1.rs"));
}

mod frame;
mod message;
pub mod raw;
mod sync;

pub use frame::*;
pub use message::*;
pub use sync::*;
mod http;
pub use http::*;
mod history;
pub use history::*;

mod gateway;
pub use gateway::*;

mod api;
mod dto;
pub use api::*;
pub use dto::*;
