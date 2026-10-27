import { type FormEvent, useEffect, useMemo, useState } from 'react'
import {
  initialContacts,
  initialGroups,
  type AvatarTone,
  type Contact,
  type ContactGroup,
} from '../data'
import { Icon } from '../icons'
import { Avatar, EmptyState, IconButton, Modal, SearchField } from '../components/ui'

type ContactSection = 'friends' | 'groups'
type AddKind = 'friend' | 'group'

const toneCycle: AvatarTone[] = ['mint', 'sky', 'coral', 'amber', 'violet', 'rose', 'sage', 'ocean']

export default function ContactsView() {
  const [contacts, setContacts] = useState(initialContacts)
  const [groups, setGroups] = useState(initialGroups)
  const [section, setSection] = useState<ContactSection>('friends')
  const [activeFriendId, setActiveFriendId] = useState(initialContacts[0].id)
  const [activeGroupId, setActiveGroupId] = useState(initialGroups[0].id)
  const [query, setQuery] = useState('')
  const [addOpen, setAddOpen] = useState(false)
  const [addKind, setAddKind] = useState<AddKind>('friend')
  const [toast, setToast] = useState('')
  const [mobileDetailOpen, setMobileDetailOpen] = useState(false)

  const activeFriend = contacts.find((contact) => contact.id === activeFriendId) ?? contacts[0]
  const activeGroup = groups.find((group) => group.id === activeGroupId) ?? groups[0]

  const filteredContacts = useMemo(() => {
    const normalized = query.trim().toLowerCase()
    if (!normalized) return contacts
    return contacts.filter((contact) =>
      `${contact.name} ${contact.title} ${contact.tags.join(' ')}`.toLowerCase().includes(normalized),
    )
  }, [contacts, query])

  const filteredGroups = useMemo(() => {
    const normalized = query.trim().toLowerCase()
    if (!normalized) return groups
    return groups.filter((group) =>
      `${group.name} ${group.description}`.toLowerCase().includes(normalized),
    )
  }, [groups, query])

  const groupedContacts = useMemo(() => {
    return filteredContacts.reduce<Record<string, Contact[]>>((groupsByInitial, contact) => {
      groupsByInitial[contact.initial] = [...(groupsByInitial[contact.initial] ?? []), contact]
      return groupsByInitial
    }, {})
  }, [filteredContacts])

  useEffect(() => {
    if (!toast) return
    const timeout = window.setTimeout(() => setToast(''), 2200)
    return () => window.clearTimeout(timeout)
  }, [toast])

  function switchSection(next: ContactSection) {
    setSection(next)
    setQuery('')
    setMobileDetailOpen(false)
  }

  function openAdd(kind: AddKind) {
    setAddKind(kind)
    setAddOpen(true)
  }

  function createContact(kind: AddKind, name: string, detail: string) {
    const tone = toneCycle[(contacts.length + groups.length) % toneCycle.length]
    const id = `${kind}-${Date.now()}`

    if (kind === 'friend') {
      const newContact: Contact = {
        id,
        name,
        initial: 'A',
        title: detail || '新朋友',
        tone,
        online: false,
        location: '未设置',
        signature: '很高兴认识你。',
        tags: ['新朋友'],
        phone: '未设置',
        email: '未设置',
        mutualGroups: [],
      }
      setContacts((current) => [newContact, ...current])
      setActiveFriendId(id)
      switchSection('friends')
      setMobileDetailOpen(true)
      setToast(`已添加好友「${name}」`)
    } else {
      const newGroup: ContactGroup = {
        id,
        name,
        members: 3,
        online: 1,
        description: detail || '一个新的交流空间。',
        announcement: '群组刚刚创建，来打个招呼吧。',
        tone,
        avatarChars: [name.slice(0, 1), '新', '群', '聊'],
        memberNames: ['林知夏', '沈屿', '苏禾'],
        recentActivity: '你刚刚创建了群组',
      }
      setGroups((current) => [newGroup, ...current])
      setActiveGroupId(id)
      switchSection('groups')
      setMobileDetailOpen(true)
      setToast(`已创建群组「${name}」`)
    }
    setAddOpen(false)
  }

  return (
    <div className={`workspace-view contacts-view ${mobileDetailOpen ? 'mobile-detail-open' : ''}`}>
      <section className="list-pane contacts-list-pane" aria-label="通讯录列表">
        <header className="pane-heading">
          <div>
            <span className="eyebrow">ADDRESS BOOK</span>
            <h1>通讯录</h1>
          </div>
          <IconButton
            icon="plus"
            label="添加好友或群组"
            variant="soft"
            onClick={() => openAdd(section === 'friends' ? 'friend' : 'group')}
          />
        </header>

        <div className="segmented contact-tabs">
          <button
            type="button"
            className={section === 'friends' ? 'is-active' : ''}
            onClick={() => switchSection('friends')}
          >
            <Icon name="user" size={17} />
            好友
            <span>{contacts.length}</span>
          </button>
          <button
            type="button"
            className={section === 'groups' ? 'is-active' : ''}
            onClick={() => switchSection('groups')}
          >
            <Icon name="group" size={18} />
            群组
            <span>{groups.length}</span>
          </button>
        </div>

        <SearchField
          placeholder={section === 'friends' ? '搜索好友、标签或职业' : '搜索群组'}
          aria-label="搜索通讯录"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onClear={() => setQuery('')}
        />

        <div className="contacts-scroll">
          {section === 'friends' ? (
            filteredContacts.length === 0 ? (
              <EmptyState title="没有找到好友" description="试试搜索姓名、职业或标签" />
            ) : (
              Object.entries(groupedContacts).map(([initial, groupContacts]) => (
                <div className="contact-letter-group" key={initial}>
                  <span className="letter-heading">{initial}</span>
                  {groupContacts.map((contact) => (
                    <button
                      type="button"
                      className={`contact-list-row ${
                        contact.id === activeFriend.id ? 'is-active' : ''
                      }`}
                      key={contact.id}
                      onClick={() => {
                        setActiveFriendId(contact.id)
                        setMobileDetailOpen(true)
                      }}
                    >
                      <Avatar
                        chars={[contact.name.slice(-1)]}
                        tone={contact.tone}
                        size={45}
                        online={contact.online}
                      />
                      <span className="contact-row-copy">
                        <strong>{contact.name}</strong>
                        <small>{contact.title}</small>
                      </span>
                      <span className="contact-row-chevron">
                        <Icon name="chevronRight" size={16} />
                      </span>
                    </button>
                  ))}
                </div>
              ))
            )
          ) : filteredGroups.length === 0 ? (
            <EmptyState title="没有找到群组" description="换个关键词，或创建一个新群组" />
          ) : (
            <div className="group-contact-list">
              {filteredGroups.map((group) => (
                <button
                  type="button"
                  className={`group-list-row ${group.id === activeGroup.id ? 'is-active' : ''}`}
                  key={group.id}
                  onClick={() => {
                    setActiveGroupId(group.id)
                    setMobileDetailOpen(true)
                  }}
                >
                  <Avatar
                    chars={group.avatarChars}
                    tone={group.tone}
                    size={48}
                    group
                  />
                  <span className="contact-row-copy">
                    <strong>{group.name}</strong>
                    <small>{group.members} 位成员 · {group.online} 人在线</small>
                  </span>
                  <span className="contact-row-chevron">
                    <Icon name="chevronRight" size={16} />
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>

        <button
          type="button"
          className="list-create-card"
          onClick={() => openAdd(section === 'friends' ? 'friend' : 'group')}
        >
          <span>
            <Icon name={section === 'friends' ? 'plus' : 'group'} size={19} />
          </span>
          <div>
            <strong>{section === 'friends' ? '添加新的朋友' : '创建一个群组'}</strong>
            <small>{section === 'friends' ? '通过 Phantom ID 或手机号添加' : '邀请好友一起开始交流'}</small>
          </div>
          <Icon name="chevronRight" size={17} />
        </button>
      </section>

      <section className="detail-pane contact-detail-pane" aria-label="联系人详情">
        <header className="detail-topbar">
          <IconButton
            icon="arrowLeft"
            label="返回通讯录列表"
            className="mobile-back-button"
            onClick={() => setMobileDetailOpen(false)}
          />
          <div className="breadcrumb">
            <span>通讯录</span>
            <Icon name="chevronRight" size={13} />
            <strong>{section === 'friends' ? '好友资料' : '群组资料'}</strong>
          </div>
          <div className="detail-topbar-actions">
            {section === 'friends' ? (
              <button type="button" className="primary-button" onClick={() => setToast(`正在打开与 ${activeFriend.name} 的会话`)}>
                <Icon name="message" size={17} />
                发消息
              </button>
            ) : (
              <button type="button" className="primary-button" onClick={() => setToast(`已进入「${activeGroup.name}」`)}>
                <Icon name="message" size={17} />
                进入群聊
              </button>
            )}
            <IconButton icon="more" label="更多操作" />
          </div>
        </header>

        <div className="contact-detail-scroll">
          {section === 'friends' ? (
            <FriendDetail contact={activeFriend} onAction={setToast} />
          ) : (
            <GroupDetail group={activeGroup} onAction={setToast} />
          )}
        </div>
      </section>

      <AddContactModal
        open={addOpen}
        initialKind={addKind}
        onClose={() => setAddOpen(false)}
        onCreate={createContact}
      />

      {toast && (
        <div className="toast" role="status">
          <Icon name="check" size={16} />
          {toast}
        </div>
      )}
    </div>
  )
}

function FriendDetail({ contact, onAction }: { contact: Contact; onAction: (message: string) => void }) {
  return (
    <article className="contact-profile">
      <div className="profile-cover profile-cover--friend">
        <span className="cover-orb cover-orb--one" />
        <span className="cover-orb cover-orb--two" />
        <span className="cover-line cover-line--one" />
        <span className="cover-line cover-line--two" />
      </div>

      <div className="contact-profile-head">
        <Avatar chars={[contact.name.slice(-1)]} tone={contact.tone} size={104} online={contact.online} />
        <div className="contact-profile-title">
          <div className="name-status-line">
            <h2>{contact.name}</h2>
            <span className={contact.online ? 'status-pill is-online' : 'status-pill'}>
              <span />
              {contact.online ? '在线' : '离线'}
            </span>
          </div>
          <p>{contact.title}</p>
          <div className="tag-row">
            {contact.tags.map((tag) => (
              <span className="tag" key={tag}>{tag}</span>
            ))}
          </div>
        </div>
        <div className="profile-head-actions">
          <IconButton icon="star" label="特别关注" variant="soft" />
          <IconButton icon="more" label="更多资料操作" variant="soft" />
        </div>
      </div>

      <blockquote className="signature-card">
        <span>“</span>
        <p>{contact.signature}</p>
      </blockquote>

      <div className="profile-content-grid">
        <section className="content-card">
          <div className="card-heading">
            <div>
              <span className="eyebrow">ABOUT</span>
              <h3>基本信息</h3>
            </div>
            <IconButton icon="edit" label="编辑备注" size={17} onClick={() => onAction('备注编辑功能已就绪')} />
          </div>
          <div className="info-list">
            <InfoRow icon="message" label="Phantom ID" value={`@${contact.id.replace('c-', '')}`} copyable />
            <InfoRow icon="mapPin" label="所在地区" value={contact.location} />
            <InfoRow icon="mail" label="邮箱" value={contact.email} />
            <InfoRow icon="smartphone" label="手机号" value={contact.phone} />
          </div>
        </section>

        <section className="content-card">
          <div className="card-heading">
            <div>
              <span className="eyebrow">CONNECTIONS</span>
              <h3>共同群组</h3>
            </div>
            <span className="heading-count">{contact.mutualGroups.length}</span>
          </div>
          {contact.mutualGroups.length > 0 ? (
            <div className="mutual-group-list">
              {contact.mutualGroups.map((group, index) => (
                <div className="mutual-group-row" key={group}>
                  <Avatar
                    chars={[group.slice(0, 1), '群', '聊', '组']}
                    tone={toneCycle[(index + 2) % toneCycle.length]}
                    size={36}
                    group
                  />
                  <span>
                    <strong>{group}</strong>
                    <small>{6 + index * 4} 位共同成员</small>
                  </span>
                  <Icon name="chevronRight" size={16} />
                </div>
              ))}
            </div>
          ) : (
            <p className="card-empty-copy">暂时没有共同群组</p>
          )}
        </section>
      </div>

      <section className="content-card notes-card">
        <div className="card-heading">
          <div>
            <span className="eyebrow">NOTE</span>
            <h3>好友备注</h3>
          </div>
          <span className="note-time">更新于 10 月 18 日</span>
        </div>
        <p>上次一起聊了新的展览和城市空间，记得周末前确认时间。</p>
      </section>

      <div className="danger-row">
        <button type="button" onClick={() => onAction('已将好友设为消息免打扰')}>
          <Icon name="bellOff" size={17} />
          消息免打扰
        </button>
        <button type="button" className="is-danger" onClick={() => onAction('删除好友需要二次确认')}>
          删除好友
        </button>
      </div>
    </article>
  )
}

function GroupDetail({ group, onAction }: { group: ContactGroup; onAction: (message: string) => void }) {
  return (
    <article className="contact-profile group-profile">
      <div className="profile-cover profile-cover--group">
        <span className="cover-orb cover-orb--one" />
        <span className="cover-orb cover-orb--two" />
        <span className="cover-dots" />
      </div>

      <div className="contact-profile-head">
        <Avatar chars={group.avatarChars} tone={group.tone} size={104} group />
        <div className="contact-profile-title">
          <div className="name-status-line">
            <h2>{group.name}</h2>
            <span className="soft-badge">群组</span>
          </div>
          <p>{group.members} 位成员 · {group.online} 人在线</p>
          <div className="tag-row">
            <span className="tag">活跃群组</span>
            <span className="tag">消息免打扰关闭</span>
          </div>
        </div>
        <div className="profile-head-actions">
          <IconButton icon="bellOff" label="群消息免打扰" variant="soft" onClick={() => onAction('已切换群消息提醒状态')} />
          <IconButton icon="more" label="更多群组操作" variant="soft" />
        </div>
      </div>

      <section className="announcement-card">
        <span className="announcement-icon">
          <Icon name="pin" size={18} />
        </span>
        <div>
          <span className="eyebrow">GROUP NOTICE</span>
          <h3>群公告</h3>
          <p>{group.announcement}</p>
        </div>
        <Icon name="chevronRight" size={18} />
      </section>

      <div className="profile-content-grid group-content-grid">
        <section className="content-card">
          <div className="card-heading">
            <div>
              <span className="eyebrow">MEMBERS</span>
              <h3>群成员</h3>
            </div>
            <button type="button" className="text-button">查看全部</button>
          </div>
          <div className="member-stack-list">
            {group.memberNames.map((member, index) => (
              <div className="member-stack-row" key={member}>
                <Avatar chars={[member.slice(-1)]} tone={toneCycle[index % toneCycle.length]} size={38} />
                <span>
                  <strong>{member}</strong>
                  <small>{index === 0 ? '群主' : index === 1 ? '管理员' : '群成员'}</small>
                </span>
              </div>
            ))}
            <button type="button" className="invite-member-row" onClick={() => onAction('已打开邀请成员面板')}>
              <span><Icon name="plus" size={18} /></span>
              邀请成员
            </button>
          </div>
        </section>

        <section className="content-card">
          <div className="card-heading">
            <div>
              <span className="eyebrow">DETAILS</span>
              <h3>群组信息</h3>
            </div>
            <IconButton icon="edit" label="编辑群组信息" size={17} onClick={() => onAction('群组编辑功能已就绪')} />
          </div>
          <div className="info-list">
            <InfoRow icon="message" label="群组 ID" value={`@${group.id.replace('g-', '')}`} copyable />
            <InfoRow icon="users" label="成员数量" value={`${group.members} 人`} />
            <InfoRow icon="sparkles" label="最近动态" value={group.recentActivity} />
          </div>
          <p className="group-description">{group.description}</p>
        </section>
      </div>

      <div className="group-footer-actions">
        <button type="button" onClick={() => onAction('已打开群文件')}>
          <Icon name="folder" size={18} />
          群文件
        </button>
        <button type="button" onClick={() => onAction('已打开群相册')}>
          <Icon name="image" size={18} />
          群相册
        </button>
        <button type="button" className="is-danger" onClick={() => onAction('退出群组需要二次确认')}>
          <Icon name="logout" size={18} />
          退出群组
        </button>
      </div>
    </article>
  )
}

function InfoRow({
  icon,
  label,
  value,
  copyable = false,
}: {
  icon: 'message' | 'mapPin' | 'mail' | 'smartphone' | 'users' | 'sparkles'
  label: string
  value: string
  copyable?: boolean
}) {
  return (
    <div className="info-row">
      <span className="info-icon"><Icon name={icon} size={17} /></span>
      <span className="info-label">{label}</span>
      <span className="info-value">{value}</span>
      {copyable && (
        <button type="button" className="copy-button" aria-label={`复制${label}`}>
          <Icon name="copy" size={14} />
        </button>
      )}
    </div>
  )
}

function AddContactModal({
  open,
  initialKind,
  onClose,
  onCreate,
}: {
  open: boolean
  initialKind: AddKind
  onClose: () => void
  onCreate: (kind: AddKind, name: string, detail: string) => void
}) {
  const [kind, setKind] = useState<AddKind>(initialKind)
  const [name, setName] = useState('')
  const [detail, setDetail] = useState('')

  useEffect(() => {
    if (open) {
      setKind(initialKind)
      setName('')
      setDetail('')
    }
  }, [initialKind, open])

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!name.trim()) return
    onCreate(kind, name.trim(), detail.trim())
  }

  return (
    <Modal
      open={open}
      title={kind === 'friend' ? '添加新的朋友' : '创建一个群组'}
      description={kind === 'friend' ? '输入好友的 Phantom ID 或昵称' : '和好友创建一个新的交流空间'}
      onClose={onClose}
    >
      <form className="modal-form" onSubmit={handleSubmit}>
        <div className="segmented modal-segmented">
          <button
            type="button"
            className={kind === 'friend' ? 'is-active' : ''}
            onClick={() => setKind('friend')}
          >
            <Icon name="user" size={17} />
            添加好友
          </button>
          <button
            type="button"
            className={kind === 'group' ? 'is-active' : ''}
            onClick={() => setKind('group')}
          >
            <Icon name="group" size={18} />
            创建群组
          </button>
        </div>

        <label className="form-field">
          <span>{kind === 'friend' ? '好友昵称' : '群组名称'}</span>
          <input
            autoFocus
            value={name}
            placeholder={kind === 'friend' ? '例如：周予安' : '例如：产品灵感交流群'}
            onChange={(event) => setName(event.target.value)}
          />
        </label>

        <label className="form-field">
          <span>{kind === 'friend' ? '备注 / 职业' : '群组简介'}</span>
          <input
            value={detail}
            placeholder={kind === 'friend' ? '例如：产品经理' : '一句话介绍这个群组'}
            onChange={(event) => setDetail(event.target.value)}
          />
        </label>

        {kind === 'friend' && (
          <div className="add-tip">
            <Icon name="shield" size={16} />
            添加后对方会收到好友申请，通过后即可开始聊天。
          </div>
        )}

        <div className="modal-actions">
          <button type="button" className="secondary-button" onClick={onClose}>取消</button>
          <button type="submit" className="primary-button" disabled={!name.trim()}>
            {kind === 'friend' ? '发送申请' : '创建群组'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
