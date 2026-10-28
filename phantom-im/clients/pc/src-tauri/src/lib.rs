pub mod config;
pub mod cookie;
pub mod diagnostics;
pub mod gateway;

use std::path::PathBuf;
use std::process::Command;
use std::sync::Mutex;

use tauri::{Manager, RunEvent, WebviewUrl, WebviewWindowBuilder};

use crate::config::GatewayConfig;
use crate::gateway::GatewayHandle;

struct GatewayService(Mutex<Option<GatewayHandle>>);

impl GatewayService {
    fn stop(&self) {
        if let Ok(mut handle) = self.0.lock() {
            if let Some(handle) = handle.as_mut() {
                handle.stop();
            }
        }
    }
}

fn resolve_web_root(app: &tauri::App) -> Result<PathBuf, Box<dyn std::error::Error>> {
    let bundled = app
        .path()
        .resolve("web", tauri::path::BaseDirectory::Resource)?;
    if bundled.join("index.html").is_file() {
        return Ok(bundled);
    }

    let source = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources/web");
    if source.join("index.html").is_file() {
        return Ok(source);
    }

    Err(format!(
        "未找到 Phantom IM 内置 Web 资源；请先执行 npm run build:web（检查 {} 或 {}）",
        bundled.display(),
        source.display()
    )
    .into())
}

fn create_main_window(app: &tauri::App, url: WebviewUrl) -> Result<(), Box<dyn std::error::Error>> {
    WebviewWindowBuilder::new(app, "main", url)
        .title("Phantom IM")
        .inner_size(1280.0, 820.0)
        .min_inner_size(1080.0, 700.0)
        .center()
        .resizable(true)
        .decorations(true)
        .initialization_script(
            "Object.defineProperty(window, '__PHANTOM_IM_DESKTOP__', { value: Object.freeze({ runtime: 'tauri' }), enumerable: false, configurable: false });",
        )
        .build()?;
    Ok(())
}

#[tauri::command]
fn write_frontend_log(level: String, message: String) {
    let message = format!("前端 {message}");
    match level.to_ascii_lowercase().as_str() {
        "error" => diagnostics::error(&message),
        "warn" => diagnostics::warn(&message),
        _ => diagnostics::info(&message),
    }
}

#[tauri::command]
fn diagnostic_log_path() -> Option<String> {
    diagnostics::current_path().map(|path| path.display().to_string())
}

#[tauri::command]
fn open_diagnostic_log_dir() -> Result<(), String> {
    let directory = std::env::temp_dir().join(diagnostics::LOG_DIR_NAME);
    std::fs::create_dir_all(&directory).map_err(|error| error.to_string())?;

    #[cfg(target_os = "macos")]
    let mut command = Command::new("open");
    #[cfg(target_os = "windows")]
    let mut command = Command::new("explorer");
    #[cfg(target_os = "linux")]
    let mut command = Command::new("xdg-open");
    #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
    return Err("当前平台不支持打开诊断日志目录".to_owned());

    command
        .arg(&directory)
        .spawn()
        .map(|_| ())
        .map_err(|error| format!("无法打开诊断日志目录: {error}"))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    match diagnostics::initialize() {
        Ok(path) => eprintln!("Phantom IM 诊断日志: {}", path.display()),
        Err(error) => eprintln!("Phantom IM 诊断日志初始化失败: {error}"),
    }
    diagnostics::info(&format!(
        "应用启动 version={} os={} arch={} debug={}",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::OS,
        std::env::consts::ARCH,
        cfg!(debug_assertions)
    ));

    let app = tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            write_frontend_log,
            diagnostic_log_path,
            open_diagnostic_log_dir
        ])
        .setup(|app| {
            let dev_url = "http://127.0.0.1:5273";
            if cfg!(debug_assertions) {
                diagnostics::info(&format!("开发模式加载 Web UI url={dev_url}"));
                return create_main_window(app, WebviewUrl::External(dev_url.parse()?)).map_err(
                    |error| {
                        diagnostics::error(&format!("创建主窗口失败 error={error}"));
                        error
                    },
                );
            }

            let config_path = app.path().app_config_dir()?.join("config.json");
            diagnostics::info(&format!("加载桌面配置 path={}", config_path.display()));
            GatewayConfig::ensure_default_file(&config_path).map_err(|error| {
                diagnostics::error(&format!("创建默认桌面配置失败 error={error}"));
                error
            })?;
            let config =
                GatewayConfig::from_environment_and_file(Some(&config_path)).map_err(|error| {
                    diagnostics::error(&format!("读取桌面配置失败 error={error}"));
                    error
                })?;
            diagnostics::info(&format!(
                "BFF 配置 origin={} timeout_seconds={}",
                config.bff_origin(),
                config.request_timeout().as_secs()
            ));
            let web_root = resolve_web_root(app).map_err(|error| {
                diagnostics::error(&format!("解析内置 Web 资源失败 error={error}"));
                error
            })?;
            diagnostics::info(&format!("内置 Web 资源 path={}", web_root.display()));
            let handle = tauri::async_runtime::block_on(gateway::start(config, web_root)).map_err(
                |error| {
                    diagnostics::error(&format!("本地网关启动失败 error={error}"));
                    error
                },
            )?;
            let origin = handle.origin().to_owned();
            diagnostics::info(&format!(
                "本地网关启动 origin={} address={}",
                origin,
                handle.addr()
            ));
            app.manage(GatewayService(Mutex::new(Some(handle))));
            create_main_window(app, WebviewUrl::External(origin.parse()?)).map_err(|error| {
                diagnostics::error(&format!("创建主窗口失败 error={error}"));
                error
            })
        })
        .build(tauri::generate_context!())
        .expect("failed to build Phantom IM");

    app.run(|app_handle, event| {
        if matches!(event, RunEvent::Exit) {
            diagnostics::info("应用退出");
            if let Some(service) = app_handle.try_state::<GatewayService>() {
                service.stop();
            }
        }
    });
}
