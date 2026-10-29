# web-im-consumer-web Specification

## Purpose

定义 React Web IM 消费者面向测试人员的聊天产品交互，覆盖注册登录、会话与消息、联系人、群聊、搜索、设置和 Phantom 平台功能诊断。

## Requirements

### Requirement: 注册与登录界面

Web IM SHALL 提供独立注册页和登录页。注册页 MUST 收集昵称、登录名、密码和确认密码，并展示字段校验及服务端错误；注册成功后 MUST 返回登录页且不得自动登录。登录成功后 MUST 启动 Phantom Web SDK 并进入会话首页。

#### Scenario: 注册成功后返回登录
- **WHEN** 用户在注册页提交合法且可用的注册信息
- **THEN** 页面显示注册成功并进入登录页，不建立 SDK 连接

#### Scenario: 登录后建立连接
- **WHEN** 用户使用注册账号和密码成功登录
- **THEN** Web IM 获取 Phantom UserToken，通过 SDK 连接 Phantom，并展示会话首页

#### Scenario: 登录失败保持未登录
- **WHEN** 用户提交错误登录名或密码
- **THEN** 页面显示登录失败且不启动 SDK 连接

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

### Requirement: 会话列表与状态管理

Web IM SHALL 展示服务端会话快照，包括标题、最后消息摘要、时间、未读数、@提醒、置顶、免打扰、草稿和只读状态。置顶会话 MUST 显示在普通会话之前；免打扰会话 MUST 使用有未读标识而不展示普通未读计数。用户 SHALL 能打开、置顶、取消置顶、免打扰、取消免打扰和删除会话，并在桌面端通过右键菜单访问这些操作。

#### Scenario: 新消息更新会话
- **WHEN** 当前用户收到某会话的新消息
- **THEN** 对应会话更新摘要、时间和未读状态，并按服务端规则调整排序

#### Scenario: 打开会话标记已读
- **WHEN** 用户打开包含未读消息的会话
- **THEN** Web IM 将会话标记为已读并刷新未读显示

#### Scenario: 右键管理会话
- **WHEN** 用户在桌面端右键点击会话
- **THEN** 菜单展示置顶、免打扰和删除操作并提供相应结果反馈

### Requirement: 数字角标与导航未读汇总

Web IM SHALL 对所有数字角标应用统一的超限显示规则：`1-99` MUST 显示真实数字，`100` 及以上 MUST 显示 `99+`。该规则 SHALL 覆盖导航未读、会话未读、未读筛选、好友申请、共同群组和群成员等数字角标，MUST NOT 应用于“群组”“只读”等文字状态标签或正文中的普通数字。

数字角标 MUST NOT 修改或丢失真实计数。视觉上显示 `99+` 时，无障碍描述和业务扣减仍 MUST 使用真实数量。

左侧导航栏“消息”按钮 SHALL 展示当前用户所有非免打扰会话的未读总数。该总数 MUST 在用户位于任意主导航页面时持续更新，并在用户打开未读会话并成功标记已读后扣除该会话的真实未读数。

#### Scenario: 数字角标达到阈值

- **WHEN** 任一数字角标的真实数量为 99
- **THEN** 角标显示 `99`
- **WHEN** 任一数字角标的真实数量为 100 或更大
- **THEN** 角标显示 `99+`，无障碍描述仍包含真实数量

#### Scenario: 非聊天页收到新消息

- **WHEN** 用户停留在通讯录或个人中心，并收到某个非免打扰会话的新消息
- **THEN** 左侧“消息”按钮的未读角标实时更新为所有非免打扰会话的未读总数

#### Scenario: 打开会话后扣除未读

- **WHEN** 左侧“消息”角标为 8，用户打开一个包含 5 条未读的非免打扰会话并成功标记已读
- **THEN** 该会话未读数归零，左侧“消息”角标变为 3

#### Scenario: 全部未读清除后隐藏角标

- **WHEN** 用户打开最后一个包含未读消息的非免打扰会话并成功标记已读
- **THEN** 左侧“消息”按钮不再显示数字角标

#### Scenario: 免打扰会话不计入导航总数

- **WHEN** 用户收到免打扰会话的新消息
- **THEN** 左侧“消息”按钮的未读总数不增加，该会话继续按既有规则显示非数字未读标识

### Requirement: 历史消息与聊天时间线

Web IM SHALL 按 `conv_seq` 顺序展示会话消息，并支持向前分页加载云端历史。消息 MUST 按服务端时间线排序，MUST NOT 因实时推送、历史加载或重连同步产生重复气泡。输入区 SHALL 支持恢复和保存当前会话草稿。

#### Scenario: 加载更早消息
- **WHEN** 用户滚动到当前会话顶部并请求更早历史
- **THEN** Web IM 分页加载更早消息并保持既有消息顺序和滚动位置

#### Scenario: 重连后补齐消息
- **WHEN** SDK 在断线重连后同步到离线消息
- **THEN** Web IM 将新增消息合并到正确会话和时间线，不重复展示已存在的 `msg_id`

#### Scenario: 恢复草稿
- **WHEN** 用户重新打开存在服务端草稿的会话
- **THEN** 输入区恢复该会话草稿且不影响其他会话

### Requirement: 常用消息发送

Web IM SHALL 支持发送文本、表情、图片和文件消息。文本长度超过 5000 个字符时 MUST 在发送前阻止并提示。消息 MUST 先以发送中状态展示，再根据 SDK 响应和同步状态更新；发送失败的消息 SHALL 支持使用原 `client_msg_id` 重发。图片和文件上传 SHALL 展示进度，并允许用户识别上传失败。

#### Scenario: 发送文本消息
- **WHEN** 用户输入合法文本并发送
- **THEN** 消息立即以发送中状态出现，成功后展示服务端回填的序列号和发送状态

#### Scenario: 文本超长
- **WHEN** 用户输入的文本超过 5000 个字符
- **THEN** Web IM 阻止发送并显示超长提示

#### Scenario: 图片或文件发送
- **WHEN** 用户选择图片或文件并完成发送
- **THEN** Web IM 展示上传进度，并在上传和消息提交成功后展示对应媒体气泡

#### Scenario: 失败消息重发
- **WHEN** 一条消息进入失败状态且用户选择重发
- **THEN** Web IM 复用原 `client_msg_id` 重新提交并更新同一条消息

### Requirement: 消息操作

Web IM SHALL 根据消息类型、发送者和服务端权限提供引用、转发、撤回和本地删除操作。撤回和转发结果 MUST 以 Phantom 服务端响应及同步事件为准；本地删除 MUST 只影响当前浏览器中的展示。

#### Scenario: 引用并回复
- **WHEN** 用户选择一条消息进行引用并发送回复
- **THEN** 新消息展示引用快照，原消息后续撤回时引用快照仍保留

#### Scenario: 撤回自己的消息
- **WHEN** 用户对仍在服务端允许时限内的本人消息执行撤回
- **THEN** 双方的对应消息更新为撤回提示

#### Scenario: 本地删除消息
- **WHEN** 用户删除一条消息
- **THEN** 该消息只从当前 Web IM 中消失，其他用户和云端漫游记录不受影响

### Requirement: 联系人与好友申请

Web IM SHALL 展示当前用户 Phantom ID、好友列表、好友申请和黑名单，并支持输入登录名或 Phantom ID 查找用户、确认头像与资料后发起好友申请、接受或拒绝申请、拉黑、解除拉黑、开始单聊和查看联系人资料。登录名解析 MUST 由接入方 BFF 完成，平台好友申请 MUST 使用解析后的 Phantom ID。关系变化 MUST 通过 SDK 同步事件刷新界面。

#### Scenario: 确认资料后发起好友申请
- **WHEN** 用户 A 输入用户 B 的登录名或 Phantom ID 并进入下一步
- **THEN** Web IM 展示 B 的头像、昵称、登录名和 Phantom ID，A 确认后再发送好友申请

#### Scenario: 发起并接受好友申请
- **WHEN** 用户 A 确认用户 B 的资料后发送好友申请且 B 接受
- **THEN** 双方好友列表更新并能进入同一单聊会话

#### Scenario: 离线期间关系变化
- **WHEN** 用户离线期间收到好友申请或好友关系发生变化
- **THEN** 用户重新连接后界面通过同步事件展示最新关系状态

### Requirement: 群聊与基础群管理

Web IM SHALL 支持从好友创建群聊、打开群会话、查看成员、邀请成员、查看和发布公告、设置成员禁言、退群，以及由群主解散群聊。群成员和权限变化 MUST 通过 SDK 同步事件刷新。

#### Scenario: 创建群聊并发送消息
- **WHEN** 用户选择至少两名好友创建群聊
- **THEN** 新群出现在群列表，用户能够打开群会话并发送消息

#### Scenario: 更新群公告
- **WHEN** 有权限的群成员发布群公告
- **THEN** 群公告更新并在其他成员的群会话中同步展示

#### Scenario: 非成员查看失效群
- **WHEN** 群解散或当前用户被移出群聊
- **THEN** 群会话保留历史但输入区进入只读状态

### Requirement: 全局搜索与消息定位

Web IM SHALL 支持按联系人、群组和聊天记录执行全局搜索，并支持会话内搜索。搜索结果 MUST 按分类展示；点击消息命中项 MUST 打开对应会话、加载目标上下文并高亮目标 `conv_seq`。

#### Scenario: 搜索三类结果
- **WHEN** 用户输入同时命中联系人、群和消息的关键词
- **THEN** Web IM 分类展示三类结果及命中数量

#### Scenario: 定位历史消息
- **WHEN** 用户点击一条聊天记录搜索结果
- **THEN** Web IM 打开对应会话并定位到目标消息

#### Scenario: 无搜索结果
- **WHEN** 搜索请求完成且没有任何匹配
- **THEN** Web IM 展示明确空状态并不保留旧结果

### Requirement: 实时状态与通知

Web IM SHALL 展示单聊对方的在线或输入状态，并在输入信令停止 5 秒或连接断开后清除输入提示。Web IM SHALL 支持浏览器通知权限申请、通知设置、点击通知定位会话，以及设备列表和远程下线。

#### Scenario: 输入提示超时
- **WHEN** 对方发送输入信令后 5 秒内没有新信令
- **THEN** Web IM 清除“对方正在输入”提示

#### Scenario: 点击浏览器通知
- **WHEN** 用户点击包含目标会话的通知
- **THEN** Web IM 打开对应会话并定位最新未读消息

#### Scenario: 远程下线设备
- **WHEN** 用户在设备设置中远程下线另一台设备
- **THEN** 对应设备从列表移除，目标设备按 Phantom 规则断开

### Requirement: 平台功能测试诊断

Web IM SHALL 提供独立测试诊断面板，展示连接状态、当前 Phantom ID、会话标识、同步事件、消息状态和可读错误日志，并提供 Token 过期、断线重连和同端顶替等测试入口。诊断能力 MUST 使用公开 SDK 或 BFF 行为，MUST NOT 直接读取 Phantom 数据库或调用内部业务接口。

#### Scenario: 查看实时连接与同步事件
- **WHEN** SDK 连接、重连或同步事件发生变化
- **THEN** 诊断面板追加展示对应状态和事件信息，正常聊天界面不被诊断信息淹没

#### Scenario: 测试 Token 过期
- **WHEN** 测试人员从诊断面板触发过期 Token 场景
- **THEN** SDK 触发 Token 过期回调，Web IM 通过有效登录会话刷新 Token 并恢复连接

#### Scenario: 测试同端顶替
- **WHEN** 同一账号以相同 Web 设备类型建立第二个连接
- **THEN** 旧连接展示被踢下线原因，且 Web IM 不自动重连旧连接

#### Scenario: 诊断不绕过平台契约
- **WHEN** 测试人员查看或操作诊断面板
- **THEN** 所有 Phantom 数据仍来自公开 SDK/OpenAPI 结果，不直接访问 Phantom 数据库或内部接口

### Requirement: 文件上传前大小检查

Web IM SHALL 在读取文件并计算 SHA-256 之前检查图片或文件的大小。超过 500 × 1024 × 1024 字节时 MUST 提示“文件超过 500MB，无法发送”，且不得发起上传或消息提交。

#### Scenario: 发送超大文件
- **WHEN** 用户选择一个超过 500MB 的文件
- **THEN** 显示大小限制提示，不读取文件内容、不初始化上传、不发送消息

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
