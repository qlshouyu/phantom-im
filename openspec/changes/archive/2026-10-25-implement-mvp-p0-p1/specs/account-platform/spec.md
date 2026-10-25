## Purpose

定义 Phantom IM 作为 PaaS 平台提供的应用、密钥、用户身份、资料、状态和应用级能力配置契约，供接入方通过 OpenAPI 与 SDK 完成租户隔离和用户接入。

## ADDED Requirements

### Requirement: 应用创建与控制台管理（AC-01）

平台 SHALL 允许开发者在最小管理控制台创建应用并分配全局唯一 AppID。AppSecret MUST 仅在创建时明文展示一次，此后不可再次读取；应用详情 SHALL 仅展示 AppID、状态和可轮换的密钥元数据。

#### Scenario: 创建应用后仅展示一次密钥
- **WHEN** 开发者创建一个有效应用
- **THEN** 平台返回 AppID 和一次性的 AppSecret 明文，后续查询应用详情不再返回 AppSecret

#### Scenario: 重新打开应用详情
- **WHEN** 开发者关闭创建结果后再次打开应用详情
- **THEN** 页面只展示 AppID 和密钥版本信息，不展示旧 AppSecret 明文

### Requirement: AppSecret 轮换与宽限期（AC-09）

平台 SHALL 支持 AppSecret 轮换。轮换后旧密钥 MUST 在不超过 7 天的宽限期内继续用于 UserToken 验签，宽限期结束后立即失效；开发者 MUST 能将宽限期设置为 0 以应急吊销旧密钥。

#### Scenario: 宽限期内双密钥有效
- **WHEN** 应用完成密钥轮换且仍处于宽限期
- **THEN** 使用新旧 AppSecret 签发的有效 UserToken 均可完成验签

#### Scenario: 应急轮换立即失效
- **WHEN** 开发者以 0 天宽限期轮换 AppSecret
- **THEN** 旧 AppSecret 签发的 UserToken 在下一次验签时被拒绝

### Requirement: 应用级功能配置（AC-08）

平台 SHALL 为每个应用保存关系链托管、已读反馈和阅后即焚开关及 TTL，并在认证成功时通过 `AuthResp` 下发当前配置。配置变更 MUST 只影响后续认证或重新连接的客户端，不得改变已存在消息的历史语义。

#### Scenario: 认证下发应用配置
- **WHEN** 应用关闭已读反馈但开启阅后即焚
- **THEN** 新登录客户端收到 `AuthResp`，其中已读反馈关闭且阅后即焚配置包含有效 TTL

### Requirement: OpenAPI 服务端鉴权与限频（AC-12）

所有 `/openapi/*` 请求 MUST 携带 `X-App-Id`、`X-Timestamp` 和 `X-Signature`。平台 SHALL 校验 HMAC-SHA256 签名、应用状态和不超过 5 分钟的时间戳偏差，并按应用维度限频；校验失败 MUST 返回统一鉴权错误且不得执行写操作。

#### Scenario: 错误签名被拒绝
- **WHEN** 调用方使用错误 AppSecret 生成 OpenAPI 签名
- **THEN** 平台返回鉴权失败，且用户、应用或消息数据保持不变

#### Scenario: 过期时间戳被拒绝
- **WHEN** OpenAPI 请求时间戳与服务端时间相差超过 5 分钟
- **THEN** 平台拒绝请求并记录防重放审计信息

### Requirement: 用户导入与 Phantom ID 幂等（AC-02）

平台 SHALL 通过 OpenAPI 接收 `external_user_id` 并分配全局唯一 Phantom ID。相同 `(app_id, external_user_id)` 的重复导入 MUST 幂等返回既有 Phantom ID；未导入用户不得被隐式创建。

#### Scenario: 重复导入返回同一 Phantom ID
- **WHEN** 接入方使用相同 `external_user_id` 再次导入用户
- **THEN** 平台返回首次分配的同一 Phantom ID，且不创建新用户

#### Scenario: 未知接收方被拒绝
- **WHEN** 请求向未导入当前应用的 Phantom ID 发送消息
- **THEN** 平台返回用户不存在错误且不创建该用户

### Requirement: 用户资料读写与同步（AC-05）

平台 SHALL 保存昵称、头像 URL 和 ext 扩展字段，支持 OpenAPI 与 SDK 写入，并允许应用配置禁用 SDK 写入口。资料字段 MUST 按 PRD 长度约束校验；更新成功后 MUST 生成 `ProfileEvent` 供在线端和离线端同步。

#### Scenario: OpenAPI 更新资料触发同步
- **WHEN** 接入方通过 OpenAPI 更新用户昵称
- **THEN** 该用户及其他相关在线端收到 `ProfileEvent`，后续资料查询返回新昵称

#### Scenario: SDK 写入被应用禁用
- **WHEN** 用户在禁止 SDK 写入资料的应用中调用 SDK 更新头像
- **THEN** 平台拒绝写操作并保留原头像

### Requirement: 用户禁用、恢复与强制下线（AC-06, AC-10）

平台 SHALL 支持禁用、恢复用户及强制下线全部或指定设备。禁用 MUST 立即踢出全部在线连接并拒绝后续登录；禁用期间其他用户发来的消息 MUST 正常入库，恢复后可通过增量同步补齐。

#### Scenario: 禁用立即踢出全部设备
- **WHEN** 接入方禁用一名有多台在线设备的用户
- **THEN** 所有设备收到 `KickPush{reason=TOKEN_REVOKED}` 并断开，后续认证被拒绝

#### Scenario: 禁用期间消息保留
- **WHEN** 用户被禁用期间收到新消息
- **THEN** 消息正常落入会话 Timeline 和用户同步 Timeline，用户恢复后可同步补齐

### Requirement: 用户删除与匿名化（AC-07）

平台 SHALL 支持删除用户并释放 `external_user_id` 映射，Phantom ID MUST 永不复用。删除后历史消息 MUST 保留并展示“已注销用户”快照，用户 MUST 自动退出所有群组。

#### Scenario: 删除后历史发送者匿名化
- **WHEN** 已删除用户的历史消息被其他群成员查看
- **THEN** 消息内容仍存在，发送者显示为“已注销用户”及默认头像

#### Scenario: 删除后 Phantom ID 不复用
- **WHEN** 平台删除一个用户后创建或导入其他用户
- **THEN** 新用户不会获得已删除用户的 Phantom ID
