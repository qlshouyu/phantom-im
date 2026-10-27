# Phantom IM

Phantom IM 是一个包含 PaaS 平台与消费方产品的 IM 工程：

- `platform/`：Phantom IM PaaS，提供平台服务、通信契约、各端 SDK 和平台管理控制台。
- `consumers/easy-im/`：基于 Phantom IM PaaS 构建的消费方 IM 产品，包含独立 BFF 和多终端客户端。

## 目录概览

```text
platform/
├── server/                         # Spring Boot / JDK 21 Maven 聚合工程
│   └── phantom-admin-console/     # 平台管理控制台（独立 npm 工程）
├── contracts/
│   ├── proto/phantom/v1/           # Java 与各端 SDK 共用的 protobuf 契约
│   └── openapi/                    # HTTP OpenAPI 契约
└── sdks/web/                       # @phantom/sdk-web

consumers/easy-im/
├── bff/                            # Easy IM 账号、Session、UserToken 与 HTTP BFF
└── clients/web/                    # Easy IM Web 客户端
```

## 当前状态

- Phantom 平台服务和 Web SDK 已具备 M0 链路。
- `consumers/easy-im/clients/web/` 当前是新 UI 壳，使用本地模拟数据。
- Easy IM 的 BFF、SDK、WebSocket 和真实业务数据接入尚待后续实现。
- 原 `phantom-im-web-ui/` 已移除，不再作为 Easy IM 客户端维护。

## 本地启动

### 1. 基础设施

```bash
docker compose -f deploy/local/docker-compose.yml up -d
docker compose -f deploy/local/rocketmq/docker-compose.yml up -d
```

### 2. Phantom 平台单体

```bash
cd platform/server
mvn -DskipTests package
java -jar phantom-launchers/phantom-boot-monolith/target/phantom-monolith.jar
```

服务端口为 `28080`，WebSocket 路径为 `/ws`。

### 3. Easy IM BFF

```bash
cd consumers/easy-im/bff
cp .env.example .env
set -a; source .env; set +a
mvn -s .mvn/settings.xml spring-boot:run
```

BFF 默认端口为 `28090`。完整配置见 [`consumers/easy-im/README.md`](consumers/easy-im/README.md)。

### 4. Easy IM Web UI

```bash
cd consumers/easy-im/clients/web
npm install
npm run dev
```

开发地址为 `http://localhost:5173`。当前 UI 仍是视觉壳，后续接入 BFF 与 `@phantom/sdk-web`。

## 平台管理控制台

```bash
cd platform/server/phantom-admin-console
npm install
npm run dev
```

管理控制台默认端口为 `5174`，通过 Vite 代理访问 Phantom OpenAPI。

## 验证

```bash
# 平台服务端
cd platform/server && mvn test

# Web SDK
cd platform/sdks/web && npm test && npm run typecheck

# Easy IM BFF
cd consumers/easy-im/bff && mvn -s .mvn/settings.xml test

# 快速冒烟（需启动平台服务与中间件）
cd tools/e2e-smoke && node smoke.mjs
```

规格与设计分别位于 `openspec/` 和 `docs/`。
