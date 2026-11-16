use crate::client::Permit;
use crate::*;
impl PhantomClient {
    pub async fn send_text(
        &self,
        conv_id: String,
        text: String,
        call: CallOptions,
    ) -> SdkResult<ChatMessage> {
        self.send_message(
            SendMessageInput {
                conv_id,
                conv_type: ConversationType::Single,
                content: MessageContent::Text(TextContent { text }),
                reply_to_msg_id: None,
                mentions: Vec::new(),
                client_msg_id: None,
            },
            call,
        )
        .await
    }
    pub(crate) fn send_permit(&self, id: &str) -> SdkResult<Permit> {
        self.read()?;
        if !self
            .inner
            .sends
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(id.into())
        {
            return Err(SdkError::new(ErrorKind::Busy, "相同逻辑消息正在发送"));
        }
        Ok(Permit::Send(self.inner.sends.clone(), id.into()))
    }
    pub async fn send_message(
        &self,
        mut input: SendMessageInput,
        call: CallOptions,
    ) -> SdkResult<ChatMessage> {
        self.read()?;
        input.validate()?;
        let id = input
            .client_msg_id
            .clone()
            .map(Ok)
            .unwrap_or_else(|| self.inner.host.ids.generate())?;
        let permit = self.send_permit(&id)?;
        input.client_msg_id = Some(id.clone());
        self.invoke_detached(call, Some(permit), move |k, c| {
            Box::pin(async move { k.prepare_send(input, &c, false).await })
        })
        .await
        .map_err(|mut e| {
            e.client_msg_id = Some(id);
            e
        })
    }
    pub async fn resend(&self, client_msg_id: String, call: CallOptions) -> SdkResult<ChatMessage> {
        self.read()?;
        validate_id(&client_msg_id, "client_msg_id")?;
        let permit = self.send_permit(&client_msg_id)?;
        let id = client_msg_id.clone();
        self.invoke_detached(call, Some(permit), move |k, c| {
            Box::pin(async move { k.prepare_resend(client_msg_id, &c).await })
        })
        .await
        .map_err(|mut e| {
            e.client_msg_id = Some(id);
            e
        })
    }
    pub async fn recall(&self, msg_id: String, call: CallOptions) -> SdkResult<RecallResult> {
        self.read()?;
        validate_id(&msg_id, "msg_id")?;
        self.invoke(call, None, move |k, c| {
            Box::pin(async move { k.recall(msg_id, &c).await })
        })
        .await
    }
    pub fn mark_message_displayed(&self, msg_id: String) -> SdkResult<()> {
        self.read()?;
        validate_id(&msg_id, "msg_id")?;
        let now = self.inner.host.clock.now_ms();
        let epoch = self.inner.fence.load(std::sync::atomic::Ordering::Acquire);
        let mut view = self.inner.view.lock().unwrap_or_else(|e| e.into_inner());
        if view.state == ConnectionState::Released {
            return Err(SdkError::new(ErrorKind::Released, "SDK 已释放"));
        }
        if view.session.is_none() {
            return Err(SdkError::new(ErrorKind::NotLoggedIn, "尚未登录"));
        }
        let mut displayed = self
            .inner
            .displayed
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if displayed.contains_key(&(epoch, msg_id.clone())) {
            return Ok(());
        }
        let message = view
            .storage
            .messages
            .iter()
            .find(|m| m.msg_id.as_deref() == Some(&msg_id))
            .ok_or_else(|| SdkError::new(ErrorKind::NotFound, "当前账号没有该消息"))?;
        if message.burn_after_read_ttl_seconds == 0
            || matches!(message.state, MessageState::Recalled | MessageState::Burned)
            || view
                .storage
                .display_deadlines
                .iter()
                .any(|d| d.msg_id == msg_id)
        {
            return Ok(());
        }
        let ttl = i64::from(message.burn_after_read_ttl_seconds)
            .checked_mul(1000)
            .ok_or_else(|| SdkError::new(ErrorKind::InvalidArgument, "展示时长超出范围"))?;
        let deadline = DisplayDeadline {
            msg_id: msg_id.clone(),
            first_displayed_at: now,
            deadline_at: now
                .checked_add(ttl)
                .ok_or_else(|| SdkError::new(ErrorKind::InvalidArgument, "展示截止时间超出范围"))?,
        };
        let monotonic_deadline = self
            .inner
            .host
            .clock
            .monotonic_ms()
            .saturating_add(ttl as u64);
        let pending = deadline.clone();
        self.inner
            .sender
            .try_send(Box::new(move |k| {
                Box::pin(async move {
                    if k.epoch != epoch || k.live().is_err() {
                        return Ok(());
                    }
                    k.display_monotonic
                        .entry(pending.msg_id.clone())
                        .or_insert(monotonic_deadline);
                    k.pending_displays
                        .entry(pending.msg_id.clone())
                        .or_insert(pending);
                    if let Err(error) = k.burn_expired().await {
                        k.failed(error);
                    }
                    Ok(())
                })
            }))
            .map_err(|_| SdkError::new(ErrorKind::Busy, "无法排入展示时间持久化任务"))?;
        displayed.insert((epoch, msg_id), deadline.clone());
        view.storage.display_deadlines.push(deadline);
        Ok(())
    }
}
