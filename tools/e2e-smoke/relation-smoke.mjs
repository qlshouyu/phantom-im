import { createHmac } from "node:crypto";

const baseUrl = process.env.BASE_URL ?? "http://127.0.0.1:28080";
const bootstrapAppId = process.env.BOOTSTRAP_APP_ID ?? "console";
const bootstrapSecret = process.env.BOOTSTRAP_SECRET ?? "phantom-console-dev-secret-change-me";

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function hmac(secret, payload) {
  return createHmac("sha256", secret).update(payload).digest("hex");
}

function base64url(value) {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

function userToken(appId, userId, appSecret) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64url({ alg: "HS256", typ: "JWT" });
  const payload = base64url({ app_id: appId, user_id: userId, iat: now, exp: now + 3600 });
  const signature = createHmac("sha256", appSecret).update(`${header}.${payload}`).digest("base64url");
  return `${header}.${payload}.${signature}`;
}

async function openApiRequest(appId, secret, method, path, payload) {
  const body = payload === undefined ? "" : JSON.stringify(payload);
  const timestamp = Date.now();
  const response = await fetch(baseUrl + path, {
    method,
    headers: {
      "content-type": "application/json",
      "x-app-id": appId,
      "x-timestamp": String(timestamp),
      "x-signature": hmac(secret, method + path + body + timestamp)
    },
    body: body || undefined
  });
  return parse(response);
}

async function userRequest(token, appId, userId, method, path, payload) {
  const response = await fetch(baseUrl + path, {
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

async function parse(response) {
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

function biz(response, code, step) {
  assert(response.json.code === code, `${step}: expected code ${code}, got ${JSON.stringify(response.json)}`);
}

const suffix = Date.now();
const app = ok(await openApiRequest(bootstrapAppId, bootstrapSecret, "POST", "/openapi/apps", {
  name: `relation-smoke-${suffix}`
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
const aliceToken = userToken(app.app_id, alice.user_id, app.app_secret);
const bobToken = userToken(app.app_id, bob.user_id, app.app_secret);

const card = ok(await userRequest(aliceToken, app.app_id, alice.user_id, "GET", `/users/${bob.user_id}`),
  "read user card");
assert(card.nickname === "Bob" && card.friend === false, "user card mismatch");

const created = ok(await userRequest(aliceToken, app.app_id, alice.user_id, "POST", "/friends/requests", {
  to_user_id: bob.user_id,
  verification_message: "relation smoke"
}), "create friend request");
assert(created.status === "PENDING", "friend request should be pending");

const inbox = ok(await userRequest(bobToken, app.app_id, bob.user_id, "GET", "/friends/requests"),
  "list requests");
assert(inbox.unread_count >= 1, "friend request unread count missing");
assert(inbox.items.some((item) => item.request_id === created.request_id), "request not visible to receiver");

ok(await userRequest(bobToken, app.app_id, bob.user_id, "POST", `/friends/requests/${created.request_id}`, {
  action: "ACCEPT"
}), "accept request");
assert(ok(await userRequest(aliceToken, app.app_id, alice.user_id, "GET", "/friends"), "alice friends")
  .some((friend) => friend.user_id === bob.user_id), "accepted friend missing from alice list");

const group = ok(await userRequest(aliceToken, app.app_id, alice.user_id, "POST", "/friends/groups", {
  name: "同事"
}), "create friend group");
const updated = ok(await userRequest(aliceToken, app.app_id, alice.user_id, "PUT", `/friends/${bob.user_id}`, {
  remark: "Bob-备注",
  pinned: true,
  group_ids: [group.group_id]
}), "update friend");
assert(updated.display_name === "Bob-备注" && updated.pinned === true, "remark/pinned not applied");
assert(updated.group_ids.includes(group.group_id), "friend group not applied");

ok(await userRequest(aliceToken, app.app_id, alice.user_id, "DELETE", `/friends/${bob.user_id}`),
  "delete friend one-way");
assert(ok(await userRequest(aliceToken, app.app_id, alice.user_id, "GET", "/friends"), "alice after delete")
  .length === 0, "alice should no longer see bob");
assert(ok(await userRequest(bobToken, app.app_id, bob.user_id, "GET", "/friends"), "bob after alice delete")
  .some((friend) => friend.user_id === alice.user_id), "one-way delete removed reverse relation");

ok(await userRequest(aliceToken, app.app_id, alice.user_id, "POST", "/blacklist", {
  user_id: bob.user_id
}), "blacklist bob");
assert(ok(await userRequest(bobToken, app.app_id, bob.user_id, "GET", "/friends"), "bob after blacklist")
  .length === 0, "blacklist did not remove reverse relation");
biz(await userRequest(bobToken, app.app_id, bob.user_id, "POST", "/friends/requests", {
  to_user_id: alice.user_id,
  verification_message: "blocked"
}), 3004, "blocked friend request");

ok(await userRequest(aliceToken, app.app_id, alice.user_id, "DELETE", "/blacklist", {
  user_id: bob.user_id
}), "unblock bob");
assert(ok(await userRequest(aliceToken, app.app_id, alice.user_id, "GET", "/blacklist"), "blacklist list")
  .length === 0, "blacklist entry was not removed");

const qr = ok(await userRequest(aliceToken, app.app_id, alice.user_id, "GET", "/friends/qr"), "get qr");
const refreshed = ok(await userRequest(aliceToken, app.app_id, alice.user_id, "POST", "/friends/qr/refresh"),
  "refresh qr");
assert(qr.token !== refreshed.token, "qr refresh did not rotate token");
biz(await userRequest(bobToken, app.app_id, bob.user_id, "POST", "/friends/qr/scan", {
  token: qr.token
}), 1001, "old qr token must be invalid");
const scanned = ok(await userRequest(bobToken, app.app_id, bob.user_id, "POST", "/friends/qr/scan", {
  token: refreshed.token
}), "scan refreshed qr");
assert(scanned.user.user_id === alice.user_id, "qr scan returned wrong user");

console.log(JSON.stringify({
  ok: true,
  app_id: app.app_id,
  alice_id: alice.user_id,
  bob_id: bob.user_id,
  checks: ["user-card", "request", "unread", "accept", "remark", "group", "one-way-delete", "blacklist", "unblock", "qr-refresh"]
}, null, 2));
