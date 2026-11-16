mod support;
use phantom_protocol::api_http_response;
use phantom_sdk::*;
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, Ordering},
};
use tokio::sync::Notify;
struct Api {
    base: Arc<support::Server>,
    store: Arc<MemoryStoreFactory>,
    responses: Mutex<std::collections::BTreeMap<String, serde_json::Value>>,
    calls: Mutex<Vec<String>>,
    reject: Mutex<Option<SdkError>>,
    block: AtomicBool,
    entered: Notify,
    release: Notify,
}
impl Api {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            base: Arc::default(),
            store: Arc::new(MemoryStoreFactory::new()),
            responses: Mutex::default(),
            calls: Mutex::default(),
            reject: Mutex::default(),
            block: AtomicBool::new(false),
            entered: Notify::new(),
            release: Notify::new(),
        })
    }
    fn client(self: &Arc<Self>) -> PhantomClient {
        let mut host = support::host(&self.base, self.store.clone());
        host.api = self.clone();
        PhantomClient::with_host(ClientOptions::new("https://example.test", "app"), host).unwrap()
    }
}
impl PlatformApi for Api {
    fn execute(
        &self,
        request: ApiRequest,
        identity: HttpIdentity,
        id: String,
    ) -> SdkFuture<'_, ApiResponse> {
        Box::pin(async move {
            self.calls.lock().unwrap().push(request.operation().into());
            self.entered.notify_one();
            if self.block.load(Ordering::Acquire) {
                self.release.notified().await;
            }
            if let Some(error) = self.reject.lock().unwrap().clone() {
                return Err(error);
            }
            let fixtures: serde_json::Value = serde_json::from_str(include_str!(
                "../../phantom-protocol/tests/fixtures/http_domains.json"
            ))
            .unwrap();
            if let Some(data) = self
                .responses
                .lock()
                .unwrap()
                .get(request.operation())
                .cloned()
            {
                return api_http_response(&request, data);
            }
            if matches!(
                request,
                ApiRequest::ListConversations { .. }
                    | ApiRequest::GetUserProfile { .. }
                    | ApiRequest::ListGroups
                    | ApiRequest::History { .. }
            ) {
                return self.base.execute(request, identity, id).await;
            }
            api_http_response(&request, fixtures[request.operation()].clone())
        })
    }
}
fn call() -> CallOptions {
    CallOptions {
        timeout_ms: Some(3000),
        cancellation: None,
    }
}
fn cases() -> Vec<ApiRequest> {
    vec![
ApiRequest::Forward { input:serde_json::from_str::<ForwardInput>(r#"{"msg_ids":["id"],"target_conv_ids":["id"],"merged":false}"#).unwrap() },
ApiRequest::DeleteLocal { msg_id:"id".into() },
ApiRequest::ClearHistory { conv_id:"id".into() },
ApiRequest::GetMedia { media_id:"id".into() },
ApiRequest::ListConversations { query:serde_json::from_str::<ConversationQuery>(r#"{"cursor":"cursor","limit":5}"#).unwrap() },
ApiRequest::GetConversation { conv_id:"id".into() },
ApiRequest::DeleteConversation { conv_id:"id".into() },
ApiRequest::MarkRead { conv_id:"id".into(), read_conv_seq:9_007_199_254_740_993 },
ApiRequest::GetReadPosition { conv_id:"id".into() },
ApiRequest::SaveDraft { input:serde_json::from_str::<DraftInput>(r#"{"conv_id":"id","draft":"草稿","edited_at":9007199254740993}"#).unwrap() },
ApiRequest::ClearDraft { conv_id:"id".into(), edited_at:Some(9_007_199_254_740_993) },
ApiRequest::HandleMessageRequest { conv_id:"id".into(), accept:true },
ApiRequest::GetUserProfile { user_id:Some("id".into()) },
ApiRequest::UpdateProfile { input:serde_json::from_str::<ProfileUpdate>(r#"{"nickname":"昵称","avatar_url":null,"ext":{"role":"x","conv_type":"保留"}}"#).unwrap() },
ApiRequest::ListFriends,
ApiRequest::CreateFriendRequest { input:serde_json::from_str::<FriendRequestInput>(r#"{"to_user_id":"id","verification_message":"验证"}"#).unwrap() },
ApiRequest::ListFriendRequests { include_handled:true },
ApiRequest::HandleFriendRequest { request_id:"id".into(), action:FriendRequestAction::Accept },
ApiRequest::UpdateFriend { friend_id:"id".into(), patch:serde_json::from_str::<FriendPatch>(r#"{"remark":"备注","pinned":true,"group_ids":["id"]}"#).unwrap() },
ApiRequest::DeleteFriend { friend_id:"id".into() },
ApiRequest::ListBlacklist,
ApiRequest::BlockUser { user_id:"id".into() },
ApiRequest::UnblockUser { user_id:"id".into() },
ApiRequest::ListFriendGroups,
ApiRequest::CreateFriendGroup { name:"id".into() },
ApiRequest::RenameFriendGroup { group_id:"id".into(), name:"id".into() },
ApiRequest::DeleteFriendGroup { group_id:"id".into() },
ApiRequest::GetQrCard,
ApiRequest::RefreshQrCard,
ApiRequest::ScanQrCard { token:"id".into() },
ApiRequest::GetRelationSettings,
ApiRequest::UpdateRelationSettings { patch:serde_json::from_str::<RelationSettingsPatch>(r#"{"auto_accept_requests":true}"#).unwrap() },
ApiRequest::ListGroups,
ApiRequest::CreateGroup { input:serde_json::from_str::<CreateGroupInput>(r#"{"name":"群","member_ids":["id"]}"#).unwrap() },
ApiRequest::GetGroup { group_id:"id".into() },
ApiRequest::UpdateGroup { group_id:"id".into(), patch:serde_json::from_str::<GroupPatch>(r#"{"name":"新名字","avatar_url":null,"member_invite_enabled":false}"#).unwrap() },
ApiRequest::ListGroupMembers { group_id:"id".into() },
ApiRequest::InviteGroupMembers { group_id:"id".into(), user_ids:vec!["id".into()] },
ApiRequest::RemoveGroupMember { group_id:"id".into(), user_id:"id".into() },
ApiRequest::UpdateGroupRole { group_id:"id".into(), user_id:"id".into(), role:AssignableGroupRole::Admin },
ApiRequest::TransferGroupOwner { input:serde_json::from_str::<TransferGroupInput>(r#"{"group_id":"id","user_id":"id","old_owner_role":"Member"}"#).unwrap() },
ApiRequest::LeaveGroup { group_id:"id".into() },
ApiRequest::DissolveGroup { group_id:"id".into() },
ApiRequest::MuteGroupMember { input:serde_json::from_str::<MuteInput>(r#"{"group_id":"id","target":"All","duration":"Permanent"}"#).unwrap() },
ApiRequest::UnmuteGroupMember { group_id:"id".into(), target:MuteTarget::All },
ApiRequest::GetAnnouncement { group_id:"id".into() },
ApiRequest::UpdateAnnouncement { group_id:"id".into(), content:"id".into() },
ApiRequest::UpdateGroupNickname { group_id:"id".into(), user_id:"id".into(), nickname:"id".into() },
ApiRequest::CreateGroupInvite { group_id:"id".into(), expires_in_seconds:Some(600) },
ApiRequest::JoinGroupByInvite { token:"id".into() },
ApiRequest::HandleGroupJoinRequest { group_id:"id".into(), request_id:"id".into(), accept:true },
ApiRequest::ListDevices,
ApiRequest::RemoteLogout { device_id:"id".into() },
ApiRequest::RegisterPushToken { input:serde_json::from_str::<PushTokenInput>(r#"{"token":"push-reference","device_type":"Desktop","provider":"test"}"#).unwrap() },
ApiRequest::UnregisterPushToken { token:"id".into() },
ApiRequest::GetPushSettings,
ApiRequest::UpdatePushSettings { input:serde_json::from_str::<PushSettingsInput>(r#"{"push_enabled":true,"preview_enabled":false}"#).unwrap() },
ApiRequest::GetPrivacyLinks,
ApiRequest::UpdatePrivacyLinks { input:serde_json::from_str::<PrivacyLinksInput>(r#"{"privacy_policy_url":"","terms_url":null}"#).unwrap() },
ApiRequest::Search { query:serde_json::from_str::<SearchQuery>(r#"{"query":"搜索","limit":5}"#).unwrap() },
ApiRequest::SearchSuggestions { query:serde_json::from_str::<SearchQuery>(r#"{"query":"搜索","limit":5}"#).unwrap() },
ApiRequest::SearchConversation { query:serde_json::from_str::<ConversationSearchQuery>(r#"{"conv_id":"id","query":"搜索","limit":5}"#).unwrap() },
ApiRequest::Report { input:serde_json::from_str::<ReportInput>(r#"{"target_type":"Message","target_id":"id","reason":"spam","description":"报告"}"#).unwrap() },
ApiRequest::ListReports { limit:5 },
ApiRequest::SubmitAppeal { input:serde_json::from_str::<AppealInput>(r#"{"sanction_id":"id","content":"申诉"}"#).unwrap() },
ApiRequest::ListAppeals { appeal_id:Some("id".into()) },
ApiRequest::ExportAccount,
ApiRequest::GetAccountExports,
ApiRequest::SetMinorMode { enabled:true }
]
}
#[tokio::test]
async fn facade_routes_all_domain_requests_to_typed_responses_and_commits_projections() {
    let api = Api::new();
    let sdk = api.client();
    support::ready(&sdk).await;
    for request in cases() {
        let op = request.operation();
        let result = match request {
            ApiRequest::Forward { input } => sdk.forward(input, call()).await.map(|_| ()),
            ApiRequest::DeleteLocal { msg_id } => {
                sdk.delete_local(msg_id, call()).await.map(|_| ())
            }
            ApiRequest::ClearHistory { conv_id } => {
                sdk.clear_history(conv_id, call()).await.map(|_| ())
            }
            ApiRequest::GetMedia { media_id } => sdk.get_media(media_id, call()).await.map(|_| ()),
            ApiRequest::ListConversations { query } => {
                sdk.list_conversations(query, call()).await.map(|_| ())
            }
            ApiRequest::GetConversation { conv_id } => {
                sdk.get_conversation(conv_id, call()).await.map(|_| ())
            }
            ApiRequest::DeleteConversation { conv_id } => {
                sdk.delete_conversation(conv_id, call()).await.map(|_| ())
            }
            ApiRequest::MarkRead {
                conv_id,
                read_conv_seq,
            } => sdk
                .mark_read(conv_id, read_conv_seq, call())
                .await
                .map(|_| ()),
            ApiRequest::GetReadPosition { conv_id } => {
                sdk.get_read_position(conv_id, call()).await.map(|_| ())
            }
            ApiRequest::SaveDraft { input } => sdk.save_draft(input, call()).await.map(|_| ()),
            ApiRequest::ClearDraft { conv_id, edited_at } => sdk
                .clear_draft(conv_id, edited_at, call())
                .await
                .map(|_| ()),
            ApiRequest::HandleMessageRequest { conv_id, accept } => sdk
                .handle_message_request(conv_id, accept, call())
                .await
                .map(|_| ()),
            ApiRequest::GetUserProfile { user_id } => {
                sdk.get_user_profile(user_id, call()).await.map(|_| ())
            }
            ApiRequest::UpdateProfile { input } => {
                sdk.update_profile(input, call()).await.map(|_| ())
            }
            ApiRequest::ListFriends => sdk.list_friends(call()).await.map(|_| ()),
            ApiRequest::CreateFriendRequest { input } => {
                sdk.create_friend_request(input, call()).await.map(|_| ())
            }
            ApiRequest::ListFriendRequests { include_handled } => sdk
                .list_friend_requests(include_handled, call())
                .await
                .map(|_| ()),
            ApiRequest::HandleFriendRequest { request_id, action } => sdk
                .handle_friend_request(request_id, action, call())
                .await
                .map(|_| ()),
            ApiRequest::UpdateFriend { friend_id, patch } => sdk
                .update_friend(friend_id, patch, call())
                .await
                .map(|_| ()),
            ApiRequest::DeleteFriend { friend_id } => {
                sdk.delete_friend(friend_id, call()).await.map(|_| ())
            }
            ApiRequest::ListBlacklist => sdk.list_blacklist(call()).await.map(|_| ()),
            ApiRequest::BlockUser { user_id } => sdk.block_user(user_id, call()).await.map(|_| ()),
            ApiRequest::UnblockUser { user_id } => {
                sdk.unblock_user(user_id, call()).await.map(|_| ())
            }
            ApiRequest::ListFriendGroups => sdk.list_friend_groups(call()).await.map(|_| ()),
            ApiRequest::CreateFriendGroup { name } => {
                sdk.create_friend_group(name, call()).await.map(|_| ())
            }
            ApiRequest::RenameFriendGroup { group_id, name } => sdk
                .rename_friend_group(group_id, name, call())
                .await
                .map(|_| ()),
            ApiRequest::DeleteFriendGroup { group_id } => {
                sdk.delete_friend_group(group_id, call()).await.map(|_| ())
            }
            ApiRequest::GetQrCard => sdk.get_qr_card(call()).await.map(|_| ()),
            ApiRequest::RefreshQrCard => sdk.refresh_qr_card(call()).await.map(|_| ()),
            ApiRequest::ScanQrCard { token } => sdk.scan_qr_card(token, call()).await.map(|_| ()),
            ApiRequest::GetRelationSettings => sdk.get_relation_settings(call()).await.map(|_| ()),
            ApiRequest::UpdateRelationSettings { patch } => sdk
                .update_relation_settings(patch, call())
                .await
                .map(|_| ()),
            ApiRequest::ListGroups => sdk.list_groups(call()).await.map(|_| ()),
            ApiRequest::CreateGroup { input } => sdk.create_group(input, call()).await.map(|_| ()),
            ApiRequest::GetGroup { group_id } => sdk.get_group(group_id, call()).await.map(|_| ()),
            ApiRequest::UpdateGroup { group_id, patch } => {
                sdk.update_group(group_id, patch, call()).await.map(|_| ())
            }
            ApiRequest::ListGroupMembers { group_id } => {
                sdk.list_group_members(group_id, call()).await.map(|_| ())
            }
            ApiRequest::InviteGroupMembers { group_id, user_ids } => sdk
                .invite_group_members(group_id, user_ids, call())
                .await
                .map(|_| ()),
            ApiRequest::RemoveGroupMember { group_id, user_id } => sdk
                .remove_group_member(group_id, user_id, call())
                .await
                .map(|_| ()),
            ApiRequest::UpdateGroupRole {
                group_id,
                user_id,
                role,
            } => sdk
                .update_group_role(group_id, user_id, role, call())
                .await
                .map(|_| ()),
            ApiRequest::TransferGroupOwner { input } => {
                sdk.transfer_group_owner(input, call()).await.map(|_| ())
            }
            ApiRequest::LeaveGroup { group_id } => {
                sdk.leave_group(group_id, call()).await.map(|_| ())
            }
            ApiRequest::DissolveGroup { group_id } => {
                sdk.dissolve_group(group_id, call()).await.map(|_| ())
            }
            ApiRequest::MuteGroupMember { input } => {
                sdk.mute_group_member(input, call()).await.map(|_| ())
            }
            ApiRequest::UnmuteGroupMember { group_id, target } => sdk
                .unmute_group_member(group_id, target, call())
                .await
                .map(|_| ()),
            ApiRequest::GetAnnouncement { group_id } => {
                sdk.get_announcement(group_id, call()).await.map(|_| ())
            }
            ApiRequest::UpdateAnnouncement { group_id, content } => sdk
                .update_announcement(group_id, content, call())
                .await
                .map(|_| ()),
            ApiRequest::UpdateGroupNickname {
                group_id,
                user_id,
                nickname,
            } => sdk
                .update_group_nickname(group_id, user_id, nickname, call())
                .await
                .map(|_| ()),
            ApiRequest::CreateGroupInvite {
                group_id,
                expires_in_seconds,
            } => sdk
                .create_group_invite(group_id, expires_in_seconds, call())
                .await
                .map(|_| ()),
            ApiRequest::JoinGroupByInvite { token } => {
                sdk.join_group_by_invite(token, call()).await.map(|_| ())
            }
            ApiRequest::HandleGroupJoinRequest {
                group_id,
                request_id,
                accept,
            } => sdk
                .handle_group_join_request(group_id, request_id, accept, call())
                .await
                .map(|_| ()),
            ApiRequest::ListDevices => sdk.list_devices(call()).await.map(|_| ()),
            ApiRequest::RemoteLogout { device_id } => {
                sdk.remote_logout(device_id, call()).await.map(|_| ())
            }
            ApiRequest::RegisterPushToken { input } => {
                sdk.register_push_token(input, call()).await.map(|_| ())
            }
            ApiRequest::UnregisterPushToken { token } => {
                sdk.unregister_push_token(token, call()).await.map(|_| ())
            }
            ApiRequest::GetPushSettings => sdk.get_push_settings(call()).await.map(|_| ()),
            ApiRequest::UpdatePushSettings { input } => {
                sdk.update_push_settings(input, call()).await.map(|_| ())
            }
            ApiRequest::GetPrivacyLinks => sdk.get_privacy_links(call()).await.map(|_| ()),
            ApiRequest::UpdatePrivacyLinks { input } => {
                sdk.update_privacy_links(input, call()).await.map(|_| ())
            }
            ApiRequest::Search { query } => sdk.search(query, call()).await.map(|_| ()),
            ApiRequest::SearchSuggestions { query } => {
                sdk.search_suggestions(query, call()).await.map(|_| ())
            }
            ApiRequest::SearchConversation { query } => {
                sdk.search_conversation(query, call()).await.map(|_| ())
            }
            ApiRequest::Report { input } => sdk.report(input, call()).await.map(|_| ()),
            ApiRequest::ListReports { limit } => sdk.list_reports(limit, call()).await.map(|_| ()),
            ApiRequest::SubmitAppeal { input } => {
                sdk.submit_appeal(input, call()).await.map(|_| ())
            }
            ApiRequest::ListAppeals { appeal_id } => {
                sdk.list_appeals(appeal_id, call()).await.map(|_| ())
            }
            ApiRequest::ExportAccount => sdk.export_account(call()).await.map(|_| ()),
            ApiRequest::GetAccountExports => sdk.get_account_exports(call()).await.map(|_| ()),
            ApiRequest::SetMinorMode { enabled } => {
                sdk.set_minor_mode(enabled, call()).await.map(|_| ())
            }
            _ => unreachable!(),
        };
        assert!(result.is_ok(), "{op}: {result:?}");
    }
    assert!(api.calls.lock().unwrap().len() > 65);
    sdk.shutdown(call()).await.unwrap();
}

#[tokio::test]
async fn cancelled_search_releases_queue_and_permissions_payloads_and_rejections_survive() {
    let api = Api::new();
    let sdk = api.client();
    support::ready(&sdk).await;
    api.block.store(true, Ordering::Release);
    let token = CancellationToken::new();
    let task = {
        let sdk = sdk.clone();
        let cancellation = token.clone();
        tokio::spawn(async move {
            sdk.search(
                SearchQuery {
                    query: "旧搜索".into(),
                    limit: 5,
                },
                CallOptions {
                    timeout_ms: Some(3000),
                    cancellation: Some(cancellation),
                },
            )
            .await
        })
    };
    // 同步初始化也会发出通知；明确观察 search 已进入网络。
    tokio::time::timeout(std::time::Duration::from_secs(2), async {
        while !api.calls.lock().unwrap().iter().any(|op| op == "search") {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    token.cancel();
    assert_eq!(task.await.unwrap().unwrap_err().kind, ErrorKind::Cancelled);
    api.block.store(false, Ordering::Release);
    let result = sdk
        .search(
            SearchQuery {
                query: "新搜索".into(),
                limit: 5,
            },
            call(),
        )
        .await
        .unwrap();
    assert_eq!(result.messages[0].conv_seq, 9_007_199_254_740_993);
    assert!(!result.messages[0].msg_id.is_empty());
    for limit in [0, 51] {
        assert_eq!(
            sdk.search(
                SearchQuery {
                    query: "q".into(),
                    limit
                },
                call()
            )
            .await
            .unwrap_err()
            .kind,
            ErrorKind::InvalidArgument
        );
    }
    let exports = sdk.export_account(call()).await.unwrap();
    assert_eq!(exports[0].payload_json, "{\"原样\":9007199254740993}");
    let mut error = SdkError::new(ErrorKind::Business, "功能或权限被服务端拒绝");
    error.server_code = Some(5002);
    error.outcome = OperationOutcome::Rejected;
    *api.reject.lock().unwrap() = Some(error);
    let before = api.calls.lock().unwrap().len();
    let error = sdk
        .update_profile(
            ProfileUpdate {
                nickname: Some("新资料".into()),
                avatar_url: None,
                ext: Default::default(),
            },
            call(),
        )
        .await
        .unwrap_err();
    assert_eq!(error.server_code, Some(5002));
    assert_eq!(error.outcome, OperationOutcome::Rejected);
    assert_eq!(api.calls.lock().unwrap().len(), before + 1);
    sdk.shutdown(call()).await.unwrap();
}
#[tokio::test]
async fn cancellation_after_compliance_submission_is_unknown_and_never_retried() {
    let api = Api::new();
    let sdk = api.client();
    support::ready(&sdk).await;
    api.block.store(true, Ordering::Release);
    let token = CancellationToken::new();
    let task = {
        let sdk = sdk.clone();
        let cancellation = token.clone();
        tokio::spawn(async move {
            sdk.report(
                ReportInput {
                    target_type: ReportTarget::Message,
                    target_id: "message".into(),
                    reason: "spam".into(),
                    description: "报告".into(),
                },
                CallOptions {
                    timeout_ms: Some(3000),
                    cancellation: Some(cancellation),
                },
            )
            .await
        })
    };
    tokio::time::timeout(std::time::Duration::from_secs(2), async {
        while !api.calls.lock().unwrap().iter().any(|op| op == "report") {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    token.cancel();
    let error = task.await.unwrap().unwrap_err();
    assert_eq!(error.outcome, OperationOutcome::Unknown);
    assert_eq!(
        api.calls
            .lock()
            .unwrap()
            .iter()
            .filter(|op| *op == "report")
            .count(),
        1
    );
    sdk.shutdown(call()).await.unwrap();
}

#[tokio::test]
async fn group_join_results_permissions_and_domain_invalidation_preserve_server_semantics() {
    let api = Api::new();
    let sdk = api.client();
    support::ready(&sdk).await;
    for (status, request) in [("PENDING", Some("request")), ("JOINED", None)] {
        api.responses.lock().unwrap().insert(
            "join_group_by_invite".into(),
            serde_json::json!({"group_id":"group","request_id":request,"status":status}),
        );
        let result = sdk
            .join_group_by_invite("invite-token".into(), call())
            .await
            .unwrap();
        assert_eq!(result.status, status);
        assert_eq!(result.request_id.as_deref(), request);
    }
    let announcement = sdk.get_announcement("group".into(), call()).await.unwrap();
    assert_eq!(announcement.version, 9_007_199_254_740_993);
    assert!(
        sdk.create_group_invite("group".into(), Some(3600), call())
            .await
            .unwrap()
            .expires_at
            .is_some()
    );
    sdk.list_friends(call()).await.unwrap();
    sdk.create_friend_group("分组".into(), call())
        .await
        .unwrap();
    sdk.update_friend(
        "peer".into(),
        FriendPatch {
            remark: None,
            pinned: Some(true),
            group_ids: Some(vec![]),
        },
        call(),
    )
    .await
    .unwrap();
    sdk.list_devices(call()).await.unwrap();
    sdk.remote_logout("other-device".into(), call())
        .await
        .unwrap();
    let mut denied = SdkError::new(ErrorKind::Business, "权限拒绝");
    denied.server_code = Some(5002);
    denied.outcome = OperationOutcome::Rejected;
    *api.reject.lock().unwrap() = Some(denied);
    assert_eq!(
        sdk.transfer_group_owner(
            TransferGroupInput {
                group_id: "group".into(),
                user_id: "peer".into(),
                old_owner_role: AssignableGroupRole::Admin
            },
            call()
        )
        .await
        .unwrap_err()
        .server_code,
        Some(5002)
    );
    assert_eq!(
        sdk.mute_group_member(
            MuteInput {
                group_id: "group".into(),
                target: MuteTarget::All,
                duration: MuteDuration::Permanent
            },
            call()
        )
        .await
        .unwrap_err()
        .server_code,
        Some(5002)
    );
    assert_eq!(
        sdk.update_group_role(
            "group".into(),
            "peer".into(),
            AssignableGroupRole::Admin,
            call()
        )
        .await
        .unwrap_err()
        .server_code,
        Some(5002)
    );
    sdk.shutdown(call()).await.unwrap();
    let store = api
        .store
        .open(StorageNamespace {
            service_url: "https://example.test".into(),
            app_id: "app".into(),
            user_id: "user".into(),
        })
        .await
        .unwrap();
    let state = store.load().await.unwrap();
    for key in [
        ProjectionKey::Friends,
        ProjectionKey::FriendGroups,
        ProjectionKey::Devices,
        ProjectionKey::GroupDetail("group".into()),
    ] {
        assert!(state.invalidated.contains(&key), "{key:?}");
    }
    assert!(
        state
            .projections
            .iter()
            .any(|p| matches!(p, DomainProjection::Friends(_)))
    );
    store.close().await.unwrap();
}

#[tokio::test]
async fn deletion_and_clear_history_survive_stale_history_pages_and_keep_cursor_order() {
    let api = Api::new();
    let sdk = api.client();
    support::ready(&sdk).await;
    *api.base.history.lock().unwrap() = (1..=3)
        .map(|seq| support::message(&format!("history-{seq}"), seq))
        .collect();
    let query = HistoryQuery {
        conv_id: "gr_group".into(),
        before_conv_seq: None,
        limit: 2,
    };
    let page = sdk.history(query.clone(), call()).await.unwrap();
    assert!(page.has_more);
    assert_eq!(page.next_cursor, Some(2));
    assert_eq!(
        page.items
            .iter()
            .map(|m| m.conv_seq.unwrap())
            .collect::<Vec<_>>(),
        [2, 3]
    );
    let older = sdk
        .history(
            HistoryQuery {
                before_conv_seq: page.next_cursor,
                ..query.clone()
            },
            call(),
        )
        .await
        .unwrap();
    assert!(!older.has_more);
    assert_eq!(older.items[0].conv_seq, Some(1));
    sdk.delete_local("msg-history-2".into(), call())
        .await
        .unwrap();
    sdk.history(query.clone(), call()).await.unwrap();
    assert!(
        !sdk.get_messages(MessageCacheQuery {
            conv_id: "gr_group".into(),
            before_conv_seq: None,
            limit: 100
        })
        .unwrap()
        .iter()
        .any(|m| m.msg_id.as_deref() == Some("msg-history-2"))
    );
    let mut conversation: serde_json::Value = serde_json::from_str(include_str!(
        "../../phantom-protocol/tests/fixtures/http_domains.json"
    ))
    .unwrap();
    conversation["get_conversation"]["conv_id"] = serde_json::json!("gr_group");
    conversation["get_conversation"]["max_conv_seq"] = serde_json::json!(3);
    api.responses.lock().unwrap().insert(
        "get_conversation".into(),
        conversation["get_conversation"].clone(),
    );
    sdk.clear_history("gr_group".into(), call()).await.unwrap();
    let stale = sdk.history(query, call()).await.unwrap();
    assert!(stale.items.is_empty());
    assert!(
        sdk.get_messages(MessageCacheQuery {
            conv_id: "gr_group".into(),
            before_conv_seq: None,
            limit: 100
        })
        .unwrap()
        .is_empty()
    );
    let mut error = SdkError::new(ErrorKind::Network, "提交后连接断开");
    error.outcome = OperationOutcome::Unknown;
    *api.reject.lock().unwrap() = Some(error);
    let before = api
        .calls
        .lock()
        .unwrap()
        .iter()
        .filter(|op| *op == "forward")
        .count();
    let error = sdk
        .forward(
            ForwardInput {
                msg_ids: vec!["msg-history-1".into()],
                target_conv_ids: vec!["gr_target".into()],
                merged: false,
            },
            call(),
        )
        .await
        .unwrap_err();
    assert_eq!(error.outcome, OperationOutcome::Unknown);
    assert_eq!(
        api.calls
            .lock()
            .unwrap()
            .iter()
            .filter(|op| *op == "forward")
            .count(),
        before + 1
    );
    sdk.shutdown(call()).await.unwrap();
}

#[tokio::test]
async fn twelve_sync_events_refresh_invalidated_domains_before_ready_and_preserve_unknown_inbox() {
    let api = Api::new();
    let mut events = Vec::new();
    for bytes in [
        include_bytes!("../../phantom-protocol/fixtures/sync-10.bin").as_slice(),
        include_bytes!("../../phantom-protocol/fixtures/sync-11.bin"),
        include_bytes!("../../phantom-protocol/fixtures/sync-12.bin"),
        include_bytes!("../../phantom-protocol/fixtures/sync-13.bin"),
        include_bytes!("../../phantom-protocol/fixtures/sync-14.bin"),
        include_bytes!("../../phantom-protocol/fixtures/sync-15.bin"),
        include_bytes!("../../phantom-protocol/fixtures/sync-16.bin"),
        include_bytes!("../../phantom-protocol/fixtures/sync-17.bin"),
        include_bytes!("../../phantom-protocol/fixtures/sync-18.bin"),
        include_bytes!("../../phantom-protocol/fixtures/sync-19.bin"),
        include_bytes!("../../phantom-protocol/fixtures/sync-20.bin"),
        include_bytes!("../../phantom-protocol/fixtures/sync-21.bin"),
    ] {
        events.push(phantom_protocol::decode_sync_event(bytes).unwrap().event);
    }
    events.push(SyncEvent {
        user_seq: 13,
        event_time: 1013,
        payload: SyncPayload::Unknown {
            field_number: 19001,
            raw_bytes: include_bytes!("../../phantom-protocol/fixtures/unknown-member.bin")
                .to_vec(),
        },
    });
    api.base.target.store(13, Ordering::Release);
    api.base.scripts.lock().unwrap().push_back(SyncBatch {
        events,
        max_user_seq: 13,
        has_more: false,
        full_resync: false,
    });
    let sdk = api.client();
    support::ready(&sdk).await;
    for name in [
        "list_friends",
        "list_friend_requests",
        "list_blacklist",
        "list_friend_groups",
        "get_relation_settings",
        "list_groups",
        "list_reports",
    ] {
        assert!(
            api.calls.lock().unwrap().iter().any(|op| op == name),
            "缺少 {name} 刷新"
        );
    }
    assert_eq!(sdk.get_sync_status().unwrap().applied_user_seq, 13);
    sdk.shutdown(call()).await.unwrap();
    let store = api
        .store
        .open(StorageNamespace {
            service_url: "https://example.test".into(),
            app_id: "app".into(),
            user_id: "user".into(),
        })
        .await
        .unwrap();
    let state = store.load().await.unwrap();
    assert_eq!(state.inbox.len(), 13);
    let recalled = state
        .messages
        .iter()
        .find(|m| m.msg_id.as_deref() == Some("m"))
        .unwrap();
    assert_eq!(recalled.state, MessageState::Recalled);
    assert!(recalled.content.is_none());
    assert!(
        state
            .projections
            .iter()
            .any(|p| matches!(p, DomainProjection::Reports(_)))
    );
    store.close().await.unwrap();
}

#[tokio::test]
async fn expired_qr_group_terminal_and_disabled_compliance_preserve_business_rejections() {
    let api = Api::new();
    let sdk = api.client();
    support::ready(&sdk).await;
    // 业务码取自 Java ErrorCodes；这些拒绝不应被读取/写入包装成成功或自动重试。
    for (code, message, operation) in [
        (1001, "二维码已失效", "scan_qr_card"),
        (5004, "群已解散", "get_group"),
        (5004, "群已解散", "leave_group"),
        (5002, "无权限", "set_minor_mode"),
    ] {
        let mut rejected = SdkError::new(ErrorKind::Business, message);
        rejected.server_code = Some(code);
        rejected.outcome = OperationOutcome::Rejected;
        *api.reject.lock().unwrap() = Some(rejected);
        let before = api.calls.lock().unwrap().len();
        let result = match operation {
            "scan_qr_card" => sdk
                .scan_qr_card("expired-token".into(), call())
                .await
                .map(|_| ()),
            "get_group" => sdk
                .get_group("dissolved-group".into(), call())
                .await
                .map(|_| ()),
            "leave_group" => sdk.leave_group("dissolved-group".into(), call()).await,
            _ => sdk.set_minor_mode(true, call()).await,
        };
        let error = result.unwrap_err();
        assert_eq!(error.kind, ErrorKind::Business);
        assert_eq!(error.server_code, Some(code));
        assert_eq!(error.message, message);
        assert_eq!(error.outcome, OperationOutcome::Rejected);
        assert_eq!(api.calls.lock().unwrap().len(), before + 1);
        assert_eq!(sdk.get_current_user_id().unwrap().as_deref(), Some("user"));
    }
    sdk.shutdown(call()).await.unwrap();
}
