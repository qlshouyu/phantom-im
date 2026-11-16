//! 运行前由接入方提供既有账号和环境变量；本示例不会签发 UserToken。
use phantom_sdk::*;
fn required(name: &str) -> SdkResult<String> {
    std::env::var(name)
        .map_err(|_| SdkError::new(ErrorKind::InvalidArgument, format!("缺少环境变量 {name}")))
}
#[tokio::main]
async fn main() -> SdkResult<()> {
    let client = PhantomClient::new(ClientOptions::new(
        required("PHANTOM_BASE_URL")?,
        required("PHANTOM_APP_ID")?,
    ))?;
    let events = client.subscribe(SubscriptionOptions { capacity: 256 })?;
    let result = async {
        let login = client
            .login(
                LoginOptions {
                    user_token: required("PHANTOM_USER_TOKEN")?,
                    user_id: required("PHANTOM_USER_ID")?,
                    device_id: required("PHANTOM_DEVICE_ID")?,
                    device_type: DeviceType::Desktop,
                    platform: "rust-example".into(),
                    client_version: None,
                },
                CallOptions::default(),
            )
            .await?;
        client.wait_ready(CallOptions::default()).await?;
        let conv_id = single_conv_id(login.session.user_id, required("PHANTOM_PEER_ID")?)?;
        let message = client
            .send_text(conv_id, "Rust SDK 接入示例".into(), CallOptions::default())
            .await?;
        println!("已发送逻辑消息 {}", message.client_msg_id);
        client
            .logout(LogoutOptions { clear_cache: false }, CallOptions::default())
            .await
    }
    .await;
    events.close();
    let cleanup = client.shutdown(CallOptions::default()).await;
    result.and(cleanup)
}
