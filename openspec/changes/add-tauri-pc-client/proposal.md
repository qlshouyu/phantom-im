## Why

Phantom IM 目前只有浏览器消费端，无法以独立桌面应用安装、启动和三平台分发。通过 Tauri 2 复用现有 React Web 客户端，可以在不维护第二套 UI 的前提下补齐 Windows、macOS、Linux PC 客户端，并以 macOS 作为主开发与日常验收平台。

## What Changes

- 在 `phantom-im/clients/pc/` 新增 Phantom IM Rust + Tauri 2 桌面工程，产品名固定为 `Phantom IM`。
- 桌面端内置并复用现有 `phantom-im/clients/web`，不复制或分叉业务页面；构建时先执行 Web 生产构建，再将 `dist` 产物复制到 Tauri/Rust 资源目录并随安装包分发。
- Rust 侧提供仅监听回环地址的本地 Web 网关：静态托管内置 Web 资源，并将 `/api/*` 转发到可配置的 Easy IM BFF，保持现有相对路径、HttpOnly Session、请求体和错误语义。
- 提供 Phantom IM 桌面窗口、平台标识和原生窗口控制接入，默认使用现有 Web 客户端设计的 1280×820、最小 1080×700 原生标题栏窗口。
- 增加 Windows、macOS、Linux 构建与安装包配置；macOS 为主开发平台，三个平台均作为正式交付验收目标。
- 补充桌面构建、运行、跨平台验证和故障排查文档，并增加针对本地网关、资源打包和桌面启动的测试。

### Non-goals

- 不复制、重写或分叉现有 Web IM 页面、领域逻辑和 SDK 适配层。
- 不把 Phantom 单体、MySQL、Redis、RocketMQ 或 Easy IM BFF 打包进桌面应用。
- 不提供离线聊天、离线 BFF 能力或云端不可达时的本地消息数据库。
- 不在本变更中新增 IM 业务能力，也不改变 Phantom 平台、Web SDK、BFF 或现有浏览器客户端的既有行为。
- 不包含代码签名、macOS 公证、自动更新和发布商店上架流程；这些可作为后续独立变更。

## Capabilities

### New Capabilities

- `desktop-im-consumer`: 定义 Phantom IM Tauri 桌面客户端的 Web 资源复用、本地 BFF 网关、登录态、三平台打包与运行行为。

### Modified Capabilities

无。现有 `web-im-consumer-web` 和 `web-im-consumer-bff` 的行为契约保持不变，桌面端通过兼容的本地同源网关复用它们。

## Impact

- 新增 `phantom-im/clients/pc/`：Rust/Tauri 入口、桌面配置、Web 构建与资源复制脚本、本地回环网关、测试和桌面文档。
- 复用 `phantom-im/clients/web/` 的生产构建产物；若桥接确需扩展平台注入点，只允许做不改变浏览器行为的兼容修改。
- 运行期依赖可配置的 Easy IM BFF 和 Phantom 服务；安装包内含 Web UI，但不内含 BFF 或 Phantom。
- 新增 Node/npm、Rust/Cargo 与 Tauri 2 CLI 构建依赖，并分别使用 Windows、macOS、Linux 原生环境生成安装包。
- 新增向 BFF 转发 `/api/*` 的本地网络路径，需要限制监听地址、目标地址、路径范围和跨站来源。
- 新增 GitHub-hosted Windows/Linux x64 CI 构建与安装启动冒烟；不包含真实登录、代码签名、公证或自动更新。
- 需要更新仓库根目录与 `phantom-im/README.md` 的目录、构建和验证说明。
