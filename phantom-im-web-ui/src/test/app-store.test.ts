import { beforeEach, describe, expect, it } from "vitest";
import type { ChatMessage } from "@phantom/sdk-web";
import { useAppStore } from "../store/app-store";

function message(overrides: Partial<ChatMessage>): ChatMessage {
  return {
    msgId: "", clientMsgId: "client-1", convId: "single_a_b", convType: "SINGLE",
    senderId: "user-a", kind: "TEXT", content: {}, text: "hello", convSeq: 0,
    serverTime: 1, state: "sending", recalled: false, restricted: false,
    isRequest: false, mentions: [], burnAfterReadTtlSeconds: 0, ...overrides,
  };
}

describe("app store", () => {
  beforeEach(() => {
    useAppStore.setState({
      account: null, runtime: null, nav: "chat",
      connection: { status: "idle", kickedReason: null }, activeConvId: null,
      conversations: [], messagesByConv: {}, typingByConv: {}, presenceByUser: {},
      drafts: {}, diagnostics: [], search: null, highlightConvSeq: null,
    });
  });

  it("merges server回填消息而不产生重复气泡", () => {
    const store = useAppStore.getState();
    store.upsertMessage(message({}));
    store.upsertMessage(message({ msgId: "msg-1", convSeq: 7, state: "sent", serverTime: 2 }));
    const messages = useAppStore.getState().messagesByConv.single_a_b;
    expect(messages).toHaveLength(1);
    expect(messages[0].msgId).toBe("msg-1");
    expect(messages[0].state).toBe("sent");
  });

  it("按 conv_seq 排序并追加诊断事件", () => {
    const store = useAppStore.getState();
    store.upsertMessage(message({ msgId: "m2", clientMsgId: "c2", convSeq: 2 }));
    store.upsertMessage(message({ msgId: "m1", clientMsgId: "c1", convSeq: 1 }));
    store.addDiagnostic("connection", "online");
    const state = useAppStore.getState();
    expect(state.messagesByConv.single_a_b.map((item) => item.convSeq)).toEqual([1, 2]);
    expect(state.diagnostics.at(-1)?.detail).toBe("online");
  });

  it("未确认消息始终排在已确认消息之后", () => {
    const store = useAppStore.getState();
    store.upsertMessage(message({
      msgId: "m1", clientMsgId: "c1", convSeq: 1, state: "sent", serverTime: 100,
    }));
    store.upsertMessage(message({
      clientMsgId: "c2", convSeq: 0, state: "sending", serverTime: 200,
    }));

    expect(useAppStore.getState().messagesByConv.single_a_b
      .map((item) => item.clientMsgId)).toEqual(["c1", "c2"]);
  });

  it("退出会话时保留已加载的运行时配置", () => {
    const runtime = {
      app_id: "app-1",
      ws_url: "ws://localhost:28080/ws",
      proxy_base_url: "/api",
      test_mode: false,
    };
    useAppStore.setState({
      account: {
        account_id: "a1", phantom_user_id: "u1", login_name: "alice", nickname: "Alice",
      },
      runtime,
      connection: { status: "online", kickedReason: null },
      activeConvId: "single_alice_bob",
    });

    useAppStore.getState().resetSession();

    const state = useAppStore.getState();
    expect(state.runtime).toBe(runtime);
    expect(state.account).toBeNull();
    expect(state.connection).toEqual({ status: "idle", kickedReason: null });
    expect(state.activeConvId).toBeNull();
  });

  it("连接状态原子转换并清除旧踢下线原因", () => {
    const store = useAppStore.getState();
    store.setConnection("kicked", "KICK_REASON_SAME_TYPE_LOGIN");

    expect(useAppStore.getState().connection).toEqual({
      status: "kicked",
      kickedReason: "KICK_REASON_SAME_TYPE_LOGIN",
    });

    store.setConnection("online");

    expect(useAppStore.getState().connection).toEqual({
      status: "online",
      kickedReason: null,
    });
  });
});
