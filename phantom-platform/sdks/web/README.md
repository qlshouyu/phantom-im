# @phantom/sdk-web

Phantom IM 的 TypeScript Web SDK 参考实现，向应用封装 WebSocket / Protobuf 与 HTTP / JSON 通道。

## 接入文档

- [HTML 客户端 SDK 集成文档](../../../docs/phantom-platform/sdk/integration.html)：概述、集成流程、完整 JS API 和 Rust 核心层设计。
- [文档首页与接入职责](../../../docs/phantom-platform/sdk/README.md)
- [第三方应用接入指南](../../../docs/phantom-platform/sdk/getting-started.md)：服务端准备、UserToken、登录、消息与媒体示例。
- [完整 API 参考](../../../docs/phantom-platform/sdk/integration.html#js-api)：公开方法、事件、默认值、返回与错误处理。
- [数据模型与消息内容](../../../docs/phantom-platform/sdk/data-models.md)：SDK 类型、HTTP 字段和 Protobuf 内容。
- [跨语言 SDK 行为契约](../../../docs/phantom-platform/sdk/cross-language-contract.md)：其他端实现与验收参考，以及当前 JS 的实现限制。
- [客户端 SDK 架构文档](../../../docs/phantom-platform/sdk/architecture.md)：JS 当前架构、原生 Rust Kernel 目标设计与分层图说明。

当前 package.json 版本为 `0.1.0`，入口为 TypeScript 源码；发布状态与运行环境要求见接入指南。UserToken 必须由应用服务端使用 AppSecret 签发。

## 最小示例

```ts
import { PhantomClient, singleConvId } from '@phantom/sdk-web';

const client = new PhantomClient({
  baseUrl: 'http://127.0.0.1:28080',
  appId,
});
client.on('message', (message) => {
  // 同一条消息会因发送和状态更新多次触发；按消息标识更新 UI。
  console.log(message);
});
await client.login({ userToken, userId, deviceId, deviceType: 'WEB' });
const message = await client.sendText(singleConvId(userId, peerUserId), '你好');
if (message.state === 'failed') {
  // 用户重试时调用 client.resend(message)，复用原 clientMsgId。
}
// 应用退出登录时调用 client.logout()。
```

`baseUrl` 是 HTTP/HTTPS 绝对地址，不包含查询参数、片段或 WebSocket 的 `/ws` 后缀；支持路径前缀和末尾 `/`。例如 `https://im.example/gateway/` 对应 HTTP `https://im.example/gateway/...` 和 WebSocket `wss://im.example/gateway/ws`。旧构造参数 `url`、`httpBaseUrl` 已移除，请统一迁移为 `baseUrl`。需要经 BFF 代理 HTTP 时，可通过 `fetch` 注入转发实现。

示例中的 appId、userToken、userId、peerUserId 和 deviceId 由应用提供。发送失败通常以 `state: 'failed'` 返回；HTTP 返回多数保留 snake_case，不能统一按 ChatMessage 读取。完整行为见接口文档。

## 本地验证

```bash
npm test
npm run typecheck
```

真实链路 E2E 还需启动 MySQL、Redis、RocketMQ 和平台服务。
