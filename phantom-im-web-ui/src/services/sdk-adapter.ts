import {
  PhantomClient, type ChatMessage, type DeviceType,
} from "@phantom/sdk-web";
import { phantomToken } from "./api";
import { useAppStore } from "../store/app-store";
import type { Account, Conversation } from "../types";

let client: PhantomClient | null = null;
let currentAccount: Account | null = null;
let suppressExpiredRefresh = false;
const typingTimers = new Map<string, ReturnType<typeof setTimeout>>();

function connection(state: string) {
  useAppStore.getState().setConnection(state);
}

function diagnostic(type: string, detail: unknown) {
  useAppStore.getState().addDiagnostic(type, detail);
}

function installEvents(target: PhantomClient) {
  target.on("statechange", connection);
  target.on("message", (message) => {
    useAppStore.getState().upsertMessage(message);
    diagnostic("message", {
      msg_id: message.msgId,
      client_msg_id: message.clientMsgId,
      conv_id: message.convId,
      conv_seq: message.convSeq,
      state: message.state,
    });
    notifyIncoming(message);
  });
  target.on("typing", (event: any) => {
    const convId = event?.convId ?? event?.conv_id;
    if (!convId) return;
    const typing = Boolean(event?.typing);
    useAppStore.getState().setTyping(convId, typing);
    clearTimeout(typingTimers.get(convId));
    if (typing) {
      typingTimers.set(convId, setTimeout(() => {
        useAppStore.getState().setTyping(convId, false);
      }, 5_000));
    }
    diagnostic("typing", event);
  });
  target.on("presence", (event: any) => {
    if (event?.userId) useAppStore.getState().setPresence(event.userId, Boolean(event.online));
    diagnostic("presence", event);
  });
  target.on("kicked", (reason) => {
    useAppStore.getState().setKickedReason(reason || "账号已在其他设备登录");
    connection("kicked");
    diagnostic("kicked", { reason });
  });
  target.on("tokenExpired", () => {
    diagnostic("token-expired", "SDK 报告 UserToken 已过期");
    if (suppressExpiredRefresh) return;
    void refreshTokenAndLogin();
  });
  target.on("sync", (event) => {
    diagnostic("sync", event);
    if (event?.type === "FULL_RESYNC_REQUIRED") void loadConversations();
  });
  for (const event of ["relation", "conversation", "group", "profile"] as const) {
    target.on(event, (payload: any) => {
      diagnostic(event, payload);
      if (event === "relation") window.dispatchEvent(new Event("phantom:relation"));
      if (event === "group") window.dispatchEvent(new Event("phantom:group"));
      if (event === "relation" || event === "group"
          || payload?.type === "FULL_RESYNC_REQUIRED") void loadConversations();
    });
  }
  target.on("notificationClick", (data) => {
    const convId = data.conv_id ?? data.convId;
    if (convId) {
      useAppStore.getState().setNav("chat");
      void openConversation(convId);
    }
  });
}

function notifyIncoming(message: ChatMessage) {
  if (typeof Notification === "undefined" || Notification.permission !== "granted") return;
  if (document.visibilityState === "visible") return;
  if (message.senderId === currentAccount?.phantom_user_id) return;
  const notification = new Notification("Phantom Web IM 新消息", {
    body: message.text,
    tag: message.convId,
  });
  notification.onclick = () => {
    window.focus();
    client?.handleNotificationClick({ conv_id: message.convId, msg_id: message.msgId });
    notification.close();
  };
}

export async function connect(account: Account, options: { expired?: boolean } = {}): Promise<void> {
  const runtime = useAppStore.getState().runtime;
  if (!runtime) throw new Error("运行配置尚未加载");
  disconnect(false);
  currentAccount = account;
  const token = await phantomToken(Boolean(options.expired));
  const next = new PhantomClient({
    url: runtime.ws_url,
    appId: runtime.app_id,
    httpBaseUrl: `${location.origin}${runtime.proxy_base_url}`,
  });
  client = next;
  installEvents(next);
  suppressExpiredRefresh = Boolean(options.expired);
  try {
    await next.login({
      userToken: token.user_token,
      userId: token.phantom_user_id,
      deviceId: `web-${account.account_id}-${crypto.randomUUID()}`,
      deviceType: "WEB" as DeviceType,
    });
  } finally {
    if (options.expired) {
      setTimeout(() => {
        suppressExpiredRefresh = false;
        void refreshTokenAndLogin();
      }, 800);
    } else {
      suppressExpiredRefresh = false;
    }
  }
  await loadConversations();
}

export async function refreshTokenAndLogin(): Promise<void> {
  if (!currentAccount) return;
  const account = currentAccount;
  const runtime = useAppStore.getState().runtime;
  if (!runtime) return;
  try {
    const token = await phantomToken(false);
    const previous = client;
    client = null;
    previous?.logout();
    const next = new PhantomClient({
      url: runtime.ws_url,
      appId: runtime.app_id,
      httpBaseUrl: `${location.origin}${runtime.proxy_base_url}`,
    });
    client = next;
    installEvents(next);
    await next.login({
      userToken: token.user_token,
      userId: token.phantom_user_id,
      deviceId: `web-${account.account_id}-${crypto.randomUUID()}`,
      deviceType: "WEB" as DeviceType,
    });
    await loadConversations();
    diagnostic("token-refreshed", "使用新的 Phantom UserToken 恢复连接");
  } catch (error) {
    diagnostic("token-refresh-failed", String(error));
    connection("auth-failed");
  }
}

export function disconnect(emitState = true): void {
  for (const timer of typingTimers.values()) clearTimeout(timer);
  typingTimers.clear();
  client?.logout();
  client = null;
  currentAccount = null;
  if (emitState) connection("closed");
}

export function getClient(): PhantomClient {
  if (!client) throw new Error("Phantom SDK 尚未连接");
  return client;
}

export async function loadConversations(cursor?: string): Promise<any> {
  if (!client) return { items: [], next_cursor: null, has_more: false };
  const page = await client.listConversations(cursor, 50);
  const items = (page?.items ?? []) as Conversation[];
  if (cursor) useAppStore.getState().appendConversations(items);
  else useAppStore.getState().setConversations(items);
  return page;
}

export async function openConversation(convId: string): Promise<void> {
  if (!client) return;
  const store = useAppStore.getState();
  store.setActiveConversation(convId);
  try {
    const conversation = await client.getConversation(convId) as Conversation;
    store.patchConversation(convId, conversation);
    if (conversation.draft) store.setDraft(convId, conversation.draft);
    const readSeq = Number(conversation.last_conv_seq ?? conversation.max_conv_seq ?? 0);
    if (readSeq > 0) await client.markRead(convId, readSeq);
    store.patchConversation(convId, { unread_count: 0, mention_count: 0 });
  } catch {
    diagnostic("conversation-open", { conv_id: convId, detail: "新会话或快照暂不可用" });
  }
  try {
    await client.history(convId, 0, 50);
    store.mergeMessages(convId, client.getMessages(convId));
  } catch (error) {
    diagnostic("history-load-failed", String(error));
  }
}

export async function loadOlder(convId: string): Promise<boolean> {
  if (!client) return false;
  const list = useAppStore.getState().messagesByConv[convId] ?? [];
  const before = Math.min(...list.filter((item) => item.convSeq > 0).map((item) => item.convSeq));
  const items = await client.history(convId, Number.isFinite(before) ? before : 0, 50);
  useAppStore.getState().mergeMessages(convId, client.getMessages(convId));
  return items.length > 0;
}

export async function sendText(convId: string, text: string, replyToMsgId?: string) {
  return getClient().sendMessage({ convId, kind: "TEXT", content: { text: { text } }, replyToMsgId });
}

export async function sendEmoji(convId: string, emojiId: string) {
  return getClient().sendMessage({ convId, kind: "EMOJI", content: { emoji: { emoji_id: emojiId } } });
}

export async function sendFile(convId: string, file: File,
                               onProgress?: (uploaded: number, total: number) => void,
                               signal?: AbortSignal) {
  const digest = await crypto.subtle.digest("SHA-256", await file.arrayBuffer());
  const sha256 = [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, "0")).join("");
  const kind = file.type.startsWith("image/") ? "IMAGE" : "FILE";
  const upload = await getClient().uploadMedia(file, {
    fileName: file.name,
    sha256,
    mediaType: kind,
    onProgress,
    signal,
  });
  const sizeBytes = upload.sizeBytes || file.size;
  if (kind === "IMAGE") {
    const bitmap = await createImageBitmap(file);
    const content = {
      image: {
        mediaId: upload.mediaId,
        url: upload.url,
        thumbUrl: upload.thumbUrl,
        width: bitmap.width,
        height: bitmap.height,
        sizeBytes,
      },
    };
    bitmap.close();
    return getClient().sendMessage({ convId, kind, content });
  }
  const extension = file.name.includes(".") ? file.name.split(".").pop()! : "";
  return getClient().sendMessage({
    convId,
    kind: "FILE",
    content: { file: { mediaId: upload.mediaId, url: upload.url, fileName: file.name,
      sizeBytes, ext: extension } },
  });
}

export async function triggerExpiredToken(): Promise<void> {
  if (!client || !currentAccount) return;
  const runtime = useAppStore.getState().runtime;
  if (!runtime) return;
  suppressExpiredRefresh = true;
  const account = currentAccount;
  const token = await phantomToken(true);
  const probe = new PhantomClient({
    url: runtime.ws_url,
    appId: runtime.app_id,
    httpBaseUrl: `${location.origin}${runtime.proxy_base_url}`,
  });
  probe.on("statechange", (state: string) => diagnostic("expired-probe-state", state));
  probe.on("tokenExpired", () => diagnostic("token-expired", "测试探针收到 Token 过期回调"));
  try {
    await probe.login({
      userToken: token.user_token,
      userId: token.phantom_user_id,
      deviceId: `web-expired-${account.account_id}-${crypto.randomUUID()}`,
      deviceType: "WEB" as DeviceType,
    });
  } catch {
    // 该失败是诊断场景的预期结果；随后用新 Token 替换连接。
  } finally {
    probe.logout();
    setTimeout(() => {
      suppressExpiredRefresh = false;
      void refreshTokenAndLogin();
    }, 800);
  }
}

export async function remoteDisconnectAndReconnect(): Promise<void> {
  if (!client || !currentAccount) return;
  client.logout();
  connection("closed");
  const account = currentAccount;
  setTimeout(() => { void connect(account); }, 300);
}
