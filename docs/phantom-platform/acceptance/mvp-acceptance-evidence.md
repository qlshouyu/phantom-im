# MVP 验收证据（2026-10-24）

> 本文记录整改前的历史执行结果，命令中的旧目录名保留用于还原当时的验证上下文；当前目录结构见仓库根 README。

本文件仅记录实际执行过的命令与结果，不将未执行项标记为通过。

## 已执行

| 检查项 | 命令 | 结果 |
|---|---|---|
| Java 全量测试 | `cd phantom-im-server && mvn test` | 37 个测试类、131 个测试，0 失败、0 错误、0 跳过 |
| 服务端打包 | `cd phantom-im-server && mvn -DskipTests package` | 30 个 Maven reactor 模块全部成功 |
| 本地迁移幂等 | 对 `01-schema.sql`、`02-mvp-schema.sql` 执行两次 | 两次均成功 |
| 健康检查 | `curl http://127.0.0.1:28080/actuator/health` | MySQL、Redis、liveness/readiness 均 `UP` |
| 真实链路冒烟 | `cd tools/e2e-smoke && node smoke.mjs` | 18/18 通过：认证、发送、幂等、离线同步、撤回、群消息、设备顶替 |
| 安全检查 | `cd tools/e2e-smoke && node security-smoke.mjs` | 7/7 通过：OpenAPI 防重放、JWT/跨应用、媒体越权、未认证搜索、举报队列与审计、密钥明文检查 |
| 韧性测试 | `cd tools/e2e-smoke && node resilience-smoke.mjs` | 4/4 通过；Redis 重启后冒烟 18/18，MQ 暂停期间 outbox 留存并在恢复后发布，事件重投只写入一次 |
| 容量测试 | `CAPACITY_MEMBERS=500 node capacity-smoke.mjs` | 500 人群建群 1,370ms；单条消息收单 5,278ms；500 条写扩散 3,277ms；历史与搜索分页返回正确 |
| Web E2E | `cd client/apps/web-demo && npm run e2e` | 7/7 通过：单聊、重连、多消息顺序、Token 过期、四类媒体、通知权限/点击、设备顶替、关系、群管理、草稿、引用、搜索、举报、会话状态 |
| 管理控制台 E2E | `cd client/apps/admin-console && npm run e2e` | 1/1 通过：AppSecret 仅展示一次 |
| SDK 单测 | `cd client && npm test` | 14/14 通过 |
| Web/管理控制台构建 | `cd client && npm run build` | 两个应用均构建成功 |
| 规格校验 | `openspec validate implement-mvp-p0-p1 --strict` | 通过 |
| proto bundle | `node tools/e2e-smoke/gen-bundle.mjs` | 生成成功，无手工漂移 |
| 发布恢复演练 | `cd tools/e2e-smoke && node release-rehearsal.mjs` | 4/4 通过：MySQL 备份、媒体目录备份、关系链开关回滚、outbox 零积压 |
| FFmpeg 多平台打包脚本 | `node --check phantom-im-server/scripts/fetch-ffmpeg-tools.mjs` | 语法检查通过；四平台资产与 SHA-256 已固定，zip 直接生成到 resources |
| FFmpeg zip 写入器 | `node --test phantom-im-server/scripts/ffmpeg-zip.test.mjs` | 1/1 通过：可执行属性、压缩数据、中心目录和确定性输出 |
| 真实四平台下载 | `FFMPEG_TOOLS_PROXY=http://127.0.0.1:7897 mvn -pl phantom-services/phantom-media -am -DskipTests package` | 37 分钟完成下载、两层 SHA-256 校验和四个 zip 生成 |
| resources 命中后跳过下载 | 设置不可达 `FFMPEG_TOOLS_BASE_URL=http://127.0.0.1:9` 后执行 `mvn -DskipTests package` | 构建成功，证明本地 zip 存在时未访问下载源 |
| 单体 jar 装配 | `jar tf` 检查 `phantom-monolith.jar` 内嵌套的 media jar | 四平台 zip 均存在；最终 fat jar 约 269MB |

## 范围外或外部依赖项

- APNs/FCM 与第三方内容审核真实供应商联调未执行；本地使用明确标识的 provider/适配器契约，生产凭据由部署方注入。
- 推送吞吐未做供应商级压测；本次验证了本地 provider 契约、重试、DLQ 状态与失效 Token 清理。
- FFmpeg 首次四平台下载在当前网络下耗时约 37 分钟；已通过本机 HTTP 代理完成，并验证后续 resources 命中时完全跳过网络。
- P2 项 AC-11、NT-14、SR-15、SR-16 未实现，符合本次范围。

## 已知限制

- 本地搜索使用 MySQL `LIKE`，未实现拼音、分词和语义搜索。
- ffmpeg 工具按平台/架构放置到 classpath 后会在首次启动释放到持久化目录；仓库默认不提交第三方二进制。未放入当前平台工具时，视频上传保留初始化元数据并降级为不生成封面。
- 生产凭据、对象存储和推送/审核供应商由部署方注入；仓库内 demo 凭据不得用于生产。
