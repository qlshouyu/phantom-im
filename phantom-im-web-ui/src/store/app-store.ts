import type { ChatMessage } from "@phantom/sdk-web";
import { create } from "zustand";
import type {
  Account, Conversation, DiagnosticEvent, NavKey, RuntimeConfig, SearchResults,
} from "../types";

interface AppState {
  account: Account | null;
  runtime: RuntimeConfig | null;
  nav: NavKey;
  connection: string;
  activeConvId: string | null;
  conversations: Conversation[];
  messagesByConv: Record<string, ChatMessage[]>;
  typingByConv: Record<string, boolean>;
  presenceByUser: Record<string, boolean>;
  drafts: Record<string, string>;
  diagnostics: DiagnosticEvent[];
  search: SearchResults | null;
  kickedReason: string | null;
  highlightConvSeq: number | null;
  setAccount: (account: Account | null) => void;
  setRuntime: (runtime: RuntimeConfig | null) => void;
  setNav: (nav: NavKey) => void;
  setConnection: (connection: string) => void;
  setActiveConversation: (convId: string | null) => void;
  setConversations: (items: Conversation[]) => void;
  appendConversations: (items: Conversation[]) => void;
  patchConversation: (convId: string, patch: Partial<Conversation>) => void;
  upsertMessage: (message: ChatMessage) => void;
  mergeMessages: (convId: string, messages: ChatMessage[]) => void;
  setTyping: (convId: string, typing: boolean) => void;
  setPresence: (userId: string, online: boolean) => void;
  setDraft: (convId: string, draft: string) => void;
  addDiagnostic: (type: string, detail: unknown) => void;
  setSearch: (search: SearchResults | null) => void;
  setKickedReason: (reason: string | null) => void;
  setHighlightConvSeq: (seq: number | null) => void;
  resetSession: () => void;
}

const messageKey = (message: ChatMessage) => message.clientMsgId || message.msgId;

function compareMessages(a: ChatMessage, b: ChatMessage): number {
  const aPending = !Number.isFinite(a.convSeq) || a.convSeq <= 0;
  const bPending = !Number.isFinite(b.convSeq) || b.convSeq <= 0;
  if (aPending !== bPending) return aPending ? 1 : -1;
  return (a.convSeq || 0) - (b.convSeq || 0)
    || a.serverTime - b.serverTime
    || messageKey(a).localeCompare(messageKey(b));
}

function mergeMessageList(current: ChatMessage[], incoming: ChatMessage[]): ChatMessage[] {
  const map = new Map<string, ChatMessage>();
  for (const message of [...current, ...incoming]) map.set(messageKey(message), message);
  return [...map.values()].sort(compareMessages);
}

function sortConversations(items: Conversation[]): Conversation[] {
  return [...items].sort((a, b) => {
    if (Boolean(a.pinned) !== Boolean(b.pinned)) return a.pinned ? -1 : 1;
    return Number(b.last_message_at ?? 0) - Number(a.last_message_at ?? 0);
  });
}

const initialSession = {
  account: null,
  runtime: null,
  nav: "chat" as NavKey,
  connection: "idle",
  activeConvId: null,
  conversations: [],
  messagesByConv: {},
  typingByConv: {},
  presenceByUser: {},
  drafts: {},
  diagnostics: [],
  search: null,
  kickedReason: null,
  highlightConvSeq: null,
};

export const useAppStore = create<AppState>((set, get) => ({
  ...initialSession,
  setAccount: (account) => set({ account }),
  setRuntime: (runtime) => set({ runtime }),
  setNav: (nav) => set({ nav }),
  setConnection: (connection) => {
    set({ connection });
    get().addDiagnostic("connection", connection);
  },
  setActiveConversation: (activeConvId) => set({ activeConvId }),
  setConversations: (conversations) => set({ conversations: sortConversations(conversations) }),
  appendConversations: (items) => set((state) => {
    const byId = new Map(state.conversations.map((item) => [item.conv_id, item]));
    for (const item of items) byId.set(item.conv_id, item);
    return { conversations: sortConversations([...byId.values()]) };
  }),
  patchConversation: (convId, patch) => set((state) => ({
    conversations: sortConversations(state.conversations.map((item) =>
      item.conv_id === convId ? { ...item, ...patch } : item)),
  })),
  upsertMessage: (message) => set((state) => {
    const current = state.messagesByConv[message.convId] ?? [];
    const messages = mergeMessageList(current, [message]);
    const active = state.activeConvId === message.convId;
    const conversations = state.conversations.map((conversation) => {
      if (conversation.conv_id !== message.convId) return conversation;
      return {
        ...conversation,
        last_message_digest: message.text,
        last_message_at: message.serverTime,
        unread_count: active || message.senderId === state.account?.phantom_user_id
          ? conversation.unread_count ?? 0
          : Number(conversation.unread_count ?? 0) + 1,
      };
    });
    return {
      messagesByConv: { ...state.messagesByConv, [message.convId]: messages },
      conversations: sortConversations(conversations),
    };
  }),
  mergeMessages: (convId, incoming) => set((state) => ({
    messagesByConv: {
      ...state.messagesByConv,
      [convId]: mergeMessageList(state.messagesByConv[convId] ?? [], incoming),
    },
  })),
  setTyping: (convId, typing) => set((state) => ({
    typingByConv: { ...state.typingByConv, [convId]: typing },
  })),
  setPresence: (userId, online) => set((state) => ({
    presenceByUser: { ...state.presenceByUser, [userId]: online },
  })),
  setDraft: (convId, draft) => set((state) => ({
    drafts: { ...state.drafts, [convId]: draft },
  })),
  addDiagnostic: (type, detail) => set((state) => {
    const event: DiagnosticEvent = {
      id: crypto.randomUUID(),
      at: Date.now(),
      type,
      detail: typeof detail === "string" ? detail : JSON.stringify(detail),
    };
    return { diagnostics: [...state.diagnostics.slice(-199), event] };
  }),
  setSearch: (search) => set({ search }),
  setKickedReason: (kickedReason) => set({ kickedReason }),
  setHighlightConvSeq: (highlightConvSeq) => set({ highlightConvSeq }),
  resetSession: () => set({ ...initialSession }),
}));
