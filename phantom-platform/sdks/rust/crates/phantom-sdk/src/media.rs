use crate::client::Permit;
use crate::*;
impl PhantomClient {
    fn transfer_permit(&self, id: &str) -> SdkResult<Permit> {
        self.read()?;
        validate_id(id, "operation_id")?;
        if !self
            .inner
            .transfers
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(id.into())
        {
            return Err(SdkError::new(ErrorKind::Busy, "相同媒体操作正在进行"));
        }
        Ok(Permit::Send(self.inner.transfers.clone(), id.into()))
    }
    pub async fn upload_media(
        &self,
        input: UploadInput,
        call: CallOptions,
    ) -> SdkResult<UploadedMedia> {
        self.read()?;
        input.metadata.validate()?;
        let operation_id = input.operation_id.clone();
        let permit = self.transfer_permit(&operation_id)?;
        self.invoke_detached(call, Some(permit), move |k, c| {
            Box::pin(async move { k.prepare_upload_media(input, &c) })
        })
        .await
        .map_err(|mut e| {
            e.operation_id = Some(operation_id);
            e
        })
    }
    pub async fn get_media_content(
        &self,
        media_id: String,
        variant: MediaVariant,
        call: CallOptions,
    ) -> SdkResult<MediaContent> {
        self.read()?;
        validate_id(&media_id, "media_id")?;
        self.invoke_detached(call, None, move |k, c| {
            Box::pin(async move { k.prepare_get_media_content(media_id, variant, &c) })
        })
        .await
    }
    pub async fn download_media(
        &self,
        input: DownloadInput,
        call: CallOptions,
    ) -> SdkResult<DownloadedMedia> {
        self.read()?;
        validate_id(&input.media_id, "media_id")?;
        if input.destination.as_os_str().is_empty() {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "下载目标不能为空",
            ));
        }
        let operation_id = input.operation_id.clone();
        let permit = self.transfer_permit(&operation_id)?;
        self.invoke_detached(call, Some(permit), move |k, c| {
            Box::pin(async move { k.prepare_download_media(input, &c) })
        })
        .await
        .map_err(|mut e| {
            e.operation_id = Some(operation_id);
            e
        })
    }
    pub async fn send_media(
        &self,
        mut input: SendMediaInput,
        call: CallOptions,
    ) -> SdkResult<ChatMessage> {
        self.read()?;
        validate_id(&input.media_id, "media_id")?;
        validate_id(&input.conv_id, "conv_id")?;
        let id = input
            .client_msg_id
            .clone()
            .map(Ok)
            .unwrap_or_else(|| self.inner.host.ids.generate())?;
        validate_id(&id, "client_msg_id")?;
        let permit = self.send_permit(&id)?;
        input.client_msg_id = Some(id.clone());
        self.invoke_detached(call, Some(permit), move |k, c| {
            Box::pin(async move { k.prepare_send_media(input, &c) })
        })
        .await
        .map_err(|mut e| {
            e.client_msg_id = Some(id);
            e
        })
    }
}
