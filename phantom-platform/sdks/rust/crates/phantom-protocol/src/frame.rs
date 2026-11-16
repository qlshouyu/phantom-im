use crate::{message::protocol, wire};
use phantom_domain as d;
use prost::Message;

#[derive(Clone, Debug)]
pub struct Frame {
    pub command: i32,
    pub cid: u64,
    pub code: i32,
    pub payload: Vec<u8>,
    pub raw_bytes: Vec<u8>,
}

pub fn decode_frame(encoded: &[u8]) -> d::SdkResult<Frame> {
    let value = wire::Envelope::decode(encoded).map_err(|_| protocol("Envelope Protobuf 损坏"))?;
    if value.version != 1 {
        return Err(protocol("Envelope 版本不兼容"));
    }
    Ok(Frame {
        command: value.cmd,
        cid: value.cid,
        code: value.code,
        payload: value.payload,
        raw_bytes: encoded.to_vec(),
    })
}

pub fn encode_frame(command: wire::Command, cid: u64, payload: &impl Message) -> Vec<u8> {
    wire::Envelope {
        version: 1,
        cmd: command as i32,
        cid,
        code: 0,
        payload: payload.encode_to_vec(),
    }
    .encode_to_vec()
}

pub fn auth_request(app_id: &str, input: &d::LoginOptions) -> wire::AuthReq {
    wire::AuthReq {
        access_token: input.user_token.clone(),
        device_id: input.device_id.clone(),
        device_type: match input.device_type {
            d::DeviceType::Mobile => 1,
            d::DeviceType::Desktop => 2,
            d::DeviceType::Web => 3,
        },
        platform: input.platform.clone(),
        client_version: input.client_version.clone().unwrap_or_default(),
        app_id: app_id.into(),
        user_id: input.user_id.clone(),
    }
}

pub fn decode_auth(encoded: &[u8], session: d::SessionInfo) -> d::SdkResult<d::LoginResult> {
    let value = wire::AuthResp::decode(encoded).map_err(|_| protocol("认证响应 Protobuf 损坏"))?;
    if value.max_user_seq < 0 || value.heartbeat_interval_s == 0 {
        return Err(protocol("认证同步目标或心跳周期无效"));
    }
    let burn = value
        .burn_after_read
        .ok_or_else(|| protocol("认证缺少阅后即焚配置"))?;
    let read = value
        .read_receipt
        .ok_or_else(|| protocol("认证缺少已读配置"))?;
    let features = value
        .app_features
        .ok_or_else(|| protocol("认证缺少应用配置"))?;
    Ok(d::LoginResult {
        session,
        server_time: value.server_time,
        max_user_seq: value.max_user_seq,
        heartbeat_interval_s: value.heartbeat_interval_s,
        app_config: d::AppConfig {
            burn_after_read: d::BurnAfterReadConfig {
                enabled: burn.enabled,
                ttl_seconds: burn.ttl_seconds,
            },
            read_receipt_enabled: read.enabled,
            relation_hosted: features.relation_hosted,
            sdk_profile_write_enabled: features.sdk_profile_write_enabled,
            max_devices: features.max_devices,
        },
    })
}
