## Why

M0 仅验证了单聊文本、在线投递、断线同步和 JS Web SDK 主链路，PRD 中 MVP 阶段的 175 条 P0/P1 需求尚未实现。现在需要基于已固定的 WSS + Protobuf、HTTP + JSON、RocketMQ 事务消息和双 Timeline 架构，一次性规划完整 MVP，为后续按任务清单实施提供可验证的规格与交付边界。

## What Changes

- 基于 PaaS 定位补齐应用、Phantom ID 用户、OpenAPI、资料和应用级功能配置。
- 实现关系链、会话状态、富消息、媒体上传、单聊交互、群聊和多端同步。
- 实现离线推送、全局搜索、内容风控、举报处置、限流、消息请求和合规支撑。
- 扩展现有网关、单聊收单、扇出投递、增量同步和 JS Web SDK 契约。
- 扩展 Web IM Demo，并新增最小应用管理控制台，用于应用、密钥和功能配置管理。
- 按 PaaS 口径适配冲突需求：手机号发现、扫码授权、隐私政策和注销冷静期由接入方负责；平台提供 Phantom ID、UserToken、数据导出和用户删除/匿名化支撑。
- **BREAKING**：单聊文本收单扩展为通用消息收单，消息状态、撤回、同步事件和 SDK API 同步扩展；现有 M0 调用方需按新契约升级。

本次仅纳入 P0 与 P1，明确排除 P2：AC-11、NT-14、SR-15、SR-16。

## Capabilities

### New Capabilities

- `account-platform`: 应用、AppSecret、OpenAPI 鉴权、Phantom ID 用户、资料、状态和应用配置。
- `relationship-management`: 好友申请、好友列表、备注、分组、删除、黑名单和二维码名片。
- `conversation-management`: 会话生成、列表、未读、置顶、免打扰、删除、草稿和会话状态同步。
- `rich-messaging`: 通用消息类型、状态机、撤回、转发、引用、受限投递、阅后即焚和 ACK 重推。
- `media-management`: 分片上传、断点续传、秒传、缩略图、媒体元数据、访问控制和生命周期。
- `direct-chat`: 单聊入口、标题与在线状态、消息操作、输入状态、本地清除和非好友限制。
- `group-chat`: 建群、成员、角色、邀请、禁言、@、公告、群名片、群资料和群状态事件。
- `multi-device-session`: 多端在线、历史漫游、已读同步、设备管理和 PaaS 扫码授权适配。
- `offline-notification`: 推送 Token、离线决策、隐私预览、免打扰/@穿透、角标、深链和多端抑制。
- `global-search`: 联系人、群组和聊天记录搜索、结果定位、会话内搜索和索引同步。
- `security-compliance`: 内容审核、限流、消息请求、举报、账号处置、申诉、数据导出和未成年人模式。

### Modified Capabilities

- `connection-gateway`: 增加应用配置下发、设备数量限制、AppState、Presence/Typing 路由和限流接入点。
- `single-chat-messaging`: 从单聊文本扩展为通用消息收单，并增加权限、会话状态和消息类型校验。
- `message-fanout-delivery`: 扩展群聊写扩散、全部同步事件、受限投递和 5 秒未 ACK 重推。
- `sync-catchup`: 扩展为消息、关系、群、资料、已读和会话状态的统一增量同步。
- `web-sdk`: 扩展为完整 MVP 客户端契约，覆盖账号、关系、会话、消息、媒体、群、设备和通知设置。

## Impact

- 服务端：新增 `user`、`openapi`、`relation`、`conversation`、`group`、`media`、`search`、`risk` 服务及对应 service-api；扩展 gateway/message/fanout/push/sync。
- 数据：新增应用、用户、关系、群组、会话状态、媒体、搜索索引、举报与处置等表，并执行兼容迁移。
- 协议：扩展 `proto/phantom/v1/*.proto` 与 HTTP OpenAPI；保持字段号只增不删。
- 客户端：扩展 JS Web SDK 和 Web IM Demo，并新增最小应用管理控制台。
- 基础设施：新增媒体对象存储、推送通道、搜索索引和内容审核适配层；本地默认实现需可在 Docker Compose 中运行。
- 测试：增加 Java 单元/集成测试、SDK 单测、真实链路冒烟、Playwright E2E、弱网与容量测试。
