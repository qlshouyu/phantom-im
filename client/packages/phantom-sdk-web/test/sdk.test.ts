import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PhantomClient, singleConvId } from "../src";
import * as P from "../src/proto";

const APP_ID = "app_test";

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  static onSend?: (ws: FakeWebSocket, env: any) => void;

  binaryType = "";
  readyState = 0;
  sent: Uint8Array[] = [];
  onopen?: () => void;
  onmessage?: (ev: { data: ArrayBuffer }) => void;
  onclose?: () => void;
  onerror?: () => void;

  constructor(public url: string) {
    FakeWebSocket.instances.push(this);
    queueMicrotask(() => {
      this.readyState = 1;
      this.onopen?.();
    });
  }

  send(data: Uint8Array) {
    this.sent.push(data);
    FakeWebSocket.onSend?.(this, P.Envelope.decode(data));
  }

  close() {
    if (this.readyState === 3) return;
    this.readyState = 3;
    queueMicrotask(() => this.onclose?.());
  }

  serverClose() {
    this.readyState = 3;
    queueMicrotask(() => this.onclose?.());
  }

  serverSend(env: Uint8Array) {
    const bytes = env.buffer.slice(env.byteOffset, env.byteOffset + env.byteLength);
    this.onmessage?.({ data: bytes as ArrayBuffer });
  }
}

function response(ws: FakeWebSocket, cmd: number, cid: number, payload: Uint8Array, code = 0) {
  ws.serverSend(P.Envelope.encode({ version: 1, cmd, cid, code, payload }).finish());
}

function authResponse(ws: FakeWebSocket, maxUserSeq = 0, code = 0) {
  const req = P.Envelope.decode(ws.sent[ws.sent.length - 1]);
  const payload = code === 0
    ? P.AuthResp.encode({ serverTime: Date.now(), maxUserSeq, heartbeatIntervalS: 25 }).finish()
    : new Uint8Array();
  response(ws, P.Command.COMMAND_AUTH_RESP, Number(req.cid), payload, code);
}

function msg(input: { msgId: string; clientMsgId?: string; convId: string; convSeq?: number; text?: string; senderId?: string }) {
  return {
    msgId: input.msgId,
    clientMsgId: input.clientMsgId ?? "c_" + input.msgId,
    convId: input.convId,
    convType: 1,
    senderId: input.senderId ?? "u_bob",
    msgType: 1,
    content: { text: { text: input.text ?? input.msgId } },
    convSeq: input.convSeq ?? 1,
    serverTime: 1000,
  };
}

function push(ws: FakeWebSocket, userSeq: number, message: any) {
  const payload = P.MsgPush.encode({ msg: message, userSeq }).finish();
  ws.serverSend(P.Envelope.encode({ version: 1, cmd: P.Command.COMMAND_MSG_PUSH, cid: 0, code: 0, payload }).finish());
}

async function login(client: PhantomClient, userId = "u_bob") {
  await client.login({ userToken: "token", userId, deviceId: "dev-1", deviceType: "WEB" });
}

describe("PhantomClient", () => {
  let memory: Map<string, string>;
  let clients: PhantomClient[];

  beforeEach(() => {
    FakeWebSocket.instances = [];
    FakeWebSocket.onSend = undefined;
    memory = new Map();
    clients = [];
    (globalThis as any).WebSocket = FakeWebSocket;
    Object.defineProperty(globalThis, "localStorage", {
      configurable: true,
      value: {
        getItem: (k: string) => memory.get(k) ?? null,
        setItem: (k: string, v: string) => memory.set(k, v),
        removeItem: (k: string) => memory.delete(k),
        clear: () => memory.clear(),
      },
    });
  });

  afterEach(() => {
    for (const client of clients) client.logout();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function newClient() {
    const client = new PhantomClient({ url: "ws://test/ws", appId: APP_ID });
    clients.push(client);
    return client;
  }

  it("token 过期时触发 tokenExpired 回调", async () => {
    const client = newClient();
    const expired = vi.fn();
    client.on("tokenExpired", expired);
    FakeWebSocket.onSend = (ws, env) => {
      if (env.cmd === P.Command.COMMAND_AUTH_REQ) authResponse(ws, 0, 2001);
    };

    await expect(client.login({ userToken: "expired", userId: "u_bob", deviceId: "dev-1" }))
      .rejects.toThrow("auth failed code=2001");
    expect(expired).toHaveBeenCalledOnce();
    vi.useFakeTimers();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(FakeWebSocket.instances).toHaveLength(1);
  });

  it("重复 MsgPush 按 msg_id 去重且不重复触发 message", async () => {
    const client = newClient();
    FakeWebSocket.onSend = (ws, env) => {
      if (env.cmd === P.Command.COMMAND_AUTH_REQ) authResponse(ws);
    };
    await login(client);
    const events = vi.fn();
    client.on("message", events);
    const ws = FakeWebSocket.instances[0];
    const convId = singleConvId("u_alice", "u_bob");
    const message = msg({ msgId: "m_1", convId, convSeq: 1 });

    push(ws, 10, message);
    push(ws, 10, message);

    expect(client.getMessages(convId)).toHaveLength(1);
    expect(events).toHaveBeenCalledOnce();
  });

  it("自己消息服务端回推时按 client_msg_id 合并，不产生双气泡", async () => {
    const client = newClient();
    FakeWebSocket.onSend = (ws, env) => {
      if (env.cmd === P.Command.COMMAND_AUTH_REQ) authResponse(ws);
      if (env.cmd === P.Command.COMMAND_SEND_MSG_REQ) {
        const req = P.SendMsgReq.decode(env.payload) as any;
        response(ws, P.Command.COMMAND_SEND_MSG_RESP, Number(env.cid),
          P.SendMsgResp.encode({ msgId: "m_self", convSeq: 7, serverTime: 2000 }).finish());
        expect(req.clientMsgId).toMatch(/^c-/);
      }
    };
    await login(client);
    const convId = singleConvId("u_alice", "u_bob");
    const local = await client.sendText(convId, "hello");

    push(FakeWebSocket.instances[0], 20,
      msg({ msgId: "m_self", clientMsgId: local.clientMsgId, convId, convSeq: 7, senderId: "u_bob" }));

    expect(client.getMessages(convId)).toHaveLength(1);
    expect(client.getMessages(convId)[0].msgId).toBe("m_self");
  });

  it("被踢下线后不自动重连", async () => {
    const client = newClient();
    FakeWebSocket.onSend = (ws, env) => {
      if (env.cmd === P.Command.COMMAND_AUTH_REQ) authResponse(ws);
    };
    await login(client);
    const kicked = vi.fn();
    client.on("kicked", kicked);
    vi.useFakeTimers();

    const ws = FakeWebSocket.instances[0];
    const payload = P.KickPush.encode({ reason: 1 }).finish();
    ws.serverSend(P.Envelope.encode({ version: 1, cmd: P.Command.COMMAND_KICK_PUSH, cid: 0, code: 0, payload }).finish());
    await vi.advanceTimersByTimeAsync(10_000);

    expect(kicked).toHaveBeenCalledWith("KICK_REASON_SAME_TYPE_LOGIN");
    expect(FakeWebSocket.instances).toHaveLength(1);
  });

  it("意外断线按 1s、2s 指数退避重连", async () => {
    const client = newClient();
    let authRequests = 0;
    FakeWebSocket.onSend = (ws, env) => {
      if (env.cmd === P.Command.COMMAND_AUTH_REQ && authRequests++ === 0) authResponse(ws);
    };
    await login(client);
    vi.spyOn(Math, "random").mockReturnValue(0.5);
    vi.useFakeTimers();

    FakeWebSocket.instances[0].serverClose();
    await vi.advanceTimersByTimeAsync(999);
    expect(FakeWebSocket.instances).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(FakeWebSocket.instances).toHaveLength(2);

    FakeWebSocket.instances[1].serverClose();
    await vi.advanceTimersByTimeAsync(1_999);
    expect(FakeWebSocket.instances).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(FakeWebSocket.instances).toHaveLength(3);
  });

  it("发送超时置为失败，重发复用原 client_msg_id", async () => {
    const client = newClient();
    FakeWebSocket.onSend = (ws, env) => {
      if (env.cmd === P.Command.COMMAND_AUTH_REQ) authResponse(ws);
    };
    await login(client);
    vi.useFakeTimers();
    const convId = singleConvId("u_alice", "u_bob");

    const first = client.sendText(convId, "timeout");
    await vi.advanceTimersByTimeAsync(15_000);
    const failed = await first;
    expect(failed.state).toBe("failed");

    const ws = FakeWebSocket.instances[0];
    const beforeResend = ws.sent.length;
    const resend = client.resend(failed);
    await Promise.resolve();
    const resentFrame = P.Envelope.decode(ws.sent[beforeResend]);
    const resentReq = P.SendMsgReq.decode(resentFrame.payload) as any;
    expect(resentReq.clientMsgId).toBe(failed.clientMsgId);
    response(ws, P.Command.COMMAND_SEND_MSG_RESP, Number(resentFrame.cid),
      P.SendMsgResp.encode({ msgId: "m_retry", convSeq: 8, serverTime: 3000 }).finish());
    await resend;

    expect(failed.state).toBe("sent");
    expect(failed.msgId).toBe("m_retry");
    expect(client.getMessages(convId)).toHaveLength(1);
  });

  it("同步事件按 user_seq 去重、有序应用并持久化本地历史", async () => {
    const convId = singleConvId("u_alice", "u_bob");
    FakeWebSocket.onSend = (ws, env) => {
      if (env.cmd === P.Command.COMMAND_AUTH_REQ) {
        authResponse(ws, 2);
      } else if (env.cmd === P.Command.COMMAND_SYNC_REQ) {
        const ev = (userSeq: number, msgId: string) => ({
          userSeq,
          eventTime: 1000 + userSeq,
          newMessage: {
            msg: msg({ msgId, convId, convSeq: userSeq }),
            convId,
            convSeq: userSeq,
            msgId,
          },
        });
        const respBytes = P.SyncResp.encode({
          events: [ev(1, "m_1"), ev(2, "m_2"), ev(2, "m_2")], maxUserSeq: 2, hasMore: false,
        }).finish();
        response(ws, P.Command.COMMAND_SYNC_RESP, Number(env.cid), respBytes);
      }
    };
    const first = newClient();
    await first.login({ userToken: "token", userId: "u_bob", deviceId: "dev-1" });

    expect(first.getMessages(convId).map((m) => [m.msgId, m.convSeq])).toEqual([
      ["m_1", 1],
      ["m_2", 2],
    ]);
    expect(memory.get(`phantom.cursor.${APP_ID}.u_bob`)).toBe("2");
    first.logout();

    FakeWebSocket.instances = [];
    const second = newClient();
    await second.login({ userToken: "token", userId: "u_bob", deviceId: "dev-1" });
    expect(second.getMessages(convId).map((m) => m.msgId)).toEqual(["m_1", "m_2"]);
  });
});
