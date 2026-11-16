use phantom_domain::*;
use phantom_ports::*;
use phantom_protocol::{api_http_request, api_http_response, decode_api_response, decode_http_dto};
use std::sync::Arc;
#[derive(Clone)]
pub struct HttpPlatformApi {
    http: Arc<dyn Http>,
}
impl HttpPlatformApi {
    pub fn new(http: Arc<dyn Http>) -> Self {
        Self { http }
    }
    async fn data(
        &self,
        request: &ApiRequest,
        identity: &HttpIdentity,
        request_id: &str,
    ) -> SdkResult<(serde_json::Value, u16)> {
        let mut input = api_http_request(request)?;
        input.identity = Some(identity.clone());
        input.request_id = Some(request_id.into());
        let response = self.http.execute(input).await.map_err(|mut error| {
            if error.request_id.is_none() {
                error.request_id = Some(request_id.into());
            }
            error.message = error.message.replace(&identity.user_token, "[REDACTED]");
            error
        })?;
        let secret = match request {
            ApiRequest::RegisterPushToken { input } => Some(input.token.as_str()),
            ApiRequest::UnregisterPushToken { token } => Some(token.as_str()),
            _ => None,
        };
        let mut secrets = vec![identity.user_token.as_str()];
        if let Some(secret) = secret {
            secrets.push(secret);
        }
        decode_api_response(&response, Some(request_id), &secrets)
            .map(|data| (data, response.status))
    }
}
impl PlatformApi for HttpPlatformApi {
    fn execute(
        &self,
        request: ApiRequest,
        identity: HttpIdentity,
        request_id: String,
    ) -> SdkFuture<'_, ApiResponse> {
        Box::pin(async move {
            request.validate()?;
            let (mut data, status) = self.data(&request, &identity, &request_id).await?;
            if let ApiRequest::CompleteUpload { upload_id } = &request {
                if let Some(fields) = data.as_object_mut()
                    && fields
                        .get("upload_id")
                        .is_some_and(serde_json::Value::is_null)
                {
                    fields.insert(
                        "upload_id".into(),
                        serde_json::Value::String(upload_id.clone()),
                    );
                }
                let session: UploadSession =
                    decode_http_dto(data).map_err(|e| context(e, status, &request_id))?;
                let media_id = session
                    .media_id
                    .clone()
                    .filter(|id| !id.is_empty())
                    .ok_or_else(|| {
                        SdkError::new(ErrorKind::Protocol, "上传完成响应缺少 media_id")
                    })?;
                let (data, status) = self
                    .data(&ApiRequest::GetMedia { media_id }, &identity, &request_id)
                    .await?;
                let media = decode_http_dto(data).map_err(|e| context(e, status, &request_id))?;
                return Ok(ApiResponse::CompleteUpload(Box::new(UploadedMedia {
                    session,
                    media,
                })));
            }
            api_http_response(&request, data).map_err(|e| context(e, status, &request_id))
        })
    }
}

fn context(mut error: SdkError, status: u16, request_id: &str) -> SdkError {
    error.http_status.get_or_insert(status);
    error.request_id.get_or_insert_with(|| request_id.into());
    error
}
