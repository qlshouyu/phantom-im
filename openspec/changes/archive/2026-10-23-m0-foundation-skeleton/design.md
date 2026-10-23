## Context

见 `proposal.md - Why`。本设计在既有三份规格（协议 / MQ 架构 / 工程结构）与 `proto/phantom/v1/*.proto` 之上落地 M0 行走骨架，不重新设计架构，只做"从 0 建起且能端到端验证"的最小实现选择。

约束（已定，见 `openspec/config.yaml` context）：Spring Boot 4.x / JDK 21、Maven monorepo 单体 launcher、MySQL(3306)、Redis(6379, 密码 123456)、RocketMQ(本地已就绪)。需求契约见本变更 `specs/` 下 5 份 spec。

## Goals / Non-Goals

**Goals:**
- 建立可持续演进的 Maven monorepo 骨架（后续里程碑只加 module，不改结构）。
- 单体形态下跑通"A→在线 B 单聊文本 + 断线 SyncReq 补齐"，验证事务消息、双 Timeline、取号守卫、路由表四大高风险机制协同。
- 落地取号"永不回退"守卫，把架构底线（MSG-15/16）变成可测试的行为。

**Non-Goals（设计层边界，范围性非目标见 proposal Impact）:**
- 不实现跨进程调用：M0 全为同 JVM 本地调用，`phantom-service-api` 只定义接口、**不写 gRPC stub**（拆分时才补）。
- 不引入 Nacos：单体无需服务发现与配置中心，AppSecret 以本地配置注入。
- 不做分表：会话 Timeline 单表起步，分表路由留到规模阶段。
- 不做完整多端/多租户策略：仅实现单应用验签与同类型互踢的最小子集。

## Decisions

### D1. Module 子集与单体装配

M0 只建脊柱所需 module，其余 14 个 service 待后续里程碑接入：

```
phantom-im-server/ (packaging=pom, revision 占位统一版本)
├── phantom-dependencies/            BOM
├── phantom-proto/                   protobuf-maven-plugin 编译 proto/phantom/v1/*.proto
├── phantom-common/                  错误码、ID 生成、统一响应/异常
├── phantom-infra/{mq,redis,db}/     starter：RocketMQ 模板 / Redis+Lua / 数据源
├── phantom-service-api/{route,...}  仅接口（M0 本地实现，不含 stub）
├── phantom-services/{gateway,message,fanout,push,sync}/   M0 五服务
└── phantom-launchers/phantom-boot-monolith/               唯一 launcher
```

- 每个 service module 用 `@AutoConfiguration` + `AutoConfiguration.imports` 自暴露，launcher 只声明依赖、**不写全局 `@ComponentScan`**（避免单体隐式耦合，遵工程结构 §2）。
- 网关指令分发用 **SPI**：各业务 module 注册自己的 `Command` 处理器（如 message 注册 `SEND_MSG_REQ`），gateway 不依赖任何业务 module。
- 备选：一把梭单 module 单体——否决，会破坏"可分可合"的演进前提，后续拆分需大改。

### D2. 存储模型（MySQL）

三张核心表（单库，字符集 utf8mb4）：

| 表 | 键 | 用途 | 保留 |
|---|---|---|---|
| `conv_timeline` | PK `(conv_id, conv_seq)`；唯一键 `(sender_id, client_msg_id)` | 会话消息体（永久漫游 MD-03）+ 发送幂等 | 永久 |
| `user_timeline` | PK `(user_id, user_seq)`；唯一键 `(user_id, msg_id)` 兜底扇出幂等 | 用户同步事件（M0 仅 NewMessageEvent） | 30 天滚动（M0 不做清理任务，留 M1+） |
| `seq_watermark` | PK `scope`(`conv:{id}`/`user:{id}`) | 取号真相源水位，供 Redis 恢复对齐 | 永久 |

- `conv_timeline` 的唯一键 `(sender_id, client_msg_id)` 直接承载 `single-chat-messaging` 的发送幂等——重复提交命中唯一键即返回首次结果，无需额外幂等表。
- `seq_watermark` 与落库在**同一本地事务**内推进（落 conv_timeline 时 `UPDATE seq_watermark SET seq=GREATEST(seq, :conv_seq)`），保证真相源不落后于已落库消息。

### D3. 取号与恢复对齐（本变更最关键机制，对应 sequence-allocation spec）

取号走 Redis Lua 脚本保证"对齐 + 自增"原子：

```
-- alloc_seq.lua  KEYS[1]=seq:conv:{id}  ARGV[1]=mysql_max_hint(可选)
-- 1) 若 key 不存在（Redis 冷启动/丢数据）→ 用调用方传入的 mysql_max 兜底 SET
-- 2) 若传入 mysql_max > 当前值 → 先对齐（GETSET/SET）
-- 3) 返回 INCR 结果
```

调用方（message 分配 conv_seq / fanout 分配 user_seq）流程：
1. 尝试 `INCR seq:conv:{id}`；
2. **仅当** key 是本进程内首次触达或检测到疑似重置（值异常小）时，读 `seq_watermark` 的 MySQL max 作为 `mysql_max_hint` 走对齐版 Lua——避免每次取号都查库。
3. 稳态下就是裸 `INCR`（O(1)）；异常态（Redis 丢数据）由对齐守卫兜底。

Redis 配置 `appendonly yes` + `appendfsync everysec`：正常重启计数器续增（满足用户诉求 #2）；极端丢数据由 MySQL 真相源对齐（满足"永不回退"，spec 场景已固化）。

- 备选一：DB 自增序列取号——否决，高并发下 DB 单点热点，且失去 Redis O(1) 优势。
- 备选二：号段(segment)预分配——更抗热点，但 M0 单体压力小、YAGNI；接口保留可平滑升级，不影响 spec。

### D4. 事务消息与扇出链路

严格按 MQ 架构 §2：`message` 发 half → 落 `conv_timeline`（含 watermark）→ commit；回查以 `conv_timeline` 有无该 `msg_id` 判定。`TOPIC_MSG_EVENT` 顺序消息 ShardingKey=`conv_id`。`fanout` 消费后按接收者分配 user_seq 写 `user_timeline`（`(user_id, msg_id)` 唯一键幂等），再触发在线推送。M0 的推送由 `push` 服务查路由表直投（**不做 5 秒重推**，那属 M1 `TOPIC_PUSH_RETRY`）。

### D5. 路由表 Lua 原子登记

`route:{user_id}` Hash，field=device_type，值为 JSON（device_id/conn_id/gateway_id/app_id/last_active/foreground）。登记用 Lua "读旧值+写新值+返回旧记录"，旧记录 device_id 不同 → 新连接所在网关向旧 (gateway_id,conn_id) 发 `KickPush`。心跳 `HSET` 刷 last_active，判活 `now-last_active > 2×interval`（不用 key 级 TTL，避免三端连坐）。M0 单网关实例，跨实例 RPC 投递留到网关分离阶段。

### D6. JS Web 验证工装

`tools/js-harness/`：浏览器页 + protobuf.js 加载 `proto/phantom/v1/*.proto`，能建立 WSS、发 AuthReq(内置一枚测试 UserToken 及签发脚本)、发 SendMsgReq、渲染收到的 MsgPush、手动触发 SyncReq。定位为验收工装，不是 SDK。验收脚本：开两个浏览器标签页（A/B）→ A 发文本 → B 实时收到 → 关闭 B 标签页 → A 再发 → 重开 B → SyncReq 补齐。

### D7. JS Web 正式 SDK 与 Web IM Demo（对应 web-sdk spec）

M0 交付**正式 JS Web SDK**（升级自原验证工装定位），作为其他平台 SDK 的参考实现——`web-sdk` spec 即跨端统一的客户端行为契约，其他端 SDK 立项时对齐该契约。

- **技术形态**：TypeScript + protobuf.js（加载仓库根同一份 `proto/phantom/v1/*.proto`，与服务端零漂移），打包 ESM，可 npm 发布/本地 link。
- **分层**：`transport`(WSS + Envelope 编解码 + cid 配对 + Ping/Pong) → `protocol`(指令封装) → `sync-engine`(游标管理 + SyncReq 补齐 + 重连退避) → `store`(按 conv_id 本地缓存，msg_id 去重/conv_seq 有序) → `public-api`(connect/login/sendText/事件订阅)。同步游标与自动 ACK 游标为同一份，持久化于浏览器本地存储。
- **M0 API 子集**：只暴露 M0 服务端支持的能力（连接/登录、单聊文本收发、自动 ACK、重连+同步、KickPush 透传）；撤回/会话状态/群等留待后续里程碑随服务端一并扩展，API 预留但不实现。
- **Web IM Demo**：一个消费该 SDK 的最小 IM 前端（登录、会话、单聊收发、连接状态展示），既是 SDK 的用法示范，也是 e2e 测试载体。
- **E2E**：用浏览器自动化（Playwright）开两个独立上下文模拟用户 A/B，跑"A 发文本 → B 实时收到 → 关闭 B → A 再发 → B 重连 SyncReq 补齐"，作为 M0 五份服务端 spec + web-sdk spec 的联合验收。
- 备选：SDK 用原生 WebSocket 手写编解码——否决，protobuf.js 复用同一份契约，避免手写编解码与 proto 漂移。

## Risks / Trade-offs

- [Redis 与 MySQL 水位短暂不一致导致取号对齐误判] → 对齐只在"Redis 值 < MySQL max"时触发且只增不减，方向单调安全；watermark 与落库同事务推进，不会出现 MySQL max 虚高。
- [事务消息回查依赖 conv_timeline 查询性能] → 回查按 `msg_id`（建二级索引），低频路径，可接受。
- [单网关实例是伪在线拓扑] → M0 验证功能正确性足够；路由表值已含 gateway_id，多实例 RPC 投递为纯增量，不改 spec。
- [首次取号查 watermark 的"疑似重置"判定偏启发式] → 保守策略：进程启动后每个 scope 首次取号一律走一次对齐版 Lua，之后裸 INCR；多一次查库仅发生在冷启动，代价可忽略。
- [proto 与实现漂移] → phantom-proto 从仓库根同一份 .proto 编译，JS 工装也加载同一份，杜绝双份契约。

## Migration Plan

Greenfield，无存量迁移。部署即"起本地栈（RocketMQ+MySQL+Redis）→ `mvn` 构建 → 跑 phantom-boot-monolith → 开 JS 工装验收"。回滚 = 停进程；数据表可直接 drop 重建（DDL 幂等 `CREATE TABLE IF NOT EXISTS`）。

## 实现落地记录（M0 实测偏差）

实现阶段与设计的必要偏差，已在联调环境验证（`tools/e2e-smoke/smoke.mjs` 8/8 通过）：

- **复用本机既有服务**：联调机已有 `kuship-mysql`(root/123456，匹配) 与 `kuship-redis`(**实际无密码**，与约定的 123456 不符)。M0 直接复用二者，未另起容器；`deploy/local/` 备有等价 compose 供无既有服务的环境使用。Redis 密码在 `application.yml` 置空并可配置。
- **RocketMQ 改用 5.x gRPC 客户端（`rocketmq-client-java`）经 proxy `127.0.0.1:8081`**：经典 remoting 客户端从宿主机 JVM 连 broker 会拿到 broker 注册的容器内网地址（`172.21.0.2:10911`）不可达；5.x gRPC 客户端走已发布到宿主的 proxy，由 proxy 内部路由 broker。事务 API 用 `beginTransaction/send(half)/commit|rollback` + `TransactionChecker` 回查，语义与设计一致。
- **topic 需预建且为 TRANSACTION 类型**：gRPC 生产者启动即拉取 `TOPIC_MSG_EVENT` 路由，须先 `mqadmin updateTopic -t TOPIC_MSG_EVENT -a +message.type=TRANSACTION`（否则 40402 No route）。
- **端口**：服务端口 28080（8080 被 rmqproxy、18080 被本机 Nacos 占用）。
- **BOM 内联**：`phantom-dependencies` 独立 BOM 为避免 reactor 循环解析，M0 内联于根 pom 的 dependencyManagement（版本治理等效）。
- **MQ 顺序消息简化**：M0 用并发消费，会话内可观测有序由同步分配的 conv_seq 保证；ShardingKey=conv_id 的严格顺序消费留待后续。
- **验收收口修正（2026-10-23）**：同 `device_type` 的新连接即使 `device_id` 相同（如第二个标签页）也顶替旧连接；SDK 在鉴权失败后不携带旧 Token 自动重试；SDK 持久化按 `conv_id` 组织的本地消息历史，并在服务端回推本人消息时按 `client_msg_id` 合并乐观态，避免双气泡。
