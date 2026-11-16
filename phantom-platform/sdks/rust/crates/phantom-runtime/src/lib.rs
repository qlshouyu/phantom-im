//! 默认宿主能力。调用者无需进入 Tokio；所有实例复用进程级运行时。
mod files;
pub use files::DefaultFiles;
use phantom_domain::*;
use phantom_ports::*;
use std::{
    sync::{Arc, OnceLock},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::{
    runtime::{Builder, Runtime},
    sync::Mutex,
    task::{AbortHandle, JoinHandle},
};

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
pub fn shared_runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        Builder::new_multi_thread()
            .enable_all()
            .thread_name("phantom-sdk")
            .build()
            .expect("无法创建 Phantom 共享运行时")
    })
}
#[derive(Default)]
pub struct DefaultClock;
impl Clock for DefaultClock {
    fn now_ms(&self) -> TimestampMs {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|v| v.as_millis().min(i64::MAX as u128) as i64)
            .unwrap_or(0)
    }
    fn monotonic_ms(&self) -> u64 {
        static START: OnceLock<Instant> = OnceLock::new();
        START
            .get_or_init(Instant::now)
            .elapsed()
            .as_millis()
            .min(u64::MAX as u128) as u64
    }
    fn sleep(&self, duration: Duration) -> SdkFuture<'_, ()> {
        let _entered = shared_runtime().enter();
        let sleep = tokio::time::sleep(duration);
        Box::pin(async move {
            sleep.await;
            Ok(())
        })
    }
}
#[derive(Default)]
pub struct DefaultScheduler;
struct JoinState {
    join: Option<JoinHandle<SdkResult<()>>>,
    result: Option<SdkResult<()>>,
}
struct DefaultTask {
    abort: AbortHandle,
    state: Mutex<JoinState>,
}
impl Scheduler for DefaultScheduler {
    fn spawn(&self, work: SdkFuture<'static, ()>) -> Arc<dyn Task> {
        let join = shared_runtime().spawn(work);
        Arc::new(DefaultTask {
            abort: join.abort_handle(),
            state: Mutex::new(JoinState {
                join: Some(join),
                result: None,
            }),
        })
    }
}
impl Task for DefaultTask {
    fn cancel(&self) {
        self.abort.abort();
    }
    fn join(&self) -> SdkFuture<'_, ()> {
        Box::pin(async {
            // 保留 join 锁直到结束，确保所有并发 join 都观察到结束后的状态。
            let mut state = self.state.lock().await;
            if let Some(result) = &state.result {
                return result.clone();
            }
            let result = match state.join.as_mut() {
                Some(handle) => handle.await.unwrap_or_else(|e| {
                    Err(SdkError::new(
                        if e.is_cancelled() {
                            ErrorKind::Cancelled
                        } else {
                            ErrorKind::Protocol
                        },
                        "后台任务已结束",
                    ))
                }),
                None => Ok(()),
            };
            state.join = None;
            state.result = Some(result.clone());
            result
        })
    }
}
#[derive(Default)]
pub struct NoopDiagnostics;
impl Diagnostics for NoopDiagnostics {
    fn record(&self, _: Diagnostic) {}
}

#[derive(Default)]
pub struct DefaultIds;
impl Ids for DefaultIds {
    fn generate(&self) -> SdkResult<String> {
        let mut bytes = [0u8; 16];
        getrandom::fill(&mut bytes)
            .map_err(|_| SdkError::new(ErrorKind::Storage, "无法取得 UUID 随机源"))?;
        bytes[6] = (bytes[6] & 0x0f) | 0x40;
        bytes[8] = (bytes[8] & 0x3f) | 0x80;
        Ok(format!(
            "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
            bytes[0],
            bytes[1],
            bytes[2],
            bytes[3],
            bytes[4],
            bytes[5],
            bytes[6],
            bytes[7],
            bytes[8],
            bytes[9],
            bytes[10],
            bytes[11],
            bytes[12],
            bytes[13],
            bytes[14],
            bytes[15]
        ))
    }
}

#[derive(Default)]
pub struct DefaultHashing;
impl Hashing for DefaultHashing {
    fn sha256(&self, reader: Arc<dyn FileReader>) -> SdkFuture<'_, String> {
        Box::pin(async move {
            use sha2::{Digest, Sha256};
            let mut hasher = Sha256::new();
            let mut offset = 0;
            while offset < reader.size() {
                let length = (reader.size() - offset).min(1_048_576) as u32;
                let bytes = reader.read_range(offset, length).await?;
                if bytes.len() != length as usize {
                    return Err(SdkError::new(ErrorKind::Storage, "文件摘要读取不完整"));
                }
                hasher.update(&bytes);
                offset += bytes.len() as u64;
            }
            Ok(hasher
                .finalize()
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect())
        })
    }
}
