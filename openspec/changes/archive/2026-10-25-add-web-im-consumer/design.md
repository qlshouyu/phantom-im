## Context

现有 Web Demo 是 424 行原生 DOM 联调页，通过 URL 参数选择用户，并由 Vite 将 Phantom HTTP 路径代理到 `127.0.0.1:28080`。浏览器内保存 AppSecret 并直接签发 UserToken，不符合真实接入方形态。

`@phantom/sdk-web` 已支持分别配置 WebSocket 地址与 `httpBaseUrl`，HTTP 请求会携带 `Authorization: Bearer <UserToken>`、`X-App-Id`、`X-User-Id` 和 `X-Device-Id`。Phantom Gateway 当前通过单个 `app-id/app-secret` 配置验证用户 Token，因此第一版只支持单应用。

本设计以 `proposal.md` 的目标和两份 delta spec 的行为契约为前提，重点说明工程拆分、身份映射、会话、代理与前端状态组织。

## Goals / Non-Goals

**Goals:**

- 用真实接入方方式隔离 AppSecret、账号密码和 Phantom 业务数据。
- 让 React Web IM 通过 SDK 完成可重复、可观测的平台功能测试。
- 支持 BFF 多实例部署和共享登录态。
- 保持 `phantom-im-server` 的业务代码和数据 schema 不变。

**Non-Goals:**

- 不实现多 Phantom 应用、账号找回、邮箱/短信验证和第三方登录。
- 不实现生产级消息搜索、媒体转码或推送通道，这些继续复用 Phantom。
- 不把 WebSocket 经 BFF 转发，也不在 BFF 缓存聊天业务数据。
- 不为后置消息类型提前建设完整 UI 框架。

## Decisions

### 1. 双工程与单应用拓扑

前端从 `client/apps/web-demo` 移动到仓库根目录并重命名为 `phantom-im-web-ui/`，改造为 React + TypeScript + Vite。Java BFF 位于与前端平级的 `phantom-im-web-bff/`，使用 Spring Boot 4、JDK 21，并拥有自己的父 POM、构建和启动入口，不加入 `phantom-im-server` 聚合工程。

```text
Browser
  |
  +-- WSS + Protobuf ---------------------------> Phantom Gateway :28080/ws
  |
  +-- HTTP /api/auth/** ------------------------> Web IM BFF :28090
  +-- HTTP /api/phantom/** ---------------------> Web IM BFF :28090 --> Phantom HTTP :28080
                                                        |
                                                        +--> MySQL phantom_web_im
                                                        +--> Redis DB 1
```

目标仓库结构：

```text
phantom-im/
  phantom-im-server/
  client/
    packages/phantom-sdk-web/
  phantom-im-web-ui/
    package.json
    vite.config.ts
    playwright.config.ts
    src/
  phantom-im-web-bff/
    pom.xml
    src/main/java/
    src/main/resources/
```

`phantom-im-web-ui/` 作为独立 npm 工程，不再属于 `client/` workspace，并通过 `"@phantom/sdk-web": "file:../client/packages/phantom-sdk-web"` 引用 SDK 源码。Playwright 配置和测试随前端目录一起迁移。开发环境由 UI 的 Vite 代理 `/api` 到 BFF；生产环境由 BFF 同时提供 `phantom-im-web-ui/dist` 构建产物和同源 API。WS 地址通过 BFF 运行配置返回，前端仍直接连接 Phantom。

选择两个根目录平级工程而非 Phantom 子模块，是为了让 UI 与 BFF 的配套关系直观，并避免消费者与平台在版本、依赖、数据库权限和部署生命周期上互相绑定。代价是 UI 不再使用 `client/` workspace，需要单独安装依赖、构建和运行。

### 2. 账号与 Phantom ID 映射

在现有 MySQL 实例中创建独立 `phantom_web_im` schema，并为 BFF 配置只允许访问该 schema 的数据库账号。BFF 通过 HTTP 调用 Phantom OpenAPI，不进行跨 schema 查询。

核心表 `web_im_account`：

```text
account_id        CHAR(36)     主键，也是 external_user_id
app_id            VARCHAR(64)  第一版固定为配置中的应用 ID
login_name        VARCHAR(32)  应用内唯一
nickname          VARCHAR(64)
password_hash     VARCHAR(255)
phantom_user_id   VARCHAR(64)  唯一；开通完成前为空
status            VARCHAR(24)  PROVISIONING / ACTIVE / PROVISIONING_FAILED / DISABLED
created_at        BIGINT
updated_at        BIGINT
```

注册时先生成不可变 `account_id`，插入 `PROVISIONING` 账号，再调用幂等 OpenAPI 用户导入：

```text
external_user_id = account_id
nickname         = nickname
```

成功后保存 `phantom_user_id` 并更新为 `ACTIVE`；Phantom 调用失败则标记 `PROVISIONING_FAILED` 并向用户返回失败，后续用同一 `account_id` 重试不会创建重复用户。只有 `ACTIVE` 账号允许登录。

登录名使用数据库大小写不敏感唯一约束，带空白字符的输入先去除首尾空白。密码使用 Argon2id 带盐哈希；Argon2id 相比 BCrypt 更适合避免 72 字节输入限制，也便于后续调整内存和并行参数。

### 3. Redis 分布式 Session

BFF 使用 Spring Session Data Redis。现有 Redis 实例的 DB 0 已被 Phantom 使用，BFF 固定使用 DB 1，并通过 `phantom:web-im:session:` 命名空间隔离 Key。

- Cookie 名：`PHANTOM_WEB_IM_SESSION`。
- Cookie 属性：`HttpOnly`、`SameSite=Lax`、`Path=/`，HTTPS 环境追加 `Secure`。
- 7 天未活跃过期。
- 登录成功后调用 Session ID 轮换；退出时删除 Session 并清 Cookie。
- Session 只保存 `accountId`、`phantomUserId`、`loginName`、`nickname`、`authenticatedAt`。
- 密码哈希、AppSecret、Phantom UserToken 不进入 Session。
- Redis 故障时受保护接口失败关闭，不降级为匿名访问。

选择 Redis Session 而不是长期 JWT，是为了支持立即退出、服务端撤销和多实例共享；不选择 MySQL Session，是为了避免每次高频 API 请求访问关系型数据库。

### 4. Phantom UserToken

BFF 使用 Phantom AppID/AppSecret 和 JJWT 签发 HS256 UserToken，声明至少包含 `app_id`、`user_id`、`iat`、`exp`，默认有效期 15 分钟。Token 只在已登录 Web IM 会话存在时按需签发，不持久化到 MySQL 或 Redis Session。

前端在登录后获取 UserToken 并传给 `PhantomClient.login`。SDK 触发 `tokenExpired` 时，前端再次请求 Token，并使用新 Token 重新调用 `login()`。若 BFF 返回未认证，则清理前端 SDK 状态并回到登录页。

### 5. HTTP 路由与代理边界

BFF 对外提供以下接口契约：

```text
GET  /api/runtime-config         AppID、WS 地址、BFF 代理基地址
POST /api/auth/register          注册，不建立 Session
POST /api/auth/login             登录并设置 Session Cookie
POST /api/auth/logout            注销当前 Session
GET  /api/auth/me                当前账号与 Phantom 身份摘要
POST /api/auth/phantom-token     签发短期 Phantom UserToken
ALL  /api/phantom/**             代理 Phantom 用户面 HTTP API
```

响应沿用 `{ code, message, data, request_id }` 结构，错误使用稳定业务码和明确消息。注册接口的成功状态只表示本地账号和 Phantom 用户映射均已完成。

`/api/phantom/**` 去除 `/api/phantom` 前缀后转发到配置的 Phantom HTTP 地址。代理只允许 `/users`、`/friends`、`/blacklist`、`/groups`、`/conversations`、`/messages`、`/media`、`/devices`、`/push`、`/search`、`/reports`、`/appeals` 和 `/account` 下的用户面路径，禁止 `/openapi/**`。

代理要求有效 Web IM Session，并校验 `X-User-Id` 等于 Session 中的 `phantomUserId`。Phantom UserToken 由 SDK 通过 `Authorization` 头传入，BFF 原样转发并由 Phantom 最终验签。代理保留查询参数、请求体、响应状态、响应体和业务错误，不缓存聊天数据。

所有基于 Cookie 的状态变更接口校验同源 `Origin`/`Referer`，并只接受 JSON 或二进制请求，避免跨站表单触发敏感操作。

### 6. 注册与错误处理

注册输入与错误语义：

```text
nickname          1-64 字符
login_name        4-32 位，仅字母、数字、下划线
password          8-64 字符
password_confirm  必须与 password 一致
```

注册错误至少区分字段不合法、登录名已存在、Phantom 开通失败和服务暂时不可用。登录错误不区分账号不存在与密码错误。密码和 AppSecret 不进入应用日志；请求日志对 Authorization、Cookie、password 和 password_confirm 做脱敏。

### 7. React 前端结构

前端使用 React Router 管理注册、登录和主应用路由；使用 Zustand 保存跨页面账号、会话、当前聊天和诊断状态；样式使用 CSS Modules 或普通 CSS，不引入大型 UI 组件库。目录按业务能力划分：

```text
src/
  app/            路由、启动、运行配置
  features/auth/
  features/conversations/
  features/chat/
  features/contacts/
  features/groups/
  features/search/
  features/settings/
  features/diagnostics/
  services/       BFF API、SDK 适配、错误映射
  components/     通用布局、弹窗、菜单和空状态
```

所有 SDK 事件先进入单一 `sdk-adapter`，再更新 Zustand store，组件不直接订阅 SDK。消息列表按 `conv_seq` 排序，并以 `msg_id`/`client_msg_id` 合并乐观消息。输入草稿按会话防抖保存，切换会话前立即冲刷一次。

主界面采用三栏布局：主导航、列表区和聊天内容区。联系人、群聊、搜索与设置复用列表区和详情区，不通过顶部 Tab 堆叠完整页面。诊断面板作为右侧可收起的抽屉，不嵌入正常消息流。

### 8. 功能测试诊断

诊断面板仅消费 SDK 公开事件、当前前端状态和 BFF 响应，展示连接状态、Phantom ID、会话 ID、`user_seq`、消息 ID、客户端消息 ID、发送状态和可读错误日志。

测试入口：

- Token 过期：BFF 在测试模式返回已过期 Token，前端验证 SDK 回调和刷新链路。
- 断线重连：主动关闭 SDK 连接，验证指数退避、同步和消息补齐。
- 同端顶替：同账号在第二标签页登录相同 Web 设备类型，验证旧连接收到 Kicked。

测试能力不得调用 Phantom 内部接口或读取 Phantom 数据库。诊断 Token 接口只在显式启用测试模式时可用。

### 9. 验证策略

- BFF：JUnit 覆盖注册校验、账号状态、密码哈希、Session、Token claims、代理路径允许列表和身份一致性；集成测试连接真实 MySQL/Redis 验证迁移、Session 共享和 OpenAPI 失败重试。
- 前端：Vitest/React Testing Library 覆盖表单、store、消息去重和状态展示。
- E2E：Playwright 使用真实 Phantom、BFF、MySQL、Redis，覆盖注册后登录、双用户收发、HTTP 代理、重连补齐、Token 过期和同端顶替。
- 静态检查：前端 TypeScript 类型检查与生产构建，BFF Maven 测试和打包。

## Risks / Trade-offs

- [本地账号与 Phantom 用户开通无法共享事务] → 使用 `PROVISIONING` 状态、稳定 `external_user_id` 和幂等 OpenAPI 导入，失败时可安全重试。
- [同一 Redis 实例的 Phantom 与 BFF 可能互相影响] → BFF 固定 DB 1、独立前缀和独立连接配置；生产可通过配置切换到独立 Redis。
- [Redis 故障导致全部用户不可登录] → 受保护请求失败关闭，BFF 健康检查暴露 Redis 状态，不采用不安全的本地 Session 降级。
- [Cookie 会话带来 CSRF 风险] → 使用 `SameSite=Lax`、HTTPS `Secure`、同源校验和 JSON/二进制请求约束。
- [浏览器直连 WS 可能受网络或 TLS 配置影响] → 将 WS 地址纳入 BFF 运行配置并在健康检查/诊断面板中明确连接目标。
- [SDK 部分领域事件仍使用动态类型] → 在 `sdk-adapter` 集中做运行时归一化和类型收窄，避免各组件重复处理 `any`。
- [UI 通过本地文件依赖引用 SDK，可能误用旧安装产物] → 每次依赖变更后重新安装；CI 同时构建 SDK 和 UI，并在类型检查中直接解析 workspace 源码。
- [测试数据长期累积] → V1 为每次 E2E 生成唯一登录名；后续可增加仅依赖公开 OpenAPI 的测试数据清理工具。
- [直接使用同一 MySQL 实例仍存在运维耦合] → 使用独立 schema、独立数据库账号和独立迁移，禁止跨 schema 查询；未来可无代码切换到独立实例。

## Migration Plan

1. 创建 `phantom_web_im` schema、BFF 专用数据库账号和最小权限。
2. 部署 BFF 的 Flyway 迁移，创建账号表及必要索引。
3. 在现有 Redis 中确认 DB 1 可用，并配置 Session 命名空间。
4. 先启动 Phantom 单体，再以环境变量注入 AppID/AppSecret、MySQL、Redis 和 Phantom 地址，启动 BFF。
5. 将现有 Web Demo 迁移为 `phantom-im-web-ui/`，重新安装本地 SDK 依赖并构建 React 前端；开发环境使用 Vite，生产环境由 `phantom-im-web-bff/` 提供静态资源。
6. 运行 BFF 测试、前端类型检查、生产构建和 Playwright 端到端验证。

回滚时停止 Web IM 和 BFF 即可，Phantom 平台无需回滚。`phantom_web_im` 数据和 Redis DB 1 可保留用于问题分析；确认无需保留后再由运维单独清理。

## Open Questions

无会影响规格、实现方案或任务拆分的问题。密码找回、多应用和测试数据自动清理作为后续变更处理。
