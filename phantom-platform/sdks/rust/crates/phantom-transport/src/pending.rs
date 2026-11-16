use phantom_domain::{ErrorKind, SdkError, SdkResult};
use phantom_protocol::Frame;
use std::{
    collections::BTreeMap,
    sync::{
        Arc, Mutex, Weak,
        atomic::{AtomicU64, Ordering},
    },
};
use tokio::sync::oneshot;

struct Pending {
    expected_command: i32,
    sender: oneshot::Sender<SdkResult<Frame>>,
}
#[derive(Default)]
struct State {
    entries: BTreeMap<u64, Pending>,
    closed: bool,
}
struct Inner {
    next: AtomicU64,
    state: Mutex<State>,
}
#[derive(Clone)]
pub struct PendingRequests {
    inner: Arc<Inner>,
}
pub enum ResponseDisposition {
    Completed,
    Push,
    Late,
}
pub struct RequestTicket {
    pub cid: u64,
    receiver: Option<oneshot::Receiver<SdkResult<Frame>>>,
    owner: Weak<Inner>,
}
impl Default for PendingRequests {
    fn default() -> Self {
        Self::new()
    }
}
impl PendingRequests {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Inner {
                next: AtomicU64::new(1),
                state: Mutex::new(State::default()),
            }),
        }
    }
    pub fn register(&self, expected_command: i32) -> SdkResult<RequestTicket> {
        let cid = self
            .inner
            .next
            .try_update(Ordering::Relaxed, Ordering::Relaxed, |n| n.checked_add(1))
            .map_err(|_| SdkError::new(ErrorKind::Busy, "连接 cid 已耗尽"))?;
        let (sender, receiver) = oneshot::channel();
        let mut state = self.inner.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.closed {
            return Err(SdkError::new(ErrorKind::NotConnected, "连接请求表已关闭"));
        }
        state.entries.insert(
            cid,
            Pending {
                expected_command,
                sender,
            },
        );
        Ok(RequestTicket {
            cid,
            receiver: Some(receiver),
            owner: Arc::downgrade(&self.inner),
        })
    }
    pub fn complete(&self, frame: Frame) -> ResponseDisposition {
        if frame.cid == 0 {
            return ResponseDisposition::Push;
        }
        let pending = self
            .inner
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .entries
            .remove(&frame.cid);
        let Some(pending) = pending else {
            return ResponseDisposition::Late;
        };
        let result = if pending.expected_command != frame.command {
            Err(SdkError::new(ErrorKind::Protocol, "响应命令与请求不匹配"))
        } else {
            Ok(frame)
        };
        let _ = pending.sender.send(result);
        ResponseDisposition::Completed
    }
    pub fn close(&self, error: SdkError) {
        let entries = {
            let mut state = self.inner.state.lock().unwrap_or_else(|e| e.into_inner());
            state.closed = true;
            std::mem::take(&mut state.entries)
        };
        for (_, pending) in entries {
            let _ = pending.sender.send(Err(error.clone()));
        }
    }
    pub fn len(&self) -> usize {
        self.inner
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .entries
            .len()
    }
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}
impl RequestTicket {
    pub async fn wait(mut self) -> SdkResult<Frame> {
        self.receiver
            .take()
            .expect("请求接收器只消费一次")
            .await
            .map_err(|_| SdkError::new(ErrorKind::Network, "连接在响应前关闭"))?
    }
}
impl Drop for RequestTicket {
    fn drop(&mut self) {
        if let Some(owner) = self.owner.upgrade() {
            owner
                .state
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .entries
                .remove(&self.cid);
        }
    }
}
