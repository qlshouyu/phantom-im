//! 存储契约的纯记录操作；消息终态合并、ACK 和事件时序由 Kernel 决定。
use crate::*;

fn upsert<T, K: PartialEq>(items: &mut Vec<T>, value: T, key: impl Fn(&T) -> K) {
    if let Some(index) = items.iter().position(|old| key(old) == key(&value)) {
        items[index] = value;
    } else {
        items.push(value);
    }
}
impl StorageSnapshot {
    /// 在副本上预检整个批次；任何失败都不改变原快照。
    /// 自定义后端可复用此纯辅助方法，也可自行实现相同记录契约。
    pub fn applying(&self, changes: StorageChangeSet) -> SdkResult<Self> {
        if changes.expected_revision != self.revision {
            return Err(SdkError::new(ErrorKind::Conflict, "存储批次版本已过期"));
        }
        let revision = self
            .revision
            .checked_add(1)
            .ok_or_else(|| SdkError::new(ErrorKind::Storage, "存储版本计数耗尽"))?;
        let mut next = self.clone();
        for change in changes.mutations {
            match change {
                StorageMutation::PutMessage(v) => {
                    validate_id(&v.client_msg_id, "client_msg_id")?;
                    validate_id(&v.conv_id, "conv_id")?;
                    if let Some(seq) = v.conv_seq {
                        validate_seq(seq, "conv_seq")?;
                    }
                    if v.msg_id.as_ref().is_some_and(|id| {
                        next.messages.iter().any(|old| {
                            old.msg_id.as_ref() == Some(id) && old.client_msg_id != v.client_msg_id
                        })
                    }) {
                        return Err(SdkError::new(
                            ErrorKind::Conflict,
                            "同一 msg_id 对应不同逻辑消息",
                        ));
                    }
                    upsert(&mut next.messages, v, |v| v.client_msg_id.clone());
                }
                StorageMutation::RemoveMessage { client_msg_id } => {
                    next.messages.retain(|v| v.client_msg_id != client_msg_id)
                }
                StorageMutation::PutConversation(v) => {
                    upsert(&mut next.conversations, v, |v| v.conv_id.clone())
                }
                StorageMutation::RemoveConversation { conv_id } => {
                    next.conversations.retain(|v| v.conv_id != conv_id)
                }
                StorageMutation::PutDraft(v) => upsert(&mut next.drafts, v, |v| v.conv_id.clone()),
                StorageMutation::PutInbox(v) => {
                    validate_seq(v.user_seq, "user_seq")?;
                    upsert(&mut next.inbox, v, |v| v.user_seq);
                }
                StorageMutation::PutOutbox(v) => {
                    if v.client_msg_id != v.message.client_msg_id {
                        return Err(SdkError::new(ErrorKind::Storage, "Outbox 逻辑身份不一致"));
                    }
                    validate_id(&v.client_msg_id, "client_msg_id")?;
                    upsert(&mut next.outbox, v, |v| v.client_msg_id.clone());
                }
                StorageMutation::RemoveOutbox { client_msg_id } => {
                    next.outbox.retain(|v| v.client_msg_id != client_msg_id)
                }
                StorageMutation::PutMessageTombstone(v) => {
                    upsert(&mut next.message_tombstones, v, |v| v.msg_id.clone())
                }
                StorageMutation::PutConversationTombstone(v) => {
                    upsert(&mut next.conversation_tombstones, v, |v| v.conv_id.clone())
                }
                StorageMutation::PutDisplayDeadline(v) => {
                    upsert(&mut next.display_deadlines, v, |v| v.msg_id.clone())
                }
                StorageMutation::RemoveDisplayDeadline { msg_id } => {
                    next.display_deadlines.retain(|v| v.msg_id != msg_id)
                }
                StorageMutation::PutProjection(v) => {
                    next.invalidated.retain(|key| *key != v.key());
                    upsert(&mut next.projections, v, DomainProjection::key);
                }
                StorageMutation::InvalidateProjection(key) => {
                    if !next.invalidated.contains(&key) {
                        next.invalidated.push(key);
                    }
                }
                StorageMutation::SetUserSeq(seq) => {
                    validate_seq(seq, "user_seq")?;
                    if seq < next.user_seq {
                        return Err(SdkError::new(ErrorKind::Conflict, "已提交游标不能倒退"));
                    }
                    next.user_seq = seq;
                }
                StorageMutation::ReplaceState(state) => {
                    if state.user_seq < next.user_seq {
                        return Err(SdkError::new(ErrorKind::Conflict, "替换状态不能使游标倒退"));
                    }
                    next = *state;
                }
            }
        }
        next.revision = revision;
        Ok(next)
    }

    pub fn query_messages(&self, query: &MessageCacheQuery) -> SdkResult<Vec<ChatMessage>> {
        query.validate()?;
        let mut items: Vec<_> = self
            .messages
            .iter()
            .filter(|msg| {
                msg.conv_id == query.conv_id
                    && query
                        .before_conv_seq
                        .is_none_or(|before| msg.conv_seq.is_some_and(|seq| seq < before))
            })
            .cloned()
            .collect();
        items.sort_by(|a, b| match (a.conv_seq, b.conv_seq) {
            (Some(left), Some(right)) => left
                .cmp(&right)
                .then_with(|| a.client_msg_id.cmp(&b.client_msg_id)),
            (None, Some(_)) => std::cmp::Ordering::Greater,
            (Some(_), None) => std::cmp::Ordering::Less,
            (None, None) => a.client_msg_id.cmp(&b.client_msg_id),
        });
        let discard = items.len().saturating_sub(query.limit as usize);
        Ok(items.drain(discard..).collect())
    }
}
