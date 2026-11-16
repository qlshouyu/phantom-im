# Phantom Rust SDK 与测试 CLI

本目录是独立 Cargo workspace，提供 Rust Facade、Kernel、默认适配和直接调用 SDK 的 `phantom-sdk-cli`。公开方法以 [Rust 接口文档](../../../docs/phantom-platform/sdk/integration.html#rust-core) 为基线，共 103 项，另有事件订阅和取消句柄。实现与验证索引见 [API 覆盖清单](api-coverage.md) 和 [验证记录](verification.md)。目前提供仓库源码接入，未发布公共包或其他语言绑定。

| crate | 职责 | 项目内依赖 |
| --- | --- | --- |
| phantom-domain | DTO、错误、校验和纯状态合并 | 无 |
| phantom-ports | 存储、HTTP/WS、时钟、调度、文件等公开契约 | domain |
| phantom-core | 账号状态、同步、收发和媒体用例 | domain、ports |
| phantom-protocol | 共享 protobuf 和 HTTP DTO 映射 | domain、ports |
| phantom-transport | HTTP、WebSocket、流式媒体 | domain、ports、protocol |
| phantom-store | SQLite、Memory、存储回调适配 | domain、ports |
| phantom-runtime | Tokio、时钟、文件、摘要与 ID | domain、ports |
| phantom-sdk | 公开导出、组合根、实例队列和调用上下文 | 各 SDK crate |
| phantom-sdk-cli | schema、call、listen、scenario、smoke | 仅 sdk |

## 构建与验证

工具链固定为 Rust/Cargo 1.99.1，edition 2024；protobuf 构建需要 `protoc`，本机验证版本为 36.2。协议构建直接读取 `../../contracts/proto/phantom/v1/`，无需 Maven、npm、Tauri 或运行中的平台。`Cargo.lock` 已生成并纳入交付；首次下载依赖需要可用的 Cargo 源。

在本目录执行：

```bash
cargo check --workspace --all-targets --locked
cargo test --workspace --all-targets --locked
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo build --workspace --locked
cargo run -p phantom-sdk-cli -- --help
cargo run -p phantom-sdk-cli -- schema send_message
```

测试包含本地 TCP/WebSocket 服务和默认应用数据目录中的唯一测试账号；受限沙箱需要相应权限。无需真实 UserToken 的测试不会接入真实平台。本机验证范围为 macOS arm64，其他目标平台尚未验收。

## Rust 接入

在宿主 Cargo 工程中以 `path` 依赖本仓库的 `crates/phantom-sdk`，异步宿主使用 Tokio。可编译示例：[完整接入流程](crates/phantom-sdk/examples/basic.rs)、[自定义 trait 存储](crates/phantom-sdk/examples/custom_storage.rs)、[回调存储](crates/phantom-sdk/examples/callback_storage.rs)。

```rust
use phantom_sdk::*;

async fn connect(base_url: &str, app_id: &str, login: LoginOptions) -> SdkResult<PhantomClient> {
    let client = PhantomClient::new(ClientOptions::new(base_url, app_id))?;
    let events = client.subscribe(SubscriptionOptions { capacity: 256 })?;
    let result = async {
        client.login(login, CallOptions::default()).await?;
        client.wait_ready(CallOptions::default()).await?;
        Ok(())
    }.await;
    events.close();
    if let Err(error) = result {
        let _ = client.shutdown(CallOptions::default()).await;
        return Err(error);
    }
    Ok(client)
}
```

实际应用应保留并消费订阅。`login` 在认证后返回，`wait_ready` 等待同步完成。事件携带单调 `event_id` 和会话信息；不同订阅独立，溢出订阅以 `EventLagged` 终止，应用需要重新读取快照。Token 过期后必须提供新的同身份 UserToken，SDK 不会签发 Token，也不会用旧 Token 无限重试。退出使用 `logout`；宿主关闭时显式 `shutdown`。Drop 安排清理，不能替代等待持久化屏障。

运行完整示例前提供接入方已签发的 `PHANTOM_USER_TOKEN`，以及 `PHANTOM_BASE_URL`、`PHANTOM_APP_ID`、`PHANTOM_USER_ID`、`PHANTOM_DEVICE_ID`、`PHANTOM_PEER_ID`，然后执行 `cargo run -p phantom-sdk --example basic`。

## 存储选择和责任

`ClientOptions::new(base_url, app_id)` 将 `storage` 留空，默认使用 SQLite，登录时才打开账号目录。默认根目录由 `directories::BaseDirs::data_local_dir()` 解析，再追加 `phantom/sdk`：macOS 通常是 `~/Library/Application Support/phantom/sdk`；Linux 为 `$XDG_DATA_HOME/phantom/sdk` 或 `~/.local/share/phantom/sdk`；Windows 为 `%LOCALAPPDATA%\phantom\sdk`。每个规范化服务地址、app_id、user_id 组合形成独立 SHA-256 目录，库文件为 `account.sqlite3`。无法解析或写入时返回 Storage，不回退到 Memory 或临时目录。

```rust
use phantom_sdk::*;
use std::{path::PathBuf, sync::Arc};
let mut options = ClientOptions::new("https://phantom.example.test/prefix", "app");
options.storage = Some(StorageOptions::Directory(PathBuf::from("./my-sdk-cache")));
// 或 Some(StorageOptions::Memory)，仅在当前 factory 的生命周期保留状态。
// 或 Some(StorageOptions::Custom(Arc::new(MyStoreFactory)))，由宿主实现公开 trait。
```

实际 trait/回调完整实现见 [独立宿主后端](crates/phantom-store/examples/support/host.rs)。它使用宿主共享池保存序列化记录，声明 `Volatile`；它用于演示接口和资源所有权，不能据此声称跨进程持久化。生产后端需提供 `StoreFactory::capabilities/open` 和 `Store::namespace/capabilities/load/query_messages/get_conversation/get_projection/apply/flush/clear/close`，自行管理物理 schema 和迁移。

回调路径将同一宿主实现映射为 `StorageCallbacks`，经 `CallbackStoreFactory::new(callbacks)` 校验全部必需回调，再使用 `StorageOptions::Custom(Arc::new(adapter))`。回调具有账号句柄和关联上下文，Future 必须 Send；缺失回调立即报 InvalidArgument。

所有后端都必须提供账号写入租约、revision 校验和原子变更。Inbox、Outbox、投影与 user_seq 同批提交，提交后才能 ACK 或发布事件。`flush` 返回真实持久化屏障错误；`clear` 仅清当前账号；`close` 幂等释放所属句柄，不能关闭宿主共享连接池。自定义失败不触发 SQLite 双写或后端切换。恢复保留 client_msg_id、草稿、游标及终态标记，未完成发送恢复为 Failed/Unknown，交由应用决定重发。撤回/焚毁清除正文和派生引用；展示 TTL 在运行期使用单调时钟，重启按持久化绝对截止时间恢复。

## CLI

CLI 参数说明可直接使用 `--help`；机器调用默认 JSON，`listen` 为 NDJSON。输出版本为 `schema_version=1`。所有 i64/u64 在 CLI 边界使用规范十进制字符串，字节使用 base64；SDK 的 Rust 类型仍是整数和 `Vec<u8>`。

```bash
cargo run -p phantom-sdk-cli -- schema
cargo run -p phantom-sdk-cli -- schema mark_read
printf '%s' '{"a":"alice","b":"bob"}' | cargo run -p phantom-sdk-cli -- call single_conv_id
printf '%s' '{"conv_id":"si_alice__bob","read_conv_seq":"9007199254740993"}' | \
  cargo run -p phantom-sdk-cli -- \
    --base-url https://phantom.example.test --app-id app --user-id alice \
    --device-id rust-cli-alice --token-env PHANTOM_USER_TOKEN call mark_read --dry-run
cargo run -p phantom-sdk-cli -- scenario scenarios/relations.json --dry-run
```

`call METHOD --input FILE` 从文件读取，`--input -` 或省略时从 stdin 读取。`--token-env NAME` 和 `--token-file PATH` 是凭据引用，不接受字面 UserToken 参数。dry-run 只校验引用形式，不读取凭据文件、媒体、缓存，不创建 SDK 或联网。真实 call 会按方法需求登录并等待 Ready；需要精确控制生命周期时使用 scenario。

存储留空使用 SDK 默认 SQLite；`--storage memory` 不建库；`--storage-dir PATH` 选择显式目录，不能与 Memory 同用。CLI 不加载自定义存储代码；宿主扩展使用上面的 Rust 示例。

```bash
cargo run -p phantom-sdk-cli -- \
  --base-url http://127.0.0.1:28080 --app-id app --user-id alice \
  --device-id rust-listener --token-env PHANTOM_USER_TOKEN \
  listen --duration-ms 10000
# 也支持 --max-events N 或显式 --follow；Ctrl-C 传播取消，清理上限 5 秒。
```

`call/scenario` 默认整体期限 60 秒，smoke 默认 120 秒；使用 `--timeout-ms` 覆盖。listen 显式持续时间决定期限，follow 持续监听并响应中断。退出码：0 成功；1 其他业务失败；2 输入；3 认证/权限；4 网络/协议/超时；5 断言；6 存储/内部；130 中断。JSON 错误保留 kind、server_code、http_status、outcome、retryable 和关联标识。

scenario 支持 `client/new`、`subscribe`、`call`、`wait_event`、`assert`、`cancel`、`close_subscription`、`shutdown`。引用格式为 `{"$ref":"step_id#/data/msg_id"}`，只可引用已完成前序步骤；`background:true` 必须命名 cancellation，并在后续 cancel 步骤等待结果。用户文本作为 JSON 数据处理，不执行 shell。每步报告 passed/failed/skipped；必需步骤失败后保留根因并跳过后续步骤。

六个 [领域场景](scenarios/README.md) 可在替换有效 ID/环境后执行。smoke 配置是含 `a`、`b` 的账号配置对象，二者同平台同应用、不同用户和设备，Token 仍使用外部引用：

```json
{
  "a":{"base_url":"http://127.0.0.1:28080","app_id":"app","user_id":"alice","device_id":"rust-smoke-a","token_env":"PHANTOM_TOKEN_A"},
  "b":{"base_url":"http://127.0.0.1:28080","app_id":"app","user_id":"bob","device_id":"rust-smoke-b","token_env":"PHANTOM_TOKEN_B"}
}
```

保存为本机私有配置后运行 `cargo run -p phantom-sdk-cli -- smoke --config /private/path/smoke.json`。smoke 始终使用本次专属临时 SQLite 目录，验证 Ready、收发、成功身份重复提交幂等、历史、离线同步和重建实例后的缓存恢复，关闭后清理自己的临时目录，保留用户目录。Failed/resend 与 full_resync 分支见受控测试；成功重复发送不算 Failed 重发证据。真实平台不可用或无有效预置账号时，报告失败或 skipped，不计为通过。
