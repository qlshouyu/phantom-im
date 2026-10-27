# MVP 发布与回滚手册

## 1. 发布前检查

```bash
docker compose -f deploy/local/docker-compose.yml up -d
docker compose -f deploy/local/rocketmq/docker-compose.yml up -d
docker exec -i phantom-mysql mysql -uroot -p123456 < deploy/local/mysql-init/01-schema.sql
docker exec -i phantom-mysql mysql -uroot -p123456 < deploy/local/mysql-init/02-mvp-schema.sql
cd phantom-platform/server && mvn clean test && mvn -DskipTests package
cd phantom-platform/sdks/web && npm install && npm test && npm run typecheck
```

确认 `GET /actuator/health` 的 `db`、`redis`、`ping` 均为 `UP`，RocketMQ proxy/broker/name server 容器健康。

## 2. 发布步骤

1. 备份 `phantom_im` 数据库与 `phantom.media.local-root` 媒体目录。
2. 执行幂等 DDL；脚本只新增表、列、索引，不删除已有字段。
3. 部署 `phantom-launchers/phantom-boot-monolith/target/phantom-monolith.jar`。
4. 等健康检查通过后，依次验证 WebSocket 认证、单聊发送、群消息、Sync 补齐。
5. 运行 `cd tools/e2e-smoke && node smoke.mjs`。
6. 运行 `node security-smoke.mjs`，确认 OpenAPI 重放、JWT/跨应用、媒体越权、搜索未认证与举报审计全部通过。
7. 运行 `node release-rehearsal.mjs`，生成 MySQL/媒体备份并验证关系链开关和 outbox 积压。
8. 启动 Easy IM Web UI 与管理控制台；Easy IM Web 当前尚未恢复真实端到端测试。

### ffmpeg 工具包

按目标平台把 `ffmpeg-tools.zip` 放入
`phantom-platform/server/phantom-services/phantom-media/src/main/resources/ffmpeg/{platform}/{arch}/`，
zip 根目录包含 `ffmpeg` 和 `ffprobe`（Windows 为 `.exe`）。首次启动会释放到
`phantom.media.tool-root/{ffmpeg-version}/{platform}-{arch}`；存在 `.ready` 标记且两个文件可执行时跳过释放。

标准发布包把四个平台资产同时打入同一个 jar，部署时仅释放与当前操作系统/CPU 架构匹配的一套。
推荐目录名为 `x86_64`、`aarch64`，平台目录名为 `macos`、`linux`、`windows`。

`mvn package` 首次执行时需要 Node.js 18+、`curl`，并会把四个平台资产直接下载、组装到
`phantom-media/src/main/resources/ffmpeg/.../ffmpeg-tools.zip`。目标 zip 已存在且非空时不再访问网络。
内网环境可通过 `FFMPEG_TOOLS_BASE_URL` 指定固定版本镜像，下载结果仍受 SHA-256 校验保护。
GitHub 直连较慢时可通过 `FFMPEG_TOOLS_PROXY=http://127.0.0.1:7897` 使用本地 HTTP 代理；
下载中断后再次构建会从 `.download/` 中已下载部分继续。

## 3. 回滚

- 应用回滚：停止新版本，部署上一版 jar；数据库新增列/表保持不动。
- 功能回滚：关闭应用配置中的关系链、阅后即焚、推送预览或设备策略开关。
- 消息链路异常：暂停 fanout consumer，观察 `TOPIC_MSG_EVENT` 和 `sync_outbox` 积压；恢复后按原 consumer group 重投。
- 媒体异常：切换 `MediaStorage` 回上一实现；已绑定普通消息的对象不得删除。
- DLQ：修复消费者后按原消息 ID 重放，`(msg_id, receiver_id)`、`(user_id, msg_id)` 唯一键保证幂等。

## 4. 恢复验证

- Redis 清空后首次取号必须从 MySQL `seq_watermark` 对齐，序号不得回退。
- MQ 重投后 `user_timeline` 和 `sync_outbox` 不产生重复事件。
- 媒体暂停/恢复后未完成上传可用同一 `sha256` 续传。
- 推送失败记录 `push_delivery.status=FAILED`，人工/定时重试沿用同一 `(msg_id, token_id)` 记录。

可重复执行：

```bash
cd tools/e2e-smoke
node resilience-smoke.mjs   # 重启 Redis、暂停/恢复 MQ Proxy、重投同步事件
node release-rehearsal.mjs  # MySQL/媒体备份、回滚开关与 outbox 积压检查
CAPACITY_MEMBERS=500 node capacity-smoke.mjs
```

`resilience-smoke.mjs` 会短暂重启 `phantom-redis` 并暂停 `rmqproxy`，只应在本地验证环境执行。

## 5. 已知本地边界

- 本地 `LocalPushProvider` 和 `LocalContentModerationProvider` 仅用于开发与 E2E，不代表生产 APNs/FCM 或第三方审核能力。
- APNs/FCM 生产凭据、对象存储凭据和审核供应商标识由部署方注入。
- 当前搜索为 MySQL `LIKE` 实现，适用于 MVP 数据规模；高数据量应替换 `SearchIndex` SPI。
