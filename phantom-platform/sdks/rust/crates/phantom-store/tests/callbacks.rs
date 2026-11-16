#[path = "support/host.rs"]
mod host;
mod support;
use phantom_domain::*;
use phantom_ports::*;
use phantom_store::*;
use std::sync::Arc;
use support::*;

#[tokio::test]
async fn independent_host_trait_and_callbacks_share_the_builtin_contract() {
    contract(Arc::new(host::HostFactory::default())).await;
    let host = host::HostFactory::default();
    contract(Arc::new(
        CallbackStoreFactory::new(host::callbacks(host)).unwrap(),
    ))
    .await;
}
#[tokio::test]
async fn callbacks_keep_namespace_handle_and_host_pool_ownership() {
    let host = host::HostFactory::default();
    let factory = CallbackStoreFactory::new(host::callbacks(host.clone())).unwrap();
    let a = factory.open(namespace("a")).await.unwrap();
    let b = factory.open(namespace("b")).await.unwrap();
    a.load().await.unwrap();
    a.apply(batch(0, "a")).await.unwrap();
    a.query_messages(MessageCacheQuery {
        conv_id: "gr_g".into(),
        before_conv_seq: None,
        limit: 10,
    })
    .await
    .unwrap();
    a.get_conversation("gr_g".into()).await.unwrap();
    a.get_projection(ProjectionKey::Groups).await.unwrap();
    a.flush().await.unwrap();
    a.clear().await.unwrap();
    a.close().await.unwrap();
    assert!(a.load().await.is_err());
    a.close().await.unwrap();
    b.apply(batch(0, "b")).await.unwrap();
    b.close().await.unwrap();
    // SDK 关闭的只是自己的账号句柄，宿主共享资源仍可供新实例使用。
    host.open(namespace("c"))
        .await
        .unwrap()
        .close()
        .await
        .unwrap();
    let calls: Vec<_> = host
        .calls()
        .into_iter()
        .filter(|(_, ns, _)| ns.user_id == "a")
        .collect();
    assert_eq!(
        calls.iter().map(|v| v.0).collect::<Vec<_>>(),
        [
            "open",
            "load",
            "apply",
            "query_messages",
            "get_conversation",
            "get_projection",
            "flush",
            "clear",
            "close"
        ]
    );
    assert!(
        calls
            .iter()
            .all(|v| v.1 == namespace("a") && v.2 == calls[0].2)
    );
}
#[tokio::test]
async fn callback_failure_never_falls_back_and_commit_is_atomic() {
    let host = host::HostFactory::default();
    let factory = CallbackStoreFactory::new(host::callbacks(host.clone())).unwrap();
    host.fail(Some("open"));
    assert_eq!(
        factory.open(namespace("a")).await.err().unwrap().kind,
        ErrorKind::Storage
    );
    host.fail(None);
    let store = factory.open(namespace("a")).await.unwrap();
    for operation in ["load", "apply", "flush", "clear"] {
        host.fail(Some(operation));
        let error = match operation {
            "load" => store.load().await.unwrap_err(),
            "apply" => store.apply(batch(0, "failed")).await.unwrap_err(),
            "flush" => store.flush().await.unwrap_err(),
            _ => store.clear().await.unwrap_err(),
        };
        assert_eq!(error.kind, ErrorKind::Storage);
        host.fail(None);
        assert_eq!(store.load().await.unwrap(), StorageSnapshot::default());
    }
    store.close().await.unwrap();
}
#[test]
fn callbacks_require_every_operation_and_are_send_sync() {
    for missing in 0..10 {
        let mut callbacks = host::callbacks(host::HostFactory::default());
        match missing {
            0 => callbacks.capabilities = None,
            1 => callbacks.open = None,
            2 => callbacks.load = None,
            3 => callbacks.query_messages = None,
            4 => callbacks.get_conversation = None,
            5 => callbacks.get_projection = None,
            6 => callbacks.apply = None,
            7 => callbacks.flush = None,
            8 => callbacks.clear = None,
            _ => callbacks.close = None,
        }
        assert_eq!(
            CallbackStoreFactory::new(callbacks).err().unwrap().kind,
            ErrorKind::InvalidArgument
        );
    }
    fn send_sync<T: Send + Sync>() {}
    send_sync::<CallbackStoreFactory>();
    send_sync::<StorageCallbacks>();
    fn send<T: Send>(_: T) {}
    let factory = CallbackStoreFactory::new(host::callbacks(host::HostFactory::default())).unwrap();
    send(factory.open(namespace("a")));
}

#[tokio::test]
async fn cancelled_or_concurrent_close_waiters_resume_one_host_close_operation() {
    let host = host::HostFactory::default();
    let mut callbacks = host::callbacks(host.clone());
    let original = callbacks.close.take().unwrap();
    let release = Arc::new(tokio::sync::Notify::new());
    let gate = release.clone();
    let started = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let calls = started.clone();
    callbacks.close = Some(Arc::new(move |ctx| {
        let gate = gate.clone();
        let original = original.clone();
        let calls = calls.clone();
        Box::pin(async move {
            calls.fetch_add(1, std::sync::atomic::Ordering::AcqRel);
            gate.notified().await;
            original(ctx).await
        })
    }));
    let factory = CallbackStoreFactory::new(callbacks).unwrap();
    let store = factory.open(namespace("a")).await.unwrap();
    let handle = store.clone();
    let abandoned = tokio::spawn(async move { handle.close().await });
    while started.load(std::sync::atomic::Ordering::Acquire) == 0 {
        tokio::task::yield_now().await;
    }
    abandoned.abort();
    let _ = abandoned.await;
    let first = store.clone();
    let one = tokio::spawn(async move { first.close().await });
    let second = store.clone();
    let two = tokio::spawn(async move { second.close().await });
    tokio::task::yield_now().await;
    release.notify_one();
    tokio::time::timeout(std::time::Duration::from_secs(1), async {
        one.await.unwrap().unwrap();
        two.await.unwrap().unwrap();
    })
    .await
    .unwrap();
    assert_eq!(started.load(std::sync::atomic::Ordering::Acquire), 1);
    // 只有本句柄的租约被释放，宿主工厂仍正常工作。
    host.open(namespace("a"))
        .await
        .unwrap()
        .close()
        .await
        .unwrap();
}
