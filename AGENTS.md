# 仓库指南

## 协作要求

- 对话、提交说明和新增文档默认使用中文。
- 不确定的内容不得臆测或猜测，应先向用户确认。

## 项目结构与模块组织

- `phantom-im-server/`：JDK 21 Spring Boot Maven 聚合工程。共享契约位于 `phantom-common` 和 `phantom-service-api`；基础设施 starter 位于 `phantom-infra/*`；业务服务位于 `phantom-services/*`；装配入口位于 `phantom-launchers/phantom-boot-monolith`。
- `client/`：npm workspace，包含 TypeScript Web SDK（`packages/phantom-sdk-web`）及管理端应用。
- `phantom-im-web-bff/`：独立 Spring Boot 4 Maven 工程，提供 Web IM 注册登录、Redis Session、UserToken 与 Phantom HTTP 代理。
- `phantom-im-web-ui/`：独立 npm 工程，React + TypeScript + Vite Web IM，通过本地文件依赖消费 `@phantom/sdk-web`。
- `proto/phantom/v1/`：Java 与 JS 共用的通信契约源文件；`docs/` 存放 PRD 与设计，`openspec/` 存放变更规格，`deploy/local/` 存放本地基础设施，`tools/e2e-smoke/` 存放冒烟测试客户端。

## 构建、测试与开发命令

前置要求：JDK 21、Maven、Node.js/npm；端到端测试还需 MySQL、Redis 和 RocketMQ。

```bash
# 本地基础设施，在仓库根目录执行
docker compose -f deploy/local/docker-compose.yml up -d
docker compose -f docs/third/rocketmq/docker-compose.yml up -d

# 服务端构建与运行（端口 28080，WebSocket 路径 /ws）
cd phantom-im-server && mvn -DskipTests package
java -jar phantom-launchers/phantom-boot-monolith/target/phantom-monolith.jar

# Web IM（先按 docs/web-im-consumer.md 配置 BFF）
cd phantom-im-web-ui && npm install
npm run dev
npm run build
npm run e2e

# 快速冒烟测试
cd tools/e2e-smoke && npm install && node smoke.mjs
```

修改 protobuf 后，需要重新构建服务端，并执行 `node tools/e2e-smoke/gen-bundle.mjs` 更新 SDK 契约包。

## 编码风格与命名约定

- Java：使用 4 空格缩进；包名为 `im.phantom.<module>`；类名使用 `PascalCase`，成员使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。
- TypeScript：使用 2 空格缩进并启用严格类型；类型名使用 `PascalCase`，值使用 `camelCase`；包名保持 `@phantom/*`。
- 当前未强制配置格式化、lint 或 checkstyle 工具。请与周边代码保持一致，避免无关重排，文件编码使用 UTF-8。
- protobuf 字段号不得复用；枚举首值必须为 `*_UNSPECIFIED = 0`。

## 测试指南

- Playwright 测试位于 `phantom-im-web-ui/tests/*.spec.ts`，名称应描述具体行为；运行前须启动 Phantom 单体、BFF 与中间件。
- `tools/e2e-smoke/smoke.mjs` 验证认证、投递、幂等和重连同步。
- JUnit 测试放在各 Maven 工程 `src/test/java` 下，镜像生产包结构，并以 `*Test.java` 命名；BFF 已配置 JUnit/Mockito/MockMvc 测试套件。
- 使用能覆盖改动的最小测试范围；未实际运行的测试或覆盖率不得对外声称。

## 提交与拉取请求规范

- 使用 Conventional Commits，例如 `fix(dev): 说明` 或 `docs(design): 说明`；类型可选 `feat`、`fix`、`docs`、`build`、`chore`，并按需添加作用域。
- PR 应说明改动内容、影响模块、关联 issue 或 OpenSpec 变更，并列出验证命令及结果。Web UI 可见改动需附截图。
- 当前没有 PR 模板或 CI 工作流，需手动提供验证证据；演示用凭据和配置不得用于生产环境。

## 架构与配置说明

业务服务模块保持独立，通过启动器与自动装配层进行组合。修改端口或客户端端点时，需同步更新服务端配置、`phantom-im-web-bff/`、`phantom-im-web-ui/`、Playwright 测试和冒烟测试客户端。
