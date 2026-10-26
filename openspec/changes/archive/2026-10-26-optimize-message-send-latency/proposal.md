## Why

当前 1v1 发送链路在返回 `SendMsgResp` 前串行执行多次风险/关系查询、Redis 取号、RocketMQ 事务消息 half-send、MySQL 事务和事务 commit；Web IM 还会在调用发送 API 前等待草稿清理 HTTP 请求。实测常态延迟可接受，但存在 200–500ms 级长尾，且搜索全量重建、同步 WebSocket 写入和多消费组争用会放大 P95/P99。

本次变更旨在降低消息从用户点击发送到本地乐观回显、发送确认和接收端展示的延迟，同时通过应用级开关保留按需启用风险检查的能力。

## What Changes

- 新增应用级 `riskCheckEnabled` 配置，**默认关闭**。开关仅控制内容审核与用户安全校验；好友、黑名单、会话成员、群权限、只读状态、消息格式、媒体归属和发送幂等等基础校验始终执行。**BREAKING**：默认行为由“强制风险检查”改为“默认关闭、由应用显式开启”。
- 发送确认不再等待 RocketMQ 事务消息 half-send 和 commit；改为在消息 Timeline 落库的同一 MySQL 事务中写 Outbox，提交后立即返回发送确认，由异步 relay 至少一次发布新消息事件。
- 应用配置读取增加短 TTL 缓存，并在单次发送内复用，减少重复 MySQL 查询；关系与风险校验在不改变语义的前提下合并或减少查询次数。
- WebSocket 网关将完整发送事务移出网络 I/O 线程，使用有界、按连接串行的工作队列，避免单条慢消息阻塞同一连接的后续帧。
- fanout 保留用户 Timeline 写入与 `user_seq` 唯一分配语义，但在线推送改为有界异步投递，按用户串行、接收者之间可并行；单设备慢连接不得阻塞其他接收者，失败时依赖 Sync 补齐。
- 搜索索引改为事件定向增量更新；草稿、送达、已读、会话状态等与搜索内容无关的事件不得触发 `reindexUser()` 全量重建。
- Web IM 点击发送后立即调用 SDK 并展示乐观消息；草稿清理改为后台执行，不得位于发送请求之前。
- Web SDK 本地持久化改为合并/防抖写入，单条消息不得多次同步序列化全部会话缓存。
- 新增发送链路阶段指标与可重复基准测试，覆盖 P50/P95/P99、慢设备隔离、Outbox 重试和风险开关切换。
- 显式配置并监控数据库连接池与 MQ 消费并发，避免多个消费组和 WebSocket 线程争用默认连接池。

### Non-goals

- 不改变 `SendMsgReq`、`SendMsgResp`、`MsgPush` 或 `MsgAck` 的 Protobuf 字段与 wire contract。
- 不关闭、不降级好友关系、黑名单、会话权限、群权限、消息类型、媒体归属和幂等校验。
- 不改变 `conv_seq`/`user_seq` 的 Redis 分配及 MySQL 水位恢复语义。
- 不引入新的消息中间件，不迁移现有 Timeline 数据模型。
- 不以牺牲至少一次投递、离线 Sync 补齐或消费幂等为代价追求低延迟。
- 不把全量搜索重建从运维能力中删除，只将其从普通消息和无关同步事件热路径移除。

## Capabilities

### New Capabilities

- `message-path-observability`: 定义发送、Outbox 发布、fanout、在线投递和 ACK 各阶段的可观测性、基准方法与性能验收口径。

### Modified Capabilities

- `account-platform`: 应用级功能配置新增默认关闭的风险检查开关，并定义配置更新与生效语义。
- `security-compliance`: 风险检查变为应用可配置能力；关闭时仅跳过内容审核和用户安全校验。
- `single-chat-messaging`: 发送确认路径改为本地事务 Outbox，保持落库与投递的最终原子性及至少一次发布。
- `connection-gateway`: 发送指令使用有界串行工作队列执行，不阻塞 WebSocket I/O 线程。
- `message-fanout-delivery`: 在线推送异步化、按用户有序、慢设备隔离及有界背压。
- `global-search`: 同步事件按类型增量更新索引，禁止无关事件触发用户全量重建。
- `direct-chat`: 点击发送立即乐观展示，不等待草稿持久化接口。
- `web-sdk`: 本地消息缓存采用合并/防抖持久化，保持有序、去重和重启恢复语义。

## Impact

- 服务端：`phantom-message`、`phantom-gateway`、`phantom-fanout`、`phantom-search`、`phantom-openapi`、`phantom-risk`、`phantom-conversation` 及启动器配置。
- 数据：`app_config` 新增默认 false 的风险开关列；复用 `sync_outbox` 承载消息发布事件或新增等价消息 Outbox 表及索引。
- 外部 API：应用功能配置 JSON 新增 `risk_check_enabled`；现有 Protobuf 消息不新增或修改字段。
- 前端：`phantom-im-web-ui` 调整发送与草稿时序，`@phantom/sdk-web` 调整本地缓存持久化策略。
- 配置与运维：新增有界队列、WebSocket 发送超时、数据库连接池、搜索消费并发和 Outbox relay 参数；补充告警与基准报告。
- 安全：风险检查默认关闭是明确的行为变更；接入方必须显式开启才能获得内容审核、禁言和未成年人发送/接收限制。
- 测试：新增发送延迟基准、开关矩阵、Outbox 故障恢复、慢设备隔离、搜索增量索引和前端乐观回显测试。
