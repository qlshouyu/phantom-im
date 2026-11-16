"""独立 wire fixture 生成器；只使用字段号和标准库，不导入 SDK/prost。"""
from pathlib import Path

ROOT = Path(__file__).parent

def varint(value):
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)

def integer(number, value):
    return varint(number << 3) + varint(value)

def data(number, value):
    value = value.encode() if isinstance(value, str) else value
    return varint(number << 3 | 2) + varint(len(value)) + value

def save(name, value):
    (ROOT / name).write_bytes(value)
    return value

auth = data(1, 'fixture-token') + data(2, 'device') + integer(3, 2) + data(4, 'macos') + data(6, 'app') + data(7, 'user')
save('auth-request.bin', auth)
save('envelope-auth.bin', integer(1, 1) + integer(2, 1) + integer(3, 42) + data(5, auth))
save('auth-response.bin', integer(1, 1710000000000) + integer(2, 9007199254740993) + integer(3, 30) + data(4, integer(1, 1) + integer(2, 60)) + data(5, integer(1, 1)) + data(6, integer(1, 1) + integer(2, 1) + integer(3, 3)))
message = data(1, 'm') + data(2, 'c') + data(3, 'gr_g') + integer(4, 2) + data(5, 'u') + integer(6, 1) + data(7, data(1, data(1, '你好'))) + integer(8, 9007199254740993) + integer(9, 1710000000000) + data(11, data(1, 'peer') + data(2, '名字') + data(4, '摘要')) + integer(14, 4)
save('message.bin', message)
payloads = [
    data(1, message) + data(2, 'gr_g') + integer(3, 9007199254740993) + data(4, 'm'),
    data(1, 'm') + data(2, 'gr_g') + data(3, 'u'),
    data(1, 'gr_g') + integer(2, 9007199254740993),
    data(1, 'gr_g') + integer(2, 9007199254740993) + data(3, 'reader') + integer(4, 2),
    data(1, 'gr_g') + integer(2, 1) + integer(3, 1) + integer(4, 100),
    data(1, 'gr_g') + data(2, '草稿') + integer(3, 100),
    integer(1, 10) + data(2, 'peer') + data(3, data(1, 'peer') + data(2, '名字')) + data(4, 'request') + data(5, '验证') + integer(6, 100),
    integer(1, 9) + data(2, 'g') + data(3, 'u') + data(4, 'owner') + data(5, data(1, 'nickname') + data(2, '名字')),
    integer(1, 3) + data(2, 'peer') + data(3, data(1, 'remark') + data(2, '备注')),
    data(1, 'gr_g') + data(2, 'm') + data(3, 'delivery.restricted'),
    data(1, 'm') + data(2, 'gr_g') + integer(3, 4) + integer(4, 100),
    data(1, 'report') + data(2, 'MESSAGE') + data(3, 'm') + data(4, 'RESOLVED') + data(5, 'report.resolved') + integer(6, 100),
]
events = [integer(1, i + 1) + integer(2, 1000 + i) + data(10 + i, body) for i, body in enumerate(payloads)]
for i, event in enumerate(events):
    save(f'sync-{10+i}.bin', event)
save('sync-page.bin', b''.join(data(1, event) for event in events) + integer(2, 12))
unknown = data(19001, b'\x00\xff\x08\x80\x01')
save('unknown-member.bin', unknown)
unknown_event = integer(1, 13) + integer(2, 1013) + unknown
save('mixed-page.bin', data(1, events[0]) + data(1, unknown_event) + data(1, events[1]) + integer(2, 13))
save('unknown-message.bin', data(1, 'm') + data(2, 'c') + data(3, 'gr_g') + integer(4, 99) + integer(6, 42) + data(7, data(22, b'\x00\xff\x08\x80\x01')) + integer(8, 1) + integer(14, 99))
