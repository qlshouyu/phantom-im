//! 独立受控平台，按共享 proto 的字段号生成 wire；不调用被测 SDK 的协议转换。
use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::mpsc,
};
use tokio_tungstenite::tungstenite::Message;
fn varint(mut n: u64) -> Vec<u8> {
    let mut v = Vec::new();
    while n >= 128 {
        v.push(n as u8 | 128);
        n >>= 7;
    }
    v.push(n as u8);
    v
}
fn number(tag: u64, n: u64) -> Vec<u8> {
    [varint(tag << 3), varint(n)].concat()
}
fn bytes(tag: u64, b: &[u8]) -> Vec<u8> {
    [varint((tag << 3) | 2), varint(b.len() as u64), b.to_vec()].concat()
}
fn text(tag: u64, s: &str) -> Vec<u8> {
    bytes(tag, s.as_bytes())
}
fn take_varint(input: &mut &[u8]) -> u64 {
    let mut n = 0;
    for shift in (0..70).step_by(7) {
        let b = input[0];
        *input = &input[1..];
        n |= u64::from(b & 127) << shift;
        if b < 128 {
            return n;
        }
    }
    panic!("测试输入 varint 损坏")
}
fn fields(mut input: &[u8]) -> BTreeMap<u64, Vec<Vec<u8>>> {
    let mut out = BTreeMap::<u64, Vec<Vec<u8>>>::new();
    while !input.is_empty() {
        let tag = take_varint(&mut input);
        let data = match tag & 7 {
            0 => varint(take_varint(&mut input)),
            2 => {
                let len = take_varint(&mut input) as usize;
                let data = input[..len].to_vec();
                input = &input[len..];
                data
            }
            _ => panic!("未声明的测试 wire 类型"),
        };
        out.entry(tag >> 3).or_default().push(data);
    }
    out
}
fn n(fields: &BTreeMap<u64, Vec<Vec<u8>>>, tag: u64) -> u64 {
    fields
        .get(&tag)
        .map(|v| take_varint(&mut v[0].as_slice()))
        .unwrap_or(0)
}
fn b(fields: &BTreeMap<u64, Vec<Vec<u8>>>, tag: u64) -> Vec<u8> {
    fields.get(&tag).map(|v| v[0].clone()).unwrap_or_default()
}
fn s(fields: &BTreeMap<u64, Vec<Vec<u8>>>, tag: u64) -> String {
    String::from_utf8(b(fields, tag)).unwrap()
}
fn frame(cmd: u64, cid: u64, code: u64, payload: &[u8]) -> Vec<u8> {
    [
        number(1, 1),
        number(2, cmd),
        number(3, cid),
        number(4, code),
        bytes(5, payload),
    ]
    .concat()
}
#[derive(Clone)]
struct Stored {
    client: String,
    conv: String,
    sender: String,
    seq: u64,
    content: Vec<u8>,
}
impl Stored {
    fn msg_id(&self) -> String {
        format!("msg-{}", self.client)
    }
    fn message(&self) -> Vec<u8> {
        [
            text(1, &self.msg_id()),
            text(2, &self.client),
            text(3, &self.conv),
            number(4, 1),
            text(5, &self.sender),
            number(6, 1),
            bytes(7, &self.content),
            number(8, self.seq),
            number(9, 1000),
            number(14, 2),
        ]
        .concat()
    }
    fn event(&self) -> Vec<u8> {
        let body = [
            bytes(1, &self.message()),
            text(2, &self.conv),
            number(3, self.seq),
            text(4, &self.msg_id()),
        ]
        .concat();
        [number(1, self.seq), number(2, 1000), bytes(10, &body)].concat()
    }
    fn view(&self) -> Value {
        let content = fields(&self.content);
        let text_value = s(&fields(&b(&content, 1)), 1);
        json!({"msg_id":self.msg_id(),"client_msg_id":self.client,"conv_id":self.conv,"conv_type":"SINGLE","sender_id":self.sender,"msg_type":"TEXT","content_json":json!({"text":{"text":text_value}}).to_string(),"conv_seq":self.seq,"server_time":1000,"status":"SENT","recalled":false,"restricted":false,"is_request":false,"mentions":[],"burn_after_read_ttl_seconds":0})
    }
}
#[derive(Default)]
pub struct State {
    pub block_auth: AtomicBool,
    messages: Mutex<Vec<Stored>>,
    sockets: Mutex<BTreeMap<String, mpsc::UnboundedSender<Vec<u8>>>>,
    pub logouts: AtomicU64,
    pub http: AtomicU64,
}
pub struct Platform {
    pub base: String,
    pub state: Arc<State>,
    task: tokio::task::JoinHandle<()>,
}
impl Drop for Platform {
    fn drop(&mut self) {
        self.task.abort();
    }
}
impl Platform {
    pub async fn start() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}/prefix", listener.local_addr().unwrap());
        let state = Arc::new(State::default());
        let shared = state.clone();
        let task = tokio::spawn(async move {
            while let Ok((socket, _)) = listener.accept().await {
                let state = shared.clone();
                tokio::spawn(async move {
                    let mut peek = [0; 2048];
                    let len = socket.peek(&mut peek).await.unwrap();
                    if String::from_utf8_lossy(&peek[..len]).starts_with("GET /prefix/ws ") {
                        websocket(socket, state).await;
                    } else {
                        http(socket, state).await;
                    }
                });
            }
        });
        Self { base, state, task }
    }
}
async fn http(mut socket: TcpStream, state: Arc<State>) {
    let mut request = Vec::new();
    let mut chunk = [0; 2048];
    while !request.windows(4).any(|w| w == b"\r\n\r\n") {
        let len = socket.read(&mut chunk).await.unwrap();
        if len == 0 {
            return;
        }
        request.extend_from_slice(&chunk[..len]);
    }
    state.http.fetch_add(1, Ordering::AcqRel);
    let request = String::from_utf8_lossy(&request);
    let path = request
        .lines()
        .next()
        .unwrap()
        .split_whitespace()
        .nth(1)
        .unwrap();
    let value = if path == "/prefix/conversations?limit=200"
        || path.starts_with("/prefix/conversations?")
    {
        json!({"items":[],"next_cursor":null,"has_more":false,"max_user_seq":0,"badge_count":0})
    } else if path == "/prefix/groups" {
        json!([])
    } else if path.starts_with("/prefix/users/") {
        json!({"user_id":"a","app_id":"app","nickname":"测试","avatar_url":null,"ext":{},"status":"ACTIVE"})
    } else if path.contains("/messages") {
        json!({"items":state.messages.lock().unwrap().iter().map(Stored::view).collect::<Vec<_>>(),"next_cursor":0,"has_more":false})
    } else {
        json!([])
    };
    let body = json!({"code":0,"message":"ok","data":value}).to_string();
    let response = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let _ = socket.write_all(response.as_bytes()).await;
}
async fn websocket(socket: TcpStream, state: Arc<State>) {
    let Ok(ws) = tokio_tungstenite::accept_async(socket).await else {
        return;
    };
    let (mut sink, mut stream) = ws.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let mut user = String::new();
    loop {
        tokio::select! {
         outgoing=rx.recv()=>{let Some(outgoing)=outgoing else{break;};if sink.send(Message::Binary(outgoing.into())).await.is_err(){break;}}
         incoming=stream.next()=>{let Some(Ok(Message::Binary(input)))=incoming else{break;};let envelope=fields(&input);let command=n(&envelope,2);let cid=n(&envelope,3);let payload=fields(&b(&envelope,5));
          let (response,body,code)=match command {
           1=>{if state.block_auth.load(Ordering::Acquire){continue;}user=s(&payload,7);let token=s(&payload,1);let code=if token==format!("token-{user}"){0}else{2003};state.sockets.lock().unwrap().insert(user.clone(),tx.clone());let max=state.messages.lock().unwrap().len() as u64;(2,[number(1,1000),number(2,max),number(3,60),bytes(4,&number(2,60)),bytes(5,&number(1,1)),bytes(6,&[number(2,1),number(3,5)].concat())].concat(),code)}
           512=>{let since=n(&payload,1);let messages=state.messages.lock().unwrap();let mut body=messages.iter().filter(|m|m.seq>since).flat_map(|m|bytes(1,&m.event())).collect::<Vec<_>>();body.extend(number(2,messages.len() as u64));(513,body,0)}
           256=>{let client=s(&payload,1);let mut messages=state.messages.lock().unwrap();let message=if let Some(old)=messages.iter().find(|m|m.client==client){old.clone()}else{let message=Stored{client,conv:s(&payload,2),sender:user.clone(),seq:messages.len() as u64+1,content:b(&payload,5)};messages.push(message.clone());let push=frame(258,0,0,&[bytes(1,&message.message()),number(2,message.seq)].concat());for target in state.sockets.lock().unwrap().values(){let _=target.send(push.clone());}message};(257,[text(1,&message.msg_id()),number(2,message.seq),number(3,1000)].concat(),0)}
           6=>{state.logouts.fetch_add(1,Ordering::AcqRel);(7,vec![],0)}
           3=>(4,vec![],0),
           259=>continue,
           _=>panic!("意外的测试命令 {command}"),
          };
          if sink.send(Message::Binary(frame(response,cid,code,&body).into())).await.is_err(){break;}
         }
        }
    }
}
