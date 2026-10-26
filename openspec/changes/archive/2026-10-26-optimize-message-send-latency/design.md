## Context

见 `proposal.md` 的 Why。当前实现的关键约束如下：

- `MessageAppService.handleSend()` 在 WebSocket 收包线程内同步完成风险/关系查询、Redis 取号、RocketMQ 事务消息 half-send、MySQL Timeline 写入及事务 commit。
- `TxMqProducer` 发送确认依赖 Broker 可用性；`sync_outbox` 已有领域事件持久化、重试和 `PUBLISHED` 状态能力，但消息模块目前仅使用 `aggregate_type='message'` 发布状态类 `SYNC_EVENT`。
- fanout 在 MQ 消费线程内串行写双方用户 Timeline，并同步调用每个设备 WebSocket 发送。
- `SearchEventConsumer` 对任意 `SYNC_EVENT` 调用 `reindexUser()`；该方法可扫描 10,000 个联系人、1,000 个群和 2,000 条消息。
- Web IM 在 `sendText()` 前 `await clearDraft()`；SDK 在单条消息追加和重排时多次同步序列化全部本地缓存。
- 当前单体未显式配置 HikariCP 和 MQ 消费并发，多个消费者与 WebSocket 请求共享默认连接池。

## Goals / Non-Goals

**Goals:**

- 将用户点击到本地乐观回显、服务端发送确认、接收端在线推送的 P95/P99 降下来，并消除草稿 HTTP 对发送的前置阻塞。
- 在 Broker 短时不可用或数据库短时抖动时，发送确认和至少一次投递不互相阻塞。
- 通过应用级开关默认关闭内容审核和用户安全校验，同时保持关系、权限、格式、媒体和幂等校验。
- 在不改变 `conv_seq`/`user_seq` 契约的前提下实现有序异步投递、慢设备隔离和可观测性。
- 支持灰度、回滚和可重复性能验证。

**Non-Goals:**

- 不改变 Protobuf wire contract，不引入 Kafka、Pulsar 或新数据库。
- 不将消息内容复制到搜索之外的额外存储，不迁移既有 Timeline。
- 不让异步队列成为消息真相源；异步队列不可用时以用户 Timeline + Sync 为准。
- 不关闭好友、黑名单、会话权限、群权限、发送限流或消息格式校验。

## Decisions

### 1. 应用级风险开关与短 TTL 配置缓存

在 `app_config` 新增 `risk_check_enabled TINYINT(1) NOT NULL DEFAULT 0`，并扩展 `AppFeatureConfigDto` 与 OpenAPI 功能配置 JSON 字段 `risk_check_enabled`。新应用和未显式配置的应用均为关闭状态。

`DatabaseAppConfigProvider` 增加进程内 TTL 缓存，默认 2 秒，可由配置覆盖。消息发送读取一次 `AppFeatureConfigDto` 快照，后续风险开关、关系链和阅后即焚判断复用该快照。多实例部署下允许各实例在 TTL 内短暂使用旧值，配置更新后的最大生效窗口定义为 5 秒。

开关关闭时跳过：

- `ContentModerationProvider.moderate/review`
- `UserSafetyChecker.assertCanSend/assertCanReceive`
- 未成年人模式、禁言和发送侧安全状态查询

始终执行：

- 消息格式、长度、媒体归属和引用校验
- 好友关系、双向黑名单、受限投递
- 会话成员、群成员、角色和只读状态
- `client_msg_id` 幂等、限流和 `conv_seq` 分配

内部不把该开关加入 `AuthResp`，因为其只影响服务端发送决策，客户端无需感知。也未采用全局 Spring 配置，因为 Phantom 是多租户 PaaS，开关必须按应用隔离。

### 2. 本地事务 Outbox 替代同步事务消息

新增消息事件 Outbox 类型，复用 `sync_outbox` 表，记录 `aggregate_type='message_event'`、`aggregate_id=msg_id`，payload 为 `Msg`。消息体写入 `conv_timeline`、`message_meta`、`conv_seq` 水位和 Outbox 必须处于同一 MySQL 事务。

发送确认路径变为：

```text
校验 -> 幂等 -> Redis 取号 -> 本地事务(消息 + Outbox) -> commit -> SendMsgResp
```

新增消息事件 relay：

- 事务 commit 后通过进程内信号唤醒，尽可能立即发布 `TOPIC_MSG_EVENT/TAG_NEW_MSG`。
- 使用固定延迟轮询作为兜底，默认每 100ms 拉取一批待发布记录。
- 发布成功后标记 `PUBLISHED`；失败按指数退避更新 `next_retry_at`。
- 发布后标记前崩溃可能重复发布，依赖现有 `(sender_id, client_msg_id)`、`(user_id, msg_id)` 以及 fanout 幂等消费去重。

保留 `phantom.message.publish-mode=transaction-mq|outbox` 一个发布周期。默认先保持现有值，部署完成后切换 `outbox`；回滚时可切回 `transaction-mq`。稳定后再移除 `TxMqProducer` 在消息热路径的使用。

替代方案是继续使用事务消息并缩短超时，但仍要等待至少两次 Broker RPC，无法消除 Broker 抖动带来的发送确认长尾，因此不采用。

### 3. 发送链路应用配置与关系查询收敛

发送时只加载一次应用配置快照；风险关闭时不执行任何风险查询。关系校验增加单次查询读取双向黑名单和双向好友状态的能力，替代多次独立 `COUNT(*)`，语义与现有限制一致。

查询优化不改变错误优先级和受限投递策略；若单查询实现无法保持原有错误区分，则保留分步查询，但必须复用应用配置缓存。

### 4. 网关有界串行执行器

仅为 `SEND_MSG_REQ` 引入按连接串行的工作队列，网络 I/O 线程只负责解析和入队。工作队列由固定大小线程池与全局/单连接容量限制组成；同一连接任务串行，不同连接可并行。

队列满时返回可重试失败，不执行未受理的消息；心跳、ACK 和离线救援等轻量指令保持快速路径。使用有界队列而不是直接切换虚拟线程，因为真正的稀缺资源是 MySQL 连接和下游 RPC，无限并发只会把延迟转移为连接池等待。

### 5. fanout 异步有序投递

用户 Timeline 写入和 `user_seq` 分配仍由 fanout 消费线程完成，以保持唯一写入方语义。写入成功后，将 `MsgPush` 交给按 `receiver_id` 串行的有界投递执行器：

- 同一用户的任务严格按 `user_seq` 入队顺序执行。
- 不同用户可并行，避免发送者多设备或某个慢设备拖住接收者。
- 全局队列有界；拒绝时跳过本次在线推送，但保留用户 Timeline 供 Sync 补齐。
- 每设备 WebSocket 发送时限从硬编码 10 秒改为可配置，默认 2 秒。
- 原 5 秒 ACK 重推机制保留。

未采用“MQ 顺序消费”，因为它会令不同会话互相阻塞，而且不能解决单个慢 WebSocket 连接问题。

### 6. 搜索增量索引

`SearchEventConsumer` 按事件类型分发：

- 新消息：仅更新该消息对应文档。
- 资料、关系、群和成员变更：更新受影响对象的文档。
- 草稿、送达、已读、已读回执、会话置顶/免打扰等：忽略，不触发搜索索引写入。
- 全量重建只由显式运维/管理入口触发。

保留权限过滤作为最终兜底；增加断言，验证草稿和已读事件不会调用 `reindexUser()`。

### 7. 前端发送与草稿解耦

`ChatView.submit()` 先清空输入并调用 SDK `sendText()`，使 SDK 立即追加“发送中”消息。草稿清理作为后台 best-effort 操作；由于服务端在消息投影阶段已有 `clearDraftAfterSend()`，客户端不再等待草稿接口返回。

SDK 本地持久化采用单次待执行写入：同一事件循环窗口内的追加、重排和状态回填合并，延迟默认 200ms；`pagehide`、退出登录和显式刷新时 flush。持久化仍保存完整有序消息缓存，但每条消息最多合并为一次序列化。

### 8. 观测、连接池与 MQ 并发

使用 Micrometer 记录阶段 Timer/Counter，使用 `client_msg_id`/`msg_id` 关联链路。阶段指标包括：收单、风险、关系、取号、本地事务、Outbox lag、fanout 写入、路由查询、WS 推送、ACK 和端到端。额外记录 Outbox pending/retry、投递队列深度/拒绝、WebSocket 超时和 Hikari pending/acquire。

显式配置 Hikari 连接池与超时，并通过环境变量覆盖；初始值以可观测和压测结果为准，不直接通过扩大连接数掩盖下游问题。RocketMQ 5.x PushConsumer 通过 `setConsumptionThreadCount` 和缓存上限配置消费并发；搜索、fanout、会话投影使用独立上限，避免一个消费组耗尽连接池。

Actuator 暴露 `metrics` 端点，生产环境由部署层控制访问。性能基准在 IDE 外以生产式 JVM 参数运行，避免调试器和 profiler 影响结论。

## Risks / Trade-offs

- [风险检查默认关闭削弱内容安全] → 在配置 API、文档和发布说明中明确标注；接入方显式开启；记录配置变更审计；保留关系与权限强制校验。
- [Outbox relay 延迟导致接收端感知变慢] → after-commit 立即唤醒 + 100ms 兜底轮询；监控 pending 数和最老事件年龄；超过阈值告警。
- [Outbox 重复发布] → 现有消息与 fanout 幂等键兜底；补充“发布成功后标记失败”的故障测试。
- [异步 fanout 乱序] → 按 `receiver_id` 串行队列，同用户严格按 `user_seq` 提交；队列拒绝时保留 Timeline 并通过 Sync 补齐。
- [队列满导致在线推送缺失] → 不丢消息真相源，返回/记录可观测拒绝；客户端依靠 Sync 恢复；压测确定容量。
- [风险配置缓存多实例不一致] → TTL 限为 2 秒，最大生效窗口 5 秒；配置更新接口返回成功时明确该语义。
- [增量搜索遗漏权限变更] → 保留查询期权限过滤；为关系退出、群退出和删除对象增加专项测试；保留显式重建。
- [连接池调大导致数据库过载] → 先增加 pending/acquire 指标，再用压测确定上限；MQ 并发和 Web 请求共享同一预算。
- [前端后台草稿清理失败] → 仅记录诊断，不回滚已发送消息；服务端消息投影仍会清理草稿。

## Migration Plan

1. 增加 `app_config.risk_check_enabled`，默认 0；扩展应用配置 DTO/OpenAPI，保留旧请求兼容默认 false。
2. 先上线配置缓存、发送链路指标、网关/ fanout 有界执行器和搜索增量索引，灰度观察 P95/P99、队列拒绝和连接池等待。
3. 增加 `message_event` Outbox 与 relay，保留 `publish-mode` 开关；确认消费端兼容后按实例切换为 `outbox`。
4. 上线 Web IM 草稿解耦和 SDK 合并持久化，记录点击到乐观回显与接收端展示的分段指标。
5. 在生产式参考环境运行基线/变更后基准；满足 P95/P99 门槛后移除同步事务消息热路径。
6. 回滚策略：发送发布模式切回 `transaction-mq`；风险检查通过应用配置开启；异步队列可切回同步投递；数据库新增列和 Outbox 表保留，不做破坏性回滚。

## Open Questions

无。风险开关边界已由用户确认。
