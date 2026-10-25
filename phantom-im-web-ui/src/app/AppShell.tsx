import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, logout } from "../services/api";
import { connect, disconnect } from "../services/sdk-adapter";
import { useAppStore } from "../store/app-store";
import type { NavKey } from "../types";
import { ChatView } from "../features/chat/ChatView";
import { ContactsView } from "../features/contacts/ContactsView";
import { DiagnisticsPanel } from "../features/diagnostics/DiagnosticsPanel";
import { GroupsView } from "../features/groups/GroupsView";
import { SearchView } from "../features/search/SearchView";
import { SettingsView } from "../features/settings/SettingsView";

const navItems: Array<{ key: NavKey; icon: string; label: string }> = [
  { key: "chat", icon: "💬", label: "聊天" },
  { key: "contacts", icon: "👤", label: "联系人" },
  { key: "groups", icon: "👥", label: "群聊" },
  { key: "search", icon: "🔎", label: "搜索" },
  { key: "settings", icon: "⚙", label: "设置" },
];

export function AppShell() {
  const account = useAppStore((state) => state.account)!;
  const nav = useAppStore((state) => state.nav);
  const setNav = useAppStore((state) => state.setNav);
  const connection = useAppStore((state) => state.connection);
  const kickedReason = useAppStore((state) => state.kickedReason);
  const resetSession = useAppStore((state) => state.resetSession);
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);
  const [error, setError] = useState("");
  const navigate = useNavigate();

  useEffect(() => {
    if (connection === "idle" || connection === "closed") {
      connect(account).catch((reason: Error) => setError(reason.message));
    }
  }, [account, connection]);

  async function signOut() {
    try {
      await logout();
    } catch (reason) {
      if (!(reason instanceof ApiError) || reason.status !== 401) setError(reason instanceof Error ? reason.message : "退出失败");
    } finally {
      disconnect(false);
      resetSession();
      navigate("/login", { replace: true });
    }
  }

  return (
    <div className="app-shell">
      <nav className="main-nav" aria-label="主导航">
        <div className="nav-brand">P</div>
        {navItems.map((item) => (
          <button key={item.key} className={nav === item.key ? "nav-item active" : "nav-item"}
            data-testid={`tab-${item.key}`} onClick={() => setNav(item.key)} title={item.label}>
            <span>{item.icon}</span><small>{item.label}</small>
          </button>
        ))}
        <button className="nav-item diagnostics-entry" data-testid="open-diagnostics"
          onClick={() => setDiagnosticsOpen((value) => !value)} title="诊断">
          <span>◉</span><small>诊断</small>
        </button>
        <button className="nav-avatar" onClick={signOut} title="退出登录">
          {account.nickname.slice(0, 1).toUpperCase()}
        </button>
      </nav>

      <div className={`connection-pill ${connection}`} data-testid="connection-status">
        {connection === "online" ? "在线" : connection === "kicked" ? "已下线" : connection}
      </div>
      {error && <div className="global-error" role="alert">{error}</div>}
      {kickedReason && <div className="kicked-banner" role="alert">连接已断开：{kickedReason}</div>}

      <div className="workspace">
        {nav === "chat" && <ChatView />}
        {nav === "contacts" && <ContactsView />}
        {nav === "groups" && <GroupsView />}
        {nav === "search" && <SearchView />}
        {nav === "settings" && <SettingsView />}
      </div>
      <DiagnisticsPanel open={diagnosticsOpen} onClose={() => setDiagnosticsOpen(false)} />
    </div>
  );
}
