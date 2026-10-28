use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use axum::extract::{Request, State};
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use axum::Router;
use phantom_im_pc_lib::config::GatewayConfig;
use phantom_im_pc_lib::gateway;
use serde_json::json;
use tempfile::TempDir;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;

#[derive(Default)]
struct MockState {
    api_hits: AtomicUsize,
    blocked_hits: AtomicUsize,
}

async fn echo(request: Request) -> Response {
    let method = request.method().to_string();
    let uri = request.uri().to_string();
    let origin = request
        .headers()
        .get(header::ORIGIN)
        .and_then(|value| value.to_str().ok())
        .unwrap_or_default()
        .to_owned();
    axum::Json(json!({
        "method": method,
        "uri": uri,
        "origin": origin,
    }))
    .into_response()
}

async fn login() -> Response {
    let mut response =
        axum::Json(json!({"code": 0, "message": "ok", "request_id": "r1"})).into_response();
    response.headers_mut().append(
        header::SET_COOKIE,
        HeaderValue::from_static(
            "PHANTOM_WEB_IM_SESSION=secret; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800; Secure; Domain=example.com",
        ),
    );
    response.headers_mut().append(
        header::SET_COOKIE,
        HeaderValue::from_static("theme=light; Path=/; SameSite=Lax"),
    );
    response
}

async fn register() -> Response {
    axum::Json(json!({
        "code": 0,
        "message": "ok",
        "request_id": "r-register",
        "data": {"account_id": "a1", "phantom_user_id": "u1", "status": "ACTIVE"}
    }))
    .into_response()
}

async fn token(request: Request) -> Response {
    if request
        .headers()
        .get(header::COOKIE)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value.contains("PHANTOM_WEB_IM_SESSION=secret"))
    {
        axum::Json(json!({
            "code": 0,
            "message": "ok",
            "request_id": "r-token",
            "data": {"user_token": "jwt-token", "phantom_user_id": "u1", "app_id": "app_demo"}
        }))
        .into_response()
    } else {
        (StatusCode::UNAUTHORIZED, axum::Json(json!({"code": 401}))).into_response()
    }
}

async fn conversations(request: Request) -> Response {
    if request
        .headers()
        .get(header::COOKIE)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value.contains("PHANTOM_WEB_IM_SESSION=secret"))
    {
        axum::Json(json!({
            "code": 0,
            "message": "ok",
            "request_id": "r-conversations",
            "data": {"items": [{"conv_id": "c1", "unread_count": 0}]}
        }))
        .into_response()
    } else {
        (StatusCode::UNAUTHORIZED, axum::Json(json!({"code": 401}))).into_response()
    }
}

async fn me(request: Request) -> Response {
    if request
        .headers()
        .get(header::COOKIE)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value.contains("PHANTOM_WEB_IM_SESSION=secret"))
    {
        axum::Json(json!({"account_id": "a1"})).into_response()
    } else {
        (StatusCode::UNAUTHORIZED, axum::Json(json!({"code": 401}))).into_response()
    }
}

async fn binary(request: Request) -> Response {
    let body = axum::body::to_bytes(request.into_body(), 1024 * 1024)
        .await
        .unwrap();
    (StatusCode::OK, body).into_response()
}

async fn no_content() -> StatusCode {
    StatusCode::NO_CONTENT
}

async fn server_error() -> Response {
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        axum::Json(json!({"code": 500, "message": "upstream failure", "request_id": "r2"})),
    )
        .into_response()
}

async fn slow() -> Response {
    tokio::time::sleep(Duration::from_millis(1500)).await;
    axum::Json(json!({"code": 0})).into_response()
}

async fn count_hit(State(state): State<Arc<MockState>>) -> Response {
    state.api_hits.fetch_add(1, Ordering::SeqCst);
    axum::Json(json!({"code": 0})).into_response()
}

async fn blocked(State(state): State<Arc<MockState>>) -> Response {
    state.blocked_hits.fetch_add(1, Ordering::SeqCst);
    StatusCode::NOT_FOUND.into_response()
}

struct MockBff {
    origin: String,
    state: Arc<MockState>,
    task: JoinHandle<()>,
}

impl Drop for MockBff {
    fn drop(&mut self) {
        self.task.abort();
    }
}

async fn mock_bff() -> MockBff {
    let state = Arc::new(MockState::default());
    let router = Router::new()
        .route("/api/echo", get(echo).post(echo).put(echo))
        .route("/api/auth/register", post(register))
        .route("/api/auth/login", post(login))
        .route("/api/auth/phantom-token", post(token))
        .route("/api/auth/me", get(me))
        .route("/api/phantom/conversations", get(conversations))
        .route("/api/phantom/binary", put(binary))
        .route("/api/status/204", get(no_content))
        .route("/api/status/500", get(server_error))
        .route("/api/slow", get(slow))
        .route("/api/count", post(count_hit))
        .fallback(blocked)
        .with_state(state.clone());
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let addr = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    MockBff {
        origin: format!("http://127.0.0.1:{}", addr.port()),
        state,
        task,
    }
}

fn web_fixture() -> TempDir {
    let dir = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(dir.path().join("assets")).unwrap();
    std::fs::write(
        dir.path().join("index.html"),
        "<!doctype html><html><body><div id=root></div></body></html>",
    )
    .unwrap();
    std::fs::write(dir.path().join("assets/app.js"), "console.log('phantom')").unwrap();
    std::fs::write(dir.path().join("favicon.svg"), "<svg></svg>").unwrap();
    dir
}

async fn start_gateway(
    bff_origin: &str,
    timeout_seconds: u64,
    root: &TempDir,
) -> gateway::GatewayHandle {
    gateway::start(
        GatewayConfig::parse(bff_origin, timeout_seconds).unwrap(),
        root.path().to_path_buf(),
    )
    .await
    .unwrap()
}

fn test_client() -> reqwest::Client {
    reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .unwrap()
}

async fn raw_http(addr: std::net::SocketAddr, request: &str) -> String {
    let mut stream = TcpStream::connect(addr).await.unwrap();
    stream.write_all(request.as_bytes()).await.unwrap();
    let mut response = Vec::new();
    stream.read_to_end(&mut response).await.unwrap();
    String::from_utf8_lossy(&response).into_owned()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn binds_loopback_and_serves_web_resources_with_csp() {
    let bff = mock_bff().await;
    let web = web_fixture();
    let gateway = start_gateway(&bff.origin, 60, &web).await;
    assert!(gateway.addr().ip().is_loopback());
    assert!(gateway.origin().starts_with("http://phantom-im.localhost:"));

    let client = test_client();
    let base = format!("http://{}", gateway.addr());
    let index = client.get(format!("{base}/")).send().await.unwrap();
    assert_eq!(index.status(), StatusCode::OK);
    assert_eq!(index.headers()[header::CONTENT_TYPE], "text/html");
    let csp = index.headers()[header::CONTENT_SECURITY_POLICY]
        .to_str()
        .unwrap();
    assert!(csp.contains("script-src 'self'"));
    assert!(csp.contains("connect-src 'self' ws: wss:"));
    assert!(csp.contains("script-src 'self' 'unsafe-eval'"));
    assert!(!csp.contains("script-src *"));
    assert!(!csp.contains("connect-src *"));

    let asset = client
        .get(format!("{base}/assets/app.js"))
        .send()
        .await
        .unwrap();
    assert_eq!(asset.status(), StatusCode::OK);
    assert!(asset.headers()[header::CACHE_CONTROL]
        .to_str()
        .unwrap()
        .contains("immutable"));

    let favicon = client
        .get(format!("{base}/favicon.svg"))
        .send()
        .await
        .unwrap();
    assert_eq!(favicon.status(), StatusCode::OK);
    assert_eq!(favicon.headers()[header::CONTENT_TYPE], "image/svg+xml");

    let spa = client
        .get(format!("{base}/settings/profile"))
        .send()
        .await
        .unwrap();
    assert_eq!(spa.status(), StatusCode::OK);
    assert!(spa.text().await.unwrap().contains("id=root"));

    let missing = client
        .get(format!("{base}/assets/missing.js"))
        .send()
        .await
        .unwrap();
    assert_eq!(missing.status(), StatusCode::NOT_FOUND);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn proxies_json_binary_status_and_rewrites_origin() {
    let bff = mock_bff().await;
    let web = web_fixture();
    let gateway = start_gateway(&bff.origin, 60, &web).await;
    let client = test_client();
    let base = format!("http://{}", gateway.addr());

    let registered = client
        .post(format!("{base}/api/auth/register"))
        .header(header::ORIGIN, gateway.origin())
        .json(&json!({
            "nickname": "Mock User",
            "login_name": "mock_user",
            "password": "password-mock-1",
            "password_confirm": "password-mock-1"
        }))
        .send()
        .await
        .unwrap();
    assert_eq!(registered.status(), StatusCode::OK);
    assert_eq!(
        registered.json::<serde_json::Value>().await.unwrap()["data"]["phantom_user_id"],
        "u1"
    );

    let json_response = client
        .get(format!("{base}/api/echo?a=1&b=two"))
        .header(header::ORIGIN, gateway.origin())
        .send()
        .await
        .unwrap();
    assert_eq!(json_response.status(), StatusCode::OK);
    let value: serde_json::Value = json_response.json().await.unwrap();
    assert_eq!(value["method"], "GET");
    assert_eq!(value["uri"], "/api/echo?a=1&b=two");
    assert_eq!(value["origin"], bff.origin);

    let source = vec![0_u8, 1, 2, 127, 128, 255];
    let binary_response = client
        .put(format!("{base}/api/phantom/binary?part=1"))
        .header(header::ORIGIN, gateway.origin())
        .header(header::CONTENT_TYPE, "application/octet-stream")
        .body(source.clone())
        .send()
        .await
        .unwrap();
    assert_eq!(binary_response.status(), StatusCode::OK);
    assert_eq!(binary_response.bytes().await.unwrap().as_ref(), source);

    let no_content = client
        .get(format!("{base}/api/status/204"))
        .send()
        .await
        .unwrap();
    assert_eq!(no_content.status(), StatusCode::NO_CONTENT);

    let failure = client
        .get(format!("{base}/api/status/500"))
        .send()
        .await
        .unwrap();
    assert_eq!(failure.status(), StatusCode::INTERNAL_SERVER_ERROR);
    assert!(failure.text().await.unwrap().contains("upstream failure"));

    let login = client
        .post(format!("{base}/api/auth/login"))
        .header(header::ORIGIN, gateway.origin())
        .send()
        .await
        .unwrap();
    assert_eq!(login.status(), StatusCode::OK);
    let cookies: Vec<_> = login
        .headers()
        .get_all(header::SET_COOKIE)
        .iter()
        .map(|value| value.to_str().unwrap().to_owned())
        .collect();
    assert_eq!(cookies.len(), 2);
    assert!(cookies[0].contains("HttpOnly"));
    assert!(cookies[0].contains("SameSite=Lax"));
    assert!(cookies[0].contains("Max-Age=604800"));
    assert!(!cookies[0].to_ascii_lowercase().contains("secure"));
    assert!(!cookies[0].to_ascii_lowercase().contains("domain="));

    let cookie = cookies[0].split(';').next().unwrap().to_owned();
    let me = client
        .get(format!("{base}/api/auth/me"))
        .header(header::COOKIE, cookie.clone())
        .send()
        .await
        .unwrap();
    assert_eq!(me.status(), StatusCode::OK);

    let token = client
        .post(format!("{base}/api/auth/phantom-token"))
        .header(header::ORIGIN, gateway.origin())
        .header(header::COOKIE, cookie.clone())
        .send()
        .await
        .unwrap();
    assert_eq!(token.status(), StatusCode::OK);
    assert_eq!(
        token.json::<serde_json::Value>().await.unwrap()["data"]["user_token"],
        "jwt-token"
    );

    let conversations = client
        .get(format!("{base}/api/phantom/conversations"))
        .header(header::COOKIE, cookie.clone())
        .send()
        .await
        .unwrap();
    assert_eq!(conversations.status(), StatusCode::OK);
    assert_eq!(
        conversations.json::<serde_json::Value>().await.unwrap()["data"]["items"][0]["conv_id"],
        "c1"
    );

    drop(gateway);
    let restarted = start_gateway(&bff.origin, 60, &web).await;
    let restarted_base = format!("http://{}", restarted.addr());
    let restored = client
        .get(format!("{restarted_base}/api/auth/me"))
        .header(header::COOKIE, cookie)
        .send()
        .await
        .unwrap();
    assert_eq!(restored.status(), StatusCode::OK);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn rejects_cross_site_path_traversal_and_openapi_requests() {
    let bff = mock_bff().await;
    let web = web_fixture();
    let gateway = start_gateway(&bff.origin, 60, &web).await;
    let client = test_client();
    let base = format!("http://{}", gateway.addr());

    let evil = client
        .post(format!("{base}/api/count"))
        .header(header::ORIGIN, "https://evil.example")
        .send()
        .await
        .unwrap();
    assert_eq!(evil.status(), StatusCode::FORBIDDEN);

    let missing_origin = client
        .post(format!("{base}/api/count"))
        .send()
        .await
        .unwrap();
    assert_eq!(missing_origin.status(), StatusCode::FORBIDDEN);

    let openapi = client
        .get(format!("{base}/api/openapi/users"))
        .send()
        .await
        .unwrap();
    assert_eq!(openapi.status(), StatusCode::BAD_REQUEST);

    let traversal = raw_http(
        gateway.addr(),
        "GET /api/%2e%2e/openapi/users HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
    )
    .await;
    assert!(traversal.starts_with("HTTP/1.1 400"));

    let arbitrary = client
        .get(format!("{base}/openapi/users"))
        .send()
        .await
        .unwrap();
    assert_eq!(arbitrary.status(), StatusCode::OK);
    assert!(arbitrary.text().await.unwrap().contains("id=root"));

    assert_eq!(bff.state.api_hits.load(Ordering::SeqCst), 0);
    assert_eq!(bff.state.blocked_hits.load(Ordering::SeqCst), 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn returns_gateway_errors_for_timeout_and_unreachable_bff() {
    let web = web_fixture();
    let bff = mock_bff().await;
    let timed = start_gateway(&bff.origin, 1, &web).await;
    let client = test_client();
    let timeout = client
        .get(format!("http://{}/api/slow", timed.addr()))
        .send()
        .await
        .unwrap();
    assert_eq!(timeout.status(), StatusCode::GATEWAY_TIMEOUT);
    assert!(timeout.text().await.unwrap().contains("BFF 请求超时"));

    let unavailable = start_gateway("http://127.0.0.1:1", 60, &web).await;
    let error = client
        .get(format!("http://{}/api/echo", unavailable.addr()))
        .send()
        .await
        .unwrap();
    assert_eq!(error.status(), StatusCode::BAD_GATEWAY);
    assert!(error.text().await.unwrap().contains("BFF 服务不可达"));
}
