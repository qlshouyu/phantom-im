//! 收发、历史、同步共用的领域合并。存储适配不包含这些业务规则。
use phantom_domain::*;
fn invalid(message: &str) -> SdkError {
    SdkError::new(ErrorKind::Protocol, message)
}
pub fn merge_message(
    snapshot: &mut StorageSnapshot,
    mut message: ChatMessage,
) -> SdkResult<Option<ChatMessage>> {
    validate_id(&message.client_msg_id, "client_msg_id")
        .map_err(|_| invalid("服务端消息缺少逻辑身份"))?;
    if snapshot.conversation_tombstones.iter().any(|t| {
        t.conv_id == message.conv_id && message.conv_seq.is_some_and(|n| n <= t.through_conv_seq)
    }) {
        return Ok(None);
    }
    if let Some(marker) = snapshot.message_tombstones.iter().find(|t| {
        Some(&t.msg_id) == message.msg_id.as_ref()
            || t.client_msg_id.as_ref() == Some(&message.client_msg_id)
    }) {
        match marker.reason {
            TombstoneReason::Deleted => return Ok(None),
            TombstoneReason::Recalled => message.state = MessageState::Recalled,
            TombstoneReason::Burned => message.state = MessageState::Burned,
        }
    }
    let existing = snapshot.messages.iter().position(|old| {
        old.client_msg_id == message.client_msg_id
            || (message.msg_id.is_some() && old.msg_id == message.msg_id)
    });
    if let Some(index) = existing {
        let old = &snapshot.messages[index];
        if old.conv_id != message.conv_id
            || old.sender_id != message.sender_id
            || (old.msg_id.is_some() && message.msg_id.is_some() && old.msg_id != message.msg_id)
        {
            return Err(invalid("消息逻辑身份与已有记录冲突"));
        }
        message.client_msg_id = old.client_msg_id.clone();
        message.msg_id = message.msg_id.or_else(|| old.msg_id.clone());
        message.conv_seq = message.conv_seq.or(old.conv_seq);
        message.server_time = message.server_time.or(old.server_time);
        message.state = merge_message_state(&old.state, &message.state);
        message.recalled |= old.recalled;
        if old.mentions.is_some() && message.mentions.is_none() {
            message.mentions = old.mentions.clone();
        }
        clear_terminal_content(&mut message);
        if old == &message {
            if matches!(
                message.state,
                MessageState::Sent
                    | MessageState::Delivered
                    | MessageState::Read
                    | MessageState::Recalled
                    | MessageState::Burned
            ) {
                snapshot
                    .outbox
                    .retain(|o| o.client_msg_id != message.client_msg_id);
            }
            return Ok(None);
        }
        snapshot.messages[index] = message.clone();
    } else {
        clear_terminal_content(&mut message);
        snapshot.messages.push(message.clone());
    }
    if matches!(
        message.state,
        MessageState::Sent
            | MessageState::Delivered
            | MessageState::Read
            | MessageState::Recalled
            | MessageState::Burned
    ) {
        snapshot
            .outbox
            .retain(|o| o.client_msg_id != message.client_msg_id);
    }
    Ok(Some(message))
}
fn marker(snapshot: &mut StorageSnapshot, conv_id: &str, msg_id: &str, reason: TombstoneReason) {
    let client_msg_id = snapshot
        .messages
        .iter()
        .find(|m| m.msg_id.as_deref() == Some(msg_id))
        .map(|m| m.client_msg_id.clone());
    let record = MessageTombstone {
        msg_id: msg_id.into(),
        client_msg_id,
        conv_id: Some(conv_id.into()),
        reason,
    };
    if let Some(old) = snapshot
        .message_tombstones
        .iter_mut()
        .find(|m| m.msg_id == msg_id)
    {
        if old.reason != TombstoneReason::Deleted && old.reason != TombstoneReason::Burned {
            *old = record;
        }
    } else {
        snapshot.message_tombstones.push(record);
    }
}
fn invalidate(snapshot: &mut StorageSnapshot, key: ProjectionKey) {
    if !snapshot.invalidated.contains(&key) {
        snapshot.invalidated.push(key);
    }
}
fn message_event(snapshot: &mut StorageSnapshot, msg_id: &str, state: MessageState) {
    for message in snapshot
        .messages
        .iter_mut()
        .filter(|m| m.msg_id.as_deref() == Some(msg_id))
    {
        message.state = merge_message_state(&message.state, &state);
        clear_terminal_content(message);
        if matches!(message.state, MessageState::Recalled | MessageState::Burned) {
            snapshot
                .outbox
                .retain(|o| o.client_msg_id != message.client_msg_id);
        }
    }
}
pub fn apply_sync_events(
    snapshot: &StorageSnapshot,
    events: &[SyncEvent],
    owner_id: &str,
) -> SdkResult<StorageSnapshot> {
    let mut next = snapshot.clone();
    let mut last = snapshot.user_seq;
    let mut accepted: Vec<&SyncEvent> = Vec::new();
    for event in events {
        if event.user_seq <= 0 {
            return Err(invalid("同步事件游标必须大于零"));
        }
        if event.user_seq <= snapshot.user_seq {
            continue;
        }
        if event.user_seq < last {
            return Err(invalid("同步分页未按 user_seq 升序返回"));
        }
        if event.user_seq == last {
            if accepted.last().is_some_and(|previous| *previous == event) {
                continue;
            }
            return Err(invalid("同一 user_seq 对应不同同步事件"));
        }
        last = event.user_seq;
        accepted.push(event);
    }
    // 先建立整页终态，避免先发正文事件再撤回。
    for event in &accepted {
        match &event.payload {
            SyncPayload::Recall(v) => {
                marker(&mut next, &v.conv_id, &v.msg_id, TombstoneReason::Recalled)
            }
            SyncPayload::MessageStatus(v)
                if matches!(v.status, MessageState::Recalled | MessageState::Burned) =>
            {
                marker(
                    &mut next,
                    &v.conv_id,
                    &v.msg_id,
                    if v.status == MessageState::Burned {
                        TombstoneReason::Burned
                    } else {
                        TombstoneReason::Recalled
                    },
                )
            }
            _ => {}
        }
    }
    for event in &accepted {
        match &event.payload {
            SyncPayload::NewMessage(v)
                if v.msg.is_none()
                    && next.message_tombstones.iter().any(|t| t.msg_id == v.msg_id) => {}
            SyncPayload::NewMessage(v) => {
                let Some(msg) = v.msg.as_ref() else {
                    return Err(invalid("同步消息引用尚未补取正文"));
                };
                if msg.conv_id != v.conv_id
                    || msg.conv_seq != Some(v.conv_seq)
                    || msg.msg_id.as_deref() != Some(&v.msg_id)
                {
                    return Err(invalid("同步消息与引用身份不一致"));
                }
                merge_message(&mut next, msg.clone())?;
                if let Some(conv) = next
                    .conversations
                    .iter_mut()
                    .find(|c| c.conv_id == v.conv_id)
                    && v.conv_seq > conv.max_conv_seq
                {
                    conv.max_conv_seq = v.conv_seq;
                    conv.last_message_at = msg.server_time.unwrap_or(event.event_time);
                    conv.last_message_digest =
                        if next.message_tombstones.iter().any(|t| t.msg_id == v.msg_id) {
                            String::new()
                        } else {
                            msg.text.clone()
                        };
                    if msg.sender_id != owner_id {
                        conv.unread_count = conv.unread_count.saturating_add(1);
                    }
                }
            }
            SyncPayload::Recall(v) => message_event(&mut next, &v.msg_id, MessageState::Recalled),
            SyncPayload::ReadPosition(v) => {
                if let Some(conv) = next
                    .conversations
                    .iter_mut()
                    .find(|c| c.conv_id == v.conv_id)
                {
                    conv.read_conv_seq = conv.read_conv_seq.max(v.read_conv_seq);
                    if conv.read_conv_seq >= conv.max_conv_seq {
                        conv.unread_count = 0;
                        conv.mention_me = false;
                    }
                }
            }
            SyncPayload::ConversationState(v) => {
                if v.field == ConversationStateField::Deleted && v.bool_value {
                    next.conversations.retain(|c| c.conv_id != v.conv_id);
                } else if let Some(conv) = next
                    .conversations
                    .iter_mut()
                    .find(|c| c.conv_id == v.conv_id)
                {
                    match v.field {
                        ConversationStateField::Pinned => {
                            if v.op_time >= conv.pinned_at {
                                conv.pinned = v.bool_value;
                                conv.pinned_at = v.op_time;
                            }
                        }
                        ConversationStateField::Muted => conv.muted = v.bool_value,
                        ConversationStateField::ReadOnly => conv.read_only = v.bool_value,
                        ConversationStateField::MarkedRead if v.bool_value => {
                            conv.unread_count = 0;
                            conv.read_conv_seq = conv.max_conv_seq;
                            conv.mention_me = false;
                        }
                        _ => {}
                    }
                }
            }
            SyncPayload::Draft(v) => {
                let newer = next
                    .drafts
                    .iter()
                    .find(|d| d.conv_id == v.conv_id)
                    .is_none_or(|old| v.edited_at > old.edited_at);
                if newer {
                    next.drafts.retain(|d| d.conv_id != v.conv_id);
                    next.drafts.push(v.clone());
                    if let Some(c) = next
                        .conversations
                        .iter_mut()
                        .find(|c| c.conv_id == v.conv_id)
                    {
                        c.draft = v.draft.clone();
                        c.draft_edited_at = v.edited_at;
                    }
                }
            }
            SyncPayload::Relation(_) => {
                for key in [
                    ProjectionKey::Friends,
                    ProjectionKey::FriendRequests,
                    ProjectionKey::Blacklist,
                    ProjectionKey::FriendGroups,
                    ProjectionKey::RelationSettings,
                ] {
                    invalidate(&mut next, key);
                }
            }
            SyncPayload::Group(v) => {
                for key in [
                    ProjectionKey::Groups,
                    ProjectionKey::GroupDetail(v.group_id.clone()),
                    ProjectionKey::GroupMembers(v.group_id.clone()),
                ] {
                    invalidate(&mut next, key);
                }
            }
            SyncPayload::Profile(v) => {
                invalidate(&mut next, ProjectionKey::UserProfile(v.target_id.clone()));
                if v.kind == ProfileEventType::MySettings {
                    invalidate(&mut next, ProjectionKey::RelationSettings);
                }
                if v.kind == ProfileEventType::FriendRemark {
                    invalidate(&mut next, ProjectionKey::Friends);
                }
            }
            SyncPayload::RestrictedDelivery(v) => {
                for m in next
                    .messages
                    .iter_mut()
                    .filter(|m| m.msg_id.as_deref() == Some(&v.msg_id))
                {
                    m.restricted = true;
                }
            }
            SyncPayload::Report(_) => invalidate(&mut next, ProjectionKey::Reports),
            _ => {}
        }
        let field_number = sync_field_number(&event.payload);
        let payload = match &event.payload {
            SyncPayload::Unknown {
                field_number,
                raw_bytes,
            } => InboxPayload::Unknown {
                field_number: *field_number,
                raw_bytes: raw_bytes.clone(),
            },
            _ => InboxPayload::Applied { field_number },
        };
        next.inbox.push(InboxRecord {
            user_seq: event.user_seq,
            event_time: event.event_time,
            payload,
        });
    }
    // 回执和状态在新消息之后合并，以覆盖同页内先状态后正文的顺序。
    for event in &accepted {
        match &event.payload {
            SyncPayload::MessageStatus(v) => message_event(&mut next, &v.msg_id, v.status.clone()),
            SyncPayload::ReadReceipt(v) => {
                for msg in next.messages.iter_mut().filter(|m| {
                    m.conv_id == v.conv_id
                        && m.sender_id == owner_id
                        && m.sender_id != v.reader_id
                        && m.conv_seq.is_some_and(|s| s <= v.read_conv_seq)
                }) {
                    msg.state = merge_message_state(&msg.state, &MessageState::Read);
                    clear_terminal_content(msg);
                }
            }
            _ => {}
        }
    }
    next.outbox.retain(|o| {
        !next.messages.iter().any(|m| {
            m.client_msg_id == o.client_msg_id
                && matches!(
                    m.state,
                    MessageState::Sent
                        | MessageState::Delivered
                        | MessageState::Read
                        | MessageState::Recalled
                        | MessageState::Burned
                )
        })
    });
    next.user_seq = last;
    // 同页终态也清除已有会话摘要和引用快照中的正文。
    clear_derived_content(&mut next);
    Ok(next)
}
pub fn sync_field_number(payload: &SyncPayload) -> u32 {
    match payload {
        SyncPayload::NewMessage(_) => 10,
        SyncPayload::Recall(_) => 11,
        SyncPayload::ReadPosition(_) => 12,
        SyncPayload::ReadReceipt(_) => 13,
        SyncPayload::ConversationState(_) => 14,
        SyncPayload::Draft(_) => 15,
        SyncPayload::Relation(_) => 16,
        SyncPayload::Group(_) => 17,
        SyncPayload::Profile(_) => 18,
        SyncPayload::RestrictedDelivery(_) => 19,
        SyncPayload::MessageStatus(_) => 20,
        SyncPayload::Report(_) => 21,
        SyncPayload::Unknown { field_number, .. } => *field_number,
    }
}
pub fn clear_derived_content(snapshot: &mut StorageSnapshot) {
    let terminal: Vec<_> = snapshot
        .message_tombstones
        .iter()
        .filter(|t| t.reason != TombstoneReason::Deleted)
        .map(|t| t.msg_id.clone())
        .collect();
    for message in snapshot
        .messages
        .iter_mut()
        .chain(snapshot.outbox.iter_mut().map(|o| &mut o.message))
    {
        if message
            .reply_to_msg_id
            .as_ref()
            .is_some_and(|id| terminal.contains(id))
        {
            message.reply_snapshot = None;
        }
        if let Some(MessageContent::Merged(content)) = &mut message.content {
            for embedded in &mut content.messages {
                if embedded
                    .msg_id
                    .as_ref()
                    .is_some_and(|id| terminal.contains(id))
                {
                    embedded.state = MessageState::Recalled;
                    clear_terminal_content(embedded);
                }
            }
        }
    }
    for conv in &mut snapshot.conversations {
        if snapshot.messages.iter().any(|m| {
            m.conv_id == conv.conv_id
                && m.conv_seq == Some(conv.max_conv_seq)
                && matches!(m.state, MessageState::Recalled | MessageState::Burned)
        }) {
            conv.last_message_digest.clear();
        }
    }
}

/// 普通提交只修改相关记录；全量重建才使用 ReplaceState。
pub fn changes_between(old: &StorageSnapshot, new: &StorageSnapshot) -> Vec<StorageMutation> {
    let mut out = Vec::new();
    macro_rules! changed {
        ($field:ident, $key:expr, $variant:ident) => {
            for v in &new.$field {
                if old.$field.iter().find(|p| $key(p) == $key(v)) != Some(v) {
                    out.push(StorageMutation::$variant(v.clone()));
                }
            }
        };
    }
    changed!(
        messages,
        |v: &ChatMessage| v.client_msg_id.clone(),
        PutMessage
    );
    changed!(
        conversations,
        |v: &Conversation| v.conv_id.clone(),
        PutConversation
    );
    changed!(drafts, |v: &ConversationDraft| v.conv_id.clone(), PutDraft);
    changed!(inbox, |v: &InboxRecord| v.user_seq, PutInbox);
    changed!(
        outbox,
        |v: &OutboxRecord| v.client_msg_id.clone(),
        PutOutbox
    );
    changed!(
        message_tombstones,
        |v: &MessageTombstone| v.msg_id.clone(),
        PutMessageTombstone
    );
    changed!(
        conversation_tombstones,
        |v: &ConversationTombstone| v.conv_id.clone(),
        PutConversationTombstone
    );
    changed!(
        display_deadlines,
        |v: &DisplayDeadline| v.msg_id.clone(),
        PutDisplayDeadline
    );
    for v in &new.projections {
        if old.projections.iter().find(|p| p.key() == v.key()) != Some(v)
            || (old.invalidated.contains(&v.key()) && !new.invalidated.contains(&v.key()))
        {
            out.push(StorageMutation::PutProjection(v.clone()));
        }
    }
    for v in &old.messages {
        if !new
            .messages
            .iter()
            .any(|n| n.client_msg_id == v.client_msg_id)
        {
            out.push(StorageMutation::RemoveMessage {
                client_msg_id: v.client_msg_id.clone(),
            });
        }
    }
    for v in &old.conversations {
        if !new.conversations.iter().any(|n| n.conv_id == v.conv_id) {
            out.push(StorageMutation::RemoveConversation {
                conv_id: v.conv_id.clone(),
            });
        }
    }
    for v in &old.outbox {
        if !new
            .outbox
            .iter()
            .any(|n| n.client_msg_id == v.client_msg_id)
        {
            out.push(StorageMutation::RemoveOutbox {
                client_msg_id: v.client_msg_id.clone(),
            });
        }
    }
    for v in &old.display_deadlines {
        if !new.display_deadlines.iter().any(|n| n.msg_id == v.msg_id) {
            out.push(StorageMutation::RemoveDisplayDeadline {
                msg_id: v.msg_id.clone(),
            });
        }
    }
    for key in &new.invalidated {
        if !old.invalidated.contains(key) {
            out.push(StorageMutation::InvalidateProjection(key.clone()));
        }
    }
    if old.user_seq != new.user_seq {
        out.push(StorageMutation::SetUserSeq(new.user_seq));
    }
    out
}
