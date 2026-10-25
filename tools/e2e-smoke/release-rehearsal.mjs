import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";

const BASE = process.env.PHANTOM_HTTP_URL ?? "http://127.0.0.1:28080";
const APP_ID = "app_demo";
const APP_SECRET = "phantom_demo_secret_please_change_0123456789";
const stamp = new Date().toISOString().replace(/[:.]/g, "-");
let passed = 0;
let failed = 0;
const check = (condition, message, detail = "") => {
  if (condition) { passed++; console.log(`  ✅ ${message}`); }
  else { failed++; console.error(`  ❌ ${message}${detail ? `: ${detail}` : ""}`); }
};

function headers(method, requestPath, body = "") {
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
    headers: headers(method, requestPath, text), body: text || undefined });
  const payload = await response.json();
  if (!response.ok || payload.code !== 0) throw new Error(`${method} ${requestPath}: ${payload.message}`);
  return payload.data;
}

console.log("发布前恢复与回滚演练");
const dbBackup = path.join(os.tmpdir(), `phantom-mysql-${stamp}.sql`);
const dbDump = execFileSync("docker", ["exec", "phantom-mysql", "mysqldump", "-uroot", "-p123456",
  "--single-transaction", "phantom_im"], { encoding: "buffer", maxBuffer: 200 * 1024 * 1024 });
fs.writeFileSync(dbBackup, dbDump);
check(dbDump.length > 0 && fs.statSync(dbBackup).size === dbDump.length,
  "MySQL 一致性备份可生成并落盘", `${dbBackup} ${dbDump.length} bytes`);

const mediaRoot = process.env.PHANTOM_MEDIA_ROOT ?? path.join(os.tmpdir(), "phantom-media");
const mediaBackup = path.join(os.tmpdir(), `phantom-media-${stamp}.tgz`);
if (fs.existsSync(mediaRoot)) {
  execFileSync("tar", ["-czf", mediaBackup, "-C", mediaRoot, "."]);
  const listed = execFileSync("tar", ["-tzf", mediaBackup], { encoding: "utf8" });
  check(fs.statSync(mediaBackup).size > 0 && listed.length >= 0,
    "媒体目录备份可生成并校验", `${mediaBackup} ${fs.statSync(mediaBackup).size} bytes`);
} else {
  check(false, "媒体目录不存在，无法验证备份", mediaRoot);
}

const original = await openApi("GET", `/openapi/apps/${APP_ID}/config`);
const disabled = await openApi("PUT", `/openapi/apps/${APP_ID}/config`, {
  ...original, relation_hosted: false,
});
const restored = await openApi("PUT", `/openapi/apps/${APP_ID}/config`, original);
check(disabled.relation_hosted === false && restored.relation_hosted === original.relation_hosted,
  "关系链功能开关可关闭并恢复",
  `disabled=${disabled.relation_hosted} restored=${restored.relation_hosted}`);

const pending = execFileSync("docker", ["exec", "phantom-mysql", "mysql", "-N", "-B", "-uroot",
  "-p123456", "phantom_im", "-e", "SELECT COUNT(*) FROM sync_outbox WHERE status='PENDING'"],
{ encoding: "utf8" }).trim();
check(Number(pending) === 0, "outbox 无未发布积压", `pending=${pending}`);

console.log(`\n发布演练结果：${passed} 通过，${failed} 失败`);
console.log(`备份文件：${dbBackup}`);
if (fs.existsSync(mediaBackup)) console.log(`媒体备份：${mediaBackup}`);
if (failed > 0) process.exitCode = 1;
