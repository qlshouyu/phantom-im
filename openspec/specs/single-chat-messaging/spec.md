# single-chat-messaging Specification

## Purpose

定义单聊文本消息的收单契约：客户端幂等提交、服务端分配会话序号并落会话 Timeline、经事务消息保证"落库与投递原子"，构成消息可靠必达的第一环。

## Requirements

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

### Requirement: 单聊通用消息收单（MSG-01, MSG-02, MSG-03, MSG-04, MSG-05, MSG-06, MSG-07, MSG-08, MSG-12, MSG-17）

单聊收单 SHALL 从文本扩展为 PRD 允许的全部 P0/P1 消息类型，并对类型、内容必填字段、文本长度、媒体引用和合并消息结构执行服务端校验。通过校验的消息 MUST 使用与文本相同的幂等、`conv_seq` 分配、事务消息和响应契约。

#### Scenario: 发送有效语音消息
- **WHEN** 客户端提交带有效 `media_id` 和不超过 60 秒时长的语音消息
- **THEN** 服务端完成幂等检查和取号，落库并提交投递事务

#### Scenario: 消息类型内容不匹配
- **WHEN** 客户端以 IMAGE 类型提交缺少媒体引用的内容
- **THEN** 服务端返回内容校验错误且不分配会话序号

### Requirement: 单聊发送权限与关系校验（DC-15, DC-16, DC-17, DC-18, REL-12, REL-13）

服务端 MUST 在收单时校验发送者与会话成员关系、应用关系链配置、好友状态、黑名单和会话只读状态。当前用户主动删除或拉黑对方时 MUST 拒绝发送；对方删除或拉黑当前用户时 MUST 正常落库但不向对方投递，并生成受限投递提示。

#### Scenario: 发送者已拉黑对方
- **WHEN** 已拉黑对方的用户直接发送单聊消息
- **THEN** 服务端返回黑名单限制错误且不落库

#### Scenario: 接收方已删除发送者
- **WHEN** 发送者向已单向删除自己的用户发送消息
- **THEN** 服务端保存发送者消息但不生成接收方同步事件，并向发送者写入受限投递提示
