import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  timeout: 30_000,
  use: { baseURL: "http://localhost:5174", trace: "on-first-retry" },
  projects: [{ name: "chrome", use: { ...devices["Desktop Chrome"], channel: "chrome" } }],
  webServer: { command: "npm run dev", url: "http://localhost:5174", reuseExistingServer: true },
});
