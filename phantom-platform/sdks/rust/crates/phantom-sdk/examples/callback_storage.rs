//! 回调与 trait 路径使用相同的原子 StorageChangeSet、命名空间和句柄契约。
#[path = "../../phantom-store/examples/support/host.rs"]
#[allow(dead_code)]
mod host;
use phantom_sdk::*;
use std::sync::Arc;
#[tokio::main]
async fn main() -> SdkResult<()> {
    let pool = host::HostFactory::default();
    let callbacks = host::callbacks(pool.clone());
    // new 检查所有必需回调及契约版本，缺失项立即 InvalidArgument。
    let adapter = CallbackStoreFactory::new(callbacks)?;
    let mut options = ClientOptions::new("https://phantom.example.test", "example-app");
    options.storage = Some(StorageOptions::Custom(Arc::new(adapter)));
    let client = PhantomClient::new(options)?;
    client.flush_storage(CallOptions::default()).await?;
    client.shutdown(CallOptions::default()).await?;
    // SDK 关闭自己打开的账号句柄，pool 仍属于宿主；不会整体关闭或转写 SQLite。
    drop(pool);
    Ok(())
}
