#!/usr/bin/env python3
"""从公开接口基线、实现和 Controller 契约测试更新可审阅的覆盖索引。"""
import html
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
repo = root.parents[2]
doc = (repo / 'docs/phantom-platform/sdk/integration.html').read_text()
section = doc.split('<section id="rust-core"', 1)[1].split('<section id="android-sdk"', 1)[0]
methods = []
for match in re.finditer(r'<tr><td><code>((?:async )?fn .+?)</code>', section):
    signature = html.unescape(match.group(1))
    name = re.search(r'fn (\w+)\(', signature).group(1)
    anchor = list(re.finditer(r'<(?:section|h[2-6])[^>]*id="([^"]+)"', section[:match.start()]))
    methods.append((signature, name, anchor[-1].group(1) if anchor else 'rust-core'))
assert len(methods) == 103, len(methods)
sources = {}
for path in (root / 'crates/phantom-sdk/src').glob('*.rs'):
    for line, value in enumerate(path.read_text().splitlines(), 1):
        match = re.search(r'pub (?:async )?fn (\w+)\(', value)
        if match:
            if match.group(1) != 'new' or path.name == 'client.rs':
                sources[match.group(1)] = (path.relative_to(root), line)
sources['single_conv_id'] = (Path('crates/phantom-domain/src/validation.rs'), 1)
for path in (root / 'crates/phantom-domain/src').glob('*.rs'):
    for line, value in enumerate(path.read_text().splitlines(), 1):
        if 'pub fn single_conv_id(' in value:
            sources['single_conv_id'] = (path.relative_to(root), line)
cli = (root / 'crates/phantom-sdk-cli/src/methods.rs').read_text()
route_source = (repo / 'phantom-platform/server/phantom-common/src/main/java/im/phantom/common/http/ApiRoutes.java').read_text()
routes = dict(re.findall(r'String (\w+) = "([^"]+)"', route_source))
cases = (root / 'crates/phantom-protocol/tests/http_domains.rs').read_text()
http = {}
# case 列表有意保持独立于 Facade 的 Controller route/method 登记。
for match in re.finditer(r'\(ApiRequest::(\w+).*?,HttpMethod::(\w+),"(\w+)"\)', cases):
    variant, method, route = match.groups()
    name = re.sub(r'(?<!^)([A-Z])', r'_\1', variant).lower()
    http[name] = f'{method.upper()} {routes[route]}'
assert len(http) == 74, len(http)
def tests(name):
    if name in http:
        special = {'history':'storage_matrix.rs、lifecycle.rs', 'update_conversation':'conversations.rs', 'mark_read':'conversations.rs', 'save_draft':'conversations.rs', 'clear_draft':'conversations.rs'}
        if name in ['init_upload','upload_part','complete_upload','get_media']:
            evidence = 'media.rs'
        else:
            evidence = special.get(name, 'domains.rs')
        return f'protocol/tests/http_domains.rs；sdk/tests/{evidence}'
    if name in ['upload_media','download_media','send_media','get_media_content']:
        return 'sdk/tests/media.rs'
    if name in ['send_text','send_message','resend','recall','get_messages','single_conv_id']:
        return 'sdk/tests/sending.rs、lifecycle.rs；domain/tests/contracts.rs'
    if name == 'mark_message_displayed':
        return 'sdk/tests/burn_clock.rs、recovery.rs'
    if name in ['send_typing','subscribe_presence','unsubscribe_presence','set_app_state','handle_notification_click']:
        return 'sdk/tests/lifecycle_clock.rs'
    if name == 'get_conversation_state':
        return 'sdk/tests/conversations.rs'
    return 'sdk/tests/lifecycle.rs、lifecycle_clock.rs、control.rs、storage_matrix.rs'
lines = ['# Rust API 覆盖清单', '', '基线为 [Rust 接口文档](../../../docs/phantom-platform/sdk/integration.html#rust-core) 的 103 项。表中源码路径和签名来自实际实现；HTTP 路由来自 Java ApiRoutes 与独立 Controller 契约测试。CLI 103 项分派可编译，`schema METHOD` 使用相同参数/结果类型。测试路径中的 protocol/sdk/domain 分别指 crates/phantom-protocol、crates/phantom-sdk、crates/phantom-domain。', '', '所有表内方法已有实现；此表登记源码和受控测试证据，不代表真实平台的所有权限/功能分支已经验收。真实 smoke 与领域验收仍见 [验证记录](verification.md)。', '', '| 序号 | 文档锚点 | 公开签名 | 实现 | 路由/命令 | CLI | 测试证据 |', '| --- | --- | --- | --- | --- | --- | --- |']
for index, (signature, name, anchor) in enumerate(methods, 1):
    assert name in sources, name
    assert f'"{name}"' in cli, name
    path, line = sources[name]
    command = http.get(name)
    if not command:
        command = 'WS SEND_MSG_REQ' if name in ['send_message','send_text','resend','send_media'] else 'WS RECALL_MSG_REQ' if name == 'recall' else 'WS/本地编排' if name in ['login','reconnect','update_token','logout','shutdown','wait_ready','send_typing','subscribe_presence','unsubscribe_presence','set_app_state'] else '本地/媒体编排'
    lines.append(f'| {index} | `{anchor}` | `{signature}` | [{path.name}:{line}]({path}#L{line}) | `{command}` | `call {name}` / `schema {name}` | {tests(name)} |')
lines += ['', '## 句柄与配置扩展', '', '| 公开入口 | 实现与契约 | 验证/宿主示例 |', '| --- | --- | --- |', '| `PhantomClient::subscribe`、`EventSubscription::next/close` | [events.rs](crates/phantom-core/src/events.rs)；独立队列、单消费者、关闭/溢出与会话隔离 | sdk/tests/control.rs |', '| `CancellationToken::new/cancel/is_cancelled`、Clone | [call.rs](crates/phantom-core/src/call.rs)；整体 deadline 和提交阶段 | sdk/tests/control.rs、media.rs、sending.rs；CLI scenario/cancel |', '| `ClientOptions::new`、`storage: Option<StorageOptions>` | [options.rs](crates/phantom-sdk/src/options.rs)；None 默认 SQLite，Memory/Directory/Custom | sdk/tests/storage_matrix.rs；[基础示例](crates/phantom-sdk/examples/basic.rs) |', '| `StoreFactory`、`Store` | [storage.rs](crates/phantom-ports/src/storage.rs)；capabilities/open、namespace/load/query/get/apply/flush/clear/close | [独立宿主实现](crates/phantom-store/examples/support/host.rs)；[trait 接入示例](crates/phantom-sdk/examples/custom_storage.rs) |', '| `StorageCallbacks`、`CallbackStoreFactory`、句柄/回调上下文 | [callback.rs](crates/phantom-store/src/callback.rs)；全部必需回调、版本和资源所有权 | store/tests/callbacks.rs；[回调接入示例](crates/phantom-sdk/examples/callback_storage.rs) |', '| Memory / SQLite 存储 | [memory.rs](crates/phantom-store/src/memory.rs)、[sqlite.rs](crates/phantom-store/src/sqlite.rs)；相同事务契约 | store/tests/storage.rs、migration.rs；sdk/tests/storage_matrix.rs、recovery.rs |', '', '## DTO、错误、事件与存储契约类型索引', '', '下表枚举 domain、ports、公开存储适配、Facade 选项和事件/取消句柄的公开类型；字段与全部枚举分支以源码链接为准。`json-schema` feature 同时为领域类型提供 CLI schema，二进制仍通过共享 proto 生成。自定义后端声明的持久性及版本由宿主负责，独立宿主示例为 Volatile。', '', '| 类型 | 定义 |', '| --- | --- |']
paths = list((root/'crates/phantom-domain/src').glob('*.rs')) + list((root/'crates/phantom-ports/src').glob('*.rs')) + [root/f'crates/{name}/src/{file}' for name,file in [('phantom-sdk','options.rs'),('phantom-sdk','client.rs'),('phantom-core','events.rs'),('phantom-core','call.rs'),('phantom-core','kernel.rs'),('phantom-store','callback.rs'),('phantom-store','memory.rs'),('phantom-store','sqlite.rs')]]
seen=set()
for path in sorted(paths):
    for line, value in enumerate(path.read_text().splitlines(),1):
        match = re.match(r'pub (?:struct|enum|trait|type) (\w+)', value)
        if match and match.group(1) not in seen:
            name=match.group(1)
            if name in {'CallbackStore','MemoryStore','SqliteStore'}:
                continue
            if 'phantom-core' in str(path) and name not in {'CallOptions','CancellationToken','EventSubscription','HostServices'}:
                continue
            seen.add(name);rel=path.relative_to(root)
            lines.append(f'| `{name}` | [{rel}:{line}]({rel}#L{line}) |')
lines += ['', '事件包括全部 12 类 SyncPayload、未知分支原始字节、生命周期、发送/移除、进度、Presence/Typing 与通知点击；二进制 fixture 位于 protocol/tests/wire_contracts.rs，投影和原子性验证位于 sdk/tests/sync_engine.rs。存储契约记录涵盖命名空间、持久性、revision、Inbox/Outbox、投影、消息与会话 tombstone、草稿和展示截止时间。', '', '检查命令：`python3 tools/generate_coverage.py`、`python3 tools/generate_cli.py`，然后执行 workspace 检查和 CLI schema/dry-run。源码没有范围内的 Unsupported 占位；ErrorKind::Unsupported 作为公开错误分类保留。', '']
(root/'api-coverage.md').write_text('\n'.join(lines))
print(f'已登记 {len(methods)} 项公开方法、{len(http)} 项 HTTP 路由、{len(seen)} 项公开类型')
