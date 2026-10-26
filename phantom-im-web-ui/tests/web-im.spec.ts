import { expect, test, type Page } from "@playwright/test";

function uniqueName(prefix: string): string {
  return `${prefix}_${Date.now()}_${Math.floor(Math.random() * 100_000)}`.slice(0, 32);
}

async function register(page: Page, nickname: string, loginName: string): Promise<void> {
  await page.goto("/register");
  await page.getByTestId("register-nickname").fill(nickname);
  await page.getByTestId("register-login-name").fill(loginName);
  await page.getByTestId("register-password").fill("password123");
  await page.getByTestId("register-password-confirm").fill("password123");
  await page.getByTestId("register-submit").click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByText("注册成功，请登录")).toBeVisible();
}

async function login(page: Page, loginName: string): Promise<void> {
  await page.getByTestId("login-name").fill(loginName);
  await page.getByTestId("login-password").fill("password123");
  await page.getByTestId("login-submit").click();
  await expect(page.getByTestId("connection-status")).toHaveText("在线", { timeout: 20_000 });
}

async function phantomId(page: Page): Promise<string> {
  await page.getByTestId("tab-contacts").click();
  return (await page.getByTestId("my-phantom-id").textContent())!.trim();
}

test("真实注册登录、好友、单聊、代理与 Token 刷新", async ({ browser }) => {
  const suffix = Date.now();
  const accountA = uniqueName("weba");
  const accountB = uniqueName("webb");
  const contextA = await browser.newContext();
  const contextB = await browser.newContext();
  const alice = await contextA.newPage();
  const bob = await contextB.newPage();

  await register(alice, "Alice Web", accountA);
  await register(bob, "Bob Web", accountB);
  await login(alice, accountA);
  await login(bob, accountB);
  const aliceId = await phantomId(alice);
  const bobId = await phantomId(bob);
  expect(aliceId).toMatch(/^u_/);
  expect(bobId).toMatch(/^u_/);

  await alice.getByTestId("friend-id").fill(bobId);
  await alice.getByTestId("add-friend").click();
  await bob.getByTestId("friend-request").getByRole("button", { name: "接受" }).click();
  await expect(bob.getByTestId("friend-item")).toHaveCount(1, { timeout: 15_000 });

  await bob.getByTestId("friend-item").getByRole("button", { name: "发消息" }).click();
  await alice.getByTestId("friend-item").getByRole("button", { name: "发消息" }).click();
  const text = `e2e-${suffix}`;
  await alice.getByTestId("message-input").fill(text);
  await alice.getByTestId("send-message").click();
  await expect(alice.getByTestId("messages").getByText(text)).toBeVisible({ timeout: 15_000 });
  await expect(bob.getByTestId("messages").getByText(text)).toBeVisible({ timeout: 15_000 });

  await alice.getByTestId("media-input").setInputFiles({
    name: "pixel.png",
    mimeType: "image/png",
    buffer: Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==", "base64"),
  });
  await expect(alice.getByTestId("message")).toHaveCount(2, { timeout: 20_000 });
  await expect(bob.getByTestId("message")).toHaveCount(2, { timeout: 20_000 });

  await alice.getByTestId("open-diagnostics").click();
  await expect(alice.getByTestId("diagnostics-panel")).toBeVisible();
  await alice.getByTestId("trigger-expired-token").click();
  await expect(alice.getByTestId("diagnostic-connection")).toHaveText("online", { timeout: 20_000 });
  await expect(alice.getByTestId("diagnostics-panel")).toContainText("token-refreshed", { timeout: 20_000 });
  await alice.getByTestId("disconnect-reconnect").click();
  await expect(alice.getByTestId("diagnostic-connection")).toHaveText("closed", { timeout: 5_000 });
  await expect(alice.getByTestId("diagnostic-connection")).toHaveText("online", { timeout: 20_000 });

  const secondTab = await contextA.newPage();
  await secondTab.goto("/");
  await expect(secondTab.getByTestId("connection-status")).toHaveText("在线", { timeout: 20_000 });
  await expect(alice.getByTestId("connection-status")).toHaveText("已下线", { timeout: 20_000 });
  await expect(alice.getByTestId("diagnostics-panel")).toContainText("SAME_TYPE_LOGIN", { timeout: 20_000 });

  // 踢下线是终止态，页面不得自行重连。
  await alice.waitForTimeout(1_500);
  await expect(alice.getByTestId("connection-status")).toHaveText("已下线");
  await expect(secondTab.getByTestId("connection-status")).toHaveText("在线");

  // 用户显式重连后，旧连接提示必须随新的 online 状态一起清除。
  await alice.getByTestId("disconnect-reconnect").click();
  await expect(alice.getByTestId("connection-status")).toHaveText("在线", { timeout: 20_000 });
  await expect(alice.locator(".kicked-banner")).toHaveCount(0);
  await expect(secondTab.getByTestId("connection-status")).toHaveText("已下线", { timeout: 20_000 });

  await contextA.close();
  await contextB.close();
});
