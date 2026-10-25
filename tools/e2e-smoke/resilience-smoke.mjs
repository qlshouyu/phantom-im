import { execFileSync, spawnSync } from "node:child_process";
import crypto from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import jwt from "jsonwebtoken";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BASE = process.env.PHANTOM_HTTP_URL ?? "http://127.0.0.1:28080";
const APP_ID = "app_demo";
const APP_SECRET = "phantom_demo_secret_please_change_0123456789";
const runId = Date.now().toString(36);
let passed = 0;
let failed = 0;

const check = (condition, message, detail = "") => {
  if (condition) {
    passed++;
    console.log(`  ✅ ${message}`);
  } else {
    failed++;
    console.error(`  ❌ ${message}${detail ? `: ${detail}` : ""}`);
  }
};
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const docker = (...args) => execFileSync("docker", args, { encoding: "utf8" }).trim();
const mysql = (sql) => docker("exec", "phantom-mysql", "mysql", "-N", "-B", "-uroot", "-p123456",
  "phantom_im", "-e", sql);

async function waitFor(label, fn, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try {
      last = await fn();
      if (last) return last;
    } catch (error) {
      last = error;
    }
    await sleep(1_000);
  }
  throw new Error(`${label} 超时: ${last}`);
}

function headers(method, requestPath, body = "") {
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
  const response = await fetch(BASE + requestPath, {
    method, headers: headers(method, requestPath, text), body: text || undefined,
  });
  const payload = await response.json();
  if (!response.ok || payload.code !== 0) throw new Error(`${method} ${requestPath}: ${payload.message}`);
  return payload.data;
}

async function userApi(userId, method, requestPath, body) {
  const token = jwt.sign({ app_id: APP_ID, user_id: userId }, APP_SECRET,
    { algorithm: "HS256", expiresIn: "1h" });
  const text = body === undefined ? "" : JSON.stringify(body);
  const response = await fetch(BASE + requestPath, {
    method,
    headers: { Authorization: `Bearer ${token}`, "X-App-Id": APP_ID, "X-User-Id": userId,
      "X-Device-Id": `resilience-${userId}`, "Content-Type": "application/json" },
    body: text || undefined,
  });
  const payload = await response.json();
  if (!response.ok || payload.code !== 0) throw new Error(`${method} ${requestPath}: ${payload.message}`);
  return payload.data;
}

function runSmoke(label) {
  console.log(`\n[${label}]`);
  const result = spawnSync(process.execPath, [path.join(__dirname, "smoke.mjs")], {
    cwd: __dirname, stdio: "inherit",
  });
  check(result.status === 0, `${label}通过`);
}

await waitFor("服务健康", async () => (await fetch(BASE + "/actuator/health")).ok);

console.log("\n[Redis 重启恢复]");
docker("restart", "phantom-redis");
await waitFor("Redis 恢复", () => docker("exec", "phantom-redis", "redis-cli", "-a", "123456", "ping").includes("PONG"));
runSmoke("Redis 重启后完整冒烟");

console.log("\n[Outbox 暂停与恢复]");
const user = await openApi("POST", "/openapi/users", { external_user_id: `resilience_${runId}` });
const userId = user.user_id ?? user.userId;
const report = await userApi(userId, "POST", "/reports", {
  target_type: "MESSAGE", target_id: `missing_${runId}`, reason: "SPAM", description: "resilience",
});
docker("pause", "rmqproxy");
try {
  await openApi("POST", `/openapi/risk/reports?report_id=${encodeURIComponent(report.report_id)}`,
    { action: "RESOLVED" });
  const pending = Number(mysql("SELECT COUNT(*) FROM sync_outbox WHERE aggregate_type='risk-report' " +
    "AND status='PENDING'"));
  check(pending > 0, "MQ 暂停期间状态事件保留在 outbox", `pending=${pending}`);
} finally {
  docker("unpause", "rmqproxy");
}
await waitFor("outbox 发布", () => Number(mysql(
  "SELECT COUNT(*) FROM sync_outbox WHERE aggregate_type='risk-report' AND status='PENDING'")) === 0);
const eventId = mysql("SELECT outbox_id FROM sync_outbox WHERE aggregate_type='risk-report' " +
  "ORDER BY created_at DESC LIMIT 1");
check(Boolean(eventId), "举报状态事件已发布", eventId);

console.log("\n[MQ 重投幂等]");
mysql(`UPDATE sync_outbox SET status='PENDING', next_retry_at=0 WHERE outbox_id='${eventId}'`);
await waitFor("重投完成", () => mysql(
  `SELECT status FROM sync_outbox WHERE outbox_id='${eventId}'`).includes("PUBLISHED"));
const timelineCount = Number(mysql(
  `SELECT COUNT(*) FROM user_timeline WHERE user_id='${userId}' AND msg_id='${eventId}'`));
check(timelineCount === 1, "相同 event_id 重投只写入一次用户 Timeline", `count=${timelineCount}`);

console.log(`\n韧性测试结果：${passed} 通过，${failed} 失败`);
if (failed > 0) process.exitCode = 1;
