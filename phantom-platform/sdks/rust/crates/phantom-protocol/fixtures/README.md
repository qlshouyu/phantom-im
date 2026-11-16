# 协议二进制 fixture

`generate.py` 仅使用 Python 标准库、共享 proto 的字段号和手写数据生成 `.bin`，不调用 SDK/prost 编码器，避免只验证编码器与解码器互相一致。重新生成：

```bash
python3 crates/phantom-protocol/fixtures/generate.py
```

覆盖 Envelope/AuthReq/AuthResp、消息大序号、12 类 SyncEvent、已知/未知混合分页，以及未知内容的二进制字节。测试另覆盖 oneof 最后成员生效、同名成员合并及未知成员作为合并边界。

`DecodedSyncEvent.raw_bytes` 保留整个原始事件；`SyncPayload::Unknown.raw_bytes` 保留未知 oneof 字段的完整 wire 编码（字段 key、长度及内容），不重新编码载荷。

`MessageContent::Unknown.raw_json` 对 HTTP JSON 保留服务端原载荷；未知 Protobuf 正文没有可用 JSON 字段定义，使用 `{"protobuf_hex":"原始 MsgContent wire 字节的小写十六进制"}` 表示并保留原字节。该内容只用于接收，不能发送。未知枚举保留原始整数。
