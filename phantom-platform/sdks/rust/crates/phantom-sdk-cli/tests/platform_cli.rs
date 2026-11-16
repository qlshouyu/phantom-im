#[path = "support/platform.rs"]
mod platform;
use serde_json::{Value, json};
use std::{
    io::{BufRead, BufReader, Write},
    process::{Command, Stdio},
    sync::atomic::Ordering,
};
fn config(base: &str, user: &str, root: &std::path::Path) -> Value {
    let token = root.join(format!("token-{user}"));
    std::fs::write(&token, format!("token-{user}")).unwrap();
    json!({"base_url":base,"app_id":"app","user_id":user,"device_id":format!("device-{user}"),"token_file":token,"storage":"memory"})
}
async fn invoke(args: Vec<String>, input: Value) -> std::process::Output {
    tokio::task::spawn_blocking(move || {
        let mut command = Command::new(env!("CARGO_BIN_EXE_phantom-sdk-cli"));
        if let Some(path) = input.pointer("/a/token_file").and_then(Value::as_str) {
            let temporary = std::path::Path::new(path)
                .parent()
                .unwrap()
                .join("owned-temporary");
            std::fs::create_dir_all(&temporary).unwrap();
            command.env("TMPDIR", temporary);
        }
        let mut child = command
            .args(args)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        child
            .stdin
            .take()
            .unwrap()
            .write_all(input.to_string().as_bytes())
            .unwrap();
        child.wait_with_output().unwrap()
    })
    .await
    .unwrap()
}
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn smoke_runs_real_cli_process_through_controlled_http_ws_and_sqlite_restart() {
    let server = platform::Platform::start().await;
    let root = tempfile::tempdir().unwrap();
    let sentinel = root.path().join("user-cache");
    std::fs::create_dir(&sentinel).unwrap();
    std::fs::write(sentinel.join("keep"), "用户资源").unwrap();
    let mut a = config(&server.base, "a", root.path());
    let mut b = config(&server.base, "b", root.path());
    a["storage_dir"] = json!(sentinel);
    b["storage_dir"] = json!(sentinel);
    a["storage"] = json!("sqlite");
    b["storage"] = json!("sqlite");
    let output = invoke(
        vec![
            "--timeout-ms".into(),
            "10000".into(),
            "smoke".into(),
            "--config".into(),
            "-".into(),
        ],
        json!({"a":a,"b":b}),
    )
    .await;
    let report: Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(
        output.status.code(),
        Some(0),
        "{report}; {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_eq!(report["data"]["passed"], 6);
    assert_eq!(report["data"]["failed"], 0);
    assert_eq!(report["data"]["skipped"], 0);
    assert_eq!(
        std::fs::read_to_string(sentinel.join("keep")).unwrap(),
        "用户资源"
    );
    assert_eq!(std::fs::read_dir(&sentinel).unwrap().count(), 1);
    assert!(server.state.logouts.load(Ordering::Acquire) >= 3);
    assert!(server.state.http.load(Ordering::Acquire) > 0);
    assert_eq!(
        std::fs::read_dir(root.path().join("owned-temporary"))
            .unwrap()
            .count(),
        0
    );
}
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn listen_limits_and_sigint_produce_ndjson_summary_and_release_owned_session() {
    let server = platform::Platform::start().await;
    let root = tempfile::tempdir().unwrap();
    let cfg = config(&server.base, "a", root.path());
    let prefix = vec![
        "--base-url".to_string(),
        server.base.clone(),
        "--app-id".into(),
        "app".into(),
        "--user-id".into(),
        "a".into(),
        "--device-id".into(),
        "listen-device".into(),
        "--token-file".into(),
        cfg["token_file"].as_str().unwrap().into(),
        "--storage".into(),
        "memory".into(),
    ];
    for suffix in [
        vec!["listen", "--duration-ms", "100"],
        vec!["listen", "--max-events", "1"],
    ] {
        let mut args = prefix.clone();
        args.extend(suffix.into_iter().map(str::to_owned));
        let output = invoke(args, json!({})).await;
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stdout)
        );
        let lines: Vec<Value> = String::from_utf8(output.stdout)
            .unwrap()
            .lines()
            .map(|l| serde_json::from_str(l).unwrap())
            .collect();
        assert!(lines.iter().any(|v| v["type"] == "event"));
        assert_eq!(lines.last().unwrap()["type"], "summary");
        assert_eq!(lines.last().unwrap()["ok"], true);
    }
    let mut args = prefix;
    args.extend(["listen".into(), "--follow".into()]);
    let output = tokio::task::spawn_blocking(move || {
        let mut child = Command::new(env!("CARGO_BIN_EXE_phantom-sdk-cli"))
            .args(args)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        let mut reader = BufReader::new(child.stdout.take().unwrap());
        let mut first = String::new();
        reader.read_line(&mut first).unwrap();
        assert_eq!(
            serde_json::from_str::<Value>(&first).unwrap()["type"],
            "event"
        );
        assert!(
            Command::new("kill")
                .args(["-INT", &child.id().to_string()])
                .status()
                .unwrap()
                .success()
        );
        let mut rest = String::new();
        std::io::Read::read_to_string(&mut reader, &mut rest).unwrap();
        let status = child.wait().unwrap();
        (status, rest)
    })
    .await
    .unwrap();
    assert_eq!(output.0.code(), Some(130));
    let lines: Vec<Value> = output
        .1
        .lines()
        .map(|l| serde_json::from_str(l).unwrap())
        .collect();
    assert_eq!(lines.last().unwrap()["type"], "summary");
    assert_eq!(lines.last().unwrap()["ok"], false);
    assert!(server.state.logouts.load(Ordering::Acquire) >= 3);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn call_preserves_persistent_cache_across_processes_and_auth_storage_failures_have_stable_exits()
 {
    let server = platform::Platform::start().await;
    let root = tempfile::tempdir().unwrap();
    let cfg = config(&server.base, "a", root.path());
    let prefix = vec![
        "--base-url".to_string(),
        server.base.clone(),
        "--app-id".into(),
        "app".into(),
        "--user-id".into(),
        "a".into(),
        "--device-id".into(),
        "device-a".into(),
        "--token-file".into(),
        cfg["token_file"].as_str().unwrap().into(),
        "--storage-dir".into(),
        root.path().join("cache").to_str().unwrap().into(),
    ];
    let message = json!({"input":{"conv_id":"si_a__b","conv_type":"Single","content":{"Text":{"text":"进程间缓存"}},"reply_to_msg_id":null,"mentions":[],"client_msg_id":"stable-process-message"}});
    let mut first_id = Value::Null;
    for _ in 0..2 {
        let mut args = prefix.clone();
        args.extend(["call".into(), "send_message".into()]);
        let output = invoke(args, message.clone()).await;
        let report: Value = serde_json::from_slice(&output.stdout).unwrap();
        assert!(output.status.success(), "{report}");
        if first_id.is_null() {
            first_id = report["data"]["msg_id"].clone();
        } else {
            assert_eq!(report["data"]["msg_id"], first_id);
        }
    }
    let mut args = prefix.clone();
    args.extend(["call".into(), "get_messages".into()]);
    let output = invoke(
        args,
        json!({"query":{"conv_id":"si_a__b","before_conv_seq":null,"limit":100}}),
    )
    .await;
    let report: Value = serde_json::from_slice(&output.stdout).unwrap();
    assert!(output.status.success(), "{report}");
    assert_eq!(report["data"].as_array().unwrap().len(), 1);
    assert_eq!(report["data"][0]["conv_seq"], "1");
    std::fs::write(cfg["token_file"].as_str().unwrap(), "bad-token-private").unwrap();
    let mut args = prefix.clone();
    args.extend(["call".into(), "list_friends".into()]);
    let output = invoke(args, json!({})).await;
    assert_eq!(output.status.code(), Some(3));
    assert!(!String::from_utf8_lossy(&output.stdout).contains("bad-token-private"));
    std::fs::write(cfg["token_file"].as_str().unwrap(), "token-a").unwrap();
    let blocked = root.path().join("file-is-not-directory");
    std::fs::write(&blocked, "keep").unwrap();
    let mut args = prefix;
    *args.last_mut().unwrap() = blocked.to_str().unwrap().into();
    args.extend(["call".into(), "list_friends".into()]);
    let output = invoke(args, json!({})).await;
    assert_eq!(
        output.status.code(),
        Some(6),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    assert_eq!(std::fs::read_to_string(blocked).unwrap(), "keep");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn smoke_overall_timeout_preserves_failed_and_skipped_report() {
    let server = platform::Platform::start().await;
    server.state.block_auth.store(true, Ordering::Release);
    let root = tempfile::tempdir().unwrap();
    let output = invoke(
        vec![
            "--timeout-ms".into(),
            "100".into(),
            "smoke".into(),
            "--config".into(),
            "-".into(),
        ],
        json!({"a":config(&server.base,"a",root.path()),"b":config(&server.base,"b",root.path())}),
    )
    .await;
    let report: Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(output.status.code(), Some(4), "{report}");
    assert_eq!(report["data"]["passed"], 0);
    assert_eq!(report["data"]["failed"], 1);
    assert_eq!(report["data"]["skipped"], 5);
    assert_eq!(report["error"]["kind"], "Timeout");
    assert_eq!(
        std::fs::read_dir(root.path().join("owned-temporary"))
            .unwrap()
            .count(),
        0
    );
}
