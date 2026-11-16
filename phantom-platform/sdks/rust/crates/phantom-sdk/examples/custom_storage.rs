//! 编译：cargo check -p phantom-sdk --example custom_storage
//! 独立 JSON 字节宿主示例声明 Volatile；生产持久后端须自行实现事务和 flush。
#[path = "../../phantom-store/examples/support/host.rs"]
#[allow(dead_code)]
mod host;
use phantom_sdk::*;
use std::sync::Arc;
fn main() -> SdkResult<()> {
    let pool = host::HostFactory::default();
    let factory: Arc<dyn StoreFactory> = Arc::new(pool);
    let mut options = ClientOptions::new("https://phantom.example.test", "example-app");
    options.storage = Some(StorageOptions::Custom(factory));
    let client = PhantomClient::new(options)?;
    // new 只校验能力，登录后才打开 service_url/app_id/user_id 对应账号。
    assert_eq!(client.get_state()?, ConnectionState::Idle);
    // Drop 安排所属句柄清理；长期宿主应显式 await shutdown。
    drop(client);
    Ok(())
}
