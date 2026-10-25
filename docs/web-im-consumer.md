# Web IM 消费方开发与运行

Web IM 是 Phantom IM 的真实接入方示例：浏览器 WebSocket 直连 Phantom Gateway，HTTP/JSON 经 Web IM BFF 代理；账号密码、AppSecret 和 Phantom UserToken 签发均在 BFF 侧完成。

## 1. 工程与数据隔离

| 项目 | 本地值 |
|---|---|
| BFF 端口 | `28090` |
| UI 开发端口 | `5173` |
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

## 2. 环境变量

复制 `phantom-im-web-bff/.env.example` 并按环境注入。关键变量：

| 变量 | 必填 | 说明 |
|---|---|---|
| `PHANTOM_WEB_IM_APP_ID` | 是 | 单 Phantom 应用 AppID |
| `PHANTOM_WEB_IM_APP_SECRET` | 是 | OpenAPI HMAC 与 UserToken HS256 共用密钥，不下发浏览器 |
| `PHANTOM_HTTP_URL` | 否 | Phantom HTTP 地址 |
| `PHANTOM_WS_URL` | 否 | Phantom WebSocket 地址 |
| `WEB_IM_DB_URL` | 否 | `phantom_web_im` JDBC URL |
| `WEB_IM_DB_USERNAME` / `WEB_IM_DB_PASSWORD` | 否 | 独立 schema 账号 |
| `WEB_IM_REDIS_HOST` / `PORT` / `PASSWORD` | 否 | Session Redis；DB 固定为 1 |
| `WEB_IM_COOKIE_SECURE` | 否 | HTTPS 部署设为 `true` |
| `WEB_IM_TEST_MODE` | 否 | 启用 Token 过期诊断接口 |
| `WEB_IM_ALLOWED_ORIGINS` | 否 | 逗号分隔的同源允许地址 |

缺少 AppID 或 AppSecret 时 BFF 拒绝启动。UserToken 必须直接使用 AppSecret 签发，以匹配 Phantom Gateway 的验签密钥；禁止提交真实 AppSecret，也不要在生产环境启用测试模式。

## 3. 启动顺序

1. 启动 MySQL、Redis 和 RocketMQ。
2. 构建并启动 Phantom 单体。
3. 启动 BFF：

   ```bash
   cd phantom-im-web-bff
   set -a; source .env; set +a
   mvn -s .mvn/settings.xml spring-boot:run
   ```

4. 启动 UI：

   ```bash
   cd phantom-im-web-ui
   npm install --registry=https://registry.npmjs.org
   npm run dev
   ```

5. 打开 `http://localhost:5173/register`，注册成功后返回登录页；登录成功才会启动 SDK。

生产构建：

```bash
cd phantom-im-web-ui && npm run build
cd ../phantom-im-web-bff && mvn -s .mvn/settings.xml -DskipTests package
java -jar target/phantom-im-web-bff.jar
```

BFF 从 `../phantom-im-web-ui/dist/` 提供静态资源；可通过 `WEB_IM_UI_DIST` 改为绝对路径。

## 4. 验证命令

```bash
# BFF：校验、状态机、Session、JWT、OpenAPI 签名与代理白名单
cd phantom-im-web-bff
mvn -s .mvn/settings.xml test

# UI：表单、store、消息去重、诊断
cd phantom-im-web-ui
npm test
npm run typecheck
npm run build

# 真实端到端：Phantom + BFF + MySQL + Redis + UI
npm run e2e
```

Playwright 覆盖注册、登录、好友申请、单聊、媒体消息、HTTP 代理、Token 过期刷新、主动重连和同端顶替。测试依赖真实中间件与 Phantom 单体，不应 mock。

## 5. 故障排查

- **BFF 启动失败并提示配置**：检查必填环境变量；Token 密钥至少 32 字节。
- **MySQL 表不存在**：确认 `WEB_IM_DB_URL` 指向 `phantom_web_im`，并检查 Flyway 日志和 `flyway_schema_history`。
- **登录后受保护请求 401**：确认 Cookie 名与浏览器请求一致；HTTPS 下需设置 `WEB_IM_COOKIE_SECURE=true`。
- **Redis 中没有 Session**：确认使用 `spring-boot-starter-session-data-redis`，DB 1 中应出现 `phantom:web-im:session:sessions:*`。
- **代理返回 403**：`X-User-Id` 必须等于当前 Session 的 Phantom ID；只允许用户面路径，禁止 `/openapi/**`。
- **WebSocket 连接失败**：检查 BFF `GET /api/runtime-config` 返回的 `ws_url` 是否能从浏览器直接访问。
- **本机 Maven 镜像不可解析**：BFF 自带 `.mvn/settings.xml`，使用 `mvn -s .mvn/settings.xml ...` 指定阿里云 public 仓库。
- **端口占用**：BFF 默认 `28090`；旧 Web Demo 已迁移到 `phantom-im-web-ui/`，不要继续启动旧路径。
