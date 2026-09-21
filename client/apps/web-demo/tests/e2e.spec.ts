import { test, expect, type Page } from "@playwright/test";

// 端到端：两个浏览器上下文模拟用户 A/B，联合验证 6 份 spec 主场景。
// 前置：服务端单体运行在 127.0.0.1:18080（RocketMQ/MySQL/Redis 就绪）。

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

test("单聊在线收发 + 断线重连 SyncReq 补齐", async ({ browser }) => {
  const stamp = Date.now();
  const ctxA = await browser.newContext();
  const ctxB = await browser.newContext();
  const alice = await ctxA.newPage();
  const bob = await ctxB.newPage();

  await alice.goto("/?user=alice&peer=bob");
  await bob.goto("/?user=bob&peer=alice");
  await online(alice);
  await online(bob);

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
