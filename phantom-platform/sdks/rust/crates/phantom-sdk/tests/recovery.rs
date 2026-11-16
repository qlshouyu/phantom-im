use phantom_core::open_account;
use phantom_sdk::*;
use std::sync::Arc;
fn ns() -> StorageNamespace {
    StorageNamespace {
        service_url: "https://example.test/api".into(),
        app_id: "app".into(),
        user_id: "user".into(),
    }
}
fn sending(id: &str) -> ChatMessage {
    ChatMessage {
        msg_id: None,
        client_msg_id: id.into(),
        conv_id: "gr_group".into(),
        conv_type: ConversationType::Group,
        sender_id: "user".into(),
        kind: MessageKind::Text,
        content: Some(MessageContent::Text(TextContent {
            text: "秘密正文".into(),
        })),
        text: "秘密正文".into(),
        conv_seq: None,
        server_time: None,
        state: MessageState::Sending,
        recalled: false,
        restricted: false,
        is_request: false,
        reply_to_msg_id: None,
        reply_snapshot: None,
        mentions: None,
        burn_after_read_ttl_seconds: 60,
        error: None,
    }
}
#[tokio::test]
async fn reopening_persistent_account_recovers_sends_markers_drafts_and_display_deadlines() {
    let dir = tempfile::tempdir().unwrap();
    let factory = Arc::new(SqliteStoreFactory::directory(dir.path().to_path_buf()).unwrap());
    let store = factory.open(ns()).await.unwrap();
    let unfinished = sending("stable-id");
    let mut confirmed = sending("late-confirmed");
    confirmed.msg_id = Some("confirmed".into());
    confirmed.state = MessageState::Read;
    let mut displayed = sending("displayed");
    displayed.msg_id = Some("displayed-msg".into());
    displayed.state = MessageState::Sent;
    let mut terminal = sending("terminal");
    terminal.msg_id = Some("recalled-msg".into());
    terminal.state = MessageState::Recalled;
    let outbox = |m: &ChatMessage| {
        StorageMutation::PutOutbox(OutboxRecord {
            client_msg_id: m.client_msg_id.clone(),
            message: m.clone(),
            outcome: OperationOutcome::Unknown,
            submitted: true,
        })
    };
    let stale_confirmed = sending("late-confirmed");
    store
        .apply(StorageChangeSet {
            expected_revision: 0,
            mutations: vec![
                StorageMutation::PutMessage(unfinished.clone()),
                outbox(&unfinished),
                StorageMutation::PutMessage(confirmed),
                outbox(&stale_confirmed),
                StorageMutation::PutMessage(displayed),
                StorageMutation::PutMessage(terminal.clone()),
                outbox(&terminal),
                StorageMutation::PutDraft(ConversationDraft {
                    conv_id: "gr_group".into(),
                    draft: "草稿".into(),
                    edited_at: 55,
                }),
                StorageMutation::SetUserSeq(9_007_199_254_740_993),
                StorageMutation::PutMessageTombstone(MessageTombstone {
                    msg_id: "deleted".into(),
                    client_msg_id: None,
                    conv_id: Some("gr_group".into()),
                    reason: TombstoneReason::Deleted,
                }),
                StorageMutation::PutConversationTombstone(ConversationTombstone {
                    conv_id: "gr_old".into(),
                    through_conv_seq: 777,
                }),
                StorageMutation::PutDisplayDeadline(DisplayDeadline {
                    msg_id: "displayed-msg".into(),
                    first_displayed_at: 100,
                    deadline_at: 200,
                }),
            ],
        })
        .await
        .unwrap();
    store.flush().await.unwrap();
    store.close().await.unwrap();
    drop(store);
    drop(factory);
    let factory = Arc::new(SqliteStoreFactory::directory(dir.path().to_path_buf()).unwrap());
    let mut account = open_account(factory.clone(), ns()).await.unwrap();
    let message = account
        .snapshot
        .messages
        .iter()
        .find(|m| m.client_msg_id == "stable-id")
        .unwrap();
    assert_eq!(message.state, MessageState::Failed);
    assert_eq!(
        message.error.as_ref().unwrap().outcome,
        OperationOutcome::Unknown
    );
    assert_eq!(
        message.error.as_ref().unwrap().client_msg_id.as_deref(),
        Some("stable-id")
    );
    assert_eq!(account.snapshot.outbox.len(), 1);
    assert_eq!(account.snapshot.outbox[0].client_msg_id, "stable-id");
    assert_eq!(
        account
            .snapshot
            .messages
            .iter()
            .find(|m| m.client_msg_id == "late-confirmed")
            .unwrap()
            .state,
        MessageState::Read
    );
    let terminal = account
        .snapshot
        .messages
        .iter()
        .find(|m| m.client_msg_id == "terminal")
        .unwrap();
    assert!(terminal.content.is_none());
    assert!(terminal.text.is_empty());
    assert_eq!(account.snapshot.drafts[0].draft, "草稿");
    assert_eq!(account.snapshot.user_seq, 9_007_199_254_740_993);
    assert_eq!(account.snapshot.message_tombstones[0].msg_id, "deleted");
    assert_eq!(
        account.snapshot.conversation_tombstones[0].through_conv_seq,
        777
    );
    assert!(account.recover_expired(199).await.unwrap().is_empty());
    assert_eq!(account.snapshot.display_deadlines[0].deadline_at, 200);
    assert_eq!(
        account.recover_expired(200).await.unwrap()[0].state,
        MessageState::Burned
    );
    account.store.flush().await.unwrap();
    account.store.close().await.unwrap();
    drop(account);
    let reopened = open_account(factory, ns()).await.unwrap();
    let burned = reopened
        .snapshot
        .messages
        .iter()
        .find(|m| m.client_msg_id == "displayed")
        .unwrap();
    assert_eq!(burned.state, MessageState::Burned);
    assert!(burned.content.is_none());
    assert!(burned.text.is_empty());
    assert!(reopened.snapshot.display_deadlines.is_empty());
    assert!(
        reopened
            .snapshot
            .message_tombstones
            .iter()
            .any(|t| t.msg_id == "displayed-msg" && t.reason == TombstoneReason::Burned)
    );
    reopened.store.close().await.unwrap();
}
