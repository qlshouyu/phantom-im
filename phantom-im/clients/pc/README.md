# Phantom IM PC 客户端

Phantom IM 的 Tauri 2 桌面客户端，复用 `../web` 的 React 生产构建产物。桌面工程不维护第二套业务页面、领域逻辑或 SDK 适配器。

## 架构

```text
Tauri WebView
  └── http://phantom-im.localhost:<随机端口>/
        ├── 静态资源 -> src-tauri/resources/web/
        └── /api/*    -> 可配置的 Easy IM BFF
```

生产态 Rust 只监听 `127.0.0.1`，启动随机回环端口并创建 `Phantom IM` 主窗口。BFF Session Cookie 仍为 `HttpOnly`，由 WebView 保存并在后续请求中自动回传。Phantom WebSocket 不经本地网关，继续由 BFF 下发的 `ws_url` 直连 Phantom。

## Tauri 版本

实现于 2026-10-27 查询官方 registry：

- `@tauri-apps/cli`: `2.12.4`
- `@tauri-apps/api`: `2.12.4`（现有 `../web` 依赖）
- Rust `tauri`: `2.12.5`（`Cargo.lock` 固化实际解析版本）
- Rust `tauri-build`: `2.7.4`（`Cargo.lock` 固化实际解析版本）

这些版本属于 Tauri 2 稳定线，不使用 `3.0.0-alpha` 等预发布版本。升级 Tauri 版本时应同步更新 CLI、前端 API、Rust crate 和两个锁文件。

## 前置要求

- Node.js 与 npm
- Rust stable 与 Cargo
- macOS：Xcode Command Line Tools
- Windows：Microsoft C++ Build Tools 与 WebView2 Runtime
- Linux：Tauri 2 官方要求的 WebKitGTK、GTK、AppIndicator、librsvg、patchelf 等系统依赖
- 已启动的 Phantom 单体和 Easy IM BFF

macOS 是主开发与首个验收平台。Windows、Linux 安装包必须在对应原生环境构建，不能以 macOS 交叉构建结果替代。

## 开发

```bash
cd phantom-im/clients/pc
npm install
npm run dev
```

桌面开发使用专用 Vite 端口 `http://127.0.0.1:5273`，避免与独立 Web 开发端口 `5173` 冲突。Vite 仍将 `/api` 代理到 `EASY_IM_BFF_URL` 或默认 `http://127.0.0.1:28090`。

## 生产构建

```bash
npm run build
```

打包前会实际执行：

1. `../web` 的 `npm run build`（TypeScript 检查 + Vite 构建）；
2. 清空并复制 `../web/dist/` 到 `src-tauri/resources/web/`；
3. 编译 Rust release；
4. 生成 Tauri bundle。

任一 Web 构建、资源复制或 Rust 编译步骤失败，命令会非零退出。

常用命令：

```bash
# 仅生成当前平台的 app 主产物（macOS 适合无 Finder 自动化的环境）
npm run tauri -- build --bundles app

# 当前平台全部 bundle
npm run build

# 指定 bundle
npm run tauri -- build --bundles nsis,msi       # Windows
npm run tauri -- build --bundles app,dmg        # macOS
npm run tauri -- build --bundles appimage,deb   # Linux
```

macOS 默认输出：

```text
src-tauri/target/release/bundle/macos/Phantom IM.app
src-tauri/target/release/bundle/dmg/Phantom IM_*.dmg
```

DMG 创建依赖交互式 Finder。无窗口/无 Finder 自动化的 CI 或远端会话应使用 `--bundles app`，或在具备交互会话的 macOS runner 上生成 DMG。

## BFF 地址配置

安装包安装完成后，客户端首次启动时会在用户配置目录创建 `config.json`；若文件已存在则不会覆盖。默认写入的 BFF 地址为 `http://10.10.137.197:28090`，请求超时为 60 秒。

运行时按以下优先级读取配置：

1. 环境变量 `PHANTOM_IM_BFF_URL`；
2. 桌面配置文件 `config.json`；
3. 默认本地地址。

可选环境变量 `PHANTOM_IM_BFF_TIMEOUT_SECONDS` 设置 BFF 请求超时，默认 60 秒。

配置文件位置：

- macOS：`~/Library/Application Support/im.phantom.desktop/config.json`
- Windows：`%APPDATA%\im.phantom.desktop\config.json`
- Linux：`$XDG_CONFIG_HOME/im.phantom.desktop/config.json`

示例：

```json
{
  "bff_url": "http://10.10.137.197:28090",
  "timeout_seconds": 60
}
```

BFF 地址必须是 `http://` 或 `https://` 绝对 URL，且不得包含用户名、密码、查询参数或片段。网关会以 BFF 来源重写状态变更请求的 `Origin`/`Referer`，因此 BFF 的 `WEB_IM_ALLOWED_ORIGINS` 必须包含实际 BFF 公网来源。例如：

```bash
WEB_IM_ALLOWED_ORIGINS=https://im.example.com
```

安装包不包含 BFF AppSecret、Phantom AppSecret 或服务端环境变量。

## 诊断日志

客户端启动后会向当前用户的临时目录追加按日期命名的诊断日志：

- macOS：`$TMPDIR/phantom-im-logs/phantom-im-yyyyMMdd.log`
- Windows：`%TEMP%\phantom-im-logs\phantom-im-yyyyMMdd.log`
- Linux：`${TMPDIR:-/tmp}/phantom-im-logs/phantom-im-yyyyMMdd.log`

登录页底部提供“打开诊断日志目录”入口。客户端跨天运行时会自动切换为当天日期文件；同一天日志超过 5 MiB 时轮转为 `phantom-im-yyyyMMdd.log.1`，避免无限增长。

日志用于排查客户端启动、BFF 地址、本地网关、API 请求与响应状态、BFF `request_id`、网络超时、WebView 未捕获异常和未处理 Promise。日志不会记录密码、Cookie、Authorization Token、请求正文或消息正文；搜索类 URL 查询参数也不会写入。

用户反馈登录或连接问题时，可以压缩整个 `phantom-im-logs` 目录发送。日志包含设备时间、客户端版本、操作系统、BFF 来源和请求结果，不包含服务器密钥。

## 安全边界

- 本地网关只绑定 `127.0.0.1`，只转发 `/api/*`，拒绝点路径、OpenAPI 管理路径、非法 Host 和非应用来源的状态变更请求。
- BFF Session 仍为 `HttpOnly`；页面 JavaScript 和 `localStorage` 不保存 Session 或 UserToken。
- 本地网关仅对回环来源移除 `Secure`/`Domain` Cookie 属性，保留 `HttpOnly`、`SameSite`、`Path` 和有效期；到 BFF 的传输仍使用配置的 HTTP/HTTPS。
- Tauri 只授予主窗口最小 `core:window` 权限，不开放文件系统、Shell 或通用 HTTP 插件权限。
- 内置 Web SDK 的 protobuf.js 运行时使用 `new Function`，因此页面 CSP 允许 `script-src 'self' 'unsafe-eval'`。CSP 仍禁止任意脚本来源、`object`、外部 frame 和任意连接源。

## 测试

```bash
cd phantom-im/clients/pc
npm test
```

包含：

- Node 资源复制成功/失败测试；
- Rust 配置 URL 校验；
- Cookie 重写；
- 回环绑定、静态资源、SPA fallback、CSP；
- JSON/二进制代理、多 `Set-Cookie`、204/4xx/5xx、超时与不可达；
- 注册、登录、Session 恢复、Token、会话代理和 mock 二进制上传；
- 跨站来源、路径穿越和 OpenAPI 隔离。

真实链路验收需要 Phantom、BFF 和打包后的桌面应用：

```bash
# 1. 生成并启动 app
npm run tauri -- build --bundles app
open 'src-tauri/target/release/bundle/macos/Phantom IM.app'

# 2. 找到本地网关端口
lsof -nP -a -p "$(pgrep -f '/Phantom IM.app/Contents/MacOS/phantom-im-pc' | head -n 1)" -iTCP -sTCP:LISTEN

# 3. 通过安装态网关运行 Web 验收
cd ../web
E2E_BASE_URL=http://phantom-im.localhost:<端口> \
  npm run test:e2e -- --timeout=240000 \
  e2e/auth.spec.ts e2e/lifecycle.spec.ts e2e/conversation-controls.spec.ts e2e/acceptance.spec.ts
```

## 常见问题

### 页面白屏

检查 Web 构建是否最新、安装包内是否存在 `Contents/Resources/web/index.html`，并查看 WebView 控制台。若 CSP 禁止 `unsafe-eval`，现有 protobuf.js 会在启动时失败。

### BFF 返回跨站请求被拒绝

确认 BFF 的 `WEB_IM_ALLOWED_ORIGINS` 包含实际 BFF 来源。桌面网关会把请求来源改写成 BFF 自身来源，而不是随机本地端口。

### `tauri build` 卡在 crates.io

本工程使用 `.cargo/config.toml` 的 rsproxy 稀疏索引。在依赖已缓存时可执行：

```bash
CARGO_NET_OFFLINE=true npm run tauri -- build --bundles app
```

### 5273 端口被占用

停止占用进程后重试。不要修改为独立 Web 的 5173，桌面开发命令已与浏览器开发端口分离。

## 验收状态

- macOS：已构建 `Phantom IM.app`，内置资源、真实 Phantom/BFF 登录、Session 恢复、断线重连、文本与媒体发送、退出登录通过。
- Windows x64：GitHub Actions run [40622977596](https://github.com/qlshouyu/phantom-im/actions/runs/40622977596) 的 MSI/NSIS 构建、安装、启动和卸载通过。
- Linux x64：同一 run 的 DEB/AppImage 构建、安装或解包运行、启动和清理通过。

完整三平台记录见 [`../../../docs/phantom-im/pc-acceptance-report.md`](../../../docs/phantom-im/pc-acceptance-report.md)。

## GitHub Actions

GitHub-hosted x64 workflow 位于：

```text
.github/workflows/pc-build-install-smoke.yml
```

触发方式：

- `workflow_dispatch`
- 推送到 `master` 且命中 PC/Web/SDK 相关路径
- 相关路径的 Pull Request

流程分为：

1. `build` 矩阵：Windows 生成 MSI/NSIS，Linux 生成 AppImage/DEB；执行 Web/PC 测试、资源校验、SHA-256 汇总和 artifact 上传。
2. `smoke` 矩阵：在全新 runner 下载 artifact，分别静默安装 MSI/NSIS 或安装/解包 DEB/AppImage，启动 Phantom IM，检查回环网关首页并卸载清理。

范围限制：

- 仅验收 x64。
- 只验证构建、安装和启动，不连接真实 BFF 登录，也不发送消息。
- 不包含 Windows 代码签名、macOS 公证或自动更新。
- 仓库配置了 `github` 远端 `git@github.com:qlshouyu/phantom-im.git`；当前 `master` 已由 GitHub Actions 完成 Windows/Linux x64 构建、安装和启动冒烟。

若 GitHub-hosted Windows runner 无法在服务会话中启动 GUI，工作流会失败而不是伪造通过；此时应改用交互式 self-hosted Windows runner，或明确降低启动验收标准。
