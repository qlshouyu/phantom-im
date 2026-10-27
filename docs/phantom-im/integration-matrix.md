# Easy IM Web 能力矩阵

> 基线：2026-10-27。范围覆盖当前 Easy IM Web UI 的全部可交互入口，以及认证、连接生命周期、搜索和诊断。
>
> 状态定义：
>
> - **已支持**：PaaS/SDK 契约与实现已具备，前端可直接接入。
> - **需适配**：能力已存在，但需要领域映射、组合调用或前端状态管理。
> - **契约缺口**：当前没有足以实现 UI 目标的公开契约；不得用本地模拟数据伪装成功。
> - **暂未开放**：产品暂不提供该能力，UI 必须展示明确不可用状态。
>
> 浏览器只通过 Easy IM BFF 的 `/api` 与 `/api/phantom` 同源路径访问服务；Phantom UserToken 只保存在内存。

## 最终交付状态（2026-10-27）

| 产品域 | 最终状态 | 验收 |
|---|---|---|
| 认证、Session、Token、退出 | 已完成真实 BFF | 开发态/生产式 E2E、BFF HTTP 冒烟 |
| 消息、会话、媒体、草稿、搜索 | 已完成真实 SDK/PaaS | 双用户/三用户 E2E、Vitest、SDK 回归 |
| 好友、申请、分组、黑名单 | 已完成真实 SDK/PaaS | 接受/拒绝/忽略、备注/分组/拉黑/删除 E2E |
| 群组、成员与权限 | 已完成真实 SDK/PaaS | 邀请、禁言、管理员、公告、退出、解散 E2E |
| 个人资料、QR、设备、通知 | 已完成真实 SDK/PaaS | 资料刷新、QR、远程下线、推送设置、通知点击 E2E |
| 搜索与诊断 | 已完成真实 SDK/BFF 状态 | 全局/会话内搜索、Token 过期、重连、顶替 E2E |
| 无 PaaS 契约入口 | 明确暂未开放 | 收藏夹/云空间/群文件/群相册/通话不发起伪请求 |

实现与验收详情见 [`acceptance-report.md`](acceptance-report.md)。本地模拟数据文件 `phantom-im/clients/web/src/data.ts` 已移除。

## 认证、启动与连接

| UI 入口 | 当前 UI 行为 | BFF / SDK / PaaS 目标 | 状态 | 接入要求 |
|---|---|---|---|---|
| 应用启动 | 直接显示登录页 | `GET /api/runtime-config` + `GET /api/auth/me` | 需适配 | 显式启动状态机：配置 → Session → Token → SDK 登录/同步 → ready |
| 登录 | 本地延时后进入应用 | `POST /api/auth/login` | 已支持 | Cookie 必须 `credentials: include`；映射字段错误与 `request_id` |
| 注册 | 本地创建账号，不登录 | `POST /api/auth/register` | 已支持 | 注册成功后回到登录；账号已存在映射到 `loginName` |
| Session 恢复 | 不恢复 | `GET /api/auth/me` + `POST /api/auth/phantom-token` | 已支持 | 刷新时重新签发短期 Token，不持久化 Token |
| Token 过期恢复 | 无 | SDK `tokenExpired` + `POST /api/auth/phantom-token` | 需适配 | 单飞刷新；失败时清理 SDK 并返回登录页 |
| 退出登录 | 本地模拟提示 | SDK `logout()` + `POST /api/auth/logout` | 已支持 | 二次确认、停止重连、清理视图状态并清 Cookie |
| 在线/离线/重连 | 固定显示“在线” | SDK `statechange` / `disconnected` / `connected` | 已支持 | 展示 connecting/authing/online/reconnecting/closed |
| 同端顶替 | 无 | SDK `kicked` | 已支持 | 停止重连，保留原因并显示重新登录入口 |
| 网络中断 | 无 | SDK `disconnected` 与指数退避重连 | 已支持 | 显示可恢复状态；恢复后由 SDK sync 合并 |
| BFF 会话失效 | 无 | BFF `401` / 业务错误码 | 需适配 | 终止 SDK、清理敏感状态、回到登录页 |
| 诊断入口 | 无 | 公开 SDK 事件 + BFF runtime/me | 需适配 | 只展示公开状态与可读日志，不读取数据库或内部接口 |

## 消息与会话

| UI 入口 | 当前 UI 行为 | BFF / SDK / PaaS 目标 | 状态 | 接入要求 |
|---|---|---|---|---|
| 会话列表 | `data.ts` 模拟 | SDK `listConversations()` / `conversation` 事件 | 已支持 | 映射标题、摘要、时间、未读、@、置顶、免打扰、草稿、只读 |
| 会话搜索框 | 仅过滤本地列表 | SDK `searchSuggestionsDebounced()` / `search()` | 已支持 | 展示联系人、群组、聊天记录分类；点击可定位 |
| 全部/未读筛选 | 本地计数 | 会话快照 `unread_count`、`badge_count` | 需适配 | 服务端结果为准；打开会话后调用已读 |
| 发起新会话 | 仅选择模拟会话 | SDK 好友/会话数据 + 单聊 `conv_id` | 需适配 | 从真实好友创建/打开单聊会话 |
| 打开会话 | 本地标记已读 | SDK `markRead()` | 已支持 | 以 `max_conv_seq` 标记并接受服务端同步结果 |
| 历史消息分页 | 无 | SDK `history(convId, beforeConvSeq)` | 已支持 | 向上加载，按 `conv_seq` 合并并按 `msg_id` 去重 |
| 文本发送 | 直接插入本地消息 | SDK `sendText()` / `sendMessage()` | 已支持 | 展示 sending/sent/failed；失败重发复用 `client_msg_id` |
| 图片/文件发送 | 插入假附件 | SDK `uploadMedia()` + `sendMedia()` | 已支持 | 真实文件选择、进度、取消、失败重试和 `media_id` |
| 发送超时/失败 | 无 | SDK 乐观状态 + `resend()` | 已支持 | 失败气泡可见；重发后服务端仅一条 |
| 引用 | 无 | SDK `sendMessage({ replyToMsgId })` | 需适配 | 消息菜单选择引用，展示回复摘要 |
| 转发 | 无 | SDK `forward()` | 已支持 | 选择目标会话；成功后等待同步刷新 |
| 撤回 | 无 | SDK `recall()` / recall 同步事件 | 已支持 | 仅本人/有权限消息展示；失败保持原状态 |
| 本地删除 | 无 | SDK `deleteLocal()` | 已支持 | 只删当前用户视图，不影响对方 |
| 清空历史 | 无 | SDK `clearHistory()` | 已支持 | 二次确认；失败保留历史 |
| 草稿 | 本地输入态 | SDK `saveDraft()` / `clearDraft()` + draft 事件 | 已支持 | 停止输入 2 秒同步；切换/刷新恢复，空草稿清除 |
| 会话置顶 | 静态标记 | SDK `updateConversation({ pinned })` | 已支持 | 乐观更新；失败回滚；刷新后服务端为准 |
| 会话免打扰 | 静态标记 | SDK `updateConversation({ muted })` | 已支持 | 乐观更新；失败回滚；刷新后服务端为准 |
| 删除会话 | 无 | SDK `deleteConversation()` | 已支持 | 二次确认；成功后从视图移除 |
| 单聊在线状态 | 模拟 | SDK `presence` + `subscribePresence()` | 已支持 | 订阅当前单聊好友，断线清空 |
| 输入状态 | 静态展示 | SDK `typing` + `sendTyping()` | 已支持 | 5 秒无新信令或断线后清除 |
| 语音/视频通话 | 仅图标 | 无公开 PaaS 媒体通话契约 | 契约缺口 | 显示“暂未开放”，不得发起伪成功 |
| 消息操作菜单 | 无 | 引用/转发/撤回/删除组合 | 需适配 | 按消息归属、撤回窗口和服务端权限控制入口 |

## 通讯录、好友与群组

| UI 入口 | 当前 UI 行为 | BFF / SDK / PaaS 目标 | 状态 | 接入要求 |
|---|---|---|---|---|
| 好友列表 | `data.ts` 模拟 | SDK `listFriends()` + `relation` 事件 | 已支持 | 映射备注、特别关注、分组；空态/失败/重试 |
| 联系人搜索 | 仅过滤本地 | SDK `search()` / `searchSuggestions()` | 需适配 | 使用服务端联系人结果；可加入本地已加载映射 |
| 按登录名或 Phantom ID 加好友 | 本地创建联系人 | BFF `accounts/lookup` + SDK `createFriendRequest(toUserId)` | 已支持 | 先展示头像、昵称、登录名和 Phantom ID 供确认；错误标识显示明确错误，不创建本地好友 |
| 好友申请列表 | 无 | SDK `listFriendRequests()` | 已支持 | 展示方向、验证消息、未读和处理结果 |
| 接受/拒绝/忽略申请 | 无 | SDK `handleFriendRequest()` | 已支持 | 接受后等待 `relation` 同步并刷新双方列表 |
| 好友备注 | 伪成功提示 | SDK `updateFriend(friendId, { remark })` | 已支持 | 保存成功后刷新；失败保留原值 |
| 好友特别关注 | 仅图标 | SDK `updateFriend(friendId, { pinned })` | 已支持 | 后端返回后更新；失败回滚 |
| 好友分组 | 无 | SDK 好友分组 CRUD + `updateFriend({ groupIds })` | 已支持 | 分组创建/重命名/删除/移动好友 |
| 删除好友 | 伪提示 | SDK `deleteFriend()` | 已支持 | 二次确认；失败保留关系 |
| 黑名单 | 无 | SDK `listBlacklist()` / `blockUser()` / `unblockUser()` | 已支持 | 拉黑/解除需要明确结果 |
| 好友资料卡 | 模拟职业、地区、签名、手机号、邮箱 | `users/{id}` + 好友备注/分组 | 需适配 | 仅展示公开资料或 `ext` 中产品允许公开的字段；缺失不伪造 |
| 共同群组 | 模拟 | `groups` 与好友关系组合 | 需适配 | 没有专用共同群组 API，可从当前用户真实群成员交集计算 |
| 二维码名片 | 固定伪二维码 | SDK `getQrCard()` / `refreshQrCard()` | 已支持 | 渲染 `cardUrl`/`token`，复制真实标识，刷新后更新 |
| 群组列表 | `data.ts` 模拟 | SDK `listGroups()` + `group` 事件 | 已支持 | 映射群名、头像、成员数、状态 |
| 群组详情 | 模拟 | SDK `getGroup()` | 已支持 | 公告、邀请开关、群主与成员权限 |
| 群成员 | 模拟姓名列表 | SDK `listGroupMembers()` + 用户资料补充 | 需适配 | 成员 DTO 只有 userId/角色/群昵称/禁言，需要按需读取资料 |
| 从好友创建群组 | 本地创建 | SDK `createGroup(name, memberIds)` | 已支持 | 使用真实好友 ID；成功后打开真实群会话 |
| 邀请成员 | 伪反馈 | SDK `inviteGroupMembers()` | 已支持 | 只会话成员/权限允许者可见 |
| 成员禁言/解除 | 无 | SDK `muteGroupMember()` + PaaS DELETE mutes | 需适配 | SDK 当前缺少解除禁言封装，需要补齐 |
| 移出成员 | 无 | SDK `removeGroupMember()` | 已支持 | 按群主/管理员权限控制 |
| 修改成员角色 | 无 | SDK `updateGroupRole()` | 已支持 | 群主可设置管理员/普通成员 |
| 转让群主 | 无 | SDK `transferGroupOwner()` | 已支持 | 二次确认，成功后刷新角色 |
| 退出群组 | 伪提示 | SDK `leaveGroup()` | 已支持 | 二次确认；成功后移除视图并等待同步 |
| 解散群组 | 无 | SDK `dissolveGroup()` | 已支持 | 仅群主；二次确认 |
| 群公告 | 只读模拟 | SDK `updateAnnouncement()` + group 事件 | 已支持 | 有权限用户编辑；其他成员同步刷新 |
| 群名片 | 模拟 | SDK `updateGroupNickname()` | 已支持 | 当前用户修改自己的群昵称 |
| 群消息免打扰 | 伪提示 | SDK `updateConversation({ muted })` | 已支持 | 依据群 `conv_id` 更新真实会话状态 |
| 群文件 | 伪成功 | 无公开 PaaS 群文件契约 | 暂未开放 | 明确不可用，不发请求、不显示成功 Toast |
| 群相册 | 伪成功 | 无公开 PaaS 群相册契约 | 暂未开放 | 明确不可用，不发请求、不显示成功 Toast |
| 语音/视频群通话 | 无 | 无公开 PaaS 通话契约 | 契约缺口 | 不展示可执行成功路径 |

## 个人中心、设备与通知

| UI 入口 | 当前 UI 行为 | BFF / SDK / PaaS 目标 | 状态 | 接入要求 |
|---|---|---|---|---|
| 个人资料 | `data.ts` 模拟 | SDK `getUserProfile()` / profile 事件 | 已支持 | 展示 Phantom ID、昵称、头像与 `ext` 公开字段 |
| 编辑资料 | 仅本地更新 | SDK `updateProfile()` | 已支持 | 保存成功以服务端返回为准；失败保留编辑内容 |
| 头像更换 | 伪提示 | SDK 媒体上传 + `updateProfile({ avatarUrl })` | 需适配 | 上传真实文件后更新头像 URL |
| 账号安全 | 伪提示 | BFF Session 状态 + SDK 设备列表 | 需适配 | 展示真实 Session/设备状态，不伪造双重验证 |
| 设备列表 | 固定“2 台设备” | SDK `listDevices()` | 已支持 | 展示当前设备与其他在线设备 |
| 远程下线 | 无 | SDK `remoteLogout(deviceId)` | 已支持 | 当前设备操作禁用；目标设备收到真实下线 |
| 通知开关 | 本地状态 | SDK `getPushSettings()` / `updatePushSettings()` | 已支持 | 服务端重新读取一致；权限拒绝不阻塞聊天 |
| 声音提示 | 本地开关 | 浏览器能力 + push settings | 需适配 | 声音属于本机偏好时可本地保存；服务通知开关走 SDK |
| 在线状态展示 | 本地开关 | SDK push/隐私能力探测 | 契约缺口 | 当前无独立“展示在线状态”公开设置，明确不可用或仅本机展示 |
| 紧凑会话列表 | 本地开关 | 纯前端显示偏好 | 已支持 | localStorage 保存非敏感偏好 |
| 浏览器通知 | 无 | SDK `requestNotificationPermission()` / notificationClick | 已支持 | 权限拒绝显示状态；点击定位会话 |
| 收藏夹 | 显示 24 条伪数据 | 无公开收藏契约 | 暂未开放 | 显示空/不可用，不显示数量、不发请求 |
| 云空间 | 显示 2.4 GB/10 GB | 无公开云空间契约 | 暂未开放 | 显示暂未开放，不伪造容量 |
| 最近动态 | 模拟数据 | 无聚合动态契约 | 契约缺口 | 移除或标注暂未开放，不用模拟动态 |
| 版本信息 | 伪“最新版本” | 构建版本/发布信息 | 需适配 | 可展示前端构建版本，不声称服务端最新 |
| 偏好设置 | 伪“已同步” | SDK push settings + 本机偏好 | 需适配 | 成功必须来自设置接口或明确的本机存储 |
| 退出登录 | 伪提示 | SDK `logout()` + BFF logout | 已支持 | 同认证区退出流程 |

## 全局搜索、同步与诊断

| UI 入口 | 当前 UI 行为 | BFF / SDK / PaaS 目标 | 状态 | 接入要求 |
|---|---|---|---|---|
| 全局搜索 | 本地字符串过滤 | SDK `search()` | 已支持 | 联系人、群组、消息分类与分页/空态 |
| 搜索联想 | 无 | SDK `searchSuggestionsDebounced()` | 已支持 | 防抖，只提交最后输入 |
| 会话内搜索 | 仅图标 | SDK `searchConversation()` | 已支持 | 点击结果加载上下文并高亮目标 `conv_seq` |
| 实时消息 | 无 | SDK `message` 事件 | 已支持 | 事件驱动刷新，不使用轮询替代 |
| 同步补缺 | 无 | SDK `sync` 事件与自动 sync | 已支持 | 合并缺失消息并按 `msg_id` 去重 |
| 关系/群/资料同步 | 无 | SDK `relation` / `group` / `profile` 事件 | 已支持 | 收到事件后刷新对应领域快照 |
| 在线/输入同步 | 无 | SDK `presence` / `typing` 事件 | 已支持 | 断线或 5 秒超时清除瞬态状态 |
| 诊断连接状态 | 无 | SDK `statechange` / `kicked` / `tokenExpired` | 需适配 | 展示状态、错误和重连次数摘要 |
| 刷新 Token | 无 | BFF `POST /api/auth/phantom-token` | 已支持 | 单飞刷新；仅测试/诊断按钮显式触发 |
| 模拟 Token 过期 | 无 | BFF `POST /api/auth/phantom-token/test-expired` | 已支持 | 仅 `testMode=true` 可见 |
| 断线重连诊断 | 无 | SDK 状态事件 | 需适配 | 可关闭/恢复网络；生产环境不伪造断线 |
| 同端顶替提示 | 无 | SDK `kicked` | 已支持 | 阻止自动重连，显示原因与重新登录 |
| 执行日志 | 无 | 前端诊断内存环形缓冲 | 需适配 | 不记录 Token、密码、Cookie 或消息正文 |

## 已知契约差异与处理原则

1. 好友资料 DTO 不包含职业、地区、签名、手机号、邮箱等社交资料字段。Web 只展示 `users/{id}` 公开资料和 `ext` 中产品约定的字段；缺失时显示“未设置”。
2. 群成员 DTO 不内嵌昵称和头像。页面按成员 ID 读取公开用户资料，并集中缓存在适配层。
3. 会话快照不包含“在线人数”。群详情只显示真实成员数和当前可获得的在线状态，不伪造在线人数。
4. PaaS 没有语音/视频通话、收藏夹、云空间、群文件、群相册和聚合动态公开契约，这些入口统一标记暂未开放。
5. 当前 UserToken 由 BFF 使用 AppSecret 签发；浏览器不得接触 AppSecret，也不得将 UserToken、Cookie 或密码写入 `localStorage`。
6. SDK 会按公开契约缓存消息和同步游标以支持断线恢复；认证 Token 不属于该缓存。
7. 2026-10-27 契约检查发现 `mvp-openapi.yaml` 遗漏 14 个已由 `ApiRoutes`、服务端控制器和 SDK 使用的用户面路径（群角色/群昵称/转让/退出/入群申请、消息请求、转发/撤回/删除、媒体内容、搜索联想/重建、未成年人模式），且 `/conversations/{convId}/messages` 缺少 DELETE 清空历史声明，现已补齐；`tools/easy-im-contract-check.mjs` 作为防回归门禁。
