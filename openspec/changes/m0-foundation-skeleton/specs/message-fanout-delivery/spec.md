## Purpose

定义消息收单之后的写扩散与在线投递契约：扇出服务作为 user_seq 唯一写入方，将消息事件写入每个接收者的用户同步 Timeline，并查在线路由表下发 MsgPush、以 MsgAck 记录设备投递进度。

## ADDED Requirements

### Requirement: 写扩散与 user_seq 唯一写入

扇出服务 SHALL 消费消息事件，为会话内每个接收者（单聊为收发双方）逐一分配 user_seq 并写入其用户同步 Timeline（`NewMessageEvent`，默认内联完整消息体）。扇出 MUST 按 `(msg_id, receiver_id)` 幂等——同一接收者对同一消息 MUST NOT 产生重复的同步事件。user_seq MUST 仅由扇出服务分配，杜绝多写入方竞态。

#### Scenario: 单聊双向写扩散
- **WHEN** 一条单聊消息完成收单并进入扇出
- **THEN** 收发双方各自的用户同步 Timeline 各新增一条 `NewMessageEvent`，各带该用户维度递增的 user_seq

#### Scenario: 扇出幂等
- **WHEN** 同一消息事件因重试被扇出服务重复消费
- **THEN** 每个接收者的同步 Timeline 中该消息事件仍只存在一条，user_seq 不重复分配

### Requirement: 在线推送 MsgPush 与设备 ACK 游标

对每个接收者，服务端 SHALL 查在线路由表，对其每个在线连接下发 `MsgPush{ msg, user_seq }`。客户端收到后按 `msg_id` 幂等去重、落本地库并回 `MsgAck{ user_seq }`。服务端 SHALL 以设备维度记录已确认的 user_seq 游标，该游标 MUST 与 `SyncReq.since_user_seq` 为同一进度（在线推送与离线补拉共用一套游标）。接收者无任何在线连接时，本条 MUST 不做在线推送、仅留存于同步 Timeline 待后续补齐。

#### Scenario: 在线直推并确认
- **WHEN** 接收者有在线连接且收到 `MsgPush`
- **THEN** 客户端回 `MsgAck{ user_seq }`，服务端将该设备的 ACK 游标推进至该 user_seq

#### Scenario: 无在线连接留待同步
- **WHEN** 接收者当前无任何在线可达连接
- **THEN** 服务端不下发 MsgPush，该 `NewMessageEvent` 仍留在其同步 Timeline，等待下次 `SyncReq` 补齐

#### Scenario: 重复推送客户端去重
- **WHEN** 客户端因重连竞态收到同一 `msg_id` 的重复 `MsgPush`
- **THEN** 客户端按 `msg_id` 幂等丢弃，本地库中该消息仅一条
