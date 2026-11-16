use phantom_domain::*;
use serde::de::DeserializeOwned;
use serde_json::{Value, json};
fn invalid() -> SdkError {
    SdkError::new(ErrorKind::Protocol, "HTTP 领域响应字段不完整或类型无效")
}
/// HTTP DTO 使用 snake_case；整数仅经精确的十进制解析，不转为浮点数。
pub fn decode_http_dto<T: DeserializeOwned>(mut value: Value) -> SdkResult<T> {
    normalize(&mut value)?;
    serde_json::from_value(value).map_err(|_| invalid())
}
fn normalize(value: &mut Value) -> SdkResult<()> {
    match value {
        Value::Array(values) => {
            for value in values {
                normalize(value)?;
            }
        }
        Value::Object(fields) => {
            for (key, value) in fields {
                if key == "ext" || key == "params" {
                    continue;
                }
                match key.as_str() {
                    "conv_type" => {
                        let number = match value.as_str() {
                            Some("CONV_TYPE_SINGLE" | "SINGLE") => 1,
                            Some("CONV_TYPE_GROUP" | "GROUP") => 2,
                            Some("CONV_TYPE_CHANNEL") => 3,
                            // 拒绝陌生人请求时，ConversationAppService 返回空类型的只读快照。
                            Some("CONV_TYPE_UNSPECIFIED" | "") => 0,
                            Some(s) => s.parse::<i32>().map_err(|_| invalid())?,
                            None => i32::try_from(value.as_i64().ok_or_else(invalid)?)
                                .map_err(|_| invalid())?,
                        };
                        *value = match number {
                            1 => json!("Single"),
                            2 => json!("Group"),
                            n => json!({"Unknown": n}),
                        };
                    }
                    "role" => {
                        let raw = value.as_str().ok_or_else(invalid)?;
                        *value = match raw {
                            "OWNER" => json!("Owner"),
                            "ADMIN" => json!("Admin"),
                            "MEMBER" => json!("Member"),
                            raw => json!({"Unknown": raw}),
                        };
                    }
                    "device_type" => {
                        *value = match value.as_i64() {
                            Some(1) => json!("Mobile"),
                            Some(2) => json!("Desktop"),
                            Some(3) => json!("Web"),
                            _ => return Err(invalid()),
                        };
                    }
                    "media_type" => {
                        *value = match value.as_str() {
                            Some("IMAGE") => json!("Image"),
                            Some("VOICE") => json!("Voice"),
                            Some("VIDEO") => json!("Video"),
                            Some("FILE") => json!("File"),
                            _ => return Err(invalid()),
                        };
                    }
                    "conv_seq" | "server_time" | "created_at" | "updated_at" | "edited_at"
                    | "last_message_at" | "pinned_at" | "draft_edited_at" | "max_conv_seq"
                    | "read_conv_seq" | "max_user_seq" | "muted_until" | "handled_at"
                    | "expires_at" | "login_at" | "last_active_at" | "version" => {
                        if let Some(raw) = value.as_str() {
                            *value = json!(raw.parse::<i64>().map_err(|_| invalid())?);
                        }
                        if value.as_i64().is_some_and(|n| n < 0) {
                            return Err(invalid());
                        }
                    }
                    "size_bytes" | "part_size" | "part_number" | "total_parts" | "member_count"
                    | "unread_count" | "badge_count" | "friend_limit" | "contact_total"
                    | "group_total" | "message_total" | "total" | "width" | "height"
                    | "duration_seconds" => {
                        if let Some(raw) = value.as_str() {
                            *value = json!(raw.parse::<u64>().map_err(|_| invalid())?);
                        }
                    }
                    _ => normalize(value)?,
                }
            }
        }
        _ => {}
    }
    Ok(())
}
