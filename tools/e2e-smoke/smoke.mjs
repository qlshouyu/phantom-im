// Phantom IM M0 端到端冒烟测试：验证 5 份服务端 spec 的主场景。
// 用法：node smoke.mjs
import protobuf from "protobufjs";
import { WebSocket } from "ws";
import jwt from "jsonwebtoken";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PROTO_DIR = path.resolve(__dirname, "../../proto");
const WS_URL = "ws://127.0.0.1:28080/ws";
const APP_ID = "app_demo";
const APP_SECRET = "phantom_demo_secret_please_change_0123456789";

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
  SyncReq: T("SyncReq"), SyncResp: T("SyncResp"), MsgContent: T("MsgContent") };

const single = (a, b) => "si_" + [a, b].sort().join("__");
const token = (userId) => jwt.sign({ app_id: APP_ID, user_id: userId }, APP_SECRET,
  { algorithm: "HS256", expiresIn: "1h" });

let PASS = 0, FAIL = 0;
const ok = (c, m) => { if (c) { PASS++; console.log(`  ✅ ${m}`); } else { FAIL++; console.error(`  ❌ ${m}`); } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- 极简客户端 ----
class Client {
  constructor(userId) { this.userId = userId; this.cid = 0; this.pending = new Map(); this.pushes = []; }
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
  async auth(deviceId = "dev-" + this.userId) {
    const payload = Msg.AuthReq.encode({ accessToken: token(this.userId), deviceId,
      deviceType: 3 /*WEB*/, platform: "web", clientVersion: "0.1", appId: APP_ID, userId: this.userId }).finish();
    const env = await this.request(CMD.COMMAND_AUTH_REQ, payload);
    return { code: env.code, resp: env.code === 0 ? Msg.AuthResp.decode(env.payload) : null };
  }
  async sendText(convId, text, clientMsgId) {
    const content = Msg.MsgContent.encode({ text: { text } }).finish();
    const payload = Msg.SendMsgReq.encode({ clientMsgId, convId, convType: 1 /*SINGLE*/,
      msgType: 1 /*TEXT*/, content: Msg.MsgContent.decode(content) }).finish();
    const env = await this.request(CMD.COMMAND_SEND_MSG_REQ, payload);
    return { code: env.code, resp: env.code === 0 ? Msg.SendMsgResp.decode(env.payload) : null };
  }
  async sync(sinceUserSeq) {
    const payload = Msg.SyncReq.encode({ sinceUserSeq, limit: 200 }).finish();
    const env = await this.request(CMD.COMMAND_SYNC_REQ, payload);
    return Msg.SyncResp.decode(env.payload);
  }
  close() { try { this.ws.close(); } catch {} }
}

const textOf = (push) => push.msg?.content?.text?.text;

// ---- 场景 ----
console.log("== Phantom IM M0 端到端冒烟 ==");
const conv = single("u_alice", "u_bob");

const alice = new Client("u_alice");
const bob = new Client("u_bob");
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
const bob2 = new Client("u_bob");
await bob2.connect();
const b2Auth = await bob2.auth();
ok(b2Auth.code === 0 && Number(b2Auth.resp.maxUserSeq) > bobLastUserSeq,
  `Bob 重连，AuthResp.maxUserSeq=${b2Auth.resp?.maxUserSeq} > 断线前 ${bobLastUserSeq}`);
const sr = await bob2.sync(bobLastUserSeq);
const synced = sr.events.find((e) => e.newMessage && e.newMessage.msg?.content?.text?.text === "offline msg");
ok(!!synced, `SyncReq 补齐到离线消息 "offline msg" (userSeq=${synced?.userSeq})`);

alice.close(); bob2.close();
await sleep(300);
console.log(`\n== 结果：${PASS} 通过 / ${FAIL} 失败 ==`);
process.exit(FAIL === 0 ? 0 : 1);
