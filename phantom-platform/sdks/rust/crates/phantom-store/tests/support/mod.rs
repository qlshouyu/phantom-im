#![allow(dead_code)] // 各测试二进制复用其中不同的契约辅助函数。
use phantom_domain::*;
pub fn namespace(user: &str) -> StorageNamespace {
    StorageNamespace {
        service_url: "https://example.test/prefix".into(),
        app_id: "app".into(),
        user_id: user.into(),
    }
}
pub fn message(id: &str) -> ChatMessage {
    ChatMessage {
        msg_id: Some(format!("msg-{id}")),
        client_msg_id: id.into(),
        conv_id: "gr_g".into(),
        conv_type: ConversationType::Group,
        sender_id: "user".into(),
        kind: MessageKind::Text,
        content: Some(MessageContent::Text(TextContent {
            text: "正文".into(),
        })),
        text: "正文".into(),
        conv_seq: Some(9_007_199_254_740_993),
        server_time: Some(1000),
        state: MessageState::Read,
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
pub fn batch(revision: u64, id: &str) -> StorageChangeSet {
    let message = message(id);
    StorageChangeSet {
        expected_revision: revision,
        mutations: vec![
            StorageMutation::PutMessage(message.clone()),
            StorageMutation::PutOutbox(OutboxRecord {
                client_msg_id: id.into(),
                message,
                outcome: OperationOutcome::Unknown,
                submitted: true,
            }),
            StorageMutation::PutInbox(InboxRecord {
                user_seq: 9_007_199_254_740_993,
                event_time: 1000,
                payload: InboxPayload::Applied { field_number: 10 },
            }),
            StorageMutation::SetUserSeq(9_007_199_254_740_993),
        ],
    }
}

use phantom_ports::*;
use std::sync::Arc;
pub async fn contract(factory: Arc<dyn StoreFactory>) {
    let ns = namespace("用户/../?%#");
    let store = factory.open(ns.clone()).await.unwrap();
    assert_eq!(store.namespace(), &ns);
    assert_eq!(store.load().await.unwrap(), StorageSnapshot::default());
    assert_eq!(
        factory.open(ns.clone()).await.err().unwrap().kind,
        ErrorKind::Conflict
    );
    store.apply(batch(0, "one")).await.unwrap();
    let committed = store.load().await.unwrap();
    assert_eq!(committed.user_seq, 9_007_199_254_740_993);
    assert_eq!(committed.messages.len(), 1);
    assert_eq!(committed.outbox.len(), 1);
    assert_eq!(committed.inbox.len(), 1);
    assert_eq!(
        store
            .query_messages(MessageCacheQuery {
                conv_id: "gr_g".into(),
                before_conv_seq: None,
                limit: 50
            })
            .await
            .unwrap()[0]
            .text,
        "正文"
    );
    let bad = StorageChangeSet {
        expected_revision: 1,
        mutations: vec![
            StorageMutation::PutMessage(message("two")),
            StorageMutation::SetUserSeq(-1),
        ],
    };
    assert_eq!(store.apply(bad).await.unwrap_err().kind, ErrorKind::Storage);
    assert_eq!(store.load().await.unwrap(), committed);
    assert_eq!(
        store.apply(batch(0, "stale")).await.unwrap_err().kind,
        ErrorKind::Conflict
    );
    let mut environment = ns.clone();
    environment.service_url = "https://other.test/deployment".into();
    let mut application = ns.clone();
    application.app_id = "other-app".into();
    let mut opaque = ns.clone();
    opaque.user_id = "../含特殊字符/a?b#c".into();
    for isolated in [environment, application, opaque] {
        let isolated = factory.open(isolated).await.unwrap();
        assert_eq!(isolated.load().await.unwrap().user_seq, 0);
        isolated
            .apply(StorageChangeSet {
                expected_revision: 0,
                mutations: vec![StorageMutation::SetUserSeq(17)],
            })
            .await
            .unwrap();
        isolated.close().await.unwrap();
        assert_eq!(store.load().await.unwrap(), committed);
    }
    let other = factory.open(namespace("other")).await.unwrap();
    assert_eq!(other.load().await.unwrap().user_seq, 0);
    store.flush().await.unwrap();
    store.close().await.unwrap();
    store.close().await.unwrap();
    assert!(store.load().await.is_err());
    let reopened = factory.open(ns).await.unwrap();
    assert_eq!(reopened.load().await.unwrap(), committed);
    reopened.clear().await.unwrap();
    assert_eq!(reopened.load().await.unwrap().user_seq, 0);
    assert_eq!(other.load().await.unwrap().user_seq, 0);
    reopened.close().await.unwrap();
    other.close().await.unwrap();
}
