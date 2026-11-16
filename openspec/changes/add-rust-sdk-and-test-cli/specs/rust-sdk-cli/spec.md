## Purpose

定义直接依赖 Phantom Rust SDK 的测试命令行工具，让开发者和自动化脚本能通过相同公开接口执行领域调用、连续事件订阅及多账号测试场景，并可靠识别结果、失败原因和测试前置条件。

## ADDED Requirements

### Requirement: 基于公开 SDK 的命令入口

工具 SHALL 提供 `phantom-sdk-cli` 可执行文件、--help、--version 和命令输入模式查询。CLI 的认证、网络通信、消息/游标规则、持久化及重连 MUST 通过公开 Rust SDK 完成，不另写协议客户端。CLI SHALL 提供领域方法调用、监听、同进程多步骤场景和双账号 smoke 命令；范围内所有领域及生命周期公共方法 MUST 有调用映射或场景操作入口，维护对应清单；存储接口/回调等不可序列化的宿主扩展通过 Rust 接入示例和契约测试覆盖。

#### Scenario: 命令发现与合法调用
- **WHEN** 用户查看帮助、查询 send_message 输入模式并提交符合模式的输入
- **THEN** CLI 提供参数含义及类型，以 SDK 的 send_message 执行并返回真实结果

### Requirement: 凭据与配置输入

CLI SHALL 接受 base_url、app_id、账号/设备元数据、请求期限及可选存储模式/目录；未指定时 SHALL 使用 SDK 默认 SQLite，显式 Memory 不得创建 SQLite。自定义接口和回调由 Rust 宿主代码注入，CLI SHALL 不从 JSON 或命令行加载任意实现代码；相应扩展入口通过 SDK 接入示例及契约测试验证。UserToken MUST 由环境变量或显式指定凭据文件提供，不作为命令行参数或场景文件中的字面值。多账号场景 SHALL 通过命名凭据引用取得各自 Token。SDK 和 CLI MUST 不接收 AppSecret，不签发 Token、不导入用户；日志、报告、错误、dry-run 和场景输出 MUST 不泄漏 Token 或认证头。

#### Scenario: 默认使用 SDK 存储
- **WHEN** 用户执行需要登录的普通 CLI 命令，未指定存储模式或目录
- **THEN** CLI 将存储配置留空并由 SDK 采用默认 SQLite，不自行实现一套默认存储规则

#### Scenario: 双账号凭据注入
- **WHEN** smoke 指定两个已有测试账号及其命名 Token 环境变量
- **THEN** CLI 创建两个独立 SDK 实例并登录，不读取应用签名密钥或尝试创建平台账号

#### Scenario: 缺失凭据
- **WHEN** 非帮助命令需要登录而指定 Token 环境变量或文件不存在
- **THEN** CLI 返回结构化配置错误，不启动网络请求，也不进入交互提示

### Requirement: 单次调用与同进程场景

call SHALL 支持领域方法和类型化 JSON 输入，可通过文件或标准输入提供；单次调用 SHALL 自动完成必要登录/就绪、执行和释放。scenario SHALL 在同一进程中保持命名 SDK 实例、会话、订阅、变量和步骤结果，支持调用、等待事件、断言、取消、重连、退出/再登录及跨步骤引用；脚本不得执行任意 shell 或动态代码。生命周期相关测试 MUST 能控制登录、wait_ready 和释放时机，不被自动流程遮蔽。

#### Scenario: 发送后在同一会话监听
- **WHEN** 场景先建立订阅并登录，再发送和等待对应消息事件
- **THEN** 操作共享同一 SDK 会话，能断言 event_id、消息身份和 session 的关联

#### Scenario: 结构化输入无效
- **WHEN** 输入含未知方法、未知字段、类型不匹配或非法跨步骤引用
- **THEN** CLI 在提交对应业务调用前返回参数错误并指明方法或步骤

### Requirement: 机器可读输出及精度

单次命令 SHALL 默认只向 stdout 输出一个带 schema_version、ok、data 或 error 的 JSON 结果；诊断与进度 SHALL 写入 stderr。listen SHALL 输出逐行可解析的 NDJSON 事件及终止摘要。SDK i64/u64 字段在 CLI JSON 边界 MUST 以十进制字符串表示并在输入中校验，以兼容使用浮点数的消费者；SDK Rust 类型保持整数。错误 MUST 保留 SDK kind、server_code、http_status、outcome、retryable 及可用关联标识。

#### Scenario: 大序号结果
- **WHEN** CLI 输出 conv_seq 或 user_seq 超过 2^53 的结果
- **THEN** JSON 中返回精确十进制字符串，脚本可无损恢复数值，stdout 不混入日志

#### Scenario: SDK 返回业务错误
- **WHEN** 调用被服务端权限拒绝
- **THEN** CLI 输出 ok=false 和原业务码及 Rejected outcome，并使用约定非零退出码

### Requirement: 有界等待、信号与资源清理

CLI SHALL 为 call、scenario 和 smoke 设置整体截止时间，并把操作取消传播给 SDK。listen SHALL 支持按持续时间、事件数退出及显式持续监听。收到中断信号 MUST 取消等待、关闭订阅并有界调用 shutdown；非 TTY 环境 MUST 不提示、不分页、不永久等候缺失输入。持久化模式重复运行 SHALL 保留同一账号缓存，临时测试目录 SHALL 在所属测试结束后清理；默认或用户指定的持久化目录不得作为测试临时目录删除。

#### Scenario: CI 等不到事件
- **WHEN** smoke 在设定期限内未收到匹配事件
- **THEN** 输出失败断言和超时原因，关闭所有实例并退出，不无限等待

#### Scenario: 监听收到中断
- **WHEN** 用户中断正在监听的命令
- **THEN** CLI 传播取消、释放 SDK，并输出终止摘要及约定中断退出码

### Requirement: SDK 双账号冒烟测试

smoke SHALL 直接通过两个公开 SDK 实例验证登录/Ready、发送与接收、相同 client_msg_id 重复提交的幂等、历史回查、离线期间新消息的重连补齐及 Directory 模式重新创建实例后的缓存恢复。测试 SHALL 在发送前订阅并使用稳定关联标识，验证一次逻辑消息及最终状态；幂等重复提交不得冒称验证了 Failed 状态的 resend，失败重发须用可控故障测试覆盖。客户端 MUST 使用不同设备标识和隔离存储，避免互踢及缓存冲突。前置条件不足 MUST 单独报告，不能计为通过。

#### Scenario: 完成真实服务测试
- **WHEN** 提供可访问的平台、两个已注册且允许互发消息的账号和有效 UserToken
- **THEN** smoke 实际执行各步骤，输出通过/失败/未执行统计、耗时和可脱敏关联标识，通过条件基于 SDK 查询与事件

#### Scenario: 平台连接失败
- **WHEN** 平台或必要中间件不可用，认证或连接无法完成
- **THEN** smoke 返回非零退出码及可诊断原因，后续场景标记未执行，不生成虚假的通过记录

### Requirement: 可检查的执行预览与操作边界

CLI SHALL 支持输入模式查询及 call/scenario 的 dry-run，在不创建 SDK 会话、不联网、不写缓存和不读写媒体内容的情况下检查输入并输出方法、账号引用、参数和预期副作用摘要。场景描述中的用户文本 MUST 作为数据处理，不作为命令。真实测试和写操作 SHALL 在用户显式执行对应命令时执行；工具不得在缺少前置条件时擅自更换环境、签发 Token、部署服务或清理非本次测试资源。

#### Scenario: dry-run 预览发送与删除
- **WHEN** 用户对包含发送和清空历史的场景执行 dry-run
- **THEN** CLI 返回脱敏方法和副作用清单，不登录、不发送、不删除任何数据

### Requirement: 稳定退出码与测试报告

CLI MUST 约定退出码 0 为成功、2 为输入/配置错误、3 为认证/权限问题、4 为网络/协议/超时问题、5 为测试断言失败、6 为存储/内部错误、130 为用户中断；其他 SDK 业务失败 SHALL 使用 1 并保留结构化分类。smoke/scenario 报告 SHALL 区分 passed、failed 和 skipped 及原因；必需前置条件缺失或必需步骤 skipped MUST 非零退出。环境前置问题优先映射对应输入、认证、网络或存储类别，执行完成但断言不满足时返回 5。

#### Scenario: 断言失败
- **WHEN** SDK 调用成功但历史查询中消息数量与幂等断言不符
- **THEN** CLI 输出 failed 步骤、预期和实际值并以 5 退出

#### Scenario: 必需步骤未执行
- **WHEN** 必需测试因缺少第二个账号 Token 而不能执行
- **THEN** 报告标记 skipped 及缺失配置，以 2 退出而非 0
