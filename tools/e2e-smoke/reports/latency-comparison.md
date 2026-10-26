# 1v1 消息发送延迟对比

采集工具：`tools/e2e-smoke/latency-benchmark.mjs`

## 环境

- 机器：Apple M5，10 核，24 GiB
- JVM：`-Xms512m -Xmx512m -XX:+UseG1GC -XX:+AlwaysPreTouch`
- 链路：Phantom 单体 + MySQL + Redis + RocketMQ，单实例
- 固定速率：20 条/秒；预热 100 条；测量 1000 条

## 基线（同步事务消息）

| 阶段 | P50 | P95 | P99 | 成功样本 | 失败样本 |
|---|---:|---:|---:|---:|---:|
| `SendMsgResp` | 70.42ms | 714.28ms | 732.01ms | 20 | 1960 |
| 接收端 E2E | 116.57ms | 757.89ms | 775.63ms | 20 | 1960 |

基线受旧发送限流 120 次/分钟影响，预热 100 条后仅前 20 条测量请求成功，其余请求被限流；
报告保留了全部失败样本，未删除慢样本。

## 变更后（本地事务 Outbox）

| 阶段 | P50 | P95 | P99 | 成功样本 | 失败样本 |
|---|---:|---:|---:|---:|---:|
| `SendMsgResp` | 6.53ms | 12.67ms | 86.63ms | 1000 | 0 |
| 接收端 E2E | 18.02ms | 31.30ms | 127.86ms | 1000 | 0 |

验收结果：

- `SendMsgResp` P95 `12.67ms` ≤ `50ms`
- 接收端 E2E P95 `31.30ms` ≤ `100ms`
- 接收端 E2E P99 `127.86ms` ≤ `200ms`

原始报告：`latency-baseline.json`、`latency-after.json`。
