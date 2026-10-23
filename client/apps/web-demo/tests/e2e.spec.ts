import { test, expect, type Page } from "@playwright/test";

// 端到端：两个浏览器上下文模拟用户 A/B，联合验证 6 份 spec 主场景。
// 前置：服务端单体运行在 127.0.0.1:28080（RocketMQ/MySQL/Redis 就绪）。

async function online(page: Page) {
  await expect(page.getByTestId("status")).toHaveText("online", { timeout: 15_000 });
}
async function sendFrom(page: Page, text: string) {
  await page.getByTestId("input").fill(text);
  await page.getByTestId("send").click();
}
function messages(page: Page) {
  return page.getByTestId("messages").getByTestId("message");
}
async function open(page: Page, user: string, peer: string, extra = "") {
  await page.goto(`/?user=${encodeURIComponent(user)}&peer=${encodeURIComponent(peer)}${extra}`);
  await online(page);
}

test("单聊在线收发 + 断线重连 SyncReq 补齐", async ({ browser }) => {
  const stamp = Date.now();
  const aliceUser = `alice-${stamp}`;
  const bobUser = `bob-${stamp}`;
  const ctxA = await browser.newContext();
  const ctxB = await browser.newContext();
  const alice = await ctxA.newPage();
  const bob = await ctxB.newPage();

  await open(alice, aliceUser, bobUser);
  await open(bob, bobUser, aliceUser);

  // 1) 在线收发：Alice 发，Bob 实时收到（自动 ACK 在 SDK 内）
  const online1 = `online-${stamp}`;
  await sendFrom(alice, online1);
  await expect(messages(alice).filter({ hasText: online1 })).toHaveCount(1);
  await expect(messages(bob).filter({ hasText: online1 })).toHaveCount(1, { timeout: 15_000 });

  // 2) Bob 断开（不自动重连）
  await bob.getByTestId("disconnect").click();
  await expect(bob.getByTestId("status")).toHaveText("closed");

  // 3) Bob 离线期间 Alice 再发
  const offline1 = `offline-${stamp}`;
  await sendFrom(alice, offline1);
  await expect(messages(alice).filter({ hasText: offline1 })).toHaveCount(1);
  // 此刻 Bob 尚未收到
  await expect(messages(bob).filter({ hasText: offline1 })).toHaveCount(0);

  // 4) Bob 重连 → SyncReq 补齐离线消息
  await bob.getByTestId("reconnect").click();
  await online(bob);
  await expect(messages(bob).filter({ hasText: offline1 })).toHaveCount(1, { timeout: 15_000 });

  await ctxA.close();
  await ctxB.close();
});

test("多条离线消息有序补齐，刷新后本地历史不重复", async ({ browser }) => {
  const stamp = Date.now();
  const aliceUser = `alice-multi-${stamp}`;
  const bobUser = `bob-multi-${stamp}`;
  const ctxA = await browser.newContext();
  const ctxB = await browser.newContext();
  const alice = await ctxA.newPage();
  const bob = await ctxB.newPage();

  await open(alice, aliceUser, bobUser);
  await open(bob, bobUser, aliceUser);

  // 文本超长：客户端进入失败态，绝不投递。
  const tooLong = `too-long-${stamp}-` + "x".repeat(5001);
  await sendFrom(alice, tooLong);
  await expect(messages(alice).filter({ hasText: tooLong })).toContainText("failed");
  await expect(messages(bob).filter({ hasText: tooLong })).toHaveCount(0);

  await bob.getByTestId("disconnect").click();
  await expect(bob.getByTestId("status")).toHaveText("closed");

  const offline = [1, 2, 3].map((n) => `offline-${stamp}-${n}`);
  for (const text of offline) {
    await sendFrom(alice, text);
    await expect(messages(alice).filter({ hasText: text })).toContainText("sent");
  }

  await bob.getByTestId("reconnect").click();
  await online(bob);
  for (const text of offline) {
    await expect(messages(bob).filter({ hasText: text })).toHaveCount(1, { timeout: 15_000 });
  }
  const received = await messages(bob).allTextContents();
  const positions = offline.map((text) => received.findIndex((line) => line.includes(text)));
  expect(positions.every((p) => p >= 0)).toBeTruthy();
  expect(positions).toEqual([...positions].sort((a, b) => a - b));

  // 游标和本地消息均持久化；刷新后历史仍在，且不会因 SyncReq 重复渲染。
  await bob.reload();
  await online(bob);
  for (const text of offline) {
    await expect(messages(bob).filter({ hasText: text })).toHaveCount(1);
  }

  await ctxA.close();
  await ctxB.close();
});

test("Token 过期进入明确状态", async ({ page }) => {
  const stamp = Date.now();
  await page.goto(`/?user=expired-${stamp}&peer=peer-${stamp}&expired=1`);
  await expect(page.getByTestId("status")).toHaveText("token-expired", { timeout: 15_000 });
});

test("同用户第二个 WEB 连接顶替旧连接", async ({ browser }) => {
  const stamp = Date.now();
  const user = `kick-${stamp}`;
  const peer = `peer-${stamp}`;
  const firstCtx = await browser.newContext();
  const secondCtx = await browser.newContext();
  const first = await firstCtx.newPage();
  const second = await secondCtx.newPage();

  await open(first, user, peer);
  await open(second, user, peer);

  await expect(first.getByTestId("status")).toHaveText("kicked:KICK_REASON_SAME_TYPE_LOGIN", { timeout: 15_000 });
  await expect(second.getByTestId("status")).toHaveText("online");

  await firstCtx.close();
  await secondCtx.close();
});
