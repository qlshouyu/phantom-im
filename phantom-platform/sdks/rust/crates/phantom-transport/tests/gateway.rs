use phantom_domain::*;
use phantom_ports::*;
use phantom_protocol::*;
use phantom_transport::*;
use prost::Message;
use std::sync::Arc;
use tokio::sync::{Mutex, mpsc};
struct Socket {
    writes: mpsc::UnboundedSender<Vec<u8>>,
    reads: Mutex<mpsc::UnboundedReceiver<Vec<u8>>>,
}
impl WebSocket for Socket {
    fn send(&self, bytes: Vec<u8>) -> SdkFuture<'_, ()> {
        Box::pin(async move {
            self.writes
                .send(bytes)
                .map_err(|_| SdkError::new(ErrorKind::Network, "closed"))
        })
    }
    fn receive(&self) -> SdkFuture<'_, Option<Vec<u8>>> {
        Box::pin(async { Ok(self.reads.lock().await.recv().await) })
    }
    fn close(&self) -> SdkFuture<'_, ()> {
        Box::pin(async { Ok(()) })
    }
}
struct Connector(Arc<Socket>);
impl WebSocketConnector for Connector {
    fn connect(&self) -> SdkFuture<'_, Arc<dyn WebSocket>> {
        Box::pin(async { Ok(self.0.clone() as Arc<dyn WebSocket>) })
    }
}
struct SchedulerImpl;
struct Running(tokio::task::AbortHandle);
impl Task for Running {
    fn cancel(&self) {
        self.0.abort();
    }
    fn join(&self) -> SdkFuture<'_, ()> {
        Box::pin(async { Ok(()) })
    }
}
impl Scheduler for SchedulerImpl {
    fn spawn(&self, work: SdkFuture<'static, ()>) -> Arc<dyn Task> {
        Arc::new(Running(tokio::spawn(work).abort_handle()))
    }
}
async fn setup() -> (
    Arc<dyn Gateway>,
    mpsc::UnboundedReceiver<Vec<u8>>,
    mpsc::UnboundedSender<Vec<u8>>,
) {
    let (write, writes) = mpsc::unbounded_channel();
    let (reads, read) = mpsc::unbounded_channel();
    let factory = DefaultGatewayFactory::new(Arc::new(Connector(Arc::new(Socket {
        writes: write,
        reads: Mutex::new(read),
    }))));
    (
        factory.connect(Arc::new(SchedulerImpl)).await.unwrap(),
        writes,
        reads,
    )
}
fn message() -> ChatMessage {
    decode_message(include_bytes!(
        "../../phantom-protocol/fixtures/message.bin"
    ))
    .unwrap()
}
#[tokio::test]
async fn gateway_pairs_send_and_emits_late_confirmation_without_reusing_cid() {
    let (gateway, mut writes, reads) = setup().await;
    let input = message();
    let expected = input.client_msg_id.clone();
    let first = gateway.clone();
    let sending = input.clone();
    let task =
        tokio::spawn(async move { first.request(GatewayRequest::Send(Box::new(sending))).await });
    let frame = decode_frame(&writes.recv().await.unwrap()).unwrap();
    assert_eq!(frame.command, wire::Command::SendMsgReq as i32);
    let request = wire::SendMsgReq::decode(frame.payload.as_slice()).unwrap();
    assert_eq!(request.client_msg_id, expected);
    task.abort();
    let _ = task.await;
    reads
        .send(encode_frame(
            wire::Command::SendMsgResp,
            frame.cid,
            &wire::SendMsgResp {
                msg_id: "new-id".into(),
                conv_seq: 9007199254740993,
                server_time: 100,
            },
        ))
        .unwrap();
    let Some(GatewayPush::Confirmation {
        client_msg_id,
        result,
    }) = gateway.next_push().await.unwrap()
    else {
        panic!("未保留迟到发送身份")
    };
    assert_eq!(client_msg_id, expected);
    assert_eq!(result.unwrap().conv_seq, 9007199254740993);
    let second = gateway.clone();
    let task =
        tokio::spawn(async move { second.request(GatewayRequest::Send(Box::new(input))).await });
    let next = decode_frame(&writes.recv().await.unwrap()).unwrap();
    assert!(next.cid > frame.cid);
    reads
        .send(encode_frame(
            wire::Command::SendMsgResp,
            next.cid,
            &wire::SendMsgResp {
                msg_id: "new-id".into(),
                conv_seq: 9007199254740993,
                server_time: 100,
            },
        ))
        .unwrap();
    assert!(matches!(
        task.await.unwrap().unwrap(),
        GatewayResponse::Sent(_)
    ));
    gateway.close().await.unwrap();
}
#[tokio::test]
async fn connection_end_clears_waiting_requests_and_ack_uses_only_given_committed_cursor() {
    let (gateway, mut writes, reads) = setup().await;
    gateway.acknowledge(9007199254740993).await.unwrap();
    let frame = decode_frame(&writes.recv().await.unwrap()).unwrap();
    assert_eq!(frame.command, wire::Command::MsgAck as i32);
    assert_eq!(
        wire::MsgAck::decode(frame.payload.as_slice())
            .unwrap()
            .user_seq,
        9007199254740993
    );
    let pending = gateway.clone();
    let task = tokio::spawn(async move { pending.request(GatewayRequest::Ping).await });
    writes.recv().await.unwrap();
    drop(reads);
    assert_eq!(task.await.unwrap().unwrap_err().kind, ErrorKind::Network);
    assert_eq!(
        gateway.next_push().await.unwrap_err().kind,
        ErrorKind::Network
    );
    gateway.close().await.unwrap();
}
#[tokio::test]
async fn gateway_rejects_wrong_commands_and_retains_business_code() {
    for code in [0, 2001] {
        let (gateway, mut writes, reads) = setup().await;
        let pending = gateway.clone();
        let task = tokio::spawn(async move { pending.request(GatewayRequest::Ping).await });
        let frame = decode_frame(&writes.recv().await.unwrap()).unwrap();
        let bytes = wire::Envelope {
            version: 1,
            cmd: if code == 0 {
                wire::Command::LogoutResp as i32
            } else {
                wire::Command::Pong as i32
            },
            cid: frame.cid,
            code,
            payload: vec![],
        }
        .encode_to_vec();
        reads.send(bytes).unwrap();
        let error = task.await.unwrap().unwrap_err();
        if code == 0 {
            assert_eq!(error.kind, ErrorKind::Protocol);
        } else {
            assert_eq!(error.kind, ErrorKind::Authentication);
            assert_eq!(error.server_code, Some(2001));
            assert_eq!(error.outcome, OperationOutcome::Rejected);
        }
        gateway.close().await.unwrap();
    }
}
