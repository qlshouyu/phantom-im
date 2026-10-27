import { type FormEvent, useEffect, useMemo, useState } from 'react'
import type { UserProfile } from '../data'
import { Icon } from '../icons'
import { Avatar, IconButton, Modal, Switch } from '../components/ui'

interface ProfileViewProps {
  profile: UserProfile
  onUpdate: (profile: UserProfile) => void
}

type ProfilePanel = 'qr' | 'favorites' | 'account' | 'preferences'

export default function ProfileView({ profile, onUpdate }: ProfileViewProps) {
  const [qrOpen, setQrOpen] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [toast, setToast] = useState('')
  const [soundEnabled, setSoundEnabled] = useState(true)
  const [showOnline, setShowOnline] = useState(true)
  const [compactMode, setCompactMode] = useState(false)
  const [draft, setDraft] = useState(profile)

  const greeting = getGreeting()
  const qrCells = useMemo(() => createQrPattern(), [])
  const shortName = profile.name.slice(-2)

  useEffect(() => {
    if (!toast) return
    const timeout = window.setTimeout(() => setToast(''), 2200)
    return () => window.clearTimeout(timeout)
  }, [toast])

  function openPanel(panel: ProfilePanel) {
    if (panel === 'qr') {
      setQrOpen(true)
      return
    }
    const messages: Record<Exclude<ProfilePanel, 'qr'>, string> = {
      favorites: '收藏夹里还没有内容',
      account: '账号与安全设置已是最新',
      preferences: '偏好设置已同步',
    }
    setToast(messages[panel])
  }

  function openEdit() {
    setDraft(profile)
    setEditOpen(true)
  }

  function saveProfile(event: FormEvent) {
    event.preventDefault()
    if (!draft.name.trim()) return
    onUpdate({ ...draft, name: draft.name.trim() })
    setEditOpen(false)
    setToast('个人资料已保存')
  }

  return (
    <div className="workspace-view profile-view">
      <section className="list-pane profile-side-pane" aria-label="个人中心导航">
        <header className="pane-heading">
          <div>
            <span className="eyebrow">MY PHANTOM</span>
            <h1>个人中心</h1>
          </div>
          <IconButton icon="more" label="更多个人中心操作" />
        </header>

        <div className="profile-card">
          <span className="profile-card-glow" />
          <div className="profile-card-topline">
            <span>MY PROFILE</span>
            <span className="profile-verified"><Icon name="check" size={11} /> 已认证</span>
          </div>
          <Avatar chars={[shortName]} tone="mint" size={76} online />
          <h2>{profile.name}</h2>
          <p>@{profile.handle}</p>
          <span className="profile-card-signature">{profile.signature}</span>
          <button type="button" className="profile-edit-button" onClick={openEdit}>
            <Icon name="edit" size={15} />
            编辑资料
          </button>
        </div>

        <nav className="profile-menu" aria-label="个人中心功能">
          <button type="button" onClick={() => openPanel('qr')}>
            <span className="menu-icon menu-icon--green"><Icon name="qr" size={19} /></span>
            <span className="menu-copy">
              <strong>我的二维码</strong>
              <small>扫码加我为好友</small>
            </span>
            <Icon name="chevronRight" size={17} />
          </button>
          <button type="button" onClick={() => openPanel('favorites')}>
            <span className="menu-icon menu-icon--amber"><Icon name="star" size={19} /></span>
            <span className="menu-copy">
              <strong>我的收藏</strong>
              <small>消息、图片与文件</small>
            </span>
            <span className="menu-count">24</span>
            <Icon name="chevronRight" size={17} />
          </button>
          <button type="button" onClick={() => openPanel('account')}>
            <span className="menu-icon menu-icon--blue"><Icon name="shield" size={19} /></span>
            <span className="menu-copy">
              <strong>账号与安全</strong>
              <small>密码、设备与隐私</small>
            </span>
            <Icon name="chevronRight" size={17} />
          </button>
          <button type="button" onClick={() => openPanel('preferences')}>
            <span className="menu-icon menu-icon--violet"><Icon name="palette" size={19} /></span>
            <span className="menu-copy">
              <strong>偏好设置</strong>
              <small>通知、显示与外观</small>
            </span>
            <Icon name="chevronRight" size={17} />
          </button>
        </nav>

        <div className="profile-side-footer">
          <div className="profile-storage">
            <div>
              <span>云端空间</span>
              <strong>2.4 GB <small>/ 10 GB</small></strong>
            </div>
            <div className="storage-track"><span /></div>
          </div>
          <button type="button" onClick={() => setToast('Phantom Web 已是最新版本')}>
            <Icon name="help" size={17} />
            帮助与反馈
            <span>v1.0.0</span>
          </button>
        </div>
      </section>

      <section className="detail-pane profile-main-pane" aria-label="个人资料与设置">
        <header className="detail-topbar">
          <div className="breadcrumb">
            <span>个人中心</span>
            <Icon name="chevronRight" size={13} />
            <strong>概览</strong>
          </div>
          <div className="profile-topbar-status">
            <span className="online-dot" />
            所有设备已同步
          </div>
        </header>

        <div className="profile-main-scroll">
          <section className="profile-welcome">
            <div>
              <span className="eyebrow">{greeting.en}</span>
              <h2>{greeting.zh}，{shortName}</h2>
              <p>欢迎回来。今天有 <strong>3 条</strong>新的互动等待查看。</p>
            </div>
            <div className="welcome-mark">
              <span />
              <span />
              <span />
            </div>
          </section>

          <section className="overview-grid">
            <article className="overview-card overview-card--primary">
              <div className="overview-label">
                <span className="overview-icon"><Icon name="users" size={19} /></span>
                好友
              </div>
              <strong>09</strong>
              <p>本周新增 2 位</p>
              <span className="overview-accent" />
            </article>
            <article className="overview-card">
              <div className="overview-label">
                <span className="overview-icon overview-icon--amber"><Icon name="group" size={19} /></span>
                群组
              </div>
              <strong>04</strong>
              <p>2 个群组有新消息</p>
            </article>
            <article className="overview-card">
              <div className="overview-label">
                <span className="overview-icon overview-icon--violet"><Icon name="message" size={19} /></span>
                本周消息
              </div>
              <strong>186</strong>
              <p>比上周多 12%</p>
            </article>
          </section>

          <div className="profile-dashboard-grid">
            <section className="content-card profile-info-card">
              <div className="card-heading">
                <div>
                  <span className="eyebrow">PROFILE</span>
                  <h3>个人资料</h3>
                </div>
                <button type="button" className="secondary-button secondary-button--small" onClick={openEdit}>
                  <Icon name="edit" size={15} />
                  编辑
                </button>
              </div>
              <div className="info-list">
                <ProfileInfoRow icon="message" label="Phantom ID" value={`@${profile.handle}`} />
                <ProfileInfoRow icon="sparkles" label="个性签名" value={profile.signature} />
                <ProfileInfoRow icon="mapPin" label="所在地区" value={profile.location} />
                <ProfileInfoRow icon="mail" label="邮箱" value={profile.email} />
                <ProfileInfoRow icon="smartphone" label="手机号" value={profile.phone} />
              </div>
              <div className="completion-card">
                <div>
                  <span>资料完整度</span>
                  <strong>92%</strong>
                </div>
                <div className="completion-track"><span /></div>
                <p>补充个人主页背景，让好友更容易认出你。</p>
              </div>
            </section>

            <section className="content-card security-card">
              <div className="card-heading">
                <div>
                  <span className="eyebrow">SECURITY</span>
                  <h3>账号安全</h3>
                </div>
                <span className="security-score">安全</span>
              </div>
              <div className="security-orbit">
                <div className="security-ring">
                  <span className="security-shield"><Icon name="shield" size={31} /></span>
                </div>
                <div>
                  <strong>账号保护良好</strong>
                  <p>已开启登录保护与双重验证</p>
                </div>
              </div>
              <div className="security-list">
                <div>
                  <span><Icon name="lock" size={17} /></span>
                  <div><strong>登录密码</strong><small>上次更新于 3 个月前</small></div>
                  <span className="security-ok"><Icon name="check" size={13} /></span>
                </div>
                <div>
                  <span><Icon name="smartphone" size={17} /></span>
                  <div><strong>登录设备</strong><small>2 台设备正在使用</small></div>
                  <Icon name="chevronRight" size={16} />
                </div>
              </div>
            </section>

            <section className="content-card preference-card">
              <div className="card-heading">
                <div>
                  <span className="eyebrow">PREFERENCES</span>
                  <h3>偏好设置</h3>
                </div>
                <Icon name="palette" size={18} />
              </div>
              <PreferenceRow
                icon="volume"
                title="消息提示音"
                description="收到新消息时播放提示音"
                checked={soundEnabled}
                onChange={setSoundEnabled}
              />
              <PreferenceRow
                icon="users"
                title="展示在线状态"
                description="允许好友看到我的在线状态"
                checked={showOnline}
                onChange={setShowOnline}
              />
              <PreferenceRow
                icon="grid"
                title="紧凑会话列表"
                description="在同一屏展示更多聊天"
                checked={compactMode}
                onChange={setCompactMode}
              />
            </section>

            <section className="content-card activity-card">
              <div className="card-heading">
                <div>
                  <span className="eyebrow">ACTIVITY</span>
                  <h3>最近动态</h3>
                </div>
                <span className="heading-count">本周</span>
              </div>
              <div className="activity-list">
                <ActivityRow tone="mint" title="加入了「周末出逃计划」" time="今天 10:12" />
                <ActivityRow tone="amber" title="收藏了 3 条消息" time="昨天 18:40" />
                <ActivityRow tone="violet" title="更新了个人签名" time="周三 20:06" />
              </div>
            </section>
          </div>

          <footer className="profile-main-footer">
            <span>Phantom IM · Web Preview</span>
            <button type="button" onClick={() => setToast('退出登录需要二次确认')}>
              <Icon name="logout" size={15} />
              退出登录
            </button>
          </footer>
        </div>
      </section>

      <Modal
        open={qrOpen}
        title="我的二维码"
        description="使用 Phantom 扫一扫，加我为好友"
        onClose={() => setQrOpen(false)}
        width={390}
      >
        <div className="qr-modal-content">
          <div className="qr-frame">
            <div className="qr-grid">
              {qrCells.map((filled, index) => (
                <span key={index} className={filled ? 'is-filled' : ''} />
              ))}
            </div>
            <span className="qr-brand"><Icon name="chat" size={19} /></span>
          </div>
          <Avatar chars={[shortName]} tone="mint" size={54} />
          <h3>{profile.name}</h3>
          <p>@{profile.handle}</p>
          <button
            type="button"
            className="secondary-button qr-copy-button"
            onClick={() => setToast('Phantom ID 已复制')}
          >
            <Icon name="copy" size={15} />
            复制 Phantom ID
          </button>
        </div>
      </Modal>

      <Modal
        open={editOpen}
        title="编辑个人资料"
        description="这些信息将展示给你的好友"
        onClose={() => setEditOpen(false)}
      >
        <form className="modal-form" onSubmit={saveProfile}>
          <div className="avatar-edit-row">
            <Avatar chars={[draft.name.slice(-2)]} tone="mint" size={62} />
            <div>
              <strong>个人头像</strong>
              <small>支持 JPG、PNG，最大 5 MB</small>
            </div>
            <button type="button" className="secondary-button secondary-button--small" onClick={() => setToast('头像上传功能已就绪')}>
              <Icon name="camera" size={15} />
              更换
            </button>
          </div>
          <label className="form-field">
            <span>昵称</span>
            <input value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} />
          </label>
          <label className="form-field">
            <span>个性签名</span>
            <input value={draft.signature} onChange={(event) => setDraft({ ...draft, signature: event.target.value })} />
          </label>
          <div className="form-field-grid">
            <label className="form-field">
              <span>所在地区</span>
              <input value={draft.location} onChange={(event) => setDraft({ ...draft, location: event.target.value })} />
            </label>
            <label className="form-field">
              <span>手机号</span>
              <input value={draft.phone} onChange={(event) => setDraft({ ...draft, phone: event.target.value })} />
            </label>
          </div>
          <label className="form-field">
            <span>邮箱</span>
            <input type="email" value={draft.email} onChange={(event) => setDraft({ ...draft, email: event.target.value })} />
          </label>
          <div className="modal-actions">
            <button type="button" className="secondary-button" onClick={() => setEditOpen(false)}>取消</button>
            <button type="submit" className="primary-button" disabled={!draft.name.trim()}>保存修改</button>
          </div>
        </form>
      </Modal>

      {toast && (
        <div className="toast" role="status">
          <Icon name="check" size={16} />
          {toast}
        </div>
      )}
    </div>
  )
}

function ProfileInfoRow({
  icon,
  label,
  value,
}: {
  icon: 'message' | 'sparkles' | 'mapPin' | 'mail' | 'smartphone'
  label: string
  value: string
}) {
  return (
    <div className="info-row profile-info-row">
      <span className="info-icon"><Icon name={icon} size={17} /></span>
      <span className="info-label">{label}</span>
      <span className="info-value">{value}</span>
    </div>
  )
}

function PreferenceRow({
  icon,
  title,
  description,
  checked,
  onChange,
}: {
  icon: 'volume' | 'users' | 'grid'
  title: string
  description: string
  checked: boolean
  onChange: (checked: boolean) => void
}) {
  return (
    <div className="preference-row">
      <span className="preference-icon"><Icon name={icon} size={18} /></span>
      <div>
        <strong>{title}</strong>
        <small>{description}</small>
      </div>
      <Switch checked={checked} onChange={onChange} label={title} />
    </div>
  )
}

function ActivityRow({ tone, title, time }: { tone: 'mint' | 'amber' | 'violet'; title: string; time: string }) {
  return (
    <div className="activity-row">
      <span className={`activity-dot activity-dot--${tone}`} />
      <div>
        <strong>{title}</strong>
        <small>{time}</small>
      </div>
    </div>
  )
}

function getGreeting() {
  const hour = new Date().getHours()
  if (hour < 6) return { zh: '夜深了', en: 'GOOD NIGHT' }
  if (hour < 11) return { zh: '早上好', en: 'GOOD MORNING' }
  if (hour < 14) return { zh: '中午好', en: 'GOOD NOON' }
  if (hour < 19) return { zh: '下午好', en: 'GOOD AFTERNOON' }
  return { zh: '晚上好', en: 'GOOD EVENING' }
}

function createQrPattern() {
  const size = 21
  const cells: boolean[] = []

  const isFinderCell = (x: number, y: number, originX: number, originY: number) => {
    const localX = x - originX
    const localY = y - originY
    if (localX < 0 || localY < 0 || localX > 6 || localY > 6) return false
    const outer = localX === 0 || localX === 6 || localY === 0 || localY === 6
    const center = localX >= 2 && localX <= 4 && localY >= 2 && localY <= 4
    return outer || center
  }

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const inFinder =
        isFinderCell(x, y, 0, 0) ||
        isFinderCell(x, y, 14, 0) ||
        isFinderCell(x, y, 0, 14)
      const inFinderQuietZone =
        (x <= 7 && y <= 7) ||
        (x >= 13 && y <= 7) ||
        (x <= 7 && y >= 13)
      const randomFill = (x * 11 + y * 7 + x * y * 3 + (x + y) * 5) % 13 < 6
      cells.push(inFinder || (!inFinderQuietZone && randomFill))
    }
  }

  return cells
}
