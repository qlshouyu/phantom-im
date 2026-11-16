//! 用类型生成的 JSON Schema 处理 CLI 边界，SDK 中的整数和字节类型保持原状。
use base64::{Engine, engine::general_purpose::STANDARD};
use phantom_sdk::*;
use schemars::JsonSchema;
use serde::{Serialize, de::DeserializeOwned};
use serde_json::{Value, json};

pub fn invalid(message: impl Into<String>) -> SdkError {
    SdkError::new(ErrorKind::InvalidArgument, message)
}
pub fn raw_schema<T: JsonSchema>() -> Value {
    serde_json::to_value(schemars::schema_for!(T)).expect("JSON schema 可序列化")
}
pub fn schema<T: JsonSchema>() -> Value {
    let mut value = raw_schema::<T>();
    rewrite(&mut value);
    value
}
fn wide(s: &Value) -> bool {
    matches!(s["format"].as_str(), Some("int64" | "uint64"))
}
fn binary(s: &Value) -> bool {
    s["type"] == "array" && s["items"]["format"] == "uint8"
}
fn rewrite(s: &mut Value) {
    if wide(s) {
        let unsigned = s["format"] == "uint64";
        let object = s.as_object_mut().unwrap();
        object.remove("minimum");
        object.remove("maximum");
        object.insert("type".into(), json!("string"));
        object.insert(
            "pattern".into(),
            json!(if unsigned {
                "^(0|[1-9][0-9]*)$"
            } else {
                "^(0|-?[1-9][0-9]*)$"
            }),
        );
        return;
    }
    if binary(s) {
        *s = json!({"type":"string","contentEncoding":"base64"});
        return;
    }
    match s {
        Value::Object(map) => {
            for child in map.values_mut() {
                rewrite(child);
            }
        }
        Value::Array(items) => {
            for child in items {
                rewrite(child);
            }
        }
        _ => {}
    }
}
pub fn decode<T: JsonSchema + DeserializeOwned>(value: Value) -> SdkResult<T> {
    let root = raw_schema::<T>();
    let value = convert(value, &root, &root, true, 0)?;
    serde_json::from_value(value).map_err(|_| {
        invalid("输入与方法类型不匹配，请查询 schema；未知字段、无效枚举和越界数值均被拒绝")
    })
}
pub fn encode<T: JsonSchema + Serialize>(value: &T) -> SdkResult<Value> {
    let root = raw_schema::<T>();
    convert(
        serde_json::to_value(value)
            .map_err(|_| SdkError::new(ErrorKind::Protocol, "结果无法序列化"))?,
        &root,
        &root,
        false,
        0,
    )
}
fn shape(value: &Value, schema: &Value, root: &Value) -> bool {
    if let Some(reference) = schema["$ref"].as_str() {
        return shape(
            value,
            root.pointer(reference.trim_start_matches('#'))
                .unwrap_or(&Value::Null),
            root,
        );
    }
    if schema.get("const").is_some_and(|v| v != value) {
        return false;
    }
    if let Some(values) = schema["enum"].as_array() {
        return values.contains(value);
    }
    if let Some(required) = schema["required"].as_array()
        && (!value.is_object()
            || required
                .iter()
                .filter_map(Value::as_str)
                .any(|key| value.get(key).is_none()))
    {
        return false;
    }
    if let Some(properties) = schema["properties"].as_object() {
        for (key, sub) in properties {
            if let Some(value) = value.get(key)
                && !shape(value, sub, root)
            {
                return false;
            }
        }
    }
    match schema["type"].as_str() {
        Some("null") => value.is_null(),
        Some("string") => value.is_string(),
        Some("object") => value.is_object(),
        Some("array") => value.is_array() || value.is_string(),
        Some("boolean") => value.is_boolean(),
        Some("number") => value.is_number(),
        Some("integer") => value.is_number() || value.is_string(),
        _ => true,
    }
}
fn convert(
    mut value: Value,
    schema: &Value,
    root: &Value,
    input: bool,
    depth: usize,
) -> SdkResult<Value> {
    if depth > 96 {
        return Err(invalid("输入嵌套过深"));
    }
    if let Some(reference) = schema["$ref"].as_str() {
        let resolved = root
            .pointer(reference.trim_start_matches('#'))
            .ok_or_else(|| invalid("类型模式引用失效"))?;
        return convert(value, resolved, root, input, depth + 1);
    }
    if value.is_null() {
        return Ok(value);
    }
    if wide(schema) {
        return if input {
            let s = value
                .as_str()
                .ok_or_else(|| invalid("i64/u64 必须使用十进制字符串"))?;
            if schema["format"] == "uint64" {
                let n = s
                    .parse::<u64>()
                    .map_err(|_| invalid("u64 十进制字符串无效或越界"))?;
                if n.to_string() != s {
                    return Err(invalid("十进制字符串必须使用规范形式"));
                }
                Ok(json!(n))
            } else {
                let n = s
                    .parse::<i64>()
                    .map_err(|_| invalid("i64 十进制字符串无效或越界"))?;
                if n.to_string() != s {
                    return Err(invalid("十进制字符串必须使用规范形式"));
                }
                Ok(json!(n))
            }
        } else {
            Ok(Value::String(value.to_string()))
        };
    }
    if binary(schema) {
        return if input {
            let bytes = STANDARD
                .decode(
                    value
                        .as_str()
                        .ok_or_else(|| invalid("二进制字段必须为 base64 字符串"))?,
                )
                .map_err(|_| invalid("base64 无效"))?;
            Ok(json!(bytes))
        } else {
            let bytes: Vec<u8> =
                serde_json::from_value(value).map_err(|_| invalid("二进制结果格式无效"))?;
            Ok(json!(STANDARD.encode(bytes)))
        };
    }
    for keyword in ["anyOf", "oneOf"] {
        if let Some(branches) = schema[keyword].as_array() {
            let branch = branches
                .iter()
                .find(|s| shape(&value, s, root))
                .ok_or_else(|| invalid("输入不匹配任何公开类型分支"))?;
            return convert(value, branch, root, input, depth + 1);
        }
    }
    if let Some(values) = value.as_object_mut() {
        if let Some(properties) = schema["properties"].as_object() {
            if input
                && schema["additionalProperties"] == false
                && values.keys().any(|k| !properties.contains_key(k))
            {
                return Err(invalid("输入包含未知字段"));
            }
            for (name, sub) in properties {
                if let Some(v) = values.get_mut(name) {
                    *v = convert(v.take(), sub, root, input, depth + 1)?;
                }
            }
        } else if schema["additionalProperties"].is_object() {
            for v in values.values_mut() {
                *v = convert(
                    v.take(),
                    &schema["additionalProperties"],
                    root,
                    input,
                    depth + 1,
                )?;
            }
        }
    } else if let Some(values) = value.as_array_mut()
        && schema["items"].is_object()
    {
        for v in values {
            *v = convert(v.take(), &schema["items"], root, input, depth + 1)?;
        }
    }
    Ok(value)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::Deserialize;
    #[derive(Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
    #[serde(deny_unknown_fields)]
    struct Boundary {
        signed: i64,
        unsigned: u64,
        optional: Option<i64>,
        bytes: Vec<u8>,
    }
    #[test]
    fn schema_encoding_and_decoding_share_exact_integer_and_base64_contract() {
        let value = Boundary {
            signed: i64::MIN,
            unsigned: u64::MAX,
            optional: Some(9_007_199_254_740_993),
            bytes: vec![0, 255, 13, 10],
        };
        let encoded = encode(&value).unwrap();
        assert_eq!(
            encoded,
            json!({"signed":"-9223372036854775808","unsigned":"18446744073709551615","optional":"9007199254740993","bytes":"AP8NCg=="})
        );
        assert_eq!(decode::<Boundary>(encoded).unwrap(), value);
        let schema = schema::<Boundary>();
        assert_eq!(schema["properties"]["signed"]["type"], "string");
        assert_eq!(schema["properties"]["bytes"]["contentEncoding"], "base64");
        for invalid in [
            json!(0),
            json!("-0"),
            json!("01"),
            json!("1.0"),
            json!("+1"),
            json!("18446744073709551616"),
        ] {
            let input = json!({"signed":"1","unsigned":invalid,"optional":null,"bytes":""});
            assert!(decode::<Boundary>(input).is_err());
        }
    }
    #[test]
    fn optional_enum_payloads_preserve_unknown_bytes_and_wide_sequences() {
        let event = SyncEvent {
            user_seq: i64::MAX,
            event_time: i64::MAX,
            payload: SyncPayload::Unknown {
                field_number: 99,
                raw_bytes: vec![0, 255],
            },
        };
        let value = encode(&event).unwrap();
        assert_eq!(value["user_seq"], i64::MAX.to_string());
        assert_eq!(decode::<SyncEvent>(value).unwrap(), event);
    }
}
