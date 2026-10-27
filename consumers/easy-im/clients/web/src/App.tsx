import { useState } from 'react'
import { initialProfile, type UserProfile } from './data'
import { Icon, PhantomMark, type IconName } from './icons'
import AuthScreen, { type AuthAccount } from './views/AuthScreen'
import ChatsView from './views/ChatsView'
import ContactsView from './views/ContactsView'
import ProfileView from './views/ProfileView'

type AppTab = 'chats' | 'contacts' | 'profile'

const navigation: Array<{ id: AppTab; label: string; icon: IconName }> = [
  { id: 'chats', label: '消息', icon: 'chat' },
  { id: 'contacts', label: '通讯录', icon: 'contacts' },
  { id: 'profile', label: '个人中心', icon: 'user' },
]

export default function App() {
  const [authenticated, setAuthenticated] = useState(false)
  const [activeTab, setActiveTab] = useState<AppTab>('chats')
  const [unreadTotal, setUnreadTotal] = useState(8)
  const [profile, setProfile] = useState<UserProfile>(initialProfile)

  function handleAuthenticated(account: AuthAccount) {
    setProfile((current) => ({
      ...current,
      name: account.nickname,
      handle: account.loginName,
    }))
    setAuthenticated(true)
  }

  if (!authenticated) {
    return <AuthScreen onAuthenticated={handleAuthenticated} />
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
                      <span className="nav-badge">{unreadTotal}</span>
                    )}
                  </span>
                  <span>{item.label}</span>
                </button>
              )
            })}
          </nav>

          <div className="nav-rail-foot">
            <span className="rail-status"><span /> 在线</span>
            <span className="rail-version">v1.0</span>
          </div>
        </aside>

        <div className="workspace">
          {activeTab === 'chats' && <ChatsView onUnreadChange={setUnreadTotal} />}
          {activeTab === 'contacts' && <ContactsView />}
          {activeTab === 'profile' && <ProfileView profile={profile} onUpdate={setProfile} />}
        </div>
      </div>
    </main>
  )
}
