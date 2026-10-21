# Phantom IM 服务端（M0 行走骨架）

单聊文本「A→在线 B + 断线 SyncReq 补齐」端到端跑通的最小实现。可分可合工程结构，M0 只做单体。

## 组成

```
phantom-im-server/          Maven monorepo（Spring Boot 4.x / JDK 21）
  phantom-proto             proto/phantom/v1 编译产物
  phantom-common            错误码 / conv_id / 网关指令 SPI 契约
  phantom-infra-{db,redis,mq}  数据源 / 取号守卫+路由表 / RocketMQ 事务消息
  phantom-service-api/phantom-api-route  服务间契约（M0 本地实现）
  phantom-services/{gateway,message,fanout,push,sync}  五个业务服务
  phantom-launchers/phantom-boot-monolith  单体启动器
../client/                  前端 monorepo：JS Web SDK（参考实现）+ Web Demo + Playwright e2e
../tools/e2e-smoke          Node 冒烟测试（端到端验证 5 份服务端 spec）
```

## 依赖服务（联调）

| 服务 | 地址 | 说明 |
|---|---|---|
| MySQL | 127.0.0.1:3306 root/123456 | 库 `phantom_im`，DDL 见 `../deploy/local/mysql-init/01-schema.sql` |
| Redis | 127.0.0.1:6379 密码 123456 | 取号/路由表；密码见 `../deploy/local/redis.conf`，需与 application.yml 一致 |
| RocketMQ | proxy 127.0.0.1:8081 | 5.x gRPC 客户端；见 `../docs/third/rocketmq` |

无既有 MySQL/Redis 时：`docker compose -f ../deploy/local/docker-compose.yml up -d`。

## 一次性准备

```bash
# 1) 建表（既有 MySQL）
docker run --rm -i mysql:8.0 mysql -h host.docker.internal -uroot -p123456 < ../deploy/local/mysql-init/01-schema.sql
# 2) 建事务 topic + 扇出消费组（首次，或清空 broker-store 卷后）
docker exec -e JAVA_OPT_EXT=-Xmx128m rmqbroker sh mqadmin updateTopic -n rmqnamesrv:9876 -c DefaultCluster \
  -t TOPIC_MSG_EVENT -r 8 -w 8 -a +message.type=TRANSACTION
docker exec -e JAVA_OPT_EXT=-Xmx128m rmqbroker sh mqadmin updateSubGroup -n rmqnamesrv:9876 -c DefaultCluster -g phantom-fanout
```

## 构建与运行

```bash
mvn -DskipTests package
java -jar phantom-launchers/phantom-boot-monolith/target/phantom-monolith.jar
# 服务端口 28080，WS 端点 ws://127.0.0.1:28080/ws
```

### 调试启动（IDEA）

运行 `phantom-boot-monolith` 模块的 `im.phantom.boot.PhantomMonolithApplication`。启动前确认 MySQL / Redis / RocketMQ 已就绪，且已完成上文「一次性准备」。

**常见启动失败**

- **`txMqProducer` 创建失败：`Expected the service ProducerImpl-0 [FAILED] to be RUNNING`，根因 `No topic route info in name server for the topic: TOPIC_MSG_EVENT`**
  - 原因：RocketMQ 中尚无 `TOPIC_MSG_EVENT`。5.x gRPC 客户端经 proxy 取路由，**不会**触发 broker 的 `autoCreateTopicEnable` 自动建 Topic；且事务消息要求 Topic 声明 `message.type=TRANSACTION`，自动创建的普通 Topic 也无法使用。
  - 处理：执行「一次性准备」第 2 步建 Topic 与消费组，可用 `docker exec rmqbroker sh mqadmin topicRoute -n rmqnamesrv:9876 -t TOPIC_MSG_EVENT` 确认路由已存在，然后重新启动。
- **服务正常启动，但 Web Demo 报 `WebSocket connection to 'ws://127.0.0.1:28080/ws' failed`**
  - 原因：端口被其他进程抢占。Docker（Lima）的端口转发绑定在 `127.0.0.1:<port>`，比 Java 的 `*:<port>` 更具体，`127.0.0.1` 的连接会被转发进容器而非本服务（曾遇到 Nacos 容器占用 18080，返回 500 `No static resource ws`）。
  - 排查：`lsof -nP -iTCP:28080 -sTCP:LISTEN` 应只有本服务的 java 进程；`docker ps --format '{{.Names}}\t{{.Ports}}' | grep 28080` 应为空。若冲突，统一修改 `application.yml` 的 `server.port` 与客户端 `WS_URL`（`client/apps/web-demo/src/main.ts`、`tests/e2e.spec.ts`、`tools/e2e-smoke/smoke.mjs`）。

## 验证

```bash
# A) Node 冒烟（最快）
cd ../tools/e2e-smoke && npm i && node smoke.mjs

# B) Web Demo + Playwright e2e
cd ../client && npm i
cd apps/web-demo && npx playwright install chromium
npm run e2e         # 两浏览器上下文：A 发→B 收→B 断→A 发→B 重连 SyncReq 补齐
npm run dev         # 手动体验：/?user=alice 与 /?user=bob 两个标签页
```
