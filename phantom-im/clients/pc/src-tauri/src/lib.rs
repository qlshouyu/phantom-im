pub mod config;
pub mod cookie;
pub mod gateway;

use std::path::PathBuf;
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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .setup(|app| {
            let dev_url = "http://127.0.0.1:5273";
            if cfg!(debug_assertions) {
                return create_main_window(app, WebviewUrl::External(dev_url.parse()?));
            }

            let config_path = app.path().app_config_dir()?.join("config.json");
            GatewayConfig::ensure_default_file(&config_path)?;
            let config = GatewayConfig::from_environment_and_file(Some(&config_path))?;
            let web_root = resolve_web_root(app)?;
            let handle = tauri::async_runtime::block_on(gateway::start(config, web_root))?;
            let origin = handle.origin().to_owned();
            app.manage(GatewayService(Mutex::new(Some(handle))));
            create_main_window(app, WebviewUrl::External(origin.parse()?))
        })
        .build(tauri::generate_context!())
        .expect("failed to build Phantom IM");

    app.run(|app_handle, event| {
        if matches!(event, RunEvent::Exit) {
            if let Some(service) = app_handle.try_state::<GatewayService>() {
                service.stop();
            }
        }
    });
}
