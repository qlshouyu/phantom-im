#[path = "support/fault_store.rs"]
mod fault_store;
#[path = "../../phantom-store/tests/support/host.rs"]
#[allow(dead_code)]
mod host;
mod support;
use phantom_sdk::*;
use std::sync::Arc;
use support::*;
fn query() -> MessageCacheQuery {
    MessageCacheQuery {
        conv_id: "gr_group".into(),
        before_conv_seq: None,
        limit: 100,
    }
}
fn call() -> CallOptions {
    CallOptions {
        timeout_ms: Some(3_000),
        cancellation: None,
    }
}
#[tokio::test]
async fn same_sdk_flow_uses_memory_sqlite_host_trait_and_callback_without_dual_writes() {
    for mode in 0..5 {
        let directory = tempfile::tempdir().unwrap();
        let server = Arc::new(Server::default());
        let app_id = format!(
            "rust-sdk-matrix-{}-{}-{mode}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        );
        let make_client = |factory| {
            PhantomClient::with_host(
                ClientOptions::new("https://example.test/prefix", &app_id),
                support::host(&server, factory),
            )
            .unwrap()
        };
        let pool = host::HostFactory::default();
        let factory: Arc<dyn StoreFactory> = match mode {
            0 => Arc::new(MemoryStoreFactory::new()),
            1 => Arc::new(SqliteStoreFactory::directory(directory.path().to_path_buf()).unwrap()),
            2 => Arc::new(pool.clone()),
            3 => Arc::new(CallbackStoreFactory::new(host::callbacks(pool.clone())).unwrap()),
            _ => Arc::new(SqliteStoreFactory::new()),
        };
        let fail = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let factory: Arc<dyn StoreFactory> = Arc::new(fault_store::FaultFactory {
            inner: factory,
            fail: fail.clone(),
        });
        let sdk = make_client(factory.clone());
        sdk.flush_storage(call()).await.unwrap();
        ready(&sdk).await;
        let sent = sdk
            .send_message(input("outgoing", "发出的消息"), call())
            .await
            .unwrap();
        server
            .history
            .lock()
            .unwrap()
            .push(message("http-history", 2));
        let history = sdk
            .history(
                HistoryQuery {
                    conv_id: "gr_group".into(),
                    before_conv_seq: None,
                    limit: 100,
                },
                call(),
            )
            .await
            .unwrap();
        assert_eq!(history.items.len(), 2);
        let events = sdk
            .subscribe(SubscriptionOptions { capacity: 128 })
            .unwrap();
        fail.store(true, std::sync::atomic::Ordering::Release);
        server.push(
            0,
            GatewayPush::Sync {
                event: Box::new(event("incoming", 1)),
                ack_required: true,
            },
        );
        loop {
            if let EventKind::Error(error) = events.next(call()).await.unwrap().unwrap().kind {
                assert_eq!(error.kind, ErrorKind::Storage);
                break;
            }
        }
        assert_eq!(sdk.get_sync_status().unwrap().applied_user_seq, 0);
        assert!(server.acks.lock().unwrap().is_empty());
        assert!(
            !sdk.get_messages(query())
                .unwrap()
                .iter()
                .any(|m| m.client_msg_id == "incoming")
        );
        fail.store(false, std::sync::atomic::Ordering::Release);
        server.push(
            0,
            GatewayPush::Sync {
                event: Box::new(event("incoming", 1)),
                ack_required: true,
            },
        );
        tokio::time::timeout(std::time::Duration::from_secs(2), async {
            loop {
                if server.acks.lock().unwrap().contains(&1) {
                    break;
                }
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        server.push(
            0,
            GatewayPush::Sync {
                event: Box::new(SyncEvent {
                    user_seq: 2,
                    event_time: 20,
                    payload: SyncPayload::Recall(RecallEvent {
                        msg_id: "msg-http-history".into(),
                        conv_id: "gr_group".into(),
                        operator_id: "user".into(),
                    }),
                }),
                ack_required: true,
            },
        );
        tokio::time::timeout(std::time::Duration::from_secs(2), async {
            while !server.acks.lock().unwrap().contains(&2) {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        sdk.history(
            HistoryQuery {
                conv_id: "gr_group".into(),
                before_conv_seq: None,
                limit: 100,
            },
            call(),
        )
        .await
        .unwrap();
        let recalled = sdk
            .get_messages(query())
            .unwrap()
            .into_iter()
            .find(|m| m.client_msg_id == "http-history")
            .unwrap();
        assert_eq!(recalled.state, MessageState::Recalled);
        assert!(recalled.content.is_none());
        assert!(recalled.text.is_empty());
        let namespace = StorageNamespace {
            service_url: "https://example.test/prefix".into(),
            app_id: app_id.clone(),
            user_id: "user".into(),
        };
        sdk.flush_storage(call()).await.unwrap();
        sdk.shutdown(call()).await.unwrap();
        let reopened = factory.open(namespace.clone()).await.unwrap();
        let snapshot = reopened.load().await.unwrap();
        assert_eq!(snapshot.user_seq, 2);
        assert_eq!(snapshot.inbox.len(), 2);
        assert!(snapshot.outbox.is_empty());
        assert!(snapshot.messages.iter().any(|m| m.msg_id == sent.msg_id));
        assert!(
            snapshot
                .messages
                .iter()
                .any(|m| m.client_msg_id == "http-history")
        );
        assert!(
            snapshot
                .messages
                .iter()
                .any(|m| m.client_msg_id == "incoming")
        );
        reopened.close().await.unwrap();
        if mode == 1 {
            let sqlite = SqliteStoreFactory::directory(directory.path().to_path_buf()).unwrap();
            let raw =
                rusqlite::Connection::open(sqlite.database_path(&namespace).unwrap()).unwrap();
            let count: i64 = raw
                .query_row("SELECT count(*) FROM messages", [], |r| r.get(0))
                .unwrap();
            assert_eq!(count, 3);
            let history_count: i64 = raw
                .query_row(
                    "SELECT count(*) FROM messages WHERE client_msg_id='http-history'",
                    [],
                    |r| r.get(0),
                )
                .unwrap();
            assert_eq!(history_count, 1);
        }
        // clear_cache 只影响当前 namespace，关闭账号句柄不会关闭共享工厂。
        let other = factory
            .open(StorageNamespace {
                user_id: "other".into(),
                ..namespace.clone()
            })
            .await
            .unwrap();
        other
            .apply(StorageChangeSet {
                expected_revision: 0,
                mutations: vec![StorageMutation::SetUserSeq(77)],
            })
            .await
            .unwrap();
        other.close().await.unwrap();
        let sdk = make_client(factory.clone());
        ready(&sdk).await;
        assert_eq!(sdk.get_messages(query()).unwrap().len(), 3);
        sdk.logout(LogoutOptions { clear_cache: true }, call())
            .await
            .unwrap();
        sdk.shutdown(call()).await.unwrap();
        let account = factory.open(namespace).await.unwrap();
        assert!(account.load().await.unwrap().messages.is_empty());
        account.close().await.unwrap();
        let other = factory
            .open(StorageNamespace {
                service_url: "https://example.test/prefix".into(),
                app_id: app_id.clone(),
                user_id: "other".into(),
            })
            .await
            .unwrap();
        assert_eq!(other.load().await.unwrap().user_seq, 77);
        other.close().await.unwrap();
        if mode == 4 {
            for user_id in ["user", "other"] {
                let path = SqliteStoreFactory::new()
                    .database_path(&StorageNamespace {
                        service_url: "https://example.test/prefix".into(),
                        app_id: app_id.clone(),
                        user_id: user_id.into(),
                    })
                    .unwrap();
                std::fs::remove_dir_all(path.parent().unwrap()).unwrap();
            }
        }
        if mode == 2 || mode == 3 {
            assert_eq!(std::fs::read_dir(directory.path()).unwrap().count(), 0);
            let operations: Vec<_> = pool.calls().into_iter().map(|c| c.0).collect();
            for operation in ["open", "load", "apply", "flush", "clear", "close"] {
                assert!(operations.contains(&operation));
            }
        }
    }
}
#[tokio::test]
async fn custom_commit_failure_publishes_no_message_or_ack_and_replay_is_atomic() {
    for callback in [false, true] {
        let pool = host::HostFactory::default();
        let server = Arc::new(Server::default());
        let factory: Arc<dyn StoreFactory> = if callback {
            Arc::new(CallbackStoreFactory::new(host::callbacks(pool.clone())).unwrap())
        } else {
            Arc::new(pool.clone())
        };
        let sdk = client(&server, factory.clone());
        ready(&sdk).await;
        let subscription = sdk.subscribe(SubscriptionOptions { capacity: 64 }).unwrap();
        pool.fail(Some("apply"));
        server.push(
            0,
            GatewayPush::Sync {
                event: Box::new(event("atomic", 1)),
                ack_required: true,
            },
        );
        loop {
            let event = subscription.next(call()).await.unwrap().unwrap();
            match event.kind {
                EventKind::MessageChanged(_) => panic!("提交失败前发布了消息"),
                EventKind::Error(e) => {
                    assert_eq!(e.kind, ErrorKind::Storage);
                    break;
                }
                _ => {}
            }
        }
        assert!(server.acks.lock().unwrap().is_empty());
        assert_eq!(sdk.get_sync_status().unwrap().applied_user_seq, 0);
        assert!(sdk.get_messages(query()).unwrap().is_empty());
        pool.fail(Some("flush"));
        assert_eq!(
            sdk.flush_storage(call()).await.unwrap_err().kind,
            ErrorKind::Storage
        );
        pool.fail(None);
        server.push(
            0,
            GatewayPush::Sync {
                event: Box::new(event("atomic", 1)),
                ack_required: true,
            },
        );
        loop {
            let event = subscription.next(call()).await.unwrap().unwrap();
            if let EventKind::MessageChanged(m) = event.kind {
                assert_eq!(m.client_msg_id, "atomic");
                break;
            }
        }
        sdk.flush_storage(call()).await.unwrap();
        assert_eq!(sdk.get_messages(query()).unwrap().len(), 1);
        assert_eq!(sdk.get_sync_status().unwrap().applied_user_seq, 1);
        assert_eq!(*server.acks.lock().unwrap(), [1]);
        sdk.shutdown(call()).await.unwrap();
    }
}

#[tokio::test]
async fn default_sqlite_reopens_real_platform_directory_and_cleans_only_its_unique_account() {
    let app_id = format!(
        "rust-sdk-default-test-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    );
    let namespace = StorageNamespace {
        service_url: "https://example.test/prefix".into(),
        app_id: app_id.clone(),
        user_id: "user".into(),
    };
    let factory = Arc::new(SqliteStoreFactory::new());
    let path = factory.database_path(&namespace).unwrap();
    assert!(!path.exists());
    let server = Arc::new(Server::default());
    let options = ClientOptions::new(&namespace.service_url, &app_id);
    assert!(options.storage.is_none());
    let sdk =
        PhantomClient::with_host(options.clone(), support::host(&server, factory.clone())).unwrap();
    assert!(!path.exists());
    ready(&sdk).await;
    sdk.send_message(input("default-persistent", "默认目录验证"), call())
        .await
        .unwrap();
    sdk.flush_storage(call()).await.unwrap();
    sdk.shutdown(call()).await.unwrap();
    assert!(path.is_file());
    let raw = rusqlite::Connection::open(&path).unwrap();
    let count: i64 = raw
        .query_row(
            "SELECT count(*) FROM messages WHERE client_msg_id='default-persistent'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(count, 1);
    drop(raw);
    let reopened = PhantomClient::with_host(
        options,
        support::host(&server, Arc::new(SqliteStoreFactory::new())),
    )
    .unwrap();
    ready(&reopened).await;
    assert_eq!(reopened.get_messages(query()).unwrap().len(), 1);
    reopened.shutdown(call()).await.unwrap();
    // 只删除本次唯一 app_id 的账号散列目录，保留默认根目录和其他账号。
    std::fs::remove_dir_all(path.parent().unwrap()).unwrap();
}
