## ADDED Requirements

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
