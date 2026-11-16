use crate::*;
impl PhantomClient {
    pub async fn forward(
        &self,
        input: ForwardInput,
        call: CallOptions,
    ) -> SdkResult<MessageAction> {
        self.read()?;
        let request = ApiRequest::Forward { input };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::Forward(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn delete_local(&self, msg_id: String, call: CallOptions) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::DeleteLocal { msg_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::DeleteLocal => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn clear_history(&self, conv_id: String, call: CallOptions) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::ClearHistory { conv_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::ClearHistory => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn history(&self, query: HistoryQuery, call: CallOptions) -> SdkResult<MessagePage> {
        self.read()?;
        let request = ApiRequest::History { query };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::History(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn init_upload(
        &self,
        input: UploadMetadata,
        call: CallOptions,
    ) -> SdkResult<UploadSession> {
        self.read()?;
        let request = ApiRequest::InitUpload { input };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::InitUpload(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn upload_part(
        &self,
        input: UploadPartInput,
        call: CallOptions,
    ) -> SdkResult<UploadPartResult> {
        self.read()?;
        let request = ApiRequest::UploadPart { input };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::UploadPart(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn complete_upload(
        &self,
        upload_id: String,
        call: CallOptions,
    ) -> SdkResult<UploadedMedia> {
        self.read()?;
        let request = ApiRequest::CompleteUpload { upload_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::CompleteUpload(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn get_media(&self, media_id: String, call: CallOptions) -> SdkResult<MediaInfo> {
        self.read()?;
        let request = ApiRequest::GetMedia { media_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::GetMedia(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn list_conversations(
        &self,
        query: ConversationQuery,
        call: CallOptions,
    ) -> SdkResult<ConversationPage> {
        self.read()?;
        let request = ApiRequest::ListConversations { query };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::ListConversations(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn get_conversation(
        &self,
        conv_id: String,
        call: CallOptions,
    ) -> SdkResult<Conversation> {
        self.read()?;
        let request = ApiRequest::GetConversation { conv_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::GetConversation(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn update_conversation(
        &self,
        conv_id: String,
        patch: ConversationPatch,
        call: CallOptions,
    ) -> SdkResult<Conversation> {
        self.read()?;
        let request = ApiRequest::UpdateConversation { conv_id, patch };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::UpdateConversation(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn delete_conversation(&self, conv_id: String, call: CallOptions) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::DeleteConversation { conv_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::DeleteConversation => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn mark_read(
        &self,
        conv_id: String,
        read_conv_seq: Seq,
        call: CallOptions,
    ) -> SdkResult<ReadPosition> {
        self.read()?;
        let request = ApiRequest::MarkRead {
            conv_id,
            read_conv_seq,
        };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::MarkRead(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn get_read_position(
        &self,
        conv_id: String,
        call: CallOptions,
    ) -> SdkResult<ReadPosition> {
        self.read()?;
        let request = ApiRequest::GetReadPosition { conv_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::GetReadPosition(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn save_draft(
        &self,
        input: DraftInput,
        call: CallOptions,
    ) -> SdkResult<ConversationDraft> {
        self.read()?;
        let request = ApiRequest::SaveDraft { input };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::SaveDraft(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn clear_draft(
        &self,
        conv_id: String,
        edited_at: Option<TimestampMs>,
        call: CallOptions,
    ) -> SdkResult<ConversationDraft> {
        self.read()?;
        let request = ApiRequest::ClearDraft { conv_id, edited_at };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::ClearDraft(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn handle_message_request(
        &self,
        conv_id: String,
        accept: bool,
        call: CallOptions,
    ) -> SdkResult<Conversation> {
        self.read()?;
        let request = ApiRequest::HandleMessageRequest { conv_id, accept };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::HandleMessageRequest(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn get_user_profile(
        &self,
        user_id: Option<String>,
        call: CallOptions,
    ) -> SdkResult<UserProfile> {
        self.read()?;
        let request = ApiRequest::GetUserProfile { user_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::GetUserProfile(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn update_profile(
        &self,
        input: ProfileUpdate,
        call: CallOptions,
    ) -> SdkResult<UserProfile> {
        self.read()?;
        let request = ApiRequest::UpdateProfile { input };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::UpdateProfile(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn list_friends(&self, call: CallOptions) -> SdkResult<Vec<Friend>> {
        self.read()?;
        let request = ApiRequest::ListFriends;
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::ListFriends(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn create_friend_request(
        &self,
        input: FriendRequestInput,
        call: CallOptions,
    ) -> SdkResult<FriendRequestResult> {
        self.read()?;
        let request = ApiRequest::CreateFriendRequest { input };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::CreateFriendRequest(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn list_friend_requests(
        &self,
        include_handled: bool,
        call: CallOptions,
    ) -> SdkResult<FriendRequestPage> {
        self.read()?;
        let request = ApiRequest::ListFriendRequests { include_handled };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::ListFriendRequests(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn handle_friend_request(
        &self,
        request_id: String,
        action: FriendRequestAction,
        call: CallOptions,
    ) -> SdkResult<FriendRequest> {
        self.read()?;
        let request = ApiRequest::HandleFriendRequest { request_id, action };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::HandleFriendRequest(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn update_friend(
        &self,
        friend_id: String,
        patch: FriendPatch,
        call: CallOptions,
    ) -> SdkResult<Friend> {
        self.read()?;
        let request = ApiRequest::UpdateFriend { friend_id, patch };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::UpdateFriend(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn delete_friend(&self, friend_id: String, call: CallOptions) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::DeleteFriend { friend_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::DeleteFriend => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn list_blacklist(&self, call: CallOptions) -> SdkResult<Vec<BlacklistEntry>> {
        self.read()?;
        let request = ApiRequest::ListBlacklist;
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::ListBlacklist(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn block_user(&self, user_id: String, call: CallOptions) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::BlockUser { user_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::BlockUser => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn unblock_user(&self, user_id: String, call: CallOptions) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::UnblockUser { user_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::UnblockUser => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn list_friend_groups(&self, call: CallOptions) -> SdkResult<Vec<FriendGroup>> {
        self.read()?;
        let request = ApiRequest::ListFriendGroups;
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::ListFriendGroups(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn create_friend_group(
        &self,
        name: String,
        call: CallOptions,
    ) -> SdkResult<FriendGroup> {
        self.read()?;
        let request = ApiRequest::CreateFriendGroup { name };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::CreateFriendGroup(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn rename_friend_group(
        &self,
        group_id: String,
        name: String,
        call: CallOptions,
    ) -> SdkResult<FriendGroup> {
        self.read()?;
        let request = ApiRequest::RenameFriendGroup { group_id, name };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::RenameFriendGroup(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn delete_friend_group(&self, group_id: String, call: CallOptions) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::DeleteFriendGroup { group_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::DeleteFriendGroup => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn get_qr_card(&self, call: CallOptions) -> SdkResult<QrCard> {
        self.read()?;
        let request = ApiRequest::GetQrCard;
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::GetQrCard(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn refresh_qr_card(&self, call: CallOptions) -> SdkResult<QrCard> {
        self.read()?;
        let request = ApiRequest::RefreshQrCard;
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::RefreshQrCard(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn scan_qr_card(
        &self,
        token: String,
        call: CallOptions,
    ) -> SdkResult<QrCardScanResult> {
        self.read()?;
        let request = ApiRequest::ScanQrCard { token };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::ScanQrCard(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn get_relation_settings(&self, call: CallOptions) -> SdkResult<RelationSettings> {
        self.read()?;
        let request = ApiRequest::GetRelationSettings;
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::GetRelationSettings(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn update_relation_settings(
        &self,
        patch: RelationSettingsPatch,
        call: CallOptions,
    ) -> SdkResult<RelationSettings> {
        self.read()?;
        let request = ApiRequest::UpdateRelationSettings { patch };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::UpdateRelationSettings(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn list_groups(&self, call: CallOptions) -> SdkResult<Vec<Group>> {
        self.read()?;
        let request = ApiRequest::ListGroups;
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::ListGroups(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn create_group(
        &self,
        input: CreateGroupInput,
        call: CallOptions,
    ) -> SdkResult<Group> {
        self.read()?;
        let request = ApiRequest::CreateGroup { input };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::CreateGroup(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn get_group(&self, group_id: String, call: CallOptions) -> SdkResult<GroupDetail> {
        self.read()?;
        let request = ApiRequest::GetGroup { group_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::GetGroup(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn update_group(
        &self,
        group_id: String,
        patch: GroupPatch,
        call: CallOptions,
    ) -> SdkResult<GroupDetail> {
        self.read()?;
        let request = ApiRequest::UpdateGroup { group_id, patch };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::UpdateGroup(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn list_group_members(
        &self,
        group_id: String,
        call: CallOptions,
    ) -> SdkResult<Vec<GroupMember>> {
        self.read()?;
        let request = ApiRequest::ListGroupMembers { group_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::ListGroupMembers(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn invite_group_members(
        &self,
        group_id: String,
        user_ids: Vec<String>,
        call: CallOptions,
    ) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::InviteGroupMembers { group_id, user_ids };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::InviteGroupMembers => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn remove_group_member(
        &self,
        group_id: String,
        user_id: String,
        call: CallOptions,
    ) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::RemoveGroupMember { group_id, user_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::RemoveGroupMember => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn update_group_role(
        &self,
        group_id: String,
        user_id: String,
        role: AssignableGroupRole,
        call: CallOptions,
    ) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::UpdateGroupRole {
            group_id,
            user_id,
            role,
        };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::UpdateGroupRole => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn transfer_group_owner(
        &self,
        input: TransferGroupInput,
        call: CallOptions,
    ) -> SdkResult<Group> {
        self.read()?;
        let request = ApiRequest::TransferGroupOwner { input };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::TransferGroupOwner(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn leave_group(&self, group_id: String, call: CallOptions) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::LeaveGroup { group_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::LeaveGroup => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn dissolve_group(&self, group_id: String, call: CallOptions) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::DissolveGroup { group_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::DissolveGroup => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn mute_group_member(&self, input: MuteInput, call: CallOptions) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::MuteGroupMember { input };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::MuteGroupMember => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn unmute_group_member(
        &self,
        group_id: String,
        target: MuteTarget,
        call: CallOptions,
    ) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::UnmuteGroupMember { group_id, target };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::UnmuteGroupMember => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn get_announcement(
        &self,
        group_id: String,
        call: CallOptions,
    ) -> SdkResult<Announcement> {
        self.read()?;
        let request = ApiRequest::GetAnnouncement { group_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::GetAnnouncement(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn update_announcement(
        &self,
        group_id: String,
        content: String,
        call: CallOptions,
    ) -> SdkResult<Announcement> {
        self.read()?;
        let request = ApiRequest::UpdateAnnouncement { group_id, content };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::UpdateAnnouncement(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn update_group_nickname(
        &self,
        group_id: String,
        user_id: String,
        nickname: String,
        call: CallOptions,
    ) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::UpdateGroupNickname {
            group_id,
            user_id,
            nickname,
        };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::UpdateGroupNickname => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn create_group_invite(
        &self,
        group_id: String,
        expires_in_seconds: Option<u32>,
        call: CallOptions,
    ) -> SdkResult<GroupInvite> {
        self.read()?;
        let request = ApiRequest::CreateGroupInvite {
            group_id,
            expires_in_seconds,
        };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::CreateGroupInvite(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn join_group_by_invite(
        &self,
        token: String,
        call: CallOptions,
    ) -> SdkResult<JoinGroupResult> {
        self.read()?;
        let request = ApiRequest::JoinGroupByInvite { token };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::JoinGroupByInvite(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn handle_group_join_request(
        &self,
        group_id: String,
        request_id: String,
        accept: bool,
        call: CallOptions,
    ) -> SdkResult<JoinGroupResult> {
        self.read()?;
        let request = ApiRequest::HandleGroupJoinRequest {
            group_id,
            request_id,
            accept,
        };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::HandleGroupJoinRequest(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn list_devices(&self, call: CallOptions) -> SdkResult<Vec<DeviceInfo>> {
        self.read()?;
        let request = ApiRequest::ListDevices;
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::ListDevices(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn remote_logout(&self, device_id: String, call: CallOptions) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::RemoteLogout { device_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::RemoteLogout => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn register_push_token(
        &self,
        input: PushTokenInput,
        call: CallOptions,
    ) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::RegisterPushToken { input };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::RegisterPushToken => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn unregister_push_token(&self, token: String, call: CallOptions) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::UnregisterPushToken { token };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::UnregisterPushToken => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn get_push_settings(&self, call: CallOptions) -> SdkResult<PushSettings> {
        self.read()?;
        let request = ApiRequest::GetPushSettings;
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::GetPushSettings(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn update_push_settings(
        &self,
        input: PushSettingsInput,
        call: CallOptions,
    ) -> SdkResult<PushSettings> {
        self.read()?;
        let request = ApiRequest::UpdatePushSettings { input };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::UpdatePushSettings(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn get_privacy_links(&self, call: CallOptions) -> SdkResult<PushSettings> {
        self.read()?;
        let request = ApiRequest::GetPrivacyLinks;
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::GetPrivacyLinks(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn update_privacy_links(
        &self,
        input: PrivacyLinksInput,
        call: CallOptions,
    ) -> SdkResult<PushSettings> {
        self.read()?;
        let request = ApiRequest::UpdatePrivacyLinks { input };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::UpdatePrivacyLinks(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn search(&self, query: SearchQuery, call: CallOptions) -> SdkResult<SearchResult> {
        self.read()?;
        let request = ApiRequest::Search { query };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::Search(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn search_suggestions(
        &self,
        query: SearchQuery,
        call: CallOptions,
    ) -> SdkResult<SearchSuggestions> {
        self.read()?;
        let request = ApiRequest::SearchSuggestions { query };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::SearchSuggestions(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn search_conversation(
        &self,
        query: ConversationSearchQuery,
        call: CallOptions,
    ) -> SdkResult<ConversationSearch> {
        self.read()?;
        let request = ApiRequest::SearchConversation { query };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::SearchConversation(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn report(&self, input: ReportInput, call: CallOptions) -> SdkResult<Report> {
        self.read()?;
        let request = ApiRequest::Report { input };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::Report(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn list_reports(&self, limit: u32, call: CallOptions) -> SdkResult<Vec<Report>> {
        self.read()?;
        let request = ApiRequest::ListReports { limit };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::ListReports(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn submit_appeal(&self, input: AppealInput, call: CallOptions) -> SdkResult<Appeal> {
        self.read()?;
        let request = ApiRequest::SubmitAppeal { input };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::SubmitAppeal(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn list_appeals(
        &self,
        appeal_id: Option<String>,
        call: CallOptions,
    ) -> SdkResult<Vec<Appeal>> {
        self.read()?;
        let request = ApiRequest::ListAppeals { appeal_id };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::ListAppeals(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn export_account(&self, call: CallOptions) -> SdkResult<Vec<AccountExport>> {
        self.read()?;
        let request = ApiRequest::ExportAccount;
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::ExportAccount(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn get_account_exports(&self, call: CallOptions) -> SdkResult<Vec<AccountExport>> {
        self.read()?;
        let request = ApiRequest::GetAccountExports;
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::GetAccountExports(value) => Ok(*value),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
    pub async fn set_minor_mode(&self, enabled: bool, call: CallOptions) -> SdkResult<()> {
        self.read()?;
        let request = ApiRequest::SetMinorMode { enabled };
        request.validate()?;
        match self.invoke_api(request, call).await? {
            ApiResponse::SetMinorMode => Ok(()),
            _ => Err(SdkError::new(
                ErrorKind::Protocol,
                "领域返回类型与调用不匹配",
            )),
        }
    }
}
