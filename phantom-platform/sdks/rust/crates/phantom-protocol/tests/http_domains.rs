//! Controller/ApiRoutes 与 Java record 的独立契约 fixture。
use phantom_domain::*;
use phantom_ports::*;
use phantom_protocol::*;
use serde_json::{Value, json};
fn cases() -> Vec<(ApiRequest, HttpMethod, &'static str)> {
    vec![
(ApiRequest::Forward { input:serde_json::from_str::<ForwardInput>(r#"{"msg_ids":["id"],"target_conv_ids":["id"],"merged":false}"#).unwrap() },HttpMethod::Post,"MESSAGE_FORWARD"),
(ApiRequest::DeleteLocal { msg_id:"id".into() },HttpMethod::Delete,"MESSAGE_LOCAL_DELETE"),
(ApiRequest::ClearHistory { conv_id:"id".into() },HttpMethod::Delete,"CONVERSATION_MESSAGES_CLEAR"),
(ApiRequest::History { query:serde_json::from_str::<HistoryQuery>(r#"{"conv_id":"id","before_conv_seq":9007199254740993,"limit":5}"#).unwrap() },HttpMethod::Get,"CONVERSATION_MESSAGES"),
(ApiRequest::InitUpload { input:serde_json::from_str::<UploadMetadata>(r#"{"file_name":"file.bin","size_bytes":5,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","mime_type":"application/octet-stream","media_type":"File","duration_seconds":null,"width":null,"height":null,"burn_after_read":false,"burn_after_read_ttl_seconds":null}"#).unwrap() },HttpMethod::Post,"MEDIA_UPLOAD_INIT"),
(ApiRequest::UploadPart { input:serde_json::from_str::<UploadPartInput>(r#"{"upload_id":"id","part_number":1,"bytes":[0,255]}"#).unwrap() },HttpMethod::Put,"MEDIA_UPLOAD_PART"),
(ApiRequest::CompleteUpload { upload_id:"id".into() },HttpMethod::Post,"MEDIA_UPLOAD_COMPLETE"),
(ApiRequest::GetMedia { media_id:"id".into() },HttpMethod::Get,"MEDIA"),
(ApiRequest::ListConversations { query:serde_json::from_str::<ConversationQuery>(r#"{"cursor":"cursor","limit":5}"#).unwrap() },HttpMethod::Get,"CONVERSATIONS"),
(ApiRequest::GetConversation { conv_id:"id".into() },HttpMethod::Get,"CONVERSATION"),
(ApiRequest::UpdateConversation { conv_id:"id".into(), patch:serde_json::from_str::<ConversationPatch>(r#"{"pinned":true,"muted":false}"#).unwrap() },HttpMethod::Post,"CONVERSATION"),
(ApiRequest::DeleteConversation { conv_id:"id".into() },HttpMethod::Delete,"CONVERSATION"),
(ApiRequest::MarkRead { conv_id:"id".into(), read_conv_seq:9_007_199_254_740_993 },HttpMethod::Put,"CONVERSATION_READ_POSITIONS"),
(ApiRequest::GetReadPosition { conv_id:"id".into() },HttpMethod::Get,"CONVERSATION_READ_POSITIONS"),
(ApiRequest::SaveDraft { input:serde_json::from_str::<DraftInput>(r#"{"conv_id":"id","draft":"草稿","edited_at":9007199254740993}"#).unwrap() },HttpMethod::Put,"CONVERSATION_DRAFT"),
(ApiRequest::ClearDraft { conv_id:"id".into(), edited_at:Some(9_007_199_254_740_993) },HttpMethod::Delete,"CONVERSATION_DRAFT"),
(ApiRequest::HandleMessageRequest { conv_id:"id".into(), accept:true },HttpMethod::Post,"CONVERSATION_REQUEST"),
(ApiRequest::GetUserProfile { user_id:Some("id".into()) },HttpMethod::Get,"USER"),
(ApiRequest::UpdateProfile { input:serde_json::from_str::<ProfileUpdate>(r#"{"nickname":"昵称","avatar_url":null,"ext":{"role":"x","conv_type":"保留"}}"#).unwrap() },HttpMethod::Put,"USERS_ME"),
(ApiRequest::ListFriends,HttpMethod::Get,"FRIENDS"),
(ApiRequest::CreateFriendRequest { input:serde_json::from_str::<FriendRequestInput>(r#"{"to_user_id":"id","verification_message":"验证"}"#).unwrap() },HttpMethod::Post,"FRIEND_REQUESTS"),
(ApiRequest::ListFriendRequests { include_handled:true },HttpMethod::Get,"FRIEND_REQUESTS"),
(ApiRequest::HandleFriendRequest { request_id:"id".into(), action:FriendRequestAction::Accept },HttpMethod::Post,"FRIEND_REQUEST"),
(ApiRequest::UpdateFriend { friend_id:"id".into(), patch:serde_json::from_str::<FriendPatch>(r#"{"remark":"备注","pinned":true,"group_ids":["id"]}"#).unwrap() },HttpMethod::Put,"FRIEND"),
(ApiRequest::DeleteFriend { friend_id:"id".into() },HttpMethod::Delete,"FRIEND"),
(ApiRequest::ListBlacklist,HttpMethod::Get,"BLACKLIST"),
(ApiRequest::BlockUser { user_id:"id".into() },HttpMethod::Post,"BLACKLIST"),
(ApiRequest::UnblockUser { user_id:"id".into() },HttpMethod::Delete,"BLACKLIST"),
(ApiRequest::ListFriendGroups,HttpMethod::Get,"FRIEND_GROUPS"),
(ApiRequest::CreateFriendGroup { name:"id".into() },HttpMethod::Post,"FRIEND_GROUPS"),
(ApiRequest::RenameFriendGroup { group_id:"id".into(), name:"id".into() },HttpMethod::Put,"FRIEND_GROUP"),
(ApiRequest::DeleteFriendGroup { group_id:"id".into() },HttpMethod::Delete,"FRIEND_GROUP"),
(ApiRequest::GetQrCard,HttpMethod::Get,"FRIEND_QR"),
(ApiRequest::RefreshQrCard,HttpMethod::Post,"FRIEND_QR_REFRESH"),
(ApiRequest::ScanQrCard { token:"id".into() },HttpMethod::Post,"FRIEND_QR_SCAN"),
(ApiRequest::GetRelationSettings,HttpMethod::Get,"FRIEND_SETTINGS"),
(ApiRequest::UpdateRelationSettings { patch:serde_json::from_str::<RelationSettingsPatch>(r#"{"auto_accept_requests":true}"#).unwrap() },HttpMethod::Put,"FRIEND_SETTINGS"),
(ApiRequest::ListGroups,HttpMethod::Get,"GROUPS"),
(ApiRequest::CreateGroup { input:serde_json::from_str::<CreateGroupInput>(r#"{"name":"群","member_ids":["id"]}"#).unwrap() },HttpMethod::Post,"GROUPS"),
(ApiRequest::GetGroup { group_id:"id".into() },HttpMethod::Get,"GROUP"),
(ApiRequest::UpdateGroup { group_id:"id".into(), patch:serde_json::from_str::<GroupPatch>(r#"{"name":"新名字","avatar_url":null,"member_invite_enabled":false}"#).unwrap() },HttpMethod::Put,"GROUP"),
(ApiRequest::ListGroupMembers { group_id:"id".into() },HttpMethod::Get,"GROUP_MEMBERS"),
(ApiRequest::InviteGroupMembers { group_id:"id".into(), user_ids:vec!["id".into()] },HttpMethod::Post,"GROUP_MEMBERS"),
(ApiRequest::RemoveGroupMember { group_id:"id".into(), user_id:"id".into() },HttpMethod::Delete,"GROUP_MEMBERS"),
(ApiRequest::UpdateGroupRole { group_id:"id".into(), user_id:"id".into(), role:AssignableGroupRole::Admin },HttpMethod::Put,"GROUP_MEMBER_ROLE"),
(ApiRequest::TransferGroupOwner { input:serde_json::from_str::<TransferGroupInput>(r#"{"group_id":"id","user_id":"id","old_owner_role":"Member"}"#).unwrap() },HttpMethod::Post,"GROUP_TRANSFER"),
(ApiRequest::LeaveGroup { group_id:"id".into() },HttpMethod::Delete,"GROUP_LEAVE"),
(ApiRequest::DissolveGroup { group_id:"id".into() },HttpMethod::Delete,"GROUP"),
(ApiRequest::MuteGroupMember { input:serde_json::from_str::<MuteInput>(r#"{"group_id":"id","target":"All","duration":"Permanent"}"#).unwrap() },HttpMethod::Post,"GROUP_MUTES"),
(ApiRequest::UnmuteGroupMember { group_id:"id".into(), target:MuteTarget::All },HttpMethod::Delete,"GROUP_MUTES"),
(ApiRequest::GetAnnouncement { group_id:"id".into() },HttpMethod::Get,"GROUP_ANNOUNCEMENT"),
(ApiRequest::UpdateAnnouncement { group_id:"id".into(), content:"id".into() },HttpMethod::Put,"GROUP_ANNOUNCEMENT"),
(ApiRequest::UpdateGroupNickname { group_id:"id".into(), user_id:"id".into(), nickname:"id".into() },HttpMethod::Put,"GROUP_MEMBER_NICKNAME"),
(ApiRequest::CreateGroupInvite { group_id:"id".into(), expires_in_seconds:Some(600) },HttpMethod::Post,"GROUP_INVITES"),
(ApiRequest::JoinGroupByInvite { token:"id".into() },HttpMethod::Post,"GROUP_JOIN"),
(ApiRequest::HandleGroupJoinRequest { group_id:"id".into(), request_id:"id".into(), accept:true },HttpMethod::Post,"GROUP_JOIN_REQUEST"),
(ApiRequest::ListDevices,HttpMethod::Get,"DEVICES"),
(ApiRequest::RemoteLogout { device_id:"id".into() },HttpMethod::Post,"DEVICE_LOGOUT"),
(ApiRequest::RegisterPushToken { input:serde_json::from_str::<PushTokenInput>(r#"{"token":"push-reference","device_type":"Desktop","provider":"test"}"#).unwrap() },HttpMethod::Post,"PUSH_TOKENS"),
(ApiRequest::UnregisterPushToken { token:"id".into() },HttpMethod::Delete,"PUSH_TOKENS"),
(ApiRequest::GetPushSettings,HttpMethod::Get,"PUSH_SETTINGS"),
(ApiRequest::UpdatePushSettings { input:serde_json::from_str::<PushSettingsInput>(r#"{"push_enabled":true,"preview_enabled":false}"#).unwrap() },HttpMethod::Put,"PUSH_SETTINGS"),
(ApiRequest::GetPrivacyLinks,HttpMethod::Get,"PRIVACY_LINKS"),
(ApiRequest::UpdatePrivacyLinks { input:serde_json::from_str::<PrivacyLinksInput>(r#"{"privacy_policy_url":"","terms_url":null}"#).unwrap() },HttpMethod::Put,"PRIVACY_LINKS"),
(ApiRequest::Search { query:serde_json::from_str::<SearchQuery>(r#"{"query":"搜索","limit":5}"#).unwrap() },HttpMethod::Get,"SEARCH"),
(ApiRequest::SearchSuggestions { query:serde_json::from_str::<SearchQuery>(r#"{"query":"搜索","limit":5}"#).unwrap() },HttpMethod::Get,"SEARCH_SUGGEST"),
(ApiRequest::SearchConversation { query:serde_json::from_str::<ConversationSearchQuery>(r#"{"conv_id":"id","query":"搜索","limit":5}"#).unwrap() },HttpMethod::Get,"CONVERSATION_SEARCH"),
(ApiRequest::Report { input:serde_json::from_str::<ReportInput>(r#"{"target_type":"Message","target_id":"id","reason":"spam","description":"报告"}"#).unwrap() },HttpMethod::Post,"REPORTS"),
(ApiRequest::ListReports { limit:5 },HttpMethod::Get,"REPORTS"),
(ApiRequest::SubmitAppeal { input:serde_json::from_str::<AppealInput>(r#"{"sanction_id":"id","content":"申诉"}"#).unwrap() },HttpMethod::Post,"APPEALS"),
(ApiRequest::ListAppeals { appeal_id:Some("id".into()) },HttpMethod::Get,"APPEALS"),
(ApiRequest::ExportAccount,HttpMethod::Post,"ACCOUNT_EXPORT"),
(ApiRequest::GetAccountExports,HttpMethod::Get,"ACCOUNT_EXPORT"),
(ApiRequest::SetMinorMode { enabled:true },HttpMethod::Post,"ACCOUNT_MINOR_MODE"),
]
}
#[test]
fn every_http_operation_matches_server_route_method_and_typed_response() {
    let routes = include_str!(
        "../../../../../server/phantom-common/src/main/java/im/phantom/common/http/ApiRoutes.java"
    );
    let fixtures: Value = serde_json::from_str(include_str!("fixtures/http_domains.json")).unwrap();
    let cases = cases();
    assert_eq!(cases.len(), 74);
    for (request, method, route) in cases {
        request
            .validate()
            .unwrap_or_else(|e| panic!("{}: {e}", request.operation()));
        let actual = api_http_request(&request).unwrap();
        assert_eq!(actual.method, method, "{}", request.operation());
        let declaration = format!(r#"String {route} = ""#);
        let expected = routes
            .split(&declaration)
            .nth(1)
            .unwrap_or_else(|| panic!("缺少 {route}"))
            .split('"')
            .next()
            .unwrap();
        let expected = expected
            .split('/')
            .skip(1)
            .map(|p| {
                if p.starts_with('{') {
                    if p == "{partNumber}" { "1" } else { "id" }
                } else {
                    p
                }
            })
            .collect::<Vec<_>>();
        assert_eq!(actual.path, expected, "{}", request.operation());
        if request.operation() != "complete_upload" {
            let response = api_http_response(&request, fixtures[request.operation()].clone())
                .unwrap_or_else(|e| panic!("{}: {e}", request.operation()));
            assert_eq!(response.operation(), request.operation());
        }
    }
}
#[test]
fn requests_keep_integer_query_precision_and_server_option_enum_semantics() {
    for (request, _, _) in cases() {
        let actual = api_http_request(&request).unwrap();
        let body = match actual.body {
            HttpBody::Json(bytes) => serde_json::from_slice::<Value>(&bytes).unwrap(),
            _ => Value::Null,
        };
        match request {
            ApiRequest::MarkRead { .. } => {
                assert_eq!(body["read_conv_seq"], json!(9_007_199_254_740_993i64))
            }
            ApiRequest::History { .. } => assert!(
                actual
                    .query
                    .contains(&("before_conv_seq".into(), "9007199254740993".into()))
            ),
            ApiRequest::MuteGroupMember { .. } => {
                assert_eq!(body["user_id"], "ALL");
                assert!(body["duration_seconds"].is_null());
            }
            ApiRequest::RegisterPushToken { .. } => assert_eq!(body["device_type"], 2),
            ApiRequest::UpdateGroupRole { .. } => assert_eq!(body["role"], "ADMIN"),
            ApiRequest::TransferGroupOwner { .. } => assert_eq!(body["old_owner_role"], "MEMBER"),
            ApiRequest::UpdatePushSettings { .. } => {
                assert_eq!(body["push_enabled"], true);
                assert_eq!(body["preview_enabled"], false);
            }
            ApiRequest::UpdatePrivacyLinks { .. } => {
                assert_eq!(body["privacy_policy_url"], "");
                assert!(body["terms_url"].is_null());
            }
            ApiRequest::UnblockUser { .. } => assert_eq!(body["user_id"], "id"),
            _ => {}
        }
    }
}

#[test]
fn group_mute_presets_and_privacy_keep_or_clear_match_controller_contracts() {
    for (duration, seconds) in [
        (MuteDuration::TenMinutes, Some(600)),
        (MuteDuration::OneHour, Some(3600)),
        (MuteDuration::OneDay, Some(86400)),
        (MuteDuration::Permanent, None),
    ] {
        let request = ApiRequest::MuteGroupMember {
            input: MuteInput {
                group_id: "group".into(),
                target: MuteTarget::User("member".into()),
                duration,
            },
        };
        let actual = api_http_request(&request).unwrap();
        let HttpBody::Json(bytes) = actual.body else {
            panic!("禁言必须有JSON正文")
        };
        let body: Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(body["user_id"], "member");
        assert_eq!(body["duration_seconds"], json!(seconds));
    }
    for (policy, terms) in [
        (None, Some("".into())),
        (Some("https://example.test/privacy".into()), None),
    ] {
        let request = ApiRequest::UpdatePrivacyLinks {
            input: PrivacyLinksInput {
                privacy_policy_url: policy.clone(),
                terms_url: terms.clone(),
            },
        };
        let actual = api_http_request(&request).unwrap();
        let HttpBody::Json(bytes) = actual.body else {
            panic!("隐私链接更新必须有JSON正文")
        };
        let body: Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(body["privacy_policy_url"], json!(policy));
        assert_eq!(body["terms_url"], json!(terms));
    }
}

#[test]
fn conversation_snapshot_short_names_and_rejected_request_match_java_service() {
    let fixtures: Value = serde_json::from_str(include_str!("fixtures/http_domains.json")).unwrap();
    for (wire, expected) in [
        (json!("SINGLE"), ConversationType::Single),
        (json!("GROUP"), ConversationType::Group),
        (json!("CONV_TYPE_SINGLE"), ConversationType::Single),
        (json!("CONV_TYPE_GROUP"), ConversationType::Group),
        (json!(1), ConversationType::Single),
        (json!(2), ConversationType::Group),
        (json!("99"), ConversationType::Unknown(99)),
    ] {
        let mut snapshot = fixtures["get_conversation"].clone();
        snapshot["conv_type"] = wire;
        let result = api_http_response(
            &ApiRequest::GetConversation {
                conv_id: "id".into(),
            },
            snapshot,
        )
        .unwrap();
        let ApiResponse::GetConversation(value) = result else {
            panic!("会话响应类型错误")
        };
        assert_eq!(value.conv_type, expected);
    }
    // Java handleMessageRequest(..., false) 返回无会话类型的只读占位快照，不能推断为单聊。
    let mut rejected = fixtures["get_conversation"].clone();
    rejected["conv_type"] = json!("");
    rejected["read_only"] = json!(true);
    let result = api_http_response(
        &ApiRequest::HandleMessageRequest {
            conv_id: "id".into(),
            accept: false,
        },
        rejected,
    )
    .unwrap();
    let ApiResponse::HandleMessageRequest(value) = result else {
        panic!("请求处理响应类型错误")
    };
    assert_eq!(value.conv_type, ConversationType::Unknown(0));
    assert!(value.read_only);
}
