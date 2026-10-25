import { PhantomClient, singleConvId, type ChatMessage } from "@phantom/sdk-web";
import { devToken } from "./dev-token";
import { ensureDevUser } from "./dev-openapi";

// M0 单应用联调参数（与服务端 application.yml 一致）
const WS_URL = "ws://127.0.0.1:28080/ws";
const APP_ID = "app_demo";
const APP_SECRET = "phantom_demo_secret_please_change_0123456789";
const HTTP_URL = "";

const params = new URLSearchParams(location.search);
const user = params.get("user") ?? "alice";
const peer = params.get("peer") ?? (user === "alice" ? "bob" : "alice");
const device = params.get("device") ?? "1";
const useExpiredToken = params.get("expired") === "1";
let userId = "u_" + user;
let peerId = "u_" + peer;
const deviceId = "web-" + user + "-" + device;
let convId = singleConvId(userId, peerId);
let replyTarget = "";
let selectedGroupId = "";
const groupMemberNames = new Map<string, string>();

const $ = (id: string) => document.getElementById(id)!;
$("who").textContent = `${user} → ${peer}`;

const client = new PhantomClient({ url: WS_URL, appId: APP_ID, httpBaseUrl: location.origin });
let reportTarget = "";

function renderStatus(s: string) {
  const el = $("status");
  el.textContent = s;
  el.className = s;
}
function renderMessage(m: ChatMessage) {
  if (m.convId !== convId) return;
  // client_msg_id 在发送前后保持不变，作为稳定 DOM key，避免回填 msg_id 后产生双气泡。
  const key = `m-${m.senderId}-${m.clientMsgId}`;
  let el = document.querySelector<HTMLDivElement>(`[data-key="${key}"]`);
  if (!el) {
    el = document.createElement("div");
    el.dataset.key = key;
    el.className = "msg " + (m.senderId === userId ? "self" : "peer");
    el.setAttribute("data-testid", "message");
    $("messages").appendChild(el);
  }
  const quote = m.replySnapshot?.digest
    ? `<div class="quote" data-testid="quoted-message">引用：${escapeHtml(m.replySnapshot.digest)}</div>`
    : m.replyToMsgId
      ? `<div class="quote" data-testid="quoted-message">引用：${escapeHtml(
          client.getMessages(convId).find((item) => item.msgId === m.replyToMsgId)?.text ?? m.replyToMsgId)}</div>`
      : "";
  el.innerHTML = `${quote}${escapeHtml(m.text)}<small>${escapeHtml(senderLabel(m))} · ${m.state} · seq=${m.convSeq || "-"}${m.restricted ? " · 可能未送达" : ""}</small>
    <div class="msg-actions">
      ${m.senderId === userId && !m.recalled ? `<button data-action="recall" data-msg-id="${escapeHtml(m.msgId)}">撤回</button>` : ""}
      <button data-action="forward" data-msg-id="${escapeHtml(m.msgId)}">转发</button>
      <button data-action="quote" data-msg-id="${escapeHtml(m.msgId)}">引用</button>
      <button data-action="report" data-msg-id="${escapeHtml(m.msgId)}">举报</button>
      <button data-action="delete" data-msg-id="${escapeHtml(m.msgId)}">本地删除</button>
    </div>`;
  $("messages").scrollTop = $("messages").scrollHeight;
}
function escapeHtml(s: string) { return s.replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]!)); }

function senderLabel(message: ChatMessage): string {
  if (message.convType === "GROUP") {
    return groupMemberNames.get(message.senderId)
      ?? (message.senderId === userId ? "我" : message.senderId);
  }
  return message.senderId === userId ? "我" : peer;
}

client.on("statechange", renderStatus);
client.on("message", (message: ChatMessage) => {
  renderMessage(message);
  loadConversations().catch(console.error);
});
let tokenExpired = false;
client.on("kicked", (r) => renderStatus("kicked:" + r));
client.on("tokenExpired", () => { tokenExpired = true; renderStatus("token-expired"); });
client.on("typing", (event: any) => {
  if (event?.convId === convId) renderStatus(event.typing ? "对方正在输入…" : "online");
});
client.on("conversation", (event: any) => {
  if (event?.type === "FULL_RESYNC_REQUIRED") loadConversations().catch(console.error);
});
client.on("relation", () => { loadContacts().catch(console.error); });
client.on("notificationClick", (data) => {
  if (data.conv_id) {
    convId = data.conv_id;
    renderStatus("notification:" + convId);
  }
});

async function start() {
  userId = await ensureDevUser(HTTP_URL, APP_ID, APP_SECRET, user);
  peerId = await ensureDevUser(HTTP_URL, APP_ID, APP_SECRET, peer);
  convId = singleConvId(userId, peerId);
  (window as any).__convId = convId;
  const token = await devToken(APP_ID, userId, APP_SECRET, useExpiredToken);
  await client.login({ userToken: token, userId, deviceId, deviceType: "WEB" });
  client.getMessages(convId).forEach(renderMessage);
  await Promise.allSettled([loadConversations(), loadContacts(), loadGroups(), loadDevices(), loadPushSettings(), loadReports()]);
  try {
    const conversation = await client.getConversation(convId);
    if (conversation?.draft) ($("input") as HTMLInputElement).value = conversation.draft;
  } catch { /* 新会话尚无快照 */ }
}

$("btn-send").addEventListener("click", send);
$("input").addEventListener("keydown", (e) => { if ((e as KeyboardEvent).key === "Enter") send(); });
function send() {
  const input = $("input") as HTMLInputElement;
  const text = input.value.trim();
  if (!text) return;
  input.value = "";
  const message = replyTarget
    ? client.sendMessage({ convId, kind: "TEXT", content: { text: { text } }, replyToMsgId: replyTarget })
    : client.sendText(convId, text);
  replyTarget = "";
  message.catch((error) => { console.error(error); renderStatus("send-error"); });
}

let typingTimer: any;
$("input").addEventListener("input", () => {
  client.sendTyping(convId, true).catch(() => {});
  clearTimeout(typingTimer);
  typingTimer = setTimeout(() => client.sendTyping(convId, false).catch(() => {}), 3_000);
  clearTimeout((window as any).draftTimer);
  (window as any).draftTimer = setTimeout(() => {
    const draft = ($("input") as HTMLInputElement).value;
    client.saveDraft(convId, draft).catch(console.error);
  }, 2_000);
});

$("file").addEventListener("change", async () => {
  const file = ($("file") as HTMLInputElement).files?.[0];
  if (!file) return;
  try {
    const hash = await crypto.subtle.digest("SHA-256", await file.arrayBuffer());
    const sha256 = [...new Uint8Array(hash)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
    const type: "IMAGE" | "VOICE" | "VIDEO" | "FILE" = file.type.startsWith("image/")
      ? "IMAGE" : file.type.startsWith("audio/") ? "VOICE"
        : file.type.startsWith("video/") ? "VIDEO" : "FILE";
    renderStatus("uploading");
    const session = await client.uploadMedia(file, {
      fileName: file.name, sha256, mediaType: type,
      durationSeconds: type === "VOICE" || type === "VIDEO" ? 1 : undefined,
    });
    const media = await client.getMedia(session.mediaId);
    const extra = type === "IMAGE"
      ? { width: media.width, height: media.height, size_bytes: file.size }
      : type === "VOICE"
        ? { duration_s: media.durationSeconds ?? 1 }
        : type === "VIDEO"
          ? { duration_s: media.durationSeconds ?? 1, size_bytes: file.size,
              width: media.width ?? 0, height: media.height ?? 0, cover_url: session.thumbUrl ?? "" }
          : { file_name: file.name, size_bytes: file.size };
    await client.sendMedia(convId, type, session, extra);
    renderStatus(`media-sent:${type}`);
  } catch (error) {
    renderStatus("upload-error:" + (error instanceof Error ? error.message : String(error)));
    console.error(error);
  }
});

$("messages").addEventListener("click", async (event) => {
  const target = (event.target as HTMLElement).closest<HTMLButtonElement>("button[data-action]");
  if (!target) return;
  const msgId = target.dataset.msgId ?? "";
  try {
    if (target.dataset.action === "recall") await client.recall(msgId);
    if (target.dataset.action === "delete") await client.deleteLocal(msgId), renderStatus("online");
    if (target.dataset.action === "forward") {
      const destination = window.prompt("转发到 conv_id", convId);
      if (destination) await client.forward([msgId], [destination]);
    }
    if (target.dataset.action === "quote") {
      replyTarget = msgId;
      $("input").focus();
      renderStatus("quoting:" + msgId);
    }
    if (target.dataset.action === "report") {
      reportTarget = msgId;
      $("report-panel").hidden = false;
    }
  } catch (error) { console.error(error); renderStatus("action-error"); }
});

$("submit-report").addEventListener("click", async () => {
  if (!reportTarget) return;
  try {
    await client.report("MESSAGE", reportTarget, ($("report-reason") as HTMLSelectElement).value);
    $("report-panel").hidden = true;
    renderStatus("reported");
    await loadReports();
  } catch (error) {
    console.error(error);
    renderStatus("report-error:" + (error instanceof Error ? error.message : String(error)));
  }
});

async function loadConversations() {
  const page = await client.listConversations();
  const list = $("conversation-list");
  list.innerHTML = "";
  for (const item of page.items ?? []) {
    const row = document.createElement("div");
    row.className = "item";
    row.dataset.testid = "conversation-item";
    row.innerHTML = `<span data-op="open">${escapeHtml(item.title || item.conv_id)} · ${item.unread_count ?? 0}</span>
      <button data-op="pin">${item.pinned ? "取消置顶" : "置顶"}</button>
      <button data-op="mute">${item.muted ? "取消免打扰" : "免打扰"}</button>
      <button data-op="delete">删除</button>`;
    row.querySelector('[data-op="pin"]')?.addEventListener("click", () => client.updateConversation(item.conv_id, { pinned: !item.pinned }).then(loadConversations));
    row.querySelector('[data-op="mute"]')?.addEventListener("click", () => client.updateConversation(item.conv_id, { muted: !item.muted }).then(loadConversations));
    row.querySelector('[data-op="delete"]')?.addEventListener("click", () => client.deleteConversation(item.conv_id).then(loadConversations));
    row.querySelector('[data-op="open"]')?.addEventListener("click", () => openConversation(item.conv_id, item.title));
    list.appendChild(row);
  }
}

async function loadContacts() {
  const [friends, requests, blacklist] = await Promise.all([
    client.listFriends(), client.listFriendRequests(), client.listBlacklist(),
  ]);
  $("friend-list").innerHTML = friends.map((friend: any) => `<div class="item" data-testid="friend-item">${escapeHtml(friend.display_name || friend.nickname || friend.user_id)} <small>${escapeHtml(friend.user_id)}</small></div>`).join("");
  $("request-list").innerHTML = (requests.items ?? []).map((request: any) => `<div class="item" data-testid="friend-request">${escapeHtml(request.from_user_id)}
    <button data-action="accept-request" data-request-id="${escapeHtml(request.request_id)}">接受</button>
    <button data-action="reject-request" data-request-id="${escapeHtml(request.request_id)}">拒绝</button></div>`).join("");
  $("blacklist").innerHTML = blacklist.map((item: any) => `<div class="item" data-testid="blacklist-item">${escapeHtml(item.user_id || item.userId)}
    <button data-action="unblock" data-user-id="${escapeHtml(item.user_id || item.userId)}">解除拉黑</button></div>`).join("");
}

async function loadGroups() {
  const groups = await client.listGroups();
  $("group-list").innerHTML = groups.map((group: any) => `<div class="item" data-testid="group-item">
    <button data-group-open="${escapeHtml(group.group_id)}" data-conv-id="${escapeHtml(group.conv_id)}">${escapeHtml(group.name)} · ${group.member_count} 人</button></div>`).join("");
}

async function loadDevices() {
  const devices = await client.listDevices();
  $("device-list").innerHTML = devices.map((device: any) => `<div class="item" data-testid="device-item">${escapeHtml(device.device_name || device.device_id)} · ${escapeHtml(device.platform || "")}
    <button data-device-logout="${escapeHtml(device.device_id)}">远程下线</button></div>`).join("");
}

async function loadReports() {
  const reports = await client.listReports();
  $("report-list").innerHTML = reports.map((report: any) => `<div class="item" data-testid="report-item">${escapeHtml(report.targetType || report.target_type)} · ${escapeHtml(report.status)}</div>`).join("");
}

async function loadPushSettings() {
  const settings = await client.getPushSettings();
  ($("push-enabled") as HTMLInputElement).checked = settings.push_enabled;
  ($("preview-enabled") as HTMLInputElement).checked = settings.preview_enabled;
  $("notification-permission").textContent = client.getNotificationPermission();
}

$("add-friend").addEventListener("click", async () => {
  await client.createFriendRequest(($("friend-id") as HTMLInputElement).value.trim());
  await loadContacts();
});
$("request-list").addEventListener("click", async (event) => {
  const button = (event.target as HTMLElement).closest<HTMLButtonElement>("button[data-action]");
  if (!button) return;
  const action = button.dataset.action === "accept-request" ? "ACCEPT" : "REJECT";
  await client.handleFriendRequest(button.dataset.requestId ?? "", action);
  await loadContacts();
});
$("block-user").addEventListener("click", async () => {
  await client.blockUser(($("blacklist-id") as HTMLInputElement).value.trim());
  await loadContacts();
});
$("blacklist").addEventListener("click", async (event) => {
  const button = (event.target as HTMLElement).closest<HTMLButtonElement>("button[data-action='unblock']");
  if (!button) return;
  await client.unblockUser(button.dataset.userId ?? "");
  await loadContacts();
});
$("create-group").addEventListener("click", async () => {
  const members = ($("group-members") as HTMLInputElement).value.split(",").map((value) => value.trim()).filter(Boolean);
  await client.createGroup(($("group-name") as HTMLInputElement).value.trim(), members);
  await loadGroups();
});

$("group-list").addEventListener("click", async (event) => {
  const button = (event.target as HTMLElement).closest<HTMLButtonElement>("button[data-group-open]");
  if (!button) return;
  selectedGroupId = button.dataset.groupOpen ?? "";
  openConversation(button.dataset.convId ?? "", button.textContent ?? "群聊");
  $("selected-group").textContent = `当前群：${selectedGroupId}`;
  await loadGroupMemberNames(selectedGroupId);
  try {
    const detail = await client.getGroup(selectedGroupId);
    if (detail.announcement?.content) $("group-announcement-input").setAttribute("value", detail.announcement.content);
  } catch { /* 群详情按需加载 */ }
});
$("group-invite").addEventListener("click", async () => {
  if (!selectedGroupId) return;
  await client.inviteGroupMembers(selectedGroupId, [($("group-invite-id") as HTMLInputElement).value.trim()]);
  renderStatus("group-invited");
});
$("group-announce").addEventListener("click", async () => {
  if (!selectedGroupId) return;
  await client.updateAnnouncement(selectedGroupId, ($("group-announcement-input") as HTMLInputElement).value.trim());
  renderStatus("group-announced");
});
$("group-nickname-save").addEventListener("click", async () => {
  if (!selectedGroupId) return;
  const targetUserId = ($("group-nickname-id") as HTMLInputElement).value.trim();
  const nickname = ($("group-nickname") as HTMLInputElement).value.trim();
  await client.updateGroupNickname(selectedGroupId,
    targetUserId, nickname);
  groupMemberNames.set(targetUserId, nickname);
  client.getMessages(convId).forEach(renderMessage);
  renderStatus("group-nickname-saved");
});
$("group-mute").addEventListener("click", async () => {
  if (!selectedGroupId) return;
  await client.muteGroupMember(selectedGroupId, ($("group-nickname-id") as HTMLInputElement).value.trim() || null, 600);
  renderStatus("group-muted");
});
$("group-leave").addEventListener("click", async () => {
  if (!selectedGroupId) return;
  await client.leaveGroup(selectedGroupId);
  selectedGroupId = "";
  renderStatus("group-left");
  await loadGroups();
});
$("group-dissolve").addEventListener("click", async () => {
  if (!selectedGroupId) return;
  await client.dissolveGroup(selectedGroupId);
  selectedGroupId = "";
  renderStatus("group-dissolved");
  await loadGroups();
});
$("device-list").addEventListener("click", async (event) => {
  const button = (event.target as HTMLElement).closest<HTMLButtonElement>("button[data-device-logout]");
  if (!button) return;
  await client.remoteLogout(button.dataset.deviceLogout ?? "");
  renderStatus("device-removed");
  await loadDevices();
});

function openConversation(targetConvId: string, title = "") {
  convId = targetConvId;
  replyTarget = "";
  $("messages").innerHTML = "";
  client.getMessages(convId).forEach(renderMessage);
  $("who").textContent = title || targetConvId;
  renderStatus("conversation:" + convId);
}

async function loadGroupMemberNames(groupId: string) {
  try {
    const members = await client.listGroupMembers(groupId);
    groupMemberNames.clear();
    for (const member of members) {
      groupMemberNames.set(member.userId ?? member.user_id,
        member.groupNickname ?? member.group_nickname ?? "");
    }
  } catch { /* 群成员按需加载 */ }
}

function renderSearchResult(result: any) {
  const total = (result?.contactTotal ?? result?.contact_total ?? 0)
    + (result?.groupTotal ?? result?.group_total ?? 0)
    + (result?.messageTotal ?? result?.message_total ?? result?.total ?? 0);
  if (!result || total === 0) {
    $("search-results").textContent = "未找到相关结果";
    return;
  }
  const contacts = (result.contacts ?? []).map((hit: any) =>
    `<div data-testid="search-contact" data-user-id="${escapeHtml(hit.userId ?? hit.user_id ?? "")}">联系人：${escapeHtml(hit.remark || hit.nickname || hit.userId || hit.user_id || "")}</div>`).join("");
  const groups = (result.groups ?? []).map((hit: any) =>
    `<button data-testid="search-group" data-conv-id="${escapeHtml(hit.convId ?? hit.conv_id ?? "")}">群聊：${escapeHtml(hit.name)}</button>`).join("");
  const messages = (result.messages ?? []).map((hit: any) =>
    `<button data-testid="search-message" data-conv-id="${escapeHtml(hit.convId ?? hit.conv_id ?? "")}" data-conv-seq="${hit.convSeq ?? hit.conv_seq}">消息：${escapeHtml(hit.digest)}</button>`).join("");
  $("search-results").innerHTML = contacts + groups + messages;
}

$("search-query").addEventListener("input", async () => {
  const query = ($("search-query") as HTMLInputElement).value.trim();
  if (!query) {
    $("search-results").textContent = "";
    return;
  }
  renderSearchResult(await client.searchSuggestionsDebounced(query, 300));
});
$("search-btn").addEventListener("click", async () => {
  const query = ($("search-query") as HTMLInputElement).value.trim();
  renderSearchResult(await client.searchDebounced(query, 300));
});
$("search-results").addEventListener("click", (event) => {
  const target = (event.target as HTMLElement).closest<HTMLElement>("[data-conv-id]");
  if (!target) return;
  openConversation(target.dataset.convId ?? convId);
  renderStatus(`search-open:${convId}:${target.dataset.convSeq ?? ""}`);
});
$("save-push").addEventListener("click", () => client.updatePushSettings(
  ($("push-enabled") as HTMLInputElement).checked,
  ($("preview-enabled") as HTMLInputElement).checked));
$("request-notification").addEventListener("click", async () => {
  $("notification-permission").textContent = await client.requestNotificationPermission();
});

for (const tab of document.querySelectorAll<HTMLButtonElement>("nav button[data-panel]")) {
  tab.addEventListener("click", () => {
    document.querySelectorAll("nav button").forEach((button) => button.classList.remove("active"));
    document.querySelectorAll(".panel").forEach((panel) => panel.classList.remove("active"));
    tab.classList.add("active");
    const panel = tab.dataset.panel;
    if (panel !== "chat") $(`panel-${panel}`).classList.add("active");
  });
}

// 演示：断开（不自动重连）/ 重连（重新登录并 SyncReq 补齐离线消息）
$("btn-disconnect").addEventListener("click", () => client.logout());
$("btn-reconnect").addEventListener("click", () => start());

start().catch((e) => { console.error(e); if (!tokenExpired) renderStatus("error"); });

// 暴露给 e2e 调试
(window as any).__phantom = client;
