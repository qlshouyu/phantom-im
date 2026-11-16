use crate::*;
use std::{path::PathBuf, sync::Arc};
#[derive(Clone)]
pub struct ClientOptions {
    pub base_url: String,
    pub app_id: String,
    pub request_timeout_ms: u64,
    pub storage: Option<StorageOptions>,
}
impl ClientOptions {
    pub fn validate(&self) -> SdkResult<()> {
        phantom_transport::BaseUrl::parse(&self.base_url)?;
        validate_id(&self.app_id, "app_id")?;
        if self.request_timeout_ms == 0 {
            return Err(SdkError::new(
                ErrorKind::InvalidArgument,
                "请求期限必须大于零",
            ));
        }
        Ok(())
    }
    pub fn new(base_url: impl Into<String>, app_id: impl Into<String>) -> Self {
        Self {
            base_url: base_url.into(),
            app_id: app_id.into(),
            request_timeout_ms: 15_000,
            storage: None,
        }
    }
}
#[derive(Clone)]
pub enum StorageOptions {
    Memory,
    Directory(PathBuf),
    Custom(Arc<dyn StoreFactory>),
}
