# Easy IM Web 真实集成验收报告

- 验收日期：2026-10-27
- 变更：`complete-easy-im-web-integration`
- 环境：macOS、JDK 21、Node.js、MySQL 8.0.46、Redis 7.4.8、RocketMQ 5.3.2、Phantom 单体、Easy IM BFF、Vite Web。
- 数据隔离：Playwright 每次使用带时间戳的唯一账号；验证数据写入本地 `phantom_im` 与 `phantom_web_im`。

## 1. 启动前置

```bash
docker compose -f deploy/local/docker-compose.yml up -d
docker compose -f deploy/local/rocketmq/docker-compose.yml up -d

cd phantom-platform/server
mvn -DskipTests package
java -jar phantom-launchers/phantom-boot-monolith/target/phantom-monolith.jar

cd phantom-im/bff
set -a; source .env; set +a
mvn -s .mvn/settings.xml spring-boot:run

cd phantom-im/clients/web
npm install
npm run dev
```

前置健康检查：

```bash
node tools/easy-im-preflight.mjs
```

实际结果：MySQL、Redis、RocketMQ Namesrv、RocketMQ Broker、Phantom、BFF 共 **6/6** 通过。

## 2. 自动化结果

| 范围 | 命令 | 实际结果 |
|---|---|---|
| Web 单元/组件 | `cd phantom-im/clients/web && npm test` | 6 个文件，23 项通过 |
| Web 类型检查 | `npm run typecheck` | 通过 |
| Web 构建 | `npm run build` | 通过；仅提示主 chunk 约 543 KB |
| Web 契约 | `npm run test:contract` | 65 个 ApiRoutes、61 个 OpenAPI 路径、65 个 SDK HTTP 调用一致 |
| SDK 测试 | `cd phantom-platform/sdks/web && npm test -- --run` | 20 项通过 |
| SDK 类型检查 | `npm run typecheck` | 通过 |
| BFF 全量 | `cd phantom-im/bff && WEB_IM_REAL_HTTP_SMOKE=true mvn -s .mvn/settings.xml test` | 18 项通过，含真实 MySQL/Redis HTTP 冒烟 |
| Phantom 平台全量 | `cd phantom-platform/server && mvn test` | 30 个模块，163 项通过 |
| 平台现有冒烟 | `cd tools/e2e-smoke && node smoke.mjs` | 18/18 通过 |
| Web 开发态 E2E | `cd phantom-im/clients/web && npm run test:e2e -- --timeout=240000` | **14 项通过，1 项生产式用例按环境跳过** |
| 生产式 E2E | `E2E_PROD=true E2E_BASE_URL=http://127.0.0.1:28090 npm run test:e2e -- e2e/production.spec.ts` | **1 项通过** |
| 截图用例 | `npm run test:e2e -- e2e/acceptance.spec.ts` | 1 项通过并生成 4 张截图 |

## 3. 已验收场景

### 认证与生命周期

- 注册不自动登录，登录失败保持未登录。
- 登录后建立 SDK 连接；刷新页面通过 HttpOnly Session 恢复账号并重新签发 Phantom Token。
- 退出确认后停止 SDK、注销 BFF Session，再次访问展示登录页。
- 模拟 Token 过期后通过 BFF 单飞刷新并恢复 SDK，不要求重新输入密码。
- 主动断线进入退避重连，重连成功后状态从 `reconnecting` 恢复为 `ready`。
- 同账号同类型新连接顶替旧连接；旧连接停止自动重连并展示原因。

### 消息与会话

- 双用户好友申请、接受、单聊实时送达。
- 三成员群组创建、群消息送达。
- 会话列表的未读、已读接口结果和消息展示一致。
- 草稿在刷新后恢复；空草稿会清除。
- 图片通过真实分片上传并发送，消息含真实媒体标识。
- 置顶、免打扰和删除会话刷新后保持服务端结果。
- 引用回复、转发、撤回和本地删除按各自语义生效；本地删除不影响对方。
- 单聊在线状态和输入提示出现，输入停止后清除。

### 通讯录、群组与个人中心

- 无效登录名或 Phantom ID 有明确错误；确认用户资料后的有效申请、接受、拒绝、忽略均验证。
- 好友备注、好友分组、拉黑、解除拉黑、重新添加和删除好友均写入真实后端。
- 群邀请、成员禁言、角色变更、移出成员、公告、群昵称、群免打扰、退出与解散均验证。
- QR Card 通过真实接口生成、刷新，并以二维码图片渲染。
- 个人资料保存后刷新仍为服务端值。
- 设备列表展示当前 Web 与另一 DESKTOP SDK 连接；远程下线后目标连接退出在线状态。
- 推送设置经过 SDK 保存并重新读取一致。
- 浏览器通知点击可定位真实会话。
- 收藏夹、云空间、群文件、群相册和在线状态隐私设置明确显示暂未开放。

### 搜索与诊断

- 全局搜索可分类返回联系人、群组和消息。
- 点击消息结果可打开会话并高亮 `conv_seq`。
- 会话内搜索可加载命中上下文并高亮。
- 个人中心诊断展示连接、Phantom ID、AppID、WebSocket、Request ID、测试模式和最近事件；日志不包含 Token、Cookie、密码或消息正文。

## 4. 契约修复记录

真实 UI 链路暴露并修复：

1. OpenAPI 遗漏 14 个用户面路径及清空历史 DELETE 声明。
2. OpenAPI bootstrap 应用未落库，导致用户导入外键失败。
3. `GET/PUT /users/me` 缺少统一用户资料控制器，旧关系卡控制器与 OpenAPI 冲突。
4. 群邀请直接更新尚不存在的用户会话只读状态；新增 `addGroupMembers` 会话契约。
5. 群成员移出接口 body/query 与 SDK 不一致。
6. 解散群组仍出现在群列表。
7. SDK 补齐好友分组、解除禁言和主动重连能力。
8. 前端修复在线状态 `status` 枚举读取、输入提示清理、好友备注初始化竞态和临时会话快照合并。

## 5. 关键页面截图

- [登录页](acceptance/auth-login.png)
- [消息页](acceptance/desktop-chat.png)
- [通讯录](acceptance/desktop-contacts.png)
- [个人中心](acceptance/desktop-profile.png)

## 6. 未覆盖或明确未开放

- 语音/视频通话：无公开 PaaS 通话契约。
- 收藏夹、云空间、群文件、群相册：无公开契约，UI 显示暂未开放。
- “展示在线状态”隐私偏好：无独立公开 API，未伪造开关。
- 浏览器通知测试使用受控 `Notification` 替身验证应用接线；操作系统级通知展示不在无头浏览器验收范围。
- Playwright HTML 报告和失败 trace 位于 `phantom-im/clients/web/playwright-report/` 与 `test-results/`，默认不入库。

## 7. 已知提示

- Web 主 bundle 超过 Vite 500 KB 提示阈值，不影响构建成功。后续可对二维码/富 UI 依赖做代码分割。
- 本地测试账号和消息会保留在开发数据库；自动化账号均带唯一后缀，不依赖固定演示账号。
