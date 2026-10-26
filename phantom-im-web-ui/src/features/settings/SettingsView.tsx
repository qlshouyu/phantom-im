import { useEffect, useState } from "react";
import { getClient } from "../../services/sdk-adapter";
import { useAppStore } from "../../store/app-store";

type Row = Record<string, any>;

export function SettingsView() {
  const account = useAppStore((state) => state.account)!;
  const connection = useAppStore((state) => state.connection.status);
  const [devices, setDevices] = useState<Row[]>([]);
  const [pushEnabled, setPushEnabled] = useState(false);
  const [previewEnabled, setPreviewEnabled] = useState(false);
  const [permission, setPermission] = useState(getClient().getNotificationPermission());
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function load() {
    try {
      const [deviceRows, push] = await Promise.all([
        getClient().listDevices(), getClient().getPushSettings(),
      ]);
      setDevices(deviceRows as Row[]);
      setPushEnabled(Boolean(push?.push_enabled));
      setPreviewEnabled(Boolean(push?.preview_enabled));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "设置加载失败");
    }
  }

  useEffect(() => { void load(); }, []);

  async function remoteLogout(deviceId: string) {
    try {
      await getClient().remoteLogout(deviceId);
      setNotice("设备已远程下线");
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "远程下线失败");
    }
  }

  async function requestPermission() {
    const result = await getClient().requestNotificationPermission();
    setPermission(result);
    if (result === "granted") setNotice("浏览器通知权限已开启");
  }

  async function savePush(nextPush: boolean, nextPreview: boolean) {
    setPushEnabled(nextPush);
    setPreviewEnabled(nextPreview);
    try {
      await getClient().updatePushSettings(nextPush, nextPreview);
      setNotice("通知设置已保存");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "通知设置保存失败");
    }
  }

  return (
    <div className="content-layout">
      <section className="list-pane">
        <header className="pane-header"><div><h2>设置</h2><span className="muted">账号、设备与通知</span></div></header>
        <div className="section-block">
          <h3>当前账号</h3>
          <div className="data-row"><div><strong>{account.nickname}</strong>
            <small>{account.login_name} · {account.phantom_user_id}</small></div>
            <span className="status-chip">{connection}</span></div>
        </div>
        <div className="section-block">
          <h3>设备</h3>
          {devices.map((device) => <div className="data-row" data-testid="device-item" key={device.device_id}>
            <div><strong>{device.device_name || device.device_id}</strong>
              <small>{device.platform || "WEB"} · {device.device_type || "当前设备"}</small></div>
            <button onClick={() => void remoteLogout(String(device.device_id))}>远程下线</button>
          </div>)}
          {!devices.length && <div className="empty-state">暂无设备记录</div>}
        </div>
        <div className="section-block">
          <h3>浏览器通知</h3>
          <p>权限：<b data-testid="notification-permission">{permission}</b></p>
          {permission !== "granted" && <button data-testid="request-notification" onClick={() => void requestPermission()}>申请通知权限</button>}
          <label className="check-row"><input type="checkbox" checked={pushEnabled}
            onChange={(event) => void savePush(event.target.checked, previewEnabled)} />启用推送</label>
          <label className="check-row"><input type="checkbox" checked={previewEnabled}
            onChange={(event) => void savePush(pushEnabled, event.target.checked)} />显示消息预览</label>
          {notice && <div className="notice" role="status">{notice}</div>}
          {error && <div className="form-error" role="alert">{error}</div>}
        </div>
      </section>
      <section className="detail-pane">
        <div className="search-guide">
          <h2>通知定位</h2>
          <p>点击浏览器通知会通过 SDK 的公开事件打开目标会话，并定位最新未读消息。</p>
          <p>远程下线设备后，目标设备由 Phantom 按平台规则断开；当前设备不会被伪造下线。</p>
        </div>
      </section>
    </div>
  );
}
