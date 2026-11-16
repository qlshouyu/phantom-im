use crate::*;

/// 晚到确认不能覆盖已读、送达或清除正文的终态。
pub fn merge_message_state(current: &MessageState, incoming: &MessageState) -> MessageState {
    use MessageState::*;
    match (current, incoming) {
        (Burned, _) | (_, Burned) => Burned,
        (Recalled, _) | (_, Recalled) => Recalled,
        (Read, _) | (_, Read) => Read,
        (Delivered, _) | (_, Delivered) => Delivered,
        (Sent, _) | (_, Sent) => Sent,
        (Unknown(_), _) => incoming.clone(),
        (_, Unknown(_)) => current.clone(),
        _ => incoming.clone(),
    }
}

/// 在协议转换和存储提交之前应用，终态正文不能被旧历史恢复。
pub fn clear_terminal_content(message: &mut ChatMessage) {
    if message.recalled && !matches!(message.state, MessageState::Burned) {
        message.state = MessageState::Recalled;
    }
    if matches!(message.state, MessageState::Recalled | MessageState::Burned) {
        message.recalled |= matches!(message.state, MessageState::Recalled);
        message.content = None;
        message.text.clear();
        message.reply_snapshot = None;
        message.error = None;
    }
}
