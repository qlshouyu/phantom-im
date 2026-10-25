// Phantom IM M0 端到端冒烟测试：验证 5 份服务端 spec 的主场景。
// 用法：node smoke.mjs
import protobuf from "protobufjs";
import { WebSocket } from "ws";
import jwt from "jsonwebtoken";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PROTO_DIR = path.resolve(__dirname, "../../proto");
const WS_URL = "ws://127.0.0.1:28080/ws";
const APP_ID = "app_demo";
const APP_SECRET = "phantom_demo_secret_please_change_0123456789";
const HTTP_URL = "http://127.0.0.1:28080";

// ---- 载入 proto ----
const root = new protobuf.Root();
root.resolvePath = (origin, target) =>
  target.startsWith("phantom/") ? path.join(PROTO_DIR, target) : protobuf.util.path.resolve(origin, target);
await root.load(
  ["envelope", "common", "message", "sync", "signaling"].map((n) => path.join(PROTO_DIR, `phantom/v1/${n}.proto`)),
  { keepCase: false }
);
const T = (n) => root.lookupType(`phantom.v1.${n}`);
const Envelope = T("Envelope");
const CMD = root.lookupEnum("phantom.v1.Command").values;
const Msg = { AuthReq: T("AuthReq"), AuthResp: T("AuthResp"), Ping: T("Ping"), Pong: T("Pong"),
  SendMsgReq: T("SendMsgReq"), SendMsgResp: T("SendMsgResp"), MsgPush: T("MsgPush"), MsgAck: T("MsgAck"),
  SyncReq: T("SyncReq"), SyncResp: T("SyncResp"), MsgContent: T("MsgContent"),
  RecallMsgReq: T("RecallMsgReq"), RecallMsgResp: T("RecallMsgResp"), KickPush: T("KickPush") };

const single = (a, b) => "si_" + [a, b].sort().join("__");
const token = (userId) => jwt.sign({ app_id: APP_ID, user_id: userId }, APP_SECRET,
  { algorithm: "HS256", expiresIn: "1h" });

let PASS = 0, FAIL = 0;
const ok = (c, m) => { if (c) { PASS++; console.log(`  ✅ ${m}`); } else { FAIL++; console.error(`  ❌ ${m}`); } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const waitClose = (ws, timeoutMs = 15_000) => new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error("等待连接关闭超时")), timeoutMs);
  ws.once("close", (code, reason) => { clearTimeout(timer); resolve({ code, reason: reason.toString() }); });
  ws.once("error", () => {});
});
const waitOpen = (ws) => new Promise((resolve, reject) => {
  ws.once("open", resolve);
  ws.once("error", reject);
});

function openApiHeaders(method, path, body) {
  const timestamp = Date.now();
  const signature = crypto.createHmac("sha256", APP_SECRET)
    .update(method + path + body + timestamp).digest("hex");
  return { "Content-Type": "application/json", "X-App-Id": APP_ID,
    "X-Timestamp": String(timestamp), "X-Signature": signature };
}
async function openApi(method, path, body = undefined) {
  const text = body === undefined ? "" : JSON.stringify(body);
  const response = await fetch(HTTP_URL + path, { method,
    headers: openApiHeaders(method, path, text), body: text || undefined });
  const payload = await response.json();
  if (!response.ok || payload.code !== 0) throw new Error(`${method} ${path}: ${payload.message}`);
  return payload.data;
}
async function userApi(userId, method, path, body = undefined) {
  const text = body === undefined ? "" : JSON.stringify(body);
  const response = await fetch(HTTP_URL + path, { method, headers: {
    Authorization: `Bearer ${token(userId)}`, "X-App-Id": APP_ID, "X-User-Id": userId,
    "X-Device-Id": "dev-" + userId, "Content-Type": "application/json",
  }, body: text || undefined });
  const payload = await response.json();
  if (!response.ok || payload.code !== 0) throw new Error(`${method} ${path}: ${payload.message}`);
  return payload.data;
}

// ---- 极简客户端 ----
class Client {
  constructor(userId) { this.userId = userId; this.cid = 0; this.pending = new Map(); this.pushes = []; this.kicks = []; }
  connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(WS_URL);
      this.ws.binaryType = "arraybuffer";
      this.ws.on("open", resolve);
      this.ws.on("error", reject);
      this.ws.on("message", (data) => this._onFrame(new Uint8Array(data)));
    });
  }
  _onFrame(bytes) {
    const env = Envelope.decode(bytes);
    if (env.cid && this.pending.has(Number(env.cid))) {
      const { resolve } = this.pending.get(Number(env.cid));
      this.pending.delete(Number(env.cid));
      resolve(env);
    } else if (env.cmd === CMD.COMMAND_MSG_PUSH) {
      const push = Msg.MsgPush.decode(env.payload);
      this.pushes.push(push);
      // 自动 ACK
      this._send(CMD.COMMAND_MSG_ACK, Msg.MsgAck.encode({ userSeq: push.userSeq }).finish());
    } else if (env.cmd === CMD.COMMAND_KICK_PUSH) {
      this.kicks.push(Msg.KickPush.decode(env.payload));
    }
  }
  _send(cmd, payload, cid = 0) {
    const env = Envelope.encode({ version: 1, cmd, cid, code: 0, payload }).finish();
    this.ws.send(env);
  }
  request(cmd, payload) {
    const cid = ++this.cid;
    return new Promise((resolve) => {
      this.pending.set(cid, { resolve });
      this._send(cmd, payload, cid);
    });
  }
  async auth(deviceId = "dev-" + this.userId, accessToken = token(this.userId)) {
    const payload = Msg.AuthReq.encode({ accessToken, deviceId,
      deviceType: 3 /*WEB*/, platform: "web", clientVersion: "0.1", appId: APP_ID, userId: this.userId }).finish();
    const env = await this.request(CMD.COMMAND_AUTH_REQ, payload);
    return { code: env.code, resp: env.code === 0 ? Msg.AuthResp.decode(env.payload) : null };
  }
  async sendText(convId, text, clientMsgId, convType = 1) {
    const content = Msg.MsgContent.encode({ text: { text } }).finish();
    const payload = Msg.SendMsgReq.encode({ clientMsgId, convId, convType,
      msgType: 1 /*TEXT*/, content: Msg.MsgContent.decode(content) }).finish();
    const env = await this.request(CMD.COMMAND_SEND_MSG_REQ, payload);
    return { code: env.code, resp: env.code === 0 ? Msg.SendMsgResp.decode(env.payload) : null };
  }
  async sync(sinceUserSeq) {
    const payload = Msg.SyncReq.encode({ sinceUserSeq, limit: 200 }).finish();
    const env = await this.request(CMD.COMMAND_SYNC_REQ, payload);
    return Msg.SyncResp.decode(env.payload);
  }
  async recall(msgId) {
    const env = await this.request(CMD.COMMAND_RECALL_MSG_REQ, Msg.RecallMsgReq.encode({ msgId }).finish());
    return { code: env.code, resp: env.code === 0 ? Msg.RecallMsgResp.decode(env.payload) : null };
  }
  close() { try { this.ws.close(); } catch {} }
}

const textOf = (push) => push.msg?.content?.text?.text;

// ---- 场景 ----
console.log("== Phantom IM M0 端到端冒烟 ==");
const runId = Date.now().toString(36);
const aliceExternalId = `alice_${runId}`;
const bobExternalId = `bob_${runId}`;

// 业务用户前置：外部 ID 由 OpenAPI 映射为平台 Phantom ID，后续 Token 必须使用 Phantom ID。
const aliceImported = await openApi("POST", "/openapi/users", { external_user_id: aliceExternalId });
const bobImported = await openApi("POST", "/openapi/users", { external_user_id: bobExternalId });
const aliceId = aliceImported.user_id ?? aliceImported.userId;
const bobId = bobImported.user_id ?? bobImported.userId;
const conv = single(aliceId, bobId);

// 0) 连接网关边界（connection-gateway）
const invalidFrame = new WebSocket(WS_URL);
await waitOpen(invalidFrame);
const invalidClosed = waitClose(invalidFrame);
invalidFrame.send(Buffer.from([0xff, 0x00, 0x01]));
const invalidClose = await invalidClosed;
ok(invalidClose.code === 1007, `非法帧断开 code=${invalidClose.code}`);

const nonAuth = new WebSocket(WS_URL);
await waitOpen(nonAuth);
const nonAuthClosed = waitClose(nonAuth);
const badFirstFrame = Envelope.encode({ version: 1, cmd: CMD.COMMAND_PING, cid: 1, code: 0,
  payload: Msg.Ping.encode({}).finish() }).finish();
nonAuth.send(badFirstFrame);
const badFirstClose = await nonAuthClosed;
ok(badFirstClose.code === 1008, `首帧非 AuthReq 断开 code=${badFirstClose.code}`);

const idle = new WebSocket(WS_URL);
await waitOpen(idle);
const idleClose = await waitClose(idle, 12_000);
const idleClosed = await idleClose;
ok(idleClosed.code === 1008 && idleClosed.reason.includes("auth timeout"),
  `认证超时断开 code=${idleClosed.code} reason=${idleClosed.reason}`);

const expiredClient = new Client(`u_expired_${runId}`);
await expiredClient.connect();
const expiredToken = jwt.sign({ app_id: APP_ID, user_id: expiredClient.userId }, APP_SECRET,
  { algorithm: "HS256", expiresIn: -1 });
const expiredAuth = await expiredClient.auth(`dev-expired-${runId}`, expiredToken);
ok(expiredAuth.code === 2001, `过期 Token 返回 2001`);
expiredClient.close();

const alice = new Client(aliceId);
const bob = new Client(bobId);
await alice.connect(); await bob.connect();

// 1) 认证（connection-gateway）
const aAuth = await alice.auth();
const bAuth = await bob.auth();
ok(aAuth.code === 0, "Alice 认证成功");
ok(bAuth.code === 0, "Bob 认证成功");

// 2) 单聊文本发送 + 在线投递（single-chat-messaging + message-fanout-delivery）
const s1 = await alice.sendText(conv, "hello bob", "cm-1");
ok(s1.code === 0 && s1.resp.msgId && Number(s1.resp.convSeq) > 0, `发送成功 msgId=${s1.resp?.msgId} convSeq=${s1.resp?.convSeq}`);
await sleep(1500); // 等扇出 + 在线推送
const got = bob.pushes.find((p) => textOf(p) === "hello bob");
ok(!!got, `Bob 在线收到 "hello bob" (userSeq=${got?.userSeq})`);

// 3) 发送幂等（single-chat-messaging）
const s2 = await alice.sendText(conv, "hello bob", "cm-1");
ok(s2.code === 0 && s2.resp.msgId === s1.resp.msgId && String(s2.resp.convSeq) === String(s1.resp.convSeq),
  `重发同 clientMsgId 命中幂等（msgId/convSeq 不变）`);

// 4) 断线 → 离线消息 → 重连 SyncReq 补齐（sync-catchup）
const bobLastUserSeq = Number(got?.userSeq ?? 0);
bob.close(); await sleep(500);
const s3 = await alice.sendText(conv, "offline msg", "cm-2");
ok(s3.code === 0, `Bob 离线期间 Alice 再发成功 convSeq=${s3.resp?.convSeq}`);
await sleep(1000);
const bob2 = new Client(bobId);
await bob2.connect();
const b2Auth = await bob2.auth();
ok(b2Auth.code === 0 && Number(b2Auth.resp.maxUserSeq) > bobLastUserSeq,
  `Bob 重连，AuthResp.maxUserSeq=${b2Auth.resp?.maxUserSeq} > 断线前 ${bobLastUserSeq}`);
const sr = await bob2.sync(bobLastUserSeq);
const synced = sr.events.find((e) => e.newMessage && e.newMessage.msg?.content?.text?.text === "offline msg");
ok(!!synced, `SyncReq 补齐到离线消息 "offline msg" (userSeq=${synced?.userSeq}, events=${JSON.stringify(sr.events.map((e) => ({
  userSeq: String(e.userSeq),
  text: e.newMessage?.msg?.content?.text?.text,
})))})`);

// 5) 撤回（rich-messaging / MSG-11）
const recallSend = await alice.sendText(conv, "recall me", "cm-recall");
await sleep(500);
const recalled = await alice.recall(recallSend.resp.msgId);
ok(recalled.code === 0 && recalled.resp.msgId === recallSend.resp.msgId,
  `2 分钟内撤回成功 msgId=${recallSend.resp.msgId}`);
const recallAgain = await alice.recall(recallSend.resp.msgId);
ok(recallAgain.code === 4004, `重复撤回返回 ALREADY_RECALLED code=${recallAgain.code}`);

// 6) 群消息与群写扩散（GC-01 / GC-02）
const carolImported = await openApi("POST", "/openapi/users", { external_user_id: `carol_${runId}` });
const carolId = carolImported.user_id ?? carolImported.userId;
for (const [from, to] of [[aliceId, bobId], [aliceId, carolId]]) {
  await userApi(from, "POST", "/friends/requests", { to_user_id: to, verification_message: "smoke" });
  const page = await userApi(to, "GET", "/friends/requests?include_handled=false");
  const request = page.items.find((item) => item.from_user_id === from);
  await userApi(to, "POST", `/friends/requests/${request.request_id}`, { action: "ACCEPT" });
}
const group = await userApi(aliceId, "POST", "/groups",
  { name: "smoke-group", member_ids: [bobId, carolId] });
ok(Boolean(group.conv_id && group.member_count === 3), `创建三人群 groupId=${group.group_id}`);
const groupMessage = await alice.sendText(group.conv_id, "hello group", "cm-group", 2);
ok(groupMessage.code === 0, `群消息收单成功 msgId=${groupMessage.resp?.msgId}`);
await sleep(1500);
ok(bob2.pushes.some((push) => textOf(push) === "hello group"), "群成员收到群消息");

// 7) 同类型设备顶替（multi-device-session / MD-02）
const aliceNewDevice = new Client(aliceId);
await aliceNewDevice.connect();
const replacement = await aliceNewDevice.auth(`dev-replaced-${runId}`);
await sleep(500);
ok(replacement.code === 0 && alice.kicks.some((kick) => kick.reason === 1),
  "同类型新连接踢出旧连接 SAME_TYPE_LOGIN");

aliceNewDevice.close(); bob2.close();
await sleep(300);
console.log(`\n== 结果：${PASS} 通过 / ${FAIL} 失败 ==`);
process.exit(FAIL === 0 ? 0 : 1);
