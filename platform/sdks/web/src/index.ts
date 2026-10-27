// Phantom IM 官方 JS Web SDK（MVP 参考实现）。
// WSS+Protobuf 负责实时指令/推送/同步；HTTP+JSON 负责 CRUD、漫游、搜索、媒体和设备。
import * as P from "./proto";

export { singleConvId } from "./proto";

export type DeviceType = "MOBILE" | "DESKTOP" | "WEB";
const DEVICE_TYPE_VALUE: Record<DeviceType, number> = { MOBILE: 1, DESKTOP: 2, WEB: 3 };
const CONV_TYPE_VALUE = { SINGLE: 1, GROUP: 2 } as const;
const MSG_TYPE_VALUE: Record<MessageKind, number> = {
  TEXT: 1, EMOJI: 2, IMAGE: 3, VOICE: 4, VIDEO: 5, FILE: 6,
  LOCATION: 7, CARD: 8, MERGED: 9, SYSTEM: 10,
};

export type MessageKind = "TEXT" | "EMOJI" | "IMAGE" | "VOICE" | "VIDEO" | "FILE" |
  "LOCATION" | "CARD" | "MERGED" | "SYSTEM";
export type MessageState = "sending" | "sent" | "delivered" | "read" | "failed" | "recalled" | "burned";

export interface LoginOptions {
  userToken: string;
  userId: string;
  deviceId: string;
  deviceType?: DeviceType;
}

export interface SendMessageInput {
  convId: string;
  convType?: "SINGLE" | "GROUP";
  kind?: MessageKind;
  content: Record<string, unknown>;
  replyToMsgId?: string;
  mentions?: string[];
  clientMsgId?: string;
}

export interface ChatMessage {
  msgId: string;
  clientMsgId: string;
  convId: string;
  convType: "SINGLE" | "GROUP";
  senderId: string;
  kind: MessageKind;
  content: Record<string, any>;
  text: string;
  convSeq: number;
  serverTime: number;
  state: MessageState;
  recalled: boolean;
  restricted: boolean;
  isRequest: boolean;
  replyToMsgId?: string;
  replySnapshot?: Record<string, any>;
  mentions: string[];
  burnAfterReadTtlSeconds: number;
}

function compareMessages(a: ChatMessage, b: ChatMessage): number {
  const aPending = !Number.isFinite(a.convSeq) || a.convSeq <= 0;
  const bPending = !Number.isFinite(b.convSeq) || b.convSeq <= 0;
  if (aPending !== bPending) return aPending ? 1 : -1;
  return (a.convSeq || 0) - (b.convSeq || 0)
    || a.serverTime - b.serverTime
    || (a.clientMsgId || a.msgId).localeCompare(b.clientMsgId || b.msgId);
}

export interface MediaUploadOptions {
  fileName: string;
  sizeBytes: number;
  sha256: string;
  mimeType: string;
  mediaType: "IMAGE" | "VOICE" | "VIDEO" | "FILE";
  durationSeconds?: number;
  width?: number;
  height?: number;
  burnAfterRead?: boolean;
  burnAfterReadTtlSeconds?: number;
  signal?: AbortSignal;
  onProgress?: (uploadedBytes: number, totalBytes: number) => void;
}

export interface UploadSession {
  uploadId: string;
  partSize: number;
  sizeBytes: number;
  totalParts: number;
  uploadedParts: number[];
  instant: boolean;
  mediaId?: string;
  url?: string;
  thumbUrl?: string;
}

type ConnState = "idle" | "connecting" | "authing" | "online" | "reconnecting" | "closed";
type EventMap = {
  connected: () => void;
  disconnected: () => void;
  message: (m: ChatMessage) => void;
  kicked: (reason: string) => void;
  tokenExpired: () => void;
  statechange: (s: ConnState) => void;
  sync: (event: any) => void;
  relation: (event: any) => void;
  conversation: (event: any) => void;
  group: (event: any) => void;
  profile: (event: any) => void;
  presence: (event: any) => void;
  typing: (event: any) => void;
  notificationClick: (data: Record<string, string>) => void;
};

const SEND_TIMEOUT_MS = 15_000;
const HEARTBEAT_MS = 25_000;
const MAX_BACKOFF_MS = 60_000;
const CACHE_VERSION = 3;
const PERSIST_DELAY_MS = 200;

class Emitter {
  private map = new Map<string, Function[]>();
  on<K extends keyof EventMap>(event: K, fn: EventMap[K]): this {
    const handlers = this.map.get(event) ?? [];
    handlers.push(fn);
    this.map.set(event, handlers);
    return this;
  }
  off<K extends keyof EventMap>(event: K, fn: EventMap[K]): this {
    this.map.set(event, (this.map.get(event) ?? []).filter((item) => item !== fn));
    return this;
  }
  emit<K extends keyof EventMap>(event: K, ...args: Parameters<EventMap[K]>): void {
    for (const handler of this.map.get(event) ?? []) (handler as any)(...args);
  }
}

export class PhantomClient extends Emitter {
  private readonly wsUrl: string;
  private readonly httpBaseUrl: string;
  private readonly appId: string;
  private readonly fetchImpl: typeof fetch;
  private ws?: WebSocket;
  private opts?: LoginOptions;
  private cid = 0;
  private pending = new Map<number, { resolve: (e: any) => void; reject: (err: any) => void; timer: any }>();
  private messages = new Map<string, ChatMessage[]>();
  private conversations = new Map<string, any>();
  private locallyDeleted = new Set<string>();
  private seen = new Set<string>();
  private cursor = 0;
  private storeUserId?: string;
  private state: ConnState = "idle";
  private kicked = false;
  private authFailed = false;
  private backoff = 1000;
  private hbTimer: any;
  private appConfig?: Record<string, any>;
  private searchSequence = 0;
  private searchTimer: any;
  private persistTimer: ReturnType<typeof setTimeout> | null = null;
  private persistPending = false;
  private readonly pagehideHandler = () => this.flushPersistence();

  constructor(options: { url: string; appId: string; httpBaseUrl?: string; fetch?: typeof fetch }) {
    super();
    this.wsUrl = options.url;
    this.appId = options.appId;
    this.httpBaseUrl = options.httpBaseUrl ?? options.url.replace(/^ws/, "http").replace(/\/ws\/?$/, "");
    this.fetchImpl = options.fetch ?? globalThis.fetch.bind(globalThis);
    if (typeof window !== "undefined" && typeof window.addEventListener === "function") {
      window.addEventListener("pagehide", this.pagehideHandler);
    }
  }

  getState(): ConnState { return this.state; }
  getCurrentUserId(): string | undefined { return this.opts?.userId; }
  getAppConfig(): Record<string, any> | undefined { return this.appConfig; }
  getMessages(convId: string): ChatMessage[] { return [...(this.messages.get(convId) ?? [])]; }

  async login(options: LoginOptions): Promise<void> {
    this.flushPersistence();
    this.opts = options;
    this.kicked = false;
    this.authFailed = false;
    this.cursor = this.loadCursor(options.userId);
    this.loadStore(options.userId);
    await this.openAndAuth();
  }

  private setState(state: ConnState): void { this.state = state; this.emit("statechange", state); }

  private openAndAuth(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.setState(this.state === "reconnecting" ? "reconnecting" : "connecting");
      const ws = new WebSocket(this.wsUrl);
      ws.binaryType = "arraybuffer";
      this.ws = ws;
      ws.onopen = async () => {
        this.setState("authing");
        try {
          const response = await this.auth();
          if (response.code !== 0) {
            this.authFailed = true;
            if (response.code === 2001) this.emit("tokenExpired");
            reject(new Error("auth failed code=" + response.code));
            try { ws.close(); } catch { /* noop */ }
            return;
          }
          this.appConfig = response.appConfig;
          this.backoff = 1000;
          this.setState("online");
          this.emit("connected");
          this.startHeartbeat();
          if (Number(response.maxUserSeq || 0) > this.cursor) await this.syncLoop();
          else if (this.cursor === 0) {
            try { await this.fullResync(); }
            catch { this.emit("conversation", { type: "SNAPSHOT_UNAVAILABLE" }); }
          }
          resolve();
        } catch (error) { reject(error); }
      };
      ws.onmessage = (event) => this.onFrame(new Uint8Array(event.data as ArrayBuffer));
      ws.onclose = () => this.onClose();
      ws.onerror = () => { /* onclose drives reconnect */ };
    });
  }

  private async auth(): Promise<any> {
    const opts = this.opts!;
    const payload = P.AuthReq.encode({
      accessToken: opts.userToken, deviceId: opts.deviceId,
      deviceType: DEVICE_TYPE_VALUE[opts.deviceType ?? "WEB"], platform: "web",
      clientVersion: "0.2.0", appId: this.appId, userId: opts.userId,
    }).finish();
    const env = await this.request(P.Command.COMMAND_AUTH_REQ, payload);
    return env.code === 0 ? { code: 0, ...P.AuthResp.decode(env.payload) } : { code: env.code };
  }

  async sendText(convId: string, text: string): Promise<ChatMessage> {
    return this.sendMessage({ convId, kind: "TEXT", content: { text: { text } } });
  }

  /** 统一发送 API：所有消息类型复用幂等、乐观态、超时失败和失败重发语义。 */
  async sendMessage(input: SendMessageInput): Promise<ChatMessage> {
    if (!this.opts) throw new Error("not logged in");
    const clientMsgId = input.clientMsgId ?? "c-" + crypto.randomUUID();
    const convType = input.convType ?? (input.convId.startsWith("gr_") ? "GROUP" : "SINGLE");
    const kind = input.kind ?? "TEXT";
    const existing = [...this.messages.values()].flat().find((item) => item.clientMsgId === clientMsgId);
    const message: ChatMessage = existing ?? {
      msgId: "", clientMsgId, convId: input.convId, convType,
      senderId: this.opts.userId, kind, content: input.content,
      text: this.messageText(input.content, MSG_TYPE_VALUE[kind]), convSeq: 0,
      serverTime: Date.now(), state: "sending",
      recalled: false, restricted: false, isRequest: false,
      replyToMsgId: input.replyToMsgId, mentions: input.mentions ?? [], burnAfterReadTtlSeconds: 0,
    };
    message.state = "sending";
    message.kind = kind;
    message.content = input.content;
    message.text = this.messageText(input.content, MSG_TYPE_VALUE[kind]);
    if (!existing) this.appendLocal(message);
    this.emit("message", message);
    try {
      const content = P.MsgContent.decode(P.MsgContent.encode(input.content).finish());
      const payload = P.SendMsgReq.encode({
        clientMsgId, convId: input.convId,
        convType: CONV_TYPE_VALUE[convType], msgType: MSG_TYPE_VALUE[kind], content,
        replyToMsgId: input.replyToMsgId ?? "", mentions: input.mentions ?? [],
      }).finish();
      const env = await this.request(P.Command.COMMAND_SEND_MSG_REQ, payload);
      if (env.code !== 0) {
        message.state = "failed";
        this.emit("message", message);
        return message;
      }
      const response = P.SendMsgResp.decode(env.payload) as any;
      message.msgId = response.msgId;
      message.convSeq = Number(response.convSeq);
      message.serverTime = Number(response.serverTime);
      message.state = "sent";
      this.seen.add(message.msgId);
      this.reorder(message.convId);
      this.emit("message", message);
      return message;
    } catch {
      message.state = "failed";
      this.emit("message", message);
      return message;
    }
  }

  async resend(message: ChatMessage): Promise<void> {
    await this.sendMessage({ convId: message.convId, convType: message.convType,
      kind: message.kind, content: message.content, clientMsgId: message.clientMsgId,
      replyToMsgId: message.replyToMsgId, mentions: message.mentions });
  }

  async recall(msgId: string): Promise<any> {
    const env = await this.request(P.Command.COMMAND_RECALL_MSG_REQ,
      P.RecallMsgReq.encode({ msgId }).finish());
    if (env.code !== 0) throw this.protocolError(env.code);
    return P.RecallMsgResp.decode(env.payload);
  }

  async forward(msgIds: string[], targetConvIds: string[], merged = false): Promise<any> {
    return this.http("POST", "/messages/forward", { msg_ids: msgIds, target_conv_ids: targetConvIds, merged });
  }

  async deleteLocal(msgId: string): Promise<void> {
    await this.http("DELETE", `/messages/${encodeURIComponent(msgId)}`);
    this.locallyDeleted.add(msgId);
    for (const [convId, messages] of this.messages) {
      this.messages.set(convId, messages.filter((message) => message.msgId !== msgId));
    }
    this.persistStore();
  }

  async clearHistory(convId: string): Promise<void> {
    await this.http("DELETE", `/conversations/${encodeURIComponent(convId)}/messages`);
    this.messages.set(convId, []);
    this.persistStore();
  }

  async history(convId: string, beforeConvSeq = 0, limit = 50): Promise<any[]> {
    const page = await this.http<any>("GET",
      `/conversations/${encodeURIComponent(convId)}/messages?before_conv_seq=${beforeConvSeq}&limit=${limit}`);
    const items = (page?.items ?? []).filter((item: any) => !this.locallyDeleted.has(item.msg_id));
    for (const item of items) this.mergeHistoryItem(item);
    return items;
  }

  async uploadMedia(file: Blob, options: Omit<MediaUploadOptions, "sizeBytes" | "mimeType"> & {
    sizeBytes?: number; mimeType?: string;
  }): Promise<UploadSession> {
    const session = this.normalizeUpload(await this.http<any>("POST", "/media/upload/init", {
      file_name: options.fileName, size_bytes: options.sizeBytes ?? file.size,
      sha256: options.sha256, mime_type: options.mimeType ?? file.type,
      media_type: options.mediaType, duration_seconds: options.durationSeconds,
      width: options.width, height: options.height, burn_after_read: options.burnAfterRead,
      burn_after_read_ttl_seconds: options.burnAfterReadTtlSeconds,
    }));
    if (session.instant) return session;
    const uploaded = new Set(session.uploadedParts);
    let uploadedBytes = uploaded.size * session.partSize;
    for (let part = 1; part <= session.totalParts; part++) {
      if (uploaded.has(part)) continue;
      const start = (part - 1) * session.partSize;
      const end = Math.min(start + session.partSize, session.sizeBytes);
      const bytes = new Uint8Array(await file.slice(start, end).arrayBuffer());
      await this.uploadPart(session.uploadId, part, bytes, options.signal);
      uploadedBytes += bytes.byteLength;
      options.onProgress?.(uploadedBytes, session.sizeBytes);
    }
    return this.normalizeUpload(await this.http<any>("POST",
      `/media/upload/${encodeURIComponent(session.uploadId)}/complete`));
  }

  async uploadPart(uploadId: string, partNumber: number, bytes: Uint8Array, signal?: AbortSignal): Promise<any> {
    return this.http("PUT", `/media/upload/${encodeURIComponent(uploadId)}/part/${partNumber}`, bytes, { signal });
  }

  async getMedia(mediaId: string): Promise<any> {
    const media = await this.http<any>("GET", `/media/${encodeURIComponent(mediaId)}`);
    return {
      ...media,
      mediaId: media.media_id ?? media.mediaId,
      mediaType: media.media_type ?? media.mediaType,
      mimeType: media.mime_type ?? media.mimeType,
      sizeBytes: media.size_bytes ?? media.sizeBytes,
      thumbUrl: media.thumb_url ?? media.thumbUrl,
      durationSeconds: media.duration_seconds ?? media.durationSeconds,
      fileName: media.file_name ?? media.fileName,
    };
  }

  /** 使用当前登录用户的短时签名读取媒体内容，适用于 <img>/<video> 等无法附加自定义头的场景。 */
  async getMediaContent(mediaId: string, variant: "original" | "thumb" = "thumb"): Promise<Blob> {
    const media = await this.getMedia(mediaId);
    const path = variant === "thumb" ? media.thumbUrl ?? media.url : media.url;
    if (!path) throw new Error("media url missing");
    const response = await this.fetchImpl(`${this.httpBaseUrl}${path}`, {
      method: "GET",
      headers: this.httpHeaders(),
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.blob();
  }

  private normalizeUpload(value: any): UploadSession {
    return {
      uploadId: value.upload_id ?? value.uploadId ?? "",
      partSize: Number(value.part_size ?? value.partSize ?? 0),
      sizeBytes: Number(value.size_bytes ?? value.sizeBytes ?? 0),
      totalParts: Number(value.total_parts ?? value.totalParts ?? 0),
      uploadedParts: value.uploaded_parts ?? value.uploadedParts ?? [],
      instant: Boolean(value.instant),
      mediaId: value.media_id ?? value.mediaId,
      url: value.url,
      thumbUrl: value.thumb_url ?? value.thumbUrl,
    };
  }

  async sendMedia(convId: string, kind: "IMAGE" | "VOICE" | "VIDEO" | "FILE",
                  media: UploadSession, extra: Record<string, unknown> = {}): Promise<ChatMessage> {
    const raw = { media_id: media.mediaId, url: media.url, thumb_url: media.thumbUrl,
      cover_url: media.thumbUrl, ...extra };
    const normalized = Object.fromEntries(Object.entries(raw).map(([key, value]) => [
      key.replace(/_([a-z])/g, (_, letter: string) => letter.toUpperCase()), value,
    ]));
    const content = { [kind.toLowerCase()]: normalized };
    return this.sendMessage({ convId, kind, content });
  }

  async listConversations(cursor?: string, limit = 50): Promise<any> {
    const query = new URLSearchParams({ limit: String(limit) });
    if (cursor) query.set("cursor", cursor);
    const page = await this.http<any>("GET", `/conversations?${query}`);
    for (const item of page.items ?? []) this.conversations.set(item.conv_id, item);
    return page;
  }
  async getConversation(convId: string): Promise<any> { return this.http("GET", `/conversations/${encodeURIComponent(convId)}`); }
  getConversationState(convId: string): any | undefined { return this.conversations.get(convId); }
  async updateConversation(convId: string, patch: { pinned?: boolean; muted?: boolean }): Promise<any> {
    this.optimisticConversation(convId, patch);
    try { return await this.http("POST", `/conversations/${encodeURIComponent(convId)}`, patch); }
    catch (error) { this.rollbackConversation(convId, patch); throw error; }
  }
  async deleteConversation(convId: string): Promise<void> { await this.http("DELETE", `/conversations/${encodeURIComponent(convId)}`); }
  async markRead(convId: string, readConvSeq: number): Promise<any> {
    return this.http("PUT", `/conversations/${encodeURIComponent(convId)}/read-positions`, { read_conv_seq: readConvSeq });
  }
  async saveDraft(convId: string, draft: string, editedAt = Date.now()): Promise<any> {
    return this.http("PUT", `/conversations/${encodeURIComponent(convId)}/draft`, { draft, edited_at: editedAt });
  }
  async clearDraft(convId: string, editedAt = Date.now()): Promise<any> {
    return this.http("DELETE", `/conversations/${encodeURIComponent(convId)}/draft`, { edited_at: editedAt });
  }
  async handleMessageRequest(convId: string, accept: boolean): Promise<any> {
    return this.http("POST", `/conversations/${encodeURIComponent(convId)}/request`, { accept });
  }

  async getUserProfile(userId = this.opts?.userId): Promise<any> {
    return this.http("GET", userId === this.opts?.userId ? "/users/me" : `/users/${encodeURIComponent(userId!)}`);
  }
  async updateProfile(profile: Record<string, unknown>): Promise<any> { return this.http("PUT", "/users/me", profile); }
  async listFriends(): Promise<any[]> { return this.http("GET", "/friends"); }
  async createFriendRequest(toUserId: string, verificationMessage = ""): Promise<any> {
    return this.http("POST", "/friends/requests", { to_user_id: toUserId, verification_message: verificationMessage });
  }
  async listFriendRequests(includeHandled = false): Promise<any> {
    return this.http("GET", `/friends/requests?include_handled=${includeHandled}`);
  }
  async handleFriendRequest(requestId: string, action: "ACCEPT" | "REJECT" | "IGNORE"): Promise<any> {
    return this.http("POST", `/friends/requests/${encodeURIComponent(requestId)}`, { action });
  }
  async updateFriend(friendId: string, request: Record<string, unknown>): Promise<any> {
    return this.http("PUT", `/friends/${encodeURIComponent(friendId)}`, request);
  }
  async deleteFriend(friendId: string): Promise<void> { await this.http("DELETE", `/friends/${encodeURIComponent(friendId)}`); }
  async listBlacklist(): Promise<any[]> { return this.http("GET", "/blacklist"); }
  async blockUser(userId: string): Promise<void> { await this.http("POST", "/blacklist", { user_id: userId }); }
  async unblockUser(userId: string): Promise<void> { await this.http("DELETE", "/blacklist", { user_id: userId }); }
  async getQrCard(): Promise<any> { return this.http("GET", "/friends/qr"); }
  async refreshQrCard(): Promise<any> { return this.http("POST", "/friends/qr/refresh"); }
  async scanQrCard(token: string): Promise<any> { return this.http("POST", "/friends/qr/scan", { token }); }

  async listGroups(): Promise<any[]> { return this.http("GET", "/groups"); }
  async createGroup(name: string, memberIds: string[]): Promise<any> {
    return this.http("POST", "/groups", { name, member_ids: memberIds });
  }
  async getGroup(groupId: string): Promise<any> { return this.http("GET", `/groups/${encodeURIComponent(groupId)}`); }
  async listGroupMembers(groupId: string): Promise<any[]> { return this.http("GET", `/groups/${encodeURIComponent(groupId)}/members`); }
  async inviteGroupMembers(groupId: string, userIds: string[]): Promise<void> {
    await this.http("POST", `/groups/${encodeURIComponent(groupId)}/members`, { user_ids: userIds });
  }
  async removeGroupMember(groupId: string, userId: string): Promise<void> {
    await this.http("DELETE", `/groups/${encodeURIComponent(groupId)}/members?user_id=${encodeURIComponent(userId)}`);
  }
  async updateGroupRole(groupId: string, userId: string, role: "ADMIN" | "MEMBER"): Promise<void> {
    await this.http("PUT", `/groups/${encodeURIComponent(groupId)}/members/${encodeURIComponent(userId)}/role`, { role });
  }
  async transferGroupOwner(groupId: string, userId: string, oldOwnerRole: "ADMIN" | "MEMBER" = "MEMBER"): Promise<any> {
    return this.http("POST", `/groups/${encodeURIComponent(groupId)}/transfer`, { user_id: userId, old_owner_role: oldOwnerRole });
  }
  async leaveGroup(groupId: string): Promise<void> {
    await this.http("DELETE", `/groups/${encodeURIComponent(groupId)}/leave`);
  }
  async dissolveGroup(groupId: string): Promise<void> { await this.http("DELETE", `/groups/${encodeURIComponent(groupId)}`); }
  async muteGroupMember(groupId: string, userId: string | null, durationSeconds: number | null): Promise<void> {
    await this.http("POST", `/groups/${encodeURIComponent(groupId)}/mutes`, { user_id: userId, duration_seconds: durationSeconds });
  }
  async updateAnnouncement(groupId: string, content: string): Promise<any> {
    return this.http("PUT", `/groups/${encodeURIComponent(groupId)}/announcement`, { content });
  }
  async updateGroupNickname(groupId: string, userId: string, nickname: string): Promise<void> {
    await this.http("PUT", `/groups/${encodeURIComponent(groupId)}/members/${encodeURIComponent(userId)}/nickname`, { nickname });
  }

  async listDevices(): Promise<any[]> { return this.http("GET", "/devices"); }
  async remoteLogout(deviceId: string): Promise<void> { await this.http("POST", `/devices/${encodeURIComponent(deviceId)}/logout`); }
  async registerPushToken(token: string, deviceType: DeviceType, provider: string): Promise<void> {
    await this.http("POST", "/push/tokens", { token, device_type: DEVICE_TYPE_VALUE[deviceType], provider });
  }
  async unregisterPushToken(token: string): Promise<void> { await this.http("DELETE", "/push/tokens", { token }); }
  async getPushSettings(): Promise<any> { return this.http("GET", "/push/settings"); }
  async updatePushSettings(pushEnabled: boolean, previewEnabled: boolean): Promise<any> {
    return this.http("PUT", "/push/settings", { push_enabled: pushEnabled, preview_enabled: previewEnabled });
  }
  getNotificationPermission(): NotificationPermission | "unsupported" {
    return typeof Notification === "undefined" ? "unsupported" : Notification.permission;
  }
  async requestNotificationPermission(): Promise<NotificationPermission | "unsupported"> {
    if (typeof Notification === "undefined") return "unsupported";
    return Notification.requestPermission();
  }

  async search(query: string, limit = 20): Promise<any> {
    return this.http("GET", `/search?q=${encodeURIComponent(query)}&limit=${limit}`);
  }
  async searchSuggestions(query: string, limit = 10): Promise<any> {
    return this.http("GET", `/search/suggest?q=${encodeURIComponent(query)}&limit=${limit}`);
  }

  /** 输入防抖：后发请求覆盖先发请求，旧响应不会覆盖新关键词结果。 */
  searchDebounced(query: string, waitMs = 300, limit = 20): Promise<any> {
    clearTimeout(this.searchTimer);
    const sequence = ++this.searchSequence;
    return new Promise((resolve, reject) => {
      this.searchTimer = setTimeout(() => {
        this.search(query, limit).then((result) => {
          if (sequence === this.searchSequence) resolve(result);
          else resolve({ contacts: [], groups: [], messages: [], contactTotal: 0,
            groupTotal: 0, messageTotal: 0, stale: true });
        }).catch((error) => sequence === this.searchSequence ? reject(error) : resolve(null));
      }, Math.max(0, waitMs));
    });
  }
  searchSuggestionsDebounced(query: string, waitMs = 300, limit = 10): Promise<any> {
    clearTimeout(this.searchTimer);
    const sequence = ++this.searchSequence;
    return new Promise((resolve, reject) => {
      this.searchTimer = setTimeout(() => {
        this.searchSuggestions(query, limit).then((result) => {
          if (sequence === this.searchSequence) resolve(result);
          else resolve({ contacts: [], groups: [], total: 0, stale: true });
        }).catch((error) => sequence === this.searchSequence ? reject(error) : resolve(null));
      }, Math.max(0, waitMs));
    });
  }
  async searchConversation(convId: string, query: string, limit = 20): Promise<any> {
    return this.http("GET", `/conversations/${encodeURIComponent(convId)}/search?q=${encodeURIComponent(query)}&limit=${limit}`);
  }
  async report(targetType: "MESSAGE" | "USER" | "GROUP", targetId: string,
               reason: string, description = ""): Promise<any> {
    return this.http("POST", "/reports", { target_type: targetType, target_id: targetId, reason, description });
  }
  async listReports(): Promise<any[]> { return this.http("GET", "/reports"); }
  async submitAppeal(sanctionId: string, content: string): Promise<any> {
    return this.http("POST", "/appeals", { sanction_id: sanctionId, content });
  }
  async listAppeals(appealId?: string): Promise<any[]> {
    return this.http("GET", "/appeals" + (appealId ? `?appeal_id=${encodeURIComponent(appealId)}` : ""));
  }
  async exportAccount(): Promise<any[]> { return this.http("POST", "/account/export"); }
  async getAccountExports(): Promise<any[]> { return this.http("GET", "/account/export"); }
  async setMinorMode(enabled: boolean): Promise<void> { await this.http("POST", "/account/minor-mode", { enabled }); }
  async getPrivacyLinks(): Promise<any> { return this.http("GET", "/account/privacy-links"); }
  async updatePrivacyLinks(privacyPolicyUrl: string, termsUrl: string): Promise<any> {
    return this.http("PUT", "/account/privacy-links", { privacy_policy_url: privacyPolicyUrl, terms_url: termsUrl });
  }

  async sendTyping(convId: string, typing: boolean): Promise<void> {
    this.rawSend(P.Command.COMMAND_TYPING_SIGNAL, P.TypingSignal.encode({ convId, typing }).finish());
  }
  async subscribePresence(userIds: string[]): Promise<any> {
    const env = await this.request(P.Command.COMMAND_PRESENCE_SUB_REQ,
      P.PresenceSubReq.encode({ userIds }).finish());
    if (env.code === 0) {
      const response = P.PresenceSubResp.decode(env.payload) as any;
      for (const presence of response.presences ?? []) this.emit("presence", presence);
      return response;
    }
    throw this.protocolError(env.code);
  }
  async unsubscribePresence(userIds: string[]): Promise<void> {
    this.rawSend(P.Command.COMMAND_PRESENCE_UNSUB_REQ, P.PresenceUnsubReq.encode({ userIds }).finish());
  }
  async setAppState(foreground: boolean): Promise<void> {
    await this.request(P.Command.COMMAND_APP_STATE_REQ, P.AppStateReq.encode({ foreground }).finish());
  }

  handleNotificationClick(data: Record<string, string>): void {
    this.emit("notificationClick", data);
    if (data.conv_id) this.emit("conversation", { type: "NOTIFICATION_CLICK", convId: data.conv_id });
  }

  logout(): void {
    this.flushPersistence();
    this.kicked = true;
    this.stopHeartbeat();
    try { this.ws?.close(); } catch { /* noop */ }
    if (this.opts) {
      this.removeKey(this.cursorKey(this.opts.userId));
      this.removeKey(this.messagesKey(this.opts.userId));
    }
    this.messages.clear();
    this.locallyDeleted.clear();
    this.seen.clear();
    this.cursor = 0;
    this.opts = undefined;
    this.storeUserId = undefined;
    this.appConfig = undefined;
    this.setState("closed");
  }

  private onFrame(bytes: Uint8Array): void {
    const env = P.Envelope.decode(bytes) as any;
    const cid = Number(env.cid);
    if (cid && this.pending.has(cid)) {
      const pending = this.pending.get(cid)!;
      this.pending.delete(cid);
      clearTimeout(pending.timer);
      pending.resolve(env);
      return;
    }
    switch (env.cmd) {
      case P.Command.COMMAND_MSG_PUSH: this.onPush(env); break;
      case P.Command.COMMAND_SYNC_EVENT_PUSH: this.onSyncPush(env); break;
      case P.Command.COMMAND_KICK_PUSH: this.onKick(env); break;
      case P.Command.COMMAND_TYPING_PUSH: this.emit("typing", P.TypingPush.decode(env.payload)); break;
      case P.Command.COMMAND_PRESENCE_PUSH:
        this.emit("presence", (P.PresencePush.decode(env.payload) as any).presence);
        break;
      default: break;
    }
  }

  private onPush(env: any): void {
    const push = P.MsgPush.decode(env.payload) as any;
    const userSeq = Number(push.userSeq);
    this.rawSend(P.Command.COMMAND_MSG_ACK, P.MsgAck.encode({ userSeq }).finish());
    if (userSeq > this.cursor) {
      this.applyNewMessage(push.msg);
      this.cursor = userSeq;
      this.saveCursor();
    }
  }

  private onSyncPush(env: any): void {
    const push = P.SyncEventPush.decode(env.payload) as any;
    this.applySyncEvent(push.event);
    this.cursor = Math.max(this.cursor, Number(push.event?.userSeq ?? 0));
    this.saveCursor();
    this.rawSend(P.Command.COMMAND_MSG_ACK, P.MsgAck.encode({ userSeq: this.cursor }).finish());
  }

  private onKick(env: any): void {
    const kick = P.KickPush.decode(env.payload) as any;
    const reason = P.KickPush.lookupEnum("KickReason").valuesById?.[kick.reason] ?? String(kick.reason);
    this.kicked = true;
    this.stopHeartbeat();
    this.setState("closed");
    this.emit("kicked", reason);
    try { this.ws?.close(); } catch { /* noop */ }
  }

  private applyNewMessage(msg: any): void {
    if (!msg?.msgId || this.seen.has(msg.msgId)) return;
    const existing = (this.messages.get(msg.convId) ?? []).find((item) => item.clientMsgId === msg.clientMsgId);
    if (existing) {
      existing.msgId = msg.msgId;
      existing.convSeq = Number(msg.convSeq);
      existing.serverTime = Number(msg.serverTime);
      existing.state = this.stateFromProto(msg.status);
      this.seen.add(msg.msgId);
      this.reorder(msg.convId);
      this.emit("message", {...existing});
      return;
    }
    const message = this.fromProtoMessage(msg);
    this.seen.add(msg.msgId);
    this.appendLocal(message);
    this.reorder(message.convId);
    this.emit("message", message);
  }

  private applySyncEvent(event: any): void {
    if (!event) return;
    if (event.newMessage?.msg) this.applyNewMessage(event.newMessage.msg);
    if (event.recall) this.markRecalled(event.recall.msgId);
    if (event.msgStatus) this.applyStatus(event.msgStatus);
    if (event.restrictedDelivery) this.applyRestricted(event.restrictedDelivery);
    this.emit("sync", event);
    if (event.relation) this.emit("relation", event.relation);
    if (event.group) this.emit("group", event.group);
    if (event.profile) this.emit("profile", event.profile);
    if (event.convState || event.draft || event.readReceipt || event.readPosition) {
      this.emit("conversation", event);
    }
  }

  private markRecalled(msgId: string): void {
    for (const [convId, messages] of this.messages) {
      const message = messages.find((item) => item.msgId === msgId);
      if (message) {
        message.recalled = true;
        message.state = "recalled";
        message.text = "消息已撤回";
        this.reorder(convId);
        this.emit("message", {...message});
      }
    }
  }

  private applyStatus(status: any): void {
    for (const messages of this.messages.values()) {
      const message = messages.find((item) => item.msgId === status.msgId);
      if (message) {
        message.state = this.stateFromProto(status.status);
        this.emit("message", {...message});
      }
    }
  }

  private applyRestricted(event: any): void {
    for (const messages of this.messages.values()) {
      const message = messages.find((item) => item.msgId === event.msgId);
      if (message) {
        message.restricted = true;
        this.emit("message", {...message});
      }
    }
  }

  private async syncLoop(): Promise<void> {
    let hasMore = true;
    while (hasMore) {
      const env = await this.request(P.Command.COMMAND_SYNC_REQ,
        P.SyncReq.encode({ sinceUserSeq: this.cursor, limit: 200 }).finish());
      if (env.code !== 0) throw this.protocolError(env.code);
      const response = P.SyncResp.decode(env.payload) as any;
      if (response.fullResync) {
        this.messages.clear();
        this.conversations.clear();
        this.seen.clear();
        this.cursor = 0;
        this.emit("conversation", { type: "FULL_RESYNC_REQUIRED" });
        await this.fullResync();
        return;
      }
      for (const event of response.events ?? []) {
        const sequence = Number(event.userSeq);
        if (sequence <= this.cursor) continue;
        this.applySyncEvent(event);
        this.cursor = Math.max(this.cursor, sequence);
      }
      this.saveCursor();
      hasMore = Boolean(response.hasMore);
    }
  }

  /** 新设备或游标过期时按会话快照 + 历史分页重建，并重置到服务端 max_user_seq。 */
  private async fullResync(): Promise<void> {
    let cursor: string | undefined;
    do {
      const page = await this.listConversations(cursor, 200);
      for (const conversation of page.items ?? []) {
        this.conversations.set(conversation.conv_id, conversation);
        let before = 0;
        let hasMore = true;
        while (hasMore) {
          const items = await this.history(conversation.conv_id, before, 200);
          hasMore = items.length === 200;
          if (items.length > 0) before = Number(items[0].conv_seq);
        }
      }
      this.cursor = Math.max(this.cursor, Number(page.max_user_seq ?? 0));
      cursor = page.has_more ? page.next_cursor : undefined;
    } while (cursor);
    this.saveCursor();
  }

  private onClose(): void {
    this.stopHeartbeat();
    this.emit("disconnected");
    if (this.kicked || this.authFailed || this.state === "closed") return;
    this.setState("reconnecting");
    const jitter = this.backoff * (0.7 + Math.random() * 0.6);
    setTimeout(() => {
      if (!this.kicked) this.openAndAuth().catch(() => { /* next close drives retry */ });
    }, Math.min(jitter, MAX_BACKOFF_MS));
    this.backoff = Math.min(this.backoff * 2, MAX_BACKOFF_MS);
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.hbTimer = setInterval(() => {
      this.request(P.Command.COMMAND_PING, P.Ping.encode({}).finish()).catch(() => { /* reconnect */ });
    }, HEARTBEAT_MS);
  }
  private stopHeartbeat(): void { if (this.hbTimer) { clearInterval(this.hbTimer); this.hbTimer = undefined; } }

  private request(cmd: number, payload: Uint8Array): Promise<any> {
    const cid = ++this.cid;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(cid);
        reject(new Error("timeout"));
      }, SEND_TIMEOUT_MS);
      this.pending.set(cid, { resolve, reject, timer });
      this.rawSend(cmd, payload, cid);
    });
  }
  private rawSend(cmd: number, payload: Uint8Array, cid = 0): void {
    const env = P.Envelope.encode({ version: 1, cmd, cid, code: 0, payload }).finish();
    this.ws?.send(env);
  }
  private protocolError(code: number): Error { return new Error("protocol error code=" + code); }

  private httpHeaders(): Record<string, string> {
    if (!this.opts) throw new Error("not logged in");
    return {
      Authorization: `Bearer ${this.opts.userToken}`,
      "X-App-Id": this.appId,
      "X-User-Id": this.opts.userId,
      "X-Device-Id": this.opts.deviceId,
    };
  }

  private async http<T = any>(method: string, path: string, body?: any,
                              options: { signal?: AbortSignal } = {}): Promise<T> {
    const headers = this.httpHeaders();
    const init: RequestInit = { method, headers, signal: options.signal };
    if (body instanceof Uint8Array) {
      headers["Content-Type"] = "application/octet-stream";
      init.body = body as BodyInit;
    } else if (body !== undefined) {
      headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(body);
    }
    const response = await this.fetchImpl(`${this.httpBaseUrl}${path}`, init);
    if (!response.ok) {
      let detail: any = {};
      try { detail = await response.json(); } catch { /* noop */ }
      const error = new Error(detail?.message ?? `HTTP ${response.status}`) as Error & { code?: number };
      error.code = detail?.code;
      throw error;
    }
    if (response.status === 204) return undefined as T;
    const payload = await response.json();
    if (payload && typeof payload === "object" && "code" in payload) {
      if (payload.code !== 0) {
        const error = new Error(payload.message ?? "request failed") as Error & { code?: number };
        error.code = payload.code;
        throw error;
      }
      return payload.data as T;
    }
    return payload as T;
  }

  private fromProtoMessage(msg: any): ChatMessage {
    const content = msg.content ?? {};
    const burned = this.burned(msg);
    const safeContent = burned ? {} : content;
    return {
      msgId: msg.msgId ?? "", clientMsgId: msg.clientMsgId ?? "", convId: msg.convId,
      convType: Number(msg.convType) === CONV_TYPE_VALUE.GROUP ? "GROUP" : "SINGLE",
      senderId: msg.senderId, kind: this.kindFromProto(msg.msgType), content: safeContent,
      text: burned ? "消息已焚毁" : this.messageText(safeContent, msg.msgType),
      convSeq: Number(msg.convSeq), serverTime: Number(msg.serverTime),
      state: msg.recalled ? "recalled" : burned ? "burned" : this.stateFromProto(msg.status),
      recalled: Boolean(msg.recalled), restricted: Boolean(msg.restricted),
      isRequest: Boolean(msg.isRequest), replyToMsgId: msg.replyToMsgId || undefined,
      replySnapshot: msg.replySnapshot, mentions: msg.mentions ?? [],
      burnAfterReadTtlSeconds: Number(msg.burnAfterReadTtlSeconds ?? 0),
    };
  }

  private kindFromProto(value: number): MessageKind {
    return (Object.entries(MSG_TYPE_VALUE).find(([, id]) => id === value)?.[0] ?? "TEXT") as MessageKind;
  }
  private stateFromProto(value: number): MessageState {
    if (value === 4) return "read";
    if (value === 3) return "delivered";
    if (value === 5) return "failed";
    return "sent";
  }
  private burned(msg: any): boolean {
    return this.burnedAt(msg.serverTime, msg.burnAfterReadTtlSeconds);
  }
  private burnedAt(serverTime: unknown, ttlSeconds: unknown): boolean {
    return Number(ttlSeconds ?? 0) > 0
      && Date.now() > Number(serverTime) + Number(ttlSeconds) * 1000;
  }
  private messageText(content: Record<string, any>, type: number): string {
    if (type === MSG_TYPE_VALUE.SYSTEM) return this.systemText(content.system);
    return content.text?.text ?? this.contentDigest(content, type);
  }
  private systemText(system: any): string {
    const template = String(system?.templateId ?? "");
    const params = system?.params ?? {};
    const actor = params.operator_name ?? params.operator_id ?? params.name ?? "";
    const templates: Record<string, string> = {
      "group.created": actor ? `${actor} 创建了群聊` : "群聊已创建",
      "group.member.joined": actor ? `${actor} 加入了群聊` : "成员加入群聊",
      "group.member.left": actor ? `${actor} 退出了群聊` : "成员退出群聊",
      "group.member.kicked": actor ? `${actor} 被移出群聊` : "成员被移出群聊",
      "group.role.changed": "群成员角色已更新",
      "friend.accepted": "好友申请已通过",
      "delivery.restricted": "消息可能不会被对方看到",
      "moderation.recalled": "消息因内容规范被撤回",
    };
    return templates[template] ?? params.text ?? (template ? `[系统消息] ${template}` : "[系统消息]");
  }
  private contentDigest(content: any, type: number): string {
    if (type === 3) return "[图片]";
    if (type === 4) return "[语音]";
    if (type === 5) return "[视频]";
    if (type === 6) return "[文件] " + (content.file?.fileName ?? "");
    if (content.emoji?.emojiId) return content.emoji.emojiId;
    return "";
  }

  private mergeHistoryItem(item: any): void {
    const id = item.msg_id;
    if (!id || this.seen.has(id)) return;
    const burned = this.burnedAt(item.server_time, item.burn_after_read_ttl_seconds);
    const content = burned ? {} : this.parseContent(item.content_json);
    const message: ChatMessage = {
      msgId: id, clientMsgId: item.client_msg_id ?? "", convId: item.conv_id,
      convType: item.conv_type === "GROUP" ? "GROUP" : "SINGLE", senderId: item.sender_id,
      kind: (item.msg_type ?? "TEXT").replace("MSG_TYPE_", "") as MessageKind,
      content, text: burned ? "消息已焚毁" : "",
      convSeq: Number(item.conv_seq), serverTime: Number(item.server_time),
      state: item.recalled ? "recalled" : burned ? "burned"
        : item.status === "MSG_STATUS_READ" ? "read" : "sent",
      recalled: Boolean(item.recalled), restricted: Boolean(item.restricted),
      isRequest: Boolean(item.is_request), replyToMsgId: item.reply_to_msg_id || undefined,
      replySnapshot: { senderId: item.reply_sender_id, senderName: item.reply_sender_name, digest: item.reply_digest },
      mentions: [], burnAfterReadTtlSeconds: Number(item.burn_after_read_ttl_seconds ?? 0),
    };
    if (!burned) message.text = this.messageText(message.content, MSG_TYPE_VALUE[message.kind]);
    this.seen.add(id);
    this.appendLocal(message);
    this.reorder(message.convId);
  }
  private parseContent(value: string): Record<string, any> {
    try { return JSON.parse(value || "{}"); } catch { return {}; }
  }

  private optimisticConversation(convId: string, patch: { pinned?: boolean; muted?: boolean }): void {
    const key = `phantom.conversations.${this.appId}.${this.opts?.userId ?? "anonymous"}`;
    this.writeJson(key + ".rollback." + convId, patch);
  }
  private rollbackConversation(convId: string, patch: { pinned?: boolean; muted?: boolean }): void {
    const key = `phantom.conversations.${this.appId}.${this.opts?.userId ?? "anonymous"}.rollback.${convId}`;
    const previous = this.readJson<Record<string, unknown>>(key);
    if (previous) this.emit("conversation", { type: "OPTIMISTIC_ROLLBACK", convId, previous });
    this.removeKey(key);
  }

  private appendLocal(message: ChatMessage): void {
    const messages = this.messages.get(message.convId) ?? [];
    messages.push(message);
    this.messages.set(message.convId, messages);
    this.persistStore();
  }
  private sanitizeStoredMessage(message: ChatMessage): ChatMessage {
    const state = message.state === "sending" ? "failed" : message.state;
    if (!this.burnedAt(message.serverTime, message.burnAfterReadTtlSeconds)) return { ...message, state };
    return { ...message, state: "burned", content: {}, text: "消息已焚毁" };
  }
  private reorder(convId: string, persist = true): void {
    const messages = this.messages.get(convId);
    if (messages) messages.sort(compareMessages);
    if (persist) this.persistStore();
  }

  private cursorKey(userId: string): string { return `phantom.cursor.${this.appId}.${userId}`; }
  private messagesKey(userId: string): string { return `phantom.messages.${this.appId}.${userId}`; }
  private loadCursor(userId: string): number {
    try { return Number(localStorage.getItem(this.cursorKey(userId)) ?? 0); } catch { return 0; }
  }
  private saveCursor(): void {
    if (this.opts) this.writeJson(this.cursorKey(this.opts.userId), this.cursor);
  }
  private loadStore(userId: string): void {
    if (this.storeUserId === userId) return;
    this.messages.clear();
    this.seen.clear();
    this.locallyDeleted.clear();
    this.storeUserId = userId;
    try {
      const raw = localStorage.getItem(this.messagesKey(userId));
      if (!raw) return;
      const parsed = JSON.parse(raw) as { version?: number; messages?: Record<string, ChatMessage[]> } | Record<string, ChatMessage[]>;
      const stored = "messages" in parsed && parsed.messages ? parsed.messages : parsed as Record<string, ChatMessage[]>;
      for (const [convId, list] of Object.entries(stored)) {
        if (!Array.isArray(list)) continue;
        const valid = list.filter((message) => message?.convId === convId && message.clientMsgId)
          .map((message) => this.sanitizeStoredMessage(message));
        this.messages.set(convId, valid);
        for (const message of valid) if (message.msgId) this.seen.add(message.msgId);
        this.reorder(convId, false);
      }
    } catch {
      this.messages.clear();
      this.seen.clear();
    }
  }
  private persistStore(): void {
    if (!this.storeUserId) return;
    this.persistPending = true;
    if (this.persistTimer !== null) return;
    this.persistTimer = setTimeout(() => {
      this.persistTimer = null;
      this.flushPersistence();
    }, PERSIST_DELAY_MS);
  }

  /** 页面隐藏、退出登录或宿主显式刷新前调用；同步写入最新完整有序缓存。 */
  flushPersistence(): void {
    if (this.persistTimer !== null) {
      clearTimeout(this.persistTimer);
      this.persistTimer = null;
    }
    if (!this.persistPending || !this.storeUserId) return;
    this.persistPending = false;
    this.writeJson(this.messagesKey(this.storeUserId), {
      version: CACHE_VERSION,
      messages: Object.fromEntries(this.messages),
    });
  }
  private writeJson(key: string, value: unknown): void {
    try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* noop */ }
  }
  private readJson<T>(key: string): T | undefined {
    try { const raw = localStorage.getItem(key); return raw ? JSON.parse(raw) as T : undefined; }
    catch { return undefined; }
  }
  private removeKey(key: string): void { try { localStorage.removeItem(key); } catch { /* noop */ } }
}
