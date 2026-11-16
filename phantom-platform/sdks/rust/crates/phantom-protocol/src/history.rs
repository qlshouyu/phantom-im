use crate::{conversation_type, message::protocol, message_kind, message_state};
use phantom_domain as d;
use serde::{Deserialize, Deserializer};
use serde_json::Value;

fn signed(value: &Value) -> d::SdkResult<i64> {
    value
        .as_i64()
        .or_else(|| value.as_str().and_then(|s| s.parse().ok()))
        .ok_or_else(|| protocol("JSON int64 必须是整数或十进制整数字符串"))
}
fn unsigned(value: &Value) -> d::SdkResult<u64> {
    value
        .as_u64()
        .or_else(|| value.as_str().and_then(|s| s.parse().ok()))
        .ok_or_else(|| protocol("JSON uint64 必须是非负整数或十进制整数字符串"))
}
fn de_i64<'de, D: Deserializer<'de>>(de: D) -> Result<i64, D::Error> {
    signed(&Value::deserialize(de)?).map_err(|_| serde::de::Error::custom("int64 格式无效"))
}
fn text(v: &Value, name: &str) -> d::SdkResult<String> {
    match v.get(name) {
        None => Ok(String::new()),
        Some(Value::String(s)) => Ok(s.clone()),
        _ => Err(protocol("Protobuf JSON 字符串类型无效")),
    }
}
fn optional(v: &Value, name: &str) -> d::SdkResult<Option<String>> {
    Ok(Some(text(v, name)?).filter(|s| !s.is_empty()))
}
fn integer(v: &Value, name: &str) -> d::SdkResult<i64> {
    v.get(name)
        .map(signed)
        .transpose()
        .map(|v| v.unwrap_or_default())
}
fn uint(v: &Value, name: &str) -> d::SdkResult<u64> {
    v.get(name)
        .map(unsigned)
        .transpose()
        .map(|v| v.unwrap_or_default())
}
fn uint32(v: &Value, name: &str) -> d::SdkResult<u32> {
    u32::try_from(uint(v, name)?).map_err(|_| protocol("Protobuf JSON uint32 超出范围"))
}
fn flag(v: &Value, name: &str) -> d::SdkResult<bool> {
    match v.get(name) {
        None => Ok(false),
        Some(Value::Bool(b)) => Ok(*b),
        _ => Err(protocol("Protobuf JSON bool 类型无效")),
    }
}
fn strings(v: &Value, name: &str) -> d::SdkResult<Option<Vec<String>>> {
    match v.get(name) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Array(items)) => items
            .iter()
            .map(|v| {
                v.as_str()
                    .map(str::to_owned)
                    .ok_or_else(|| protocol("字符串数组类型无效"))
            })
            .collect::<d::SdkResult<Vec<_>>>()
            .map(Some),
        _ => Err(protocol("字符串数组类型无效")),
    }
}
fn enum_number(value: &Value, prefix: &str, names: &[&str]) -> d::SdkResult<i32> {
    if let Some(n) = value.as_i64() {
        return i32::try_from(n).map_err(|_| protocol("枚举数值超出 int32 范围"));
    }
    if let Some(name) = value.as_str() {
        if let Ok(n) = name.parse::<i32>() {
            return Ok(n);
        }
        if let Some(index) = names
            .iter()
            .position(|known| name == *known || name.strip_prefix(prefix) == Some(*known))
        {
            return Ok(index as i32);
        }
    }
    Err(protocol("无法取得未知枚举名称对应的数值"))
}
fn conv(value: &Value) -> d::SdkResult<d::ConversationType> {
    enum_number(
        value,
        "CONV_TYPE_",
        &["UNSPECIFIED", "SINGLE", "GROUP", "CHANNEL"],
    )
    .map(conversation_type)
}
fn kind(value: &Value) -> d::SdkResult<d::MessageKind> {
    enum_number(
        value,
        "MSG_TYPE_",
        &[
            "UNSPECIFIED",
            "TEXT",
            "EMOJI",
            "IMAGE",
            "VOICE",
            "VIDEO",
            "FILE",
            "LOCATION",
            "CARD",
            "MERGED",
            "SYSTEM",
            "CALL_RECORD",
        ],
    )
    .map(message_kind)
}
fn state(value: &Value) -> d::SdkResult<d::MessageState> {
    enum_number(
        value,
        "MSG_STATUS_",
        &[
            "UNSPECIFIED",
            "SENDING",
            "SENT",
            "DELIVERED",
            "READ",
            "FAILED",
        ],
    )
    .map(message_state)
}
fn content(
    value: &Value,
    kind: &d::MessageKind,
    original: &str,
) -> d::SdkResult<Option<d::MessageContent>> {
    use d::MessageContent as C;
    let object = value
        .as_object()
        .ok_or_else(|| protocol("content_json 必须包含 Protobuf JSON 对象"))?;
    if object.is_empty() {
        return Ok(None);
    }
    if matches!(kind, d::MessageKind::Unknown(_)) || object.len() != 1 {
        return Ok(Some(C::Unknown {
            kind: crate::kind_number(kind),
            raw_json: original.into(),
        }));
    }
    let (tag, body) = object.iter().next().expect("已检查非空");
    if !body.is_object() {
        return Err(protocol("Protobuf JSON 正文成员必须是对象"));
    }
    let decoded = match tag.as_str() {
        "text" => C::Text(d::TextContent {
            text: text(body, "text")?,
        }),
        "emoji" => C::Emoji(d::EmojiContent {
            emoji_id: text(body, "emojiId")?,
        }),
        "image" => C::Image(d::ImageContent {
            media_id: text(body, "mediaId")?,
            url: optional(body, "url")?,
            thumb_url: optional(body, "thumbUrl")?,
            width: uint32(body, "width")?,
            height: uint32(body, "height")?,
            size_bytes: uint(body, "sizeBytes")?,
        }),
        "voice" => C::Voice(d::VoiceContent {
            media_id: text(body, "mediaId")?,
            url: optional(body, "url")?,
            duration_s: uint32(body, "durationS")?,
        }),
        "video" => C::Video(d::VideoContent {
            media_id: text(body, "mediaId")?,
            url: optional(body, "url")?,
            cover_url: optional(body, "coverUrl")?,
            duration_s: uint32(body, "durationS")?,
            size_bytes: uint(body, "sizeBytes")?,
            width: uint32(body, "width")?,
            height: uint32(body, "height")?,
        }),
        "file" => C::File(d::FileContent {
            media_id: text(body, "mediaId")?,
            url: optional(body, "url")?,
            file_name: text(body, "fileName")?,
            size_bytes: uint(body, "sizeBytes")?,
            ext: optional(body, "ext")?,
        }),
        "location" => C::Location(d::LocationContent {
            latitude: body
                .get("latitude")
                .map(|v| {
                    v.as_f64()
                        .filter(|n| n.is_finite())
                        .ok_or_else(|| protocol("latitude 格式无效"))
                })
                .transpose()?
                .unwrap_or_default(),
            longitude: body
                .get("longitude")
                .map(|v| {
                    v.as_f64()
                        .filter(|n| n.is_finite())
                        .ok_or_else(|| protocol("longitude 格式无效"))
                })
                .transpose()?
                .unwrap_or_default(),
            poi_name: text(body, "poiName")?,
            address: text(body, "address")?,
        }),
        "card" => C::Card(d::CardContent {
            card_type: conv(body.get("cardType").unwrap_or(&Value::from(0)))?,
            target_id: text(body, "targetId")?,
            snapshot_name: text(body, "snapshotName")?,
            snapshot_avatar: optional(body, "snapshotAvatar")?,
        }),
        "merged" => C::Merged(d::MergedContent {
            title: text(body, "title")?,
            messages: match body.get("messages") {
                None => vec![],
                Some(Value::Array(messages)) => messages
                    .iter()
                    .map(proto_message)
                    .collect::<d::SdkResult<_>>()?,
                _ => return Err(protocol("合并消息数组格式无效")),
            },
        }),
        "system" => C::System(d::SystemContent {
            template_id: text(body, "templateId")?,
            params: match body.get("params") {
                None => d::StringMap::new(),
                Some(Value::Object(params)) => params
                    .iter()
                    .map(|(k, v)| {
                        Ok((
                            k.clone(),
                            v.as_str()
                                .ok_or_else(|| protocol("系统消息参数不是字符串"))?
                                .to_owned(),
                        ))
                    })
                    .collect::<d::SdkResult<_>>()?,
                _ => return Err(protocol("系统消息参数格式无效")),
            },
        }),
        _ => C::Unknown {
            kind: crate::kind_number(kind),
            raw_json: original.into(),
        },
    };
    Ok(Some(decoded))
}
fn proto_message(value: &Value) -> d::SdkResult<d::ChatMessage> {
    if !value.is_object() {
        return Err(protocol("合并消息快照必须是 Protobuf JSON 对象"));
    }
    let kind = kind(value.get("msgType").unwrap_or(&Value::from(0)))?;
    let body = value.get("content").unwrap_or(&Value::Null);
    let content = if body.is_null() {
        None
    } else {
        content(body, &kind, &body.to_string())?
    };
    let seq = integer(value, "convSeq")?;
    if seq < 0 {
        return Err(protocol("消息序号为负数"));
    }
    let mut message = d::ChatMessage {
        msg_id: optional(value, "msgId")?,
        client_msg_id: text(value, "clientMsgId")?,
        conv_id: text(value, "convId")?,
        conv_type: conv(value.get("convType").unwrap_or(&Value::from(0)))?,
        sender_id: text(value, "senderId")?,
        kind,
        text: match &content {
            Some(d::MessageContent::Text(t)) => t.text.clone(),
            _ => String::new(),
        },
        content,
        conv_seq: Some(seq),
        server_time: Some(integer(value, "serverTime")?),
        state: state(value.get("status").unwrap_or(&Value::from(0)))?,
        recalled: flag(value, "recalled")?,
        restricted: flag(value, "restricted")?,
        is_request: flag(value, "isRequest")?,
        reply_to_msg_id: optional(value, "replyToMsgId")?,
        reply_snapshot: value
            .get("replySnapshot")
            .filter(|v| !v.is_null())
            .map(|v| {
                Ok(d::ReplySnapshot {
                    sender_id: text(v, "senderId")?,
                    sender_name: text(v, "senderName")?,
                    msg_type: v.get("msgType").map(crate::history::kind).transpose()?,
                    digest: text(v, "digest")?,
                })
            })
            .transpose()?,
        mentions: strings(value, "mentions")?,
        burn_after_read_ttl_seconds: uint32(value, "burnAfterReadTtlSeconds")?,
        error: None,
    };
    d::clear_terminal_content(&mut message);
    Ok(message)
}

#[derive(Deserialize)]
struct MessageView {
    msg_id: String,
    client_msg_id: String,
    conv_id: String,
    conv_type: Value,
    sender_id: String,
    msg_type: Value,
    content_json: String,
    #[serde(deserialize_with = "de_i64")]
    conv_seq: i64,
    #[serde(deserialize_with = "de_i64")]
    server_time: i64,
    status: Value,
    recalled: bool,
    restricted: bool,
    is_request: bool,
    reply_to_msg_id: Option<String>,
    reply_sender_id: Option<String>,
    reply_sender_name: Option<String>,
    reply_digest: Option<String>,
    mentions: Option<Vec<String>>,
    burn_after_read_ttl_seconds: u32,
}
pub fn decode_history_message(value: Value) -> d::SdkResult<d::ChatMessage> {
    let value: MessageView =
        serde_json::from_value(value).map_err(|_| protocol("MessageViewDto 字段缺失或类型无效"))?;
    if value.conv_seq < 0 {
        return Err(protocol("历史消息 conv_seq 为负数"));
    }
    let kind = kind(&value.msg_type)?;
    let content = if value.recalled {
        None
    } else {
        let parsed = serde_json::from_str::<Value>(&value.content_json)
            .map_err(|_| protocol("content_json 不是合法 JSON"))?;
        content(&parsed, &kind, &value.content_json)?
    };
    let reply_snapshot = if [
        &value.reply_sender_id,
        &value.reply_sender_name,
        &value.reply_digest,
    ]
    .iter()
    .any(|v| v.as_ref().is_some_and(|s| !s.is_empty()))
    {
        Some(d::ReplySnapshot {
            sender_id: value.reply_sender_id.unwrap_or_default(),
            sender_name: value.reply_sender_name.unwrap_or_default(),
            msg_type: None,
            digest: value.reply_digest.unwrap_or_default(),
        })
    } else {
        None
    };
    let mut message = d::ChatMessage {
        msg_id: Some(value.msg_id).filter(|s| !s.is_empty()),
        client_msg_id: value.client_msg_id,
        conv_id: value.conv_id,
        conv_type: conv(&value.conv_type)?,
        sender_id: value.sender_id,
        kind,
        text: match &content {
            Some(d::MessageContent::Text(t)) => t.text.clone(),
            _ => String::new(),
        },
        content,
        conv_seq: Some(value.conv_seq),
        server_time: Some(value.server_time),
        state: state(&value.status)?,
        recalled: value.recalled,
        restricted: value.restricted,
        is_request: value.is_request,
        reply_to_msg_id: value.reply_to_msg_id.filter(|s| !s.is_empty()),
        reply_snapshot,
        mentions: value.mentions,
        burn_after_read_ttl_seconds: value.burn_after_read_ttl_seconds,
        error: None,
    };
    d::clear_terminal_content(&mut message);
    Ok(message)
}
#[derive(Deserialize)]
struct MessagePage {
    items: Vec<Value>,
    #[serde(deserialize_with = "de_i64")]
    next_cursor: i64,
    has_more: bool,
}
pub fn decode_history(value: Value) -> d::SdkResult<d::MessagePage> {
    let page: MessagePage =
        serde_json::from_value(value).map_err(|_| protocol("MessagePageDto 字段缺失或类型无效"))?;
    if page.next_cursor < 0 || (page.has_more && page.next_cursor == 0) {
        return Err(protocol("历史分页游标无效"));
    }
    let mut items = page
        .items
        .into_iter()
        .map(decode_history_message)
        .collect::<d::SdkResult<Vec<_>>>()?;
    items.sort_by_key(|m| m.conv_seq);
    Ok(d::MessagePage {
        items,
        next_cursor: if page.has_more {
            Some(page.next_cursor)
        } else {
            None
        },
        has_more: page.has_more,
    })
}
