use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::panic::{self, PanicHookInfo};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

use chrono::Local;

pub const LOG_DIR_NAME: &str = "phantom-im-logs";
const MAX_LOG_BYTES: u64 = 5 * 1024 * 1024;
const MAX_MESSAGE_CHARS: usize = 16 * 1024;

static LOGGER: OnceLock<DiagnosticLogger> = OnceLock::new();
static PANIC_HOOK: OnceLock<()> = OnceLock::new();

struct LogState {
    file: Option<File>,
    size: u64,
    date: String,
    path: PathBuf,
}

struct DiagnosticLogger {
    directory: PathBuf,
    max_bytes: u64,
    state: Mutex<LogState>,
}

pub fn initialize() -> io::Result<PathBuf> {
    let directory = std::env::temp_dir().join(LOG_DIR_NAME);
    let logger = DiagnosticLogger::open_in(&directory)?;
    let path = logger
        .current_path()
        .ok_or_else(|| io::Error::other("无法读取诊断日志路径"))?;
    let _ = LOGGER.set(logger);
    install_panic_hook();
    info(&format!("诊断日志初始化 path={}", path.display()));
    Ok(path)
}

pub fn current_path() -> Option<PathBuf> {
    LOGGER.get().and_then(DiagnosticLogger::current_path)
}

pub fn info(message: &str) {
    write("INFO", message);
}

pub fn warn(message: &str) {
    write("WARN", message);
}

pub fn error(message: &str) {
    write("ERROR", message);
}

fn write(level: &str, message: &str) {
    if let Some(logger) = LOGGER.get() {
        logger.write(level, message);
    }
}

impl DiagnosticLogger {
    fn open_in(directory: &Path) -> io::Result<Self> {
        Self::open_in_with_limit(directory, MAX_LOG_BYTES)
    }

    fn open_in_with_limit(directory: &Path, max_bytes: u64) -> io::Result<Self> {
        fs::create_dir_all(directory)?;
        set_directory_permissions(directory)?;

        let date = current_date();
        let path = log_path(directory, &date);
        let (file, size) = open_current_file(&path, max_bytes)?;
        Ok(Self {
            directory: directory.to_path_buf(),
            max_bytes,
            state: Mutex::new(LogState {
                file: Some(file),
                size,
                date,
                path,
            }),
        })
    }

    fn current_path(&self) -> Option<PathBuf> {
        self.state.lock().ok().map(|state| state.path.clone())
    }

    fn write(&self, level: &str, message: &str) {
        let now = Local::now();
        let date = now.format("%Y%m%d").to_string();
        let timestamp = now.format("%Y-%m-%dT%H:%M:%S%.3f%:z");
        let line = format!(
            "{timestamp} {level:<5} [pid={}] {}\n",
            std::process::id(),
            sanitize_message(message)
        );

        let Ok(mut state) = self.state.lock() else {
            return;
        };
        if state.date != date {
            if let Err(error) = self.open_date_locked(&mut state, &date) {
                eprintln!("Phantom IM 诊断日志切换日期失败: {error}");
                return;
            }
        }
        if state.size > 0 && state.size.saturating_add(line.len() as u64) > self.max_bytes {
            if let Err(error) = self.rotate_locked(&mut state) {
                eprintln!("Phantom IM 诊断日志轮转失败: {error}");
            }
        }

        let Some(file) = state.file.as_mut() else {
            return;
        };
        if let Err(error) = file.write_all(line.as_bytes()).and_then(|_| file.flush()) {
            eprintln!("Phantom IM 诊断日志写入失败: {error}");
            return;
        }
        state.size = state.size.saturating_add(line.len() as u64);
    }

    fn rotate_locked(&self, state: &mut LogState) -> io::Result<()> {
        if let Some(mut file) = state.file.take() {
            let _ = file.flush();
        }
        rotate_path(&state.path);
        let file = open_append(&state.path)?;
        set_file_permissions(&state.path)?;
        state.size = file.metadata()?.len();
        state.file = Some(file);
        Ok(())
    }

    fn open_date_locked(&self, state: &mut LogState, date: &str) -> io::Result<()> {
        if let Some(mut file) = state.file.take() {
            let _ = file.flush();
        }
        fs::create_dir_all(&self.directory)?;
        set_directory_permissions(&self.directory)?;
        let path = log_path(&self.directory, date);
        let (file, size) = open_current_file(&path, self.max_bytes)?;
        state.file = Some(file);
        state.size = size;
        state.date = date.to_owned();
        state.path = path;
        Ok(())
    }
}

fn install_panic_hook() {
    if PANIC_HOOK.set(()).is_err() {
        return;
    }
    let default_hook = panic::take_hook();
    panic::set_hook(Box::new(move |info: &PanicHookInfo<'_>| {
        error(&format!("未捕获 panic {}", panic_summary(info)));
        default_hook(info);
    }));
}

fn panic_summary(info: &PanicHookInfo<'_>) -> String {
    let message = if let Some(message) = info.payload().downcast_ref::<&str>() {
        (*message).to_owned()
    } else if let Some(message) = info.payload().downcast_ref::<String>() {
        message.clone()
    } else {
        "未知 panic 内容".to_owned()
    };
    match info.location() {
        Some(location) => format!(
            "message={message} location={}:{}:{}",
            location.file(),
            location.line(),
            location.column()
        ),
        None => format!("message={message} location=unknown"),
    }
}

fn sanitize_message(message: &str) -> String {
    let mut output = String::new();
    for character in message.chars().take(MAX_MESSAGE_CHARS) {
        match character {
            '\r' => output.push_str("\\r"),
            '\n' => output.push_str("\\n"),
            other => output.push(other),
        }
    }
    if message.chars().count() > MAX_MESSAGE_CHARS {
        output.push_str("...[truncated]");
    }
    output
}

fn open_append(path: &Path) -> io::Result<File> {
    OpenOptions::new().create(true).append(true).open(path)
}

fn open_current_file(path: &Path, max_bytes: u64) -> io::Result<(File, u64)> {
    if fs::metadata(path)
        .map(|metadata| metadata.len() >= max_bytes)
        .unwrap_or(false)
    {
        rotate_path(path);
    }
    let file = open_append(path)?;
    set_file_permissions(path)?;
    let size = file.metadata()?.len();
    Ok((file, size))
}

fn rotate_path(path: &Path) {
    let file_name = path
        .file_name()
        .and_then(|value| value.to_str())
        .unwrap_or("phantom-im.log");
    let backup = path.with_file_name(format!("{file_name}.1"));
    let _ = fs::remove_file(&backup);
    let _ = fs::rename(path, backup);
}

fn log_path(directory: &Path, date: &str) -> PathBuf {
    directory.join(format!("phantom-im-{date}.log"))
}

fn current_date() -> String {
    Local::now().format("%Y%m%d").to_string()
}

#[cfg(unix)]
fn set_directory_permissions(path: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))
}

#[cfg(not(unix))]
fn set_directory_permissions(_path: &Path) -> io::Result<()> {
    Ok(())
}

#[cfg(unix)]
fn set_file_permissions(path: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))
}

#[cfg(not(unix))]
fn set_file_permissions(_path: &Path) -> io::Result<()> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_readable_lines_and_escapes_control_characters() {
        let directory = tempfile::tempdir().unwrap();
        let logger = DiagnosticLogger::open_in_with_limit(directory.path(), 1024).unwrap();
        let path = logger.current_path().unwrap();

        logger.write("INFO", "first line\nsecond line");

        let content = fs::read_to_string(path).unwrap();
        assert!(content.contains("INFO"));
        assert!(content.contains("first line\\nsecond line"));
        assert_eq!(content.lines().count(), 1);
    }

    #[test]
    fn rotates_when_the_file_reaches_the_size_limit() {
        let directory = tempfile::tempdir().unwrap();
        let logger = DiagnosticLogger::open_in_with_limit(directory.path(), 180).unwrap();
        let path = logger.current_path().unwrap();

        logger.write("INFO", &"a".repeat(100));
        logger.write("WARN", &"b".repeat(100));

        let backup_path =
            path.with_file_name(format!("{}.1", path.file_name().unwrap().to_string_lossy()));
        let current = fs::read_to_string(path).unwrap();
        let backup = fs::read_to_string(backup_path).unwrap();
        assert!(current.contains("WARN"));
        assert!(backup.contains("INFO"));
    }

    #[test]
    fn log_file_name_contains_the_local_date() {
        let directory = tempfile::tempdir().unwrap();
        let logger = DiagnosticLogger::open_in_with_limit(directory.path(), 1024).unwrap();
        let path = logger.current_path().unwrap();
        let file_name = path.file_name().unwrap().to_string_lossy();

        assert_eq!(file_name, format!("phantom-im-{}.log", current_date()));
    }

    #[test]
    fn switches_to_a_new_file_when_the_date_changes() {
        let directory = tempfile::tempdir().unwrap();
        let logger = DiagnosticLogger::open_in_with_limit(directory.path(), 1024).unwrap();
        {
            let mut state = logger.state.lock().unwrap();
            logger.open_date_locked(&mut state, "20000101").unwrap();
            state
                .file
                .as_mut()
                .unwrap()
                .write_all(b"old date\n")
                .unwrap();
        }

        logger.write("INFO", "new date");

        let current = logger.current_path().unwrap();
        let old = log_path(directory.path(), "20000101");
        assert!(current.ends_with(format!("phantom-im-{}.log", current_date())));
        assert!(fs::read_to_string(old).unwrap().contains("old date"));
        assert!(fs::read_to_string(current).unwrap().contains("new date"));
    }
}
