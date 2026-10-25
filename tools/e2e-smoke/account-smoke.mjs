import { createHmac } from "node:crypto";

const baseUrl = process.env.BASE_URL ?? "http://127.0.0.1:28081";
const bootstrapAppId = process.env.BOOTSTRAP_APP_ID ?? "console";
const bootstrapSecret = process.env.BOOTSTRAP_SECRET ?? "phantom-console-dev-secret-change-me";

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function signature(secret, method, path, body, timestamp) {
  return createHmac("sha256", secret).update(method + path + body + timestamp).digest("hex");
}

async function signedRequest(appId, secret, method, path, payload) {
  const body = payload === undefined ? "" : JSON.stringify(payload);
  const timestamp = Date.now();
  const response = await fetch(baseUrl + path, {
    method,
    headers: {
      "content-type": "application/json",
      "x-app-id": appId,
      "x-timestamp": String(timestamp),
      "x-signature": signature(secret, method, path, body, timestamp)
    },
    body: body || undefined
  });
  const text = await response.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { raw: text }; }
  return { status: response.status, json };
}

function ok(response, step) {
  assert(response.status === 200, `${step}: HTTP ${response.status} ${JSON.stringify(response.json)}`);
  assert(response.json.code === 0, `${step}: code=${response.json.code} ${response.json.message}`);
  return response.json.data;
}

const suffix = Date.now();
const app = ok(await signedRequest(bootstrapAppId, bootstrapSecret, "POST", "/openapi/apps",
  { name: `account-smoke-${suffix}` }), "create app");
assert(app.app_id && app.app_secret, "create app did not return app_id/app_secret");

const detail = ok(await signedRequest(app.app_id, app.app_secret, "GET", `/openapi/apps/${app.app_id}`),
  "app detail");
assert(!JSON.stringify(detail).includes(app.app_secret), "app detail leaked AppSecret");

const externalUserId = `U-${suffix}`;
const first = ok(await signedRequest(app.app_id, app.app_secret, "POST", "/openapi/users", {
  external_user_id: externalUserId,
  nickname: "Alice",
  avatar_url: "",
  ext: { source: "account-smoke" }
}), "import user");
assert(first.created === true, "first import should create user");

const second = ok(await signedRequest(app.app_id, app.app_secret, "POST", "/openapi/users", {
  external_user_id: externalUserId,
  nickname: "Alice",
  avatar_url: "",
  ext: {}
}), "repeat import");
assert(second.created === false && second.user_id === first.user_id, "repeat import is not idempotent");

const profile = ok(await signedRequest(app.app_id, app.app_secret, "GET", `/openapi/users/${first.user_id}`),
  "read profile");
assert(profile.nickname === "Alice" && profile.status === "ACTIVE", "profile was not created correctly");

ok(await signedRequest(app.app_id, app.app_secret, "POST", `/openapi/users/${first.user_id}/disable`),
  "disable user");
const disabled = ok(await signedRequest(app.app_id, app.app_secret, "GET", `/openapi/users/${first.user_id}`),
  "read disabled profile");
assert(disabled.status === "DISABLED", "disabled status was not persisted");

ok(await signedRequest(app.app_id, app.app_secret, "DELETE", `/openapi/users/${first.user_id}`),
  "delete user");
const deleted = ok(await signedRequest(app.app_id, app.app_secret, "GET", `/openapi/users/${first.user_id}`),
  "read deleted profile");
assert(deleted.nickname === "已注销用户", "deleted user was not anonymized");

const reimported = ok(await signedRequest(app.app_id, app.app_secret, "POST", "/openapi/users", {
  external_user_id: externalUserId,
  nickname: "Alice2",
  avatar_url: "",
  ext: {}
}), "re-import released external id");
assert(reimported.created === true && reimported.user_id !== first.user_id,
  "released external_user_id was not reusable");

const invalid = await signedRequest(app.app_id, "wrong-secret", "GET", `/openapi/apps/${app.app_id}`);
assert(invalid.status === 401 && invalid.json.code === 2003, "invalid signature was not rejected");

console.log(JSON.stringify({
  ok: true,
  app_id: app.app_id,
  imported_user_id: first.user_id,
  reimported_user_id: reimported.user_id,
  checks: ["one-time-secret", "hmac", "import-idempotency", "disable", "delete-anonymize", "external-id-release"]
}, null, 2));
