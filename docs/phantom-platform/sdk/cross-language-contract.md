# 跨语言 SDK 行为契约

本文供其他语言 SDK 的实现者使用。公开能力以 [API 参考](integration.html#js-api)为清单，结构和线协议以[数据模型](data-models.md)及共享 Protobuf / HTTP DTO 为依据。

下面“行为契约”归纳现有 [Web SDK 规格](../../../openspec/specs/web-sdk/spec.md)和通信契约的要求；“当前实现”描述 JS 源码的实际表现；“建议”是新 SDK 的设计建议。已知实现差异单独列出，不表示已在本次文档工作中修复或完成验收。

## 1. 对齐范围与接口组织

各语言可采用本地命名和异步范式，例如 Kotlin suspend、Swift async/await、Go context 或 C# Task。方法不必逐字同名，但业务输入、返回意义、事件和错误结果应等价。

建议按下列领域组织；JS 当前是在单个 PhantomClient 上提供方法：

| 领域 | 应提供的能力 | 对应 JS 入口举例 |
| --- | --- | --- |
| 客户端生命周期 | 登录、退出、连接状态、Token 过期、被踢下线 | login、logout、getState、事件订阅 |
| 消息 | 统一发送、快捷文本、重试、历史、撤回、转发、删除 | sendMessage、resend、history |
| 媒体 | 上传、续传、取消、进度、受控读取 | uploadMedia、sendMedia、getMediaContent |
| 会话 | 列表、详情、状态、已读、草稿、请求分区 | listConversations、markRead、saveDraft |
| 用户与关系 | 资料、好友申请、好友、分组、黑名单、名片 | getUserProfile、listFriends、scanQrCard |
| 群 | 建群、成员、权限、禁言、公告、转让、退出 | createGroup、updateGroupRole、leaveGroup |
| 设备与推送 | 设备管理、Token、通知设置、通知点击 | listDevices、registerPushToken |
| 信令 | 输入状态、在线订阅、前后台 | sendTyping、subscribePresence、setAppState |
| 搜索与合规 | 搜索、举报、申诉、导出、未成年人模式 | search、report、exportAccount |

浏览器通知权限不要求非 Web SDK 照搬；应映射到所在系统的通知能力。JS 独有的 localStorage、Blob、AbortSignal、fetch 与 Long 表示也应替换为平台本地实现。若新增方法覆盖当前未封装的平台路由，应在该 SDK 文档明确标注支持范围。

## 2. 认证和生命周期

行为契约：

1. 应用服务端签发 UserToken；SDK 不接收 AppSecret，也不承担应用账号密码登录。
2. 建立 WebSocket 后，首个业务帧发送 AuthReq。app_id、user_id 与 Token claims 一致；device_id 由宿主持久化，device_type 使用 1 MOBILE、2 DESKTOP、3 WEB。
3. 认证成功后暴露 online / connected，保存最大 user_seq、服务端时钟及功能配置，启动同步与心跳。应分别说明“认证在线”和“初始数据就绪”的可观察时点。
4. 认证 2001 通知宿主更新 Token；凭据失效不无限盲重试。获取新 Token 的地址、权限与有效期由接入方确定。
5. 网络断开自动重连，指数基数 1、2、4…秒，上限 60 秒，叠加 ±30% 抖动；认证成功后重置退避。
6. KickPush 后停止自动重连并通知宿主原因；主动 logout 同样停止重连，清理当前用户的消息、游标和敏感状态。
7. 多账号切换不得混用缓存、异步响应和事件。

建议新 SDK 使用连接代次或取消令牌隔离旧连接回调，并为登录、重连、退出设置清晰的并发语义。Token 刷新接口和“同步完成”信号如有新增，应在各端文档明确，不伪装成现有 JS 方法。

## 3. WebSocket 线协议

一条 WebSocket binary 消息承载一个序列化 Envelope。不要把 JSON、文本帧或业务 Protobuf 直接作为顶层帧发送，也不要额外添加仓库协议未定义的长度头。

| Envelope 字段 | 类型 | 约定 |
| --- | --- | --- |
| version | uint32 | 当前为 1 |
| cmd | Command | 决定 payload 的 Protobuf 类型 |
| cid | uint64 | 配对请求由客户端递增生成，响应原样回带；推送通常为 0 |
| code | int32 | 响应中 0 表示成功，非 0 是平台业务错误 |
| payload | bytes | 下表对应报文的 Protobuf 字节；业务失败不应按成功响应结构强行解码 |

JS 当前使用的指令映射如下，数值来自 [envelope.proto](../../../phantom-platform/contracts/proto/phantom/v1/envelope.proto)：

| 功能 | 上行 cmd / body | 响应或下行 cmd / body |
| --- | --- | --- |
| 认证 | `0x0001 AUTH_REQ` / AuthReq | `0x0002 AUTH_RESP` / AuthResp |
| 心跳 | `0x0003 PING` / Ping | `0x0004 PONG` / Pong |
| 被踢 | 无 | `0x0005 KICK_PUSH` / KickPush |
| 发送消息 | `0x0100 SEND_MSG_REQ` / SendMsgReq | `0x0101 SEND_MSG_RESP` / SendMsgResp |
| 新消息推送 | 无 | `0x0102 MSG_PUSH` / MsgPush |
| 确认游标 | `0x0103 MSG_ACK` / MsgAck | 不等待业务响应 |
| 撤回 | `0x0104 RECALL_MSG_REQ` / RecallMsgReq | `0x0105 RECALL_MSG_RESP` / RecallMsgResp；JS 通过 SyncEvent 应用撤回变化 |
| 增量同步 | `0x0200 SYNC_REQ` / SyncReq | `0x0201 SYNC_RESP` / SyncResp |
| 通用事件推送 | 无 | `0x0207 SYNC_EVENT_PUSH` / SyncEventPush |
| 输入状态 | `0x0300 TYPING_SIGNAL` / TypingSignal | `0x0301 TYPING_PUSH` / TypingPush |
| 在线订阅 | `0x0302 PRESENCE_SUB_REQ` / PresenceSubReq | `0x0303 PRESENCE_SUB_RESP` / PresenceSubResp |
| 取消订阅 | `0x0304 PRESENCE_UNSUB_REQ` / PresenceUnsubReq | 不等待业务响应 |
| 在线变化 | 无 | `0x0305 PRESENCE_PUSH` / PresencePush |
| 前后台 | `0x0306 APP_STATE_REQ` / AppStateReq | `0x0307 APP_STATE_RESP` / AppStateResp |

请求表需要按 cid 配对，并在响应、超时及取消时释放资源。JS 当前配对请求超时为 15 秒，心跳固定为 25 秒。AuthResp 中也有 heartbeat_interval_s，其他端应结合平台策略处理，不能把 Web 当前常量当作全部端的服务器要求。

从仓库 `.proto` 生成各语言类型；不要手工复制 JS 的 proto-bundle.json 作为长期契约源。修改字段只能增加新编号，不复用旧字段号；未知新增字段和未知事件应能兼容处理。协议版本不兼容时应明确失败。

## 4. HTTP 通道

所有用户 HTTP 请求携带 `Authorization: Bearer <UserToken>`、`X-App-Id`、`X-User-Id`，当前 JS 还发送 `X-Device-Id`。JSON 命名为 snake_case；分页、body 和返回模型与 [API 映射](integration.html#js-api)一致。

需保留以下语义：

- HTTP 非 2xx 与 `ApiResponse.code != 0` 都是失败；HTTP 2xx 不能单独判为成功。
- 成功返回领域 data；204 按无返回值处理。建议新 SDK 在诊断信息保留 request_id，而非把它当业务字段。
- DELETE 可能含 JSON body，尤其草稿、黑名单和推送 Token 删除；代理和本地 HTTP 库不可自动丢弃。
- 分片是原始二进制 body；媒体内容下载是 Blob / 字节流，不按 JSON 解码。
- SDK 不把 HTTP 查询转成 WS 命令，例如 JS markRead 使用 HTTP，即便协议里也定义了 MARK_READ_REQ。
- 普通查询重试和写操作重试需要分别定义；不能默认把非幂等创建操作重复发送。

建议使用各语言本地的强类型领域模型，集中实现 wire snake_case ↔ 语言字段映射。新增 SDK 无需把 JS 的 `any`、HTTP 原始对象和命名不一致复制到公共 API。

## 5. 消息身份、状态和本地存储

行为契约：

- 同一逻辑发送先产生稳定 client_msg_id，立即回显 sending。失败重试复用原 ID，不产生第二个本地气泡或第二条服务端消息。
- 收到发送成功响应后回填 msg_id、conv_seq、server_time；sent 表示接受，不等同于 delivered 或 read。
- 自己消息的回推、重连同步和发送响应可能交叉到达：先按 msg_id 去重，再用 client_msg_id 合并乐观消息。
- 确认消息按 conv_seq 排序；本地待确认消息尚无有效 conv_seq，不得插到已确认序列前方。
- 消息最终状态依赖投递、已读、撤回等同步事件；状态应防止倒退，撤回优先，不因为旧发送响应恢复正文或覆盖终态。
- 撤回和焚毁要使宿主可观察，显示受控占位；过期敏感正文不应继续留在缓存、搜索摘要和展示中。
- 历史与推送合并应保持同一实体，不因分页加载产生重复；数据被删除或清空后不应在重建缓存时重新出现。

JS 的失败通常 resolve failed 消息，而不是抛发送业务错误。新语言可使用统一结果对象或类型化错误，但需同时保留可重试的消息身份、状态和明确的业务失败信息，避免仅靠字符串解析；兼容适配层应说明差别。

## 6. 同步、去重与 ACK

conv_seq 用于会话消息排序，user_seq 用于用户 Timeline，ACK 与 Sync 共享 user_seq 进度。

1. 按 appId + userId 读取本地游标。认证返回 max_user_seq 大于本地游标时，发 `SyncReq { since_user_seq, limit: 200 }`。
2. 按 user_seq 顺序处理，跳过已应用事件。更新领域状态并持久化游标后，再继续后续页，直到 has_more=false。
3. MsgPush 需自动回 MsgAck。当前 JS 也为 SyncEventPush 回 ACK；即使推送重复，仍应使服务端获得确认，同时避免向宿主重复发布同一变化。
4. 撤回、状态、已读、会话、草稿、关系、群、资料、受限投递和举报处理都属于同步事件；有些变化不会作为新消息出现。
5. 当 full_resync=true，放弃过期 Timeline 回放，拉取会话快照和历史分页重建，恢复到快照对应的 max_user_seq。
6. 新设备或没有本地游标时应能建立会话快照。重建期间的在线推送不能被快照游标覆盖而遗漏。

建议将事件应用、去重记录和游标写入作为一致的本地事务，或采用等价恢复机制。对乱序高序号先到、缓存写失败、在线推送与同步并发、超大消息仅带引用等情况定义补齐策略。不要在尚未应用缺口事件时只把游标设置为已看到的最大值。

持久化范围应隔离 appId + userId；本地缓存带版本并提供迁移或安全重建。凭据由宿主管理，不放入消息缓存。退出/换账号需处理未完成请求、定时器和状态清理。

## 7. 媒体与宿主能力

- 上传先初始化；根据服务端 partSize 分割，从 1 编号；只上传缺失分片；最后完成校验，取得 media_id 后再发送消息。
- 续传和秒传需要匹配当前应用、用户和文件/策略元数据；相同 SHA-256 不能代表任意用户均可访问该媒体。
- 普通文件保留任意合法文件名、合法 MIME 与原始字节；图片、语音、视频按指定 media_type 校验并处理，不能因扩展名自行改变媒体类型。
- 完成响应只有寻址信息时，发送媒体消息前读取服务端元数据补齐尺寸、大小、时长等字段。
- 进度与取消需要有明确生命周期；建议取消可覆盖初始化、分片和完成阶段，并令被取消任务有可观察终态。
- 下载需要按当前登录用户重新取得受控路径；系统媒体控件无法加认证头时，可使用本地字节/文件 URL 桥接。
- 输入状态的 3 秒节流、5 秒过期，前后台上报和通知点击导航，需要明确由 SDK 还是宿主负责。当前 JS 把这些 UI 生命周期大多交给宿主。

## 当前 JS 参考实现的差异与限制

以下来自源码核对，属于当前接入必须理解的边界；新增语言应按行为契约设计并验证。

| 项目 | 当前 JS 实际行为 | 接入影响 / 新 SDK 参考 |
| --- | --- | --- |
| 包和握手版本 | package.json 为 0.1.0，AuthReq.clientVersion 硬编码 0.2.0；platform 固定 web | 协议版本仍为 1；新 SDK 用本端真实平台和版本 |
| 握手配置 | getAppConfig 读取不存在的 response.appConfig；实际 AuthResp 为 burnAfterRead、readReceipt、appFeatures 等 | 该 getter 通常 undefined；应按真实字段建立配置模型 |
| 登录就绪 | online / connected 先于同步完成；首次快照失败可能只发 SNAPSHOT_UNAVAILABLE | 宿主需要区分在线、数据加载与失败 |
| 发送错误 | 非 0 与传输异常转换成 failed 消息，服务端 code 被丢弃 | 新 SDK 建议保留错误、消息身份和状态 |
| WS 通用错误 | recall / presence / sync 的 protocolError 没有 code 属性；setAppState 不检查响应 code | 不能假设所有拒绝都有 error.code 或所有成功 resolve 都代表业务成功 |
| 心跳 | 固定 25 秒，不采用握手建议；PING 超时仅吞掉异常 | 没有完整的客户端心跳超时关闭逻辑；新 SDK 应明确失活判定 |
| 手动重连 | reconnect 依赖当前连接 close 回调，closed 状态会阻止自动重连 | 不视为“任意状态恢复连接”的保证；显式登录由宿主控制 |
| 在线同步去重 | SyncResp 跳过旧 userSeq；SyncEventPush 未按游标过滤就应用事件；MsgPush 直接推进游标 | 不保证所有在线事件仅回调一次或缺口可补齐；新 SDK 需统一排序/去重 |
| 无内联正文事件 | newMessage 只有引用而无 msg 时未主动回捞 | 新 SDK 根据 msgId / convSeq 补取正文 |
| 状态防倒退 | applyStatus 直接覆盖状态，发送响应可能把已读等状态改回 sent | 新 SDK 应以状态合并规则解决推送/响应竞态 |
| 历史返回 | 返回原始 MessageView[]；历史合并不逐项触发 message；conv_type 按 GROUP 判断而服务端返回 CONV_TYPE_GROUP，群历史可能被归为 SINGLE；状态映射简化、mentions 为空 | UI 显式刷新；新 SDK 建立统一强类型历史模型 |
| 会话缓存 | 列表和全量快照填充缓存；乐观更新/回滚仅发通知，未真正改回内部 map，其他领域主要转发事件 | 宿主重新取数；不要承诺已有完整本地域数据库 |
| 缓存版本与原子性 | 写 version=3，但加载未按版本号校验/迁移；消息延迟约 200ms 写，游标独立立即写，写失败被忽略 | 不具备原子恢复保证；新 SDK 应实现一致性与迁移策略 |
| 缓存清理 | logout 清理消息和游标，未清理 conversations map；删除标记未写入缓存 | 换账号清理宿主状态；新 SDK 应完整隔离本地状态 |
| 焚毁时点 | 按 serverTime + TTL 在消息转换/缓存加载时判断；无按实际展示时刻启动的销毁计时器 | 当前不等同于完整展示后阅后即焚实现；新 SDK 按产品与协议语义实现并验收 |
| 上传取消与进度 | AbortSignal 仅传给分片；已传字节为估算值；秒传不发逐片进度 | 不承诺全流程取消或每次进度都精确 |
| 防抖 Promise | 被清除的计时器对应 Promise 不结束；两个搜索方法共用计时器 | 宿主可自行防抖；新 SDK 对取消任务提供明确结束结果 |
| 生命周期清理 | 无 destroy；on/off 由宿主管理；pagehide 监听未提供公开移除接口 | 宿主控制实例数量；新 SDK 提供本端资源释放机制 |
| 保留协议指令 | JS 不处理所有定义的指令，例如独立 RECALL_PUSH、READ_RECEIPT_PUSH、CONV_DIGEST | 服务端当前链路使用 SyncEvent；其他端按实际服务能力声明支持范围 |

## 8. 跨语言验收清单

下列是实现与验收建议，不能视为当前所有项目均已通过。

| 场景 | 预期可观察结果 |
| --- | --- |
| 有效 Token 登录 | 完成首帧认证，连接状态可观察；功能配置与协议响应一致 |
| 过期/吊销/禁用身份 | 保留业务错误；过期通知宿主，停止无效重试 |
| 请求超时与连接取消 | 调用在确定时间结束，pending 资源被释放 |
| 文本发送成功和 15 秒超时 | sending → sent / failed；成功回填 ID 和序号 |
| 失败后复用 clientMsgId 重发 | 服务端与本地均不产生第二条逻辑消息 |
| 发送响应与自己消息回推竞态 | 同一气泡，顺序稳定，已读等终态不倒退 |
| 重复/乱序 MsgPush 与 SyncEventPush | 自动 ACK、领域事件不重复，缺口能通过同步补齐 |
| 断线期间发生新消息、撤回、已读、关系/群变更 | 重连回放至最新游标，最终状态与服务端一致 |
| full_resync 与重建期间推送并发 | 会话、历史和游标一致，不遗漏在线变化 |
| 持久化失败、重启、未知缓存版本 | 不误报恢复成功，不泄漏跨账号数据，可重建 |
| 已删除/已清空/已撤回/已焚毁的内容 | 历史、同步、重启后不恢复不应展示的正文 |
| 上传中断、续传、秒传、取消和错误 MIME | 仅补传缺失分片，完整性验证正确，任务终态明确 |
| FILE 无 MIME、任意合法扩展名 | 使用 octet-stream 兜底，按 FILE 保留原字节和文件名 |
| 会话乐观状态被拒绝 | 本地状态与 UI 同时回滚，错误可观察 |
| HTTP 2xx/code 非 0 与非 2xx/code 缺失 | 均报告失败，结构化错误可区分 |
| 旧搜索响应和已取消搜索 | 不覆盖新结果，所有任务最终完成或取消 |
| 被踢下线、退出、账号切换 | 不自动抢回登录，旧请求/事件不污染新用户 |
| 64 位大序号与未知新增字段 | 序号无精度损失，保持向前兼容 |

已有 JS 回归入口为 `phantom-platform/sdks/web/test/sdk.test.ts`，真实服务链路入口为 `tools/e2e-smoke/`。跨语言实现可复用其业务场景，但需额外覆盖本表中的竞态和当前实现缺口。
