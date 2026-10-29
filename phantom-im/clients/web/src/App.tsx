import { useEffect, useState } from 'react'
import { useApp } from './app/AppProvider'
import { useUnreadBadge } from './app/useUnreadBadge'
import { CountBadge } from './components/CountBadge'
import { Modal } from './components/ui'
import { Icon, PhantomMark, type IconName } from './icons'
import AuthScreen from './views/AuthScreen'
import ChatsView from './views/ChatsView'
import ContactsView, { type ConversationTarget } from './views/ContactsView'
import ProfileView from './views/ProfileView'

type AppTab = 'chats' | 'contacts' | 'profile'

const navigation: Array<{ id: AppTab; label: string; icon: IconName }> = [
  { id: 'chats', label: '消息', icon: 'chat' },
  { id: 'contacts', label: '通讯录', icon: 'contacts' },
  { id: 'profile', label: '个人中心', icon: 'user' },
]

export default function App() {
  const { state, login, register, retry, logout, dismissKicked, adapter, gateway: connectionGateway } = useApp()
  const [activeTab, setActiveTab] = useState<AppTab>('chats')
  const [logoutOpen, setLogoutOpen] = useState(false)
  const [logoutBusy, setLogoutBusy] = useState(false)
  const [retrying, setRetrying] = useState(false)
  const [chatTarget, setChatTarget] = useState<ConversationTarget>()
  const { unreadTotal, markConversationRead, refreshUnread } = useUnreadBadge(connectionGateway)

  useEffect(() => {
    const currentGateway = connectionGateway
    if (!currentGateway) return
    const openNotificationConversation = async (convId: string) => {
      if (!convId) return
      let name = '会话'
      let kind: 'direct' | 'group' = 'direct'
      try {
        const conversation = (await currentGateway.listConversations()).find((item) => item.id === convId)
        if (conversation) {
          name = conversation.name
          kind = conversation.kind
        }
      } catch {
        // 通知仍可打开会话，标题稍后由会话快照补齐。
      }
      setChatTarget({ id: convId, name, kind })
      setActiveTab('chats')
      window.focus()
    }
    const offMessage = currentGateway.on('message', (message: any) => {
      if (message.senderId === currentGateway.currentUserId) return
      if (typeof Notification === 'undefined'
          || Notification.permission !== 'granted'
          || document.visibilityState === 'visible') return
      try {
        const notification = new Notification(message.author || 'Phantom 新消息', {
          body: message.text || '[新消息]',
          tag: message.msgId || message.clientMsgId,
        })
        notification.onclick = () => {
          void openNotificationConversation(message.convId)
          notification.close()
        }
      } catch {
        // 浏览器通知失败不影响实时消息。
      }
    })
    const offClick = currentGateway.on('notificationClick', (data: Record<string, string>) => {
      void openNotificationConversation(data.conv_id)
    })
    return () => { offMessage(); offClick() }
  }, [connectionGateway])

  if (state.phase === 'bootstrapping' || state.phase === 'connecting' || state.phase === 'syncing') {
    return (
      <BootScreen
        phase={state.phase}
        title={state.phase === 'bootstrapping' ? '正在恢复登录状态' : '正在连接 Phantom'}
        detail={state.phase === 'syncing' ? '正在同步会话与消息…' : '请稍候'}
      />
    )
  }

  if (state.phase === 'error') {
    return (
      <BootScreen
        phase="error"
        title="无法连接 Easy IM"
        detail={errorMessage(state.error)}
        action={(
          <button
            type="button"
            className="primary-button"
            disabled={retrying}
            onClick={() => void handleRetry()}
          >
            {retrying ? '正在重试…' : '重试'}
          </button>
        )}
      />
    )
  }

  if (state.phase === 'kicked') {
    return (
      <BootScreen
        phase="kicked"
        title="当前设备已被顶替"
        detail={state.reason ?? '同一账号已在其他 Web 设备登录'}
        action={<button type="button" className="primary-button" onClick={dismissKicked}>重新登录</button>}
      />
    )
  }

  if (state.phase === 'unauthenticated' || !state.account) {
    return (
      <AuthScreen
        onLogin={async (input) => {
          await login(input)
        }}
        onRegister={register}
      />
    )
  }

  const account = state.account
  const connectionLabel = adapter?.state === 'online'
    ? '在线'
    : adapter?.state === 'reconnecting'
      ? '重连中'
      : '连接中'

  async function handleRetry() {
    if (retrying) return
    setRetrying(true)
    try {
      await retry()
    } finally {
      setRetrying(false)
    }
  }

  async function confirmLogout() {
    setLogoutBusy(true)
    try {
      await logout()
      setLogoutOpen(false)
    } finally {
      setLogoutBusy(false)
    }
  }

  return (
    <main className="desktop-app">
      <div className="app-shell">
        <aside className="nav-rail" aria-label="主导航">
          <div className="brand" title="Phantom IM">
            <span className="brand-mark"><PhantomMark size={28} /></span>
            <span className="brand-word">PHANTOM</span>
          </div>

          <nav className="primary-nav">
            {navigation.map((item) => {
              const isActive = activeTab === item.id
              return (
                <button
                  type="button"
                  key={item.id}
                  className={`nav-item ${isActive ? 'is-active' : ''}`}
                  onClick={() => setActiveTab(item.id)}
                  aria-current={isActive ? 'page' : undefined}
                  title={item.label}
                >
                  <span className="nav-icon">
                    <Icon name={item.icon} size={23} />
                    {item.id === 'chats' && unreadTotal > 0 && (
                      <CountBadge
                        count={unreadTotal}
                        label="未读消息"
                        tone="danger"
                        size="sm"
                        className="nav-badge"
                      />
                    )}
                  </span>
                  <span>{item.label}</span>
                </button>
              )
            })}
          </nav>

          <div className="nav-rail-foot">
            <span className={`rail-status rail-status--${adapter?.state ?? 'idle'}`}>
              <span /> {connectionLabel}
            </span>
            <span className="rail-version">v1.0</span>
          </div>
        </aside>

        <div className="workspace">
          {activeTab === 'chats' && (
            <ChatsView
              onConversationRead={markConversationRead}
              onUnreadRefresh={refreshUnread}
              initialTarget={chatTarget}
              onTargetConsumed={() => setChatTarget(undefined)}
            />
          )}
          {activeTab === 'contacts' && (
            <ContactsView onOpenConversation={(target) => {
              setChatTarget(target)
              setActiveTab('chats')
            }} />
          )}
          {activeTab === 'profile' && (
            <ProfileView onLogout={() => setLogoutOpen(true)} />
          )}
        </div>
      </div>

      <Modal
        open={logoutOpen}
        title="退出登录"
        description="退出后将停止实时连接并清除当前设备上的登录状态。"
        onClose={() => !logoutBusy && setLogoutOpen(false)}
        width={420}
      >
        <div className="modal-actions">
          <button type="button" className="secondary-button" disabled={logoutBusy} onClick={() => setLogoutOpen(false)}>
            取消
          </button>
          <button type="button" className="primary-button" disabled={logoutBusy} onClick={() => void confirmLogout()}>
            {logoutBusy ? '正在退出…' : '确认退出'}
          </button>
        </div>
      </Modal>
    </main>
  )
}

function BootScreen({
  phase,
  title,
  detail,
  action,
}: {
  phase: string
  title: string
  detail: string
  action?: React.ReactNode
}) {
  return (
    <main className={`boot-screen boot-screen--${phase}`}>
      <span className="boot-glow" />
      <div className="boot-card" role="status">
        <span className="boot-mark"><PhantomMark size={34} /></span>
        <span className="eyebrow">PHANTOM IM</span>
        <h1>{title}</h1>
        <p>{detail}</p>
        {!action && <span className="boot-spinner" aria-label="加载中" />}
        {action}
      </div>
    </main>
  )
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) {
    const detail = error as Error & { code?: number; requestId?: string }
    const diagnostics = [
      detail.code ? `错误码 ${detail.code}` : '',
      detail.requestId ? `request_id ${detail.requestId}` : '',
    ].filter(Boolean)
    return diagnostics.length > 0 ? `${error.message}（${diagnostics.join(' · ')}）` : error.message
  }
  return '请检查 BFF、Phantom 与本地中间件是否已启动'
}
