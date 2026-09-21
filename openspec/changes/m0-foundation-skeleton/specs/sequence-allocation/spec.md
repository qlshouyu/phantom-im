## Purpose

定义 conv_seq（会话内排序）与 user_seq（用户同步 Timeline 编号）的取号契约：以 Redis 快速分配、MySQL 为真相源，保证严格单调递增且在 Redis 数据丢失后永不回退，从而守住"不重、不乱序"的架构底线。

## ADDED Requirements

### Requirement: conv_seq 会话粒度严格递增取号

服务端 SHALL 为每个会话独立维护一个 conv_seq 计数器，经 Redis `INCR seq:conv:{conv_id}` 分配。同一会话内先后分配的 conv_seq MUST 严格递增且不重复；不同会话之间相互独立。conv_seq MUST 在返回 `SendMsgResp` 之前完成分配（同步链路内），不得进入异步流程。

#### Scenario: 同会话连续取号递增
- **WHEN** 同一会话内先后两次分配 conv_seq
- **THEN** 后一次的 conv_seq 严格大于前一次，且二者不相等

#### Scenario: 不同会话互不影响
- **WHEN** 会话 A 与会话 B 各自分配 conv_seq
- **THEN** 两个会话的 conv_seq 序列相互独立，互不干扰

### Requirement: user_seq 用户粒度唯一递增取号

服务端 SHALL 为每个用户维护一个 user_seq 计数器，经 Redis `INCR seq:user:{user_id}` 分配，且 user_seq MUST 仅由扇出服务作为唯一写入方分配（见 message-fanout-delivery）。同一用户先后分配的 user_seq MUST 严格递增且不重复。

#### Scenario: 同用户取号唯一递增
- **WHEN** 同一用户的同步 Timeline 先后写入两个事件
- **THEN** 两个事件的 user_seq 严格递增且互不重复

### Requirement: Redis 恢复对齐守卫，杜绝序号回退

MySQL 中已落库的 conv_seq / user_seq MUST 视为真相源。当 Redis 冷启动或数据丢失导致其计数器低于 MySQL 中对应维度的已落库最大值时，服务端在下一次取号前 MUST 先将 Redis 计数器对齐到 `MySQL_MAX`，使新分配值从 `MySQL_MAX + 1` 起。任何情况下 MUST NOT 重复发出一个已落库的 conv_seq 或 user_seq。

#### Scenario: Redis 丢数据后不回退
- **WHEN** 某会话已落库 conv_seq 达 1005，随后 Redis 重启且 `seq:conv:{conv_id}` 丢失（读到 0 或小于 1005）
- **THEN** 下一次取号前计数器被对齐到 1005，新分配的 conv_seq 为 1006（≥ 已落库 max + 1），绝不重复发放 1001~1005

#### Scenario: Redis 正常持久化续增
- **WHEN** Redis 以 AOF 持久化正常重启，计数器值不低于 MySQL 已落库 max
- **THEN** 取号直接在现值上 `INCR` 续增，无需回补
