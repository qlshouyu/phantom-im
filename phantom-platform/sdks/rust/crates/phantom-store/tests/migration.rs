mod support;
use phantom_domain::*;
use phantom_ports::*;
use phantom_store::*;
use support::*;
fn legacy(factory: &SqliteStoreFactory, ns: &StorageNamespace, snapshot: &StorageSnapshot) {
    let path = factory.database_path(ns).unwrap();
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    let conn = rusqlite::Connection::open(path).unwrap();
    conn.execute_batch("CREATE TABLE legacy_snapshot(namespace TEXT NOT NULL,snapshot TEXT NOT NULL);PRAGMA user_version=0;").unwrap();
    conn.execute(
        "INSERT INTO legacy_snapshot VALUES(?1,?2)",
        rusqlite::params![
            serde_json::to_string(ns).unwrap(),
            serde_json::to_string(snapshot).unwrap()
        ],
    )
    .unwrap();
}
#[tokio::test]
async fn known_fixture_schema_upgrades_atomically_with_typed_data_preserved() {
    let dir = tempfile::tempdir().unwrap();
    let factory = SqliteStoreFactory::directory(dir.path().to_path_buf()).unwrap();
    let ns = namespace("user");
    let snapshot = StorageSnapshot::default()
        .applying(batch(0, "legacy"))
        .unwrap();
    legacy(&factory, &ns, &snapshot);
    let store = factory.open(ns.clone()).await.unwrap();
    assert_eq!(store.load().await.unwrap(), snapshot);
    store.close().await.unwrap();
    let conn = rusqlite::Connection::open(factory.database_path(&ns).unwrap()).unwrap();
    let version: i32 = conn
        .query_row("PRAGMA user_version", [], |r| r.get(0))
        .unwrap();
    assert_eq!(version, 1);
    let legacy: i64 = conn
        .query_row(
            "SELECT count(*) FROM sqlite_master WHERE name='legacy_snapshot'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(legacy, 0);
}
#[tokio::test]
async fn migration_write_failure_rolls_back_schema_and_preserves_original_snapshot() {
    let dir = tempfile::tempdir().unwrap();
    let factory = SqliteStoreFactory::directory(dir.path().to_path_buf()).unwrap();
    let ns = namespace("user");
    let mut snapshot = StorageSnapshot::default();
    let one = message("one");
    let mut two = one.clone();
    two.client_msg_id = "two".into();
    snapshot.messages = vec![one, two];
    legacy(&factory, &ns, &snapshot);
    assert_eq!(
        factory.open(ns.clone()).await.err().unwrap().kind,
        ErrorKind::Storage
    );
    let conn = rusqlite::Connection::open(factory.database_path(&ns).unwrap()).unwrap();
    let value: String = conn
        .query_row("SELECT snapshot FROM legacy_snapshot", [], |r| r.get(0))
        .unwrap();
    assert_eq!(
        serde_json::from_str::<StorageSnapshot>(&value).unwrap(),
        snapshot
    );
    let tables: i64 = conn
        .query_row(
            "SELECT count(*) FROM sqlite_master WHERE name='messages'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(tables, 0);
    let version: i32 = conn
        .query_row("PRAGMA user_version", [], |r| r.get(0))
        .unwrap();
    assert_eq!(version, 0);
}
#[tokio::test]
async fn future_schema_and_corrupt_database_are_rejected_without_overwriting_files() {
    for corrupt in [false, true] {
        let dir = tempfile::tempdir().unwrap();
        let factory = SqliteStoreFactory::directory(dir.path().to_path_buf()).unwrap();
        let ns = namespace("user");
        let path = factory.database_path(&ns).unwrap();
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        if corrupt {
            std::fs::write(&path, b"not a SQLite database").unwrap();
        } else {
            let conn = rusqlite::Connection::open(&path).unwrap();
            conn.execute_batch("PRAGMA user_version=99;CREATE TABLE future(value TEXT);INSERT INTO future VALUES('preserve');").unwrap();
        }
        let before = std::fs::read(&path).unwrap();
        assert_eq!(
            factory.open(ns).await.err().unwrap().kind,
            ErrorKind::Storage
        );
        assert_eq!(std::fs::read(path).unwrap(), before);
    }
}

#[tokio::test]
async fn ambiguous_legacy_rows_are_preserved_instead_of_discarding_accounts() {
    let dir = tempfile::tempdir().unwrap();
    let factory = SqliteStoreFactory::directory(dir.path().to_path_buf()).unwrap();
    let ns = namespace("user");
    legacy(&factory, &ns, &StorageSnapshot::default());
    let path = factory.database_path(&ns).unwrap();
    let raw = rusqlite::Connection::open(&path).unwrap();
    raw.execute_batch("INSERT INTO legacy_snapshot SELECT * FROM legacy_snapshot")
        .unwrap();
    drop(raw);
    let before = std::fs::read(&path).unwrap();
    assert_eq!(
        factory.open(ns).await.err().unwrap().kind,
        ErrorKind::Storage
    );
    assert_eq!(std::fs::read(path).unwrap(), before);
}
