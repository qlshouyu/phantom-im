use crate::ApiRequest;
use phantom_domain::*;
fn nonempty(values: &[String], name: &str) -> SdkResult<()> {
    if values.is_empty() {
        return Err(SdkError::new(
            ErrorKind::InvalidArgument,
            format!("{name} 不能为空"),
        ));
    }
    for value in values {
        validate_id(value, name)?;
    }
    Ok(())
}
fn length(value: &str, max: usize) -> SdkResult<()> {
    if value.chars().count() > max {
        Err(SdkError::new(ErrorKind::InvalidArgument, "输入字符数超限"))
    } else {
        Ok(())
    }
}
impl ApiRequest {
    pub fn validate(&self) -> SdkResult<()> {
        use ApiRequest::*;
        match self {
            Forward { input } => {
                nonempty(&input.msg_ids, "msg_ids")?;
                nonempty(&input.target_conv_ids, "target_conv_ids")?;
                if input.merged && input.msg_ids.len() > 100 {
                    return Err(SdkError::new(
                        ErrorKind::InvalidArgument,
                        "合并转发最多 100 条",
                    ));
                }
            }
            DeleteLocal { msg_id } => {
                validate_id(msg_id, "msg_id")?;
            }
            ClearHistory { conv_id } => {
                validate_id(conv_id, "conv_id")?;
            }
            History { query } => {
                query.validate()?;
            }
            InitUpload { input } => {
                input.validate()?;
            }
            UploadPart { input } => {
                validate_id(&input.upload_id, "upload_id")?;
                if input.part_number == 0 || input.bytes.is_empty() {
                    return Err(SdkError::new(
                        ErrorKind::InvalidArgument,
                        "分片编号或字节数无效",
                    ));
                }
            }
            CompleteUpload { upload_id } => {
                validate_id(upload_id, "upload_id")?;
            }
            GetMedia { media_id } => {
                validate_id(media_id, "media_id")?;
            }
            ListConversations { query } => {
                validate_page(query.limit, 200, None)?;
                if let Some(cursor) = &query.cursor {
                    validate_id(cursor, "cursor")?;
                }
            }
            GetConversation { conv_id } => {
                validate_id(conv_id, "conv_id")?;
            }
            UpdateConversation { conv_id, .. } => {
                validate_id(conv_id, "conv_id")?;
            }
            DeleteConversation { conv_id } => {
                validate_id(conv_id, "conv_id")?;
            }
            MarkRead {
                conv_id,
                read_conv_seq,
            } => {
                validate_id(conv_id, "conv_id")?;
                validate_seq(*read_conv_seq, "read_conv_seq")?;
            }
            GetReadPosition { conv_id } => {
                validate_id(conv_id, "conv_id")?;
            }
            SaveDraft { input } => {
                validate_id(&input.conv_id, "conv_id")?;
                length(&input.draft, 5000)?;
                if let Some(at) = input.edited_at {
                    validate_seq(at, "edited_at")?;
                }
            }
            ClearDraft { conv_id, edited_at } => {
                validate_id(conv_id, "conv_id")?;
                if let Some(at) = edited_at {
                    validate_seq(*at, "edited_at")?;
                }
            }
            HandleMessageRequest { conv_id, .. } => {
                validate_id(conv_id, "conv_id")?;
            }
            GetUserProfile { user_id } => {
                if let Some(id) = user_id {
                    validate_id(id, "user_id")?;
                }
            }
            UpdateProfile { .. } => {}
            ListFriends => {}
            CreateFriendRequest { input } => {
                validate_id(&input.to_user_id, "to_user_id")?;
                length(&input.verification_message, 30)?;
            }
            ListFriendRequests { .. } => {}
            HandleFriendRequest { request_id, .. } => {
                validate_id(request_id, "request_id")?;
            }
            UpdateFriend { friend_id, .. } => {
                validate_id(friend_id, "friend_id")?;
            }
            DeleteFriend { friend_id } => {
                validate_id(friend_id, "friend_id")?;
            }
            ListBlacklist => {}
            BlockUser { user_id } => {
                validate_id(user_id, "user_id")?;
            }
            UnblockUser { user_id } => {
                validate_id(user_id, "user_id")?;
            }
            ListFriendGroups => {}
            CreateFriendGroup { name } => {
                validate_id(name, "name")?;
            }
            RenameFriendGroup { group_id, name } => {
                validate_id(group_id, "group_id")?;
                validate_id(name, "name")?;
            }
            DeleteFriendGroup { group_id } => {
                validate_id(group_id, "group_id")?;
            }
            GetQrCard => {}
            RefreshQrCard => {}
            ScanQrCard { token } => {
                validate_id(token, "token")?;
            }
            GetRelationSettings => {}
            UpdateRelationSettings { .. } => {}
            ListGroups => {}
            CreateGroup { input } => {
                validate_id(&input.name, "name")?;
                for id in &input.member_ids {
                    validate_id(id, "member_ids")?;
                }
            }
            GetGroup { group_id } => {
                validate_id(group_id, "group_id")?;
            }
            UpdateGroup { group_id, patch } => {
                validate_id(group_id, "group_id")?;
                if let Some(name) = &patch.name {
                    validate_id(name, "name")?;
                }
            }
            ListGroupMembers { group_id } => {
                validate_id(group_id, "group_id")?;
            }
            InviteGroupMembers { group_id, user_ids } => {
                validate_id(group_id, "group_id")?;
                nonempty(user_ids, "user_ids")?;
            }
            RemoveGroupMember { group_id, user_id } => {
                validate_id(group_id, "group_id")?;
                validate_id(user_id, "user_id")?;
            }
            UpdateGroupRole {
                group_id, user_id, ..
            } => {
                validate_id(group_id, "group_id")?;
                validate_id(user_id, "user_id")?;
            }
            TransferGroupOwner { input } => {
                validate_id(&input.group_id, "group_id")?;
                validate_id(&input.user_id, "user_id")?;
            }
            LeaveGroup { group_id } => {
                validate_id(group_id, "group_id")?;
            }
            DissolveGroup { group_id } => {
                validate_id(group_id, "group_id")?;
            }
            MuteGroupMember { input } => {
                validate_id(&input.group_id, "group_id")?;
                if let MuteTarget::User(id) = &input.target {
                    validate_id(id, "user_id")?;
                }
            }
            UnmuteGroupMember { group_id, target } => {
                validate_id(group_id, "group_id")?;
                if let MuteTarget::User(id) = target {
                    validate_id(id, "user_id")?;
                }
            }
            GetAnnouncement { group_id } => {
                validate_id(group_id, "group_id")?;
            }
            UpdateAnnouncement { group_id, .. } => {
                validate_id(group_id, "group_id")?;
            }
            UpdateGroupNickname {
                group_id, user_id, ..
            } => {
                validate_id(group_id, "group_id")?;
                validate_id(user_id, "user_id")?;
            }
            CreateGroupInvite { group_id, .. } => {
                validate_id(group_id, "group_id")?;
            }
            JoinGroupByInvite { token } => {
                validate_id(token, "token")?;
            }
            HandleGroupJoinRequest {
                group_id,
                request_id,
                ..
            } => {
                validate_id(group_id, "group_id")?;
                validate_id(request_id, "request_id")?;
            }
            ListDevices => {}
            RemoteLogout { device_id } => {
                validate_id(device_id, "device_id")?;
            }
            RegisterPushToken { input } => {
                validate_id(&input.token, "token")?;
                validate_id(&input.provider, "provider")?;
            }
            UnregisterPushToken { token } => {
                validate_id(token, "token")?;
            }
            GetPushSettings => {}
            UpdatePushSettings { .. } => {}
            GetPrivacyLinks => {}
            UpdatePrivacyLinks { input } => {
                for url in [&input.privacy_policy_url, &input.terms_url]
                    .into_iter()
                    .flatten()
                {
                    length(url, 1024)?;
                }
            }
            Search { query } => {
                validate_id(&query.query, "query")?;
                validate_page(query.limit, 50, None)?;
            }
            SearchSuggestions { query } => {
                validate_id(&query.query, "query")?;
                validate_page(query.limit, 20, None)?;
            }
            SearchConversation { query } => {
                validate_id(&query.conv_id, "conv_id")?;
                validate_id(&query.query, "query")?;
                validate_page(query.limit, 50, None)?;
            }
            Report { input } => {
                validate_id(&input.target_id, "target_id")?;
                validate_id(&input.reason, "reason")?;
            }
            ListReports { limit } => {
                validate_page(*limit, 100, None)?;
            }
            SubmitAppeal { input } => {
                validate_id(&input.sanction_id, "sanction_id")?;
                validate_id(&input.content, "content")?;
            }
            ListAppeals { appeal_id } => {
                if let Some(id) = appeal_id {
                    validate_id(id, "appeal_id")?;
                }
            }
            ExportAccount => {}
            GetAccountExports => {}
            SetMinorMode { .. } => {}
        }
        Ok(())
    }
}
