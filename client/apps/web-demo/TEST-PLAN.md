# Web Demo 测试方案

Web Demo 是 `@phantom/sdk-web` 的验证载体。本方案验证 Demo 在真实服务端上正确落地 6 份 spec 的**客户端可观测行为**——测的是「SDK 行为 + 服务端闭环」，不是 UI 美观。

- 上游 spec：`openspec/changes/m0-foundation-skeleton/specs/`（connection-gateway / single-chat-messaging / message-fanout-delivery / sync-catchup / sequence-allocation / web-sdk）
- 被测件：`client/apps/web-demo`（消费 `client/packages/phantom-sdk-web`）

## 1. 前置条件

- 单体服务端运行于 `127.0.0.1:28080`（MySQL/Redis/RocketMQ 就绪，`TOPIC_MSG_EVENT` 已建为 TRANSACTION 类型）。
- Vite dev server `127.0.0.1:5173`（Playwright `webServer` 会自动拉起并复用）。
- 断言锚点（`data-testid`）：`status` / `messages` / `message` / `input` / `send` / `disconnect` / `reconnect`。
- 运行：`cd client/apps/web-demo && npm run e2e`（默认系统 Chrome `channel: chrome`，免下载浏览器）。

## 2. 测试矩阵（用例 × spec × 现状）

| # | 用例 | 对应 spec | 方式 | 现状 |
|---|---|---|---|---|
| A1 | 登录成功 → status=online | web-sdk / connection-gateway | e2e | ✅ 已覆盖 |
| A2 | Token 过期 → 触发 `tokenExpired` | web-sdk | e2e（需造过期 token，小幅扩 demo） | ⬜ 待补 |
| A3 | 首帧非法 / 认证超时断开 | connection-gateway | 集成（非 demo） | ⬜ 待补 |
| B1 | 发文本 → 自身显示 sent + convSeq | single-chat-messaging | e2e | ✅ |
| B2 | 重发同 clientMsgId → msgId/convSeq 不变（幂等） | single-chat-messaging | SDK 单测（见 §6） | ⬜ 待补 |
| B3 | 文本 > 5000 → 失败态 | single-chat-messaging | e2e | ⬜ 待补 |
| C1 | A 发 → B 在线实时收到 | message-fanout-delivery | e2e | ✅ |
| C2 | 重复推送 → B 端 msgId 去重（列表不重复） | web-sdk / fanout | SDK 单测（见 §6） | ⬜ 待补 |
| D1 | B 断线 → A 发 → B 重连 SyncReq 补齐 | sync-catchup | e2e | ✅ |
| D2 | 多条离线消息 → 重连按 user_seq 有序补齐 | sync-catchup | e2e | ⬜ 待补 |
| D3 | 刷新页面（游标持久化）→ 不重复拉历史 | web-sdk | e2e | ⬜ 待补 |
| E1 | 同用户第二标签登录 → 旧标签被踢（`kicked`） | connection-gateway | e2e（需小幅扩 demo） | ⬜ 待补 |
| F1 | 断网自动重连（指数退避） | web-sdk | SDK 单测 / 手动 | ⬜ 待补 |

## 3. 当前 e2e（已通过）

`tests/e2e.spec.ts` 1 条：**A1 + B1 + C1 + D1** 主链路（在线收发 → 断线 → 离线发 → 重连补齐）。系统 Chrome，`1 passed`。

## 4. 建议补充的 Playwright 用例

### 4.1 现有 demo UI 即可测（无需改 demo）

- **B3 超长**：`input` 填 5001 字符 → `send` → 断言该消息气泡 `state=failed`。
- **D2 多条离线有序**：B `disconnect` → A 连发 3 条 → B `reconnect` → 断言 `messages` 内 3 条按 `convSeq` 递增出现且顺序正确。
- **D3 游标持久化**：单页发若干条 → `page.reload()` → 断言历史不重复渲染、且可继续收发（验证 localStorage 游标 + 重连 sync 不回灌）。

### 4.2 需小幅扩 demo 才能测

- **E1 互踢**：demo 支持 `?user=alice&device=2` 起第二连接 → 断言第一个标签 `status` 进入 `kicked:SAME_TYPE_LOGIN`（现已有 `kicked` 事件与 alert，改为写入 status 文案即可）。
- **A2 Token 过期**：demo 加「用已过期 token 登录」入口（`dev-token` 支持传负 `exp`）→ 断言进入 token 过期回调路径。

## 5. 手动冒烟（5 分钟）

```
1. 两标签页 /?user=alice 与 /?user=bob → 均 online
2. alice 发 "hi" → bob 实时出现，气泡显示 seq、"我/对方"
3. bob 点「断开」→ status=closed；alice 再发 "offline" → bob 无
4. bob 点「重连」→ online → "offline" 经 SyncReq 出现
5. bob 刷新页面 → 历史在（游标），继续收发正常
```

## 6. 分层测试策略（补齐 e2e 盲区）

e2e 造不出的场景下沉到 **SDK 单测**（Vitest + mock WebSocket）：

- 重复 MsgPush（同 msgId）→ store 只一条、只 emit 一次（C2）
- 收到 `user_seq ≤ 游标` 的事件 → 丢弃（sync-catchup 幂等）
- KickPush → 不自动重连（E1 的纯客户端部分）
- `onClose` → 指数退避重连时序（F1，用假定时器）
- 发送 15s 无响应 → 置 `failed`（web-sdk 超时）

服务端侧的幂等 / 事务回查 / 取号回退 → 归 **Java 集成测试**（不经 demo），对应 tasks.md 中标注「留待补充」的单测项：
- 取号 Redis 丢 key → 对齐到 `MySQL max + 1`，不重发（sequence-allocation 两场景）
- 落库失败 → 事务回查 rollback（single-chat-messaging）
- 扇出重复消费 → `(user_id, msg_id)` 幂等（message-fanout-delivery）

## 7. 验收标准

- P0 用例（A1 / B1 / C1 / D1 / D2 / D3）全绿 = Demo 可发布。
- e2e 无固定 `sleep`，一律用 `expect(...).toHaveCount/toHaveText` 的自动等待（现用例已遵循）。
- 每条用例自带唯一时间戳后缀文本，避免多次运行的历史消息串扰。
- CI 用系统 Chrome `channel: 'chrome'`（免下载）或缓存 Playwright 浏览器；服务端与依赖中间件在 CI 前置拉起。

## 8. 运行清单

```bash
# 服务端（另开终端）
cd phantom-im-server && java -jar phantom-launchers/phantom-boot-monolith/target/phantom-monolith.jar

# e2e（自动拉起 vite）
cd client/apps/web-demo && npm run e2e

# 手动体验
cd client/apps/web-demo && npm run dev   # /?user=alice 与 /?user=bob 两标签页
```
