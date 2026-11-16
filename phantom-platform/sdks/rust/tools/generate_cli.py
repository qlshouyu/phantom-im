#!/usr/bin/env python3
"""由 Facade 公开签名生成 CLI 类型与分派；生成结果提交仓库，普通构建无需 Python。"""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
methods = []
# 接口表是评审基线，生成结果对公开 Facade 编译，签名变更会立即产生编译错误。
import html
for signature in re.findall(r'<tr><td><code>(.*?)</code></td>',(root.parents[2]/'docs/phantom-platform/sdk/integration.html').read_text()):
    signature = html.unescape(signature)
    match = re.fullmatch(r'(async )?fn (\w+)\((.*?)\) -> SdkResult<(.+)>',signature)
    if not match: continue
    asynchronous,name,params,result=match.groups()
    fields = re.findall(r'(\w+): (.+?)(?:, (?=\w+:)|$)',params.replace('&self, ','').replace('&self',''))
    fields = [(k,t) for k,t in fields if k != 'call']
    if name == 'new': fields=[]; result='ConnectionState'
    methods.append((name,fields,result,bool(asynchronous)))
assert len(methods) == 103, len(methods)
header = '''// 由 tools/generate_cli.py 生成；schema、反序列化和执行共享这些参数类型。
use phantom_sdk::*;
use serde::{Deserialize,Serialize};
use schemars::JsonSchema;
use serde_json::{Value,json};
use crate::{wire,config::Config};
'''
blocks=[header]; arms=[]; names=[]; schemas=[]; validates=[]; effects=[]; needs=[]
api_text=(root/'crates/phantom-sdk/src/api.rs').read_text()
for name,fields,result,asynchronous in methods:
    variant=''.join(p.capitalize() for p in name.split('_'))
    original=fields
    if name == 'login': fields=[]
    if name == 'update_token': fields=[('input','crate::config::TokenReference')]
    struct=variant+'Params'
    blocks.append(f'#[derive(Deserialize,Serialize,JsonSchema)]\n#[serde(deny_unknown_fields)]\npub struct {struct} {{ '+', '.join('pub '+k+': '+t for k,t in fields)+' }\n')
    names.append(name)
    args=','.join('p.'+k for k,t in fields)
    if name == 'login': args='config.login()?'
    if name == 'update_token': args='TokenUpdate { user_token: p.input.read()? }'
    if asynchronous: args+=(',' if args else '')+'call'
    execute=f'client.{name}({args})'+('.await' if asynchronous else '')+'?'
    if name == 'single_conv_id': execute='single_conv_id(p.a,p.b)?'
    if name == 'new': execute='client.get_state()?'
    pvar='p' if fields else '_p'
    if not fields: execute=execute.replace('p.','_p.')
    arms.append(f'"{name}" => {{ let {pvar}: {struct} = wire::decode(params)?; wire::encode(&({execute})) }},')
    schemas.append(f'"{name}" => Ok(json!({{"method":"{name}","input":wire::schema::<{struct}>(),"result":wire::schema::<{result}>(),"side_effect":effect(method)}})),')
    validation='Ok(())'
    api=re.search(rf'pub async fn {name}\(.*?let request = ApiRequest::(\w+)',api_text,re.S)
    if f'fn {name}(' in api_text and api:
        validation=f'ApiRequest::{api.group(1)}'+(' { '+', '.join(k+': p.'+k for k,t in original)+' }' if original else '')+'.validate()'
    elif name in ['send_message','upload_media','init_upload']:
        validation='p.input.validate()' if name!='upload_media' else 'p.input.metadata.validate()'
    elif name in ['history','get_messages']: validation='p.query.validate()'
    elif name in ['send_text']: validation='MessageContent::Text(TextContent { text: p.text }).validate_send()'
    elif name == 'single_conv_id': validation='single_conv_id(p.a,p.b).map(|_| ())'
    elif name == 'update_token': validation='p.input.validate()'
    guards=[]
    for field,ty in fields:
        if (field.endswith('_id') or field == 'conv_id') and ty == 'String':
            guards.append(f'validate_id(&p.{field}, "{field}")?;')
        elif field.endswith('_id') and ty == 'Option<String>':
            guards.append(f'if let Some(id) = &p.{field} {{ validate_id(id, "{field}")?; }}')
    validation=' '.join(guards)+' '+validation
    var='p' if 'p.' in validation else '_p'
    validates.append(f'"{name}" => {{ let {var}: {struct} = wire::decode(params)?; {validation} }},')
    local=name.startswith('get_') and name in ['get_state','get_current_user_id','get_app_config','get_sync_status'] or name in ['logout','shutdown','flush_storage','login','single_conv_id','new']
    needs.append(f'"{name}" => {str(not local).lower()},')
    effect='read'
    if name in ['single_conv_id','new','get_state','get_current_user_id','get_app_config','get_sync_status','get_messages','get_conversation_state','mark_message_displayed','handle_notification_click','flush_storage']: effect='local'
    elif name in ['get_announcement']: effect='marks_announcement_read'
    elif name in ['delete_local','clear_history','delete_conversation','dissolve_group','delete_account']: effect='destructive'
    elif name.startswith(('get_','list_','search_','resolve_','build_')) or name in ['history','wait_ready']: pass
    elif name in ['login','logout','shutdown','reconnect','update_token']: effect='session'
    else: effect='write'
    effects.append(f'"{name}" => "{effect}",')
blocks.append('pub const METHODS: &[&str] = &['+','.join('"'+n+'"' for n in names)+'];\n')
blocks.append('pub fn describe(method: &str) -> SdkResult<Value> { match method {\n'+'\n'.join(schemas)+'\n_ => Err(wire::invalid("未知方法")), } }\n')
blocks.append('pub fn effect(method: &str) -> &\'static str { match method {\n'+'\n'.join(effects)+'\n_ => "unknown", } }\n')
blocks.append('pub fn needs_session(method: &str) -> bool { match method {\n'+'\n'.join(needs)+'\n_ => false, } }\n')
blocks.append('pub fn validate(method: &str, params: Value) -> SdkResult<()> { match method {\n'+'\n'.join(validates)+'\n_ => Err(wire::invalid("未知方法")), } }\n')
blocks.append('pub async fn execute(client: &PhantomClient, config: &Config, method: &str, params: Value, call: CallOptions) -> SdkResult<Value> { match method {\n'+'\n'.join(arms)+'\n_ => Err(wire::invalid("未知方法")), } }\n')
blocks.append('pub fn execute_helper(params: Value) -> SdkResult<Value> { let p: SingleConvIdParams = wire::decode(params)?; wire::encode(&single_conv_id(p.a,p.b)?) }\n')
(root/'crates/phantom-sdk-cli/src/methods.rs').write_text(''.join(blocks))
print(f'已生成 {len(methods)} 项类型化方法')

import subprocess
subprocess.run(["cargo", "fmt", "--manifest-path", str(root/"Cargo.toml"), "--all"], check=True)
