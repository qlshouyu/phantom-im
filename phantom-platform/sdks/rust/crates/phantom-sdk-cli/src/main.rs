//! 仅通过公开 Phantom SDK 调用平台；所有 stdout 均为 JSON/NDJSON。
mod config;
mod methods;
mod scenario;
mod smoke;
mod wire;
use clap::{Parser, Subcommand};
use config::Config;
use phantom_sdk::*;
use serde_json::{Value, json};
use std::{
    io::Read,
    path::PathBuf,
    time::{Duration, Instant},
};

#[derive(Parser)]
#[command(
    version,
    about = "Phantom Rust SDK 的类型化调用与测试工具",
    after_help = "Token 只接受 --token-env/--token-file 引用。call 默认等待 Ready；精确生命周期测试使用 scenario。"
)]
struct Args {
    #[command(flatten)]
    config: Config,
    #[arg(long, global = true)]
    timeout_ms: Option<u64>,
    #[command(subcommand)]
    command: Command,
}
#[derive(Subcommand)]
enum Command {
    /// 列出方法，或查询某项方法的输入、输出和副作用模式。
    Schema { method: Option<String> },
    /// 从 JSON 文件或 stdin（-）读取方法参数。
    Call {
        method: String,
        #[arg(long, default_value = "-")]
        input: PathBuf,
        #[arg(long)]
        dry_run: bool,
    },
    /// 监听事件；指定期限、条数或显式 --follow。
    Listen {
        #[arg(long)]
        duration_ms: Option<u64>,
        #[arg(long)]
        max_events: Option<u64>,
        #[arg(long)]
        follow: bool,
    },
    /// 在同一进程中执行命名实例、订阅、调用、等待和断言。
    Scenario {
        file: PathBuf,
        #[arg(long)]
        dry_run: bool,
    },
    /// 两个预置账号的真实平台收发、幂等、离线与恢复测试。
    Smoke {
        #[arg(long)]
        config: PathBuf,
        #[arg(long)]
        dry_run: bool,
    },
}
pub(crate) struct Execution {
    clients: Vec<PhantomClient>,
    cancellation: CancellationToken,
    deadline: Option<Instant>,
    temporary: Vec<tempfile::TempDir>,
    listened_events: u64,
    partial_report: Option<(Vec<String>, Vec<Value>)>,
}
impl Execution {
    fn new(timeout_ms: u64) -> SdkResult<Self> {
        if timeout_ms == 0 {
            return Err(wire::invalid("整体期限必须大于零"));
        }
        let deadline = Instant::now()
            .checked_add(Duration::from_millis(timeout_ms))
            .ok_or_else(|| wire::invalid("整体期限超出范围"))?;
        Ok(Self {
            clients: Vec::new(),
            cancellation: CancellationToken::new(),
            deadline: Some(deadline),
            temporary: Vec::new(),
            listened_events: 0,
            partial_report: None,
        })
    }
    pub(crate) fn call(&self) -> SdkResult<CallOptions> {
        let Some(deadline) = self.deadline else {
            return Ok(CallOptions {
                timeout_ms: None,
                cancellation: Some(self.cancellation.clone()),
            });
        };
        let remaining = deadline
            .saturating_duration_since(Instant::now())
            .as_millis();
        if remaining == 0 {
            return Err(SdkError::new(ErrorKind::Timeout, "CLI 整体期限已到"));
        }
        Ok(CallOptions {
            timeout_ms: Some(remaining.min(u128::from(u64::MAX)) as u64),
            cancellation: Some(self.cancellation.clone()),
        })
    }
    pub(crate) fn client(&mut self, config: &Config) -> SdkResult<PhantomClient> {
        let client = config.client()?;
        self.clients.push(client.clone());
        Ok(client)
    }
    async fn cleanup(&mut self) -> SdkResult<()> {
        self.cancellation.cancel();
        let mut tasks = tokio::task::JoinSet::new();
        for client in self.clients.drain(..) {
            tasks.spawn(async move {
                client
                    .shutdown(CallOptions {
                        timeout_ms: Some(5_000),
                        cancellation: None,
                    })
                    .await
            });
        }
        let cleanup = async {
            let mut first = None;
            while let Some(result) = tasks.join_next().await {
                match result {
                    Ok(Ok(())) => {}
                    Ok(Err(e)) => {
                        first.get_or_insert(e);
                    }
                    Err(_) => {
                        first.get_or_insert(SdkError::new(ErrorKind::Storage, "SDK 清理任务失败"));
                    }
                }
            }
            first.map_or(Ok(()), Err)
        };
        let result = tokio::time::timeout(Duration::from_secs(5), cleanup)
            .await
            .map_err(|_| SdkError::new(ErrorKind::Timeout, "SDK 资源清理超过 5 秒"))
            .and_then(|r| r);
        if result.is_ok() {
            self.temporary.clear();
        } else {
            for directory in self.temporary.drain(..) {
                let path = directory.keep();
                eprintln!("清理未完成，保留本次测试目录：{}", path.display());
            }
        }
        result
    }
}
pub(crate) fn read_json(path: &std::path::Path) -> SdkResult<Value> {
    let bytes = if path.as_os_str() == "-" {
        let mut bytes = Vec::new();
        std::io::stdin()
            .take(16 * 1024 * 1024 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| wire::invalid("无法读取标准输入"))?;
        bytes
    } else {
        let metadata = std::fs::metadata(path).map_err(|_| wire::invalid("无法读取输入文件"))?;
        if metadata.len() > 16 * 1024 * 1024 {
            return Err(wire::invalid("JSON 输入超过 16MiB"));
        }
        std::fs::read(path).map_err(|_| wire::invalid("无法读取输入文件"))?
    };
    if bytes.len() > 16 * 1024 * 1024 {
        return Err(wire::invalid("JSON 输入超过 16MiB"));
    }
    serde_json::from_slice(&bytes).map_err(|_| wire::invalid("JSON 输入无效"))
}
pub(crate) fn emit(value: &Value) {
    println!("{}", value);
}
pub(crate) fn envelope(result: SdkResult<Value>) -> Value {
    match result {
        Ok(data) => json!({"schema_version":1,"ok":true,"data":data}),
        Err(error) => json!({"schema_version":1,"ok":false,"error":error}),
    }
}
pub(crate) fn exit_code(error: &SdkError) -> i32 {
    if error.server_code == Some(5002) || matches!(error.http_status, Some(401 | 403)) {
        return 3;
    }
    match error.kind {
        ErrorKind::InvalidArgument => 2,
        ErrorKind::Authentication | ErrorKind::NotLoggedIn => 3,
        ErrorKind::Network | ErrorKind::Protocol | ErrorKind::Timeout | ErrorKind::NotConnected => {
            4
        }
        ErrorKind::Storage => 6,
        ErrorKind::Cancelled => 130,
        _ => 1,
    }
}
pub(crate) fn assertion(message: impl Into<String>) -> SdkError {
    SdkError::new(ErrorKind::Conflict, message)
}
pub(crate) fn redact(value: &mut Value) {
    match value {
        Value::Object(map) => {
            for (key, value) in map {
                if matches!(key.as_str(), "user_token" | "token" | "authorization") {
                    *value = json!("[REDACTED]");
                } else {
                    redact(value);
                }
            }
        }
        Value::Array(items) => {
            for v in items {
                redact(v);
            }
        }
        _ => {}
    }
}
async fn run(args: Args, execution: &mut Execution) -> SdkResult<Value> {
    match args.command {
        Command::Schema { method } => match method {
            Some(method) => methods::describe(&method),
            None => Ok(
                json!({"methods":methods::METHODS,"config":wire::schema::<Config>(),"scenario":wire::schema::<scenario::Scenario>(),"integer64":"decimal-string","binary":"base64"}),
            ),
        },
        Command::Call {
            method,
            input,
            dry_run,
        } => {
            let params = read_json(&input)?;
            methods::validate(&method, params.clone())?;
            let session = methods::needs_session(&method);
            args.config.validate(session || method == "login")?;
            if dry_run {
                let mut params = params;
                redact(&mut params);
                return Ok(
                    json!({"dry_run":true,"method":method,"params":params,"account":args.config.user_id,"side_effect":methods::effect(&method),"login":session}),
                );
            }
            if method == "single_conv_id" {
                return methods::execute_helper(params);
            }
            let client = execution.client(&args.config)?;
            if session {
                client
                    .login(args.config.login()?, execution.call()?)
                    .await?;
                client.wait_ready(execution.call()?).await?;
            }
            methods::execute(&client, &args.config, &method, params, execution.call()?).await
        }
        Command::Listen {
            duration_ms,
            max_events,
            follow,
        } => {
            if duration_ms == Some(0)
                || max_events == Some(0)
                || (!follow && duration_ms.is_none() && max_events.is_none())
            {
                return Err(wire::invalid(
                    "listen 需要正数 duration_ms/max_events 或显式 follow",
                ));
            }
            args.config.validate(true)?;
            let client = execution.client(&args.config)?;
            let subscription = client.subscribe(SubscriptionOptions { capacity: 512 })?;
            client
                .login(args.config.login()?, execution.call()?)
                .await?;
            let end =
                duration_ms.and_then(|ms| Instant::now().checked_add(Duration::from_millis(ms)));
            let mut count = 0u64;
            loop {
                if max_events.is_some_and(|n| count >= n)
                    || end.is_some_and(|d| Instant::now() >= d)
                {
                    break;
                }
                let mut call = execution.call()?;
                if let Some(end) = end {
                    call.timeout_ms = Some(
                        call.timeout_ms.unwrap().min(
                            end.saturating_duration_since(Instant::now())
                                .as_millis()
                                .max(1) as u64,
                        ),
                    );
                }
                match subscription.next(call).await {
                    Ok(Some(event)) => {
                        count += 1;
                        execution.listened_events = count;
                        emit(
                            &json!({"schema_version":1,"type":"event","data":wire::encode(&event)?}),
                        );
                    }
                    Ok(None) => break,
                    Err(e)
                        if e.kind == ErrorKind::Timeout
                            && end.is_some_and(|d| Instant::now() >= d) =>
                    {
                        break;
                    }
                    Err(e) => return Err(e),
                }
            }
            subscription.close();
            Ok(json!({"type":"summary","events":count.to_string(),"reason":"limit"}))
        }
        Command::Scenario { file, dry_run } => {
            scenario::run(read_json(&file)?, dry_run, execution).await
        }
        Command::Smoke { config, dry_run } => {
            smoke::run(read_json(&config)?, dry_run, execution).await
        }
    }
}
#[tokio::main]
async fn main() {
    let args = match Args::try_parse() {
        Ok(args) => args,
        Err(error) => {
            if matches!(
                error.kind(),
                clap::error::ErrorKind::DisplayHelp | clap::error::ErrorKind::DisplayVersion
            ) {
                let _ = error.print();
                return;
            }
            emit(&envelope(Err(wire::invalid(
                "命令行参数无效，请使用 --help 查询",
            ))));
            std::process::exit(2);
        }
    };
    let default_timeout = if matches!(args.command, Command::Smoke { .. }) {
        120_000
    } else {
        60_000
    };
    // 显式 follow 不受默认 60 秒限制，仍可通过 timeout_ms 设置上限。
    let timeout = args.timeout_ms.unwrap_or(match &args.command {
        Command::Listen {
            duration_ms: Some(ms),
            ..
        } => ms.saturating_add(10_000),
        Command::Listen { follow: true, .. } => default_timeout,
        _ => default_timeout,
    });
    let mut execution = match Execution::new(timeout) {
        Ok(e) => e,
        Err(error) => {
            emit(&envelope(Err(error)));
            std::process::exit(2);
        }
    };
    let listening = matches!(args.command, Command::Listen { .. });
    let unbounded = args.timeout_ms.is_none()
        && matches!(
            args.command,
            Command::Listen {
                follow: true,
                duration_ms: None,
                ..
            }
        );
    if unbounded {
        execution.deadline = None;
    }
    let result = tokio::select! { biased; result = run(args,&mut execution) => result, signal = tokio::signal::ctrl_c() => {
        match signal { Ok(()) => Err(SdkError::new(ErrorKind::Cancelled,"用户中断")),Err(_) => Err(SdkError::new(ErrorKind::Storage,"无法注册中断信号")) }
    },_ = async { if unbounded { std::future::pending::<()>().await; } else { tokio::time::sleep(Duration::from_millis(timeout)).await; } } => Err(SdkError::new(ErrorKind::Timeout,"CLI 整体期限已到")) };
    let cleanup = execution.cleanup().await;
    let result = match result {
        Ok(data) => cleanup.map(|_| data),
        Err(error) => {
            if cleanup.is_err() {
                eprintln!("SDK 清理未完成，报告保留原始失败原因");
            }
            Err(error)
        }
    };
    let mut code = result.as_ref().err().map_or(0, exit_code);
    let mut output = match result {
        Ok(mut data) => {
            if let Some(object) = data.as_object_mut() {
                let report_code = object
                    .remove("_exit_code")
                    .and_then(|v| v.as_i64())
                    .unwrap_or(0) as i32;
                let error = object.remove("_error").unwrap_or(Value::Null);
                if report_code != 0 {
                    code = report_code;
                    json!({"schema_version":1,"ok":false,"data":data,"error":error})
                } else {
                    envelope(Ok(data))
                }
            } else {
                envelope(Ok(data))
            }
        }
        Err(error) => {
            let mut output = envelope(Err(error.clone()));
            if let Some((ids, mut steps)) = execution.partial_report.take() {
                let index = steps.len();
                steps.push(json!({"id":ids.get(index).map(String::as_str).unwrap_or("cleanup"),"status":"failed","error":error}));
                for id in ids.iter().skip(index + 1) {
                    steps.push(json!({"id":id,"status":"skipped","reason":"执行已取消、超时或资源清理失败"}));
                }
                let passed = steps.iter().filter(|v| v["status"] == "passed").count();
                let failed = steps.iter().filter(|v| v["status"] == "failed").count();
                output["data"] = json!({"passed":passed,"failed":failed,"skipped":steps.len()-passed-failed,"steps":steps});
            }
            output
        }
    };
    if listening {
        output["type"] = json!("summary");
        output["events"] = json!(execution.listened_events.to_string());
    }
    emit(&output);
    std::process::exit(code);
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exit_codes_preserve_error_classes_without_forged_server_codes() {
        for (kind, code) in [
            (ErrorKind::InvalidArgument, 2),
            (ErrorKind::Authentication, 3),
            (ErrorKind::Network, 4),
            (ErrorKind::Protocol, 4),
            (ErrorKind::Timeout, 4),
            (ErrorKind::Storage, 6),
            (ErrorKind::Cancelled, 130),
            (ErrorKind::Business, 1),
        ] {
            let error = SdkError::new(kind, "测试错误");
            assert_eq!(exit_code(&error), code);
            let output = envelope(Err(error));
            assert_eq!(output["ok"], false);
            assert_eq!(output["schema_version"], 1);
            assert!(output["error"]["server_code"].is_null());
        }
        let mut permission = SdkError::new(ErrorKind::Business, "权限拒绝");
        permission.server_code = Some(5002);
        assert_eq!(exit_code(&permission), 3);
    }
}
