## Purpose

定义断线重连与多端场景下的增量同步契约：客户端凭单一 user_seq 游标从用户同步 Timeline 顺序补齐落后的事件，与在线推送共用同一进度，实现"离线可达、恢复即补齐"。

## ADDED Requirements

### Requirement: 增量同步补齐 SyncReq/SyncResp

服务端收到 `SyncReq{ since_user_seq, limit }` 后 SHALL 返回 `SyncResp{ events[], max_user_seq, has_more }`，其中 `events` 为该用户同步 Timeline 中 user_seq 大于 `since_user_seq` 的事件，按 user_seq 升序、数量不超过 `limit`。当仍有更多事件未返回时 `has_more` MUST 为 true。客户端 SHALL 循环拉取直至 `has_more=false`，并按 user_seq 顺序原子应用、每批持久化游标。

#### Scenario: 落后补齐
- **WHEN** 客户端本地游标为 N，服务端最新 user_seq 为 N+37，客户端发送 `SyncReq{ since_user_seq=N }`
- **THEN** 服务端按 user_seq 升序返回 N+1..N+37 的事件（分批则每批 `has_more` 正确标记），客户端顺序应用后游标推进至 N+37

#### Scenario: 已最新无事件
- **WHEN** 客户端游标已等于服务端最新 user_seq
- **THEN** 服务端返回空 `events`、`has_more=false`，客户端游标不变

### Requirement: 同步与在线推送共用同一游标

`SyncReq.since_user_seq` MUST 与 message-fanout-delivery 中的设备级 ACK 游标为同一进度。凡经在线 `MsgPush` 已确认（MsgAck）的事件，其在后续 `SyncReq` 中 MUST NOT 被重复下发；凡未确认或未推送的事件，MUST 能被 `SyncReq` 补齐。不存在"在线一套、离线一套"的双账。

#### Scenario: 在线已确认的不再重复补齐
- **WHEN** 某事件已通过 MsgPush 下发且客户端已回 MsgAck，随后客户端以该游标发起 `SyncReq`
- **THEN** 该事件不再出现在 `SyncResp.events` 中

#### Scenario: 在线漏收的经同步补齐
- **WHEN** 某 `NewMessageEvent` 因客户端离线未经 MsgPush 送达
- **THEN** 客户端重连后的 `SyncReq` 能拉到该事件，最终一致

### Requirement: 客户端重复事件幂等丢弃

因重推或重连竞态导致的重复事件，客户端 SHALL 按 user_seq 幂等丢弃，MUST NOT 重复应用到本地库。

#### Scenario: 重复事件被丢弃
- **WHEN** 客户端收到一个 user_seq 不大于本地已应用游标的事件
- **THEN** 客户端丢弃该事件，本地状态不变
