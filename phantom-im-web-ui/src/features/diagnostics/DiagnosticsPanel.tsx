import { remoteDisconnectAndReconnect, triggerExpiredToken } from "../../services/sdk-adapter";
import { useAppStore } from "../../store/app-store";

export function DiagnisticsPanel({ open, onClose }: { open: boolean; onClose: () => void }) {
  const account = useAppStore((state) => state.account);
  const connection = useAppStore((state) => state.connection.status);
  const kickedReason = useAppStore((state) => state.connection.kickedReason);
  const activeConvId = useAppStore((state) => state.activeConvId);
  const messagesByConv = useAppStore((state) => state.messagesByConv);
  const diagnostics = useAppStore((state) => state.diagnostics);
  if (!open) return null;
  const latest = activeConvId ? (messagesByConv[activeConvId] ?? []).at(-1) : undefined;

  return (
    <aside className="diagnostics-panel" data-testid="diagnostics-panel">
      <header><div><h2>测试诊断</h2><small>仅消费公开 SDK/BFF 行为</small></div>
        <button onClick={onClose} aria-label="关闭诊断">×</button></header>
      <dl className="diagnostic-grid">
        <dt>连接</dt><dd data-testid="diagnostic-connection">{connection}</dd>
        <dt>Phantom ID</dt><dd>{account?.phantom_user_id}</dd>
        <dt>会话 ID</dt><dd>{activeConvId || "-"}</dd>
        <dt>消息 ID</dt><dd>{latest?.msgId || "-"}</dd>
        <dt>客户端消息 ID</dt><dd>{latest?.clientMsgId || "-"}</dd>
        <dt>状态</dt><dd>{latest?.state || "-"}</dd>
        <dt>user_seq</dt><dd>{latest ? latest.convSeq : "-"}</dd>
      </dl>
      {kickedReason && <div className="form-error">Kicked：{kickedReason}。旧连接不会自动重连。</div>}
      <div className="diagnostic-actions">
        <button data-testid="trigger-expired-token" onClick={() => void triggerExpiredToken()}>模拟 Token 过期</button>
        <button data-testid="disconnect-reconnect" onClick={() => void remoteDisconnectAndReconnect()}>断开并重连</button>
        <button onClick={() => window.open(location.href, "_blank")}>同端顶替（新标签页）</button>
      </div>
      <div className="diagnostic-log">
        {diagnostics.slice().reverse().map((event) => <div key={event.id}>
          <time>{new Date(event.at).toLocaleTimeString()}</time><b>{event.type}</b><code>{event.detail}</code>
        </div>)}
        {!diagnostics.length && <div className="empty-state">等待 SDK 事件…</div>}
      </div>
    </aside>
  );
}
