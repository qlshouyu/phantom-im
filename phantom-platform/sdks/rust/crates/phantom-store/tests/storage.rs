mod support;
use phantom_domain::*;
use phantom_ports::*;
use phantom_store::*;
use std::sync::Arc;
use support::*;

#[tokio::test]
async fn memory_and_sqlite_share_atomic_account_storage_contract() {
    contract(Arc::new(MemoryStoreFactory::new())).await;
    let dir = tempfile::tempdir().unwrap();
    contract(Arc::new(
        SqliteStoreFactory::directory(dir.path().to_path_buf()).unwrap(),
    ))
    .await;
}
#[tokio::test]
async fn persistent_cache_reopens_with_new_factory_and_has_real_sql_indexes() {
    let dir = tempfile::tempdir().unwrap();
    let factory = SqliteStoreFactory::directory(dir.path().to_path_buf()).unwrap();
    let ns = namespace("user");
    let store = factory.open(ns.clone()).await.unwrap();
    store.apply(batch(0, "stable")).await.unwrap();
    store.flush().await.unwrap();
    store.close().await.unwrap();
    let path = factory.database_path(&ns).unwrap();
    let raw = rusqlite::Connection::open(&path).unwrap();
    let (id, seq): (String, i64) = raw
        .query_row("SELECT client_msg_id,conv_seq FROM messages", [], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })
        .unwrap();
    assert_eq!(id, "stable");
    assert_eq!(seq, 9_007_199_254_740_993);
    let indexes:i64=raw.query_row("SELECT count(*) FROM sqlite_master WHERE type='index' AND name='messages_conversation_sequence'",[],|r|r.get(0)).unwrap();
    assert_eq!(indexes, 1);
    drop(raw);
    let new = SqliteStoreFactory::directory(dir.path().to_path_buf())
        .unwrap()
        .open(ns)
        .await
        .unwrap();
    assert_eq!(
        new.load().await.unwrap().messages[0].client_msg_id,
        "stable"
    );
    new.close().await.unwrap();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(
            std::fs::metadata(path).unwrap().permissions().mode() & 0o777,
            0o600
        );
    }
}
#[tokio::test]
async fn namespace_digest_isolates_environments_apps_and_opaque_identifiers() {
    let dir = tempfile::tempdir().unwrap();
    let factory = SqliteStoreFactory::directory(dir.path().to_path_buf()).unwrap();
    let a = namespace("../a/b?中文");
    let mut b = a.clone();
    b.service_url = "https://other.test/prefix".into();
    let mut c = a.clone();
    c.app_id = "other".into();
    assert_ne!(
        factory.database_path(&a).unwrap(),
        factory.database_path(&b).unwrap()
    );
    assert_ne!(
        factory.database_path(&a).unwrap(),
        factory.database_path(&c).unwrap()
    );
    let one = factory.open(a.clone()).await.unwrap();
    one.apply(batch(0, "one")).await.unwrap();
    for ns in [b, c] {
        let other = factory.open(ns).await.unwrap();
        assert!(other.load().await.unwrap().messages.is_empty());
        other.close().await.unwrap();
    }
    let duplicate = SqliteStoreFactory::directory(dir.path().to_path_buf()).unwrap();
    assert_eq!(
        duplicate.open(a.clone()).await.err().unwrap().kind,
        ErrorKind::Conflict
    );
    one.close().await.unwrap();
    duplicate.open(a).await.unwrap().close().await.unwrap();
}
#[tokio::test]
async fn failures_at_sql_write_stages_rollback_projection_outbox_inbox_and_cursor() {
    for table in ["messages", "outbox", "inbox", "meta"] {
        let dir = tempfile::tempdir().unwrap();
        let factory = SqliteStoreFactory::directory(dir.path().to_path_buf()).unwrap();
        let ns = namespace("user");
        let store = factory.open(ns.clone()).await.unwrap();
        let raw = rusqlite::Connection::open(factory.database_path(&ns).unwrap()).unwrap();
        let operation = if table == "meta" { "UPDATE" } else { "INSERT" };
        raw.execute_batch(&format!("CREATE TRIGGER injected BEFORE {operation} ON {table} BEGIN SELECT RAISE(ABORT,'injected'); END;")).unwrap();
        assert_eq!(
            store.apply(batch(0, "failure")).await.unwrap_err().kind,
            ErrorKind::Storage
        );
        assert_eq!(store.load().await.unwrap(), StorageSnapshot::default());
        for affected in ["messages", "outbox", "inbox"] {
            let count: i64 = raw
                .query_row(&format!("SELECT count(*) FROM {affected}"), [], |r| {
                    r.get(0)
                })
                .unwrap();
            assert_eq!(count, 0, "{table} 失败后残留 {affected}");
        }
        let seq: i64 = raw
            .query_row("SELECT user_seq FROM meta", [], |r| r.get(0))
            .unwrap();
        assert_eq!(seq, 0);
        drop(raw);
        store.close().await.unwrap();
    }
}
#[tokio::test]
async fn flush_reports_busy_barrier_without_losing_already_committed_data() {
    let dir = tempfile::tempdir().unwrap();
    let factory = SqliteStoreFactory::directory(dir.path().to_path_buf()).unwrap();
    let ns = namespace("user");
    let store = factory.open(ns.clone()).await.unwrap();
    store.apply(batch(0, "first")).await.unwrap();
    let reader = rusqlite::Connection::open(factory.database_path(&ns).unwrap()).unwrap();
    reader.execute_batch("BEGIN;").unwrap();
    let _: i64 = reader
        .query_row("SELECT count(*) FROM messages", [], |r| r.get(0))
        .unwrap();
    store
        .apply(StorageChangeSet {
            expected_revision: 1,
            mutations: vec![StorageMutation::PutMessage(message("second"))],
        })
        .await
        .unwrap();
    assert_eq!(store.flush().await.unwrap_err().kind, ErrorKind::Storage);
    reader.execute_batch("ROLLBACK").unwrap();
    drop(reader);
    store.flush().await.unwrap();
    store.close().await.unwrap();
}
#[tokio::test]
async fn invalid_directory_reports_storage_error_instead_of_using_another_backend() {
    let file = tempfile::NamedTempFile::new().unwrap();
    let factory = SqliteStoreFactory::directory(file.path().to_path_buf()).unwrap();
    assert_eq!(
        factory.open(namespace("user")).await.err().unwrap().kind,
        ErrorKind::Storage
    );
}
