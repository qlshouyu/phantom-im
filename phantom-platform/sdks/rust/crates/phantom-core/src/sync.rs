use crate::*;
use std::time::Duration;
impl Kernel {
    async fn snapshot_page(
        &self,
        query: phantom_domain::ConversationQuery,
        context: &CallContext,
    ) -> phantom_domain::SdkResult<phantom_domain::ConversationPage> {
        match self
            .api_request(
                phantom_ports::ApiRequest::ListConversations { query },
                context,
            )
            .await?
        {
            phantom_ports::ApiResponse::ListConversations(page) => Ok(*page),
            _ => Err(phantom_domain::SdkError::new(
                phantom_domain::ErrorKind::Protocol,
                "会话快照响应不匹配",
            )),
        }
    }
    async fn build_snapshot(
        &mut self,
        context: &CallContext,
        full: bool,
    ) -> phantom_domain::SdkResult<phantom_domain::StorageSnapshot> {
        use phantom_domain::*;
        let mut state = self.snapshot()?.clone();
        self.current.sync.phase = if full {
            SyncPhase::Rebuilding
        } else {
            SyncPhase::Syncing
        };
        self.publish_view();
        self.emit(EventKind::SyncProgress(self.current.sync.clone()));
        let mut cursor = None;
        let mut baseline = None;
        let mut seen = std::collections::BTreeSet::new();
        state.conversations.clear();
        loop {
            let page = self
                .snapshot_page(
                    ConversationQuery {
                        cursor: cursor.clone(),
                        limit: 200,
                    },
                    context,
                )
                .await?;
            if page.max_user_seq < 0 {
                return Err(SdkError::new(ErrorKind::Protocol, "会话快照基线无效"));
            }
            baseline.get_or_insert(page.max_user_seq);
            for item in page.items {
                crate::api::conversation(&mut state, item);
            }
            if !page.has_more {
                break;
            }
            let next = page
                .next_cursor
                .filter(|v| !v.is_empty())
                .ok_or_else(|| SdkError::new(ErrorKind::Protocol, "会话分页缺少下一页游标"))?;
            if !seen.insert(next.clone()) {
                return Err(SdkError::new(ErrorKind::Protocol, "会话分页游标重复"));
            }
            cursor = Some(next);
        }
        if full {
            let baseline =
                baseline.ok_or_else(|| SdkError::new(ErrorKind::Protocol, "缺少重建基线"))?;
            if baseline < state.user_seq {
                return Err(SdkError::new(ErrorKind::Protocol, "重建快照早于已提交游标"));
            }
            state.user_seq = baseline;
            // 已处理 Inbox 仅为去重元信息；本地发送、终态和展示时间始终保留。
            state.inbox.clear();
        }
        for key in [
            ProjectionKey::UserProfile(
                self.current
                    .session
                    .as_ref()
                    .ok_or_else(|| SdkError::new(ErrorKind::NotLoggedIn, "尚未登录"))?
                    .user_id
                    .clone(),
            ),
            ProjectionKey::Groups,
        ] {
            self.refresh_projection(&mut state, key, context).await?;
        }
        if self
            .current
            .config
            .as_ref()
            .is_some_and(|c| c.relation_hosted)
        {
            for key in [
                ProjectionKey::Friends,
                ProjectionKey::FriendRequests,
                ProjectionKey::Blacklist,
                ProjectionKey::FriendGroups,
                ProjectionKey::RelationSettings,
            ] {
                self.refresh_projection(&mut state, key, context).await?;
            }
        }
        let groups = state
            .projections
            .iter()
            .find_map(|p| {
                if let DomainProjection::Groups(g) = p {
                    Some(g.clone())
                } else {
                    None
                }
            })
            .unwrap_or_default();
        for group in groups {
            for key in [
                ProjectionKey::GroupDetail(group.group_id.clone()),
                ProjectionKey::GroupMembers(group.group_id.clone()),
            ] {
                self.refresh_projection(&mut state, key, context).await?;
            }
        }
        clear_derived_content(&mut state);
        Ok(state)
    }
    async fn refresh_projection(
        &self,
        state: &mut phantom_domain::StorageSnapshot,
        key: phantom_domain::ProjectionKey,
        context: &CallContext,
    ) -> phantom_domain::SdkResult<()> {
        use phantom_domain::*;
        use phantom_ports::{ApiRequest as R, ApiResponse as A};
        let request = match &key {
            ProjectionKey::UserProfile(id) => R::GetUserProfile {
                user_id: Some(id.clone()),
            },
            ProjectionKey::Friends => R::ListFriends,
            ProjectionKey::FriendRequests => R::ListFriendRequests {
                include_handled: true,
            },
            ProjectionKey::Blacklist => R::ListBlacklist,
            ProjectionKey::FriendGroups => R::ListFriendGroups,
            ProjectionKey::RelationSettings => R::GetRelationSettings,
            ProjectionKey::Groups => R::ListGroups,
            ProjectionKey::GroupDetail(id) => R::GetGroup {
                group_id: id.clone(),
            },
            ProjectionKey::GroupMembers(id) => R::ListGroupMembers {
                group_id: id.clone(),
            },
            ProjectionKey::Devices => R::ListDevices,
            ProjectionKey::PushSettings => R::GetPushSettings,
            ProjectionKey::PrivacyLinks => R::GetPrivacyLinks,
            ProjectionKey::Reports => R::ListReports { limit: 100 },
            ProjectionKey::Appeals => R::ListAppeals { appeal_id: None },
            ProjectionKey::AccountExports => R::GetAccountExports,
        };
        let response = self.api_request(request, context).await?;
        let value = match response {
            A::GetUserProfile(v) => DomainProjection::UserProfile(*v),
            A::ListFriends(v) => DomainProjection::Friends(*v),
            A::ListFriendRequests(v) => DomainProjection::FriendRequests(*v),
            A::ListBlacklist(v) => DomainProjection::Blacklist(*v),
            A::ListFriendGroups(v) => DomainProjection::FriendGroups(*v),
            A::GetRelationSettings(v) => DomainProjection::RelationSettings(*v),
            A::ListGroups(v) => DomainProjection::Groups(*v),
            A::GetGroup(v) => DomainProjection::GroupDetail(*v),
            A::ListGroupMembers(v) => {
                let ProjectionKey::GroupMembers(group_id) = key else {
                    return Err(SdkError::new(ErrorKind::Protocol, "成员快照身份不匹配"));
                };
                DomainProjection::GroupMembers {
                    group_id,
                    members: *v,
                }
            }
            A::ListDevices(v) => DomainProjection::Devices(*v),
            A::GetPushSettings(v) => DomainProjection::PushSettings(*v),
            A::GetPrivacyLinks(v) => DomainProjection::PrivacyLinks(*v),
            A::ListReports(v) => DomainProjection::Reports(*v),
            A::ListAppeals(v) => DomainProjection::Appeals(*v),
            A::GetAccountExports(v) => DomainProjection::AccountExports(*v),
            _ => return Err(SdkError::new(ErrorKind::Protocol, "领域快照响应不匹配")),
        };
        crate::api::projection(state, value);
        Ok(())
    }
    async fn fill_events(
        &self,
        state: &mut phantom_domain::StorageSnapshot,
        events: &mut [phantom_domain::SyncEvent],
        context: &CallContext,
    ) -> phantom_domain::SdkResult<()> {
        use phantom_domain::*;
        use phantom_ports::{ApiRequest as R, ApiResponse as A};
        for event in events {
            let SyncPayload::NewMessage(value) = &mut event.payload else {
                continue;
            };
            if value.conv_seq <= 0 || value.conv_id.is_empty() || value.msg_id.is_empty() {
                return Err(SdkError::new(ErrorKind::Protocol, "同步消息引用不完整"));
            }
            let marker = state
                .message_tombstones
                .iter()
                .find(|t| t.msg_id == value.msg_id);
            if marker.is_some_and(|t| t.reason == TombstoneReason::Deleted) {
                value.msg = None;
                continue;
            }
            let cleared = state
                .conversation_tombstones
                .iter()
                .any(|t| t.conv_id == value.conv_id);
            if cleared || value.msg.is_none() {
                let response = self
                    .api_request(
                        R::History {
                            query: HistoryQuery {
                                conv_id: value.conv_id.clone(),
                                before_conv_seq: value.conv_seq.checked_add(1),
                                limit: 1,
                            },
                        },
                        context,
                    )
                    .await?;
                let A::History(page) = response else {
                    return Err(SdkError::new(ErrorKind::Protocol, "消息引用补取响应不匹配"));
                };
                if let Some(message) = page.items.iter().find(|m| {
                    m.msg_id.as_deref() == Some(&value.msg_id) && m.conv_seq == Some(value.conv_seq)
                }) {
                    value.msg = Some(message.clone());
                } else if cleared || marker.is_some() {
                    value.msg = None;
                    if marker.is_none() {
                        state.message_tombstones.push(MessageTombstone {
                            msg_id: value.msg_id.clone(),
                            client_msg_id: None,
                            conv_id: Some(value.conv_id.clone()),
                            reason: TombstoneReason::Deleted,
                        });
                    }
                } else {
                    return Err(SdkError::new(
                        ErrorKind::Protocol,
                        "消息引用未在指定历史窗口找到",
                    ));
                }
            }
        }
        Ok(())
    }
    fn emit_sync_events(
        &mut self,
        events: &[phantom_domain::SyncEvent],
        since: phantom_domain::Seq,
    ) {
        use phantom_domain::*;
        let mut emitted = std::collections::BTreeSet::new();
        for original in events.iter().filter(|e| e.user_seq > since) {
            if !emitted.insert(original.user_seq) {
                continue;
            }
            if let SyncPayload::ConversationState(value) = &original.payload {
                self.touch_conversation(
                    &value.conv_id,
                    value.field == ConversationStateField::Pinned,
                    value.field == ConversationStateField::Muted,
                );
            }
            let mut event = original.clone();
            if let SyncPayload::NewMessage(value) = &mut event.payload {
                value.msg = self.account.as_ref().and_then(|a| {
                    a.snapshot
                        .messages
                        .iter()
                        .find(|m| m.msg_id.as_deref() == Some(&value.msg_id))
                        .cloned()
                });
            }
            self.emit(EventKind::Sync(event));
        }
    }
    pub async fn synchronize(
        &mut self,
        context: &CallContext,
    ) -> phantom_domain::SdkResult<phantom_domain::SyncStatus> {
        use phantom_domain::*;
        use phantom_ports::*;
        self.online()?;
        self.current.sync.phase = SyncPhase::Syncing;
        self.current.failure = None;
        self.publish_view();
        let mut staging = if self.snapshot()?.revision == 0 {
            Some(self.build_snapshot(context, false).await?)
        } else {
            None
        };
        let mut full = false;
        let mut rebuilds = 0;
        let mut empty = 0;
        let mut staged_events = Vec::new();
        loop {
            context.check()?;
            self.live()?;
            let since = staging.as_ref().unwrap_or(self.snapshot()?).user_seq;
            let response = self
                .gateway_request(GatewayRequest::Sync { since, limit: 200 }, context)
                .await?;
            let GatewayResponse::Sync(mut batch) = response else {
                return Err(SdkError::new(ErrorKind::Protocol, "同步未返回分页"));
            };
            if batch.max_user_seq < 0 {
                return Err(SdkError::new(ErrorKind::Protocol, "同步目标游标为负数"));
            }
            self.current.sync.target_user_seq =
                self.current.sync.target_user_seq.max(batch.max_user_seq);
            if batch.full_resync {
                rebuilds += 1;
                if rebuilds > 2 {
                    return Err(SdkError::new(ErrorKind::Protocol, "全量重建基线持续失效"));
                }
                staging = Some(self.build_snapshot(context, true).await?);
                full = true;
                staged_events.clear();
                continue;
            }
            let mut base = staging.clone().unwrap_or(self.snapshot()?.clone());
            self.fill_events(&mut base, &mut batch.events, context)
                .await?;
            let owner = self
                .current
                .session
                .as_ref()
                .ok_or_else(|| SdkError::new(ErrorKind::NotLoggedIn, "尚未登录"))?
                .user_id
                .clone();
            let mut next = apply_sync_events(&base, &batch.events, &owner)?;
            let applied = next.user_seq;
            for key in next.invalidated.clone() {
                // 退群/解散后不再查询失去权限的群详情，保持失效标记即可。
                if matches!(&key,ProjectionKey::GroupDetail(id) | ProjectionKey::GroupMembers(id) if next.projections.iter().any(|p| matches!(p,DomainProjection::Groups(g) if !g.iter().any(|v| v.group_id == *id && v.status == "ACTIVE"))))
                {
                    continue;
                }
                self.refresh_projection(&mut next, key, context).await?;
            }
            if staging.is_some() {
                staging = Some(next);
                staged_events.extend(batch.events);
            } else {
                self.commit(next, false).await?;
                self.emit_sync_events(&batch.events, since);
            }
            self.current.sync.applied_user_seq = applied;
            self.publish_view();
            self.emit(EventKind::SyncProgress(self.current.sync.clone()));
            if !batch.has_more && applied >= self.current.sync.target_user_seq {
                if let Some(next) = staging.take() {
                    let old = self.snapshot()?.user_seq;
                    self.commit(next, full).await?;
                    self.emit_sync_events(&staged_events, old);
                }
                self.current.sync.phase = SyncPhase::Ready;
                self.current.sync.applied_user_seq = self.snapshot()?.user_seq;
                self.publish_view();
                self.emit(EventKind::Ready(self.current.sync.clone()));
                return Ok(self.current.sync.clone());
            }
            if applied <= since {
                empty += 1;
                if empty >= 3 {
                    return Err(SdkError::new(
                        ErrorKind::Protocol,
                        "同步分页持续无进度，拒绝跳过目标游标",
                    ));
                }
                context
                    .run(self.host.clock.sleep(Duration::from_millis(50)))
                    .await?;
            } else {
                empty = 0;
            }
        }
    }
    pub async fn push(
        &mut self,
        push: phantom_ports::GatewayPush,
        context: &CallContext,
    ) -> phantom_domain::SdkResult<()> {
        use phantom_domain::*;
        use phantom_ports::*;
        self.online()?;
        match push {
            GatewayPush::Confirmation {
                client_msg_id,
                result,
            } => {
                self.confirm(&client_msg_id, result).await?;
            }
            GatewayPush::Sync {
                mut event,
                ack_required,
            } => {
                let since = self.snapshot()?.user_seq;
                if event.user_seq > since.saturating_add(1) {
                    self.current.sync.target_user_seq =
                        self.current.sync.target_user_seq.max(event.user_seq);
                    self.synchronize(context).await?;
                }
                if event.user_seq > self.snapshot()?.user_seq {
                    let mut base = self.snapshot()?.clone();
                    self.fill_events(&mut base, std::slice::from_mut(&mut event), context)
                        .await?;
                    let owner = self
                        .current
                        .session
                        .as_ref()
                        .ok_or_else(|| SdkError::new(ErrorKind::NotLoggedIn, "尚未登录"))?
                        .user_id
                        .clone();
                    let next = apply_sync_events(&base, std::slice::from_ref(&event), &owner)?;
                    self.commit(next, false).await?;
                    self.emit_sync_events(std::slice::from_ref(&event), since);
                }
                if ack_required {
                    let connection = self
                        .connection
                        .as_ref()
                        .ok_or_else(|| SdkError::new(ErrorKind::NotConnected, "连接不存在"))?;
                    context
                        .run(connection.acknowledge(self.snapshot()?.user_seq))
                        .await?;
                }
            }
            GatewayPush::Kicked(info) => {
                self.kicked = true;
                self.state(ConnectionState::Closed);
                self.emit(EventKind::Kicked(info));
                if let Some(connection) = self.connection.take() {
                    let _ = connection.close().await;
                }
            }
            GatewayPush::Presence(info) => self.emit(EventKind::Presence(info)),
            GatewayPush::Typing(info) => self.emit(EventKind::Typing(info)),
        }
        Ok(())
    }
}
