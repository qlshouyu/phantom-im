# Easy IM BFF

Easy IM 消费方的账号服务与 HTTP BFF。独立 Spring Boot 4 / JDK 21 Maven 工程，不加入 `phantom-platform/server` 聚合工程。

## 启动

```bash
cp .env.example .env
set -a; source .env; set +a
mvn -s .mvn/settings.xml spring-boot:run
```

- 健康检查：`GET http://127.0.0.1:28090/actuator/health`
- 运行时配置：`GET http://127.0.0.1:28090/api/runtime-config`
- Phantom 代理：`/api/phantom/**`
- 数据源：仅 `phantom_web_im`
- Spring Session：Redis DB 1，前缀 `phantom:web-im:session:`
- 默认 UI 构建目录：`../clients/web/dist/`

关键环境变量：

| 变量 | 必填 | 说明 |
|---|---|---|
| `PHANTOM_WEB_IM_APP_ID` | 是 | Phantom 应用 AppID |
| `PHANTOM_WEB_IM_APP_SECRET` | 是 | OpenAPI HMAC 与 UserToken HS256 签名密钥，不下发客户端 |
| `PHANTOM_HTTP_URL` | 否 | Phantom HTTP 地址 |
| `PHANTOM_WS_URL` | 否 | Phantom WebSocket 地址 |
| `WEB_IM_DB_URL` | 否 | `phantom_web_im` JDBC URL |
| `WEB_IM_DB_USERNAME` / `WEB_IM_DB_PASSWORD` | 否 | 独立 schema 账号 |
| `WEB_IM_REDIS_HOST` / `PORT` / `PASSWORD` | 否 | Session Redis |
| `WEB_IM_COOKIE_SECURE` | 否 | HTTPS 部署设为 `true` |
| `WEB_IM_TEST_MODE` | 否 | 启用短期过期 Token 诊断接口 |
| `WEB_IM_ALLOWED_ORIGINS` | 否 | 逗号分隔的允许来源 |

## 接口语义

- `/api/auth/*` 负责注册、登录、Session、短期 Token 与退出。
- `GET /api/accounts/lookup?identifier=...` 在登录 Session 下按登录名或 Phantom ID 精确解析用户，供添加好友前确认资料。
- 注册不会创建登录 Session；登录成功才设置 HttpOnly Cookie。
- `/api/phantom/**` 只代理公开用户面 API，要求 Session 中的 Phantom ID 与 `X-User-Id` 一致。
- 代理会覆盖可信的 `X-App-Id`、`X-User-Id`，并转发 `Authorization`、`Content-Type`、`User-Agent`、`X-Device-Id`。
- `401`、字段错误、`request_id` 和上游中断均使用统一错误语义。
- 生产构建由 BFF 静态托管，浏览器继续使用同源 `/api` 与 `/api/phantom`。
- Phantom IM PC 由 Rust 回环网关托管内置 Web；网关把 `/api/*` 转发到 BFF，并把 `Origin`/`Referer` 改写为 BFF 来源。配置 `WEB_IM_ALLOWED_ORIGINS` 时必须包含实际 BFF 来源。

## 测试

```bash
mvn -s .mvn/settings.xml test
```

真实 HTTP 冒烟需要本地 MySQL、Redis 和 BFF 运行依赖：

```bash
WEB_IM_REAL_HTTP_SMOKE=true mvn -s .mvn/settings.xml test
```

`RealHttpSmokeTest` 使用随机端口 BFF、真实 MySQL/Redis，以及受控 Phantom HTTP stub，覆盖未登录、注册、登录、Session 恢复、Token、代理、上游中断和退出。

完整联调步骤见 [`../README.md`](../README.md)。
