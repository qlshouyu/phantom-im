# 消息发送低延迟链路与运维说明

## 发送链路

默认 `outbox` 模式的发送确认路径：

```text
基础校验 -> 幂等检查 -> Redis 取号 -> 本地事务
  -> conv_timeline + message_meta + conv_seq 水位 + message_event Outbox
  -> commit -> SendMsgResp
```

事务提交后通过进程内信号立即唤醒 `MessageEventOutboxRelay`，并以 100ms 轮询兜底。relay 发布
`TOPIC_MSG_EVENT/TAG_NEW_MSG` 后标记 `PUBLISHED`；失败按指数退避写回 `next_retry_at`。fanout
写入用户 Timeline 后，将在线推送交给按接收者串行的有界执行器；慢设备或队列过载不会回滚消息，
客户端通过 `SyncReq` 补齐。

## 风险检查

`risk_check_enabled` 默认关闭。关闭时仅跳过内容审核和发送侧用户安全校验；好友、黑名单、
受限投递、会话/群权限、只读状态、消息格式、媒体归属、幂等和限流始终执行。开启方法与完整边界
见 `docs/risk-check.md`。应用配置缓存默认 2 秒，最大生效窗口应控制在 5 秒内。

## 启动配置

| 配置 | 默认值 | 说明 |
|---|---:|---|
| `phantom.message.publish-mode` | `outbox`（本地启动配置） | `outbox` 或回滚用 `transaction-mq` |
| `phantom.message.outbox-poll-ms` | `100` | Outbox 兜底轮询间隔 |
| `phantom.message.outbox-batch-size` | `100` | 单批发布记录数 |
| `phantom.message.outbox-retry-base-ms` | `100` | 指数退避基数 |
| `phantom.message.outbox-retry-max-ms` | `30000` | 最大重试间隔 |
| `phantom.app-config.cache-ttl-ms` | `2000` | 应用配置缓存 TTL |
| `phantom.gateway.send-worker-threads` | `4` | 网关发送工作线程数 |
| `phantom.gateway.send-queue-capacity` | `2000` | 网关进程级发送容量 |
| `phantom.gateway.send-per-connection-capacity` | `8` | 单连接等待任务上限 |
| `phantom.gateway.send-task-timeout-ms` | `5000` | 发送任务最大排队等待时间 |
| `phantom.gateway.send-shutdown-timeout-ms` | `10000` | 停机排空时限 |
| `phantom.gateway.ws-send-time-limit-ms` | `2000` | 单设备 WebSocket 发送时限 |
| `phantom.gateway.ws-send-buffer-size-bytes` | `524288` | 单设备发送缓冲上限 |
| `phantom.gateway.send-rate-limit` | `2400` | 单用户发送限流上限（次/窗口） |
| `phantom.gateway.send-rate-window-seconds` | `60` | 发送限流窗口（秒） |
| `phantom.fanout.delivery.worker-threads` | `8` | fanout 在线投递线程数 |
| `phantom.fanout.delivery.queue-capacity` | `5000` | fanout 进程级投递容量 |
| `phantom.fanout.delivery.per-user-capacity` | `50` | 单接收者等待任务上限 |
| `phantom.fanout.delivery.shutdown-timeout-ms` | `10000` | fanout 停机排空时限 |
| `spring.datasource.hikari.maximum-pool-size` | `20` | 数据库连接池上限 |
| `spring.datasource.hikari.minimum-idle` | `5` | 数据库连接池最小空闲 |
| `spring.datasource.hikari.connection-timeout` | `5000` | 获取连接超时（毫秒） |

以上配置均支持对应环境变量覆盖，默认环境变量名已在 `application.yml` 中声明。RocketMQ
PushConsumer 的线程数和缓存上限按 `phantom.mq.consumers.fanout`、`conversation`、`search`
分别配置，避免单一消费域耗尽数据库连接池。

## 过载行为

- 网关发送队列满或排队超时：返回 `RATE_LIMITED` 可重试错误，不执行为未受理任务，连接与心跳保持。
- fanout 投递队列满：跳过本次在线推送，不删除用户 Timeline；客户端重连后通过 `SyncReq` 补齐。
- 单设备 WebSocket 超时：只影响该设备；同一用户其他设备并发发送，其他接收者不受影响。
- Outbox 发布失败：发送确认不受影响，记录重试并在 Broker 恢复后继续发布。

## 告警建议

| 指标 | 建议告警 |
|---|---|
| `phantom.mq.outbox.oldest.age` | 持续 30 秒大于 1000ms |
| `phantom.mq.outbox.retries` | 5 分钟内增长超过阈值 |
| `phantom.delivery.queue.rejected` | 任意队列持续出现拒绝 |
| `phantom.ws.send.timeouts` | 按设备类型观察持续超时 |
| `hikaricp.connections.pending` | 持续大于 0 或接近池上限 |
| `hikaricp.connections.acquire` | P95 接近获取连接超时 |
| `phantom.message.stage.duration` | `send_accept` / `outbox_publish` / `ws_push` P95/P99 回归 |

## 灰度与回滚

1. 保持 `phantom.message.publish-mode=transaction-mq` 上线新增表、指标和队列执行器。
2. 将单个实例切换为 `outbox`，确认 `TOPIC_MSG_EVENT` 消费、Outbox pending 和发送确认正常。
3. 全量切换后观察 P95/P99、队列拒绝、连接池等待和 Outbox 最老事件年龄。
4. 回滚时把 `publish-mode` 切回 `transaction-mq`；Outbox 表和 `app_config.risk_check_enabled`
   保留，不做破坏性回滚。风险检查可通过应用配置重新开启。

## 兼容性

本次变更未修改任何 Protobuf 字段或 wire contract。`conv_seq` 仍由 Redis 分配、MySQL 水位为准；
`user_seq` 仍由 fanout 作为唯一写入方分配。Outbox 重投和 fanout 重试依赖现有唯一键保持幂等，
不会产生新的 `user_seq`。
