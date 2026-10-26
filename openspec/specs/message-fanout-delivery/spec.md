# message-fanout-delivery Specification

## Purpose

定义消息收单之后的写扩散与在线投递契约：扇出服务作为 user_seq 唯一写入方，将消息事件写入每个接收者的用户同步 Timeline，并查在线路由表下发 MsgPush、以 MsgAck 记录设备投递进度。

## Requirements

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

### Requirement: 单聊与群聊写扩散（GC-02, MD-05）

扇出服务 SHALL 为单聊双方和群聊全部有效成员分配用户序号并写入同步事件。群聊写扩散 MUST 支持最多 500 名成员并保持 `(msg_id, receiver_id)` 幂等；被移出、删除或无权接收的用户 MUST 不产生新消息事件。

#### Scenario: 群消息写扩散
- **WHEN** 一个 500 人群收到新消息
- **THEN** 每个当时有效成员获得一条且仅一条新消息同步事件

#### Scenario: 扇出重试幂等
- **WHEN** 群消息事件被重复消费
- **THEN** 已写入成员的 `user_seq` 不重复，未写入成员可继续补齐

### Requirement: 状态类同步事件扇出（CV-18, MD-07, MD-08, MD-09, MD-10, MD-11, MD-12, MD-13）

扇出服务 SHALL 支持将撤回、已读位置、已读回执、会话状态、草稿、关系、群、资料和受限投递事件写入目标用户同步 Timeline。所有事件 MUST 由唯一写入方分配 `user_seq`，并按 `user_seq` 保持全局顺序。

#### Scenario: 会话置顶同步到其他端
- **WHEN** 用户在手机端置顶会话
- **THEN** 扇出服务向同一账号其他在线或离线设备写入对应 `ConvStateEvent`

### Requirement: 在线推送重试与投递状态（MSG-09）

扇出或推送链路 SHALL 在消息推送后等待设备 ACK，5 秒未确认时使用同一 `user_seq` 重推一次。收到任一目标设备 ACK 后 MUST 为发送者生成送达状态；最终未确认时 MUST 保留同步事件供 Sync 补齐。

#### Scenario: ACK 后更新送达状态
- **WHEN** 接收方设备确认新消息的 `user_seq`
- **THEN** 发送者收到该消息的 `DELIVERED` 状态事件

### Requirement: 有界异步在线投递与慢设备隔离

fanout SHALL 在完成目标用户的用户 Timeline 写入后，将在线推送交给按用户串行的有界异步投递队列。同一用户的事件 MUST 按 `user_seq` 提交顺序投递；不同接收者之间 MUST 可并行推进，单个慢设备、慢连接或不可达连接 MUST NOT 阻塞 MQ 消费线程或其他接收者。队列溢出或投递失败时 MUST 保留用户 Timeline 事件并依赖后续 Sync 补齐。

#### Scenario: 慢设备不阻塞其他接收者
- **WHEN** 单聊一方存在一个写入缓慢的在线设备，而另一方在线连接正常
- **THEN** 正常连接在发送超时范围内收到 `MsgPush`，不被慢设备阻塞

#### Scenario: 同一用户保持 user_seq 提交顺序
- **WHEN** 同一用户连续产生两个新消息推送事件
- **THEN** 投递队列按较小 `user_seq` 到较大 `user_seq` 的顺序提交到该用户的连接

#### Scenario: 投递队列过载后保留同步事件
- **WHEN** 某用户的异步投递队列已满
- **THEN** 本次在线推送可被跳过，但该事件的用户 Timeline 已持久化，客户端可通过 `SyncReq` 补齐

#### Scenario: 单设备发送超时不影响其他设备
- **WHEN** 同一用户的一个在线设备超过推送发送时限
- **THEN** 平台终止或跳过该设备本次推送，并继续向该用户其他在线设备投递
