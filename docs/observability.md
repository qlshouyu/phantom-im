# 消息链路可观测性

## 本地读取

本地启动单体时启用 `local` profile：

```bash
java -jar phantom-launchers/phantom-boot-monolith/target/phantom-monolith.jar \
  --spring.profiles.active=local
```

随后可读取：

```bash
curl http://127.0.0.1:28080/actuator/health
curl http://127.0.0.1:28080/actuator/metrics
curl http://127.0.0.1:28080/actuator/metrics/phantom.message.stage.duration
```

默认配置只暴露 `health,info`。`metrics` 仅在 `application-local.yml` 中启用；生产部署必须通过
内网、网关鉴权或指标采集网络访问，不得直接暴露到公网。需要临时调整暴露列表时，使用
`MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` 环境变量，并由部署平台负责访问控制。

## 消息阶段指标

- `phantom.message.stage.duration`：按 `stage` 和 `result` 标记的阶段耗时，启用百分位直方图。
- `phantom.message.stage.skipped`：被跳过阶段计数，例如风险检查关闭时 `stage=risk,reason=disabled`。
- `phantom.mq.outbox.pending`：待发布 Outbox 数量。
- `phantom.mq.outbox.oldest.age`：最老待发布 Outbox 年龄（毫秒）。
- `phantom.mq.outbox.retries`：Outbox 发布重试次数。
- `phantom.ws.send.timeouts`：WebSocket 单设备发送超时次数。
- `phantom.delivery.queue.depth`：在线投递队列深度。
- `phantom.delivery.queue.rejected`：在线投递队列拒绝次数及原因。
- `hikaricp.connections.pending` / `hikaricp.connections.acquire`：连接池等待线程数与获取连接耗时。

`msg_id` 和 `client_msg_id` 不进入指标标签，以避免高基数；它们记录在结构化日志中，用于从
`发送受理完成`、`收单成功`、`扇出` 和 `设备 ACK 处理完成` 日志关联单条消息。
