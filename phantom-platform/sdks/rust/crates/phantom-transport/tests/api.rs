use phantom_domain::*;
use phantom_ports::*;
use phantom_transport::HttpPlatformApi;
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, VecDeque},
    sync::{Arc, Mutex},
};
#[derive(Default)]
struct HttpMock {
    responses: Mutex<VecDeque<SdkResult<HttpResponse>>>,
    requests: Mutex<Vec<HttpRequest>>,
}
impl Http for HttpMock {
    fn execute(&self, input: HttpRequest) -> SdkFuture<'_, HttpResponse> {
        Box::pin(async {
            self.requests.lock().unwrap().push(input);
            self.responses
                .lock()
                .unwrap()
                .pop_front()
                .expect("未计划的 HTTP 请求")
        })
    }
}
fn identity() -> HttpIdentity {
    HttpIdentity {
        user_token: "secret-token".into(),
        app_id: "app".into(),
        user_id: "user".into(),
        device_id: "device".into(),
    }
}
fn response(data: Value) -> SdkResult<HttpResponse> {
    Ok(HttpResponse {
        status: 200,
        headers: BTreeMap::new(),
        body: serde_json::to_vec(&json!({"code":0,"data":data})).unwrap(),
    })
}
#[tokio::test]
async fn typed_api_attaches_identity_and_preserves_context_when_dto_is_broken() {
    let http = Arc::new(HttpMock::default());
    let api = HttpPlatformApi::new(http.clone());
    http.responses
        .lock()
        .unwrap()
        .push_back(response(json!({"missing":"fields"})));
    let error = api
        .execute(
            ApiRequest::GetUserProfile { user_id: None },
            identity(),
            "request-1".into(),
        )
        .await
        .unwrap_err();
    assert_eq!(error.kind, ErrorKind::Protocol);
    assert_eq!(error.request_id.as_deref(), Some("request-1"));
    assert_eq!(error.http_status, Some(200));
    let requests = http.requests.lock().unwrap();
    assert_eq!(requests[0].identity.as_ref(), Some(&identity()));
    assert_eq!(requests[0].request_id.as_deref(), Some("request-1"));
    assert_eq!(requests[0].path, ["users", "me"]);
}
#[tokio::test]
async fn upload_complete_uses_request_identity_for_nullable_upload_id_and_fetches_authoritative_media()
 {
    let http = Arc::new(HttpMock::default());
    let api = HttpPlatformApi::new(http.clone());
    http.responses.lock().unwrap().extend([
 response(json!({"upload_id":null,"part_size":2,"size_bytes":5,"total_parts":3,"uploaded_parts":[1,2,3],"instant":false,"media_id":"media","url":"/stale","thumb_url":null})),
 response(json!({"media_id":"media","media_type":"FILE","mime_type":"application/octet-stream","size_bytes":"5","url":"/signed?sig=a%2Fb","thumb_url":null,"duration_seconds":null,"width":null,"height":null,"file_name":"raw.bin","status":"READY"})),
 ]);
    let ApiResponse::CompleteUpload(result) = api
        .execute(
            ApiRequest::CompleteUpload {
                upload_id: "upload".into(),
            },
            identity(),
            "complete-request".into(),
        )
        .await
        .unwrap()
    else {
        panic!()
    };
    assert_eq!(result.session.upload_id, "upload");
    assert_eq!(result.media.size_bytes, 5);
    assert_eq!(result.media.url.as_deref(), Some("/signed?sig=a%2Fb"));
    let requests = http.requests.lock().unwrap();
    assert_eq!(requests.len(), 2);
    assert_eq!(requests[0].path, ["media", "upload", "upload", "complete"]);
    assert_eq!(requests[1].path, ["media", "media"]);
    assert!(
        requests
            .iter()
            .all(|r| r.request_id.as_deref() == Some("complete-request"))
    );
}
#[tokio::test]
async fn network_timeout_and_business_failure_are_never_retried_or_stripped() {
    let http = Arc::new(HttpMock::default());
    let api = HttpPlatformApi::new(http.clone());
    let mut timeout = SdkError::new(ErrorKind::Timeout, "secret-token network detail");
    timeout.outcome = OperationOutcome::Unknown;
    http.responses.lock().unwrap().push_back(Err(timeout));
    let error = api
        .execute(
            ApiRequest::ExportAccount,
            identity(),
            "network-request".into(),
        )
        .await
        .unwrap_err();
    assert_eq!(error.kind, ErrorKind::Timeout);
    assert_eq!(error.request_id.as_deref(), Some("network-request"));
    assert!(!error.message.contains("secret-token"));
    http.responses.lock().unwrap().push_back(Ok(HttpResponse {
        status: 200,
        headers: BTreeMap::new(),
        body: br#"{"code":5002,"message":"denied secret-token","data":null}"#.to_vec(),
    }));
    let error = api
        .execute(
            ApiRequest::ExportAccount,
            identity(),
            "business-request".into(),
        )
        .await
        .unwrap_err();
    assert_eq!(error.server_code, Some(5002));
    assert_eq!(error.outcome, OperationOutcome::Rejected);
    assert_eq!(error.http_status, Some(200));
    assert!(!error.message.contains("secret-token"));
    assert_eq!(http.requests.lock().unwrap().len(), 2);
}
