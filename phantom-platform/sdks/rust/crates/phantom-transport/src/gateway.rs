use crate::{PendingRequests, ResponseDisposition};
use phantom_domain::*;
use phantom_ports::*;
use phantom_protocol::*;
use std::{
    collections::BTreeMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
};
use tokio::sync::{Mutex as AsyncMutex, mpsc};
#[derive(Clone)]
pub struct DefaultGatewayFactory {
    connector: Arc<dyn WebSocketConnector>,
}
impl DefaultGatewayFactory {
    pub fn new(connector: Arc<dyn WebSocketConnector>) -> Self {
        Self { connector }
    }
}
struct Shared {
    socket: Arc<dyn WebSocket>,
    pending: PendingRequests,
    sent: Mutex<BTreeMap<u64, String>>,
    secret: Mutex<Option<String>>,
    closed: AtomicBool,
    terminal: Mutex<Option<SdkError>>,
}
struct Connection {
    shared: Arc<Shared>,
    pushes: AsyncMutex<mpsc::Receiver<SdkResult<GatewayPush>>>,
    task: Arc<dyn Task>,
}
fn disconnected() -> SdkError {
    let mut error = SdkError::new(ErrorKind::Network, "平台长连接已断开");
    error.outcome = OperationOutcome::Unknown;
    error
}
async fn read_loop(
    shared: Arc<Shared>,
    pushes: mpsc::Sender<SdkResult<GatewayPush>>,
) -> SdkResult<()> {
    let result: SdkResult<()> = async {
        loop {
            let Some(bytes) = shared.socket.receive().await? else {
                return Err(disconnected());
            };
            let frame = decode_frame(&bytes)?;
            if frame.cid != 0 {
                let confirmation = shared
                    .sent
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .remove(&frame.cid)
                    .map(|client_msg_id| {
                        let result = if frame.command == wire::Command::SendMsgResp as i32 {
                            decode_confirmation(&frame)
                        } else {
                            Err(SdkError::new(ErrorKind::Protocol, "消息确认命令不匹配"))
                        };
                        GatewayPush::Confirmation {
                            client_msg_id,
                            result,
                        }
                    });
                shared.pending.complete(frame);
                if let Some(push) = confirmation {
                    pushes.try_send(Ok(push)).map_err(|_| {
                        SdkError::new(ErrorKind::EventLagged, "连接推送积压；需要重新同步")
                    })?;
                }
            } else {
                if !matches!(shared.pending.complete(frame), ResponseDisposition::Push) {
                    continue;
                }
                if let Some(mut push) = decode_gateway_push(&bytes)? {
                    if let GatewayPush::Kicked(info) = &mut push
                        && let Some(secret) =
                            &*shared.secret.lock().unwrap_or_else(|e| e.into_inner())
                    {
                        info.detail = redact(&info.detail, &[secret]);
                    }
                    pushes.try_send(Ok(push)).map_err(|_| {
                        SdkError::new(ErrorKind::EventLagged, "连接推送积压；需要重新同步")
                    })?;
                }
            }
        }
    }
    .await;
    let error = result.err().unwrap_or_else(disconnected);
    shared.closed.store(true, Ordering::Release);
    shared.pending.close(error.clone());
    shared
        .sent
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clear();
    *shared.terminal.lock().unwrap_or_else(|e| e.into_inner()) = Some(error);
    Ok(())
}
impl GatewayFactory for DefaultGatewayFactory {
    fn connect(&self, scheduler: Arc<dyn Scheduler>) -> SdkFuture<'_, Arc<dyn Gateway>> {
        Box::pin(async move {
            let socket = self.connector.connect().await?;
            let shared = Arc::new(Shared {
                socket,
                pending: PendingRequests::new(),
                sent: Mutex::default(),
                secret: Mutex::default(),
                closed: AtomicBool::new(false),
                terminal: Mutex::default(),
            });
            let (sender, receiver) = mpsc::channel(512);
            let task = scheduler.spawn(Box::pin(read_loop(shared.clone(), sender)));
            Ok(Arc::new(Connection {
                shared,
                pushes: AsyncMutex::new(receiver),
                task,
            }) as Arc<dyn Gateway>)
        })
    }
}
impl Gateway for Connection {
    fn request(&self, input: GatewayRequest) -> SdkFuture<'_, GatewayResponse> {
        Box::pin(async move {
            if self.shared.closed.load(Ordering::Acquire) {
                return Err(disconnected());
            }
            if let GatewayRequest::Authenticate { input, .. } = &input {
                *self.shared.secret.lock().unwrap_or_else(|e| e.into_inner()) =
                    Some(input.user_token.clone());
            }
            let (_, expected) = gateway_command(&input);
            let ticket = self
                .shared
                .pending
                .register(expected.map(|v| v as i32).unwrap_or(0))?;
            let bytes = encode_gateway_request(&input, ticket.cid)?;
            if let GatewayRequest::Send(message) = &input {
                let mut sent = self.shared.sent.lock().unwrap_or_else(|e| e.into_inner());
                if sent.len() >= 1024 {
                    return Err(SdkError::new(ErrorKind::Busy, "连接待核对的发送过多"));
                }
                sent.insert(ticket.cid, message.client_msg_id.clone());
            }
            if let Err(error) = self.shared.socket.send(bytes).await {
                self.shared
                    .sent
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .remove(&ticket.cid);
                return Err(error);
            }
            if expected.is_none() {
                return Ok(GatewayResponse::Done);
            }
            decode_gateway_response(&input, &ticket.wait().await?)
        })
    }
    fn next_push(&self) -> SdkFuture<'_, Option<GatewayPush>> {
        Box::pin(async {
            let mut receiver = self.pushes.lock().await;
            if let Some(error) = self
                .shared
                .terminal
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .take()
            {
                receiver.close();
                while receiver.try_recv().is_ok() {}
                return Err(error);
            }
            match receiver.recv().await {
                Some(value) => value.map(Some),
                None => self
                    .shared
                    .terminal
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .take()
                    .map(Err)
                    .unwrap_or(Ok(None)),
            }
        })
    }
    fn acknowledge(&self, user_seq: Seq) -> SdkFuture<'_, ()> {
        Box::pin(async move {
            if user_seq < 0 {
                return Err(SdkError::new(ErrorKind::Protocol, "ACK 游标不能为负数"));
            }
            self.shared
                .socket
                .send(encode_frame(
                    wire::Command::MsgAck,
                    0,
                    &wire::MsgAck { user_seq },
                ))
                .await
        })
    }
    fn close(&self) -> SdkFuture<'_, ()> {
        Box::pin(async {
            if self.shared.closed.swap(true, Ordering::AcqRel) {
                self.task.cancel();
                return Ok(());
            }
            self.shared.pending.close(disconnected());
            self.task.cancel();
            self.shared
                .sent
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clear();
            self.shared
                .secret
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .take();
            self.shared.socket.close().await
        })
    }
}
impl Drop for Connection {
    fn drop(&mut self) {
        self.shared.pending.close(disconnected());
        self.task.cancel();
    }
}
