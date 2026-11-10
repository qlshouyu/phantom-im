#!/usr/bin/env python3
"""生成客户端 SDK 分层架构的可编辑 draw.io 图（设计稿，非现状扫描）。"""
from pathlib import Path
from xml.etree import ElementTree as ET
import html

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/phantom-platform/architecture/diagrams'
PALETTE = {
    'api': ('#dae8fc', '#6c8ebf'),
    'bridge': ('#e1d5e7', '#9673a6'),
    'kernel': ('#d5e8d4', '#82b366'),
    'port': ('#fff2cc', '#d6b656'),
    'host': ('#ffe6cc', '#d79b00'),
    'external': ('#f5f5f5', '#666666'),
}


class Diagram:
    def __init__(self, name, width, height):
        self.file = ET.Element('mxfile', host='drawio', version='26.0.0')
        page = ET.SubElement(self.file, 'diagram', id=name, name=name)
        model = ET.SubElement(page, 'mxGraphModel', dx='1720', dy='1400', grid='1', gridSize='10',
                              guides='1', tooltips='1', connect='1', arrows='1', fold='1',
                              page='1', pageScale='1', pageWidth=str(width), pageHeight=str(height),
                              math='0', shadow='0', background='#ffffff')
        self.root = ET.SubElement(model, 'root')
        ET.SubElement(self.root, 'mxCell', id='0')
        ET.SubElement(self.root, 'mxCell', id='1', parent='0')

    def box(self, id, value, x, y, w, h, role='api', parent='1', extra=''):
        fill, stroke = PALETTE[role]
        style = ('rounded=1;arcSize=10;whiteSpace=wrap;html=1;'
                 f'fillColor={fill};strokeColor={stroke};strokeWidth=1.5;'
                 'fontFamily=Helvetica;fontSize=17;fontColor=#24334a;'
                 'spacing=12;align=center;verticalAlign=middle;')
        cell = ET.SubElement(self.root, 'mxCell', id=id, value=value, style=style + extra,
                             vertex='1', parent=parent)
        ET.SubElement(cell, 'mxGeometry', x=str(x), y=str(y), width=str(w), height=str(h), **{'as': 'geometry'})
        return id

    def text(self, id, value, x, y, w, h, size=16, bold=False):
        return self.box(id, value, x, y, w, h, 'external', extra=(
            'fillColor=none;strokeColor=none;align=left;spacing=0;'
            f'fontSize={size};fontStyle={1 if bold else 0};'))

    def layer(self, id, title, x, y, w, h, role):
        return self.box(id, title, x, y, w, h, role, extra=(
            'swimlane;rounded=0;startSize=34;collapsible=0;container=1;'
            'align=left;spacingLeft=16;spacingTop=0;fontSize=17;fontStyle=1;'
            'swimlaneFillColor=#ffffff;'))

    def edge(self, id, source, target, value='', dashed=False, exit_x=.5, entry_x=.5,
             exit_y=1, entry_y=0, points=()):
        style = ('edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;'
                 'strokeColor=#52657a;strokeWidth=1.7;endArrow=blockThin;endFill=1;'
                 'fontFamily=Helvetica;fontSize=14;fontColor=#40516a;labelBackgroundColor=#ffffff;'
                 f'exitX={exit_x};exitY={exit_y};exitDx=0;exitDy=0;'
                 f'entryX={entry_x};entryY={entry_y};entryDx=0;entryDy=0;')
        if dashed:
            style += 'dashed=1;dashPattern=6 4;'
        cell = ET.SubElement(self.root, 'mxCell', id=id, value=value, style=style,
                             edge='1', parent='1', source=source, target=target)
        geo = ET.SubElement(cell, 'mxGeometry', relative='1', **{'as': 'geometry'})
        if points:
            arr = ET.SubElement(geo, 'Array', **{'as': 'points'})
            for x, y in points:
                ET.SubElement(arr, 'mxPoint', x=str(x), y=str(y))

    def save(self, name):
        ET.indent(self.file, space='  ')
        ET.ElementTree(self.file).write(OUT / name, encoding='utf-8', xml_declaration=True)


def label(title, *lines):
    return '<b>' + html.escape(title) + '</b>' + ''.join('<br>' + html.escape(line) for line in lines)


def overall():
    d = Diagram('客户端 SDK 分层架构（目标设计）', 1720, 1390)
    d.text('overview.title', 'Phantom 客户端 SDK · 分层架构', 60, 25, 1600, 50, 32, True)
    d.text('overview.subtitle', '目标设计 · 2026-11-09  |  JS 独立实现；非 JS 客户端共享同一 Rust Kernel',
           60, 82, 1600, 32, 18)
    d.box('host.native', label('第三方原生与跨平台应用', '应用 UI / 业务账号 / UserToken 获取 / 页面状态'),
          60, 145, 1180, 75, 'external')
    d.box('host.browser', label('浏览器应用', 'React / Vue / Flutter Web'), 1300, 145, 360, 75, 'external')
    d.layer('layer.api', 'L1  平台 API', 60, 275, 1180, 140, 'api')
    wrappers = [
        ('android', 'Android SDK', 'Kotlin · suspend / Flow'),
        ('ios', 'iOS SDK', 'Swift · async / AsyncStream'),
        ('flutter', 'Flutter SDK', 'Dart · Future / Stream'),
        ('desktop', '桌面 SDK', 'Rust / Tauri；其他端可扩展'),
    ]
    for i, (id, title, sub) in enumerate(wrappers):
        d.box('wrapper.' + id, label(title, sub), 20 + i * 290, 52, 270, 65, parent='layer.api')
        d.edge('app.to.' + id, 'host.native', 'wrapper.' + id, exit_x=(155 + i * 290) / 1180)
    d.layer('layer.bindings', 'L2  桥接', 60, 465, 1180, 125, 'bridge')
    d.box('binding.uniffi', label('UniFFI', '生成 Kotlin / Swift 绑定'), 20, 51, 560, 54, 'bridge', 'layer.bindings')
    d.box('binding.dart', label('flutter_rust_bridge', 'Dart FFI 直接连接 Rust'), 600, 51, 270, 54, 'bridge', 'layer.bindings')
    d.box('binding.desktop', label('Rust API / 宿主桥接', 'Tauri IPC；C ABI 按需扩展'), 890, 51, 270, 54, 'bridge', 'layer.bindings')
    d.edge('android.binding', 'wrapper.android', 'binding.uniffi', entry_x=135 / 560)
    d.edge('ios.binding', 'wrapper.ios', 'binding.uniffi', entry_x=425 / 560)
    d.edge('flutter.binding', 'wrapper.flutter', 'binding.dart')
    d.edge('desktop.binding', 'wrapper.desktop', 'binding.desktop')
    d.layer('layer.facade', 'L3  Rust Facade', 60, 640, 1180, 110, 'kernel')
    d.box('rust.facade', label('统一领域 API · 客户端生命周期 · 类型化命令 / 结果 / 事件',
                             '一个进程内共享运行时；每个客户端实例拥有独立账号会话与状态'),
          20, 45, 1140, 50, 'kernel', 'layer.facade')
    for source, entry in [('binding.uniffi', .245), ('binding.dart', .64), ('binding.desktop', .89)]:
        d.edge(source + '.facade', source, 'rust.facade', entry_x=entry)
    d.layer('layer.kernel', 'L4  Rust Kernel', 60, 800, 1180, 140, 'kernel')
    d.box('rust.kernel', label('跨端只维护一份核心业务与状态机',
                             '连接 / 认证 / 重连 · 消息 / 幂等 / Outbox · ACK / 同步 / 游标 / Inbox',
                             '会话 / 已读 / 草稿 · 关系 / 群 / 资料 · 媒体 / 搜索 / 设备 / 合规'),
          20, 46, 1140, 77, 'kernel', 'layer.kernel')
    d.edge('facade.kernel', 'rust.facade', 'rust.kernel', '领域调用 / 事件')
    d.layer('layer.infrastructure', 'L5  基础设施与平台适配', 60, 990, 1180, 155, 'port')
    d.box('rust.infrastructure', label('Rust 默认基础设施',
                                     'WSS + Protobuf / HTTP + JSON · SQLite / 迁移 / 事务',
                                     '异步运行时 · 文件分片 / 哈希 · TLS · 日志与诊断'),
          20, 49, 850, 84, 'port', 'layer.infrastructure')
    d.box('host.adapters', label('平台 Host 适配', '安全存储 / 生命周期 / 推送', '目录 / 网络状态；由 L1 注入'),
          890, 49, 270, 84, 'host', 'layer.infrastructure', 'fontSize=15;')
    d.edge('kernel.infrastructure', 'rust.kernel', 'rust.infrastructure', '通过 Ports', exit_x=.373)
    d.edge('infrastructure.host', 'rust.infrastructure', 'host.adapters', dashed=True,
           exit_x=1, exit_y=.5, entry_x=0, entry_y=.5)
    d.box('sdk.js', label('JS / TypeScript SDK', '独立实现', '',
                         '领域 API / 消息模型', '连接 / 认证 / 重连', '消息状态 / 幂等', 'ACK / 同步 / 游标',
                         '浏览器缓存与生命周期', '', 'WebSocket / fetch', '浏览器平台能力', '',
                         '与 Rust 对齐：', '协议 / 模型 / 错误码', '行为契约 / 一致性用例', '', '不依赖 Rust / WASM'),
          1300, 275, 360, 870, 'api', extra='fontSize=19;')
    d.edge('browser.js', 'host.browser', 'sdk.js')
    d.box('phantom.platform', label('Phantom Platform', '实时网关 WSS / Protobuf  +  用户业务 HTTP / JSON'),
          60, 1240, 1600, 80, 'external')
    d.edge('rust.platform', 'rust.infrastructure', 'phantom.platform', 'WSS / HTTPS', entry_x=.278)
    d.edge('js.platform', 'sdk.js', 'phantom.platform', 'WSS / HTTPS', entry_x=.8875)
    d.text('overview.legend', '绿色：Rust 共享核心  ·  蓝色：平台 API / 独立 JS SDK  ·  紫色：桥接  ·  橙色：平台实现的 Host Ports',
           60, 1344, 1600, 27, 15)
    d.save('client-sdk-layered-architecture.drawio')


def kernel():
    d = Diagram('Rust Kernel 内部架构（目标设计）', 1720, 1440)
    d.text('kernel.title', 'Phantom Rust Kernel · 内部职责与依赖', 60, 25, 1600, 50, 32, True)
    d.text('kernel.subtitle', '目标设计 · 实线向下依赖抽象；虚线表示基础设施实现 Port；领域模型不依赖平台、网络或数据库',
           60, 82, 1600, 32, 18)
    d.box('facade.public', label('Rust SDK Facade / phantom-sdk',
                               '实例与会话生命周期 · 统一结果与事件 · 任务取消 · 配置与组合根'),
          60, 155, 1600, 80, 'kernel')
    d.layer('kernel.services', 'L4-A  领域服务 / 用例', 60, 310, 1600, 150, 'kernel')
    services = [
        ('messages', '消息与会话', '发送 / 重试 / 撤回 / 历史', '已读 / 未读 / 草稿'),
        ('relationships', '关系 / 群 / 资料', '好友 / 申请 / 分组 / 黑名单', '群成员 / 角色 / 公告'),
        ('media', '媒体服务', '分片 / 续传 / 摘要 / 取消', '受控读取 / 媒体消息'),
        ('extended', '设备 / 信令 / 扩展业务', '推送配置 / Presence / Typing', '搜索 / 举报 / 导出 / 合规'),
    ]
    for i, (id, title, first, second) in enumerate(services):
        d.box('service.' + id, label(title, first, second), 20 + i * 395, 51, 375, 78,
              'kernel', 'kernel.services', 'fontSize=16;')
    d.edge('facade.services', 'facade.public', 'kernel.services', '统一命令与事件')
    d.layer('kernel.engines', 'L4-B  核心引擎与状态机', 60, 560, 1200, 160, 'kernel')
    engines = [
        ('connection', '连接 / 会话 Actor', '认证 / Token 更新', '心跳 / 退避 / 连接代次'),
        ('message', '消息引擎', 'clientMsgId / Outbox', '发送状态 / 去重 / 排序'),
        ('sync', '同步引擎', 'Inbox / userSeq / ACK', '回放 / 缺口 / 全量重建'),
        ('state', '状态与查询', '会话 / 草稿 / 已读', '多端事件 / 查询投影'),
    ]
    for i, (id, title, first, second) in enumerate(engines):
        d.box('engine.' + id, label(title, first, second), 20 + i * 295, 51, 275, 88,
              'kernel', 'kernel.engines', 'fontSize=15;')
    d.edge('services.engines', 'kernel.services', 'kernel.engines', '协调状态机', exit_x=.375)
    d.box('domain.types', label('共享领域模型 / phantom-domain', '',
                              'Client / Account / Conversation', 'Message / Media / Group', 'Command / Result / Event',
                              'SdkError / Code / Cursor', '', '业务规则与状态合并', '所有层按需引用',
                              '不依赖 FFI / UI / 数据库'),
          1320, 560, 340, 390, 'bridge', extra='fontSize=17;')
    d.box('kernel.ports', label('Ports / phantom-ports',
                              'Transport · StoreTransaction · Clock / Scheduler · FileSource',
                              'SecureStore · Lifecycle · NetworkMonitor · PushTokenProvider · Diagnostics'),
          60, 835, 1200, 115, 'port')
    d.edge('engines.ports', 'kernel.engines', 'kernel.ports', '只依赖接口')
    d.layer('kernel.adapters', 'L5  适配实现', 60, 1100, 1600, 170, 'port')
    adapters = [
        ('transport', 'Rust Transport', 'WebSocket / HTTP / TLS', 'Protobuf / JSON 编解码', 'port'),
        ('store', 'Rust Store', 'SQLite / 迁移 / 账号隔离', '事件 + 游标事务 / Outbox', 'port'),
        ('runtime', 'Rust Runtime / IO', 'Tokio / 任务调度 / 文件', '分片 / 哈希 / 时钟', 'port'),
        ('host', '平台 Host 实现', 'Kotlin / Swift / Dart / Rust', '安全存储 / 生命周期 / 推送', 'host'),
    ]
    for i, (id, title, first, second, role) in enumerate(adapters):
        d.box('adapter.' + id, label(title, first, second), 20 + i * 395, 51, 375, 95,
              role, 'kernel.adapters', 'fontSize=16;')
        # 前三个实现垂直连接；Host 从领域模型下方绕行，再向上实现 Port。
        entry_x = (207.5 + i * 395) / 1200 if i < 3 else .95
        points = () if i < 3 else ((1452.5, 1030), (1200, 1030))
        d.edge('adapter.' + id + '.ports', 'adapter.' + id, 'kernel.ports', dashed=True,
               exit_y=0, entry_y=1, entry_x=entry_x, points=points)
    d.text('kernel.composition', '组合根：phantom-sdk 选择默认实现并注入 Ports；FFI 只转换模型与调度事件，不重复实现领域逻辑。',
           60, 1320, 1600, 32, 17)
    d.text('kernel.transactions', '一致性边界：先事务提交 Inbox / 领域状态 / userSeq，再发送 ACK；本地 Outbox 复用稳定 clientMsgId。',
           60, 1364, 1600, 32, 17)
    d.save('client-sdk-rust-kernel.drawio')


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    overall()
    kernel()
    print('已生成：client-sdk-layered-architecture.drawio / client-sdk-rust-kernel.drawio')
