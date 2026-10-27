# 应用级风险检查

## 默认行为

`app_config.risk_check_enabled` 默认为 `0`。未显式开启风险检查的应用在发送消息时会跳过：

- 文本、图片等内容审核与异步复审；
- 发送者禁言、封禁等发送侧安全状态检查；
- 接收者未成年人模式及陌生人消息限制；
- 被跳过能力对应的用户状态、处罚和未成年人模式查询。

这是 M0 之后的明确行为变更。接入方需要内容安全能力时必须显式开启。

## 始终执行的校验

风险检查关闭不会绕过以下基础访问控制：

- 会话成员、群成员、角色和只读状态；
- 好友关系、双向黑名单与受限投递；
- 消息格式、长度、媒体归属与引用校验；
- `client_msg_id` 幂等、发送限流和 `conv_seq` 分配；
- 封禁用户重新登录时的认证拒绝和在线踢出。

## 开启方式

通过应用功能配置接口读写 `risk_check_enabled`：

```json
{
  "relation_hosted": true,
  "read_receipt_enabled": true,
  "burn_after_read_enabled": false,
  "burn_after_read_ttl_seconds": 0,
  "sdk_profile_write_enabled": true,
  "max_devices": 3,
  "risk_check_enabled": true
}
```

旧客户端不传 `risk_check_enabled` 时按 `false` 处理。服务端配置缓存的默认 TTL 为 2 秒，
可通过 `phantom.app-config.cache-ttl-ms` 调整，但部署配置应将 TTL 控制在 5 秒以内，以满足
配置更新后的最大生效窗口。
