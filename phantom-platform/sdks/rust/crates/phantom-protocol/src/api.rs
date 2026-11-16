//! 按服务端 Controller/DTO 映射公开领域请求；与 CLI/Rust 序列化形式分离。
use crate::{decode_history, decode_http_dto};
use phantom_domain::*;
use phantom_ports::*;
use serde::Serialize;
use serde_json::{Value, json};
fn value(input: &impl Serialize) -> SdkResult<Value> {
    serde_json::to_value(input)
        .map_err(|_| SdkError::new(ErrorKind::InvalidArgument, "请求字段无法编码"))
}
fn role_name(role: &AssignableGroupRole) -> &'static str {
    match role {
        AssignableGroupRole::Admin => "ADMIN",
        AssignableGroupRole::Member => "MEMBER",
    }
}
fn device_number(device: &DeviceType) -> i32 {
    match device {
        DeviceType::Mobile => 1,
        DeviceType::Desktop => 2,
        DeviceType::Web => 3,
    }
}
fn mute_target(target: &MuteTarget) -> String {
    match target {
        MuteTarget::All => "ALL".into(),
        MuteTarget::User(id) => id.clone(),
    }
}
fn mute_duration(duration: &MuteDuration) -> Option<u64> {
    match duration {
        MuteDuration::TenMinutes => Some(600),
        MuteDuration::OneHour => Some(3600),
        MuteDuration::OneDay => Some(86400),
        MuteDuration::Permanent => None,
    }
}
fn upload_metadata(input: &UploadMetadata) -> Value {
    json!({ "file_name": input.file_name, "size_bytes": input.size_bytes, "sha256": input.sha256, "mime_type": input.mime_type, "media_type": match input.media_type { MediaType::Image => "IMAGE", MediaType::Voice => "VOICE", MediaType::Video => "VIDEO", MediaType::File => "FILE" }, "duration_seconds": input.duration_seconds, "width": input.width, "height": input.height, "burn_after_read": input.burn_after_read, "burn_after_read_ttl_seconds": input.burn_after_read_ttl_seconds })
}
fn history_query(query: &HistoryQuery) -> Vec<(String, String)> {
    let mut q = vec![("limit".into(), query.limit.to_string())];
    if let Some(before) = query.before_conv_seq {
        q.push(("before_conv_seq".into(), before.to_string()));
    }
    q
}
fn conversation_query(query: &ConversationQuery) -> Vec<(String, String)> {
    let mut q = vec![("limit".into(), query.limit.to_string())];
    if let Some(cursor) = &query.cursor {
        q.push(("cursor".into(), cursor.clone()));
    }
    q
}
fn search_query(query: &str, limit: u32) -> Vec<(String, String)> {
    vec![
        ("q".into(), query.into()),
        ("limit".into(), limit.to_string()),
    ]
}
fn json_body(value: Value) -> SdkResult<HttpBody> {
    serde_json::to_vec(&value)
        .map(HttpBody::Json)
        .map_err(|_| SdkError::new(ErrorKind::InvalidArgument, "请求字段无法编码"))
}
pub fn api_http_request(request: &ApiRequest) -> SdkResult<HttpRequest> {
    use ApiRequest::*;
    let (method, path, query, body) = match request {
        Forward { input } => (
            HttpMethod::Post,
            vec!["messages".into(), "forward".into()],
            Vec::new(),
            json_body(value(input)?)?,
        ),
        DeleteLocal { msg_id } => (
            HttpMethod::Delete,
            vec!["messages".into(), msg_id.to_string()],
            Vec::new(),
            HttpBody::Empty,
        ),
        ClearHistory { conv_id } => (
            HttpMethod::Delete,
            vec![
                "conversations".into(),
                conv_id.to_string(),
                "messages".into(),
            ],
            Vec::new(),
            HttpBody::Empty,
        ),
        History { query } => (
            HttpMethod::Get,
            vec![
                "conversations".into(),
                query.conv_id.to_string(),
                "messages".into(),
            ],
            history_query(query),
            HttpBody::Empty,
        ),
        InitUpload { input } => (
            HttpMethod::Post,
            vec!["media".into(), "upload".into(), "init".into()],
            Vec::new(),
            json_body(upload_metadata(input))?,
        ),
        UploadPart { input } => (
            HttpMethod::Put,
            vec![
                "media".into(),
                "upload".into(),
                input.upload_id.to_string(),
                "part".into(),
                input.part_number.to_string(),
            ],
            Vec::new(),
            HttpBody::Bytes(input.bytes.clone()),
        ),
        CompleteUpload { upload_id } => (
            HttpMethod::Post,
            vec![
                "media".into(),
                "upload".into(),
                upload_id.to_string(),
                "complete".into(),
            ],
            Vec::new(),
            HttpBody::Empty,
        ),
        GetMedia { media_id } => (
            HttpMethod::Get,
            vec!["media".into(), media_id.to_string()],
            Vec::new(),
            HttpBody::Empty,
        ),
        ListConversations { query } => (
            HttpMethod::Get,
            vec!["conversations".into()],
            conversation_query(query),
            HttpBody::Empty,
        ),
        GetConversation { conv_id } => (
            HttpMethod::Get,
            vec!["conversations".into(), conv_id.to_string()],
            Vec::new(),
            HttpBody::Empty,
        ),
        UpdateConversation { conv_id, patch } => (
            HttpMethod::Post,
            vec!["conversations".into(), conv_id.to_string()],
            Vec::new(),
            json_body(value(patch)?)?,
        ),
        DeleteConversation { conv_id } => (
            HttpMethod::Delete,
            vec!["conversations".into(), conv_id.to_string()],
            Vec::new(),
            HttpBody::Empty,
        ),
        MarkRead {
            conv_id,
            read_conv_seq,
        } => (
            HttpMethod::Put,
            vec![
                "conversations".into(),
                conv_id.to_string(),
                "read-positions".into(),
            ],
            Vec::new(),
            json_body(json!({"read_conv_seq": read_conv_seq}))?,
        ),
        GetReadPosition { conv_id } => (
            HttpMethod::Get,
            vec![
                "conversations".into(),
                conv_id.to_string(),
                "read-positions".into(),
            ],
            Vec::new(),
            HttpBody::Empty,
        ),
        SaveDraft { input } => (
            HttpMethod::Put,
            vec![
                "conversations".into(),
                input.conv_id.to_string(),
                "draft".into(),
            ],
            Vec::new(),
            json_body(json!({"draft": input.draft, "edited_at": input.edited_at}))?,
        ),
        ClearDraft { conv_id, edited_at } => (
            HttpMethod::Delete,
            vec!["conversations".into(), conv_id.to_string(), "draft".into()],
            Vec::new(),
            json_body(json!({"edited_at": edited_at}))?,
        ),
        HandleMessageRequest { conv_id, accept } => (
            HttpMethod::Post,
            vec![
                "conversations".into(),
                conv_id.to_string(),
                "request".into(),
            ],
            Vec::new(),
            json_body(json!({"accept": accept}))?,
        ),
        GetUserProfile { user_id } => (
            HttpMethod::Get,
            vec![
                "users".into(),
                user_id.clone().unwrap_or_else(|| "me".into()),
            ],
            Vec::new(),
            HttpBody::Empty,
        ),
        UpdateProfile { input } => (
            HttpMethod::Put,
            vec!["users".into(), "me".into()],
            Vec::new(),
            json_body(value(input)?)?,
        ),
        ListFriends => (
            HttpMethod::Get,
            vec!["friends".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        CreateFriendRequest { input } => (
            HttpMethod::Post,
            vec!["friends".into(), "requests".into()],
            Vec::new(),
            json_body(value(input)?)?,
        ),
        ListFriendRequests { include_handled } => (
            HttpMethod::Get,
            vec!["friends".into(), "requests".into()],
            vec![("include_handled".into(), include_handled.to_string())],
            HttpBody::Empty,
        ),
        HandleFriendRequest { request_id, action } => (
            HttpMethod::Post,
            vec!["friends".into(), "requests".into(), request_id.to_string()],
            Vec::new(),
            json_body(
                json!({"action": match action { FriendRequestAction::Accept => "ACCEPT", FriendRequestAction::Reject => "REJECT", FriendRequestAction::Ignore => "IGNORE" }}),
            )?,
        ),
        UpdateFriend { friend_id, patch } => (
            HttpMethod::Put,
            vec!["friends".into(), friend_id.to_string()],
            Vec::new(),
            json_body(value(patch)?)?,
        ),
        DeleteFriend { friend_id } => (
            HttpMethod::Delete,
            vec!["friends".into(), friend_id.to_string()],
            Vec::new(),
            HttpBody::Empty,
        ),
        ListBlacklist => (
            HttpMethod::Get,
            vec!["blacklist".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        BlockUser { user_id } => (
            HttpMethod::Post,
            vec!["blacklist".into()],
            Vec::new(),
            json_body(json!({"user_id": user_id}))?,
        ),
        UnblockUser { user_id } => (
            HttpMethod::Delete,
            vec!["blacklist".into()],
            Vec::new(),
            json_body(json!({"user_id": user_id}))?,
        ),
        ListFriendGroups => (
            HttpMethod::Get,
            vec!["friends".into(), "groups".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        CreateFriendGroup { name } => (
            HttpMethod::Post,
            vec!["friends".into(), "groups".into()],
            Vec::new(),
            json_body(json!({"name": name}))?,
        ),
        RenameFriendGroup { group_id, name } => (
            HttpMethod::Put,
            vec!["friends".into(), "groups".into(), group_id.to_string()],
            Vec::new(),
            json_body(json!({"name": name}))?,
        ),
        DeleteFriendGroup { group_id } => (
            HttpMethod::Delete,
            vec!["friends".into(), "groups".into(), group_id.to_string()],
            Vec::new(),
            HttpBody::Empty,
        ),
        GetQrCard => (
            HttpMethod::Get,
            vec!["friends".into(), "qr".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        RefreshQrCard => (
            HttpMethod::Post,
            vec!["friends".into(), "qr".into(), "refresh".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        ScanQrCard { token } => (
            HttpMethod::Post,
            vec!["friends".into(), "qr".into(), "scan".into()],
            Vec::new(),
            json_body(json!({"token": token}))?,
        ),
        GetRelationSettings => (
            HttpMethod::Get,
            vec!["friends".into(), "settings".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        UpdateRelationSettings { patch } => (
            HttpMethod::Put,
            vec!["friends".into(), "settings".into()],
            Vec::new(),
            json_body(value(patch)?)?,
        ),
        ListGroups => (
            HttpMethod::Get,
            vec!["groups".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        CreateGroup { input } => (
            HttpMethod::Post,
            vec!["groups".into()],
            Vec::new(),
            json_body(value(input)?)?,
        ),
        GetGroup { group_id } => (
            HttpMethod::Get,
            vec!["groups".into(), group_id.to_string()],
            Vec::new(),
            HttpBody::Empty,
        ),
        UpdateGroup { group_id, patch } => (
            HttpMethod::Put,
            vec!["groups".into(), group_id.to_string()],
            Vec::new(),
            json_body(value(patch)?)?,
        ),
        ListGroupMembers { group_id } => (
            HttpMethod::Get,
            vec!["groups".into(), group_id.to_string(), "members".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        InviteGroupMembers { group_id, user_ids } => (
            HttpMethod::Post,
            vec!["groups".into(), group_id.to_string(), "members".into()],
            Vec::new(),
            json_body(json!({"user_ids": user_ids}))?,
        ),
        RemoveGroupMember { group_id, user_id } => (
            HttpMethod::Delete,
            vec!["groups".into(), group_id.to_string(), "members".into()],
            vec![("user_id".into(), user_id.clone())],
            HttpBody::Empty,
        ),
        UpdateGroupRole {
            group_id,
            user_id,
            role,
        } => (
            HttpMethod::Put,
            vec![
                "groups".into(),
                group_id.to_string(),
                "members".into(),
                user_id.to_string(),
                "role".into(),
            ],
            Vec::new(),
            json_body(json!({"user_id": user_id, "role": role_name(role)}))?,
        ),
        TransferGroupOwner { input } => (
            HttpMethod::Post,
            vec![
                "groups".into(),
                input.group_id.to_string(),
                "transfer".into(),
            ],
            Vec::new(),
            json_body(
                json!({"user_id": input.user_id, "old_owner_role": role_name(&input.old_owner_role)}),
            )?,
        ),
        LeaveGroup { group_id } => (
            HttpMethod::Delete,
            vec!["groups".into(), group_id.to_string(), "leave".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        DissolveGroup { group_id } => (
            HttpMethod::Delete,
            vec!["groups".into(), group_id.to_string()],
            Vec::new(),
            HttpBody::Empty,
        ),
        MuteGroupMember { input } => (
            HttpMethod::Post,
            vec!["groups".into(), input.group_id.to_string(), "mutes".into()],
            Vec::new(),
            json_body(
                json!({"user_id": mute_target(&input.target), "duration_seconds": mute_duration(&input.duration)}),
            )?,
        ),
        UnmuteGroupMember { group_id, target } => (
            HttpMethod::Delete,
            vec!["groups".into(), group_id.to_string(), "mutes".into()],
            vec![("user_id".into(), mute_target(target))],
            HttpBody::Empty,
        ),
        GetAnnouncement { group_id } => (
            HttpMethod::Get,
            vec!["groups".into(), group_id.to_string(), "announcement".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        UpdateAnnouncement { group_id, content } => (
            HttpMethod::Put,
            vec!["groups".into(), group_id.to_string(), "announcement".into()],
            Vec::new(),
            json_body(json!({"content": content}))?,
        ),
        UpdateGroupNickname {
            group_id,
            user_id,
            nickname,
        } => (
            HttpMethod::Put,
            vec![
                "groups".into(),
                group_id.to_string(),
                "members".into(),
                user_id.to_string(),
                "nickname".into(),
            ],
            Vec::new(),
            json_body(json!({"nickname": nickname}))?,
        ),
        CreateGroupInvite {
            group_id,
            expires_in_seconds,
        } => (
            HttpMethod::Post,
            vec!["groups".into(), group_id.to_string(), "invites".into()],
            Vec::new(),
            json_body(json!({"expires_in_seconds": expires_in_seconds}))?,
        ),
        JoinGroupByInvite { token } => (
            HttpMethod::Post,
            vec!["groups".into(), "join".into()],
            Vec::new(),
            json_body(json!({"token": token}))?,
        ),
        HandleGroupJoinRequest {
            group_id,
            request_id,
            accept,
        } => (
            HttpMethod::Post,
            vec![
                "groups".into(),
                group_id.to_string(),
                "join-requests".into(),
                request_id.to_string(),
            ],
            Vec::new(),
            json_body(json!({"accept": accept}))?,
        ),
        ListDevices => (
            HttpMethod::Get,
            vec!["devices".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        RemoteLogout { device_id } => (
            HttpMethod::Post,
            vec!["devices".into(), device_id.to_string(), "logout".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        RegisterPushToken { input } => (
            HttpMethod::Post,
            vec!["push".into(), "tokens".into()],
            Vec::new(),
            json_body(
                json!({"token": input.token, "device_type": device_number(&input.device_type), "provider": input.provider}),
            )?,
        ),
        UnregisterPushToken { token } => (
            HttpMethod::Delete,
            vec!["push".into(), "tokens".into()],
            Vec::new(),
            json_body(json!({"token": token}))?,
        ),
        GetPushSettings => (
            HttpMethod::Get,
            vec!["push".into(), "settings".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        UpdatePushSettings { input } => (
            HttpMethod::Put,
            vec!["push".into(), "settings".into()],
            Vec::new(),
            json_body(value(input)?)?,
        ),
        GetPrivacyLinks => (
            HttpMethod::Get,
            vec!["account".into(), "privacy-links".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        UpdatePrivacyLinks { input } => (
            HttpMethod::Put,
            vec!["account".into(), "privacy-links".into()],
            Vec::new(),
            json_body(value(input)?)?,
        ),
        Search { query } => (
            HttpMethod::Get,
            vec!["search".into()],
            search_query(&query.query, query.limit),
            HttpBody::Empty,
        ),
        SearchSuggestions { query } => (
            HttpMethod::Get,
            vec!["search".into(), "suggest".into()],
            search_query(&query.query, query.limit),
            HttpBody::Empty,
        ),
        SearchConversation { query } => (
            HttpMethod::Get,
            vec![
                "conversations".into(),
                query.conv_id.to_string(),
                "search".into(),
            ],
            search_query(&query.query, query.limit),
            HttpBody::Empty,
        ),
        Report { input } => (
            HttpMethod::Post,
            vec!["reports".into()],
            Vec::new(),
            json_body(
                json!({"target_type": match input.target_type { ReportTarget::Message => "MESSAGE", ReportTarget::User => "USER", ReportTarget::Group => "GROUP" }, "target_id": input.target_id, "reason": input.reason, "description": input.description}),
            )?,
        ),
        ListReports { limit } => (
            HttpMethod::Get,
            vec!["reports".into()],
            vec![("limit".into(), limit.to_string())],
            HttpBody::Empty,
        ),
        SubmitAppeal { input } => (
            HttpMethod::Post,
            vec!["appeals".into()],
            Vec::new(),
            json_body(value(input)?)?,
        ),
        ListAppeals { appeal_id } => (
            HttpMethod::Get,
            vec!["appeals".into()],
            appeal_id
                .iter()
                .map(|id| ("appeal_id".into(), id.clone()))
                .collect(),
            HttpBody::Empty,
        ),
        ExportAccount => (
            HttpMethod::Post,
            vec!["account".into(), "export".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        GetAccountExports => (
            HttpMethod::Get,
            vec!["account".into(), "export".into()],
            Vec::new(),
            HttpBody::Empty,
        ),
        SetMinorMode { enabled } => (
            HttpMethod::Post,
            vec!["account".into(), "minor-mode".into()],
            Vec::new(),
            json_body(json!({"enabled": enabled}))?,
        ),
    };
    Ok(HttpRequest {
        method,
        path,
        query,
        body,
        identity: None,
        request_id: None,
    })
}
/// complete_upload 的服务器响应只有 UploadSession；由适配层随后补取权威 MediaInfo。
pub fn api_http_response(request: &ApiRequest, data: Value) -> SdkResult<ApiResponse> {
    use ApiRequest::*;
    Ok(match request {
        Forward { .. } => ApiResponse::Forward(Box::new(decode_http_dto(data)?)),
        DeleteLocal { .. } => ApiResponse::DeleteLocal,
        ClearHistory { .. } => ApiResponse::ClearHistory,
        History { .. } => ApiResponse::History(Box::new(decode_history(data)?)),
        InitUpload { .. } => ApiResponse::InitUpload(Box::new(decode_http_dto(data)?)),
        UploadPart { .. } => ApiResponse::UploadPart(Box::new(decode_http_dto(data)?)),
        CompleteUpload { .. } => {
            return Err(SdkError::new(
                ErrorKind::Protocol,
                "上传完成后必须先补取媒体元数据",
            ));
        }
        GetMedia { .. } => ApiResponse::GetMedia(Box::new(decode_http_dto(data)?)),
        ListConversations { .. } => {
            ApiResponse::ListConversations(Box::new(decode_http_dto(data)?))
        }
        GetConversation { .. } => ApiResponse::GetConversation(Box::new(decode_http_dto(data)?)),
        UpdateConversation { .. } => {
            ApiResponse::UpdateConversation(Box::new(decode_http_dto(data)?))
        }
        DeleteConversation { .. } => ApiResponse::DeleteConversation,
        MarkRead { .. } => ApiResponse::MarkRead(Box::new(decode_http_dto(data)?)),
        GetReadPosition { .. } => ApiResponse::GetReadPosition(Box::new(decode_http_dto(data)?)),
        SaveDraft { .. } => ApiResponse::SaveDraft(Box::new(decode_http_dto(data)?)),
        ClearDraft { .. } => ApiResponse::ClearDraft(Box::new(decode_http_dto(data)?)),
        HandleMessageRequest { .. } => {
            ApiResponse::HandleMessageRequest(Box::new(decode_http_dto(data)?))
        }
        GetUserProfile { .. } => ApiResponse::GetUserProfile(Box::new(decode_http_dto(data)?)),
        UpdateProfile { .. } => ApiResponse::UpdateProfile(Box::new(decode_http_dto(data)?)),
        ListFriends => ApiResponse::ListFriends(Box::new(decode_http_dto(data)?)),
        CreateFriendRequest { .. } => {
            ApiResponse::CreateFriendRequest(Box::new(decode_http_dto(data)?))
        }
        ListFriendRequests { .. } => {
            ApiResponse::ListFriendRequests(Box::new(decode_http_dto(data)?))
        }
        HandleFriendRequest { .. } => {
            ApiResponse::HandleFriendRequest(Box::new(decode_http_dto(data)?))
        }
        UpdateFriend { .. } => ApiResponse::UpdateFriend(Box::new(decode_http_dto(data)?)),
        DeleteFriend { .. } => ApiResponse::DeleteFriend,
        ListBlacklist => ApiResponse::ListBlacklist(Box::new(decode_http_dto(data)?)),
        BlockUser { .. } => ApiResponse::BlockUser,
        UnblockUser { .. } => ApiResponse::UnblockUser,
        ListFriendGroups => ApiResponse::ListFriendGroups(Box::new(decode_http_dto(data)?)),
        CreateFriendGroup { .. } => {
            ApiResponse::CreateFriendGroup(Box::new(decode_http_dto(data)?))
        }
        RenameFriendGroup { .. } => {
            ApiResponse::RenameFriendGroup(Box::new(decode_http_dto(data)?))
        }
        DeleteFriendGroup { .. } => ApiResponse::DeleteFriendGroup,
        GetQrCard => ApiResponse::GetQrCard(Box::new(decode_http_dto(data)?)),
        RefreshQrCard => ApiResponse::RefreshQrCard(Box::new(decode_http_dto(data)?)),
        ScanQrCard { .. } => ApiResponse::ScanQrCard(Box::new(decode_http_dto(data)?)),
        GetRelationSettings => ApiResponse::GetRelationSettings(Box::new(decode_http_dto(data)?)),
        UpdateRelationSettings { .. } => {
            ApiResponse::UpdateRelationSettings(Box::new(decode_http_dto(data)?))
        }
        ListGroups => ApiResponse::ListGroups(Box::new(decode_http_dto(data)?)),
        CreateGroup { .. } => ApiResponse::CreateGroup(Box::new(decode_http_dto(data)?)),
        GetGroup { .. } => ApiResponse::GetGroup(Box::new(decode_http_dto(data)?)),
        UpdateGroup { .. } => ApiResponse::UpdateGroup(Box::new(decode_http_dto(data)?)),
        ListGroupMembers { .. } => ApiResponse::ListGroupMembers(Box::new(decode_http_dto(data)?)),
        InviteGroupMembers { .. } => ApiResponse::InviteGroupMembers,
        RemoveGroupMember { .. } => ApiResponse::RemoveGroupMember,
        UpdateGroupRole { .. } => ApiResponse::UpdateGroupRole,
        TransferGroupOwner { .. } => {
            ApiResponse::TransferGroupOwner(Box::new(decode_http_dto(data)?))
        }
        LeaveGroup { .. } => ApiResponse::LeaveGroup,
        DissolveGroup { .. } => ApiResponse::DissolveGroup,
        MuteGroupMember { .. } => ApiResponse::MuteGroupMember,
        UnmuteGroupMember { .. } => ApiResponse::UnmuteGroupMember,
        GetAnnouncement { .. } => ApiResponse::GetAnnouncement(Box::new(decode_http_dto(data)?)),
        UpdateAnnouncement { .. } => {
            ApiResponse::UpdateAnnouncement(Box::new(decode_http_dto(data)?))
        }
        UpdateGroupNickname { .. } => ApiResponse::UpdateGroupNickname,
        CreateGroupInvite { .. } => {
            ApiResponse::CreateGroupInvite(Box::new(decode_http_dto(data)?))
        }
        JoinGroupByInvite { .. } => {
            ApiResponse::JoinGroupByInvite(Box::new(decode_http_dto(data)?))
        }
        HandleGroupJoinRequest { .. } => {
            ApiResponse::HandleGroupJoinRequest(Box::new(decode_http_dto(data)?))
        }
        ListDevices => ApiResponse::ListDevices(Box::new(decode_http_dto(data)?)),
        RemoteLogout { .. } => ApiResponse::RemoteLogout,
        RegisterPushToken { .. } => ApiResponse::RegisterPushToken,
        UnregisterPushToken { .. } => ApiResponse::UnregisterPushToken,
        GetPushSettings => ApiResponse::GetPushSettings(Box::new(decode_http_dto(data)?)),
        UpdatePushSettings { .. } => {
            ApiResponse::UpdatePushSettings(Box::new(decode_http_dto(data)?))
        }
        GetPrivacyLinks => ApiResponse::GetPrivacyLinks(Box::new(decode_http_dto(data)?)),
        UpdatePrivacyLinks { .. } => {
            ApiResponse::UpdatePrivacyLinks(Box::new(decode_http_dto(data)?))
        }
        Search { .. } => ApiResponse::Search(Box::new(decode_http_dto(data)?)),
        SearchSuggestions { .. } => {
            ApiResponse::SearchSuggestions(Box::new(decode_http_dto(data)?))
        }
        SearchConversation { .. } => {
            ApiResponse::SearchConversation(Box::new(decode_http_dto(data)?))
        }
        Report { .. } => ApiResponse::Report(Box::new(decode_http_dto(data)?)),
        ListReports { .. } => ApiResponse::ListReports(Box::new(decode_http_dto(data)?)),
        SubmitAppeal { .. } => ApiResponse::SubmitAppeal(Box::new(decode_http_dto(data)?)),
        ListAppeals { .. } => ApiResponse::ListAppeals(Box::new(decode_http_dto(data)?)),
        ExportAccount => ApiResponse::ExportAccount(Box::new(decode_http_dto(data)?)),
        GetAccountExports => ApiResponse::GetAccountExports(Box::new(decode_http_dto(data)?)),
        SetMinorMode { .. } => ApiResponse::SetMinorMode,
    })
}
