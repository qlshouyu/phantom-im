import crypto from "node:crypto";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import jwt from "jsonwebtoken";
import protobuf from "protobufjs";
import { WebSocket } from "ws";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PROTO_DIR = path.resolve(__dirname, "../../proto");
const BASE = process.env.PHANTOM_HTTP_URL ?? "http://127.0.0.1:28080";
const WS_URL = process.env.PHANTOM_WS_URL ?? "ws://127.0.0.1:28080/ws";
const APP_ID = "app_demo";
const APP_SECRET = "phantom_demo_secret_please_change_0123456789";
const MEMBERS = Number(process.env.CAPACITY_MEMBERS ?? 500);
const runId = Date.now().toString(36);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const root = new protobuf.Root();
root.resolvePath = (origin, target) => target.startsWith("phantom/")
  ? path.join(PROTO_DIR, target) : protobuf.util.path.resolve(origin, target);
await root.load(["envelope", "common", "message", "sync", "signaling"]
  .map((name) => path.join(PROTO_DIR, `phantom/v1/${name}.proto`)), { keepCase: false });
const type = (name) => root.lookupType(`phantom.v1.${name}`);
const Envelope = type("Envelope");
const CMD = root.lookupEnum("phantom.v1.Command").values;
const AuthReq = type("AuthReq"), AuthResp = type("AuthResp"), SendMsgReq = type("SendMsgReq");
const SendMsgResp = type("SendMsgResp"), MsgContent = type("MsgContent");

const token = (userId) => jwt.sign({ app_id: APP_ID, user_id: userId }, APP_SECRET,
  { algorithm: "HS256", expiresIn: "2h" });

function openHeaders(method, requestPath, body = "") {
  const timestamp = Date.now();
  return {
    "Content-Type": "application/json", "X-App-Id": APP_ID, "X-Timestamp": String(timestamp),
    "X-Signature": crypto.createHmac("sha256", APP_SECRET)
      .update(method + requestPath + body + timestamp).digest("hex"),
  };
}

async function openApi(method, requestPath, body) {
  const text = body === undefined ? "" : JSON.stringify(body);
  const response = await fetch(BASE + requestPath, { method,
    headers: openHeaders(method, requestPath, text), body: text || undefined });
  const payload = await response.json();
  if (!response.ok || payload.code !== 0) throw new Error(`${method} ${requestPath}: ${payload.message}`);
  return payload.data;
}

async function userApi(userId, method, requestPath, body) {
  const text = body === undefined ? "" : JSON.stringify(body);
  const response = await fetch(BASE + requestPath, { method, headers: {
    Authorization: `Bearer ${token(userId)}`, "X-App-Id": APP_ID, "X-User-Id": userId,
    "X-Device-Id": `capacity-${userId}`, "Content-Type": "application/json",
  }, body: text || undefined });
  const payload = await response.json();
  if (!response.ok || payload.code !== 0) throw new Error(`${method} ${requestPath}: ${payload.message}`);
  return payload.data;
}

function seedUsers(count) {
  const ids = Array.from({ length: count }, (_, index) => `u_cap_${runId}_${index}`);
  const now = Date.now();
  const users = ids.map((id, index) => `('${id}','${APP_ID}','capacity_${runId}_${index}',` +
    `'ACTIVE',FALSE,${now},${now},NULL)`).join(",");
  const profiles = ids.map((id, index) => `('${id}','capacity-${index}','',JSON_OBJECT(),NULL,${now})`).join(",");
  execFileSync("docker", ["exec", "phantom-mysql", "mysql", "-uroot", "-p123456", "phantom_im", "-e",
    `INSERT IGNORE INTO app_user(user_id,app_id,external_user_id,status,minor_mode,created_at,updated_at,deleted_at) VALUES ${users};` +
    `INSERT IGNORE INTO user_profile(user_id,nickname,avatar_url,ext_json,display_snapshot,updated_at) VALUES ${profiles};`],
  { encoding: "utf8" });
  return ids;
}

function connect(ws) {
  return new Promise((resolve, reject) => {
    ws.once("open", resolve);
    ws.once("error", reject);
  });
}

async function authenticate(ws, userId) {
  let cid = 1;
  const response = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("认证超时")), 15_000);
    ws.on("message", (data) => {
      const envelope = Envelope.decode(new Uint8Array(data));
      if (Number(envelope.cid) !== cid) return;
      clearTimeout(timer);
      if (envelope.code !== 0) reject(new Error(`认证失败 code=${envelope.code}`));
      else resolve(AuthResp.decode(envelope.payload));
    });
  });
  ws.send(Envelope.encode({ version: 1, cmd: CMD.COMMAND_AUTH_REQ, cid, code: 0,
    payload: AuthReq.encode({ accessToken: token(userId), deviceId: `capacity-${userId}`,
      deviceType: 3, platform: "web", clientVersion: "capacity", appId: APP_ID, userId }).finish(),
  }).finish());
  return response;
}

async function sendText(ws, convId, text) {
  const cid = 2;
  const response = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("发送超时")), 20_000);
    ws.on("message", (data) => {
      const envelope = Envelope.decode(new Uint8Array(data));
      if (Number(envelope.cid) !== cid) return;
      clearTimeout(timer);
      if (envelope.code !== 0) reject(new Error(`发送失败 code=${envelope.code}`));
      else resolve(SendMsgResp.decode(envelope.payload));
    });
  });
  const content = MsgContent.encode({ text: { text } }).finish();
  ws.send(Envelope.encode({ version: 1, cmd: CMD.COMMAND_SEND_MSG_REQ, cid, code: 0,
    payload: SendMsgReq.encode({ clientMsgId: `capacity-${runId}`, convId, convType: 2,
      msgType: 1, content: MsgContent.decode(content) }).finish(),
  }).finish());
  return response;
}

async function waitForCount(sql, expected, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const output = execFileSync("docker", ["exec", "phantom-mysql", "mysql", "-N", "-B", "-uroot",
      "-p123456", "phantom_im", "-e", sql], { encoding: "utf8" }).trim();
    const count = Number(output || 0);
    if (count >= expected) return count;
    await sleep(1_000);
  }
  throw new Error(`等待数据库计数 ${expected} 超时`);
}

console.log(`容量测试：${MEMBERS} 人群、单条消息写扩散与历史/搜索查询`);
const users = seedUsers(MEMBERS);
const owner = users[0];
const members = users.slice(1);
const now = Date.now();
const pairs = [];
for (const member of members) {
  pairs.push(`('${owner}','${member}',${now})`, `('${member}','${owner}',${now})`);
}
execFileSync("docker", ["exec", "phantom-mysql", "mysql", "-uroot", "-p123456", "phantom_im", "-e",
  `INSERT IGNORE INTO friend_relation(user_id,friend_id,created_at) VALUES ${pairs.join(",")}`],
{ encoding: "utf8" });

const startedGroup = performance.now();
const group = await userApi(owner, "POST", "/groups", {
  name: `capacity-${runId}`, member_ids: members,
});
const groupMs = performance.now() - startedGroup;
console.log(`  ✓ 建群 ${group.member_count} 人耗时 ${groupMs.toFixed(0)}ms`);

const ws = new WebSocket(WS_URL);
await connect(ws);
await authenticate(ws, owner);
const startedSend = performance.now();
const sent = await sendText(ws, group.conv_id, `capacity-message-${runId}`);
const sendMs = performance.now() - startedSend;
const fanoutStarted = performance.now();
const fanoutCount = await waitForCount(
  `SELECT COUNT(*) FROM user_timeline WHERE msg_id='${sent.msgId}'`, MEMBERS);
const fanoutMs = performance.now() - fanoutStarted;
console.log(`  ✓ 收单耗时 ${sendMs.toFixed(0)}ms，${fanoutCount} 条写扩散完成耗时 ${fanoutMs.toFixed(0)}ms`);

const history = await userApi(owner, "GET",
  `/conversations/${encodeURIComponent(group.conv_id)}/messages?limit=1`);
const search = await userApi(owner, "GET", `/search?q=${encodeURIComponent(`capacity-message-${runId}`)}&limit=1`);
console.log(`  ✓ 历史分页 items=${history.items?.length ?? 0} hasMore=${history.has_more ?? history.hasMore}`);
console.log(`  ✓ 搜索分页 messageTotal=${search.messageTotal ?? search.message_total} items=${search.messages?.length ?? 0}`);
ws.close();

if (group.member_count !== MEMBERS || fanoutCount !== MEMBERS) {
  throw new Error(`容量断言失败 memberCount=${group.member_count} fanout=${fanoutCount}`);
}
console.log("容量测试通过；结果可写入 docs/platform/acceptance/mvp-acceptance-evidence.md");
