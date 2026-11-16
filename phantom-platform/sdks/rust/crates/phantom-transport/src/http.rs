use crate::BaseUrl;
use phantom_domain::{ErrorKind, MediaInfo, MediaVariant, OperationOutcome, SdkError, SdkResult};
use phantom_ports::*;
use reqwest::{
    Client, Method,
    header::{HeaderMap, HeaderName, HeaderValue, LOCATION},
};
use std::{collections::BTreeMap, time::Duration};

#[derive(Clone)]
pub struct HttpAdapter {
    base: BaseUrl,
    client: Client,
    exact_client: hyper_util::client::legacy::Client<
        hyper_rustls::HttpsConnector<hyper_util::client::legacy::connect::HttpConnector>,
        http_body_util::Full<bytes::Bytes>,
    >,
    timeout: Duration,
}
impl HttpAdapter {
    pub fn new(base: BaseUrl, timeout_ms: u64) -> SdkResult<Self> {
        if timeout_ms == 0 {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "请求期限必须大于 0",
            ));
        }
        let client = Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_millis(timeout_ms))
            .build()
            .map_err(|_| SdkError::new(ErrorKind::Network, "无法创建 HTTP 客户端"))?;
        let connector = hyper_rustls::HttpsConnectorBuilder::new()
            .with_webpki_roots()
            .https_or_http()
            .enable_http1()
            .build();
        let exact_client =
            hyper_util::client::legacy::Client::builder(hyper_util::rt::TokioExecutor::new())
                .build(connector);
        Ok(Self {
            base,
            client,
            exact_client,
            timeout: Duration::from_millis(timeout_ms),
        })
    }
    async fn exact_request(&self, request: HttpRequest) -> SdkResult<HttpResponse> {
        use http_body_util::BodyExt;
        let target = self.base.endpoint_target(&request.path, &request.query)?;
        let mut headers = Self::headers(request.identity.as_ref(), request.request_id.as_deref())?;
        let method = match request.method {
            HttpMethod::Get => "GET",
            HttpMethod::Post => "POST",
            HttpMethod::Put => "PUT",
            HttpMethod::Patch => "PATCH",
            HttpMethod::Delete => "DELETE",
        };
        let body = match request.body {
            HttpBody::Empty => Vec::new(),
            HttpBody::Json(bytes) => {
                headers.insert("content-type", HeaderValue::from_static("application/json"));
                bytes
            }
            HttpBody::Bytes(bytes) => {
                headers.insert(
                    "content-type",
                    HeaderValue::from_static("application/octet-stream"),
                );
                bytes
            }
        };
        let mut wire = hyper::Request::builder()
            .method(method)
            .uri(target)
            .body(http_body_util::Full::new(bytes::Bytes::from(body)))
            .map_err(|_| SdkError::new(ErrorKind::InvalidArgument, "HTTP 目标或请求字段无效"))?;
        *wire.headers_mut() = headers;
        let failure = |kind| {
            let mut error = SdkError::new(kind, "HTTP 请求未取得完整响应");
            error.request_id = request.request_id.clone();
            error.outcome = OperationOutcome::Unknown;
            error.retryable = true;
            error
        };
        tokio::time::timeout(self.timeout, async {
            let response = self
                .exact_client
                .request(wire)
                .await
                .map_err(|_| failure(ErrorKind::Network))?;
            let status = response.status().as_u16();
            let headers = response
                .headers()
                .iter()
                .filter_map(|(name, value)| {
                    value
                        .to_str()
                        .ok()
                        .map(|v| (name.to_string(), v.to_owned()))
                })
                .collect();
            let body = response
                .into_body()
                .collect()
                .await
                .map_err(|_| failure(ErrorKind::Network))?
                .to_bytes()
                .to_vec();
            Ok(HttpResponse {
                status,
                headers,
                body,
            })
        })
        .await
        .map_err(|_| failure(ErrorKind::Timeout))?
    }
    pub fn base_url(&self) -> &BaseUrl {
        &self.base
    }
    fn headers(identity: Option<&HttpIdentity>, request_id: Option<&str>) -> SdkResult<HeaderMap> {
        let mut headers = HeaderMap::new();
        let mut put = |key: &'static str, value: &str| -> SdkResult<()> {
            let value = HeaderValue::from_str(value).map_err(|_| {
                SdkError::new(
                    ErrorKind::InvalidArgument,
                    "HTTP 认证或关联标识包含非法字符",
                )
            })?;
            headers.insert(HeaderName::from_static(key), value);
            Ok(())
        };
        if let Some(identity) = identity {
            phantom_domain::validate_id(&identity.user_token, "user_token")?;
            phantom_domain::validate_id(&identity.app_id, "app_id")?;
            phantom_domain::validate_id(&identity.user_id, "user_id")?;
            phantom_domain::validate_id(&identity.device_id, "device_id")?;
            put("authorization", &format!("Bearer {}", identity.user_token))?;
            put("x-app-id", &identity.app_id)?;
            put("x-user-id", &identity.user_id)?;
            put("x-device-id", &identity.device_id)?;
        }
        if let Some(id) = request_id {
            put("x-request-id", id)?;
        }
        Ok(headers)
    }
    fn network(error: &reqwest::Error, request_id: Option<String>) -> SdkError {
        let mut result = SdkError::new(
            if error.is_timeout() {
                ErrorKind::Timeout
            } else {
                ErrorKind::Network
            },
            "HTTP 请求未取得完整响应",
        );
        result.outcome = OperationOutcome::Unknown;
        result.request_id = request_id;
        result.retryable = true;
        result
    }
    async fn response(
        response: reqwest::Response,
        request_id: Option<String>,
    ) -> SdkResult<HttpResponse> {
        let status = response.status().as_u16();
        let headers = response
            .headers()
            .iter()
            .filter_map(|(name, value)| {
                value
                    .to_str()
                    .ok()
                    .map(|v| (name.to_string(), v.to_owned()))
            })
            .collect::<BTreeMap<_, _>>();
        let body = response
            .bytes()
            .await
            .map_err(|e| Self::network(&e, request_id))?
            .to_vec();
        Ok(HttpResponse {
            status,
            headers,
            body,
        })
    }
    pub async fn json(&self, request: HttpRequest) -> SdkResult<serde_json::Value> {
        let secret = request.identity.as_ref().map(|i| i.user_token.clone());
        let request_id = request.request_id.clone();
        let response = self.execute(request).await?;
        phantom_protocol::decode_api_response(
            &response,
            request_id.as_deref(),
            &secret.as_deref().into_iter().collect::<Vec<_>>(),
        )
    }
    /// 仅按当前目标来源附加凭据；每次重定向重新决定，不转发上一跳认证头。
    pub async fn media(
        &self,
        url: &str,
        identity: Option<HttpIdentity>,
    ) -> SdkResult<HttpResponse> {
        Self::response(self.media_response(url, identity).await?, None).await
    }
    async fn media_response(
        &self,
        url: &str,
        identity: Option<HttpIdentity>,
    ) -> SdkResult<reqwest::Response> {
        let mut url = self.base.media_url(url)?;
        for hop in 0..=10 {
            let auth = if self.base.same_origin(&url) {
                identity.as_ref()
            } else {
                None
            };
            let response = self
                .client
                .get(url.clone())
                .headers(Self::headers(auth, None)?)
                .send()
                .await
                .map_err(|e| Self::network(&e, None))?;
            if response.status().is_redirection() {
                if hop == 10 {
                    return Err(SdkError::new(ErrorKind::Protocol, "媒体重定向次数超限"));
                }
                let location = response
                    .headers()
                    .get(LOCATION)
                    .and_then(|v| v.to_str().ok())
                    .ok_or_else(|| {
                        SdkError::new(ErrorKind::Protocol, "媒体重定向缺少有效 Location")
                    })?;
                let next = url
                    .join(location)
                    .map_err(|_| SdkError::new(ErrorKind::Protocol, "媒体重定向地址无效"))?;
                // 绝对地址沿用同一校验；重定向的相对地址遵循当前 URL 的标准语义。
                url = self.base.media_url(next.as_str())?;
                continue;
            }
            if !response.status().is_success() {
                let response = Self::response(response, None).await?;
                let secret = identity.as_ref().map(|i| i.user_token.as_str());
                return match phantom_protocol::decode_api_response(
                    &response,
                    None,
                    &secret.into_iter().collect::<Vec<_>>(),
                ) {
                    Err(error) => Err(error),
                    Ok(_) => Err(SdkError::new(ErrorKind::Protocol, "媒体响应未包含内容")),
                };
            }
            return Ok(response);
        }
        unreachable!("重定向循环在上限处返回")
    }
    pub fn media_address(info: &MediaInfo, variant: MediaVariant) -> SdkResult<&str> {
        let url = match variant {
            MediaVariant::Original => info.url.as_deref(),
            MediaVariant::Thumb => info.thumb_url.as_deref().or(info.url.as_deref()),
        };
        url.filter(|v| !v.is_empty())
            .ok_or_else(|| SdkError::new(ErrorKind::Protocol, "媒体元数据缺少签名内容地址"))
    }
}
impl Http for HttpAdapter {
    fn execute(&self, request: HttpRequest) -> SdkFuture<'_, HttpResponse> {
        Box::pin(async move {
            if request
                .path
                .iter()
                .any(|segment| matches!(segment.as_str(), "." | ".."))
            {
                return self.exact_request(request).await;
            }
            let url = self.base.endpoint(&request.path, &request.query)?;
            let headers = Self::headers(request.identity.as_ref(), request.request_id.as_deref())?;
            let method = match request.method {
                HttpMethod::Get => Method::GET,
                HttpMethod::Post => Method::POST,
                HttpMethod::Put => Method::PUT,
                HttpMethod::Patch => Method::PATCH,
                HttpMethod::Delete => Method::DELETE,
            };
            let mut builder = self.client.request(method, url).headers(headers);
            builder = match request.body {
                HttpBody::Empty => builder,
                HttpBody::Json(bytes) => builder
                    .header("content-type", "application/json")
                    .body(bytes),
                HttpBody::Bytes(bytes) => builder
                    .header("content-type", "application/octet-stream")
                    .body(bytes),
            };
            let response = builder
                .send()
                .await
                .map_err(|e| Self::network(&e, request.request_id.clone()))?;
            Self::response(response, request.request_id).await
        })
    }
}

struct ResponseStream(tokio::sync::Mutex<reqwest::Response>);
impl MediaStream for ResponseStream {
    fn next_chunk(&self) -> SdkFuture<'_, Option<Vec<u8>>> {
        Box::pin(async {
            self.0
                .lock()
                .await
                .chunk()
                .await
                .map(|bytes| bytes.map(|b| b.to_vec()))
                .map_err(|e| HttpAdapter::network(&e, None))
        })
    }
}
impl MediaAccess for HttpAdapter {
    fn open(&self, url: String, identity: HttpIdentity) -> SdkFuture<'_, MediaDownload> {
        Box::pin(async move {
            let response = self.media_response(&url, Some(identity)).await?;
            let mime_type = response
                .headers()
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .map(str::to_owned)
                .ok_or_else(|| SdkError::new(ErrorKind::Protocol, "媒体响应缺少 MIME"))?;
            let content_length = response.content_length();
            Ok(MediaDownload {
                mime_type,
                content_length,
                stream: std::sync::Arc::new(ResponseStream(tokio::sync::Mutex::new(response))),
            })
        })
    }
}
