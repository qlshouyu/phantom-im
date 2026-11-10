# 第三方应用接入指南

适用版本和文档导航见[文档首页](README.md)。下面的业务账号、Token 和服务器地址均需替换为接入方实际值。

## 1. 准备应用和用户身份

1. 由平台管理方分配 AppID、AppSecret、WebSocket 地址与 HTTP 地址。开发环境单体默认分别为 `ws://127.0.0.1:28080/ws` 和 `http://127.0.0.1:28080`；部署环境使用实际地址和 TLS。
2. 应用服务端通过 `POST /openapi/users` 导入业务用户，保存返回的 `data.user_id`，即 Phantom ID。客户端的 `userId` 必须使用这个值。
3. 用户完成应用自身登录后，应用服务端为其签发 UserToken，并把 Token、AppID、Phantom ID 和有效期返回客户端。
4. 用户退出、禁用和强制下线由应用服务端结合自身会话及平台 OpenAPI 处理。

导入用户的 JSON 请求为：

```json
{
  "external_user_id": "your-business-user-id",
  "nickname": "用户昵称",
  "avatar_url": "https://your-app.example/avatar.png",
  "ext": { "badge": "member" }
}
```

成功响应的 `data` 为 `{ "user_id": "平台返回的ID", "created": true }`。`created` 可能为 `false`，应保存实际返回的 ID。

`/openapi/*` 使用服务端 HMAC 鉴权：请求头为 `X-App-Id`、`X-Timestamp`（毫秒整数）、`X-Signature`。签名是使用 AppSecret UTF-8 字节为密钥，对 `HTTP方法 + 请求路径及原始查询串 + 原始UTF-8请求体 + 时间戳` 计算 HMAC-SHA256，再转小写十六进制。拼接不加分隔符；签名和发送须使用相同的请求体字节。无请求体时使用空串。时间窗及限流以部署配置为准，重复签名可能被判为重放。详情见[平台路由与鉴权](../api/mvp-routes.md)。

UserToken 使用 JWT HS256，以 AppSecret 的 UTF-8 字节签名。当前服务端示例写入的 claims：

| Claim | 含义 |
| --- | --- |
| `app_id` | 平台分配的 AppID，必须与客户端构造参数一致 |
| `user_id` | 导入返回的 Phantom ID，必须与 `login.userId` 一致 |
| `iat` | 签发时间，JWT NumericDate，单位秒 |
| `exp` | 过期时间，JWT NumericDate，单位秒 |

签发实现可参考 [Easy IM JwtService](../../../phantom-im/bff/src/main/java/im/phantom/web/auth/JwtService.java)。Token 有效期由应用服务端确定。客户端不得取得 AppSecret。SDK 持有当前 Token 供连接和 HTTP 请求使用，不负责持久化或自动签发新 Token。

## 2. 引入当前仓库 SDK

当前仓库通过本地包引用 SDK。可在使用 npm 的应用中执行，路径需指向实际 checkout：

```bash
npm install /absolute/path/to/phantom-im/phantom-platform/sdks/web
```

应用构建工具需能处理包内 TypeScript、JSON 与 ESM 依赖。不要据此假定包已在 npm 公共仓库发布。仓库里的 Vite 接入实例见 [Web 客户端](../../../phantom-im/clients/web/package.json)。

```ts
import { PhantomClient, PhantomAuthError, singleConvId } from '@phantom/sdk-web';
import type { ChatMessage } from '@phantom/sdk-web';

const client = new PhantomClient({
  baseUrl: 'https://im.your-app.example',
  appId: '平台分配的AppID',
});
```

`baseUrl` 必须是 HTTP/HTTPS 绝对地址，不包含查询参数、片段或 WebSocket 的 `/ws` 后缀；SDK 自动移除末尾 `/`，HTTP 接口与 WebSocket 使用同一基础地址。例：`https://im.your-app.example/gateway/` 的 HTTP 接口为 `https://im.your-app.example/gateway/...`，WebSocket 为 `wss://im.your-app.example/gateway/ws`。`http://` 对应 `ws://`。旧构造参数 `url`、`httpBaseUrl` 已移除，需改传 `baseUrl`。统一入口的反向代理应同时转发 HTTP 路径和 `/ws`；如应用仍需通过 BFF 代理 HTTP，可注入 `fetch` 转发请求，参考 [PhantomSdkAdapter](../../../phantom-im/clients/web/src/phantom/sdk-adapter.ts)。跨域时服务入口需允许 SDK 所用的 HTTP 方法和认证头，并允许 WebSocket 连接；SDK 普通 HTTP 请求没有默认启用跨站 Cookie。

## 3. 绑定事件并登录

下例 `identity` 来自应用自己的已认证服务端，`renderMessages` 等函数由应用提供；不是 SDK 内置函数。

```ts
// identity: { userToken: string; userId: string }
// peerUserId 是对方的 Phantom ID。
const convId = singleConvId(identity.userId, peerUserId);

const onMessage = (message: ChatMessage) => {
  if (message.convId === convId) renderMessages(client.getMessages(convId));
};
client.on('message', onMessage);
client.on('statechange', (state) => renderConnectionState(state));
client.on('kicked', (reason) => showSignedOut(reason));
client.on('tokenExpired', () => showTokenRefreshRequired());

let deviceId = localStorage.getItem('your-app.phantom.deviceId');
if (!deviceId) {
  deviceId = crypto.randomUUID();
  localStorage.setItem('your-app.phantom.deviceId', deviceId);
}

try {
  await client.login({
    userToken: identity.userToken,
    userId: identity.userId,
    deviceId,
    deviceType: 'WEB',
  });
  // login 完成后再读取初始会话与消息。
  renderMessages(client.getMessages(convId));
} catch (error) {
  if (error instanceof PhantomAuthError && error.code === 2001) {
    showTokenRefreshRequired();
  } else {
    showLoginError(error);
  }
}
```

`connected` 在认证成功时触发，先于首次同步完成；`await login()` 等待认证后所启动的同步流程结束。首次快照拉取失败有时通过 `conversation: { type: 'SNAPSHOT_UNAVAILABLE' }` 通知，不能仅凭登录完成认定所有历史已成功加载。

设备类型为 `WEB`、`DESKTOP` 或 `MOBILE`；当前 Web SDK 默认 `WEB`。同类型设备登录可能踢下线已有设备，收到 `kicked` 后 SDK 停止自动重连。一个客户端实例只用于一个活动登录；重复登录应串行，切换账号先退出并清理应用自身状态。

## 4. 收发消息和已读

```ts
const sent = await client.sendText(convId, '你好');
if (sent.state === 'failed') {
  showSendFailed(sent);
}

// 用户选择重试时执行，沿用原 clientMsgId。
if (sent.state === 'failed') await client.resend(sent);

// 发送引用或 @ 消息时使用统一入口。
const reply = await client.sendMessage({
  convId,
  convType: 'SINGLE',
  kind: 'TEXT',
  content: { text: { text: '收到，稍后回复' } },
  replyToMsgId: originalMessage.msgId,
});

// 仅对用户实际看过的消息上报已读；不能用“收到推送”代替。
if (reply.convSeq > 0) await client.markRead(convId, reply.convSeq);
```

`sendText` / `sendMessage` 会先触发 `message(state='sending')`，成功后更新为 `sent`；业务拒绝、传输异常或 15 秒超时通常以 `failed` 消息返回。应同时处理调用前置错误的 Promise 拒绝和返回消息的状态。

UI 按 `msgId` 和 `clientMsgId` 更新同一条气泡，不能把每次 `message` 事件都当作新增。`sent` 表示服务端接受，`delivered` 表示投递状态，`read` 表示已读状态。SDK 自动处理收件 ACK，宿主不需要手动 ACK。

## 5. 历史消息与会话

```ts
const page = await client.listConversations(undefined, 50);
renderConversations(page.items); // HTTP 字段，例如 conv_id、unread_count。
if (page.has_more) {
  const next = await client.listConversations(page.next_cursor, 50);
  appendConversations(next.items);
}

const rows = await client.history(convId, 0, 50); // 0 表示从最新一页开始。
renderMessages(client.getMessages(convId)); // 合并后的 SDK ChatMessage[]。

// 再向前查询：使用本页最小 conv_seq；空页时停止。
if (rows.length > 0) {
  const before = Math.min(...rows.map((row) => Number(row.conv_seq)));
  const olderRows = await client.history(convId, before, 50);
  renderMessages(client.getMessages(convId));
}
```

`history()` 直接返回 HTTP `items`，保留 `msg_id`、`content_json` 等字段；它同时把历史合入 SDK 缓存，不逐条触发 `message`。历史分页元信息没有从该方法透出，不应仅凭返回条数构造强一致的完整历史导出。UI 使用 `getMessages()`；历史合并后的部分状态、提及与引用信息存在限制，见[实现差异表](cross-language-contract.md#当前-js-参考实现的差异与限制)。

## 6. 上传和发送媒体

```ts
async function sendFile(file: File, convId: string, signal?: AbortSignal) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  const sha256 = Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0')).join('');
  const media = await client.uploadMedia(file, {
    fileName: file.name,
    sha256,
    mediaType: 'FILE',
    signal,
    onProgress: (uploaded, total) => renderUploadProgress(uploaded / total),
  });
  const message = await client.sendMedia(convId, 'FILE', media);
  if (message.state === 'failed') showSendFailed(message);
  return message;
}
```

SDK 根据初始化结果跳过已上传分片并顺序上传剩余内容，完成校验后取得 `mediaId`。此 SHA-256 示例会把整个文件载入内存，大文件应用可在宿主中分块计算摘要。`signal` 当前仅传给分片请求；初始化和完成请求不受该信号控制。

图片读取示例：

```ts
const blob = await client.getMediaContent(mediaId, 'thumb');
const objectUrl = URL.createObjectURL(blob);
imageElement.src = objectUrl;
// 页面替换图片或卸载时：URL.revokeObjectURL(objectUrl)。
```

`getMediaContent()` 自动取得当前用户的受控媒体地址，并携带认证头下载；不要把旧的签名 URL 当成永久地址。

## 7. Token 更新、重连和退出

Token 更新由应用完成：捕获 `tokenExpired` 或 HTTP `2001` 后，从应用服务端取得新 UserToken，再串行调用 `login()`。建议应用把并发刷新合并成一个任务；SDK 没有 `refreshToken()` 或 `setToken()` 方法。现有参考封装见 [PhantomSdkAdapter](../../../phantom-im/clients/web/src/phantom/sdk-adapter.ts)。

网络意外断开时 SDK 自动退避重连并同步；`client.reconnect()` 只触发当前连接关闭与重连，不返回“已经恢复在线”的 Promise。用 `statechange` / `connected` 观察进度。

```ts
client.off('message', onMessage); // off 需要最初绑定的函数引用。
client.logout();                 // 停止自动重连，清理当前用户消息和游标。
clearYourApplicationSession();    // 应用自身会话由应用处理。
```

退出不会清理宿主注册的所有事件监听器。应用销毁组件时应自行解绑；更换用户时还需清理应用的会话、联系人等展示状态。完整参数与限制见 [API 参考](integration.html#js-api)。
