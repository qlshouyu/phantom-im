use std::env;
use std::fs;
use std::io::Write;
use std::path::Path;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use thiserror::Error;
use url::Url;

pub const DEFAULT_BFF_URL: &str = "http://10.10.137.197:28090";
pub const DEFAULT_TIMEOUT_SECONDS: u64 = 60;
pub const BFF_URL_ENV: &str = "PHANTOM_IM_BFF_URL";
pub const BFF_TIMEOUT_ENV: &str = "PHANTOM_IM_BFF_TIMEOUT_SECONDS";

#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("BFF 地址无效: {0}")]
    InvalidUrl(String),
    #[error("BFF 地址必须是 http 或 https 绝对 URL")]
    UnsupportedUrl,
    #[error("BFF 地址不能包含用户名、密码、查询参数或片段")]
    UnsafeUrl,
    #[error("无法读取桌面配置 {path}: {source}")]
    Read {
        path: String,
        #[source]
        source: std::io::Error,
    },
    #[error("无法创建桌面配置目录 {path}: {source}")]
    CreateDirectory {
        path: String,
        #[source]
        source: std::io::Error,
    },
    #[error("无法创建桌面配置 {path}: {source}")]
    Write {
        path: String,
        #[source]
        source: std::io::Error,
    },
    #[error("桌面配置格式无效: {0}")]
    InvalidConfig(String),
    #[error("BFF 超时必须是大于 0 的秒数")]
    InvalidTimeout,
}

#[derive(Debug, Clone)]
pub struct GatewayConfig {
    bff_url: Url,
    request_timeout: Duration,
}

#[derive(Debug, Deserialize, Serialize)]
struct FileConfig {
    bff_url: Option<String>,
    timeout_seconds: Option<u64>,
}

impl GatewayConfig {
    pub fn ensure_default_file(config_path: &Path) -> Result<(), ConfigError> {
        if config_path.is_file() {
            return Ok(());
        }

        if let Some(parent) = config_path.parent() {
            fs::create_dir_all(parent).map_err(|source| ConfigError::CreateDirectory {
                path: parent.display().to_string(),
                source,
            })?;
        }

        let mut content = serde_json::to_string_pretty(&FileConfig {
            bff_url: Some(DEFAULT_BFF_URL.to_owned()),
            timeout_seconds: Some(DEFAULT_TIMEOUT_SECONDS),
        })
        .map_err(|error| ConfigError::InvalidConfig(error.to_string()))?;
        content.push('\n');

        match fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(config_path)
        {
            Ok(mut file) => {
                file.write_all(content.as_bytes())
                    .map_err(|source| ConfigError::Write {
                        path: config_path.display().to_string(),
                        source,
                    })
            }
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => Ok(()),
            Err(source) => Err(ConfigError::Write {
                path: config_path.display().to_string(),
                source,
            }),
        }
    }

    pub fn parse(raw: &str, timeout_seconds: u64) -> Result<Self, ConfigError> {
        let bff_url =
            Url::parse(raw).map_err(|error| ConfigError::InvalidUrl(error.to_string()))?;
        if !matches!(bff_url.scheme(), "http" | "https") || bff_url.host_str().is_none() {
            return Err(ConfigError::UnsupportedUrl);
        }
        if !bff_url.username().is_empty()
            || bff_url.password().is_some()
            || bff_url.query().is_some()
            || bff_url.fragment().is_some()
        {
            return Err(ConfigError::UnsafeUrl);
        }
        if timeout_seconds == 0 {
            return Err(ConfigError::InvalidTimeout);
        }
        Ok(Self {
            bff_url,
            request_timeout: Duration::from_secs(timeout_seconds),
        })
    }

    pub fn from_sources(
        bff_url: Option<&str>,
        timeout_seconds: Option<u64>,
        config_path: Option<&Path>,
    ) -> Result<Self, ConfigError> {
        let mut file_config = config_path
            .filter(|path| path.is_file())
            .map(|path| {
                let content = fs::read_to_string(path).map_err(|source| ConfigError::Read {
                    path: path.display().to_string(),
                    source,
                })?;
                serde_json::from_str::<FileConfig>(&content)
                    .map_err(|error| ConfigError::InvalidConfig(error.to_string()))
            })
            .transpose()?
            .unwrap_or(FileConfig {
                bff_url: None,
                timeout_seconds: None,
            });

        let raw = bff_url
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(str::to_owned)
            .or_else(|| file_config.bff_url.take())
            .unwrap_or_else(|| DEFAULT_BFF_URL.to_owned());
        let timeout = timeout_seconds
            .or(file_config.timeout_seconds)
            .unwrap_or(DEFAULT_TIMEOUT_SECONDS);
        Self::parse(&raw, timeout)
    }

    pub fn from_environment_and_file(config_path: Option<&Path>) -> Result<Self, ConfigError> {
        let env_url = env::var(BFF_URL_ENV).ok();
        let env_timeout = env::var(BFF_TIMEOUT_ENV)
            .ok()
            .map(|value| {
                value
                    .parse::<u64>()
                    .map_err(|_| ConfigError::InvalidTimeout)
            })
            .transpose()?;
        Self::from_sources(env_url.as_deref(), env_timeout, config_path)
    }

    #[cfg(test)]
    pub fn bff_url(&self) -> &Url {
        &self.bff_url
    }

    pub fn request_timeout(&self) -> Duration {
        self.request_timeout
    }

    pub fn bff_origin(&self) -> String {
        self.bff_url.origin().ascii_serialization()
    }

    pub fn upstream_url(&self, path_and_query: &str) -> String {
        let base = self.bff_url.as_str().trim_end_matches('/');
        format!("{base}{path_and_query}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_http_and_https_absolute_urls() {
        let http = GatewayConfig::parse("http://127.0.0.1:28090", 60).unwrap();
        let https = GatewayConfig::parse("https://bff.example.com/base/", 30).unwrap();
        assert_eq!(
            http.upstream_url("/api/x?a=1"),
            "http://127.0.0.1:28090/api/x?a=1"
        );
        assert_eq!(
            https.upstream_url("/api/x"),
            "https://bff.example.com/base/api/x"
        );
    }

    #[test]
    fn rejects_relative_and_non_http_urls() {
        assert!(matches!(
            GatewayConfig::parse("/api", 60),
            Err(ConfigError::InvalidUrl(_))
        ));
        assert!(matches!(
            GatewayConfig::parse("ftp://example.com", 60),
            Err(ConfigError::UnsupportedUrl)
        ));
    }

    #[test]
    fn rejects_credentials_query_and_fragment() {
        for value in [
            "https://user:pass@example.com",
            "https://example.com?token=secret",
            "https://example.com/#fragment",
        ] {
            assert!(matches!(
                GatewayConfig::parse(value, 60),
                Err(ConfigError::UnsafeUrl)
            ));
        }
    }

    #[test]
    fn rejects_zero_timeout() {
        assert!(matches!(
            GatewayConfig::parse(DEFAULT_BFF_URL, 0),
            Err(ConfigError::InvalidTimeout)
        ));
    }

    #[test]
    fn environment_value_overrides_file() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("config.json");
        fs::write(
            &path,
            r#"{"bff_url":"https://file.example.com","timeout_seconds":20}"#,
        )
        .unwrap();

        let config =
            GatewayConfig::from_sources(Some("http://env.example.com:9000"), None, Some(&path))
                .unwrap();
        assert_eq!(config.bff_url().as_str(), "http://env.example.com:9000/");
        assert_eq!(config.request_timeout(), Duration::from_secs(20));
    }

    #[test]
    fn uses_remote_bff_url_as_default() {
        let config = GatewayConfig::from_sources(None, None, None).unwrap();

        assert_eq!(config.bff_url().as_str(), "http://10.10.137.197:28090/");
        assert_eq!(config.request_timeout(), Duration::from_secs(60));
    }

    #[test]
    fn first_launch_creates_default_config_file() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("nested/config.json");

        GatewayConfig::ensure_default_file(&path).unwrap();

        let content = fs::read_to_string(&path).unwrap();
        let value: serde_json::Value = serde_json::from_str(&content).unwrap();
        assert_eq!(
            value["bff_url"].as_str(),
            Some("http://10.10.137.197:28090")
        );
        assert_eq!(value["timeout_seconds"].as_u64(), Some(60));
    }

    #[test]
    fn default_config_creation_does_not_overwrite_existing_file() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("config.json");
        let existing = r#"{"bff_url":"https://custom.example.com"}"#;
        fs::write(&path, existing).unwrap();

        GatewayConfig::ensure_default_file(&path).unwrap();

        assert_eq!(fs::read_to_string(&path).unwrap(), existing);
    }
}
