mod support;
use phantom_domain::*;
use phantom_ports::*;
use phantom_transport::*;
use std::time::Duration;
use support::*;
fn identity() -> HttpIdentity {
    HttpIdentity {
        user_token: "fixture-token".into(),
        app_id: "app".into(),
        user_id: "user".into(),
        device_id: "device".into(),
    }
}
fn request() -> HttpRequest {
    HttpRequest {
        path: vec!["users".into(), "a/b".into()],
        query: vec![("limit".into(), "50".into())],
        method: HttpMethod::Post,
        body: HttpBody::Json(br#"{"nickname":"name"}"#.to_vec()),
        identity: Some(identity()),
        request_id: Some("request".into()),
    }
}

#[tokio::test]
async fn actual_http_request_uses_prefix_headers_and_controller_json_envelope() {
    let (address, task) = server(vec![Reply::json(
        r#"{"code":0,"data":{"user_id":"user"},"request_id":"request"}"#,
    )])
    .await;
    let adapter =
        HttpAdapter::new(BaseUrl::parse(&format!("{address}/prefix/")).unwrap(), 1000).unwrap();
    let result = adapter.json(request()).await.unwrap();
    assert_eq!(result["user_id"], "user");
    let requests = collected(task).await;
    let request = requests[0].to_lowercase();
    assert!(request.starts_with("post /prefix/users/a%2fb?limit=50 http/1.1"));
    for header in [
        "authorization: bearer fixture-token",
        "x-app-id: app",
        "x-user-id: user",
        "x-device-id: device",
        "x-request-id: request",
        "content-type: application/json",
    ] {
        assert!(request.contains(header), "缺少 {header}");
    }
    assert!(request.ends_with(r#"{"nickname":"name"}"#));
}
#[tokio::test]
async fn response_business_error_and_request_timeout_are_not_successful_values() {
    let (address, task) = server(vec![Reply::json(
        r#"{"code":5002,"message":"拒绝 fixture-token","request_id":"server-request","data":null}"#,
    )])
    .await;
    let adapter = HttpAdapter::new(BaseUrl::parse(&address).unwrap(), 1000).unwrap();
    let error = adapter.json(request()).await.unwrap_err();
    assert_eq!(error.server_code, Some(5002));
    assert_eq!(error.http_status, Some(200));
    assert_eq!(error.request_id.as_deref(), Some("server-request"));
    assert!(!error.message.contains("fixture-token"));
    collected(task).await;
    let mut reply = Reply::json(r#"{"code":0,"data":null}"#);
    reply.delay = Duration::from_millis(100);
    let (address, task) = server(vec![reply]).await;
    let adapter = HttpAdapter::new(BaseUrl::parse(&address).unwrap(), 10).unwrap();
    let error = adapter.json(request()).await.unwrap_err();
    assert_eq!(error.kind, ErrorKind::Timeout);
    assert_eq!(error.outcome, OperationOutcome::Unknown);
    assert_eq!(error.request_id.as_deref(), Some("request"));
    task.abort();
}
#[tokio::test]
async fn signed_media_query_and_relative_prefix_survive_and_redirect_drops_identity() {
    let (external, external_task) = server(vec![Reply {
        status: 200,
        headers: vec![("content-type".into(), "image/jpeg".into())],
        body: vec![0, 255, 1, 2],
        delay: Duration::ZERO,
    }])
    .await;
    let (platform, platform_task) = server(vec![Reply {
        status: 302,
        headers: vec![(
            "location".into(),
            format!("{external}/asset?signature=a%2Bb"),
        )],
        body: vec![],
        delay: Duration::ZERO,
    }])
    .await;
    let adapter = HttpAdapter::new(
        BaseUrl::parse(&format!("{platform}/prefix/")).unwrap(),
        1000,
    )
    .unwrap();
    let response = adapter
        .media(
            "/media/m/content?expires=42&signature=a%2Bb&variant=original",
            Some(identity()),
        )
        .await
        .unwrap();
    assert_eq!(response.body, vec![0, 255, 1, 2]);
    let sent = collected(platform_task).await[0].to_lowercase();
    assert!(
        sent.starts_with(
            "get /prefix/media/m/content?expires=42&signature=a%2bb&variant=original "
        )
    );
    assert!(sent.contains("authorization: bearer fixture-token"));
    let received = collected(external_task).await[0].to_lowercase();
    assert!(received.starts_with("get /asset?signature=a%2bb "));
    for header in ["authorization:", "x-app-id:", "x-user-id:", "x-device-id:"] {
        assert!(!received.contains(header), "第三方收到 {header}");
    }
}
#[tokio::test]
async fn absolute_external_media_has_no_platform_credentials() {
    let (external, task) = server(vec![Reply::json("asset")]).await;
    let adapter = HttpAdapter::new(
        BaseUrl::parse("https://platform.invalid/prefix/").unwrap(),
        1000,
    )
    .unwrap();
    adapter
        .media(&format!("{external}/asset"), Some(identity()))
        .await
        .unwrap();
    let request = collected(task).await[0].to_lowercase();
    assert!(!request.contains("fixture-token"));
    assert!(!request.contains("x-user-id"));
    assert!(adapter.base_url().media_url("file:///tmp/secret").is_err());
    assert!(
        adapter
            .base_url()
            .media_url("https://user:secret@external.test/a")
            .is_err()
    );
}
#[test]
fn thumbnail_fallback_keeps_the_original_signature() {
    let info = MediaInfo {
        media_id: "m".into(),
        media_type: MediaType::Image,
        mime_type: "image/jpeg".into(),
        size_bytes: 10,
        url: Some("/media/m/content?signature=original&variant=original".into()),
        thumb_url: None,
        duration_seconds: None,
        width: None,
        height: None,
        file_name: None,
        status: "READY".into(),
    };
    assert_eq!(
        HttpAdapter::media_address(&info, MediaVariant::Thumb).unwrap(),
        info.url.as_deref().unwrap()
    );
}

#[tokio::test]
async fn opaque_dot_identifiers_reach_http_server_as_encoded_segments_without_path_normalization() {
    let (address, task) = server(vec![
        Reply::json(r#"{"code":0,"data":null}"#),
        Reply::json(r#"{"code":5002,"message":"fixture-token","data":null}"#),
    ])
    .await;
    let adapter =
        HttpAdapter::new(BaseUrl::parse(&format!("{address}/prefix")).unwrap(), 1000).unwrap();
    for id in [".", ".."] {
        let mut input = request();
        input.path = vec!["users".into(), id.into(), "profile".into()];
        let result = adapter.json(input).await;
        if id == "." {
            assert!(result.unwrap().is_null());
        } else {
            let error = result.unwrap_err();
            assert_eq!(error.server_code, Some(5002));
            assert!(!error.message.contains("fixture-token"));
        }
    }
    let requests = collected(task).await;
    assert!(requests[0].starts_with("POST /prefix/users/%2E/profile?limit=50 HTTP/1.1"));
    assert!(requests[1].starts_with("POST /prefix/users/%2E%2E/profile?limit=50 HTTP/1.1"));
    assert!(requests.iter().all(|r| {
        r.to_lowercase()
            .contains("authorization: bearer fixture-token")
    }));
}
