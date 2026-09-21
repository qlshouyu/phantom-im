> 进度：任务组 1–7（服务端）已实现，`tools/e2e-smoke/smoke.mjs` 端到端验证 8/8 通过
> （认证/发送/在线投递+ACK/幂等/断线重连 SyncReq 补齐，真实 MySQL+Redis+RocketMQ）。
> 落地偏差（已记录于 design/说明）：复用本机既有 MySQL(root/123456)/Redis(无密码)；
> RocketMQ 改用 5.x gRPC 客户端经 proxy:8081（经典 remoting 客户端从宿主机不可达容器内网 broker）；
> conv_id 采用可逆排序拼接；phantom-dependencies BOM 内联于根 pom；MQ 顺序消息以 conv_seq 保证可观测有序。

## 1. 本地联调栈与工程骨架（D1）

- [x] 1.1 本地栈就绪：复用本机既有 MySQL(3306)/Redis(6379)，`deploy/local/` 备 compose+DDL；建表 DDL 已应用（conv_timeline/user_timeline/seq_watermark）
- [x] 1.2 Maven 聚合根 `phantom-im-server/`（packaging=pom、统一版本、依赖方向约定）
- [x] 1.3 版本治理：Spring Boot 4.0.0/JDK21、RocketMQ 5.x、protobuf 锁定于根 pom dependencyManagement（BOM 角色）
- [x] 1.4 `phantom-proto`：protobuf-maven-plugin 从 `proto/phantom/v1/*.proto` 生成 126 个 Java 类
- [x] 1.5 `phantom-common`（错误码/异常/ID/conv_id/SPI 契约）与 `phantom-boot-monolith` 可启动

## 2. 基础设施 starter（D2/D3/D5）

- [x] 2.1 `phantom-infra-db`：数据源 + JdbcTemplate 自动装配
- [x] 2.2 `phantom-infra-redis`：StringRedisTemplate + Lua 脚本（取号/路由表）
- [x] 2.3 `phantom-infra-mq`：RocketMQ 5.x gRPC **事务消息模板**（beginTransaction/commit/rollback + 回查）+ 消费者工厂

## 3. 取号与恢复对齐（spec: sequence-allocation，design D3）

- [x] 3.1 `alloc_seq.lua`：不存在/低于 hint 时用 MySQL max 对齐后 INCR，稳态裸 INCR
- [x] 3.2 `SeqAllocator`：进程首次触达 scope 走对齐版（读 `seq_watermark`），之后裸 INCR；`nextConvSeq`/`nextUserSeq`
- [x] 3.3 冒烟验证 conv_seq/user_seq 严格递增；对齐守卫单测留待补充（回退场景）

## 4. 接入网关（spec: connection-gateway，design D5）

- [x] 4.1 `phantom-gateway`：WS `/ws` + `Envelope` 编解码；非法帧隔离；cid 配对
- [x] 4.2 认证握手：首帧 `AuthReq` JWT HS256 验签；`AuthResp`；10s 超时/首帧非 AuthReq 断开
- [x] 4.3 路由表登记 Lua（读旧+写新+返回旧）+ 同类型互踢 `KickPush{SAME_TYPE_LOGIN}`
- [x] 4.4 心跳 Ping/Pong 刷 last_active + `now-last_active>2×interval` 判活（field 粒度）
- [x] 4.5 指令分发 SPI：`CommandHandler` 收集分发，gateway 不依赖业务 module

## 5. 单聊文本收单（spec: single-chat-messaging，design D4）

- [x] 5.1 `phantom-message` 注册 `SEND_MSG_REQ`；conv_id 单聊规则（ConvIds）
- [x] 5.2 收单：`(sender_id,client_msg_id)` 幂等 → conv_seq → 落 `conv_timeline` + 同事务推进水位
- [x] 5.3 事务消息：half→落库→commit；回查以 msg_id 判定；返回 `SendMsgResp`
- [x] 5.4 TEXT>5000 返回 `4001 MSG_TOO_LONG`
- [x] 5.5 冒烟验证发送成功 + 重发幂等（msgId/convSeq 不变）；回查 rollback 单测留待补充

## 6. 扇出与在线投递（spec: message-fanout-delivery，design D4）

- [x] 6.1 `phantom-fanout` 消费 `TOPIC_MSG_EVENT`：双方各分配 user_seq 写 `user_timeline`（`(user_id,msg_id)` 幂等）
- [x] 6.2 `phantom-push`：查路由表下发 `MsgPush{msg,user_seq}`；无在线连接不推
- [x] 6.3 `MsgAck` 回收：`AckCursorStore` 设备维度记录游标
- [x] 6.4 冒烟验证单向投递 + 幂等；重复 MsgPush 客户端去重（SDK 侧）

## 7. 增量同步补齐（spec: sync-catchup）

- [x] 7.1 `phantom-sync` 注册 `SYNC_REQ`：按 `since_user_seq` 升序返回 ≤limit + `max_user_seq` + `has_more`
- [x] 7.2 同步游标与 ACK 游标同一进度（客户端自带游标，已 ACK 不再下发）
- [x] 7.3 冒烟验证断线→重连→补齐离线消息；分批/幂等丢弃单测留待补充

## 8. 正式 JS Web SDK（spec: web-sdk，design D7）

- [x] 8.1 SDK 工程 `client/packages/phantom-sdk-web`：TypeScript + protobuf.js（预编译 proto-bundle.json，浏览器/Node 同构）；dev token 签发（demo 内 WebCrypto HS256）
- [x] 8.2 transport 层：WS 连接 + `Envelope` 编解码 + 请求响应 cid 配对 + Ping/Pong 心跳
- [x] 8.3 连接/登录：`login(userToken, deviceInfo)` 完成 AuthReq/AuthResp；`2001 TOKEN_EXPIRED` → `tokenExpired` 事件
- [x] 8.4 发送：`sendText` 生成 client_msg_id + 乐观态 + SendMsgResp 回填；15s 超时；`resend` 复用原 client_msg_id
- [x] 8.5 接收：`MsgPush` 按 msg_id 去重 + 落本地缓存 + 自动回 `MsgAck` + `message` 事件
- [x] 8.6 sync-engine：指数退避抖动重连 + 重连后比对 max_user_seq 发 `SyncReq` 循环补齐 + 游标 localStorage 持久化
- [x] 8.7 `KickPush` → `kicked` 事件不自动重连；store 按 conv_id 缓存（conv_seq 有序、msg_id 去重）+ 事件订阅
- [x] 8.8 e2e 覆盖发送幂等/重复推送去重/重连补齐；被踢不重连由 SDK 逻辑保证（专项单测留待补充）

## 9. Web IM Demo 与端到端 E2E（design D7）

- [x] 9.1 `client/apps/web-demo`：消费 SDK 的最小 IM 前端（登录、单聊收发、连接状态、断开/重连按钮）
- [x] 9.2 Playwright e2e：两浏览器上下文 A/B → A 发→B 实时收到并 ACK → B 断→A 再发→B 重连 SyncReq 补齐（**1 passed**，系统 Chrome）
- [x] 9.3 验收证据：`tools/e2e-smoke` 8/8 + Playwright 1/1；`openspec validate m0-foundation-skeleton --strict` 通过
