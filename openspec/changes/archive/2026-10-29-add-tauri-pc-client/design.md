## Context

现有 `phantom-im/clients/web` 是 React 19 + TypeScript + Vite 消费端，已经通过相对路径 `/api` 访问 Easy IM BFF，并由 `@phantom/sdk-web` 直连 Phantom WebSocket。它已有浏览器/Tauri 平台适配，但仓库中尚无 `src-tauri`、桌面构建链路或安装包配置。

Easy IM BFF 的登录态依赖 `HttpOnly` Session Cookie，Phantom HTTP 请求依赖同源 `/api/phantom/**` 代理，媒体上传还包含二进制请求体。桌面端若直接加载 `tauri://localhost` 并对 BFF 发起跨源 fetch，会破坏 Cookie、CORS 和现有多数相对路径假设，因此不能只增加一个 Tauri 壳。

本设计采用内置 Web 资源加 Rust 本地同源网关。它比复制一套桌面 UI 或改造所有 BFF/SDK 调用更窄，也避免让 Web UI 直接持有 Session 或服务端秘密。

## Goals / Non-Goals

**Goals:**

- 用同一份 Web 构建产物运行浏览器和桌面端，桌面工程不维护业务页面。
- 在桌面安装包内提供完整 UI，不依赖 BFF 托管静态文件。
- 保持现有 `/api/*` 相对路径、BFF Session、SDK HTTP 代理和二进制上传语义。
- 用一个可测试、只监听回环地址的网关隔离 Web 资源托管与 BFF 转发。
- 在 macOS 完成主开发链路，并具备 Windows、Linux 原生打包配置。

**Non-Goals:**

- 不改写现有 IM 页面、领域模型、SDK 协议或 BFF 业务实现。
- 不在桌面应用中缓存聊天数据或提供离线模式。
- 不代理 Phantom WebSocket；WebSocket 仍由 BFF 运行时配置直接连接到 Phantom Gateway。
- 不实现签名、公证、自动更新或应用商店发布。

## Decisions

### 1. 工程布局与构建链路

新增 `phantom-im/clients/pc/`，其中包含轻量 npm 构建脚本、`src-tauri/` Rust/Tauri 工程和桌面文档。产品名及窗口标题为 `Phantom IM`，包标识使用稳定的 `im.phantom.desktop` 一类反向域名标识。

桌面构建顺序固定为：

1. 在 `clients/web` 执行现有 `npm run build`，包含 TypeScript 检查和 Vite 生产构建。
2. 用跨平台 Node 脚本清空并重建 `clients/pc/src-tauri/resources/web/`。
3. 完整复制 `clients/web/dist/` 到该资源目录。
4. Tauri 将资源目录打入安装包，并完成 Rust/Tauri 构建。

选择 Node 复制脚本而不是 shell 的 `cp`/`xcopy`，避免 Windows、macOS、Linux 命令差异。复制脚本任一阶段失败必须非零退出，防止 Tauri 继续打包旧资源。

开发态优先保留 Vite HMR：Tauri `beforeDevCommand` 启动现有 Web 开发服务器，窗口加载 `http://127.0.0.1:5173`，继续使用 Vite 的 `/api` 代理。生产态加载 Rust 回环网关。两条路径都必须覆盖登录和 SDK HTTP 请求，不能只验证 HMR 开发态。

备选“Tauri 直接加载 BFF 托管页面”被否决，因为它依赖 BFF 静态托管且无法满足安装包内置 Web UI 的要求。备选“复制/分叉 React 源码到桌面目录”被否决，因为会产生两套 UI 和长期漂移。

### 2. 回环同源网关

生产态 Rust 启动仅绑定回环地址的本地 HTTP 网关，使用随机可用端口和稳定的 `phantom-im.localhost` 主机名，并在端口就绪后创建主窗口。稳定主机名使 WebView Cookie 在应用重启后仍按主机保留，随机端口避免固定端口冲突。

网关路由分为：

- `/api/*`：转发到配置的 Easy IM BFF；
- 其他路径：从安装包资源目录读取 Web 静态资源，未知无扩展名路径回退到 `index.html`，支持 SPA 路由。

网关以流式方式转发请求和响应，保留 HTTP 方法、查询参数、请求头中的必要字段、二进制请求体、响应状态和多值 `Set-Cookie`。上游目标只来自启动配置，不接受请求参数中的 URL。转发前拒绝点路径、非 `/api/` 路径和非法目标，避免变成开放代理。

WebSocket 不经本地网关。BFF 返回的 `ws_url` 仍由现有 SDK 直接连接；Phantom 生产环境需要使用 `wss://`。

备选“扩展前端 fetch 适配器并通过 Tauri IPC 执行所有请求”被否决为第一阶段主方案：它需要重新实现 Fetch 的二进制、流和取消语义，并额外解决 Cookie 跨应用重启持久化，改动面大于本地同源网关。若本地网关在某个 WebView 平台出现不可接受限制，再以独立变更评估 IPC 方案。

### 3. Session Cookie 与来源校验

WebView 的门户始终是本地网关来源，因此现有 Web 客户端不需要改 `/api` 基地址。BFF 的 `Set-Cookie` 由网关转发给 WebView，后续请求再由 WebView 自动通过 Cookie 头送回网关并转发到 BFF。

当 BFF 使用 HTTPS 且 Cookie 带 `Secure` 时，本地网关只对回环来源重写 `Set-Cookie`：保留 Cookie 名、值、`HttpOnly`、`SameSite`、`Path` 和有效期，移除仅阻止本机 HTTP WebView 保存的 `Secure`/不兼容 `Domain` 属性。上游请求仍经 HTTPS，服务端 Session 仍为权威；Cookie 不对页面 JavaScript 开放。该重写只允许发生在回环网关，不改变浏览器直连 BFF 的生产行为。

对状态变更请求，网关校验 `Origin` 必须等于当前本地应用来源；缺失 `Origin` 的导航或同源 GET 可按允许列表处理。转发到 BFF 时将 `Origin`/`Referer` 改写为配置的 BFF 来源，以继续满足 BFF 的 `SameOriginFilter`。部署文档必须说明 `WEB_IM_ALLOWED_ORIGINS` 需要包含实际 BFF 公网来源。

BFF URL 只接受无用户信息、无查询和无片段的 `http`/`https` 绝对地址。默认本地值为 `http://127.0.0.1:28090`，发布运行时可通过环境变量或桌面配置文件覆盖；不得把 BFF 凭据或服务端环境变量写入安装包。

### 4. Tauri 页面权限与安全响应头

生产页面由应用自己的本地网关提供，不属于 Tauri 内置资源来源。Tauri 能力配置只向主窗口开放现有 Web UI 必需的 `core:window` 权限，并限制可访问来源为本地网关，不开放文件系统、Shell、任意 HTTP 或任意 IPC 权限。

本地网关为静态响应设置限制性 CSP：默认只允许自身资源、`data:`/`blob:` 媒体和必要的 `ws:`/`wss:` 连接；不允许任意脚本来源。若实现需要 Tauri 远程页面 IPC，必须使用最小能力集合并通过三平台冒烟确认页面仍被识别为 Tauri 环境；若无法安全启用，则改为由网关页面注入只读桌面标识并保持原生标题栏操作，不扩大通用 IPC 权限。

窗口使用原生标题栏、可调整大小、默认 1280×820、最小 1080×700、居中。现有 `src/platform/tauri.ts` 的窗口控制接口保持不变。

### 5. 版本锁定与交付

实现开始时从官方 npm 与 crates.io 元数据确认当前最新的 Tauri 2 稳定版，不使用预发布版；将 Tauri CLI、`@tauri-apps/api`、Rust `tauri` 及官方插件锁定到同一兼容版本线，并提交 `package-lock.json` 与 `Cargo.lock`。仓库当前无法在规划阶段联网确认精确补丁版本，因此不在设计文档中臆测版本号。

Tauri 安装在目标操作系统原生构建，不做非受支持的跨平台交叉编译。macOS 为主开发平台并完成真实业务链路验收；Windows、Linux 使用 GitHub-hosted x64 runner 验证构建、安装、启动和内置网关首页，不要求在 CI 中完成真实 BFF 登录或消息发送。CI 只生成未签名产物，签名、公证和自动更新留作后续变更。

安装包目标至少包括：Windows 的 MSI/NSIS 安装包、macOS 的 `.app`/DMG、Linux 的 AppImage/DEB（以 Tauri 当前稳定版实际支持组合为准）。签名和公证信息留空不影响未签名开发包构建。

## Risks / Trade-offs

- [本地 HTTP WebView 页面无法使用 Tauri IPC 或无法被 `isTauri()` 识别] → 将远程页面能力限制到主窗口必需权限并做三平台启动冒烟；若平台行为不一致，使用本地网关注入桌面标识作为兼容层，不扩大通用 IPC。
- [转发 `Secure` Session Cookie 到本地 HTTP 来源会降低本机 Cookie 属性] → 仅允许回环随机端口网关进行最小属性重写，保留 `HttpOnly`/`SameSite`，并确保到 BFF 的传输仍使用配置的 HTTPS。
- [网关成为 SSRF 或任意 URL 代理] → 目标 URL 只来自启动配置，路由只允许 `/api/`，拒绝路径穿越和跨站变更请求，并增加负向集成测试。
- [BFF 的多值 Cookie、二进制上传或错误响应在转发中丢失] → 使用流式代理和原始状态/头转发，测试多 `Set-Cookie`、分片二进制、204、4xx/5xx 与超时。
- [内置资源目录在开发态和安装态路径不同] → 通过 Tauri 资源解析 API 统一定位，测试源目录和打包资源两种模式，并检查最终安装包确含 `index.html`。
- [随机端口或 `.localhost` 在某个平台不可用] → 在启动阶段选择可用端口并保留 `127.0.0.1` 回退来源，三平台启动测试覆盖名称解析和 Cookie 恢复。
- [macOS 开发环境无法代替 Windows/Linux 原生验收] → 明确要求对应原生 runner 构建与运行冒烟，不以 macOS 交叉构建结果替代。
- [Tauri 2 依赖快速更新导致 CLI、Rust crate 与 JS API 不兼容] → 实现时一次锁定精确版本并在文档记录，升级通过独立变更执行。

## Migration Plan

1. 先建立最小 Tauri 工程、Web 构建复制脚本和单窗口启动，确认 macOS 开发态与生产资源加载。
2. 接入回环网关、静态资源托管和 BFF 转发，完成登录、Session 恢复、Token、`/api/phantom` 与媒体上传冒烟。
3. 增加安全边界测试、Tauri 能力配置和三平台打包配置，完善构建文档。
4. 在 macOS 完成真实业务链路验收；Windows、Linux 通过 GitHub-hosted x64 CI 生成 MSI/NSIS/DEB/AppImage，并在全新 runner 上完成安装、启动和本地网关首页检查。
5. 采用新目录和独立安装包发布，无数据库或 BFF 迁移。回滚时停止分发桌面产物即可，现有 Web 客户端和 BFF 不受影响。
