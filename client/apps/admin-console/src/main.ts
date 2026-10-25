import { signOpenApi } from "./hmac";

const API = "";
const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

function credentials() {
  return { appId: ($("credential-app-id") as HTMLInputElement).value,
    secret: ($("credential-secret") as HTMLInputElement).value };
}

async function openApi<T>(method: string, path: string, body?: unknown): Promise<T> {
  const { appId, secret } = credentials();
  const timestamp = Date.now();
  const text = body === undefined ? "" : JSON.stringify(body);
  const signature = await signOpenApi(secret, method, path, text, timestamp);
  const response = await fetch(API + path, {
    method,
    headers: { "Content-Type": "application/json", "X-App-Id": appId,
      "X-Timestamp": String(timestamp), "X-Signature": signature },
    body: text || undefined,
  });
  const payload = await response.json();
  if (!response.ok || payload.code !== 0) throw new Error(payload.message ?? `HTTP ${response.status}`);
  return payload.data as T;
}

function errorText(error: unknown) {
  $("error").textContent = error instanceof Error ? error.message : String(error);
}

async function createApp() {
  $("error").textContent = "";
  const name = ($("new-app-name") as HTMLInputElement).value.trim();
  if (!name) return errorText("请输入应用名称");
  const created = await openApi<any>("POST", "/openapi/apps", { name });
  ($("created-secret") as HTMLElement).textContent = `AppID: ${created.app_id}\nAppSecret: ${created.app_secret}`;
  $("secret-once").hidden = false;
  ($("target-app-id") as HTMLInputElement).value = created.app_id;
}

async function loadDetail() {
  const appId = ($("target-app-id") as HTMLInputElement).value.trim();
  if (!appId) return errorText("请输入目标 App ID");
  const detail = await openApi<any>("GET", `/openapi/apps/${encodeURIComponent(appId)}`);
  ($("app-detail") as HTMLElement).textContent = JSON.stringify(detail, null, 2);
  fillConfig(detail.app.feature_config ?? detail.features ?? {});
}

async function rotate() {
  const appId = ($("target-app-id") as HTMLInputElement).value.trim();
  const graceDays = Number(($("grace-days") as HTMLInputElement).value);
  const result = await openApi<any>("POST", `/openapi/apps/${encodeURIComponent(appId)}/secrets/rotate`,
    { grace_days: graceDays });
  ($("app-detail") as HTMLElement).textContent = JSON.stringify(result, null, 2);
}

function fillConfig(config: any) {
  const set = (id: string, value: unknown) => { ($(id) as HTMLInputElement | HTMLSelectElement).value = String(value); };
  set("relation-hosted", config.relation_hosted ?? true);
  set("read-receipt", config.read_receipt_enabled ?? true);
  set("burn-enabled", config.burn_after_read_enabled ?? false);
  set("burn-ttl", config.burn_after_read_ttl_seconds ?? 60);
  set("profile-write", config.sdk_profile_write_enabled ?? true);
  set("max-devices", config.max_devices ?? 3);
}

async function saveConfig() {
  const appId = ($("target-app-id") as HTMLInputElement).value.trim();
  const value = (id: string) => ($(id) as HTMLInputElement | HTMLSelectElement).value;
  const result = await openApi<any>("PUT", `/openapi/apps/${encodeURIComponent(appId)}/config`, {
    relation_hosted: value("relation-hosted") === "true",
    read_receipt_enabled: value("read-receipt") === "true",
    burn_after_read_enabled: value("burn-enabled") === "true",
    burn_after_read_ttl_seconds: Number(value("burn-ttl")),
    sdk_profile_write_enabled: value("profile-write") === "true",
    max_devices: Number(value("max-devices")),
  });
  ($("app-detail") as HTMLElement).textContent = JSON.stringify(result, null, 2);
}

for (const [id, action] of [["create-app", createApp], ["load-detail", loadDetail],
  ["rotate-secret", rotate], ["save-config", saveConfig]] as const) {
  $(id).addEventListener("click", () => Promise.resolve(action()).catch(errorText));
}
