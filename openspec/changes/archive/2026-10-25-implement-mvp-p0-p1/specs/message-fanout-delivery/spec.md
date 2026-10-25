## ADDED Requirements

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
