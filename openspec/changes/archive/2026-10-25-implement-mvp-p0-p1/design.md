## Context

见 `proposal.md - Why`。当前代码只有 M0 行走骨架：六个主规格、五个业务服务、单体 launcher、JS Web SDK 和 Web Demo。M0 已固定的关键约束必须保留：

- WSS + Protobuf 承担实时指令、推送和增量同步；HTTP + JSON 承担 CRUD、漫游、搜索、媒体和设备管理。
- 会话 Timeline 保存完整消息，用户同步 Timeline 保存轻量事件；`conv_seq` 保证会话内顺序，`user_seq` 保证用户事件顺序。
- `message` 同步收单并落会话 Timeline，`fanout` 是 `user_seq` 唯一写入方，`push` 负责最后一公里。
- 应用按 module 隔离，通过 service-api 支持同 JVM 直调和未来 gRPC 拆分；本轮仍只部署单体。
- JS Web SDK 是正式客户端契约，iOS、Android 和桌面原生 SDK 不纳入本轮实现。

## Goals / Non-Goals

**Goals:**

- 在一个可审查的 program-level change 中定义完整 P0/P1 行为、技术落位、依赖顺序和验收任务。
- 保证 M0 已有消息可靠性、顺序和多端同步语义在扩展后不回退。
- 将新增能力拆到独立 module 和独立数据表中，保持未来按域拆分能力。
- 提供本地可运行的媒体、搜索、推送和内容审核适配实现，同时保留生产替换接口。
- 明确所有 P0/P1 需求在服务端、SDK、Web Demo、管理控制台的交付位置。

**Non-Goals:**

- 不实现 AC-11、NT-14、SR-15、SR-16 等 P2 能力。
- 不实现 iOS、Android、桌面原生 SDK。
- 不实现服务真正拆分为独立进程、Nacos 服务发现或 gRPC stub。
- 不引入 Elasticsearch/OpenSearch、对象存储或真实 APNs/FCM 凭据作为本地启动硬依赖。
- 不实现 E2EE、音视频、朋友圈等路线图能力。

## Decisions

### D1. 保持单体部署，按领域新增 module

新增业务 module：

```text
phantom-services/
├── phantom-user/          # Phantom ID、资料、用户状态
├── phantom-openapi/       # 应用、密钥、应用配置、server-to-server API
├── phantom-relation/      # 好友、分组、黑名单、二维码名片
├── phantom-conversation/  # 会话快照、未读、置顶、免打扰、草稿
├── phantom-group/         # 群成员、角色、禁言、公告、邀请
├── phantom-media/         # 上传、对象元数据、访问与清理
├── phantom-search/        # 索引与查询
└── phantom-risk/          # 审核、举报、处置、申诉、审计
```

现有 `gateway/message/fanout/push/sync` 原地扩展，不重写 M0 主链路。新增 `phantom-service-api/{user,config,conversation,relation,group,device}` 接口模块，调用方只依赖接口。

理由：符合既定“可分可合”结构，避免单体 module 继续膨胀。备选方案是全部塞入现有五个 module，但会破坏领域边界并增加后续拆分成本。

### D2. WS 与 HTTP 的职责边界保持不变

- WS：认证、心跳、消息发送/撤回、ACK、实时信令、`SyncReq/SyncResp` 和各类 Push。
- HTTP：应用与用户管理、关系、会话快照/状态、群管理、漫游、媒体上传、搜索、设备、推送设置、举报和合规接口。
- OpenAPI：接入方 server-to-server 应用、用户、状态、资料、数据导出和管理接口。
- SDK：对宿主隐藏 WS/HTTP 差异，统一暴露领域 API、事件和本地状态。

理由：低频请求不占长连接，高实时动作保持单一长连接入口。备选方案是所有业务都走 WS，但会降低可调试性并扩大网关职责。

### D3. PaaS 责任边界按账号 v0.2 与协议设计执行

- 手机号、隐私设置、扫码授权、注销冷静期和 C 端隐私页面由接入方负责。
- 平台只接受接入方解析后的 Phantom ID 和签发的 UserToken。
- 平台不提供扫码登录端点；接入方完成扫码授权并签发 UserToken 后，桌面端直接调用 SDK 登录。
- 平台提供 Phantom ID/资料、关系、群和会话元数据导出，以及用户删除/匿名化 OpenAPI。
- 隐私政策与用户协议链接由接入方配置，SDK 和 Web Demo 负责展示。

关系链托管关闭时，平台不维护好友申请与好友列表，也不把好友关系作为单聊前置条件；黑名单、应用状态、消息请求和安全限制仍生效。关系链开启时执行完整好友和非好友消息请求规则。

理由：保持平台不侵入接入方账号体系，同时让关系链托管成为可选能力。备选方案是平台仍保存手机号和隐私设置，但这会与 v0.2 定位冲突。

### D4. 双 Timeline 扩展为统一状态事件总线

消息事件继续走 `TOPIC_MSG_EVENT`。关系、群、资料、已读、会话状态、草稿和受限投递事件通过 `TOPIC_SYNC_EVENT` 发布，由 `fanout` 唯一分配 `user_seq` 写入用户同步 Timeline。

领域服务采用本地事务 + outbox 记录状态变更，再由发布器投递到 RocketMQ。消费端按事件 ID 幂等。备选方案是每个服务直接写用户 Timeline，但会破坏“user_seq 唯一写入方”约束；或全部使用事务消息，但状态事件不需要消息路径的强原子回查，outbox 更简单可观测。

### D5. 消息模型扩展为完整状态机

`conv_timeline` 增加消息状态、撤回、引用快照、mentions、受限标记、内容版本和阅后即焚元数据。消息发送仍由 `message` 收单；接收端 ACK 后由 `fanout` 写入 `DELIVERED` 状态，已读位置推进后写入 `READ` 状态。状态只能前进，重复或旧状态事件必须丢弃。

媒体消息只保存 `media_id`、URL 和元数据引用，文件本体由 `media` 服务管理。合并转发保存不可变消息快照，不引用后续可变原消息。

### D6. 会话状态以服务端快照为权威

新增 `conversation`、`conversation_member`、`user_conversation`、`read_position` 表。客户端可乐观更新置顶、免打扰、删除和草稿，但同步事件与服务端快照是最终权威。会话列表快照携带 `max_user_seq`，用于新设备或游标过期时的全量重建。

未读数由服务端按 `max_conv_seq - read_conv_seq` 排除本人消息后计算，客户端不得长期自行累加为权威值。

### D7. 群成员权限在服务端集中校验

群消息发送、撤回、@全体、邀请、踢人、禁言、公告、群资料和邀请链接必须通过统一群权限策略校验。群成员变更写 `GroupEvent`，会话只读状态由群状态派生，不单独维护互相冲突的真值。

群上限固定 500 人，写扩散采用纯写扩散；该规模下不实现读扩散降级。

### D8. 媒体采用存储 SPI，本地默认文件系统

`media` 服务定义 `MediaStorage` SPI，提供本地文件系统和 S3-compatible 适配器。开发与 E2E 默认使用本地目录；生产通过配置替换。上传分片和对象元数据保存 MySQL，文件本体由存储适配器管理。

理由：避免本地开发强制新增 MinIO，同时保留生产对象存储能力。备选方案是直接绑定 MinIO，但会增加部署依赖。

### D9. 搜索采用索引文档 + MySQL 检索的 MVP 实现

新增联系人、群和消息搜索文档表，通过 MQ 消费领域事件幂等更新。查询使用 MySQL 索引和 `LIKE` 子串匹配，满足 MVP 功能；不实现拼音、分词、语义搜索或跨语言联想。接口保持 `SearchIndex` SPI，后续可替换 OpenSearch。

理由：不新增重量级搜索基础设施，优先完成 P0/P1 行为。备选方案是立即引入 OpenSearch，但超出 MVP 必要复杂度。

### D10. 推送与审核使用可配置 Provider

推送服务定义 `PushProvider`，提供 fake/local provider、APNs adapter 和 FCM adapter；没有生产凭据时仍可运行本地 E2E。风控服务定义 `ContentModerationProvider`，本地实现关键词与 MIME/元数据规则，生产可接第三方审核。

本地 provider 必须明确标识为开发实现，不能宣称等价于生产内容审核或真实系统推送。

### D11. 管理控制台与 IM Demo 分离

新增 `client/apps/admin-console`，只承载应用创建、AppSecret 轮换、应用配置和必要的 OpenAPI 管理视图。`client/apps/web-demo` 专注 IM 用户侧功能。两端共享 workspace 构建与 Playwright 基础配置，但不共享宿主业务状态。

### D12. 分阶段实施，单个 program-level change 管理全局契约

实施顺序：

1. 公共基础设施、协议与数据库迁移。
2. 应用、用户、OpenAPI 与认证扩展。
3. 关系链、会话与通用消息核心。
4. 媒体、单聊交互和群聊。
5. 多端、已读、设备、推送和 AppState。
6. 搜索、风控、举报和合规。
7. SDK、Web Demo、管理控制台、E2E 与发布收口。

每个阶段必须形成可运行的纵向闭环，不能只交付数据库或接口骨架。

## Risks / Trade-offs

- [单体中新增 8 个 service module，启动与依赖管理复杂度上升] → 严格使用自动装配、service-api 和 module 依赖测试；禁止业务 module 互相直接依赖。
- [状态事件通过 outbox + MQ，存在短暂最终一致窗口] → 客户端使用乐观更新和快照兜底；事件消费者幂等，监控 outbox 积压。
- [MySQL LIKE 搜索在大数据量下性能有限] → 限定 MVP 数据规模和分页；保留 `SearchIndex` SPI，后续替换搜索引擎。
- [本地 fake 推送和审核不能验证真实供应商行为] → 适配器契约测试 + 生产 smoke checklist；文档明确本地能力边界。
- [500 人群写扩散放大明显] → MQ 削峰、按用户幂等、批量写入和积压监控；不引入读扩散以控制 MVP 复杂度。
- [PaaS 冲突需求可能出现产品和技术理解偏差] → 所有适配写入规格和任务验收；PRD 后续修改必须同步 delta 规格。
- [一个 change 覆盖整个 MVP，任务规模大] → tasks 按阶段分组，每阶段独立验收；实施时不允许跳过前置阶段直接做后置功能。
- [多端状态与消息事件种类增多导致竞态] → 统一 `user_seq` 顺序、`msg_id`/事件 ID 去重、状态只进不退和服务端快照兜底。

## Migration Plan

1. 扩展 proto 与 HTTP OpenAPI，先保证旧字段和旧枚举值不变。
2. 新增幂等 DDL 和兼容列；M0 数据使用默认应用、用户状态和消息状态回填。
3. 引入 outbox 与新增 MQ topic/consumer group；旧 `TOPIC_MSG_EVENT` 语义保持不变。
4. 按阶段开启应用配置开关，先完成账号/关系/会话，再开放富消息、群聊、推送、搜索和安全能力。
5. Web SDK 采用向后兼容扩展；旧 `sendText` 保留为通用发送 API 的文本快捷方法。
6. 回滚时优先关闭新功能开关并回退应用版本；已写入列保持只增不删。开发环境允许重建 `02-mvp-schema.sql` 和清空 MQ/Redis 本地数据。

## Open Questions

- 生产 APNs/FCM 凭据和第三方内容审核供应商由部署方提供；缺失时使用明确标识的 local/fake provider。
- 管理控制台 MVP 只覆盖应用与配置管理，不规划开发者组织、计费和运营后台。
