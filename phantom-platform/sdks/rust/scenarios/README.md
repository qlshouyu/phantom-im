# CLI 领域场景

在 Rust workspace 目录运行 `cargo run -p phantom-sdk-cli -- scenario scenarios/relations.json --dry-run`。六份 JSON 均使用公开 SDK 方法，可先预检结构，再在具备前置条件的平台执行。

执行前修改每份文件的 `client/new.config`：平台 base_url、app_id、user_id 和唯一 device_id，并由接入方在 `PHANTOM_USER_TOKEN` 提供有效 Token。JSON 不执行环境插值，`REPLACE_WITH_*` 是须手工替换的业务 ID；dry-run 只验证其结构，不验证服务端是否存在。

| 文件 | 前置条件与副作用 |
| --- | --- |
| [relations.json](relations.json) | 托管关系功能、有效好友二维码 Token；读取资料、好友/申请/分组/黑名单、关系设置和二维码 |
| [groups.json](groups.json) | 当前用户是所填群的成员，群内已发布公告，并具有创建邀请权限；没有公告时服务端返回业务拒绝。读取公告会标记已读，创建邀请会写服务端数据 |
| [media.json](media.json) | 当前账号有权访问 READY 媒体；读取元数据及原始内容，CLI 以 base64 返回内容 |
| [devices.json](devices.json) | 设备、推送和在线状态功能可用；设置前台状态、订阅后取消 Presence，并交付通知点击事件 |
| [search.json](search.json) | 填入可访问会话；全局搜索、建议与会话搜索，结果受服务端权限限制 |
| [compliance.json](compliance.json) | 举报/申诉/导出查询权限，以及修改未成年人模式的测试授权；set_minor_mode 会产生业务写入 |

这些场景使用 Memory，避免演示产生长期本地缓存。服务端拒绝、功能未开放、ID 无效均属于真实失败，后续步骤 skipped，不能当作平台通过。上传、下载、取消、Failed/resend、全量重建和多后端一致性由 workspace 的专属受控测试验证；上述文件不伪造这些服务端前提。
