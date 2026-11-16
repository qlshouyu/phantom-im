use crate::*;
use serde::Deserialize;
use std::collections::BTreeMap;
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Smoke {
    a: Config,
    b: Config,
}
const STEPS: &[&str] = &[
    "login_ready",
    "send_receive",
    "idempotency",
    "history",
    "offline_send",
    "restart_restore",
];
pub async fn run(input: Value, dry_run: bool, execution: &mut Execution) -> SdkResult<Value> {
    let config: Smoke = serde_json::from_value(input)
        .map_err(|_| wire::invalid("smoke 配置必须包含 a/b 账号配置，且不能包含未知字段"))?;
    // 缺少前置条件的必需步骤全部 skipped，并保留输入根因与退出码。
    let preflight = (|| {
        config.a.validate(true)?;
        config.b.validate(true)?;
        if config.a.user_id == config.b.user_id || config.a.device_id == config.b.device_id {
            return Err(wire::invalid("smoke 需要不同账号和不同 device_id"));
        }
        if config.a.base_url != config.b.base_url || config.a.app_id != config.b.app_id {
            return Err(wire::invalid("smoke 两个账号必须使用同一平台地址和 app_id"));
        }
        Ok(())
    })();
    if let Err(error) = preflight {
        return Ok(
            json!({"passed":0,"failed":0,"skipped":STEPS.len(),"steps":STEPS.iter().map(|id| json!({"id":id,"status":"skipped","reason":"账号或环境前置配置不足"})).collect::<Vec<_>>(),"_exit_code":exit_code(&error),"_error":error}),
        );
    }
    if dry_run {
        return Ok(
            json!({"dry_run":true,"steps":STEPS,"storage":"本次测试专属临时 SQLite 目录","side_effects":["登录两个测试账号","发送两条测试消息","查询历史","重新创建接收端"],"accounts":[config.a.user_id,config.b.user_id]}),
        );
    }
    execution.partial_report = Some((
        STEPS.iter().map(|id| (*id).to_owned()).collect(),
        Vec::new(),
    ));
    let temporary = tempfile::tempdir()
        .map_err(|_| SdkError::new(ErrorKind::Storage, "无法创建本次 smoke 目录"))?;
    let mut a_config = config.a;
    let mut b_config = config.b;
    a_config.storage = config::Storage::Sqlite;
    b_config.storage = config::Storage::Sqlite;
    a_config.storage_dir = Some(temporary.path().join("a"));
    b_config.storage_dir = Some(temporary.path().join("b"));
    execution.temporary.push(temporary);
    let a = execution.client(&a_config)?;
    let b = execution.client(&b_config)?;
    let mut report = Vec::new();
    let mut root_code = 0;
    let started = Instant::now();
    let result: SdkResult<()> = async {
        let mut stage = Instant::now();
        a.login(a_config.login()?, execution.call()?).await?;
        b.login(b_config.login()?, execution.call()?).await?;
        a.wait_ready(execution.call()?).await?;
        b.wait_ready(execution.call()?).await?;
        // smoke 只观察本轮发送；历史同步完成后订阅，避免旧消息耗尽队列。
        let b_events = b.subscribe(SubscriptionOptions { capacity: 512 })?;
        report.push(passed("login_ready", stage));
        execution.partial_report = Some((
            STEPS.iter().map(|id| (*id).to_owned()).collect(),
            report.clone(),
        ));
        stage = Instant::now();
        let conv_id = single_conv_id(
            a_config.user_id.clone().unwrap(),
            b_config.user_id.clone().unwrap(),
        )?;
        let run = format!(
            "rust-smoke-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_err(|_| SdkError::new(ErrorKind::Storage, "系统时间无效"))?
                .as_nanos()
        );
        let input = SendMessageInput {
            conv_id: conv_id.clone(),
            conv_type: ConversationType::Single,
            content: MessageContent::Text(TextContent {
                text: format!("Phantom Rust smoke {run}"),
            }),
            reply_to_msg_id: None,
            mentions: Vec::new(),
            client_msg_id: Some(run.clone()),
        };
        let first = a.send_message(input.clone(), execution.call()?).await?;
        let received = receive(&b_events, &run, execution).await?;
        verify(
            first.msg_id.is_some()
                && first.msg_id == received.msg_id
                && first.conv_seq == received.conv_seq,
            "收发身份或序号不一致",
            &mut root_code,
        )?;
        report.push(passed("send_receive", stage));
        execution.partial_report = Some((
            STEPS.iter().map(|id| (*id).to_owned()).collect(),
            report.clone(),
        ));
        stage = Instant::now();
        let repeated = a.send_message(input, execution.call()?).await?;
        verify(
            first.msg_id == repeated.msg_id && first.conv_seq == repeated.conv_seq,
            "同一逻辑身份重复提交未保持幂等",
            &mut root_code,
        )?;
        let cached = b.get_messages(MessageCacheQuery {
            conv_id: conv_id.clone(),
            before_conv_seq: None,
            limit: 200,
        })?;
        verify(
            cached.iter().filter(|m| m.client_msg_id == run).count() == 1,
            "接收缓存中逻辑消息数量不为一",
            &mut root_code,
        )?;
        report.push(passed("idempotency", stage));
        execution.partial_report = Some((
            STEPS.iter().map(|id| (*id).to_owned()).collect(),
            report.clone(),
        ));
        stage = Instant::now();
        let history = b
            .history(
                HistoryQuery {
                    conv_id: conv_id.clone(),
                    before_conv_seq: None,
                    limit: 200,
                },
                execution.call()?,
            )
            .await?;
        verify(
            history
                .items
                .iter()
                .filter(|m| m.client_msg_id == run && m.msg_id == first.msg_id)
                .count()
                == 1,
            "HTTP 历史未找到唯一逻辑消息",
            &mut root_code,
        )?;
        b.flush_storage(execution.call()?).await?;
        report.push(passed("history", stage));
        execution.partial_report = Some((
            STEPS.iter().map(|id| (*id).to_owned()).collect(),
            report.clone(),
        ));
        stage = Instant::now();
        b_events.close();
        b.shutdown(execution.call()?).await?;
        let offline_id = format!("{run}-offline");
        let offline = a
            .send_message(
                SendMessageInput {
                    conv_id: conv_id.clone(),
                    conv_type: ConversationType::Single,
                    content: MessageContent::Text(TextContent {
                        text: format!("离线补齐 {run}"),
                    }),
                    reply_to_msg_id: None,
                    mentions: vec![],
                    client_msg_id: Some(offline_id.clone()),
                },
                execution.call()?,
            )
            .await?;
        report.push(passed("offline_send", stage));
        execution.partial_report = Some((
            STEPS.iter().map(|id| (*id).to_owned()).collect(),
            report.clone(),
        ));
        stage = Instant::now();
        let restarted = execution.client(&b_config)?;
        restarted
            .login(b_config.login()?, execution.call()?)
            .await?;
        // login 返回前已恢复同目录持久化快照；不依赖随后 HTTP history 写回来制造恢复证据。
        let restored = restarted.get_messages(MessageCacheQuery {
            conv_id: conv_id.clone(),
            before_conv_seq: None,
            limit: 200,
        })?;
        verify(
            restored.iter().any(|m| m.msg_id == first.msg_id),
            "重新创建实例后未恢复已 flush 的消息",
            &mut root_code,
        )?;
        restarted.wait_ready(execution.call()?).await?;
        let cached = restarted.get_messages(MessageCacheQuery {
            conv_id,
            before_conv_seq: None,
            limit: 200,
        })?;
        let counts: BTreeMap<_, _> = cached
            .iter()
            .map(|m| (m.client_msg_id.clone(), m.msg_id.clone()))
            .collect();
        verify(
            counts.get(&offline_id) == Some(&offline.msg_id),
            "重连未补齐离线期间消息",
            &mut root_code,
        )?;
        report.push(passed("restart_restore", stage));
        execution.partial_report = Some((
            STEPS.iter().map(|id| (*id).to_owned()).collect(),
            report.clone(),
        ));
        Ok(())
    }
    .await;
    let root = result.err();
    if let Some(error) = &root {
        if root_code == 0 {
            root_code = exit_code(error);
        }
        let index = report.len();
        report.push(json!({"id":STEPS[index.min(STEPS.len()-1)],"status":"failed","error":error}));
        for id in STEPS.iter().skip(index + 1) {
            report.push(json!({"id":id,"status":"skipped","reason":"前序必需步骤失败"}));
        }
    }
    let passed = report.iter().filter(|v| v["status"] == "passed").count();
    let failed = report.iter().filter(|v| v["status"] == "failed").count();
    Ok(
        json!({"passed":passed,"failed":failed,"skipped":report.len()-passed-failed,"steps":report,"elapsed_ms":started.elapsed().as_millis().to_string(),"coverage_note":"成功身份重复提交仅验证幂等；Failed/resend 与 full_resync 依赖受控测试","_exit_code":root_code,"_error":root}),
    )
}
fn passed(id: &str, start: Instant) -> Value {
    json!({"id":id,"status":"passed","elapsed_ms":start.elapsed().as_millis().to_string()})
}
fn verify(condition: bool, message: &str, code: &mut i32) -> SdkResult<()> {
    if condition {
        Ok(())
    } else {
        *code = 5;
        Err(assertion(message))
    }
}
async fn receive(
    subscription: &EventSubscription,
    id: &str,
    execution: &Execution,
) -> SdkResult<ChatMessage> {
    loop {
        let event = subscription
            .next(execution.call()?)
            .await?
            .ok_or_else(|| SdkError::new(ErrorKind::Released, "等待接收时订阅已结束"))?;
        if let EventKind::MessageChanged(message) = event.kind
            && message.client_msg_id == id
        {
            return Ok(message);
        }
    }
}
