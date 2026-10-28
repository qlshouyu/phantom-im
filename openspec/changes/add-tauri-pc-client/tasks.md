## 1. 工具链与工程骨架

- [x] 1.1 查询 npm 与 crates.io 官方元数据，确定当前最新 Tauri 2 稳定版及兼容的 `@tauri-apps/cli`、`@tauri-apps/api`、Rust `tauri` 版本，并在 `clients/pc/README.md` 记录选型依据；用 `npm ls`、`cargo metadata --locked` 验证不存在 Tauri 1 依赖
- [x] 1.2 创建 `phantom-im/clients/pc/`、`package.json`、`src-tauri/Cargo.toml`、`build.rs`、Rust 入口和 Tauri 2 配置，设置产品名 `Phantom IM`、稳定应用标识、主窗口默认尺寸和最小尺寸；执行 `cargo check` 验证空壳工程可编译
- [x] 1.3 配置 macOS、Windows、Linux 安装包目标和 `Cargo.lock`/`package-lock.json` 提交策略；执行 `npm run tauri info`，确认当前主机工具链和 bundle 目标被识别

## 2. Web 构建与资源打包

- [x] 2.1 新增跨平台 Node 资源脚本，在 `clients/web` 执行 `npm run build` 后清空并复制 `dist` 到 `clients/pc/src-tauri/resources/web/`；运行脚本验证目录中包含 `index.html`、JS/CSS/favicon 等生产资源
- [x] 2.2 将资源脚本接入 Tauri `beforeBuildCommand`，并让任一 Web 构建、清理或复制步骤失败时以非零状态退出；使用故障注入或临时错误路径验证上层打包不会继续
- [x] 2.3 配置开发态 `beforeDevCommand` 与 Vite `devUrl`，保留现有 HMR 和 `/api` 代理；在 macOS 启动开发窗口并验证页面热更新、登录前界面和 Tauri 运行时标识
- [x] 2.4 将 `src-tauri/resources/web` 加入 Tauri bundle 资源并实现安装态资源定位；构建未签名开发包后检查包内包含完整 Web 资源，且 BFF 不托管静态页面时窗口仍能加载 UI

## 3. Rust 回环网关

- [x] 3.1 实现 BFF 运行配置解析，只接受无用户信息、查询和片段的 `http`/`https` 绝对 URL，默认 `http://127.0.0.1:28090`；用单元测试覆盖合法 URL、相对 URL、带凭据 URL、带查询/片段 URL 和非法 scheme
- [x] 3.2 在随机回环端口启动本地网关，并提供 `phantom-im.localhost` 生产入口；用集成测试验证只绑定回环地址、端口可用且启动失败时可报告错误
- [x] 3.3 实现内置 Web 静态资源路由、MIME 类型、缓存头和无扩展名 SPA fallback；用集成测试请求首页、JS/CSS、favicon、直接子路径及缺失静态文件
- [x] 3.4 实现 `/api/*` 流式转发，保留方法、查询参数、允许的请求头、二进制请求体、响应状态、响应头和多值 `Set-Cookie`；用受控 mock BFF 验证 JSON、PUT 二进制、204、4xx/5xx 和上游超时
- [x] 3.5 实现回环 Cookie 兼容转发，保留 `HttpOnly`/`SameSite`/`Path`/有效期并仅对本地来源移除不兼容 `Secure`/`Domain`；用测试验证登录 Cookie 可回传、页面脚本不可读取、重启后可按稳定主机恢复 Session
- [x] 3.6 实现来源与路径防护，拒绝非 `/api/*` 转发、点路径、任意 URL 和非应用来源的状态变更请求，并转发前把 `Origin`/`Referer` 改写为 BFF 来源；用负向测试证明被拒绝请求不会到达 mock BFF
- [x] 3.7 添加静态安全响应头与限制性 CSP，允许自身资源、`data:`/`blob:` 媒体及必要的 `ws:`/`wss:` 连接；用响应头测试验证不存在通配脚本源或任意连接源
- [x] 3.8 在网关就绪后创建 Phantom IM 主窗口，接入原生标题栏、最小化/最大化/关闭/拖拽和关闭时服务停止；在 macOS 验证窗口尺寸、居中、最小尺寸及退出后无残留监听进程

## 4. Tauri 安全与三平台集成

- [x] 4.1 配置最小 Tauri 能力，只允许主窗口使用现有 Web UI 必需的 `core:window` 权限，并限制到本地网关来源；通过能力配置检查和运行时权限测试确认无文件系统、Shell 或任意 HTTP 权限
- [x] 4.2 验证生产页面中的 `isTauri()`、桌面平台属性和窗口控制可用；若远程本地页面无法安全获得 IPC，则按设计改为只读桌面标识兼容层并补充回归测试，不扩大通用 IPC 权限
- [x] 4.3 配置 Windows、macOS、Linux 窗口和 bundle 差异，检查应用名称、图标、安装包标识、系统依赖和构建配置；执行 `npm run tauri info` 及配置校验确认三平台结构完整
- [x] 4.4 运行现有 Web 回归 `npm test`、`npm run typecheck`、`npm run build` 和 `npm run test:contract`，确认桌面接入没有改变浏览器端行为

## 5. 测试与验收门禁

- [x] 5.1 为网关建立可重复的 Rust 单元/集成测试套件，并执行 `cargo test` 验证静态资源、代理、Cookie、来源校验、路径拒绝、超时和 CSP
- [x] 5.2 建立桌面生产链路冒烟：以 mock BFF 启动内置资源网关，验证首页加载、注册/登录、Session 恢复、Token 获取、`/api/phantom` 转发和二进制上传
- [x] 5.3 在本地真实 Phantom + Easy IM BFF 环境完成桌面登录、会话同步、文本发送、媒体发送、断线重连和退出登录冒烟，并记录命令、日志摘要和截图
- [x] 5.4 检查构建产物不存在 AppSecret、服务端环境变量或明文 Session；确认 Session Cookie 在页面 JavaScript 和 `localStorage` 中均不可读

## 6. 文档与运维说明

- [x] 6.1 编写 `phantom-im/clients/pc/README.md`，覆盖 Rust/Node/Tauri 前置依赖、Web 构建复制、macOS 主开发、BFF 地址配置、生产打包和常见故障；按文档从干净终端执行一遍命令验证
- [x] 6.2 更新仓库根目录 `README.md` 与 `phantom-im/README.md` 的目录结构、PC 客户端启动/构建/验证命令和三平台产物说明；检查文档链接和命令路径有效

## 7. 三平台交付验证

- [x] 7.1 在 macOS 执行生产打包，安装或启动 `.app`/DMG，验证 Phantom IM 名称、内置 UI、BFF 登录、冷启动 Session 恢复和基本消息链路
- [x] 7.2 新增 GitHub-hosted Windows/Linux x64 CI workflow 与安装/启动冒烟脚本，仅验证构建、安装、启动和本地网关首页；用 YAML、Bash 和脚本静态检查验证配置可解析，并确认流程不包含签名、公证或自动更新
- [ ] 7.3 同步仓库到 GitHub 后触发 Windows x64 job，构建 MSI/NSIS，分别静默安装、启动、访问本地网关首页并卸载；记录 run URL、产物和退出码
- [ ] 7.4 触发 Linux x64 job，构建 DEB/AppImage，分别安装或解包运行、启动、访问本地网关首页并清理；记录 run URL、产物和退出码
- [ ] 7.5 汇总 GitHub Actions 两平台 run URL、安装包 SHA-256、启动冒烟结果和未签名限制；没有实际 CI run 时不得将 Windows/Linux 标记为通过
