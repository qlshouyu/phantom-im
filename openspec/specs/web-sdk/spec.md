# web-sdk Specification

## Purpose

定义 Phantom IM 官方 JS Web SDK 的客户端行为契约：封装长连接、认证、单聊收发、自动 ACK、断线重连与增量同步，向宿主 Web 应用暴露稳定 API。该契约同时作为其他平台 SDK（iOS/Android/桌面）的参考实现规范——各端 SDK 须提供等价的可观测行为。

## Requirements

### Requirement: 连接与登录

SDK SHALL 提供 `connect/login(userToken, deviceInfo)` 建立 WSS 连接并以首帧 `AuthReq` 完成认证，成功后暴露已连接状态与 `AuthResp` 中的 `max_user_seq` 供同步引擎使用。SDK MUST 内部完成 `Envelope` 的 Protobuf 编解码与请求-响应 `cid` 配对，宿主 App 无需感知帧结构。当认证返回 `2001 TOKEN_EXPIRED` 时，SDK MUST 回调宿主取新 UserToken 而非静默失败。

#### Scenario: 登录成功
- **WHEN** 宿主以有效 UserToken 调用 `login`
- **THEN** SDK 建立 WSS、完成 AuthReq/AuthResp，进入已连接状态并触发 `onConnected` 事件

#### Scenario: Token 过期回调宿主
- **WHEN** 认证或重连时服务端返回 `2001 TOKEN_EXPIRED`
- **THEN** SDK 触发 `onTokenExpired` 回调请求宿主提供新 Token，不自行放弃连接

### Requirement: 发送单聊文本

SDK SHALL 提供 `sendText(convId, text)`：本地生成 `client_msg_id`、立即以"发送中"乐观态回显、经长连接发 `SendMsgReq`；收到 `SendMsgResp` 后将该消息更新为"已发送"并回填 `msg_id`/`conv_seq`/`server_time`。超时（默认 15s）无响应 MUST 置为"失败"，宿主可触发重发，重发 MUST 复用原 `client_msg_id`（依赖服务端幂等，不产生重复消息）。

#### Scenario: 发送成功回填
- **WHEN** 宿主调用 `sendText` 且服务端返回 `SendMsgResp{code=0}`
- **THEN** 该本地消息由"发送中"变为"已发送"，携带服务端回填的 `msg_id`/`conv_seq`/`server_time`

#### Scenario: 超时失败可重发
- **WHEN** 发送后 15s 未收到响应
- **THEN** 该消息置为"失败"；宿主重发时 SDK 复用原 `client_msg_id`，成功后仍为同一条（不重复）

### Requirement: 接收消息与自动 ACK

SDK SHALL 处理服务端 `MsgPush`：按 `msg_id` 幂等去重、写入本地缓存、**自动回 `MsgAck{ user_seq }`**，并向宿主触发 `onMessage` 事件。重复 `MsgPush`（重推/重连竞态）MUST 被 SDK 去重，宿主 MUST NOT 收到重复的 `onMessage`。

#### Scenario: 收到新消息并自动确认
- **WHEN** SDK 收到一条新的 `MsgPush`
- **THEN** SDK 落本地缓存、自动回 `MsgAck`、触发一次 `onMessage`

#### Scenario: 重复推送被去重
- **WHEN** SDK 因竞态收到同一 `msg_id` 的第二条 `MsgPush`
- **THEN** SDK 丢弃该重复帧，本地缓存与 `onMessage` 均不重复

### Requirement: 断线重连与增量同步

网络断开后 SDK SHALL 以指数退避（`1s,2s,4s,...` 上限 60s，叠加 ±30% 抖动）自动重连；重连成功后重新 `AuthReq`，比较 `AuthResp.max_user_seq` 与本地游标，落后则发 `SyncReq` 循环补齐直至 `has_more=false`，按 user_seq 顺序原子应用并持久化游标。同步游标 MUST 与自动 ACK 进度为同一游标。

#### Scenario: 重连后补齐落后消息
- **WHEN** SDK 断线期间服务端新增若干事件，随后 SDK 重连成功
- **THEN** SDK 依据 `max_user_seq` 发起 `SyncReq` 补齐全部落后事件，本地状态与服务端一致

#### Scenario: 指数退避重连
- **WHEN** 连接意外断开
- **THEN** SDK 按 1s、2s、4s… 递增（含抖动、上限 60s）自动重连，不产生固定间隔的重连风暴

### Requirement: 被踢下线透传宿主

收到 `KickPush` 时 SDK MUST NOT 自动重连，MUST 向宿主回调 `onKicked(reason)` 并透传原因（如 `SAME_TYPE_LOGIN`），由宿主决定后续处理。

#### Scenario: 同端登录被顶替
- **WHEN** SDK 收到 `KickPush{ reason=SAME_TYPE_LOGIN }`
- **THEN** SDK 断开且不自动重连，触发 `onKicked("SAME_TYPE_LOGIN")` 交由宿主处理

### Requirement: 本地会话缓存与事件订阅

SDK SHALL 维护按 `conv_id` 组织的本地消息缓存，并向宿主暴露读取接口与事件订阅（至少 `onConnected` / `onMessage` / `onKicked` / `onTokenExpired`）。消息在缓存中 MUST 按 `conv_seq` 有序、按 `msg_id` 去重。

#### Scenario: 宿主读取会话消息有序无重
- **WHEN** 宿主查询某会话的本地消息列表
- **THEN** 返回结果按 `conv_seq` 升序排列且无重复 `msg_id`
