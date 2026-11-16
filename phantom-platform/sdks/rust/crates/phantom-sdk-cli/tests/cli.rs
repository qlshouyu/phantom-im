use serde_json::{Value, json};
use std::{
    io::Write,
    process::{Command, Stdio},
};
fn cli(args: &[&str], input: Option<Value>) -> std::process::Output {
    let mut child = Command::new(env!("CARGO_BIN_EXE_phantom-sdk-cli"))
        .args(args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    if let Some(value) = input {
        child
            .stdin
            .take()
            .unwrap()
            .write_all(value.to_string().as_bytes())
            .unwrap();
    }
    child.wait_with_output().unwrap()
}
fn data(output: &std::process::Output) -> Value {
    serde_json::from_slice(&output.stdout).unwrap_or_else(|e| {
        panic!(
            "JSON 无效: {e}, stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        )
    })
}
#[test]
fn help_version_and_all_method_schemas_need_no_credentials() {
    assert!(cli(&["--help"], None).status.success());
    assert!(cli(&["--version"], None).status.success());
    let output = cli(&["schema"], None);
    assert!(output.status.success());
    let value = data(&output);
    let methods = value["data"]["methods"].as_array().unwrap();
    assert_eq!(methods.len(), 103);
    for method in methods {
        let output = cli(&["schema", method.as_str().unwrap()], None);
        assert!(output.status.success(), "{method}");
        let value = data(&output);
        assert!(value["data"]["input"].is_object());
        assert!(value["data"]["result"].is_object() || value["data"]["result"].is_boolean());
    }
    let value = data(&cli(&["schema", "mark_read"], None));
    assert_eq!(
        value["data"]["input"]["properties"]["read_conv_seq"]["type"],
        "string"
    );
}
#[test]
fn helper_runs_without_sdk_url_or_login_and_rejects_unknown_fields() {
    let output = cli(&["call", "single_conv_id"], Some(json!({"a":"b","b":"a"})));
    assert_eq!(output.status.code(), Some(0));
    assert_eq!(data(&output)["data"], "si_a__b");
    let output = cli(
        &["call", "single_conv_id"],
        Some(json!({"a":"b","b":"a","extra":true})),
    );
    assert_eq!(output.status.code(), Some(2));
    let output = cli(
        &["call", "single_conv_id", "--dry-run"],
        Some(json!({"a":"","b":"a"})),
    );
    assert_eq!(output.status.code(), Some(2));
}
#[test]
fn dry_run_checks_large_integer_and_binary_without_reading_token_media_or_storage() {
    let directory = tempfile::tempdir().unwrap();
    let absent = directory.path().join("must-not-create");
    let token = directory.path().join("missing-token");
    let base = [
        "--base-url",
        "https://127.0.0.1:1/prefix",
        "--app-id",
        "app",
        "--user-id",
        "user",
        "--device-id",
        "device",
        "--token-file",
        token.to_str().unwrap(),
        "--storage-dir",
        absent.to_str().unwrap(),
        "call",
    ];
    let mut args = base.to_vec();
    args.extend(["mark_read", "--dry-run"]);
    let output = cli(
        &args,
        Some(json!({"conv_id":"si_a__b","read_conv_seq":"9007199254740993"})),
    );
    assert_eq!(output.status.code(), Some(0), "{}", data(&output));
    assert!(!absent.exists());
    for number in [
        json!(9007199254740993u64),
        json!("9223372036854775808"),
        json!("1e3"),
        json!("01"),
    ] {
        let output = cli(
            &args,
            Some(json!({"conv_id":"si_a__b","read_conv_seq":number})),
        );
        assert_eq!(output.status.code(), Some(2));
    }
    let mut args = base.to_vec();
    args.extend(["upload_part", "--dry-run"]);
    let output = cli(
        &args,
        Some(json!({"input":{"upload_id":"upload","part_number":1,"bytes":"AP8NCg=="}})),
    );
    assert_eq!(output.status.code(), Some(0), "{}", data(&output));
    assert!(!absent.exists());
    let output = cli(
        &args,
        Some(json!({"input":{"upload_id":"upload","part_number":1,"bytes":[0,255]}})),
    );
    assert_eq!(output.status.code(), Some(2));
}
#[test]
fn scenario_has_no_tty_dependency_and_assertion_failure_keeps_report_and_exit_code() {
    let scenario = json!({"steps":[
        {"op":"client/new","id":"c","config":{"base_url":"https://127.0.0.1:1","app_id":"app","storage":"memory"}},
        {"op":"call","id":"state","client":"c","method":"get_state","params":{}},
        {"op":"assert","id":"check","actual":{"$ref":"state#/data"},"equals":"Online"},
        {"op":"shutdown","id":"end","client":"c"}
    ]});
    let output = cli(&["scenario", "-"], Some(scenario.clone()));
    assert_eq!(output.status.code(), Some(5), "{}", data(&output));
    let value = data(&output);
    assert_eq!(value["ok"], false);
    assert_eq!(value["data"]["passed"], 2);
    assert_eq!(value["data"]["failed"], 1);
    assert_eq!(value["data"]["skipped"], 1);
    assert!(value["error"]["server_code"].is_null());
    let output = cli(&["scenario", "-", "--dry-run"], Some(scenario));
    assert_eq!(output.status.code(), Some(0), "{}", data(&output));
}
#[test]
fn invalid_references_and_storage_conflicts_are_rejected_before_sdk_creation() {
    let output = cli(
        &[
            "--storage",
            "memory",
            "--storage-dir",
            "/missing",
            "call",
            "new",
            "--dry-run",
        ],
        Some(json!({})),
    );
    assert_eq!(output.status.code(), Some(2));
    let output = cli(
        &["scenario", "-", "--dry-run"],
        Some(
            json!({"steps":[{"op":"assert","id":"check","actual":{"$ref":"later#/data"},"equals":1}]}),
        ),
    );
    assert_eq!(output.status.code(), Some(2));
    let output = cli(&["call", "no_such_method", "--dry-run"], Some(json!({})));
    assert_eq!(output.status.code(), Some(2));
    let output = cli(&["listen"], None);
    assert_eq!(output.status.code(), Some(2));
}
#[test]
fn smoke_missing_accounts_are_skipped_with_nonzero_input_exit() {
    let output = cli(&["smoke", "--config", "-"], Some(json!({"a":{},"b":{}})));
    assert_eq!(output.status.code(), Some(2));
    let value = data(&output);
    assert_eq!(value["data"]["passed"], 0);
    assert_eq!(value["data"]["skipped"], 6);
}

#[test]
fn scenario_preflight_requires_login_references_and_joined_background_results() {
    let new = json!({"op":"client/new","id":"c","config":{"base_url":"https://127.0.0.1:1","app_id":"app","storage":"memory"}});
    let output = cli(
        &["scenario", "-", "--dry-run"],
        Some(json!({"steps":[new.clone(),
            {"op":"call","id":"login","client":"c","method":"login","params":{}}
        ]})),
    );
    assert_eq!(output.status.code(), Some(2));
    let background = json!({"op":"call","id":"work","client":"c","method":"wait_ready","params":{},"background":true,"cancellation":"stop"});
    let output = cli(
        &["scenario", "-", "--dry-run"],
        Some(json!({"steps":[new.clone(),background.clone()]})),
    );
    assert_eq!(output.status.code(), Some(2));
    let output = cli(
        &["scenario", "-", "--dry-run"],
        Some(json!({"steps":[new.clone(),background.clone(),
            {"op":"assert","id":"early","actual":{"$ref":"work#/ok"},"equals":false},
            {"op":"cancel","id":"cancel","cancellation":"stop"}
        ]})),
    );
    assert_eq!(output.status.code(), Some(2));
    let output = cli(
        &["scenario", "-"],
        Some(json!({"steps":[new,background,
            {"op":"cancel","id":"cancel","cancellation":"stop"},
            {"op":"assert","id":"settled","actual":{"$ref":"work#/ok"},"equals":false},
            {"op":"shutdown","id":"done","client":"c"}
        ]})),
    );
    assert_eq!(output.status.code(), Some(0), "{}", data(&output));
    assert_eq!(data(&output)["data"]["results"]["work"]["ok"], false);
}

#[test]
fn all_shipped_domain_scenarios_pass_schema_and_dry_run_without_credentials_io() {
    for contents in [
        include_str!("../../../scenarios/relations.json"),
        include_str!("../../../scenarios/groups.json"),
        include_str!("../../../scenarios/media.json"),
        include_str!("../../../scenarios/devices.json"),
        include_str!("../../../scenarios/search.json"),
        include_str!("../../../scenarios/compliance.json"),
    ] {
        let output = cli(
            &["scenario", "-", "--dry-run"],
            Some(serde_json::from_str(contents).unwrap()),
        );
        assert_eq!(output.status.code(), Some(0), "{}", data(&output));
        assert_eq!(data(&output)["data"]["dry_run"], true);
    }
}

#[test]
fn announcement_schema_exposes_read_receipt_side_effect() {
    let output = cli(&["schema", "get_announcement"], None);
    assert_eq!(
        data(&output)["data"]["side_effect"],
        "marks_announcement_read"
    );
}

#[test]
fn nullable_request_id_reference_can_feed_group_approval_without_reading_credentials() {
    let directory = tempfile::tempdir().unwrap();
    let cache = directory.path().join("absent-cache");
    let token = directory.path().join("missing-token");
    let scenario = json!({"steps":[
        {"op":"client/new","id":"c","config":{
            "base_url":"https://127.0.0.1:1","app_id":"app","user_id":"user",
            "device_id":"device","token_file":token,"storage_dir":cache
        }},
        {"op":"call","id":"join","client":"c","method":"join_group_by_invite",
            "params":{"token":"test-invite"}},
        {"op":"call","id":"approve","client":"c","method":"handle_group_join_request",
            "params":{"group_id":"gr_test","request_id":{"$ref":"join#/data/request_id"},"accept":true}},
        {"op":"shutdown","id":"done","client":"c"}
    ]});
    let output = cli(&["scenario", "-", "--dry-run"], Some(scenario));
    assert_eq!(output.status.code(), Some(0), "{}", data(&output));
    assert!(!token.exists());
    assert!(!cache.exists());
    let output = cli(
        &["call", "handle_group_join_request", "--dry-run"],
        Some(json!({"group_id":"gr_test","request_id":null,"accept":true})),
    );
    assert_eq!(output.status.code(), Some(2), "{}", data(&output));
}
