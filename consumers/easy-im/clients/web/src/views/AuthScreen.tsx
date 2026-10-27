import {
  type FormEvent,
  type InputHTMLAttributes,
  useState,
} from 'react'
import { Icon, PhantomMark, type IconName } from '../icons'

type AuthMode = 'login' | 'register'

export interface AuthAccount {
  loginName: string
  nickname: string
}

interface AuthScreenProps {
  onAuthenticated: (account: AuthAccount) => void
}

interface LoginFields {
  loginName: string
  password: string
}

interface RegistrationFields extends LoginFields {
  nickname: string
  passwordConfirm: string
}

const emptyLoginFields: LoginFields = {
  loginName: '',
  password: '',
}

const emptyRegistrationFields: RegistrationFields = {
  nickname: '',
  loginName: '',
  password: '',
  passwordConfirm: '',
}

interface AuthTextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'className'> {
  label: string
  icon: IconName
  error?: string
  hint?: string
}

function AuthTextField({ label, icon, error, hint, id, ...props }: AuthTextFieldProps) {
  const descriptionId = error || hint ? `${id}-description` : undefined

  return (
    <div className="auth-field">
      <label className="auth-field-label" htmlFor={id}>{label}</label>
      <span className={`auth-input ${error ? 'has-error' : ''}`}>
        <Icon name={icon} size={18} />
        <input
          id={id}
          aria-invalid={Boolean(error)}
          aria-describedby={descriptionId}
          {...props}
        />
      </span>
      {(error || hint) && (
        <small
          id={descriptionId}
          className={error ? 'auth-field-error' : 'auth-field-hint'}
        >
          {error ?? hint}
        </small>
      )}
    </div>
  )
}

interface AuthPasswordFieldProps extends Omit<AuthTextFieldProps, 'icon' | 'type'> {
  autoComplete: string
}

function AuthPasswordField({
  label,
  error,
  hint,
  id,
  autoComplete,
  ...props
}: AuthPasswordFieldProps) {
  const [visible, setVisible] = useState(false)
  const descriptionId = error || hint ? `${id}-description` : undefined

  return (
    <div className="auth-field">
      <label className="auth-field-label" htmlFor={id}>{label}</label>
      <span className={`auth-input auth-input--password ${error ? 'has-error' : ''}`}>
        <Icon name="lock" size={18} />
        <input
          id={id}
          type={visible ? 'text' : 'password'}
          autoComplete={autoComplete}
          aria-invalid={Boolean(error)}
          aria-describedby={descriptionId}
          {...props}
        />
        <button
          type="button"
          className="auth-password-toggle"
          aria-label={visible ? `隐藏${label}` : `显示${label}`}
          aria-pressed={visible}
          onClick={() => setVisible((current) => !current)}
        >
          <Icon name={visible ? 'eyeOff' : 'eye'} size={18} />
        </button>
      </span>
      {(error || hint) && (
        <small
          id={descriptionId}
          className={error ? 'auth-field-error' : 'auth-field-hint'}
        >
          {error ?? hint}
        </small>
      )}
    </div>
  )
}

function validateRegistration(fields: RegistrationFields) {
  const errors: Partial<Record<keyof RegistrationFields, string>> = {}
  const nickname = fields.nickname.trim()
  const loginName = fields.loginName.trim()

  if (!nickname) {
    errors.nickname = '请输入昵称'
  } else if (nickname.length > 64) {
    errors.nickname = '昵称长度必须为 1 至 64 个字符'
  }

  if (!loginName) {
    errors.loginName = '请输入登录名'
  } else if (!/^[A-Za-z0-9_]{4,32}$/.test(loginName)) {
    errors.loginName = '登录名必须为 4 至 32 位字母、数字或下划线'
  }

  if (!fields.password) {
    errors.password = '请输入密码'
  } else if (fields.password.length < 8 || fields.password.length > 64) {
    errors.password = '密码长度必须为 8 至 64 个字符'
  }

  if (!fields.passwordConfirm) {
    errors.passwordConfirm = '请再次输入密码'
  } else if (fields.password !== fields.passwordConfirm) {
    errors.passwordConfirm = '两次输入的密码不一致'
  }

  return errors
}

function validateLogin(fields: LoginFields) {
  const errors: Partial<Record<keyof LoginFields, string>> = {}
  if (!fields.loginName.trim()) errors.loginName = '请输入登录名'
  if (!fields.password) errors.password = '请输入密码'
  return errors
}

export default function AuthScreen({ onAuthenticated }: AuthScreenProps) {
  const [mode, setMode] = useState<AuthMode>('login')
  const [loginFields, setLoginFields] = useState<LoginFields>(emptyLoginFields)
  const [registrationFields, setRegistrationFields] = useState<RegistrationFields>(emptyRegistrationFields)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [notice, setNotice] = useState('')
  const [registeredAccount, setRegisteredAccount] = useState<AuthAccount | null>(null)
  const [busy, setBusy] = useState(false)

  function switchMode(nextMode: AuthMode) {
    if (mode === nextMode) return
    setMode(nextMode)
    setErrors({})
    setNotice('')
  }

  function updateLoginField(field: keyof LoginFields, value: string) {
    setLoginFields((current) => ({ ...current, [field]: value }))
    setErrors((current) => ({ ...current, [field]: '' }))
    setNotice('')
  }

  function updateRegistrationField(field: keyof RegistrationFields, value: string) {
    setRegistrationFields((current) => ({ ...current, [field]: value }))
    setErrors((current) => ({ ...current, [field]: '' }))
  }

  async function submitLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const nextErrors = validateLogin(loginFields)
    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors)
      return
    }

    setBusy(true)
    await new Promise((resolve) => window.setTimeout(resolve, 360))
    const loginName = loginFields.loginName.trim()
    const matchedAccount = registeredAccount?.loginName === loginName
      ? registeredAccount
      : { loginName, nickname: loginName }
    onAuthenticated(matchedAccount)
  }

  async function submitRegistration(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const nextErrors = validateRegistration(registrationFields)
    if (Object.keys(nextErrors).length > 0) {
      setErrors(nextErrors)
      return
    }

    setBusy(true)
    await new Promise((resolve) => window.setTimeout(resolve, 420))
    const account = {
      loginName: registrationFields.loginName.trim(),
      nickname: registrationFields.nickname.trim(),
    }
    setRegisteredAccount(account)
    setLoginFields({ loginName: account.loginName, password: '' })
    setRegistrationFields(emptyRegistrationFields)
    setErrors({})
    setMode('login')
    setNotice('注册成功，请使用新账号登录')
    setBusy(false)
  }

  const isLogin = mode === 'login'

  return (
    <main className="auth-page">
      <section className="auth-showcase" aria-label="Phantom 产品介绍">
        <span className="auth-glow auth-glow--top" />
        <span className="auth-glow auth-glow--bottom" />

        <div className="auth-brand">
          <span className="auth-brand-mark"><PhantomMark size={30} /></span>
          <span>
            <strong>PHANTOM</strong>
            <small>DESKTOP MESSENGER</small>
          </span>
        </div>

        <div className="auth-showcase-content">
          <span className="auth-kicker"><span /> 专注于每一次交流</span>
          <h1>让对话更清晰，<br />让连接更自然。</h1>
          <p>在一个安静、专注的空间里，与朋友和团队保持联系。</p>

          <div className="auth-message-stage" aria-hidden="true">
            <div className="auth-person-card">
              <span className="auth-person-avatar">苏</span>
              <span>
                <strong>苏禾</strong>
                <small><i /> 在线</small>
              </span>
            </div>
            <div className="auth-message-bubble auth-message-bubble--incoming">
              新版交互稿已经整理好了，大家有空看一下。
              <small>10:24</small>
            </div>
            <div className="auth-message-bubble auth-message-bubble--outgoing">
              收到，我刚看完，整体节奏很舒服。
              <small>10:26</small>
            </div>
            <div className="auth-typing-card">
              <span className="auth-typing-dots"><i /><i /><i /></span>
              苏禾正在输入
            </div>
          </div>
        </div>

        <footer className="auth-showcase-footer">
          <span>即时消息</span>
          <i />
          <span>团队协作</span>
          <i />
          <span>桌面体验</span>
        </footer>
      </section>

      <section className="auth-panel" aria-labelledby="auth-title">
        <div className="auth-card">
          <div className="auth-card-heading">
            <span className="auth-card-mark"><PhantomMark size={24} /></span>
            <div>
              <span className="eyebrow">PHANTOM ACCOUNT</span>
              <h2 id="auth-title">{isLogin ? '欢迎回来' : '创建你的账号'}</h2>
              <p>{isLogin ? '使用登录名和密码进入 Phantom。' : '填写基本信息，开始使用 Phantom。'}</p>
            </div>
          </div>

          <div className="auth-mode-switch" role="group" aria-label="登录或注册">
            <button
              type="button"
              className={isLogin ? 'is-active' : ''}
              aria-pressed={isLogin}
              onClick={() => switchMode('login')}
            >
              登录
            </button>
            <button
              type="button"
              className={!isLogin ? 'is-active' : ''}
              aria-pressed={!isLogin}
              onClick={() => switchMode('register')}
            >
              注册
            </button>
          </div>

          {notice && (
            <div className="auth-notice" role="status">
              <Icon name="check" size={16} />
              {notice}
            </div>
          )}

          {isLogin ? (
            <form className="auth-form" onSubmit={submitLogin} noValidate>
              <AuthTextField
                id="auth-login-name"
                name="loginName"
                label="登录名"
                icon="user"
                placeholder="输入你的登录名"
                autoComplete="username"
                autoFocus
                value={loginFields.loginName}
                error={errors.loginName}
                onChange={(event) => updateLoginField('loginName', event.target.value)}
              />
              <AuthPasswordField
                id="auth-login-password"
                name="password"
                label="密码"
                placeholder="输入你的密码"
                autoComplete="current-password"
                value={loginFields.password}
                error={errors.password}
                onChange={(event) => updateLoginField('password', event.target.value)}
              />
              <div className="auth-only-method">
                <Icon name="shield" size={15} />
                <span>当前仅支持账号密码登录</span>
              </div>
              <button className="auth-submit" type="submit" disabled={busy} data-testid="auth-login-submit">
                <span>{busy ? '正在登录…' : '登录 Phantom'}</span>
                {!busy && <Icon name="arrowRight" size={18} />}
              </button>
            </form>
          ) : (
            <form className="auth-form" onSubmit={submitRegistration} noValidate>
              <AuthTextField
                id="auth-register-nickname"
                name="nickname"
                label="昵称"
                icon="user"
                placeholder="你想展示给好友的名字"
                autoComplete="nickname"
                autoFocus
                value={registrationFields.nickname}
                error={errors.nickname}
                onChange={(event) => updateRegistrationField('nickname', event.target.value)}
              />
              <AuthTextField
                id="auth-register-login-name"
                name="loginName"
                label="登录名"
                icon="message"
                placeholder="4–32 位字母、数字或下划线"
                autoComplete="username"
                value={registrationFields.loginName}
                error={errors.loginName}
                onChange={(event) => updateRegistrationField('loginName', event.target.value)}
              />
              <div className="auth-field-grid">
                <AuthPasswordField
                  id="auth-register-password"
                  name="password"
                  label="密码"
                  placeholder="至少 8 位字符"
                  autoComplete="new-password"
                  value={registrationFields.password}
                  error={errors.password}
                  onChange={(event) => updateRegistrationField('password', event.target.value)}
                />
                <AuthPasswordField
                  id="auth-register-password-confirm"
                  name="passwordConfirm"
                  label="确认密码"
                  placeholder="再次输入密码"
                  autoComplete="new-password"
                  value={registrationFields.passwordConfirm}
                  error={errors.passwordConfirm}
                  onChange={(event) => updateRegistrationField('passwordConfirm', event.target.value)}
                />
              </div>
              <p className="auth-register-note">注册只会创建账号，完成后需要登录。</p>
              <button className="auth-submit" type="submit" disabled={busy} data-testid="auth-register-submit">
                <span>{busy ? '正在创建…' : '创建账号'}</span>
                {!busy && <Icon name="arrowRight" size={18} />}
              </button>
            </form>
          )}

          <p className="auth-switch-copy">
            {isLogin ? '还没有账号？' : '已经有账号？'}
            <button type="button" onClick={() => switchMode(isLogin ? 'register' : 'login')}>
              {isLogin ? '立即注册' : '返回登录'}
            </button>
          </p>
        </div>
      </section>
    </main>
  )
}
