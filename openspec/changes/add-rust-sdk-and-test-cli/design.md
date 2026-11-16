## Context

动机和范围见 proposal.md。本设计依据用户指定的 `docs/phantom-platform/sdk/architecture.md` 和 `integration.html#rust-core`，以仓库中的真实协议及服务实现约束 wire 映射。

已核对的现状：

- `phantom-platform/sdks/web/src/index.ts` 已覆盖主要领域方法，`src/proto.ts` 使用五份共享 proto；Rust SDK 目录不存在。已有 PC Cargo 工程属于 Tauri 宿主，与共享 SDK 独立。
- 架构文档定义 L3 Facade、L4 Kernel 和 L5 适配及八个核心 crate 的建议结构；本设计采用这一组织，新增 CLI crate，不创建尚无消费方的 FFI 壳。
- Rust 接口表列出 103 项函数/方法，另在正文定义 subscribe、EventSubscription 和 CancellationToken。覆盖范围以整章为准，包含 JS 尚无便捷入口但服务端已有的群邀请、关系设置等方法。
- 根据本次用户修订，存储由 SDK 使用者通过接口实现或回调决定，未配置时使用 SQLite。原文档 ClientOptions.storage 为必填且仅支持 Memory/Directory，本设计将其改为可选并扩展 Custom；实施时同步文档，不将原草案描述当作新配置的限制。
- `phantom-common/.../http/ApiRoutes.java`、各业务 Controller 和 `phantom-service-api` 下 DTO 是 HTTP 映射依据；OpenAPI 需要与这些实现结合查阅。`MessageViewDto` 的 content_json、CONV_TYPE_GROUP 等枚举名和缺失 mentions/引用类型需要归一化。
- `ConversationController` 的已读通过 HTTP PUT read-positions；不能因为 proto 有 MarkReadReq 就改变 Rust 文档所沿用的调用路径。撤回沿用 WS 请求，HTTP 查询/写入按现有接口逐项映射。
- `SyncAppService.sync` 当前固定 `full_resync=false`，`UserTimelineReadDao` 按 user_seq 查询，max_user_seq 为随后单独取得的全局进度；不能将它误当作任意分页已应用进度。全量重建仍是 Rust 必须实现的契约，使用可控协议测试触发。
- `tools/e2e-smoke/smoke.mjs` 自行编解码协议并签发演示 Token，只用作场景参考；Rust CLI 从宿主取得 UserToken，所有网络通过公开 SDK 执行。
- `web-sdk` 旧规格允许吞掉 localStorage 失败，且对 Token 过期、搜索导航的描述与本次 Rust 文档存在差异。Rust 严格按其已明确的事务存储、失效 Token 停止重试、返回定位数据语义实现，不修改 JS 规格，也不实现 UI 导航。

## Goals / Non-Goals

**Goals:**

- 通过 crate 依赖约束保持业务规则可测试，默认组合具备真实平台联调能力。
- 将会话隔离、提交进度和任务结束作为共同基础，领域 API 共享同一套规则。
- 让 CLI 成为公开 API 的可重复验收入口，并区分本地确定性测试和真实服务证据。

**Non-Goals:**

- 不在本次建立多语言 ABI、移动端系统权限实现或各平台发布矩阵。
- 不把原生 SDK 的存储规则反向改为浏览器延迟保存，也不把演示签名密钥编入 SDK/CLI。
- 不迁移现有用户缓存；Rust 使用新的存储格式，不读写 JS localStorage 或 Tauri 配置。

## Decisions

### 1. 独立 workspace 与可校验的依赖方向

在 `phantom-platform/sdks/rust/` 建立 workspace，统一 crate 版本、Rust edition、最低工具链声明和 Cargo.lock。工具链及依赖版本在首次实际构建时锁定并记录，不在规划阶段宣称已验证的平台兼容性。

| crate | 职责 | 项目内允许依赖 |
| --- | --- | --- |
| phantom-domain | 公共领域 DTO、存储逻辑记录/变更、枚举、错误、纯校验及消息状态合并规则 | 无 |
| phantom-ports | 网络、公开存储扩展契约、时钟、调度、文件和诊断能力接口 | domain |
| phantom-core | 会话、消息、同步、领域用例与事件管理 | domain、ports |
| phantom-protocol | 五份 proto 生成类型、Envelope 与 HTTP DTO 转换 | domain、ports |
| phantom-transport | 默认 HTTP、WebSocket 和鉴权实现 | domain、ports、protocol |
| phantom-store | 内置 Memory/SQLite、默认目录、锁、迁移及回调到存储接口的适配 | domain、ports |
| phantom-runtime | 共享运行时、计时、取消支撑、默认文件源和脱敏诊断 | domain、ports |
| phantom-sdk | PhantomClient、ClientOptions/StorageOptions、公开扩展类型导出、订阅、调用上下文和组合根 | core、domain、ports 及各默认适配 |
| phantom-sdk-cli | 命令解析、CLI DTO、场景执行和报告 | sdk 的公开导出 |

对外统一导出 `phantom_sdk`，包括供使用者实现的 StoreFactory/Store、存储逻辑记录及 StorageCallbacks/CallbackStoreFactory；业务 Future 和快照不暴露网络库、SQL 或生成 proto 类型。ClientOptions/StorageOptions 含接口对象，放在 Facade，避免 domain 反向依赖 ports。Ports 使用可注入的 Send + Sync 能力；需要动态分发的异步接口通过显式 Future 返回类型解决，不把具体运行时的任务类型写进核心模型。Cargo 依赖检查防止 core 引用 transport/store 等默认适配。

备选：一个大 crate 开发较快，但不能从编译层阻止核心耦合；将 CLI 放进现有 Tauri 工程会使测试依赖桌面构建。因此采用独立 workspace 和架构文档中的 crate 边界。

### 2. 默认适配与协议生成

默认方案采用 Tokio 共享运行时、reqwest HTTP、tokio-tungstenite WebSocket、prost/prost-build 协议生成、rusqlite SQLite、serde JSON 及 clap 命令解析。它们属于本提案的实现选型，具体版本、TLS feature 和工具链兼容结果必须在实施任务中验证后锁定。

构建只从现有 `phantom-platform/contracts/proto/phantom/v1/{envelope,common,message,sync,signaling}.proto` 生成代码，不维护 Rust 协议副本。将共享 proto 源纳入构建变更追踪。仓库内使用 path 依赖并明确 proto 编译工具前置条件；公开注册表打包不是本次验收目标。

协议层保留原始帧，映射前识别未知字段，针对未知 SyncEvent oneof 保存其字段号和原始字段字节。未知枚举保留数值，未知消息正文保留文档要求的载荷。为此编写独立二进制 fixture 测试，不能只验证生成类型能解码已知消息。

HTTP 对照路由、Controller 和 DTO 建立方法映射，保留分页及业务错误；历史 content_json 按 proto JSON 规则解析，并处理 int64 字符串、枚举名称和缺失字段。base_url 用 URL 类型规范化，追加子路径时保留部署前缀，动态路径组件逐段编码。

媒体内容 URL 只按现有签名访问契约使用；凭据仅附加到已配置平台来源，跨来源 URL 或重定向不得转发平台认证头。未知或不兼容响应返回 Protocol，不伪造默认成功。

备选：手写全部 proto 易产生字段号偏差；重用 protobuf.js 描述会把 JS 工具链带入 Rust。采用共享源生成及显式领域转换。

### 3. 共享运行时、实例 Actor 和同步快照

默认 Facade 复用进程内运行时；每个 Client 持有独立会话命令队列、取消树、请求表、连接 generation 和账号 epoch。运行时工作线程承担网络/计时，数据库和阻塞文件操作进入专用工作队列。同步读取方法读取内存投影快照，不同步查库。

所有会话操作在单一控制队列中串行决定受理/Busy/Conflict。logout 和 shutdown 先推进 epoch/关闭标志并取消旧工作，再进行有界清理；等待 Future 的取消不得撤销本地身份清理或 Released 终态。所有返回、推送、定时器和提交携带 epoch/generation，入队和提交前均复核。

new 只校验配置和组装，不提前认证；所有后端在登录恢复阶段按命名空间异步打开，配置为自定义实现时不创建默认 SQLite 目录或连接。Client 外层释放最后一个引用时触发尽力取消，明确完成清理仍以 shutdown 为准。

Token 更新串行建立新的认证连接，以当前 app_id/user_id/device_id 提交新凭据，服务端验签确认后才替换活动凭据。旧连接因同设备替换产生的迟到 Kick 必须按 generation 丢弃；旧账号 Token 不能仅靠本地解码判定可信。认证失败保留当前身份和可用旧凭据，旧凭据已失效时关闭并通知宿主。

备选：每次 API 创建运行时会使取消、句柄和任务所有权分散；仅靠锁和共享可变对象容易让旧响应污染新会话。因此采用实例命令队列和短时快照锁。

### 4. 可注入存储、默认 SQLite 与恢复策略

按用户最新要求，SDK 依赖唯一的公开存储契约，存储实现由 SDK 使用者决定。内置 SqliteStoreFactory/SqliteStore 分别实现与自定义实现相同的 StoreFactory/Store；Memory 和 CallbackStoreFactory 也实现该契约。未配置时仅在 Facade 组装阶段选择 SQLite 工厂，随后将相同类型的存储接口对象注入 Kernel。

后端选择只发生在组合根，Kernel 不检查 SQLite/Custom 类型，也不保留默认 SQLite 专用读写路径。消息去重、状态合并、终态过滤、同步推进、恢复及 ACK/事件时序在 domain/core 共用；存储实现仅承担契约规定的读取、原子变更、持久性和资源管理。SQLite 的 SQL、物理表及迁移只在内置适配中出现。默认配置和显式 SQLite 都进入同一实现，不复制业务逻辑。

**配置与默认选择**

将 `ClientOptions.storage` 调整为 `Option<StorageOptions>`，提供 `ClientOptions::new(base_url, app_id)`，其中 storage=None、请求期限默认 15 秒，调用者无须填写存储字段。StorageOptions 保留 `Memory`、`Directory(PathBuf)` 并增加 `Custom(Arc<dyn StoreFactory>)`：

| 配置 | 采用的实现 | 路径/资源归属 |
| --- | --- | --- |
| None / 默认构造 | 内置 SQLite | 平台标准用户应用数据目录下 `phantom/sdk/`，按环境/应用/账号分区 |
| Some(Directory(path)) | 内置 SQLite | 使用者指定根目录 |
| Some(Memory) | 内置易失内存存储 | 不创建 SQLite 或目录 |
| Some(Custom(factory)) | 使用者的接口实现 | 由使用者管理物理位置和资源 |
| Some(Custom(CallbackStoreFactory::new(callbacks)?)) | 使用者的回调实现 | 同一扩展契约，由回调管理物理位置和资源 |

一次创建只选择一种实现，Client 存活期间不可热切换；构造后无法访问选定后端时显式报错，不回退到 SQLite、Memory 或临时目录。默认目录无法解析/写入返回 Storage，调用者可以重新创建并指定 Directory 或 Custom。默认目录策略是本次提案的具体选择，实施时使用平台目录 API 验证，并在接入文档列出实际路径规则，不把当前工作目录当作默认位置。

**公开接口与回调适配**

StoreFactory 按类型化 StorageNamespace（规范化服务来源/路径、app_id、user_id）打开独立账号 Store；Store 提供加载恢复快照、按会话/序号查询、读取投影、原子应用 StorageChangeSet、flush、清理当前命名空间和关闭所属句柄。StorageChangeSet 在一次提交中包含消息/领域投影、Inbox、Outbox、user_seq 及删除/焚毁等变更。公开的是逻辑存储记录及契约版本，不暴露 SQL、SQLite 表名或 Protobuf 生成对象，也不把存储任务限制成只持久化聊天文本。

使用者既可实现 trait，也可传入 StorageCallbacks，由 CallbackStoreFactory 统一适配同一接口。回调集合覆盖打开、恢复/查询、原子变更、flush、命名空间清理和关闭，携带对应 namespace/句柄；必需回调缺失在使用前返回 InvalidArgument。两种入口不形成两套行为，默认选择也不检查某次回调的返回值来决定切换后端。异步接口返回 Send Future，接口对象及闭包为 Send + Sync，不依赖 Tokio 任务类型。调用宿主实现时不持有核心状态锁，阻塞 IO 由实现者安排到适当执行上下文。

后端声明存储契约版本和 Persistent/Volatile 能力；Persistent 在原子提交/flush 成功时满足约定持久性，Volatile 不承诺进程重启恢复，但仍要求批次全有或全无。缺少原子提交能力的逐项写回调不能作为合法实现。SDK 校验必需能力和契约兼容性；使用者通过共用存储契约测试证明自己的实现满足提交、恢复和隔离规则，不能声称运行时能自动证明任意第三方数据库实现正确。

Factory 可持有宿主共享的数据库或连接池，SDK 只释放本实例打开的 Store 句柄，不关闭共享资源。clear_cache=true 只调用当前 namespace 的清理，不删共享库。不同账号必须隔离；同账号并发写入由后端的写入租约/冲突契约处理，不能让独立实例各自推进不一致游标。自定义后端自行维护物理结构、加密、迁移和备份，SDK 只传递逻辑数据/版本要求，后端故障映射为 Storage，账号写入租约竞争映射为 Conflict。

**内置 SQLite 与统一历史处理**

内置 SQLite 账号库命名空间由 StorageNamespace 的稳定摘要构成，数据库元信息校验完整命名空间，避免跨环境串库及任意路径。内置 Memory 实现相同接口；默认及自定义后端均通过同一套事务存储接口参与核心处理。

SQLite 包含 schema 元信息、消息和会话投影、领域缓存/失效标记、Inbox、Outbox、同步游标、已删除/已清空/已焚毁标记及展示截止时间；索引覆盖 conv_id/conv_seq、msg_id 和 client_msg_id，事务只修改相关行。收发、同步和 HTTP history 统一通过 StorageChangeSet 写入选定后端；在 SQLite 下写入消息表，在自定义实现下由其保存，不向默认库双写。启动时恢复可供同步 get_messages 读取的内存快照；history 保留远端分页语义，不在初始化时无界下载全部历史。撤回和焚毁清除所选后端的正文及对应 Outbox/派生内容，仅保留必要身份及终态标记。

发送先提交投影和 Outbox 再发网络；接收将投影、Inbox 和游标放在同一原子变更，成功后刷新内存快照、回 ACK、发布事件。自定义接口和回调失败同样保留旧游标并报告 Storage，不能静默忽略。flush_storage 调用选定后端的提交屏障，不成为 SQLite 专属能力。

内置 SQLite 的已知旧 schema 在事务内迁移；首版提供升级/失败回滚 fixture，不编造已有 Rust 用户数据。自定义后端负责自身格式迁移。所有后端面对不兼容/损坏需报告 Storage 并保留原数据，由宿主显式选择重建，不擅自换后端。持久后端恢复时将 Sending/结果不确定 Outbox 保留为可核对的 Failed/Unknown 状态，不自动重发；易失后端不承诺跨进程恢复。

内置 SQLite 使用应用专用目录和文件权限隔离，不承诺静态加密；自定义实现可采用使用者的加密或存储设施。Token 仅驻留内存，不传给存储回调。自动容量淘汰和移动端备份策略不在本次范围，不能隐式丢弃未确认进度。

备选：只允许指定 SQLite 路径不能满足使用者自选技术；同时双写自定义后端与 SQLite 会分裂状态。采用公开存储抽象加单一后端选择，接口和回调共享相同事务契约，SQLite 提供开箱即用的默认实现。

### 5. 同步推进与可中断全量重建

在线推送和增量响应统一进入同步引擎：

1. 对已提交事件去重；未处理推送只暂存，不因为收到较大 user_seq 就推进累计 ACK。
2. 发现在线跳号时从已提交游标请求 SyncReq。按服务端返回页顺序和其覆盖语义处理，不要求所有整数序号连续，也不自行臆造缺失事件。
3. 每页先预合并撤回/终态，再事务提交；有更多页时从已应用页末继续。`max_user_seq` 是目标信息，不能替代未处理页末；空页但目标仍大于已应用游标时有界重查，最终超时/Protocol，不能静默跳过进度。
4. MsgAck 只确认安全已提交进度。SyncEventPush 不额外发私有 ACK 帧，文档中只有 MsgPush 要求 MsgAck。
5. 无内联正文通过 conv_id/conv_seq 的历史窗口补取，精确定位后合并同一消息；补取不能还原删除/撤回/焚毁内容，也不能无界阻塞。

full_resync 在独立 staging 投影重建；会话分页从第一页的快照基线开始，后续页不能把重放基线抬高。分页快照没有跨请求事务保证，期间到达的事件先缓冲/留待重放，随后从保守基线补齐增量，并对关系/群/资料等需要完整查询的投影执行刷新，统一合并后原子切换。不能边分页边覆盖活动库游标。缺少版本信息的领域投影使用失效再查询，不能用较旧乐观回滚覆盖较新快照。若基线过期或持续无进度，丢弃 staging 并有界重试或报错，旧已提交投影保持可用。

初次无缓存也恢复会话及所需领域快照；历史按需分页，不在 wait_ready 中无界下载全部永久历史。Ready 表示本次目标基线完整，随后增量继续运行。当前服务端无法触发 full_resync，因此上述流程主要由模拟 HTTP/WS 和故障存储测试给出确定性证据。

备选：取每页最大的 max_user_seq 并覆盖活动状态简单，但可能遗漏重建期间变化。采用保守基线、重放与 staging 切换。

### 6. 完整领域 API 与文档对照

以当前 Rust 接口表为基线建立 `api-coverage.md`，逐方法记录文档锚点、公开签名、传输路由/命令或纯本地行为、DTO 映射、测试和 CLI 入口，并额外登记本次新增的 ClientOptions 默认构造和存储接口/回调配置。以下分组共 103 项，不包括正文另列的订阅/取消句柄方法：

| 分组 | 数量 | 方法 |
| --- | --- | --- |
| 会话生命周期 | 12 | `new`, `get_state`, `get_current_user_id`, `get_app_config`, `get_sync_status`, `flush_storage`, `login`, `wait_ready`, `update_token`, `reconnect`, `logout`, `shutdown` |
| 消息 | 11 | `single_conv_id`, `send_text`, `send_message`, `resend`, `recall`, `forward`, `delete_local`, `clear_history`, `history`, `get_messages`, `mark_message_displayed` |
| 媒体 | 8 | `upload_media`, `init_upload`, `upload_part`, `complete_upload`, `get_media`, `get_media_content`, `download_media`, `send_media` |
| 会话领域 | 10 | `list_conversations`, `get_conversation`, `get_conversation_state`, `update_conversation`, `delete_conversation`, `mark_read`, `get_read_position`, `save_draft`, `clear_draft`, `handle_message_request` |
| 用户与关系 | 20 | `get_user_profile`, `update_profile`, `list_friends`, `create_friend_request`, `list_friend_requests`, `handle_friend_request`, `update_friend`, `delete_friend`, `list_blacklist`, `block_user`, `unblock_user`, `list_friend_groups`, `create_friend_group`, `rename_friend_group`, `delete_friend_group`, `get_qr_card`, `refresh_qr_card`, `scan_qr_card`, `get_relation_settings`, `update_relation_settings` |
| 群 | 19 | `list_groups`, `create_group`, `get_group`, `update_group`, `list_group_members`, `invite_group_members`, `remove_group_member`, `update_group_role`, `transfer_group_owner`, `leave_group`, `dissolve_group`, `mute_group_member`, `unmute_group_member`, `get_announcement`, `update_announcement`, `update_group_nickname`, `create_group_invite`, `join_group_by_invite`, `handle_group_join_request` |
| 设备、推送与信令 | 13 | `list_devices`, `remote_logout`, `register_push_token`, `unregister_push_token`, `get_push_settings`, `update_push_settings`, `get_privacy_links`, `update_privacy_links`, `send_typing`, `subscribe_presence`, `unsubscribe_presence`, `set_app_state`, `handle_notification_click` |
| 搜索与合规 | 10 | `search`, `search_suggestions`, `search_conversation`, `report`, `list_reports`, `submit_appeal`, `list_appeals`, `export_account`, `get_account_exports`, `set_minor_mode` |

还需覆盖 `PhantomClient::subscribe`、`EventSubscription::{next,close}` 和 `CancellationToken::{new,clone,cancel,is_cancelled}`，以及全部公开 DTO 和事件变体。发送状态机、账号校验、调用期限和错误组装是领域方法的共用基础，不在每个 HTTP 方法中复制。

get_announcement 虽是 GET，当前会标记公告已读，CLI 模式与 dry-run 摘要需标记这一副作用。群、好友、账号导出等无幂等键写操作不自动重试；可以通过独立场景显式验证相应权限失败。

备选：以 JS 方法数量作为完成标准会漏掉 Rust 增补能力。采用 Rust 章节清单并与实际服务逐项映射。

### 7. 媒体、展示计时与宿主输入

媒体内部统一可分片读取的 FileSource，桌面默认使用文档支持的 Path/Bytes；选择文件和沙箱授权由宿主解决。摘要按原始字节计算；init/part/complete/get_media 作为同一高层 deadline 子步骤，利用 uploaded_parts 恢复进度，完成后读取权威元数据。下载先写同目录临时文件，成功校验后提交；overwrite=false 使用不可覆盖提交，取消/失败清理临时文件，避免检查与写入竞态。

mark_message_displayed 在内存中同步记录首次输入并安排持久化屏障；可恢复的 Burned 事件以存储提交为基础发布。保存绝对截止时间，运行期间用可注入单调时钟调度，恢复时比较截止时间；后续历史映射先应用本地终态过滤。计时不绑定 server_time 加 TTL，也不把收到消息当成展示。后台挂起期间不承诺持续执行，到期检查在恢复时立即完成。

本次采用显式 set_app_state、推送注册、通知点击等公开输入和默认桌面文件适配；Host Ports 保持平台无关，具体移动系统实现后续交付。

备选：全部媒体转成内存字节或在语言包装层计时会增加复制并分裂规则。采用核心任务和受控文件源。

### 8. 错误、取消与事件订阅

每个调用持有 deadline、取消令牌、epoch、operation/client_msg_id 和提交阶段。整体期限只计算一次，子调用共享剩余预算；预取消不发业务请求，已提交后取消/超时返回 Unknown。响应、超时和取消经单次完成槽竞争，保证 Future 只结束一次；晚到的消息确认仍可推进投影与事件，不能把调用返回成功再改为取消。

订阅使用每句柄独立有界队列和终止槽，可靠事件积压时记录 EventLagged 并关闭，终止槽不被已满队列挤掉。事件发布在本地提交后，不持有状态锁调用宿主。logout 清空旧 epoch 队列；next 取消不消费将来事件、不关闭订阅；shutdown 关闭并丢弃未消费项。

备选：广播队列静默丢失或取消时直接 drop Future 不能满足外部语义，采用可观测终止及显式取消树。

### 9. CLI 的调用、场景与输出合同

命令形式采用 `phantom-sdk-cli`：

| 命令 | 用途 |
| --- | --- |
| `schema [method]` | 列出公开方法或输出输入/结果 JSON 模式 |
| `call <method> --input <file|-> [--dry-run]` | 执行单次调用，自动完成必要会话准备和释放 |
| `listen --duration-ms <n> [--max-events <n>]` / `--follow` | 通过 EventSubscription 输出持续事件 |
| `scenario <file|-> [--dry-run]` | 同进程命名实例和多步骤场景 |
| `smoke` | 内置双账号真实服务测试，显式选择隔离的临时 SQLite Directory |

全局参数包含 base-url、app-id、user-id、device-id、device-type、platform、storage、storage-dir 和 timeout-ms；storage 只选择内置 default/sqlite/memory，未指定存储时保持 ClientOptions.storage=None，由 SDK 决定默认 SQLite。storage-dir 显式选择 Directory，不能与 memory 同时设置，冲突返回参数错误。UserToken 只能通过 `--token-env` 或 `--token-file` 的引用提供，多账号凭据使用角色名与引用，禁止 Token 字面值参数。场景的内置合法操作为 client/new、subscribe、call、wait_event、assert、cancel、close_subscription 和 shutdown；流程通过受限字段引用连接，不能执行 shell、JS 或 Rust 表达式。update_token 参数由凭据引用注入，不允许 schema 模式诱导用户把 Token 写入脚本。

单次调用默认等待 Ready；通过场景精确测试认证但未就绪、Token 更新及退出取消。call 对纯本地/helper 方法仅创建所需实例或直接执行，不强制不必要的登录；所有 lifecycle 方法使用场景入口，生命周期覆盖映射写入清单。存储 trait/回调是 Rust 宿主注入点，不能序列化成 JSON 实例；CLI 不动态加载用户代码，扩展入口通过 Rust 宿主示例和存储契约测试覆盖。

命令描述表绑定输入 DTO、校验、结果 DTO、调用分派及副作用元数据，schema 与执行使用同一类型来源。CLI DTO 专门将 Seq、TimestampMs、字节数等 i64/u64 序列化为十进制字符串，Rust DTO 不改变类型。默认 stdout 为单个 `schema_version=1` 的结果，listen 为 NDJSON 事件/终止摘要，stderr 为脱敏诊断和进度。二进制字段定义 base64 表示，大媒体优先文件路径，不把任意原始字节直接打印到终端。

固定退出码见 rust-sdk-cli 规格。场景先保留引发终止的根因，再标记下游 skipped；断言失败用 5，认证/网络/存储等前置失败用对应类别，缺少凭据用 2，中断用 130。默认整体期限为 60 秒，smoke 为 120 秒，调用可显式覆盖；listen 要求有限持续时间/数量或显式 --follow。Ctrl-C 触发取消并最多等待 5 秒清理，报告保留真实根因。

dry-run 只做输入和引用结构校验、输出脱敏预览，不创建 SDK、不打开账号数据库、不读凭据内容或媒体文件；依赖运行结果的字段仅验证引用存在与目标形状，不宣称已验证动态值。测试命令和场景写操作由显式命令触发，不额外引入交互确认。

备选：为每个接口单独维护命令模型会漂移，REPL 也不便 CI；采用统一类型化 call + 数据场景和少量专用命令。

### 10. 验证分层与真实 smoke

| 层次 | 核心证据 |
| --- | --- |
| 编译/API | 文档接入示例、全部方法签名和模型、Send/Sync、crate 依赖方向、fmt/clippy |
| 核心确定性 | 可注入时钟/网络/存储的重复、乱序、取消、失败重发、终态合并、epoch、事件溢出、崩溃恢复；以默认 SQLite、显式 SQLite、自定义接口与回调参数化运行相同 SDK 行为场景，对比消息/游标/事件/错误的一致性，恢复比较限定持久实现 |
| 边界契约 | 共享 proto golden fixtures、未知事件原始字节、HTTP 路由/DTO、2xx 业务错误及非 2xx、分页及大序号 |
| CLI 进程 | 帮助/schema、无 TTY、JSON/NDJSON、退出码、dry-run 无副作用、信号、Token 脱敏 |
| 真实服务 | CLI smoke 及有权限前置条件的领域场景，记录实际环境、命令和结果 |

smoke 从已有 A/B 账号和 Token 开始，先订阅并登录两个不同设备/目录实例，等待 Ready；A 发送带显式 client_msg_id 的消息，B 验证接收且不重复；重复提交同身份验证幂等，用历史复核身份和序号；将 B 实例 shutdown 后由 A 发送，再新建 B 以相同目录/设备登录，验证离线同步和重启缓存恢复。A/B 关闭所有句柄，按所用临时目录归属清理。仅当 SDK 真正进入 Failed 再调用 resend 才能报告重试覆盖，真实 smoke 的重复成功发送不计作该分支证据。

关系/群/媒体/搜索/合规的完整 API 以本地契约测试覆盖，并配套可执行真实场景；受账号权限、功能开关和服务端当前能力限制的分支必须列明未执行原因。当前 full_resync 分支使用受控 mock 验收，不改服务端实现来制造通过。没有可用真实环境时实施不能声明 E2E 通过，需保留未完成的真实联调任务及前置条件。

## Risks / Trade-offs

- [Rust 文档仍标为草案，范围较大] → 以当前 103 项方法和正文句柄接口作为审阅基线，覆盖清单逐项验收；实施中如需改变公开契约先明确差异并确认。
- [服务端快照不是跨分页事务，且 full_resync 标记未启用] → 使用保守重放基线、staging 和故障测试；明确真实 E2E 覆盖限制，不宣称服务端具备窗口淘汰能力。
- [未知 proto 字段与历史 JSON 可能被普通映射丢弃] → 原始字节识别及独立 fixtures，覆盖未知 oneof、枚举和超大序号。
- [默认 SQLite 与自定义实现的提交保证不同] → 公布统一事务/flush 契约与持久性声明，接口和回调运行同一套契约测试；错误不切换后端，不在持核心锁时调用宿主。
- [SQLite 阻塞、目录竞争及取消提交竞态] → 专用执行队列、账号排他租约、事务阶段测试和存储屏障；不在持核心锁时进行 IO。
- [账号库包含明文聊天记录] → 使用应用专用目录及权限，不保存 Token；文档明确本次未承诺静态加密，不将其表述为生产加密方案。
- [媒体目标提交和重定向存在边界复杂度] → 不覆盖原文件、失败清理临时文件、仅对平台来源传认证头，并用文件/HTTP 故障用例验证。
- [依赖和 TLS feature 的目标兼容性尚未构建验证] → 首步锁定可用工具链和依赖组合，仅声明实际验证的宿主平台；移动目标支持另行验收。
- [CLI 可能独立复制领域逻辑或泄漏 Token] → 限制为公开 SDK 依赖、统一方法分派，测试错误/日志/dry-run 脱敏和无 TTY 运行。

## Migration Plan

1. 新增 workspace、公开类型和组合骨架，完成存储/协议/任务基础后实现核心链路；不接管现有 JS 或 PC 实例。
2. 逐组完成 API 与契约验证，再落 CLI 和真实服务验收；本地 Cargo.lock 和文档记录已验证工具链，按仓库路径集成。
3. 更新 SDK 文档的 Rust 实现状态、源码依赖方式、已验证范围和 CLI 操作，保留用户已有改动。原草案状态仅在实际实现及检查完成后调整。
4. 回退时停止 CLI/宿主使用并移除源码依赖；不涉及服务端 schema 或协议回滚。内置 SQLite 账号目录由用户按明确路径处理，新版缓存不可强行由旧版打开；自定义存储由宿主按其版本和资源策略处理，SDK 不擅自转换或迁移到 SQLite。
