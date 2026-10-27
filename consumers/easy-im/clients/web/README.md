# Easy IM Web 客户端

Easy IM 的 React + Vite + TypeScript Web 客户端，也可作为后续 Tauri 2 桌面端的 WebView UI 基础。界面采用全窗桌面布局，当前使用本地模拟数据，尚未连接 Easy IM BFF 和 Phantom SDK。

## 页面范围

- **登录与注册**：账号密码表单、密码显隐、注册校验和成功后回填登录名。
- **消息**：会话搜索、全部/未读筛选、群聊与单聊、图片/文件消息和消息发送。
- **通讯录**：好友与群组切换、联系人搜索、好友资料、群组公告、成员列表、新增好友和创建群组。
- **个人中心**：资料卡、二维码名片、资料编辑、账号安全、偏好开关和云空间概览。

宽屏使用「主导航 + 列表 + 详情」三栏布局；窗口窄于 `960px` 时切换为桌面侧栏加单内容区。

## 本地运行

```bash
cd consumers/easy-im/clients/web
npm install
npm run dev
```

开发地址：`http://localhost:5173`。

```bash
npm run build
npm run preview
```

## 接入状态

当前认证流程和展示数据均为本地模拟。后续需要接入：

- Easy IM BFF 的注册、登录、Session 和运行时配置。
- Phantom UserToken 获取流程。
- `@phantom/sdk-web` 的 WebSocket、消息、同步和领域 API。
- BFF HTTP 代理及真实媒体、好友、群组和搜索能力。

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

`src/platform/` 已预留统一的桌面平台接口：

- `index.ts`：识别 Tauri/浏览器运行环境，并写入 `data-runtime`、`data-platform`。
- `tauri.ts`：封装 Tauri 2 窗口控制 API。
- `browser.ts`：浏览器开发时的降级实现。
- `types.ts`：窗口控制和平台类型定义。

页面组件不直接依赖 Tauri API。后续的通知、文件选择、托盘和深链接能力也通过平台适配层暴露。

## 设计预览

- [登录](../../../../docs/consumers/easy-im/design/previews/auth-login.png)
- [注册](../../../../docs/consumers/easy-im/design/previews/auth-register.png)
- [消息](../../../../docs/consumers/easy-im/design/previews/desktop-chat.png)
- [通讯录](../../../../docs/consumers/easy-im/design/previews/desktop-contacts.png)
- [群组](../../../../docs/consumers/easy-im/design/previews/desktop-groups.png)
- [个人中心](../../../../docs/consumers/easy-im/design/previews/desktop-profile.png)
- [1280 × 820 窗口](../../../../docs/consumers/easy-im/design/previews/desktop-1280.png)
- [窄窗口消息列表](../../../../docs/consumers/easy-im/design/previews/desktop-narrow-list.png)
- [窄窗口聊天详情](../../../../docs/consumers/easy-im/design/previews/desktop-narrow-detail.png)

## 目录

```text
src/
├── components/     # 通用 UI 组件
├── platform/       # Tauri / 浏览器平台适配层
├── views/          # 认证、消息、通讯录、个人中心
├── App.tsx         # 主导航与页面装配
├── data.ts         # 本地模拟数据
├── icons.tsx       # 内联 SVG 图标
└── styles.css      # 桌面视觉系统与响应式布局
```
