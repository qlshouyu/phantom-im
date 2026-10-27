# 仓库指南

## 协作要求

- 对话、提交说明和新增文档默认使用中文。
- 不确定的内容不得臆测或猜测，应先向用户确认。

## 项目结构与模块组织

- `phantom-platform/server/`：JDK 21 Spring Boot Maven 聚合工程。共享契约位于 `phantom-common` 和 `phantom-service-api`；基础设施 starter 位于 `phantom-infra/*`；业务服务位于 `phantom-services/*`；装配入口位于 `phantom-launchers/phantom-boot-monolith`。
- `phantom-platform/server/phantom-admin-console/`：平台管理控制台，独立 npm/Vite 工程，不加入 Maven reactor。
- `phantom-platform/contracts/proto/phantom/v1/`：Java 与各端 SDK 共用的 protobuf 通信契约源文件。
- `phantom-platform/contracts/openapi/`：平台 HTTP OpenAPI 契约。
- `phantom-platform/sdks/web/`：TypeScript Web SDK（`@phantom/sdk-web`）。
- `phantom-im/bff/`：Easy IM 独立 Spring Boot 4 Maven 工程，提供账号、Redis Session、UserToken 与 Phantom HTTP 代理。
- `phantom-im/clients/web/`：Easy IM Web 客户端，React + TypeScript + Vite。
- `docs/phantom-platform/`：平台 PRD、架构、运维与验收文档；`docs/phantom-im/`：消费方文档与设计资产。
- `deploy/local/`：本地 MySQL、Redis 与 RocketMQ 基础设施；`openspec/`：规格驱动变更；`tools/e2e-smoke/`：跨产品黑盒冒烟测试。

## 构建、测试与开发命令

前置要求：JDK 21、Maven、Node.js/npm；端到端测试还需 MySQL、Redis 和 RocketMQ。

```bash
# 本地基础设施，在仓库根目录执行
docker compose -f deploy/local/docker-compose.yml up -d
docker compose -f deploy/local/rocketmq/docker-compose.yml up -d

# 平台服务端（端口 28080，WebSocket 路径 /ws）
cd phantom-platform/server && mvn -DskipTests package
java -jar phantom-launchers/phantom-boot-monolith/target/phantom-monolith.jar

# 平台管理控制台（端口 5174）
cd phantom-platform/server/phantom-admin-console && npm install && npm run dev

# Easy IM BFF（端口 28090，先按 phantom-im/README.md 配置环境变量）
cd phantom-im/bff && mvn -s .mvn/settings.xml spring-boot:run

# Easy IM Web UI（端口 5173，当前为未接真实服务的 UI 壳）
cd phantom-im/clients/web && npm install && npm run dev

# 快速冒烟测试
cd tools/e2e-smoke && npm install && node smoke.mjs
```

修改 protobuf 后，需要重新构建平台服务端，并执行 `node tools/e2e-smoke/gen-bundle.mjs` 更新 SDK 契约包。

## 编码风格与命名约定

- Java：使用 4 空格缩进；包名为 `im.phantom.<module>`；类名使用 `PascalCase`，成员使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。
- TypeScript：使用 2 空格缩进并启用严格类型；类型名使用 `PascalCase`，值使用 `camelCase`；SDK 包名保持 `@phantom/*`。
- 当前未强制配置格式化、lint 或 checkstyle 工具。请与周边代码保持一致，避免无关重排，文件编码使用 UTF-8。
- protobuf 字段号不得复用；枚举首值必须为 `*_UNSPECIFIED = 0`。

## 测试指南

- Playwright 测试放在对应前端工程内，名称应描述具体行为；运行前按被测应用要求启动 Phantom 单体、BFF 与中间件。
- `tools/e2e-smoke/smoke.mjs` 验证认证、投递、幂等和重连同步。
- JUnit 测试放在各 Maven 工程 `src/test/java` 下，镜像生产包结构，并以 `*Test.java` 命名；BFF 已配置 JUnit/Mockito/MockMvc 测试套件。
- 使用能覆盖改动的最小测试范围；未实际运行的测试或覆盖率不得对外声称。

## 提交与拉取请求规范

- 使用 Conventional Commits，例如 `fix(dev): 说明` 或 `docs(design): 说明`；类型可选 `feat`、`fix`、`docs`、`build`、`chore`，并按需添加作用域。
- PR 应说明改动内容、影响模块、关联 issue 或 OpenSpec 变更，并列出验证命令及结果。Web UI 可见改动需附截图。
- 当前没有 PR 模板或 CI 工作流，需手动提供验证证据；演示用凭据和配置不得用于生产环境。

## 架构与配置说明

业务服务模块保持独立，通过启动器与自动装配层进行组合。修改端口或客户端端点时，需同步更新平台服务端配置、`phantom-im/bff/`、Easy IM 客户端、Playwright 测试和冒烟测试客户端。
