use phantom_domain::*;

fn text(value: &str) -> MessageContent {
    MessageContent::Text(TextContent { text: value.into() })
}

fn message() -> ChatMessage {
    ChatMessage {
        msg_id: Some("message".into()),
        client_msg_id: "client".into(),
        conv_id: "conversation".into(),
        conv_type: ConversationType::Group,
        sender_id: "sender".into(),
        kind: MessageKind::Text,
        content: Some(text("正文")),
        text: "正文".into(),
        conv_seq: Some(9_007_199_254_740_993),
        server_time: Some(i64::MAX),
        state: MessageState::Read,
        recalled: false,
        restricted: false,
        is_request: false,
        reply_to_msg_id: None,
        reply_snapshot: Some(ReplySnapshot {
            sender_id: "a".into(),
            sender_name: "a".into(),
            msg_type: None,
            digest: "引用".into(),
        }),
        mentions: None,
        burn_after_read_ttl_seconds: 0,
        error: None,
    }
}

#[test]
fn identifiers_are_opaque_and_only_blank_is_rejected() {
    for id in ["", "  ", "\t\n", "\u{3000}"] {
        assert_eq!(
            validate_id(id, "id").unwrap_err().kind,
            ErrorKind::InvalidArgument
        );
    }
    assert_eq!(
        single_conv_id("用户/一".into(), "a ?".into()).unwrap(),
        "si_a ?__用户/一"
    );
    assert_eq!(
        single_conv_id("b".into(), "a".into()).unwrap(),
        single_conv_id("a".into(), "b".into()).unwrap()
    );
}

#[test]
fn unicode_limit_counts_codepoints_instead_of_bytes_or_graphemes() {
    assert!(text(&"🦀".repeat(5000)).validate_send().is_ok());
    assert!(text(&"🦀".repeat(5001)).validate_send().is_err());
    assert!(text(&"e\u{301}".repeat(2500)).validate_send().is_ok());
    assert!(text(&"e\u{301}".repeat(2501)).validate_send().is_err());
    assert!(text("").validate_send().is_err());
}

#[test]
fn sequence_and_paging_boundaries_do_not_use_floating_point() {
    for seq in [0, 9_007_199_254_740_993, i64::MAX] {
        assert!(validate_seq(seq, "seq").is_ok());
    }
    assert!(validate_seq(-1, "seq").is_err());
    for (limit, before, valid) in [
        (1, None, true),
        (200, Some(i64::MAX), true),
        (0, None, false),
        (201, None, false),
        (50, Some(0), false),
        (50, Some(-1), false),
    ] {
        assert_eq!(validate_page(limit, 200, before).is_ok(), valid);
    }
}

#[test]
fn absent_fields_unknown_payload_and_large_numbers_survive_roundtrip() {
    let mut original = message();
    original.kind = MessageKind::Unknown(2048);
    original.content = Some(MessageContent::Unknown {
        kind: 2048,
        raw_json: "{\"new\":true}".into(),
    });
    let encoded = serde_json::to_string(&original).unwrap();
    assert!(encoded.contains("9007199254740993"));
    let decoded: ChatMessage = serde_json::from_str(&encoded).unwrap();
    assert_eq!(original, decoded);
    assert_eq!(decoded.mentions, None);
    assert_eq!(decoded.reply_snapshot.unwrap().msg_type, None);
    original.mentions = Some(vec![]);
    let decoded: ChatMessage =
        serde_json::from_str(&serde_json::to_string(&original).unwrap()).unwrap();
    assert_eq!(decoded.mentions, Some(vec![]));
}

#[test]
fn delivery_and_terminal_states_resist_late_send_confirmations() {
    for current in [
        MessageState::Sent,
        MessageState::Delivered,
        MessageState::Read,
        MessageState::Recalled,
        MessageState::Burned,
    ] {
        for incoming in [
            MessageState::Sending,
            MessageState::Failed,
            MessageState::Unknown(42),
        ] {
            assert_eq!(merge_message_state(&current, &incoming), current);
        }
    }
    assert_eq!(
        merge_message_state(&MessageState::Read, &MessageState::Sent),
        MessageState::Read
    );
    assert_eq!(
        merge_message_state(&MessageState::Burned, &MessageState::Recalled),
        MessageState::Burned
    );
    assert_eq!(
        merge_message_state(&MessageState::Unknown(42), &MessageState::Unknown(99)),
        MessageState::Unknown(99)
    );
}

#[test]
fn terminal_content_removes_all_embedded_original_text() {
    for state in [MessageState::Recalled, MessageState::Burned] {
        let mut value = message();
        value.state = state;
        clear_terminal_content(&mut value);
        assert!(value.content.is_none());
        assert!(value.text.is_empty());
        assert!(value.reply_snapshot.is_none());
        let once = value.clone();
        clear_terminal_content(&mut value);
        assert_eq!(once, value);
    }
    let mut value = message();
    value.recalled = true;
    clear_terminal_content(&mut value);
    assert_eq!(value.state, MessageState::Recalled);
    assert!(value.content.is_none());
}

#[test]
fn media_bounds_and_unknown_send_are_rejected() {
    for (seconds, valid) in [(0, false), (1, true), (60, true), (61, false)] {
        let value = MessageContent::Voice(VoiceContent {
            media_id: "voice".into(),
            url: None,
            duration_s: seconds,
        });
        assert_eq!(value.validate_send().is_ok(), valid);
    }
    for (seconds, bytes, valid) in [
        (1, 104_857_600, true),
        (300, 1, true),
        (301, 1, false),
        (0, 1, false),
        (1, 104_857_601, false),
    ] {
        let value = MessageContent::Video(VideoContent {
            media_id: "video".into(),
            url: None,
            cover_url: None,
            duration_s: seconds,
            size_bytes: bytes,
            width: 1,
            height: 1,
        });
        assert_eq!(value.validate_send().is_ok(), valid);
    }
    for (size_bytes, valid) in [(524_288_000, true), (524_288_001, false)] {
        let value = MessageContent::File(FileContent {
            media_id: "file".into(),
            url: None,
            file_name: "文件".into(),
            size_bytes,
            ext: None,
        });
        assert_eq!(value.validate_send().is_ok(), valid);
    }
    assert!(
        MessageContent::Unknown {
            kind: 42,
            raw_json: "{}".into()
        }
        .validate_send()
        .is_err()
    );
}

#[test]
fn merged_message_count_and_nested_content_are_checked() {
    for (count, valid) in [(0, false), (1, true), (100, true), (101, false)] {
        let value = MessageContent::Merged(MergedContent {
            title: "合并".into(),
            messages: vec![message(); count],
        });
        assert_eq!(value.validate_send().is_ok(), valid);
    }
    let mut nested = message();
    nested.kind = MessageKind::Merged;
    assert!(
        MessageContent::Merged(MergedContent {
            title: "".into(),
            messages: vec![nested]
        })
        .validate_send()
        .is_err()
    );
}

#[test]
fn debug_does_not_expose_authentication_tokens() {
    let secret = "private-test-token";
    let input = LoginOptions {
        user_token: secret.into(),
        user_id: "user".into(),
        device_id: "device".into(),
        device_type: DeviceType::Desktop,
        platform: "test".into(),
        client_version: None,
    };
    assert!(!format!("{input:?}").contains(secret));
    assert!(
        !format!(
            "{:?}",
            TokenUpdate {
                user_token: secret.into()
            }
        )
        .contains(secret)
    );
    assert!(
        !format!(
            "{:?}",
            PushTokenInput {
                token: secret.into(),
                device_type: DeviceType::Desktop,
                provider: "test".into()
            }
        )
        .contains(secret)
    );
}

#[test]
fn media_validation_preserves_file_fallback_and_checks_type_specific_bounds() {
    let file = UploadMetadata {
        file_name: "无扩展名".into(),
        size_bytes: 5,
        sha256: "a".repeat(64),
        mime_type: "application/octet-stream".into(),
        media_type: MediaType::File,
        duration_seconds: None,
        width: None,
        height: None,
        burn_after_read: false,
        burn_after_read_ttl_seconds: None,
    };
    file.validate().unwrap();
    for mime in [
        "",
        "application",
        "audio/",
        "application/octet-stream\r\nX: bad",
    ] {
        let mut m = file.clone();
        m.mime_type = mime.into();
        assert_eq!(m.validate().unwrap_err().kind, ErrorKind::InvalidArgument);
    }
    for size in [0, 524_288_001] {
        let mut m = file.clone();
        m.size_bytes = size;
        assert!(m.validate().is_err());
    }
    let mut voice = file.clone();
    voice.media_type = MediaType::Voice;
    voice.mime_type = "audio/ogg".into();
    assert!(voice.validate().is_err());
    for duration in [0, 61] {
        voice.duration_seconds = Some(duration);
        assert!(voice.validate().is_err());
    }
    voice.duration_seconds = Some(60);
    voice.validate().unwrap();
    let mut image = file.clone();
    image.media_type = MediaType::Image;
    assert!(image.validate().is_err());
    image.mime_type = "image/png".into();
    image.validate().unwrap();
    image.width = Some(0);
    assert!(image.validate().is_err());
    let mut video = file.clone();
    video.media_type = MediaType::Video;
    video.mime_type = "video/mp4".into();
    video.size_bytes = 104_857_601;
    assert!(video.validate().is_err());
    video.size_bytes = 5;
    video.duration_seconds = Some(301);
    assert!(video.validate().is_err());
    video.duration_seconds = Some(300);
    video.validate().unwrap();
}
