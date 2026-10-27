#!/usr/bin/env node
/**
 * Easy IM Web 集成契约检查。
 *
 * 校验：
 * 1. phantom-platform/contracts/openapi 中的全部 HTTP 路径都存在于 ApiRoutes.java。
 * 2. SDK 中使用 this.http(...) 调用的 HTTP 路径都存在于 OpenAPI。
 * 3. BFF 代理转发会话鉴权头，SDK HTTP 客户端携带 App/User/Device 身份头。
 * 4. UI 依赖的关键响应字段仍由 OpenAPI 声明。
 * 5. BFF 与 SDK 没有直接绕过代理访问 OpenAPI 管理面。
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = (file) => readFileSync(path.join(ROOT, file), "utf8");

const routesSource = read("phantom-platform/server/phantom-common/src/main/java/im/phantom/common/http/ApiRoutes.java");
const openapi = read("phantom-platform/contracts/openapi/mvp-openapi.yaml");
const sdk = read("phantom-platform/sdks/web/src/index.ts");
const proxy = read("phantom-im/bff/src/main/java/im/phantom/web/phantom/ProxyController.java");

const routePaths = new Set(
  [...routesSource.matchAll(/public static final String\s+\w+\s*=\s*"([^"]+)";/g)].map((match) => match[1]),
);
const openapiPaths = new Set(
  [...openapi.matchAll(/^  (\/[^:]+):\s*$/gm)].map((match) => match[1]),
);
const normalizeRoute = (route) =>
  route.split("?")[0].replace(/\$\{query\}/g, "").replace(/\$\{[^}]+\}/g, "{}").replace(/\{[^}]+\}/g, "{}");
const sdkCalls = [...sdk.matchAll(/this\.http(?:<[^>]+>)?\(\s*["'`](GET|POST|PUT|DELETE)["'`]\s*,\s*([`"'])([^`"']+)\2/g)]
  .map((match) => ({ method: match[1], route: match[3].split("?")[0], canonical: normalizeRoute(match[3]) }));
const openapiCanonicalPaths = new Set([...openapiPaths].map(normalizeRoute));

assert.ok(routePaths.size > 30, "未解析到 ApiRoutes 路径");
assert.ok(openapiPaths.size > 30, "未解析到 OpenAPI 路径");
assert.ok(sdkCalls.length > 30, "未解析到 SDK HTTP 调用");

const openapiMissingRoutes = [...openapiPaths].filter((route) => !routePaths.has(route));
assert.deepEqual(openapiMissingRoutes, [], `OpenAPI 路径缺少 ApiRoutes 常量：\n${openapiMissingRoutes.join("\n")}`);

const routeMissingOpenapi = [...routePaths].filter((route) => !openapiPaths.has(route) && !route.startsWith("/openapi"));
assert.deepEqual(routeMissingOpenapi, [], `用户面 ApiRoutes 路径未写入 OpenAPI：\n${routeMissingOpenapi.join("\n")}`);

const sdkMissingOpenapi = sdkCalls.filter(({ canonical }) => !openapiCanonicalPaths.has(canonical));
assert.deepEqual(sdkMissingOpenapi, [], `SDK 调用了 OpenAPI 未声明的路径：\n${sdkMissingOpenapi.map(({ method, route }) => `${method} ${route}`).join("\n")}`);

assert.ok(!sdk.includes('"/openapi/'), "SDK 不应调用 OpenAPI 管理面");
assert.ok(!proxy.includes('path.startsWith("/openapi")'), "BFF 代理策略应显式拒绝 OpenAPI 管理面");

for (const header of ["Authorization", "X-App-Id", "X-User-Id", "X-Device-Id"]) {
  assert.ok(new RegExp(`["'\`]?${header}["'\`]?\\s*:`).test(sdk), `SDK 缺少 HTTP 身份头 ${header}`);
}
assert.ok(/FORWARD_HEADERS[\s\S]*authorization[\s\S]*user-agent[\s\S]*x-device-id/.test(proxy),
  "BFF 代理未转发 Authorization/User-Agent/X-Device-Id");
assert.ok(/builder\.header\("X-App-Id"/.test(proxy), "BFF 代理未写入受信任的 X-App-Id");
assert.ok(/builder\.header\("X-User-Id"/.test(proxy), "BFF 代理未写入会话用户 X-User-Id");

const requiredFields = [
  "conv_id:", "conv_type:", "title:", "last_message_digest:", "last_message_at:",
  "unread_count:", "mention_me:", "pinned:", "muted:", "draft:", "max_conv_seq:",
  "read_conv_seq:", "read_only:", "client_msg_id:", "conv_seq:", "user_seq:",
  "server_time:", "request_id:", "msg_id:", "is_request:",
];
for (const field of requiredFields) {
  assert.ok(openapi.includes(field), `OpenAPI 缺少 Web UI 依赖字段 ${field}`);
}

const httpMethods = new Set(["GET", "POST", "PUT", "DELETE", "PATCH"]);
const pathMethods = new Map();
let currentPath = null;
for (const line of openapi.split("\n")) {
  const pathMatch = line.match(/^  (\/[^:]+):\s*$/);
  if (pathMatch) {
    currentPath = pathMatch[1];
    pathMethods.set(normalizeRoute(currentPath), new Set());
    continue;
  }
  const methodMatch = line.match(/^    (get|post|put|delete|patch):/);
  if (currentPath && methodMatch) pathMethods.get(normalizeRoute(currentPath)).add(methodMatch[1].toUpperCase());
}
for (const { method, route, canonical } of sdkCalls) {
  assert.ok(httpMethods.has(method), `SDK HTTP 方法无效：${method}`);
  assert.ok(pathMethods.get(canonical)?.has(method), `OpenAPI 未声明 ${method} ${route}`);
}

console.log(`✓ 契约检查通过：${routePaths.size} 个 ApiRoutes、${openapiPaths.size} 个 OpenAPI 路径、${sdkCalls.length} 个 SDK HTTP 调用。`);
