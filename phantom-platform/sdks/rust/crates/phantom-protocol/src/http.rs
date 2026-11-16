use phantom_domain::{ErrorKind, OperationOutcome, SdkError, SdkResult};
use phantom_ports::HttpResponse;
use serde_json::Value;

pub fn redact(value: &str, secrets: &[&str]) -> String {
    secrets
        .iter()
        .filter(|s| !s.is_empty())
        .fold(value.to_owned(), |value, secret| {
            value.replace(secret, "[REDACTED]")
        })
}

/// 按服务端 ApiResponse 的 snake_case JSON 解析；不把缺失业务包当作成功。
pub fn decode_api_response(
    response: &HttpResponse,
    request_id: Option<&str>,
    secrets: &[&str],
) -> SdkResult<Value> {
    let parsed = serde_json::from_slice::<Value>(&response.body).ok();
    let header_id = response
        .headers
        .iter()
        .find(|(name, _)| name.eq_ignore_ascii_case("x-request-id"))
        .map(|(_, value)| value.as_str());
    let correlation = parsed
        .as_ref()
        .and_then(|v| v.get("request_id"))
        .and_then(Value::as_str)
        .or(header_id)
        .or(request_id);
    let context = |kind, message: &str, outcome| {
        let mut error = SdkError::new(kind, redact(message, secrets));
        error.http_status = Some(response.status);
        error.request_id = correlation.map(|s| redact(s, secrets));
        error.outcome = outcome;
        error
    };
    let code = parsed
        .as_ref()
        .and_then(|v| v.get("code"))
        .and_then(Value::as_i64)
        .and_then(|n| i32::try_from(n).ok());
    if let Some(code) = code.filter(|n| *n != 0) {
        let kind = if (2000..3000).contains(&code) || response.status == 401 {
            ErrorKind::Authentication
        } else {
            ErrorKind::Business
        };
        let message = parsed
            .as_ref()
            .and_then(|v| v.get("message"))
            .and_then(Value::as_str)
            .unwrap_or("平台拒绝了请求");
        let mut error = context(kind, message, OperationOutcome::Rejected);
        error.server_code = Some(code);
        error.retryable = code == 1003;
        return Err(error);
    }
    if !(200..300).contains(&response.status) {
        let kind = if matches!(response.status, 401 | 403) {
            ErrorKind::Authentication
        } else {
            ErrorKind::Network
        };
        let mut error = context(
            kind,
            "HTTP 状态表示请求失败",
            if response.status < 500 {
                OperationOutcome::Rejected
            } else {
                OperationOutcome::Unknown
            },
        );
        error.server_code = code;
        error.retryable = response.status >= 500 || response.status == 429;
        return Err(error);
    }
    let Some(value) = parsed.as_ref() else {
        return Err(context(
            ErrorKind::Protocol,
            "HTTP 响应不是合法的 ApiResponse JSON",
            OperationOutcome::Unknown,
        ));
    };
    if code != Some(0) {
        return Err(context(
            ErrorKind::Protocol,
            "HTTP 响应缺少合法业务码",
            OperationOutcome::Unknown,
        ));
    }
    value.get("data").cloned().ok_or_else(|| {
        context(
            ErrorKind::Protocol,
            "HTTP 响应缺少 data 字段",
            OperationOutcome::Unknown,
        )
    })
}
