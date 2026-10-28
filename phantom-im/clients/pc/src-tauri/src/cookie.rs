use axum::http::HeaderValue;

pub fn rewrite_set_cookie(
    value: &str,
) -> Result<HeaderValue, axum::http::header::InvalidHeaderValue> {
    let rewritten = value
        .split(';')
        .filter_map(|part| {
            let trimmed = part.trim();
            let name = trimmed
                .split_once('=')
                .map(|(name, _)| name.trim())
                .unwrap_or(trimmed)
                .to_ascii_lowercase();
            (!matches!(name.as_str(), "secure" | "domain")).then_some(trimmed)
        })
        .collect::<Vec<_>>()
        .join("; ");
    HeaderValue::from_str(&rewritten)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_http_only_same_site_path_and_expiry() {
        let value = rewrite_set_cookie(
            "PHANTOM_WEB_IM_SESSION=secret; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800; Secure; Domain=example.com",
        )
        .unwrap();
        let text = value.to_str().unwrap();
        assert!(text.contains("PHANTOM_WEB_IM_SESSION=secret"));
        assert!(text.contains("HttpOnly"));
        assert!(text.contains("SameSite=Lax"));
        assert!(text.contains("Path=/"));
        assert!(text.contains("Max-Age=604800"));
        assert!(!text.to_ascii_lowercase().contains("secure"));
        assert!(!text.to_ascii_lowercase().contains("domain="));
    }
}
