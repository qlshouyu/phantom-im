use crate::BaseUrl;
use futures_util::{
    SinkExt, StreamExt,
    stream::{SplitSink, SplitStream},
};
use phantom_domain::*;
use phantom_ports::*;
use std::sync::Arc;
use tokio::{net::TcpStream, sync::Mutex};
use tokio_tungstenite::{MaybeTlsStream, WebSocketStream, connect_async, tungstenite::Message};
type Socket = WebSocketStream<MaybeTlsStream<TcpStream>>;
#[derive(Clone)]
pub struct DefaultWebSocketConnector {
    base: BaseUrl,
}
impl DefaultWebSocketConnector {
    pub fn new(base: BaseUrl) -> Self {
        Self { base }
    }
}
struct SocketConnection {
    writer: Mutex<SplitSink<Socket, Message>>,
    reader: Mutex<SplitStream<Socket>>,
}
fn network() -> SdkError {
    let mut error = SdkError::new(ErrorKind::Network, "WebSocket 连接操作失败");
    error.outcome = OperationOutcome::Unknown;
    error
}
impl WebSocketConnector for DefaultWebSocketConnector {
    fn connect(&self) -> SdkFuture<'_, Arc<dyn WebSocket>> {
        Box::pin(async {
            let (socket, _) = connect_async(self.base.websocket()?.as_str())
                .await
                .map_err(|_| network())?;
            let (writer, reader) = socket.split();
            Ok(Arc::new(SocketConnection {
                writer: Mutex::new(writer),
                reader: Mutex::new(reader),
            }) as Arc<dyn WebSocket>)
        })
    }
}
impl WebSocket for SocketConnection {
    fn send(&self, frame: Vec<u8>) -> SdkFuture<'_, ()> {
        Box::pin(async move {
            self.writer
                .lock()
                .await
                .send(Message::Binary(frame.into()))
                .await
                .map_err(|_| network())
        })
    }
    fn receive(&self) -> SdkFuture<'_, Option<Vec<u8>>> {
        Box::pin(async {
            let mut reader = self.reader.lock().await;
            loop {
                match reader.next().await {
                    Some(Ok(Message::Binary(bytes))) => return Ok(Some(bytes.to_vec())),
                    Some(Ok(Message::Close(_))) | None => return Ok(None),
                    Some(Ok(Message::Ping(_) | Message::Pong(_))) => continue,
                    Some(Ok(_)) => {
                        return Err(SdkError::new(
                            ErrorKind::Protocol,
                            "平台 WebSocket 必须使用二进制帧",
                        ));
                    }
                    Some(Err(_)) => return Err(network()),
                }
            }
        })
    }
    fn close(&self) -> SdkFuture<'_, ()> {
        Box::pin(async {
            self.writer
                .lock()
                .await
                .close()
                .await
                .map_err(|_| network())
        })
    }
}
