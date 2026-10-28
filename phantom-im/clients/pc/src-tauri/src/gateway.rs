use std::path::{Component, Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use axum::body::Body;
use axum::extract::{Request, State};
use axum::http::header::{
    self, HeaderName, HeaderValue, CACHE_CONTROL, CONTENT_LENGTH, CONTENT_TYPE, HOST, ORIGIN,
    REFERER, SET_COOKIE,
};
use axum::http::{Method, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::any;
use axum::Router;
use bytes::Bytes;
use futures_util::TryStreamExt;
use mime_guess::from_path;
use percent_encoding::percent_decode_str;
use reqwest::{Client, Method as ReqwestMethod};
use serde_json::json;
use thiserror::Error;
use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tokio::task::JoinHandle;
use url::Url;

use crate::config::GatewayConfig;
use crate::cookie::rewrite_set_cookie;

const CSP: &str = "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; media-src 'self' data: blob:; connect-src 'self' ws: wss:; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";
const HOP_BY_HOP_HEADERS: &[&str] = &[
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
];
const FORWARDED_REQUEST_HEADERS: &[&str] = &[
    "authorization",
    "content-type",
    "user-agent",
    "x-app-id",
    "x-user-id",
    "x-device-id",
    "x-request-id",
    "accept",
    "accept-language",
    "cache-control",
    "range",
    "if-range",
    "if-none-match",
];

#[derive(Debug, Error)]
pub enum GatewayError {
    #[error("无法绑定本地回环端口: {0}")]
    Bind(#[source] std::io::Error),
    #[error("内置 Web 资源不完整，缺少 {0}")]
    MissingResource(String),
    #[error("BFF HTTP 客户端初始化失败: {0}")]
    Client(String),
}

#[derive(Clone)]
struct GatewayState {
    config: GatewayConfig,
    resource_root: Arc<PathBuf>,
    client: Client,
    allowed_origins: Arc<Vec<String>>,
    allowed_hosts: Arc<Vec<String>>,
}

pub struct GatewayHandle {
    origin: String,
    addr: std::net::SocketAddr,
    shutdown: Option<oneshot::Sender<()>>,
    task: Option<JoinHandle<()>>,
}

impl GatewayHandle {
    pub fn origin(&self) -> &str {
        &self.origin
    }

    pub fn addr(&self) -> std::net::SocketAddr {
        self.addr
    }

    pub fn stop(&mut self) {
        if let Some(shutdown) = self.shutdown.take() {
            let _ = shutdown.send(());
        }
        if let Some(task) = self.task.take() {
            task.abort();
        }
    }
}

impl Drop for GatewayHandle {
    fn drop(&mut self) {
        self.stop();
    }
}

pub async fn start(
    config: GatewayConfig,
    resource_root: PathBuf,
) -> Result<GatewayHandle, GatewayError> {
    if !resource_root.join("index.html").is_file() {
        return Err(GatewayError::MissingResource(
            resource_root.join("index.html").display().to_string(),
        ));
    }

    let listener = TcpListener::bind(("127.0.0.1", 0))
        .await
        .map_err(GatewayError::Bind)?;
    let addr = listener.local_addr().map_err(GatewayError::Bind)?;
    let port = addr.port();
    let origin = format!("http://phantom-im.localhost:{port}");
    let allowed_origins = vec![
        origin.clone(),
        format!("http://127.0.0.1:{port}"),
        format!("http://localhost:{port}"),
        format!("http://[::1]:{port}"),
    ];
    let allowed_hosts = vec![
        format!("phantom-im.localhost:{port}"),
        format!("127.0.0.1:{port}"),
        format!("localhost:{port}"),
        format!("[::1]:{port}"),
    ];
    let client = Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(5))
        .timeout(config.request_timeout())
        .build()
        .map_err(|error| GatewayError::Client(error.to_string()))?;

    let state = GatewayState {
        config,
        resource_root: Arc::new(resource_root),
        client,
        allowed_origins: Arc::new(allowed_origins),
        allowed_hosts: Arc::new(allowed_hosts),
    };
    let router = Router::new()
        .route("/api/{*path}", any(proxy_api))
        .fallback(any(serve_static))
        .with_state(state);

    let (shutdown_tx, shutdown_rx) = oneshot::channel();
    let task = tokio::spawn(async move {
        let _ = axum::serve(listener, router)
            .with_graceful_shutdown(async {
                let _ = shutdown_rx.await;
            })
            .await;
    });

    Ok(GatewayHandle {
        origin,
        addr,
        shutdown: Some(shutdown_tx),
        task: Some(task),
    })
}

async fn proxy_api(State(state): State<GatewayState>, request: Request) -> Response {
    if let Err(response) = validate_request(&state, &request) {
        return response;
    }

    let method = request.method().clone();
    let uri = request.uri().clone();
    let path_and_query = uri
        .path_and_query()
        .map(|value| value.as_str())
        .unwrap_or_else(|| uri.path());
    let upstream_url = state.config.upstream_url(path_and_query);
    let upstream_method = match ReqwestMethod::from_bytes(method.as_str().as_bytes()) {
        Ok(method) => method,
        Err(_) => return gateway_error(StatusCode::BAD_REQUEST, 1001, "不支持的 HTTP 方法"),
    };

    let mut builder = state.client.request(upstream_method, upstream_url);
    for name in FORWARDED_REQUEST_HEADERS {
        if let Some(value) = request.headers().get(*name) {
            builder = builder.header(*name, value);
        }
    }
    if let Some(cookie) = request.headers().get(header::COOKIE) {
        builder = builder.header(header::COOKIE, cookie);
    }
    builder = builder.header(ORIGIN, state.config.bff_origin());
    builder = builder.header(REFERER, format!("{}/", state.config.bff_origin()));

    let body_stream = request
        .into_body()
        .into_data_stream()
        .map_err(std::io::Error::other);
    builder = builder.body(reqwest::Body::wrap_stream(body_stream));

    let upstream = match builder.send().await {
        Ok(response) => response,
        Err(error) if error.is_timeout() => {
            return gateway_error(StatusCode::GATEWAY_TIMEOUT, 50301, "BFF 请求超时");
        }
        Err(_) => return gateway_error(StatusCode::BAD_GATEWAY, 50302, "BFF 服务不可达"),
    };

    let status = upstream.status();
    let headers = upstream.headers().clone();
    let mut response = Response::new(Body::from_stream(upstream.bytes_stream()));
    *response.status_mut() = status;
    let output_headers = response.headers_mut();
    for (name, value) in &headers {
        if is_hop_by_hop(name) || name == CONTENT_LENGTH {
            continue;
        }
        if name == SET_COOKIE {
            if let Ok(rewritten) = rewrite_set_cookie(value.to_str().unwrap_or_default()) {
                output_headers.append(SET_COOKIE, rewritten);
            }
            continue;
        }
        output_headers.append(name, value.clone());
    }
    response
}

fn validate_request(state: &GatewayState, request: &Request) -> Result<(), Response> {
    let host = request
        .headers()
        .get(HOST)
        .and_then(|value| value.to_str().ok())
        .unwrap_or_default();
    if !state.allowed_hosts.iter().any(|allowed| allowed == host) {
        return Err(gateway_error(
            StatusCode::BAD_REQUEST,
            1001,
            "非法的本地 Host",
        ));
    }

    if let Some(origin) = request.headers().get(ORIGIN) {
        let origin = origin.to_str().unwrap_or_default();
        if !state
            .allowed_origins
            .iter()
            .any(|allowed| allowed == origin)
        {
            return Err(gateway_error(
                StatusCode::FORBIDDEN,
                1002,
                "非应用来源被拒绝",
            ));
        }
    }
    if let Some(referer) = request.headers().get(REFERER) {
        let referer = referer.to_str().unwrap_or_default();
        if !is_allowed_referer(state, referer) {
            return Err(gateway_error(
                StatusCode::FORBIDDEN,
                1002,
                "非应用来源被拒绝",
            ));
        }
    }

    if is_state_changing(request.method()) {
        let origin = request
            .headers()
            .get(ORIGIN)
            .and_then(|value| value.to_str().ok())
            .unwrap_or_default();
        if !state
            .allowed_origins
            .iter()
            .any(|allowed| allowed == origin)
        {
            return Err(gateway_error(
                StatusCode::FORBIDDEN,
                1002,
                "状态变更请求缺少合法来源",
            ));
        }
    }

    if !is_safe_api_path(request.uri().path()) {
        return Err(gateway_error(
            StatusCode::BAD_REQUEST,
            1003,
            "仅允许转发 BFF API 路径",
        ));
    }
    Ok(())
}

fn is_allowed_referer(state: &GatewayState, referer: &str) -> bool {
    Url::parse(referer)
        .ok()
        .map(|url| {
            state
                .allowed_origins
                .iter()
                .any(|origin| origin == &url.origin().ascii_serialization())
        })
        .unwrap_or(false)
}

fn is_state_changing(method: &Method) -> bool {
    matches!(
        *method,
        Method::POST | Method::PUT | Method::PATCH | Method::DELETE
    )
}

fn is_safe_api_path(path: &str) -> bool {
    if !path.starts_with("/api/") || path.starts_with("/api/openapi") {
        return false;
    }
    let lower = path.to_ascii_lowercase();
    if lower.contains("..") || lower.contains("%2e") || lower.contains('\\') || lower.contains("//")
    {
        return false;
    }
    true
}

async fn serve_static(State(state): State<GatewayState>, request: Request) -> Response {
    if request.method() != Method::GET && request.method() != Method::HEAD {
        return gateway_error(
            StatusCode::METHOD_NOT_ALLOWED,
            1004,
            "静态资源仅支持 GET/HEAD",
        );
    }
    let raw_path = request.uri().path();
    let decoded = match percent_decode_str(raw_path).decode_utf8() {
        Ok(value) => value.into_owned(),
        Err(_) => return gateway_error(StatusCode::BAD_REQUEST, 1005, "静态资源路径无效"),
    };
    let relative = decoded.trim_start_matches('/');
    if !safe_relative_path(relative) {
        return gateway_error(StatusCode::BAD_REQUEST, 1005, "静态资源路径无效");
    }

    let requested = if relative.is_empty() {
        state.resource_root.join("index.html")
    } else {
        state.resource_root.join(relative)
    };
    let file = if requested.is_file() {
        requested
    } else if requested.extension().is_none() {
        state.resource_root.join("index.html")
    } else {
        return gateway_error(StatusCode::NOT_FOUND, 1006, "资源不存在");
    };

    let bytes = match tokio::fs::read(&file).await {
        Ok(bytes) => bytes,
        Err(_) => return gateway_error(StatusCode::NOT_FOUND, 1006, "资源不存在"),
    };
    let content_type = from_path(&file).first_or_octet_stream().to_string();
    let is_index = file.file_name().and_then(|name| name.to_str()) == Some("index.html");
    static_response(
        content_type,
        is_index,
        request.method() == Method::HEAD,
        bytes.into(),
    )
}

fn safe_relative_path(relative: &str) -> bool {
    if relative.contains('\\') || relative.contains('\0') {
        return false;
    }
    Path::new(relative)
        .components()
        .all(|component| matches!(component, Component::Normal(_) | Component::CurDir))
}

fn static_response(
    content_type: String,
    is_index: bool,
    head_only: bool,
    bytes: Bytes,
) -> Response {
    let content_length = bytes.len();
    let body = if head_only {
        Body::empty()
    } else {
        Body::from(bytes)
    };
    let mut response = body.into_response();
    let headers = response.headers_mut();
    headers.insert(CONTENT_TYPE, HeaderValue::from_str(&content_type).unwrap());
    headers.insert(CONTENT_LENGTH, HeaderValue::from(content_length));
    headers.insert(
        CACHE_CONTROL,
        HeaderValue::from_static(if is_index {
            "no-store"
        } else if content_type.starts_with("text/html") {
            "no-cache"
        } else {
            "public, max-age=31536000, immutable"
        }),
    );
    headers.insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_static(CSP),
    );
    headers.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    headers.insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
    headers.insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("DENY"));
    headers.insert(
        HeaderName::from_static("cross-origin-opener-policy"),
        HeaderValue::from_static("same-origin"),
    );
    headers.insert(
        HeaderName::from_static("cross-origin-resource-policy"),
        HeaderValue::from_static("same-origin"),
    );
    headers.insert(
        HeaderName::from_static("permissions-policy"),
        HeaderValue::from_static("camera=(), microphone=(), geolocation=()"),
    );
    response
}

fn gateway_error(status: StatusCode, code: i32, message: &str) -> Response {
    let body = json!({
        "code": code,
        "message": message,
        "request_id": null
    });
    let mut response = (status, axum::Json(body)).into_response();
    response
        .headers_mut()
        .insert(CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

fn is_hop_by_hop(name: &HeaderName) -> bool {
    HOP_BY_HOP_HEADERS
        .iter()
        .any(|header| name.as_str().eq_ignore_ascii_case(header))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_unsafe_api_paths() {
        assert!(is_safe_api_path("/api/auth/me"));
        assert!(!is_safe_api_path("/api"));
        assert!(!is_safe_api_path("/openapi/users"));
        assert!(!is_safe_api_path("/api/../openapi/users"));
        assert!(!is_safe_api_path("/api/%2e%2e/openapi/users"));
        assert!(!is_safe_api_path("/api\\openapi"));
        assert!(!is_safe_api_path("/api/openapi/users"));
    }

    #[test]
    fn static_paths_cannot_escape_resource_root() {
        assert!(safe_relative_path("assets/app.js"));
        assert!(safe_relative_path("settings/profile"));
        assert!(!safe_relative_path("../secret"));
        assert!(!safe_relative_path("assets\\..\\secret"));
    }
}
