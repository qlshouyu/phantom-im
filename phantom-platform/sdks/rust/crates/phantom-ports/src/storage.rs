use crate::SdkFuture;
use phantom_domain::*;
use std::sync::Arc;

/// 打开时取得整个命名空间的排他写入租约；冲突返回 Conflict。
/// 工厂可以持有共享连接池；关闭 Store 不能关闭工厂的宿主共享资源。
pub trait StoreFactory: Send + Sync {
    fn capabilities(&self) -> StorageCapabilities;
    fn open(&self, namespace: StorageNamespace) -> SdkFuture<'_, Arc<dyn Store>>;
}

/// 每个账号的独立句柄。回调期间 SDK 不持有核心状态锁。
///
/// apply 必须全有或全无，并以 expected_revision 拒绝过期批次；Persistent
/// 在成功返回前达到承诺的持久性。flush 返回真实屏障结果；错误不触发其他后端。
/// clear 只清当前账号，保持租约；close 幂等释放所属句柄和租约。
pub trait Store: Send + Sync {
    fn namespace(&self) -> &StorageNamespace;
    fn capabilities(&self) -> StorageCapabilities;
    fn load(&self) -> SdkFuture<'_, StorageSnapshot>;
    fn query_messages(&self, query: MessageCacheQuery) -> SdkFuture<'_, Vec<ChatMessage>>;
    fn get_conversation(&self, conv_id: String) -> SdkFuture<'_, Option<Conversation>>;
    fn get_projection(&self, key: ProjectionKey) -> SdkFuture<'_, Option<DomainProjection>>;
    fn apply(&self, changes: StorageChangeSet) -> SdkFuture<'_, StorageCommit>;
    fn flush(&self) -> SdkFuture<'_, ()>;
    fn clear(&self) -> SdkFuture<'_, ()>;
    fn close(&self) -> SdkFuture<'_, ()>;
}
