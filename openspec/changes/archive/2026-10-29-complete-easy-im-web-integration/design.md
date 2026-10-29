## Context

`phantom-im/clients/web` 已是新的 React + Vite UI 壳，但 `App.tsx`、认证页和业务视图仍依赖本地状态与 `data.ts`，`package.json` 也尚未依赖 `@phantom/sdk-web`。Easy IM BFF 已具备注册登录、Redis Session、Cookie、运行时配置、短期 UserToken 和 Phantom HTTP 同源代理；Phantom Web SDK 已封装 WebSocket/Protobuf、HTTP API、同步、缓存和领域事件。当前缺口是把三者组合成可恢复、可观测、可测试的真实浏览器应用。

约束：浏览器不得接触 AppSecret；Web IM Session 使用 HttpOnly Cookie；Phantom UserToken 只保存在内存并由 BFF 短期签发；开发态 Vite 与 BFF 跨端口，生产态由 BFF 同源托管静态资源；真实联调依赖 MySQL、Redis、RocketMQ 和 Phantom 单体。

## Goals / Non-Goals

**Goals:**

- 以单一启动状态机完成运行时配置、Session 恢复、Token 获取、SDK 登录和业务首屏加载。
- 将当前 UI 的消息、通讯录、个人中心从模拟数据切换到真实 SDK/BFF/PaaS 数据，并覆盖加载、空、失败、重试和写操作反馈。
- 保留 SDK 的幂等、顺序、去重、重连和同步语义，不在 React 层重复实现消息协议。
- 建立可重复的双用户浏览器 E2E 和基础设施前置检查，作为“实际对接和联调”的完成标准。
- 对 BFF、SDK 和 Phantom 服务只做联调暴露出的契约/实现修复，不扩大平台产品范围。

**Non-Goals:**

- 不新增收藏夹、云空间、群文件或群相册等当前 PaaS 规格未覆盖的后端能力；对应 UI 入口显示暂未开放。
- 不实现 Tauri 桌面打包、离线推送服务端、移动端或新的 Phantom 业务域。
- 不把 Phantom UserToken、AppSecret 或密码哈希持久化到浏览器存储。
- 不以网络 Mock 作为全链路验收证据；Mock 仅用于组件单测和异常分支单测。

## Decisions

### 1. 前端采用三层适配结构

- `BFF API 层`：统一处理 `credentials: include`、`ApiResponse` 解包、字段错误、`request_id`、401 会话失效和运行时配置。
- `SDK 适配层`：持有 `PhantomClient` 单例，暴露连接状态机、Token 刷新、领域事件和类型化业务方法；所有 HTTP 请求通过 `httpBaseUrl = /api/phantom` 进入 BFF 代理。
- `页面状态层`：认证上下文保存 Session 账号与连接状态，领域 hooks/状态负责会话、消息、联系人、群组、资料等加载和乐观更新展示。

这样可避免页面组件直接依赖 SDK 的 snake_case 响应或 WebSocket 细节，同时保持协议语义集中在 SDK。替代方案是让页面直接调用 SDK，改动小但会造成状态重复、错误处理和类型转换散落。

### 2. 使用显式启动状态机

应用启动顺序为：`bootstrapping` → `unauthenticated` 或 `connecting` → `syncing` → `ready`；错误进入可重试状态。启动时先请求 `/api/runtime-config`，再请求 `/api/auth/me`；Session 有效时调用 `/api/auth/phantom-token`，用返回的 AppID、WebSocket URL、代理基地址、Phantom ID 和短期 Token 创建或复用 SDK 客户端。SDK `tokenExpired` 时仅允许一个刷新请求在途，刷新成功后重新登录 SDK；401 时终止重连并回到登录页。

不使用 `localStorage` 保存 Token，因为 HttpOnly Session 已能完成刷新恢复，持久化 JWT 会扩大泄露面。替代方案是页面刷新后重新引导用户登录，但会破坏 7 天 Session 的产品语义。

### 3. 真实数据替换以 UI 领域为边界

- 认证与个人资料：BFF `/api/auth/*`、`/api/runtime-config`，SDK 资料、二维码和设备 API。
- 消息：SDK `listConversations`、`getMessages`、`history`、`sendText/sendMessage`、`sendMedia`、`recall`、`forward`、`deleteLocal`、`markRead` 和草稿 API。
- 通讯录：SDK 好友、申请、黑名单、分组、群组、成员、公告和禁言 API。
- 搜索：SDK 全局搜索、联想、会话内搜索及消息定位。
- 实时状态：SDK `presence`、`typing`、`message`、`sync`、`conversation`、`relation`、`group`、`profile` 事件。

页面只消费适配层返回的稳定领域模型。服务端字段缺失或契约不一致时在适配层集中修正并增加契约测试；禁止在多个页面分别兼容同一字段。

### 4. 开发与生产使用同一 API 路径

Vite 开发服务器将 `/api` 代理到 `http://127.0.0.1:28090`，保留浏览器同源路径；生产构建由 BFF 静态托管，仍使用 `/api`。SDK 的 WebSocket URL 来自 BFF 运行时配置，HTTP 基地址固定通过 `/api/phantom` 代理，避免 SDK 默认从 WebSocket URL 推导出 Phantom 直连 HTTP 地址。

不通过放宽 BFF CORS 让前端直接请求 Phantom；该方案会绕过同源 Session/身份一致性边界。

### 5. UI 操作以真实结果为准

读取操作使用明确的 loading/empty/error 状态。写操作采用“请求中 → 成功或失败”；消息可保留 SDK 的乐观状态，但非消息业务不得先显示成功 Toast。媒体发送使用浏览器文件选择、SDK 分片上传进度和失败原因；收藏夹、云空间等无契约入口只展示不可用状态。

### 6. 测试分层并保留真实链路门禁

- 单元测试：BFF 响应解包、字段映射、启动状态机、Token 刷新去重、错误呈现。
- BFF 集成测试：登录 Cookie、Session 恢复、Token、代理身份校验、上游错误透传。
- SDK 回归：现有 Vitest、类型检查和生成 proto bundle。
- Playwright 全链路：启动真实基础设施和三个服务，使用两个独立浏览器上下文完成注册、登录、好友、单聊、群聊、刷新、重连和异常场景。

全链路测试等待可观察的 ACK、同步事件或服务端查询结果，不以固定长延时作为主要同步手段；测试账号包含随机后缀，避免重复数据互相污染。

## Risks / Trade-offs

- [SDK/BFF/Phantom 字段或路径存在未发现的不一致] → 先建立契约冒烟测试，将映射集中在适配层；联调失败时修复 SDK/BFF/服务端契约并补回归测试，不在页面打补丁。
- [开发态 Vite 代理与生产同源托管行为不同] → 两套启动方式都执行登录、Token、代理和 WebSocket 冒烟，Playwright 至少覆盖生产式静态托管或等价同源配置。
- [双用户实时 E2E 易受异步与共享数据影响] → 使用独立浏览器上下文、唯一账号、显式等待同步条件，并在失败时保留 trace、截图和服务日志。
- [大范围替换模拟数据会同时暴露多个未完成接口] → 按认证、单聊、通讯录/群组、资料/设置、搜索/诊断分批合并；每批必须保持应用可运行，最终统一执行全链路门禁。
- [浏览器通知、推送 Token 在本地环境能力有限] → 权限和 Token 注册使用能力探测；不支持时展示明确状态，不阻塞核心聊天验收。
- [SDK 本地缓存与新 UI 状态可能重复] → SDK 作为消息与游标真相源，React 只维护视图状态和适配后的领域快照，避免二次持久化。

## Migration Plan

1. 先配置 Vite 代理与前端依赖，完成运行时配置、Session 恢复、登录、Token 和 SDK 连接；未登录时仍保留现有认证视觉。
2. 按消息、通讯录/群组、个人中心/设备、搜索/诊断逐步切换数据源，每步删除对应模拟路径并补单元测试。
3. 在本地基础设施上执行 BFF、SDK 和双用户 Playwright 联调，记录通过命令、截图和日志。
4. 生产构建由 BFF 托管，验证 Cookie、同源代理、WebSocket、刷新恢复和退出登录；回滚时同时回退 Web 静态包和 BFF 版本，数据库无新增迁移。
