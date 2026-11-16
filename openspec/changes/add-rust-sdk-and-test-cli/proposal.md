## Why

原生 SDK 架构和 Rust 公共接口已有文档，但仓库目前只有独立的 JS SDK，没有可复用的 Rust 核心及验证入口。实现 Rust SDK 和直接消费该 SDK 的 CLI，可以验证原生端的收发、同步、持久化及完整领域能力，并为后续平台包装提供统一基础。

## What Changes

- 在 `phantom-platform/sdks/rust/` 建立独立 Cargo workspace，按领域模型、Ports、Kernel、基础设施和 Facade 分层，实现 `integration.html#rust-core` 的完整 Rust v0.1 接口、模型、错误和事件契约。
- 实现认证与生命周期、Token 更新、重连与心跳、消息与媒体、会话、用户与好友、群、设备与推送、搜索及合规接口；使用现有 WebSocket/Protobuf 和 HTTP/JSON 契约。
- 提供公开存储接口和等价的存储回调适配器，由 SDK 使用者决定具体存储实现；未配置时默认注入实现同一存储契约的内置 SQLite，并支持显式 SQLite 目录和 Memory。默认 SQLite、自定义接口及回调适配均通过同一契约调用，消息与同步业务逻辑保持一致。收发消息、HTTP 历史分页结果、消息状态、删除/焚毁标记、Outbox、Inbox 与 user_seq 统一交由选定实现管理；所有实现遵守原子提交、提交后 ACK 和账号隔离，自定义存储失败不回退到其他后端。
- 提供基于公开 Rust Facade 的 `phantom-sdk-cli`：单次领域调用、持续事件监听、同进程多步骤场景和双账号冒烟测试；支持 JSON/NDJSON、稳定退出码、凭据外部注入和有界等待。
- 建立公共 API 覆盖清单、协议/HTTP 契约测试、可注入故障的核心测试，以及通过 CLI 运行的真实平台验收，补充 Rust SDK 与 CLI 使用文档。
- 本次交付 Rust crate 和测试 CLI；Android/Kotlin、iOS/Swift、Flutter/Dart、C ABI、WASM、现有 Tauri 客户端迁移、公共包注册表发布和移动端系统 Host 具体实现不纳入本次范围。

## Capabilities

### New Capabilities

- `rust-sdk`: 定义原生共享 Rust SDK 的完整公开 API、可注入存储接口/回调及默认 SQLite、状态一致性、持久化、传输、领域服务和事件契约。
- `rust-sdk-cli`: 定义直接通过 Rust SDK 执行调用、订阅和测试场景的命令行工具及其机器可读结果和退出行为。

### Modified Capabilities

无。现有 `web-sdk` 保留 JS 行为要求，平台服务端能力和协议保持兼容；Rust 的更强存储一致性、失效凭据处理和错误契约在新规格中明确，不将 JS 实现缺口沿用到 Rust。

## Impact

- 新增 `phantom-platform/sdks/rust/` 及其中的 SDK crates、CLI、测试和构建说明；独立于 Maven、npm 以及 `phantom-im/clients/pc/src-tauri/` 的现有 Cargo 工程。
- 复用 `phantom-platform/contracts/proto/phantom/v1/`、HTTP 路由、服务端 DTO 和错误码；不将 AppSecret 或 Token 签发能力加入 SDK/CLI。
- 新增 Rust 异步、HTTP/WebSocket、Protobuf、SQLite 和命令行解析依赖，具体版本在实施时通过构建兼容性验证后锁定。SQLite 是默认适配；自定义实现不需要依赖其 SQL 或数据表结构。
- 调整 Rust 草案的 ClientOptions 存储配置为可选，并增加公开存储接口与回调适配器；实施时同步接口文档和示例。这是按本次用户要求对原 Memory/Directory 必填配置的明确修订。
- 补充 `docs/phantom-platform/sdk/` 的 Rust 接入、能力状态、CLI 测试说明和架构实现状态；保留其中已有的用户修改。
- 真实服务测试需要现有 Phantom 单体和中间件，以及由接入方预置的测试账号、UserToken 和权限；测试结果须区分通过、失败和前置条件不足。
