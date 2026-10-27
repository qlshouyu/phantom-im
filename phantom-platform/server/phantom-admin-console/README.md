# Phantom Admin Console

Phantom IM PaaS 的平台管理控制台，用于应用创建、AppID/AppSecret 管理、密钥轮换和平台功能配置。

该工程是独立的 npm/Vite 应用，位于 `phantom-platform/server/` 下，但不加入 Maven reactor。

```bash
cd phantom-platform/server/phantom-admin-console
npm install
npm run dev
```

- 开发地址：`http://localhost:5174`
- Vite 将 `/openapi` 代理到 `http://127.0.0.1:28080`
- 构建：`npm run build`
- Playwright：`npm run e2e`

演示凭据不得用于生产环境。
