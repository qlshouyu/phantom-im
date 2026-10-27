## Why

Easy IM 的新 Web UI 已完成界面迁移，但认证、会话、消息、联系人、群组和个人资料仍主要使用本地模拟数据，无法验证 Easy IM BFF、`@phantom/sdk-web` 与 Phantom PaaS 的真实产品链路。现在需要以当前 UI 为准完成端到端接入，并用真实中间件、平台单体和浏览器联调证明关键功能可用。

## What Changes

- 将新 Web UI 的注册、登录、退出、Session 恢复、运行时配置、Phantom UserToken 获取和 SDK 连接替换为真实 BFF 流程。
- 以当前「消息 / 通讯录 / 个人中心」界面为准，将会话、历史消息、文本与媒体发送、联系人、好友申请、群组、资料、搜索、在线/输入状态等模拟数据替换为 SDK/PaaS 结果。
- 为真实异步链路补齐加载、空状态、失败、重试、发送中、上传进度、Token 过期、断线重连和被踢下线反馈，禁止用成功 Toast 模拟未完成的后端操作。
- 保留 UI 中平台尚未提供的能力边界：收藏夹、云空间等无对应 PaaS 契约的入口必须显示不可用或明确的暂未开放状态，不新增虚构后端行为。
- 建立 Easy IM BFF、Phantom 单体、MySQL、Redis、RocketMQ 与 Web UI 的本地联调流程，并补充双用户 Playwright E2E、SDK/BFF 集成测试和故障场景验收。
- 仅在联调发现契约或实现缺陷时修复 `@phantom/sdk-web`、BFF 或 Phantom 用户面接口；不改变“接入方服务端签发 UserToken”和“浏览器不接触 AppSecret”的安全边界。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `web-im-consumer-web`: 将对齐当前新 UI 的三栏信息架构，并新增真实 BFF/SDK/PaaS 数据接入、连接生命周期、错误恢复、无模拟成功行为和本地全链路 E2E 验收要求。

## Impact

- 主要代码：`phantom-im/clients/web/`。
- 服务端联调：`phantom-im/bff/` 的 Session、运行时配置、Token、代理和错误响应验证。
- SDK 与契约：`phantom-platform/sdks/web/`、`phantom-platform/contracts/openapi/mvp-openapi.yaml`、`phantom-platform/contracts/proto/phantom/v1/`；仅在发现不一致时修复。
- 平台服务：仅在真实 UI 链路暴露用户面 API 缺陷时修复 `phantom-platform/server/phantom-services/` 对应模块。
- 基础设施与测试：MySQL、Redis、RocketMQ、Phantom 单体、BFF、Vite Web UI、Playwright 和现有 `tools/e2e-smoke/` 回归脚本。
- 配置与文档：Easy IM README、BFF/Web 启动说明、联调环境变量和验收记录。
