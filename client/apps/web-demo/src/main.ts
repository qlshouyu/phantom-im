import { PhantomClient, singleConvId, type ChatMessage } from "@phantom/sdk-web";
import { devToken } from "./dev-token";

// M0 单应用联调参数（与服务端 application.yml 一致）
const WS_URL = "ws://127.0.0.1:18080/ws";
const APP_ID = "app_demo";
const APP_SECRET = "phantom_demo_secret_please_change_0123456789";

const params = new URLSearchParams(location.search);
const user = params.get("user") ?? "alice";
const peer = params.get("peer") ?? (user === "alice" ? "bob" : "alice");
const userId = "u_" + user;
const peerId = "u_" + peer;
const deviceId = "web-" + user;
const convId = singleConvId(userId, peerId);

const $ = (id: string) => document.getElementById(id)!;
$("who").textContent = `${user} → ${peer}`;

const client = new PhantomClient({ url: WS_URL, appId: APP_ID });

function renderStatus(s: string) {
  const el = $("status");
  el.textContent = s;
  el.className = s;
}
function renderMessage(m: ChatMessage) {
  if (m.convId !== convId) return;
  const key = "m-" + (m.msgId || m.clientMsgId);
  let el = document.querySelector<HTMLDivElement>(`[data-key="${key}"]`);
  if (!el) {
    el = document.createElement("div");
    el.dataset.key = key;
    el.className = "msg " + (m.senderId === userId ? "self" : "peer");
    el.setAttribute("data-testid", "message");
    $("messages").appendChild(el);
  }
  el.innerHTML = `${escapeHtml(m.text)}<small>${m.senderId === userId ? "我" : peer} · ${m.state} · seq=${m.convSeq || "-"}</small>`;
  $("messages").scrollTop = $("messages").scrollHeight;
}
function escapeHtml(s: string) { return s.replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]!)); }

client.on("statechange", renderStatus);
client.on("message", renderMessage);
client.on("kicked", (r) => { renderStatus("kicked:" + r); alert("被踢下线: " + r); });
client.on("tokenExpired", () => alert("Token 过期，需重新获取"));

async function start() {
  const token = await devToken(APP_ID, userId, APP_SECRET);
  await client.login({ userToken: token, userId, deviceId, deviceType: "WEB" });
  client.getMessages(convId).forEach(renderMessage);
}

$("btn-send").addEventListener("click", send);
$("input").addEventListener("keydown", (e) => { if ((e as KeyboardEvent).key === "Enter") send(); });
function send() {
  const input = $("input") as HTMLInputElement;
  const text = input.value.trim();
  if (!text) return;
  input.value = "";
  client.sendText(convId, text);
}

// 演示：断开（不自动重连）/ 重连（重新登录并 SyncReq 补齐离线消息）
$("btn-disconnect").addEventListener("click", () => client.logout());
$("btn-reconnect").addEventListener("click", () => start());

start().catch((e) => { console.error(e); renderStatus("error"); });

// 暴露给 e2e 调试
(window as any).__phantom = client;
