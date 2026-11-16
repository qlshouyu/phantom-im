//! 依赖领域模型和能力接口的 Kernel。
mod account;
mod call;
mod events;
pub use call::{CallContext, CallOptions, CancellationToken};
pub use events::{EventHub, EventSubscription};
use phantom_domain::*;
use phantom_ports::*;
use std::sync::Arc;

pub struct OpenedAccount {
    pub store: Arc<dyn Store>,
    pub snapshot: StorageSnapshot,
}

pub fn storage_error(mut error: SdkError) -> SdkError {
    if error.kind != ErrorKind::Conflict {
        error.kind = ErrorKind::Storage;
    }
    error
}

/// 组装时校验契约声明，登录时才按账号打开；失败不选择其他后端。
pub async fn open_account(
    factory: Arc<dyn StoreFactory>,
    namespace: StorageNamespace,
) -> SdkResult<OpenedAccount> {
    open_account_impl(factory, namespace, None).await
}

pub async fn open_account_guarded(
    factory: Arc<dyn StoreFactory>,
    namespace: StorageNamespace,
    scheduler: Arc<dyn Scheduler>,
) -> SdkResult<OpenedAccount> {
    open_account_impl(factory, namespace, Some(scheduler)).await
}
struct StoreCleanup {
    store: Option<Arc<dyn Store>>,
    scheduler: Option<Arc<dyn Scheduler>>,
}
impl Drop for StoreCleanup {
    fn drop(&mut self) {
        if let (Some(store), Some(scheduler)) = (self.store.take(), self.scheduler.take()) {
            scheduler.spawn(Box::pin(async move { store.close().await }));
        }
    }
}
async fn open_account_impl(
    factory: Arc<dyn StoreFactory>,
    namespace: StorageNamespace,
    scheduler: Option<Arc<dyn Scheduler>>,
) -> SdkResult<OpenedAccount> {
    namespace.validate()?;
    factory.capabilities().validate()?;
    let store = factory
        .open(namespace.clone())
        .await
        .map_err(storage_error)?;
    let mut cleanup = StoreCleanup {
        store: Some(store.clone()),
        scheduler,
    };
    let result = async {
        store.capabilities().validate()?;
        if store.namespace() != &namespace {
            return Err(SdkError::new(
                ErrorKind::Storage,
                "存储返回了其他账号的句柄",
            ));
        }
        let snapshot = store.load().await.map_err(storage_error)?;
        if snapshot.user_seq < 0 {
            return Err(SdkError::new(ErrorKind::Storage, "恢复的账号游标为负数"));
        }
        Ok(snapshot)
    }
    .await;
    match result {
        Ok(snapshot) => {
            let mut account = OpenedAccount { store, snapshot };
            if let Err(error) = account.recover_sends().await {
                let _ = account.store.close().await;
                cleanup.store = None;
                return Err(error);
            }
            cleanup.store = None;
            Ok(account)
        }
        Err(error) => {
            let _ = store.close().await;
            cleanup.store = None;
            Err(error)
        }
    }
}

mod projection;
pub use projection::*;

mod kernel;
pub use kernel::*;

mod messages;

mod api;

mod sync;

mod media;

mod detached;
pub use detached::*;
