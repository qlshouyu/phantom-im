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
| Redis | 127.0.0.1:6379 | 取号/路由表；本机实测无密码（可在 application.yml 配置） |
| RocketMQ | proxy 127.0.0.1:8081 | 5.x gRPC 客户端；见 `../docs/third/rocketmq` |

无既有 MySQL/Redis 时：`docker compose -f ../deploy/local/docker-compose.yml up -d`。

## 一次性准备

```bash
# 1) 建表（既有 MySQL）
docker run --rm -i mysql:8.0 mysql -h host.docker.internal -uroot -p123456 < ../deploy/local/mysql-init/01-schema.sql
# 2) 建事务 topic（首次）
docker exec rmqbroker sh mqadmin updateTopic -n rmqnamesrv:9876 -c DefaultCluster -t TOPIC_MSG_EVENT -a +message.type=TRANSACTION
```

## 构建与运行

```bash
mvn -DskipTests package
java -jar phantom-launchers/phantom-boot-monolith/target/phantom-monolith.jar
# 服务端口 18080，WS 端点 ws://127.0.0.1:18080/ws
```

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
