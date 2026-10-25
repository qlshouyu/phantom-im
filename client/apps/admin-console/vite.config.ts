import { defineConfig } from "vite";

export default defineConfig({ server: { port: 5174, strictPort: true,
  proxy: { "/openapi": "http://127.0.0.1:28080" } } });
