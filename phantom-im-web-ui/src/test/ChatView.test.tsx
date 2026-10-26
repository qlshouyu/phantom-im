import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ChatMessage } from "@phantom/sdk-web";
import { ChatView } from "../features/chat/ChatView";
import { useAppStore } from "../store/app-store";

const mocks = vi.hoisted(() => ({
  sendText: vi.fn(),
  clearDraft: vi.fn(),
  saveDraft: vi.fn(),
  sendTyping: vi.fn(),
  loadConversations: vi.fn(),
  loadOlder: vi.fn(),
  openConversation: vi.fn(),
  sendFile: vi.fn(),
  loadMediaThumbnail: vi.fn(),
}));

vi.mock("../services/sdk-adapter", () => ({
  sendText: mocks.sendText,
  clearDraft: undefined,
  saveDraft: undefined,
  loadConversations: mocks.loadConversations,
  loadOlder: mocks.loadOlder,
  openConversation: mocks.openConversation,
  sendFile: mocks.sendFile,
  loadMediaThumbnail: mocks.loadMediaThumbnail,
  getClient: () => ({
    clearDraft: mocks.clearDraft,
    saveDraft: mocks.saveDraft,
    sendTyping: mocks.sendTyping,
  }),
}));

describe("ChatView", () => {
  afterEach(() => {
    useAppStore.getState().resetSession();
    vi.clearAllMocks();
  });

  it("草稿接口未返回时仍立即展示发送中消息并正常回填成功状态", async () => {
    Object.defineProperty(HTMLElement.prototype, "scrollTo", {
      configurable: true,
      value: vi.fn(),
    });
    const convId = "si_u1__u2";
    const sending: ChatMessage = {
      msgId: "", clientMsgId: "c-optimistic", convId, convType: "SINGLE", senderId: "u1",
      kind: "TEXT", content: { text: { text: "慢草稿测试" } }, text: "慢草稿测试",
      convSeq: 0, serverTime: 1, state: "sending", recalled: false, restricted: false,
      isRequest: false, mentions: [], burnAfterReadTtlSeconds: 0,
    };
    let finishSend!: (message: ChatMessage) => void;
    const pendingSend = new Promise<ChatMessage>((resolve) => { finishSend = resolve; });
    mocks.sendText.mockImplementation(() => {
      useAppStore.getState().upsertMessage(sending);
      return pendingSend;
    });
    mocks.clearDraft.mockImplementation(() => new Promise(() => undefined));
    mocks.saveDraft.mockResolvedValue(undefined);
    mocks.sendTyping.mockResolvedValue(undefined);
    useAppStore.setState({
      account: {
        account_id: "a1", phantom_user_id: "u1", login_name: "alice", nickname: "Alice",
      },
      activeConvId: convId,
      conversations: [{ conv_id: convId, title: "Bob", peer_user_id: "u2" }],
      messagesByConv: {},
      drafts: {},
    });
    render(<ChatView />);

    fireEvent.change(screen.getByTestId("message-input"), { target: { value: "慢草稿测试" } });
    fireEvent.click(screen.getByTestId("send-message"));

    expect(mocks.sendText).toHaveBeenCalledWith(convId, "慢草稿测试", undefined);
    expect(mocks.clearDraft).toHaveBeenCalledWith(convId);
    expect(await within(screen.getByTestId("messages")).findByText("慢草稿测试")).toBeInTheDocument();
    expect(screen.getByText(/sending/)).toBeInTheDocument();

    await act(async () => {
      finishSend({ ...sending, msgId: "m1", convSeq: 1, state: "sent" });
      await pendingSend;
    });

    await waitFor(() => {
      expect(useAppStore.getState().messagesByConv[convId][0].state).toBe("sent");
    });
  });

  it("图片消息加载成功后展示缩略图而不是图片占位文本", async () => {
    Object.defineProperty(HTMLElement.prototype, "scrollTo", {
      configurable: true,
      value: vi.fn(),
    });
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: vi.fn(() => "blob:image-thumb"),
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      configurable: true,
      value: vi.fn(),
    });
    mocks.loadMediaThumbnail.mockResolvedValue(new Blob(["thumb"], { type: "image/jpeg" }));
    const convId = "si_u1__u2";
    const imageMessage: ChatMessage = {
      msgId: "m-image", clientMsgId: "c-image", convId, convType: "SINGLE", senderId: "u1",
      kind: "IMAGE", content: {
        image: { mediaId: "media_1", thumbUrl: "/media/media_1/content?variant=thumb", width: 320, height: 180 },
      }, text: "[图片]", convSeq: 2, serverTime: 2, state: "sent", recalled: false,
      restricted: false, isRequest: false, mentions: [], burnAfterReadTtlSeconds: 0,
    };
    useAppStore.setState({
      account: {
        account_id: "a1", phantom_user_id: "u1", login_name: "alice", nickname: "Alice",
      },
      activeConvId: convId,
      conversations: [{ conv_id: convId, title: "Bob", peer_user_id: "u2" }],
      messagesByConv: { [convId]: [imageMessage] },
      drafts: {},
    });
    render(<ChatView />);

    const image = await screen.findByTestId("message-image");
    expect(image).toHaveAttribute("src", "blob:image-thumb");
    expect(image).toHaveAttribute("alt", "图片消息");
    expect(screen.queryByText("[图片]")).not.toBeInTheDocument();
    expect(mocks.loadMediaThumbnail).toHaveBeenCalledWith("media_1");
  });
});
