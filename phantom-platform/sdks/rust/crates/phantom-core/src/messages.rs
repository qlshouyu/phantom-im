use crate::*;
impl Kernel {
    pub async fn prepare_send(
        &mut self,
        input: SendMessageInput,
        context: &CallContext,
        retry: bool,
    ) -> SdkResult<Detached<ChatMessage>> {
        use phantom_domain::*;
        self.online()?;
        context.check()?;
        input.validate()?;
        let client_msg_id = input
            .client_msg_id
            .clone()
            .map(Ok)
            .unwrap_or_else(|| self.host.ids.generate())?;
        let existing = self
            .snapshot()?
            .messages
            .iter()
            .find(|m| m.client_msg_id == client_msg_id)
            .cloned();
        if let Some(old) = &existing {
            if old.conv_id != input.conv_id
                || old.conv_type != input.conv_type
                || old.reply_to_msg_id != input.reply_to_msg_id
                || old.mentions.as_ref().is_some_and(|m| *m != input.mentions)
                || old.kind != input.content.kind()
            {
                return Err(SdkError::new(
                    ErrorKind::Conflict,
                    "client_msg_id 已用于其他逻辑消息",
                ));
            }
            if old.content.as_ref().is_some_and(|c| c != &input.content) {
                return Err(SdkError::new(ErrorKind::Conflict, "重试不能更改原消息正文"));
            }
            if matches!(
                old.state,
                MessageState::Sent
                    | MessageState::Delivered
                    | MessageState::Read
                    | MessageState::Recalled
                    | MessageState::Burned
            ) {
                return Ok(Detached::ready(old.clone()));
            }
            if old.state == MessageState::Sending {
                return Err(SdkError::new(ErrorKind::Busy, "相同逻辑消息正在发送"));
            }
            if retry && old.state != MessageState::Failed {
                return Err(SdkError::new(ErrorKind::Conflict, "当前消息状态不允许重发"));
            }
        } else if retry {
            return Err(SdkError::new(ErrorKind::NotFound, "本地没有待重发消息"));
        }
        let sender_id = self
            .current
            .session
            .as_ref()
            .ok_or_else(|| SdkError::new(ErrorKind::NotLoggedIn, "尚未登录"))?
            .user_id
            .clone();
        let text = match &input.content {
            MessageContent::Text(v) => v.text.clone(),
            _ => String::new(),
        };
        let message = ChatMessage {
            msg_id: None,
            client_msg_id: client_msg_id.clone(),
            conv_id: input.conv_id,
            conv_type: input.conv_type,
            sender_id,
            kind: input.content.kind(),
            content: Some(input.content),
            text,
            conv_seq: None,
            server_time: None,
            state: MessageState::Sending,
            recalled: false,
            restricted: false,
            is_request: false,
            reply_to_msg_id: input.reply_to_msg_id,
            reply_snapshot: None,
            mentions: Some(input.mentions),
            burn_after_read_ttl_seconds: 0,
            error: None,
        };
        let mut next = self.snapshot()?.clone();
        next.messages.retain(|m| m.client_msg_id != client_msg_id);
        next.messages.push(message.clone());
        next.outbox.retain(|o| o.client_msg_id != client_msg_id);
        next.outbox.push(OutboxRecord {
            client_msg_id: client_msg_id.clone(),
            message: message.clone(),
            outcome: OperationOutcome::NotSubmitted,
            submitted: false,
        });
        self.commit(next, false).await?;
        // 在网络前持久记录可能提交的阶段；崩溃恢复不能将它当作可自动重放的队列。
        let mut submitted = self.snapshot()?.clone();
        if let Some(outbox) = submitted
            .outbox
            .iter_mut()
            .find(|o| o.client_msg_id == client_msg_id)
        {
            outbox.submitted = true;
            outbox.outcome = OperationOutcome::Unknown;
        }
        self.commit(submitted, false).await?;
        let connection = self
            .connection
            .clone()
            .ok_or_else(|| SdkError::new(ErrorKind::NotConnected, "平台连接不存在"))?;
        let context = context.clone();
        let epoch = self.epoch;
        let generation = self.generation;
        let work_id = client_msg_id.clone();
        Ok(Detached::new(
            Box::pin(async move {
                context.check()?;
                context.mark_submitted();
                let result = context
                    .run(connection.request(GatewayRequest::Send(Box::new(message.clone()))))
                    .await;
                match result {
                    Ok(GatewayResponse::Sent(receipt)) => {
                        let mut confirmed = message;
                        confirmed.msg_id = Some(receipt.msg_id);
                        confirmed.conv_seq = Some(receipt.conv_seq);
                        confirmed.server_time = Some(receipt.server_time);
                        confirmed.state = MessageState::Sent;
                        Ok(confirmed)
                    }
                    result => {
                        let mut error = match result {
                            Err(error) => error,
                            _ => SdkError::new(ErrorKind::Protocol, "发送未返回消息确认"),
                        };
                        error.client_msg_id = Some(work_id);
                        if context.submitted() && error.outcome == OperationOutcome::NotSubmitted {
                            error.outcome = OperationOutcome::Unknown;
                        }
                        Err(error)
                    }
                }
            }),
            move |kernel, mut result| {
                Box::pin(async move {
                    if kernel.epoch != epoch {
                        return Err(SdkError::new(ErrorKind::Cancelled, "发送所属账号已结束"));
                    }
                    kernel.live()?;
                    if kernel.generation != generation {
                        let mut error = SdkError::new(
                            ErrorKind::Cancelled,
                            "发送所属连接已结束，请核对消息状态",
                        );
                        error.outcome = OperationOutcome::Unknown;
                        result = Err(error);
                    }
                    match result {
                        Ok(message) => kernel
                            .confirm(
                                &client_msg_id,
                                Ok(MessageConfirmation {
                                    msg_id: message.msg_id.unwrap(),
                                    conv_seq: message.conv_seq.unwrap(),
                                    server_time: message.server_time.unwrap(),
                                }),
                            )
                            .await?
                            .ok_or_else(|| {
                                SdkError::new(ErrorKind::Storage, "发送确认缺少本地逻辑消息")
                            }),
                        Err(mut error) => {
                            error.client_msg_id = Some(client_msg_id.clone());
                            kernel.confirm(&client_msg_id, Err(error.clone())).await?;
                            Err(error)
                        }
                    }
                })
            },
        ))
    }
    pub async fn send(
        &mut self,
        input: SendMessageInput,
        context: &CallContext,
        retry: bool,
    ) -> SdkResult<ChatMessage> {
        let plan = self.prepare_send(input, context, retry).await?;
        plan.resolve(self).await
    }
    pub async fn confirm(
        &mut self,
        client_msg_id: &str,
        result: phantom_domain::SdkResult<phantom_ports::MessageConfirmation>,
    ) -> phantom_domain::SdkResult<Option<phantom_domain::ChatMessage>> {
        use phantom_domain::*;
        self.live()?;
        let mut next = self.snapshot()?.clone();
        let Some(mut message) = next
            .messages
            .iter()
            .find(|m| m.client_msg_id == client_msg_id)
            .cloned()
        else {
            return Ok(None);
        };
        match result {
            Ok(receipt) => {
                message.msg_id = Some(receipt.msg_id);
                message.conv_seq = Some(receipt.conv_seq);
                message.server_time = Some(receipt.server_time);
                message.state = merge_message_state(&message.state, &MessageState::Sent);
                message.error = None;
            }
            Err(error) => {
                if matches!(
                    message.state,
                    MessageState::Sent
                        | MessageState::Delivered
                        | MessageState::Read
                        | MessageState::Recalled
                        | MessageState::Burned
                ) {
                    return Ok(Some(message));
                }
                message.state = MessageState::Failed;
                message.error = Some(error.clone());
                if let Some(outbox) = next
                    .outbox
                    .iter_mut()
                    .find(|o| o.client_msg_id == client_msg_id)
                {
                    outbox.message = message.clone();
                    outbox.outcome = error.outcome;
                    outbox.submitted = error.outcome != OperationOutcome::NotSubmitted;
                }
            }
        }
        merge_message(&mut next, message)?;
        clear_derived_content(&mut next);
        self.commit(next, false).await?;
        Ok(self
            .snapshot()?
            .messages
            .iter()
            .find(|m| m.client_msg_id == client_msg_id)
            .cloned())
    }
    pub async fn prepare_resend(
        &mut self,
        client_msg_id: String,
        context: &CallContext,
    ) -> SdkResult<Detached<ChatMessage>> {
        use phantom_domain::*;
        self.online()?;
        validate_id(&client_msg_id, "client_msg_id")?;
        let old = self
            .snapshot()?
            .messages
            .iter()
            .find(|m| m.client_msg_id == client_msg_id)
            .cloned()
            .ok_or_else(|| SdkError::new(ErrorKind::NotFound, "本地没有待重发消息"))?;
        if old.state != MessageState::Failed {
            return Err(SdkError::new(ErrorKind::Conflict, "只有 Failed 消息可重发"));
        }
        let content = old
            .content
            .ok_or_else(|| SdkError::new(ErrorKind::Conflict, "重发消息正文已清除"))?;
        self.prepare_send(
            SendMessageInput {
                conv_id: old.conv_id,
                conv_type: old.conv_type,
                content,
                reply_to_msg_id: old.reply_to_msg_id,
                mentions: old.mentions.unwrap_or_default(),
                client_msg_id: Some(client_msg_id),
            },
            context,
            true,
        )
        .await
    }
    pub async fn recall(
        &mut self,
        msg_id: String,
        context: &CallContext,
    ) -> phantom_domain::SdkResult<phantom_domain::RecallResult> {
        use phantom_domain::*;
        validate_id(&msg_id, "msg_id")?;
        match self
            .gateway_request(phantom_ports::GatewayRequest::Recall(msg_id), context)
            .await?
        {
            phantom_ports::GatewayResponse::Recalled(result) => Ok(result),
            _ => Err(SdkError::new(ErrorKind::Protocol, "撤回未返回确认")),
        }
    }
}
