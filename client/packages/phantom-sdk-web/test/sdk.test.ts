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

  it("待确认消息保持已确认消息之后", async () => {
    const client = newClient();
    FakeWebSocket.onSend = (ws, env) => {
      if (env.cmd === P.Command.COMMAND_AUTH_REQ) authResponse(ws);
    };
    await login(client);
    const ws = FakeWebSocket.instances[0];
    const convId = singleConvId("u_alice", "u_bob");
    push(ws, 10, msg({ msgId: "m_1", convId, convSeq: 1 }));

    FakeWebSocket.onSend = (socket, env) => {
      if (env.cmd === P.Command.COMMAND_SEND_MSG_REQ) {
        response(socket, P.Command.COMMAND_SEND_MSG_RESP, Number(env.cid),
          P.SendMsgResp.encode({ msgId: "m_2", convSeq: 2, serverTime: 2000 }).finish());
      }
    };
    const pending = client.sendText(convId, "hello");

    expect(client.getMessages(convId).map((item) => item.state)).toEqual(["sent", "sending"]);
    await pending;
    expect(client.getMessages(convId).map((item) => item.msgId)).toEqual(["m_1", "m_2"]);
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

  it("同步撤回事件把本地消息替换为撤回态", async () => {
    const client = newClient();
    FakeWebSocket.onSend = (ws, env) => {
      if (env.cmd === P.Command.COMMAND_AUTH_REQ) authResponse(ws);
    };
    await login(client);
    const ws = FakeWebSocket.instances[0];
    const convId = singleConvId("u_alice", "u_bob");
    push(ws, 1, msg({ msgId: "m_recall", convId, convSeq: 1 }));

    const event = { userSeq: 2, eventTime: 2000, recall: { msgId: "m_recall", convId, operatorId: "u_bob" } };
    const payload = P.SyncEventPush.encode({ event }).finish();
    ws.serverSend(P.Envelope.encode({ cmd: P.Command.COMMAND_SYNC_EVENT_PUSH, cid: 0, code: 0, payload }).finish());

    expect(client.getMessages(convId)[0].state).toBe("recalled");
    expect(client.getMessages(convId)[0].text).toContain("撤回");
  });

  it("系统消息按模板渲染且阅后即焚过期后只保留墓碑", async () => {
    const client = newClient();
    FakeWebSocket.onSend = (ws, env) => {
      if (env.cmd === P.Command.COMMAND_AUTH_REQ) authResponse(ws);
    };
    await login(client);
    const ws = FakeWebSocket.instances[0];
    const convId = singleConvId("u_alice", "u_bob");

    push(ws, 1, {
      ...msg({ msgId: "m_system", convId, convSeq: 1 }),
      msgType: 10,
      content: { system: { templateId: "group.created", params: { operator_name: "Alice" } } },
    });
    push(ws, 2, {
      ...msg({ msgId: "m_burn", convId, convSeq: 2, text: "secret" }),
      serverTime: Date.now() - 2_000,
      burnAfterReadTtlSeconds: 1,
    });

    const messages = client.getMessages(convId);
    expect(messages[0].text).toBe("Alice 创建了群聊");
    expect(messages[1].state).toBe("burned");
    expect(messages[1].text).toBe("消息已焚毁");
    expect(messages[1].content).toEqual({});
  });

  it("通知点击携带深链信息并触发宿主事件", () => {
    const client = newClient();
    const clicked = vi.fn();
    client.on("notificationClick", clicked);
    client.handleNotificationClick({ conv_id: "c1", deep_link: "phantom://conversation/c1" });
    expect(clicked).toHaveBeenCalledWith({
      conv_id: "c1", deep_link: "phantom://conversation/c1",
    });
  });

  it("联想搜索防抖只提交最后一次输入", async () => {
    vi.useFakeTimers();
    const queries: string[] = [];
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const url = new URL(String(input));
      if (url.pathname === "/search/suggest") queries.push(url.searchParams.get("q") ?? "");
      return Response.json({ code: 0, data: { contacts: [], groups: [], total: 0 } });
    }) as unknown as typeof fetch;
    const client = new PhantomClient({ url: "ws://test/ws", appId: APP_ID, fetch: fetchMock });
    clients.push(client);
    FakeWebSocket.onSend = (ws, env) => {
      if (env.cmd === P.Command.COMMAND_AUTH_REQ) authResponse(ws);
    };
    await login(client);

    client.searchSuggestionsDebounced("a", 300);
    const second = client.searchSuggestionsDebounced("ab", 300);
    await vi.advanceTimersByTimeAsync(300);
    await second;

    expect(queries).toEqual(["ab"]);
  });

  it("媒体上传从已传分片继续并只上传剩余分片", async () => {
    const calls: Array<{ method: string; path: string; body?: any }> = [];
    const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const url = new URL(String(input));
      const method = init?.method ?? "GET";
      calls.push({ method, path: url.pathname, body: init?.body });
      if (url.pathname === "/media/upload/init") {
        return Response.json({ code: 0, data: {
          uploadId: "upload_1", partSize: 2, sizeBytes: 6, totalParts: 3,
          uploadedParts: [1], instant: false,
        } });
      }
      if (url.pathname.endsWith("/complete")) {
        return Response.json({ code: 0, data: {
          uploadId: "", partSize: 2, sizeBytes: 6, totalParts: 3, uploadedParts: [1, 2, 3],
          instant: false, mediaId: "media_1", url: "/media/media_1/content",
        } });
      }
      return Response.json({ code: 0, data: { uploadId: "upload_1", partNumber: 2 } });
    }) as unknown as typeof fetch;
    const client = new PhantomClient({ url: "ws://test/ws", appId: APP_ID, fetch: fetchMock });
    clients.push(client);
    FakeWebSocket.onSend = (ws, env) => {
      if (env.cmd === P.Command.COMMAND_AUTH_REQ) authResponse(ws);
    };
    await login(client);
    const progress: number[] = [];
    const result = await client.uploadMedia(new Blob(["abcdef"]), {
      fileName: "a.txt", sha256: "a".repeat(64), mediaType: "FILE",
      onProgress: (uploaded) => progress.push(uploaded),
    });

    expect(result.mediaId).toBe("media_1");
    expect(calls.filter((call) => call.path.includes("/part/")).map((call) => call.path))
      .toEqual(["/media/upload/upload_1/part/2", "/media/upload/upload_1/part/3"]);
    expect(progress).toEqual([4, 6]);
  });

  it("会话乐观更新失败时发出回滚事件", async () => {
    const fetchMock = vi.fn(async () => Response.json(
      { code: 1001, message: "rejected", data: null }, { status: 400 })) as unknown as typeof fetch;
    const client = new PhantomClient({ url: "ws://test/ws", appId: APP_ID, fetch: fetchMock });
    clients.push(client);
    FakeWebSocket.onSend = (ws, env) => {
      if (env.cmd === P.Command.COMMAND_AUTH_REQ) authResponse(ws);
    };
    await login(client);
    const rollback = vi.fn();
    client.on("conversation", rollback);
    await expect(client.updateConversation("si_a__b", { pinned: true })).rejects.toThrow("rejected");
    expect(rollback).toHaveBeenCalledWith(expect.objectContaining({ type: "OPTIMISTIC_ROLLBACK" }));
  });

  it("退出登录清理游标和本地消息", async () => {
    const convId = singleConvId("u_alice", "u_bob");
    FakeWebSocket.onSend = (ws, env) => {
      if (env.cmd === P.Command.COMMAND_AUTH_REQ) authResponse(ws, 1);
      else if (env.cmd === P.Command.COMMAND_SYNC_REQ) {
        const bytes = P.SyncResp.encode({ events: [{ userSeq: 1, eventTime: 1000,
          newMessage: { msg: msg({ msgId: "m_logout", convId }), convId, convSeq: 1, msgId: "m_logout" } }],
          maxUserSeq: 1, hasMore: false }).finish();
        response(ws, P.Command.COMMAND_SYNC_RESP, Number(env.cid), bytes);
      }
    };
    const client = newClient();
    await login(client);
    expect(memory.has(`phantom.cursor.${APP_ID}.u_bob`)).toBe(true);
    client.logout();
    expect(memory.has(`phantom.cursor.${APP_ID}.u_bob`)).toBe(false);
    expect(memory.has(`phantom.messages.${APP_ID}.u_bob`)).toBe(false);
  });
});
