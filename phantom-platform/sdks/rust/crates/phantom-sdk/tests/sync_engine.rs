mod support;
use phantom_core::{apply_sync_events, clear_derived_content};
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
async fn pagination_accepts_coalesced_gaps_preserves_large_sequences_and_does_not_jump_target() {
    let server = Arc::new(Server::default());
    let large = 9_007_199_254_740_993;
    server.target.store(large + 10, Ordering::Release);
    server.scripts.lock().unwrap().extend([
        SyncBatch {
            events: vec![event("a", large)],
            max_user_seq: large + 10,
            has_more: true,
            full_resync: false,
        },
        SyncBatch {
            events: vec![event("b", large + 5)],
            max_user_seq: large + 10,
            has_more: false,
            full_resync: false,
        },
        SyncBatch {
            events: vec![event("c", large + 10)],
            max_user_seq: large + 10,
            has_more: false,
            full_resync: false,
        },
    ]);
    let sdk = client(&server, Arc::new(MemoryStoreFactory::new()));
    ready(&sdk).await;
    assert_eq!(sdk.get_sync_status().unwrap().applied_user_seq, large + 10);
    assert_eq!(sdk.get_messages(query()).unwrap().len(), 3);
    assert_eq!(
        server
            .requests
            .lock()
            .unwrap()
            .iter()
            .filter(|m| *m == "sync")
            .count(),
        3
    );
    sdk.shutdown(call()).await.unwrap();
}
#[tokio::test]
async fn empty_pages_with_unreached_target_fail_bounded_without_advancing_cursor() {
    let server = Arc::new(Server::default());
    server.target.store(10, Ordering::Release);
    let sdk = client(&server, Arc::new(MemoryStoreFactory::new()));
    sdk.login(login("user"), call()).await.unwrap();
    let error = sdk.wait_ready(call()).await.unwrap_err();
    assert_eq!(error.kind, ErrorKind::Protocol);
    assert_eq!(sdk.get_sync_status().unwrap().applied_user_seq, 0);
    assert_eq!(
        server
            .requests
            .lock()
            .unwrap()
            .iter()
            .filter(|m| *m == "sync")
            .count(),
        3
    );
    sdk.shutdown(call()).await.unwrap();
}
#[tokio::test]
async fn full_resync_stages_changes_and_failure_preserves_previous_persistent_snapshot() {
    let directory = tempfile::tempdir().unwrap();
    let server = Arc::new(Server::default());
    let factory = Arc::new(SqliteStoreFactory::directory(directory.path().to_path_buf()).unwrap());
    let sdk = client(&server, factory.clone());
    ready(&sdk).await;
    sdk.send_message(input("old", "保留原库"), call())
        .await
        .unwrap();
    sdk.flush_storage(call()).await.unwrap();
    server.target.store(2, Ordering::Release);
    server.scripts.lock().unwrap().extend([
        SyncBatch {
            events: vec![],
            max_user_seq: 2,
            has_more: false,
            full_resync: true,
        },
        SyncBatch {
            events: vec![event("staged", 1)],
            max_user_seq: 2,
            has_more: true,
            full_resync: false,
        },
        SyncBatch {
            events: vec![],
            max_user_seq: 2,
            has_more: false,
            full_resync: false,
        },
        SyncBatch {
            events: vec![],
            max_user_seq: 2,
            has_more: false,
            full_resync: false,
        },
        SyncBatch {
            events: vec![],
            max_user_seq: 2,
            has_more: false,
            full_resync: false,
        },
    ]);
    sdk.reconnect(call()).await.unwrap();
    assert_eq!(
        sdk.wait_ready(call()).await.unwrap_err().kind,
        ErrorKind::Protocol
    );
    let cached = sdk.get_messages(query()).unwrap();
    assert!(cached.iter().any(|m| m.client_msg_id == "old"));
    assert!(!cached.iter().any(|m| m.client_msg_id == "staged"));
    assert_eq!(sdk.get_sync_status().unwrap().applied_user_seq, 0);
    sdk.shutdown(call()).await.unwrap();
    let reopened = factory
        .open(StorageNamespace {
            service_url: "https://example.test/prefix".into(),
            app_id: "app".into(),
            user_id: "user".into(),
        })
        .await
        .unwrap();
    let state = reopened.load().await.unwrap();
    assert!(state.messages.iter().any(|m| m.client_msg_id == "old"));
    assert!(!state.messages.iter().any(|m| m.client_msg_id == "staged"));
    reopened.close().await.unwrap();
}
#[tokio::test]
async fn successful_full_resync_commits_recall_before_releasing_message_events() {
    let server = Arc::new(Server::default());
    server.target.store(2, Ordering::Release);
    server.scripts.lock().unwrap().extend([
        SyncBatch {
            events: vec![],
            max_user_seq: 2,
            has_more: false,
            full_resync: true,
        },
        SyncBatch {
            events: vec![
                event("same-page", 1),
                SyncEvent {
                    user_seq: 2,
                    event_time: 20,
                    payload: SyncPayload::Recall(RecallEvent {
                        msg_id: "msg-same-page".into(),
                        conv_id: "gr_group".into(),
                        operator_id: "user".into(),
                    }),
                },
            ],
            max_user_seq: 2,
            has_more: false,
            full_resync: false,
        },
    ]);
    let sdk = client(&server, Arc::new(MemoryStoreFactory::new()));
    let subscription = sdk
        .subscribe(SubscriptionOptions { capacity: 128 })
        .unwrap();
    ready(&sdk).await;
    let cached = sdk.get_messages(query()).unwrap();
    assert_eq!(cached.len(), 1);
    assert_eq!(cached[0].state, MessageState::Recalled);
    assert!(cached[0].content.is_none());
    loop {
        let event = subscription.next(call()).await.unwrap().unwrap();
        match event.kind {
            EventKind::MessageChanged(m) => assert!(m.content.is_none()),
            EventKind::Sync(SyncEvent {
                payload: SyncPayload::NewMessage(v),
                ..
            }) => assert!(v.msg.unwrap().content.is_none()),
            EventKind::Ready(_) => break,
            _ => {}
        }
    }
    sdk.shutdown(call()).await.unwrap();
}
#[test]
fn all_twelve_events_and_unknown_inbox_are_atomic_and_terminal_content_is_cleared_in_outbox() {
    let mut state = StorageSnapshot::default();
    state.messages.push(message("source", 1));
    let mut reply = message("reply", 2);
    reply.reply_to_msg_id = Some("msg-source".into());
    reply.reply_snapshot = Some(ReplySnapshot {
        sender_id: "user".into(),
        sender_name: "名字".into(),
        msg_type: Some(MessageKind::Text),
        digest: "需清理原文".into(),
    });
    state.messages.push(reply.clone());
    state.outbox.push(OutboxRecord {
        client_msg_id: reply.client_msg_id.clone(),
        message: reply,
        outcome: OperationOutcome::Unknown,
        submitted: true,
    });
    let payloads = vec![
        SyncPayload::NewMessage(NewMessageEvent {
            msg: Some(message("new", 3)),
            conv_id: "gr_group".into(),
            conv_seq: 3,
            msg_id: "msg-new".into(),
            is_request: false,
        }),
        SyncPayload::Recall(RecallEvent {
            msg_id: "msg-source".into(),
            conv_id: "gr_group".into(),
            operator_id: "user".into(),
        }),
        SyncPayload::ReadPosition(ReadPositionEvent {
            conv_id: "gr_group".into(),
            read_conv_seq: 3,
        }),
        SyncPayload::ReadReceipt(ReadReceiptEvent {
            conv_id: "gr_group".into(),
            read_conv_seq: 3,
            reader_id: "peer".into(),
            read_count: 1,
        }),
        SyncPayload::ConversationState(ConversationStateEvent {
            conv_id: "gr_group".into(),
            field: ConversationStateField::Muted,
            bool_value: true,
            op_time: 1,
        }),
        SyncPayload::Draft(ConversationDraft {
            conv_id: "gr_group".into(),
            draft: "草稿".into(),
            edited_at: 10,
        }),
        SyncPayload::Relation(RelationEvent {
            kind: RelationEventType::ApplyReceived,
            peer_id: "peer".into(),
            peer: None,
            request_id: Some("request".into()),
            verification_message: "验证".into(),
            created_at: 1,
            group_ids: vec![],
            pinned: false,
            remark: "".into(),
            group_id: None,
            group_name: "".into(),
            group_deleted: false,
        }),
        SyncPayload::Group(GroupEvent {
            kind: GroupEventType::ProfileChanged,
            group_id: "group".into(),
            target_user_ids: vec![],
            operator_id: "user".into(),
            params: Default::default(),
        }),
        SyncPayload::Profile(ProfileEvent {
            kind: ProfileEventType::MyProfile,
            target_id: "user".into(),
            changed: Default::default(),
        }),
        SyncPayload::RestrictedDelivery(RestrictedDeliveryEvent {
            conv_id: "gr_group".into(),
            msg_id: "msg-new".into(),
            template_id: "notice".into(),
        }),
        SyncPayload::MessageStatus(MessageStatusEvent {
            msg_id: "msg-new".into(),
            conv_id: "gr_group".into(),
            status: MessageState::Read,
            status_time: 20,
        }),
        SyncPayload::Report(ReportEvent {
            report_id: "report".into(),
            target_type: "MESSAGE".into(),
            target_id: "msg-new".into(),
            status: "OPEN".into(),
            template_id: "notice".into(),
            updated_at: 30,
        }),
        SyncPayload::Unknown {
            field_number: 123,
            raw_bytes: vec![218, 7, 1, 255],
        },
    ];
    let events: Vec<_> = payloads
        .into_iter()
        .enumerate()
        .map(|(i, payload)| SyncEvent {
            user_seq: i as i64 + 1,
            event_time: 10,
            payload,
        })
        .collect();
    let next = apply_sync_events(&state, &events, "user").unwrap();
    assert_eq!(state.user_seq, 0);
    assert_eq!(next.user_seq, 13);
    assert_eq!(next.inbox.len(), 13);
    assert_eq!(
        next.inbox.last().unwrap().payload,
        InboxPayload::Unknown {
            field_number: 123,
            raw_bytes: vec![218, 7, 1, 255]
        }
    );
    assert!(next.invalidated.contains(&ProjectionKey::Friends));
    assert!(
        next.invalidated
            .contains(&ProjectionKey::GroupDetail("group".into()))
    );
    assert!(
        next.invalidated
            .contains(&ProjectionKey::UserProfile("user".into()))
    );
    assert!(next.invalidated.contains(&ProjectionKey::Reports));
    assert!(
        next.messages
            .iter()
            .find(|m| m.client_msg_id == "source")
            .unwrap()
            .content
            .is_none()
    );
    assert!(
        next.messages
            .iter()
            .find(|m| m.client_msg_id == "reply")
            .unwrap()
            .reply_snapshot
            .is_none()
    );
    // 单独保留未完成 Outbox，验证派生引用清除不只作用于主消息表。
    let mut next = next;
    let mut pending = state.outbox[0].clone();
    pending.client_msg_id = "pending-only".into();
    pending.message.client_msg_id = pending.client_msg_id.clone();
    next.outbox.push(pending);
    clear_derived_content(&mut next);
    assert!(next.outbox.last().unwrap().message.reply_snapshot.is_none());
    assert_eq!(apply_sync_events(&next, &events, "user").unwrap(), next);
    let mut bad = events;
    bad[1].user_seq = 1;
    assert_eq!(
        apply_sync_events(&state, &bad, "user").unwrap_err().kind,
        ErrorKind::Protocol
    );
}

#[tokio::test]
async fn out_of_order_push_fills_gap_then_duplicates_only_ack_committed_cursor() {
    let server = Arc::new(Server::default());
    let sdk = client(&server, Arc::new(MemoryStoreFactory::new()));
    ready(&sdk).await;
    let subscription = sdk
        .subscribe(SubscriptionOptions { capacity: 128 })
        .unwrap();
    server.scripts.lock().unwrap().push_back(SyncBatch {
        events: vec![event("first", 1), event("second", 2), event("third", 3)],
        max_user_seq: 3,
        has_more: false,
        full_resync: false,
    });
    for (index, seq) in [3, 3, 1].into_iter().enumerate() {
        server.push(
            0,
            GatewayPush::Sync {
                event: Box::new(event(if seq == 1 { "first" } else { "third" }, seq)),
                ack_required: true,
            },
        );
        tokio::time::timeout(std::time::Duration::from_secs(2), async {
            while server.acks.lock().unwrap().len() <= index {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
    }
    assert_eq!(*server.acks.lock().unwrap(), [3, 3, 3]);
    assert_eq!(sdk.get_messages(query()).unwrap().len(), 3);
    let mut messages = 0;
    loop {
        match subscription
            .next(CallOptions {
                timeout_ms: Some(20),
                cancellation: None,
            })
            .await
        {
            Ok(Some(event)) => {
                if matches!(event.kind, EventKind::MessageChanged(_)) {
                    messages += 1;
                }
            }
            Err(e) if e.kind == ErrorKind::Timeout => break,
            other => panic!("{other:?}"),
        }
    }
    assert_eq!(messages, 3);
    sdk.shutdown(call()).await.unwrap();
}
#[tokio::test]
async fn missing_inline_content_must_be_resolved_before_ack_and_failed_reference_is_replayable() {
    let server = Arc::new(Server::default());
    let sdk = client(&server, Arc::new(MemoryStoreFactory::new()));
    ready(&sdk).await;
    let subscription = sdk
        .subscribe(SubscriptionOptions { capacity: 128 })
        .unwrap();
    let mut missing = event("reference", 1);
    if let SyncPayload::NewMessage(value) = &mut missing.payload {
        value.msg = None;
    }
    server.push(
        0,
        GatewayPush::Sync {
            event: Box::new(missing.clone()),
            ack_required: true,
        },
    );
    loop {
        if let EventKind::Error(error) = subscription.next(call()).await.unwrap().unwrap().kind {
            assert_eq!(error.kind, ErrorKind::Protocol);
            break;
        }
    }
    assert!(server.acks.lock().unwrap().is_empty());
    assert_eq!(sdk.get_sync_status().unwrap().applied_user_seq, 0);
    assert!(sdk.get_messages(query()).unwrap().is_empty());
    server.history.lock().unwrap().push(message("reference", 1));
    server.push(
        0,
        GatewayPush::Sync {
            event: Box::new(missing),
            ack_required: true,
        },
    );
    tokio::time::timeout(std::time::Duration::from_secs(2), async {
        while server.acks.lock().unwrap().is_empty() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(sdk.get_messages(query()).unwrap()[0].text, "正文");
    assert_eq!(*server.acks.lock().unwrap(), [1]);
    sdk.shutdown(call()).await.unwrap();
}

struct Pages {
    base: Arc<Server>,
    pages: std::sync::Mutex<std::collections::VecDeque<ConversationPage>>,
    blocked: tokio::sync::Notify,
    release: tokio::sync::Notify,
}
impl PlatformApi for Pages {
    fn execute(
        &self,
        request: ApiRequest,
        identity: HttpIdentity,
        id: String,
    ) -> SdkFuture<'_, ApiResponse> {
        Box::pin(async move {
            if let ApiRequest::ListConversations { query } = &request {
                if query.cursor.as_deref() == Some("page-two") {
                    self.blocked.notify_one();
                    self.release.notified().await;
                }
                return Ok(ApiResponse::ListConversations(Box::new(
                    self.pages.lock().unwrap().pop_front().unwrap(),
                )));
            }
            self.base.execute(request, identity, id).await
        })
    }
}
#[tokio::test]
async fn full_snapshot_replays_first_page_baseline_and_serializes_push_during_later_pages() {
    let server = Arc::new(Server::default());
    server.target.store(5, Ordering::Release);
    let page = |max, more| ConversationPage {
        items: vec![],
        next_cursor: if more { Some("page-two".into()) } else { None },
        has_more: more,
        max_user_seq: max,
        badge_count: 0,
    };
    let pages = Arc::new(Pages {
        base: server.clone(),
        pages: std::sync::Mutex::new([page(0, false), page(2, true), page(5, false)].into()),
        blocked: tokio::sync::Notify::new(),
        release: tokio::sync::Notify::new(),
    });
    let recall = |seq| SyncEvent {
        user_seq: seq,
        event_time: 20,
        payload: SyncPayload::Recall(RecallEvent {
            msg_id: "msg-during-pages".into(),
            conv_id: "gr_group".into(),
            operator_id: "user".into(),
        }),
    };
    server.scripts.lock().unwrap().extend([
        SyncBatch {
            events: vec![],
            max_user_seq: 5,
            has_more: false,
            full_resync: true,
        },
        SyncBatch {
            events: vec![
                event("during-pages", 3),
                recall(4),
                SyncEvent {
                    user_seq: 5,
                    event_time: 20,
                    payload: SyncPayload::Draft(ConversationDraft {
                        conv_id: "gr_group".into(),
                        draft: "草稿".into(),
                        edited_at: 20,
                    }),
                },
            ],
            max_user_seq: 5,
            has_more: false,
            full_resync: false,
        },
    ]);
    let mut host = support::host(&server, Arc::new(MemoryStoreFactory::new()));
    host.api = pages.clone();
    let sdk =
        PhantomClient::with_host(ClientOptions::new("https://example.test", "app"), host).unwrap();
    let subscription = sdk
        .subscribe(SubscriptionOptions { capacity: 128 })
        .unwrap();
    sdk.login(login("user"), call()).await.unwrap();
    pages.blocked.notified().await;
    server.push(
        0,
        GatewayPush::Sync {
            event: Box::new(recall(6)),
            ack_required: true,
        },
    );
    pages.release.notify_one();
    sdk.wait_ready(call()).await.unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(2), async {
        while !server.acks.lock().unwrap().contains(&6) {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    let sinces: Vec<_> = server
        .gateway_requests
        .lock()
        .unwrap()
        .iter()
        .filter_map(|r| {
            if let GatewayRequest::Sync { since, .. } = r {
                Some(*since)
            } else {
                None
            }
        })
        .collect();
    assert_eq!(sinces, [0, 2]);
    let message = &sdk.get_messages(query()).unwrap()[0];
    assert_eq!(message.state, MessageState::Recalled);
    assert!(message.content.is_none());
    loop {
        match subscription
            .next(CallOptions {
                timeout_ms: Some(20),
                cancellation: None,
            })
            .await
        {
            Ok(Some(event)) => {
                if let EventKind::MessageChanged(m) = event.kind {
                    assert!(m.content.is_none());
                }
            }
            Err(e) if e.kind == ErrorKind::Timeout => break,
            other => panic!("{other:?}"),
        }
    }
    sdk.shutdown(call()).await.unwrap();
}

#[tokio::test]
async fn wait_ready_recovers_internal_event_lag_after_large_sync_batch() {
    use std::{future::Future, task::Poll};
    let server = Arc::new(Server::default());
    server.block_sync.store(true, Ordering::Release);
    server.target.store(300, Ordering::Release);
    server.scripts.lock().unwrap().push_back(SyncBatch {
        events: (1..=300)
            .map(|seq| event(&format!("burst-{seq}"), seq))
            .collect(),
        max_user_seq: 300,
        has_more: false,
        full_resync: false,
    });
    let sdk = client(&server, Arc::new(MemoryStoreFactory::new()));
    sdk.login(login("user"), call()).await.unwrap();
    let external = sdk.subscribe(SubscriptionOptions { capacity: 8 }).unwrap();
    let mut waiting = Box::pin(sdk.wait_ready(call()));
    std::future::poll_fn(|cx| {
        assert!(waiting.as_mut().poll(cx).is_pending());
        Poll::Ready(())
    })
    .await;
    server.release.notify_one();
    tokio::time::timeout(std::time::Duration::from_secs(3), async {
        while sdk.get_sync_status().unwrap().phase != SyncPhase::Ready {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    let status = waiting.await.unwrap();
    assert_eq!(status.phase, SyncPhase::Ready);
    assert_eq!(status.applied_user_seq, 300);
    assert_eq!(
        external.next(call()).await.unwrap_err().kind,
        ErrorKind::EventLagged
    );
    sdk.shutdown(call()).await.unwrap();
}
