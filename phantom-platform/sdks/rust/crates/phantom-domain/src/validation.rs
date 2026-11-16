use crate::*;

pub fn validate_id(value: &str, field: &str) -> SdkResult<()> {
    if value.trim().is_empty() {
        return Err(SdkError::new(
            ErrorKind::InvalidArgument,
            format!("{field} 不能为空"),
        ));
    }
    Ok(())
}

pub fn validate_seq(value: Seq, field: &str) -> SdkResult<()> {
    if value < 0 {
        return Err(SdkError::new(
            ErrorKind::InvalidArgument,
            format!("{field} 必须非负"),
        ));
    }
    Ok(())
}

pub fn validate_page(limit: u32, maximum: u32, before: Option<Seq>) -> SdkResult<()> {
    if limit == 0 || limit > maximum || before.is_some_and(|seq| seq <= 0) {
        return Err(SdkError::new(
            ErrorKind::InvalidArgument,
            "分页大小或起始序号无效",
        ));
    }
    Ok(())
}

pub fn single_conv_id(a: String, b: String) -> SdkResult<String> {
    validate_id(&a, "a")?;
    validate_id(&b, "b")?;
    Ok(if a <= b {
        format!("si_{a}__{b}")
    } else {
        format!("si_{b}__{a}")
    })
}

impl MessageContent {
    pub fn kind(&self) -> MessageKind {
        match self {
            Self::Text(_) => MessageKind::Text,
            Self::Emoji(_) => MessageKind::Emoji,
            Self::Image(_) => MessageKind::Image,
            Self::Voice(_) => MessageKind::Voice,
            Self::Video(_) => MessageKind::Video,
            Self::File(_) => MessageKind::File,
            Self::Location(_) => MessageKind::Location,
            Self::Card(_) => MessageKind::Card,
            Self::Merged(_) => MessageKind::Merged,
            Self::System(_) => MessageKind::System,
            Self::Unknown { kind, .. } => MessageKind::Unknown(*kind),
        }
    }

    pub fn validate_send(&self) -> SdkResult<()> {
        let invalid = |message| SdkError::new(ErrorKind::InvalidArgument, message);
        match self {
            Self::Text(value) => {
                if value.text.is_empty() || value.text.chars().count() > 5000 {
                    return Err(invalid("文本必须为 1–5000 个 Unicode 码点"));
                }
            }
            Self::Emoji(value) => validate_id(&value.emoji_id, "emoji_id")?,
            Self::Image(value) => validate_id(&value.media_id, "media_id")?,
            Self::Voice(value) => {
                validate_id(&value.media_id, "media_id")?;
                if !(1..=60).contains(&value.duration_s) {
                    return Err(invalid("语音时长必须为 1–60 秒"));
                }
            }
            Self::Video(value) => {
                validate_id(&value.media_id, "media_id")?;
                if !(1..=300).contains(&value.duration_s) || value.size_bytes > 104_857_600 {
                    return Err(invalid("视频时长或字节数超限"));
                }
            }
            Self::File(value) => {
                validate_id(&value.media_id, "media_id")?;
                validate_id(&value.file_name, "file_name")?;
                if value.size_bytes > 524_288_000 {
                    return Err(invalid("文件字节数超限"));
                }
            }
            Self::Location(value) => {
                if !value.latitude.is_finite()
                    || !value.longitude.is_finite()
                    || !(-90.0..=90.0).contains(&value.latitude)
                    || !(-180.0..=180.0).contains(&value.longitude)
                {
                    return Err(invalid("经纬度无效"));
                }
            }
            Self::Card(value) => {
                validate_id(&value.target_id, "target_id")?;
                if matches!(value.card_type, ConversationType::Unknown(_)) {
                    return Err(invalid("不能发送未知名片类型"));
                }
            }
            Self::Merged(value) => {
                if !(1..=100).contains(&value.messages.len()) {
                    return Err(invalid("合并消息必须为 1–100 条"));
                }
                if value.messages.iter().any(|msg| {
                    matches!(msg.kind, MessageKind::Merged)
                        || matches!(msg.content, Some(Self::Merged(_)))
                }) {
                    return Err(invalid("合并消息不能嵌套"));
                }
            }
            Self::System(value) => validate_id(&value.template_id, "template_id")?,
            Self::Unknown { .. } => return Err(invalid("未知正文只用于接收")),
        }
        Ok(())
    }
}

impl SendMessageInput {
    pub fn validate(&self) -> SdkResult<()> {
        validate_id(&self.conv_id, "conv_id")?;
        if matches!(self.conv_type, ConversationType::Unknown(_)) {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "不能发送到未知会话类型",
            ));
        }
        if let Some(id) = &self.client_msg_id {
            validate_id(id, "client_msg_id")?;
        }
        if let Some(id) = &self.reply_to_msg_id {
            validate_id(id, "reply_to_msg_id")?;
        }
        for id in &self.mentions {
            validate_id(id, "mentions")?;
        }
        self.content.validate_send()
    }
}

impl HistoryQuery {
    pub fn validate(&self) -> SdkResult<()> {
        validate_id(&self.conv_id, "conv_id")?;
        validate_page(self.limit, 200, self.before_conv_seq)
    }
}
impl MessageCacheQuery {
    pub fn validate(&self) -> SdkResult<()> {
        validate_id(&self.conv_id, "conv_id")?;
        validate_page(self.limit, 200, self.before_conv_seq)
    }
}

impl UploadMetadata {
    pub fn validate(&self) -> SdkResult<()> {
        validate_id(&self.file_name, "file_name")?;
        if self.size_bytes == 0
            || self.size_bytes > 524_288_000
            || (self.media_type == MediaType::Video && self.size_bytes > 104_857_600)
        {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "媒体字节数无效或超出类型限制",
            ));
        }
        if self.sha256.len() != 64
            || !self
                .sha256
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "sha256 必须为 64 位小写十六进制",
            ));
        }
        let mime = self
            .mime_type
            .split(';')
            .next()
            .unwrap_or("")
            .trim()
            .to_ascii_lowercase();
        let Some((family, subtype)) = mime.split_once('/') else {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "媒体 MIME 格式无效",
            ));
        };
        let token = |s: &str| {
            !s.is_empty()
                && s.bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"!#$&^_.+-".contains(&b))
        };
        if self.mime_type.chars().any(char::is_control)
            || !token(family)
            || !token(subtype)
            || match self.media_type {
                MediaType::Image => family != "image",
                MediaType::Voice => family != "audio",
                MediaType::Video => family != "video",
                MediaType::File => false,
            }
        {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "媒体 MIME 与类型不匹配",
            ));
        }
        if self.media_type == MediaType::Voice
            && !self.duration_seconds.is_some_and(|n| (1..=60).contains(&n))
        {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "语音必须提供 1–60 秒时长",
            ));
        }
        if self.media_type == MediaType::Video
            && self
                .duration_seconds
                .is_some_and(|n| !(1..=300).contains(&n))
        {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "视频时长必须为 1–300 秒",
            ));
        }
        if self.width == Some(0) || self.height == Some(0) {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "媒体宽高必须大于零",
            ));
        }
        Ok(())
    }
}
