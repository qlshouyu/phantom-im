use phantom_domain as d;
use phantom_protocol::*;
use prost::Message;

#[test]
fn independent_envelope_and_auth_fixtures_match_shared_command_fields() {
    let frame = decode_frame(include_bytes!("../fixtures/envelope-auth.bin")).unwrap();
    assert_eq!(frame.command, 1);
    assert_eq!(frame.cid, 42);
    assert_eq!(frame.code, 0);
    assert_eq!(
        frame.payload,
        include_bytes!("../fixtures/auth-request.bin")
    );
    let req = wire::AuthReq::decode(frame.payload.as_slice()).unwrap();
    assert_eq!(req.app_id, "app");
    assert_eq!(req.user_id, "user");
    assert_eq!(req.device_type, 2);
    assert_eq!(
        encode_frame(wire::Command::AuthReq, 42, &req),
        frame.raw_bytes
    );
    let result = decode_auth(
        include_bytes!("../fixtures/auth-response.bin"),
        d::SessionInfo {
            session_id: "s".into(),
            user_id: "user".into(),
            device_id: "device".into(),
        },
    )
    .unwrap();
    assert_eq!(result.max_user_seq, 9_007_199_254_740_993);
    assert_eq!(result.heartbeat_interval_s, 30);
    assert!(result.app_config.read_receipt_enabled);
    assert_eq!(result.app_config.burn_after_read.ttl_seconds, 60);
    assert_eq!(result.app_config.max_devices, 3);
    assert!(decode_auth(&[], result.session).is_err());
}

#[test]
fn message_fixture_retains_large_sequences_and_unknown_field_absence() {
    let msg = decode_message(include_bytes!("../fixtures/message.bin")).unwrap();
    assert_eq!(msg.conv_type, d::ConversationType::Group);
    assert_eq!(msg.conv_seq, Some(9_007_199_254_740_993));
    assert_eq!(msg.text, "你好");
    assert_eq!(msg.state, d::MessageState::Read);
    assert_eq!(msg.mentions, None);
    assert_eq!(msg.reply_snapshot.unwrap().msg_type, None);
}

#[test]
fn all_twelve_independent_sync_fixtures_map_the_actual_oneof_tags() {
    let page = decode_sync_page(include_bytes!("../fixtures/sync-page.bin")).unwrap();
    assert_eq!(page.events.len(), 12);
    assert_eq!(page.max_user_seq, 12);
    assert!(!page.has_more);
    assert!(!page.full_resync);
    let tags: Vec<_> = page
        .events
        .iter()
        .map(|event| {
            use d::SyncPayload::*;
            match &event.event.payload {
                NewMessage(v) => {
                    assert_eq!(v.msg.as_ref().unwrap().text, "你好");
                    10
                }
                Recall(v) => {
                    assert_eq!(v.operator_id, "u");
                    11
                }
                ReadPosition(v) => {
                    assert_eq!(v.read_conv_seq, 9_007_199_254_740_993);
                    12
                }
                ReadReceipt(v) => {
                    assert_eq!(v.reader_id, "reader");
                    13
                }
                ConversationState(v) => {
                    assert_eq!(v.field, d::ConversationStateField::Pinned);
                    14
                }
                Draft(v) => {
                    assert_eq!(v.draft, "草稿");
                    15
                }
                Relation(v) => {
                    assert_eq!(v.kind, d::RelationEventType::ApplySent);
                    assert_eq!(v.request_id.as_deref(), Some("request"));
                    16
                }
                Group(v) => {
                    assert_eq!(v.kind, d::GroupEventType::NicknameChanged);
                    assert_eq!(v.params["nickname"], "名字");
                    17
                }
                Profile(v) => {
                    assert_eq!(v.kind, d::ProfileEventType::FriendRemark);
                    18
                }
                RestrictedDelivery(v) => {
                    assert_eq!(v.template_id, "delivery.restricted");
                    19
                }
                MessageStatus(v) => {
                    assert_eq!(v.status, d::MessageState::Read);
                    20
                }
                Report(v) => {
                    assert_eq!(v.status, "RESOLVED");
                    21
                }
                Unknown { .. } => panic!("已知 fixture 被误识别为未知"),
            }
        })
        .collect();
    assert_eq!(tags, (10..=21).collect::<Vec<_>>());
    for (index, event) in page.events.iter().enumerate() {
        assert_eq!(event.event.user_seq, index as i64 + 1);
    }
}

#[test]
fn unknown_event_between_known_events_keeps_the_exact_encoded_field() {
    let page = decode_sync_page(include_bytes!("../fixtures/mixed-page.bin")).unwrap();
    assert!(matches!(
        page.events[0].event.payload,
        d::SyncPayload::NewMessage(_)
    ));
    match &page.events[1].event.payload {
        d::SyncPayload::Unknown {
            field_number,
            raw_bytes,
        } => {
            assert_eq!(*field_number, 19001);
            assert_eq!(raw_bytes, include_bytes!("../fixtures/unknown-member.bin"));
        }
        _ => panic!("未知事件丢失"),
    }
    assert!(matches!(
        page.events[2].event.payload,
        d::SyncPayload::Recall(_)
    ));
}

#[test]
fn unknown_oneof_takes_precedence_and_resets_known_member_merging() {
    let metadata = [8, 1];
    let known_a = [0x5a, 3, 0x0a, 1, b'a'];
    let known_b = [0x5a, 3, 0x12, 1, b'b'];
    let unknown = include_bytes!("../fixtures/unknown-member.bin").as_slice();
    let encoded = [metadata.as_slice(), &known_a, unknown].concat();
    assert!(matches!(
        decode_sync_event(&encoded).unwrap().event.payload,
        d::SyncPayload::Unknown { .. }
    ));
    let encoded = [encoded.as_slice(), &known_b].concat();
    match decode_sync_event(&encoded).unwrap().event.payload {
        d::SyncPayload::Recall(v) => {
            assert_eq!(v.msg_id, "");
            assert_eq!(v.conv_id, "b");
        }
        _ => panic!("最后已知成员没有生效"),
    }
    let merged = [metadata.as_slice(), &known_a, &known_b].concat();
    match decode_sync_event(&merged).unwrap().event.payload {
        d::SyncPayload::Recall(v) => {
            assert_eq!(v.msg_id, "a");
            assert_eq!(v.conv_id, "b");
        }
        _ => panic!("同名 message 应按 Protobuf 规则合并"),
    }
}

#[test]
fn unknown_message_enums_and_binary_content_are_lossless() {
    let msg = decode_message(include_bytes!("../fixtures/unknown-message.bin")).unwrap();
    assert_eq!(msg.kind, d::MessageKind::Unknown(42));
    assert_eq!(msg.conv_type, d::ConversationType::Unknown(99));
    assert_eq!(msg.state, d::MessageState::Unknown(99));
    match msg.content.unwrap() {
        d::MessageContent::Unknown { kind, raw_json } => {
            assert_eq!(kind, 42);
            let payload: serde_json::Value = serde_json::from_str(&raw_json).unwrap();
            assert_eq!(payload["protobuf_hex"], "b2010500ff088001");
        }
        _ => panic!("未知正文丢失"),
    }
}

#[test]
fn malformed_varints_lengths_and_groups_fail_instead_of_losing_bytes() {
    for bytes in [
        &[0][..],
        &[0x80][..],
        &[10, 2, 0][..],
        &[0x0f][..],
        &[0x0b, 0x14][..],
        &[8, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 2][..],
    ] {
        assert_eq!(raw::fields(bytes).unwrap_err().kind, d::ErrorKind::Protocol);
    }
    let group = [0x0b, 0x10, 1, 0x0c];
    assert_eq!(raw::fields(&group).unwrap()[0].encoded, &group);
    assert!(decode_frame(&[8, 2]).is_err());
}
