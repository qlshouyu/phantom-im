# Easy IM

Easy IM 是 Phantom IM PaaS 的消费方产品，包含独立 BFF、React Web 客户端与 Tauri 2 PC 客户端。

## 当前状态

- 注册、登录、退出、Redis Session、短期 Phantom UserToken 和 Session 恢复已接入真实 BFF。
- Web 使用 `@phantom/sdk-web` 建立 WebSocket、执行同步、收发消息并处理重连/顶替/Token 过期。
- 消息、通讯录、好友申请、群组、资料、设备、通知、搜索和诊断均使用真实 SDK/PaaS 数据。
- 收藏夹、云空间、群文件、群相册和语音/视频通话没有公开 PaaS 契约，界面明确显示暂未开放。
- 本地真实链路由 Playwright 双/多用户上下文、BFF HTTP 冒烟和现有 `tools/e2e-smoke` 覆盖。
- Phantom IM PC 复用 Web 生产构建，通过 Rust 回环同源网关连接 BFF；macOS 真实业务链路已验收，Windows/Linux x64 的 CI 构建、安装和启动验收已通过。

完整能力状态见 [`docs/phantom-im/integration-matrix.md`](../docs/phantom-im/integration-matrix.md)，验收记录见 [`docs/phantom-im/acceptance-report.md`](../docs/phantom-im/acceptance-report.md)。

## 目录

```text
phantom-im/
├── bff/                    # 独立 Spring Boot 账号服务与 HTTP BFF
├── clients/
│   ├── web/                # React + Vite Web 客户端
│   └── pc/                 # Tauri 2 + Rust Phantom IM 桌面客户端
└── README.md
```

## 从空环境启动

前置要求：JDK 21、Maven、Node.js/npm、Docker。

```bash
# 1. MySQL + Redis
docker compose -f deploy/local/docker-compose.yml up -d

# 2. RocketMQ
docker compose -f deploy/local/rocketmq/docker-compose.yml up -d

# 3. Phantom 单体
cd phantom-platform/server
mvn -DskipTests package
java -jar phantom-launchers/phantom-boot-monolith/target/phantom-monolith.jar
```

另开终端启动 BFF：

```bash
cd phantom-im/bff
cp .env.example .env
# 填写本地 PHANTOM_WEB_IM_APP_ID / PHANTOM_WEB_IM_APP_SECRET
set -a; source .env; set +a
mvn -s .mvn/settings.xml spring-boot:run
```

再启动 Web：

```bash
cd phantom-im/clients/web
npm install
npm run dev
```

访问 `http://localhost:5173`。Vite 将 `/api` 代理到 `http://127.0.0.1:28090`。

启动 PC 客户端：

```bash
cd phantom-im/clients/pc
npm install
npm run dev
```

PC 开发服务器使用 `http://127.0.0.1:5273`。生产构建会先执行 Web 构建，再把 `dist` 复制到 Tauri 资源目录。完整说明见 [`clients/pc/README.md`](clients/pc/README.md)。

全部服务启动后可执行：

```bash
node tools/easy-im-preflight.mjs
```

## 本地值

| 项目 | 本地值 |
|---|---|
| Phantom 单体 | `http://127.0.0.1:28080`，WebSocket `ws://127.0.0.1:28080/ws` |
| BFF | `http://127.0.0.1:28090` |
| Web 开发服务器 | `http://127.0.0.1:5173` |
| PC 开发服务器 | `http://127.0.0.1:5273` |
| MySQL | `127.0.0.1:3306`，业务库 `phantom_im`，BFF 库 `phantom_web_im` |
| Redis | `127.0.0.1:6379`，BFF Session DB 1 |
| RocketMQ | Namesrv `127.0.0.1:9876`，gRPC proxy `127.0.0.1:8081` |

BFF 的 HttpOnly Session Cookie 为 `PHANTOM_WEB_IM_SESSION`，7 天有效。浏览器不会持久化 Phantom UserToken。

## 验证与验收

```bash
# BFF（真实 MySQL/Redis 冒烟）
cd phantom-im/bff
WEB_IM_REAL_HTTP_SMOKE=true mvn -s .mvn/settings.xml test

# Web 单元、类型、构建、契约
cd phantom-im/clients/web
npm test
npm run typecheck
npm run build
npm run test:contract

# Web 真实全链路
npm run test:e2e -- --timeout=240000

# PC 构建与网关测试
cd phantom-im/clients/pc
npm test
npm run tauri -- build --bundles app

# 生产式同源（BFF 已使用最新 dist）
E2E_PROD=true E2E_BASE_URL=http://127.0.0.1:28090 \
  npm run test:e2e -- e2e/production.spec.ts

# SDK
cd phantom-platform/sdks/web
npm test -- --run
npm run typecheck

# Phantom 平台
cd phantom-platform/server
mvn test

# 平台冒烟
cd tools/e2e-smoke
node smoke.mjs
```

## 安全边界

- AppSecret 仅存在于 BFF 与 Phantom 服务端，浏览器不可见。
- Phantom UserToken 只在内存中，由 BFF 短期签发。
- Web IM Session 使用 HttpOnly Cookie。
- 密码、Cookie、Token 不写入 localStorage；浏览器只保存非敏感设备 ID 和本机 UI 偏好。
