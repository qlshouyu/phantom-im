//! 平台无关的公共模型与纯领域规则。
//!
//! 序号始终使用整数，未知枚举和缺失字段按接收契约保留。
use std::collections::BTreeMap;
use std::path::PathBuf;

pub type Seq = i64;
pub type TimestampMs = i64;
pub type StringMap = BTreeMap<String, String>;
pub type SdkResult<T> = Result<T, SdkError>;

mod session;
pub use session::*;
mod message;
pub use message::*;
mod media;
pub use media::*;
mod conversation;
pub use conversation::*;
mod relation;
pub use relation::*;
mod group;
pub use group::*;
mod device;
pub use device::*;
mod search;
pub use search::*;
mod event;
pub use event::*;
mod error;
pub use error::*;
mod validation;
pub use validation::*;
mod merge;
pub use merge::*;
mod storage;
pub use storage::*;
mod storage_changes;
