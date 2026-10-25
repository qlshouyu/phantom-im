## Why

现有 `web-demo` 是以参数切换身份、按 Tab 堆叠接口操作的联调页面，无法以真实聊天工具形态持续验证 Phantom IM PaaS。需要一个独立部署、真实消费 Web SDK 的 Web IM，并通过接入方自己的 Java BFF 承担注册、登录、UserToken 签发和 HTTP 代理，以验证平台在接近生产接入方式下的完整能力。

## What Changes

- 将现有 Web Demo 目录移动到仓库根目录并重命名为 `phantom-im-web-ui/`，改造为 React + TypeScript + Vite 的 Web IM 聊天工具。
- 新增与 `phantom-im-web-ui/` 平级的独立 Maven 工程 `phantom-im-web-bff/`，作为 Web IM 的接入方账号服务与 BFF，不加入 `phantom-im-server` 聚合工程。
- `phantom-im-web-ui/` 作为独立 npm 工程，通过本地文件依赖引用 `client/packages/phantom-sdk-web`。
- 新增独立 MySQL schema `phantom_web_im`，保存 Web IM 账号、密码哈希及 Phantom ID 映射；BFF 不跨库读取 Phantom 业务数据。
- 新增注册、登录和退出流程。注册仅需要昵称、登录名、密码和确认密码；注册成功后不自动登录。
- 使用 Spring Session + Redis 分布式 Session，Web IM BFF 使用独立 Redis DB 与命名空间。
- 第一版只服务一个 Phantom 应用，AppID/AppSecret、Phantom HTTP 地址和 WebSocket 地址由 BFF 配置提供。
- 浏览器 WebSocket 直连 Phantom Gateway，HTTP/JSON 请求经 Web IM BFF 代理到 Phantom，并从 BFF 获取短期 Phantom UserToken。
- Web IM 第一版覆盖会话、单聊、群聊、常用消息操作、联系人、搜索、设备、通知和测试诊断能力。
- 新增面向平台功能验证的诊断面板，展示连接、同步、错误和测试场景入口，但不绕过 Phantom 公开 SDK/OpenAPI 契约。

### Non-goals

- 不修改 Phantom 平台的消息、会话、关系、群组和安全业务实现。
- 不支持多个 Phantom 应用、应用切换或跨应用账号关联。
- 不在第一版实现语音录制、视频、位置、名片、合并转发、举报申诉和数据导出。
- 不建设生产级第三方登录、短信、邮箱验证或找回密码能力。
- 不通过数据库或内部接口直接读取 Phantom 状态来伪造测试结果。

## Capabilities

### New Capabilities

- `web-im-consumer-bff`: Web IM 消费者账号、注册登录、Redis 分布式 Session、Phantom 用户开通与令牌签发、HTTP 代理和单应用运行配置。
- `web-im-consumer-web`: React Web IM 的聊天产品交互、会话与消息、联系人、群聊、搜索、设置和功能测试诊断。

### Modified Capabilities

无。现有 `web-sdk` 及其他 Phantom 平台能力由 Web IM 直接消费，不改变其行为契约。

## Impact

- 前端：将现有 Web Demo 移动到 `phantom-im-web-ui/`，替换页面结构和状态管理，引入 React 相关依赖，并调整 Playwright 测试和 SDK 本地依赖。
- 服务端：新增独立 `phantom-im-web-bff/` Spring Boot 工程及构建、运行、测试配置。
- 数据：在现有 MySQL 实例中新增隔离的 `phantom_web_im` schema；在现有 Redis 实例中新增隔离 Session DB 和 key 前缀。
- 外部接口：新增 BFF 注册、登录、退出、运行配置、Phantom Token 和 HTTP 代理接口。
- 平台：WebSocket 直连 Phantom，HTTP 调用使用现有 Phantom SDK/OpenAPI 契约；平台源码无需因本变更调整业务行为。
- 运维与验证：新增 BFF 启动参数、AppSecret 注入方式、健康检查、开发启动说明和端到端测试入口。
