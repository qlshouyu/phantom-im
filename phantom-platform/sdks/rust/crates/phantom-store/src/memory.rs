use phantom_domain::*;
use phantom_ports::*;
use std::{
    collections::BTreeMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
};

#[derive(Default)]
struct Account {
    snapshot: StorageSnapshot,
    leased: bool,
}
type Accounts = Mutex<BTreeMap<StorageNamespace, Arc<Mutex<Account>>>>;
#[derive(Clone, Default)]
pub struct MemoryStoreFactory {
    accounts: Arc<Accounts>,
}
pub struct MemoryStore {
    namespace: StorageNamespace,
    account: Arc<Mutex<Account>>,
    closed: AtomicBool,
}
fn capabilities() -> StorageCapabilities {
    StorageCapabilities {
        contract_version: STORAGE_CONTRACT_VERSION,
        durability: StorageDurability::Volatile,
        atomic_changes: true,
        exclusive_writer: true,
    }
}
impl MemoryStoreFactory {
    pub fn new() -> Self {
        Self::default()
    }
}
impl StoreFactory for MemoryStoreFactory {
    fn capabilities(&self) -> StorageCapabilities {
        capabilities()
    }
    fn open(&self, namespace: StorageNamespace) -> SdkFuture<'_, Arc<dyn Store>> {
        Box::pin(async move {
            namespace.validate()?;
            let account = self
                .accounts
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .entry(namespace.clone())
                .or_default()
                .clone();
            {
                let mut state = account.lock().unwrap_or_else(|e| e.into_inner());
                if state.leased {
                    return Err(SdkError::new(ErrorKind::Conflict, "账号存储已有写入实例"));
                }
                state.leased = true;
            }
            Ok(Arc::new(MemoryStore {
                namespace,
                account,
                closed: AtomicBool::new(false),
            }) as Arc<dyn Store>)
        })
    }
}
impl MemoryStore {
    fn check(&self) -> SdkResult<()> {
        if self.closed.load(Ordering::Acquire) {
            Err(SdkError::new(ErrorKind::Storage, "存储句柄已关闭"))
        } else {
            Ok(())
        }
    }
    fn release(&self) {
        let mut state = self.account.lock().unwrap_or_else(|e| e.into_inner());
        if !self.closed.swap(true, Ordering::AcqRel) {
            state.leased = false;
        }
    }
}
impl Store for MemoryStore {
    fn namespace(&self) -> &StorageNamespace {
        &self.namespace
    }
    fn capabilities(&self) -> StorageCapabilities {
        capabilities()
    }
    fn load(&self) -> SdkFuture<'_, StorageSnapshot> {
        Box::pin(async move {
            let state = self.account.lock().unwrap_or_else(|e| e.into_inner());
            self.check()?;
            Ok(state.snapshot.clone())
        })
    }
    fn query_messages(&self, query: MessageCacheQuery) -> SdkFuture<'_, Vec<ChatMessage>> {
        Box::pin(async move {
            let state = self.account.lock().unwrap_or_else(|e| e.into_inner());
            self.check()?;
            state.snapshot.query_messages(&query)
        })
    }
    fn get_conversation(&self, id: String) -> SdkFuture<'_, Option<Conversation>> {
        Box::pin(async move {
            let state = self.account.lock().unwrap_or_else(|e| e.into_inner());
            self.check()?;
            Ok(state
                .snapshot
                .conversations
                .iter()
                .find(|v| v.conv_id == id)
                .cloned())
        })
    }
    fn get_projection(&self, key: ProjectionKey) -> SdkFuture<'_, Option<DomainProjection>> {
        Box::pin(async move {
            let state = self.account.lock().unwrap_or_else(|e| e.into_inner());
            self.check()?;
            if state.snapshot.invalidated.contains(&key) {
                return Ok(None);
            }
            Ok(state
                .snapshot
                .projections
                .iter()
                .find(|v| v.key() == key)
                .cloned())
        })
    }
    fn apply(&self, changes: StorageChangeSet) -> SdkFuture<'_, StorageCommit> {
        Box::pin(async move {
            let mut state = self.account.lock().unwrap_or_else(|e| e.into_inner());
            self.check()?;
            let next = state.snapshot.applying(changes).map_err(|mut e| {
                if e.kind != ErrorKind::Conflict {
                    e.kind = ErrorKind::Storage;
                }
                e
            })?;
            let result = StorageCommit {
                revision: next.revision,
                user_seq: next.user_seq,
            };
            state.snapshot = next;
            Ok(result)
        })
    }
    fn flush(&self) -> SdkFuture<'_, ()> {
        Box::pin(async move { self.check() })
    }
    fn clear(&self) -> SdkFuture<'_, ()> {
        Box::pin(async move {
            let mut state = self.account.lock().unwrap_or_else(|e| e.into_inner());
            self.check()?;
            let revision = state
                .snapshot
                .revision
                .checked_add(1)
                .ok_or_else(|| SdkError::new(ErrorKind::Storage, "存储版本计数耗尽"))?;
            state.snapshot = StorageSnapshot {
                revision,
                ..StorageSnapshot::default()
            };
            Ok(())
        })
    }
    fn close(&self) -> SdkFuture<'_, ()> {
        Box::pin(async move {
            self.release();
            Ok(())
        })
    }
}
impl Drop for MemoryStore {
    fn drop(&mut self) {
        self.release();
    }
}
