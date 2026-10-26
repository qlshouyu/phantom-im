import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppShell } from "../app/AppShell";
import { connect } from "../services/sdk-adapter";
import { useAppStore } from "../store/app-store";

vi.mock("../services/sdk-adapter", () => ({
  connect: vi.fn(() => Promise.resolve()),
  disconnect: vi.fn(),
}));
vi.mock("../services/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../services/api")>();
  return { ...actual, logout: vi.fn() };
});
vi.mock("../features/chat/ChatView", () => ({ ChatView: () => null }));
vi.mock("../features/contacts/ContactsView", () => ({ ContactsView: () => null }));
vi.mock("../features/diagnostics/DiagnosticsPanel", () => ({ DiagnisticsPanel: () => null }));
vi.mock("../features/groups/GroupsView", () => ({ GroupsView: () => null }));
vi.mock("../features/search/SearchView", () => ({ SearchView: () => null }));
vi.mock("../features/settings/SettingsView", () => ({ SettingsView: () => null }));

const account = {
  account_id: "a1",
  phantom_user_id: "u1",
  login_name: "alice",
  nickname: "Alice",
};

function renderShell() {
  return render(<MemoryRouter><AppShell /></MemoryRouter>);
}

describe("app shell connection bootstrap", () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    cleanup();
    vi.clearAllMocks();
    useAppStore.setState({
      account,
      nav: "chat",
      connection: { status: "idle", kickedReason: null },
      diagnostics: [],
    });
  });

  it("只对首次 idle 状态发起连接", () => {
    renderShell();
    expect(connect).toHaveBeenCalledOnce();
  });

  it("closed 状态不会触发页面级自动重连", () => {
    useAppStore.setState({ connection: { status: "closed", kickedReason: null } });
    renderShell();
    expect(connect).not.toHaveBeenCalled();
  });

  it("kicked 状态保持终止态并展示断开原因", () => {
    useAppStore.setState({
      connection: { status: "kicked", kickedReason: "KICK_REASON_SAME_TYPE_LOGIN" },
    });
    expect(useAppStore.getState().connection.status).toBe("kicked");
    renderShell();

    expect(connect).not.toHaveBeenCalled();
    expect(screen.getByTestId("connection-status")).toHaveTextContent("已下线");
    expect(screen.getByRole("alert")).toHaveTextContent("连接已断开：KICK_REASON_SAME_TYPE_LOGIN");
  });
});
