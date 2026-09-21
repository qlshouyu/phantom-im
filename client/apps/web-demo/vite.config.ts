import { defineConfig } from "vite";

export default defineConfig({
  server: { port: 5173, strictPort: true },
  // 直接消费 SDK 的 TS 源（monorepo 参考实现，无需预构建）
  resolve: { preserveSymlinks: false },
});
