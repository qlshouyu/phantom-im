# Easy IM

Easy IM 是 Phantom IM PaaS 的消费方产品。它拥有独立的 BFF 和多终端客户端，通过 Phantom 平台服务与 `@phantom/sdk-web` 接入 IM 能力。

## 当前状态

- `bff/`：已实现账号注册登录、Redis Session、UserToken 签发和 Phantom HTTP 代理。
- `clients/web/`：已迁入新的 React + Vite UI 壳，当前使用模拟数据，尚未连接 BFF 和 SDK。
- 原 `phantom-im-web-ui/` 已移除，不再维护。
- 后续将以当前 Web UI 为基础，逐步接入 BFF、`@phantom/sdk-web` 和真实 IM 业务。

## 目录

```text
consumers/easy-im/
├── bff/                    # Easy IM BFF
├── clients/
│   └── web/                # Easy IM Web 客户端
└── README.md
```

后续可继续增加 `clients/desktop/`、`clients/android/`、`clients/ios/`，BFF 保持产品级共享。

## 本地值

| 项目 | 本地值 |
|---|---|
| BFF 端口 | `28090` |
| Web UI 开发端口 | `5173` |
| Phantom 单体 | `28080` |
| MySQL schema | `phantom_web_im` |
| MySQL 账号 | `phantom_web_im` / `phantom_web_im_dev` |
| Redis DB | `1` |
| Session key 前缀 | `phantom:web-im:session:` |
| Session Cookie | `PHANTOM_WEB_IM_SESSION`，`HttpOnly`、`SameSite=Lax`，7 天过期 |

BFF 只连接 `phantom_web_im`，不读取 `phantom_im` 业务表。`deploy/local/mysql-init/03-web-im-schema.sql` 创建 schema 和最小权限账号；业务表由 BFF 启动时的 Flyway `V1__create_web_im_account.sql` 创建。

已有本地 MySQL 数据卷不会重新执行 init SQL，可手动执行：

```bash
docker compose -f deploy/local/docker-compose.yml exec -T mysql \
  mysql -uroot -p123456 < deploy/local/mysql-init/03-web-im-schema.sql
```

## BFF 启动

```bash
cd consumers/easy-im/bff
cp .env.example .env
set -a; source .env; set +a
mvn -s .mvn/settings.xml spring-boot:run
```

关键环境变量：

| 变量 | 必填 | 说明 |
|---|---|---|
| `PHANTOM_WEB_IM_APP_ID` | 是 | Phantom 应用 AppID |
| `PHANTOM_WEB_IM_APP_SECRET` | 是 | OpenAPI HMAC 与 UserToken HS256 共用密钥，不下发客户端 |
| `PHANTOM_HTTP_URL` | 否 | Phantom HTTP 地址 |
| `PHANTOM_WS_URL` | 否 | Phantom WebSocket 地址 |
| `WEB_IM_DB_URL` | 否 | `phantom_web_im` JDBC URL |
| `WEB_IM_DB_USERNAME` / `WEB_IM_DB_PASSWORD` | 否 | 独立 schema 账号 |
| `WEB_IM_REDIS_HOST` / `PORT` / `PASSWORD` | 否 | Session Redis；DB 固定为 1 |
| `WEB_IM_COOKIE_SECURE` | 否 | HTTPS 部署设为 `true` |
| `WEB_IM_TEST_MODE` | 否 | 启用 Token 过期诊断接口 |
| `WEB_IM_ALLOWED_ORIGINS` | 否 | 逗号分隔的同源允许地址 |

缺少 AppID 或 AppSecret 时 BFF 拒绝启动。UserToken 必须直接使用 AppSecret 签发；禁止提交真实 AppSecret，也不要在生产环境启用测试模式。

Maven `artifactId` 和可执行 Jar 名称当前仍为 `phantom-im-web-bff`，本次目录整改未修改构建坐标。

## Web UI 启动

```bash
cd consumers/easy-im/clients/web
npm install
npm run dev
```

开发地址：`http://localhost:5173`。

生产构建：

```bash
npm run build
```

BFF 默认从 `../clients/web/dist/` 托管构建产物，可通过 `WEB_IM_UI_DIST` 改为绝对路径。当前 UI 尚未接入 BFF，静态托管能力不代表业务链路已完成。

## 验证

```bash
# BFF
cd consumers/easy-im/bff
mvn -s .mvn/settings.xml test

# Web UI
cd consumers/easy-im/clients/web
npm run build
```

后续接入 SDK 后，需要补充使用真实 Phantom、BFF 和中间件的 Playwright 端到端测试。

## 后续接入顺序

1. 沿用当前视觉组件和平台适配层。
2. 将模拟认证替换为 BFF 登录、Session 和运行时配置。
3. 通过 BFF 获取短期 UserToken。
4. 接入 `@phantom/sdk-web` 的 WebSocket、消息、同步和领域 API。
5. 将原 Web IM 的行为验收场景重新实现到新客户端。
6. 完成 Web 后再扩展桌面和移动客户端。
