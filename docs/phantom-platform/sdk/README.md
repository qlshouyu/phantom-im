# Phantom SDK 接入文档

本文档供第三方应用接入 Phantom IM，也作为后续 Android、iOS、桌面及其他语言 SDK 的实现参考。

核对日期：2026-11-09。当前参考包：`@phantom/sdk-web@0.1.0`，协议：`phantom.v1` / `Envelope.version = 1`。接口与行为来自仓库当前实现；不代表 npm 公共仓库发布状态或某个线上环境的部署状态。

HTML 集成入口：[客户端 SDK 集成文档](integration.html)。文档包含概述、集成流程、JS SDK 完整接口与 Rust 核心层设计，并为 Android、iOS 和 Flutter 保留后续章节。JS API 以 HTML 中的“完整 API 参考”为维护入口。

## 阅读顺序

| 文档 | 解决的问题 |
| --- | --- |
| [HTML 客户端 SDK 集成文档](integration.html) | 统一阅读入口，含流程、完整 JS API、数据模型与 Rust 核心设计 |
| [第三方应用接入指南](getting-started.md) | 服务端准备、UserToken、引入 SDK、登录、收发消息、历史分页、媒体和退出 |
| [JS SDK API 参考](integration.html#js-api) | 所有公开方法的参数、默认值、返回、事件、HTTP / WebSocket 映射与错误处理 |
| [数据模型与消息内容](data-models.md) | SDK 类型、HTTP 数据结构、消息内容、字段命名和分页格式 |
| [跨语言 SDK 行为契约](cross-language-contract.md) | 协议实现、状态、幂等、ACK、同步、缓存、兼容规则、实现差异与验收清单 |
| [客户端 SDK 架构文档](architecture.md) | JS 当前架构、原生 Rust Kernel 目标架构、分层图、职责边界及开发建议 |

第三方应用通常只需要使用 SDK 领域接口。实现新的语言 SDK 时，还需直接读取 [Protobuf 契约](../../../phantom-platform/contracts/proto/phantom/v1/)和 [HTTP OpenAPI](../../../phantom-platform/contracts/openapi/mvp-openapi.yaml)。

## 客户端 SDK 架构设计

[客户端 SDK 架构文档](architecture.md)作为统一架构入口，包含“JS SDK”和“原生SDK”两个主体章节，说明 JS 当前实现，以及原生客户端共享 Rust Kernel、使用 Kotlin / Swift / Dart 或桌面宿主包装的目标设计。原生章节包含总体分层图、Kernel 内部图及逐层说明；共享契约、工程发布和实施验收等额外章节供评审确定，原生目标架构尚未实现。

原生部分的扩展草案见[客户端 SDK 分层架构设计](../architecture/2026-11-09-client-sdk-layered-architecture.md)。

## 接入职责

| 参与方 | 职责 |
| --- | --- |
| 应用服务端 | 自有账号登录；使用平台 OpenAPI 导入用户；维护业务账号与 Phantom ID 的映射；用 AppSecret 签发 UserToken；在用户会话有效时重新签发 Token |
| 应用客户端 | 从自有服务端取得 UserToken、AppID、Phantom ID 和服务地址；维护稳定 deviceId；驱动登录、业务 UI、已读、输入状态与通知点击 |
| Phantom SDK | 长连接认证、Protobuf 编解码、发送状态、接收 ACK、重连、增量同步、HTTP 领域接口和本地消息缓存 |
| Phantom 平台 | 应用与用户隔离、鉴权、消息序号与幂等、投递、数据持久化以及业务权限校验 |

AppSecret 仅用于服务端。SDK 不提供第三方应用的注册、密码登录或 UserToken 签发接口。Easy IM BFF 是仓库中的接入示例，第三方可使用自己的服务端。

## 使用前应了解的实现边界

- 当前包入口直接导出 TypeScript 源码，需要支持 TypeScript 与 JSON 模块的构建工具；仓库未提供可直接使用的 CDN / UMD 产物或单独发布构建命令。
- 当前客户端使用浏览器全局 `WebSocket`、`fetch`、`Blob`、`crypto.randomUUID()` 等能力。Node.js、React Native、小程序的直接兼容性需要另行验证。
- JS 消息对象、Protobuf 事件使用 camelCase；大多数 HTTP 结果保留 snake_case。完整对应关系见[数据模型](data-models.md)。
- 发送失败、Token 失效、配置读取、会话缓存和防抖等行为存在需注意的边界，见 [API 错误处理](integration.html#js-http-通道和错误处理)和[实现差异表](cross-language-contract.md#当前-js-参考实现的差异与限制)。

## 文档依据与维护

事实来源按用途划分：

- [SDK 公开入口与实现](../../../phantom-platform/sdks/web/src/index.ts)：JS 可调用方法、类型、事件和当前实际行为。
- [SDK 单元测试](../../../phantom-platform/sdks/web/test/sdk.test.ts)：已有回归证据。
- [共享 Protobuf](../../../phantom-platform/contracts/proto/phantom/v1/)：字段号、枚举值和实时指令。
- [服务端共享 DTO](../../../phantom-platform/server/phantom-service-api/)与[业务模块](../../../phantom-platform/server/phantom-services/)：HTTP 字段、响应类型、校验和权限。
- [Web SDK 规格](../../../openspec/specs/web-sdk/spec.md)：各端应对齐的行为要求。
- [HTTP 路由清单](../api/mvp-routes.md)：补充查阅平台接口，含尚未封装为 SDK 方法的能力。

变更 SDK 方法、事件、DTO、错误行为或 Protobuf 时，同步维护对应文档。当前 OpenAPI 主要列举路由，不能单独用于生成完整 SDK；跨语言实现需要结合这里的映射、DTO 和 Protobuf。已知实现差异以专表披露，不能作为其他端放宽行为契约的依据。
