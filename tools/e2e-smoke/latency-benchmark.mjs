// Phantom IM 1v1 在线发送基准。默认 20 msg/s、预热 100 条、测量 1000 条。
// 用法：BENCH_ENFORCE=false node latency-benchmark.mjs --label baseline --output reports/baseline.json
import crypto from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import jwt from "jsonwebtoken";
import protobuf from "protobufjs";
import { WebSocket } from "ws";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PROTO_DIR = path.resolve(__dirname, "../../proto");
const APP_ID = process.env.BENCH_APP_ID || "app_demo";
const APP_SECRET = process.env.BENCH_APP_SECRET || "phantom_demo_secret_please_change_0123456789";
const HTTP_URL = process.env.BENCH_HTTP_URL || "http://127.0.0.1:28080";
const WS_URL = process.env.BENCH_WS_URL || "ws://127.0.0.1:28080/ws";
const RATE = positiveInt(process.env.BENCH_RATE, 20);
const MESSAGE_COUNT = positiveInt(process.env.BENCH_MESSAGES, 1000);
const WARMUP_COUNT = nonNegativeInt(process.env.BENCH_WARMUP, 100);
const PUSH_TIMEOUT_MS = positiveInt(process.env.BENCH_PUSH_TIMEOUT_MS, 10_000);
const ENFORCE = (process.env.BENCH_ENFORCE || "true") !== "false";
const LIMITS = {
  sendRespP95Ms: Number(process.env.BENCH_SEND_P95_MS || 50),
  receiverE2eP95Ms: Number(process.env.BENCH_E2E_P95_MS || 100),
  receiverE2eP99Ms: Number(process.env.BENCH_E2E_P99_MS || 200),
};

const root = new protobuf.Root();
root.resolvePath = (origin, target) =>
  target.startsWith("phantom/") ? path.join(PROTO_DIR, target) : protobuf.util.path.resolve(origin, target);
await root.load(
  ["envelope", "common", "message", "sync", "signaling"].map((n) => path.join(PROTO_DIR, `phantom/v1/${n}.proto`)),
  { keepCase: false },
);
const type = (name) => root.lookupType(`phantom.v1.${name}`);
const Envelope = type("Envelope");
const CMD = root.lookupEnum("phantom.v1.Command").values;
const Proto = {
  AuthReq: type("AuthReq"),
  AuthResp: type("AuthResp"),
  SendMsgReq: type("SendMsgReq"),
  SendMsgResp: type("SendMsgResp"),
  MsgContent: type("MsgContent"),
  MsgAck: type("MsgAck"),
  MsgPush: type("MsgPush"),
};

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const makeToken = (userId) => jwt.sign({ app_id: APP_ID, user_id: userId }, APP_SECRET,
  { algorithm: "HS256", expiresIn: "2h" });
const singleConv = (a, b) => "si_" + [a, b].sort().join("__");

function positiveInt(value, fallback) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function nonNegativeInt(value, fallback) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : fallback;
}

function openApiHeaders(method, requestPath, body) {
  const timestamp = Date.now();
  const signature = crypto.createHmac("sha256", APP_SECRET)
    .update(method + requestPath + body + timestamp).digest("hex");
  return {
    "Content-Type": "application/json",
    "X-App-Id": APP_ID,
    "X-Timestamp": String(timestamp),
    "X-Signature": signature,
  };
}

async function openApi(method, requestPath, body) {
  const text = body === undefined ? "" : JSON.stringify(body);
  const response = await fetch(HTTP_URL + requestPath, {
    method,
    headers: openApiHeaders(method, requestPath, text),
    body: text || undefined,
  });
  const payload = await response.json();
  if (!response.ok || payload.code !== 0) {
    throw new Error(`${method} ${requestPath}: ${payload.message || response.status}`);
  }
  return payload.data;
}

async function userApi(userId, method, requestPath, body) {
  const text = body === undefined ? "" : JSON.stringify(body);
  const response = await fetch(HTTP_URL + requestPath, {
    method,
    headers: {
      Authorization: `Bearer ${makeToken(userId)}`,
      "X-App-Id": APP_ID,
      "X-User-Id": userId,
      "X-Device-Id": `bench-${userId}`,
      "Content-Type": "application/json",
    },
    body: text || undefined,
  });
  const payload = await response.json();
  if (!response.ok || payload.code !== 0) {
    throw new Error(`${method} ${requestPath}: ${payload.message || response.status}`);
  }
  return payload.data;
}

class BenchClient {
  constructor(userId, role) {
    this.userId = userId;
    this.role = role;
    this.cid = 0;
    this.pending = new Map();
    this.pushWaiters = new Map();
  }

  async connect() {
    await new Promise((resolve, reject) => {
      this.ws = new WebSocket(WS_URL);
      this.ws.binaryType = "arraybuffer";
      this.ws.once("open", resolve);
      this.ws.once("error", reject);
      this.ws.on("message", (data) => this.onFrame(new Uint8Array(data)));
    });
  }

  onFrame(bytes) {
    const envelope = Envelope.decode(bytes);
    if (envelope.cid && this.pending.has(Number(envelope.cid))) {
      const waiter = this.pending.get(Number(envelope.cid));
      this.pending.delete(Number(envelope.cid));
      waiter.resolve(envelope);
      return;
    }
    if (envelope.cmd !== CMD.COMMAND_MSG_PUSH) return;
    const push = Proto.MsgPush.decode(envelope.payload);
    this.sendFrame(CMD.COMMAND_MSG_ACK, Proto.MsgAck.encode({ userSeq: push.userSeq }).finish());
    const clientMsgId = push.msg?.clientMsgId;
    const waiter = this.pushWaiters.get(clientMsgId);
    if (waiter) {
      this.pushWaiters.delete(clientMsgId);
      waiter(push);
    }
  }

  sendFrame(cmd, payload, cid = 0) {
    this.ws.send(Envelope.encode({ version: 1, cmd, cid, code: 0, payload }).finish());
  }

  request(cmd, payload) {
    const cid = ++this.cid;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(cid);
        reject(new Error(`请求超时 cmd=${cmd} cid=${cid}`));
      }, PUSH_TIMEOUT_MS);
      this.pending.set(cid, {
        resolve: (value) => { clearTimeout(timer); resolve(value); },
      });
      this.sendFrame(cmd, payload, cid);
    });
  }

  async authenticate() {
    const payload = Proto.AuthReq.encode({
      accessToken: makeToken(this.userId),
      deviceId: `bench-${this.role}-${this.userId}`,
      deviceType: 3,
      platform: "web-benchmark",
      clientVersion: "0.1",
      appId: APP_ID,
      userId: this.userId,
    }).finish();
    const envelope = await this.request(CMD.COMMAND_AUTH_REQ, payload);
    if (envelope.code !== 0) throw new Error(`认证失败 user=${this.userId} code=${envelope.code}`);
    return Proto.AuthResp.decode(envelope.payload);
  }

  async sendText(convId, text, clientMsgId) {
    const content = Proto.MsgContent.encode({ text: { text } }).finish();
    const payload = Proto.SendMsgReq.encode({
      clientMsgId,
      convId,
      convType: 1,
      msgType: 1,
      content: Proto.MsgContent.decode(content),
    }).finish();
    const envelope = await this.request(CMD.COMMAND_SEND_MSG_REQ, payload);
    return {
      code: envelope.code,
      response: envelope.code === 0 ? Proto.SendMsgResp.decode(envelope.payload) : null,
    };
  }

  waitForPush(clientMsgId) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pushWaiters.delete(clientMsgId);
        reject(new Error(`等待 MsgPush 超时 clientMsgId=${clientMsgId}`));
      }, PUSH_TIMEOUT_MS);
      this.pushWaiters.set(clientMsgId, (push) => {
        clearTimeout(timer);
        resolve(push);
      });
    });
  }

  close() {
    try { this.ws?.close(); } catch { /* best effort */ }
  }
}

async function ensureFriends(aliceId, bobId) {
  await userApi(aliceId, "POST", "/friends/requests", {
    to_user_id: bobId,
    verification_message: "latency benchmark",
  });
  const page = await userApi(bobId, "GET", "/friends/requests?include_handled=false");
  const request = page.items.find((item) => item.from_user_id === aliceId);
  if (!request) throw new Error("未找到基准好友申请");
  await userApi(bobId, "POST", `/friends/requests/${request.request_id}`, { action: "ACCEPT" });
}

async function readMetricSummary(name) {
  try {
    const response = await fetch(`${HTTP_URL}/actuator/metrics/${encodeURIComponent(name)}`);
    if (!response.ok) return null;
    const payload = await response.json();
    return payload.measurements || null;
  } catch {
    return null;
  }
}

function percentile(sorted, value) {
  if (sorted.length === 0) return null;
  const index = Math.min(sorted.length - 1, Math.ceil((value / 100) * sorted.length) - 1);
  return round(sorted[index]);
}

function summary(values) {
  const sorted = values.filter(Number.isFinite).sort((a, b) => a - b);
  if (sorted.length === 0) return { samples: 0, p50: null, p95: null, p99: null, max: null };
  return {
    samples: sorted.length,
    p50: percentile(sorted, 50),
    p95: percentile(sorted, 95),
    p99: percentile(sorted, 99),
    max: round(sorted.at(-1)),
  };
}

function round(value) {
  return Math.round(value * 100) / 100;
}

async function waitUntil(epochMs) {
  const delay = epochMs - Date.now();
  if (delay > 0) await sleep(delay);
}

function parseOutputPath() {
  const index = process.argv.indexOf("--output");
  if (index >= 0 && process.argv[index + 1]) return path.resolve(process.argv[index + 1]);
  const labelIndex = process.argv.indexOf("--label");
  const label = labelIndex >= 0 && process.argv[labelIndex + 1] ? process.argv[labelIndex + 1] : "run";
  return path.join(__dirname, "reports", `latency-${label}-${new Date().toISOString().replaceAll(":", "-")}.json`);
}

async function runRound(sender, receiver, convId, count, prefix) {
  const sendLatencies = [];
  const e2eLatencies = [];
  const failures = [];
  const sends = [];
  const pushes = [];
  const roundStarted = Date.now();
  const intervalMs = 1000 / RATE;

  for (let i = 0; i < count; i++) {
    await waitUntil(roundStarted + Math.floor(i * intervalMs));
    const clientMsgId = `${prefix}-${i}`;
    const started = performance.now();
    const pushPromise = receiver.waitForPush(clientMsgId)
      .then((push) => { e2eLatencies.push(performance.now() - started); return push; })
      .catch((error) => { failures.push({ clientMsgId, phase: "receiver_e2e", error: error.message }); return null; });
    pushes.push(pushPromise);
    const sendPromise = sender.sendText(convId, `latency ${clientMsgId}`, clientMsgId)
      .then((result) => {
        if (result.code !== 0 || !result.response) {
          failures.push({ clientMsgId, phase: "send_resp", error: `code=${result.code}` });
          return null;
        }
        sendLatencies.push(performance.now() - started);
        return result.response;
      })
      .catch((error) => {
        failures.push({ clientMsgId, phase: "send_resp", error: error.message });
        return null;
      });
    sends.push(sendPromise);
  }

  const responses = (await Promise.all(sends)).filter(Boolean);
  await Promise.all(pushes);
  return {
    responses,
    sendResp: summary(sendLatencies),
    receiverE2e: summary(e2eLatencies),
    failures,
  };
}

const label = process.argv.includes("--label")
  ? process.argv[process.argv.indexOf("--label") + 1] : "run";
const runId = Date.now().toString(36);
const metricsBefore = {
  stage: await readMetricSummary("phantom.message.stage.duration"),
  outbox: await readMetricSummary("phantom.mq.outbox.pending"),
};

console.log(`== Phantom IM 1v1 延迟基准 (${label}) ==`);
console.log(`rate=${RATE}/s warmup=${WARMUP_COUNT} messages=${MESSAGE_COUNT}`);

const aliceImport = await openApi("POST", "/openapi/users", { external_user_id: `bench_alice_${runId}` });
const bobImport = await openApi("POST", "/openapi/users", { external_user_id: `bench_bob_${runId}` });
const aliceId = aliceImport.user_id ?? aliceImport.userId;
const bobId = bobImport.user_id ?? bobImport.userId;
const convId = singleConv(aliceId, bobId);
await ensureFriends(aliceId, bobId);

const alice = new BenchClient(aliceId, "sender");
const bob = new BenchClient(bobId, "receiver");
await alice.connect();
await bob.connect();
await Promise.all([alice.authenticate(), bob.authenticate()]);

if (WARMUP_COUNT > 0) {
  const warmup = await runRound(alice, bob, convId, WARMUP_COUNT, `warmup-${runId}`);
  console.log(`预热完成 sendP95=${warmup.sendResp.p95}ms e2eP95=${warmup.receiverE2e.p95}ms failures=${warmup.failures.length}`);
}

const measured = await runRound(alice, bob, convId, MESSAGE_COUNT, `measure-${runId}`);
const metricsAfter = {
  stage: await readMetricSummary("phantom.message.stage.duration"),
  outbox: await readMetricSummary("phantom.mq.outbox.pending"),
};
alice.close();
bob.close();

const thresholdChecks = {
  sendRespP95: measured.sendResp.p95 !== null && measured.sendResp.p95 <= LIMITS.sendRespP95Ms,
  receiverE2eP95: measured.receiverE2e.p95 !== null && measured.receiverE2e.p95 <= LIMITS.receiverE2eP95Ms,
  receiverE2eP99: measured.receiverE2e.p99 !== null && measured.receiverE2e.p99 <= LIMITS.receiverE2eP99Ms,
};
const passed = measured.failures.length === 0 && Object.values(thresholdChecks).every(Boolean);
const report = {
  benchmark: "phantom-im-1v1-online-latency",
  label,
  timestamp: new Date().toISOString(),
  passed,
  enforce: ENFORCE,
  environment: {
    hostname: os.hostname(),
    platform: `${os.platform()} ${os.release()}`,
    cpuModel: os.cpus()[0]?.model || "unknown",
    cpuCount: os.cpus().length,
    totalMemoryBytes: os.totalmem(),
    nodeVersion: process.version,
    httpUrl: HTTP_URL,
    wsUrl: WS_URL,
    appId: APP_ID,
    ratePerSecond: RATE,
    warmupCount: WARMUP_COUNT,
    messageCount: MESSAGE_COUNT,
    jvmArgs: process.env.BENCH_JVM_ARGS || null,
    profile: process.env.BENCH_PROFILE || "production-like",
  },
  thresholds: LIMITS,
  thresholdChecks,
  sampleCount: measured.responses.length,
  failureCount: measured.failures.length,
  failures: measured.failures,
  stages: {
    sendResp: measured.sendResp,
    receiverE2e: measured.receiverE2e,
  },
  actuatorMetrics: { before: metricsBefore, after: metricsAfter },
};

const outputPath = parseOutputPath();
await fs.mkdir(path.dirname(outputPath), { recursive: true });
await fs.writeFile(outputPath, JSON.stringify(report, null, 2) + "\n", "utf8");

console.log(`SendMsgResp: p50=${measured.sendResp.p50}ms p95=${measured.sendResp.p95}ms p99=${measured.sendResp.p99}ms`);
console.log(`接收端 E2E: p50=${measured.receiverE2e.p50}ms p95=${measured.receiverE2e.p95}ms p99=${measured.receiverE2e.p99}ms`);
console.log(`样本=${report.sampleCount} 失败=${report.failureCount} 报告=${outputPath}`);

if (ENFORCE && !passed) process.exitCode = 1;
