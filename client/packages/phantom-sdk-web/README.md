# @phantom/sdk-web

Phantom IM MVP 的正式 TypeScript Web SDK 参考实现。SDK 隐藏 WSS/Protobuf 与 HTTP/JSON 的差异，向宿主暴露统一领域 API。

## 初始化与登录

```ts
const client = new PhantomClient({ url: "ws://127.0.0.1:28080/ws", appId: "app_demo" });
await client.login({ userToken, userId, deviceId, deviceType: "WEB" });
```

UserToken 必须由接入方服务端使用 AppSecret 签发，SDK 不生成也不持久化凭据。`logout()` 会断开连接、停止重连并清理当前用户游标与消息缓存。

## 消息

- `sendMessage(input)`：文本、表情、图片、语音、视频、文件、位置、名片、合并消息统一入口，支持 `clientMsgId` 幂等。
- `sendText`：文本快捷方法；`resend`：复用原 `clientMsgId`。
- `recall`、`forward`、`deleteLocal`、`clearHistory`、`history`。
- 所有消息先以 `sending` 乐观展示，再按服务端响应和同步事件更新为 `sent/delivered/read/failed/recalled/burned`。

## 媒体

`uploadMedia(file, options)` 自动执行初始化、断点续传、剩余分片上传和完成校验；支持 `onProgress` 与 `AbortSignal`。完成后使用 `sendMedia` 提交消息。

## 领域 API

SDK 提供会话、关系、群聊、设备、推送设置、搜索、举报、申诉、数据导出和未成年人模式方法。HTTP 返回统一 `ApiResponse.data`；失败时抛出的 `Error` 带 `code` 字段。

## 同步与缓存

- 游标按 `appId + userId` 隔离，按 `user_seq` 去重并拒绝乱序旧事件。
- 同步事件覆盖新消息、撤回、消息状态、已读、会话状态、草稿、关系、群和资料。
- 消息与游标写入 `localStorage`；缓存带版本号，未知旧格式会安全重建。
- 订阅 `message`、`sync`、`relation`、`conversation`、`group`、`profile`、`presence`、`typing` 和 `notificationClick` 事件。

## 本地命令

```bash
npm test
npm run typecheck
```

真实链路 E2E 需要先启动 MySQL、Redis、RocketMQ 与单体服务。
