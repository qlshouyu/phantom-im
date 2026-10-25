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
      account: null, runtime: null, nav: "chat", connection: "idle", activeConvId: null,
      conversations: [], messagesByConv: {}, typingByConv: {}, presenceByUser: {},
      drafts: {}, diagnostics: [], search: null, kickedReason: null, highlightConvSeq: null,
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
});
