use phantom_domain::{ErrorKind, SdkError, SdkResult};
use url::Url;

#[derive(Clone, Debug)]
pub struct BaseUrl {
    url: Url,
}
impl BaseUrl {
    pub fn parse(value: &str) -> SdkResult<Self> {
        let invalid = || {
            SdkError::new(
                ErrorKind::InvalidArgument,
                "base_url 必须是无凭据、查询参数或片段的 HTTP(S) 地址",
            )
        };
        let mut url = Url::parse(value).map_err(|_| invalid())?;
        if value.trim() != value
            || !matches!(url.scheme(), "http" | "https")
            || url.host_str().is_none()
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
        {
            return Err(invalid());
        }
        let path = format!("{}/", url.path().trim_end_matches('/'));
        url.set_path(&path);
        Ok(Self { url })
    }
    pub fn normalized(&self) -> String {
        self.url.as_str().trim_end_matches('/').into()
    }
    pub fn endpoint(&self, segments: &[String], query: &[(String, String)]) -> SdkResult<Url> {
        let mut url = self.url.clone();
        {
            let mut path = url
                .path_segments_mut()
                .map_err(|_| SdkError::new(ErrorKind::InvalidArgument, "服务地址不能追加路径"))?;
            path.pop_if_empty();
            for segment in segments {
                if segment == "." || segment == ".." || segment.is_empty() {
                    return Err(SdkError::new(
                        ErrorKind::InvalidArgument,
                        "路径组件不能是空值或 URL 保留的点段",
                    ));
                }
                path.push(segment);
            }
        }
        if !query.is_empty() {
            url.query_pairs_mut()
                .extend_pairs(query.iter().map(|(k, v)| (k.as_str(), v.as_str())));
        }
        Ok(url)
    }
    /// HTTP request-target 必须保留不透明 ID 的点段，不能再交给 WHATWG URL 路径归一化。
    pub fn endpoint_target(
        &self,
        segments: &[String],
        query: &[(String, String)],
    ) -> SdkResult<String> {
        let mut target = self.url.as_str().trim_end_matches('/').to_owned();
        for segment in segments {
            if segment.is_empty() {
                return Err(SdkError::new(
                    ErrorKind::InvalidArgument,
                    "路径组件不能为空",
                ));
            }
            let encoded = match segment.as_str() {
                "." => "%2E".to_owned(),
                ".." => "%2E%2E".to_owned(),
                _ => {
                    let mut one = self.url.clone();
                    one.set_path("/");
                    one.path_segments_mut()
                        .map_err(|_| {
                            SdkError::new(ErrorKind::InvalidArgument, "服务地址不能追加路径")
                        })?
                        .pop_if_empty()
                        .push(segment);
                    one.path().trim_start_matches('/').to_owned()
                }
            };
            target.push('/');
            target.push_str(&encoded);
        }
        let mut parameters = self.url.clone();
        parameters
            .query_pairs_mut()
            .extend_pairs(query.iter().map(|(k, v)| (k.as_str(), v.as_str())));
        if !query.is_empty() {
            target.push('?');
            target.push_str(parameters.query().unwrap_or_default());
        }
        Ok(target)
    }
    pub fn websocket(&self) -> SdkResult<Url> {
        let mut url = self.endpoint(&["ws".into()], &[])?;
        let scheme = if url.scheme() == "https" { "wss" } else { "ws" };
        url.set_scheme(scheme)
            .map_err(|_| SdkError::new(ErrorKind::InvalidArgument, "无法推导 WebSocket 地址"))?;
        Ok(url)
    }
    pub fn same_origin(&self, url: &Url) -> bool {
        self.url.origin() == url.origin()
    }
    pub fn media_url(&self, value: &str) -> SdkResult<Url> {
        // 平台返回 /media/...，该路径属于 API 的部署前缀；绝对/协议相对 URL 按来源处理。
        let value = if value.starts_with('/') && !value.starts_with("//") {
            &value[1..]
        } else {
            value
        };
        let url = self
            .url
            .join(value)
            .map_err(|_| SdkError::new(ErrorKind::Protocol, "媒体地址无效"))?;
        if !matches!(url.scheme(), "http" | "https")
            || url.host_str().is_none()
            || !url.username().is_empty()
            || url.password().is_some()
            || url.fragment().is_some()
        {
            return Err(SdkError::new(
                ErrorKind::Protocol,
                "媒体地址必须是无凭据的 HTTP(S) URL",
            ));
        }
        Ok(url)
    }
}
