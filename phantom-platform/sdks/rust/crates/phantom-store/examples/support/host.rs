//! 独立宿主存储：JSON 字节记录与句柄租约，不依赖内置适配或 SQLite。
use phantom_domain::*;
use phantom_ports::*;
use phantom_store::*;
use std::{
    collections::BTreeMap,
    sync::{Arc, Mutex},
};

#[derive(Default)]
struct Pool {
    data: BTreeMap<StorageNamespace, Vec<u8>>,
    writers: BTreeMap<StorageNamespace, u64>,
    next: u64,
    calls: Vec<(&'static str, StorageNamespace, u64)>,
    fail: Option<&'static str>,
}
#[derive(Clone, Default)]
pub struct HostFactory(Arc<Mutex<Pool>>);
struct Handle {
    factory: HostFactory,
    namespace: StorageNamespace,
    id: u64,
}
fn error(message: &str) -> SdkError {
    SdkError::new(ErrorKind::Storage, message)
}
impl HostFactory {
    pub fn fail(&self, operation: Option<&'static str>) {
        self.0.lock().unwrap().fail = operation;
    }
    pub fn calls(&self) -> Vec<(&'static str, StorageNamespace, u64)> {
        self.0.lock().unwrap().calls.clone()
    }
    fn opening(&self, namespace: StorageNamespace) -> SdkResult<Handle> {
        namespace.validate()?;
        let mut pool = self.0.lock().unwrap();
        if pool.fail == Some("open") {
            return Err(error("宿主打开失败"));
        }
        if pool.writers.contains_key(&namespace) {
            return Err(SdkError::new(ErrorKind::Conflict, "宿主写入租约竞争"));
        }
        pool.next += 1;
        let id = pool.next;
        pool.writers.insert(namespace.clone(), id);
        pool.calls.push(("open", namespace.clone(), id));
        Ok(Handle {
            factory: self.clone(),
            namespace,
            id,
        })
    }
}
impl Handle {
    fn operation<T>(
        &self,
        name: &'static str,
        f: impl FnOnce(&mut StorageSnapshot) -> SdkResult<T>,
    ) -> SdkResult<T> {
        let mut pool = self.factory.0.lock().unwrap();
        if pool.writers.get(&self.namespace) != Some(&self.id) {
            return Err(error("宿主句柄已关闭"));
        }
        pool.calls.push((name, self.namespace.clone(), self.id));
        if pool.fail == Some(name) {
            return Err(error("注入宿主操作失败"));
        }
        let mut state: StorageSnapshot = pool
            .data
            .get(&self.namespace)
            .map(|v| serde_json::from_slice(v).map_err(|_| error("宿主记录损坏")))
            .transpose()?
            .unwrap_or_default();
        let value = f(&mut state)?;
        let bytes = serde_json::to_vec(&state).map_err(|_| error("宿主编码失败"))?;
        pool.data.insert(self.namespace.clone(), bytes);
        Ok(value)
    }
    fn closing(&self) -> SdkResult<()> {
        let mut pool = self.factory.0.lock().unwrap();
        if pool.writers.get(&self.namespace) != Some(&self.id) {
            return Ok(());
        }
        pool.calls.push(("close", self.namespace.clone(), self.id));
        pool.writers.remove(&self.namespace);
        if pool.fail == Some("close") {
            return Err(error("宿主关闭失败"));
        }
        Ok(())
    }
}
impl Drop for Handle {
    fn drop(&mut self) {
        let _ = self.closing();
    }
}
impl StoreFactory for HostFactory {
    fn capabilities(&self) -> StorageCapabilities {
        StorageCapabilities {
            contract_version: STORAGE_CONTRACT_VERSION,
            durability: StorageDurability::Volatile,
            atomic_changes: true,
            exclusive_writer: true,
        }
    }
    fn open(&self, namespace: StorageNamespace) -> SdkFuture<'_, Arc<dyn Store>> {
        Box::pin(async move { Ok(Arc::new(self.opening(namespace)?) as Arc<dyn Store>) })
    }
}
impl Store for Handle {
    fn namespace(&self) -> &StorageNamespace {
        &self.namespace
    }
    fn capabilities(&self) -> StorageCapabilities {
        self.factory.capabilities()
    }
    fn load(&self) -> SdkFuture<'_, StorageSnapshot> {
        Box::pin(async { self.operation("load", |s| Ok(s.clone())) })
    }
    fn query_messages(&self, query: MessageCacheQuery) -> SdkFuture<'_, Vec<ChatMessage>> {
        Box::pin(async move { self.operation("query_messages", |s| s.query_messages(&query)) })
    }
    fn get_conversation(&self, id: String) -> SdkFuture<'_, Option<Conversation>> {
        Box::pin(async move {
            self.operation("get_conversation", |s| {
                Ok(s.conversations.iter().find(|v| v.conv_id == id).cloned())
            })
        })
    }
    fn get_projection(&self, key: ProjectionKey) -> SdkFuture<'_, Option<DomainProjection>> {
        Box::pin(async move {
            self.operation("get_projection", |s| {
                Ok(if s.invalidated.contains(&key) {
                    None
                } else {
                    s.projections.iter().find(|v| v.key() == key).cloned()
                })
            })
        })
    }
    fn apply(&self, changes: StorageChangeSet) -> SdkFuture<'_, StorageCommit> {
        Box::pin(async move {
            self.operation("apply", |s| {
                let next = s.applying(changes).map_err(|mut e| {
                    if e.kind != ErrorKind::Conflict {
                        e.kind = ErrorKind::Storage;
                    }
                    e
                })?;
                let commit = StorageCommit {
                    revision: next.revision,
                    user_seq: next.user_seq,
                };
                *s = next;
                Ok(commit)
            })
        })
    }
    fn flush(&self) -> SdkFuture<'_, ()> {
        Box::pin(async { self.operation("flush", |_| Ok(())) })
    }
    fn clear(&self) -> SdkFuture<'_, ()> {
        Box::pin(async {
            self.operation("clear", |s| {
                let revision = s.revision.checked_add(1).ok_or_else(|| error("版本溢出"))?;
                *s = StorageSnapshot {
                    revision,
                    ..StorageSnapshot::default()
                };
                Ok(())
            })
        })
    }
    fn close(&self) -> SdkFuture<'_, ()> {
        Box::pin(async { self.closing() })
    }
}

/// 回调宿主持有自己的句柄表；close 只删除对应项，pool 继续服务其他账号。
pub fn callbacks(host: HostFactory) -> StorageCallbacks {
    type Handles = Arc<Mutex<BTreeMap<String, Arc<dyn Store>>>>;
    let handles: Handles = Arc::default();
    let opening = host.clone();
    let open_handles = handles.clone();
    let mut cb = StorageCallbacks {
        capabilities: Some(host.capabilities()),
        open: Some(Arc::new(move |ns| {
            let host = opening.clone();
            let handles = open_handles.clone();
            Box::pin(async move {
                let store = host.opening(ns)?;
                let id = store.id.to_string();
                let mut registry = handles.lock().unwrap();
                registry.insert(id.clone(), Arc::new(store));
                Ok(StorageHandle(id))
            })
        })),
        ..StorageCallbacks::default()
    };
    fn lookup(handles: &Handles, ctx: &StorageCallbackContext) -> SdkResult<Arc<dyn Store>> {
        let store = handles
            .lock()
            .unwrap()
            .get(&ctx.handle.0)
            .cloned()
            .ok_or_else(|| error("未知回调句柄"))?;
        if store.namespace() != &ctx.namespace {
            return Err(error("回调命名空间不符"));
        }
        Ok(store)
    }
    let registry = handles.clone();
    cb.load = Some(Arc::new(move |ctx| {
        let registry = registry.clone();
        Box::pin(async move { lookup(&registry, &ctx)?.load().await })
    }));
    let registry = handles.clone();
    cb.flush = Some(Arc::new(move |ctx| {
        let registry = registry.clone();
        Box::pin(async move { lookup(&registry, &ctx)?.flush().await })
    }));
    let registry = handles.clone();
    cb.clear = Some(Arc::new(move |ctx| {
        let registry = registry.clone();
        Box::pin(async move { lookup(&registry, &ctx)?.clear().await })
    }));
    let registry = handles.clone();
    cb.query_messages = Some(Arc::new(move |ctx, input| {
        let registry = registry.clone();
        Box::pin(async move { lookup(&registry, &ctx)?.query_messages(input).await })
    }));
    let registry = handles.clone();
    cb.get_conversation = Some(Arc::new(move |ctx, input| {
        let registry = registry.clone();
        Box::pin(async move { lookup(&registry, &ctx)?.get_conversation(input).await })
    }));
    let registry = handles.clone();
    cb.get_projection = Some(Arc::new(move |ctx, input| {
        let registry = registry.clone();
        Box::pin(async move { lookup(&registry, &ctx)?.get_projection(input).await })
    }));
    let registry = handles.clone();
    cb.apply = Some(Arc::new(move |ctx, input| {
        let registry = registry.clone();
        Box::pin(async move { lookup(&registry, &ctx)?.apply(input).await })
    }));
    cb.close = Some(Arc::new(move |ctx| {
        let handles = handles.clone();
        Box::pin(async move {
            let store = lookup(&handles, &ctx)?;
            store.close().await?;
            handles.lock().unwrap().remove(&ctx.handle.0);
            Ok(())
        })
    }));
    cb
}
