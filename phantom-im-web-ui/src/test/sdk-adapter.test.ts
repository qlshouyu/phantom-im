import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useAppStore } from "../store/app-store";

const mocks = vi.hoisted(() => {
  class MockPhantomClient {
    static instances: MockPhantomClient[] = [];
    private handlers = new Map<string, Set<(...args: any[]) => void>>();
    loginOptions?: Record<string, unknown>;

    constructor(public options: Record<string, unknown>) {
      MockPhantomClient.instances.push(this);
    }

    on(event: string, handler: (...args: any[]) => void): this {
      const handlers = this.handlers.get(event) ?? new Set();
      handlers.add(handler);
      this.handlers.set(event, handlers);
      return this;
    }

    off(event: string, handler: (...args: any[]) => void): this {
      this.handlers.get(event)?.delete(handler);
      return this;
    }

    emit(event: string, ...args: any[]): void {
      for (const handler of this.handlers.get(event) ?? []) handler(...args);
    }

    async login(options: Record<string, unknown>): Promise<void> {
      this.loginOptions = options;
      this.emit("statechange", "online");
    }

    logout(): void {
      this.emit("statechange", "closed");
    }

    async listConversations(): Promise<{ items: never[] }> {
      return { items: [] };
    }

    getMessages(): never[] {
      return [];
    }

    getNotificationPermission(): NotificationPermission {
      return "default";
    }
  }
  return { MockPhantomClient };
});

vi.mock("@phantom/sdk-web", () => ({ PhantomClient: mocks.MockPhantomClient }));
vi.mock("../services/api", () => ({
  phantomToken: vi.fn(async () => ({
    user_token: "token",
    phantom_user_id: "u1",
    app_id: "app-1",
    expires_at: Date.now() + 60_000,
    expires_in_seconds: 60,
  })),
}));

import { connect, disconnect } from "../services/sdk-adapter";

const account = {
  account_id: "a1",
  phantom_user_id: "u1",
  login_name: "alice",
  nickname: "Alice",
};

describe("sdk adapter connection lifecycle", () => {
  beforeEach(() => {
    disconnect(false);
    mocks.MockPhantomClient.instances.length = 0;
    useAppStore.setState({
      account,
      runtime: {
        app_id: "app-1",
        ws_url: "ws://localhost:28080/ws",
        proxy_base_url: "/api",
        test_mode: false,
      },
      connection: { status: "idle", kickedReason: null },
      conversations: [],
      diagnostics: [],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    disconnect(false);
  });

  it("旧客户端迟到的踢下线事件不会覆盖当前在线状态", async () => {
    await connect(account);
    const previous = mocks.MockPhantomClient.instances[0];

    await connect(account);
    expect(mocks.MockPhantomClient.instances).toHaveLength(2);
    expect(useAppStore.getState().connection.status).toBe("online");

    previous.emit("kicked", "KICK_REASON_SAME_TYPE_LOGIN");

    expect(useAppStore.getState().connection).toEqual({
      status: "online",
      kickedReason: null,
    });
  });

  it("当前连接被踢下线后进入终止态，迟到 online 不能恢复", async () => {
    await connect(account);
    const current = mocks.MockPhantomClient.instances[0];

    current.emit("statechange", "closed");
    current.emit("kicked", "KICK_REASON_SAME_TYPE_LOGIN");
    current.emit("statechange", "online");

    expect(useAppStore.getState().connection).toEqual({
      status: "kicked",
      kickedReason: "KICK_REASON_SAME_TYPE_LOGIN",
    });
  });

  it("disconnect(false) 解绑后再 logout，不会触发 closed 状态", async () => {
    await connect(account);
    expect(useAppStore.getState().connection.status).toBe("online");

    disconnect(false);

    expect(useAppStore.getState().connection.status).toBe("online");
    expect(mocks.MockPhantomClient.instances[0].loginOptions?.deviceType).toBe("WEB");
  });
});
