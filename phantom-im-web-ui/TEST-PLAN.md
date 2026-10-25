# Web IM 测试方案

Web IM 是 Phantom IM 的真实消费方：React UI 调用 BFF 获取账号、Session 和短期 UserToken，再通过 `@phantom/sdk-web` 直连 Phantom WebSocket，并通过 BFF 代理 HTTP API。

## 自动化分层

| 层级 | 命令 | 覆盖 |
|---|---|---|
| UI 单元/组件 | `npm test` | 注册字段校验、Zustand store、消息去重、诊断面板 |
| 类型与构建 | `npm run typecheck && npm run build` | 严格 TypeScript、React 路由与生产包 |
| BFF 单元 | `cd ../phantom-im-web-bff && mvn -s .mvn/settings.xml test` | 注册校验、状态流转、Session、JWT、OpenAPI 签名、代理白名单 |
| 真实 E2E | `npm run e2e` | 注册登录、好友、单聊、媒体、代理、Token 刷新、重连、同端顶替 |

## E2E 前置条件

- Phantom 单体运行于 `127.0.0.1:28080`，MySQL/Redis/RocketMQ 健康。
- BFF 运行于 `127.0.0.1:28090`，`WEB_IM_TEST_MODE=true`。
- `phantom_web_im` 已由 Flyway 初始化。
- Playwright 使用系统 Google Chrome（`channel: chrome`）。

E2E 每次生成唯一登录名和消息文本，避免历史数据串扰。Token 过期场景通过 BFF 测试接口签发已过期 Token，再验证 SDK 回调、Token 刷新和新连接恢复。

## 手动验收

1. 注册两个账号，确认注册成功返回登录页且注册期间没有 WebSocket 连接。
2. 两个浏览器上下文分别登录，确认连接状态为“在线”。
3. A 通过 Phantom ID 添加 B，B 接受后双方进入同一单聊。
4. 发送文本和图片，确认双方气泡状态从发送中推进到已送达。
5. 打开诊断，依次验证 Token 过期、主动断开重连、同端顶替。
6. 确认诊断面板只展示 SDK/BFF 公开事件，不读取 Phantom 数据库或内部接口。
