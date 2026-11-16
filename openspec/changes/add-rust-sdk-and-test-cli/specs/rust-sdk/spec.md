## Purpose

定义 Phantom 原生端共享 Rust SDK 面向 Rust 宿主的完整行为契约，统一公开接口、生命周期、消息与领域能力、同步持久化、错误及事件语义，为后续语言包装和直接调用提供可验证基础。

## ADDED Requirements

### Requirement: 完整的类型化 Rust 公共 API

SDK SHALL 实现 `docs/phantom-platform/sdk/integration.html#rust-core` v0.1 列出的全部公共函数、方法、输入、返回模型、枚举和事件，包括初始化与登录、消息、媒体、会话、用户与好友、群、设备与推送、搜索与合规及订阅。公开接口 MUST 隐藏协议和数据库结构，保持 `PhantomClient: Send + Sync`、可发送的异步 Future 和独立返回快照。ID、序号、时间、缺失字段、未知枚举和参数约束 MUST 遵循文档；Seq 使用非负 i64，时间使用 Unix 毫秒。SDK SHALL 提供可编译的接入示例和逐项 API 覆盖清单，不以占位 `Unsupported` 替代本次范围内的已定义能力。存储配置以本变更的可选接口/回调及默认 SQLite 要求覆盖原草案中必填的 Memory/Directory 配置，相关新增公共类型和默认构造方式 MUST 同步到接口文档。

#### Scenario: 按接口文档编译调用
- **WHEN** Rust 宿主依照文档创建实例、订阅、登录、等待就绪、发送消息并释放
- **THEN** 示例可以使用 workspace 内 SDK crate 编译执行，调用方无需接触 Protobuf 或 SQL

#### Scenario: 大序号与缺失字段
- **WHEN** HTTP 或 Protobuf 返回超过 2^53 的合法序号，且历史未提供 mentions 或引用消息类型
- **THEN** SDK 精确保留序号，将缺失字段返回为 None，不补造空集合或类型

### Requirement: 服务地址与传输兼容

SDK SHALL 仅接受合法 http/https base_url，保留路径前缀并按协议转换及追加 `/ws` 得到 WebSocket 地址；查询参数、片段及非法配置 MUST 返回 InvalidArgument。SDK MUST 使用现有 WebSocket 二进制 Envelope、请求 cid 配对、命令及 HTTP 路由、鉴权和业务响应封装，不通过新增私有协议替代平台契约。HTTP 2xx 且业务 code 非零及非 2xx 均 MUST 报错并保留可取得的业务码、HTTP 状态和请求标识。

#### Scenario: 带前缀的服务地址
- **WHEN** 使用 `https://example.test/phantom/` 初始化并登录及查询历史
- **THEN** 连接地址为 `wss://example.test/phantom/ws`，HTTP 调用保留 `/phantom` 前缀，动态路径标识被正确编码

#### Scenario: HTTP 业务失败
- **WHEN** 服务端以 HTTP 200 返回非零业务 code
- **THEN** 调用返回结构化 Business 或 Authentication 错误，不向宿主报告成功

### Requirement: 登录、就绪与串行会话操作

SDK SHALL 实现 new、状态读取、login、wait_ready、update_token、reconnect、logout、flush_storage 和 shutdown 的文档语义。login SHALL 在认证成功时返回 LoginResult，wait_ready SHALL 在当前登录数据恢复并同步至认证目标游标后返回 Ready。已有会话再次登录 MUST 返回 Conflict；登录、重新认证、重连之间的并发冲突 MUST 返回 Busy；退出和释放 MUST 能优先结束请求。失败、超时或取消的登录 MUST 关闭本次连接并回到 Closed，不在后台继续本次登录。

#### Scenario: 认证完成但尚未就绪
- **WHEN** 登录成功而认证目标游标尚未追平
- **THEN** login 返回认证信息并发布 Connected，wait_ready 继续等待，在数据恢复完成后返回且发布 Ready

#### Scenario: 并发登录与取消
- **WHEN** 首次登录尚未完成时再次登录，随后取消首次登录
- **THEN** 第二次登录返回 Busy，首次登录返回 Cancelled，本次连接被关闭且不遗留后台登录

### Requirement: 重连、心跳与凭据失效

SDK SHALL 使用认证返回的心跳周期并检测连接失活；意外断线 SHALL 以 1、2、4 秒递增、最多 60 秒并带 ±30% 抖动的有界退避重连，重新认证并补齐同步。SDK MUST 在 Token 过期时通知宿主并停止无效凭据重试；收到 KickPush 或吊销后 MUST 停止自动重连并拒绝通过 reconnect 抢回会话。update_token MUST 经服务端认证确认属于当前应用和账号，再替换 HTTP/WS 后续凭据；失败不得切换账号。

#### Scenario: Token 过期后的更新
- **WHEN** 当前 Token 过期，宿主提交同账号的新 Token
- **THEN** SDK 通知 TokenExpired、停止旧凭据重试，成功重新认证后所有后续请求使用新凭据

#### Scenario: 被踢后显式重连
- **WHEN** SDK 收到同端登录或吊销原因的 KickPush 后，宿主调用 reconnect
- **THEN** SDK 保留原因并通知 Kicked，不建立抢回连接，要求先 logout 再由宿主决定 login

### Requirement: 账号隔离与释放

SDK MUST 隔离服务地址、app_id、user_id 和账号会话的数据、异步结果、连接及事件。logout SHALL 取消旧账号请求、清除当前身份和内存状态、丢弃订阅中旧账号待投递事件；clear_cache=false SHALL 保留持久缓存，true SHALL 清除该账号缓存。logout 的取消不得中断身份清理。shutdown MUST 幂等释放任务及订阅；即使等待超时或取消也进入 Released，之后仅 get_state 和重复 shutdown 可调用，既有订阅句柄仍可 close/next。Token SHALL 由宿主提供，不自动落盘，不接收 AppSecret。

#### Scenario: 切换账号时收到旧响应
- **WHEN** 用户 A 退出后登录 B，A 的历史响应、发送确认或订阅待投递事件晚到
- **THEN** B 的缓存、游标和事件不包含 A 的数据

#### Scenario: 释放等待被取消
- **WHEN** shutdown 等待期间收到取消
- **THEN** 实例仍进入 Released 并停止所属任务，后续业务调用返回 Released，订阅可观察关闭

### Requirement: 消息发送身份与终态合并

SDK SHALL 实现全部已定义内容的 send_message、单聊 send_text、resend、recall 和 forward，并遵守引用、mentions、大小、时长及 Unicode 码点限制。发送仅在 Online 时受理，不提供离线新消息提交。受理后 MUST 保留稳定 client_msg_id、消息及发送记录，发布 Sending；确认后返回服务端身份和序号；失败 MUST 返回带 client_msg_id 的错误并保留 Failed 快照。同一身份并发发送返回 Busy，已成功身份返回既有确认消息。重试 MUST 复用原身份且不得改变原会话和正文；响应与自身推送 MUST 合并同一逻辑消息，Sent 不得覆盖 Delivered/Read，撤回或焚毁的正文不得恢复。无幂等键的转发 MUST NOT 自动重试。

#### Scenario: 超时重发与晚到推送
- **WHEN** 一次发送提交后超时，随后自身推送和同 client_msg_id 的重发确认先后到达
- **THEN** 首次调用返回 outcome=Unknown 的错误，最终仅保留一条逻辑消息，状态按确认结果前进且不重复插入

#### Scenario: 断线提交新消息
- **WHEN** 实例有登录身份但连接为 Reconnecting，宿主发送新消息
- **THEN** 返回 NotConnected，不创建离线待发消息，也不在网络恢复后擅自发送

### Requirement: 接收、同步与持久化进度一致

SDK SHALL 将 MsgPush、SyncEventPush 和 SyncResp 纳入统一有序处理，对重复或旧 user_seq 幂等去重，对跳号主动发起增量同步并遵守服务端分页及事件合并语义。消息/领域投影、已处理记录及 user_seq MUST 原子提交；所有存储实现完成约定的事务提交后才可发送累计 MsgAck 和发布可靠领域事件；持久实现 MUST 确认数据已达到其声明的持久性保证。存储失败 MUST 保留可重放进度，不得推进 ACK 或报告 Ready。ACK MUST 不越过尚未完成处理的进度；重复消息允许重发已提交 ACK。SDK MUST 覆盖当前 12 种 SyncPayload，并对未知新增事件保留字段号及原始字节，不中断其他已知事件。

#### Scenario: 接收提交失败
- **WHEN** 新消息已收到但本地事务提交失败
- **THEN** 不确认该未提交进度、不发布已提交的 MessageChanged，返回或发布 Storage 错误，恢复后可重新拉取处理

#### Scenario: 在线乱序与重复
- **WHEN** 在线推送先到较大 user_seq，再通过同步补齐此前事件并重复收到该推送
- **THEN** SDK 不直接越过缺口 ACK，按已完成同步进度提交，各领域只应用一次且顺序正确

#### Scenario: 未知新增事件
- **WHEN** 同步批次在已知事件之间含有未知 oneof 字段
- **THEN** SDK 保留未知事件字段号与字节，通过 Unknown 通知并安全提交相应进度，其余已知事件正常处理

### Requirement: 引用补取与全量恢复

SDK SHALL 在新消息事件缺少内联正文时按会话和序号引用补取，补取后更新同一消息。收到 full_resync=true 或首次无缓存恢复时，SDK SHALL 分页获取会话及所需历史和领域快照，协调重建期间的在线推送，从安全快照基线继续增量同步。重建失败 MUST 不覆盖已提交可用状态或误报 Ready；缺失正文不得伪造为正常消息。全量重建 MUST 不复活已删除、清空、撤回或焚毁内容。

#### Scenario: 仅含引用的新消息事件
- **WHEN** 收到带会话和序号引用但 msg=None 的新消息事件
- **THEN** SDK 通过现有历史查询补取，完成后以 MessageChanged 暴露真实消息，不伪造正文

#### Scenario: 重建期间有新事件
- **WHEN** full_resync 触发分页重建，其间收到新消息和撤回推送
- **THEN** SDK 从安全基线补齐新事件，最终游标和投影一致，撤回正文不重新出现，之后才发布 Ready

### Requirement: 可注入存储接口与默认 SQLite

SDK SHALL 提供公开的、与数据库技术无关的存储接口及等价回调适配方式，由 SDK 使用者实现自己的存储。配置 MUST 是可选的，提供只填写服务地址和 app_id 就能采用默认存储的配置构造方式；未设置存储接口、回调或显式内置模式时 MUST 使用 SQLite，不得默认为 Memory。使用者 SHALL 可显式指定内置 SQLite 的目录或选择 Memory。默认 SQLite SHALL 使用平台标准用户应用数据目录下的 SDK 专用目录，并隔离服务地址、应用和账号；无法解析或访问目录 MUST 返回 Storage，不得悄悄使用临时目录或内存。

自定义存储 SHALL 是当前 Client 的唯一存储后端，覆盖消息、会话、领域投影、Inbox、Outbox、游标和终态标记，不向内置默认 SQLite 双写。接口及回调 MUST 使用类型化存储记录/变更，不要求使用者理解 SQLite 表或 Protobuf。配置 SHALL 仅在创建 Client 时确定；回调集合缺少必需操作 MUST 在使用前返回 InvalidArgument。自定义实现的打开、查询、提交、清理或刷新故障 MUST 返回 Storage；账号写入租约竞争 SHALL 返回 Conflict，不得切回 SQLite。接口实现和回调适配 SHALL 遵守同一套账号隔离、批次原子性、持久性声明、错误及 flush/关闭契约。

#### Scenario: 未提供存储配置
- **WHEN** 使用者通过默认配置构造方式创建 Client，未设置存储接口、回调或其他存储模式
- **THEN** SDK 采用内置 SQLite 保存本地历史和同步状态，并支持同环境/账号重启恢复

#### Scenario: 使用自定义存储接口
- **WHEN** 使用者注入自己的存储接口实现并执行收发、历史、flush 和退出
- **THEN** SDK 仅调用该实现处理相应存储，不打开默认 SQLite 或向其写入数据

#### Scenario: 使用存储回调
- **WHEN** 使用者提供完整的存储回调集合，完成打开、加载、查询、原子变更、刷新和关闭
- **THEN** SDK 通过统一适配层使用这些回调，消息与游标具有与接口实现相同的提交语义

#### Scenario: 回调缺少必需操作
- **WHEN** 使用者配置了自定义回调，但未提供原子提交等必需操作
- **THEN** SDK 在使用前返回 InvalidArgument，不把不完整配置解释为未配置，也不回退 SQLite

#### Scenario: 自定义存储故障不切换后端
- **WHEN** 已配置的自定义存储无法打开或事务提交失败
- **THEN** SDK 返回 Storage，不创建默认 SQLite、不推进未提交进度及 ACK

#### Scenario: 默认目录不可用
- **WHEN** 未设置存储配置，但系统无法解析或写入默认 SQLite 目录
- **THEN** SDK 返回 Storage，使用者可重新创建实例并指定目录或自定义存储，不静默改用非持久存储

### Requirement: 所有存储实现共享同一契约与业务语义

内置 SQLite MUST 实现与使用者自定义接口完全相同的公开存储契约，Memory 和回调适配也 MUST 实现该契约。未设置存储时，SDK SHALL 选择 SQLite 的契约实现，消息、同步、历史、恢复、清缓存和 flush 均通过所选契约实现执行。SDK MUST 对所有实现使用相同的消息去重、状态合并、终态处理、账号隔离、提交后 ACK 和可靠事件规则；不得对默认 SQLite 使用另一套业务语义或绕过存储契约。

SDK SHALL 使用共用契约测试及相同 SDK 行为场景验收所有实现。在声明能力相同且输入、时间和故障条件相同的情况下，各实现 MUST 产生一致的逻辑消息快照、游标、事件语义与错误结果；仅物理存储和资源管理方式不同。内置 SQLite 的路径配置差异 MUST 不改变这些语义；声明易失的实现不参与跨进程持久恢复比较。

#### Scenario: 默认 SQLite 和显式 SQLite 行为一致
- **WHEN** 同一收发、历史合并及恢复场景分别使用未设置存储的配置和显式指定 SQLite 的配置执行
- **THEN** 两者均通过同一存储契约，产生一致的逻辑消息、终态、同步进度及事件语义

#### Scenario: 替换存储后业务规则不变
- **WHEN** 在相同能力和输入条件下，将 SQLite 实现替换为自定义接口或回调实现
- **THEN** 消息去重、已读/撤回/焚毁终态、提交失败不推进 ACK、账号隔离与 flush 行为保持一致

### Requirement: 选定存储中的本地聊天历史

SDK SHALL 将已受理的本地发送消息、收到的消息和通过 history 成功获取的历史分页结果合并保存到选定存储，包含消息身份、会话、发送者、类型、可用正文、conv_seq、时间、状态、引用和已提供的 mentions 等字段；已撤回或焚毁的正文 MUST 按终态清除，不得作为隐藏原文保留。相同逻辑消息 MUST 按 client_msg_id/msg_id 幂等合并。存储 SHALL 支持按账号、会话和 conv_seq 查询；对于内置 SQLite 或声明持久化的自定义存储，重新创建实例并登录同一账号后，本地消息读取 SHALL 恢复已保存历史，不需要再次从服务端获取同一批消息。Memory 或自定义易失存储不承诺跨进程恢复。history 的远端分页契约保持不变，本地只保存已收发或明确获取的历史，不承诺自动下载全部服务端历史。

#### Scenario: 历史分页写入后重启
- **WHEN** 采用 SQLite 或持久化自定义存储，history 获取一页消息并完成 flush_storage，随后关闭并以相同命名空间重新创建实例登录
- **THEN** 已保存消息可从恢复的本地快照读取，正文和状态与持久化结果一致，不要求再次请求同一历史页

#### Scenario: 在线消息与历史重叠
- **WHEN** 一条消息先通过实时推送保存，随后同一消息出现在历史分页或同步结果中
- **THEN** 选定存储中仅保留一条逻辑消息，补齐真实可用字段且较新终态不被旧结果覆盖

#### Scenario: 已焚毁消息不保留原文
- **WHEN** 已保存的消息到达焚毁期限，随后从选定的持久化存储重启并读取历史
- **THEN** 对应存储正文已清除，焚毁标记继续生效，历史补取不恢复原文

### Requirement: 存储模式、迁移与崩溃恢复

Memory 模式和声明易失的自定义存储 SHALL 保持同等事务语义但不承诺跨进程保存；默认 SQLite、显式 Directory 模式和声明持久化的自定义存储 SHALL 跨实例恢复账号的消息、会话、草稿、处理进度、删除/焚毁标记及发送身份。崩溃恢复 MUST 将未完成发送保留为可核对/手动重试的状态，不自动重放结果不确定的写操作。flush_storage SHALL 等待本账号待提交状态并报告存储错误。内置 SQLite 的已知旧版本缓存 SHALL 事务迁移；自定义后端 SHALL 管理自身物理格式及版本，SDK SHALL 校验其声明的存储契约兼容性。未知新版本或损坏 MUST 显式返回 Storage，不静默清除或把游标重置为已就绪。同一账号缓存被并发写入 MUST 得到明确冲突结果；清缓存仅作用于当前账号命名空间，关闭实例仅释放所属存储句柄，不关闭宿主共享的数据库或连接池。

#### Scenario: 崩溃后恢复同一发送
- **WHEN** 进程在消息提交本地后、服务端确认落库前退出，随后使用同目录重新登录
- **THEN** SDK 恢复原 client_msg_id 和发送状态，通过查询/推送确认或手动 resend 处理，不生成新的逻辑消息

#### Scenario: 未知缓存版本
- **WHEN** 当前 SDK 打开比自身支持版本更新的缓存
- **THEN** 返回可诊断的 Storage 错误，保留原数据，宿主可显式选择清缓存或新目录重建

### Requirement: 历史、本地删除与展示后焚毁

history SHALL 返回统一类型及真实分页字段，消息按 conv_seq 升序；get_messages SHALL 同步返回缓存快照，未编号 Sending/Failed 消息位于已编号消息之后。delete_local 和 clear_history SHALL 同步隐藏本端缓存并保留防复活标记，通知 MessageRemoved。mark_message_displayed SHALL 对本地消息首次实际展示启动 TTL，重复调用不重置，TTL=0 无操作，不替代 mark_read；到期 MUST 清除正文并通知 Burned，重启或历史补取不得恢复正文。

#### Scenario: 展示后计时并重启
- **WHEN** 宿主首次上报实际展示后退出，TTL 到期后重新启动并拉取历史
- **THEN** 消息保持 Burned 且不返回原正文，重复展示上报不会延长 TTL

#### Scenario: 未缓存消息删除
- **WHEN** delete_local 成功但该消息不在当前缓存
- **THEN** SDK 不伪造某个会话的 MessageRemoved，后续历史遵守服务端删除结果

### Requirement: 会话、已读与草稿

SDK SHALL 实现文档中会话分页、详情、缓存、置顶/免打扰、删除、已读、草稿、陌生人请求及未读汇总接口。乐观更新被拒绝时 MUST 恢复被本次更新影响的值，且不得覆盖并发到达的较新服务端状态。mark_read SHALL 使用当前 HTTP read-positions 契约并保留只前进位置；草稿 SHALL 保留 edited_at 冲突语义。只读缓存查询未登录 MUST 返回 NotLoggedIn。

#### Scenario: 乐观置顶失败时有新同步
- **WHEN** 一次置顶乐观更新后收到较新服务端会话状态，而原请求最终失败
- **THEN** SDK 报告原错误并保留较新服务端状态，不以旧快照覆盖它

#### Scenario: 已读位置回退请求
- **WHEN** mark_read 提交低于已有位置的 read_conv_seq
- **THEN** SDK 保留服务端生效的较大位置，未读及回执不回退

### Requirement: 用户、好友与群完整领域能力

SDK SHALL 实现文档列出的资料、设置、好友申请/处理、好友与黑名单、分组、二维码、群资料、成员、角色、群主转让、退群/解散、禁言、公告、群名片、邀请和入群审批接口，使用具体输入/输出类型及真实可选字段。权限和功能开关拒绝 MUST 保留服务端错误，不在本地伪造成功。对应同步事件 SHALL 更新已加载投影或使其失效后补取，并发布统一领域事件。

#### Scenario: 群成员变更同步
- **WHEN** 当前用户在另一端被邀请入群或被移出群
- **THEN** Rust SDK 应用群事件，刷新或失效相关群/会话投影并通知宿主

#### Scenario: 功能开关拒绝资料写入
- **WHEN** 应用未开启 SDK 资料写入，宿主调用 update_profile
- **THEN** SDK 返回原业务错误，不更改为已成功的本地资料

### Requirement: 媒体任务与受控文件读写

SDK SHALL 实现高层和分片上传、元数据、受控内容读取、下载及媒体发送接口。上传 MUST 校验摘要、MIME、字节数、类型及适用元数据，保持原字节，FILE 无明确类型时支持 application/octet-stream；恢复时仅补传未确认分片，秒传后仍返回完整元数据。operation_id MUST 唯一关联进行中的任务，TransferProgress MUST 反映确认字节且不超过总数。高层超时/取消 SHALL 覆盖初始化、分片和完成等待；下载失败/取消不得将不完整文件作为成功结果，overwrite=false 不得覆盖已有目标。send_media MUST 根据权威媒体元数据生成消息。

#### Scenario: 中断续传与秒传
- **WHEN** 相同文件重新初始化上传，服务端返回部分已传分片或 instant=true
- **THEN** SDK 仅补传缺片或跳过传输，返回完整 MediaInfo，进度和完成事件与实际状态一致

#### Scenario: 下载取消
- **WHEN** 下载过程中取消，且目标路径已有文件而未允许覆盖
- **THEN** 不损坏已有文件，也不将半成品返回为成功结果，调用返回 Conflict 或 Cancelled 对应实际阶段

### Requirement: 设备、推送及实时信令

SDK SHALL 实现设备列表/远程下线、推送 Token 注册/解绑、推送设置、隐私链接、Typing、Presence、前后台上报和通知点击输入。系统通知权限、推送 Token 获取和页面导航 SHALL 由宿主负责。Typing true SHALL 按 3 秒节流，false SHALL 立即提交；Presence 请求 SHALL 校验去重后 1–50 个用户。无业务响应的信令成功仅代表本地提交，set_app_state SHALL 等待业务确认。退出前解绑推送由宿主显式调用。

#### Scenario: 系统通知点击
- **WHEN** 宿主把合法 NotificationClick 输入 SDK
- **THEN** SDK 原样发布应用可消费的通知点击数据，不执行页面导航

#### Scenario: 输入状态节流
- **WHEN** 宿主在 3 秒内重复发送 typing=true，随后发送 false
- **THEN** SDK 合并重复 true 信令并立即提交 false，不宣称已送达对端

### Requirement: 搜索与合规接口

SDK SHALL 实现全局搜索、建议、会话搜索、举报及列表、申诉及列表、账号导出/记录和未成年人模式。结果 MUST 保留权限允许的真实定位、分页或数组形式，不增加不存在的分页。取消搜索 MUST 结束该次调用，旧结果不得写入新账号或覆盖较新状态。账号导出 SHALL 保留服务端数组及 payload_json，不自动写出文件；应用管理、AppSecret 和索引重建 SHALL 不作为客户端能力暴露。

#### Scenario: 取消搜索后继续查询
- **WHEN** 宿主取消旧搜索并提交新搜索
- **THEN** 旧调用返回 Cancelled，新调用独立完成，全部等待有明确终态

#### Scenario: 导出账号
- **WHEN** 宿主发起并查询账号导出
- **THEN** SDK 返回文档定义的导出记录列表，不擅自创建本地导出文件

### Requirement: 超时、取消与写入结果不确定性

SDK MUST 为所有异步调用实现 CallOptions 的整体期限及取消传播；默认使用实例期限，EventSubscription.next 的 timeout=None 允许无限等待。已取消的令牌 MUST 阻止请求提交；调用最终只能以成功、失败、超时或取消之一结束。SdkError SHALL 保留文档要求的类别和关联字段，outcome SHALL 区分 NotSubmitted、Rejected 和 Unknown。已提交后的超时/取消不得宣称服务端已回滚；retryable 不授权自动重试非幂等写操作。

#### Scenario: 预取消写请求
- **WHEN** 使用已取消令牌调用建群或发送消息
- **THEN** 返回 Cancelled 和 NotSubmitted，不发起服务端业务请求

#### Scenario: 已提交写请求取消
- **WHEN** 举报请求已提交而响应尚未到达时取消
- **THEN** 返回 Cancelled 和 Unknown，不自动重试或伪造撤销成功

### Requirement: 独立订阅与事件积压

SDK SHALL 提供容量 1–65536 的独立事件订阅，按实例内单调 event_id 和会话标识交付已提交领域变化；每个订阅只有一个 next 消费者，互不抢占。队列溢出 MUST 使 next 返回 EventLagged 并关闭该订阅，宿主可重订阅及重读快照。close SHALL 幂等丢弃待消费事件，next 超时/取消仅结束本次等待；logout SHALL 清空旧账号队列但保留订阅，shutdown SHALL 关闭全部订阅。

#### Scenario: 慢消费者溢出
- **WHEN** 某订阅达到容量且可靠事件继续到达
- **THEN** 该订阅的 next 返回 EventLagged 后关闭，其他订阅继续工作，不静默丢失一段事件后继续交付

#### Scenario: 等待取消后继续消费
- **WHEN** 一次 next 被取消且订阅未关闭
- **THEN** 本次返回 Cancelled，后续 next 仍可正常收到新事件
