import QRCode from 'qrcode'
import { type FormEvent, useEffect, useMemo, useState } from 'react'
import { useApp } from '../app/AppProvider'
import { Avatar, IconButton, Modal, Switch } from '../components/ui'
import type { DeviceInfo, PushSettings, QrCard, UserProfile } from '../domain/types'
import { Icon } from '../icons'
import { getOrCreateDeviceId } from '../phantom/device'

interface ProfileViewProps {
  onLogout: () => void
}

type ProfilePanel = 'qr' | 'account' | 'preferences' | 'diagnostics'

export default function ProfileView({ onLogout }: ProfileViewProps) {
  const {
    gateway,
    state,
    runtime,
    requestId,
    connectionState,
    diagnostics,
    refreshToken,
    simulateTokenExpiry,
    reconnect,
  } = useApp()
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [friendCount, setFriendCount] = useState(0)
  const [groupCount, setGroupCount] = useState(0)
  const [devices, setDevices] = useState<DeviceInfo[]>([])
  const [pushSettings, setPushSettings] = useState<PushSettings | null>(null)
  const [notificationPermission, setNotificationPermission] = useState<NotificationPermission | 'unsupported'>('unsupported')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [editOpen, setEditOpen] = useState(false)
  const [draft, setDraft] = useState<UserProfile | null>(null)
  const [saving, setSaving] = useState(false)
  const [qrOpen, setQrOpen] = useState(false)
  const [qrCard, setQrCard] = useState<QrCard | null>(null)
  const [qrImage, setQrImage] = useState('')
  const [qrBusy, setQrBusy] = useState(false)
  const [securityOpen, setSecurityOpen] = useState(false)
  const [preferencesOpen, setPreferencesOpen] = useState(false)
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false)
  const [diagnosticsBusy, setDiagnosticsBusy] = useState('')
  const [soundEnabled, setSoundEnabled] = useState(() => readBoolean('easy-im.sound', true))
  const [compactMode, setCompactMode] = useState(() => readBoolean('easy-im.compact', false))
  const currentDeviceId = useMemo(() => getOrCreateDeviceId(), [])

  const greeting = getGreeting()
  const shortName = profile?.name.slice(-2) ?? state.account?.nickname.slice(-2) ?? '我'

  useEffect(() => {
    if (!gateway) {
      setLoading(false)
      setError('SDK 尚未连接')
      return
    }
    void loadProfile()
    const off = gateway.on('profile', () => { void loadProfile() })
    return off
  }, [gateway])

  useEffect(() => {
    if (!notice) return
    const timer = window.setTimeout(() => setNotice(''), 2600)
    return () => window.clearTimeout(timer)
  }, [notice])

  useEffect(() => {
    if (!gateway) return
    setNotificationPermission(gateway.getNotificationPermission())
  }, [gateway])

  useEffect(() => {
    document.documentElement.classList.toggle('is-compact-conversations', compactMode)
    writeBoolean('easy-im.compact', compactMode)
  }, [compactMode])

  useEffect(() => {
    writeBoolean('easy-im.sound', soundEnabled)
  }, [soundEnabled])

  async function loadProfile() {
    if (!gateway) return
    try {
      const [nextProfile, friends, groups, nextDevices, nextPushSettings] = await Promise.all([
        gateway.getUserProfile(),
        gateway.listFriends(),
        gateway.listGroups(),
        gateway.listDevices(),
        gateway.getPushSettings(),
      ])
      setProfile(nextProfile)
      setFriendCount(friends.length)
      setGroupCount(groups.length)
      setDevices(nextDevices)
      setPushSettings(nextPushSettings)
      setError('')
    } catch (reason) {
      setError(reasonMessage(reason, '个人资料加载失败'))
    } finally {
      setLoading(false)
    }
  }

  function openEdit() {
    if (!profile) return
    setDraft(profile)
    setEditOpen(true)
  }

  async function saveProfile(event: FormEvent) {
    event.preventDefault()
    if (!gateway || !draft?.name.trim()) return
    setSaving(true)
    try {
      const saved = await gateway.updateProfile({ ...draft, name: draft.name.trim() })
      setProfile(saved)
      setEditOpen(false)
      setNotice('个人资料已保存')
    } catch (reason) {
      setNotice(reasonMessage(reason, '资料保存失败，编辑内容已保留'))
    } finally {
      setSaving(false)
    }
  }

  async function openQr() {
    if (!gateway) return
    setQrOpen(true)
    setQrBusy(true)
    try {
      const card = await gateway.getQrCard()
      setQrCard(card)
      await renderQr(card)
    } catch (reason) {
      setNotice(reasonMessage(reason, '二维码加载失败'))
    } finally {
      setQrBusy(false)
    }
  }

  async function refreshQr() {
    if (!gateway) return
    setQrBusy(true)
    try {
      const card = await gateway.refreshQrCard()
      setQrCard(card)
      await renderQr(card)
      setNotice('二维码已刷新')
    } catch (reason) {
      setNotice(reasonMessage(reason, '二维码刷新失败'))
    } finally {
      setQrBusy(false)
    }
  }

  async function renderQr(card: QrCard) {
    const value = card.cardUrl || card.token
    if (!value) throw new Error('二维码名片响应缺少可编码内容')
    setQrImage(await QRCode.toDataURL(value, {
      width: 260,
      margin: 1,
      color: { dark: '#0b1714', light: '#f4fff9' },
    }))
  }

  async function copyQr() {
    if (!qrCard) return
    try {
      await navigator.clipboard.writeText(qrCard.cardUrl || qrCard.token)
      setNotice('二维码名片链接已复制')
    } catch {
      setNotice('复制失败，请检查浏览器权限')
    }
  }

  async function logoutDevice(device: DeviceInfo) {
    if (!gateway) return
    try {
      await gateway.remoteLogout(device.deviceId)
      setDevices((current) => current.filter((item) => item.deviceId !== device.deviceId))
      setNotice('设备已下线')
    } catch (reason) {
      setNotice(reasonMessage(reason, '设备下线失败'))
    }
  }

  async function updatePush(patch: Partial<PushSettings>) {
    if (!gateway || !pushSettings) return
    const previous = pushSettings
    const next = { ...pushSettings, ...patch }
    setPushSettings(next)
    try {
      const saved = await gateway.updatePushSettings(next.pushEnabled, next.previewEnabled)
      setPushSettings(saved)
      setNotice('通知偏好已同步')
    } catch (reason) {
      setPushSettings(previous)
      setNotice(reasonMessage(reason, '通知偏好保存失败'))
    }
  }

  async function requestNotifications() {
    if (!gateway) return
    try {
      const permission = await gateway.requestNotificationPermission()
      setNotificationPermission(permission)
      setNotice(permission === 'granted' ? '浏览器通知已开启' : permission === 'denied' ? '浏览器通知权限被拒绝' : '当前浏览器不支持通知')
    } catch (reason) {
      setNotice(reasonMessage(reason, '通知权限请求失败'))
    }
  }

  async function runDiagnostic(action: 'refresh' | 'expire' | 'reconnect') {
    setDiagnosticsBusy(action)
    try {
      if (action === 'refresh') {
        await refreshToken()
        setNotice('Phantom Token 已刷新')
      } else if (action === 'expire') {
        await simulateTokenExpiry()
        setNotice('已触发 Token 过期恢复')
      } else {
        await reconnect()
        setNotice('已发起 SDK 重连')
      }
    } catch (reason) {
      setNotice(reasonMessage(reason, '诊断操作失败'))
    } finally {
      setDiagnosticsBusy('')
    }
  }

  if (loading) return <ProfileState title="正在加载个人中心" description="正在读取资料、设备和通知设置…" />
  if (error || !profile) {
    return (
      <ProfileState
        title="个人中心加载失败"
        description={error || '未读取到个人资料'}
        action={<button type="button" className="primary-button" onClick={() => void loadProfile()}>重试</button>}
      />
    )
  }

  return (
    <div className="workspace-view profile-view">
      <section className="list-pane profile-side-pane" aria-label="个人中心导航">
        <header className="pane-heading">
          <div><span className="eyebrow">MY PHANTOM</span><h1>个人中心</h1></div>
          <IconButton icon="refresh" label="刷新个人中心" onClick={() => void loadProfile()} />
        </header>

        <div className="profile-card">
          <span className="profile-card-glow" />
          <div className="profile-card-topline"><span>MY PROFILE</span><span className="profile-verified"><Icon name="check" size={11} />Session 已恢复</span></div>
          <Avatar chars={[shortName]} tone="mint" size={76} online />
          <h2>{profile.name}</h2>
          <p>@{profile.handle || state.account?.loginName}</p>
          <span className="profile-card-signature">{profile.signature || '还没有设置个性签名'}</span>
          <button type="button" className="profile-edit-button" onClick={openEdit}><Icon name="edit" size={15} />编辑资料</button>
        </div>

        <nav className="profile-menu" aria-label="个人中心功能">
          <button type="button" onClick={() => void openQr()}>
            <span className="menu-icon menu-icon--green"><Icon name="qr" size={19} /></span>
            <span className="menu-copy"><strong>我的二维码</strong><small>真实 QR Card 名片</small></span>
            <Icon name="chevronRight" size={17} />
          </button>
          <button type="button" onClick={() => setNotice('收藏夹暂未开放')}>
            <span className="menu-icon menu-icon--amber"><Icon name="star" size={19} /></span>
            <span className="menu-copy"><strong>我的收藏</strong><small>平台暂未提供收藏契约</small></span>
            <span className="menu-unavailable">暂未开放</span>
          </button>
          <button type="button" onClick={() => setSecurityOpen(true)}>
            <span className="menu-icon menu-icon--blue"><Icon name="shield" size={19} /></span>
            <span className="menu-copy"><strong>账号与安全</strong><small>Session 与在线设备</small></span>
            <Icon name="chevronRight" size={17} />
          </button>
          <button type="button" onClick={() => setPreferencesOpen(true)}>
            <span className="menu-icon menu-icon--violet"><Icon name="palette" size={19} /></span>
            <span className="menu-copy"><strong>偏好设置</strong><small>通知、声音与显示</small></span>
            <Icon name="chevronRight" size={17} />
          </button>
          <button type="button" onClick={() => setDiagnosticsOpen(true)}>
            <span className="menu-icon menu-icon--blue"><Icon name="info" size={19} /></span>
            <span className="menu-copy"><strong>连接诊断</strong><small>Token、同步与重连状态</small></span>
            <Icon name="chevronRight" size={17} />
          </button>
        </nav>

        <div className="profile-side-footer">
          <div className="profile-storage">
            <div><span>云端空间</span><strong>暂未开放</strong></div>
            <p>平台当前没有公开云空间契约。</p>
          </div>
          <button type="button" disabled><Icon name="info" size={15} />Easy IM Web 0.1.0</button>
        </div>
      </section>

      <section className="detail-pane profile-main-pane" aria-label="个人中心详情">
        <header className="profile-main-heading">
          <div><span className="eyebrow">{greeting.en}</span><h1>{greeting.zh}，{profile.name}</h1><p>这里是你的真实账号资料、设备与连接状态。</p></div>
          <span className={`connection-pill connection-pill--${connectionState}`}><span />{connectionLabel(connectionState)}</span>
        </header>

        <div className="profile-main-scroll">
          <section className="profile-overview">
            <article className="overview-card overview-card--primary"><div className="overview-label"><span className="overview-icon"><Icon name="users" size={19} /></span>好友</div><strong>{friendCount.toString().padStart(2, '0')}</strong><p>来自 Phantom 关系链</p><span className="overview-accent" /></article>
            <article className="overview-card"><div className="overview-label"><span className="overview-icon overview-icon--amber"><Icon name="group" size={19} /></span>群组</div><strong>{groupCount.toString().padStart(2, '0')}</strong><p>当前已加入群组</p></article>
            <article className="overview-card"><div className="overview-label"><span className="overview-icon overview-icon--violet"><Icon name="smartphone" size={19} /></span>在线设备</div><strong>{devices.length.toString().padStart(2, '0')}</strong><p>公开设备接口返回</p></article>
          </section>

          <div className="profile-dashboard-grid">
            <section className="content-card profile-info-card">
              <div className="card-heading"><div><span className="eyebrow">PROFILE</span><h3>个人资料</h3></div><button type="button" className="secondary-button secondary-button--small" onClick={openEdit}><Icon name="edit" size={15} />编辑</button></div>
              <div className="info-list">
                <ProfileInfoRow icon="message" label="Phantom ID" value={`@${profile.id}`} />
                <ProfileInfoRow icon="sparkles" label="个性签名" value={profile.signature || '未设置'} />
                <ProfileInfoRow icon="mapPin" label="所在地区" value={profile.location || '未设置'} />
                <ProfileInfoRow icon="mail" label="邮箱" value={profile.email || '未设置'} />
                <ProfileInfoRow icon="smartphone" label="手机号" value={profile.phone || '未设置'} />
              </div>
              <div className="completion-card"><div><span>资料字段</span><strong>{profileCompletion(profile)}%</strong></div><div className="completion-track"><span style={{ width: `${profileCompletion(profile)}%` }} /></div><p>仅展示和服务端同步的公开资料，不伪造缺失字段。</p></div>
            </section>

            <section className="content-card security-card">
              <div className="card-heading"><div><span className="eyebrow">SECURITY</span><h3>账号安全</h3></div><span className="security-score">Session</span></div>
              <div className="security-orbit"><div className="security-ring"><span className="security-shield"><Icon name="shield" size={31} /></span></div><div><strong>HttpOnly Session 已启用</strong><p>浏览器不保存 Phantom Token</p></div></div>
              <div className="security-list">
                <div><span><Icon name="lock" size={17} /></span><div><strong>登录方式</strong><small>账号密码 + 短期 UserToken</small></div><span className="security-ok"><Icon name="check" size={13} /></span></div>
                <button type="button" onClick={() => setSecurityOpen(true)}><span><Icon name="smartphone" size={17} /></span><div><strong>登录设备</strong><small>{devices.length} 台设备在线</small></div><Icon name="chevronRight" size={16} /></button>
              </div>
            </section>

            <section className="content-card preference-card">
              <div className="card-heading"><div><span className="eyebrow">PREFERENCES</span><h3>偏好设置</h3></div><Icon name="palette" size={18} /></div>
              <PreferenceRow icon="volume" title="消息提示音" description="本机提示音偏好" checked={soundEnabled} onChange={setSoundEnabled} />
              <PreferenceRow icon="bellOff" title="服务端通知" description="通过 SDK 同步推送设置" checked={pushSettings?.pushEnabled ?? false} onChange={(checked) => void updatePush({ pushEnabled: checked })} />
              <PreferenceRow icon="grid" title="紧凑会话列表" description="仅保存在本机" checked={compactMode} onChange={setCompactMode} />
              <div className="preference-row is-disabled"><span className="preference-icon"><Icon name="users" size={18} /></span><div><strong>展示在线状态</strong><small>当前没有独立公开隐私设置契约</small></div><span className="menu-unavailable">暂未开放</span></div>
            </section>

            <section className="content-card activity-card">
              <div className="card-heading"><div><span className="eyebrow">NOTIFICATIONS</span><h3>浏览器通知</h3></div><span className="heading-count">{notificationPermissionLabel(notificationPermission)}</span></div>
              <p className="card-empty-copy">通知点击会通过 SDK 公开的 `notificationClick` 事件定位真实会话。</p>
              <button type="button" className="secondary-button secondary-button--small" onClick={() => void requestNotifications()}>请求通知权限</button>
            </section>
          </div>

          <footer className="profile-main-footer">
            <span>Phantom IM · Easy IM Web</span>
            <button type="button" onClick={onLogout}><Icon name="logout" size={15} />退出登录</button>
          </footer>
        </div>
      </section>

      <Modal open={editOpen} title="编辑个人资料" description="保存成功后以 Phantom 服务端返回为准" onClose={() => setEditOpen(false)}>
        {draft && (
          <form className="modal-form" onSubmit={(event) => void saveProfile(event)}>
            <div className="avatar-edit-row"><Avatar chars={[draft.name.slice(-2)]} tone="mint" size={62} /><div><strong>个人头像</strong><small>头像资源由公开媒体接口管理</small></div><span className="menu-unavailable">入口暂未开放</span></div>
            <label className="form-field"><span>昵称</span><input value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} /></label>
            <label className="form-field"><span>个性签名</span><input value={draft.signature} onChange={(event) => setDraft({ ...draft, signature: event.target.value })} /></label>
            <div className="form-field-grid">
              <label className="form-field"><span>所在地区</span><input value={draft.location} onChange={(event) => setDraft({ ...draft, location: event.target.value })} /></label>
              <label className="form-field"><span>手机号</span><input value={draft.phone} onChange={(event) => setDraft({ ...draft, phone: event.target.value })} /></label>
            </div>
            <label className="form-field"><span>邮箱</span><input type="email" value={draft.email} onChange={(event) => setDraft({ ...draft, email: event.target.value })} /></label>
            <div className="modal-actions"><button type="button" className="secondary-button" onClick={() => setEditOpen(false)}>取消</button><button type="submit" className="primary-button" disabled={saving || !draft.name.trim()}>{saving ? '保存中…' : '保存修改'}</button></div>
          </form>
        )}
      </Modal>

      <Modal open={qrOpen} title="我的二维码" description="二维码内容来自真实 QR Card API" onClose={() => setQrOpen(false)} width={410}>
        <div className="qr-modal-content">
          <div className="qr-frame qr-frame--image">{qrBusy ? <span className="boot-spinner" /> : qrImage ? <img src={qrImage} alt="Phantom 好友二维码" /> : <p>暂无二维码</p>}</div>
          <Avatar chars={[shortName]} tone="mint" size={54} />
          <h3>{profile.name}</h3><p>@{profile.handle || state.account?.loginName}</p>
          {qrCard && <small className="qr-expiry">{qrCard.expiresAt ? `有效期至 ${formatDateTime(qrCard.expiresAt)}` : '长期有效'}</small>}
          <div className="qr-actions"><button type="button" className="secondary-button qr-copy-button" disabled={!qrCard} onClick={() => void copyQr()}><Icon name="copy" size={15} />复制名片链接</button><button type="button" className="secondary-button qr-copy-button" disabled={qrBusy} onClick={() => void refreshQr()}><Icon name="refresh" size={15} />刷新二维码</button></div>
        </div>
      </Modal>

      <Modal open={securityOpen} title="登录设备" description="设备列表来自 Phantom 在线路由" onClose={() => setSecurityOpen(false)} width={560}>
        <div className="device-list">
          {devices.length === 0 ? <p className="card-empty-copy">暂无在线设备</p> : devices.map((device) => {
            const current = device.deviceId === currentDeviceId
            return (
              <div className="device-row" key={device.deviceId}>
                <span className="device-icon"><Icon name={device.deviceType === 1 ? 'smartphone' : 'grid'} size={20} /></span>
                <div><strong>{device.deviceName || device.platform || 'Web 设备'}{current ? '（当前）' : ''}</strong><small>{device.platform} · {device.location || '位置未知'} · {formatDateTime(device.lastActiveAt)}</small></div>
                {!current && <button type="button" onClick={() => void logoutDevice(device)}>下线</button>}
              </div>
            )
          })}
        </div>
      </Modal>

      <Modal open={preferencesOpen} title="偏好设置" description="服务端通知与本机显示偏好分开管理" onClose={() => setPreferencesOpen(false)} width={520}>
        <div className="preferences-modal">
          <PreferenceRow icon="bellOff" title="推送通知" description="SDK `push_enabled`" checked={pushSettings?.pushEnabled ?? false} onChange={(checked) => void updatePush({ pushEnabled: checked })} />
          <PreferenceRow icon="message" title="通知预览" description="SDK `preview_enabled`" checked={pushSettings?.previewEnabled ?? false} onChange={(checked) => void updatePush({ previewEnabled: checked })} />
          <PreferenceRow icon="volume" title="本机提示音" description="仅保存在当前浏览器" checked={soundEnabled} onChange={setSoundEnabled} />
          <PreferenceRow icon="grid" title="紧凑模式" description="仅保存在当前浏览器" checked={compactMode} onChange={setCompactMode} />
          <button type="button" className="secondary-button" onClick={() => void requestNotifications()}>设置浏览器通知权限</button>
        </div>
      </Modal>

      <Modal open={diagnosticsOpen} title="连接诊断" description="以下数据仅来自公开 SDK/BFF 状态" onClose={() => setDiagnosticsOpen(false)} width={590}>
        <div className="diagnostics-grid">
          <DiagnosticRow label="连接状态" value={connectionLabel(connectionState)} />
          <DiagnosticRow label="Phantom ID" value={state.account?.phantomUserId ?? '-'} />
          <DiagnosticRow label="App ID" value={runtime?.appId ?? '-'} />
          <DiagnosticRow label="WebSocket" value={runtime?.wsUrl ?? '-'} />
          <DiagnosticRow label="Request ID" value={requestId ?? '暂无'} />
          <DiagnosticRow label="测试模式" value={runtime?.testMode ? '已启用' : '未启用'} />
          <DiagnosticRow label="设备 ID" value={currentDeviceId} />
          <DiagnosticRow label="最近原因" value={state.reason ?? '无'} />
        </div>
        <div className="diagnostic-log" aria-label="连接诊断日志">
          <div className="diagnostic-log-heading"><strong>最近事件</strong><small>最多保留 100 条，不含消息正文或凭据</small></div>
          {diagnostics.length === 0 ? <p className="card-empty-copy">暂无诊断事件</p> : diagnostics.slice(0, 20).map((entry) => (
            <div className={`diagnostic-log-row diagnostic-log-row--${entry.level}`} key={entry.id}>
              <time>{new Date(entry.at).toLocaleTimeString('zh-CN', { hour12: false })}</time>
              <span>{entry.message}</span>
            </div>
          ))}
        </div>
        <div className="diagnostic-actions">
          <button type="button" className="secondary-button" disabled={Boolean(diagnosticsBusy)} onClick={() => void runDiagnostic('refresh')}>{diagnosticsBusy === 'refresh' ? '刷新中…' : '刷新 Token'}</button>
          <button type="button" className="secondary-button" disabled={Boolean(diagnosticsBusy) || !runtime?.testMode} onClick={() => void runDiagnostic('expire')}>{diagnosticsBusy === 'expire' ? '处理中…' : '模拟 Token 过期'}</button>
          <button type="button" className="secondary-button" disabled={Boolean(diagnosticsBusy)} onClick={() => void runDiagnostic('reconnect')}>{diagnosticsBusy === 'reconnect' ? '重连中…' : '断线重连'}</button>
        </div>
      </Modal>

      {notice && <div className="toast toast--neutral" role="status"><Icon name="shield" size={16} />{notice}</div>}
    </div>
  )
}

function ProfileInfoRow({ icon, label, value }: { icon: 'message' | 'sparkles' | 'mapPin' | 'mail' | 'smartphone'; label: string; value: string }) {
  return <div className="info-row profile-info-row"><span className="info-icon"><Icon name={icon} size={17} /></span><span className="info-label">{label}</span><span className="info-value">{value}</span></div>
}

function PreferenceRow({ icon, title, description, checked, onChange }: { icon: 'volume' | 'users' | 'grid' | 'bellOff' | 'message'; title: string; description: string; checked: boolean; onChange: (checked: boolean) => void }) {
  return <div className="preference-row"><span className="preference-icon"><Icon name={icon} size={18} /></span><div><strong>{title}</strong><small>{description}</small></div><Switch checked={checked} onChange={onChange} label={title} /></div>
}

function DiagnosticRow({ label, value }: { label: string; value: string }) {
  return <div className="diagnostic-row"><span>{label}</span><strong title={value}>{value}</strong></div>
}

function ProfileState({ title, description, action }: { title: string; description: string; action?: React.ReactNode }) {
  return <div className="workspace-view view-state"><div className="view-state-card"><span className="view-state-icon"><Icon name="user" size={25} /></span><h2>{title}</h2><p>{description}</p>{action}</div></div>
}

function getGreeting() {
  const hour = new Date().getHours()
  if (hour < 6) return { zh: '夜深了', en: 'GOOD NIGHT' }
  if (hour < 11) return { zh: '早上好', en: 'GOOD MORNING' }
  if (hour < 14) return { zh: '中午好', en: 'GOOD NOON' }
  if (hour < 19) return { zh: '下午好', en: 'GOOD AFTERNOON' }
  return { zh: '晚上好', en: 'GOOD EVENING' }
}

function connectionLabel(state: string) {
  return {
    online: '已连接',
    connecting: '连接中',
    authing: '认证中',
    reconnecting: '重连中',
    closed: '已断开',
    idle: '未连接',
  }[state] ?? state
}

function notificationPermissionLabel(permission: NotificationPermission | 'unsupported') {
  if (permission === 'granted') return '已授权'
  if (permission === 'denied') return '已拒绝'
  if (permission === 'default') return '未授权'
  return '不支持'
}

function profileCompletion(profile: UserProfile): number {
  const fields = [profile.name, profile.signature, profile.location, profile.email, profile.phone]
  return Math.round(fields.filter(Boolean).length / fields.length * 100)
}

function formatDateTime(value: number): string {
  if (!value) return '未知时间'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function readBoolean(key: string, fallback: boolean): boolean {
  try {
    const value = localStorage.getItem(key)
    return value === null ? fallback : value === 'true'
  } catch {
    return fallback
  }
}

function writeBoolean(key: string, value: boolean): void {
  try { localStorage.setItem(key, String(value)) } catch { /* 本机偏好写入失败不影响核心聊天 */ }
}

function reasonMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}
