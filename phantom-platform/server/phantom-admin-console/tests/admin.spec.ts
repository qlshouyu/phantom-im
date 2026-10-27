import { test, expect } from "@playwright/test";

test("AppSecret 创建后只展示一次，详情不包含明文", async ({ page }) => {
  let created = false;
  await page.route("**/openapi/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === "POST" && path === "/openapi/apps") {
      created = true;
      await route.fulfill({ json: { code: 0, message: "ok", data: {
        app_id: "app_created", app_secret: "secret_once", feature_config: {},
      } } });
    } else {
      expect(created).toBeTruthy();
      await route.fulfill({ json: { code: 0, message: "ok", data: {
        app: { app_id: "app_created", name: "demo", status: "ACTIVE" },
        secret_versions: [{ version: "v2", status: "ACTIVE" }],
      } } });
    }
  });
  await page.goto("/");
  await page.getByTestId("create-app").click({ trial: true });
  await page.locator("#new-app-name").fill("demo");
  await page.getByTestId("create-app").click();
  await expect(page.getByTestId("secret-once")).toContainText("secret_once");
  await page.getByTestId("load-detail").click();
  await expect(page.getByTestId("app-detail")).toContainText("app_created");
  await expect(page.getByTestId("app-detail")).not.toContainText("secret_once");
});
