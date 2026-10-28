# Phantom IM

Phantom IM 是一个包含 PaaS 平台与消费方产品的 IM 工程：

- `phantom-platform/`：Phantom IM PaaS，提供平台服务、通信契约、各端 SDK 和平台管理控制台。
- `phantom-im/`：基于 Phantom IM PaaS 构建的消费方 IM 产品，包含独立 BFF 和多终端客户端。

## 目录概览

```text
phantom-platform/
├── server/                         # Spring Boot / JDK 21 Maven 聚合工程
│   └── phantom-admin-console/     # 平台管理控制台（独立 npm 工程）
├── contracts/
│   ├── proto/phantom/v1/           # Java 与各端 SDK 共用的 protobuf 契约
│   └── openapi/                    # HTTP OpenAPI 契约
└── sdks/web/                       # @phantom/sdk-web

phantom-im/
├── bff/                            # Easy IM 账号、Session、UserToken 与 HTTP BFF
└── clients/
    ├── web/                        # Easy IM Web 客户端
    └── pc/                         # Phantom IM Tauri 2 桌面客户端
```

## 当前状态

- Phantom 平台服务、Web SDK、Easy IM BFF 与 React Web 客户端已接入真实 IM 链路。
- `phantom-im/clients/web/` 使用 BFF、Web SDK 和 Phantom 真实业务数据。
- `phantom-im/clients/pc/` 提供 Tauri 2 桌面客户端，构建时复用 Web 生产产物并内置到安装包。
- macOS 已完成桌面主链路验收；Windows、Linux 构建配置已就绪，仍需对应原生环境验证。

## 本地启动

### 1. 基础设施

```bash
docker compose -f deploy/local/docker-compose.yml up -d
docker compose -f deploy/local/rocketmq/docker-compose.yml up -d
```

### 2. Phantom 平台单体

```bash
cd phantom-platform/server
mvn -DskipTests package
java -jar phantom-launchers/phantom-boot-monolith/target/phantom-monolith.jar
```

服务端口为 `28080`，WebSocket 路径为 `/ws`。

### 3. Easy IM BFF

```bash
cd phantom-im/bff
cp .env.example .env
set -a; source .env; set +a
mvn -s .mvn/settings.xml spring-boot:run
```

BFF 默认端口为 `28090`。完整配置见 [`phantom-im/README.md`](phantom-im/README.md)。

### 4. Easy IM Web UI

```bash
cd phantom-im/clients/web
npm install
npm run dev
```

开发地址为 `http://localhost:5173`，通过 Vite 代理访问 BFF。

### 5. Phantom IM PC 客户端

```bash
cd phantom-im/clients/pc
npm install
npm run dev
```

桌面开发使用 `http://127.0.0.1:5273`。生产构建会先编译 Web，再把 `dist` 复制到 Tauri 资源目录：

```bash
npm run tauri -- build --bundles app   # macOS app 主产物
npm run build                          # 当前平台全部 bundle
```

完整配置与三平台说明见 [`phantom-im/clients/pc/README.md`](phantom-im/clients/pc/README.md)。

## 平台管理控制台

```bash
cd phantom-platform/server/phantom-admin-console
npm install
npm run dev
```

管理控制台默认端口为 `5174`，通过 Vite 代理访问 Phantom OpenAPI。

## 验证

```bash
# 平台服务端
cd phantom-platform/server && mvn test

# Web SDK
cd phantom-platform/sdks/web && npm test && npm run typecheck

# Easy IM BFF
cd phantom-im/bff && mvn -s .mvn/settings.xml test

# Phantom IM PC
cd phantom-im/clients/pc && npm test

# Windows/Linux x64 构建、安装和启动由 GitHub Actions 执行
# .github/workflows/pc-build-install-smoke.yml

# 快速冒烟（需启动平台服务与中间件）
cd tools/e2e-smoke && node smoke.mjs
```

规格与设计分别位于 `openspec/` 和 `docs/`。
