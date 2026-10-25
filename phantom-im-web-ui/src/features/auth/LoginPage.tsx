import { useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { ApiError, login } from "../../services/api";
import { connect } from "../../services/sdk-adapter";
import { useAppStore } from "../../store/app-store";

export function LoginPage() {
  const setAccount = useAppStore((state) => state.setAccount);
  const navigate = useNavigate();
  const location = useLocation();
  const [loginName, setLoginName] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const message = (location.state as { message?: string } | null)?.message;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError("");
    setBusy(true);
    try {
      const account = await login({ login_name: loginName.trim(), password });
      setAccount(account);
      await connect(account);
      navigate("/", { replace: true });
    } catch (reason) {
      setError(reason instanceof ApiError
        ? reason.message
        : reason instanceof Error && reason.message
          ? `即时通信连接失败：${reason.message}`
          : "即时通信连接失败，请稍后重试");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-card">
        <div className="brand-mark">P</div>
        <h1>登录 Phantom Web IM</h1>
        <p className="muted">使用 Web IM 账号登录，SDK 将直连 Phantom Gateway。</p>
        {message && <div className="notice" role="status">{message}</div>}
        <form onSubmit={submit} className="form-stack">
          <label>登录名<input data-testid="login-name" value={loginName}
            onChange={(event) => setLoginName(event.target.value)} autoComplete="username" required /></label>
          <label>密码<input data-testid="login-password" type="password" value={password}
            onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" required /></label>
          {error && <div className="form-error" role="alert" data-testid="login-error">{error}</div>}
          <button className="primary" disabled={busy} data-testid="login-submit">
            {busy ? "登录中…" : "登录"}
          </button>
        </form>
        <p className="auth-switch">还没有账号？<Link to="/register">注册</Link></p>
      </section>
    </main>
  );
}
