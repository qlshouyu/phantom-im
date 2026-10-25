# connection-gateway Specification

## Purpose

定义客户端如何经 WebSocket 长连接接入 Phantom IM、完成认证握手，以及服务端如何登记与维护该连接的在线状态，作为所有实时消息与信令的统一入口。

## Requirements

### Requirement: WebSocket 连接与 Envelope 帧编解码

服务端 SHALL 通过 WSS 暴露单一长连接端点，每个 WebSocket binary 帧承载一个 Protobuf `Envelope`（`version` / `cmd` / `cid` / `code` / `payload`）。请求-响应帧 MUST 以 `cid` 配对，服务端主动推送帧的 `cid` MUST 为 0。无法解析为合法 `Envelope` 的帧 MUST 被拒绝且不影响其他连接。

#### Scenario: 合法请求帧被正确解析并回带 cid
- **WHEN** 客户端发送一个 `cid=42` 的合法 `Envelope` 请求帧
- **THEN** 服务端对应的响应帧回带 `cid=42`，且 `payload` 为该 `cmd` 约定的报文体

#### Scenario: 非法帧被隔离
- **WHEN** 客户端发送一个无法反序列化为 `Envelope` 的二进制帧
- **THEN** 服务端丢弃该帧或断开该连接，且不影响同网关上的其他连接

### Requirement: 认证握手（首帧 AuthReq）

连接建立后，客户端发送的**首帧 MUST 是 `AuthReq`**。服务端 SHALL 校验 UserToken（JWT HS256）签名有效、未过期，且 `AuthReq.user_id` 与 Token claims 一致。校验通过返回 `AuthResp`（含 `server_time`、`max_user_seq`、`heartbeat_interval_s`）；校验失败返回对应鉴权错误码并断开。超过 10 秒未完成认证的连接 MUST 被服务端主动断开。

#### Scenario: 认证成功
- **WHEN** 客户端首帧发送携带有效 UserToken 的 `AuthReq`
- **THEN** 服务端返回 `AuthResp{ server_time, max_user_seq, heartbeat_interval_s }` 且连接进入已认证状态

#### Scenario: Token 过期
- **WHEN** 客户端 `AuthReq` 携带的 UserToken 已过期
- **THEN** 服务端返回 `2001 TOKEN_EXPIRED` 并断开连接

#### Scenario: 认证超时
- **WHEN** 连接建立后 10 秒内客户端未发送 `AuthReq`
- **THEN** 服务端主动断开该连接

#### Scenario: 首帧非 AuthReq
- **WHEN** 已建立但未认证的连接发来任意非 `AuthReq` 指令帧
- **THEN** 服务端拒绝处理该帧并断开连接

### Requirement: 在线路由表登记与同类型互踢

认证成功后，服务端 SHALL 以 `(user_id, device_type, device_id, conn_id, gateway_id)` 将连接原子登记进 Redis 在线路由表（`route:{user_id}` Hash，field=device_type）。同一 `device_type` 已存在连接时，MUST 向旧连接推 `KickPush{ reason=SAME_TYPE_LOGIN }` 后断开，保证同类型至多一条在线连接。登记 MUST 为原子操作，并发登录只有一个胜出。

#### Scenario: 首次登记
- **WHEN** 用户某 `device_type` 首次认证成功
- **THEN** 路由表新增该 field 记录，可被后续推送查询命中

#### Scenario: 同类型登录顶替
- **WHEN** 用户在已有 MOBILE 在线连接的情况下，另一 MOBILE 设备认证成功
- **THEN** 旧 MOBILE 连接收到 `KickPush{ reason=SAME_TYPE_LOGIN }` 并被断开，路由表该 field 更新为新连接

### Requirement: 心跳与在线判活

客户端 SHALL 按 `heartbeat_interval_s` 周期发送 `Ping`，服务端回 `Pong` 并刷新该连接在路由表中的 `last_active`。服务端 SHALL 依据 `now - last_active > 2 × heartbeat_interval` 判定连接失效并摘除对应路由记录。判活 MUST 以 field（device_type）为粒度，不得因单端超时而误摘同用户其他端。

#### Scenario: 心跳续期
- **WHEN** 已认证连接发送 `Ping`
- **THEN** 服务端回 `Pong` 并更新该连接路由记录的 `last_active`

#### Scenario: 超时摘除
- **WHEN** 某连接连续 2 个心跳周期未收到任何上行帧
- **THEN** 服务端将该连接对应的路由 field 摘除，使其不再被推送命中

### Requirement: 应用感知认证与配置下发（AC-03, AC-04, AC-06, AC-08）

网关 SHALL 在 UserToken 验签后校验应用状态、用户归属和用户可用状态，并通过 `AuthResp` 下发应用级关系链、已读反馈、阅后即焚 TTL 和设备策略配置。Token 中 `app_id` 与 `user_id` MUST 同时有效，禁用、删除或封禁用户 MUST 在认证阶段被拒绝。

#### Scenario: 认证返回应用配置
- **WHEN** 有效用户完成 AuthReq
- **THEN** AuthResp 包含当前应用的关系链、已读、阅后即焚和设备策略配置

#### Scenario: 已禁用用户认证
- **WHEN** 被禁用用户携带仍在有效期内但已撤销的 UserToken 发起认证
- **THEN** 网关返回 `TOKEN_REVOKED` 并断开连接

### Requirement: 设备限制与应用状态路由（MD-01, MD-02, MD-10, MD-18）

网关 SHALL 在同一 Redis 路由记录中维护设备类型、设备标识、前台状态和最近活跃时间。新连接 MUST 按应用设备策略检查数量上限；同类型连接 MUST 原子顶替旧连接。`AppStateReq` MUST 更新对应设备的 `foreground` 状态供推送决策读取。

#### Scenario: 更新前台状态
- **WHEN** 桌面客户端发送 `AppStateReq{foreground=true}`
- **THEN** 该设备路由记录更新为前台活跃，后续推送决策可读取该状态

#### Scenario: 超过设备策略
- **WHEN** 新设备登录使在线设备数超过应用配置上限且无同类型连接可顶替
- **THEN** 网关拒绝认证并返回设备数量超限错误

### Requirement: Presence 与 Typing 信令路由（DC-05, DC-06）

网关 SHALL 将 Presence 订阅、取消订阅和 Typing 信令按路由表转发给目标在线设备。上述信令 MUST NOT 落库、进入 RocketMQ 或写入同步 Timeline；路由不存在时 MUST 返回离线或直接丢弃 Typing。

#### Scenario: 转发 Typing 信令
- **WHEN** 单聊用户向在线好友发送 Typing 信令
- **THEN** 网关仅向目标的在线连接转发 `TypingPush`，不产生持久化事件

### Requirement: 网关入口限流（SEC-13）

网关 SHALL 对认证、发送消息、关系操作和群操作等指令执行按用户与应用维度的令牌桶限流。超过阈值 MUST 返回统一限流错误和可重试时间，且不得关闭正常心跳连接。

#### Scenario: 高频发送被限流
- **WHEN** 用户短时间发送消息超过应用阈值
- **THEN** 后续指令收到限流响应，连接和心跳保持有效
