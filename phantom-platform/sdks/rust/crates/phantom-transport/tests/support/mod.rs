use std::time::Duration;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpListener,
    task::JoinHandle,
};

pub struct Reply {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
    pub delay: Duration,
}
impl Reply {
    pub fn json(body: &str) -> Self {
        Self {
            status: 200,
            headers: vec![("content-type".into(), "application/json".into())],
            body: body.as_bytes().to_vec(),
            delay: Duration::ZERO,
        }
    }
}
pub async fn server(replies: Vec<Reply>) -> (String, JoinHandle<Vec<String>>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move {
        let mut requests = Vec::new();
        for reply in replies {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut data = Vec::new();
            loop {
                let mut chunk = [0; 4096];
                let len = stream.read(&mut chunk).await.unwrap();
                if len == 0 {
                    break;
                }
                data.extend_from_slice(&chunk[..len]);
                if let Some(end) = data.windows(4).position(|w| w == b"\r\n\r\n") {
                    let header = String::from_utf8_lossy(&data[..end]);
                    let content_len = header
                        .lines()
                        .find_map(|line| {
                            line.split_once(':')
                                .filter(|(name, _)| name.eq_ignore_ascii_case("content-length"))
                                .and_then(|(_, v)| v.trim().parse::<usize>().ok())
                        })
                        .unwrap_or(0);
                    if data.len() >= end + 4 + content_len {
                        break;
                    }
                }
            }
            requests.push(String::from_utf8(data).unwrap());
            tokio::time::sleep(reply.delay).await;
            let mut header = format!(
                "HTTP/1.1 {} fixture\r\nContent-Length: {}\r\nConnection: close\r\n",
                reply.status,
                reply.body.len()
            );
            for (name, value) in reply.headers {
                header.push_str(&format!("{name}: {value}\r\n"));
            }
            header.push_str("\r\n");
            if stream.write_all(header.as_bytes()).await.is_ok() {
                let _ = stream.write_all(&reply.body).await;
            }
        }
        requests
    });
    (address, task)
}
pub async fn collected(task: JoinHandle<Vec<String>>) -> Vec<String> {
    tokio::time::timeout(Duration::from_secs(5), task)
        .await
        .unwrap()
        .unwrap()
}
