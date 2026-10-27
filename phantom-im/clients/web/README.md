# Easy IM Web 客户端

React 19 + TypeScript + Vite Web 客户端，通过 Easy IM BFF 与 `@phantom/sdk-web` 接入真实 Phantom IM。

## 功能

- 真实注册、登录、Session 恢复、Token 刷新与退出。
- 「消息 / 通讯录 / 个人中心」三栏桌面布局。
- 会话、历史和实时消息、草稿、文本/媒体发送、引用、转发、撤回、本地删除、置顶、免打扰和删除会话。
- 好友、好友申请、备注、分组、黑名单、群组、成员权限、公告、禁言、邀请、退出和解散。
- 个人资料、QR Card、设备远程下线、推送设置、浏览器通知和搜索。
- 连接状态、Token 过期、断线重连、同端顶替和公开事件诊断。
- 无 PaaS 契约的收藏夹、云空间、群文件、群相册、语音/视频通话显示暂未开放。

## 本地开发

先按 [`../../README.md`](../../README.md) 启动 Phantom 与 BFF。

```bash
npm install
npm run dev
```

开发地址：`http://127.0.0.1:5173`。Vite 将 `/api` 代理到 `http://127.0.0.1:28090`；可通过 `EASY_IM_BFF_URL` 覆盖目标。

生产构建：

```bash
npm run build
```

BFF 默认从 `../clients/web/dist/` 托管构建产物。生产态保持同源 `/api` 路径。

## 测试

```bash
npm test             # Vitest 单元/组件测试
npm run typecheck
npm run build
npm run test:contract
npm run test:e2e -- --timeout=240000
```

生产式同源 E2E：

```bash
E2E_PROD=true E2E_BASE_URL=http://127.0.0.1:28090 \
  npm run test:e2e -- e2e/production.spec.ts
```

测试失败会在 `test-results/` 保留截图、视频和 trace，HTML 报告在 `playwright-report/`。Vite 已忽略这两个测试产物目录，避免运行期间热重载。

## 架构

```text
src/
├── api/          # 类型化 BFF 客户端、ApiResponse/错误/request_id
├── app/          # 启动状态机、Token/SDK Provider、诊断日志
├── auth/         # Phantom UserToken 内存单飞管理
├── components/   # 通用 UI
├── domain/       # 稳定领域模型与协议映射
├── phantom/      # SDK 适配器、领域网关、设备 ID
├── platform/     # Tauri/浏览器平台适配
├── views/        # 认证、消息、通讯录、个人中心
├── App.tsx
└── styles.css
```

`src/data.ts` 已移除；业务数据只来自 BFF、SDK 或明确的本机 UI 偏好。

## Tauri 2 约定

第一版桌面端使用系统原生标题栏，建议窗口保持：

```json
{
  "app": {
    "windows": [
      {
        "title": "Easy IM",
        "width": 1280,
        "height": 820,
        "minWidth": 1080,
        "minHeight": 700,
        "center": true,
        "resizable": true,
        "decorations": true
      }
    ]
  }
}
```

`src/platform/` 仍负责 Tauri/浏览器差异，页面不直接依赖 Tauri API。
