//! 契约测试复用独立宿主示例；示例不依赖内置存储实现。
#[path = "../../examples/support/host.rs"]
mod implementation;
pub use implementation::*;
