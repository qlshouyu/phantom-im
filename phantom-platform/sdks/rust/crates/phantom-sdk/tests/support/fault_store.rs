use phantom_sdk::*;
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
pub struct FaultFactory {
    pub inner: Arc<dyn StoreFactory>,
    pub fail: Arc<AtomicBool>,
}
struct FaultStore {
    inner: Arc<dyn Store>,
    fail: Arc<AtomicBool>,
}
impl StoreFactory for FaultFactory {
    fn capabilities(&self) -> StorageCapabilities {
        self.inner.capabilities()
    }
    fn open(&self, ns: StorageNamespace) -> SdkFuture<'_, Arc<dyn Store>> {
        Box::pin(async move {
            Ok(Arc::new(FaultStore {
                inner: self.inner.open(ns).await?,
                fail: self.fail.clone(),
            }) as Arc<dyn Store>)
        })
    }
}
impl Store for FaultStore {
    fn namespace(&self) -> &StorageNamespace {
        self.inner.namespace()
    }
    fn capabilities(&self) -> StorageCapabilities {
        self.inner.capabilities()
    }
    fn load(&self) -> SdkFuture<'_, StorageSnapshot> {
        self.inner.load()
    }
    fn query_messages(&self, q: MessageCacheQuery) -> SdkFuture<'_, Vec<ChatMessage>> {
        self.inner.query_messages(q)
    }
    fn get_conversation(&self, id: String) -> SdkFuture<'_, Option<Conversation>> {
        self.inner.get_conversation(id)
    }
    fn get_projection(&self, k: ProjectionKey) -> SdkFuture<'_, Option<DomainProjection>> {
        self.inner.get_projection(k)
    }
    fn apply(&self, c: StorageChangeSet) -> SdkFuture<'_, StorageCommit> {
        Box::pin(async move {
            if self.fail.load(Ordering::Acquire) {
                Err(SdkError::new(ErrorKind::Storage, "注入原子提交失败"))
            } else {
                self.inner.apply(c).await
            }
        })
    }
    fn flush(&self) -> SdkFuture<'_, ()> {
        self.inner.flush()
    }
    fn clear(&self) -> SdkFuture<'_, ()> {
        self.inner.clear()
    }
    fn close(&self) -> SdkFuture<'_, ()> {
        self.inner.close()
    }
}
