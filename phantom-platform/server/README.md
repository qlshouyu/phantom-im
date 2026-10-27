# Phantom IM PaaS 服务端（M0 行走骨架）

单聊文本「A→在线 B + 断线 SyncReq 补齐」端到端跑通的最小实现。可分可合工程结构，M0 只做单体。

## 组成

```text
phantom-platform/
├── server/                                   Maven monorepo（Spring Boot 4.x / JDK 21）
│   ├── phantom-proto                         phantom-platform/contracts/proto 编译产物
│   ├── phantom-common                        错误码 / conv_id / 网关指令 SPI 契约
│   ├── phantom-infra/{db,redis,mq,observability}
│   ├── phantom-service-api/phantom-api-*     服务间调用契约
│   ├── phantom-services/phantom-*            业务服务模块
│   ├── phantom-launchers/phantom-boot-monolith
│   └── phantom-admin-console                 平台管理控制台（独立 npm 工程）
├── contracts/
│   ├── proto/phantom/v1/                     Java 与各端 SDK 共用契约
│   └── openapi/                              HTTP OpenAPI 契约
└── sdks/web/                                 TypeScript Web SDK
```

Easy IM 消费方位于 `phantom-im/`，其 BFF 不加入本 Maven 聚合工程。

## 依赖服务（联调）

| 服务 | 地址 | 说明 |
|---|---|---|
| MySQL | 127.0.0.1:3306 root/123456 | 库 `phantom_im`，DDL 见 `../../deploy/local/mysql-init/01-schema.sql` |
| Redis | 127.0.0.1:6379 密码 123456 | 取号/路由表；密码见 `../../deploy/local/redis.conf` |
| RocketMQ | proxy 127.0.0.1:8081 | 5.x gRPC 客户端；见 `../../deploy/local/rocketmq` |

无既有 MySQL/Redis 时：`docker compose -f ../../deploy/local/docker-compose.yml up -d`。

## 一次性准备

```bash
# 1) 建表（既有 MySQL）
docker run --rm -i mysql:8.0 mysql -h host.docker.internal -uroot -p123456 < ../../deploy/local/mysql-init/01-schema.sql

# 2) 建事务 topic + 扇出消费组（首次，或清空 broker-store 卷后）
docker exec -e JAVA_OPT_EXT=-Xmx128m rmqbroker sh mqadmin updateTopic -n rmqnamesrv:9876 -c DefaultCluster \
  -t TOPIC_MSG_EVENT -r 8 -w 8 -a +message.type=TRANSACTION
docker exec -e JAVA_OPT_EXT=-Xmx128m rmqbroker sh mqadmin updateSubGroup -n rmqnamesrv:9876 -c DefaultCluster -g phantom-fanout
```

## 构建与运行

```bash
cd phantom-platform/server
mvn -DskipTests package
java -jar phantom-launchers/phantom-boot-monolith/target/phantom-monolith.jar

# 也可通过 Maven 直接启动单体模块
mvn -DskipTests install
mvn -f phantom-launchers/phantom-boot-monolith/pom.xml spring-boot:run
```

服务端口 `28080`，WebSocket 端点 `ws://127.0.0.1:28080/ws`。

### 调试启动（IDEA）

运行 `phantom-boot-monolith` 模块的 `im.phantom.boot.PhantomMonolithApplication`。启动前确认 MySQL、Redis、RocketMQ 已就绪，且已完成上文「一次性准备」。

**常见启动失败**

- **`txMqProducer` 创建失败：`Expected the service ProducerImpl-0 [FAILED] to be RUNNING`，根因 `No topic route info in name server for the topic: TOPIC_MSG_EVENT`**
  - 原因：RocketMQ 中尚无 `TOPIC_MSG_EVENT`。5.x gRPC 客户端经 proxy 取路由，不会触发普通 Topic 自动创建；事务消息还要求 Topic 声明 `message.type=TRANSACTION`。
  - 处理：执行「一次性准备」第 2 步；可用 `docker exec rmqbroker sh mqadmin topicRoute -n rmqnamesrv:9876 -t TOPIC_MSG_EVENT` 确认路由。
- **服务正常启动，但客户端报 `WebSocket connection to 'ws://127.0.0.1:28080/ws' failed`**
  - 原因：端口被其他进程抢占。Docker（Lima）的端口转发绑定在 `127.0.0.1:<port>`，比 Java 的 `*:<port>` 更具体。
  - 排查：`lsof -nP -iTCP:28080 -sTCP:LISTEN` 应只有本服务的 Java 进程。若冲突，统一修改 `application.yml`、`phantom-im/bff/.env.example`、客户端配置和 `tools/e2e-smoke/smoke.mjs`。

## 平台管理控制台

```bash
cd phantom-platform/server/phantom-admin-console
npm install
npm run dev
```

管理控制台默认端口 `5174`，Vite 将 `/openapi` 代理到 `http://127.0.0.1:28080`。

## 验证

```bash
# 1) Java 单元测试
cd phantom-platform/server && mvn test

# 2) Web SDK 单元测试与类型检查
cd phantom-platform/sdks/web && npm test && npm run typecheck

# 3) Node 冒烟（最快，需启动平台服务与中间件）
cd tools/e2e-smoke && npm install && node smoke.mjs

# 4) Easy IM BFF（独立工程）
cd phantom-im/bff && mvn -s .mvn/settings.xml test
```

Easy IM Web 客户端当前是未接真实服务的 UI 壳，后续接入 BFF 与 SDK 后再恢复真实端到端测试。

### 历史 M0 验收结果（2026-10-23）

| 范围 | 命令 | 结果 |
|---|---|---|
| Java 单测 | `mvn test` | 14/14 通过 |
| SDK 单测 | `npm test -w @phantom/sdk-web` | 7/7 通过 |
| Node 冒烟 | `node smoke.mjs` | 12/12 通过 |
| 旧 Web UI Playwright | 已移除工程中的 `npm run e2e` | 4/4 通过 |

冒烟覆盖非法帧、首帧非 AuthReq、认证超时、过期 Token、发送/幂等/断线补齐。旧 Web UI 的验收场景需要在新 Easy IM 客户端重新实现。
