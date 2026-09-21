## Why

Phantom IM 的 PRD 与三份服务端设计（协议 / MQ 架构 / 工程结构）+ proto 契约均已就绪，但代码为零——这是一个 greenfield 项目。若按 module 水平铺层，会在任何一条消息真正跑通前就消耗大量成本，且架构中最聪明也最高风险的假设（事务消息回查、会话粒度取号、路由表原子登记、双 Timeline 双游标）一直悬而未决。

M0 打一根穿透全栈的针（Walking Skeleton）：让"用户 A 发一条文本消息给在线的 B，B 断线后经同步补齐"端到端跑通，一次性验证整套架构，并为后续所有功能（撤回、会话状态、群聊、媒体、搜索、平台面）建立可挂载的脊柱。

## What Changes

- **初始化 Maven monorepo 骨架**：按工程结构设计建立聚合根 + `phantom-dependencies`(BOM) + `phantom-proto`/`phantom-common`/`phantom-infra`(mq/redis/db)/`phantom-service-api`，以及本轮涉及的 service module 与 `phantom-boot-monolith` launcher（仅单体形态，同 JVM 本地调用，暂不落 gRPC stub）。
- **编译 proto 契约**：`phantom-proto` 用 protobuf-maven-plugin 从 `proto/phantom/v1/*.proto` 生成 Java。
- **本地联调栈一键起**：补齐 MySQL(3306) + Redis(6379，AOF 开启) 的 docker-compose，与已就绪的 RocketMQ 一并组成本地全栈；建表 DDL（会话 Timeline / 用户同步 Timeline / 已发 seq 水位）。
- **接入网关（connection-gateway）**：WS(WSS) 长连接、`AuthReq` 首帧验签(UserToken JWT HS256，M0 单应用简化)、`Envelope` Protobuf 编解码、在线路由表 Redis Lua 原子登记与心跳续期。
- **取号与恢复对齐（sequence-allocation）**：conv_seq(会话粒度) / user_seq(用户粒度) 经 Redis `INCR` 分配；MySQL 为真相源，Redis 冷启动/恢复时若计数器低于已落库 max 则对齐到 max，**杜绝序号回退**（守住 MSG-15/16）。
- **单聊文本收单（message-send）**：`SendMsgReq` → `client_msg_id` 幂等 → 分配 conv_seq → 落会话 Timeline → RocketMQ 事务消息 → 回 `SendMsgResp`。
- **扇出与在线投递（message-fanout-delivery）**：扇出服务作为 user_seq 唯一写入方，写用户同步 Timeline；查路由表下发 `MsgPush`，客户端回 `MsgAck` 记录设备游标。
- **增量同步补齐（sync-catchup）**：`SyncReq{since_user_seq}` → `SyncResp{events[], max_user_seq, has_more}`，在线推送与离线补拉共用同一游标。
- **正式 JS Web SDK**：TypeScript + protobuf.js，封装连接/登录、单聊文本收发、自动 ACK、断线重连与增量同步、KickPush 透传，向宿主暴露稳定 API。作为其他平台 SDK（iOS/Android/桌面，单独立项）的参考实现——`web-sdk` spec 即跨端统一客户端契约。
- **Web IM Demo + E2E**：一个消费该 SDK 的最小 IM 前端（登录/会话/单聊收发），既是用法示范，也是端到端测试载体；用浏览器自动化跑通"发→收→断线→补齐"作为 M0 联合验收。

## Capabilities

### New Capabilities
- `connection-gateway`: WS 长连接接入、认证握手(AuthReq/AuthResp)、Envelope 帧编解码、在线路由表登记与心跳判活。
- `sequence-allocation`: conv_seq / user_seq 取号契约——Redis INCR 快速分配 + MySQL 真相源恢复对齐，保证严格递增且永不回退。
- `single-chat-messaging`: 单聊文本消息收单——client_msg_id 幂等去重、conv_seq 会话内取号、落会话 Timeline、RocketMQ 事务消息保证落库与投递原子。
- `message-fanout-delivery`: 写扩散与在线投递——扇出服务唯一写 user_seq 及用户同步 Timeline、查路由下发 MsgPush、MsgAck 设备游标。
- `sync-catchup`: 断线/多端增量同步——SyncReq/SyncResp 按 user_seq 顺序补齐，与在线推送共用同一进度游标。
- `web-sdk`: 官方 JS Web SDK 客户端行为契约——连接/登录、单聊收发、自动 ACK、重连+同步、KickPush 透传、本地缓存；同时作为其他端 SDK 的参考实现规范。

### Modified Capabilities
<!-- 无：M0 为首个变更，不修改任何既有 spec。 -->

## Impact

- **新增代码**：`phantom-im-server/` Maven 聚合工程（M0 涉及的 module 子集）+ 正式 JS Web SDK + Web IM Demo（含 e2e）；`proto/` 编译产物。
- **新增基础设施配置**：MySQL / Redis 的 docker-compose 与建表 DDL（RocketMQ 已就绪）。
- **依赖**：Spring Boot 4.x / JDK 21、RocketMQ client 5.x、gRPC(仅引依赖，M0 不启用跨进程)、Nacos(M0 可暂缺，单体无需服务发现)；前端 TypeScript + protobuf.js(SDK 契约编解码) + Playwright(e2e)。
- **不在 M0 范围**（后续里程碑）：撤回、5 秒未 ACK 重推、离线推送决策树、会话状态(置顶/免打扰/草稿/已读位置)、已读回执、群聊、关系链、媒体、搜索、风控、OpenAPI 管理面、服务拆分与 gRPC stub。
- **多端/多租户简化**：M0 按单应用、同类型互踢的最小策略实现路由登记；完整多租户与设备上限策略后置。
