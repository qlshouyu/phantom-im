# Phantom IM PC 客户端三平台验收报告

- 验收日期：2026-10-27
- 变更：`add-tauri-pc-client`
- 主开发平台：macOS 26.5.1 arm64
- 产品版本：`0.1.0`
- 应用标识：`im.phantom.desktop`

## 1. 版本与工具链

| 组件 | 版本 |
|---|---|
| Tauri CLI / JS API | `2.12.4` |
| Rust `tauri` | `2.12.5` |
| Rust `tauri-build` | `2.7.4` |
| Rust | `1.99.1` |
| Node.js / npm | `26.8.2` / `11.19.1` |
| Xcode | `26.6` |

版本由 `package-lock.json`、`Cargo.lock` 和 `npm run tauri -- info` 固化/核验。

## 2. 自动化验证

| 范围 | 命令 | 结果 |
|---|---|---|
| Web 单元/组件/平台识别 | `cd phantom-im/clients/web && npm test` | 10 个文件、31 项通过 |
| Web 类型检查 | `npm run typecheck` | 通过 |
| Web 生产构建 | `npm run build` | 通过 |
| Web 契约 | `npm run test:contract` | 65 个 ApiRoutes、61 个 OpenAPI 路径、65 个 SDK HTTP 调用一致 |
| PC 资源脚本 | `cd phantom-im/clients/pc && npm run test:build` | 2 项通过 |
| PC Rust 测试 | `npm test` | Rust 8 项单元测试 + 4 项网关集成测试通过 |
| PC 实装 E2E | `E2E_BASE_URL=http://phantom-im.localhost:<端口> npm run test:e2e -- ...` | 5 项通过 |

PC 实装 E2E 覆盖：

- 注册、手动登录、刷新恢复 Session、退出登录；
- Token 过期刷新、主动断线重连、同账号顶替；
- 双用户好友/单聊初始化、未读、草稿、图片媒体发送、会话设置、引用、转发、撤回、本地删除；
- 登录页、聊天页、通讯录、个人中心验收截图。

## 3. macOS 结果

- 构建命令：`CARGO_NET_OFFLINE=true npm run tauri -- build --bundles app`
- 产物：`phantom-im/clients/pc/src-tauri/target/release/bundle/macos/Phantom IM.app`
- 大小：约 `15.82 MiB`
- `CFBundleDisplayName` / `CFBundleName`：`Phantom IM`
- `CFBundleIdentifier`：`im.phantom.desktop`
- 安装态资源：`Contents/Resources/web/index.html`、JS、CSS、favicon 均存在。
- 启动后窗口由 CoreGraphics 识别为 `Phantom IM`，WebView 与本机随机回环网关建立连接。
- 本地网关 `/api/*` 成功访问真实 BFF 运行时配置；真实 Phantom + BFF E2E 通过。
- 进程退出后本地监听端口已释放。
- 安装包秘密扫描未发现 AppSecret 或 Session 明文。

DMG 自动生成在当前无交互 Finder 的会话中因 AppleEvent 超时失败，因此本次只确认 `.app` bundle。DMG 需要在有交互 Finder 的 macOS 环境或 CI runner 重新生成，不能据此宣称 DMG 已验收。

## 4. Windows 结果

**通过。** GitHub-hosted Windows x64 runner 在 run [40622977596](https://github.com/qlshouyu/phantom-im/actions/runs/40622977596) 中完成：

- Web/PC 测试、MSI/NSIS 构建和内置资源校验；
- NSIS 静默安装、启动、访问本地网关首页、关闭并卸载；
- MSI 静默安装、启动、访问本地网关首页、关闭并卸载；
- artifact：`phantom-im-pc-windows-x64`，8,744,347 bytes。

| 产物 | SHA-256 |
|---|---|
| `Phantom IM_0.1.0_x64_en-US.msi` | `1d2696e3311ea01a5c71fa9e0ae2eff012d150b2a8d26cd599f620a7d60e82cc` |
| `Phantom IM_0.1.0_x64-setup.exe` | `47096326520b01c59681996686c35b4de333af948f0b5ad10ad1ca29fd4dcb1a` |

## 5. Linux 结果

**通过。** GitHub-hosted Ubuntu x64 runner 在同一 run 中完成：

- Web/PC 测试、DEB/AppImage 构建和内置资源校验；
- DEB 安装、Xvfb/DBus 启动、访问本地网关首页并清理；
- AppImage 解包运行、Xvfb/DBus 启动、访问本地网关首页并清理；
- artifact：`phantom-im-pc-linux-x64`，89,092,124 bytes。

| 产物 | SHA-256 |
|---|---|
| `Phantom IM_0.1.0_amd64.AppImage` | `bd5a552056afd0ed0a79fdb9c957f4cdcee2209d076ade471efc50f37600edd5` |
| `Phantom IM_0.1.0_amd64.deb` | `aea155243149d37d7dbf9747c1352c8e613d0bbd518fbdef3ee92223a5d7313b` |

## 6. 安全与已知限制

- BFF Session 仍使用 HttpOnly Cookie；页面不持久化 Session 或 UserToken。
- 本地网关仅监听回环地址，只转发 `/api/*`，拒绝跨站状态变更、点路径和 OpenAPI 管理路径。
- Tauri 能力仅授予主窗口最小 `core:window` 权限。
- 现有 protobuf.js 运行时依赖 `new Function`，因此 CSP 允许 `script-src 'self' 'unsafe-eval'`；未使用任意脚本源。
- 本次产物未做 Windows Authenticode、macOS Developer ID 签名与公证、Linux 发行签名，也未包含自动更新。
- CI 不验证真实 BFF 登录、消息发送、签名、公证或自动更新。

## 7. 验收截图

- [登录页](acceptance/auth-login.png)
- [消息页](acceptance/desktop-chat.png)
- [通讯录](acceptance/desktop-contacts.png)
- [个人中心](acceptance/desktop-profile.png)
