import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiError, register } from "../../services/api";

interface Fields {
  nickname: string;
  login_name: string;
  password: string;
  password_confirm: string;
}

const empty: Fields = { nickname: "", login_name: "", password: "", password_confirm: "" };

export function validateRegistration(fields: Fields): Record<string, string> {
  const errors: Record<string, string> = {};
  if (fields.nickname.length < 1 || fields.nickname.length > 64) errors.nickname = "昵称长度必须为 1 至 64 个字符";
  if (!/^[A-Za-z0-9_]{4,32}$/.test(fields.login_name)) errors.login_name = "登录名必须为 4 至 32 位字母、数字或下划线";
  if (fields.password.length < 8 || fields.password.length > 64) errors.password = "密码长度必须为 8 至 64 个字符";
  if (fields.password !== fields.password_confirm) errors.password_confirm = "确认密码与密码不一致";
  return errors;
}

export function RegisterPage() {
  const navigate = useNavigate();
  const [fields, setFields] = useState<Fields>(empty);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState("");
  const [busy, setBusy] = useState(false);

  function update(name: keyof Fields, value: string) {
    setFields((current) => ({ ...current, [name]: value }));
    setErrors((current) => ({ ...current, [name]: "" }));
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const localErrors = validateRegistration({ ...fields, nickname: fields.nickname.trim() });
    if (Object.keys(localErrors).length) {
      setErrors(localErrors);
      return;
    }
    setBusy(true);
    setServerError("");
    try {
      await register({ ...fields, nickname: fields.nickname.trim(), login_name: fields.login_name.trim() });
      navigate("/login", { replace: true, state: { message: "注册成功，请登录。注册过程未建立 SDK 连接。" } });
    } catch (reason) {
      if (reason instanceof ApiError) {
        setServerError(reason.message);
        if (reason.fields) setErrors(reason.fields);
      } else {
        setServerError("注册失败，请稍后重试");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-card wide">
        <div className="brand-mark">P</div>
        <h1>创建 Web IM 账号</h1>
        <p className="muted">注册会开通 Phantom 用户，但不会自动登录或创建 Session。</p>
        <form onSubmit={submit} className="form-stack" noValidate>
          <label>昵称<input data-testid="register-nickname" value={fields.nickname}
            onChange={(event) => update("nickname", event.target.value)} />
            {errors.nickname && <span className="field-error">{errors.nickname}</span>}</label>
          <label>登录名<input data-testid="register-login-name" value={fields.login_name}
            onChange={(event) => update("login_name", event.target.value)} autoComplete="username" />
            {errors.login_name && <span className="field-error">{errors.login_name}</span>}</label>
          <label>密码<input data-testid="register-password" type="password" value={fields.password}
            onChange={(event) => update("password", event.target.value)} autoComplete="new-password" />
            {errors.password && <span className="field-error">{errors.password}</span>}</label>
          <label>确认密码<input data-testid="register-password-confirm" type="password" value={fields.password_confirm}
            onChange={(event) => update("password_confirm", event.target.value)} autoComplete="new-password" />
            {errors.password_confirm && <span className="field-error">{errors.password_confirm}</span>}</label>
          {serverError && <div className="form-error" role="alert" data-testid="register-error">{serverError}</div>}
          <button className="primary" disabled={busy} data-testid="register-submit">
            {busy ? "开通中…" : "注册"}
          </button>
        </form>
        <p className="auth-switch">已有账号？<Link to="/login">返回登录</Link></p>
      </section>
    </main>
  );
}
