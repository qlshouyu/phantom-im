use crate::{
    message::{decode_message, message_state, protocol},
    raw, wire,
};
use phantom_domain as d;
use prost::Message;

#[derive(Clone, Debug)]
pub struct DecodedSyncEvent {
    pub event: d::SyncEvent,
    pub raw_bytes: Vec<u8>,
}
#[derive(Clone, Debug)]
pub struct SyncPage {
    pub events: Vec<DecodedSyncEvent>,
    pub max_user_seq: d::Seq,
    pub has_more: bool,
    pub full_resync: bool,
}

pub fn decode_sync_event(encoded: &[u8]) -> d::SdkResult<DecodedSyncEvent> {
    let fields = raw::fields(encoded)?;
    let members: Vec<_> = fields.iter().filter(|f| f.number >= 10).collect();
    let last = members
        .last()
        .ok_or_else(|| protocol("同步事件缺少 payload"))?;
    if last.wire_type != 2 {
        return Err(protocol("同步事件 payload 不是 message"));
    }
    let start = members
        .iter()
        .rposition(|f| f.number != last.number)
        .map_or(0, |i| i + 1);
    let mut selected: Vec<u8> = fields
        .iter()
        .filter(|f| f.number == 1 || f.number == 2)
        .flat_map(|f| f.encoded.iter().copied())
        .collect();
    let raw_member: Vec<u8> = members[start..]
        .iter()
        .flat_map(|f| f.encoded.iter().copied())
        .collect();
    selected.extend_from_slice(&raw_member);
    let value = wire::SyncEvent::decode(selected.as_slice())
        .map_err(|_| protocol("同步事件 Protobuf 损坏"))?;
    if value.user_seq < 0 {
        return Err(protocol("同步事件 user_seq 为负数"));
    }
    let body = raw::message_bytes(&raw_member, last.number)?;
    use d::SyncPayload as D;
    use wire::sync_event::Event as W;
    let payload = if !(10..=21).contains(&last.number) {
        D::Unknown {
            field_number: last.number,
            raw_bytes: raw_member,
        }
    } else {
        match value
            .event
            .ok_or_else(|| protocol("同步事件 payload 缺失"))?
        {
            W::NewMessage(v) => D::NewMessage(d::NewMessageEvent {
                msg: if v.msg.is_some() {
                    Some(decode_message(&raw::message_bytes(&body, 1)?)?)
                } else {
                    None
                },
                conv_id: v.conv_id,
                conv_seq: v.conv_seq,
                msg_id: v.msg_id,
                is_request: v.is_request,
            }),
            W::Recall(v) => D::Recall(d::RecallEvent {
                msg_id: v.msg_id,
                conv_id: v.conv_id,
                operator_id: v.operator_id,
            }),
            W::ReadPosition(v) => D::ReadPosition(d::ReadPositionEvent {
                conv_id: v.conv_id,
                read_conv_seq: v.read_conv_seq,
            }),
            W::ReadReceipt(v) => D::ReadReceipt(d::ReadReceiptEvent {
                conv_id: v.conv_id,
                read_conv_seq: v.read_conv_seq,
                reader_id: v.reader_id,
                read_count: v.read_count,
            }),
            W::ConvState(v) => D::ConversationState(d::ConversationStateEvent {
                conv_id: v.conv_id,
                field: match v.field {
                    1 => d::ConversationStateField::Pinned,
                    2 => d::ConversationStateField::Muted,
                    3 => d::ConversationStateField::Deleted,
                    4 => d::ConversationStateField::MarkedRead,
                    5 => d::ConversationStateField::ReadOnly,
                    n => d::ConversationStateField::Unknown(n),
                },
                bool_value: v.bool_value,
                op_time: v.op_time,
            }),
            W::Draft(v) => D::Draft(d::ConversationDraft {
                conv_id: v.conv_id,
                draft: v.draft,
                edited_at: v.edited_at,
            }),
            W::Relation(v) => D::Relation(d::RelationEvent {
                kind: match v.r#type {
                    1 => d::RelationEventType::ApplyReceived,
                    2 => d::RelationEventType::ApplyAccepted,
                    3 => d::RelationEventType::ApplyRejected,
                    4 => d::RelationEventType::FriendDeleted,
                    5 => d::RelationEventType::BlacklistAdded,
                    6 => d::RelationEventType::BlacklistRemoved,
                    7 => d::RelationEventType::ApplyIgnored,
                    8 => d::RelationEventType::FriendSettingChanged,
                    9 => d::RelationEventType::GroupChanged,
                    10 => d::RelationEventType::ApplySent,
                    n => d::RelationEventType::Unknown(n),
                },
                peer_id: v.peer_id,
                peer: v.peer.map(|p| d::RelationUser {
                    user_id: p.user_id,
                    nickname: p.nickname,
                    avatar_url: if p.avatar_url.is_empty() {
                        None
                    } else {
                        Some(p.avatar_url)
                    },
                }),
                request_id: if v.request_id.is_empty() {
                    None
                } else {
                    Some(v.request_id)
                },
                verification_message: v.verification_message,
                created_at: v.created_at,
                group_ids: v.group_ids,
                pinned: v.pinned,
                remark: v.remark,
                group_id: if v.group_id.is_empty() {
                    None
                } else {
                    Some(v.group_id)
                },
                group_name: v.group_name,
                group_deleted: v.group_deleted,
            }),
            W::Group(v) => D::Group(d::GroupEvent {
                kind: match v.r#type {
                    1 => d::GroupEventType::MemberJoined,
                    2 => d::GroupEventType::MemberLeft,
                    3 => d::GroupEventType::MemberKicked,
                    4 => d::GroupEventType::RoleChanged,
                    5 => d::GroupEventType::MuteChanged,
                    6 => d::GroupEventType::Announcement,
                    7 => d::GroupEventType::ProfileChanged,
                    8 => d::GroupEventType::Dissolved,
                    9 => d::GroupEventType::NicknameChanged,
                    n => d::GroupEventType::Unknown(n),
                },
                group_id: v.group_id,
                target_user_ids: v.target_user_ids,
                operator_id: v.operator_id,
                params: v.params,
            }),
            W::Profile(v) => D::Profile(d::ProfileEvent {
                kind: match v.r#type {
                    1 => d::ProfileEventType::MyProfile,
                    2 => d::ProfileEventType::MySettings,
                    3 => d::ProfileEventType::FriendRemark,
                    n => d::ProfileEventType::Unknown(n),
                },
                target_id: v.target_id,
                changed: v.changed,
            }),
            W::RestrictedDelivery(v) => D::RestrictedDelivery(d::RestrictedDeliveryEvent {
                conv_id: v.conv_id,
                msg_id: v.msg_id,
                template_id: v.template_id,
            }),
            W::MsgStatus(v) => D::MessageStatus(d::MessageStatusEvent {
                msg_id: v.msg_id,
                conv_id: v.conv_id,
                status: message_state(v.status),
                status_time: v.status_time,
            }),
            W::Report(v) => D::Report(d::ReportEvent {
                report_id: v.report_id,
                target_type: v.target_type,
                target_id: v.target_id,
                status: v.status,
                template_id: v.template_id,
                updated_at: v.updated_at,
            }),
        }
    };
    Ok(DecodedSyncEvent {
        event: d::SyncEvent {
            user_seq: value.user_seq,
            event_time: value.event_time,
            payload,
        },
        raw_bytes: encoded.to_vec(),
    })
}

pub fn decode_sync_page(encoded: &[u8]) -> d::SdkResult<SyncPage> {
    let value = wire::SyncResp::decode(encoded).map_err(|_| protocol("同步分页 Protobuf 损坏"))?;
    if value.max_user_seq < 0 {
        return Err(protocol("同步目标 user_seq 为负数"));
    }
    let events = raw::fields(encoded)?
        .into_iter()
        .filter(|f| f.number == 1 && f.wire_type == 2)
        .map(|f| decode_sync_event(f.value))
        .collect::<d::SdkResult<_>>()?;
    Ok(SyncPage {
        events,
        max_user_seq: value.max_user_seq,
        has_more: value.has_more,
        full_resync: value.full_resync,
    })
}
