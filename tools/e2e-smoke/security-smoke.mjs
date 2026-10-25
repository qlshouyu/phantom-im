import crypto from "node:crypto";
import jwt from "jsonwebtoken";

const BASE = process.env.PHANTOM_HTTP_URL ?? "http://127.0.0.1:28080";
const APP_ID = "app_demo";
const APP_SECRET = "phantom_demo_secret_please_change_0123456789";
const ADMIN_TOKEN = process.env.PHANTOM_RISK_ADMIN_TOKEN ?? "phantom-local-admin-token";
const runId = Date.now().toString(36);
let passed = 0;
let failed = 0;

function check(condition, message, detail = "") {
  if (condition) {
    passed++;
    console.log(`  ✅ ${message}`);
  } else {
    failed++;
    console.error(`  ❌ ${message}${detail ? `: ${detail}` : ""}`);
  }
}

function signatureHeaders(method, path, body = "", timestamp = Date.now()) {
  const signature = crypto.createHmac("sha256", APP_SECRET)
    .update(method + path + body + timestamp).digest("hex");
  return {
    "Content-Type": "application/json",
    "X-App-Id": APP_ID,
    "X-Timestamp": String(timestamp),
    "X-Signature": signature,
  };
}

async function request(path, options = {}) {
  const response = await fetch(BASE + path, options);
  const payload = await response.json().catch(() => ({}));
  return { status: response.status, payload };
}

async function openApi(method, path, body, timestamp) {
  const text = body === undefined ? "" : JSON.stringify(body);
  return request(path, { method, headers: signatureHeaders(method, path, text, timestamp), body: text || undefined });
}

function userToken(userId, appId = APP_ID, secret = APP_SECRET) {
  return jwt.sign({ app_id: appId, user_id: userId }, secret, { algorithm: "HS256", expiresIn: "1h" });
}

async function userApi(userId, method, path, body, token = userToken(userId)) {
  const text = body === undefined ? "" : JSON.stringify(body);
  return request(path, { method, headers: {
    Authorization: `Bearer ${token}`,
    "X-App-Id": APP_ID,
    "X-User-Id": userId,
    "X-Device-Id": `security-${userId}`,
    "Content-Type": "application/json",
  }, body: text || undefined });
}

async function importUser(label) {
  const response = await openApi("POST", "/openapi/users", { external_user_id: `${label}_${runId}` });
  if (response.status >= 300 || response.payload.code !== 0) {
    throw new Error(`用户导入失败: ${JSON.stringify(response.payload)}`);
  }
  return response.payload.data.user_id ?? response.payload.data.userId;
}

async function uploadFile(userId, content) {
  const sha256 = crypto.createHash("sha256").update(content).digest("hex");
  const init = await userApi(userId, "POST", "/media/upload/init", {
    file_name: "security.txt", size_bytes: content.length, sha256,
    mime_type: "text/plain", media_type: "FILE",
  });
  const session = init.payload.data;
  const uploadId = session.upload_id ?? session.uploadId;
  const partResponse = await fetch(BASE + `/media/upload/${uploadId}/part/1`, {
    method: "PUT",
    headers: {
      Authorization: `Bearer ${userToken(userId)}`,
      "X-App-Id": APP_ID,
      "X-User-Id": userId,
      "X-Device-Id": `security-${userId}`,
      "Content-Type": "application/octet-stream",
    },
    body: content,
  });
  if (partResponse.status >= 300) throw new Error(`分片上传失败: ${await partResponse.text()}`);
  const complete = await userApi(userId, "POST", `/media/upload/${uploadId}/complete`);
  return complete.payload.data.media_id ?? complete.payload.data.mediaId;
}

console.log("OpenAPI 重放、JWT/跨应用、媒体越权、搜索与举报审计安全检查");
const alice = await importUser("security_alice");
const bob = await importUser("security_bob");

const replayPath = `/openapi/users/${alice}`;
const replayTimestamp = Date.now();
const replayFirst = await openApi("GET", replayPath, undefined, replayTimestamp);
const replaySecond = await openApi("GET", replayPath, undefined, replayTimestamp);
check(replayFirst.status < 300 && replaySecond.status === 401,
  "相同 OpenAPI 签名仅首次成功，重放被拒绝",
  `first=${replayFirst.status} second=${replaySecond.status}`);

const wrongSecret = await userApi(alice, "GET", "/users/me", undefined,
  userToken(alice, APP_ID, "wrong-secret"));
const crossApp = await userApi(alice, "GET", "/users/me", undefined,
  userToken(alice, "other_app", APP_SECRET));
check(wrongSecret.status === 401 && crossApp.status === 401,
  "错误签名与跨应用 UserToken 均被拒绝",
  `wrong=${wrongSecret.status} cross=${crossApp.status}`);

const mediaId = await uploadFile(alice, Buffer.from(`media-${runId}`));
const mediaDenied = await userApi(bob, "GET", `/media/${mediaId}`);
check(mediaDenied.status === 403,
  "非会话成员无法访问媒体", `status=${mediaDenied.status}`);

const anonymousSearch = await request(`/search?q=${encodeURIComponent(runId)}`);
check(anonymousSearch.status === 401, "未认证搜索被拒绝", `status=${anonymousSearch.status}`);

const report = await userApi(alice, "POST", "/reports", {
  target_type: "MESSAGE", target_id: `missing_${runId}`, reason: "SPAM", description: "security",
});
const queue = await openApi("GET", `/openapi/risk/reports?limit=100`);
const queued = queue.payload.data?.find((item) => item.report_id === report.payload.data?.report_id);
const handled = queued
  ? await openApi("POST", `/openapi/risk/reports?report_id=${encodeURIComponent(queued.report_id)}`,
    { action: "RESOLVED" })
  : undefined;
const reports = await userApi(alice, "GET", "/reports");
const updated = reports.payload.data?.find((item) => item.report_id === report.payload.data?.report_id);
check(Boolean(queued) && handled?.status < 300 && updated?.status === "RESOLVED",
  "举报进入审核队列、处理幂等并回写结果",
  `queued=${Boolean(queued)} handled=${handled?.status} status=${updated?.status}`);

const badAudit = await fetch(`${BASE}/openapi/risk/audit`, {
  headers: { ...signatureHeaders("GET", "/openapi/risk/audit"), "X-Admin-Token": "wrong" },
});
const goodAuditPath = "/openapi/risk/audit?limit=100";
const goodAudit = await request(goodAuditPath, {
  headers: { ...signatureHeaders("GET", goodAuditPath), "X-Admin-Token": ADMIN_TOKEN },
});
const auditEntries = goodAudit.payload.data ?? [];
check(badAudit.status === 403 && goodAudit.status < 300
    && auditEntries.some((entry) => entry.action === "REPORT_SUBMIT"),
  "举报审计仅管理员可读且包含提交记录",
  `bad=${badAudit.status} good=${goodAudit.status}`);

const appDetail = await openApi("GET", `/openapi/apps/${APP_ID}`);
const serialized = JSON.stringify(appDetail.payload);
check(appDetail.status < 300 && !serialized.includes(APP_SECRET)
    && !serialized.toLowerCase().includes("app_secret"),
  "应用详情不返回 AppSecret 明文");

console.log(`\n安全检查结果：${passed} 通过，${failed} 失败`);
if (failed > 0) process.exitCode = 1;
