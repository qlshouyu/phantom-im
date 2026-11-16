use phantom_domain::*;
use phantom_protocol::*;
use serde_json::{Value, json};

fn message() -> Value {
    json!({"msg_id":"m","client_msg_id":"c","conv_id":"gr_g","conv_type":"CONV_TYPE_GROUP","sender_id":"user","msg_type":"MSG_TYPE_TEXT","content_json":r#"{"text":{"text":"你好"}}"#,"conv_seq":"9007199254740993","server_time":"1710000000000","status":"MSG_STATUS_READ","recalled":false,"restricted":false,"is_request":false,"reply_to_msg_id":"original","reply_sender_id":"peer","reply_sender_name":"名字","reply_digest":"摘要","burn_after_read_ttl_seconds":60})
}
#[test]
fn actual_message_view_maps_proto_json_names_and_does_not_invent_missing_fields() {
    let result = decode_history_message(message()).unwrap();
    assert_eq!(result.conv_type, ConversationType::Group);
    assert_eq!(result.kind, MessageKind::Text);
    assert_eq!(result.state, MessageState::Read);
    assert_eq!(result.conv_seq, Some(9_007_199_254_740_993));
    assert_eq!(result.text, "你好");
    assert_eq!(result.mentions, None);
    assert_eq!(result.reply_snapshot.unwrap().msg_type, None);
    let mut value = message();
    value["mentions"] = json!([]);
    assert_eq!(
        decode_history_message(value).unwrap().mentions,
        Some(vec![])
    );
}
#[test]
fn true_pagination_and_ascending_order_are_preserved() {
    let newer = message();
    let mut older = message();
    older["conv_seq"] = json!(1);
    let page =
        decode_history(json!({"items":[newer,older],"next_cursor":"1","has_more":true})).unwrap();
    assert_eq!(page.next_cursor, Some(1));
    assert_eq!(page.items[0].conv_seq, Some(1));
    assert!(page.has_more);
    let end = decode_history(json!({"items":[],"next_cursor":0,"has_more":false})).unwrap();
    assert_eq!(end.next_cursor, None);
    assert!(decode_history(json!({"items":[],"next_cursor":0,"has_more":true})).is_err());
}
#[test]
fn content_uint64_strings_and_unknown_json_are_lossless() {
    let mut value = message();
    value["msg_type"] = json!("MSG_TYPE_FILE");
    value["content_json"] = json!(
        r#"{"file":{"mediaId":"media","fileName":"文件","sizeBytes":"18446744073709551615"}}"#
    );
    match decode_history_message(value).unwrap().content.unwrap() {
        MessageContent::File(v) => assert_eq!(v.size_bytes, u64::MAX),
        _ => panic!("文件类型错误"),
    }
    let original = "{ \"future\": {\"sequence\":\"9007199254740993\"} }";
    let mut value = message();
    value["msg_type"] = json!(99);
    value["content_json"] = json!(original);
    match decode_history_message(value).unwrap().content.unwrap() {
        MessageContent::Unknown { kind, raw_json } => {
            assert_eq!(kind, 99);
            assert_eq!(raw_json, original);
        }
        _ => panic!("未知 JSON 丢失"),
    }
}
#[test]
fn merged_proto_json_recursively_preserves_fields_without_reply_type_inference() {
    let mut value = message();
    value["msg_type"] = json!("MSG_TYPE_MERGED");
    value["content_json"]=json!(json!({"merged":{"title":"合并","messages":[{"msgId":"nested","clientMsgId":"nested-client","convId":"gr_g","convType":"CONV_TYPE_GROUP","senderId":"peer","msgType":"MSG_TYPE_TEXT","content":{"text":{"text":"嵌套"}},"convSeq":"9007199254740995","serverTime":"1000","replySnapshot":{"senderId":"user","digest":"引用"},"status":"MSG_STATUS_DELIVERED"}]}}).to_string());
    match decode_history_message(value).unwrap().content.unwrap() {
        MessageContent::Merged(v) => {
            assert_eq!(v.messages[0].text, "嵌套");
            assert_eq!(v.messages[0].conv_seq, Some(9_007_199_254_740_995));
            assert_eq!(
                v.messages[0].reply_snapshot.as_ref().unwrap().msg_type,
                None
            );
        }
        _ => panic!("合并类型错误"),
    }
}
#[test]
fn recalled_or_unavailable_content_is_not_restored_or_faked() {
    let mut value = message();
    value["recalled"] = json!(true);
    let recalled = decode_history_message(value).unwrap();
    assert_eq!(recalled.state, MessageState::Recalled);
    assert!(recalled.content.is_none());
    assert!(recalled.text.is_empty());
    assert!(recalled.reply_snapshot.is_none());
    let mut value = message();
    value["content_json"] = json!("{}");
    assert!(decode_history_message(value).unwrap().content.is_none());
}
#[test]
fn malformed_numbers_fields_and_content_are_protocol_errors() {
    for invalid in [
        json!(-1),
        json!(1.5),
        json!("1e3"),
        json!("9223372036854775808"),
    ] {
        let mut value = message();
        value["conv_seq"] = invalid;
        assert_eq!(
            decode_history_message(value).unwrap_err().kind,
            ErrorKind::Protocol
        );
    }
    let mut value = message();
    value["content_json"] = json!("not-json");
    assert!(decode_history_message(value).is_err());
    let mut value = message();
    value.as_object_mut().unwrap().remove("conv_type");
    assert!(decode_history_message(value).is_err());
}
