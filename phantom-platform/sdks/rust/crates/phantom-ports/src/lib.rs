//! 平台无关、可动态分派的异步能力。接口 Future 为 Send，不暴露 Tokio 或数据库类型。
use phantom_domain::SdkResult;
use std::{future::Future, pin::Pin};

pub type SdkFuture<'a, T> = Pin<Box<dyn Future<Output = SdkResult<T>> + Send + 'a>>;

mod host;
mod storage;
pub use host::*;
pub use storage::*;

mod session;
pub use session::*;

mod api;
pub use api::*;

mod api_validation;
