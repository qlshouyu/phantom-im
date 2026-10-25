# Phantom IM

Phantom IM 由平台服务端、Web SDK 和独立的 Web IM 消费方组成。本仓库当前包含：

- `phantom-im-server/`：Phantom 平台单体与各业务模块。
- `client/packages/phantom-sdk-web/`：Web SDK。
- `phantom-im-web-bff/`：Web IM 接入方 BFF，独立 Spring Boot 4 Maven 工程。
- `phantom-im-web-ui/`：独立 React + Vite Web IM。
- `openspec/`：规格驱动变更。
- `deploy/local/`：本地 MySQL、Redis 基础设施。

## Web IM 本地启动

完整步骤、环境变量、数据隔离和故障排查见 [`docs/web-im-consumer.md`](docs/web-im-consumer.md)。

最短路径：

```bash
# 1. 基础设施
docker compose -f deploy/local/docker-compose.yml up -d
docker compose -f docs/third/rocketmq/docker-compose.yml up -d

# 2. Phantom 单体（另开终端）
cd phantom-im-server
mvn -DskipTests package
java -jar phantom-launchers/phantom-boot-monolith/target/phantom-monolith.jar

# 3. BFF（另开终端；本地示例值不得用于生产）
cd phantom-im-web-bff
export PHANTOM_WEB_IM_APP_ID=app_demo
export PHANTOM_WEB_IM_APP_SECRET=phantom_demo_secret_please_change_0123456789
export PHANTOM_WEB_IM_USER_TOKEN_SECRET=phantom_demo_secret_please_change_0123456789
export WEB_IM_TEST_MODE=true
mvn -s .mvn/settings.xml spring-boot:run

# 4. Web UI（另开终端）
cd phantom-im-web-ui
npm install --registry=https://registry.npmjs.org
npm run dev
```

访问 `http://localhost:5173`。生产构建后，也可由 BFF 在 `http://localhost:28090` 托管 `phantom-im-web-ui/dist`。
