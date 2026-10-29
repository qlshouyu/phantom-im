## MODIFIED Requirements

### Requirement: 聊天工具应用框架

Web IM SHALL 使用当前 Easy IM 新 UI 的「主导航 + 列表区 + 内容区」桌面布局，主导航 MUST 提供「消息」「通讯录」「个人中心」三个入口。通讯录 MUST 同时提供好友和群组视图，消息与通讯录 MUST 提供相应的搜索入口，账号、设备和偏好设置 MUST 从个人中心进入。界面 SHALL 优先适配桌面浏览器，并保持关键操作可辨识和可测试。

#### Scenario: 打开会话首页

- **WHEN** 已登录用户进入 Web IM
- **THEN** 页面同时展示主导航、真实会话列表和当前聊天内容区

#### Scenario: 切换主导航

- **WHEN** 用户选择通讯录或个人中心入口
- **THEN** 内容区切换到对应视图且当前 Phantom 连接保持有效

#### Scenario: 访问好友和群组

- **WHEN** 用户进入通讯录
- **THEN** 用户能够在好友与群组视图之间切换并查看真实数据

## ADDED Requirements

### Requirement: 真实认证启动与会话恢复

Web IM SHALL 使用 Easy IM BFF 完成注册、登录、Session 恢复、退出和 Phantom UserToken 获取，不得使用本地模拟认证。应用启动时 MUST 先加载 BFF 运行时配置并检查当前 Session；存在有效 Session 时 MUST 获取 UserToken、建立 SDK 连接并进入会话首页，不存在有效 Session 时 MUST 展示登录页。

#### Scenario: 已登录用户刷新页面

- **WHEN** 用户持有有效 Web IM Session 并刷新 Web UI
- **THEN** Web IM 通过 BFF 恢复账号信息、获取新的 Phantom UserToken 并重新建立 SDK 连接

#### Scenario: 未登录用户访问

- **WHEN** 用户没有有效 Web IM Session
- **THEN** Web IM 展示登录页且不发起 Phantom WebSocket 认证

#### Scenario: 注册与登录错误映射

- **WHEN** BFF 返回注册字段错误、登录失败或上游服务错误
- **THEN** Web IM 展示对应字段或请求错误，并保持未登录状态

#### Scenario: 退出登录

- **WHEN** 用户确认退出登录
- **THEN** Web IM 停止 SDK 连接与重连、清理当前用户本地敏感状态并调用 BFF 注销 Session，随后返回登录页

#### Scenario: Token 过期恢复

- **WHEN** SDK 报告 Phantom UserToken 过期且 BFF Session 仍有效
- **THEN** Web IM 通过 BFF 获取新 Token 并恢复 SDK 连接，不要求用户重新输入密码

### Requirement: 真实业务数据接入

Web IM SHALL 使用 `@phantom/sdk-web` 或经 Session 保护的 BFF 代理获取会话、消息、好友、好友申请、群组、成员、资料、搜索、设备和通知设置数据。除 UI 能力尚未开放的状态外，业务列表、详情和操作结果 MUST NOT 由 `data.ts` 等本地模拟数据代替。

#### Scenario: 加载会话首页

- **WHEN** SDK 连接并完成初始同步
- **THEN** Web IM 使用真实会话快照和消息缓存渲染列表、未读数与当前会话

#### Scenario: 加载联系人与群组

- **WHEN** 用户打开通讯录
- **THEN** Web IM 展示当前账号的真实好友、好友申请和群组数据

#### Scenario: 加载个人资料与设备

- **WHEN** 用户打开个人中心
- **THEN** Web IM 展示当前 Phantom 用户的资料、二维码名片和已登录设备数据

#### Scenario: 全局与会话内搜索

- **WHEN** 用户在消息或通讯录发起搜索
- **THEN** Web IM 使用 PaaS 搜索能力展示分类结果，并支持打开对应联系人或定位目标消息

### Requirement: 真实操作结果与异步反馈

Web IM SHALL 为网络读取和写操作展示明确的加载、空、失败和重试状态。发送消息、上传媒体、好友申请、群组变更、资料更新、设备下线和退出等操作 MUST 以 BFF 或 SDK 的真实响应及同步事件为准，不得在请求未成功时显示成功结果。

#### Scenario: 查询失败可重试

- **WHEN** 会话、联系人、群组或资料请求失败
- **THEN** Web IM 展示可读错误和重试入口，不使用旧模拟数据伪装成功

#### Scenario: 写操作失败

- **WHEN** 发送消息或修改关系、群组、资料失败
- **THEN** Web IM 保留失败状态或回滚乐观更新，并提示用户重试

#### Scenario: 媒体发送反馈

- **WHEN** 用户选择图片或文件并发送
- **THEN** Web IM 展示真实上传进度、上传失败原因和消息发送状态

#### Scenario: 未开放能力

- **WHEN** 用户访问收藏夹、云空间或平台尚无对应 PaaS 契约的入口
- **THEN** Web IM 明确展示暂未开放或不可用，不执行本地伪成功操作

### Requirement: 连接生命周期与故障处置

Web IM SHALL 在界面中呈现 SDK 的连接、同步、重连、Token 过期和被踢下线状态。被同类型设备顶替时 MUST 停止旧连接的自动重连并提示原因；网络恢复后 MUST 以服务端同步结果补齐会话和消息，不重复展示已存在的 `msg_id`。

#### Scenario: 断线重连

- **WHEN** WebSocket 断开后网络恢复且 SDK 重连成功
- **THEN** Web IM 接收同步事件并合并缺失消息，列表与时间线保持一致

#### Scenario: 同端登录被顶替

- **WHEN** 同一账号的新 Web 连接顶替当前连接
- **THEN** Web IM 停止旧连接、展示被顶替原因并提供重新登录入口

#### Scenario: 在线与输入状态

- **WHEN** 当前单聊对方在线、离线或发送输入信令
- **THEN** Web IM 展示对应状态，并在输入信令停止 5 秒或连接断开后清除输入提示

### Requirement: 本地真实全链路验收

Easy IM Web 集成 MUST 提供可重复的本地联调流程，覆盖 Vite UI、Easy IM BFF、Phantom 单体、MySQL、Redis 和 RocketMQ。验收测试 MUST 使用真实 BFF Session、真实 Phantom UserToken、真实 WebSocket/HTTP 请求和至少两个 Phantom 用户，不得以网络 Mock 代替主链路验收。

#### Scenario: 双用户完整主链路

- **WHEN** 两个测试用户分别注册、登录并建立 WebSocket 连接
- **THEN** 用户 A 能发起好友申请，用户 B 接受后双方可进行单聊并收到实时消息

#### Scenario: 群聊主链路

- **WHEN** 已登录用户选择好友创建群组并发送消息
- **THEN** 所有在线成员通过真实同步链路收到群组、成员和消息变化

#### Scenario: 刷新与重连一致性

- **WHEN** 测试过程中刷新页面、断网重连或 Token 过期
- **THEN** Web IM 恢复 Session 与 SDK 连接后，会话、未读数和消息顺序与服务端一致且无重复消息

#### Scenario: 验收证据

- **WHEN** 全链路测试完成
- **THEN** 测试记录包含启动前置条件、执行命令、通过结果及关键页面截图，并明确未覆盖项
