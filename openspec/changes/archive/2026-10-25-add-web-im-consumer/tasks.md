## 1. 工程与基础设施

- [x] 1.1 创建与前端平级的独立 `phantom-im-web-bff/` Maven 工程，配置 Spring Boot 4、JDK 21、Web、Validation、Security、JDBC、Flyway、Spring Session Redis 和 JJWT，并验证 `mvn -DskipTests package` 成功
- [x] 1.2 建立 BFF `application.yml` 与本地环境变量模板，覆盖单应用 AppID/AppSecret、Phantom HTTP/WS 地址、`phantom_web_im` 数据源、Redis DB 1、Cookie 和测试模式，并验证缺失关键配置时启动检查失败
- [x] 1.3 新增 `phantom_web_im` schema 初始化与 Flyway 迁移，创建 `web_im_account` 表、唯一约束和索引，并验证迁移可在空库及重复执行场景下得到一致结构
- [x] 1.4 配置 BFF 独立 MySQL 账号、Redis DB 1 和 `phantom:web-im:session:` 命名空间，并验证 BFF 不读取 `phantom_im` 业务表
- [x] 1.5 将 `client/apps/web-demo` 移动到仓库根目录并重命名为 `phantom-im-web-ui/`，同步迁移 Vite、Playwright、测试和静态资源，并验证目录中不存在对旧路径的依赖
- [x] 1.6 将 `phantom-im-web-ui/` 配置为独立 npm 工程，引入 React、React Router、Zustand 和测试依赖，通过 `file:../client/packages/phantom-sdk-web` 引用 SDK，并验证 `npm install`、类型检查和生产构建成功
- [x] 1.7 调整 Vite `/api` 代理到 `phantom-im-web-bff/` 的运行端口，并验证独立启动 UI 与 BFF 后同源 HTTP 请求成功

## 2. BFF 注册与账号映射

- [x] 2.1 实现注册请求模型、字段格式校验、确认密码校验和登录名规范化，并验证所有边界值与错误字段测试通过
- [x] 2.2 实现 `web_im_account` 数据访问与 `PROVISIONING / ACTIVE / PROVISIONING_FAILED / DISABLED` 状态流转，并验证非法状态不能登录
- [x] 2.3 实现 Argon2id 密码哈希、密码校验与敏感字段日志脱敏，并验证数据库、日志和接口响应均无明文密码
- [x] 2.4 实现 Phantom OpenAPI HMAC 客户端，以 `account_id` 作为 `external_user_id`、昵称作为资料导入值，并验证请求签名、时间戳和响应解析测试通过
- [x] 2.5 实现幂等注册开通流程：创建 PROVISIONING 账号、调用 OpenAPI、保存 Phantom ID、标记 ACTIVE，并验证重复调用不会创建重复 Phantom 用户
- [x] 2.6 实现 Phantom 开通失败处理与可安全重试路径，并验证失败响应、服务端状态和后续重试测试通过
- [x] 2.7 实现 `POST /api/auth/register`，确保注册成功不创建 Session、不返回 Phantom UserToken，并验证接口集成测试通过

## 3. BFF 登录、Session 与 Token

- [x] 3.1 配置 Spring Session Redis、`PHANTOM_WEB_IM_SESSION` Cookie、7 天过期和 HTTPS Secure 策略，并验证 Cookie 属性及 Redis key 前缀正确
- [x] 3.2 实现登录名密码认证与统一失败提示，登录时轮换 Session ID，并验证正确、错误密码和不存在账号测试通过
- [x] 3.3 实现 `POST /api/auth/login`、`POST /api/auth/logout` 和 `GET /api/auth/me`，并验证登录、退出、Cookie 清理和会话失效行为
- [x] 3.4 实现多实例 Session 共享集成测试，验证实例 A 登录后的请求可由实例 B 识别
- [x] 3.5 实现 JJWT HS256 Phantom UserToken 签发及 `POST /api/auth/phantom-token`，默认 15 分钟有效，并验证 claims、签名、过期时间和未登录拒绝场景
- [x] 3.6 实现 `GET /api/runtime-config`，返回 AppID、WS 地址和代理基地址且不泄露 AppSecret，并验证响应契约测试
- [x] 3.7 实现 Redis 故障失败关闭策略和健康状态暴露，并验证会话存储不可用时受保护接口不会匿名放行

## 4. BFF HTTP 代理与安全边界

- [x] 4.1 实现 `/api/phantom/**` 路径映射与查询参数、请求体、响应状态和响应体透传，并验证会话列表代理集成测试
- [x] 4.2 实现 Phantom 用户面路径允许列表并禁止 `/openapi/**`、任意外部 URL 和未允许路径，并验证拒绝场景测试
- [x] 4.3 实现 Session 与 `X-User-Id` 身份一致性校验，并验证伪造用户 ID、缺失 Session 和过期 Session 均被拒绝
- [x] 4.4 实现 `Authorization`、AppID、用户 ID、设备 ID 的转发及敏感请求头日志脱敏，并验证代理请求头测试
- [x] 4.5 实现 Cookie 请求的同源 `Origin`/`Referer` 校验和 JSON/二进制请求约束，并验证跨站请求及表单请求被拒绝
- [x] 4.6 实现 Phantom 超时、不可达和业务错误映射，并验证代理不会伪造成功响应

## 5. React 基础与注册登录

- [x] 5.1 建立 React 应用入口、路由、主题样式和错误边界，并验证 `/register`、`/login`、主应用路由可独立加载
- [x] 5.2 实现 BFF API 客户端、统一错误映射和运行时配置加载，并验证网络错误与服务端错误可展示
- [x] 5.3 实现注册页昵称、登录名、密码、确认密码字段及前端校验，并验证字段错误、重复登录名和 Phantom 开通失败提示
- [x] 5.4 实现注册成功返回登录页且不启动 SDK，并验证注册期间不会创建 WebSocket 连接
- [x] 5.5 实现登录页、会话恢复和退出登录，并验证登录成功后加载用户与运行配置、退出后断开 SDK 并返回登录页
- [x] 5.6 实现 SDK 适配器的登录、Token 过期刷新和重新登录流程，并验证会话有效时自动恢复、会话失效时回到登录页

## 6. 聊天工具主界面与会话

- [x] 6.1 实现三栏聊天应用框架、主导航和响应式基础布局，并验证聊天、联系人、群聊、搜索、设置入口均可访问
- [x] 6.2 实现会话 store 和会话列表拉取、分页、排序，并验证置顶优先、普通会话按最后消息排序
- [x] 6.3 实现未读、@提醒、免打扰、草稿、摘要、时间和只读状态展示，并验证对应服务端状态正确渲染
- [x] 6.4 实现打开会话、标记已读和右键菜单中的置顶、免打扰、删除操作，并验证服务端成功和失败回滚行为
- [x] 6.5 实现会话草稿本地保存、防抖同步和切换会话恢复，并验证不同会话草稿互不覆盖

## 7. 消息时间线与常用消息

- [x] 7.1 实现按 `conv_seq` 排序的消息时间线及历史分页，并验证向前加载、滚动位置保持和重连同步不产生重复消息
- [x] 7.2 实现文本输入与发送、5000 字符前端限制、发送中/已发送/送达/已读/失败状态，并验证乐观气泡与服务端回填合并
- [x] 7.3 实现 Unicode 表情输入与发送，并验证表情作为消息展示且不破坏文本输入
- [x] 7.4 实现图片和文件选择、媒体上传进度、取消/失败提示和消息提交，并验证上传成功后展示对应气泡
- [x] 7.5 实现失败消息重发并复用原 `client_msg_id`，并验证重发不会产生第二个气泡
- [x] 7.6 实现引用、单条/批量转发、撤回和本地删除操作，并验证权限、状态和引用快照展示
- [x] 7.7 实现单聊在线/输入状态、5 秒超时清除和连接断开清除，并验证输入提示不会作为持久消息展示

## 8. 联系人、群聊、搜索与设置

- [x] 8.1 实现当前 Phantom ID 展示、好友列表、好友申请与黑名单，并验证按 Phantom ID 添加、接受/拒绝、拉黑和解黑流程
- [x] 8.2 实现从联系人打开单聊及备注优先展示，并验证同一好友从不同入口进入相同 `conv_id`
- [x] 8.3 实现群列表、从好友建群、成员列表、邀请、公告、禁言、退群和解散，并验证权限错误与群状态同步
- [x] 8.4 实现联系人和群联想搜索、全局分类搜索与会话内搜索，并验证防抖、空状态和旧响应覆盖保护
- [x] 8.5 实现点击搜索结果打开会话、加载上下文并高亮目标 `conv_seq`，并验证目标已撤回或焚毁时的占位提示
- [x] 8.6 实现设备列表、远程下线、浏览器通知权限、推送开关和通知点击定位，并验证权限拒绝与成功路径

## 9. 测试诊断与自动化验证

- [x] 9.1 实现可收起诊断面板，展示连接状态、Phantom ID、会话 ID、`user_seq`、消息 ID、客户端消息 ID、状态和错误日志，并验证 SDK 事件实时更新
- [x] 9.2 实现测试模式下 Token 过期场景接口和前端入口，并验证 SDK 触发过期回调后刷新 Token、恢复连接
- [x] 9.3 实现主动断开/重连和同端顶替诊断入口，并验证指数退避、离线补齐、Kicked 原因展示及旧连接不自动重连
- [x] 9.4 编写 BFF 单元与集成测试，覆盖注册校验、密码安全、Session、Token、OpenAPI 失败、代理白名单和身份校验，并验证 `mvn test` 通过
- [x] 9.5 编写前端 Vitest/React Testing Library 测试，覆盖表单、store、消息去重、状态展示和诊断事件，并验证前端测试通过
- [x] 9.6 更新 Playwright E2E，使用真实 Phantom、BFF、MySQL、Redis 覆盖注册后登录、双用户收发、媒体、代理、重连和 Token 过期，并验证测试通过
- [x] 9.7 更新开发与运行文档，说明 schema、Redis DB、环境变量、前后端启动顺序、测试命令和故障排查，并验证文档命令可实际执行
- [x] 9.8 运行 OpenSpec 校验、前端类型检查与构建、BFF 打包和全量测试，验证变更规划与实现产物一致
