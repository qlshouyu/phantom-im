use super::sql_records as records;
use phantom_domain::*;
use phantom_ports::*;
use rusqlite::{Connection, params};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::Duration,
};
use tokio::sync::oneshot;

pub(crate) fn storage(message: &str) -> SdkError {
    SdkError::new(ErrorKind::Storage, message)
}
pub(crate) fn db<T>(result: rusqlite::Result<T>) -> SdkResult<T> {
    result.map_err(|error| match error {
        rusqlite::Error::SqliteFailure(code, _) => storage(&format!(
            "SQLite 操作失败（错误码 {}）；原账号数据予以保留",
            code.extended_code
        )),
        rusqlite::Error::InvalidColumnType(index, name, kind) => storage(&format!(
            "SQLite 返回字段类型不兼容（列 {index}: {name}, {kind:?}）"
        )),
        rusqlite::Error::QueryReturnedNoRows => storage("SQLite 缺少必需的账号记录"),
        _ => storage("SQLite 操作失败；原账号数据予以保留"),
    })
}
fn capabilities() -> StorageCapabilities {
    StorageCapabilities {
        contract_version: STORAGE_CONTRACT_VERSION,
        durability: StorageDurability::Persistent,
        atomic_changes: true,
        exclusive_writer: true,
    }
}
#[derive(Clone, Default)]
pub struct SqliteStoreFactory {
    root: Option<PathBuf>,
}
pub struct SqliteStore {
    namespace: StorageNamespace,
    sender: mpsc::Sender<Work>,
    closed: AtomicBool,
}
struct State {
    connection: Option<Connection>,
    lease: Option<File>,
    snapshot: StorageSnapshot,
}
type Job = Box<dyn FnOnce(&mut State) + Send>;
enum Work {
    Job(Job),
    Close(oneshot::Sender<SdkResult<()>>),
}

impl SqliteStoreFactory {
    /// 不创建目录或数据库；登录 open 时才解析默认目录并进行 IO。
    pub fn new() -> Self {
        Self::default()
    }
    pub fn directory(root: PathBuf) -> SdkResult<Self> {
        if root.as_os_str().is_empty() {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "SQLite 目录不能为空",
            ));
        }
        Ok(Self { root: Some(root) })
    }
    pub fn root_directory(&self) -> SdkResult<PathBuf> {
        self.root.clone().map(Ok).unwrap_or_else(|| {
            default_root(
                directories::BaseDirs::new().map(|dirs| dirs.data_local_dir().to_path_buf()),
            )
        })
    }
    pub fn database_path(&self, namespace: &StorageNamespace) -> SdkResult<PathBuf> {
        namespace.validate()?;
        let hash = Sha256::digest(records::json(namespace)?.as_bytes());
        let digest: String = hash.iter().map(|b| format!("{b:02x}")).collect();
        Ok(self.root_directory()?.join(digest).join("account.sqlite3"))
    }
}
fn default_root(data: Option<PathBuf>) -> SdkResult<PathBuf> {
    data.map(|p| p.join("phantom").join("sdk"))
        .ok_or_else(|| storage("无法解析平台用户应用数据目录；请显式配置存储目录"))
}
fn private_directory(path: &Path) -> SdkResult<()> {
    let mut builder = fs::DirBuilder::new();
    builder.recursive(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder
        .create(path)
        .map_err(|_| storage("无法创建账号存储目录"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))
            .map_err(|_| storage("无法设置账号目录权限"))?;
    }
    Ok(())
}
fn acquire(path: &Path) -> SdkResult<File> {
    let mut options = OpenOptions::new();
    options.create(true).read(true).write(true).truncate(false);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let file = options
        .open(path)
        .map_err(|_| storage("无法打开账号写入租约"))?;
    match file.try_lock() {
        Ok(()) => Ok(file),
        Err(fs::TryLockError::WouldBlock) => Err(SdkError::new(
            ErrorKind::Conflict,
            "同一账号缓存已有写入实例",
        )),
        Err(fs::TryLockError::Error(_)) => Err(storage("无法取得账号写入租约")),
    }
}
fn initialize(conn: &mut Connection, namespace: &StorageNamespace) -> SdkResult<()> {
    let version: i64 = db(conn.query_row("PRAGMA user_version", [], |row| row.get(0)))?;
    if !(0..=1).contains(&version) {
        return Err(storage("SQLite schema 版本过新或不兼容"));
    }
    let integrity: String = db(conn.query_row("PRAGMA quick_check", [], |row| row.get(0)))?;
    if integrity != "ok" {
        return Err(storage("SQLite 缓存损坏"));
    }
    let mut statement = db(conn.prepare(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
    ))?;
    let tables = db(statement.query_map([], |row| row.get::<_, String>(0)))?
        .collect::<rusqlite::Result<Vec<_>>>();
    let tables = db(tables)?;
    drop(statement);
    if version == 0 {
        let snapshot = if tables.is_empty() {
            StorageSnapshot::default()
        } else if tables == ["legacy_snapshot"] {
            let count: i64 =
                db(conn.query_row("SELECT count(*) FROM legacy_snapshot", [], |r| r.get(0)))?;
            if count != 1 {
                return Err(storage("旧缓存必须且只能包含一个账号快照"));
            }
            let (stored, data) = db(conn.query_row(
                "SELECT namespace,snapshot FROM legacy_snapshot",
                [],
                |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)),
            ))?;
            let stored: StorageNamespace =
                serde_json::from_str(&stored).map_err(|_| storage("旧缓存命名空间损坏"))?;
            if stored != *namespace {
                return Err(storage("旧缓存属于其他命名空间"));
            }
            serde_json::from_str(&data).map_err(|_| storage("旧缓存快照损坏"))?
        } else {
            return Err(storage("未知旧缓存结构，拒绝隐式重建"));
        };
        let tx = db(conn.transaction())?;
        records::create_schema(&tx)?;
        db(tx.execute(
            "INSERT INTO meta(singleton,namespace,revision,user_seq) VALUES(1,?1,?2,?3)",
            params![
                records::json(namespace)?,
                snapshot.revision.to_string(),
                snapshot.user_seq
            ],
        ))?;
        records::write_snapshot(&tx, &snapshot)?;
        if !tables.is_empty() {
            db(tx.execute_batch("DROP TABLE legacy_snapshot"))?;
        }
        db(tx.execute_batch("PRAGMA user_version=1"))?;
        db(tx.commit())?;
    }
    let stored: String = db(conn.query_row(
        "SELECT namespace FROM meta WHERE singleton=1",
        [],
        |r| r.get(0),
    ))?;
    let stored: StorageNamespace =
        serde_json::from_str(&stored).map_err(|_| storage("缓存命名空间元信息损坏"))?;
    if stored != *namespace {
        return Err(storage("缓存命名空间与账号不一致"));
    }
    Ok(())
}
impl State {
    fn open(path: PathBuf, namespace: &StorageNamespace) -> SdkResult<Self> {
        let directory = path.parent().ok_or_else(|| storage("账号数据库路径无效"))?;
        private_directory(directory)?;
        let lease = acquire(&directory.join("writer.lock"))?;
        let mut connection = db(Connection::open(&path))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            // WAL/SHM 继承主库权限，必须先收紧主库再启用 WAL。
            fs::set_permissions(&path, fs::Permissions::from_mode(0o600))
                .map_err(|_| storage("无法设置账号数据库权限"))?;
        }
        initialize(&mut connection, namespace)?;
        let mode: String =
            db(connection.query_row("PRAGMA journal_mode=WAL", [], |row| row.get(0)))?;
        if mode != "wal" {
            return Err(storage("SQLite 无法启用 WAL 日志"));
        }
        db(connection.execute_batch(
            "PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON; PRAGMA secure_delete=ON;",
        ))?;
        db(connection.busy_timeout(Duration::ZERO))?;
        // WAL 切换后重新读取最终连接状态，结束首次 schema 读取事务后才能执行检查点。
        let snapshot = records::load(&connection)?;
        Ok(Self {
            connection: Some(connection),
            lease: Some(lease),
            snapshot,
        })
    }
    fn conn(&self) -> SdkResult<&Connection> {
        self.connection
            .as_ref()
            .ok_or_else(|| storage("SQLite 句柄已关闭"))
    }
    fn apply(&mut self, changes: StorageChangeSet) -> SdkResult<StorageCommit> {
        let next = self.snapshot.applying(changes.clone()).map_err(|mut e| {
            if e.kind != ErrorKind::Conflict {
                e.kind = ErrorKind::Storage;
            }
            e
        })?;
        let connection = self
            .connection
            .as_mut()
            .ok_or_else(|| storage("SQLite 句柄已关闭"))?;
        let tx = db(connection.transaction())?;
        records::mutations(&tx, &changes.mutations)?;
        let updated = db(tx.execute(
            "UPDATE meta SET revision=?1,user_seq=?2 WHERE singleton=1 AND revision=?3",
            params![
                next.revision.to_string(),
                next.user_seq,
                self.snapshot.revision.to_string()
            ],
        ))?;
        if updated != 1 {
            return Err(SdkError::new(
                ErrorKind::Conflict,
                "账号数据库 revision 已改变",
            ));
        }
        db(tx.commit())?;
        let commit = StorageCommit {
            revision: next.revision,
            user_seq: next.user_seq,
        };
        self.snapshot = next;
        Ok(commit)
    }
    fn flush(&self) -> SdkResult<()> {
        let (busy, _, _): (i64, i64, i64) =
            db(self
                .conn()?
                .query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |r| {
                    Ok((r.get(0)?, r.get(1)?, r.get(2)?))
                }))?;
        if busy != 0 {
            return Err(storage("SQLite flush 屏障未完成"));
        }
        Ok(())
    }
    fn clear(&mut self) -> SdkResult<()> {
        let revision = self
            .snapshot
            .revision
            .checked_add(1)
            .ok_or_else(|| storage("存储版本计数耗尽"))?;
        let conn = self
            .connection
            .as_mut()
            .ok_or_else(|| storage("SQLite 句柄已关闭"))?;
        let tx = db(conn.transaction())?;
        records::clear(&tx)?;
        db(tx.execute(
            "UPDATE meta SET revision=?1,user_seq=0 WHERE singleton=1",
            [revision.to_string()],
        ))?;
        db(tx.commit())?;
        self.snapshot = StorageSnapshot {
            revision,
            ..StorageSnapshot::default()
        };
        Ok(())
    }
    fn close(&mut self) -> SdkResult<()> {
        let result = self.flush();
        let close = self
            .connection
            .take()
            .map(|c| c.close().map_err(|_| storage("SQLite 关闭失败")))
            .unwrap_or(Ok(()));
        self.lease.take();
        result.and(close)
    }
}
impl StoreFactory for SqliteStoreFactory {
    fn capabilities(&self) -> StorageCapabilities {
        capabilities()
    }
    fn open(&self, namespace: StorageNamespace) -> SdkFuture<'_, Arc<dyn Store>> {
        Box::pin(async move {
            let path = self.database_path(&namespace)?;
            let (sender, receiver) = mpsc::channel();
            let (opened, ready) = oneshot::channel();
            let account = namespace.clone();
            std::thread::Builder::new()
                .name("phantom-store".into())
                .spawn(move || {
                    let mut state = match State::open(path, &account) {
                        Ok(state) => state,
                        Err(error) => {
                            let _ = opened.send(Err(error));
                            return;
                        }
                    };
                    if opened.send(Ok(())).is_err() {
                        return;
                    }
                    while let Ok(work) = receiver.recv() {
                        match work {
                            Work::Job(job) => job(&mut state),
                            Work::Close(done) => {
                                let _ = done.send(state.close());
                                break;
                            }
                        }
                    }
                })
                .map_err(|_| storage("无法创建 SQLite 执行队列"))?;
            ready
                .await
                .map_err(|_| storage("SQLite 打开任务提前结束"))??;
            Ok(Arc::new(SqliteStore {
                namespace,
                sender,
                closed: AtomicBool::new(false),
            }) as Arc<dyn Store>)
        })
    }
}
impl SqliteStore {
    fn dispatch<T: Send + 'static>(
        &self,
        operation: impl FnOnce(&mut State) -> SdkResult<T> + Send + 'static,
    ) -> SdkFuture<'_, T> {
        Box::pin(async move {
            if self.closed.load(Ordering::Acquire) {
                return Err(storage("SQLite 句柄已关闭"));
            }
            let (done, ready) = oneshot::channel();
            self.sender
                .send(Work::Job(Box::new(move |state| {
                    let _ = done.send(operation(state));
                })))
                .map_err(|_| storage("SQLite 执行队列已关闭"))?;
            ready.await.map_err(|_| storage("SQLite 任务提前结束"))?
        })
    }
}
impl Store for SqliteStore {
    fn namespace(&self) -> &StorageNamespace {
        &self.namespace
    }
    fn capabilities(&self) -> StorageCapabilities {
        capabilities()
    }
    fn load(&self) -> SdkFuture<'_, StorageSnapshot> {
        self.dispatch(|state| Ok(state.snapshot.clone()))
    }
    fn query_messages(&self, query: MessageCacheQuery) -> SdkFuture<'_, Vec<ChatMessage>> {
        self.dispatch(move |state| records::query_messages(state.conn()?, query))
    }
    fn get_conversation(&self, id: String) -> SdkFuture<'_, Option<Conversation>> {
        self.dispatch(move |state| records::conversation(state.conn()?, &id))
    }
    fn get_projection(&self, key: ProjectionKey) -> SdkFuture<'_, Option<DomainProjection>> {
        self.dispatch(move |state| records::get_projection(state.conn()?, &key))
    }
    fn apply(&self, changes: StorageChangeSet) -> SdkFuture<'_, StorageCommit> {
        self.dispatch(move |state| state.apply(changes))
    }
    fn flush(&self) -> SdkFuture<'_, ()> {
        self.dispatch(|state| state.flush())
    }
    fn clear(&self) -> SdkFuture<'_, ()> {
        self.dispatch(|state| state.clear())
    }
    fn close(&self) -> SdkFuture<'_, ()> {
        Box::pin(async move {
            if self.closed.swap(true, Ordering::AcqRel) {
                return Ok(());
            }
            let (done, ready) = oneshot::channel();
            self.sender
                .send(Work::Close(done))
                .map_err(|_| storage("SQLite 关闭队列已结束"))?;
            ready
                .await
                .map_err(|_| storage("SQLite 关闭任务提前结束"))?
        })
    }
}
impl Drop for SqliteStore {
    fn drop(&mut self) {
        if !self.closed.swap(true, Ordering::AcqRel) {
            let (done, _) = oneshot::channel();
            let _ = self.sender.send(Work::Close(done));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn default_directory_requires_a_platform_data_root() {
        assert!(default_root(None).is_err());
        assert_eq!(
            default_root(Some(PathBuf::from("/fixture"))).unwrap(),
            PathBuf::from("/fixture/phantom/sdk")
        );
    }
}
