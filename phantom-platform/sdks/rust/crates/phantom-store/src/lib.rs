//! 实现公开 StoreFactory/Store 契约的内置适配。
mod memory;
pub use memory::*;
mod sql_records;
mod sqlite;
pub use sqlite::*;
mod callback;
pub use callback::*;
