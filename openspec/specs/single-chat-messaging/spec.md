# single-chat-messaging Specification

## Purpose

定义单聊文本消息的收单契约：客户端幂等提交、服务端分配会话序号并落会话 Timeline、经事务消息保证"落库与投递原子"，构成消息可靠必达的第一环。

## Requirements

### Requirement: 单聊文本发送收单

服务端收到 `SendMsgReq{ client_msg_id, conv_id, msg_type=TEXT, content }` 后 SHALL 依次完成：基础校验 → `client_msg_id` 幂等检查 → 分配 conv_seq → 在同一 MySQL 事务中写入完整消息体和待发布新消息事件 Outbox → 提交本地事务 → 返回 `SendMsgResp{ code=0, msg_id, conv_seq, server_time }`。发送确认 MUST NOT 等待下游 MQ 发布成功。`conv_id` 对单聊 MUST 由双方 uid 升序拼接 hash 规则生成，从任一入口进入同一好友会话必得同一 `conv_id`。文本长度超过 5000 字符 MUST 返回 `4001 MSG_TOO_LONG`。

#### Scenario: 文本消息发送成功
- **WHEN** 客户端发送合法的单聊 `SendMsgReq`（TEXT，≤5000 字符）
- **THEN** 服务端完成本地落库和 Outbox 写入后返回 `SendMsgResp{ code=0, msg_id, conv_seq, server_time }`，且该消息以分配的 conv_seq 落入会话 Timeline

#### Scenario: MQ 暂不可用时仍完成发送确认
- **WHEN** 消息和 Outbox 已成功提交，但 RocketMQ 当前不可达
- **THEN** 服务端仍返回发送成功，Outbox 在 MQ 恢复后继续发布

#### Scenario: 文本超长被拒
- **WHEN** 客户端发送的 TEXT 内容超过 5000 字符
- **THEN** 服务端返回 `4001 MSG_TOO_LONG`，不分配 conv_seq、不落库

### Requirement: client_msg_id 发送幂等

服务端 SHALL 按 `(sender_id, client_msg_id)` 对发送请求去重。对同一 `(sender_id, client_msg_id)` 的重复提交，MUST 返回首次成功的结果（同一 `msg_id` / `conv_seq` / `server_time`），且 MUST NOT 在会话 Timeline 产生第二条记录。

#### Scenario: 重发命中幂等
- **WHEN** 客户端因超时用同一 `client_msg_id` 重新提交同一条消息
- **THEN** 服务端返回首次分配的 `msg_id`/`conv_seq`/`server_time`，会话 Timeline 中该消息仅存在一条

### Requirement: 落库与投递原子性（事务消息）

会话 Timeline 落库与新消息事件发布 MUST 保持最终原子性：消息体与 Outbox 记录 MUST 在同一本地事务中提交，二者不得只成功其一。Outbox relay SHALL 以至少一次语义发布新消息事件，消费端 MUST 按 `msg_id` 保持幂等；发布成功但确认状态未落库导致的重复发布 MUST 不产生重复消息或重复 `user_seq`。

#### Scenario: 落库成功后投递提交
- **WHEN** 消息 Timeline 与 Outbox 在同一事务中成功提交
- **THEN** Outbox relay 最终发布对应新消息事件并驱动下游扇出

#### Scenario: 落库失败不投递
- **WHEN** 消息 Timeline 写入失败
- **THEN** 同一事务中的 Outbox 不提交，下游不会收到该消息事件，客户端可凭同一 `client_msg_id` 重发

#### Scenario: 发布重复保持幂等
- **WHEN** Outbox relay 发布成功后、标记已发布前异常退出，恢复后再次发布同一消息事件
- **THEN** fanout 和会话投影仍按消息幂等键处理，不产生重复消息或重复事件

### Requirement: 单聊通用消息收单（MSG-01, MSG-02, MSG-03, MSG-04, MSG-05, MSG-06, MSG-07, MSG-08, MSG-12, MSG-17）

单聊收单 SHALL 从文本扩展为 PRD 允许的全部 P0/P1 消息类型，并对类型、内容必填字段、文本长度、媒体引用和合并消息结构执行服务端校验。通过校验的消息 MUST 使用与文本相同的幂等、`conv_seq` 分配、本地事务 Outbox 和响应契约。

#### Scenario: 发送有效语音消息
- **WHEN** 客户端提交带有效 `media_id` 和不超过 60 秒时长的语音消息
- **THEN** 服务端完成幂等检查和取号，在同一本地事务中落库并写 Outbox

#### Scenario: 消息类型内容不匹配
- **WHEN** 客户端以 IMAGE 类型提交缺少媒体引用的内容
- **THEN** 服务端返回内容校验错误且不分配会话序号

### Requirement: 单聊发送权限与关系校验（DC-15, DC-16, DC-17, DC-18, REL-12, REL-13）

服务端 MUST 在收单时校验发送者与会话成员关系、应用关系链配置、好友状态、黑名单和会话只读状态；上述校验 MUST NOT 受风险检查开关影响。当前用户主动删除或拉黑对方时 MUST 拒绝发送；对方删除或拉黑当前用户时 MUST 正常落库但不向对方投递，并生成受限投递提示。

#### Scenario: 发送者已拉黑对方
- **WHEN** 已拉黑对方的用户直接发送单聊消息
- **THEN** 服务端返回黑名单限制错误且不落库

#### Scenario: 接收方已删除发送者
- **WHEN** 发送者向已单向删除自己的用户发送消息
- **THEN** 服务端保存发送者消息但不生成接收方同步事件，并向发送者写入受限投递提示

#### Scenario: 风险检查关闭仍校验关系
- **WHEN** 应用关闭风险检查且双方已不再是好友
- **THEN** 服务端仍按关系链配置执行消息请求、受限投递或拒绝逻辑，不得把非好友当作好友直接放行

### Requirement: 发送热路径不执行全量重复配置查询

单次发送处理 MUST 复用同一请求内已加载的应用功能配置，并 MUST NOT 为获取风险开关、关系链配置和阅后即焚配置重复执行等价数据库查询。风险检查关闭时 MUST 直接跳过内容审核与用户安全校验，不得为被跳过的能力执行预备查询。

#### Scenario: 风险关闭时不查询风险数据
- **WHEN** 应用关闭风险检查且用户发送普通文本
- **THEN** 发送链路不查询用户状态、有效处罚或未成年人模式，也不调用内容审核 provider

#### Scenario: 单次发送复用应用配置
- **WHEN** 一条消息同时需要关系链配置、风险开关和阅后即焚 TTL
- **THEN** 服务端复用同一份应用配置快照，不重复执行相同配置查询
