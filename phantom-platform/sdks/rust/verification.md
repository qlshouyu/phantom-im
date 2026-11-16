# Rust SDK 实施验证记录

日期：2026-11-14。关联变更：`add-rust-sdk-and-test-cli`。以下记录区分仓库受控测试与真实平台验收；任务状态见 [OpenSpec 清单](../../../openspec/changes/add-rust-sdk-and-test-cli/tasks.md)。

## 工具链与范围

实际宿主为 `aarch64-apple-darwin`（macOS arm64），`rustc 1.99.1 (58cf8f9c3 2026-10-13)`、`cargo 1.99.1 (5f94df478 2026-08-27)`、`libprotoc 36.2`，edition 2024。工具链固定在 [rust-toolchain.toml](rust-toolchain.toml)，[Cargo.lock](Cargo.lock) 锁定 223 个包。Linux、Windows、移动端、WASM 和外部包注册表发布未验收。

本 workspace 有八个 SDK crate 和一个 CLI crate，独立于 Maven、npm、Tauri。protobuf 直接读取共享的五份契约。项目内依赖检查确认 `phantom-core` 只依赖 domain/ports，CLI 只依赖公开 `phantom-sdk`。默认适配使用进程共享运行时，宿主不在 Tokio 上下文中的调用由 `control.rs` 验证；Client、存储扩展和异步 Future 的 Send/Sync 约束由编译和跨任务调用验证。

## 实际执行的检查

在本目录执行以下命令；`--offline` 仅使用已下载且由锁文件确定的依赖：

```bash
cargo metadata --format-version 1 --no-deps --locked --offline
cargo check --workspace --all-targets --locked --offline
cargo test --workspace --all-targets --locked --offline
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked --offline -- -D warnings
cargo build --workspace --locked --offline
python3 tools/generate_cli.py
python3 tools/generate_coverage.py
```

| 检查 | 结果 |
| --- | --- |
| metadata / 依赖方向 / workspace check | 通过；九个 crate |
| workspace 全量测试（all-targets） | 通过；131 passed、0 failed、0 ignored，38 个测试目标（含示例编译） |
| fmt / Clippy（warnings 视为错误）/ build | 全部通过 |
| CLI 生成器重跑 | 通过；103 项分派生成结果不变 |
| API 清单 | 103 项公开方法、74 项 HTTP 路由、209 项公开类型，另列订阅/取消与存储扩展 |
| 六份领域场景 | schema/dry-run 通过；真实环境六份适用场景通过，群标准场景 11 步和三账号扩展 35 步通过；未成年人模式写操作单列未执行 |
| 文档链接 / HTML 锚点 | 444 项 Markdown 本地文件链接有效；80 个 HTML ID 无重复，页内锚点有效 |
| OpenSpec strict validate | 通过 |

## 受控测试证据

路径均相对本目录；方法、类型和 CLI 入口逐项见 [API 覆盖清单](api-coverage.md)。

| 范围 | 验证内容 | 证据 |
| --- | --- | --- |
| 领域与协议 | Unicode、整数边界、Unknown 原始字节、终态合并；独立二进制 fixture 覆盖全部 12 类同步事件；按 Java Controller/record 构造的 HTTP fixture 覆盖 74 个操作 | [domain/contracts.rs](crates/phantom-domain/tests/contracts.rs)、[wire_contracts.rs](crates/phantom-protocol/tests/wire_contracts.rs)、[http_domains.rs](crates/phantom-protocol/tests/http_domains.rs)、[history.rs](crates/phantom-protocol/tests/history.rs) |
| 传输 | HTTP 鉴权/错误/超时、前缀、实际 request-target 的点段 ID 编码；WS cid 配对、迟到确认及待请求释放；媒体签名 URL 与跨来源凭据限制 | [http.rs](crates/phantom-transport/tests/http.rs)、[connection.rs](crates/phantom-transport/tests/connection.rs)、[gateway.rs](crates/phantom-transport/tests/gateway.rs)、[api.rs](crates/phantom-transport/tests/api.rs) |
| 存储 | Memory、SQLite 的同一事务契约；真实 SQL 查询/索引、迁移与回滚、未来版本/损坏拒绝；独立宿主接口和完整回调序列、版本/命名空间与共享池所有权 | [storage.rs](crates/phantom-store/tests/storage.rs)、[migration.rs](crates/phantom-store/tests/migration.rs)、[callbacks.rs](crates/phantom-store/tests/callbacks.rs) |
| 五种存储接入 | 默认 SQLite、显式 SQLite、Memory、自定义接口、回调均运行收发/历史/游标/ACK/重开/清理；提交失败无半状态和 ACK，恢复后重放；旧历史不能复活撤回正文 | [storage_matrix.rs](crates/phantom-sdk/tests/storage_matrix.rs)、[recovery.rs](crates/phantom-sdk/tests/recovery.rs) |
| 生命周期 | 认证先于 Ready、错误身份、Busy/Conflict、Token 更新与拒绝保留身份；300 条同步事件溢出内部订阅后重新读取状态仍可达到 Ready，外部订阅保留 EventLagged；心跳/退避/TokenExpired/Kick；退出取消和旧 epoch/generation 隔离 | [lifecycle.rs](crates/phantom-sdk/tests/lifecycle.rs)、[lifecycle_clock.rs](crates/phantom-sdk/tests/lifecycle_clock.rs)、[control.rs](crates/phantom-sdk/tests/control.rs)、[sync_engine.rs](crates/phantom-sdk/tests/sync_engine.rs) |
| 收发与同步 | Outbox 先于网络、同身份 Busy、Failed/resend、成功重复身份幂等、晚确认不倒退；增量多页与 gap、补取失败不 ACK、full staging 失败/成功、首分页基线回放和分页中撤回 | [sending.rs](crates/phantom-sdk/tests/sending.rs)、[sync_engine.rs](crates/phantom-sdk/tests/sync_engine.rs) |
| 会话与 TTL | 连续乐观修改失败按不同响应顺序回滚，不覆盖较新同步；已读只前进、草稿冲突；首次展示 TTL 使用单调时钟、持久化失败保留首次期限、重启不恢复焚毁正文 | [conversations.rs](crates/phantom-sdk/tests/conversations.rs)、[burn_clock.rs](crates/phantom-sdk/tests/burn_clock.rs)、[recovery.rs](crates/phantom-sdk/tests/recovery.rs) |
| 领域 Facade | 资料/关系/好友/二维码/群/设备/推送/搜索/合规的类型化分派与投影；取消旧搜索、业务拒绝、邀请 PENDING/JOINED、公告版本、导出 payload_json 原样保留；提交后取消 Unknown 且不盲重试 | [domains.rs](crates/phantom-sdk/tests/domains.rs)、[http_domains.rs](crates/phantom-protocol/tests/http_domains.rs) |
| 媒体 | 原字节 SHA-256、分片/秒传/权威元数据、各媒体消息类型；统一 deadline、各阶段取消、进度；下载原子提交、不覆盖和临时文件清理；挂起上传/下载/内容/元数据不阻塞独立 HTTP/普通发送/退出 | [media.rs](crates/phantom-sdk/tests/media.rs)、[control.rs](crates/phantom-sdk/tests/control.rs) |
| CLI 协议 | 帮助/103 项 schema、stdin/文件输入、十进制整数/base64、未知字段、Token 引用与脱敏、无 I/O dry-run、受限引用与后台取消、退出码及失败/skipped 报告 | [cli.rs](crates/phantom-sdk-cli/tests/cli.rs)、[wire.rs](crates/phantom-sdk-cli/src/wire.rs)、[main.rs](crates/phantom-sdk-cli/src/main.rs) |
| CLI 子进程 | 独立本地 HTTP/WS 平台验证 smoke 六步（Ready、收发、重复身份、历史、离线、SQLite 重启）、持久目录多进程复用、listen NDJSON/事件数/期限/SIGINT；超时保留 failed/skipped，清理仅本次临时目录 | [platform_cli.rs](crates/phantom-sdk-cli/tests/platform_cli.rs)、[独立受控平台](crates/phantom-sdk-cli/tests/support/platform.rs) |

完整接入、trait 存储、回调存储三个示例均纳入 all-targets 编译：[basic.rs](crates/phantom-sdk/examples/basic.rs)、[custom_storage.rs](crates/phantom-sdk/examples/custom_storage.rs)、[callback_storage.rs](crates/phantom-sdk/examples/callback_storage.rs)。示例的自定义后端声明 `Volatile`，验证同宿主池内重开；跨进程持久性须由使用者自己的实现提供，不以示例替代该保证。

HTTP 点段 ID 已在本地真实 HTTP socket 检查编码后的 request-target；HTTPS 的该分支未单独连接外部测试平台。full_resync、存储故障和 Failed/resend 使用上述确定性受控测试证据，不能据此声称真实平台曾触发这些分支。

## 真实平台验收与覆盖限制

2026-11-14 只读检查本机 `http://127.0.0.1:28080/actuator/health` 返回 `status=UP`，MySQL、Redis、liveness 和 readiness 均为 `UP`。以下业务结果来自实际 Rust CLI 调用，不以健康检查替代验收。

三个用户提供的测试账号已保存到仓库外私有项目知识库，均通过本机 BFF 正常登录并获取短期 UserToken。双账号 smoke 使用前两个账号，专用群验收使用三个账号；实际平台身份取自服务返回。Token、场景配置和含真实资料的原始报告仅保存在私有目录中，目录权限 0700、文件权限 0600。未使用 AppSecret 自行构造 Token，未部署或修改开发机业务服务。可公开的命令、环境和逐步结果见 [脱敏真实验收报告](verification/real-platform-2026-11-14.json)。

真实联调发现并修复三处问题：

- Java 会话快照使用 `SINGLE` / `GROUP` 短枚举名，协议适配现已兼容，并继续接受 protobuf 名称和数字；拒绝消息请求时返回的空类型保留为 `Unknown(0)`。新增 `conversation_snapshot_short_names_and_rejected_request_match_java_service` 回归测试通过。
- 大量历史消息回放会使 `wait_ready` 的内部事件队列溢出。内部订阅现仅作为唤醒信号，溢出后先重新订阅再读取权威同步状态；外部订阅仍按公开契约报告 `EventLagged`。新增 300 条事件的回归测试通过。smoke 在 Ready 后、发送前订阅本轮消息，避免历史同步占满收发观察队列。

- 群邀请返回 `PENDING` 时，审批场景需要引用可空的 `request_id` 字段。CLI 原预检只识别单个 `type`，将 `["string", "null"]` 的引用样例误置为 null，提前拒绝合法串联。现选择非空类型分支进行结构预检，执行时仍校验实际值。新增 `nullable_request_id_reference_can_feed_group_approval_without_reading_credentials` 回归测试先复现失败，修复后通过；缺失凭据/缓存未被读取或创建，字面 null 参数仍被拒绝。

真实双账号 smoke 六步全部通过，退出码 0：登录/Ready、消息收发、重复提交幂等、历史查询、离线发送、重建实例后的持久化恢复与离线补齐。临时 SQLite 目录由 CLI 在退出时清理。

| 领域场景 | 真实结果 | 范围及限制 |
| --- | --- | --- |
| 关系 | 14 passed、0 failed、0 skipped | 资料、好友、申请、分组、黑名单、关系设置及第二个账号二维码扫描 |
| 群 | 标准场景 11 passed、三账号扩展 35 passed；均 0 failed、0 skipped | 创建仅含三个授权账号的专用群并发布公告；列表、详情、成员、公告和邀请通过。验证两名成员接收同一群消息、历史回查、退群、邀请返回 PENDING、群主审批返回 ACCEPTED 和成员恢复为三人；结束后解散专用群并检查三个账号的群列表 |
| 媒体 | 9 passed、0 failed、0 skipped | SDK 上传本轮 53 字节专用文本，READY 后读取元数据和原始内容，并断言下载字节与上传完全相同；未将媒体发送给其他用户 |
| 设备/推送 | 13 passed、0 failed、0 skipped | 设备、推送与隐私链接查询、Presence 订阅/取消、前台状态和通知点击 |
| 搜索 | 9 passed、0 failed、0 skipped | 在真实双账号会话搜索 smoke 文本，以及全局搜索和建议 |
| 合规查询 | 9 passed、0 failed、0 skipped | 举报、申诉、账号导出查询；原示例中的 `set_minor_mode` 写操作单列未执行，因为没有公开读取接口以确保恢复原设置，不能将该操作计为通过 |

合规的 9 个通过步骤属于仅含查询的适用场景，原示例的模式写操作排除原因在脱敏报告 `omissions` 中单独记录。上述真实验收针对所列场景，不声称 103 项公开方法均已在真实平台逐一执行。

第三个账号补齐后，三个账号均成功登录，另外两个成员原本就已是群主好友，无需增删好友关系。各轮测试仅操作新建的专用群，未修改原有群公告。每轮专用群均已解散，并通过三个账号的 `list_groups` 确认该群不再出现。原先缺少公告的尝试、扩展场景误以为邀请会直接入群的断言失败，以及申请 ID 预检失败，均保留在脱敏报告 `previous_attempts` 和私有原始报告中；最终重跑标准场景及扩展场景均通过。

| OpenSpec 任务 | 状态 | 依据与下一步 |
| --- | --- | --- |
| 10.3 真实双账号 smoke | 已完成 | 六步实际通过，脱敏命令、环境与报告已保存 |
| 10.4 真实完整领域场景 | 已完成适用场景验收与结果记录 | 六份适用领域场景均通过，群额外完成三账号扩展及专用资源清理；未成年人模式写操作保留未执行说明，未计为通过 |

Failed/resend、full_resync、存储故障和多后端一致性继续引用受控测试证据，不声称真实平台触发了这些分支。本次未提交、归档或发布变更。
