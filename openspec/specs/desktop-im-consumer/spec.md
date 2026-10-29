# desktop-im-consumer Specification

## Purpose

定义 Phantom IM 在 Windows、macOS 和 Linux 上以 Tauri 桌面应用运行时的产品身份、内置 Web UI、同源 BFF 连接、登录态保持、平台窗口行为与安装包交付契约。

## Requirements

### Requirement: Phantom IM 桌面应用身份

桌面客户端 SHALL 以 `Phantom IM` 作为应用产品名称、窗口标题和应用标识展示，MUST 作为可独立安装和启动的桌面应用运行。首期正式支持 Windows、macOS 和 Linux，其中 macOS SHALL 作为主开发与日常验收平台。

#### Scenario: 启动桌面应用
- **WHEN** 用户启动已安装的 Phantom IM
- **THEN** 系统应用名称和主窗口标题显示为 `Phantom IM`，并进入现有 Web IM 的启动流程

#### Scenario: 三平台安装包可运行
- **WHEN** 分别在 Windows、macOS 和 Linux 原生环境安装对应构建产物
- **THEN** 三个平台均能启动 Phantom IM 并完成登录前界面加载

#### Scenario: macOS 主开发链路可用
- **WHEN** 开发者在 macOS 上执行项目文档给出的开发命令
- **THEN** 能够启动桌面客户端并连接本地 Easy IM BFF 进行日常联调

### Requirement: 内置 Web UI 复用与构建

桌面安装包 MUST 包含现有 `phantom-im/clients/web` 的生产构建产物，并 MUST 复用同一套 React 页面、领域逻辑、Phantom Web SDK 适配和交互行为，不得在桌面工程中维护第二套业务 UI。桌面构建流程 SHALL 先构建 Web，再将完整 `dist` 资源复制到 Tauri/Rust 资源目录；任一复制或 Web 构建步骤失败时 MUST 终止桌面打包。

#### Scenario: 安装后无需 BFF 托管页面
- **WHEN** 用户安装 Phantom IM 且 Easy IM BFF 可达但未提供静态 UI
- **THEN** 桌面应用仍从安装包内置资源加载完整 Web UI，并可通过 BFF API 登录和收发消息

#### Scenario: Web 构建参与桌面打包
- **WHEN** 执行桌面生产打包命令
- **THEN** 命令先完成 Web 类型检查和生产构建，再把生成的 `dist` 内容复制到桌面资源目录后继续 Rust/Tauri 打包

#### Scenario: Web 构建失败阻止打包
- **WHEN** Web 类型检查、生产构建或资源复制失败
- **THEN** 桌面打包以非零状态结束，且不生成被误认为有效的安装包

### Requirement: 同源 BFF 连接与登录态

桌面应用 SHALL 通过仅监听回环地址的本地同源网关向用户提供内置 UI，并将 `/api/*` 请求转发到配置的 Easy IM BFF。登录、Session 恢复、Phantom UserToken 获取、退出和 `/api/phantom/**` 代理 MUST 保持现有 Web 客户端语义。BFF Session MUST 继续作为 HttpOnly 凭据，MUST NOT 暴露给页面 JavaScript 或写入 `localStorage`。

#### Scenario: 使用内置 UI 登录
- **WHEN** 用户在内置 Web UI 提交有效账号和密码
- **THEN** 本地网关把登录请求转发到 BFF，客户端保存 HttpOnly Session，并继续获取 Phantom UserToken 和建立 SDK 连接

#### Scenario: 冷启动恢复 Session
- **WHEN** 用户已登录后关闭并重新启动 Phantom IM，且 BFF Session 仍有效
- **THEN** 应用无需再次输入密码即可恢复登录并进入会话同步流程

#### Scenario: SDK HTTP 请求经过 BFF
- **WHEN** 内置 Web UI 的 Phantom SDK 请求 `/api/phantom/**`
- **THEN** 请求经本地同源网关转发到 BFF，并保留原有方法、查询参数、请求体、响应状态和业务错误

#### Scenario: 退出登录
- **WHEN** 用户在内置 Web UI 执行退出登录
- **THEN** 本地 Session 和 BFF Session 均失效，后续受保护请求返回未认证

### Requirement: 桌面窗口与平台集成

Phantom IM SHALL 使用系统原生标题栏，并默认创建可调整大小的 1280×820 主窗口，最小尺寸为 1080×700，启动时居中。桌面运行时 SHALL 被现有 Web 客户端识别为 Tauri 环境，并保留平台窗口最小化、最大化、关闭和拖拽行为。

#### Scenario: 默认窗口尺寸
- **WHEN** 用户在未保存自定义窗口状态的情况下首次启动
- **THEN** 主窗口以 1280×820 居中显示，且不能缩小到 1080×700 以下

#### Scenario: 原生窗口操作
- **WHEN** 用户使用系统标题栏或页面提供的桌面窗口控制
- **THEN** 最小化、最大化、还原、关闭和拖拽操作作用于 Phantom IM 主窗口

#### Scenario: 平台差异可识别
- **WHEN** 内置 Web UI 在 Windows、macOS 或 Linux 桌面应用内运行
- **THEN** 客户端能够识别当前桌面运行时和操作系统，不降级为浏览器运行时行为

### Requirement: 本地网关安全边界

本地网关 MUST 仅绑定回环地址，MUST 只转发允许的 BFF API 路径，MUST 阻止把任意 URL 或 OpenAPI 管理路径作为转发目标。网关 MUST 校验请求来源，拒绝来自非应用页面的跨站状态变更请求，并 MUST 在 BFF 不可达或响应无效时返回可识别的失败结果。安装包 MUST NOT 包含或向 Web UI 暴露 BFF AppSecret、Phantom AppSecret 或服务端环境变量。

#### Scenario: 拒绝任意代理目标
- **WHEN** 请求尝试通过本地网关访问外部 URL、非 `/api/*` BFF 路径或 OpenAPI 管理路径
- **THEN** 网关拒绝请求且不向目标地址发送数据

#### Scenario: 拒绝跨站状态变更
- **WHEN** 非 Phantom IM 页面来源向本地网关发起 POST、PUT、PATCH 或 DELETE 请求
- **THEN** 网关拒绝请求且不转发到 BFF

#### Scenario: BFF 不可达
- **WHEN** 已配置的 Easy IM BFF 无法连接或超时
- **THEN** 桌面 UI 收到明确的网络失败错误，不伪造成功响应或静默切换到离线数据

#### Scenario: 安装包不含服务端秘密
- **WHEN** 检查 Phantom IM 安装包资源和运行中的页面状态
- **THEN** 不存在 BFF AppSecret、Phantom AppSecret 或服务端环境变量，页面也无法读取 BFF Session Cookie

### Requirement: 三平台构建与交付

桌面工程 SHALL 为 Windows、macOS 和 Linux 分别生成可安装的 Tauri 2 构建产物，并锁定相互兼容的 Tauri 2 最新稳定版本、Rust 依赖和前端 Tauri API 版本。构建文档 MUST 说明各平台的系统依赖、开发命令、生产打包命令、BFF 地址配置和验证步骤。

#### Scenario: macOS 主平台打包
- **WHEN** 开发者在 macOS 主开发环境执行生产打包
- **THEN** 生成可安装的 macOS 应用产物，并完成内置 Web UI 与 BFF 登录冒烟验证

#### Scenario: Windows 与 Linux 产物
- **WHEN** 在 Windows 和 Linux 原生环境分别执行生产打包
- **THEN** 各平台生成对应的可安装产物，且产物使用同一 Web 构建输入和桌面行为契约

#### Scenario: Tauri 版本可审计
- **WHEN** 检查桌面工程的锁文件、依赖清单和构建日志
- **THEN** 可确认实际使用的 Tauri 2 CLI、Rust crate 与前端 API 版本，且不存在 Tauri 1 依赖
