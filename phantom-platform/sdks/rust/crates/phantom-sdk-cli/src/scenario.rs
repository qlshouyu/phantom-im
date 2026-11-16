use crate::*;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Scenario {
    pub steps: Vec<Step>,
}
#[derive(Deserialize, Serialize, JsonSchema)]
#[serde(tag = "op", deny_unknown_fields)]
pub enum Step {
    #[serde(rename = "client/new")]
    New { id: String, config: Config },
    #[serde(rename = "subscribe")]
    Subscribe {
        id: String,
        client: String,
        capacity: u32,
    },
    #[serde(rename = "call")]
    Call {
        id: String,
        client: String,
        method: String,
        params: Value,
        #[serde(default)]
        background: bool,
        cancellation: Option<String>,
    },
    #[serde(rename = "wait_event")]
    Wait {
        id: String,
        subscription: String,
        pointer: String,
        equals: Value,
    },
    #[serde(rename = "assert")]
    Assert {
        id: String,
        actual: Value,
        equals: Value,
    },
    #[serde(rename = "cancel")]
    Cancel { id: String, cancellation: String },
    #[serde(rename = "close_subscription")]
    Close { id: String, subscription: String },
    #[serde(rename = "shutdown")]
    Shutdown { id: String, client: String },
}
impl Step {
    fn id(&self) -> &str {
        match self {
            Self::New { id, .. }
            | Self::Subscribe { id, .. }
            | Self::Call { id, .. }
            | Self::Wait { id, .. }
            | Self::Assert { id, .. }
            | Self::Cancel { id, .. }
            | Self::Close { id, .. }
            | Self::Shutdown { id, .. } => id,
        }
    }
}
fn require(ids: &BTreeSet<String>, id: &str, kind: &str) -> SdkResult<()> {
    if ids.contains(id) {
        Ok(())
    } else {
        Err(wire::invalid(format!("未定义的 {kind}: {id}")))
    }
}
fn ref_parts(reference: &str) -> SdkResult<(&str, &str)> {
    let (id, pointer) = reference
        .split_once('#')
        .ok_or_else(|| wire::invalid("引用必须为 步骤ID#/JSON/pointer"))?;
    validate_id(id, "引用步骤")?;
    if !pointer.is_empty() && !pointer.starts_with('/') {
        return Err(wire::invalid("引用必须使用 JSON Pointer"));
    }
    Ok((id, pointer))
}
fn schema_pointer<'a>(schema: &'a Value, pointer: &str, root: &'a Value) -> SdkResult<&'a Value> {
    fn descend<'a>(
        node: &'a Value,
        parts: &[String],
        root: &'a Value,
        depth: usize,
    ) -> Option<&'a Value> {
        if depth > 64 {
            return None;
        }
        if let Some(reference) = node["$ref"].as_str() {
            return descend(
                root.pointer(reference.strip_prefix('#')?)?,
                parts,
                root,
                depth + 1,
            );
        }
        if parts.is_empty() {
            return Some(node);
        }
        if let Some(branches) = node["anyOf"]
            .as_array()
            .or_else(|| node["oneOf"].as_array())
        {
            return branches
                .iter()
                .find_map(|branch| descend(branch, parts, root, depth + 1));
        }
        let part = &parts[0];
        let child = node["properties"].get(part).or_else(|| {
            if node["type"] == "array" && part.parse::<usize>().is_ok() {
                node.get("items")
            } else if node["additionalProperties"].is_object() {
                node.get("additionalProperties")
            } else {
                None
            }
        })?;
        descend(child, &parts[1..], root, depth + 1)
    }
    let parts: Vec<_> = pointer
        .split('/')
        .skip(1)
        .map(|p| p.replace("~1", "/").replace("~0", "~"))
        .collect();
    descend(schema, &parts, root, 0)
        .ok_or_else(|| wire::invalid(format!("引用字段不存在: {pointer}")))
}
fn sample(schema: &Value, root: &Value, depth: usize) -> SdkResult<Value> {
    if depth > 20 {
        return Ok(Value::Null);
    }
    if let Some(reference) = schema["$ref"].as_str() {
        return sample(
            root.pointer(reference.trim_start_matches('#'))
                .ok_or_else(|| wire::invalid("类型引用无效"))?,
            root,
            depth + 1,
        );
    }
    if let Some(branches) = schema["anyOf"]
        .as_array()
        .or_else(|| schema["oneOf"].as_array())
    {
        return sample(
            branches
                .iter()
                .find(|b| b["type"] != "null")
                .unwrap_or(&branches[0]),
            root,
            depth + 1,
        );
    }
    if let Some(value) = schema.get("const") {
        return Ok(value.clone());
    }
    if let Some(values) = schema["enum"].as_array() {
        return Ok(values[0].clone());
    }
    if let Some(types) = schema["type"].as_array()
        && let Some(kind) = types.iter().find(|kind| kind.as_str() != Some("null"))
    {
        // 可空引用的实际值在执行时校验；预检用非空分支检查后续参数形状。
        let mut concrete = schema.clone();
        concrete["type"] = kind.clone();
        return sample(&concrete, root, depth + 1);
    }
    Ok(match schema["type"].as_str() {
        Some("string") => {
            if matches!(schema["format"].as_str(), Some("int64" | "uint64")) {
                json!("1")
            } else {
                json!("reference")
            }
        }
        Some("integer" | "number") => json!(1),
        Some("boolean") => json!(true),
        Some("array") => json!([]),
        Some("object") => {
            let mut object = serde_json::Map::new();
            if let Some(properties) = schema["properties"].as_object() {
                for (k, v) in properties {
                    object.insert(k.clone(), sample(v, root, depth + 1)?);
                }
            }
            Value::Object(object)
        }
        _ => Value::Null,
    })
}
fn references(
    value: &Value,
    schemas: &BTreeMap<String, Value>,
    results: Option<&BTreeMap<String, Value>>,
) -> SdkResult<Value> {
    match value {
        Value::Object(object) if object.contains_key("$ref") => {
            if object.len() != 1 {
                return Err(wire::invalid("引用对象不能包含其他字段"));
            }
            let (id, pointer) = ref_parts(
                object["$ref"]
                    .as_str()
                    .ok_or_else(|| wire::invalid("$ref 必须为字符串"))?,
            )?;
            let schema = schemas
                .get(id)
                .ok_or_else(|| wire::invalid("引用必须指向已完成的前序步骤"))?;
            let shape = schema_pointer(schema, pointer, schema)?;
            if let Some(results) = results {
                return results
                    .get(id)
                    .and_then(|v| v.pointer(pointer))
                    .cloned()
                    .ok_or_else(|| wire::invalid("运行结果中不存在引用字段"));
            }
            sample(shape, schema, 0)
        }
        Value::Object(object) => object
            .iter()
            .map(|(k, v)| Ok((k.clone(), references(v, schemas, results)?)))
            .collect::<SdkResult<serde_json::Map<_, _>>>()
            .map(Value::Object),
        Value::Array(values) => values
            .iter()
            .map(|v| references(v, schemas, results))
            .collect::<SdkResult<Vec<_>>>()
            .map(Value::Array),
        _ => Ok(value.clone()),
    }
}
fn result_schema(data: Value) -> Value {
    json!({"type":"object","properties":{"ok":{"type":"boolean"},"data":data,"error":inline_at(wire::schema::<SdkError>(), "error")},"$defs":{}})
}
fn inline_root(schema: Value) -> Value {
    inline_at(schema, "data")
}
fn inline_at(mut schema: Value, property: &str) -> Value {
    // 结果嵌入 envelope 前将内部 $ref 改成相对嵌入位置；根 definitions 随其类型保留。
    fn fix(v: &mut Value, property: &str) {
        match v {
            Value::Object(map) => {
                for (k, v) in map {
                    if k == "$ref" {
                        if let Some(s) = v.as_str() {
                            *v = json!(s.replacen(
                                "#/$defs/",
                                &format!("#/properties/{property}/$defs/"),
                                1
                            ));
                        }
                    } else {
                        fix(v, property);
                    }
                }
            }
            Value::Array(values) => {
                for v in values {
                    fix(v, property);
                }
            }
            _ => {}
        }
    }
    fix(&mut schema, property);
    schema
}
fn preflight(scenario: &Scenario) -> SdkResult<(Vec<Value>, BTreeMap<String, Value>)> {
    if scenario.steps.is_empty() || scenario.steps.len() > 1000 {
        return Err(wire::invalid("场景步骤数量必须为 1–1000"));
    }
    let mut clients = BTreeSet::new();
    let mut configs = BTreeMap::new();
    let mut background_steps = BTreeMap::<String, String>::new();
    let mut subscriptions = BTreeSet::new();
    let mut tokens = BTreeSet::new();
    let mut ids = BTreeSet::new();
    let mut schemas = BTreeMap::new();
    let mut preview = Vec::new();
    for step in &scenario.steps {
        validate_id(step.id(), "step_id")?;
        if !ids.insert(step.id().to_owned()) {
            return Err(wire::invalid("场景步骤 ID 重复"));
        }
        let mut schema = result_schema(json!({}));
        let mut effect = "local";
        match step {
            Step::New { id, config } => {
                config.validate(false)?;
                if config.base_url.is_none() || config.app_id.is_none() {
                    return Err(wire::invalid("client/new 需要 base_url 和 app_id"));
                }
                clients.insert(id.clone());
                configs.insert(id.clone(), config);
            }
            Step::Subscribe {
                id,
                client,
                capacity,
            } => {
                require(&clients, client, "实例")?;
                if *capacity == 0 || *capacity > 65536 {
                    return Err(wire::invalid("订阅容量无效"));
                }
                subscriptions.insert(id.clone());
            }
            Step::Call {
                client,
                method,
                params,
                background,
                cancellation,
                ..
            } => {
                require(&clients, client, "实例")?;
                if method == "login" {
                    configs[client].validate(true)?;
                }
                let params = references(params, &schemas, None)?;
                methods::validate(method, params)?;
                if let Some(token) = cancellation {
                    validate_id(token, "cancellation")?;
                    tokens.insert(token.clone());
                }
                if *background && cancellation.is_none() {
                    return Err(wire::invalid("后台调用必须指定命名 cancellation"));
                }
                schema = result_schema(inline_root(methods::describe(method)?["result"].clone()));
                if *background {
                    background_steps.insert(step.id().to_owned(), cancellation.clone().unwrap());
                }
                effect = methods::effect(method);
            }
            Step::Wait {
                subscription,
                pointer,
                equals,
                ..
            } => {
                require(&subscriptions, subscription, "订阅")?;
                if !pointer.is_empty() && !pointer.starts_with('/') {
                    return Err(wire::invalid("事件匹配使用 JSON Pointer"));
                }
                references(equals, &schemas, None)?;
                schema = result_schema(inline_root(wire::schema::<SdkEvent>()));
            }
            Step::Assert { actual, equals, .. } => {
                references(actual, &schemas, None)?;
                references(equals, &schemas, None)?;
            }
            Step::Cancel { cancellation, .. } => {
                require(&tokens, cancellation, "取消令牌")?;
                for (id, token) in &background_steps {
                    if token == cancellation {
                        let source = scenario
                            .steps
                            .iter()
                            .find_map(|step| match step {
                                Step::Call {
                                    id: call_id,
                                    method,
                                    ..
                                } if call_id == id => Some(method),
                                _ => None,
                            })
                            .unwrap();
                        schemas.insert(
                            id.clone(),
                            result_schema(inline_root(
                                methods::describe(source)?["result"].clone(),
                            )),
                        );
                    }
                }
                background_steps.retain(|_, token| token != cancellation);
            }
            Step::Close { subscription, .. } => {
                require(&subscriptions, subscription, "订阅")?;
                subscriptions.remove(subscription);
            }
            Step::Shutdown { client, .. } => {
                require(&clients, client, "实例")?;
                effect = "session";
            }
        }
        if !background_steps.contains_key(step.id()) {
            schemas.insert(step.id().to_owned(), schema);
        }
        let mut step_value =
            serde_json::to_value(step).map_err(|_| wire::invalid("步骤无法序列化"))?;
        redact(&mut step_value);
        preview.push(json!({"step":step_value,"side_effect":effect,"dynamic_values":"引用仅验证结构，实际值在执行时验证"}));
    }
    if !background_steps.is_empty() {
        return Err(wire::invalid(
            "后台调用必须在场景结束前通过 cancel 步骤等待完成",
        ));
    }
    Ok((preview, schemas))
}
struct Pending {
    id: String,
    cancellation: String,
    task: tokio::task::JoinHandle<SdkResult<Value>>,
}
impl Drop for Pending {
    fn drop(&mut self) {
        self.task.abort();
    }
}
pub async fn run(input: Value, dry_run: bool, execution: &mut Execution) -> SdkResult<Value> {
    let scenario: Scenario =
        serde_json::from_value(input).map_err(|_| wire::invalid("场景结构无效或包含未知字段"))?;
    let (preview, schemas) = preflight(&scenario)?;
    if dry_run {
        return Ok(json!({"dry_run":true,"steps":preview}));
    }
    let ids: Vec<String> = scenario
        .steps
        .iter()
        .map(|step| step.id().to_owned())
        .collect();
    execution.partial_report = Some((ids.clone(), Vec::new()));
    let mut clients = BTreeMap::<String, (PhantomClient, Config)>::new();
    let mut subscriptions = BTreeMap::<String, EventSubscription>::new();
    let mut tokens = BTreeMap::<String, CancellationToken>::new();
    let mut results = BTreeMap::<String, Value>::new();
    let mut pending = Vec::<Pending>::new();
    let mut report = Vec::new();
    let mut root = None;
    let mut root_code = 0;
    for step in scenario.steps {
        let id = step.id().to_owned();
        let started = Instant::now();
        if root.is_some() {
            report.push(json!({"id":id,"status":"skipped","reason":"前序必需步骤失败"}));
            continue;
        }
        execution.partial_report = Some((ids.clone(), report.clone()));
        let assertion_step = matches!(step, Step::Assert { .. });
        let result: SdkResult<Value> = async {
            Ok(match step {
                Step::New { config, .. } => {
                    let client = execution.client(&config)?;
                    clients.insert(id.clone(), (client, config));
                    Value::Null
                }
                Step::Subscribe {
                    client, capacity, ..
                } => {
                    let subscription = clients[&client]
                        .0
                        .subscribe(SubscriptionOptions { capacity })?;
                    subscriptions.insert(id.clone(), subscription);
                    Value::Null
                }
                Step::Call {
                    client,
                    method,
                    params,
                    background,
                    cancellation,
                    ..
                } => {
                    let params = references(&params, &schemas, Some(&results))?;
                    methods::validate(&method, params.clone())?;
                    let (client, config) = clients[&client].clone();
                    let mut call = execution.call()?;
                    if let Some(name) = &cancellation {
                        call.cancellation = Some(tokens.entry(name.clone()).or_default().clone());
                    }
                    if background {
                        let task = tokio::spawn(async move {
                            methods::execute(&client, &config, &method, params, call).await
                        });
                        pending.push(Pending {
                            id: id.clone(),
                            cancellation: cancellation.unwrap(),
                            task,
                        });
                        json!({"pending":true})
                    } else {
                        methods::execute(&client, &config, &method, params, call).await?
                    }
                }
                Step::Wait {
                    subscription,
                    pointer,
                    equals,
                    ..
                } => {
                    let expected = references(&equals, &schemas, Some(&results))?;
                    loop {
                        let event = subscriptions[&subscription]
                            .next(execution.call()?)
                            .await?
                            .ok_or_else(|| SdkError::new(ErrorKind::Released, "事件订阅已结束"))?;
                        let event = wire::encode(&event)?;
                        if event.pointer(&pointer) == Some(&expected) {
                            break event;
                        }
                    }
                }
                Step::Assert { actual, equals, .. } => {
                    let actual = references(&actual, &schemas, Some(&results))?;
                    let expected = references(&equals, &schemas, Some(&results))?;
                    if actual != expected {
                        return Err(assertion(format!("步骤 {id} 断言不满足")));
                    }
                    json!({"matched":true})
                }
                Step::Cancel { cancellation, .. } => {
                    tokens.entry(cancellation.clone()).or_default().cancel();
                    for pending in pending
                        .iter_mut()
                        .filter(|p| p.cancellation == cancellation)
                    {
                        let result = (&mut pending.task).await.map_err(|_| {
                            SdkError::new(ErrorKind::Storage, "后台 SDK 调用任务失败")
                        })?;
                        results.insert(pending.id.clone(), envelope(result));
                    }
                    pending.retain(|p| p.cancellation != cancellation);
                    Value::Null
                }
                Step::Close { subscription, .. } => {
                    subscriptions.remove(&subscription).unwrap().close();
                    Value::Null
                }
                Step::Shutdown { client, .. } => {
                    clients[&client].0.shutdown(execution.call()?).await?;
                    Value::Null
                }
            })
        }
        .await;
        match result {
            Ok(data) => {
                results.insert(id.clone(), envelope(Ok(data.clone())));
                report.push(json!({"id":id,"status":"passed","data":data,"elapsed_ms":started.elapsed().as_millis().to_string()}));
            }
            Err(error) => {
                root_code = if assertion_step { 5 } else { exit_code(&error) };
                root = Some(error.clone());
                results.insert(id.clone(), envelope(Err(error.clone())));
                report.push(json!({"id":id,"status":"failed","error":error,"elapsed_ms":started.elapsed().as_millis().to_string()}));
            }
        }
    }
    execution.partial_report = Some((ids.clone(), report.clone()));
    for token in tokens.values() {
        token.cancel();
    }
    for p in pending.iter_mut() {
        let _ = (&mut p.task).await;
    }
    for subscription in subscriptions.values() {
        subscription.close();
    }
    let passed = report.iter().filter(|v| v["status"] == "passed").count();
    let failed = report.iter().filter(|v| v["status"] == "failed").count();
    let skipped = report.len() - passed - failed;
    Ok(
        json!({"passed":passed,"failed":failed,"skipped":skipped,"steps":report,"results":results,"_exit_code":root_code,"_error":root}),
    )
}
