use crate::{OpenedAccount, changes_between, clear_derived_content, storage_error};
use phantom_domain::*;

impl OpenedAccount {
    /// 在后端原子提交并验证回执后才替换可读快照，调用者此后才能 ACK/发布事件。
    pub async fn apply(&mut self, mutations: Vec<StorageMutation>) -> SdkResult<()> {
        if mutations.is_empty() {
            return Ok(());
        }
        let changes = StorageChangeSet {
            expected_revision: self.snapshot.revision,
            mutations,
        };
        let next = self
            .snapshot
            .applying(changes.clone())
            .map_err(storage_error)?;
        let commit = self.store.apply(changes).await.map_err(storage_error)?;
        if commit
            != (StorageCommit {
                revision: next.revision,
                user_seq: next.user_seq,
            })
        {
            return Err(SdkError::new(
                ErrorKind::Storage,
                "存储提交回执与原子变更不一致",
            ));
        }
        self.snapshot = next;
        Ok(())
    }

    /// 崩溃时网络结果不可推断。仅恢复本地 Failed/Unknown，不自动重放任何写请求。
    pub async fn recover_sends(&mut self) -> SdkResult<()> {
        let mut mutations = Vec::new();
        let mut messages = self.snapshot.messages.clone();
        for outbox in &self.snapshot.outbox {
            if !messages
                .iter()
                .any(|m| m.client_msg_id == outbox.client_msg_id)
            {
                messages.push(outbox.message.clone());
            }
        }
        for mut message in messages {
            let outbox = self
                .snapshot
                .outbox
                .iter()
                .find(|o| o.client_msg_id == message.client_msg_id);
            if matches!(
                message.state,
                MessageState::Sent
                    | MessageState::Delivered
                    | MessageState::Read
                    | MessageState::Recalled
                    | MessageState::Burned
            ) || message.recalled
            {
                let original = message.clone();
                clear_terminal_content(&mut message);
                if message != original {
                    mutations.push(StorageMutation::PutMessage(message.clone()));
                }
                if outbox.is_some() {
                    mutations.push(StorageMutation::RemoveOutbox {
                        client_msg_id: message.client_msg_id,
                    });
                }
                continue;
            }
            if message.state == MessageState::Sending
                || outbox.is_some_and(|o| o.outcome == OperationOutcome::Unknown)
            {
                let mut error = SdkError::new(
                    ErrorKind::Network,
                    "上次发送未完成，结果未知；请核对后显式重试",
                );
                error.outcome = OperationOutcome::Unknown;
                error.client_msg_id = Some(message.client_msg_id.clone());
                message.state = MessageState::Failed;
                message.error = Some(error);
                if self
                    .snapshot
                    .messages
                    .iter()
                    .find(|m| m.client_msg_id == message.client_msg_id)
                    != Some(&message)
                {
                    mutations.push(StorageMutation::PutMessage(message.clone()));
                }
                let recovered = OutboxRecord {
                    client_msg_id: message.client_msg_id.clone(),
                    message,
                    outcome: OperationOutcome::Unknown,
                    submitted: outbox.is_some_and(|o| o.submitted),
                };
                if outbox != Some(&recovered) {
                    mutations.push(StorageMutation::PutOutbox(recovered));
                }
            }
        }
        self.apply(mutations).await
    }

    /// 展示截止时间不随重启重置；过期后清除正文和 Outbox，只留下终态标记。
    pub async fn recover_expired(&mut self, now: TimestampMs) -> SdkResult<Vec<ChatMessage>> {
        let due = self
            .snapshot
            .display_deadlines
            .iter()
            .filter(|deadline| deadline.deadline_at <= now)
            .map(|deadline| deadline.msg_id.clone())
            .collect();
        self.expire_messages(&due).await
    }

    /// 运行期间由单调时钟决定到期集合，仍与恢复路径共享原子焚毁事务。
    pub async fn expire_messages(
        &mut self,
        due: &std::collections::BTreeSet<String>,
    ) -> SdkResult<Vec<ChatMessage>> {
        let mut mutations = Vec::new();
        let mut burned = Vec::new();
        for deadline in &self.snapshot.display_deadlines {
            if !due.contains(&deadline.msg_id) {
                continue;
            }
            if let Some(value) = self
                .snapshot
                .messages
                .iter()
                .find(|m| m.msg_id.as_deref() == Some(&deadline.msg_id))
            {
                let mut message = value.clone();
                message.state = MessageState::Burned;
                clear_terminal_content(&mut message);
                mutations.push(StorageMutation::PutMessage(message.clone()));
                mutations.push(StorageMutation::RemoveOutbox {
                    client_msg_id: message.client_msg_id.clone(),
                });
                mutations.push(StorageMutation::PutMessageTombstone(MessageTombstone {
                    msg_id: deadline.msg_id.clone(),
                    client_msg_id: Some(message.client_msg_id.clone()),
                    conv_id: Some(message.conv_id.clone()),
                    reason: TombstoneReason::Burned,
                }));
                burned.push(message);
            }
            mutations.push(StorageMutation::RemoveDisplayDeadline {
                msg_id: deadline.msg_id.clone(),
            });
        }
        if !mutations.is_empty() {
            let mut next = self
                .snapshot
                .applying(StorageChangeSet {
                    expected_revision: self.snapshot.revision,
                    mutations,
                })
                .map_err(storage_error)?;
            clear_derived_content(&mut next);
            self.apply(changes_between(&self.snapshot, &next)).await?;
        }
        Ok(burned)
    }
}
