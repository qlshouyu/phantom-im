import protobuf from "protobufjs";
import { WebSocket } from "ws";
import jwt from "jsonwebtoken";
import { createHmac } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PROTO_DIR = path.resolve(__dirname, "../../proto");
const BASE_URL = process.env.BASE_URL ?? "http://127.0.0.1:28080";
const WS_URL = process.env.WS_URL ?? "ws://127.0.0.1:28080/ws";
const BOOTSTRAP_APP_ID = process.env.BOOTSTRAP_APP_ID ?? "console";
const BOOTSTRAP_SECRET = process.env.BOOTSTRAP_SECRET ?? "phantom-console-dev-secret-change-me";

const root = new protobuf.Root();
root.resolvePath = (origin, target) =>
  target.startsWith("phantom/") ? path.join(PROTO_DIR, target) : protobuf.util.path.resolve(origin, target);
await root.load(
  ["envelope", "common", "message", "sync", "signaling"].map((name) => path.join(PROTO_DIR, `phantom/v1/${name}.proto`)),
  { keepCase: false }
);
const T = (name) => root.lookupType(`phantom.v1.${name}`);
const Envelope = T("Envelope");
const CMD = root.lookupEnum("phantom.v1.Command").values;
const Proto = {
  AuthReq: T("AuthReq"), SendMsgReq: T("SendMsgReq"), SendMsgResp: T("SendMsgResp"),
  MsgPush: T("MsgPush"), MsgAck: T("MsgAck"), MsgContent: T("MsgContent"),
  SyncEventPush: T("SyncEventPush")
};

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const single = (a, b) => "si_" + [a, b].sort().join("__");

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function hmac(secret, payload) {
  return createHmac("sha256", secret).update(payload).digest("hex");
}

async function parse(response) {
  const text = await response.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { raw: text }; }
  return { status: response.status, json };
}

async function openApiRequest(appId, secret, method, requestPath, payload) {
  const body = payload === undefined ? "" : JSON.stringify(payload);
  const timestamp = Date.now();
  const response = await fetch(BASE_URL + requestPath, {
    method,
    headers: {
      "content-type": "application/json",
      "x-app-id": appId,
      "x-timestamp": String(timestamp),
      "x-signature": hmac(secret, method + requestPath + body + timestamp)
    },
    body: body || undefined
  });
  return parse(response);
}

async function userRequest(token, appId, userId, method, requestPath, payload) {
  const response = await fetch(BASE_URL + requestPath, {
    method,
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${token}`,
      "x-app-id": appId,
      "x-user-id": userId
    },
    body: payload === undefined ? undefined : JSON.stringify(payload)
  });
  return parse(response);
}

function ok(response, step) {
  assert(response.status === 200, `${step}: HTTP ${response.status} ${JSON.stringify(response.json)}`);
  assert(response.json.code === 0, `${step}: code=${response.json.code} ${response.json.message}`);
  return response.json.data;
}

class Client {
  constructor(appId, userId, token, deviceId, deviceType) {
    this.appId = appId;
    this.userId = userId;
    this.token = token;
    this.deviceId = deviceId;
    this.deviceType = deviceType;
    this.cid = 0;
    this.pending = new Map();
    this.messages = [];
    this.events = [];
  }

  async connectAndAuth() {
    await new Promise((resolve, reject) => {
      this.ws = new WebSocket(WS_URL);
      this.ws.binaryType = "arraybuffer";
      this.ws.once("open", resolve);
      this.ws.once("error", reject);
      this.ws.on("message", (data) => this.onFrame(new Uint8Array(data)));
    });
    const payload = Proto.AuthReq.encode({
      accessToken: this.token,
      deviceId: this.deviceId,
      deviceType: this.deviceType,
      platform: "smoke",
      clientVersion: "0.1",
      appId: this.appId,
      userId: this.userId
    }).finish();
    const env = await this.request(CMD.COMMAND_AUTH_REQ, payload);
    return env.code;
  }

  onFrame(bytes) {
    const env = Envelope.decode(bytes);
    const cid = Number(env.cid);
    if (cid && this.pending.has(cid)) {
      this.pending.get(cid)(env);
      this.pending.delete(cid);
      return;
    }
    if (env.cmd === CMD.COMMAND_MSG_PUSH) {
      const push = Proto.MsgPush.decode(env.payload);
      this.messages.push(push);
      this.send(CMD.COMMAND_MSG_ACK, Proto.MsgAck.encode({ userSeq: push.userSeq }).finish());
    } else if (env.cmd === CMD.COMMAND_SYNC_EVENT_PUSH) {
      this.events.push(Proto.SyncEventPush.decode(env.payload).event);
    }
  }

  send(cmd, payload, cid = 0) {
    this.ws.send(Envelope.encode({ version: 1, cmd, cid, code: 0, payload }).finish());
  }

  request(cmd, payload) {
    const cid = ++this.cid;
    return new Promise((resolve) => {
      this.pending.set(cid, resolve);
      this.send(cmd, payload, cid);
    });
  }

  async sendText(convId, text, clientMsgId) {
    const content = Proto.MsgContent.encode({ text: { text } }).finish();
    const payload = Proto.SendMsgReq.encode({
      clientMsgId, convId, convType: 1, msgType: 1, content: Proto.MsgContent.decode(content)
    }).finish();
    const env = await this.request(CMD.COMMAND_SEND_MSG_REQ, payload);
    return { code: env.code, response: env.code === 0 ? Proto.SendMsgResp.decode(env.payload) : null };
  }

  close() {
    try { this.ws.close(); } catch {}
  }
}

async function waitFor(check, message, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const result = await check();
    if (result) return result;
    await sleep(200);
  }
  throw new Error(message);
}

const suffix = Date.now();
const app = ok(await openApiRequest(BOOTSTRAP_APP_ID, BOOTSTRAP_SECRET, "POST", "/openapi/apps", {
  name: `conversation-smoke-${suffix}`
}), "create app");

async function importUser(name) {
  return ok(await openApiRequest(app.app_id, app.app_secret, "POST", "/openapi/users", {
    external_user_id: `${name}-${suffix}`,
    nickname: name,
    avatar_url: "",
    ext: {}
  }), `import ${name}`);
}

const alice = await importUser("Alice");
const bob = await importUser("Bob");
const token = (userId) => jwt.sign({ app_id: app.app_id, user_id: userId }, app.app_secret,
  { algorithm: "HS256", expiresIn: "1h" });
const aliceToken = token(alice.user_id);
const bobToken = token(bob.user_id);
const convId = single(alice.user_id, bob.user_id);

const createdRequest = ok(await userRequest(aliceToken, app.app_id, alice.user_id, "POST", "/friends/requests", {
  to_user_id: bob.user_id,
  verification_message: "conversation smoke"
}), "create friendship request");
ok(await userRequest(bobToken, app.app_id, bob.user_id, "POST",
  `/friends/requests/${createdRequest.request_id}`, { action: "ACCEPT" }), "accept friendship");

const aliceClient = new Client(app.app_id, alice.user_id, aliceToken, `alice-${suffix}`, 3);
const bobWeb = new Client(app.app_id, bob.user_id, bobToken, `bob-web-${suffix}`, 3);
const bobDesktop = new Client(app.app_id, bob.user_id, bobToken, `bob-desktop-${suffix}`, 2);
assert(await aliceClient.connectAndAuth() === 0, "Alice auth failed");
assert(await bobWeb.connectAndAuth() === 0, "Bob web auth failed");
assert(await bobDesktop.connectAndAuth() === 0, "Bob desktop auth failed");

const sent = await aliceClient.sendText(convId, "conversation smoke", `c-${suffix}`);
assert(sent.code === 0, `send failed code=${sent.code}`);
const convSeq = Number(sent.response.convSeq);

const bobPage = await waitFor(async () => {
  const page = ok(await userRequest(bobToken, app.app_id, bob.user_id, "GET", "/conversations"), "bob conversations");
  return page.items.length === 1 ? page : null;
}, "Bob conversation was not projected");
const bobItem = bobPage.items[0];
assert(bobItem.conv_id === convId, "conversation id mismatch");
assert(bobItem.unread_count === 1, `expected unread=1, got ${bobItem.unread_count}`);
assert(bobItem.last_message_digest === "conversation smoke", "message digest mismatch");
assert(Number(bobItem.max_conv_seq) === convSeq, "max_conv_seq mismatch");
assert(Number(bobPage.max_user_seq) > 0, "max_user_seq missing");

const draft = ok(await userRequest(bobToken, app.app_id, bob.user_id, "PUT", `/conversations/${convId}/draft`, {
  draft: "draft from Bob",
  edited_at: Date.now()
}), "save draft");
assert(draft.draft === "draft from Bob", "draft not saved");
await waitFor(() => bobDesktop.events.some((event) => event.draft?.convId === convId),
  "draft event not pushed to second device");

ok(await userRequest(bobToken, app.app_id, bob.user_id, "POST", `/conversations/${convId}`, {
  pinned: true,
  muted: true
}), "pin and mute conversation");
await waitFor(() => bobDesktop.events.some((event) => event.convState?.convId === convId
  && event.convState?.field === 1), "pin event not pushed");
await waitFor(() => bobDesktop.events.some((event) => event.convState?.convId === convId
  && event.convState?.field === 2), "mute event not pushed");

const read = ok(await userRequest(bobToken, app.app_id, bob.user_id, "PUT",
  `/conversations/${convId}/read-positions`, { read_conv_seq: convSeq }), "mark read");
assert(Number(read.read_conv_seq) === convSeq, "read position mismatch");
await waitFor(() => bobDesktop.events.some((event) => event.readPosition?.convId === convId),
  "read position event not pushed");
const afterRead = ok(await userRequest(bobToken, app.app_id, bob.user_id, "GET", "/conversations"), "after read");
assert(afterRead.items[0].unread_count === 0, "unread did not clear");

ok(await userRequest(bobToken, app.app_id, bob.user_id, "DELETE", `/conversations/${convId}`),
  "delete conversation");
await waitFor(async () => (ok(await userRequest(bobToken, app.app_id, bob.user_id, "GET", "/conversations"),
  "after delete").items.length === 0), "deleted conversation still visible");
await waitFor(() => bobDesktop.events.some((event) => event.convState?.convId === convId
  && event.convState?.field === 3), "delete event not pushed");

const second = await aliceClient.sendText(convId, "recreated conversation", `c2-${suffix}`);
assert(second.code === 0, "second send failed");
const recreated = await waitFor(async () => {
  const page = ok(await userRequest(bobToken, app.app_id, bob.user_id, "GET", "/conversations"), "recreated");
  return page.items.length === 1 ? page.items[0] : null;
}, "conversation was not recreated");
assert(recreated.pinned === false && recreated.muted === false, "deleted state was not reset");
assert(recreated.unread_count === 1, "recreated unread mismatch");

ok(await userRequest(bobToken, app.app_id, bob.user_id, "PUT", `/conversations/${convId}/draft`, {
  draft: "reply draft",
  edited_at: Date.now()
}), "save reply draft");
const reply = await bobWeb.sendText(convId, "reply clears draft", `c3-${suffix}`);
assert(reply.code === 0, "reply failed");
await waitFor(async () => ok(await userRequest(bobToken, app.app_id, bob.user_id, "GET",
  `/conversations/${convId}`), "reply conversation").draft === "", "draft was not cleared after send");

ok(await userRequest(aliceToken, app.app_id, alice.user_id, "DELETE", `/friends/${bob.user_id}`),
  "delete friendship");
const aliceReadOnly = await waitFor(async () => {
  const item = ok(await userRequest(aliceToken, app.app_id, alice.user_id, "GET",
    `/conversations/${convId}`), "alice read-only conversation");
  return item.read_only ? item : null;
}, "friend deletion did not mark conversation read-only");
const bobConversation = ok(await userRequest(bobToken, app.app_id, bob.user_id, "GET",
  `/conversations/${convId}`), "bob one-way friendship conversation");
assert(aliceReadOnly.read_only === true && bobConversation.read_only === false,
  "one-way friend deletion did not preserve asymmetric conversation readability");

aliceClient.close();
bobWeb.close();
bobDesktop.close();

console.log(JSON.stringify({
  ok: true,
  app_id: app.app_id,
  alice_id: alice.user_id,
  bob_id: bob.user_id,
  conv_id: convId,
  checks: ["message-projection", "unread", "digest", "max-user-seq", "draft", "multi-device-push",
    "pin", "mute", "mark-read", "delete", "recreate", "draft-clear", "one-way-read-only"]
}, null, 2));
