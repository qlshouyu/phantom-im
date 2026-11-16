use crate::*;
impl PhantomClient {
    pub async fn send_typing(
        &self,
        conv_id: String,
        typing: bool,
        call: CallOptions,
    ) -> SdkResult<()> {
        self.read()?;
        validate_id(&conv_id, "conv_id")?;
        self.invoke(call, None, move |k, c| {
            Box::pin(async move {
                k.online()?;
                if k.snapshot()?
                    .conversations
                    .iter()
                    .any(|v| v.conv_id == conv_id && v.conv_type != ConversationType::Single)
                    || !conv_id.starts_with("si_")
                {
                    return Err(SdkError::new(
                        ErrorKind::InvalidArgument,
                        "输入状态仅支持单聊",
                    ));
                }
                let now = k.host.clock.monotonic_ms();
                if typing
                    && k.typing_at
                        .get(&conv_id)
                        .is_some_and(|last| now.saturating_sub(*last) < 3000)
                {
                    return Ok(());
                }
                match k
                    .gateway_request(
                        GatewayRequest::Typing {
                            conv_id: conv_id.clone(),
                            typing,
                        },
                        &c,
                    )
                    .await?
                {
                    GatewayResponse::Done => {
                        if typing {
                            k.typing_at.insert(conv_id, now);
                        } else {
                            k.typing_at.remove(&conv_id);
                        }
                        Ok(())
                    }
                    _ => Err(SdkError::new(ErrorKind::Protocol, "输入状态返回类型无效")),
                }
            })
        })
        .await
    }
    pub async fn subscribe_presence(
        &self,
        user_ids: Vec<String>,
        call: CallOptions,
    ) -> SdkResult<Vec<Presence>> {
        self.read()?;
        let user_ids = presence_ids(user_ids)?;
        self.invoke(call, None, move |k, c| {
            Box::pin(async move {
                match k
                    .gateway_request(GatewayRequest::SubscribePresence(user_ids), &c)
                    .await?
                {
                    GatewayResponse::Presence(presences) => Ok(presences),
                    _ => Err(SdkError::new(
                        ErrorKind::Protocol,
                        "在线状态快照返回类型无效",
                    )),
                }
            })
        })
        .await
    }
    pub async fn unsubscribe_presence(
        &self,
        user_ids: Vec<String>,
        call: CallOptions,
    ) -> SdkResult<()> {
        self.read()?;
        let user_ids = presence_ids(user_ids)?;
        self.invoke(call, None, move |k, c| {
            Box::pin(async move {
                match k
                    .gateway_request(GatewayRequest::UnsubscribePresence(user_ids), &c)
                    .await?
                {
                    GatewayResponse::Done => Ok(()),
                    _ => Err(SdkError::new(
                        ErrorKind::Protocol,
                        "取消在线订阅返回类型无效",
                    )),
                }
            })
        })
        .await
    }
    pub async fn set_app_state(&self, foreground: bool, call: CallOptions) -> SdkResult<()> {
        self.invoke(call, None, move |k, c| {
            Box::pin(async move {
                match k
                    .gateway_request(GatewayRequest::AppState(foreground), &c)
                    .await?
                {
                    GatewayResponse::Done => Ok(()),
                    _ => Err(SdkError::new(
                        ErrorKind::Protocol,
                        "前后台状态未得到业务确认",
                    )),
                }
            })
        })
        .await
    }
    pub fn handle_notification_click(&self, input: NotificationClick) -> SdkResult<()> {
        let view = self.read()?;
        if view.session.is_none() {
            return Err(SdkError::new(ErrorKind::NotLoggedIn, "尚未登录"));
        }
        self.inner
            .events
            .publish(view.session, EventKind::NotificationClick(input))
    }
}
fn presence_ids(user_ids: Vec<String>) -> SdkResult<Vec<String>> {
    for id in &user_ids {
        validate_id(id, "user_ids")?;
    }
    let ids: std::collections::BTreeSet<_> = user_ids.into_iter().collect();
    if !(1..=50).contains(&ids.len()) {
        return Err(SdkError::new(
            ErrorKind::InvalidArgument,
            "在线状态每次接受 1–50 个去重用户",
        ));
    }
    Ok(ids.into_iter().collect())
}
