## Purpose

定义单聊文本消息的收单契约：客户端幂等提交、服务端分配会话序号并落会话 Timeline、经事务消息保证"落库与投递原子"，构成消息可靠必达的第一环。

## ADDED Requirements

### Requirement: 单聊文本发送收单

服务端收到 `SendMsgReq{ client_msg_id, conv_id, msg_type=TEXT, content }` 后 SHALL 依次完成：`client_msg_id` 幂等检查 → 分配 conv_seq → 将完整消息体落会话 Timeline → 发送 RocketMQ 事务消息 → 返回 `SendMsgResp{ code=0, msg_id, conv_seq, server_time }`。`conv_id` 对单聊 MUST 由双方 uid 升序拼接 hash 规则生成，从任一入口进入同一好友会话必得同一 `conv_id`。文本长度超过 5000 字符 MUST 返回 `4001 MSG_TOO_LONG`。

#### Scenario: 文本消息发送成功
- **WHEN** 客户端发送合法的单聊 `SendMsgReq`（TEXT，≤5000 字符）
- **THEN** 服务端返回 `SendMsgResp{ code=0, msg_id, conv_seq, server_time }`，且该消息以分配的 conv_seq 落入会话 Timeline

#### Scenario: 文本超长被拒
- **WHEN** 客户端发送的 TEXT 内容超过 5000 字符
- **THEN** 服务端返回 `4001 MSG_TOO_LONG`，不分配 conv_seq、不落库

### Requirement: client_msg_id 发送幂等

服务端 SHALL 按 `(sender_id, client_msg_id)` 对发送请求去重。对同一 `(sender_id, client_msg_id)` 的重复提交，MUST 返回首次成功的结果（同一 `msg_id` / `conv_seq` / `server_time`），且 MUST NOT 在会话 Timeline 产生第二条记录。

#### Scenario: 重发命中幂等
- **WHEN** 客户端因超时用同一 `client_msg_id` 重新提交同一条消息
- **THEN** 服务端返回首次分配的 `msg_id`/`conv_seq`/`server_time`，会话 Timeline 中该消息仅存在一条

### Requirement: 落库与投递原子性（事务消息）

会话 Timeline 落库与下游投递 MUST 保持原子：二者要么都生效、要么都不生效。服务端 SHALL 采用 RocketMQ 事务消息实现——先发 half 消息、落库成功后 commit；落库失败则 rollback。Broker 回查时 SHALL 以"会话 Timeline 是否存在该 msg_id"作为 commit/rollback 依据。

#### Scenario: 落库成功后投递提交
- **WHEN** 消息成功落入会话 Timeline
- **THEN** 对应事务消息被 commit，进入下游扇出流程

#### Scenario: 落库失败不投递
- **WHEN** 消息落会话 Timeline 失败
- **THEN** 对应 half 事务消息最终被 rollback，下游不会收到该消息事件；客户端可凭同一 `client_msg_id` 超时重发
