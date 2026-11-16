use crate::*;
use std::{
    collections::BTreeSet,
    sync::{
        Mutex,
        atomic::{AtomicU64, Ordering},
    },
};

#[derive(Clone)]
struct MediaSession {
    host: HostServices,
    identity: HttpIdentity,
    events: EventHub,
    session: Option<SessionInfo>,
    view: Arc<Mutex<KernelView>>,
    fence: Arc<AtomicU64>,
    epoch: u64,
    generation: u64,
}
impl MediaSession {
    fn live(&self) -> SdkResult<()> {
        if self.epoch != self.fence.load(Ordering::Acquire)
            || self.view.lock().unwrap_or_else(|e| e.into_inner()).session != self.session
        {
            let mut error = SdkError::new(ErrorKind::Cancelled, "媒体操作所属会话已结束");
            error.outcome = OperationOutcome::Unknown;
            return Err(error);
        }
        Ok(())
    }
    async fn api(&self, request: ApiRequest, context: &CallContext) -> SdkResult<ApiResponse> {
        self.live()?;
        context.check()?;
        request.validate()?;
        let request_id = self.host.ids.generate()?;
        let operation = request.operation();
        context.mark_submitted();
        let response = context
            .run(
                self.host
                    .api
                    .execute(request, self.identity.clone(), request_id),
            )
            .await?;
        self.live()?;
        if response.operation() != operation {
            return Err(protocol("HTTP 返回类型与媒体请求不匹配"));
        }
        Ok(response)
    }
    async fn settle<T>(&self, kernel: &mut Kernel, result: SdkResult<T>) -> SdkResult<T> {
        if kernel.epoch != self.epoch || kernel.generation != self.generation {
            let mut error = SdkError::new(ErrorKind::Cancelled, "媒体操作所属会话或连接代次已结束");
            error.outcome = OperationOutcome::Unknown;
            return Err(error);
        }
        kernel.live()?;
        if let Err(error) = &result
            && error.kind == ErrorKind::Authentication
        {
            kernel.connection_lost(error.clone()).await;
        }
        result
    }
}

fn protocol(message: &str) -> SdkError {
    SdkError::new(ErrorKind::Protocol, message)
}

struct DownloadCleanup {
    target: Option<Arc<dyn DownloadTarget>>,
    scheduler: Arc<dyn Scheduler>,
}
impl Drop for DownloadCleanup {
    fn drop(&mut self) {
        if let Some(target) = self.target.take() {
            self.scheduler
                .spawn(Box::pin(async move { target.abort().await }));
        }
    }
}
impl MediaSession {
    fn progress(&self, progress: &TransferProgress) {
        if self.live().is_ok() {
            let _ = self.events.publish(
                self.session.clone(),
                EventKind::TransferProgress(progress.clone()),
            );
        }
    }
    pub async fn upload_media(
        &mut self,
        input: UploadInput,
        context: &CallContext,
    ) -> SdkResult<UploadedMedia> {
        input.metadata.validate()?;
        validate_id(&input.operation_id, "operation_id")?;
        let mut progress = TransferProgress {
            operation_id: input.operation_id.clone(),
            upload_id: None,
            media_id: None,
            transferred_bytes: 0,
            total_bytes: input.metadata.size_bytes,
            stage: TransferStage::Preparing,
        };
        self.progress(&progress);
        let result = self.upload(&input, context, &mut progress).await;
        progress.stage = match &result {
            Ok(_) => TransferStage::Completed,
            Err(e) if e.kind == ErrorKind::Cancelled => TransferStage::Cancelled,
            Err(_) => TransferStage::Failed,
        };
        self.progress(&progress);
        result.map_err(|mut e| {
            e.operation_id = Some(input.operation_id);
            e
        })
    }
    async fn upload(
        &mut self,
        input: &UploadInput,
        context: &CallContext,
        progress: &mut TransferProgress,
    ) -> SdkResult<UploadedMedia> {
        let reader = context
            .run(self.host.files.open(input.source.clone()))
            .await?;
        if reader.size() != input.metadata.size_bytes {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "文件实际大小与上传元数据不一致",
            ));
        }
        let digest = context
            .run(self.host.hashing.sha256(reader.clone()))
            .await?;
        if digest != input.metadata.sha256 {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "文件原字节摘要与上传元数据不一致",
            ));
        }
        let session = match self
            .api(
                ApiRequest::InitUpload {
                    input: input.metadata.clone(),
                },
                context,
            )
            .await?
        {
            ApiResponse::InitUpload(v) => *v,
            _ => return Err(protocol("初始化上传返回了错误响应类型")),
        };
        progress.upload_id = Some(session.upload_id.clone());
        progress.media_id = session.media_id.clone();
        if session.size_bytes != reader.size() {
            return Err(protocol("上传会话的文件大小不一致"));
        }
        if !session.instant {
            validate_id(&session.upload_id, "upload_id")
                .map_err(|_| protocol("上传会话缺少 upload_id"))?;
            if session.part_size == 0
                || session.total_parts == 0
                || u64::from(session.total_parts)
                    != reader.size().div_ceil(u64::from(session.part_size))
            {
                return Err(protocol("上传分片大小或总数不一致"));
            }
            let uploaded: BTreeSet<_> = session.uploaded_parts.iter().copied().collect();
            if uploaded.len() != session.uploaded_parts.len()
                || uploaded.iter().any(|n| *n == 0 || *n > session.total_parts)
            {
                return Err(protocol("上传会话已完成分片无效"));
            }
            let part_length = |part: u32| {
                u64::from(session.part_size)
                    .min(reader.size() - u64::from(part - 1) * u64::from(session.part_size))
            };
            progress.transferred_bytes = uploaded.iter().map(|n| part_length(*n)).sum();
            progress.stage = TransferStage::Transferring;
            self.progress(progress);
            for part_number in 1..=session.total_parts {
                if uploaded.contains(&part_number) {
                    continue;
                }
                let length = part_length(part_number);
                let bytes = context
                    .run(reader.read_range(
                        u64::from(part_number - 1) * u64::from(session.part_size),
                        length as u32,
                    ))
                    .await?;
                if bytes.len() as u64 != length {
                    return Err(protocol("读取分片字节数与文件范围不一致"));
                }
                let response = self
                    .api(
                        ApiRequest::UploadPart {
                            input: UploadPartInput {
                                upload_id: session.upload_id.clone(),
                                part_number,
                                bytes,
                            },
                        },
                        context,
                    )
                    .await?;
                let ApiResponse::UploadPart(part) = response else {
                    return Err(protocol("上传分片返回了错误响应类型"));
                };
                if part.upload_id != session.upload_id
                    || part.part_number != part_number
                    || part.size_bytes != length
                    || part.etag.trim().is_empty()
                {
                    return Err(protocol("分片确认与提交的分片不匹配"));
                }
                progress.transferred_bytes += length;
                self.progress(progress);
            }
        }
        progress.stage = TransferStage::Completing;
        self.progress(progress);
        let media = if session.instant {
            let id = session
                .media_id
                .clone()
                .ok_or_else(|| protocol("秒传响应缺少媒体标识"))?;
            self.authoritative_media(id, context).await?
        } else {
            match self
                .api(
                    ApiRequest::CompleteUpload {
                        upload_id: session.upload_id.clone(),
                    },
                    context,
                )
                .await?
            {
                ApiResponse::CompleteUpload(v) => {
                    if v.session.upload_id != session.upload_id {
                        return Err(protocol("完成上传响应属于其他会话"));
                    }
                    v.media
                }
                _ => return Err(protocol("完成上传返回了错误响应类型")),
            }
        };
        if media.status != "READY"
            || media.size_bytes != input.metadata.size_bytes
            || media.media_type != input.metadata.media_type
            || session
                .media_id
                .as_ref()
                .is_some_and(|id| *id != media.media_id)
        {
            return Err(protocol("已完成媒体与上传会话不一致"));
        }
        progress.media_id = Some(media.media_id.clone());
        progress.transferred_bytes = input.metadata.size_bytes;
        let mut session = session;
        session.media_id = Some(media.media_id.clone());
        session.url = media.url.clone();
        session.thumb_url = media.thumb_url.clone();
        Ok(UploadedMedia { session, media })
    }
    async fn authoritative_media(
        &mut self,
        id: String,
        context: &CallContext,
    ) -> SdkResult<MediaInfo> {
        match self
            .api(
                ApiRequest::GetMedia {
                    media_id: id.clone(),
                },
                context,
            )
            .await?
        {
            ApiResponse::GetMedia(v) if v.media_id == id => Ok(*v),
            _ => Err(protocol("媒体元数据身份或响应类型不一致")),
        }
    }
    async fn media_source(
        &mut self,
        id: String,
        variant: MediaVariant,
        context: &CallContext,
    ) -> SdkResult<(MediaInfo, MediaDownload, bool)> {
        let media = self.authoritative_media(id, context).await?;
        if media.status != "READY" {
            return Err(SdkError::new(ErrorKind::Conflict, "媒体尚未就绪"));
        }
        let thumb = variant == MediaVariant::Thumb && media.thumb_url.is_some();
        let url = if thumb {
            media.thumb_url.clone()
        } else {
            media.url.clone()
        }
        .ok_or_else(|| protocol("媒体缺少受控下载地址"))?;
        let response = context
            .run(self.host.media.open(url, self.identity.clone()))
            .await?;
        if response.mime_type.trim().is_empty() {
            return Err(protocol("媒体响应缺少 MIME"));
        }
        if response
            .content_length
            .is_some_and(|n| n > 524_288_000 || (!thumb && n != media.size_bytes))
        {
            return Err(protocol("媒体响应长度与元数据不一致"));
        }
        Ok((media, response, thumb))
    }
    pub async fn get_media_content(
        &mut self,
        id: String,
        variant: MediaVariant,
        context: &CallContext,
    ) -> SdkResult<MediaContent> {
        let (media, response, thumb) = self.media_source(id, variant, context).await?;
        let mut bytes = Vec::new();
        while let Some(chunk) = context.run(response.stream.next_chunk()).await? {
            if bytes.len() as u64 + chunk.len() as u64 > 524_288_000 {
                return Err(protocol("媒体响应超出最大大小"));
            }
            bytes.extend_from_slice(&chunk);
        }
        verify_length(bytes.len() as u64, &media, &response, thumb)?;
        Ok(MediaContent {
            bytes,
            mime_type: response.mime_type,
        })
    }
    pub async fn download_media(
        &mut self,
        input: DownloadInput,
        context: &CallContext,
    ) -> SdkResult<DownloadedMedia> {
        let mut progress = TransferProgress {
            operation_id: input.operation_id.clone(),
            upload_id: None,
            media_id: Some(input.media_id.clone()),
            transferred_bytes: 0,
            total_bytes: 0,
            stage: TransferStage::Preparing,
        };
        self.progress(&progress);
        let result = self.download(&input, context, &mut progress).await;
        progress.stage = match &result {
            Ok(_) => TransferStage::Completed,
            Err(e) if e.kind == ErrorKind::Cancelled => TransferStage::Cancelled,
            Err(_) => TransferStage::Failed,
        };
        self.progress(&progress);
        result.map_err(|mut e| {
            e.operation_id = Some(input.operation_id);
            e
        })
    }
    async fn download(
        &mut self,
        input: &DownloadInput,
        context: &CallContext,
        progress: &mut TransferProgress,
    ) -> SdkResult<DownloadedMedia> {
        let (media, response, thumb) = self
            .media_source(input.media_id.clone(), input.variant.clone(), context)
            .await?;
        progress.total_bytes =
            response
                .content_length
                .unwrap_or(if thumb { 0 } else { media.size_bytes });
        progress.stage = TransferStage::Transferring;
        self.progress(progress);
        let target = context
            .run(
                self.host
                    .files
                    .download_target(input.destination.clone(), input.overwrite),
            )
            .await?;
        let mut cleanup = DownloadCleanup {
            target: Some(target.clone()),
            scheduler: self.host.scheduler.clone(),
        };
        while let Some(chunk) = context.run(response.stream.next_chunk()).await? {
            let length = chunk.len() as u64;
            if progress.transferred_bytes + length > 524_288_000 {
                return Err(protocol("媒体响应超出最大大小"));
            }
            context.run(target.write(chunk)).await?;
            progress.transferred_bytes += length;
            self.progress(progress);
        }
        verify_length(progress.transferred_bytes, &media, &response, thumb)?;
        progress.total_bytes = progress.transferred_bytes;
        progress.stage = TransferStage::Completing;
        self.progress(progress);
        let path = context.run(target.commit()).await?;
        cleanup.target = None;
        Ok(DownloadedMedia {
            path,
            size_bytes: progress.transferred_bytes,
            mime_type: response.mime_type,
        })
    }
    async fn send_input(
        &mut self,
        input: SendMediaInput,
        context: &CallContext,
    ) -> SdkResult<SendMessageInput> {
        let media = self
            .authoritative_media(input.media_id.clone(), context)
            .await?;
        if media.status != "READY" {
            return Err(SdkError::new(ErrorKind::Conflict, "媒体尚未就绪"));
        }
        let width = || {
            media
                .width
                .filter(|n| *n > 0)
                .ok_or_else(|| protocol("媒体缺少有效宽度"))
        };
        let height = || {
            media
                .height
                .filter(|n| *n > 0)
                .ok_or_else(|| protocol("媒体缺少有效高度"))
        };
        let duration = || {
            media
                .duration_seconds
                .ok_or_else(|| protocol("媒体缺少时长"))
        };
        let content = match media.media_type {
            MediaType::Image => MessageContent::Image(ImageContent {
                media_id: media.media_id.clone(),
                url: media.url.clone(),
                thumb_url: media.thumb_url.clone(),
                width: width()?,
                height: height()?,
                size_bytes: media.size_bytes,
            }),
            MediaType::Voice => MessageContent::Voice(VoiceContent {
                media_id: media.media_id.clone(),
                url: media.url.clone(),
                duration_s: duration()?,
            }),
            MediaType::Video => MessageContent::Video(VideoContent {
                media_id: media.media_id.clone(),
                url: media.url.clone(),
                cover_url: media.thumb_url.clone(),
                width: width()?,
                height: height()?,
                duration_s: duration()?,
                size_bytes: media.size_bytes,
            }),
            MediaType::File => MessageContent::File(FileContent {
                media_id: media.media_id.clone(),
                url: media.url.clone(),
                file_name: media
                    .file_name
                    .clone()
                    .filter(|n| !n.trim().is_empty())
                    .ok_or_else(|| protocol("媒体缺少文件名"))?,
                size_bytes: media.size_bytes,
                ext: None,
            }),
        };
        Ok(SendMessageInput {
            conv_id: input.conv_id,
            conv_type: input.conv_type,
            content,
            reply_to_msg_id: input.reply_to_msg_id,
            mentions: input.mentions,
            client_msg_id: input.client_msg_id,
        })
    }
}
fn verify_length(
    size: u64,
    media: &MediaInfo,
    response: &MediaDownload,
    thumb: bool,
) -> SdkResult<()> {
    if response.content_length.is_some_and(|n| n != size) || (!thumb && media.size_bytes != size) {
        return Err(protocol("媒体流在完整长度之前终止或长度超出"));
    }
    Ok(())
}

impl Kernel {
    fn media_session(&self) -> SdkResult<MediaSession> {
        Ok(MediaSession {
            host: self.host.clone(),
            identity: self.identity()?,
            events: self.events.clone(),
            session: self.current.session.clone(),
            view: self.view.clone(),
            fence: self.fence.clone(),
            epoch: self.epoch,
            generation: self.generation,
        })
    }
    pub fn prepare_upload_media(
        &self,
        input: UploadInput,
        context: &CallContext,
    ) -> SdkResult<Detached<UploadedMedia>> {
        let mut work = self.media_session()?;
        let session = work.clone();
        let context = context.clone();
        Ok(Detached::new(
            Box::pin(async move { work.upload_media(input, &context).await }),
            move |kernel, result| Box::pin(async move { session.settle(kernel, result).await }),
        ))
    }
    pub fn prepare_get_media_content(
        &self,
        id: String,
        variant: MediaVariant,
        context: &CallContext,
    ) -> SdkResult<Detached<MediaContent>> {
        let mut work = self.media_session()?;
        let session = work.clone();
        let context = context.clone();
        Ok(Detached::new(
            Box::pin(async move { work.get_media_content(id, variant, &context).await }),
            move |kernel, result| Box::pin(async move { session.settle(kernel, result).await }),
        ))
    }
    pub fn prepare_download_media(
        &self,
        input: DownloadInput,
        context: &CallContext,
    ) -> SdkResult<Detached<DownloadedMedia>> {
        let mut work = self.media_session()?;
        let session = work.clone();
        let context = context.clone();
        Ok(Detached::new(
            Box::pin(async move { work.download_media(input, &context).await }),
            move |kernel, result| Box::pin(async move { session.settle(kernel, result).await }),
        ))
    }
    pub fn prepare_send_media(
        &self,
        input: SendMediaInput,
        context: &CallContext,
    ) -> SdkResult<Detached<ChatMessage>> {
        self.online()?;
        let mut work = self.media_session()?;
        let session = work.clone();
        let context = context.clone();
        let work_context = context.clone();
        Ok(Detached::stage(
            Box::pin(async move { work.send_input(input, &work_context).await }),
            move |kernel, result| {
                Box::pin(async move {
                    let input = session.settle(kernel, result).await?;
                    context.check()?;
                    kernel
                        .prepare_send(input, &context, false)
                        .await
                        .map(DetachedStep::Continue)
                })
            },
        ))
    }
}
