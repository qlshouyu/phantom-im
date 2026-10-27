# RocketMQ 本地部署（Docker Compose）

基于 `apache/rocketmq:5.3.2` 的单机部署，包含 NameServer、Broker、Proxy 三个组件，外加 Dashboard 控制台，供本地开发与联调使用。

## 目录结构

```
deploy/local/rocketmq/
├── docker-compose.yml   # 编排文件
├── conf/
│   └── broker.conf      # Broker 配置（外网 IP、刷盘、自动建 Topic 等）
└── README.md
```

日志与存储使用 docker 命名卷（`rocketmq_namesrv-logs` / `rocketmq_broker-logs` / `rocketmq_broker-store`），
而非 `./data` 绑定挂载：Lima VM 将 `~` 只读挂载，绑定挂载无法写入。
`volume-init` 为一次性服务，启动前把命名卷属主改为镜像运行用户 `rocketmq(3000)`，执行完即退出（`Exited (0)` 属正常）。

## 组件说明

| 服务      | 容器名        | 端口                     | 作用                                    |
| --------- | ------------- | ------------------------ | --------------------------------------- |
| namesrv   | rmqnamesrv    | 9876                     | 路由注册中心                            |
| broker    | rmqbroker     | 10909 / 10911 / 10912    | 消息存储与收发                          |
| proxy     | rmqproxy      | 8080 / 8081              | 5.x 代理，gRPC(8081) + Remoting(8080)   |
| dashboard | rmq-dashboard | 8088（容器内 8080）      | Web 控制台：Topic/消费组/消息查询/轨迹  |

## 快速开始

```bash
cd deploy/local/rocketmq

# 启动
docker compose up -d

# 查看状态
docker compose ps

# 查看日志
docker compose logs -f broker

# 停止
docker compose down

# 停止并清空数据（删除命名卷）
docker compose down -v
```

## 客户端接入地址

- **5.x SDK（推荐，走 Proxy）**：`127.0.0.1:8081`
- **4.x / 传统 SDK（走 NameServer）**：`127.0.0.1:9876`

## Web 控制台（Dashboard）

浏览器打开 **http://localhost:8088** ——可视化查看集群/Broker、Topic 管理、消费组与消费进度、按 Key/Topic 查询消息与消费轨迹。

- 控制台在同一 docker 网络内通过 `rocketmq.namesrv.addr=rmqnamesrv:9876` 直连 NameServer 发现 Broker，**无宿主机网络坑**（不同于业务客户端从宿主机连 broker 的场景）。
- 宿主 8080 已被 proxy 占用，故控制台映射到 **8088**。
- 单独启动/重启：`docker compose up -d dashboard` / `docker compose restart dashboard`。
- 首次拉取 `apacherocketmq/rocketmq-dashboard:latest` 镜像较慢，属正常。

> 本项目相关：可在此确认 `TOPIC_MSG_EVENT`（MIXED 类型，兼容事务与普通 Outbox 消息）与消费组 `phantom-fanout` 的消费进度，排查扇出堆积。

## 关于 `brokerIP1`（重要）

5.x 是 **proxy 架构**，拓扑为「客户端 → proxy(8081) → broker」：

- 客户端只连 proxy，**不直接使用 `brokerIP1`**；
- 真正用 `brokerIP1` 连 broker 的是 **proxy 容器**，它在 docker 网络内。

因此 `conf/broker.conf` 中 **默认注释掉 `brokerIP1`**，让 broker 自动用容器 IP 注册，
proxy 才能在同一网络内连到 broker。

> ⚠️ 若把 `brokerIP1` 写成 `127.0.0.1`，proxy 会去连自己容器而非 broker，
> 导致 **rmqproxy 启动失败 / 反复重启**。这也是本部署最容易踩的坑。

**仅当**你要用 4.x/传统 SDK 直连 NameServer（不走 proxy）、且客户端在其他机器上时，
才需要把 `brokerIP1` 设为宿主机真实 IP：

```conf
brokerIP1 = 192.168.1.100   # 宿主机真实 IP；此模式下 proxy 与 broker 需同机
```

修改后执行 `docker compose up -d` 重建 broker 生效。

## 常见问题

- **生产者报 `No route info of this topic` / `No topic route info in name server for the topic: TOPIC_MSG_EVENT`**：5.x gRPC 客户端经 proxy 取路由，**不会**触发 `autoCreateTopicEnable` 自动建 Topic。`TOPIC_MSG_EVENT` 同时承载事务消息和普通 Outbox 消息，因此 Broker 必须开启 `enableMixedMessageType=true`，Topic 声明 `message.type=MIXED`。首次部署（或清空 `broker-store` 卷后）需手动创建：

  ```bash
  docker exec -e JAVA_OPT_EXT=-Xmx128m rmqbroker sh mqadmin updateTopic -n rmqnamesrv:9876 -c DefaultCluster \
    -t TOPIC_MSG_EVENT -r 8 -w 8 -a +message.type=MIXED
  docker exec -e JAVA_OPT_EXT=-Xmx128m rmqbroker sh mqadmin updateSubGroup -n rmqnamesrv:9876 -c DefaultCluster -g phantom-fanout
  ```
- **客户端连接超时**：多为 `brokerIP1` 配置为容器内地址或不可达 IP 所致，参见上文跨机部署。
- **进程被 OOM kill / 容器反复重启 / `docker exec` 返回 137**：默认 JVM 堆偏大，Lima VM 仅 4G。compose 已通过 `JAVA_OPT_EXT` 调低各组件堆（namesrv/proxy 256m，broker 512m）；在容器内跑 `mqadmin`、`tools.sh` 时也建议加 `-e JAVA_OPT_EXT=-Xmx128m`。
- **rmqproxy 首次启动报 `create system broadcast topic DefaultHeartBeatSyncerTopic failed`**：proxy 早于 broker 注册完成启动，`restart: on-failure` 会自动重试，片刻后即恢复。
