use crate::{
    Frame, auth_request, decode_auth, decode_frame, decode_message, decode_sync_event,
    decode_sync_page, encode_frame, encode_message, raw, wire,
};
use phantom_domain::*;
use phantom_ports::*;
use prost::Message;
fn malformed() -> SdkError {
    SdkError::new(ErrorKind::Protocol, "长连接领域响应损坏或不匹配")
}
fn decode<T: Message + Default>(bytes: &[u8]) -> SdkResult<T> {
    T::decode(bytes).map_err(|_| malformed())
}
pub fn gateway_command(request: &GatewayRequest) -> (wire::Command, Option<wire::Command>) {
    use wire::Command as C;
    match request {
        GatewayRequest::Authenticate { .. } => (C::AuthReq, Some(C::AuthResp)),
        GatewayRequest::Send(_) => (C::SendMsgReq, Some(C::SendMsgResp)),
        GatewayRequest::Recall(_) => (C::RecallMsgReq, Some(C::RecallMsgResp)),
        GatewayRequest::Sync { .. } => (C::SyncReq, Some(C::SyncResp)),
        GatewayRequest::Ping => (C::Ping, Some(C::Pong)),
        GatewayRequest::Logout => (C::LogoutReq, Some(C::LogoutResp)),
        GatewayRequest::Typing { .. } => (C::TypingSignal, None),
        GatewayRequest::SubscribePresence(_) => (C::PresenceSubReq, Some(C::PresenceSubResp)),
        GatewayRequest::UnsubscribePresence(_) => (C::PresenceUnsubReq, None),
        GatewayRequest::AppState(_) => (C::AppStateReq, Some(C::AppStateResp)),
    }
}
pub fn encode_gateway_request(request: &GatewayRequest, cid: u64) -> SdkResult<Vec<u8>> {
    let (command, _) = gateway_command(request);
    Ok(match request {
        GatewayRequest::Authenticate { app_id, input, .. } => {
            encode_frame(command, cid, &auth_request(app_id, input))
        }
        GatewayRequest::Send(message) => {
            let m = encode_message(message)?;
            encode_frame(
                command,
                cid,
                &wire::SendMsgReq {
                    client_msg_id: m.client_msg_id,
                    conv_id: m.conv_id,
                    conv_type: m.conv_type,
                    msg_type: m.msg_type,
                    content: m.content,
                    reply_to_msg_id: m.reply_to_msg_id,
                    mentions: m.mentions,
                },
            )
        }
        GatewayRequest::Recall(id) => {
            encode_frame(command, cid, &wire::RecallMsgReq { msg_id: id.clone() })
        }
        GatewayRequest::Sync { since, limit } => encode_frame(
            command,
            cid,
            &wire::SyncReq {
                since_user_seq: *since,
                limit: *limit,
            },
        ),
        GatewayRequest::Ping => encode_frame(command, cid, &wire::Ping {}),
        GatewayRequest::Logout => encode_frame(command, cid, &wire::LogoutReq {}),
        GatewayRequest::Typing { conv_id, typing } => encode_frame(
            command,
            cid,
            &wire::TypingSignal {
                conv_id: conv_id.clone(),
                typing: *typing,
            },
        ),
        GatewayRequest::SubscribePresence(ids) => encode_frame(
            command,
            cid,
            &wire::PresenceSubReq {
                user_ids: ids.clone(),
            },
        ),
        GatewayRequest::UnsubscribePresence(ids) => encode_frame(
            command,
            cid,
            &wire::PresenceUnsubReq {
                user_ids: ids.clone(),
            },
        ),
        GatewayRequest::AppState(foreground) => encode_frame(
            command,
            cid,
            &wire::AppStateReq {
                foreground: *foreground,
            },
        ),
    })
}
pub fn frame_result(frame: &Frame) -> SdkResult<()> {
    if frame.code == 0 {
        return Ok(());
    }
    let mut error = SdkError::new(
        if (2000..3000).contains(&frame.code) {
            ErrorKind::Authentication
        } else {
            ErrorKind::Business
        },
        "平台拒绝了长连接请求",
    );
    error.server_code = Some(frame.code);
    error.outcome = OperationOutcome::Rejected;
    error.retryable = frame.code == 1003;
    Err(error)
}
pub fn decode_confirmation(frame: &Frame) -> SdkResult<MessageConfirmation> {
    frame_result(frame)?;
    let value: wire::SendMsgResp = decode(&frame.payload)?;
    if value.msg_id.is_empty() || value.conv_seq <= 0 || value.server_time < 0 {
        return Err(malformed());
    }
    Ok(MessageConfirmation {
        msg_id: value.msg_id,
        conv_seq: value.conv_seq,
        server_time: value.server_time,
    })
}
fn presence(v: wire::PresenceInfo) -> Presence {
    Presence {
        user_id: v.user_id,
        status: match v.status {
            1 => PresenceStatus::Online,
            2 => PresenceStatus::Away,
            3 => PresenceStatus::Offline,
            n => PresenceStatus::Unknown(n),
        },
        last_active_time: if v.status == 2 && v.last_active_time > 0 {
            Some(v.last_active_time)
        } else {
            None
        },
    }
}
pub fn decode_gateway_response(
    request: &GatewayRequest,
    frame: &Frame,
) -> SdkResult<GatewayResponse> {
    frame_result(frame)?;
    if gateway_command(request).1.map(|v| v as i32) != Some(frame.command) {
        return Err(malformed());
    }
    Ok(match request {
        GatewayRequest::Authenticate { session, .. } => {
            GatewayResponse::Authenticated(decode_auth(&frame.payload, session.clone())?)
        }
        GatewayRequest::Send(_) => GatewayResponse::Sent(decode_confirmation(frame)?),
        GatewayRequest::Recall(id) => {
            let value: wire::RecallMsgResp = decode(&frame.payload)?;
            if value.msg_id != *id {
                return Err(malformed());
            }
            GatewayResponse::Recalled(RecallResult {
                msg_id: value.msg_id,
            })
        }
        GatewayRequest::Sync { .. } => {
            let page = decode_sync_page(&frame.payload)?;
            GatewayResponse::Sync(SyncBatch {
                events: page.events.into_iter().map(|v| v.event).collect(),
                max_user_seq: page.max_user_seq,
                has_more: page.has_more,
                full_resync: page.full_resync,
            })
        }
        GatewayRequest::SubscribePresence(_) => {
            let value: wire::PresenceSubResp = decode(&frame.payload)?;
            GatewayResponse::Presence(value.presences.into_iter().map(presence).collect())
        }
        GatewayRequest::Ping => {
            let _: wire::Pong = decode(&frame.payload)?;
            GatewayResponse::Done
        }
        GatewayRequest::Logout => {
            let _: wire::LogoutResp = decode(&frame.payload)?;
            GatewayResponse::Done
        }
        GatewayRequest::AppState(_) => {
            let _: wire::AppStateResp = decode(&frame.payload)?;
            GatewayResponse::Done
        }
        _ => return Err(malformed()),
    })
}
pub fn decode_gateway_push(encoded: &[u8]) -> SdkResult<Option<GatewayPush>> {
    let frame = decode_frame(encoded)?;
    if frame.cid != 0 {
        return Err(malformed());
    }
    use wire::Command as C;
    let command = C::try_from(frame.command).ok();
    Ok(match command {
        Some(C::MsgPush) => {
            let v: wire::MsgPush = decode(&frame.payload)?;
            let bytes = raw::message_bytes(&frame.payload, 1)?;
            let msg = decode_message(&bytes)?;
            GatewayPush::Sync {
                event: Box::new(SyncEvent {
                    user_seq: v.user_seq,
                    event_time: msg.server_time.unwrap_or(0),
                    payload: SyncPayload::NewMessage(NewMessageEvent {
                        conv_id: msg.conv_id.clone(),
                        conv_seq: msg.conv_seq.ok_or_else(malformed)?,
                        msg_id: msg.msg_id.clone().ok_or_else(malformed)?,
                        is_request: msg.is_request,
                        msg: Some(msg),
                    }),
                }),
                ack_required: true,
            }
        }
        Some(C::SyncEventPush) => {
            let bytes = raw::message_bytes(&frame.payload, 1)?;
            GatewayPush::Sync {
                event: Box::new(decode_sync_event(&bytes)?.event),
                ack_required: false,
            }
        }
        Some(C::RecallPush) => {
            let v: wire::RecallPush = decode(&frame.payload)?;
            GatewayPush::Sync {
                event: Box::new(SyncEvent {
                    user_seq: v.user_seq,
                    event_time: 0,
                    payload: SyncPayload::Recall(RecallEvent {
                        msg_id: v.msg_id,
                        conv_id: v.conv_id,
                        operator_id: v.operator_id,
                    }),
                }),
                ack_required: false,
            }
        }
        Some(C::ReadReceiptPush) => {
            let v: wire::ReadReceiptPush = decode(&frame.payload)?;
            let r = v.receipt.ok_or_else(malformed)?;
            GatewayPush::Sync {
                event: Box::new(SyncEvent {
                    user_seq: v.user_seq,
                    event_time: 0,
                    payload: SyncPayload::ReadReceipt(ReadReceiptEvent {
                        conv_id: r.conv_id,
                        read_conv_seq: r.read_conv_seq,
                        reader_id: r.reader_id,
                        read_count: r.read_count,
                    }),
                }),
                ack_required: false,
            }
        }
        Some(C::KickPush) => {
            let v: wire::KickPush = decode(&frame.payload)?;
            GatewayPush::Kicked(KickInfo {
                reason: match v.reason {
                    1 => KickReason::SameTypeLogin,
                    2 => KickReason::RemoteLogout,
                    3 => KickReason::TokenRevoked,
                    4 => KickReason::Banned,
                    n => KickReason::Unknown(n),
                },
                detail: v.detail,
            })
        }
        Some(C::TypingPush) => {
            let v: wire::TypingPush = decode(&frame.payload)?;
            GatewayPush::Typing(TypingEvent {
                conv_id: v.conv_id,
                user_id: v.user_id,
                typing: v.typing,
            })
        }
        Some(C::PresencePush) => {
            let v: wire::PresencePush = decode(&frame.payload)?;
            GatewayPush::Presence(presence(v.presence.ok_or_else(malformed)?))
        }
        _ => return Ok(None),
    }
    .into())
}
