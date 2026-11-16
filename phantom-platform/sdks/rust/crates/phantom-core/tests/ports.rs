//! 只依赖 domain/ports 的独立内存与故障后端，不引用默认适配。
use phantom_core::*;
use phantom_domain::*;
use phantom_ports::*;
use std::{
    future::Future,
    pin::pin,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU32, AtomicUsize, Ordering},
    },
    task::{Context, Poll, Wake, Waker},
};

struct WakeThread(std::thread::Thread);
impl Wake for WakeThread {
    fn wake(self: Arc<Self>) {
        self.0.unpark();
    }
}
fn run<T>(future: impl Future<Output = T>) -> T {
    let waker = Waker::from(Arc::new(WakeThread(std::thread::current())));
    let mut cx = Context::from_waker(&waker);
    let mut future = pin!(future);
    loop {
        match future.as_mut().poll(&mut cx) {
            Poll::Ready(value) => return value,
            Poll::Pending => std::thread::park(),
        }
    }
}
#[derive(Default)]
struct Backend {
    state: Mutex<StorageSnapshot>,
    fault: AtomicUsize,
    opens: AtomicUsize,
    closes: AtomicUsize,
    version: AtomicU32,
}
struct Factory {
    backend: Arc<Backend>,
}
struct Handle {
    namespace: StorageNamespace,
    backend: Arc<Backend>,
}
fn caps(version: u32) -> StorageCapabilities {
    StorageCapabilities {
        contract_version: version,
        durability: StorageDurability::Volatile,
        atomic_changes: true,
        exclusive_writer: true,
    }
}
impl Backend {
    fn fail(&self, operation: usize) -> SdkResult<()> {
        if self
            .fault
            .compare_exchange(operation, 0, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
        {
            Err(SdkError::new(ErrorKind::Business, "注入存储错误"))
        } else {
            Ok(())
        }
    }
}
impl StoreFactory for Factory {
    fn capabilities(&self) -> StorageCapabilities {
        caps(self.backend.version.load(Ordering::Acquire))
    }
    fn open(&self, namespace: StorageNamespace) -> SdkFuture<'_, Arc<dyn Store>> {
        Box::pin(async move {
            self.backend.opens.fetch_add(1, Ordering::AcqRel);
            self.backend.fail(1)?;
            Ok(Arc::new(Handle {
                namespace,
                backend: self.backend.clone(),
            }) as Arc<dyn Store>)
        })
    }
}
impl Store for Handle {
    fn namespace(&self) -> &StorageNamespace {
        &self.namespace
    }
    fn capabilities(&self) -> StorageCapabilities {
        caps(1)
    }
    fn load(&self) -> SdkFuture<'_, StorageSnapshot> {
        Box::pin(async move {
            self.backend.fail(2)?;
            Ok(self.backend.state.lock().unwrap().clone())
        })
    }
    fn query_messages(&self, query: MessageCacheQuery) -> SdkFuture<'_, Vec<ChatMessage>> {
        Box::pin(async move { self.backend.state.lock().unwrap().query_messages(&query) })
    }
    fn get_conversation(&self, id: String) -> SdkFuture<'_, Option<Conversation>> {
        Box::pin(async move {
            Ok(self
                .backend
                .state
                .lock()
                .unwrap()
                .conversations
                .iter()
                .find(|v| v.conv_id == id)
                .cloned())
        })
    }
    fn get_projection(&self, key: ProjectionKey) -> SdkFuture<'_, Option<DomainProjection>> {
        Box::pin(async move {
            Ok(self
                .backend
                .state
                .lock()
                .unwrap()
                .projections
                .iter()
                .find(|v| v.key() == key)
                .cloned())
        })
    }
    fn apply(&self, changes: StorageChangeSet) -> SdkFuture<'_, StorageCommit> {
        Box::pin(async move {
            self.backend.fail(3)?;
            let mut state = self.backend.state.lock().unwrap();
            let next = state.applying(changes)?;
            let commit = StorageCommit {
                revision: next.revision,
                user_seq: next.user_seq,
            };
            *state = next;
            Ok(commit)
        })
    }
    fn flush(&self) -> SdkFuture<'_, ()> {
        Box::pin(async move { self.backend.fail(4) })
    }
    fn clear(&self) -> SdkFuture<'_, ()> {
        Box::pin(async move {
            *self.backend.state.lock().unwrap() = StorageSnapshot::default();
            Ok(())
        })
    }
    fn close(&self) -> SdkFuture<'_, ()> {
        Box::pin(async move {
            self.backend.closes.fetch_add(1, Ordering::AcqRel);
            Ok(())
        })
    }
}
fn setup() -> (Arc<Backend>, Arc<dyn StoreFactory>, StorageNamespace) {
    let backend = Arc::new(Backend::default());
    backend.version.store(1, Ordering::Release);
    let factory = Arc::new(Factory {
        backend: backend.clone(),
    });
    (
        backend,
        factory,
        StorageNamespace {
            service_url: "https://example.test/prefix".into(),
            app_id: "app".into(),
            user_id: "user".into(),
        },
    )
}
#[test]
fn kernel_opens_only_the_injected_factory_and_recovers_a_typed_snapshot() {
    let (backend, factory, namespace) = setup();
    backend.state.lock().unwrap().user_seq = 9_007_199_254_740_993;
    let account = run(open_account(factory, namespace.clone())).unwrap();
    assert_eq!(account.store.namespace(), &namespace);
    assert_eq!(account.snapshot.user_seq, 9_007_199_254_740_993);
    assert_eq!(backend.opens.load(Ordering::Acquire), 1);
    let commit = run(account.store.apply(StorageChangeSet {
        expected_revision: 0,
        mutations: vec![StorageMutation::SetUserSeq(i64::MAX)],
    }))
    .unwrap();
    assert_eq!(commit.user_seq, i64::MAX);
    backend.fault.store(3, Ordering::Release);
    assert!(
        run(account.store.apply(StorageChangeSet {
            expected_revision: 1,
            mutations: vec![StorageMutation::SetUserSeq(i64::MAX)]
        }))
        .is_err()
    );
    assert_eq!(run(account.store.load()).unwrap().revision, 1);
    backend.fault.store(4, Ordering::Release);
    assert!(run(account.store.flush()).is_err());
}
#[test]
fn factory_or_restore_failures_do_not_fallback_and_close_owned_handles() {
    for (fault, closes) in [(1, 0), (2, 1)] {
        let (backend, factory, namespace) = setup();
        backend.fault.store(fault, Ordering::Release);
        let error = match run(open_account(factory, namespace)) {
            Ok(_) => panic!("故障未传播"),
            Err(e) => e,
        };
        assert_eq!(error.kind, ErrorKind::Storage);
        assert_eq!(backend.opens.load(Ordering::Acquire), 1);
        assert_eq!(backend.closes.load(Ordering::Acquire), closes);
    }
}
#[test]
fn incompatible_contract_is_rejected_before_open_and_futures_are_send() {
    let (backend, factory, namespace) = setup();
    backend.version.store(2, Ordering::Release);
    fn send<T: Send>(_: T) {}
    send(open_account(factory.clone(), namespace.clone()));
    let error = match run(open_account(factory, namespace)) {
        Ok(_) => panic!("不兼容契约未拒绝"),
        Err(e) => e,
    };
    assert_eq!(error.kind, ErrorKind::Storage);
    assert_eq!(backend.opens.load(Ordering::Acquire), 0);
}
