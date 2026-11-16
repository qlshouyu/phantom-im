mod support;
use phantom_sdk::*;
use std::sync::{Arc, atomic::Ordering};
use support::*;
#[tokio::test]
async fn facade_authenticates_then_synchronizes_and_merges_send_history_and_deletion() {
    let server = Arc::new(Server::default());
    let store = Arc::new(MemoryStoreFactory::new());
    let client = client(&server, store);
    let events = client
        .subscribe(SubscriptionOptions { capacity: 64 })
        .unwrap();
    assert_eq!(client.get_state().unwrap(), ConnectionState::Idle);
    ready(&client).await;
    assert_eq!(client.get_state().unwrap(), ConnectionState::Online);
    assert_eq!(
        client
            .login(login("user"), CallOptions::default())
            .await
            .unwrap_err()
            .kind,
        ErrorKind::Conflict
    );
    let sent = client
        .send_message(input("stable", "你好"), CallOptions::default())
        .await
        .unwrap();
    assert_eq!(sent.state, MessageState::Sent);
    let repeated = client
        .send_message(input("stable", "你好"), CallOptions::default())
        .await
        .unwrap();
    assert_eq!(repeated.msg_id, sent.msg_id);
    assert_eq!(
        server
            .requests
            .lock()
            .unwrap()
            .iter()
            .filter(|r| *r == "send")
            .count(),
        1
    );
    let history = client
        .history(
            HistoryQuery {
                conv_id: "gr_group".into(),
                before_conv_seq: None,
                limit: 50,
            },
            CallOptions::default(),
        )
        .await
        .unwrap();
    assert_eq!(history.items.len(), 1);
    client
        .delete_local(sent.msg_id.clone().unwrap(), CallOptions::default())
        .await
        .unwrap();
    assert!(
        client
            .get_messages(MessageCacheQuery {
                conv_id: "gr_group".into(),
                before_conv_seq: None,
                limit: 50
            })
            .unwrap()
            .is_empty()
    );
    client
        .logout(LogoutOptions { clear_cache: false }, CallOptions::default())
        .await
        .unwrap();
    assert_eq!(client.get_current_user_id().unwrap(), None);
    let cancel = CancellationToken::new();
    cancel.cancel();
    assert_eq!(
        client
            .shutdown(CallOptions {
                timeout_ms: None,
                cancellation: Some(cancel)
            })
            .await
            .unwrap_err()
            .kind,
        ErrorKind::Cancelled
    );
    assert_eq!(client.get_state().unwrap(), ConnectionState::Released);
    assert_eq!(
        client.get_sync_status().unwrap_err().kind,
        ErrorKind::Released
    );
    assert_eq!(
        client
            .list_friends(CallOptions::default())
            .await
            .unwrap_err()
            .kind,
        ErrorKind::Released
    );
    client.shutdown(CallOptions::default()).await.unwrap();
    assert_eq!(events.next(CallOptions::default()).await.unwrap(), None);
}
#[tokio::test]
async fn simultaneous_login_is_busy_and_cancelled_login_returns_to_closed() {
    let server = Arc::new(Server::default());
    server.block_auth.store(true, Ordering::Release);
    let client = client(&server, Arc::new(MemoryStoreFactory::new()));
    let cancel = CancellationToken::new();
    let token = cancel.clone();
    let copy = client.clone();
    let first = tokio::spawn(async move {
        copy.login(
            login("user"),
            CallOptions {
                cancellation: Some(token),
                timeout_ms: Some(1000),
            },
        )
        .await
    });
    while server.requests.lock().unwrap().is_empty() {
        tokio::task::yield_now().await;
    }
    assert_eq!(
        client
            .login(login("user"), CallOptions::default())
            .await
            .unwrap_err()
            .kind,
        ErrorKind::Busy
    );
    cancel.cancel();
    assert_eq!(first.await.unwrap().unwrap_err().kind, ErrorKind::Cancelled);
    client
        .logout(LogoutOptions { clear_cache: false }, CallOptions::default())
        .await
        .unwrap();
    assert_eq!(client.get_state().unwrap(), ConnectionState::Closed);
    assert_eq!(client.get_current_user_id().unwrap(), None);
    client.shutdown(CallOptions::default()).await.unwrap();
}
#[tokio::test]
async fn failed_token_update_keeps_identity_and_valid_credentials() {
    let server = Arc::new(Server::default());
    let client = client(&server, Arc::new(MemoryStoreFactory::new()));
    ready(&client).await;
    assert_eq!(
        client
            .update_token(
                TokenUpdate {
                    user_token: "token-other".into()
                },
                CallOptions::default()
            )
            .await
            .unwrap_err()
            .kind,
        ErrorKind::Authentication
    );
    assert_eq!(
        client.get_current_user_id().unwrap().as_deref(),
        Some("user")
    );
    assert_eq!(client.get_state().unwrap(), ConnectionState::Online);
    client
        .send_message(
            input("after-update-failure", "正文"),
            CallOptions::default(),
        )
        .await
        .unwrap();
    client.shutdown(CallOptions::default()).await.unwrap();
}

#[tokio::test]
async fn connected_precedes_ready_and_logout_cancels_pending_send_before_switching_accounts() {
    let server = Arc::new(Server::default());
    let client = client(&server, Arc::new(MemoryStoreFactory::new()));
    let events = client
        .subscribe(SubscriptionOptions { capacity: 128 })
        .unwrap();
    client
        .login(login("user"), CallOptions::default())
        .await
        .unwrap();
    let mut connected = false;
    loop {
        let event = events.next(CallOptions::default()).await.unwrap().unwrap();
        if matches!(event.kind, EventKind::Connected(_)) {
            connected = true;
        }
        if matches!(event.kind, EventKind::Ready(_)) {
            assert!(connected);
            break;
        }
    }
    server.block_send.store(true, Ordering::Release);
    let sending = {
        let client = client.clone();
        tokio::spawn(async move {
            client
                .send_message(
                    input("old-account-send", "旧账号正文"),
                    CallOptions::default(),
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
    client
        .logout(LogoutOptions { clear_cache: false }, CallOptions::default())
        .await
        .unwrap();
    assert_eq!(
        sending.await.unwrap().unwrap_err().kind,
        ErrorKind::Cancelled
    );
    server.block_send.store(false, Ordering::Release);
    client
        .login(login("other"), CallOptions::default())
        .await
        .unwrap();
    client.wait_ready(CallOptions::default()).await.unwrap();
    assert_eq!(
        client.get_current_user_id().unwrap().as_deref(),
        Some("other")
    );
    assert!(
        client
            .get_messages(MessageCacheQuery {
                conv_id: "gr_group".into(),
                before_conv_seq: None,
                limit: 100
            })
            .unwrap()
            .is_empty()
    );
    let _ = server.pushes.lock().unwrap()[0].send(GatewayPush::Confirmation {
        client_msg_id: "old-account-send".into(),
        result: Ok(MessageConfirmation {
            msg_id: "late".into(),
            conv_seq: 99,
            server_time: 100,
        }),
    });
    client.flush_storage(CallOptions::default()).await.unwrap();
    assert!(
        client
            .get_messages(MessageCacheQuery {
                conv_id: "gr_group".into(),
                before_conv_seq: None,
                limit: 100
            })
            .unwrap()
            .is_empty()
    );
    for token in ["token-user", "token-for-different-app"] {
        assert_eq!(
            client
                .update_token(
                    TokenUpdate {
                        user_token: token.into()
                    },
                    CallOptions::default()
                )
                .await
                .unwrap_err()
                .kind,
            ErrorKind::Authentication
        );
        assert_eq!(
            client.get_current_user_id().unwrap().as_deref(),
            Some("other")
        );
    }
    client.shutdown(CallOptions::default()).await.unwrap();
    client.shutdown(CallOptions::default()).await.unwrap();
    assert_eq!(
        client.mark_message_displayed("".into()).unwrap_err().kind,
        ErrorKind::Released
    );
    assert_eq!(
        client
            .history(
                HistoryQuery {
                    conv_id: "".into(),
                    before_conv_seq: None,
                    limit: 0
                },
                CallOptions::default()
            )
            .await
            .unwrap_err()
            .kind,
        ErrorKind::Released
    );
}

#[tokio::test]
async fn mismatched_authentication_identity_is_rejected_before_opening_account() {
    let server = Arc::new(Server::default());
    server.corrupt_auth.store(true, Ordering::Release);
    let client = client(&server, Arc::new(MemoryStoreFactory::new()));
    let error = client
        .login(login("user"), CallOptions::default())
        .await
        .unwrap_err();
    assert_eq!(error.kind, ErrorKind::Protocol);
    assert_eq!(client.get_current_user_id().unwrap(), None);
    assert_eq!(client.get_state().unwrap(), ConnectionState::Closed);
    client.shutdown(CallOptions::default()).await.unwrap();
}
