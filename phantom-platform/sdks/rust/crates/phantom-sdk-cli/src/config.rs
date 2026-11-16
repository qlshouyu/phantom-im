use crate::wire::invalid;
use clap::{Args, ValueEnum};
use phantom_sdk::*;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Clone, Default, Args, Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct TokenReference {
    #[arg(long, global = true, conflicts_with = "token_file")]
    pub token_env: Option<String>,
    #[arg(long, global = true, conflicts_with = "token_env")]
    pub token_file: Option<PathBuf>,
}
impl TokenReference {
    pub fn validate(&self) -> SdkResult<()> {
        if self.token_env.is_some() == self.token_file.is_some() {
            return Err(invalid(
                "必须提供且只能提供一个 token_env 或 token_file 引用",
            ));
        }
        if self.token_env.as_ref().is_some_and(|s| {
            s.is_empty() || !s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
        }) {
            return Err(invalid("凭据环境变量名无效"));
        }
        if self
            .token_file
            .as_ref()
            .is_some_and(|s| s.as_os_str().is_empty())
        {
            return Err(invalid("凭据文件路径不能为空"));
        }
        Ok(())
    }
    pub fn read(&self) -> SdkResult<String> {
        self.validate()?;
        let value = if let Some(name) = &self.token_env {
            std::env::var(name).map_err(|_| invalid("指定的凭据环境变量缺失或无法读取"))?
        } else {
            std::fs::read_to_string(self.token_file.as_ref().unwrap())
                .map_err(|_| invalid("指定的凭据文件无法读取"))?
        };
        let value = value.trim().to_owned();
        if value.is_empty() {
            return Err(invalid("指定的凭据内容为空"));
        }
        Ok(value)
    }
}
#[derive(Clone, Copy, Default, ValueEnum, Deserialize, Serialize, JsonSchema, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum Storage {
    #[default]
    Default,
    Sqlite,
    Memory,
}
#[derive(Clone, Default, Args, Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Config {
    #[arg(long, global = true)]
    pub base_url: Option<String>,
    #[arg(long, global = true)]
    pub app_id: Option<String>,
    #[arg(long, global = true)]
    pub user_id: Option<String>,
    #[arg(long, global = true)]
    pub device_id: Option<String>,
    #[arg(long, global = true, default_value = "Desktop")]
    #[serde(default = "desktop")]
    pub device_type: String,
    #[arg(long, global = true, default_value = "rust-cli")]
    #[serde(default = "platform")]
    pub platform: String,
    #[arg(long, global = true, value_enum, default_value = "default")]
    #[serde(default)]
    pub storage: Storage,
    #[arg(long, global = true)]
    pub storage_dir: Option<PathBuf>,
    #[command(flatten)]
    #[serde(flatten)]
    pub token: TokenReference,
}
fn desktop() -> String {
    "Desktop".into()
}
fn platform() -> String {
    "rust-cli".into()
}
impl Config {
    pub fn validate(&self, session: bool) -> SdkResult<()> {
        if self.storage == Storage::Memory && self.storage_dir.is_some() {
            return Err(invalid("memory 与 storage_dir 不能同时使用"));
        }
        if self
            .storage_dir
            .as_ref()
            .is_some_and(|p| p.as_os_str().is_empty())
        {
            return Err(invalid("storage_dir 不能为空"));
        }
        if !matches!(self.device_type.as_str(), "Mobile" | "Desktop" | "Web") {
            return Err(invalid("device_type 必须为 Mobile/Desktop/Web"));
        }
        if let Some(base) = &self.base_url {
            ClientOptions::new(base, self.app_id.as_deref().unwrap_or("validate")).validate()?;
        }
        if session || self.token.token_env.is_some() || self.token.token_file.is_some() {
            self.token.validate()?;
        }
        if session {
            for (v, n) in [
                (&self.user_id, "user_id"),
                (&self.device_id, "device_id"),
                (&self.app_id, "app_id"),
                (&self.base_url, "base_url"),
            ] {
                validate_id(v.as_deref().ok_or_else(|| invalid(format!("缺少 {n}")))?, n)?;
            }
        }
        Ok(())
    }
    pub fn client(&self) -> SdkResult<PhantomClient> {
        self.validate(false)?;
        let mut options = ClientOptions::new(
            self.base_url
                .as_deref()
                .ok_or_else(|| invalid("缺少 base_url"))?,
            self.app_id
                .as_deref()
                .ok_or_else(|| invalid("缺少 app_id"))?,
        );
        options.storage = match (&self.storage, &self.storage_dir) {
            (_, Some(path)) => Some(StorageOptions::Directory(path.clone())),
            (Storage::Memory, None) => Some(StorageOptions::Memory),
            _ => None,
        };
        PhantomClient::new(options)
    }
    pub fn login(&self) -> SdkResult<LoginOptions> {
        self.validate(true)?;
        Ok(LoginOptions {
            user_token: self.token.read()?,
            user_id: self.user_id.clone().unwrap(),
            device_id: self.device_id.clone().unwrap(),
            device_type: match self.device_type.as_str() {
                "Mobile" => DeviceType::Mobile,
                "Web" => DeviceType::Web,
                _ => DeviceType::Desktop,
            },
            platform: self.platform.clone(),
            client_version: Some(env!("CARGO_PKG_VERSION").into()),
        })
    }
}
