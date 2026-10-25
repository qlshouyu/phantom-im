# web-im-consumer-bff Specification

## Purpose

定义 Web IM 消费者独立 Java 服务端的账号、注册登录、分布式会话、Phantom 用户开通、令牌签发和 HTTP 代理行为，使浏览器能够以真实接入方方式安全消费 Phantom IM PaaS。

## Requirements

### Requirement: 单 Phantom 应用配置

Web IM BFF SHALL 在第一版只服务一个 Phantom 应用，并从服务端配置读取 AppID、AppSecret、Phantom HTTP 地址和 WebSocket 地址。BFF MUST NOT 向浏览器暴露 AppSecret，也 MUST NOT 提供应用切换能力。

#### Scenario: 前端获取运行配置
- **WHEN** Web IM 前端请求运行配置
- **THEN** BFF 返回 AppID、Phantom WebSocket 地址和 BFF 代理基地址，不返回 AppSecret

#### Scenario: 未配置应用凭据时启动失败
- **WHEN** BFF 启动时缺少 AppID 或 AppSecret
- **THEN** BFF 拒绝进入可用状态并在健康检查或启动日志中报告配置错误

### Requirement: Web IM 用户注册

BFF SHALL 提供用户注册能力。注册输入 MUST 包含昵称、登录名、密码和确认密码；昵称长度 MUST 为 1 至 64 个字符，登录名 MUST 为 4 至 32 位且仅允许字母、数字和下划线，密码长度 MUST 为 8 至 64 个字符，确认密码 MUST 与密码一致。登录名 MUST 在应用内唯一。注册成功后 MUST NOT 自动建立登录会话或签发 Phantom UserToken。

#### Scenario: 注册成功
- **WHEN** 用户提交合法且未使用的注册信息
- **THEN** BFF 创建本地账号、以注册昵称开通 Phantom 用户、保存 Phantom ID 映射并返回注册成功，但不设置登录 Session

#### Scenario: 登录名重复
- **WHEN** 用户提交已被使用的登录名
- **THEN** BFF 拒绝注册并返回登录名已被占用的明确错误

#### Scenario: 注册字段不合法
- **WHEN** 用户提交越界昵称、非法登录名、越界密码或不一致的确认密码
- **THEN** BFF 拒绝注册并指出不合法字段

#### Scenario: Phantom 用户开通失败
- **WHEN** 本地账号创建后 Phantom OpenAPI 用户开通失败
- **THEN** BFF 向用户返回注册失败，不提供登录 Session，并保留可安全重试的开通状态

### Requirement: 密码与账号数据安全

BFF MUST 使用带盐的慢哈希算法保存密码，MUST NOT 以明文、可逆加密或日志形式保存密码。BFF 的账号数据 MUST 保存在独立 `phantom_web_im` schema 中，并以本地不可变账号 ID 作为 Phantom `external_user_id`。BFF MUST NOT 跨库读取 `phantom_im` 中的业务表。

#### Scenario: 密码不落明文
- **WHEN** 用户完成注册
- **THEN** 数据库中只存在密码哈希及必要账号字段，不存在明文密码或确认密码

#### Scenario: Phantom 映射稳定
- **WHEN** Web IM 账号完成 Phantom 用户开通
- **THEN** 后续登录通过本地不可变账号 ID 找到同一 Phantom ID，不重复创建用户

### Requirement: 登录与 Redis 分布式会话

BFF SHALL 使用登录名和密码完成登录。凭据正确时 MUST 建立 Web IM 登录会话，并通过 `HttpOnly`、`SameSite=Lax` Cookie 下发随机 Session ID；HTTPS 环境 MUST 启用 `Secure`。会话 MUST 共享存储并支持 7 天未活跃过期、登录时轮换 Session ID、主动退出时立即失效。密码哈希、AppSecret 和 Phantom UserToken MUST NOT 写入会话。

#### Scenario: 登录成功
- **WHEN** 用户以正确登录名和密码登录
- **THEN** BFF 建立新的分布式会话并设置 Web IM Session Cookie，响应中不包含密码哈希或 AppSecret

#### Scenario: 登录失败
- **WHEN** 用户提交不存在账号或错误密码
- **THEN** BFF 返回不区分账号是否存在或密码错误的登录失败提示，且不建立会话

#### Scenario: 多个 BFF 实例共享会话
- **WHEN** 用户在实例 A 登录后，其后续请求被实例 B 处理
- **THEN** 实例 B 能识别同一有效会话和当前账号身份

#### Scenario: 退出登录
- **WHEN** 已登录用户退出登录
- **THEN** BFF 删除该会话、清除 Cookie，后续受保护请求被拒绝

#### Scenario: 会话存储不可用
- **WHEN** BFF 无法读取或写入会话存储
- **THEN** 受保护请求按未认证或服务不可用处理，不得降级为匿名访问

### Requirement: Phantom UserToken 签发与刷新

BFF SHALL 仅为已登录 Web IM 用户签发短期 Phantom UserToken。签发的 Token MUST 使用当前 Phantom 应用的 AppID 和 Phantom ID，并包含有效签名与过期时间。SDK 报告 Token 过期时，前端 MUST 能凭有效 Web IM 会话重新获取 Token。

#### Scenario: 登录用户获取 Phantom Token
- **WHEN** 已登录用户请求 Phantom 访问凭据
- **THEN** BFF 返回短期 UserToken、Phantom ID、AppID 和过期时间

#### Scenario: Token 过期后刷新
- **WHEN** SDK 因 Token 过期触发宿主回调，且 Web IM 会话仍有效
- **THEN** BFF 签发新的 UserToken，前端使用新 Token 重新登录 SDK

#### Scenario: 未登录请求 Token
- **WHEN** 没有有效 Web IM 会话的客户端请求 Phantom UserToken
- **THEN** BFF 返回未认证错误且不签发 Token

### Requirement: Phantom HTTP 代理

BFF SHALL 为 SDK 的 HTTP 请求提供同源代理，只允许转发 Phantom 用户面 API 路径。代理 MUST 校验 Web IM 会话，MUST 校验请求中的用户身份与会话中的 Phantom ID 一致，MUST 原样保留查询参数、请求体、响应状态和业务错误，并 MUST 禁止访问 OpenAPI 管理路径或任意外部 URL。

#### Scenario: 转发会话列表请求
- **WHEN** 已登录浏览器通过 BFF 请求会话列表
- **THEN** BFF 将请求转发到 Phantom 对应接口并返回原始 HTTP 结果

#### Scenario: 拒绝身份不一致请求
- **WHEN** 请求携带的 Phantom 用户 ID 与当前 Web IM 会话不一致
- **THEN** BFF 拒绝请求且不访问 Phantom

#### Scenario: 拒绝 OpenAPI 管理请求
- **WHEN** 浏览器尝试通过 HTTP 代理访问 `/openapi/**`
- **THEN** BFF 返回禁止访问，不执行管理面调用

#### Scenario: Phantom 不可用
- **WHEN** Phantom HTTP 服务超时或不可达
- **THEN** BFF 返回可识别的上游服务错误，不伪造成功响应
