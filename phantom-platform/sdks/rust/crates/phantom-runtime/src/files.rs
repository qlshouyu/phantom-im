use crate::shared_runtime;
use phantom_domain::*;
use phantom_ports::*;
use std::{
    fs::{File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
};
#[derive(Default)]
pub struct DefaultFiles;
fn failure(message: &str) -> SdkError {
    SdkError::new(ErrorKind::Storage, message)
}
async fn blocking<T: Send + 'static>(
    work: impl FnOnce() -> SdkResult<T> + Send + 'static,
) -> SdkResult<T> {
    shared_runtime()
        .spawn_blocking(work)
        .await
        .map_err(|_| failure("文件任务提前结束"))?
}
struct BytesReader(Arc<Vec<u8>>);
impl FileReader for BytesReader {
    fn size(&self) -> u64 {
        self.0.len() as u64
    }
    fn read_range(&self, offset: u64, length: u32) -> SdkFuture<'_, Vec<u8>> {
        Box::pin(async move {
            let start = usize::try_from(offset)
                .map_err(|_| SdkError::new(ErrorKind::InvalidArgument, "文件偏移超出范围"))?;
            if start > self.0.len() {
                return Err(SdkError::new(
                    ErrorKind::InvalidArgument,
                    "文件偏移超出范围",
                ));
            }
            let end = start.saturating_add(length as usize).min(self.0.len());
            Ok(self.0[start..end].to_vec())
        })
    }
}
struct PathReader {
    file: Arc<Mutex<File>>,
    size: u64,
}
impl FileReader for PathReader {
    fn size(&self) -> u64 {
        self.size
    }
    fn read_range(&self, offset: u64, length: u32) -> SdkFuture<'_, Vec<u8>> {
        let file = self.file.clone();
        let size = self.size;
        Box::pin(async move {
            blocking(move || {
                if offset > size {
                    return Err(SdkError::new(
                        ErrorKind::InvalidArgument,
                        "文件偏移超出范围",
                    ));
                }
                let mut file = file.lock().unwrap_or_else(|e| e.into_inner());
                if file
                    .metadata()
                    .map_err(|_| failure("无法读取文件元信息"))?
                    .len()
                    != size
                {
                    return Err(failure("上传期间源文件大小发生变化"));
                }
                file.seek(SeekFrom::Start(offset))
                    .map_err(|_| failure("无法读取源文件"))?;
                let mut bytes = vec![0; (size - offset).min(u64::from(length)) as usize];
                file.read_exact(&mut bytes)
                    .map_err(|_| failure("源文件读取不完整"))?;
                Ok(bytes)
            })
            .await
        })
    }
}
struct TargetState {
    file: Option<File>,
    temporary: PathBuf,
    destination: PathBuf,
    overwrite: bool,
    committed: bool,
}
impl Drop for TargetState {
    fn drop(&mut self) {
        self.file.take();
        if !self.committed {
            let _ = std::fs::remove_file(&self.temporary);
        }
    }
}
struct Target(Arc<Mutex<TargetState>>);
impl DownloadTarget for Target {
    fn write(&self, bytes: Vec<u8>) -> SdkFuture<'_, ()> {
        let state = self.0.clone();
        Box::pin(async move {
            blocking(move || {
                let mut state = state.lock().unwrap_or_else(|e| e.into_inner());
                state
                    .file
                    .as_mut()
                    .ok_or_else(|| failure("下载目标已结束"))?
                    .write_all(&bytes)
                    .map_err(|_| failure("下载临时文件写入失败"))
            })
            .await
        })
    }
    fn commit(&self) -> SdkFuture<'_, PathBuf> {
        let state = self.0.clone();
        Box::pin(async move {
            blocking(move || {
                let mut state = state.lock().unwrap_or_else(|e| e.into_inner());
                if state.committed {
                    return Ok(state.destination.clone());
                }
                state
                    .file
                    .as_ref()
                    .ok_or_else(|| failure("下载目标已结束"))?
                    .sync_all()
                    .map_err(|_| failure("下载文件刷新失败"))?;
                // Windows 也要求移动之前关闭文件；失败时临时路径仍由 abort/Drop 清理。
                state.file.take();
                if state.overwrite {
                    std::fs::rename(&state.temporary, &state.destination)
                        .map_err(|_| failure("下载目标提交失败"))?;
                } else {
                    std::fs::hard_link(&state.temporary, &state.destination).map_err(|e| {
                        if e.kind() == std::io::ErrorKind::AlreadyExists {
                            SdkError::new(ErrorKind::Conflict, "下载目标已存在")
                        } else {
                            failure("下载目标提交失败")
                        }
                    })?;
                    let _ = std::fs::remove_file(&state.temporary);
                }
                state.committed = true;
                Ok(state.destination.clone())
            })
            .await
        })
    }
    fn abort(&self) -> SdkFuture<'_, ()> {
        let state = self.0.clone();
        Box::pin(async move {
            blocking(move || {
                let mut state = state.lock().unwrap_or_else(|e| e.into_inner());
                if state.committed {
                    return Ok(());
                }
                state.file.take();
                match std::fs::remove_file(&state.temporary) {
                    Ok(()) => Ok(()),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
                    Err(_) => Err(failure("下载临时文件清理失败")),
                }
            })
            .await
        })
    }
}
impl Files for DefaultFiles {
    fn open(&self, source: FileSource) -> SdkFuture<'_, Arc<dyn FileReader>> {
        Box::pin(async move {
            match source {
                FileSource::Bytes(bytes) => {
                    Ok(Arc::new(BytesReader(Arc::new(bytes))) as Arc<dyn FileReader>)
                }
                FileSource::Path(path) => {
                    blocking(move || {
                        let file = File::open(path).map_err(|_| failure("无法打开源文件"))?;
                        let metadata = file
                            .metadata()
                            .map_err(|_| failure("无法读取源文件元信息"))?;
                        if !metadata.is_file() {
                            return Err(SdkError::new(
                                ErrorKind::InvalidArgument,
                                "源路径不是文件",
                            ));
                        }
                        Ok(Arc::new(PathReader {
                            file: Arc::new(Mutex::new(file)),
                            size: metadata.len(),
                        }) as Arc<dyn FileReader>)
                    })
                    .await
                }
            }
        })
    }
    fn download_target(
        &self,
        path: PathBuf,
        overwrite: bool,
    ) -> SdkFuture<'_, Arc<dyn DownloadTarget>> {
        Box::pin(async move {
            blocking(move || {
                let name = path.file_name().ok_or_else(|| {
                    SdkError::new(ErrorKind::InvalidArgument, "下载目标缺少文件名")
                })?;
                let parent = path
                    .parent()
                    .filter(|p| !p.as_os_str().is_empty())
                    .unwrap_or_else(|| std::path::Path::new("."));
                if !overwrite && path.try_exists().map_err(|_| failure("无法检查下载目标"))?
                {
                    return Err(SdkError::new(ErrorKind::Conflict, "下载目标已存在"));
                }
                static NEXT: AtomicU64 = AtomicU64::new(0);
                for _ in 0..32 {
                    let mut filename = name.to_os_string();
                    filename.push(format!(
                        ".phantom-{}-{}.part",
                        std::process::id(),
                        NEXT.fetch_add(1, Ordering::Relaxed)
                    ));
                    let temporary = parent.join(filename);
                    let mut options = OpenOptions::new();
                    options.create_new(true).write(true);
                    #[cfg(unix)]
                    {
                        use std::os::unix::fs::OpenOptionsExt;
                        options.mode(0o600);
                    }
                    match options.open(&temporary) {
                        Ok(file) => {
                            return Ok(Arc::new(Target(Arc::new(Mutex::new(TargetState {
                                file: Some(file),
                                temporary,
                                destination: path,
                                overwrite,
                                committed: false,
                            }))))
                                as Arc<dyn DownloadTarget>);
                        }
                        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
                        Err(_) => return Err(failure("无法创建下载临时文件")),
                    }
                }
                Err(failure("下载临时文件名冲突"))
            })
            .await
        })
    }
}
