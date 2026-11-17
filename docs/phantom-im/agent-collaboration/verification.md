# 方案文档与图稿验证记录

日期：2026-11-16。分支：`feature-agent-cc`。范围仅限方案文档、说明入口及 draw.io 图稿，不代表协同功能已实现或运行验收通过。

## 已执行的检查

| 检查 | 实际结果 |
| --- | --- |
| 当前基线 | 从 `feature/rust-sdk` 创建 `feature-agent-cc`，继承提交 `6d08b12` 的 Rust SDK |
| 仓库现状核对 | 阅读 PC README、Rust 入口/Cargo 清单、Web 消息网关、BFF 认证控制器、Rust SDK README/校验代码及共享消息 proto |
| Claude Code 接口检查 | 本机 `claude --version` 为 2.1.325；检查 `claude --help` 与 `claude mcp add --help` 的流式输入输出、会话恢复及 HTTP MCP 参数；未执行模型调用 |
| draw.io 结构检查 | 三页 XML 可解析；节点 ID 和连线端点有效；每条边都有 geometry；技能验证器报告 0 error、0 节点重叠、0 穿过无关节点，另有 1 处线段交叉提示 |
| draw.io 图片导出 | 使用本机 draw.io 32.6.10，按第 1/2/3 页分别导出 architecture.png、task-sequence.png、routing-lifecycle.png；PNG 签名、CRC、IDAT 解压和 IEND 检查通过 |
| 图片检查 | 逐张查看中文、节点、容器、箭头及标签；修正组件图中短边标签侵入节点正文的问题，并重新导出 |
| 本地链接 | 19 个本地文件链接目标均存在；正文包含三张 PNG 预览 |
| 差异检查 | `git diff --check` 通过；新增文本同时执行行尾空白检查 |

组件图保留一处独立通道的交叉：SDK 到 IM 的消息链路，与进程管理到 Agent 的本地链路。连线采用跳线样式，没有连接圆点，不表示两个通道互通；交叉不穿过组件。其余路由图和时序图没有结构验证警告。

## 可重复检查

在仓库根目录执行。技能目录是本机工具路径，不是项目运行依赖。

```bash
python3 /Users/lugao2/.agents/skills/drawio-skill/scripts/validate.py \
  docs/phantom-im/agent-collaboration/diagrams/agent-collaboration.drawio --score

git diff --check
```

PNG 使用 draw.io 原生导出，宽度分别为 2000、1800、2000；未嵌入 XML，以便常规图片查看器读取。可编辑源文件是同目录的三页 `agent-collaboration.drawio`。

## 未执行的验证

未修改产品代码，不运行 Java、Web 或 Rust 功能测试。本次没有启动 Claude 执行进程、调用模型、读取账号凭据、登录真实账号、建群或运行真实协同场景。跨平台打包、MCP 调用、认领竞争、任务恢复、worktree 隔离及 Token 消耗比较均是主文档中的后续实施验收项。

## PRD 补充检查（2026-11-17）

本次新增 [多 Agent 协同 PRD](prd-agent-phantom.md)，并在技术方案、Easy IM README 和 PC README 中建立需求入口；未修改图稿或产品代码。

| 检查 | 实际结果 |
| --- | --- |
| 需求编号 | 32 项 ACC-FR 功能需求、7 项 ACC-NFR 非功能需求、33 项 ACC-AC 验收场景，编号连续、无重复 |
| 功能需求追踪 | 全部 32 项功能需求均关联验收场景，场景未引用不存在的需求编号 |
| 非功能需求追踪 | 全部 7 项非功能需求均明确关联验收场景 |
| 验收格式 | 全部 33 个场景均包含 Given、When、Then；真实运行验收仍未执行 |
| 文档结构 | 配置、完整流程、权限矩阵、状态、默认限制及发布门槛已覆盖；章节编号无重复 |
| 本地链接 | PRD、设计、验证记录和两个 README 的本地文件目标与 Markdown 锚点检查通过；PRD 复用现有图稿 |
| 文本质量 | Markdown 表格列数、行尾空白和 git diff --check 检查通过 |

本次检查仅证明需求文档结构及引用完整，不证明 32 项产品能力已实现，也不代替真实协同测试。
