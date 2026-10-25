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
    await expect(messages(alice).filter({ hasText: text })).toContainText(/sent|delivered|read/);
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

test("图片、语音、视频和文件媒体消息可完成本地上传与发送", async ({ browser }) => {
  const stamp = Date.now();
  const aliceCtx = await browser.newContext();
  const bobCtx = await browser.newContext();
  const alice = await aliceCtx.newPage();
  const bob = await bobCtx.newPage();
  await open(alice, `media-alice-${stamp}`, `media-bob-${stamp}`);
  await open(bob, `media-bob-${stamp}`, `media-alice-${stamp}`);

  const files = [
    { name: "pixel.png", mimeType: "image/png",
      buffer: Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=", "base64"),
      kind: "IMAGE" as const },
    { name: "voice.wav", mimeType: "audio/wav", buffer: Buffer.from("RIFF0000WAVEfmt ", "ascii"), kind: "VOICE" as const },
    { name: "clip.mp4", mimeType: "video/mp4", buffer: Buffer.from("00000018667479706d703432", "hex"), kind: "VIDEO" as const },
    { name: "note.txt", mimeType: "text/plain", buffer: Buffer.from(`file-${stamp}`), kind: "FILE" as const },
  ];

  for (const file of files) {
    await alice.getByTestId("file").setInputFiles(file);
    await expect(alice.getByTestId("status")).toHaveText(`media-sent:${file.kind}`, { timeout: 15_000 });
  }

  await expect(messages(alice).filter({ hasText: "[图片]" })).toContainText(/sent|delivered|read/);
  await expect(messages(alice).filter({ hasText: "[语音]" })).toContainText(/sent|delivered|read/);
  await expect(messages(alice).filter({ hasText: "[视频]" })).toContainText(/sent|delivered|read/);
  await expect(messages(alice).filter({ hasText: "[文件]" })).toContainText(/sent|delivered|read/);
  await expect(messages(bob).filter({ hasText: "[文件]" })).toHaveCount(1, { timeout: 15_000 });

  await aliceCtx.close();
  await bobCtx.close();
});

test("浏览器通知权限与通知点击可进入目标会话", async ({ browser }) => {
  const stamp = Date.now();
  const ctx = await browser.newContext();
  await ctx.grantPermissions(["notifications"]);
  const page = await ctx.newPage();
  await open(page, `notify-${stamp}`, `notify-peer-${stamp}`);
  await page.getByTestId("tab-settings").click();
  await page.getByTestId("request-notification").click();
  await expect(page.getByTestId("notification-permission")).toHaveText("granted");

  const convId = await page.evaluate(() => (window as any).__convId as string);
  await page.evaluate((target) => (window as any).__phantom.handleNotificationClick({
    conv_id: target,
    deep_link: `phantom://conversation/${target}`,
  }), convId);
  await expect(page.getByTestId("status")).toHaveText(`notification:${convId}`);
  await ctx.close();
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

test("MVP 联系人、群聊、会话、搜索与通知设置主链路", async ({ browser }) => {
  const stamp = Date.now();
  const aliceCtx = await browser.newContext();
  const bobCtx = await browser.newContext();
  const carolCtx = await browser.newContext();
  const alice = await aliceCtx.newPage();
  const bob = await bobCtx.newPage();
  const carol = await carolCtx.newPage();
  await open(alice, `alice-mvp-${stamp}`, `bob-mvp-${stamp}`);
  await open(bob, `bob-mvp-${stamp}`, `alice-mvp-${stamp}`);
  await open(carol, `carol-mvp-${stamp}`, `alice-mvp-${stamp}`);
  const aliceId = await alice.evaluate(() => (window as any).__phantom.getCurrentUserId());
  const bobId = await bob.evaluate(() => (window as any).__phantom.getCurrentUserId());
  const carolId = await carol.evaluate(() => (window as any).__phantom.getCurrentUserId());

  const quoteSource = `quote-source-${stamp}`;
  await sendFrom(alice, quoteSource);
  await expect(messages(alice).filter({ hasText: quoteSource })).toContainText(/sent|delivered|read/, { timeout: 15_000 });
  const draftText = `draft-${stamp}`;
  await alice.getByTestId("input").fill(draftText);
  await alice.waitForTimeout(2_300);
  const savedDraft = await alice.evaluate(async () => {
    const client = (window as any).__phantom;
    const conversation = await client.getConversation((window as any).__convId);
    return conversation.draft;
  });
  expect(savedDraft).toBe(draftText);
  await messages(alice).filter({ hasText: quoteSource }).getByRole("button", { name: "引用" }).click();
  await alice.getByTestId("input").fill(`quote-reply-${stamp}`);
  await alice.getByTestId("send").click();
  await expect(alice.getByTestId("quoted-message")).toContainText(quoteSource, { timeout: 15_000 });

  await alice.getByTestId("tab-contacts").click();
  for (const id of [bobId, carolId]) {
    await alice.locator("#friend-id").fill(id);
    await alice.getByTestId("add-friend").click();
  }
  for (const page of [bob, carol]) {
    await page.getByTestId("tab-contacts").click();
    await expect(page.getByTestId("friend-request")).toBeVisible({ timeout: 15_000 });
    await page.getByTestId("friend-request").getByRole("button", { name: "接受" }).click();
    await expect(page.getByTestId("friend-item")).toHaveCount(1, { timeout: 15_000 });
  }

  await alice.getByTestId("tab-groups").click();
  const groupName = `MVP群-${stamp}`;
  await alice.locator("#group-name").fill(groupName);
  await alice.locator("#group-members").fill(`${bobId},${carolId}`);
  await alice.getByTestId("create-group").click();
  await expect(alice.getByTestId("group-item")).toHaveCount(1, { timeout: 15_000 });
  await alice.getByTestId("group-item").getByRole("button").click();
  await expect(alice.getByTestId("status")).toContainText("conversation:gr_");
  const groupMessage = `group-message-${stamp}`;
  await sendFrom(alice, groupMessage);
  await expect(messages(alice).filter({ hasText: groupMessage })).toContainText(/sent|delivered|read/, { timeout: 15_000 });
  await alice.getByTestId("group-announcement-input").fill(`公告-${stamp}`);
  await alice.getByTestId("group-announce").click();
  await expect(alice.getByTestId("status")).toHaveText("group-announced");
  await alice.getByTestId("group-nickname-id").fill(bobId);
  await alice.getByTestId("group-nickname").fill(`群昵称-${stamp}`);
  await alice.getByTestId("group-nickname-save").click();
  await expect(alice.getByTestId("status")).toHaveText("group-nickname-saved");
  await alice.getByTestId("group-mute").click();
  await expect(alice.getByTestId("status")).toHaveText("group-muted");
  await alice.getByTestId("group-nickname-id").fill(aliceId);
  await alice.getByTestId("group-nickname").fill(`群主-${stamp}`);
  await alice.getByTestId("group-nickname-save").click();
  await sendFrom(alice, `named-bubble-${stamp}`);
  await expect(messages(alice).filter({ hasText: `named-bubble-${stamp}` }))
    .toContainText(`群主-${stamp}`);

  const searchText = `search-${stamp}`;
  await alice.getByTestId("tab-chat").click();
  await sendFrom(alice, searchText);
  await expect(messages(alice).filter({ hasText: searchText })).toContainText(/sent|delivered|read/, { timeout: 15_000 });
  await alice.getByTestId("tab-search").click();
  await alice.getByTestId("search-query").fill(groupName);
  await alice.getByTestId("search-btn").click();
  await expect(alice.getByTestId("search-group")).toBeVisible({ timeout: 15_000 });
  await alice.getByTestId("search-group").click();
  await expect(alice.getByTestId("status")).toContainText("search-open:");
  await alice.getByTestId("search-query").fill(searchText);
  await alice.getByTestId("search-btn").click();
  await expect(alice.getByTestId("search-results")).toContainText(searchText, { timeout: 15_000 });
  await alice.getByTestId("search-query").fill(`no-match-${stamp}`);
  await alice.getByTestId("search-query").fill(`no-match-${stamp}-latest`);
  await alice.getByTestId("search-btn").click();
  await expect(alice.getByTestId("search-results")).toHaveText("未找到相关结果", { timeout: 15_000 });

  await alice.getByTestId("tab-chat").click();
  await messages(alice).filter({ hasText: searchText }).getByRole("button", { name: "举报" }).click();
  await expect(alice.locator("#report-panel")).toBeVisible();
  await alice.getByTestId("submit-report").click();
  await expect(alice.getByTestId("status")).toHaveText("reported");

  await alice.getByTestId("tab-contacts").click();
  await alice.getByTestId("blacklist-id").fill(carolId);
  await alice.getByTestId("block-user").click();
  await expect(alice.getByTestId("blacklist-item")).toHaveCount(1);
  await alice.getByTestId("blacklist-item").getByRole("button").click();
  await expect(alice.getByTestId("blacklist-item")).toHaveCount(0);

  await alice.getByTestId("tab-settings").click();
  await alice.getByTestId("save-push").click();
  await expect(alice.getByTestId("report-item")).toContainText("PENDING");
  await alice.getByTestId("tab-conversations").click();
  await expect(alice.getByTestId("conversation-item").first()).toBeVisible();
  await alice.getByTestId("conversation-item").first().getByRole("button", { name: "置顶" }).click();
  await expect(alice.getByTestId("conversation-item").first()).toContainText("取消置顶");
  await alice.getByTestId("conversation-item").first().getByRole("button", { name: "免打扰" }).click();
  await expect(alice.getByTestId("conversation-item").first()).toContainText("取消免打扰");

  await aliceCtx.close();
  await bobCtx.close();
  await carolCtx.close();
});
