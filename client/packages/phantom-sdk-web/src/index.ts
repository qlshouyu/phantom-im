// Phantom IM 官方 JS Web SDK（M0 参考实现）。
// 对应 openspec/.../specs/web-sdk/spec.md —— 同时作为其他端 SDK 的行为契约。
import * as P from "./proto";

export { singleConvId } from "./proto";

export type DeviceType = "MOBILE" | "DESKTOP" | "WEB";
const DEVICE_TYPE_VALUE: Record<DeviceType, number> = { MOBILE: 1, DESKTOP: 2, WEB: 3 };

export interface LoginOptions {
  userToken: string;
  userId: string;
  deviceId: string;
  deviceType?: DeviceType;
}

export interface ChatMessage {
  msgId: string;
  clientMsgId: string;
  convId: string;
  senderId: string;
  text: string;
  convSeq: number;
  serverTime: number;
  state: "sending" | "sent" | "failed";
}

type Events = {
  connected: () => void;
  disconnected: () => void;
  message: (m: ChatMessage) => void;
  kicked: (reason: string) => void;
  tokenExpired: () => void;
  statechange: (s: ConnState) => void;
};
type ConnState = "idle" | "connecting" | "authing" | "online" | "reconnecting" | "closed";

const SEND_TIMEOUT_MS = 15_000;
const HEARTBEAT_MS = 25_000; // 演示用较短心跳
const MAX_BACKOFF_MS = 60_000;

/** 极简事件发射器。 */
class Emitter {
  private map = new Map<string, Function[]>();
  on<K extends keyof Events>(e: K, fn: Events[K]) { (this.map.get(e) ?? this.map.set(e, []).get(e)!).push(fn); return this; }
  emit<K extends keyof Events>(e: K, ...a: Parameters<Events[K]>) { (this.map.get(e) ?? []).forEach((f) => (f as any)(...a)); }
}

export class PhantomClient extends Emitter {
  private url: string;
  private appId: string;
  private ws?: WebSocket;
  private opts?: LoginOptions;

  private cid = 0;
  private pending = new Map<number, { resolve: (e: any) => void; reject: (err: any) => void; timer: any }>();

  private store = new Map<string, ChatMessage[]>();
  private seen = new Set<string>();     // msgId 去重
  private cursor = 0;                     // 已应用的最大 user_seq（与自动 ACK 同一进度）
  private storeUserId?: string;

  private state: ConnState = "idle";
  private kicked = false;
  private authFailed = false;
  private backoff = 1000;
  private hbTimer: any;

  constructor(options: { url: string; appId: string }) {
    super();
    this.url = options.url;
    this.appId = options.appId;
  }

  getState() { return this.state; }
  getMessages(convId: string): ChatMessage[] { return this.store.get(convId) ?? []; }

  /** 连接并认证（connection-gateway / web-sdk：连接与登录）。 */
  async login(opts: LoginOptions): Promise<void> {
    this.opts = opts;
    this.kicked = false;
    this.authFailed = false;
    this.cursor = this.loadCursor(opts.userId);
    this.loadStore(opts.userId);
    await this.openAndAuth();
  }

  private setState(s: ConnState) { this.state = s; this.emit("statechange", s); }

  private openAndAuth(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.setState(this.state === "reconnecting" ? "reconnecting" : "connecting");
      const ws = new WebSocket(this.url);
      ws.binaryType = "arraybuffer";
      this.ws = ws;
      ws.onopen = async () => {
        this.setState("authing");
        try {
          const resp = await this.auth();
          if (resp.code !== 0) {
            this.authFailed = true; // 鉴权失败不携带旧 Token 自动重试，等待宿主换取新 Token
            if (resp.code === 2001) this.emit("tokenExpired"); // TOKEN_EXPIRED → 回调宿主取新 Token
            reject(new Error("auth failed code=" + resp.code));
            try { ws.close(); } catch {}
            return;
          }
          this.backoff = 1000;
          this.setState("online");
          this.emit("connected");
          this.startHeartbeat();
          const max = Number(resp.maxUserSeq || 0);
          if (max > this.cursor) await this.syncLoop();  // 落后则增量补齐
          resolve();
        } catch (e) { reject(e); }
      };
      ws.onmessage = (ev) => this.onFrame(new Uint8Array(ev.data as ArrayBuffer));
      ws.onclose = () => this.onClose();
      ws.onerror = () => {/* 由 onclose 触发重连 */};
    });
  }

  private async auth(): Promise<any> {
    const o = this.opts!;
    const payload = P.AuthReq.encode({
      accessToken: o.userToken, deviceId: o.deviceId,
      deviceType: DEVICE_TYPE_VALUE[o.deviceType ?? "WEB"], platform: "web",
      clientVersion: "0.1.0", appId: this.appId, userId: o.userId,
    }).finish();
    const env = await this.request(P.Command.COMMAND_AUTH_REQ, payload);
    return env.code === 0 ? { code: 0, ...P.AuthResp.decode(env.payload) } : { code: env.code };
  }

  /** 发送单聊文本（web-sdk：发送幂等 + 乐观态 + 超时失败）。 */
  async sendText(convId: string, text: string): Promise<ChatMessage> {
    const clientMsgId = "c-" + crypto.randomUUID();
    const local: ChatMessage = { msgId: "", clientMsgId, convId, senderId: this.opts!.userId,
      text, convSeq: 0, serverTime: Date.now(), state: "sending" };
    this.appendLocal(convId, local);
    this.emit("message", local);

    const content = P.MsgContent.decode(P.MsgContent.encode({ text: { text } }).finish());
    const payload = P.SendMsgReq.encode({ clientMsgId, convId, convType: 1, msgType: 1, content }).finish();
    try {
      const env = await this.request(P.Command.COMMAND_SEND_MSG_REQ, payload);
      if (env.code !== 0) { local.state = "failed"; this.emit("message", local); return local; }
      const resp = P.SendMsgResp.decode(env.payload) as any;
      local.msgId = resp.msgId; local.convSeq = Number(resp.convSeq);
      local.serverTime = Number(resp.serverTime); local.state = "sent";
      this.seen.add(resp.msgId);
      this.reorder(convId);
      this.emit("message", local);
      return local;
    } catch {
      local.state = "failed"; this.emit("message", local); return local;
    }
  }

  /** 手动重发：复用原 clientMsgId（服务端幂等，不重复）。 */
  async resend(m: ChatMessage): Promise<void> {
    const content = P.MsgContent.decode(P.MsgContent.encode({ text: { text: m.text } }).finish());
    const payload = P.SendMsgReq.encode({ clientMsgId: m.clientMsgId, convId: m.convId,
      convType: 1, msgType: 1, content }).finish();
    m.state = "sending"; this.emit("message", m);
    try {
      const env = await this.request(P.Command.COMMAND_SEND_MSG_REQ, payload);
      if (env.code === 0) {
        const r = P.SendMsgResp.decode(env.payload) as any;
        m.msgId = r.msgId; m.convSeq = Number(r.convSeq); m.state = "sent";
        this.seen.add(r.msgId); this.reorder(m.convId);
      } else m.state = "failed";
    } catch { m.state = "failed"; }
    this.emit("message", m);
  }

  logout() {
    this.kicked = true; // 阻止自动重连
    this.stopHeartbeat();
    try { this.ws?.close(); } catch {}
    this.setState("closed");
  }

  // ---- 帧处理 ----
  private onFrame(bytes: Uint8Array) {
    const env = P.Envelope.decode(bytes) as any;
    const cid = Number(env.cid);
    if (cid && this.pending.has(cid)) {
      const p = this.pending.get(cid)!; this.pending.delete(cid); clearTimeout(p.timer); p.resolve(env);
      return;
    }
    switch (env.cmd) {
      case P.Command.COMMAND_MSG_PUSH: this.onPush(env); break;
      case P.Command.COMMAND_KICK_PUSH: this.onKick(env); break;
      default: break;
    }
  }

  private onPush(env: any) {
    const push = P.MsgPush.decode(env.payload) as any;
    const userSeq = Number(push.userSeq);
    // 自动 ACK（web-sdk：接收与自动 ACK）
    this.rawSend(P.Command.COMMAND_MSG_ACK, P.MsgAck.encode({ userSeq }).finish());
    if (userSeq > this.cursor) {
      this.applyNewMessage(push.msg);
      this.cursor = userSeq;
      this.saveCursor();
    }
  }

  private onKick(env: any) {
    const kick = P.KickPush.decode(env.payload) as any;
    const reasonName = P.KickPush.lookupEnum("KickReason").valuesById?.[kick.reason] ?? String(kick.reason);
    this.kicked = true;             // web-sdk：被踢不自动重连
    this.stopHeartbeat();
    this.setState("closed");
    this.emit("kicked", reasonName);
    try { this.ws?.close(); } catch {}
  }

  /** 应用一条新消息到本地缓存（msgId 去重 + conv_seq 有序）。 */
  private applyNewMessage(msg: any) {
    if (!msg || !msg.msgId) return;
    if (this.seen.has(msg.msgId)) return;   // 重复推送/重连竞态幂等丢弃
    // 自己发送的消息会先以乐观态存在本地；服务端回推时按 client_msg_id 合并，避免双气泡。
    const existing = this.store.get(msg.convId)?.find((m) => m.clientMsgId === msg.clientMsgId);
    if (existing) {
      existing.msgId = msg.msgId;
      existing.convSeq = Number(msg.convSeq);
      existing.serverTime = Number(msg.serverTime);
      existing.state = "sent";
      this.seen.add(msg.msgId);
      this.reorder(msg.convId);
      this.emit("message", existing);
      return;
    }
    this.seen.add(msg.msgId);
    const cm: ChatMessage = { msgId: msg.msgId, clientMsgId: msg.clientMsgId, convId: msg.convId,
      senderId: msg.senderId, text: msg.content?.text?.text ?? "",
      convSeq: Number(msg.convSeq), serverTime: Number(msg.serverTime), state: "sent" };
    this.appendLocal(cm.convId, cm);
    this.reorder(cm.convId);
    this.emit("message", cm);
  }

  // ---- 同步（sync-catchup）----
  private async syncLoop() {
    let hasMore = true;
    while (hasMore) {
      const payload = P.SyncReq.encode({ sinceUserSeq: this.cursor, limit: 200 }).finish();
      const env = await this.request(P.Command.COMMAND_SYNC_REQ, payload);
      const resp = P.SyncResp.decode(env.payload) as any;
      for (const ev of resp.events ?? []) {
        const seq = Number(ev.userSeq);
        if (seq <= this.cursor) continue;    // 幂等丢弃
        if (ev.newMessage) this.applyNewMessage(ev.newMessage.msg);
        this.cursor = Math.max(this.cursor, seq);
      }
      this.saveCursor();
      hasMore = !!resp.hasMore;
    }
  }

  // ---- 重连（web-sdk：指数退避 + 抖动）----
  private onClose() {
    this.stopHeartbeat();
    this.emit("disconnected");
    if (this.kicked || this.authFailed || this.state === "closed") return;
    this.setState("reconnecting");
    const jitter = this.backoff * (0.7 + Math.random() * 0.6); // ±30%
    setTimeout(() => {
      if (this.kicked) return;
      this.openAndAuth().catch(() => {/* onClose 会再次调度 */});
    }, Math.min(jitter, MAX_BACKOFF_MS));
    this.backoff = Math.min(this.backoff * 2, MAX_BACKOFF_MS);
  }

  private startHeartbeat() {
    this.stopHeartbeat();
    this.hbTimer = setInterval(() => {
      this.request(P.Command.COMMAND_PING, P.Ping.encode({}).finish()).catch(() => {});
    }, HEARTBEAT_MS);
  }
  private stopHeartbeat() { if (this.hbTimer) { clearInterval(this.hbTimer); this.hbTimer = undefined; } }

  // ---- 底层收发 ----
  private request(cmd: number, payload: Uint8Array): Promise<any> {
    const cid = ++this.cid;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(cid); reject(new Error("timeout")); }, SEND_TIMEOUT_MS);
      this.pending.set(cid, { resolve, reject, timer });
      this.rawSend(cmd, payload, cid);
    });
  }
  private rawSend(cmd: number, payload: Uint8Array, cid = 0) {
    const env = P.Envelope.encode({ version: 1, cmd, cid, code: 0, payload }).finish();
    this.ws?.send(env);
  }

  // ---- 本地缓存 ----
  private appendLocal(convId: string, m: ChatMessage) {
    const arr = this.store.get(convId) ?? this.store.set(convId, []).get(convId)!;
    arr.push(m);
    this.persistStore();
  }
  private reorder(convId: string) {
    const arr = this.store.get(convId);
    if (arr) arr.sort((a, b) => (a.convSeq || 0) - (b.convSeq || 0) || a.serverTime - b.serverTime);
    this.persistStore();
  }

  // ---- 游标持久化 ----
  private cursorKey(userId: string) { return `phantom.cursor.${this.appId}.${userId}`; }
  private loadCursor(userId: string): number {
    try { return Number(localStorage.getItem(this.cursorKey(userId)) ?? 0); } catch { return 0; }
  }
  private saveCursor() {
    try { if (this.opts) localStorage.setItem(this.cursorKey(this.opts.userId), String(this.cursor)); } catch {}
  }
  private messagesKey(userId: string) { return `phantom.messages.${this.appId}.${userId}`; }
  private loadStore(userId: string) {
    if (this.storeUserId === userId) return;
    this.store.clear();
    this.seen.clear();
    this.storeUserId = userId;
    try {
      const raw = localStorage.getItem(this.messagesKey(userId));
      if (!raw) return;
      const saved = JSON.parse(raw) as Record<string, ChatMessage[]>;
      for (const [convId, messages] of Object.entries(saved)) {
        if (!Array.isArray(messages)) continue;
        const valid = messages
          .filter((m) => m && m.convId === convId && m.clientMsgId)
          .map((m) => ({ ...m, state: m.state === "sending" ? "failed" : m.state }));
        this.store.set(convId, valid);
        for (const m of valid) if (m.msgId) this.seen.add(m.msgId);
        this.reorder(convId);
      }
    } catch {
      this.store.clear();
      this.seen.clear();
    }
  }
  private persistStore() {
    if (!this.opts || !this.storeUserId) return;
    try {
      localStorage.setItem(this.messagesKey(this.storeUserId), JSON.stringify(Object.fromEntries(this.store)));
    } catch {}
  }
}
