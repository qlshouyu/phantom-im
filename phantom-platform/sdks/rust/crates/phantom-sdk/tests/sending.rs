mod support;
use phantom_sdk::*;
use std::sync::{Arc, atomic::Ordering};
use support::*;
fn call() -> CallOptions {
    CallOptions {
        timeout_ms: Some(3_000),
        cancellation: None,
    }
}
fn query() -> MessageCacheQuery {
    MessageCacheQuery {
        conv_id: "gr_group".into(),
        before_conv_seq: None,
        limit: 200,
    }
}
#[tokio::test]
async fn outbox_precedes_network_same_id_is_busy_and_failed_resend_reuses_identity() {
    let server = Arc::new(Server::default());
    let sdk = client(&server, Arc::new(MemoryStoreFactory::new()));
    assert_eq!(
        sdk.send_message(input("offline", "正文"), call())
            .await
            .unwrap_err()
            .kind,
        ErrorKind::NotLoggedIn
    );
    ready(&sdk).await;
    server.block_send.store(true, Ordering::Release);
    let token = CancellationToken::new();
    let task = {
        let sdk = sdk.clone();
        let token = token.clone();
        tokio::spawn(async move {
            sdk.send_message(
                input("stable", "保留身份"),
                CallOptions {
                    timeout_ms: Some(3000),
                    cancellation: Some(token),
                },
            )
            .await
        })
    };
    tokio::time::timeout(std::time::Duration::from_secs(2), async {
        while !server.requests.lock().unwrap().iter().any(|r| r == "send") {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(
        sdk.get_messages(query()).unwrap()[0].state,
        MessageState::Sending
    );
    assert_eq!(
        sdk.send_message(input("stable", "保留身份"), call())
            .await
            .unwrap_err()
            .kind,
        ErrorKind::Busy
    );
    token.cancel();
    let error = task.await.unwrap().unwrap_err();
    assert_eq!(error.kind, ErrorKind::Cancelled);
    assert_eq!(error.outcome, OperationOutcome::Unknown);
    sdk.flush_storage(call()).await.unwrap();
    assert_eq!(
        sdk.get_messages(query()).unwrap()[0].state,
        MessageState::Failed
    );
    server.block_send.store(false, Ordering::Release);
    let retried = sdk.resend("stable".into(), call()).await.unwrap();
    assert_eq!(retried.client_msg_id, "stable");
    assert_eq!(retried.state, MessageState::Sent);
    assert_eq!(server.history.lock().unwrap().len(), 1);
    assert_eq!(
        sdk.resend("stable".into(), call()).await.unwrap_err().kind,
        ErrorKind::Conflict
    );
    assert_eq!(
        sdk.send_message(input("stable", "改变原文"), call())
            .await
            .unwrap_err()
            .kind,
        ErrorKind::Conflict
    );
    sdk.shutdown(call()).await.unwrap();
}
#[tokio::test]
async fn late_confirmation_updates_failed_message_but_cannot_roll_back_read_or_recall() {
    let server = Arc::new(Server::default());
    let sdk = client(&server, Arc::new(MemoryStoreFactory::new()));
    ready(&sdk).await;
    server.block_send.store(true, Ordering::Release);
    let error = sdk
        .send_message(
            input("late", "正文"),
            CallOptions {
                timeout_ms: Some(10),
                cancellation: None,
            },
        )
        .await
        .unwrap_err();
    assert_eq!(error.kind, ErrorKind::Timeout);
    sdk.flush_storage(call()).await.unwrap();
    assert_eq!(
        sdk.get_messages(query()).unwrap()[0].state,
        MessageState::Failed
    );
    let confirmation = || GatewayPush::Confirmation {
        client_msg_id: "late".into(),
        result: Ok(MessageConfirmation {
            msg_id: "msg-late".into(),
            conv_seq: 1,
            server_time: 10,
        }),
    };
    server.push(0, confirmation());
    tokio::time::timeout(std::time::Duration::from_secs(2), async {
        while sdk.get_messages(query()).unwrap()[0].state != MessageState::Sent {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    server.push(
        0,
        GatewayPush::Sync {
            event: Box::new(SyncEvent {
                user_seq: 1,
                event_time: 20,
                payload: SyncPayload::ReadReceipt(ReadReceiptEvent {
                    conv_id: "gr_group".into(),
                    read_conv_seq: 1,
                    reader_id: "peer".into(),
                    read_count: 1,
                }),
            }),
            ack_required: false,
        },
    );
    tokio::time::timeout(std::time::Duration::from_secs(2), async {
        while sdk.get_messages(query()).unwrap()[0].state != MessageState::Read {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    server.push(0, confirmation());
    server.push(
        0,
        GatewayPush::Sync {
            event: Box::new(SyncEvent {
                user_seq: 2,
                event_time: 30,
                payload: SyncPayload::Recall(RecallEvent {
                    msg_id: "msg-late".into(),
                    conv_id: "gr_group".into(),
                    operator_id: "user".into(),
                }),
            }),
            ack_required: false,
        },
    );
    tokio::time::timeout(std::time::Duration::from_secs(2), async {
        while sdk.get_messages(query()).unwrap()[0].state != MessageState::Recalled {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    server.push(0, confirmation());
    sdk.flush_storage(call()).await.unwrap();
    let m = sdk.get_messages(query()).unwrap().remove(0);
    assert_eq!(m.state, MessageState::Recalled);
    assert!(m.content.is_none());
    assert!(m.text.is_empty());
    sdk.shutdown(call()).await.unwrap();
}

#[tokio::test]
async fn different_send_identities_and_http_reads_can_progress_while_network_is_waiting() {
    let server = Arc::new(Server::default());
    let sdk = client(&server, Arc::new(MemoryStoreFactory::new()));
    ready(&sdk).await;
    server.block_send.store(true, Ordering::Release);
    let first = {
        let sdk = sdk.clone();
        tokio::spawn(async move { sdk.send_message(input("one", "第一条"), call()).await })
    };
    let second = {
        let sdk = sdk.clone();
        tokio::spawn(async move { sdk.send_message(input("two", "第二条"), call()).await })
    };
    tokio::time::timeout(std::time::Duration::from_secs(2), async {
        while server
            .requests
            .lock()
            .unwrap()
            .iter()
            .filter(|r| *r == "send")
            .count()
            < 2
        {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(sdk.get_messages(query()).unwrap().len(), 2);
    tokio::time::timeout(std::time::Duration::from_secs(1), sdk.list_friends(call()))
        .await
        .unwrap()
        .unwrap();
    server.block_send.store(false, Ordering::Release);
    server.release.notify_waiters();
    first.await.unwrap().unwrap();
    second.await.unwrap().unwrap();
    sdk.shutdown(call()).await.unwrap();
}

#[tokio::test]
async fn remaining_send_content_types_keep_typed_body_and_single_group_identity() {
    let server = Arc::new(Server::default());
    let sdk = client(&server, Arc::new(MemoryStoreFactory::new()));
    ready(&sdk).await;
    for (index, content) in [
        MessageContent::Emoji(EmojiContent {
            emoji_id: "smile".into(),
        }),
        MessageContent::Location(LocationContent {
            latitude: 31.2,
            longitude: 121.5,
            poi_name: "位置".into(),
            address: "地址".into(),
        }),
        MessageContent::Card(CardContent {
            card_type: ConversationType::Single,
            target_id: "peer".into(),
            snapshot_name: "名片".into(),
            snapshot_avatar: None,
        }),
        MessageContent::Merged(MergedContent {
            title: "转发".into(),
            messages: vec![message("embedded", 1)],
        }),
    ]
    .into_iter()
    .enumerate()
    {
        let result = sdk
            .send_message(
                SendMessageInput {
                    conv_id: if index % 2 == 0 {
                        "si_a__b".into()
                    } else {
                        "gr_group".into()
                    },
                    conv_type: if index % 2 == 0 {
                        ConversationType::Single
                    } else {
                        ConversationType::Group
                    },
                    content: content.clone(),
                    reply_to_msg_id: None,
                    mentions: vec![],
                    client_msg_id: Some(format!("content-{index}")),
                },
                call(),
            )
            .await
            .unwrap();
        assert_eq!(result.content, Some(content));
        assert_eq!(result.state, MessageState::Sent);
    }
    sdk.shutdown(call()).await.unwrap();
}
