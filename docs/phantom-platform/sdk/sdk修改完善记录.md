# SDK修改完善记录
- [x] 1、PhantomClient 传递参数httpBaseUrl和url合并，只传递baseUrl，内部进行websocket url的拼接和http接口的拼接，基础地址使用同一个

完成时间：2026-11-10。

- `PhantomClient` 构造参数统一使用 `baseUrl`，移除 `url`、`httpBaseUrl`。
- `baseUrl` 使用 HTTP/HTTPS 绝对地址；自动处理末尾斜杠并保留路径前缀，HTTP 追加接口路径，WebSocket 转换为 WS/WSS 后追加 `/ws`。查询参数、片段和不支持的协议在构造时拒绝。
- 同步迁移 Easy IM 适配器和设备 E2E 调用点；通过 `fetch` 注入保留 HTTP 经 BFF 代理的流程。
- 已更新 SDK README、接入指南、HTML API 参考与架构说明。
- 验证通过：SDK 单元测试 40 项、SDK 类型检查、Easy IM SDK 适配器测试 7 项及 Web 客户端类型检查。真实服务端到端测试未运行。
