// 由 tools/generate_cli.py 生成；schema、反序列化和执行共享这些参数类型。
use crate::{config::Config, wire};
use phantom_sdk::*;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct NewParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetStateParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetCurrentUserIdParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetAppConfigParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetSyncStatusParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct FlushStorageParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct LoginParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct WaitReadyParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UpdateTokenParams {
    pub input: crate::config::TokenReference,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ReconnectParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct LogoutParams {
    pub input: LogoutOptions,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ShutdownParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SingleConvIdParams {
    pub a: String,
    pub b: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SendTextParams {
    pub conv_id: String,
    pub text: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SendMessageParams {
    pub input: SendMessageInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ResendParams {
    pub client_msg_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct RecallParams {
    pub msg_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ForwardParams {
    pub input: ForwardInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct DeleteLocalParams {
    pub msg_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ClearHistoryParams {
    pub conv_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct HistoryParams {
    pub query: HistoryQuery,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetMessagesParams {
    pub query: MessageCacheQuery,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct MarkMessageDisplayedParams {
    pub msg_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UploadMediaParams {
    pub input: UploadInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct InitUploadParams {
    pub input: UploadMetadata,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UploadPartParams {
    pub input: UploadPartInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct CompleteUploadParams {
    pub upload_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetMediaParams {
    pub media_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetMediaContentParams {
    pub media_id: String,
    pub variant: MediaVariant,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct DownloadMediaParams {
    pub input: DownloadInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SendMediaParams {
    pub input: SendMediaInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ListConversationsParams {
    pub query: ConversationQuery,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetConversationParams {
    pub conv_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetConversationStateParams {
    pub conv_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UpdateConversationParams {
    pub conv_id: String,
    pub patch: ConversationPatch,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct DeleteConversationParams {
    pub conv_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct MarkReadParams {
    pub conv_id: String,
    pub read_conv_seq: Seq,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetReadPositionParams {
    pub conv_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SaveDraftParams {
    pub input: DraftInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ClearDraftParams {
    pub conv_id: String,
    pub edited_at: Option<TimestampMs>,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct HandleMessageRequestParams {
    pub conv_id: String,
    pub accept: bool,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetUserProfileParams {
    pub user_id: Option<String>,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UpdateProfileParams {
    pub input: ProfileUpdate,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ListFriendsParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct CreateFriendRequestParams {
    pub input: FriendRequestInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ListFriendRequestsParams {
    pub include_handled: bool,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct HandleFriendRequestParams {
    pub request_id: String,
    pub action: FriendRequestAction,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UpdateFriendParams {
    pub friend_id: String,
    pub patch: FriendPatch,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct DeleteFriendParams {
    pub friend_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ListBlacklistParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct BlockUserParams {
    pub user_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UnblockUserParams {
    pub user_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ListFriendGroupsParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct CreateFriendGroupParams {
    pub name: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct RenameFriendGroupParams {
    pub group_id: String,
    pub name: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct DeleteFriendGroupParams {
    pub group_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetQrCardParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct RefreshQrCardParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ScanQrCardParams {
    pub token: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetRelationSettingsParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UpdateRelationSettingsParams {
    pub patch: RelationSettingsPatch,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ListGroupsParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct CreateGroupParams {
    pub input: CreateGroupInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetGroupParams {
    pub group_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UpdateGroupParams {
    pub group_id: String,
    pub patch: GroupPatch,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ListGroupMembersParams {
    pub group_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct InviteGroupMembersParams {
    pub group_id: String,
    pub user_ids: Vec<String>,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct RemoveGroupMemberParams {
    pub group_id: String,
    pub user_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UpdateGroupRoleParams {
    pub group_id: String,
    pub user_id: String,
    pub role: AssignableGroupRole,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct TransferGroupOwnerParams {
    pub input: TransferGroupInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct LeaveGroupParams {
    pub group_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct DissolveGroupParams {
    pub group_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct MuteGroupMemberParams {
    pub input: MuteInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UnmuteGroupMemberParams {
    pub group_id: String,
    pub target: MuteTarget,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetAnnouncementParams {
    pub group_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UpdateAnnouncementParams {
    pub group_id: String,
    pub content: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UpdateGroupNicknameParams {
    pub group_id: String,
    pub user_id: String,
    pub nickname: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct CreateGroupInviteParams {
    pub group_id: String,
    pub expires_in_seconds: Option<u32>,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct JoinGroupByInviteParams {
    pub token: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct HandleGroupJoinRequestParams {
    pub group_id: String,
    pub request_id: String,
    pub accept: bool,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ListDevicesParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct RemoteLogoutParams {
    pub device_id: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct RegisterPushTokenParams {
    pub input: PushTokenInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UnregisterPushTokenParams {
    pub token: String,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetPushSettingsParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UpdatePushSettingsParams {
    pub input: PushSettingsInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetPrivacyLinksParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UpdatePrivacyLinksParams {
    pub input: PrivacyLinksInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SendTypingParams {
    pub conv_id: String,
    pub typing: bool,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SubscribePresenceParams {
    pub user_ids: Vec<String>,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct UnsubscribePresenceParams {
    pub user_ids: Vec<String>,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SetAppStateParams {
    pub foreground: bool,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct HandleNotificationClickParams {
    pub input: NotificationClick,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SearchParams {
    pub query: SearchQuery,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SearchSuggestionsParams {
    pub query: SearchQuery,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SearchConversationParams {
    pub query: ConversationSearchQuery,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ReportParams {
    pub input: ReportInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ListReportsParams {
    pub limit: u32,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SubmitAppealParams {
    pub input: AppealInput,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ListAppealsParams {
    pub appeal_id: Option<String>,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ExportAccountParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct GetAccountExportsParams {}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct SetMinorModeParams {
    pub enabled: bool,
}
pub const METHODS: &[&str] = &[
    "new",
    "get_state",
    "get_current_user_id",
    "get_app_config",
    "get_sync_status",
    "flush_storage",
    "login",
    "wait_ready",
    "update_token",
    "reconnect",
    "logout",
    "shutdown",
    "single_conv_id",
    "send_text",
    "send_message",
    "resend",
    "recall",
    "forward",
    "delete_local",
    "clear_history",
    "history",
    "get_messages",
    "mark_message_displayed",
    "upload_media",
    "init_upload",
    "upload_part",
    "complete_upload",
    "get_media",
    "get_media_content",
    "download_media",
    "send_media",
    "list_conversations",
    "get_conversation",
    "get_conversation_state",
    "update_conversation",
    "delete_conversation",
    "mark_read",
    "get_read_position",
    "save_draft",
    "clear_draft",
    "handle_message_request",
    "get_user_profile",
    "update_profile",
    "list_friends",
    "create_friend_request",
    "list_friend_requests",
    "handle_friend_request",
    "update_friend",
    "delete_friend",
    "list_blacklist",
    "block_user",
    "unblock_user",
    "list_friend_groups",
    "create_friend_group",
    "rename_friend_group",
    "delete_friend_group",
    "get_qr_card",
    "refresh_qr_card",
    "scan_qr_card",
    "get_relation_settings",
    "update_relation_settings",
    "list_groups",
    "create_group",
    "get_group",
    "update_group",
    "list_group_members",
    "invite_group_members",
    "remove_group_member",
    "update_group_role",
    "transfer_group_owner",
    "leave_group",
    "dissolve_group",
    "mute_group_member",
    "unmute_group_member",
    "get_announcement",
    "update_announcement",
    "update_group_nickname",
    "create_group_invite",
    "join_group_by_invite",
    "handle_group_join_request",
    "list_devices",
    "remote_logout",
    "register_push_token",
    "unregister_push_token",
    "get_push_settings",
    "update_push_settings",
    "get_privacy_links",
    "update_privacy_links",
    "send_typing",
    "subscribe_presence",
    "unsubscribe_presence",
    "set_app_state",
    "handle_notification_click",
    "search",
    "search_suggestions",
    "search_conversation",
    "report",
    "list_reports",
    "submit_appeal",
    "list_appeals",
    "export_account",
    "get_account_exports",
    "set_minor_mode",
];
pub fn describe(method: &str) -> SdkResult<Value> {
    match method {
        "new" => Ok(
            json!({"method":"new","input":wire::schema::<NewParams>(),"result":wire::schema::<ConnectionState>(),"side_effect":effect(method)}),
        ),
        "get_state" => Ok(
            json!({"method":"get_state","input":wire::schema::<GetStateParams>(),"result":wire::schema::<ConnectionState>(),"side_effect":effect(method)}),
        ),
        "get_current_user_id" => Ok(
            json!({"method":"get_current_user_id","input":wire::schema::<GetCurrentUserIdParams>(),"result":wire::schema::<Option<String>>(),"side_effect":effect(method)}),
        ),
        "get_app_config" => Ok(
            json!({"method":"get_app_config","input":wire::schema::<GetAppConfigParams>(),"result":wire::schema::<Option<AppConfig>>(),"side_effect":effect(method)}),
        ),
        "get_sync_status" => Ok(
            json!({"method":"get_sync_status","input":wire::schema::<GetSyncStatusParams>(),"result":wire::schema::<SyncStatus>(),"side_effect":effect(method)}),
        ),
        "flush_storage" => Ok(
            json!({"method":"flush_storage","input":wire::schema::<FlushStorageParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "login" => Ok(
            json!({"method":"login","input":wire::schema::<LoginParams>(),"result":wire::schema::<LoginResult>(),"side_effect":effect(method)}),
        ),
        "wait_ready" => Ok(
            json!({"method":"wait_ready","input":wire::schema::<WaitReadyParams>(),"result":wire::schema::<SyncStatus>(),"side_effect":effect(method)}),
        ),
        "update_token" => Ok(
            json!({"method":"update_token","input":wire::schema::<UpdateTokenParams>(),"result":wire::schema::<LoginResult>(),"side_effect":effect(method)}),
        ),
        "reconnect" => Ok(
            json!({"method":"reconnect","input":wire::schema::<ReconnectParams>(),"result":wire::schema::<LoginResult>(),"side_effect":effect(method)}),
        ),
        "logout" => Ok(
            json!({"method":"logout","input":wire::schema::<LogoutParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "shutdown" => Ok(
            json!({"method":"shutdown","input":wire::schema::<ShutdownParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "single_conv_id" => Ok(
            json!({"method":"single_conv_id","input":wire::schema::<SingleConvIdParams>(),"result":wire::schema::<String>(),"side_effect":effect(method)}),
        ),
        "send_text" => Ok(
            json!({"method":"send_text","input":wire::schema::<SendTextParams>(),"result":wire::schema::<ChatMessage>(),"side_effect":effect(method)}),
        ),
        "send_message" => Ok(
            json!({"method":"send_message","input":wire::schema::<SendMessageParams>(),"result":wire::schema::<ChatMessage>(),"side_effect":effect(method)}),
        ),
        "resend" => Ok(
            json!({"method":"resend","input":wire::schema::<ResendParams>(),"result":wire::schema::<ChatMessage>(),"side_effect":effect(method)}),
        ),
        "recall" => Ok(
            json!({"method":"recall","input":wire::schema::<RecallParams>(),"result":wire::schema::<RecallResult>(),"side_effect":effect(method)}),
        ),
        "forward" => Ok(
            json!({"method":"forward","input":wire::schema::<ForwardParams>(),"result":wire::schema::<MessageAction>(),"side_effect":effect(method)}),
        ),
        "delete_local" => Ok(
            json!({"method":"delete_local","input":wire::schema::<DeleteLocalParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "clear_history" => Ok(
            json!({"method":"clear_history","input":wire::schema::<ClearHistoryParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "history" => Ok(
            json!({"method":"history","input":wire::schema::<HistoryParams>(),"result":wire::schema::<MessagePage>(),"side_effect":effect(method)}),
        ),
        "get_messages" => Ok(
            json!({"method":"get_messages","input":wire::schema::<GetMessagesParams>(),"result":wire::schema::<Vec<ChatMessage>>(),"side_effect":effect(method)}),
        ),
        "mark_message_displayed" => Ok(
            json!({"method":"mark_message_displayed","input":wire::schema::<MarkMessageDisplayedParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "upload_media" => Ok(
            json!({"method":"upload_media","input":wire::schema::<UploadMediaParams>(),"result":wire::schema::<UploadedMedia>(),"side_effect":effect(method)}),
        ),
        "init_upload" => Ok(
            json!({"method":"init_upload","input":wire::schema::<InitUploadParams>(),"result":wire::schema::<UploadSession>(),"side_effect":effect(method)}),
        ),
        "upload_part" => Ok(
            json!({"method":"upload_part","input":wire::schema::<UploadPartParams>(),"result":wire::schema::<UploadPartResult>(),"side_effect":effect(method)}),
        ),
        "complete_upload" => Ok(
            json!({"method":"complete_upload","input":wire::schema::<CompleteUploadParams>(),"result":wire::schema::<UploadedMedia>(),"side_effect":effect(method)}),
        ),
        "get_media" => Ok(
            json!({"method":"get_media","input":wire::schema::<GetMediaParams>(),"result":wire::schema::<MediaInfo>(),"side_effect":effect(method)}),
        ),
        "get_media_content" => Ok(
            json!({"method":"get_media_content","input":wire::schema::<GetMediaContentParams>(),"result":wire::schema::<MediaContent>(),"side_effect":effect(method)}),
        ),
        "download_media" => Ok(
            json!({"method":"download_media","input":wire::schema::<DownloadMediaParams>(),"result":wire::schema::<DownloadedMedia>(),"side_effect":effect(method)}),
        ),
        "send_media" => Ok(
            json!({"method":"send_media","input":wire::schema::<SendMediaParams>(),"result":wire::schema::<ChatMessage>(),"side_effect":effect(method)}),
        ),
        "list_conversations" => Ok(
            json!({"method":"list_conversations","input":wire::schema::<ListConversationsParams>(),"result":wire::schema::<ConversationPage>(),"side_effect":effect(method)}),
        ),
        "get_conversation" => Ok(
            json!({"method":"get_conversation","input":wire::schema::<GetConversationParams>(),"result":wire::schema::<Conversation>(),"side_effect":effect(method)}),
        ),
        "get_conversation_state" => Ok(
            json!({"method":"get_conversation_state","input":wire::schema::<GetConversationStateParams>(),"result":wire::schema::<Option<Conversation>>(),"side_effect":effect(method)}),
        ),
        "update_conversation" => Ok(
            json!({"method":"update_conversation","input":wire::schema::<UpdateConversationParams>(),"result":wire::schema::<Conversation>(),"side_effect":effect(method)}),
        ),
        "delete_conversation" => Ok(
            json!({"method":"delete_conversation","input":wire::schema::<DeleteConversationParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "mark_read" => Ok(
            json!({"method":"mark_read","input":wire::schema::<MarkReadParams>(),"result":wire::schema::<ReadPosition>(),"side_effect":effect(method)}),
        ),
        "get_read_position" => Ok(
            json!({"method":"get_read_position","input":wire::schema::<GetReadPositionParams>(),"result":wire::schema::<ReadPosition>(),"side_effect":effect(method)}),
        ),
        "save_draft" => Ok(
            json!({"method":"save_draft","input":wire::schema::<SaveDraftParams>(),"result":wire::schema::<ConversationDraft>(),"side_effect":effect(method)}),
        ),
        "clear_draft" => Ok(
            json!({"method":"clear_draft","input":wire::schema::<ClearDraftParams>(),"result":wire::schema::<ConversationDraft>(),"side_effect":effect(method)}),
        ),
        "handle_message_request" => Ok(
            json!({"method":"handle_message_request","input":wire::schema::<HandleMessageRequestParams>(),"result":wire::schema::<Conversation>(),"side_effect":effect(method)}),
        ),
        "get_user_profile" => Ok(
            json!({"method":"get_user_profile","input":wire::schema::<GetUserProfileParams>(),"result":wire::schema::<UserProfile>(),"side_effect":effect(method)}),
        ),
        "update_profile" => Ok(
            json!({"method":"update_profile","input":wire::schema::<UpdateProfileParams>(),"result":wire::schema::<UserProfile>(),"side_effect":effect(method)}),
        ),
        "list_friends" => Ok(
            json!({"method":"list_friends","input":wire::schema::<ListFriendsParams>(),"result":wire::schema::<Vec<Friend>>(),"side_effect":effect(method)}),
        ),
        "create_friend_request" => Ok(
            json!({"method":"create_friend_request","input":wire::schema::<CreateFriendRequestParams>(),"result":wire::schema::<FriendRequestResult>(),"side_effect":effect(method)}),
        ),
        "list_friend_requests" => Ok(
            json!({"method":"list_friend_requests","input":wire::schema::<ListFriendRequestsParams>(),"result":wire::schema::<FriendRequestPage>(),"side_effect":effect(method)}),
        ),
        "handle_friend_request" => Ok(
            json!({"method":"handle_friend_request","input":wire::schema::<HandleFriendRequestParams>(),"result":wire::schema::<FriendRequest>(),"side_effect":effect(method)}),
        ),
        "update_friend" => Ok(
            json!({"method":"update_friend","input":wire::schema::<UpdateFriendParams>(),"result":wire::schema::<Friend>(),"side_effect":effect(method)}),
        ),
        "delete_friend" => Ok(
            json!({"method":"delete_friend","input":wire::schema::<DeleteFriendParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "list_blacklist" => Ok(
            json!({"method":"list_blacklist","input":wire::schema::<ListBlacklistParams>(),"result":wire::schema::<Vec<BlacklistEntry>>(),"side_effect":effect(method)}),
        ),
        "block_user" => Ok(
            json!({"method":"block_user","input":wire::schema::<BlockUserParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "unblock_user" => Ok(
            json!({"method":"unblock_user","input":wire::schema::<UnblockUserParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "list_friend_groups" => Ok(
            json!({"method":"list_friend_groups","input":wire::schema::<ListFriendGroupsParams>(),"result":wire::schema::<Vec<FriendGroup>>(),"side_effect":effect(method)}),
        ),
        "create_friend_group" => Ok(
            json!({"method":"create_friend_group","input":wire::schema::<CreateFriendGroupParams>(),"result":wire::schema::<FriendGroup>(),"side_effect":effect(method)}),
        ),
        "rename_friend_group" => Ok(
            json!({"method":"rename_friend_group","input":wire::schema::<RenameFriendGroupParams>(),"result":wire::schema::<FriendGroup>(),"side_effect":effect(method)}),
        ),
        "delete_friend_group" => Ok(
            json!({"method":"delete_friend_group","input":wire::schema::<DeleteFriendGroupParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "get_qr_card" => Ok(
            json!({"method":"get_qr_card","input":wire::schema::<GetQrCardParams>(),"result":wire::schema::<QrCard>(),"side_effect":effect(method)}),
        ),
        "refresh_qr_card" => Ok(
            json!({"method":"refresh_qr_card","input":wire::schema::<RefreshQrCardParams>(),"result":wire::schema::<QrCard>(),"side_effect":effect(method)}),
        ),
        "scan_qr_card" => Ok(
            json!({"method":"scan_qr_card","input":wire::schema::<ScanQrCardParams>(),"result":wire::schema::<QrCardScanResult>(),"side_effect":effect(method)}),
        ),
        "get_relation_settings" => Ok(
            json!({"method":"get_relation_settings","input":wire::schema::<GetRelationSettingsParams>(),"result":wire::schema::<RelationSettings>(),"side_effect":effect(method)}),
        ),
        "update_relation_settings" => Ok(
            json!({"method":"update_relation_settings","input":wire::schema::<UpdateRelationSettingsParams>(),"result":wire::schema::<RelationSettings>(),"side_effect":effect(method)}),
        ),
        "list_groups" => Ok(
            json!({"method":"list_groups","input":wire::schema::<ListGroupsParams>(),"result":wire::schema::<Vec<Group>>(),"side_effect":effect(method)}),
        ),
        "create_group" => Ok(
            json!({"method":"create_group","input":wire::schema::<CreateGroupParams>(),"result":wire::schema::<Group>(),"side_effect":effect(method)}),
        ),
        "get_group" => Ok(
            json!({"method":"get_group","input":wire::schema::<GetGroupParams>(),"result":wire::schema::<GroupDetail>(),"side_effect":effect(method)}),
        ),
        "update_group" => Ok(
            json!({"method":"update_group","input":wire::schema::<UpdateGroupParams>(),"result":wire::schema::<GroupDetail>(),"side_effect":effect(method)}),
        ),
        "list_group_members" => Ok(
            json!({"method":"list_group_members","input":wire::schema::<ListGroupMembersParams>(),"result":wire::schema::<Vec<GroupMember>>(),"side_effect":effect(method)}),
        ),
        "invite_group_members" => Ok(
            json!({"method":"invite_group_members","input":wire::schema::<InviteGroupMembersParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "remove_group_member" => Ok(
            json!({"method":"remove_group_member","input":wire::schema::<RemoveGroupMemberParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "update_group_role" => Ok(
            json!({"method":"update_group_role","input":wire::schema::<UpdateGroupRoleParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "transfer_group_owner" => Ok(
            json!({"method":"transfer_group_owner","input":wire::schema::<TransferGroupOwnerParams>(),"result":wire::schema::<Group>(),"side_effect":effect(method)}),
        ),
        "leave_group" => Ok(
            json!({"method":"leave_group","input":wire::schema::<LeaveGroupParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "dissolve_group" => Ok(
            json!({"method":"dissolve_group","input":wire::schema::<DissolveGroupParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "mute_group_member" => Ok(
            json!({"method":"mute_group_member","input":wire::schema::<MuteGroupMemberParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "unmute_group_member" => Ok(
            json!({"method":"unmute_group_member","input":wire::schema::<UnmuteGroupMemberParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "get_announcement" => Ok(
            json!({"method":"get_announcement","input":wire::schema::<GetAnnouncementParams>(),"result":wire::schema::<Announcement>(),"side_effect":effect(method)}),
        ),
        "update_announcement" => Ok(
            json!({"method":"update_announcement","input":wire::schema::<UpdateAnnouncementParams>(),"result":wire::schema::<Announcement>(),"side_effect":effect(method)}),
        ),
        "update_group_nickname" => Ok(
            json!({"method":"update_group_nickname","input":wire::schema::<UpdateGroupNicknameParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "create_group_invite" => Ok(
            json!({"method":"create_group_invite","input":wire::schema::<CreateGroupInviteParams>(),"result":wire::schema::<GroupInvite>(),"side_effect":effect(method)}),
        ),
        "join_group_by_invite" => Ok(
            json!({"method":"join_group_by_invite","input":wire::schema::<JoinGroupByInviteParams>(),"result":wire::schema::<JoinGroupResult>(),"side_effect":effect(method)}),
        ),
        "handle_group_join_request" => Ok(
            json!({"method":"handle_group_join_request","input":wire::schema::<HandleGroupJoinRequestParams>(),"result":wire::schema::<JoinGroupResult>(),"side_effect":effect(method)}),
        ),
        "list_devices" => Ok(
            json!({"method":"list_devices","input":wire::schema::<ListDevicesParams>(),"result":wire::schema::<Vec<DeviceInfo>>(),"side_effect":effect(method)}),
        ),
        "remote_logout" => Ok(
            json!({"method":"remote_logout","input":wire::schema::<RemoteLogoutParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "register_push_token" => Ok(
            json!({"method":"register_push_token","input":wire::schema::<RegisterPushTokenParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "unregister_push_token" => Ok(
            json!({"method":"unregister_push_token","input":wire::schema::<UnregisterPushTokenParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "get_push_settings" => Ok(
            json!({"method":"get_push_settings","input":wire::schema::<GetPushSettingsParams>(),"result":wire::schema::<PushSettings>(),"side_effect":effect(method)}),
        ),
        "update_push_settings" => Ok(
            json!({"method":"update_push_settings","input":wire::schema::<UpdatePushSettingsParams>(),"result":wire::schema::<PushSettings>(),"side_effect":effect(method)}),
        ),
        "get_privacy_links" => Ok(
            json!({"method":"get_privacy_links","input":wire::schema::<GetPrivacyLinksParams>(),"result":wire::schema::<PushSettings>(),"side_effect":effect(method)}),
        ),
        "update_privacy_links" => Ok(
            json!({"method":"update_privacy_links","input":wire::schema::<UpdatePrivacyLinksParams>(),"result":wire::schema::<PushSettings>(),"side_effect":effect(method)}),
        ),
        "send_typing" => Ok(
            json!({"method":"send_typing","input":wire::schema::<SendTypingParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "subscribe_presence" => Ok(
            json!({"method":"subscribe_presence","input":wire::schema::<SubscribePresenceParams>(),"result":wire::schema::<Vec<Presence>>(),"side_effect":effect(method)}),
        ),
        "unsubscribe_presence" => Ok(
            json!({"method":"unsubscribe_presence","input":wire::schema::<UnsubscribePresenceParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "set_app_state" => Ok(
            json!({"method":"set_app_state","input":wire::schema::<SetAppStateParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "handle_notification_click" => Ok(
            json!({"method":"handle_notification_click","input":wire::schema::<HandleNotificationClickParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        "search" => Ok(
            json!({"method":"search","input":wire::schema::<SearchParams>(),"result":wire::schema::<SearchResult>(),"side_effect":effect(method)}),
        ),
        "search_suggestions" => Ok(
            json!({"method":"search_suggestions","input":wire::schema::<SearchSuggestionsParams>(),"result":wire::schema::<SearchSuggestions>(),"side_effect":effect(method)}),
        ),
        "search_conversation" => Ok(
            json!({"method":"search_conversation","input":wire::schema::<SearchConversationParams>(),"result":wire::schema::<ConversationSearch>(),"side_effect":effect(method)}),
        ),
        "report" => Ok(
            json!({"method":"report","input":wire::schema::<ReportParams>(),"result":wire::schema::<Report>(),"side_effect":effect(method)}),
        ),
        "list_reports" => Ok(
            json!({"method":"list_reports","input":wire::schema::<ListReportsParams>(),"result":wire::schema::<Vec<Report>>(),"side_effect":effect(method)}),
        ),
        "submit_appeal" => Ok(
            json!({"method":"submit_appeal","input":wire::schema::<SubmitAppealParams>(),"result":wire::schema::<Appeal>(),"side_effect":effect(method)}),
        ),
        "list_appeals" => Ok(
            json!({"method":"list_appeals","input":wire::schema::<ListAppealsParams>(),"result":wire::schema::<Vec<Appeal>>(),"side_effect":effect(method)}),
        ),
        "export_account" => Ok(
            json!({"method":"export_account","input":wire::schema::<ExportAccountParams>(),"result":wire::schema::<Vec<AccountExport>>(),"side_effect":effect(method)}),
        ),
        "get_account_exports" => Ok(
            json!({"method":"get_account_exports","input":wire::schema::<GetAccountExportsParams>(),"result":wire::schema::<Vec<AccountExport>>(),"side_effect":effect(method)}),
        ),
        "set_minor_mode" => Ok(
            json!({"method":"set_minor_mode","input":wire::schema::<SetMinorModeParams>(),"result":wire::schema::<()>(),"side_effect":effect(method)}),
        ),
        _ => Err(wire::invalid("未知方法")),
    }
}
pub fn effect(method: &str) -> &'static str {
    match method {
        "new" => "local",
        "get_state" => "local",
        "get_current_user_id" => "local",
        "get_app_config" => "local",
        "get_sync_status" => "local",
        "flush_storage" => "local",
        "login" => "session",
        "wait_ready" => "read",
        "update_token" => "session",
        "reconnect" => "session",
        "logout" => "session",
        "shutdown" => "session",
        "single_conv_id" => "local",
        "send_text" => "write",
        "send_message" => "write",
        "resend" => "write",
        "recall" => "write",
        "forward" => "write",
        "delete_local" => "destructive",
        "clear_history" => "destructive",
        "history" => "read",
        "get_messages" => "local",
        "mark_message_displayed" => "local",
        "upload_media" => "write",
        "init_upload" => "write",
        "upload_part" => "write",
        "complete_upload" => "write",
        "get_media" => "read",
        "get_media_content" => "read",
        "download_media" => "write",
        "send_media" => "write",
        "list_conversations" => "read",
        "get_conversation" => "read",
        "get_conversation_state" => "local",
        "update_conversation" => "write",
        "delete_conversation" => "destructive",
        "mark_read" => "write",
        "get_read_position" => "read",
        "save_draft" => "write",
        "clear_draft" => "write",
        "handle_message_request" => "write",
        "get_user_profile" => "read",
        "update_profile" => "write",
        "list_friends" => "read",
        "create_friend_request" => "write",
        "list_friend_requests" => "read",
        "handle_friend_request" => "write",
        "update_friend" => "write",
        "delete_friend" => "write",
        "list_blacklist" => "read",
        "block_user" => "write",
        "unblock_user" => "write",
        "list_friend_groups" => "read",
        "create_friend_group" => "write",
        "rename_friend_group" => "write",
        "delete_friend_group" => "write",
        "get_qr_card" => "read",
        "refresh_qr_card" => "write",
        "scan_qr_card" => "write",
        "get_relation_settings" => "read",
        "update_relation_settings" => "write",
        "list_groups" => "read",
        "create_group" => "write",
        "get_group" => "read",
        "update_group" => "write",
        "list_group_members" => "read",
        "invite_group_members" => "write",
        "remove_group_member" => "write",
        "update_group_role" => "write",
        "transfer_group_owner" => "write",
        "leave_group" => "write",
        "dissolve_group" => "destructive",
        "mute_group_member" => "write",
        "unmute_group_member" => "write",
        "get_announcement" => "marks_announcement_read",
        "update_announcement" => "write",
        "update_group_nickname" => "write",
        "create_group_invite" => "write",
        "join_group_by_invite" => "write",
        "handle_group_join_request" => "write",
        "list_devices" => "read",
        "remote_logout" => "write",
        "register_push_token" => "write",
        "unregister_push_token" => "write",
        "get_push_settings" => "read",
        "update_push_settings" => "write",
        "get_privacy_links" => "read",
        "update_privacy_links" => "write",
        "send_typing" => "write",
        "subscribe_presence" => "write",
        "unsubscribe_presence" => "write",
        "set_app_state" => "write",
        "handle_notification_click" => "local",
        "search" => "write",
        "search_suggestions" => "read",
        "search_conversation" => "read",
        "report" => "write",
        "list_reports" => "read",
        "submit_appeal" => "write",
        "list_appeals" => "read",
        "export_account" => "write",
        "get_account_exports" => "read",
        "set_minor_mode" => "write",
        _ => "unknown",
    }
}
pub fn needs_session(method: &str) -> bool {
    match method {
        "new" => false,
        "get_state" => false,
        "get_current_user_id" => false,
        "get_app_config" => false,
        "get_sync_status" => false,
        "flush_storage" => false,
        "login" => false,
        "wait_ready" => true,
        "update_token" => true,
        "reconnect" => true,
        "logout" => false,
        "shutdown" => false,
        "single_conv_id" => false,
        "send_text" => true,
        "send_message" => true,
        "resend" => true,
        "recall" => true,
        "forward" => true,
        "delete_local" => true,
        "clear_history" => true,
        "history" => true,
        "get_messages" => true,
        "mark_message_displayed" => true,
        "upload_media" => true,
        "init_upload" => true,
        "upload_part" => true,
        "complete_upload" => true,
        "get_media" => true,
        "get_media_content" => true,
        "download_media" => true,
        "send_media" => true,
        "list_conversations" => true,
        "get_conversation" => true,
        "get_conversation_state" => true,
        "update_conversation" => true,
        "delete_conversation" => true,
        "mark_read" => true,
        "get_read_position" => true,
        "save_draft" => true,
        "clear_draft" => true,
        "handle_message_request" => true,
        "get_user_profile" => true,
        "update_profile" => true,
        "list_friends" => true,
        "create_friend_request" => true,
        "list_friend_requests" => true,
        "handle_friend_request" => true,
        "update_friend" => true,
        "delete_friend" => true,
        "list_blacklist" => true,
        "block_user" => true,
        "unblock_user" => true,
        "list_friend_groups" => true,
        "create_friend_group" => true,
        "rename_friend_group" => true,
        "delete_friend_group" => true,
        "get_qr_card" => true,
        "refresh_qr_card" => true,
        "scan_qr_card" => true,
        "get_relation_settings" => true,
        "update_relation_settings" => true,
        "list_groups" => true,
        "create_group" => true,
        "get_group" => true,
        "update_group" => true,
        "list_group_members" => true,
        "invite_group_members" => true,
        "remove_group_member" => true,
        "update_group_role" => true,
        "transfer_group_owner" => true,
        "leave_group" => true,
        "dissolve_group" => true,
        "mute_group_member" => true,
        "unmute_group_member" => true,
        "get_announcement" => true,
        "update_announcement" => true,
        "update_group_nickname" => true,
        "create_group_invite" => true,
        "join_group_by_invite" => true,
        "handle_group_join_request" => true,
        "list_devices" => true,
        "remote_logout" => true,
        "register_push_token" => true,
        "unregister_push_token" => true,
        "get_push_settings" => true,
        "update_push_settings" => true,
        "get_privacy_links" => true,
        "update_privacy_links" => true,
        "send_typing" => true,
        "subscribe_presence" => true,
        "unsubscribe_presence" => true,
        "set_app_state" => true,
        "handle_notification_click" => true,
        "search" => true,
        "search_suggestions" => true,
        "search_conversation" => true,
        "report" => true,
        "list_reports" => true,
        "submit_appeal" => true,
        "list_appeals" => true,
        "export_account" => true,
        "get_account_exports" => true,
        "set_minor_mode" => true,
        _ => false,
    }
}
pub fn validate(method: &str, params: Value) -> SdkResult<()> {
    match method {
        "new" => {
            let _p: NewParams = wire::decode(params)?;
            Ok(())
        }
        "get_state" => {
            let _p: GetStateParams = wire::decode(params)?;
            Ok(())
        }
        "get_current_user_id" => {
            let _p: GetCurrentUserIdParams = wire::decode(params)?;
            Ok(())
        }
        "get_app_config" => {
            let _p: GetAppConfigParams = wire::decode(params)?;
            Ok(())
        }
        "get_sync_status" => {
            let _p: GetSyncStatusParams = wire::decode(params)?;
            Ok(())
        }
        "flush_storage" => {
            let _p: FlushStorageParams = wire::decode(params)?;
            Ok(())
        }
        "login" => {
            let _p: LoginParams = wire::decode(params)?;
            Ok(())
        }
        "wait_ready" => {
            let _p: WaitReadyParams = wire::decode(params)?;
            Ok(())
        }
        "update_token" => {
            let p: UpdateTokenParams = wire::decode(params)?;
            p.input.validate()
        }
        "reconnect" => {
            let _p: ReconnectParams = wire::decode(params)?;
            Ok(())
        }
        "logout" => {
            let _p: LogoutParams = wire::decode(params)?;
            Ok(())
        }
        "shutdown" => {
            let _p: ShutdownParams = wire::decode(params)?;
            Ok(())
        }
        "single_conv_id" => {
            let p: SingleConvIdParams = wire::decode(params)?;
            single_conv_id(p.a, p.b).map(|_| ())
        }
        "send_text" => {
            let p: SendTextParams = wire::decode(params)?;
            validate_id(&p.conv_id, "conv_id")?;
            MessageContent::Text(TextContent { text: p.text }).validate_send()
        }
        "send_message" => {
            let p: SendMessageParams = wire::decode(params)?;
            p.input.validate()
        }
        "resend" => {
            let p: ResendParams = wire::decode(params)?;
            validate_id(&p.client_msg_id, "client_msg_id")?;
            Ok(())
        }
        "recall" => {
            let p: RecallParams = wire::decode(params)?;
            validate_id(&p.msg_id, "msg_id")?;
            Ok(())
        }
        "forward" => {
            let p: ForwardParams = wire::decode(params)?;
            ApiRequest::Forward { input: p.input }.validate()
        }
        "delete_local" => {
            let p: DeleteLocalParams = wire::decode(params)?;
            validate_id(&p.msg_id, "msg_id")?;
            ApiRequest::DeleteLocal { msg_id: p.msg_id }.validate()
        }
        "clear_history" => {
            let p: ClearHistoryParams = wire::decode(params)?;
            validate_id(&p.conv_id, "conv_id")?;
            ApiRequest::ClearHistory { conv_id: p.conv_id }.validate()
        }
        "history" => {
            let p: HistoryParams = wire::decode(params)?;
            ApiRequest::History { query: p.query }.validate()
        }
        "get_messages" => {
            let p: GetMessagesParams = wire::decode(params)?;
            p.query.validate()
        }
        "mark_message_displayed" => {
            let p: MarkMessageDisplayedParams = wire::decode(params)?;
            validate_id(&p.msg_id, "msg_id")?;
            Ok(())
        }
        "upload_media" => {
            let p: UploadMediaParams = wire::decode(params)?;
            p.input.metadata.validate()
        }
        "init_upload" => {
            let p: InitUploadParams = wire::decode(params)?;
            ApiRequest::InitUpload { input: p.input }.validate()
        }
        "upload_part" => {
            let p: UploadPartParams = wire::decode(params)?;
            ApiRequest::UploadPart { input: p.input }.validate()
        }
        "complete_upload" => {
            let p: CompleteUploadParams = wire::decode(params)?;
            validate_id(&p.upload_id, "upload_id")?;
            ApiRequest::CompleteUpload {
                upload_id: p.upload_id,
            }
            .validate()
        }
        "get_media" => {
            let p: GetMediaParams = wire::decode(params)?;
            validate_id(&p.media_id, "media_id")?;
            ApiRequest::GetMedia {
                media_id: p.media_id,
            }
            .validate()
        }
        "get_media_content" => {
            let p: GetMediaContentParams = wire::decode(params)?;
            validate_id(&p.media_id, "media_id")?;
            Ok(())
        }
        "download_media" => {
            let _p: DownloadMediaParams = wire::decode(params)?;
            Ok(())
        }
        "send_media" => {
            let _p: SendMediaParams = wire::decode(params)?;
            Ok(())
        }
        "list_conversations" => {
            let p: ListConversationsParams = wire::decode(params)?;
            ApiRequest::ListConversations { query: p.query }.validate()
        }
        "get_conversation" => {
            let p: GetConversationParams = wire::decode(params)?;
            validate_id(&p.conv_id, "conv_id")?;
            ApiRequest::GetConversation { conv_id: p.conv_id }.validate()
        }
        "get_conversation_state" => {
            let p: GetConversationStateParams = wire::decode(params)?;
            validate_id(&p.conv_id, "conv_id")?;
            Ok(())
        }
        "update_conversation" => {
            let p: UpdateConversationParams = wire::decode(params)?;
            validate_id(&p.conv_id, "conv_id")?;
            ApiRequest::UpdateConversation {
                conv_id: p.conv_id,
                patch: p.patch,
            }
            .validate()
        }
        "delete_conversation" => {
            let p: DeleteConversationParams = wire::decode(params)?;
            validate_id(&p.conv_id, "conv_id")?;
            ApiRequest::DeleteConversation { conv_id: p.conv_id }.validate()
        }
        "mark_read" => {
            let p: MarkReadParams = wire::decode(params)?;
            validate_id(&p.conv_id, "conv_id")?;
            ApiRequest::MarkRead {
                conv_id: p.conv_id,
                read_conv_seq: p.read_conv_seq,
            }
            .validate()
        }
        "get_read_position" => {
            let p: GetReadPositionParams = wire::decode(params)?;
            validate_id(&p.conv_id, "conv_id")?;
            ApiRequest::GetReadPosition { conv_id: p.conv_id }.validate()
        }
        "save_draft" => {
            let p: SaveDraftParams = wire::decode(params)?;
            ApiRequest::SaveDraft { input: p.input }.validate()
        }
        "clear_draft" => {
            let p: ClearDraftParams = wire::decode(params)?;
            validate_id(&p.conv_id, "conv_id")?;
            ApiRequest::ClearDraft {
                conv_id: p.conv_id,
                edited_at: p.edited_at,
            }
            .validate()
        }
        "handle_message_request" => {
            let p: HandleMessageRequestParams = wire::decode(params)?;
            validate_id(&p.conv_id, "conv_id")?;
            ApiRequest::HandleMessageRequest {
                conv_id: p.conv_id,
                accept: p.accept,
            }
            .validate()
        }
        "get_user_profile" => {
            let p: GetUserProfileParams = wire::decode(params)?;
            if let Some(id) = &p.user_id {
                validate_id(id, "user_id")?;
            }
            ApiRequest::GetUserProfile { user_id: p.user_id }.validate()
        }
        "update_profile" => {
            let p: UpdateProfileParams = wire::decode(params)?;
            ApiRequest::UpdateProfile { input: p.input }.validate()
        }
        "list_friends" => {
            let _p: ListFriendsParams = wire::decode(params)?;
            ApiRequest::ListFriends.validate()
        }
        "create_friend_request" => {
            let p: CreateFriendRequestParams = wire::decode(params)?;
            ApiRequest::CreateFriendRequest { input: p.input }.validate()
        }
        "list_friend_requests" => {
            let p: ListFriendRequestsParams = wire::decode(params)?;
            ApiRequest::ListFriendRequests {
                include_handled: p.include_handled,
            }
            .validate()
        }
        "handle_friend_request" => {
            let p: HandleFriendRequestParams = wire::decode(params)?;
            validate_id(&p.request_id, "request_id")?;
            ApiRequest::HandleFriendRequest {
                request_id: p.request_id,
                action: p.action,
            }
            .validate()
        }
        "update_friend" => {
            let p: UpdateFriendParams = wire::decode(params)?;
            validate_id(&p.friend_id, "friend_id")?;
            ApiRequest::UpdateFriend {
                friend_id: p.friend_id,
                patch: p.patch,
            }
            .validate()
        }
        "delete_friend" => {
            let p: DeleteFriendParams = wire::decode(params)?;
            validate_id(&p.friend_id, "friend_id")?;
            ApiRequest::DeleteFriend {
                friend_id: p.friend_id,
            }
            .validate()
        }
        "list_blacklist" => {
            let _p: ListBlacklistParams = wire::decode(params)?;
            ApiRequest::ListBlacklist.validate()
        }
        "block_user" => {
            let p: BlockUserParams = wire::decode(params)?;
            validate_id(&p.user_id, "user_id")?;
            ApiRequest::BlockUser { user_id: p.user_id }.validate()
        }
        "unblock_user" => {
            let p: UnblockUserParams = wire::decode(params)?;
            validate_id(&p.user_id, "user_id")?;
            ApiRequest::UnblockUser { user_id: p.user_id }.validate()
        }
        "list_friend_groups" => {
            let _p: ListFriendGroupsParams = wire::decode(params)?;
            ApiRequest::ListFriendGroups.validate()
        }
        "create_friend_group" => {
            let p: CreateFriendGroupParams = wire::decode(params)?;
            ApiRequest::CreateFriendGroup { name: p.name }.validate()
        }
        "rename_friend_group" => {
            let p: RenameFriendGroupParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            ApiRequest::RenameFriendGroup {
                group_id: p.group_id,
                name: p.name,
            }
            .validate()
        }
        "delete_friend_group" => {
            let p: DeleteFriendGroupParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            ApiRequest::DeleteFriendGroup {
                group_id: p.group_id,
            }
            .validate()
        }
        "get_qr_card" => {
            let _p: GetQrCardParams = wire::decode(params)?;
            ApiRequest::GetQrCard.validate()
        }
        "refresh_qr_card" => {
            let _p: RefreshQrCardParams = wire::decode(params)?;
            ApiRequest::RefreshQrCard.validate()
        }
        "scan_qr_card" => {
            let p: ScanQrCardParams = wire::decode(params)?;
            ApiRequest::ScanQrCard { token: p.token }.validate()
        }
        "get_relation_settings" => {
            let _p: GetRelationSettingsParams = wire::decode(params)?;
            ApiRequest::GetRelationSettings.validate()
        }
        "update_relation_settings" => {
            let p: UpdateRelationSettingsParams = wire::decode(params)?;
            ApiRequest::UpdateRelationSettings { patch: p.patch }.validate()
        }
        "list_groups" => {
            let _p: ListGroupsParams = wire::decode(params)?;
            ApiRequest::ListGroups.validate()
        }
        "create_group" => {
            let p: CreateGroupParams = wire::decode(params)?;
            ApiRequest::CreateGroup { input: p.input }.validate()
        }
        "get_group" => {
            let p: GetGroupParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            ApiRequest::GetGroup {
                group_id: p.group_id,
            }
            .validate()
        }
        "update_group" => {
            let p: UpdateGroupParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            ApiRequest::UpdateGroup {
                group_id: p.group_id,
                patch: p.patch,
            }
            .validate()
        }
        "list_group_members" => {
            let p: ListGroupMembersParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            ApiRequest::ListGroupMembers {
                group_id: p.group_id,
            }
            .validate()
        }
        "invite_group_members" => {
            let p: InviteGroupMembersParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            ApiRequest::InviteGroupMembers {
                group_id: p.group_id,
                user_ids: p.user_ids,
            }
            .validate()
        }
        "remove_group_member" => {
            let p: RemoveGroupMemberParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            validate_id(&p.user_id, "user_id")?;
            ApiRequest::RemoveGroupMember {
                group_id: p.group_id,
                user_id: p.user_id,
            }
            .validate()
        }
        "update_group_role" => {
            let p: UpdateGroupRoleParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            validate_id(&p.user_id, "user_id")?;
            ApiRequest::UpdateGroupRole {
                group_id: p.group_id,
                user_id: p.user_id,
                role: p.role,
            }
            .validate()
        }
        "transfer_group_owner" => {
            let p: TransferGroupOwnerParams = wire::decode(params)?;
            ApiRequest::TransferGroupOwner { input: p.input }.validate()
        }
        "leave_group" => {
            let p: LeaveGroupParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            ApiRequest::LeaveGroup {
                group_id: p.group_id,
            }
            .validate()
        }
        "dissolve_group" => {
            let p: DissolveGroupParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            ApiRequest::DissolveGroup {
                group_id: p.group_id,
            }
            .validate()
        }
        "mute_group_member" => {
            let p: MuteGroupMemberParams = wire::decode(params)?;
            ApiRequest::MuteGroupMember { input: p.input }.validate()
        }
        "unmute_group_member" => {
            let p: UnmuteGroupMemberParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            ApiRequest::UnmuteGroupMember {
                group_id: p.group_id,
                target: p.target,
            }
            .validate()
        }
        "get_announcement" => {
            let p: GetAnnouncementParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            ApiRequest::GetAnnouncement {
                group_id: p.group_id,
            }
            .validate()
        }
        "update_announcement" => {
            let p: UpdateAnnouncementParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            ApiRequest::UpdateAnnouncement {
                group_id: p.group_id,
                content: p.content,
            }
            .validate()
        }
        "update_group_nickname" => {
            let p: UpdateGroupNicknameParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            validate_id(&p.user_id, "user_id")?;
            ApiRequest::UpdateGroupNickname {
                group_id: p.group_id,
                user_id: p.user_id,
                nickname: p.nickname,
            }
            .validate()
        }
        "create_group_invite" => {
            let p: CreateGroupInviteParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            ApiRequest::CreateGroupInvite {
                group_id: p.group_id,
                expires_in_seconds: p.expires_in_seconds,
            }
            .validate()
        }
        "join_group_by_invite" => {
            let p: JoinGroupByInviteParams = wire::decode(params)?;
            ApiRequest::JoinGroupByInvite { token: p.token }.validate()
        }
        "handle_group_join_request" => {
            let p: HandleGroupJoinRequestParams = wire::decode(params)?;
            validate_id(&p.group_id, "group_id")?;
            validate_id(&p.request_id, "request_id")?;
            ApiRequest::HandleGroupJoinRequest {
                group_id: p.group_id,
                request_id: p.request_id,
                accept: p.accept,
            }
            .validate()
        }
        "list_devices" => {
            let _p: ListDevicesParams = wire::decode(params)?;
            ApiRequest::ListDevices.validate()
        }
        "remote_logout" => {
            let p: RemoteLogoutParams = wire::decode(params)?;
            validate_id(&p.device_id, "device_id")?;
            ApiRequest::RemoteLogout {
                device_id: p.device_id,
            }
            .validate()
        }
        "register_push_token" => {
            let p: RegisterPushTokenParams = wire::decode(params)?;
            ApiRequest::RegisterPushToken { input: p.input }.validate()
        }
        "unregister_push_token" => {
            let p: UnregisterPushTokenParams = wire::decode(params)?;
            ApiRequest::UnregisterPushToken { token: p.token }.validate()
        }
        "get_push_settings" => {
            let _p: GetPushSettingsParams = wire::decode(params)?;
            ApiRequest::GetPushSettings.validate()
        }
        "update_push_settings" => {
            let p: UpdatePushSettingsParams = wire::decode(params)?;
            ApiRequest::UpdatePushSettings { input: p.input }.validate()
        }
        "get_privacy_links" => {
            let _p: GetPrivacyLinksParams = wire::decode(params)?;
            ApiRequest::GetPrivacyLinks.validate()
        }
        "update_privacy_links" => {
            let p: UpdatePrivacyLinksParams = wire::decode(params)?;
            ApiRequest::UpdatePrivacyLinks { input: p.input }.validate()
        }
        "send_typing" => {
            let p: SendTypingParams = wire::decode(params)?;
            validate_id(&p.conv_id, "conv_id")?;
            Ok(())
        }
        "subscribe_presence" => {
            let _p: SubscribePresenceParams = wire::decode(params)?;
            Ok(())
        }
        "unsubscribe_presence" => {
            let _p: UnsubscribePresenceParams = wire::decode(params)?;
            Ok(())
        }
        "set_app_state" => {
            let _p: SetAppStateParams = wire::decode(params)?;
            Ok(())
        }
        "handle_notification_click" => {
            let _p: HandleNotificationClickParams = wire::decode(params)?;
            Ok(())
        }
        "search" => {
            let p: SearchParams = wire::decode(params)?;
            ApiRequest::Search { query: p.query }.validate()
        }
        "search_suggestions" => {
            let p: SearchSuggestionsParams = wire::decode(params)?;
            ApiRequest::SearchSuggestions { query: p.query }.validate()
        }
        "search_conversation" => {
            let p: SearchConversationParams = wire::decode(params)?;
            ApiRequest::SearchConversation { query: p.query }.validate()
        }
        "report" => {
            let p: ReportParams = wire::decode(params)?;
            ApiRequest::Report { input: p.input }.validate()
        }
        "list_reports" => {
            let p: ListReportsParams = wire::decode(params)?;
            ApiRequest::ListReports { limit: p.limit }.validate()
        }
        "submit_appeal" => {
            let p: SubmitAppealParams = wire::decode(params)?;
            ApiRequest::SubmitAppeal { input: p.input }.validate()
        }
        "list_appeals" => {
            let p: ListAppealsParams = wire::decode(params)?;
            if let Some(id) = &p.appeal_id {
                validate_id(id, "appeal_id")?;
            }
            ApiRequest::ListAppeals {
                appeal_id: p.appeal_id,
            }
            .validate()
        }
        "export_account" => {
            let _p: ExportAccountParams = wire::decode(params)?;
            ApiRequest::ExportAccount.validate()
        }
        "get_account_exports" => {
            let _p: GetAccountExportsParams = wire::decode(params)?;
            ApiRequest::GetAccountExports.validate()
        }
        "set_minor_mode" => {
            let p: SetMinorModeParams = wire::decode(params)?;
            ApiRequest::SetMinorMode { enabled: p.enabled }.validate()
        }
        _ => Err(wire::invalid("未知方法")),
    }
}
pub async fn execute(
    client: &PhantomClient,
    config: &Config,
    method: &str,
    params: Value,
    call: CallOptions,
) -> SdkResult<Value> {
    match method {
        "new" => {
            let _p: NewParams = wire::decode(params)?;
            wire::encode(&(client.get_state()?))
        }
        "get_state" => {
            let _p: GetStateParams = wire::decode(params)?;
            wire::encode(&(client.get_state()?))
        }
        "get_current_user_id" => {
            let _p: GetCurrentUserIdParams = wire::decode(params)?;
            wire::encode(&(client.get_current_user_id()?))
        }
        "get_app_config" => {
            let _p: GetAppConfigParams = wire::decode(params)?;
            wire::encode(&(client.get_app_config()?))
        }
        "get_sync_status" => {
            let _p: GetSyncStatusParams = wire::decode(params)?;
            wire::encode(&(client.get_sync_status()?))
        }
        "flush_storage" => {
            let _p: FlushStorageParams = wire::decode(params)?;
            wire::encode(&(client.flush_storage(call).await?))
        }
        "login" => {
            let _p: LoginParams = wire::decode(params)?;
            wire::encode(&(client.login(config.login()?, call).await?))
        }
        "wait_ready" => {
            let _p: WaitReadyParams = wire::decode(params)?;
            wire::encode(&(client.wait_ready(call).await?))
        }
        "update_token" => {
            let p: UpdateTokenParams = wire::decode(params)?;
            wire::encode(
                &(client
                    .update_token(
                        TokenUpdate {
                            user_token: p.input.read()?,
                        },
                        call,
                    )
                    .await?),
            )
        }
        "reconnect" => {
            let _p: ReconnectParams = wire::decode(params)?;
            wire::encode(&(client.reconnect(call).await?))
        }
        "logout" => {
            let p: LogoutParams = wire::decode(params)?;
            wire::encode(&(client.logout(p.input, call).await?))
        }
        "shutdown" => {
            let _p: ShutdownParams = wire::decode(params)?;
            wire::encode(&(client.shutdown(call).await?))
        }
        "single_conv_id" => {
            let p: SingleConvIdParams = wire::decode(params)?;
            wire::encode(&(single_conv_id(p.a, p.b)?))
        }
        "send_text" => {
            let p: SendTextParams = wire::decode(params)?;
            wire::encode(&(client.send_text(p.conv_id, p.text, call).await?))
        }
        "send_message" => {
            let p: SendMessageParams = wire::decode(params)?;
            wire::encode(&(client.send_message(p.input, call).await?))
        }
        "resend" => {
            let p: ResendParams = wire::decode(params)?;
            wire::encode(&(client.resend(p.client_msg_id, call).await?))
        }
        "recall" => {
            let p: RecallParams = wire::decode(params)?;
            wire::encode(&(client.recall(p.msg_id, call).await?))
        }
        "forward" => {
            let p: ForwardParams = wire::decode(params)?;
            wire::encode(&(client.forward(p.input, call).await?))
        }
        "delete_local" => {
            let p: DeleteLocalParams = wire::decode(params)?;
            wire::encode(&(client.delete_local(p.msg_id, call).await?))
        }
        "clear_history" => {
            let p: ClearHistoryParams = wire::decode(params)?;
            wire::encode(&(client.clear_history(p.conv_id, call).await?))
        }
        "history" => {
            let p: HistoryParams = wire::decode(params)?;
            wire::encode(&(client.history(p.query, call).await?))
        }
        "get_messages" => {
            let p: GetMessagesParams = wire::decode(params)?;
            wire::encode(&(client.get_messages(p.query)?))
        }
        "mark_message_displayed" => {
            let p: MarkMessageDisplayedParams = wire::decode(params)?;
            wire::encode(&(client.mark_message_displayed(p.msg_id)?))
        }
        "upload_media" => {
            let p: UploadMediaParams = wire::decode(params)?;
            wire::encode(&(client.upload_media(p.input, call).await?))
        }
        "init_upload" => {
            let p: InitUploadParams = wire::decode(params)?;
            wire::encode(&(client.init_upload(p.input, call).await?))
        }
        "upload_part" => {
            let p: UploadPartParams = wire::decode(params)?;
            wire::encode(&(client.upload_part(p.input, call).await?))
        }
        "complete_upload" => {
            let p: CompleteUploadParams = wire::decode(params)?;
            wire::encode(&(client.complete_upload(p.upload_id, call).await?))
        }
        "get_media" => {
            let p: GetMediaParams = wire::decode(params)?;
            wire::encode(&(client.get_media(p.media_id, call).await?))
        }
        "get_media_content" => {
            let p: GetMediaContentParams = wire::decode(params)?;
            wire::encode(
                &(client
                    .get_media_content(p.media_id, p.variant, call)
                    .await?),
            )
        }
        "download_media" => {
            let p: DownloadMediaParams = wire::decode(params)?;
            wire::encode(&(client.download_media(p.input, call).await?))
        }
        "send_media" => {
            let p: SendMediaParams = wire::decode(params)?;
            wire::encode(&(client.send_media(p.input, call).await?))
        }
        "list_conversations" => {
            let p: ListConversationsParams = wire::decode(params)?;
            wire::encode(&(client.list_conversations(p.query, call).await?))
        }
        "get_conversation" => {
            let p: GetConversationParams = wire::decode(params)?;
            wire::encode(&(client.get_conversation(p.conv_id, call).await?))
        }
        "get_conversation_state" => {
            let p: GetConversationStateParams = wire::decode(params)?;
            wire::encode(&(client.get_conversation_state(p.conv_id)?))
        }
        "update_conversation" => {
            let p: UpdateConversationParams = wire::decode(params)?;
            wire::encode(&(client.update_conversation(p.conv_id, p.patch, call).await?))
        }
        "delete_conversation" => {
            let p: DeleteConversationParams = wire::decode(params)?;
            wire::encode(&(client.delete_conversation(p.conv_id, call).await?))
        }
        "mark_read" => {
            let p: MarkReadParams = wire::decode(params)?;
            wire::encode(&(client.mark_read(p.conv_id, p.read_conv_seq, call).await?))
        }
        "get_read_position" => {
            let p: GetReadPositionParams = wire::decode(params)?;
            wire::encode(&(client.get_read_position(p.conv_id, call).await?))
        }
        "save_draft" => {
            let p: SaveDraftParams = wire::decode(params)?;
            wire::encode(&(client.save_draft(p.input, call).await?))
        }
        "clear_draft" => {
            let p: ClearDraftParams = wire::decode(params)?;
            wire::encode(&(client.clear_draft(p.conv_id, p.edited_at, call).await?))
        }
        "handle_message_request" => {
            let p: HandleMessageRequestParams = wire::decode(params)?;
            wire::encode(
                &(client
                    .handle_message_request(p.conv_id, p.accept, call)
                    .await?),
            )
        }
        "get_user_profile" => {
            let p: GetUserProfileParams = wire::decode(params)?;
            wire::encode(&(client.get_user_profile(p.user_id, call).await?))
        }
        "update_profile" => {
            let p: UpdateProfileParams = wire::decode(params)?;
            wire::encode(&(client.update_profile(p.input, call).await?))
        }
        "list_friends" => {
            let _p: ListFriendsParams = wire::decode(params)?;
            wire::encode(&(client.list_friends(call).await?))
        }
        "create_friend_request" => {
            let p: CreateFriendRequestParams = wire::decode(params)?;
            wire::encode(&(client.create_friend_request(p.input, call).await?))
        }
        "list_friend_requests" => {
            let p: ListFriendRequestsParams = wire::decode(params)?;
            wire::encode(&(client.list_friend_requests(p.include_handled, call).await?))
        }
        "handle_friend_request" => {
            let p: HandleFriendRequestParams = wire::decode(params)?;
            wire::encode(
                &(client
                    .handle_friend_request(p.request_id, p.action, call)
                    .await?),
            )
        }
        "update_friend" => {
            let p: UpdateFriendParams = wire::decode(params)?;
            wire::encode(&(client.update_friend(p.friend_id, p.patch, call).await?))
        }
        "delete_friend" => {
            let p: DeleteFriendParams = wire::decode(params)?;
            wire::encode(&(client.delete_friend(p.friend_id, call).await?))
        }
        "list_blacklist" => {
            let _p: ListBlacklistParams = wire::decode(params)?;
            wire::encode(&(client.list_blacklist(call).await?))
        }
        "block_user" => {
            let p: BlockUserParams = wire::decode(params)?;
            wire::encode(&(client.block_user(p.user_id, call).await?))
        }
        "unblock_user" => {
            let p: UnblockUserParams = wire::decode(params)?;
            wire::encode(&(client.unblock_user(p.user_id, call).await?))
        }
        "list_friend_groups" => {
            let _p: ListFriendGroupsParams = wire::decode(params)?;
            wire::encode(&(client.list_friend_groups(call).await?))
        }
        "create_friend_group" => {
            let p: CreateFriendGroupParams = wire::decode(params)?;
            wire::encode(&(client.create_friend_group(p.name, call).await?))
        }
        "rename_friend_group" => {
            let p: RenameFriendGroupParams = wire::decode(params)?;
            wire::encode(&(client.rename_friend_group(p.group_id, p.name, call).await?))
        }
        "delete_friend_group" => {
            let p: DeleteFriendGroupParams = wire::decode(params)?;
            wire::encode(&(client.delete_friend_group(p.group_id, call).await?))
        }
        "get_qr_card" => {
            let _p: GetQrCardParams = wire::decode(params)?;
            wire::encode(&(client.get_qr_card(call).await?))
        }
        "refresh_qr_card" => {
            let _p: RefreshQrCardParams = wire::decode(params)?;
            wire::encode(&(client.refresh_qr_card(call).await?))
        }
        "scan_qr_card" => {
            let p: ScanQrCardParams = wire::decode(params)?;
            wire::encode(&(client.scan_qr_card(p.token, call).await?))
        }
        "get_relation_settings" => {
            let _p: GetRelationSettingsParams = wire::decode(params)?;
            wire::encode(&(client.get_relation_settings(call).await?))
        }
        "update_relation_settings" => {
            let p: UpdateRelationSettingsParams = wire::decode(params)?;
            wire::encode(&(client.update_relation_settings(p.patch, call).await?))
        }
        "list_groups" => {
            let _p: ListGroupsParams = wire::decode(params)?;
            wire::encode(&(client.list_groups(call).await?))
        }
        "create_group" => {
            let p: CreateGroupParams = wire::decode(params)?;
            wire::encode(&(client.create_group(p.input, call).await?))
        }
        "get_group" => {
            let p: GetGroupParams = wire::decode(params)?;
            wire::encode(&(client.get_group(p.group_id, call).await?))
        }
        "update_group" => {
            let p: UpdateGroupParams = wire::decode(params)?;
            wire::encode(&(client.update_group(p.group_id, p.patch, call).await?))
        }
        "list_group_members" => {
            let p: ListGroupMembersParams = wire::decode(params)?;
            wire::encode(&(client.list_group_members(p.group_id, call).await?))
        }
        "invite_group_members" => {
            let p: InviteGroupMembersParams = wire::decode(params)?;
            wire::encode(
                &(client
                    .invite_group_members(p.group_id, p.user_ids, call)
                    .await?),
            )
        }
        "remove_group_member" => {
            let p: RemoveGroupMemberParams = wire::decode(params)?;
            wire::encode(
                &(client
                    .remove_group_member(p.group_id, p.user_id, call)
                    .await?),
            )
        }
        "update_group_role" => {
            let p: UpdateGroupRoleParams = wire::decode(params)?;
            wire::encode(
                &(client
                    .update_group_role(p.group_id, p.user_id, p.role, call)
                    .await?),
            )
        }
        "transfer_group_owner" => {
            let p: TransferGroupOwnerParams = wire::decode(params)?;
            wire::encode(&(client.transfer_group_owner(p.input, call).await?))
        }
        "leave_group" => {
            let p: LeaveGroupParams = wire::decode(params)?;
            wire::encode(&(client.leave_group(p.group_id, call).await?))
        }
        "dissolve_group" => {
            let p: DissolveGroupParams = wire::decode(params)?;
            wire::encode(&(client.dissolve_group(p.group_id, call).await?))
        }
        "mute_group_member" => {
            let p: MuteGroupMemberParams = wire::decode(params)?;
            wire::encode(&(client.mute_group_member(p.input, call).await?))
        }
        "unmute_group_member" => {
            let p: UnmuteGroupMemberParams = wire::decode(params)?;
            wire::encode(
                &(client
                    .unmute_group_member(p.group_id, p.target, call)
                    .await?),
            )
        }
        "get_announcement" => {
            let p: GetAnnouncementParams = wire::decode(params)?;
            wire::encode(&(client.get_announcement(p.group_id, call).await?))
        }
        "update_announcement" => {
            let p: UpdateAnnouncementParams = wire::decode(params)?;
            wire::encode(
                &(client
                    .update_announcement(p.group_id, p.content, call)
                    .await?),
            )
        }
        "update_group_nickname" => {
            let p: UpdateGroupNicknameParams = wire::decode(params)?;
            wire::encode(
                &(client
                    .update_group_nickname(p.group_id, p.user_id, p.nickname, call)
                    .await?),
            )
        }
        "create_group_invite" => {
            let p: CreateGroupInviteParams = wire::decode(params)?;
            wire::encode(
                &(client
                    .create_group_invite(p.group_id, p.expires_in_seconds, call)
                    .await?),
            )
        }
        "join_group_by_invite" => {
            let p: JoinGroupByInviteParams = wire::decode(params)?;
            wire::encode(&(client.join_group_by_invite(p.token, call).await?))
        }
        "handle_group_join_request" => {
            let p: HandleGroupJoinRequestParams = wire::decode(params)?;
            wire::encode(
                &(client
                    .handle_group_join_request(p.group_id, p.request_id, p.accept, call)
                    .await?),
            )
        }
        "list_devices" => {
            let _p: ListDevicesParams = wire::decode(params)?;
            wire::encode(&(client.list_devices(call).await?))
        }
        "remote_logout" => {
            let p: RemoteLogoutParams = wire::decode(params)?;
            wire::encode(&(client.remote_logout(p.device_id, call).await?))
        }
        "register_push_token" => {
            let p: RegisterPushTokenParams = wire::decode(params)?;
            wire::encode(&(client.register_push_token(p.input, call).await?))
        }
        "unregister_push_token" => {
            let p: UnregisterPushTokenParams = wire::decode(params)?;
            wire::encode(&(client.unregister_push_token(p.token, call).await?))
        }
        "get_push_settings" => {
            let _p: GetPushSettingsParams = wire::decode(params)?;
            wire::encode(&(client.get_push_settings(call).await?))
        }
        "update_push_settings" => {
            let p: UpdatePushSettingsParams = wire::decode(params)?;
            wire::encode(&(client.update_push_settings(p.input, call).await?))
        }
        "get_privacy_links" => {
            let _p: GetPrivacyLinksParams = wire::decode(params)?;
            wire::encode(&(client.get_privacy_links(call).await?))
        }
        "update_privacy_links" => {
            let p: UpdatePrivacyLinksParams = wire::decode(params)?;
            wire::encode(&(client.update_privacy_links(p.input, call).await?))
        }
        "send_typing" => {
            let p: SendTypingParams = wire::decode(params)?;
            wire::encode(&(client.send_typing(p.conv_id, p.typing, call).await?))
        }
        "subscribe_presence" => {
            let p: SubscribePresenceParams = wire::decode(params)?;
            wire::encode(&(client.subscribe_presence(p.user_ids, call).await?))
        }
        "unsubscribe_presence" => {
            let p: UnsubscribePresenceParams = wire::decode(params)?;
            wire::encode(&(client.unsubscribe_presence(p.user_ids, call).await?))
        }
        "set_app_state" => {
            let p: SetAppStateParams = wire::decode(params)?;
            wire::encode(&(client.set_app_state(p.foreground, call).await?))
        }
        "handle_notification_click" => {
            let p: HandleNotificationClickParams = wire::decode(params)?;
            wire::encode(&(client.handle_notification_click(p.input)?))
        }
        "search" => {
            let p: SearchParams = wire::decode(params)?;
            wire::encode(&(client.search(p.query, call).await?))
        }
        "search_suggestions" => {
            let p: SearchSuggestionsParams = wire::decode(params)?;
            wire::encode(&(client.search_suggestions(p.query, call).await?))
        }
        "search_conversation" => {
            let p: SearchConversationParams = wire::decode(params)?;
            wire::encode(&(client.search_conversation(p.query, call).await?))
        }
        "report" => {
            let p: ReportParams = wire::decode(params)?;
            wire::encode(&(client.report(p.input, call).await?))
        }
        "list_reports" => {
            let p: ListReportsParams = wire::decode(params)?;
            wire::encode(&(client.list_reports(p.limit, call).await?))
        }
        "submit_appeal" => {
            let p: SubmitAppealParams = wire::decode(params)?;
            wire::encode(&(client.submit_appeal(p.input, call).await?))
        }
        "list_appeals" => {
            let p: ListAppealsParams = wire::decode(params)?;
            wire::encode(&(client.list_appeals(p.appeal_id, call).await?))
        }
        "export_account" => {
            let _p: ExportAccountParams = wire::decode(params)?;
            wire::encode(&(client.export_account(call).await?))
        }
        "get_account_exports" => {
            let _p: GetAccountExportsParams = wire::decode(params)?;
            wire::encode(&(client.get_account_exports(call).await?))
        }
        "set_minor_mode" => {
            let p: SetMinorModeParams = wire::decode(params)?;
            wire::encode(&(client.set_minor_mode(p.enabled, call).await?))
        }
        _ => Err(wire::invalid("未知方法")),
    }
}
pub fn execute_helper(params: Value) -> SdkResult<Value> {
    let p: SingleConvIdParams = wire::decode(params)?;
    wire::encode(&single_conv_id(p.a, p.b)?)
}
