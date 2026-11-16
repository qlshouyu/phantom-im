use crate::*;
pub(crate) fn projection(state: &mut StorageSnapshot, value: DomainProjection) {
    state.invalidated.retain(|k| *k != value.key());
    state.projections.retain(|v| v.key() != value.key());
    state.projections.push(value);
}
fn invalidate(state: &mut StorageSnapshot, key: ProjectionKey) {
    if !state.invalidated.contains(&key) {
        state.invalidated.push(key);
    }
}
pub(crate) fn conversation(state: &mut StorageSnapshot, mut value: Conversation) -> Conversation {
    if let Some(old) = state
        .conversations
        .iter()
        .find(|c| c.conv_id == value.conv_id)
    {
        if value.pinned_at < old.pinned_at {
            value.pinned = old.pinned;
            value.pinned_at = old.pinned_at;
        }
        value.read_conv_seq = value.read_conv_seq.max(old.read_conv_seq);
        if value.read_conv_seq >= value.max_conv_seq {
            value.unread_count = 0;
        }
        if old.draft_edited_at > value.draft_edited_at {
            value.draft = old.draft.clone();
            value.draft_edited_at = old.draft_edited_at;
        }
    }
    if let Some(draft) = state
        .drafts
        .iter()
        .find(|d| d.conv_id == value.conv_id && d.edited_at > value.draft_edited_at)
    {
        value.draft = draft.draft.clone();
        value.draft_edited_at = draft.edited_at;
    }
    state.conversations.retain(|c| c.conv_id != value.conv_id);
    state.conversations.push(value.clone());
    value
}
fn draft(state: &mut StorageSnapshot, value: ConversationDraft) -> ConversationDraft {
    if let Some(old) = state
        .drafts
        .iter()
        .find(|d| d.conv_id == value.conv_id && d.edited_at > value.edited_at)
    {
        return old.clone();
    }
    if let Some(conv) = state
        .conversations
        .iter_mut()
        .find(|c| c.conv_id == value.conv_id)
        && value.edited_at >= conv.draft_edited_at
    {
        conv.draft = value.draft.clone();
        conv.draft_edited_at = value.edited_at;
    }
    state.drafts.retain(|d| d.conv_id != value.conv_id);
    state.drafts.push(value.clone());
    value
}
impl Kernel {
    pub async fn prepare_api(
        &mut self,
        mut request: ApiRequest,
        context: &CallContext,
    ) -> SdkResult<Detached<ApiResponse>> {
        self.authenticated()?;
        request.validate()?;
        context.check()?;
        match &mut request {
            ApiRequest::SaveDraft { input } => {
                if input.edited_at.is_none() {
                    input.edited_at = Some(self.calibrated_now());
                }
            }
            ApiRequest::ClearDraft { edited_at, .. } if edited_at.is_none() => {
                *edited_at = Some(self.calibrated_now());
            }
            _ => {}
        }
        let mut previous = None;
        if let ApiRequest::UpdateConversation { conv_id, patch } = &request
            && let Some(old) = self
                .snapshot()?
                .conversations
                .iter()
                .find(|c| c.conv_id == *conv_id)
                .cloned()
        {
            let mut next = self.snapshot()?.clone();
            let mut changed = old.clone();
            if let Some(pinned) = patch.pinned {
                changed.pinned = pinned;
                changed.pinned_at = self.host.clock.now_ms();
            }
            if let Some(muted) = patch.muted {
                changed.muted = muted;
            }
            conversation(&mut next, changed.clone());
            self.commit(next, false).await?;
            let parent = self
                .conversation_stamps
                .get(conv_id)
                .copied()
                .unwrap_or_default();
            self.touch_conversation(conv_id, patch.pinned.is_some(), patch.muted.is_some());
            let stamp = self
                .conversation_stamps
                .get(conv_id)
                .copied()
                .unwrap_or_default();
            self.pending_conversations.insert(
                self.change_stamp,
                crate::kernel::PendingConversation {
                    old,
                    optimistic: changed,
                    parent,
                    stamp,
                    pinned: patch.pinned.is_some(),
                    muted: patch.muted.is_some(),
                },
            );
            previous = Some(self.change_stamp);
        }
        // 未缓存的清空操作先取得服务端会话上限；后续同步还会校验该会话消息的可见性。
        if let ApiRequest::ClearHistory { conv_id } = &request
            && !self
                .snapshot()?
                .conversations
                .iter()
                .any(|c| c.conv_id == *conv_id)
        {
            if let ApiResponse::GetConversation(value) = self
                .api_request(
                    ApiRequest::GetConversation {
                        conv_id: conv_id.clone(),
                    },
                    context,
                )
                .await?
            {
                let mut next = self.snapshot()?.clone();
                conversation(&mut next, *value);
                self.commit(next, false).await?;
            } else {
                return Err(SdkError::new(ErrorKind::Protocol, "清空前未能读取会话状态"));
            }
        }
        let identity = self.identity()?;
        let request_id = self.host.ids.generate()?;
        let api = self.host.api.clone();
        let work_request = request.clone();
        let context = context.clone();
        let epoch = self.epoch;
        let generation = self.generation;
        let stamps = self.conversation_stamps.clone();
        Ok(Detached::new(
            Box::pin(async move {
                context.check()?;
                context.mark_submitted();
                let operation = work_request.operation();
                let response = context
                    .run(api.execute(work_request, identity, request_id))
                    .await?;
                if response.operation() != operation {
                    return Err(SdkError::new(
                        ErrorKind::Protocol,
                        "HTTP 返回类型与领域请求不匹配",
                    ));
                }
                Ok(response)
            }),
            move |kernel, response| {
                Box::pin(async move {
                    if kernel.epoch != epoch || kernel.generation != generation {
                        let mut error =
                            SdkError::new(ErrorKind::Cancelled, "请求所属会话或连接代次已结束");
                        error.outcome = OperationOutcome::Unknown;
                        return Err(error);
                    }
                    kernel.live()?;
                    match response {
                        Ok(mut response) => {
                            if let Some(id) = previous {
                                kernel.settle_conversation(id, Some(&response)).await?;
                            }
                            preserve_newer(
                                &kernel.current.storage,
                                &kernel.conversation_stamps,
                                &stamps,
                                &mut response,
                            );
                            kernel.observe_api(&request, &mut response).await?;
                            Ok(response)
                        }
                        Err(error) => {
                            if error.kind == ErrorKind::Authentication {
                                kernel.connection_lost(error.clone()).await;
                            }
                            if let Some(id) = previous {
                                kernel.settle_conversation(id, None).await?;
                            }
                            Err(error)
                        }
                    }
                })
            },
        ))
    }
    async fn settle_conversation(
        &mut self,
        id: u64,
        response: Option<&ApiResponse>,
    ) -> SdkResult<()> {
        let Some(pending) = self.pending_conversations.remove(&id) else {
            return Ok(());
        };
        let accepted = match response {
            Some(ApiResponse::UpdateConversation(value)) => Some(value.as_ref()),
            _ => None,
        };
        // 后续乐观请求引用本次投影作为回滚基线。前项结束时修正依赖，避免两项都失败后保留已失败的值。
        for newer in self
            .pending_conversations
            .values_mut()
            .filter(|v| v.old.conv_id == pending.old.conv_id)
        {
            if pending.pinned && newer.parent.0 == pending.stamp.0 {
                let baseline = accepted.unwrap_or(&pending.old);
                newer.old.pinned = baseline.pinned;
                newer.old.pinned_at = baseline.pinned_at;
                if accepted.is_none() {
                    newer.parent.0 = pending.parent.0;
                }
            }
            if pending.muted && newer.parent.1 == pending.stamp.1 {
                newer.old.muted = accepted.unwrap_or(&pending.old).muted;
                if accepted.is_none() {
                    newer.parent.1 = pending.parent.1;
                }
            }
        }
        if response.is_none() {
            let mut next = self.snapshot()?.clone();
            let stamp = self
                .conversation_stamps
                .entry(pending.old.conv_id.clone())
                .or_default();
            if let Some(current) = next
                .conversations
                .iter_mut()
                .find(|c| c.conv_id == pending.old.conv_id)
            {
                if pending.pinned
                    && stamp.0 == pending.stamp.0
                    && current.pinned == pending.optimistic.pinned
                {
                    current.pinned = pending.old.pinned;
                    current.pinned_at = pending.old.pinned_at;
                    stamp.0 = pending.parent.0;
                }
                if pending.muted
                    && stamp.1 == pending.stamp.1
                    && current.muted == pending.optimistic.muted
                {
                    current.muted = pending.old.muted;
                    stamp.1 = pending.parent.1;
                }
            }
            self.commit(next, false).await?;
        }
        Ok(())
    }
    pub async fn api(
        &mut self,
        request: ApiRequest,
        context: &CallContext,
    ) -> SdkResult<ApiResponse> {
        let plan = self.prepare_api(request, context).await?;
        plan.resolve(self).await
    }
    pub async fn observe_api(
        &mut self,
        request: &ApiRequest,
        response: &mut ApiResponse,
    ) -> SdkResult<()> {
        let mut next = self.snapshot()?.clone();
        let mut removed = Vec::new();
        match response {
            ApiResponse::History(page) => {
                let mut visible = Vec::new();
                for message in &page.items {
                    if (merge_message(&mut next, message.clone())?.is_some()
                        || next
                            .messages
                            .iter()
                            .any(|m| m.client_msg_id == message.client_msg_id))
                        && let Some(current) = next
                            .messages
                            .iter()
                            .find(|m| m.client_msg_id == message.client_msg_id)
                    {
                        visible.push(current.clone());
                    }
                }
                page.items = visible;
            }
            ApiResponse::ListConversations(page) => {
                for value in &mut page.items {
                    *value = conversation(&mut next, value.clone());
                }
            }
            ApiResponse::GetConversation(value)
            | ApiResponse::UpdateConversation(value)
            | ApiResponse::HandleMessageRequest(value) => {
                **value = conversation(&mut next, (**value).clone());
            }
            ApiResponse::MarkRead(value) | ApiResponse::GetReadPosition(value) => {
                if let Some(conv) = next
                    .conversations
                    .iter_mut()
                    .find(|c| c.conv_id == value.conv_id)
                {
                    value.read_conv_seq = value.read_conv_seq.max(conv.read_conv_seq);
                    conv.read_conv_seq = value.read_conv_seq;
                    if conv.read_conv_seq >= conv.max_conv_seq {
                        conv.unread_count = 0;
                        conv.mention_me = false;
                    }
                }
            }
            ApiResponse::SaveDraft(value) | ApiResponse::ClearDraft(value) => {
                **value = draft(&mut next, (**value).clone());
            }
            ApiResponse::GetUserProfile(value) | ApiResponse::UpdateProfile(value) => {
                projection(&mut next, DomainProjection::UserProfile((**value).clone()))
            }
            ApiResponse::ListFriends(value) => {
                projection(&mut next, DomainProjection::Friends((**value).clone()))
            }
            ApiResponse::ListFriendRequests(value) => projection(
                &mut next,
                DomainProjection::FriendRequests((**value).clone()),
            ),
            ApiResponse::ListBlacklist(value) => {
                projection(&mut next, DomainProjection::Blacklist((**value).clone()))
            }
            ApiResponse::ListFriendGroups(value) => {
                projection(&mut next, DomainProjection::FriendGroups((**value).clone()))
            }
            ApiResponse::GetRelationSettings(value)
            | ApiResponse::UpdateRelationSettings(value) => projection(
                &mut next,
                DomainProjection::RelationSettings((**value).clone()),
            ),
            ApiResponse::ListGroups(value) => {
                projection(&mut next, DomainProjection::Groups((**value).clone()))
            }
            ApiResponse::GetGroup(value) | ApiResponse::UpdateGroup(value) => {
                projection(&mut next, DomainProjection::GroupDetail((**value).clone()))
            }
            ApiResponse::ListGroupMembers(value) => {
                if let ApiRequest::ListGroupMembers { group_id } = request {
                    projection(
                        &mut next,
                        DomainProjection::GroupMembers {
                            group_id: group_id.clone(),
                            members: (**value).clone(),
                        },
                    );
                }
            }
            ApiResponse::ListDevices(value) => {
                projection(&mut next, DomainProjection::Devices((**value).clone()))
            }
            ApiResponse::GetPushSettings(value) | ApiResponse::UpdatePushSettings(value) => {
                projection(&mut next, DomainProjection::PushSettings((**value).clone()))
            }
            ApiResponse::GetPrivacyLinks(value) | ApiResponse::UpdatePrivacyLinks(value) => {
                projection(&mut next, DomainProjection::PrivacyLinks((**value).clone()))
            }
            ApiResponse::ListReports(value) => {
                projection(&mut next, DomainProjection::Reports((**value).clone()))
            }
            ApiResponse::ListAppeals(value) => {
                projection(&mut next, DomainProjection::Appeals((**value).clone()))
            }
            ApiResponse::ExportAccount(value) | ApiResponse::GetAccountExports(value) => {
                projection(
                    &mut next,
                    DomainProjection::AccountExports((**value).clone()),
                )
            }
            _ => {}
        }
        use ApiRequest as R;
        match request {
            R::DeleteLocal { msg_id } => {
                let message = next
                    .messages
                    .iter()
                    .find(|m| m.msg_id.as_deref() == Some(msg_id))
                    .cloned();
                next.message_tombstones.retain(|t| t.msg_id != *msg_id);
                next.message_tombstones.push(MessageTombstone {
                    msg_id: msg_id.clone(),
                    client_msg_id: message.as_ref().map(|m| m.client_msg_id.clone()),
                    conv_id: message.as_ref().map(|m| m.conv_id.clone()),
                    reason: TombstoneReason::Deleted,
                });
                if let Some(message) = message {
                    next.messages
                        .retain(|m| m.client_msg_id != message.client_msg_id);
                    next.outbox
                        .retain(|o| o.client_msg_id != message.client_msg_id);
                    next.display_deadlines.retain(|d| d.msg_id != *msg_id);
                    removed.push(MessageRemoved {
                        conv_id: message.conv_id,
                        msg_ids: vec![msg_id.clone()],
                        all: false,
                    });
                }
            }
            R::ClearHistory { conv_id } => {
                let through = next
                    .conversations
                    .iter()
                    .find(|c| c.conv_id == *conv_id)
                    .map(|c| c.max_conv_seq)
                    .unwrap_or(0)
                    .max(
                        next.messages
                            .iter()
                            .filter(|m| m.conv_id == *conv_id)
                            .filter_map(|m| m.conv_seq)
                            .max()
                            .unwrap_or(0),
                    );
                let through = next
                    .conversation_tombstones
                    .iter()
                    .find(|t| t.conv_id == *conv_id)
                    .map(|t| t.through_conv_seq.max(through))
                    .unwrap_or(through);
                next.conversation_tombstones
                    .retain(|t| t.conv_id != *conv_id);
                next.conversation_tombstones.push(ConversationTombstone {
                    conv_id: conv_id.clone(),
                    through_conv_seq: through,
                });
                let ids: Vec<_> = next
                    .messages
                    .iter()
                    .filter(|m| m.conv_id == *conv_id)
                    .filter_map(|m| m.msg_id.clone())
                    .collect();
                next.messages.retain(|m| m.conv_id != *conv_id);
                next.outbox.retain(|o| o.message.conv_id != *conv_id);
                next.display_deadlines.retain(|d| !ids.contains(&d.msg_id));
                if let Some(conv) = next
                    .conversations
                    .iter_mut()
                    .find(|c| c.conv_id == *conv_id)
                {
                    conv.last_message_digest.clear();
                }
                removed.push(MessageRemoved {
                    conv_id: conv_id.clone(),
                    msg_ids: Vec::new(),
                    all: true,
                });
            }
            R::DeleteConversation { conv_id } => {
                next.conversations.retain(|c| c.conv_id != *conv_id)
            }
            R::CreateFriendRequest { .. }
            | R::HandleFriendRequest { .. }
            | R::UpdateFriend { .. }
            | R::DeleteFriend { .. }
            | R::BlockUser { .. }
            | R::UnblockUser { .. }
            | R::CreateFriendGroup { .. }
            | R::RenameFriendGroup { .. }
            | R::DeleteFriendGroup { .. } => {
                for key in [
                    ProjectionKey::Friends,
                    ProjectionKey::FriendRequests,
                    ProjectionKey::FriendGroups,
                    ProjectionKey::Blacklist,
                ] {
                    invalidate(&mut next, key);
                }
            }
            R::CreateGroup { .. }
            | R::TransferGroupOwner { .. }
            | R::LeaveGroup { .. }
            | R::DissolveGroup { .. }
            | R::JoinGroupByInvite { .. }
            | R::HandleGroupJoinRequest { .. } => invalidate(&mut next, ProjectionKey::Groups),
            R::InviteGroupMembers { group_id, .. }
            | R::RemoveGroupMember { group_id, .. }
            | R::UpdateGroupRole { group_id, .. }
            | R::UnmuteGroupMember { group_id, .. }
            | R::UpdateGroupNickname { group_id, .. } => {
                invalidate(&mut next, ProjectionKey::GroupMembers(group_id.clone()))
            }
            R::MuteGroupMember { input } => invalidate(
                &mut next,
                ProjectionKey::GroupMembers(input.group_id.clone()),
            ),
            R::GetAnnouncement { group_id } | R::UpdateAnnouncement { group_id, .. } => {
                invalidate(&mut next, ProjectionKey::GroupDetail(group_id.clone()))
            }
            R::Report { .. } => invalidate(&mut next, ProjectionKey::Reports),
            R::SubmitAppeal { .. } => invalidate(&mut next, ProjectionKey::Appeals),
            R::RemoteLogout { .. } => invalidate(&mut next, ProjectionKey::Devices),
            _ => {}
        }
        clear_derived_content(&mut next);
        self.commit(next, false).await?;
        for event in removed {
            self.emit(EventKind::MessageRemoved(event));
        }
        Ok(())
    }
}

fn preserve_newer(
    snapshot: &StorageSnapshot,
    current: &std::collections::BTreeMap<String, (u64, u64)>,
    started: &std::collections::BTreeMap<String, (u64, u64)>,
    response: &mut ApiResponse,
) {
    let preserve = |value: &mut Conversation| {
        if let Some(old) = snapshot
            .conversations
            .iter()
            .find(|c| c.conv_id == value.conv_id)
        {
            let now = current.get(&value.conv_id).copied().unwrap_or_default();
            let before = started.get(&value.conv_id).copied().unwrap_or_default();
            if now.0 != before.0 {
                value.pinned = old.pinned;
                value.pinned_at = old.pinned_at;
            }
            if now.1 != before.1 {
                value.muted = old.muted;
            }
        }
    };
    match response {
        ApiResponse::ListConversations(page) => {
            for value in &mut page.items {
                preserve(value)
            }
        }
        ApiResponse::GetConversation(value)
        | ApiResponse::UpdateConversation(value)
        | ApiResponse::HandleMessageRequest(value) => preserve(value),
        _ => {}
    }
}
