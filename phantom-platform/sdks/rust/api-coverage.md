# Rust API 覆盖清单

基线为 [Rust 接口文档](../../../docs/phantom-platform/sdk/integration.html#rust-core) 的 103 项。表中源码路径和签名来自实际实现；HTTP 路由来自 Java ApiRoutes 与独立 Controller 契约测试。CLI 103 项分派可编译，`schema METHOD` 使用相同参数/结果类型。测试路径中的 protocol/sdk/domain 分别指 crates/phantom-protocol、crates/phantom-sdk、crates/phantom-domain。

所有表内方法已有实现；此表登记源码和受控测试证据，不代表真实平台的所有权限/功能分支已经验收。真实 smoke 与领域验收仍见 [验证记录](verification.md)。

| 序号 | 文档锚点 | 公开签名 | 实现 | 路由/命令 | CLI | 测试证据 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `rust-session` | `fn new(options: ClientOptions) -> SdkResult<Self>` | [client.rs:87](crates/phantom-sdk/src/client.rs#L87) | `本地/媒体编排` | `call new` / `schema new` | sdk/tests/lifecycle.rs、lifecycle_clock.rs、control.rs、storage_matrix.rs |
| 2 | `rust-session` | `fn get_state(&self) -> SdkResult<ConnectionState>` | [client.rs:193](crates/phantom-sdk/src/client.rs#L193) | `本地/媒体编排` | `call get_state` / `schema get_state` | sdk/tests/lifecycle.rs、lifecycle_clock.rs、control.rs、storage_matrix.rs |
| 3 | `rust-session` | `fn get_current_user_id(&self) -> SdkResult<Option<String>>` | [client.rs:202](crates/phantom-sdk/src/client.rs#L202) | `本地/媒体编排` | `call get_current_user_id` / `schema get_current_user_id` | sdk/tests/lifecycle.rs、lifecycle_clock.rs、control.rs、storage_matrix.rs |
| 4 | `rust-session` | `fn get_app_config(&self) -> SdkResult<Option<AppConfig>>` | [client.rs:205](crates/phantom-sdk/src/client.rs#L205) | `本地/媒体编排` | `call get_app_config` / `schema get_app_config` | sdk/tests/lifecycle.rs、lifecycle_clock.rs、control.rs、storage_matrix.rs |
| 5 | `rust-session` | `fn get_sync_status(&self) -> SdkResult<SyncStatus>` | [client.rs:208](crates/phantom-sdk/src/client.rs#L208) | `本地/媒体编排` | `call get_sync_status` / `schema get_sync_status` | sdk/tests/lifecycle.rs、lifecycle_clock.rs、control.rs、storage_matrix.rs |
| 6 | `rust-session` | `async fn flush_storage(&self, call: CallOptions) -> SdkResult<()>` | [client.rs:511](crates/phantom-sdk/src/client.rs#L511) | `本地/媒体编排` | `call flush_storage` / `schema flush_storage` | sdk/tests/lifecycle.rs、lifecycle_clock.rs、control.rs、storage_matrix.rs |
| 7 | `rust-session` | `async fn login(&self, input: LoginOptions, call: CallOptions) -> SdkResult<LoginResult>` | [client.rs:517](crates/phantom-sdk/src/client.rs#L517) | `WS/本地编排` | `call login` / `schema login` | sdk/tests/lifecycle.rs、lifecycle_clock.rs、control.rs、storage_matrix.rs |
| 8 | `rust-session` | `async fn wait_ready(&self, call: CallOptions) -> SdkResult<SyncStatus>` | [client.rs:575](crates/phantom-sdk/src/client.rs#L575) | `WS/本地编排` | `call wait_ready` / `schema wait_ready` | sdk/tests/lifecycle.rs、lifecycle_clock.rs、control.rs、storage_matrix.rs |
| 9 | `rust-session` | `async fn update_token(&self, input: TokenUpdate, call: CallOptions) -> SdkResult<LoginResult>` | [client.rs:546](crates/phantom-sdk/src/client.rs#L546) | `WS/本地编排` | `call update_token` / `schema update_token` | sdk/tests/lifecycle.rs、lifecycle_clock.rs、control.rs、storage_matrix.rs |
| 10 | `rust-session` | `async fn reconnect(&self, call: CallOptions) -> SdkResult<LoginResult>` | [client.rs:553](crates/phantom-sdk/src/client.rs#L553) | `WS/本地编排` | `call reconnect` / `schema reconnect` | sdk/tests/lifecycle.rs、lifecycle_clock.rs、control.rs、storage_matrix.rs |
| 11 | `rust-session` | `async fn logout(&self, input: LogoutOptions, call: CallOptions) -> SdkResult<()>` | [client.rs:505](crates/phantom-sdk/src/client.rs#L505) | `WS/本地编排` | `call logout` / `schema logout` | sdk/tests/lifecycle.rs、lifecycle_clock.rs、control.rs、storage_matrix.rs |
| 12 | `rust-session` | `async fn shutdown(&self, call: CallOptions) -> SdkResult<()>` | [client.rs:508](crates/phantom-sdk/src/client.rs#L508) | `WS/本地编排` | `call shutdown` / `schema shutdown` | sdk/tests/lifecycle.rs、lifecycle_clock.rs、control.rs、storage_matrix.rs |
| 13 | `rust-message` | `fn single_conv_id(a: String, b: String) -> SdkResult<String>` | [validation.rs:33](crates/phantom-domain/src/validation.rs#L33) | `本地/媒体编排` | `call single_conv_id` / `schema single_conv_id` | sdk/tests/sending.rs、lifecycle.rs；domain/tests/contracts.rs |
| 14 | `rust-message` | `async fn send_text(&self, conv_id: String, text: String, call: CallOptions) -> SdkResult<ChatMessage>` | [messages.rs:4](crates/phantom-sdk/src/messages.rs#L4) | `WS SEND_MSG_REQ` | `call send_text` / `schema send_text` | sdk/tests/sending.rs、lifecycle.rs；domain/tests/contracts.rs |
| 15 | `rust-message` | `async fn send_message(&self, input: SendMessageInput, call: CallOptions) -> SdkResult<ChatMessage>` | [messages.rs:36](crates/phantom-sdk/src/messages.rs#L36) | `WS SEND_MSG_REQ` | `call send_message` / `schema send_message` | sdk/tests/sending.rs、lifecycle.rs；domain/tests/contracts.rs |
| 16 | `rust-message` | `async fn resend(&self, client_msg_id: String, call: CallOptions) -> SdkResult<ChatMessage>` | [messages.rs:59](crates/phantom-sdk/src/messages.rs#L59) | `WS SEND_MSG_REQ` | `call resend` / `schema resend` | sdk/tests/sending.rs、lifecycle.rs；domain/tests/contracts.rs |
| 17 | `rust-message` | `async fn recall(&self, msg_id: String, call: CallOptions) -> SdkResult<RecallResult>` | [messages.rs:73](crates/phantom-sdk/src/messages.rs#L73) | `WS RECALL_MSG_REQ` | `call recall` / `schema recall` | sdk/tests/sending.rs、lifecycle.rs；domain/tests/contracts.rs |
| 18 | `rust-message` | `async fn forward(&self, input: ForwardInput, call: CallOptions) -> SdkResult<MessageAction>` | [api.rs:3](crates/phantom-sdk/src/api.rs#L3) | `POST /messages/forward` | `call forward` / `schema forward` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 19 | `rust-message` | `async fn delete_local(&self, msg_id: String, call: CallOptions) -> SdkResult<()>` | [api.rs:19](crates/phantom-sdk/src/api.rs#L19) | `DELETE /messages/{msgId}` | `call delete_local` / `schema delete_local` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 20 | `rust-message` | `async fn clear_history(&self, conv_id: String, call: CallOptions) -> SdkResult<()>` | [api.rs:31](crates/phantom-sdk/src/api.rs#L31) | `DELETE /conversations/{convId}/messages` | `call clear_history` / `schema clear_history` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 21 | `rust-message` | `async fn history(&self, query: HistoryQuery, call: CallOptions) -> SdkResult<MessagePage>` | [api.rs:43](crates/phantom-sdk/src/api.rs#L43) | `GET /conversations/{convId}/messages` | `call history` / `schema history` | protocol/tests/http_domains.rs；sdk/tests/storage_matrix.rs、lifecycle.rs |
| 22 | `rust-message` | `fn get_messages(&self, query: MessageCacheQuery) -> SdkResult<Vec<ChatMessage>>` | [client.rs:211](crates/phantom-sdk/src/client.rs#L211) | `本地/媒体编排` | `call get_messages` / `schema get_messages` | sdk/tests/sending.rs、lifecycle.rs；domain/tests/contracts.rs |
| 23 | `rust-message` | `fn mark_message_displayed(&self, msg_id: String) -> SdkResult<()>` | [messages.rs:81](crates/phantom-sdk/src/messages.rs#L81) | `本地/媒体编排` | `call mark_message_displayed` / `schema mark_message_displayed` | sdk/tests/burn_clock.rs、recovery.rs |
| 24 | `rust-media` | `async fn upload_media(&self, input: UploadInput, call: CallOptions) -> SdkResult<UploadedMedia>` | [media.rs:18](crates/phantom-sdk/src/media.rs#L18) | `本地/媒体编排` | `call upload_media` / `schema upload_media` | sdk/tests/media.rs |
| 25 | `rust-media` | `async fn init_upload(&self, input: UploadMetadata, call: CallOptions) -> SdkResult<UploadSession>` | [api.rs:55](crates/phantom-sdk/src/api.rs#L55) | `POST /media/upload/init` | `call init_upload` / `schema init_upload` | protocol/tests/http_domains.rs；sdk/tests/media.rs |
| 26 | `rust-media` | `async fn upload_part(&self, input: UploadPartInput, call: CallOptions) -> SdkResult<UploadPartResult>` | [api.rs:71](crates/phantom-sdk/src/api.rs#L71) | `PUT /media/upload/{uploadId}/part/{partNumber}` | `call upload_part` / `schema upload_part` | protocol/tests/http_domains.rs；sdk/tests/media.rs |
| 27 | `rust-media` | `async fn complete_upload(&self, upload_id: String, call: CallOptions) -> SdkResult<UploadedMedia>` | [api.rs:87](crates/phantom-sdk/src/api.rs#L87) | `POST /media/upload/{uploadId}/complete` | `call complete_upload` / `schema complete_upload` | protocol/tests/http_domains.rs；sdk/tests/media.rs |
| 28 | `rust-media` | `async fn get_media(&self, media_id: String, call: CallOptions) -> SdkResult<MediaInfo>` | [api.rs:103](crates/phantom-sdk/src/api.rs#L103) | `GET /media/{mediaId}` | `call get_media` / `schema get_media` | protocol/tests/http_domains.rs；sdk/tests/media.rs |
| 29 | `rust-media` | `async fn get_media_content(&self, media_id: String, variant: MediaVariant, call: CallOptions) -> SdkResult<MediaContent>` | [media.rs:36](crates/phantom-sdk/src/media.rs#L36) | `本地/媒体编排` | `call get_media_content` / `schema get_media_content` | sdk/tests/media.rs |
| 30 | `rust-media` | `async fn download_media(&self, input: DownloadInput, call: CallOptions) -> SdkResult<DownloadedMedia>` | [media.rs:49](crates/phantom-sdk/src/media.rs#L49) | `本地/媒体编排` | `call download_media` / `schema download_media` | sdk/tests/media.rs |
| 31 | `rust-media` | `async fn send_media(&self, input: SendMediaInput, call: CallOptions) -> SdkResult<ChatMessage>` | [media.rs:73](crates/phantom-sdk/src/media.rs#L73) | `WS SEND_MSG_REQ` | `call send_media` / `schema send_media` | sdk/tests/media.rs |
| 32 | `rust-conversation` | `async fn list_conversations(&self, query: ConversationQuery, call: CallOptions) -> SdkResult<ConversationPage>` | [api.rs:115](crates/phantom-sdk/src/api.rs#L115) | `GET /conversations` | `call list_conversations` / `schema list_conversations` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 33 | `rust-conversation` | `async fn get_conversation(&self, conv_id: String, call: CallOptions) -> SdkResult<Conversation>` | [api.rs:131](crates/phantom-sdk/src/api.rs#L131) | `GET /conversations/{convId}` | `call get_conversation` / `schema get_conversation` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 34 | `rust-conversation` | `fn get_conversation_state(&self, conv_id: String) -> SdkResult<Option<Conversation>>` | [client.rs:218](crates/phantom-sdk/src/client.rs#L218) | `本地/媒体编排` | `call get_conversation_state` / `schema get_conversation_state` | sdk/tests/conversations.rs |
| 35 | `rust-conversation` | `async fn update_conversation(&self, conv_id: String, patch: ConversationPatch, call: CallOptions) -> SdkResult<Conversation>` | [api.rs:147](crates/phantom-sdk/src/api.rs#L147) | `POST /conversations/{convId}` | `call update_conversation` / `schema update_conversation` | protocol/tests/http_domains.rs；sdk/tests/conversations.rs |
| 36 | `rust-conversation` | `async fn delete_conversation(&self, conv_id: String, call: CallOptions) -> SdkResult<()>` | [api.rs:164](crates/phantom-sdk/src/api.rs#L164) | `DELETE /conversations/{convId}` | `call delete_conversation` / `schema delete_conversation` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 37 | `rust-conversation` | `async fn mark_read(&self, conv_id: String, read_conv_seq: Seq, call: CallOptions) -> SdkResult<ReadPosition>` | [api.rs:176](crates/phantom-sdk/src/api.rs#L176) | `PUT /conversations/{convId}/read-positions` | `call mark_read` / `schema mark_read` | protocol/tests/http_domains.rs；sdk/tests/conversations.rs |
| 38 | `rust-conversation` | `async fn get_read_position(&self, conv_id: String, call: CallOptions) -> SdkResult<ReadPosition>` | [api.rs:196](crates/phantom-sdk/src/api.rs#L196) | `GET /conversations/{convId}/read-positions` | `call get_read_position` / `schema get_read_position` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 39 | `rust-conversation` | `async fn save_draft(&self, input: DraftInput, call: CallOptions) -> SdkResult<ConversationDraft>` | [api.rs:212](crates/phantom-sdk/src/api.rs#L212) | `PUT /conversations/{convId}/draft` | `call save_draft` / `schema save_draft` | protocol/tests/http_domains.rs；sdk/tests/conversations.rs |
| 40 | `rust-conversation` | `async fn clear_draft(&self, conv_id: String, edited_at: Option<TimestampMs>, call: CallOptions) -> SdkResult<ConversationDraft>` | [api.rs:228](crates/phantom-sdk/src/api.rs#L228) | `DELETE /conversations/{convId}/draft` | `call clear_draft` / `schema clear_draft` | protocol/tests/http_domains.rs；sdk/tests/conversations.rs |
| 41 | `rust-conversation` | `async fn handle_message_request(&self, conv_id: String, accept: bool, call: CallOptions) -> SdkResult<Conversation>` | [api.rs:245](crates/phantom-sdk/src/api.rs#L245) | `POST /conversations/{convId}/request` | `call handle_message_request` / `schema handle_message_request` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 42 | `rust-relation` | `async fn get_user_profile(&self, user_id: Option<String>, call: CallOptions) -> SdkResult<UserProfile>` | [api.rs:262](crates/phantom-sdk/src/api.rs#L262) | `GET /users/{userId}` | `call get_user_profile` / `schema get_user_profile` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 43 | `rust-relation` | `async fn update_profile(&self, input: ProfileUpdate, call: CallOptions) -> SdkResult<UserProfile>` | [api.rs:278](crates/phantom-sdk/src/api.rs#L278) | `PUT /users/me` | `call update_profile` / `schema update_profile` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 44 | `rust-relation` | `async fn list_friends(&self, call: CallOptions) -> SdkResult<Vec<Friend>>` | [api.rs:294](crates/phantom-sdk/src/api.rs#L294) | `GET /friends` | `call list_friends` / `schema list_friends` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 45 | `rust-relation` | `async fn create_friend_request(&self, input: FriendRequestInput, call: CallOptions) -> SdkResult<FriendRequestResult>` | [api.rs:306](crates/phantom-sdk/src/api.rs#L306) | `POST /friends/requests` | `call create_friend_request` / `schema create_friend_request` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 46 | `rust-relation` | `async fn list_friend_requests(&self, include_handled: bool, call: CallOptions) -> SdkResult<FriendRequestPage>` | [api.rs:322](crates/phantom-sdk/src/api.rs#L322) | `GET /friends/requests` | `call list_friend_requests` / `schema list_friend_requests` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 47 | `rust-relation` | `async fn handle_friend_request(&self, request_id: String, action: FriendRequestAction, call: CallOptions) -> SdkResult<FriendRequest>` | [api.rs:338](crates/phantom-sdk/src/api.rs#L338) | `POST /friends/requests/{requestId}` | `call handle_friend_request` / `schema handle_friend_request` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 48 | `rust-relation` | `async fn update_friend(&self, friend_id: String, patch: FriendPatch, call: CallOptions) -> SdkResult<Friend>` | [api.rs:355](crates/phantom-sdk/src/api.rs#L355) | `PUT /friends/{friendId}` | `call update_friend` / `schema update_friend` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 49 | `rust-relation` | `async fn delete_friend(&self, friend_id: String, call: CallOptions) -> SdkResult<()>` | [api.rs:372](crates/phantom-sdk/src/api.rs#L372) | `DELETE /friends/{friendId}` | `call delete_friend` / `schema delete_friend` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 50 | `rust-relation` | `async fn list_blacklist(&self, call: CallOptions) -> SdkResult<Vec<BlacklistEntry>>` | [api.rs:384](crates/phantom-sdk/src/api.rs#L384) | `GET /blacklist` | `call list_blacklist` / `schema list_blacklist` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 51 | `rust-relation` | `async fn block_user(&self, user_id: String, call: CallOptions) -> SdkResult<()>` | [api.rs:396](crates/phantom-sdk/src/api.rs#L396) | `POST /blacklist` | `call block_user` / `schema block_user` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 52 | `rust-relation` | `async fn unblock_user(&self, user_id: String, call: CallOptions) -> SdkResult<()>` | [api.rs:408](crates/phantom-sdk/src/api.rs#L408) | `DELETE /blacklist` | `call unblock_user` / `schema unblock_user` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 53 | `rust-relation` | `async fn list_friend_groups(&self, call: CallOptions) -> SdkResult<Vec<FriendGroup>>` | [api.rs:420](crates/phantom-sdk/src/api.rs#L420) | `GET /friends/groups` | `call list_friend_groups` / `schema list_friend_groups` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 54 | `rust-relation` | `async fn create_friend_group(&self, name: String, call: CallOptions) -> SdkResult<FriendGroup>` | [api.rs:432](crates/phantom-sdk/src/api.rs#L432) | `POST /friends/groups` | `call create_friend_group` / `schema create_friend_group` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 55 | `rust-relation` | `async fn rename_friend_group(&self, group_id: String, name: String, call: CallOptions) -> SdkResult<FriendGroup>` | [api.rs:448](crates/phantom-sdk/src/api.rs#L448) | `PUT /friends/groups/{groupId}` | `call rename_friend_group` / `schema rename_friend_group` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 56 | `rust-relation` | `async fn delete_friend_group(&self, group_id: String, call: CallOptions) -> SdkResult<()>` | [api.rs:465](crates/phantom-sdk/src/api.rs#L465) | `DELETE /friends/groups/{groupId}` | `call delete_friend_group` / `schema delete_friend_group` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 57 | `rust-relation` | `async fn get_qr_card(&self, call: CallOptions) -> SdkResult<QrCard>` | [api.rs:477](crates/phantom-sdk/src/api.rs#L477) | `GET /friends/qr` | `call get_qr_card` / `schema get_qr_card` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 58 | `rust-relation` | `async fn refresh_qr_card(&self, call: CallOptions) -> SdkResult<QrCard>` | [api.rs:489](crates/phantom-sdk/src/api.rs#L489) | `POST /friends/qr/refresh` | `call refresh_qr_card` / `schema refresh_qr_card` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 59 | `rust-relation` | `async fn scan_qr_card(&self, token: String, call: CallOptions) -> SdkResult<QrCardScanResult>` | [api.rs:501](crates/phantom-sdk/src/api.rs#L501) | `POST /friends/qr/scan` | `call scan_qr_card` / `schema scan_qr_card` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 60 | `rust-relation` | `async fn get_relation_settings(&self, call: CallOptions) -> SdkResult<RelationSettings>` | [api.rs:517](crates/phantom-sdk/src/api.rs#L517) | `GET /friends/settings` | `call get_relation_settings` / `schema get_relation_settings` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 61 | `rust-relation` | `async fn update_relation_settings(&self, patch: RelationSettingsPatch, call: CallOptions) -> SdkResult<RelationSettings>` | [api.rs:529](crates/phantom-sdk/src/api.rs#L529) | `PUT /friends/settings` | `call update_relation_settings` / `schema update_relation_settings` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 62 | `rust-group` | `async fn list_groups(&self, call: CallOptions) -> SdkResult<Vec<Group>>` | [api.rs:545](crates/phantom-sdk/src/api.rs#L545) | `GET /groups` | `call list_groups` / `schema list_groups` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 63 | `rust-group` | `async fn create_group(&self, input: CreateGroupInput, call: CallOptions) -> SdkResult<Group>` | [api.rs:557](crates/phantom-sdk/src/api.rs#L557) | `POST /groups` | `call create_group` / `schema create_group` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 64 | `rust-group` | `async fn get_group(&self, group_id: String, call: CallOptions) -> SdkResult<GroupDetail>` | [api.rs:573](crates/phantom-sdk/src/api.rs#L573) | `GET /groups/{groupId}` | `call get_group` / `schema get_group` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 65 | `rust-group` | `async fn update_group(&self, group_id: String, patch: GroupPatch, call: CallOptions) -> SdkResult<GroupDetail>` | [api.rs:585](crates/phantom-sdk/src/api.rs#L585) | `PUT /groups/{groupId}` | `call update_group` / `schema update_group` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 66 | `rust-group` | `async fn list_group_members(&self, group_id: String, call: CallOptions) -> SdkResult<Vec<GroupMember>>` | [api.rs:602](crates/phantom-sdk/src/api.rs#L602) | `GET /groups/{groupId}/members` | `call list_group_members` / `schema list_group_members` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 67 | `rust-group` | `async fn invite_group_members(&self, group_id: String, user_ids: Vec<String>, call: CallOptions) -> SdkResult<()>` | [api.rs:618](crates/phantom-sdk/src/api.rs#L618) | `POST /groups/{groupId}/members` | `call invite_group_members` / `schema invite_group_members` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 68 | `rust-group` | `async fn remove_group_member(&self, group_id: String, user_id: String, call: CallOptions) -> SdkResult<()>` | [api.rs:635](crates/phantom-sdk/src/api.rs#L635) | `DELETE /groups/{groupId}/members` | `call remove_group_member` / `schema remove_group_member` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 69 | `rust-group` | `async fn update_group_role(&self, group_id: String, user_id: String, role: AssignableGroupRole, call: CallOptions) -> SdkResult<()>` | [api.rs:652](crates/phantom-sdk/src/api.rs#L652) | `PUT /groups/{groupId}/members/{userId}/role` | `call update_group_role` / `schema update_group_role` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 70 | `rust-group` | `async fn transfer_group_owner(&self, input: TransferGroupInput, call: CallOptions) -> SdkResult<Group>` | [api.rs:674](crates/phantom-sdk/src/api.rs#L674) | `POST /groups/{groupId}/transfer` | `call transfer_group_owner` / `schema transfer_group_owner` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 71 | `rust-group` | `async fn leave_group(&self, group_id: String, call: CallOptions) -> SdkResult<()>` | [api.rs:690](crates/phantom-sdk/src/api.rs#L690) | `DELETE /groups/{groupId}/leave` | `call leave_group` / `schema leave_group` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 72 | `rust-group` | `async fn dissolve_group(&self, group_id: String, call: CallOptions) -> SdkResult<()>` | [api.rs:702](crates/phantom-sdk/src/api.rs#L702) | `DELETE /groups/{groupId}` | `call dissolve_group` / `schema dissolve_group` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 73 | `rust-group` | `async fn mute_group_member(&self, input: MuteInput, call: CallOptions) -> SdkResult<()>` | [api.rs:714](crates/phantom-sdk/src/api.rs#L714) | `POST /groups/{groupId}/mutes` | `call mute_group_member` / `schema mute_group_member` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 74 | `rust-group` | `async fn unmute_group_member(&self, group_id: String, target: MuteTarget, call: CallOptions) -> SdkResult<()>` | [api.rs:726](crates/phantom-sdk/src/api.rs#L726) | `DELETE /groups/{groupId}/mutes` | `call unmute_group_member` / `schema unmute_group_member` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 75 | `rust-group` | `async fn get_announcement(&self, group_id: String, call: CallOptions) -> SdkResult<Announcement>` | [api.rs:743](crates/phantom-sdk/src/api.rs#L743) | `GET /groups/{groupId}/announcement` | `call get_announcement` / `schema get_announcement` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 76 | `rust-group` | `async fn update_announcement(&self, group_id: String, content: String, call: CallOptions) -> SdkResult<Announcement>` | [api.rs:759](crates/phantom-sdk/src/api.rs#L759) | `PUT /groups/{groupId}/announcement` | `call update_announcement` / `schema update_announcement` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 77 | `rust-group` | `async fn update_group_nickname(&self, group_id: String, user_id: String, nickname: String, call: CallOptions) -> SdkResult<()>` | [api.rs:776](crates/phantom-sdk/src/api.rs#L776) | `PUT /groups/{groupId}/members/{userId}/nickname` | `call update_group_nickname` / `schema update_group_nickname` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 78 | `rust-group` | `async fn create_group_invite(&self, group_id: String, expires_in_seconds: Option<u32>, call: CallOptions) -> SdkResult<GroupInvite>` | [api.rs:798](crates/phantom-sdk/src/api.rs#L798) | `POST /groups/{groupId}/invites` | `call create_group_invite` / `schema create_group_invite` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 79 | `rust-group` | `async fn join_group_by_invite(&self, token: String, call: CallOptions) -> SdkResult<JoinGroupResult>` | [api.rs:818](crates/phantom-sdk/src/api.rs#L818) | `POST /groups/join` | `call join_group_by_invite` / `schema join_group_by_invite` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 80 | `rust-group` | `async fn handle_group_join_request(&self, group_id: String, request_id: String, accept: bool, call: CallOptions) -> SdkResult<JoinGroupResult>` | [api.rs:834](crates/phantom-sdk/src/api.rs#L834) | `POST /groups/{groupId}/join-requests/{requestId}` | `call handle_group_join_request` / `schema handle_group_join_request` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 81 | `rust-device` | `async fn list_devices(&self, call: CallOptions) -> SdkResult<Vec<DeviceInfo>>` | [api.rs:856](crates/phantom-sdk/src/api.rs#L856) | `GET /devices` | `call list_devices` / `schema list_devices` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 82 | `rust-device` | `async fn remote_logout(&self, device_id: String, call: CallOptions) -> SdkResult<()>` | [api.rs:868](crates/phantom-sdk/src/api.rs#L868) | `POST /devices/{deviceId}/logout` | `call remote_logout` / `schema remote_logout` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 83 | `rust-device` | `async fn register_push_token(&self, input: PushTokenInput, call: CallOptions) -> SdkResult<()>` | [api.rs:880](crates/phantom-sdk/src/api.rs#L880) | `POST /push/tokens` | `call register_push_token` / `schema register_push_token` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 84 | `rust-device` | `async fn unregister_push_token(&self, token: String, call: CallOptions) -> SdkResult<()>` | [api.rs:896](crates/phantom-sdk/src/api.rs#L896) | `DELETE /push/tokens` | `call unregister_push_token` / `schema unregister_push_token` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 85 | `rust-device` | `async fn get_push_settings(&self, call: CallOptions) -> SdkResult<PushSettings>` | [api.rs:908](crates/phantom-sdk/src/api.rs#L908) | `GET /push/settings` | `call get_push_settings` / `schema get_push_settings` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 86 | `rust-device` | `async fn update_push_settings(&self, input: PushSettingsInput, call: CallOptions) -> SdkResult<PushSettings>` | [api.rs:920](crates/phantom-sdk/src/api.rs#L920) | `PUT /push/settings` | `call update_push_settings` / `schema update_push_settings` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 87 | `rust-device` | `async fn get_privacy_links(&self, call: CallOptions) -> SdkResult<PushSettings>` | [api.rs:936](crates/phantom-sdk/src/api.rs#L936) | `GET /account/privacy-links` | `call get_privacy_links` / `schema get_privacy_links` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 88 | `rust-device` | `async fn update_privacy_links(&self, input: PrivacyLinksInput, call: CallOptions) -> SdkResult<PushSettings>` | [api.rs:948](crates/phantom-sdk/src/api.rs#L948) | `PUT /account/privacy-links` | `call update_privacy_links` / `schema update_privacy_links` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 89 | `rust-device` | `async fn send_typing(&self, conv_id: String, typing: bool, call: CallOptions) -> SdkResult<()>` | [signaling.rs:3](crates/phantom-sdk/src/signaling.rs#L3) | `WS/本地编排` | `call send_typing` / `schema send_typing` | sdk/tests/lifecycle_clock.rs |
| 90 | `rust-device` | `async fn subscribe_presence(&self, user_ids: Vec<String>, call: CallOptions) -> SdkResult<Vec<Presence>>` | [signaling.rs:57](crates/phantom-sdk/src/signaling.rs#L57) | `WS/本地编排` | `call subscribe_presence` / `schema subscribe_presence` | sdk/tests/lifecycle_clock.rs |
| 91 | `rust-device` | `async fn unsubscribe_presence(&self, user_ids: Vec<String>, call: CallOptions) -> SdkResult<()>` | [signaling.rs:80](crates/phantom-sdk/src/signaling.rs#L80) | `WS/本地编排` | `call unsubscribe_presence` / `schema unsubscribe_presence` | sdk/tests/lifecycle_clock.rs |
| 92 | `rust-device` | `async fn set_app_state(&self, foreground: bool, call: CallOptions) -> SdkResult<()>` | [signaling.rs:103](crates/phantom-sdk/src/signaling.rs#L103) | `WS/本地编排` | `call set_app_state` / `schema set_app_state` | sdk/tests/lifecycle_clock.rs |
| 93 | `rust-device` | `fn handle_notification_click(&self, input: NotificationClick) -> SdkResult<()>` | [signaling.rs:120](crates/phantom-sdk/src/signaling.rs#L120) | `本地/媒体编排` | `call handle_notification_click` / `schema handle_notification_click` | sdk/tests/lifecycle_clock.rs |
| 94 | `rust-search` | `async fn search(&self, query: SearchQuery, call: CallOptions) -> SdkResult<SearchResult>` | [api.rs:964](crates/phantom-sdk/src/api.rs#L964) | `GET /search` | `call search` / `schema search` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 95 | `rust-search` | `async fn search_suggestions(&self, query: SearchQuery, call: CallOptions) -> SdkResult<SearchSuggestions>` | [api.rs:976](crates/phantom-sdk/src/api.rs#L976) | `GET /search/suggest` | `call search_suggestions` / `schema search_suggestions` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 96 | `rust-search` | `async fn search_conversation(&self, query: ConversationSearchQuery, call: CallOptions) -> SdkResult<ConversationSearch>` | [api.rs:992](crates/phantom-sdk/src/api.rs#L992) | `GET /conversations/{convId}/search` | `call search_conversation` / `schema search_conversation` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 97 | `rust-search` | `async fn report(&self, input: ReportInput, call: CallOptions) -> SdkResult<Report>` | [api.rs:1008](crates/phantom-sdk/src/api.rs#L1008) | `POST /reports` | `call report` / `schema report` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 98 | `rust-search` | `async fn list_reports(&self, limit: u32, call: CallOptions) -> SdkResult<Vec<Report>>` | [api.rs:1020](crates/phantom-sdk/src/api.rs#L1020) | `GET /reports` | `call list_reports` / `schema list_reports` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 99 | `rust-search` | `async fn submit_appeal(&self, input: AppealInput, call: CallOptions) -> SdkResult<Appeal>` | [api.rs:1032](crates/phantom-sdk/src/api.rs#L1032) | `POST /appeals` | `call submit_appeal` / `schema submit_appeal` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 100 | `rust-search` | `async fn list_appeals(&self, appeal_id: Option<String>, call: CallOptions) -> SdkResult<Vec<Appeal>>` | [api.rs:1044](crates/phantom-sdk/src/api.rs#L1044) | `GET /appeals` | `call list_appeals` / `schema list_appeals` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 101 | `rust-search` | `async fn export_account(&self, call: CallOptions) -> SdkResult<Vec<AccountExport>>` | [api.rs:1060](crates/phantom-sdk/src/api.rs#L1060) | `POST /account/export` | `call export_account` / `schema export_account` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 102 | `rust-search` | `async fn get_account_exports(&self, call: CallOptions) -> SdkResult<Vec<AccountExport>>` | [api.rs:1072](crates/phantom-sdk/src/api.rs#L1072) | `GET /account/export` | `call get_account_exports` / `schema get_account_exports` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |
| 103 | `rust-search` | `async fn set_minor_mode(&self, enabled: bool, call: CallOptions) -> SdkResult<()>` | [api.rs:1084](crates/phantom-sdk/src/api.rs#L1084) | `POST /account/minor-mode` | `call set_minor_mode` / `schema set_minor_mode` | protocol/tests/http_domains.rs；sdk/tests/domains.rs |

## 句柄与配置扩展

| 公开入口 | 实现与契约 | 验证/宿主示例 |
| --- | --- | --- |
| `PhantomClient::subscribe`、`EventSubscription::next/close` | [events.rs](crates/phantom-core/src/events.rs)；独立队列、单消费者、关闭/溢出与会话隔离 | sdk/tests/control.rs |
| `CancellationToken::new/cancel/is_cancelled`、Clone | [call.rs](crates/phantom-core/src/call.rs)；整体 deadline 和提交阶段 | sdk/tests/control.rs、media.rs、sending.rs；CLI scenario/cancel |
| `ClientOptions::new`、`storage: Option<StorageOptions>` | [options.rs](crates/phantom-sdk/src/options.rs)；None 默认 SQLite，Memory/Directory/Custom | sdk/tests/storage_matrix.rs；[基础示例](crates/phantom-sdk/examples/basic.rs) |
| `StoreFactory`、`Store` | [storage.rs](crates/phantom-ports/src/storage.rs)；capabilities/open、namespace/load/query/get/apply/flush/clear/close | [独立宿主实现](crates/phantom-store/examples/support/host.rs)；[trait 接入示例](crates/phantom-sdk/examples/custom_storage.rs) |
| `StorageCallbacks`、`CallbackStoreFactory`、句柄/回调上下文 | [callback.rs](crates/phantom-store/src/callback.rs)；全部必需回调、版本和资源所有权 | store/tests/callbacks.rs；[回调接入示例](crates/phantom-sdk/examples/callback_storage.rs) |
| Memory / SQLite 存储 | [memory.rs](crates/phantom-store/src/memory.rs)、[sqlite.rs](crates/phantom-store/src/sqlite.rs)；相同事务契约 | store/tests/storage.rs、migration.rs；sdk/tests/storage_matrix.rs、recovery.rs |

## DTO、错误、事件与存储契约类型索引

下表枚举 domain、ports、公开存储适配、Facade 选项和事件/取消句柄的公开类型；字段与全部枚举分支以源码链接为准。`json-schema` feature 同时为领域类型提供 CLI schema，二进制仍通过共享 proto 生成。自定义后端声明的持久性及版本由宿主负责，独立宿主示例为 Volatile。

| 类型 | 定义 |
| --- | --- |
| `CancellationToken` | [crates/phantom-core/src/call.rs:23](crates/phantom-core/src/call.rs#L23) |
| `CallOptions` | [crates/phantom-core/src/call.rs:88](crates/phantom-core/src/call.rs#L88) |
| `EventSubscription` | [crates/phantom-core/src/events.rs:35](crates/phantom-core/src/events.rs#L35) |
| `HostServices` | [crates/phantom-core/src/kernel.rs:33](crates/phantom-core/src/kernel.rs#L33) |
| `ConversationQuery` | [crates/phantom-domain/src/conversation.rs:6](crates/phantom-domain/src/conversation.rs#L6) |
| `ConversationPage` | [crates/phantom-domain/src/conversation.rs:13](crates/phantom-domain/src/conversation.rs#L13) |
| `Conversation` | [crates/phantom-domain/src/conversation.rs:23](crates/phantom-domain/src/conversation.rs#L23) |
| `ConversationPatch` | [crates/phantom-domain/src/conversation.rs:45](crates/phantom-domain/src/conversation.rs#L45) |
| `ReadPosition` | [crates/phantom-domain/src/conversation.rs:52](crates/phantom-domain/src/conversation.rs#L52) |
| `DraftInput` | [crates/phantom-domain/src/conversation.rs:60](crates/phantom-domain/src/conversation.rs#L60) |
| `ConversationDraft` | [crates/phantom-domain/src/conversation.rs:68](crates/phantom-domain/src/conversation.rs#L68) |
| `DeviceInfo` | [crates/phantom-domain/src/device.rs:6](crates/phantom-domain/src/device.rs#L6) |
| `PushTokenInput` | [crates/phantom-domain/src/device.rs:19](crates/phantom-domain/src/device.rs#L19) |
| `PushSettings` | [crates/phantom-domain/src/device.rs:27](crates/phantom-domain/src/device.rs#L27) |
| `PushSettingsInput` | [crates/phantom-domain/src/device.rs:36](crates/phantom-domain/src/device.rs#L36) |
| `PrivacyLinksInput` | [crates/phantom-domain/src/device.rs:43](crates/phantom-domain/src/device.rs#L43) |
| `PresenceStatus` | [crates/phantom-domain/src/device.rs:49](crates/phantom-domain/src/device.rs#L49) |
| `Presence` | [crates/phantom-domain/src/device.rs:58](crates/phantom-domain/src/device.rs#L58) |
| `TypingEvent` | [crates/phantom-domain/src/device.rs:66](crates/phantom-domain/src/device.rs#L66) |
| `NotificationClick` | [crates/phantom-domain/src/device.rs:74](crates/phantom-domain/src/device.rs#L74) |
| `SdkError` | [crates/phantom-domain/src/error.rs:5](crates/phantom-domain/src/error.rs#L5) |
| `ErrorKind` | [crates/phantom-domain/src/error.rs:19](crates/phantom-domain/src/error.rs#L19) |
| `OperationOutcome` | [crates/phantom-domain/src/error.rs:40](crates/phantom-domain/src/error.rs#L40) |
| `SubscriptionOptions` | [crates/phantom-domain/src/event.rs:6](crates/phantom-domain/src/event.rs#L6) |
| `SdkEvent` | [crates/phantom-domain/src/event.rs:12](crates/phantom-domain/src/event.rs#L12) |
| `EventKind` | [crates/phantom-domain/src/event.rs:19](crates/phantom-domain/src/event.rs#L19) |
| `DisconnectInfo` | [crates/phantom-domain/src/event.rs:39](crates/phantom-domain/src/event.rs#L39) |
| `KickInfo` | [crates/phantom-domain/src/event.rs:46](crates/phantom-domain/src/event.rs#L46) |
| `KickReason` | [crates/phantom-domain/src/event.rs:52](crates/phantom-domain/src/event.rs#L52) |
| `MessageRemoved` | [crates/phantom-domain/src/event.rs:62](crates/phantom-domain/src/event.rs#L62) |
| `SyncEvent` | [crates/phantom-domain/src/event.rs:70](crates/phantom-domain/src/event.rs#L70) |
| `SyncPayload` | [crates/phantom-domain/src/event.rs:81](crates/phantom-domain/src/event.rs#L81) |
| `NewMessageEvent` | [crates/phantom-domain/src/event.rs:102](crates/phantom-domain/src/event.rs#L102) |
| `RecallEvent` | [crates/phantom-domain/src/event.rs:112](crates/phantom-domain/src/event.rs#L112) |
| `ReadPositionEvent` | [crates/phantom-domain/src/event.rs:120](crates/phantom-domain/src/event.rs#L120) |
| `ReadReceiptEvent` | [crates/phantom-domain/src/event.rs:127](crates/phantom-domain/src/event.rs#L127) |
| `ConversationStateField` | [crates/phantom-domain/src/event.rs:135](crates/phantom-domain/src/event.rs#L135) |
| `ConversationStateEvent` | [crates/phantom-domain/src/event.rs:146](crates/phantom-domain/src/event.rs#L146) |
| `RelationEventType` | [crates/phantom-domain/src/event.rs:154](crates/phantom-domain/src/event.rs#L154) |
| `RelationEvent` | [crates/phantom-domain/src/event.rs:170](crates/phantom-domain/src/event.rs#L170) |
| `GroupEventType` | [crates/phantom-domain/src/event.rs:186](crates/phantom-domain/src/event.rs#L186) |
| `GroupEvent` | [crates/phantom-domain/src/event.rs:201](crates/phantom-domain/src/event.rs#L201) |
| `ProfileEventType` | [crates/phantom-domain/src/event.rs:210](crates/phantom-domain/src/event.rs#L210) |
| `ProfileEvent` | [crates/phantom-domain/src/event.rs:219](crates/phantom-domain/src/event.rs#L219) |
| `RestrictedDeliveryEvent` | [crates/phantom-domain/src/event.rs:227](crates/phantom-domain/src/event.rs#L227) |
| `MessageStatusEvent` | [crates/phantom-domain/src/event.rs:235](crates/phantom-domain/src/event.rs#L235) |
| `ReportEvent` | [crates/phantom-domain/src/event.rs:244](crates/phantom-domain/src/event.rs#L244) |
| `Group` | [crates/phantom-domain/src/group.rs:6](crates/phantom-domain/src/group.rs#L6) |
| `GroupDetail` | [crates/phantom-domain/src/group.rs:18](crates/phantom-domain/src/group.rs#L18) |
| `CreateGroupInput` | [crates/phantom-domain/src/group.rs:27](crates/phantom-domain/src/group.rs#L27) |
| `GroupPatch` | [crates/phantom-domain/src/group.rs:34](crates/phantom-domain/src/group.rs#L34) |
| `GroupRole` | [crates/phantom-domain/src/group.rs:41](crates/phantom-domain/src/group.rs#L41) |
| `AssignableGroupRole` | [crates/phantom-domain/src/group.rs:49](crates/phantom-domain/src/group.rs#L49) |
| `GroupMember` | [crates/phantom-domain/src/group.rs:56](crates/phantom-domain/src/group.rs#L56) |
| `TransferGroupInput` | [crates/phantom-domain/src/group.rs:67](crates/phantom-domain/src/group.rs#L67) |
| `MuteTarget` | [crates/phantom-domain/src/group.rs:74](crates/phantom-domain/src/group.rs#L74) |
| `MuteDuration` | [crates/phantom-domain/src/group.rs:80](crates/phantom-domain/src/group.rs#L80) |
| `MuteInput` | [crates/phantom-domain/src/group.rs:89](crates/phantom-domain/src/group.rs#L89) |
| `Announcement` | [crates/phantom-domain/src/group.rs:97](crates/phantom-domain/src/group.rs#L97) |
| `GroupInvite` | [crates/phantom-domain/src/group.rs:106](crates/phantom-domain/src/group.rs#L106) |
| `JoinGroupResult` | [crates/phantom-domain/src/group.rs:116](crates/phantom-domain/src/group.rs#L116) |
| `Seq` | [crates/phantom-domain/src/lib.rs:7](crates/phantom-domain/src/lib.rs#L7) |
| `TimestampMs` | [crates/phantom-domain/src/lib.rs:8](crates/phantom-domain/src/lib.rs#L8) |
| `StringMap` | [crates/phantom-domain/src/lib.rs:9](crates/phantom-domain/src/lib.rs#L9) |
| `SdkResult` | [crates/phantom-domain/src/lib.rs:10](crates/phantom-domain/src/lib.rs#L10) |
| `MediaType` | [crates/phantom-domain/src/media.rs:5](crates/phantom-domain/src/media.rs#L5) |
| `MediaVariant` | [crates/phantom-domain/src/media.rs:13](crates/phantom-domain/src/media.rs#L13) |
| `FileSource` | [crates/phantom-domain/src/media.rs:19](crates/phantom-domain/src/media.rs#L19) |
| `UploadMetadata` | [crates/phantom-domain/src/media.rs:26](crates/phantom-domain/src/media.rs#L26) |
| `UploadInput` | [crates/phantom-domain/src/media.rs:41](crates/phantom-domain/src/media.rs#L41) |
| `UploadSession` | [crates/phantom-domain/src/media.rs:49](crates/phantom-domain/src/media.rs#L49) |
| `UploadPartInput` | [crates/phantom-domain/src/media.rs:63](crates/phantom-domain/src/media.rs#L63) |
| `UploadPartResult` | [crates/phantom-domain/src/media.rs:71](crates/phantom-domain/src/media.rs#L71) |
| `MediaInfo` | [crates/phantom-domain/src/media.rs:80](crates/phantom-domain/src/media.rs#L80) |
| `UploadedMedia` | [crates/phantom-domain/src/media.rs:96](crates/phantom-domain/src/media.rs#L96) |
| `MediaContent` | [crates/phantom-domain/src/media.rs:103](crates/phantom-domain/src/media.rs#L103) |
| `DownloadInput` | [crates/phantom-domain/src/media.rs:110](crates/phantom-domain/src/media.rs#L110) |
| `DownloadedMedia` | [crates/phantom-domain/src/media.rs:120](crates/phantom-domain/src/media.rs#L120) |
| `SendMediaInput` | [crates/phantom-domain/src/media.rs:128](crates/phantom-domain/src/media.rs#L128) |
| `TransferProgress` | [crates/phantom-domain/src/media.rs:139](crates/phantom-domain/src/media.rs#L139) |
| `TransferStage` | [crates/phantom-domain/src/media.rs:149](crates/phantom-domain/src/media.rs#L149) |
| `ConversationType` | [crates/phantom-domain/src/message.rs:5](crates/phantom-domain/src/message.rs#L5) |
| `MessageKind` | [crates/phantom-domain/src/message.rs:12](crates/phantom-domain/src/message.rs#L12) |
| `MessageState` | [crates/phantom-domain/src/message.rs:27](crates/phantom-domain/src/message.rs#L27) |
| `MessageContent` | [crates/phantom-domain/src/message.rs:39](crates/phantom-domain/src/message.rs#L39) |
| `TextContent` | [crates/phantom-domain/src/message.rs:55](crates/phantom-domain/src/message.rs#L55) |
| `EmojiContent` | [crates/phantom-domain/src/message.rs:61](crates/phantom-domain/src/message.rs#L61) |
| `ImageContent` | [crates/phantom-domain/src/message.rs:67](crates/phantom-domain/src/message.rs#L67) |
| `VoiceContent` | [crates/phantom-domain/src/message.rs:78](crates/phantom-domain/src/message.rs#L78) |
| `VideoContent` | [crates/phantom-domain/src/message.rs:86](crates/phantom-domain/src/message.rs#L86) |
| `FileContent` | [crates/phantom-domain/src/message.rs:98](crates/phantom-domain/src/message.rs#L98) |
| `LocationContent` | [crates/phantom-domain/src/message.rs:108](crates/phantom-domain/src/message.rs#L108) |
| `CardContent` | [crates/phantom-domain/src/message.rs:117](crates/phantom-domain/src/message.rs#L117) |
| `MergedContent` | [crates/phantom-domain/src/message.rs:126](crates/phantom-domain/src/message.rs#L126) |
| `SystemContent` | [crates/phantom-domain/src/message.rs:133](crates/phantom-domain/src/message.rs#L133) |
| `SendMessageInput` | [crates/phantom-domain/src/message.rs:140](crates/phantom-domain/src/message.rs#L140) |
| `ChatMessage` | [crates/phantom-domain/src/message.rs:151](crates/phantom-domain/src/message.rs#L151) |
| `ReplySnapshot` | [crates/phantom-domain/src/message.rs:175](crates/phantom-domain/src/message.rs#L175) |
| `HistoryQuery` | [crates/phantom-domain/src/message.rs:184](crates/phantom-domain/src/message.rs#L184) |
| `MessagePage` | [crates/phantom-domain/src/message.rs:192](crates/phantom-domain/src/message.rs#L192) |
| `ForwardInput` | [crates/phantom-domain/src/message.rs:200](crates/phantom-domain/src/message.rs#L200) |
| `MessageAction` | [crates/phantom-domain/src/message.rs:208](crates/phantom-domain/src/message.rs#L208) |
| `RecallResult` | [crates/phantom-domain/src/message.rs:215](crates/phantom-domain/src/message.rs#L215) |
| `MessageCacheQuery` | [crates/phantom-domain/src/message.rs:221](crates/phantom-domain/src/message.rs#L221) |
| `UserProfile` | [crates/phantom-domain/src/relation.rs:6](crates/phantom-domain/src/relation.rs#L6) |
| `ProfileUpdate` | [crates/phantom-domain/src/relation.rs:17](crates/phantom-domain/src/relation.rs#L17) |
| `RelationUser` | [crates/phantom-domain/src/relation.rs:25](crates/phantom-domain/src/relation.rs#L25) |
| `Friend` | [crates/phantom-domain/src/relation.rs:33](crates/phantom-domain/src/relation.rs#L33) |
| `FriendPatch` | [crates/phantom-domain/src/relation.rs:45](crates/phantom-domain/src/relation.rs#L45) |
| `FriendRequestInput` | [crates/phantom-domain/src/relation.rs:53](crates/phantom-domain/src/relation.rs#L53) |
| `FriendRequestAction` | [crates/phantom-domain/src/relation.rs:59](crates/phantom-domain/src/relation.rs#L59) |
| `FriendRequestResult` | [crates/phantom-domain/src/relation.rs:67](crates/phantom-domain/src/relation.rs#L67) |
| `FriendRequest` | [crates/phantom-domain/src/relation.rs:75](crates/phantom-domain/src/relation.rs#L75) |
| `FriendRequestPage` | [crates/phantom-domain/src/relation.rs:90](crates/phantom-domain/src/relation.rs#L90) |
| `FriendGroup` | [crates/phantom-domain/src/relation.rs:97](crates/phantom-domain/src/relation.rs#L97) |
| `BlacklistEntry` | [crates/phantom-domain/src/relation.rs:105](crates/phantom-domain/src/relation.rs#L105) |
| `QrCard` | [crates/phantom-domain/src/relation.rs:114](crates/phantom-domain/src/relation.rs#L114) |
| `UserCard` | [crates/phantom-domain/src/relation.rs:123](crates/phantom-domain/src/relation.rs#L123) |
| `QrCardScanResult` | [crates/phantom-domain/src/relation.rs:134](crates/phantom-domain/src/relation.rs#L134) |
| `RelationSettings` | [crates/phantom-domain/src/relation.rs:140](crates/phantom-domain/src/relation.rs#L140) |
| `RelationSettingsPatch` | [crates/phantom-domain/src/relation.rs:147](crates/phantom-domain/src/relation.rs#L147) |
| `SearchQuery` | [crates/phantom-domain/src/search.rs:6](crates/phantom-domain/src/search.rs#L6) |
| `ConversationSearchQuery` | [crates/phantom-domain/src/search.rs:13](crates/phantom-domain/src/search.rs#L13) |
| `ContactHit` | [crates/phantom-domain/src/search.rs:21](crates/phantom-domain/src/search.rs#L21) |
| `GroupHit` | [crates/phantom-domain/src/search.rs:30](crates/phantom-domain/src/search.rs#L30) |
| `MessageHit` | [crates/phantom-domain/src/search.rs:39](crates/phantom-domain/src/search.rs#L39) |
| `SearchResult` | [crates/phantom-domain/src/search.rs:53](crates/phantom-domain/src/search.rs#L53) |
| `SearchSuggestions` | [crates/phantom-domain/src/search.rs:64](crates/phantom-domain/src/search.rs#L64) |
| `ConversationSearch` | [crates/phantom-domain/src/search.rs:72](crates/phantom-domain/src/search.rs#L72) |
| `ReportTarget` | [crates/phantom-domain/src/search.rs:79](crates/phantom-domain/src/search.rs#L79) |
| `ReportInput` | [crates/phantom-domain/src/search.rs:87](crates/phantom-domain/src/search.rs#L87) |
| `Report` | [crates/phantom-domain/src/search.rs:96](crates/phantom-domain/src/search.rs#L96) |
| `AppealInput` | [crates/phantom-domain/src/search.rs:112](crates/phantom-domain/src/search.rs#L112) |
| `Appeal` | [crates/phantom-domain/src/search.rs:119](crates/phantom-domain/src/search.rs#L119) |
| `AccountExport` | [crates/phantom-domain/src/search.rs:132](crates/phantom-domain/src/search.rs#L132) |
| `DeviceType` | [crates/phantom-domain/src/session.rs:5](crates/phantom-domain/src/session.rs#L5) |
| `ConnectionState` | [crates/phantom-domain/src/session.rs:12](crates/phantom-domain/src/session.rs#L12) |
| `LoginOptions` | [crates/phantom-domain/src/session.rs:24](crates/phantom-domain/src/session.rs#L24) |
| `SessionInfo` | [crates/phantom-domain/src/session.rs:35](crates/phantom-domain/src/session.rs#L35) |
| `LoginResult` | [crates/phantom-domain/src/session.rs:43](crates/phantom-domain/src/session.rs#L43) |
| `AppConfig` | [crates/phantom-domain/src/session.rs:53](crates/phantom-domain/src/session.rs#L53) |
| `BurnAfterReadConfig` | [crates/phantom-domain/src/session.rs:63](crates/phantom-domain/src/session.rs#L63) |
| `SyncStatus` | [crates/phantom-domain/src/session.rs:70](crates/phantom-domain/src/session.rs#L70) |
| `SyncPhase` | [crates/phantom-domain/src/session.rs:77](crates/phantom-domain/src/session.rs#L77) |
| `LogoutOptions` | [crates/phantom-domain/src/session.rs:87](crates/phantom-domain/src/session.rs#L87) |
| `TokenUpdate` | [crates/phantom-domain/src/session.rs:93](crates/phantom-domain/src/session.rs#L93) |
| `StorageNamespace` | [crates/phantom-domain/src/storage.rs:10](crates/phantom-domain/src/storage.rs#L10) |
| `StorageDurability` | [crates/phantom-domain/src/storage.rs:26](crates/phantom-domain/src/storage.rs#L26) |
| `StorageCapabilities` | [crates/phantom-domain/src/storage.rs:32](crates/phantom-domain/src/storage.rs#L32) |
| `InboxPayload` | [crates/phantom-domain/src/storage.rs:55](crates/phantom-domain/src/storage.rs#L55) |
| `InboxRecord` | [crates/phantom-domain/src/storage.rs:65](crates/phantom-domain/src/storage.rs#L65) |
| `OutboxRecord` | [crates/phantom-domain/src/storage.rs:72](crates/phantom-domain/src/storage.rs#L72) |
| `TombstoneReason` | [crates/phantom-domain/src/storage.rs:80](crates/phantom-domain/src/storage.rs#L80) |
| `MessageTombstone` | [crates/phantom-domain/src/storage.rs:87](crates/phantom-domain/src/storage.rs#L87) |
| `ConversationTombstone` | [crates/phantom-domain/src/storage.rs:96](crates/phantom-domain/src/storage.rs#L96) |
| `DisplayDeadline` | [crates/phantom-domain/src/storage.rs:102](crates/phantom-domain/src/storage.rs#L102) |
| `ProjectionKey` | [crates/phantom-domain/src/storage.rs:112](crates/phantom-domain/src/storage.rs#L112) |
| `DomainProjection` | [crates/phantom-domain/src/storage.rs:131](crates/phantom-domain/src/storage.rs#L131) |
| `StorageSnapshot` | [crates/phantom-domain/src/storage.rs:176](crates/phantom-domain/src/storage.rs#L176) |
| `StorageMutation` | [crates/phantom-domain/src/storage.rs:192](crates/phantom-domain/src/storage.rs#L192) |
| `StorageChangeSet` | [crates/phantom-domain/src/storage.rs:212](crates/phantom-domain/src/storage.rs#L212) |
| `StorageCommit` | [crates/phantom-domain/src/storage.rs:218](crates/phantom-domain/src/storage.rs#L218) |
| `ApiRequest` | [crates/phantom-ports/src/api.rs:5](crates/phantom-ports/src/api.rs#L5) |
| `ApiResponse` | [crates/phantom-ports/src/api.rs:310](crates/phantom-ports/src/api.rs#L310) |
| `PlatformApi` | [crates/phantom-ports/src/api.rs:466](crates/phantom-ports/src/api.rs#L466) |
| `HttpMethod` | [crates/phantom-ports/src/host.rs:6](crates/phantom-ports/src/host.rs#L6) |
| `HttpBody` | [crates/phantom-ports/src/host.rs:14](crates/phantom-ports/src/host.rs#L14) |
| `HttpIdentity` | [crates/phantom-ports/src/host.rs:20](crates/phantom-ports/src/host.rs#L20) |
| `HttpRequest` | [crates/phantom-ports/src/host.rs:37](crates/phantom-ports/src/host.rs#L37) |
| `HttpResponse` | [crates/phantom-ports/src/host.rs:47](crates/phantom-ports/src/host.rs#L47) |
| `Http` | [crates/phantom-ports/src/host.rs:52](crates/phantom-ports/src/host.rs#L52) |
| `WebSocket` | [crates/phantom-ports/src/host.rs:56](crates/phantom-ports/src/host.rs#L56) |
| `WebSocketConnector` | [crates/phantom-ports/src/host.rs:61](crates/phantom-ports/src/host.rs#L61) |
| `Clock` | [crates/phantom-ports/src/host.rs:65](crates/phantom-ports/src/host.rs#L65) |
| `Task` | [crates/phantom-ports/src/host.rs:70](crates/phantom-ports/src/host.rs#L70) |
| `Scheduler` | [crates/phantom-ports/src/host.rs:74](crates/phantom-ports/src/host.rs#L74) |
| `FileReader` | [crates/phantom-ports/src/host.rs:78](crates/phantom-ports/src/host.rs#L78) |
| `DownloadTarget` | [crates/phantom-ports/src/host.rs:82](crates/phantom-ports/src/host.rs#L82) |
| `Files` | [crates/phantom-ports/src/host.rs:87](crates/phantom-ports/src/host.rs#L87) |
| `DiagnosticLevel` | [crates/phantom-ports/src/host.rs:97](crates/phantom-ports/src/host.rs#L97) |
| `Diagnostic` | [crates/phantom-ports/src/host.rs:105](crates/phantom-ports/src/host.rs#L105) |
| `Diagnostics` | [crates/phantom-ports/src/host.rs:113](crates/phantom-ports/src/host.rs#L113) |
| `Ids` | [crates/phantom-ports/src/host.rs:117](crates/phantom-ports/src/host.rs#L117) |
| `Hashing` | [crates/phantom-ports/src/host.rs:121](crates/phantom-ports/src/host.rs#L121) |
| `MediaStream` | [crates/phantom-ports/src/host.rs:124](crates/phantom-ports/src/host.rs#L124) |
| `MediaDownload` | [crates/phantom-ports/src/host.rs:127](crates/phantom-ports/src/host.rs#L127) |
| `MediaAccess` | [crates/phantom-ports/src/host.rs:132](crates/phantom-ports/src/host.rs#L132) |
| `SdkFuture` | [crates/phantom-ports/src/lib.rs:5](crates/phantom-ports/src/lib.rs#L5) |
| `GatewayRequest` | [crates/phantom-ports/src/session.rs:5](crates/phantom-ports/src/session.rs#L5) |
| `MessageConfirmation` | [crates/phantom-ports/src/session.rs:28](crates/phantom-ports/src/session.rs#L28) |
| `SyncBatch` | [crates/phantom-ports/src/session.rs:34](crates/phantom-ports/src/session.rs#L34) |
| `GatewayResponse` | [crates/phantom-ports/src/session.rs:41](crates/phantom-ports/src/session.rs#L41) |
| `GatewayPush` | [crates/phantom-ports/src/session.rs:50](crates/phantom-ports/src/session.rs#L50) |
| `Gateway` | [crates/phantom-ports/src/session.rs:63](crates/phantom-ports/src/session.rs#L63) |
| `GatewayFactory` | [crates/phantom-ports/src/session.rs:69](crates/phantom-ports/src/session.rs#L69) |
| `StoreFactory` | [crates/phantom-ports/src/storage.rs:7](crates/phantom-ports/src/storage.rs#L7) |
| `Store` | [crates/phantom-ports/src/storage.rs:17](crates/phantom-ports/src/storage.rs#L17) |
| `PhantomClient` | [crates/phantom-sdk/src/client.rs:49](crates/phantom-sdk/src/client.rs#L49) |
| `ClientOptions` | [crates/phantom-sdk/src/options.rs:4](crates/phantom-sdk/src/options.rs#L4) |
| `StorageOptions` | [crates/phantom-sdk/src/options.rs:32](crates/phantom-sdk/src/options.rs#L32) |
| `StorageHandle` | [crates/phantom-store/src/callback.rs:9](crates/phantom-store/src/callback.rs#L9) |
| `StorageCallbackContext` | [crates/phantom-store/src/callback.rs:11](crates/phantom-store/src/callback.rs#L11) |
| `StorageCallback` | [crates/phantom-store/src/callback.rs:15](crates/phantom-store/src/callback.rs#L15) |
| `StorageInputCallback` | [crates/phantom-store/src/callback.rs:17](crates/phantom-store/src/callback.rs#L17) |
| `StorageOpenCallback` | [crates/phantom-store/src/callback.rs:19](crates/phantom-store/src/callback.rs#L19) |
| `StorageCallbacks` | [crates/phantom-store/src/callback.rs:24](crates/phantom-store/src/callback.rs#L24) |
| `CallbackStoreFactory` | [crates/phantom-store/src/callback.rs:49](crates/phantom-store/src/callback.rs#L49) |
| `MemoryStoreFactory` | [crates/phantom-store/src/memory.rs:18](crates/phantom-store/src/memory.rs#L18) |
| `SqliteStoreFactory` | [crates/phantom-store/src/sqlite.rs:43](crates/phantom-store/src/sqlite.rs#L43) |

事件包括全部 12 类 SyncPayload、未知分支原始字节、生命周期、发送/移除、进度、Presence/Typing 与通知点击；二进制 fixture 位于 protocol/tests/wire_contracts.rs，投影和原子性验证位于 sdk/tests/sync_engine.rs。存储契约记录涵盖命名空间、持久性、revision、Inbox/Outbox、投影、消息与会话 tombstone、草稿和展示截止时间。

检查命令：`python3 tools/generate_coverage.py`、`python3 tools/generate_cli.py`，然后执行 workspace 检查和 CLI schema/dry-run。源码没有范围内的 Unsupported 占位；ErrorKind::Unsupported 作为公开错误分类保留。
