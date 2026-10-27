# Phantom IM 服务端工程结构设计（Maven 多模块 · 可分可合）

- 日期：2026-08-25
- 状态：待评审
- 上游规格：[IM 通讯协议设计](2026-08-07-im-protocol-design.md)、[服务端消息核心架构设计](2026-08-07-im-mq-architecture-design.md)

## 0. 设计决策记录

| 决策项 | 结论 | 理由 |
|---|---|---|
| 技术栈 | Java + Spring Boot 4.x（Spring Framework 7、Jakarta EE、JDK 21） | 团队既定；JDK 21 虚拟线程适配网关/推送等 IO 密集场景 |
| 工程形态 | 单一 Maven 聚合工程（monorepo），每个服务一个 module | 一次 checkout 全量可见，契约变更原子提交，统一版本治理 |
| 部署形态 | **可分可合**：launcher 装配层决定单体 / 按域拆分 / 全微服务 | 项目初期单体降低运维成本，规模上来后按瓶颈逐个拆出，业务代码零改动 |
| 服务间调用 | `service-api` 接口抽象 + 条件装配：同 JVM 直调，跨进程 gRPC | 复用已有 protobuf 契约体系；本地/远程实现切换不侵入业务代码 |
| 注册中心/配置 | Nacos（可替换 Consul/K8s Service） | 拆分形态的服务发现与应用配置（AC-08 功能开关）下发 |
| MQ 地位不变 | 单体形态仍经 RocketMQ | 事务消息/削峰/顺序语义是架构根基（MQ 架构文档 §3），不随部署形态变化 |

## 1. 总体结构

```
phantom-platform/server/                        # Maven 聚合根（packaging=pom）
├── pom.xml                               # modules 声明 + 全局插件管理
├── phantom-dependencies/                 # BOM：统一三方与内部模块版本
├── phantom-proto/                        # phantom-platform/contracts/proto/phantom/v1 编译产物（wire 契约，protobuf-maven-plugin）
├── phantom-common/                       # 错误码、ID 生成、通用工具、统一异常/响应包
├── phantom-infra/                        # 基础设施 starter（自动装配，业务 module 只声明依赖）
│   ├── phantom-infra-mq/                 #   RocketMQ 生产者/消费者封装、事务消息模板
│   ├── phantom-infra-redis/              #   路由表/缓存操作、Lua 脚本管理
│   └── phantom-infra-db/                 #   数据源、分表路由（Timeline 存储）
├── phantom-service-api/                  # 服务间调用契约（拆合切换的枢纽，见 §3）
│   ├── phantom-api-route/                #   在线路由表读写接口
│   ├── phantom-api-user/                 #   用户/资料/禁用状态查询
│   ├── phantom-api-conv/                 #   会话与群成员查询
│   └── phantom-api-config/               #   应用配置（AC-08 开关）读取
├── phantom-services/                     # 业务服务 module（每个 = 架构图中一个组件）
│   ├── phantom-gateway/                  #   WS 长连接网关：Envelope 编解码、UserToken 验签、路由表登记
│   ├── phantom-message/                  #   消息服务：收单、conv_seq 分配、事务消息
│   ├── phantom-fanout/                   #   扇出服务：user_seq 唯一写入方、写扩散
│   ├── phantom-sync/                     #   同步/漫游读取：SyncReq、ConvDigest、漫游分页 HTTP
│   ├── phantom-conversation/             #   会话状态：置顶/免打扰/草稿/已读位置
│   ├── phantom-group/                    #   群组：成员/权限/禁言/公告
│   ├── phantom-relation/                 #   关系链托管（应用级可选，未开启的应用不加载校验链）
│   ├── phantom-user/                     #   用户身份：Phantom ID 映射、极简资料（审核挂载点预留 AC-11）
│   ├── phantom-push/                     #   推送：在线推送（查路由下发）+ 离线推送（决策树 NT）
│   ├── phantom-media/                    #   媒体：分片上传、秒传、阅后即焚清理消费者
│   ├── phantom-search/                   #   搜索索引消费者 + 查询接口（SR）
│   ├── phantom-risk/                     #   风控/审计消费者：内容复核、系统撤回
│   └── phantom-openapi/                  #   OpenAPI 管理面：AppSecret 鉴权、用户导入/禁用、应用配置
└── phantom-launchers/                    # 装配层：只做依赖聚合与配置，无业务代码
    ├── phantom-boot-monolith/            #   单体：聚合全部 service module，一个进程
    ├── phantom-boot-gateway/             #   独立微服务启动器，每服务一个……
    ├── phantom-boot-message/
    ├── phantom-boot-core/                #   （示例）按域合并：message+fanout+sync+conversation 合部署
    └── ...
```

依赖方向强约束（maven-enforcer 校验）：

```
launchers → services → service-api / infra / common / proto
services 之间禁止直接依赖（只能依赖 service-api）；MQ 事件是服务间另一条合法通路
```

## 2. 业务 module 内部结构

每个 `phantom-services/*` 采用一致的四层包结构（以 phantom-message 为例）：

```
im.phantom.message
├── adapter/      # 入口适配：网关指令处理器（SendMsgReq）、HTTP Controller
├── application/  # 用例编排：收单流程（幂等→取号→落库→事务消息）
├── domain/       # 领域模型与规则：Msg、conv_seq 取号、撤回窗口判定
├── infra/        # 出口适配：Timeline DAO、MQ 生产、service-api 调用
└── MessageAutoConfiguration.java   # @AutoConfiguration，launcher 引入即生效
```

- 每个 module 通过 **Spring Boot 自动装配**（`AutoConfiguration.imports`）暴露自己，launcher 不写 `@ComponentScan` 全局扫描，避免单体形态下的隐式耦合；
- module 对外提供两类表面：`service-api` 接口的本地实现（`@ConditionalOnMissingBean` 注册）+ MQ 事件（生产/消费）；
- 网关的指令分发采用 SPI：各业务 module 注册自己关心的 `Command` 处理器，网关 module 不依赖任何业务 module。

## 3. 可分可合：切换机制

拆合的唯一变化点在 **launcher 的依赖清单与配置**，业务代码零改动。

**同 JVM（合）**：launcher 同时引入调用方与被调方 module。被调方的本地实现类（实现 `phantom-api-*` 接口）被自动装配注册，调用即普通方法调用。

**跨进程（分）**：launcher 只引入调用方 module + `phantom-api-*`。api module 内置 gRPC client stub 实现，`@ConditionalOnMissingBean(本地实现)` 时自动生效——本地实现不在 classpath，stub 兜底，经 Nacos 发现对端。

```java
// phantom-api-route 内（示意）
@AutoConfiguration
public class RouteApiAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(RouteTableService.class)   // 本地实现不存在 → 远程 stub
  RouteTableService routeTableGrpcClient(GrpcChannelFactory f) { ... }
}
```

规则与边界：

- **优先本地**：合并部署时天然短路 RPC，无需任何配置项；
- **api module 三件套**：接口 + DTO（复用 phantom-proto 类型）+ gRPC stub 实现，保证"分"形态开箱即用；
- **MQ 通路不参与切换**：消息服务→扇出→推送等异步链路始终走 RocketMQ，单体也不例外（保住事务消息与削峰语义）；
- **数据库不共享实体**：module 各管各的表，禁止跨 module 直接读别人的表（enforcer 无法约束，靠评审约定 + 各 module 独立的库账号）。

## 4. 部署形态矩阵

| 形态 | launcher | 适用规模 | 说明 |
|---|---|---|---|
| 单体 | `phantom-boot-monolith` | 开发/联调、私有化小规模（万级同时在线） | 一个进程 + RocketMQ + Redis + DB；网关线程模型用虚拟线程 |
| 网关分离 | monolith 去掉 gateway + `phantom-boot-gateway` | 中等规模 | 长连接生命周期与业务发布解耦（业务重启不掉线），**推荐的第一刀** |
| 按域拆分 | 自定义聚合 launcher（如 boot-core / boot-push / boot-admin） | 按瓶颈演进 | 例：推送吞吐不足时单独拆出 phantom-push 扩容 |
| 全微服务 | 每服务一个 boot-* | 大规模多租户 SaaS | 与 MQ 架构文档 §1 架构图一一对应 |

同一形态矩阵下的镜像策略：CI 对每个 launcher 产出独立镜像，部署编排（K8s values / docker-compose profile）选择组合，无需改代码重新发版。

## 5. 构建与版本治理

- **BOM**：`phantom-dependencies` 锁定 Spring Boot 4.x、RocketMQ client 5.x、gRPC、Nacos 等三方版本与全部内部 module 版本；根 pom 只 import BOM；
- **proto 单一来源**：`phantom-proto` 用 protobuf-maven-plugin 从仓库根 `phantom-platform/contracts/proto/phantom/v1/*.proto` 生成 Java（与 SDK 侧共用同一份 .proto，杜绝双份契约漂移）；
- **统一版本号**：全工程单版本（revision 占位符 + flatten-maven-plugin），module 间永远同版本，不做独立演进——独立演进是"多仓多版本"的成本，与 monorepo 初衷相悖；
- **质量门禁**：enforcer（依赖方向）、单元测试按 module 并行、`mvn -pl` 增量构建配合 CI 变更检测。

## 6. 与既有架构文档的对应

| MQ 架构文档组件 | 承载 module |
|---|---|
| WS 网关（验签/路由登记/指令分发） | phantom-gateway |
| 消息服务（同步收单） | phantom-message |
| 扇出服务（user_seq 唯一写入方） | phantom-fanout |
| 业务服务群（已读/会话/群/关系/资料） | phantom-conversation / group / relation / user（+ sync 读取侧） |
| OpenAPI 服务（管理面） | phantom-openapi |
| 在线/离线推送服务 | phantom-push |
| 搜索索引 / 风控审计 / 媒体清理 | phantom-search / phantom-risk / phantom-media |
| 在线路由表（Redis Lua） | phantom-infra-redis 提供操作封装，phantom-api-route 提供跨服务读写契约 |

## 7. 交付物

- 本设计文档
- 后续实现阶段按 §1 目录树初始化 Maven 工程（write-plans 阶段展开为具体任务）
