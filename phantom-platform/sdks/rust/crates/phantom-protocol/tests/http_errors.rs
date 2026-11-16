use phantom_domain::*;
use phantom_ports::HttpResponse;
use phantom_protocol::decode_api_response;
use std::collections::BTreeMap;

fn response(status: u16, body: &str) -> HttpResponse {
    HttpResponse {
        status,
        headers: BTreeMap::from([("X-Request-Id".into(), "header-request".into())]),
        body: body.as_bytes().to_vec(),
    }
}
#[test]
fn business_and_authentication_errors_preserve_context_even_on_http_success() {
    for (status, code, kind) in [
        (200, 5002, ErrorKind::Business),
        (403, 5002, ErrorKind::Business),
        (200, 2001, ErrorKind::Authentication),
        (401, 2003, ErrorKind::Authentication),
    ] {
        let body = format!(
            r#"{{"code":{code},"message":"拒绝 fixture-token","request_id":"body-request","data":null}}"#
        );
        let error = decode_api_response(
            &response(status, &body),
            Some("client-request"),
            &["fixture-token"],
        )
        .unwrap_err();
        assert_eq!(error.kind, kind);
        assert_eq!(error.server_code, Some(code));
        assert_eq!(error.http_status, Some(status));
        assert_eq!(error.request_id.as_deref(), Some("body-request"));
        assert_eq!(error.outcome, OperationOutcome::Rejected);
        assert!(!error.message.contains("fixture-token"));
    }
}
#[test]
fn malformed_and_missing_business_envelopes_never_become_success() {
    for body in [
        "",
        "[]",
        "not json",
        r#"{"data":1}"#,
        r#"{"code":"0","data":1}"#,
        r#"{"code":0}"#,
    ] {
        let error = decode_api_response(&response(200, body), None, &[]).unwrap_err();
        assert_eq!(error.kind, ErrorKind::Protocol);
        assert_eq!(error.http_status, Some(200));
        assert_eq!(error.request_id.as_deref(), Some("header-request"));
    }
    assert_eq!(
        decode_api_response(&response(200, r#"{"code":0,"data":null}"#), None, &[]).unwrap(),
        serde_json::Value::Null
    );
}
#[test]
fn non_json_http_failures_keep_status_and_uncertainty() {
    let error = decode_api_response(&response(503, "upstream unavailable"), None, &[]).unwrap_err();
    assert_eq!(error.kind, ErrorKind::Network);
    assert_eq!(error.http_status, Some(503));
    assert_eq!(error.server_code, None);
    assert_eq!(error.outcome, OperationOutcome::Unknown);
    assert!(error.retryable);
    let error = decode_api_response(&response(403, "denied"), None, &[]).unwrap_err();
    assert_eq!(error.kind, ErrorKind::Authentication);
    assert_eq!(error.outcome, OperationOutcome::Rejected);
}
