mod support;
use phantom_runtime::{DefaultFiles, DefaultHashing};
use phantom_sdk::*;
use std::{
    collections::VecDeque,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
};
use tokio::sync::Notify;

struct MediaServer {
    gate_operation: Mutex<Option<String>>,
    gate_started: Notify,
    info_override: Mutex<Option<MediaInfo>>,
    base: Arc<support::Server>,
    bytes: Vec<u8>,
    parts: Mutex<Vec<u32>>,
    urls: Mutex<Vec<String>>,
    corrupt_part: AtomicBool,
    instant: AtomicBool,
    blocked: AtomicBool,
    truncated: AtomicBool,
    started: Notify,
}
impl MediaServer {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            gate_operation: Mutex::default(),
            gate_started: Notify::new(),
            info_override: Mutex::default(),
            base: Arc::default(),
            bytes: vec![0, 255, 13, 10, 128],
            parts: Mutex::default(),
            urls: Mutex::default(),
            corrupt_part: AtomicBool::new(false),
            instant: AtomicBool::new(false),
            blocked: AtomicBool::new(false),
            truncated: AtomicBool::new(false),
            started: Notify::new(),
        })
    }
    fn info(&self) -> MediaInfo {
        if let Some(info) = self.info_override.lock().unwrap().clone() {
            return info;
        }
        MediaInfo {
            media_id: "media-1".into(),
            media_type: MediaType::File,
            mime_type: "application/octet-stream".into(),
            size_bytes: self.bytes.len() as u64,
            url: Some("/signed/original?sig=a%2Fb".into()),
            thumb_url: None,
            duration_seconds: None,
            width: None,
            height: None,
            file_name: Some("原始数据.bin".into()),
            status: "READY".into(),
        }
    }
    fn session(&self) -> UploadSession {
        UploadSession {
            upload_id: "upload-1".into(),
            part_size: 2,
            size_bytes: self.bytes.len() as u64,
            total_parts: 3,
            uploaded_parts: vec![1],
            instant: self.instant.load(Ordering::Acquire),
            media_id: Some("media-1".into()),
            url: None,
            thumb_url: None,
        }
    }
    fn client(self: &Arc<Self>, store: Arc<dyn StoreFactory>) -> PhantomClient {
        let mut host = support::host(&self.base, store);
        host.api = self.clone();
        host.media = self.clone();
        PhantomClient::with_host(
            ClientOptions::new("https://example.test/prefix", "app"),
            host,
        )
        .unwrap()
    }
    async fn upload(&self, operation_id: &str) -> UploadInput {
        let source = FileSource::Bytes(self.bytes.clone());
        let reader = DefaultFiles.open(source.clone()).await.unwrap();
        let sha256 = DefaultHashing.sha256(reader).await.unwrap();
        UploadInput {
            operation_id: operation_id.into(),
            source,
            metadata: UploadMetadata {
                file_name: "原始数据.bin".into(),
                size_bytes: self.bytes.len() as u64,
                sha256,
                mime_type: "application/octet-stream".into(),
                media_type: MediaType::File,
                duration_seconds: None,
                width: None,
                height: None,
                burn_after_read: false,
                burn_after_read_ttl_seconds: None,
            },
        }
    }
}
impl PlatformApi for MediaServer {
    fn execute(
        &self,
        request: ApiRequest,
        identity: HttpIdentity,
        request_id: String,
    ) -> SdkFuture<'_, ApiResponse> {
        Box::pin(async move {
            if self.gate_operation.lock().unwrap().as_deref() == Some(request.operation()) {
                self.gate_started.notify_one();
                std::future::pending::<()>().await;
            }
            Ok(match request {
                ApiRequest::InitUpload { .. } => ApiResponse::InitUpload(Box::new(self.session())),
                ApiRequest::UploadPart { input } => {
                    let offset = (input.part_number - 1) * 2;
                    assert_eq!(
                        input.bytes,
                        self.bytes[offset as usize..(offset as usize + 2).min(self.bytes.len())]
                    );
                    self.parts.lock().unwrap().push(input.part_number);
                    ApiResponse::UploadPart(Box::new(UploadPartResult {
                        upload_id: input.upload_id,
                        part_number: input.part_number,
                        etag: "etag".into(),
                        size_bytes: if self.corrupt_part.load(Ordering::Acquire) {
                            9
                        } else {
                            input.bytes.len() as u64
                        },
                    }))
                }
                ApiRequest::CompleteUpload { .. } => {
                    ApiResponse::CompleteUpload(Box::new(UploadedMedia {
                        session: self.session(),
                        media: self.info(),
                    }))
                }
                ApiRequest::GetMedia { .. } => ApiResponse::GetMedia(Box::new(self.info())),
                request => return self.base.execute(request, identity, request_id).await,
            })
        })
    }
}
struct Stream {
    chunks: Mutex<VecDeque<Vec<u8>>>,
    blocked: bool,
    started: Arc<Notify>,
}
impl MediaStream for Stream {
    fn next_chunk(&self) -> SdkFuture<'_, Option<Vec<u8>>> {
        Box::pin(async {
            self.started.notify_waiters();
            if self.blocked {
                std::future::pending::<()>().await;
            }
            Ok(self.chunks.lock().unwrap().pop_front())
        })
    }
}
impl MediaAccess for MediaServer {
    fn open(&self, url: String, _: HttpIdentity) -> SdkFuture<'_, MediaDownload> {
        Box::pin(async move {
            self.urls.lock().unwrap().push(url);
            self.started.notify_one();
            let mut bytes = self.bytes.clone();
            if self.truncated.load(Ordering::Acquire) {
                bytes.pop();
            }
            Ok(MediaDownload {
                mime_type: "application/octet-stream".into(),
                content_length: Some(self.bytes.len() as u64),
                stream: Arc::new(Stream {
                    chunks: Mutex::new(bytes.chunks(2).map(|n| n.to_vec()).collect()),
                    blocked: self.blocked.load(Ordering::Acquire),
                    started: Arc::new(Notify::new()),
                }),
            })
        })
    }
}
fn call() -> CallOptions {
    CallOptions {
        timeout_ms: Some(3_000),
        cancellation: None,
    }
}

#[tokio::test]
async fn resumed_upload_uses_raw_bytes_and_authoritative_media_with_ordered_progress() {
    for sqlite in [false, true] {
        let server = MediaServer::new();
        let directory = tempfile::tempdir().unwrap();
        let store: Arc<dyn StoreFactory> = if sqlite {
            Arc::new(SqliteStoreFactory::directory(directory.path().to_path_buf()).unwrap())
        } else {
            Arc::new(MemoryStoreFactory::new())
        };
        let client = server.client(store);
        support::ready(&client).await;
        let events = client
            .subscribe(SubscriptionOptions { capacity: 64 })
            .unwrap();
        let result = client
            .upload_media(server.upload("resume").await, call())
            .await
            .unwrap();
        assert_eq!(result.media, server.info());
        assert_eq!(*server.parts.lock().unwrap(), [2, 3]);
        let mut progress = Vec::new();
        loop {
            let event = events.next(call()).await.unwrap().unwrap();
            if let EventKind::TransferProgress(p) = event.kind {
                let done = p.stage == TransferStage::Completed;
                progress.push(p);
                if done {
                    break;
                }
            }
        }
        assert_eq!(progress.first().unwrap().stage, TransferStage::Preparing);
        assert_eq!(progress.last().unwrap().transferred_bytes, 5);
        assert!(progress.iter().any(|p| p.transferred_bytes == 2));
        assert!(progress.iter().all(|p| p.operation_id == "resume"));
        let message = client
            .send_media(
                SendMediaInput {
                    conv_id: "gr_group".into(),
                    conv_type: ConversationType::Group,
                    media_id: result.media.media_id,
                    reply_to_msg_id: None,
                    mentions: vec![],
                    client_msg_id: Some("media-send".into()),
                },
                call(),
            )
            .await
            .unwrap();
        assert!(matches!(
            message.content,
            Some(MessageContent::File(FileContent { size_bytes: 5, .. }))
        ));
        client.shutdown(call()).await.unwrap();
    }
}
#[tokio::test]
async fn instant_upload_and_digest_failure_never_submit_unneeded_parts() {
    let server = MediaServer::new();
    server.instant.store(true, Ordering::Release);
    let client = server.client(Arc::new(MemoryStoreFactory::new()));
    support::ready(&client).await;
    client
        .upload_media(server.upload("instant").await, call())
        .await
        .unwrap();
    assert!(server.parts.lock().unwrap().is_empty());
    let mut input = server.upload("wrong-hash").await;
    input.source = FileSource::Bytes(vec![1, 2, 3, 4, 5]);
    let error = client.upload_media(input, call()).await.unwrap_err();
    assert_eq!(error.kind, ErrorKind::InvalidArgument);
    assert_eq!(error.operation_id.as_deref(), Some("wrong-hash"));
    server.instant.store(false, Ordering::Release);
    server.corrupt_part.store(true, Ordering::Release);
    assert_eq!(
        client
            .upload_media(server.upload("bad-part").await, call())
            .await
            .unwrap_err()
            .kind,
        ErrorKind::Protocol
    );
    client.shutdown(call()).await.unwrap();
}
#[tokio::test]
async fn download_is_exact_and_atomic_and_thumb_falls_back_without_rewriting_url() {
    let server = MediaServer::new();
    let client = server.client(Arc::new(MemoryStoreFactory::new()));
    support::ready(&client).await;
    assert_eq!(
        client
            .get_media_content("media-1".into(), MediaVariant::Thumb, call())
            .await
            .unwrap()
            .bytes,
        server.bytes
    );
    assert_eq!(*server.urls.lock().unwrap(), ["/signed/original?sig=a%2Fb"]);
    let directory = tempfile::tempdir().unwrap();
    let destination = directory.path().join("raw.bin");
    let input = DownloadInput {
        operation_id: "download".into(),
        media_id: "media-1".into(),
        variant: MediaVariant::Original,
        destination: destination.clone(),
        overwrite: false,
    };
    let downloaded = client.download_media(input.clone(), call()).await.unwrap();
    assert_eq!(downloaded.size_bytes, 5);
    assert_eq!(std::fs::read(&destination).unwrap(), server.bytes);
    assert_eq!(
        client
            .download_media(input.clone(), call())
            .await
            .unwrap_err()
            .kind,
        ErrorKind::Conflict
    );
    server.truncated.store(true, Ordering::Release);
    let mut input = input;
    input.overwrite = true;
    assert_eq!(
        client.download_media(input, call()).await.unwrap_err().kind,
        ErrorKind::Protocol
    );
    // 清理通过共享调度器执行；完成后旧目标仍在，且没有残留临时文件。
    tokio::time::timeout(std::time::Duration::from_secs(2), async {
        loop {
            if std::fs::read_dir(directory.path()).unwrap().count() == 1 {
                break;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(std::fs::read(&destination).unwrap(), server.bytes);
    client.shutdown(call()).await.unwrap();
}
#[tokio::test]
async fn cancellation_stops_stream_releases_operation_and_removes_temporary_file() {
    let server = MediaServer::new();
    server.blocked.store(true, Ordering::Release);
    let client = server.client(Arc::new(MemoryStoreFactory::new()));
    support::ready(&client).await;
    let directory = tempfile::tempdir().unwrap();
    let input = DownloadInput {
        operation_id: "cancel-download".into(),
        media_id: "media-1".into(),
        variant: MediaVariant::Original,
        destination: directory.path().join("result.bin"),
        overwrite: false,
    };
    let cancellation = CancellationToken::new();
    let task = {
        let client = client.clone();
        let input = input.clone();
        let cancellation = cancellation.clone();
        tokio::spawn(async move {
            client
                .download_media(
                    input,
                    CallOptions {
                        timeout_ms: Some(3_000),
                        cancellation: Some(cancellation),
                    },
                )
                .await
        })
    };
    server.started.notified().await;
    assert_eq!(
        client
            .download_media(input.clone(), call())
            .await
            .unwrap_err()
            .kind,
        ErrorKind::Busy
    );
    cancellation.cancel();
    let error = task.await.unwrap().unwrap_err();
    assert_eq!(error.kind, ErrorKind::Cancelled);
    assert_eq!(error.operation_id.as_deref(), Some("cancel-download"));
    // flush 作为同一命令队列的屏障，确认已取消的传输退出。
    client.flush_storage(call()).await.unwrap();
    tokio::time::timeout(std::time::Duration::from_secs(2), async {
        loop {
            if std::fs::read_dir(directory.path()).unwrap().count() == 0 {
                break;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    server.blocked.store(false, Ordering::Release);
    client.download_media(input, call()).await.unwrap();
    client.shutdown(call()).await.unwrap();
}

#[tokio::test]
async fn upload_cancels_each_network_stage_and_never_reports_more_than_total() {
    for stage in ["init_upload", "upload_part", "complete_upload"] {
        let server = MediaServer::new();
        *server.gate_operation.lock().unwrap() = Some(stage.into());
        let sdk = server.client(Arc::new(MemoryStoreFactory::new()));
        support::ready(&sdk).await;
        let subscription = sdk
            .subscribe(SubscriptionOptions { capacity: 128 })
            .unwrap();
        let input = server.upload(stage).await;
        let token = CancellationToken::new();
        let task = {
            let sdk = sdk.clone();
            let input = input.clone();
            let token = token.clone();
            tokio::spawn(async move {
                sdk.upload_media(
                    input,
                    CallOptions {
                        timeout_ms: Some(3000),
                        cancellation: Some(token),
                    },
                )
                .await
            })
        };
        server.gate_started.notified().await;
        assert_eq!(
            sdk.upload_media(input.clone(), call())
                .await
                .unwrap_err()
                .kind,
            ErrorKind::Busy
        );
        token.cancel();
        let error = task.await.unwrap().unwrap_err();
        assert_eq!(error.kind, ErrorKind::Cancelled);
        assert_eq!(error.operation_id.as_deref(), Some(stage));
        assert_eq!(error.outcome, OperationOutcome::Unknown);
        sdk.flush_storage(call()).await.unwrap();
        loop {
            let event = subscription.next(call()).await.unwrap().unwrap();
            if let EventKind::TransferProgress(p) = event.kind {
                assert!(p.transferred_bytes <= p.total_bytes);
                if p.stage == TransferStage::Cancelled {
                    break;
                }
            }
        }
        *server.gate_operation.lock().unwrap() = None;
        sdk.upload_media(input, call()).await.unwrap();
        sdk.shutdown(call()).await.unwrap();
    }
}
#[tokio::test]
async fn all_media_message_types_use_authoritative_dimensions_duration_name_and_thumb() {
    let server = MediaServer::new();
    let sdk = server.client(Arc::new(MemoryStoreFactory::new()));
    support::ready(&sdk).await;
    for (index, kind) in [
        MediaType::Image,
        MediaType::Voice,
        MediaType::Video,
        MediaType::File,
    ]
    .into_iter()
    .enumerate()
    {
        let mut info = server.info();
        info.media_type = kind.clone();
        info.width = Some(640);
        info.height = Some(480);
        info.duration_seconds = Some(30);
        info.thumb_url = Some("/signed/thumb?sig=thumb%2B".into());
        *server.info_override.lock().unwrap() = Some(info);
        let message = sdk
            .send_media(
                SendMediaInput {
                    conv_id: "gr_group".into(),
                    conv_type: ConversationType::Group,
                    media_id: "media-1".into(),
                    reply_to_msg_id: None,
                    mentions: vec![],
                    client_msg_id: Some(format!("kind-{index}")),
                },
                call(),
            )
            .await
            .unwrap();
        match (kind, message.content.unwrap()) {
            (MediaType::Image, MessageContent::Image(v)) => {
                assert_eq!((v.width, v.height), (640, 480))
            }
            (MediaType::Voice, MessageContent::Voice(v)) => assert_eq!(v.duration_s, 30),
            (MediaType::Video, MessageContent::Video(v)) => {
                assert_eq!((v.width, v.height, v.duration_s), (640, 480, 30))
            }
            (MediaType::File, MessageContent::File(v)) => assert_eq!(v.file_name, "原始数据.bin"),
            _ => panic!("消息媒体类型不一致"),
        }
    }
    sdk.get_media_content("media-1".into(), MediaVariant::Thumb, call())
        .await
        .unwrap();
    assert_eq!(
        server.urls.lock().unwrap().last().unwrap(),
        "/signed/thumb?sig=thumb%2B"
    );
    let mut bad = server.info();
    bad.media_type = MediaType::Image;
    bad.width = None;
    *server.info_override.lock().unwrap() = Some(bad);
    assert_eq!(
        sdk.send_media(
            SendMediaInput {
                conv_id: "gr_group".into(),
                conv_type: ConversationType::Group,
                media_id: "media-1".into(),
                reply_to_msg_id: None,
                mentions: vec![],
                client_msg_id: None
            },
            call()
        )
        .await
        .unwrap_err()
        .kind,
        ErrorKind::Protocol
    );
    sdk.shutdown(call()).await.unwrap();
}

#[tokio::test]
async fn upload_deadline_covers_network_work_and_reports_terminal_failure() {
    let server = MediaServer::new();
    *server.gate_operation.lock().unwrap() = Some("complete_upload".into());
    let sdk = server.client(Arc::new(MemoryStoreFactory::new()));
    support::ready(&sdk).await;
    let subscription = sdk
        .subscribe(SubscriptionOptions { capacity: 128 })
        .unwrap();
    let error = sdk
        .upload_media(
            server.upload("deadline").await,
            CallOptions {
                timeout_ms: Some(10),
                cancellation: None,
            },
        )
        .await
        .unwrap_err();
    assert_eq!(error.kind, ErrorKind::Timeout);
    assert_eq!(error.outcome, OperationOutcome::Unknown);
    sdk.flush_storage(call()).await.unwrap();
    loop {
        let event = subscription.next(call()).await.unwrap().unwrap();
        if let EventKind::TransferProgress(p) = event.kind
            && p.stage == TransferStage::Failed
        {
            assert_eq!(p.operation_id, "deadline");
            assert!(p.transferred_bytes <= p.total_bytes);
            break;
        }
    }
    sdk.shutdown(call()).await.unwrap();
}

#[tokio::test]
async fn pending_upload_download_and_metadata_do_not_block_other_operations_or_logout() {
    for stage in ["upload", "download", "content", "send_media"] {
        let server = MediaServer::new();
        if stage == "upload" {
            *server.gate_operation.lock().unwrap() = Some("complete_upload".into());
        } else if stage == "send_media" {
            *server.gate_operation.lock().unwrap() = Some("get_media".into());
        } else {
            server.blocked.store(true, Ordering::Release);
        }
        let sdk = server.client(Arc::new(MemoryStoreFactory::new()));
        support::ready(&sdk).await;
        let directory = tempfile::tempdir().unwrap();
        let input = server.upload("parallel-upload").await;
        let destination = directory.path().join("result.bin");
        let pending = {
            let sdk = sdk.clone();
            tokio::spawn(async move {
                match stage {
                    "upload" => sdk.upload_media(input, call()).await.map(|_| ()),
                    "download" => sdk
                        .download_media(
                            DownloadInput {
                                operation_id: "parallel-download".into(),
                                media_id: "media-1".into(),
                                variant: MediaVariant::Original,
                                destination,
                                overwrite: false,
                            },
                            call(),
                        )
                        .await
                        .map(|_| ()),
                    "content" => sdk
                        .get_media_content("media-1".into(), MediaVariant::Original, call())
                        .await
                        .map(|_| ()),
                    _ => sdk
                        .send_media(
                            SendMediaInput {
                                conv_id: "gr_group".into(),
                                conv_type: ConversationType::Group,
                                media_id: "media-1".into(),
                                reply_to_msg_id: None,
                                mentions: vec![],
                                client_msg_id: Some("pending-media".into()),
                            },
                            call(),
                        )
                        .await
                        .map(|_| ()),
                }
            })
        };
        if matches!(stage, "upload" | "send_media") {
            server.gate_started.notified().await;
        } else {
            server.started.notified().await;
        }
        // 一个传输等待网络期间，HTTP、普通消息和存储屏障仍能进入并完成。
        tokio::time::timeout(std::time::Duration::from_secs(1), async {
            sdk.get_user_profile(None, call()).await.unwrap();
            sdk.send_text("si_a_b".into(), "independent".into(), call())
                .await
                .unwrap();
            sdk.flush_storage(call()).await.unwrap();
        })
        .await
        .expect("媒体等待阻塞了其他命令");
        assert!(!pending.is_finished());
        sdk.logout(LogoutOptions { clear_cache: false }, call())
            .await
            .unwrap();
        assert_eq!(
            pending.await.unwrap().unwrap_err().kind,
            ErrorKind::Cancelled
        );
        support::ready(&sdk).await;
        assert!(
            sdk.get_messages(MessageCacheQuery {
                conv_id: "gr_group".into(),
                before_conv_seq: None,
                limit: 50
            })
            .unwrap()
            .is_empty()
        );
        sdk.shutdown(call()).await.unwrap();
        tokio::time::timeout(std::time::Duration::from_secs(2), async {
            while std::fs::read_dir(directory.path())
                .unwrap()
                .next()
                .is_some()
            {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("退出后媒体临时文件未清理");
    }
}
