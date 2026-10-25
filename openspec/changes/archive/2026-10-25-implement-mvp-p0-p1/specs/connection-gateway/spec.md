## ADDED Requirements

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
