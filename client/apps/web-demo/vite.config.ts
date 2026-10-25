import { defineConfig } from "vite";

export default defineConfig({
  server: {
    port: 5173,
    strictPort: true,
    proxy: Object.fromEntries(["/openapi", "/users", "/friends", "/blacklist", "/conversations",
      "/messages", "/groups", "/media", "/devices", "/push", "/search", "/reports", "/appeals",
      "/account"].map((path) => [path, "http://127.0.0.1:28080"])),
  },
  // 直接消费 SDK 的 TS 源（monorepo 参考实现，无需预构建）
  resolve: { preserveSymlinks: false },
});
