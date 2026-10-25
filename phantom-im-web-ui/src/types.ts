import type { ChatMessage } from "@phantom/sdk-web";

export type NavKey = "chat" | "contacts" | "groups" | "search" | "settings";

export interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
  request_id?: string;
}

export interface RuntimeConfig {
  app_id: string;
  ws_url: string;
  proxy_base_url: string;
  test_mode: boolean;
}

export interface Account {
  account_id: string;
  phantom_user_id: string;
  login_name: string;
  nickname: string;
}

export interface PhantomToken {
  user_token: string;
  phantom_user_id: string;
  app_id: string;
  expires_at: number;
  expires_in_seconds: number;
}

export interface Conversation {
  conv_id: string;
  title?: string;
  peer_nickname?: string;
  peer_user_id?: string;
  last_message_digest?: string;
  last_message_at?: number;
  unread_count?: number;
  mention_count?: number;
  pinned?: boolean;
  muted?: boolean;
  draft?: string;
  read_only?: boolean;
  conv_type?: "SINGLE" | "GROUP";
  [key: string]: unknown;
}

export interface MessageState {
  messages: ChatMessage[];
}

export interface DiagnosticEvent {
  id: string;
  at: number;
  type: string;
  detail: string;
}

export interface SearchResults {
  contacts?: Array<Record<string, unknown>>;
  groups?: Array<Record<string, unknown>>;
  messages?: Array<Record<string, unknown>>;
  contact_total?: number;
  group_total?: number;
  message_total?: number;
  total?: number;
}
