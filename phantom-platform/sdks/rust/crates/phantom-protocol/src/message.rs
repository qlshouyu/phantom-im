use crate::{raw, wire};
use phantom_domain as d;
use prost::Message;

pub(crate) fn protocol(message: impl Into<String>) -> d::SdkError {
    d::SdkError::new(d::ErrorKind::Protocol, message)
}

pub fn conversation_type(value: i32) -> d::ConversationType {
    match value {
        1 => d::ConversationType::Single,
        2 => d::ConversationType::Group,
        n => d::ConversationType::Unknown(n),
    }
}
pub fn message_kind(value: i32) -> d::MessageKind {
    use d::MessageKind::*;
    match value {
        1 => Text,
        2 => Emoji,
        3 => Image,
        4 => Voice,
        5 => Video,
        6 => File,
        7 => Location,
        8 => Card,
        9 => Merged,
        10 => System,
        n => Unknown(n),
    }
}
pub fn message_state(value: i32) -> d::MessageState {
    use d::MessageState::*;
    match value {
        1 => Sending,
        2 => Sent,
        3 => Delivered,
        4 => Read,
        5 => Failed,
        n => Unknown(n),
    }
}
fn optional(value: String) -> Option<String> {
    if value.is_empty() { None } else { Some(value) }
}

pub fn decode_message(encoded: &[u8]) -> d::SdkResult<d::ChatMessage> {
    let value = wire::Msg::decode(encoded).map_err(|_| protocol("消息 Protobuf 损坏"))?;
    if value.conv_seq < 0 {
        return Err(protocol("消息 conv_seq 为负数"));
    }
    let content_raw = raw::message_bytes(encoded, 7)?;
    let content = if value.content.is_some() {
        decode_content(&content_raw, value.msg_type)?
    } else {
        None
    };
    let mut result = d::ChatMessage {
        msg_id: optional(value.msg_id),
        client_msg_id: value.client_msg_id,
        conv_id: value.conv_id,
        conv_type: conversation_type(value.conv_type),
        sender_id: value.sender_id,
        kind: message_kind(value.msg_type),
        text: match &content {
            Some(d::MessageContent::Text(t)) => t.text.clone(),
            _ => String::new(),
        },
        content,
        conv_seq: Some(value.conv_seq),
        server_time: Some(value.server_time),
        state: message_state(value.status),
        recalled: value.recalled,
        restricted: value.restricted,
        is_request: value.is_request,
        reply_to_msg_id: optional(value.reply_to_msg_id),
        reply_snapshot: value.reply_snapshot.map(|r| d::ReplySnapshot {
            sender_id: r.sender_id,
            sender_name: r.sender_name,
            msg_type: if r.msg_type == 0 {
                None
            } else {
                Some(message_kind(r.msg_type))
            },
            digest: r.digest,
        }),
        mentions: if raw::fields(encoded)?.iter().any(|f| f.number == 12) {
            Some(value.mentions)
        } else {
            None
        },
        burn_after_read_ttl_seconds: value.burn_after_read_ttl_seconds,
        error: None,
    };
    d::clear_terminal_content(&mut result);
    Ok(result)
}

fn decode_content(encoded: &[u8], kind: i32) -> d::SdkResult<Option<d::MessageContent>> {
    use d::MessageContent as C;
    let fields = raw::fields(encoded)?;
    let Some(last) = fields.last() else {
        return Ok(None);
    };
    if last.wire_type != 2 {
        return Err(protocol("消息正文 wire 类型无效"));
    }
    if !(1..=10).contains(&last.number) || matches!(message_kind(kind), d::MessageKind::Unknown(_))
    {
        return Ok(Some(C::Unknown {
            kind,
            raw_json: serde_json::json!({"protobuf_hex":raw::hex(encoded)}).to_string(),
        }));
    }
    // oneof 的最后一组同名成员允许合并；其他成员（包括未知成员）构成边界。
    let start = fields
        .iter()
        .rposition(|f| f.number != last.number)
        .map_or(0, |i| i + 1);
    let chosen: Vec<u8> = fields[start..]
        .iter()
        .flat_map(|f| f.encoded.iter().copied())
        .collect();
    let body = raw::message_bytes(&chosen, last.number)?;
    let value = wire::MsgContent::decode(chosen.as_slice())
        .map_err(|_| protocol("消息正文 Protobuf 损坏"))?;
    let mapped = match value.content.ok_or_else(|| protocol("正文类型缺失"))? {
        wire::msg_content::Content::Text(v) => C::Text(d::TextContent { text: v.text }),
        wire::msg_content::Content::Emoji(v) => C::Emoji(d::EmojiContent {
            emoji_id: v.emoji_id,
        }),
        wire::msg_content::Content::Image(v) => C::Image(d::ImageContent {
            media_id: v.media_id,
            url: optional(v.url),
            thumb_url: optional(v.thumb_url),
            width: v.width,
            height: v.height,
            size_bytes: v.size_bytes,
        }),
        wire::msg_content::Content::Voice(v) => C::Voice(d::VoiceContent {
            media_id: v.media_id,
            url: optional(v.url),
            duration_s: v.duration_s,
        }),
        wire::msg_content::Content::Video(v) => C::Video(d::VideoContent {
            media_id: v.media_id,
            url: optional(v.url),
            cover_url: optional(v.cover_url),
            duration_s: v.duration_s,
            size_bytes: v.size_bytes,
            width: v.width,
            height: v.height,
        }),
        wire::msg_content::Content::File(v) => C::File(d::FileContent {
            media_id: v.media_id,
            url: optional(v.url),
            file_name: v.file_name,
            size_bytes: v.size_bytes,
            ext: optional(v.ext),
        }),
        wire::msg_content::Content::Location(v) => C::Location(d::LocationContent {
            latitude: v.latitude,
            longitude: v.longitude,
            poi_name: v.poi_name,
            address: v.address,
        }),
        wire::msg_content::Content::Card(v) => C::Card(d::CardContent {
            card_type: conversation_type(v.card_type),
            target_id: v.target_id,
            snapshot_name: v.snapshot_name,
            snapshot_avatar: optional(v.snapshot_avatar),
        }),
        wire::msg_content::Content::Merged(v) => C::Merged(d::MergedContent {
            title: v.title,
            messages: raw::fields(&body)?
                .into_iter()
                .filter(|f| f.number == 2 && f.wire_type == 2)
                .map(|f| decode_message(f.value))
                .collect::<d::SdkResult<_>>()?,
        }),
        wire::msg_content::Content::System(v) => C::System(d::SystemContent {
            template_id: v.template_id,
            params: v.params,
        }),
    };
    Ok(Some(mapped))
}

pub fn encode_content(content: &d::MessageContent) -> d::SdkResult<wire::MsgContent> {
    use d::MessageContent as D;
    use wire::msg_content::Content as W;
    content.validate_send()?;
    let value = match content {
        D::Text(v) => W::Text(wire::TextContent {
            text: v.text.clone(),
        }),
        D::Emoji(v) => W::Emoji(wire::EmojiContent {
            emoji_id: v.emoji_id.clone(),
        }),
        D::Image(v) => W::Image(wire::ImageContent {
            media_id: v.media_id.clone(),
            url: v.url.clone().unwrap_or_default(),
            thumb_url: v.thumb_url.clone().unwrap_or_default(),
            width: v.width,
            height: v.height,
            size_bytes: v.size_bytes,
        }),
        D::Voice(v) => W::Voice(wire::VoiceContent {
            media_id: v.media_id.clone(),
            url: v.url.clone().unwrap_or_default(),
            duration_s: v.duration_s,
        }),
        D::Video(v) => W::Video(wire::VideoContent {
            media_id: v.media_id.clone(),
            url: v.url.clone().unwrap_or_default(),
            cover_url: v.cover_url.clone().unwrap_or_default(),
            duration_s: v.duration_s,
            size_bytes: v.size_bytes,
            width: v.width,
            height: v.height,
        }),
        D::File(v) => W::File(wire::FileContent {
            media_id: v.media_id.clone(),
            url: v.url.clone().unwrap_or_default(),
            file_name: v.file_name.clone(),
            size_bytes: v.size_bytes,
            ext: v.ext.clone().unwrap_or_default(),
        }),
        D::Location(v) => W::Location(wire::LocationContent {
            latitude: v.latitude,
            longitude: v.longitude,
            poi_name: v.poi_name.clone(),
            address: v.address.clone(),
        }),
        D::Card(v) => W::Card(wire::CardContent {
            card_type: match v.card_type {
                d::ConversationType::Single => 1,
                d::ConversationType::Group => 2,
                d::ConversationType::Unknown(n) => n,
            },
            target_id: v.target_id.clone(),
            snapshot_name: v.snapshot_name.clone(),
            snapshot_avatar: v.snapshot_avatar.clone().unwrap_or_default(),
        }),
        D::Merged(v) => W::Merged(wire::MergedContent {
            title: v.title.clone(),
            messages: v
                .messages
                .iter()
                .map(encode_message)
                .collect::<d::SdkResult<_>>()?,
        }),
        D::System(v) => W::System(wire::SystemContent {
            template_id: v.template_id.clone(),
            params: v.params.clone(),
        }),
        D::Unknown { .. } => {
            return Err(d::SdkError::new(
                d::ErrorKind::InvalidArgument,
                "未知正文不能发送",
            ));
        }
    };
    Ok(wire::MsgContent {
        content: Some(value),
    })
}

pub fn encode_message(value: &d::ChatMessage) -> d::SdkResult<wire::Msg> {
    Ok(wire::Msg {
        msg_id: value.msg_id.clone().unwrap_or_default(),
        client_msg_id: value.client_msg_id.clone(),
        conv_id: value.conv_id.clone(),
        conv_type: match value.conv_type {
            d::ConversationType::Single => 1,
            d::ConversationType::Group => 2,
            d::ConversationType::Unknown(n) => n,
        },
        sender_id: value.sender_id.clone(),
        msg_type: match &value.content {
            Some(c) => kind_number(&c.kind()),
            None => kind_number(&value.kind),
        },
        content: value.content.as_ref().map(encode_content).transpose()?,
        conv_seq: value.conv_seq.unwrap_or_default(),
        server_time: value.server_time.unwrap_or_default(),
        reply_to_msg_id: value.reply_to_msg_id.clone().unwrap_or_default(),
        reply_snapshot: value.reply_snapshot.as_ref().map(|r| wire::ReplySnapshot {
            sender_id: r.sender_id.clone(),
            sender_name: r.sender_name.clone(),
            msg_type: r.msg_type.as_ref().map(kind_number).unwrap_or_default(),
            digest: r.digest.clone(),
        }),
        mentions: value.mentions.clone().unwrap_or_default(),
        recalled: value.recalled,
        status: match value.state {
            d::MessageState::Sending => 1,
            d::MessageState::Sent => 2,
            d::MessageState::Delivered => 3,
            d::MessageState::Read => 4,
            d::MessageState::Failed => 5,
            d::MessageState::Unknown(n) => n,
            _ => 0,
        },
        restricted: value.restricted,
        burn_after_read_ttl_seconds: value.burn_after_read_ttl_seconds,
        is_request: value.is_request,
    })
}
pub fn kind_number(value: &d::MessageKind) -> i32 {
    use d::MessageKind::*;
    match value {
        Text => 1,
        Emoji => 2,
        Image => 3,
        Voice => 4,
        Video => 5,
        File => 6,
        Location => 7,
        Card => 8,
        Merged => 9,
        System => 10,
        Unknown(n) => *n,
    }
}
