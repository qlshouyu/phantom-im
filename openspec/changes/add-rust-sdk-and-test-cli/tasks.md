> 2026-11-14：已完成 68/68 项。全量测试 131 项通过，fmt、Clippy、build、生成器稳定性及覆盖/文档核验通过。三个账号均通过正常 BFF 登录取得 Token；真实双账号 smoke 六步通过，六份适用领域场景通过。群标准场景 11 步及三账号扩展 35 步通过，各轮专用群已解散并核对三个账号群列表。未成年人模式写操作单列未执行，未计为通过。账号、Token 和原始报告存私有项目知识库，脱敏证据见 [Rust 验证记录](../../../phantom-platform/sdks/rust/verification.md)。

## 1. Workspace 与公开契约

- [x] 1.1 在 `phantom-platform/sdks/rust/` 创建设计中的八个 SDK crate 和 CLI crate，锁定实际可构建的工具链、依赖 feature 与 Cargo.lock；通过 `cargo metadata` 和 `cargo check --workspace --locked` 验证独立构建及不依赖 Tauri/Maven/npm。
- [x] 1.2 建立 `api-coverage.md`，逐项登记 Rust 接口表 103 项、subscribe、订阅/取消句柄方法及所有 DTO/事件，并补充 ClientOptions 默认构造、可选存储接口/回调、存储契约和相应 Rust 宿主示例入口；记录锚点、路由/命令和测试位置，核对无遗漏和未经说明的 Unsupported 占位。
- [x] 1.3 实现 domain 公共模型、SDK 错误、ID/分页/数值/内容校验和状态合并，覆盖 None/Unknown、2^53 以上序号、Unicode 文本长度及终态防倒退；通过对应模型与边界用例。
- [x] 1.4 定义 ports 的公开 StoreFactory/Store、类型化 StorageNamespace/记录/原子变更、持久性及契约版本，以及 HTTP/WS、时钟、调度、文件和诊断接口，提供内存/可控故障测试替身；通过核心测试注入验证，并以依赖检查确认 core 只依赖 domain/ports。

## 2. 协议与传输适配

- [x] 2.1 从五份共享 proto 建立 Rust 生成流程和变更追踪，不复制协议源；通过 Envelope、认证、消息和全部 12 种同步事件的二进制 fixture 验证字段与命令映射。
- [x] 2.2 实现未知枚举、未知内容及 SyncEvent oneof 原始字段识别/保留；通过已知/未知事件混排的独立 wire fixture 验证字段号和字节无损。
- [x] 2.3 实现 base_url 校验、HTTP/WS 前缀推导、动态路径编码、WS cid 请求配对及待请求清理；通过有前缀 URL、非法 URL、并发/迟到/错误命令响应测试。
- [x] 2.4 实现 HTTP 鉴权、ApiResponse 解析和错误上下文，按 Controller/DTO 映射 JSON；通过 2xx 非零 code、非 2xx、缺失/损坏响应、超时及请求标识保留测试。
- [x] 2.5 实现 HTTP 历史消息/proto JSON 归一化和真实分页映射；通过 CONV_TYPE_GROUP、content_json、int64 字符串、缺失 mentions/引用类型及终页测试。
- [x] 2.6 实现受控媒体 URL 访问及认证头来源限制；通过签名 URL、缩略图回退、跨来源 URL/重定向测试，确认凭据不发给非平台来源。

## 3. 事务存储与恢复

- [x] 3.1 实现等价事务接口的内置 Memory 和 SQLite，以及配置留空时的平台默认目录与显式 Directory、消息/会话索引；通过默认构造、目录解析/权限失败、提交/回滚及两种内置模式一致性用例，验证不静默降级到 Memory 或临时目录。
- [x] 3.2 将服务地址/app_id/user_id 命名空间及账号写入租约纳入所有存储实现的契约；通过跨环境、跨账号、特殊字符 ID、同库双写冲突及自定义后端命名空间隔离测试。
- [x] 3.3 实现 Inbox、Outbox、投影和 user_seq 的事务提交与 flush 屏障；通过任意阶段写入失败注入验证无半提交、无超前 ACK、flush 返回真实错误。
- [x] 3.4 实现内置 SQLite schema 元信息、已知版本迁移、损坏/未来版本拒绝及回滚，自定义存储校验契约版本并由使用者管理物理迁移；通过升级/失败/损坏 fixture 及自定义版本不兼容测试验证原始数据保留。
- [x] 3.5 实现未完成发送、草稿、游标、删除/清空/焚毁标记和展示截止时间的重启恢复；通过重新打开目录验证稳定 client_msg_id、Unknown 发送状态及不自动重放写入。
- [x] 3.6 将收发、同步及 HTTP history 分页结果经存储接口统一幂等保存，SQLite 为默认实现，自定义后端不双写 SQLite；通过实际 SQLite 查询、自定义存储调用记录、持久后端历史分页后 flush/重建实例、去重及撤回/焚毁清除正文用例。
- [x] 3.7 打通使用者实现 StoreFactory/Store 的公开注入入口，覆盖打开、恢复/查询、原子变更、flush、清理和关闭；通过独立自定义实现运行消息/游标事务与恢复用例，验证不依赖 SQLite 表结构且默认库未创建。
- [x] 3.8 实现 StorageCallbacks 和 CallbackStoreFactory，统一适配存储接口；通过完整回调序列、缺失必需回调 InvalidArgument、异步 Send/Sync 和失败回调测试，验证回调错误不触发 SQLite 回退。
- [x] 3.9 建立共用存储契约测试覆盖内置 SQLite、Memory、自定义接口及回调，确认 SQLite 与用户实现使用同一 StoreFactory/Store 契约；验证原子变更、持久性声明、提交失败不 ACK、不双写、clear_cache 仅当前账号及 close 不关闭宿主共享资源。
- [x] 3.10 提供使用者自定义接口和回调两种可编译接入示例，覆盖配置留空使用 SQLite、选择自定义后端及其资源/版本管理；通过示例构建、契约测试和文档步骤验证。

- [x] 3.11 将同一套 SDK 收发、历史合并、终态、游标/ACK、失败和持久恢复场景参数化运行于默认 SQLite、显式 SQLite、自定义接口和回调实现；对比公开快照、事件和错误语义一致，并检查 Kernel 无后端类型判断或 SQLite 专用业务路径。

## 4. 运行时、生命周期与调用上下文

- [x] 4.1 实现共享运行时、每实例命令队列、同步快照及可选存储组装，在 Facade 定义 ClientOptions::new 与 Option<StorageOptions> 并导出存储扩展类型；通过默认 SQLite、显式目录/Memory/Custom 选择、PhantomClient Send/Sync、Future Send 及依赖方向验证。
- [x] 4.2 实现 CancellationToken、整体 deadline、调用提交阶段和单次完成控制；通过预取消、子步骤超时、响应/取消竞争测试验证 NotSubmitted/Rejected/Unknown 和无永久等待。
- [x] 4.3 实现 new、get_state、get_current_user_id、get_app_config、get_sync_status、login 与 wait_ready；通过认证先于 Ready、错误身份、重复登录 Conflict、并发 Busy 和取消回 Closed 测试。
- [x] 4.4 实现服务端心跳周期、失活判定、带抖动的有界退避、reconnect 和 TokenExpired/Kick 处理；通过虚拟时钟验证退避、停止旧凭据重试及禁止被踢后抢回。
- [x] 4.5 实现 update_token 的同身份认证和凭据切换；通过 HTTP/WS 新凭据生效、跨账号/跨应用拒绝、失败保留身份及旧连接迟到 Kick 隔离测试。
- [x] 4.6 实现 logout、shutdown 和 epoch/generation 校验，关联存储屏障及 clear_cache；通过退出/释放取消、幂等释放、Released 方法限制和旧响应/事件不污染新账号测试。
- [x] 4.7 实现独立订阅队列、单调 event_id、session、next/close 和 EventLagged 终止；通过慢消费者溢出、多订阅互不抢占、next 取消、退出清队列和释放关闭测试。

## 5. 消息与同步引擎

- [x] 5.1 实现 single_conv_id、send_text/send_message、稳定逻辑身份、Sending/Failed 事件及 Outbox 先提交再发送；通过单聊/群/各内容类型、离线拒绝和同身份并发 Busy 测试。
- [x] 5.2 实现 resend 及发送响应/自身推送的去重和终态合并；通过超时后迟到确认、Failed 重试复用身份、已成功重复发送及 Read/撤回不倒退测试。
- [x] 5.3 统一 MsgPush、SyncEventPush 和 SyncResp 处理，落实投影/Inbox/游标原子提交及提交后 ACK；通过重复、乱序、跳号、存储失败重放及事件只在提交后发布测试。
- [x] 5.4 实现增量分页及目标游标跟踪；通过 has_more、多页、合并导致的序号不连续、空页但目标未达及 max_user_seq 查询竞态测试，验证不盲跳目标游标。
- [x] 5.5 实现全部 12 类同步投影/失效刷新和无正文消息按引用补取；通过关系/群/资料/回执/举报等事件、未知分支及引用补取失败测试。
- [x] 5.6 实现首次快照恢复、full_resync staging、保守基线回放及原子切换；通过 mock 触发全量、分页中推送/撤回、重建失败和长期无进度测试，验证不遗漏或复活数据。
- [x] 5.7 实现 recall、forward、delete_local、clear_history、history、get_messages；通过撤回终态、转发不盲重试、真实分页、缓存排序及删除/清空后历史不复活测试。
- [x] 5.8 实现 mark_message_displayed、首次展示 TTL、Burned 和重启截止时间；通过虚拟时钟验证重复展示不重置、TTL=0、非本地消息 NotFound、恢复后正文不出现且不替代 mark_read。

## 6. 会话、关系与群领域

- [x] 6.1 实现会话列表/详情/缓存、置顶/免打扰、删除、未读汇总及乐观变更；通过分页、服务端拒绝回滚和回滚不覆盖较新同步状态测试。
- [x] 6.2 实现 HTTP mark_read/get_read_position、save_draft/clear_draft 和 handle_message_request；通过已读只前进、edited_at 冲突、草稿恢复及陌生人请求处理测试。
- [x] 6.3 实现 get_user_profile/update_profile、关系设置及功能开关校验；通过具体 DTO、Option 更新语义、权限/开关拒绝和资料同步测试。
- [x] 6.4 实现好友列表、申请创建/列表/处理、好友设置/删除和黑名单；通过各接口路由/DTO 契约测试、操作错误及对应投影刷新验证。
- [x] 6.5 实现好友分组和二维码读取/刷新/扫描；通过分组增改删、二维码结果及失效 Token 契约用例，核对 API 清单。
- [x] 6.6 实现群列表、创建、详情、资料、成员列表/邀请/移除及角色设置；通过路由/DTO 和权限拒绝测试，并验证群事件更新投影。
- [x] 6.7 实现群主转让、退群/解散、禁言/解禁；通过 old_owner_role、允许禁言时长/Permanent、权限失败及群终态测试。
- [x] 6.8 实现公告读取/更新、群名片、创建邀请、邀请入群及审批；通过公告已读副作用、版本、邀请有效期和待审核/直接加入的强类型结果测试。

## 7. 媒体与宿主能力输入

- [x] 7.1 实现统一 FileSource 和 Path/Bytes 默认适配、摘要及 MIME/大小/时长校验；通过原字节保留、FILE 兜底和图片/语音/视频非法参数测试。
- [x] 7.2 实现 init_upload、upload_part、complete_upload 和高层 upload_media；通过缺片续传、秒传、etag/字节数、重复 operation_id 和完整 MediaInfo 测试。
- [x] 7.3 实现高层上传 deadline/取消及 TransferProgress；通过初始化、分片和完成阶段的取消及进度不超总量测试。
- [x] 7.4 实现 get_media/get_media_content、download_media 和 send_media；通过原图/缩略图、临时文件清理、不覆盖提交竞态、下载中断和权威元数据组装测试。
- [x] 7.5 实现设备列表/远程下线、推送注册/解绑、推送设置和隐私链接；通过类型化 HTTP 契约、同账号设备绑定和 Option 更新/清空语义测试。
- [x] 7.6 实现 Typing、Presence、set_app_state 和 handle_notification_click；通过 3 秒节流、false 立即提交、去重人数边界、业务确认和点击数据原样交付测试。

## 8. 搜索与合规领域

- [x] 8.1 实现 search、search_suggestions 和 search_conversation；通过结果权限/定位字段、limit 边界、取消旧搜索和无悬挂 Future 测试。
- [x] 8.2 实现 report/list_reports、submit_appeal/list_appeals；通过各输入/返回 DTO、真实列表/筛选形式、业务码保留和提交后取消不自动重试测试。
- [x] 8.3 实现 export_account/get_account_exports 和 set_minor_mode；通过数组及 payload_json 原样返回、不自动创建文件和服务端拒绝用例。

## 9. CLI 命令与场景执行

- [x] 9.1 创建 CLI 帮助/版本/配置和凭据引用解析，存储留空直接采用 SDK 默认 SQLite，支持显式内置模式/目录但不动态加载自定义代码；通过默认持久化、Memory 不建库、目录参数冲突、无凭据帮助、配置错误退出、Token 脱敏及 Cargo 依赖检查。
- [x] 9.2 建立公共方法描述/分派与 schema 命令，处理文件/stdin 输入、CLI 整数十进制字符串和二进制 base64；通过全部方法映射、schema/执行一致、未知字段/错误数值和超大序号往返测试。
- [x] 9.3 实现 call 的必要登录/Ready、类型化调用和有界释放；通过成功/SDK 错误、无需登录的 helper、本地读取和持久目录重复运行的进程测试。
- [x] 9.4 实现命名实例/凭据/订阅、受限引用、call/wait_event/assert/cancel 等场景步骤，支持生命周期方法；通过同进程连续状态、跨步骤变量、并发取消及非法引用预检测试。
- [x] 9.5 实现 listen 的 NDJSON、持续时间/事件数/显式 follow、Ctrl-C 取消及 5 秒清理上限；通过无 TTY、超时退出、中断和订阅关闭测试。
- [x] 9.6 实现 call/scenario dry-run 和副作用元数据，包括公告读取标记已读；通过监测无 SDK 创建、无网络、无缓存/媒体/凭据读写及脱敏摘要的测试。
- [x] 9.7 实现 schema_version=1 JSON/NDJSON、stderr 诊断、稳定退出码和 passed/failed/skipped 根因报告；通过逐类 SDK 错误、断言失败、必需步骤 skipped 及 stdout 可解析进程测试。
- [x] 9.8 实现双账号 smoke：显式使用隔离的临时 SQLite 目录验证 Ready、收发、成功身份重复提交幂等、历史、离线补齐及重启恢复；通过受控平台验证断言和超时清理、不删用户持久化目录，不将成功重复发送计为 Failed/resend 覆盖。
- [x] 9.9 提供覆盖关系/群/媒体/设备/搜索/合规的可执行场景文件和前置条件清单；通过 schema/dry-run 验证全部示例，不把权限不足的场景计为通过。

## 10. 集成验收与文档

- [x] 10.1 完成基于 workspace crate 的 Rust 接入示例，验证文档的订阅/登录/Ready/发送/退出/释放流程以及全部公开类型和 Send/Sync 约束，使用 `cargo test --workspace --locked` 运行针对性测试。
- [x] 10.2 执行 `cargo fmt --all --check`、`cargo clippy --workspace --all-targets --locked -- -D warnings` 和 `cargo build --workspace --locked`；记录实际工具链及已验证宿主平台，修复阻断问题。
- [x] 10.3 使用可用 Phantom 单体、中间件及两个预置有效测试账号执行 CLI smoke，保存脱敏命令/环境/报告；必须实际验证消息、历史、离线同步和重启恢复，环境不足时保留本项未完成并写明缺失前置条件。
- [x] 10.4 在具备权限/功能开关的测试环境执行适用的完整领域场景，记录通过、失败和未执行原因；full_resync 和故障重发明确引用受控测试证据，不声称真实服务触发了这些分支。
- [x] 10.5 核对 api-coverage 清单的 103 项及句柄/DTO/事件、新增默认构造与存储接口/回调全部实现；检查 CLI 映射或 Rust 宿主扩展示例及验证证据，确认无范围内占位能力，通过覆盖清单审阅。
- [x] 10.6 补充 Rust workspace README、Rust 接入/构建/测试/CLI 文档、前置条件和缓存策略；同步接口文档中可选存储、默认 SQLite 目录、接口/回调完整示例和自定义存储责任，更新架构/集成文档的实际实现状态并保留用户已有修改；验证所有示例命令/schema 和本地链接可用，不宣称未验证的语言绑定或发布产物。
