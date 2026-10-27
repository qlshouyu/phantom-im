import { type FormEvent, useEffect, useMemo, useState } from 'react'
import { useApp } from '../app/AppProvider'
import { Avatar, EmptyState, IconButton, Modal, SearchField } from '../components/ui'
import type {
  Friend,
  FriendCandidate,
  FriendGroup,
  FriendRequest,
  Group,
  GroupMember,
  SearchResult,
  UserProfile,
} from '../domain/types'
import { Icon } from '../icons'

type ContactSection = 'friends' | 'groups' | 'requests'
type AddKind = 'friend' | 'group'
export interface ConversationTarget {
  id: string
  name: string
  kind: 'direct' | 'group'
  targetId?: string
}

interface ContactsViewProps {
  onOpenConversation?: (target: ConversationTarget) => void
}

export default function ContactsView({ onOpenConversation }: ContactsViewProps) {
  const { bff, gateway } = useApp()
  const [friends, setFriends] = useState<Friend[]>([])
  const [groups, setGroups] = useState<Group[]>([])
  const [requests, setRequests] = useState<FriendRequest[]>([])
  const [requestUnread, setRequestUnread] = useState(0)
  const [friendGroups, setFriendGroups] = useState<FriendGroup[]>([])
  const [blacklist, setBlacklist] = useState<Friend[]>([])
  const [conversationMuted, setConversationMuted] = useState<Record<string, boolean>>({})
  const [section, setSection] = useState<ContactSection>('friends')
  const [activeFriendId, setActiveFriendId] = useState('')
  const [activeGroupId, setActiveGroupId] = useState('')
  const [selectedProfile, setSelectedProfile] = useState<UserProfile | null>(null)
  const [groupDetail, setGroupDetail] = useState<Group | null>(null)
  const [groupMembers, setGroupMembers] = useState<GroupMember[]>([])
  const [commonGroups, setCommonGroups] = useState<string[]>([])
  const [query, setQuery] = useState('')
  const [searchResult, setSearchResult] = useState<SearchResult | null>(null)
  const [searching, setSearching] = useState(false)
  const [addOpen, setAddOpen] = useState(false)
  const [addKind, setAddKind] = useState<AddKind>('friend')
  const [addFriendPrefill, setAddFriendPrefill] = useState('')
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [mobileDetailOpen, setMobileDetailOpen] = useState(false)
  const [friendEditOpen, setFriendEditOpen] = useState(false)
  const [friendGroupsOpen, setFriendGroupsOpen] = useState(false)
  const [friendActionOpen, setFriendActionOpen] = useState(false)
  const [blacklistOpen, setBlacklistOpen] = useState(false)
  const [deleteFriendOpen, setDeleteFriendOpen] = useState(false)
  const [memberMenuId, setMemberMenuId] = useState('')
  const [inviteOpen, setInviteOpen] = useState(false)
  const [announcementOpen, setAnnouncementOpen] = useState(false)
  const [groupNicknameOpen, setGroupNicknameOpen] = useState(false)
  const [confirmLeaveOpen, setConfirmLeaveOpen] = useState(false)
  const [confirmDissolveOpen, setConfirmDissolveOpen] = useState(false)
  const [busy, setBusy] = useState(false)

  const activeFriend = friends.find((friend) => friend.id === activeFriendId) ?? friends[0]
  const activeGroup = groups.find((group) => group.id === activeGroupId) ?? groups[0]
  const groupView = groupDetail?.id === activeGroup?.id ? groupDetail : activeGroup
  const currentMember = groupMembers.find((member) => member.userId === gateway?.currentUserId)
  const isOwner = Boolean(groupView && groupView.ownerId === gateway?.currentUserId)
  const canManageGroup = isOwner || currentMember?.role === 'ADMIN'
  const isFriendBlocked = Boolean(activeFriend && blacklist.some((item) => item.id === activeFriend.id))

  const filteredContacts = useMemo(() => {
    const normalized = query.trim().toLowerCase()
    if (!normalized) return friends
    return friends.filter((friend) =>
      `${friend.name} ${friend.nickname} ${friend.remark ?? ''} ${friend.id}`.toLowerCase().includes(normalized))
  }, [friends, query])

  const filteredGroups = useMemo(() => {
    const normalized = query.trim().toLowerCase()
    if (!normalized) return groups
    return groups.filter((group) => `${group.name} ${group.id}`.toLowerCase().includes(normalized))
  }, [groups, query])

  const groupedContacts = useMemo(() => {
    return filteredContacts.reduce<Record<string, Friend[]>>((result, friend) => {
      const initial = /^[A-Za-z]/.test(friend.name) ? friend.name[0].toUpperCase() : '#'
      result[initial] = [...(result[initial] ?? []), friend]
      return result
    }, {})
  }, [filteredContacts])

  useEffect(() => {
    if (!gateway) {
      setLoading(false)
      setError('SDK 尚未连接')
      return
    }
    void reloadAll()
    const offs = [
      gateway.on('relation', () => { void reloadAll() }),
      gateway.on('group', () => { void reloadAll() }),
      gateway.on('profile', (event: any) => {
        const userId = String(event?.user_id ?? event?.userId ?? '')
        if (userId === activeFriend?.id) void loadFriendProfile(userId)
        void reloadAll()
      }),
    ]
    return () => offs.forEach((off) => off())
  }, [gateway])

  useEffect(() => {
    if (!notice) return
    const timer = window.setTimeout(() => setNotice(''), 2600)
    return () => window.clearTimeout(timer)
  }, [notice])

  useEffect(() => {
    if (!query.trim() || !gateway || query.trim().length < 2) {
      setSearchResult(null)
      return
    }
    const timer = window.setTimeout(() => {
      setSearching(true)
      void gateway.search(query.trim(), 20)
        .then(setSearchResult)
        .catch(() => setSearchResult(null))
        .finally(() => setSearching(false))
    }, 300)
    return () => window.clearTimeout(timer)
  }, [gateway, query])

  useEffect(() => {
    if (!gateway || !activeFriend || section !== 'friends') return
    void loadFriendProfile(activeFriend.id)
    void loadCommonGroups(activeFriend.id)
  }, [activeFriend?.id, gateway, section])

  useEffect(() => {
    if (!gateway || !activeGroup || section !== 'groups') return
    setGroupDetail(null)
    setGroupMembers([])
    void Promise.all([
      gateway.getGroup(activeGroup.id),
      gateway.listGroupMembers(activeGroup.id),
    ]).then(([detail, members]) => {
      setGroupDetail(detail)
      setGroupMembers(members)
    }).catch((reason) => setNotice(reasonMessage(reason, '群组详情加载失败')))
  }, [activeGroup?.id, gateway, section])

  async function reloadAll() {
    if (!gateway) return
    try {
      const [nextFriends, nextGroups, requestPage, nextFriendGroups, nextBlacklist, conversations] =
        await Promise.all([
          gateway.listFriends(),
          gateway.listGroups(),
          gateway.listFriendRequests(true),
          gateway.listFriendGroups(),
          gateway.listBlacklist(),
          gateway.listConversations(),
        ])
      setFriends(nextFriends)
      setGroups(nextGroups)
      setRequests(requestPage.items)
      setRequestUnread(requestPage.unreadCount)
      setFriendGroups(nextFriendGroups)
      setBlacklist(nextBlacklist)
      setConversationMuted(Object.fromEntries(conversations.map((item) => [item.id, item.muted])))
      setActiveFriendId((current) => current || nextFriends[0]?.id || '')
      setActiveGroupId((current) => current || nextGroups[0]?.id || '')
      setError('')
    } catch (reason) {
      setError(reasonMessage(reason, '通讯录加载失败'))
    } finally {
      setLoading(false)
    }
  }

  async function loadFriendProfile(userId: string) {
    if (!gateway) return
    try {
      setSelectedProfile(await gateway.getUserProfile(userId))
    } catch {
      setSelectedProfile(null)
    }
  }

  async function loadCommonGroups(friendId: string) {
    if (!gateway) return
    const matches: string[] = []
    for (const group of groups) {
      try {
        const members = await gateway.listGroupMembers(group.id)
        if (members.some((member) => member.userId === friendId)) matches.push(group.name)
      } catch {
        // 单个群详情不可读时不影响好友资料展示。
      }
    }
    setCommonGroups(matches)
  }

  function switchSection(next: ContactSection) {
    setSection(next)
    setQuery('')
    setSearchResult(null)
    setMobileDetailOpen(false)
  }

  function openAdd(kind: AddKind, prefill = '') {
    setAddKind(kind)
    setAddFriendPrefill(prefill)
    setAddOpen(true)
  }

  async function createFriend(userId: string, verificationMessage: string) {
    if (!gateway) return
    setBusy(true)
    try {
      await gateway.createFriendRequest(userId.trim(), verificationMessage.trim())
      setAddOpen(false)
      setNotice('好友申请已发送')
      await reloadAll()
    } catch (reason) {
      throw new Error(reasonMessage(reason, '好友申请发送失败'))
    } finally {
      setBusy(false)
    }
  }

  async function resolveFriend(identifier: string): Promise<FriendCandidate> {
    const account = await bff.lookupAccount(identifier)
    let profile: UserProfile | null = null
    if (gateway) {
      try {
        profile = await gateway.getUserProfile(account.phantomUserId)
      } catch {
        // 资料读取失败时仍允许使用账号昵称进入确认步骤。
      }
    }
    const profileName = profile?.name.trim()
    return {
      phantomUserId: account.phantomUserId,
      loginName: account.loginName,
      nickname: profileName && profileName !== '未设置昵称'
        ? profileName
        : account.nickname || account.loginName,
      avatarUrl: profile?.avatarUrl,
    }
  }

  async function createGroup(name: string, memberIds: string[]) {
    if (!gateway) return
    setBusy(true)
    try {
      const group = await gateway.createGroup(name.trim(), memberIds)
      setAddOpen(false)
      setSection('groups')
      setActiveGroupId(group.id)
      setMobileDetailOpen(true)
      setNotice('群组已创建')
      await reloadAll()
    } catch (reason) {
      throw new Error(reasonMessage(reason, '群组创建失败'))
    } finally {
      setBusy(false)
    }
  }

  async function handleRequest(request: FriendRequest, action: 'ACCEPT' | 'REJECT' | 'IGNORE') {
    if (!gateway) return
    try {
      await gateway.handleFriendRequest(request.id, action)
      setNotice(action === 'ACCEPT' ? '已接受好友申请' : action === 'REJECT' ? '已拒绝好友申请' : '已忽略好友申请')
      await reloadAll()
    } catch (reason) {
      setNotice(reasonMessage(reason, '好友申请处理失败'))
    }
  }

  async function updateFriend(patch: { remark?: string | null; pinned?: boolean; groupIds?: string[] }) {
    if (!gateway || !activeFriend) return
    try {
      await gateway.updateFriend(activeFriend.id, patch)
      setFriendEditOpen(false)
      setNotice('好友资料已更新')
      await reloadAll()
    } catch (reason) {
      setNotice(reasonMessage(reason, '好友资料更新失败'))
    }
  }

  async function deleteFriend() {
    if (!gateway || !activeFriend) return
    try {
      await gateway.deleteFriend(activeFriend.id)
      setDeleteFriendOpen(false)
      setFriendActionOpen(false)
      setActiveFriendId('')
      setNotice('好友已删除')
      await reloadAll()
    } catch (reason) {
      setNotice(reasonMessage(reason, '删除好友失败'))
    }
  }

  async function toggleBlock() {
    if (!gateway || !activeFriend) return
    try {
      if (isFriendBlocked) await gateway.unblockUser(activeFriend.id)
      else await gateway.blockUser(activeFriend.id)
      setFriendActionOpen(false)
      setNotice(isFriendBlocked ? '已解除拉黑' : '已加入黑名单')
      await reloadAll()
    } catch (reason) {
      setNotice(reasonMessage(reason, '黑名单操作失败'))
    }
  }

  async function toggleGroupMute() {
    if (!gateway || !groupView) return
    const next = !conversationMuted[groupView.convId]
    setConversationMuted((current) => ({ ...current, [groupView.convId]: next }))
    try {
      await gateway.updateConversation(groupView.convId, { muted: next })
      setNotice(next ? '已开启群消息免打扰' : '已关闭群消息免打扰')
    } catch (reason) {
      setConversationMuted((current) => ({ ...current, [groupView.convId]: !next }))
      setNotice(reasonMessage(reason, '群提醒设置失败'))
    }
  }

  async function updateAnnouncement(content: string) {
    if (!gateway || !groupView) return
    try {
      const next = await gateway.updateAnnouncement(groupView.id, content)
      setGroupDetail((current) => current ? { ...current, announcement: next } : current)
      setAnnouncementOpen(false)
      setNotice('群公告已更新')
    } catch (reason) {
      throw new Error(reasonMessage(reason, '群公告更新失败'))
    }
  }

  async function updateGroupNickname(nickname: string) {
    if (!gateway || !groupView) return
    try {
      await gateway.updateGroupNickname(groupView.id, gateway.currentUserId, nickname)
      setGroupNicknameOpen(false)
      setNotice('群昵称已更新')
      setGroupMembers(await gateway.listGroupMembers(groupView.id))
    } catch (reason) {
      throw new Error(reasonMessage(reason, '群昵称更新失败'))
    }
  }

  async function inviteMembers(userIds: string[]) {
    if (!gateway || !groupView) return
    try {
      await gateway.inviteGroupMembers(groupView.id, userIds)
      setInviteOpen(false)
      setNotice('成员邀请已发送')
      setGroupMembers(await gateway.listGroupMembers(groupView.id))
    } catch (reason) {
      throw new Error(reasonMessage(reason, '邀请成员失败'))
    }
  }

  async function memberAction(member: GroupMember, action: 'mute' | 'unmute' | 'remove' | 'admin' | 'member' | 'transfer') {
    if (!gateway || !groupView) return
    setMemberMenuId('')
    try {
      if (action === 'mute') await gateway.muteGroupMember(groupView.id, member.userId, 600)
      if (action === 'unmute') await gateway.unmuteGroupMember(groupView.id, member.userId)
      if (action === 'remove') await gateway.removeGroupMember(groupView.id, member.userId)
      if (action === 'admin') await gateway.updateGroupRole(groupView.id, member.userId, 'ADMIN')
      if (action === 'member') await gateway.updateGroupRole(groupView.id, member.userId, 'MEMBER')
      if (action === 'transfer') await gateway.transferGroupOwner(groupView.id, member.userId)
      setNotice(memberActionLabel(action))
      setGroupMembers(await gateway.listGroupMembers(groupView.id))
      await reloadAll()
    } catch (reason) {
      setNotice(reasonMessage(reason, '群成员操作失败'))
    }
  }

  async function leaveOrDissolve() {
    if (!gateway || !groupView) return
    try {
      if (isOwner) await gateway.dissolveGroup(groupView.id)
      else await gateway.leaveGroup(groupView.id)
      setConfirmLeaveOpen(false)
      setConfirmDissolveOpen(false)
      setActiveGroupId('')
      setNotice(isOwner ? '群组已解散' : '已退出群组')
      await reloadAll()
    } catch (reason) {
      setNotice(reasonMessage(reason, isOwner ? '解散群组失败' : '退出群组失败'))
    }
  }

  if (loading) return <ContactsState title="正在加载通讯录" description="正在读取好友、申请与群组…" />
  if (error) {
    return (
      <ContactsState
        title="通讯录加载失败"
        description={error}
        action={<button type="button" className="primary-button" onClick={() => void reloadAll()}>重试</button>}
      />
    )
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
            onClick={() => openAdd(section === 'groups' ? 'group' : 'friend')}
          />
        </header>

        <div className="segmented contact-tabs">
          <button type="button" className={section === 'friends' ? 'is-active' : ''} onClick={() => switchSection('friends')}>
            <Icon name="user" size={17} />好友<span>{friends.length}</span>
          </button>
          <button type="button" className={section === 'groups' ? 'is-active' : ''} onClick={() => switchSection('groups')}>
            <Icon name="group" size={18} />群组<span>{groups.length}</span>
          </button>
          <button type="button" className={section === 'requests' ? 'is-active' : ''} onClick={() => switchSection('requests')}>
            <Icon name="message" size={17} />申请
            {requestUnread > 0 && <span className="unread-badge">{requestUnread}</span>}
          </button>
        </div>

        <SearchField
          placeholder={section === 'groups' ? '搜索群组' : '搜索联系人或 Phantom ID'}
          aria-label="搜索通讯录"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onClear={() => setQuery('')}
        />

        {query.trim() && (
          <ContactSearchPanel
            result={searchResult}
            searching={searching}
            onFriend={(userId, nickname) => openAdd('friend', userId || nickname)}
            onGroup={(groupId) => {
              const found = groups.find((group) => group.id === groupId)
              if (found) {
                setSection('groups')
                setActiveGroupId(found.id)
                setMobileDetailOpen(true)
              } else {
                setNotice('该群组暂不在当前账号的群列表中')
              }
            }}
          />
        )}

        <div className="contact-list-scroll">
          {section === 'friends' && (
            filteredContacts.length === 0 ? (
              <EmptyState title="没有找到好友" description="通过登录名或 Phantom ID 添加新的朋友" />
            ) : Object.entries(groupedContacts)
              .sort(([left], [right]) => left.localeCompare(right))
              .map(([initial, entries]) => (
                <div className="contact-letter-group" key={initial}>
                  <span className="contact-letter">{initial}</span>
                  {entries.map((friend) => (
                    <button
                      type="button"
                      key={friend.id}
                      className={`contact-list-row ${friend.id === activeFriend?.id ? 'is-active' : ''}`}
                      onClick={() => {
                        setActiveFriendId(friend.id)
                        setMobileDetailOpen(true)
                      }}
                    >
                      <Avatar chars={[friend.name.slice(-1)]} tone="mint" size={45} />
                      <span className="contact-row-copy"><strong>{friend.name}</strong><small>@{friend.id}</small></span>
                      {friend.pinned && <Icon name="star" size={14} />}
                      <span className="contact-row-chevron"><Icon name="chevronRight" size={16} /></span>
                    </button>
                  ))}
                </div>
              ))
          )}

          {section === 'groups' && (
            filteredGroups.length === 0 ? (
              <EmptyState title="没有找到群组" description="创建一个群组邀请好友加入" />
            ) : (
              <div className="group-contact-list">
                {filteredGroups.map((group) => (
                  <button
                    type="button"
                    key={group.id}
                    className={`group-list-row ${group.id === activeGroup?.id ? 'is-active' : ''}`}
                    onClick={() => {
                      setActiveGroupId(group.id)
                      setMobileDetailOpen(true)
                    }}
                  >
                    <Avatar chars={group.avatarChars} tone={group.tone} size={48} group />
                    <span className="contact-row-copy"><strong>{group.name}</strong><small>{group.memberCount} 位成员</small></span>
                    <span className="contact-row-chevron"><Icon name="chevronRight" size={16} /></span>
                  </button>
                ))}
              </div>
            )
          )}

          {section === 'requests' && (
            requests.length === 0 ? (
              <EmptyState title="暂无好友申请" description="新的好友申请会出现在这里" />
            ) : (
              <div className="friend-request-list">
                {requests.map((request) => (
                  <div className="friend-request-card" key={request.id}>
                    <Avatar chars={[request.peerName.slice(-1)]} tone="sky" size={42} />
                    <div>
                      <strong>{request.peerName}</strong>
                      <small>{request.direction === 'INCOMING' ? '请求添加你为好友' : '等待对方处理'}</small>
                      {request.verificationMessage && <p>{request.verificationMessage}</p>}
                    </div>
                    {request.status === 'PENDING' && request.direction === 'INCOMING' ? (
                      <div className="request-actions">
                        <button type="button" onClick={() => void handleRequest(request, 'ACCEPT')}>接受</button>
                        <button type="button" onClick={() => void handleRequest(request, 'REJECT')}>拒绝</button>
                        <button type="button" onClick={() => void handleRequest(request, 'IGNORE')}>忽略</button>
                      </div>
                    ) : (
                      <span className="request-status">{request.status}</span>
                    )}
                  </div>
                ))}
              </div>
            )
          )}
        </div>

        {section === 'friends' && blacklist.length > 0 && (
          <button type="button" className="blacklist-entry" onClick={() => setBlacklistOpen(true)}>
            <Icon name="shield" size={17} />
            <span>黑名单</span>
            <strong>{blacklist.length}</strong>
            <Icon name="chevronRight" size={16} />
          </button>
        )}

        <button
          type="button"
          className="list-create-card"
          onClick={() => openAdd(section === 'groups' ? 'group' : 'friend')}
        >
          <span><Icon name={section === 'groups' ? 'group' : 'plus'} size={19} /></span>
          <div>
            <strong>{section === 'groups' ? '创建一个群组' : '添加新的朋友'}</strong>
            <small>{section === 'groups' ? '邀请好友一起开始交流' : '通过登录名或 Phantom ID 查找并添加'}</small>
          </div>
          <Icon name="chevronRight" size={17} />
        </button>
      </section>

      <section className="detail-pane contact-detail-pane" aria-label="联系人详情">
        <header className="detail-topbar">
          <IconButton icon="arrowLeft" label="返回通讯录列表" className="mobile-back-button" onClick={() => setMobileDetailOpen(false)} />
          <div className="breadcrumb"><span>通讯录</span><Icon name="chevronRight" size={13} /><strong>{section === 'groups' ? '群组资料' : section === 'requests' ? '好友申请' : '好友资料'}</strong></div>
          <div className="detail-topbar-actions">
            {section === 'friends' && activeFriend && (
              <button type="button" className="primary-button" onClick={() => onOpenConversation?.({
                id: directConversationId(gateway?.currentUserId ?? '', activeFriend.id),
                name: activeFriend.name,
                kind: 'direct',
                targetId: activeFriend.id,
              })}>
                <Icon name="message" size={17} />发消息
              </button>
            )}
            {section === 'groups' && groupView && (
              <button type="button" className="primary-button" onClick={() => onOpenConversation?.({
                id: groupView.convId,
                name: groupView.name,
                kind: 'group',
              })}>
                <Icon name="message" size={17} />进入群聊
              </button>
            )}
            {section === 'friends' && activeFriend && (
              <div className="relative-action">
                <IconButton icon="more" label="更多操作" onClick={() => setFriendActionOpen((value) => !value)} />
                {friendActionOpen && (
                  <div className="action-menu action-menu--right">
                    <button type="button" onClick={() => { setFriendEditOpen(true); setFriendActionOpen(false) }}>
                      <Icon name="edit" size={15} />编辑好友资料
                    </button>
                    <button type="button" onClick={() => { setFriendGroupsOpen(true); setFriendActionOpen(false) }}>
                      <Icon name="grid" size={15} />管理好友分组
                    </button>
                    <button type="button" onClick={() => void toggleBlock()}>
                      <Icon name="shield" size={15} />{isFriendBlocked ? '解除拉黑' : '加入黑名单'}
                    </button>
                    <button type="button" className="is-danger" onClick={() => { setDeleteFriendOpen(true); setFriendActionOpen(false) }}>
                      <Icon name="trash" size={15} />删除好友
                    </button>
                  </div>
                )}
              </div>
            )}
          </div>
        </header>

        <div className="contact-detail-scroll">
          {section === 'friends' && activeFriend && (
            <FriendDetail
              friend={activeFriend}
              profile={selectedProfile}
              commonGroups={commonGroups}
              friendGroups={friendGroups.filter((group) => activeFriend.groupIds.includes(group.id))}
              blocked={isFriendBlocked}
              onTogglePin={() => void updateFriend({ pinned: !activeFriend.pinned })}
              onEdit={() => setFriendEditOpen(true)}
            />
          )}
          {section === 'groups' && groupView && (
            <GroupDetail
              group={groupView}
              members={groupMembers}
              currentUserId={gateway?.currentUserId ?? ''}
              muted={Boolean(conversationMuted[groupView.convId])}
              canManage={canManageGroup}
              isOwner={isOwner}
              memberMenuId={memberMenuId}
              onMemberMenu={setMemberMenuId}
              onMemberAction={(member, action) => void memberAction(member, action)}
              onToggleMute={() => void toggleGroupMute()}
              onEditAnnouncement={() => setAnnouncementOpen(true)}
              onInvite={() => setInviteOpen(true)}
              onEditNickname={() => setGroupNicknameOpen(true)}
              onUnavailable={(label) => setNotice(`${label}暂未开放`)}
              onLeave={() => setConfirmLeaveOpen(true)}
              onDissolve={() => setConfirmDissolveOpen(true)}
            />
          )}
          {section === 'requests' && (
            <div className="view-state-card">
              <span className="view-state-icon"><Icon name="message" size={25} /></span>
              <h2>好友申请</h2>
              <p>在左侧查看并处理真实好友申请。接受申请后，双方好友列表会通过关系同步事件更新。</p>
            </div>
          )}
        </div>
      </section>

      <AddContactModal
        open={addOpen}
        kind={addKind}
        prefill={addFriendPrefill}
        friends={friends}
        busy={busy}
        onKindChange={setAddKind}
        onClose={() => setAddOpen(false)}
        onResolveFriend={resolveFriend}
        onCreateFriend={createFriend}
        onCreateGroup={createGroup}
      />

      <FriendEditModal
        open={friendEditOpen}
        friend={activeFriend}
        groups={friendGroups}
        onClose={() => setFriendEditOpen(false)}
        onSave={(remark, groupIds) => void updateFriend({ remark: remark || null, groupIds })}
      />

      <BlacklistModal
        open={blacklistOpen}
        entries={blacklist}
        onClose={() => setBlacklistOpen(false)}
        onUnblock={async (userId) => {
          await gateway?.unblockUser(userId)
          setBlacklistOpen(false)
          setNotice('已解除拉黑')
          await reloadAll()
        }}
      />

      <FriendGroupsModal
        open={friendGroupsOpen}
        groups={friendGroups}
        onClose={() => setFriendGroupsOpen(false)}
        onCreate={async (name) => {
          await gateway?.createFriendGroup(name)
          await reloadAll()
        }}
        onRename={async (groupId, name) => {
          await gateway?.renameFriendGroup(groupId, name)
          await reloadAll()
        }}
        onDelete={async (groupId) => {
          await gateway?.deleteFriendGroup(groupId)
          await reloadAll()
        }}
      />

      <Modal open={deleteFriendOpen} title="删除好友" description={`确认删除「${activeFriend?.name ?? ''}」？`} onClose={() => setDeleteFriendOpen(false)} width={420}>
        <div className="modal-actions">
          <button type="button" className="secondary-button" onClick={() => setDeleteFriendOpen(false)}>取消</button>
          <button type="button" className="primary-button" onClick={() => void deleteFriend()}>确认删除</button>
        </div>
      </Modal>

      <InviteMembersModal
        open={inviteOpen}
        friends={friends.filter((friend) => !groupMembers.some((member) => member.userId === friend.id))}
        onClose={() => setInviteOpen(false)}
        onInvite={inviteMembers}
      />

      <TextEditModal
        open={announcementOpen}
        title="编辑群公告"
        label="群公告"
        initialValue={groupView?.announcement ?? ''}
        multiline
        onClose={() => setAnnouncementOpen(false)}
        onSave={updateAnnouncement}
      />

      <TextEditModal
        open={groupNicknameOpen}
        title="我的群昵称"
        label="群昵称"
        initialValue={currentMember?.groupNickname ?? ''}
        onClose={() => setGroupNicknameOpen(false)}
        onSave={updateGroupNickname}
      />

      <Modal open={confirmLeaveOpen} title="退出群组" description={`确认退出「${groupView?.name ?? ''}」？`} onClose={() => setConfirmLeaveOpen(false)} width={420}>
        <div className="modal-actions">
          <button type="button" className="secondary-button" onClick={() => setConfirmLeaveOpen(false)}>取消</button>
          <button type="button" className="primary-button" onClick={() => void leaveOrDissolve()}>确认退出</button>
        </div>
      </Modal>

      <Modal open={confirmDissolveOpen} title="解散群组" description={`群主解散「${groupView?.name ?? ''}」后不可恢复。`} onClose={() => setConfirmDissolveOpen(false)} width={420}>
        <div className="modal-actions">
          <button type="button" className="secondary-button" onClick={() => setConfirmDissolveOpen(false)}>取消</button>
          <button type="button" className="primary-button" onClick={() => void leaveOrDissolve()}>确认解散</button>
        </div>
      </Modal>

      {notice && <div className="toast toast--neutral" role="status"><Icon name="shield" size={16} />{notice}</div>}
    </div>
  )
}

function FriendDetail({
  friend,
  profile,
  commonGroups,
  friendGroups,
  blocked,
  onTogglePin,
  onEdit,
}: {
  friend: Friend
  profile: UserProfile | null
  commonGroups: string[]
  friendGroups: FriendGroup[]
  blocked: boolean
  onTogglePin: () => void
  onEdit: () => void
}) {
  return (
    <article className="contact-profile">
      <div className="profile-cover profile-cover--friend"><span className="cover-orb cover-orb--one" /><span className="cover-orb cover-orb--two" /><span className="cover-line cover-line--one" /><span className="cover-line cover-line--two" /></div>
      <div className="contact-profile-head">
        <Avatar chars={[friend.name.slice(-1)]} tone="mint" size={104} online={false} />
        <div className="contact-profile-title">
          <div className="name-status-line"><h2>{friend.name}</h2>{blocked && <span className="status-pill">已拉黑</span>}</div>
          <p>{friend.remark ? `备注：${friend.remark}` : friend.nickname || 'Phantom 好友'}</p>
          <div className="tag-row">{friendGroups.map((group) => <span className="tag" key={group.id}>{group.name}</span>)}</div>
        </div>
        <div className="profile-head-actions">
          <IconButton icon="star" label="特别关注" variant="soft" onClick={onTogglePin} />
          <IconButton icon="edit" label="编辑备注" variant="soft" onClick={onEdit} />
        </div>
      </div>
      <blockquote className="signature-card"><span>“</span><p>{profile?.signature || '还没有设置个性签名。'}</p></blockquote>
      <div className="profile-content-grid">
        <section className="content-card">
          <div className="card-heading"><div><span className="eyebrow">ABOUT</span><h3>基本信息</h3></div></div>
          <div className="info-list">
            <InfoRow icon="message" label="Phantom ID" value={`@${friend.id}`} />
            <InfoRow icon="tag" label="好友备注" value={friend.remark || '未设置'} />
            <InfoRow icon="mapPin" label="所在地区" value={profile?.location || '未设置'} />
            <InfoRow icon="mail" label="邮箱" value={profile?.email || '未设置'} />
            <InfoRow icon="smartphone" label="手机号" value={profile?.phone || '未设置'} />
          </div>
        </section>
        <section className="content-card">
          <div className="card-heading"><div><span className="eyebrow">CONNECTIONS</span><h3>共同群组</h3></div><span className="heading-count">{commonGroups.length}</span></div>
          {commonGroups.length > 0 ? (
            <div className="mutual-group-list">{commonGroups.map((group) => <div className="mutual-group-row" key={group}><Avatar chars={[group[0], '群', '聊', '组']} tone="violet" size={36} group /><span><strong>{group}</strong><small>共同群组</small></span></div>)}</div>
          ) : <p className="card-empty-copy">暂时没有共同群组</p>}
        </section>
      </div>
    </article>
  )
}

function GroupDetail({
  group,
  members,
  currentUserId,
  muted,
  canManage,
  isOwner,
  memberMenuId,
  onMemberMenu,
  onMemberAction,
  onToggleMute,
  onEditAnnouncement,
  onInvite,
  onEditNickname,
  onUnavailable,
  onLeave,
  onDissolve,
}: {
  group: Group
  members: GroupMember[]
  currentUserId: string
  muted: boolean
  canManage: boolean
  isOwner: boolean
  memberMenuId: string
  onMemberMenu: (id: string) => void
  onMemberAction: (member: GroupMember, action: 'mute' | 'unmute' | 'remove' | 'admin' | 'member' | 'transfer') => void
  onToggleMute: () => void
  onEditAnnouncement: () => void
  onInvite: () => void
  onEditNickname: () => void
  onUnavailable: (label: string) => void
  onLeave: () => void
  onDissolve: () => void
}) {
  return (
    <article className="contact-profile group-profile">
      <div className="profile-cover profile-cover--group"><span className="cover-orb cover-orb--one" /><span className="cover-orb cover-orb--two" /><span className="cover-dots" /></div>
      <div className="contact-profile-head">
        <Avatar chars={group.avatarChars} tone={group.tone} size={104} group />
        <div className="contact-profile-title"><div className="name-status-line"><h2>{group.name}</h2><span className="soft-badge">群组</span></div><p>{group.memberCount} 位成员</p><div className="tag-row"><span className="tag">{group.memberInviteEnabled ? '允许邀请成员' : '仅管理员邀请'}</span></div></div>
        <div className="profile-head-actions">
          <IconButton icon="bellOff" label={muted ? '关闭群消息免打扰' : '开启群消息免打扰'} variant="soft" onClick={onToggleMute} />
          <IconButton icon="edit" label="修改群昵称" variant="soft" onClick={onEditNickname} />
        </div>
      </div>
      <section className="announcement-card">
        <span className="announcement-icon"><Icon name="pin" size={18} /></span>
        <div><span className="eyebrow">GROUP NOTICE</span><h3>群公告</h3><p>{group.announcement || '群主暂未发布公告。'}</p></div>
        {canManage && <button type="button" className="secondary-button secondary-button--small" onClick={onEditAnnouncement}>编辑</button>}
      </section>
      <div className="profile-content-grid group-content-grid">
        <section className="content-card">
          <div className="card-heading"><div><span className="eyebrow">MEMBERS</span><h3>群成员</h3></div><span className="heading-count">{members.length}</span></div>
          <div className="group-member-list">
            {members.map((member) => (
              <div className="group-member-row" key={member.userId}>
                <Avatar chars={[member.name.slice(-1)]} tone="sky" size={36} />
                <div><strong>{member.name}{member.userId === currentUserId ? '（我）' : ''}</strong><small>{roleLabel(member.role)}{member.mutedUntil && member.mutedUntil > Date.now() ? ' · 已禁言' : ''}</small></div>
                {canManage && member.userId !== currentUserId && member.role !== 'OWNER' && (
                  <div className="relative-action">
                    <IconButton icon="more" label="成员操作" onClick={() => onMemberMenu(member.userId)} />
                    {memberMenuId === member.userId && (
                      <div className="action-menu action-menu--right">
                        <button type="button" onClick={() => onMemberAction(member, member.mutedUntil ? 'unmute' : 'mute')}>{member.mutedUntil ? '解除禁言' : '禁言 10 分钟'}</button>
                        <button type="button" onClick={() => onMemberAction(member, member.role === 'ADMIN' ? 'member' : 'admin')}>{member.role === 'ADMIN' ? '取消管理员' : '设为管理员'}</button>
                        {isOwner && <button type="button" onClick={() => onMemberAction(member, 'transfer')}>转让群主</button>}
                        <button type="button" className="is-danger" onClick={() => onMemberAction(member, 'remove')}>移出群组</button>
                      </div>
                    )}
                  </div>
                )}
              </div>
            ))}
          </div>
          {canManage && <button type="button" className="inline-add-member" onClick={onInvite}><Icon name="plus" size={16} />邀请成员</button>}
        </section>
        <section className="content-card">
          <div className="card-heading"><div><span className="eyebrow">DETAILS</span><h3>群组信息</h3></div></div>
          <div className="info-list"><InfoRow icon="message" label="群组 ID" value={`@${group.id}`} /><InfoRow icon="users" label="成员数量" value={`${group.memberCount} 人`} /><InfoRow icon="sparkles" label="邀请设置" value={group.memberInviteEnabled ? '成员可邀请' : '仅管理员可邀请'} /></div>
        </section>
      </div>
      <div className="group-footer-actions">
        <button type="button" onClick={() => onUnavailable('群文件')}><Icon name="folder" size={18} />群文件</button>
        <button type="button" onClick={() => onUnavailable('群相册')}><Icon name="image" size={18} />群相册</button>
        {isOwner ? (
          <button type="button" className="is-danger" onClick={onDissolve}><Icon name="trash" size={18} />解散群组</button>
        ) : (
          <button type="button" className="is-danger" onClick={onLeave}><Icon name="logout" size={18} />退出群组</button>
        )}
      </div>
    </article>
  )
}

function InfoRow({ icon, label, value }: { icon: 'message' | 'tag' | 'mapPin' | 'mail' | 'smartphone' | 'users' | 'sparkles'; label: string; value: string }) {
  return <div className="info-row"><span className="info-icon"><Icon name={icon} size={17} /></span><span className="info-label">{label}</span><span className="info-value">{value}</span></div>
}

function ContactSearchPanel({ result, searching, onFriend, onGroup }: { result: SearchResult | null; searching: boolean; onFriend: (userId: string, nickname: string) => void; onGroup: (groupId: string) => void }) {
  if (searching) return <div className="chat-search-panel"><span>正在搜索…</span></div>
  if (!result) return null
  return (
    <div className="chat-search-panel">
      <div className="chat-search-heading"><span>Phantom 搜索</span><small>{result.contactTotal + result.groupTotal} 个结果</small></div>
      {result.contacts.length > 0 && <div className="chat-search-section"><small>联系人</small>{result.contacts.map((contact) => <button type="button" key={contact.userId} onClick={() => onFriend(contact.userId, contact.nickname)}><strong>{contact.remark || contact.nickname}</strong><span>@{contact.userId}</span></button>)}</div>}
      {result.groups.length > 0 && <div className="chat-search-section"><small>群组</small>{result.groups.map((group) => <button type="button" key={group.groupId} onClick={() => onGroup(group.groupId)}><strong>{group.name}</strong><span>群组</span></button>)}</div>}
    </div>
  )
}

export function AddContactModal({
  open,
  kind,
  prefill,
  friends,
  busy,
  onKindChange,
  onClose,
  onResolveFriend,
  onCreateFriend,
  onCreateGroup,
}: {
  open: boolean
  kind: AddKind
  prefill: string
  friends: Friend[]
  busy: boolean
  onKindChange: (kind: AddKind) => void
  onClose: () => void
  onResolveFriend: (identifier: string) => Promise<FriendCandidate>
  onCreateFriend: (userId: string, verificationMessage: string) => Promise<void>
  onCreateGroup: (name: string, memberIds: string[]) => Promise<void>
}) {
  const [identifier, setIdentifier] = useState(prefill)
  const [candidate, setCandidate] = useState<FriendCandidate | null>(null)
  const [step, setStep] = useState<'identify' | 'confirm'>('identify')
  const [verification, setVerification] = useState('')
  const [groupName, setGroupName] = useState('')
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [error, setError] = useState('')
  const [resolving, setResolving] = useState(false)

  useEffect(() => {
    if (!open) return
    setIdentifier(prefill)
    setCandidate(null)
    setStep('identify')
    setVerification('')
    setGroupName('')
    setSelectedIds([])
    setError('')
  }, [open, prefill])

  function changeKind(next: AddKind) {
    onKindChange(next)
    setStep('identify')
    setCandidate(null)
    setError('')
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError('')
    try {
      if (kind === 'friend') {
        if (step === 'identify') {
          if (!identifier.trim()) throw new Error('请输入登录名或 Phantom ID')
          setResolving(true)
          try {
            setCandidate(await onResolveFriend(identifier.trim()))
            setStep('confirm')
          } finally {
            setResolving(false)
          }
          return
        }
        if (!candidate) throw new Error('请先确认用户信息')
        await onCreateFriend(candidate.phantomUserId, verification)
      } else {
        if (!groupName.trim()) throw new Error('请输入群组名称')
        if (selectedIds.length === 0) throw new Error('请至少选择一位好友')
        await onCreateGroup(groupName, selectedIds)
      }
    } catch (reason) {
      const fallback = kind === 'group'
        ? '群组创建失败'
        : step === 'identify' ? '用户查询失败' : '好友申请发送失败'
      setError(reasonMessage(reason, fallback))
    }
  }

  return (
    <Modal
      open={open}
      title={kind === 'friend' ? (step === 'identify' ? '添加新的朋友' : '确认好友信息') : '创建一个群组'}
      description={kind === 'friend'
        ? (step === 'identify' ? '输入对方的登录名或 Phantom ID，下一步确认资料' : '请确认对方信息无误后发送好友申请')
        : '选择好友并创建真实群聊'}
      onClose={onClose}
    >
      <form className="modal-form" onSubmit={(event) => void submit(event)}>
        <div className="segmented modal-segmented">
          <button type="button" className={kind === 'friend' ? 'is-active' : ''} onClick={() => changeKind('friend')}><Icon name="user" size={17} />添加好友</button>
          <button type="button" className={kind === 'group' ? 'is-active' : ''} onClick={() => changeKind('group')}><Icon name="group" size={18} />创建群组</button>
        </div>
        {kind === 'friend' ? (
          step === 'identify' ? (
            <>
              <label className="form-field">
                <span>登录名或 Phantom ID</span>
                <input
                  autoFocus
                  value={identifier}
                  placeholder="例如：alice_1 或 u_123456"
                  onChange={(event) => setIdentifier(event.target.value)}
                />
              </label>
              <div className="add-tip"><Icon name="shield" size={15} /><span>先查询并确认头像、昵称、登录名和 Phantom ID，再发送好友申请。</span></div>
            </>
          ) : candidate ? (
            <>
              <div className="friend-confirm-card">
                <Avatar avatarUrl={candidate.avatarUrl} chars={[candidate.nickname.slice(-1)]} tone="mint" size={88} />
                <strong>{candidate.nickname}</strong>
                <dl>
                  <div><dt>登录名</dt><dd>{candidate.loginName}</dd></div>
                  <div><dt>Phantom ID</dt><dd>{candidate.phantomUserId}</dd></div>
                </dl>
              </div>
              <label className="form-field"><span>验证消息</span><input autoFocus value={verification} placeholder="介绍一下自己（可选）" onChange={(event) => setVerification(event.target.value)} /></label>
            </>
          ) : null
        ) : (
          <>
            <label className="form-field"><span>群组名称</span><input autoFocus value={groupName} placeholder="例如：产品灵感交流群" onChange={(event) => setGroupName(event.target.value)} /></label>
            <div className="form-field"><span>选择好友</span><div className="member-picker">{friends.map((friend) => <label key={friend.id}><input type="checkbox" checked={selectedIds.includes(friend.id)} onChange={(event) => setSelectedIds((current) => event.target.checked ? [...current, friend.id] : current.filter((id) => id !== friend.id))} /><Avatar chars={[friend.name.slice(-1)]} tone="mint" size={30} /><span>{friend.name}</span></label>)}</div></div>
          </>
        )}
        {error && <div className="auth-notice auth-notice--error" role="alert"><Icon name="shield" size={16} />{error}</div>}
        <div className="modal-actions">
          {kind === 'friend' && step === 'confirm' ? (
            <button type="button" className="secondary-button" onClick={() => { setStep('identify'); setCandidate(null); setError('') }}>上一步</button>
          ) : (
            <button type="button" className="secondary-button" onClick={onClose}>取消</button>
          )}
          <button type="submit" className="primary-button" disabled={busy || resolving}>
            {busy ? '处理中…' : resolving ? '查询中…' : kind === 'friend' && step === 'identify' ? '下一步' : kind === 'friend' ? '发送申请' : '创建群组'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

function FriendEditModal({ open, friend, groups, onClose, onSave }: { open: boolean; friend?: Friend; groups: FriendGroup[]; onClose: () => void; onSave: (remark: string, groupIds: string[]) => void }) {
  const [remark, setRemark] = useState('')
  const [groupIds, setGroupIds] = useState<string[]>([])
  useEffect(() => {
    if (!open || !friend) return
    setRemark(friend.remark ?? '')
    setGroupIds(friend.groupIds)
  }, [open, friend?.id])
  return (
    <Modal open={open} title="编辑好友资料" description={`设置「${friend?.name ?? ''}」的备注与分组`} onClose={onClose}>
      <form className="modal-form" onSubmit={(event) => { event.preventDefault(); onSave(remark.trim(), groupIds) }}>
        <label className="form-field"><span>好友备注</span><input autoFocus value={remark} placeholder="设置一个容易识别的备注" onChange={(event) => setRemark(event.target.value)} /></label>
        <div className="form-field"><span>好友分组</span><div className="group-checkboxes">{groups.map((group) => <label key={group.id}><input type="checkbox" checked={groupIds.includes(group.id)} onChange={(event) => setGroupIds((current) => event.target.checked ? [...current, group.id] : current.filter((id) => id !== group.id))} />{group.name}<small>{group.memberCount}</small></label>)}</div></div>
        <div className="modal-actions"><button type="button" className="secondary-button" onClick={onClose}>取消</button><button type="submit" className="primary-button">保存修改</button></div>
      </form>
    </Modal>
  )
}

function BlacklistModal({ open, entries, onClose, onUnblock }: { open: boolean; entries: Friend[]; onClose: () => void; onUnblock: (userId: string) => Promise<void> }) {
  const [busyId, setBusyId] = useState('')
  return (
    <Modal open={open} title="黑名单" description="解除拉黑后，好友关系仍以服务端数据为准" onClose={onClose}>
      <div className="device-list">
        {entries.length === 0 ? <p className="card-empty-copy">黑名单为空</p> : entries.map((entry) => (
          <div className="device-row" key={entry.id}>
            <Avatar chars={[entry.name.slice(-1)]} tone="coral" size={36} />
            <div><strong>{entry.name}</strong><small>@{entry.id}</small></div>
            <button type="button" disabled={busyId === entry.id} onClick={() => {
              setBusyId(entry.id)
              void onUnblock(entry.id).finally(() => setBusyId(''))
            }}>{busyId === entry.id ? '处理中…' : '解除拉黑'}</button>
          </div>
        ))}
      </div>
    </Modal>
  )
}

function FriendGroupsModal({ open, groups, onClose, onCreate, onRename, onDelete }: { open: boolean; groups: FriendGroup[]; onClose: () => void; onCreate: (name: string) => Promise<void>; onRename: (id: string, name: string) => Promise<void>; onDelete: (id: string) => Promise<void> }) {
  const [name, setName] = useState('')
  const [editingId, setEditingId] = useState('')
  const [editingName, setEditingName] = useState('')
  return (
    <Modal open={open} title="好友分组" description="创建、重命名或删除真实好友分组" onClose={onClose}>
      <div className="modal-form">
        <div className="inline-search"><input value={name} placeholder="新分组名称" onChange={(event) => setName(event.target.value)} /><button type="button" className="primary-button" disabled={!name.trim()} onClick={() => void onCreate(name.trim()).then(() => setName(''))}>创建</button></div>
        <div className="friend-group-manage-list">{groups.map((group) => <div key={group.id}>{editingId === group.id ? <input autoFocus value={editingName} onChange={(event) => setEditingName(event.target.value)} /> : <strong>{group.name}</strong>}<small>{group.memberCount} 位好友</small>{editingId === group.id ? <><button type="button" onClick={() => void onRename(group.id, editingName).then(() => setEditingId(''))}>保存</button><button type="button" onClick={() => setEditingId('')}>取消</button></> : <><button type="button" onClick={() => { setEditingId(group.id); setEditingName(group.name) }}>重命名</button><button type="button" onClick={() => void onDelete(group.id)}>删除</button></>}</div>)}</div>
      </div>
    </Modal>
  )
}

function InviteMembersModal({ open, friends, onClose, onInvite }: { open: boolean; friends: Friend[]; onClose: () => void; onInvite: (ids: string[]) => Promise<void> }) {
  const [ids, setIds] = useState<string[]>([])
  const [error, setError] = useState('')
  useEffect(() => { if (open) { setIds([]); setError('') } }, [open])
  return (
    <Modal open={open} title="邀请群成员" description="从真实好友中选择邀请对象" onClose={onClose}>
      <form className="modal-form" onSubmit={(event) => { event.preventDefault(); void onInvite(ids).catch((reason) => setError(reasonMessage(reason, '邀请失败'))) }}>
        <div className="member-picker">{friends.map((friend) => <label key={friend.id}><input type="checkbox" checked={ids.includes(friend.id)} onChange={(event) => setIds((current) => event.target.checked ? [...current, friend.id] : current.filter((id) => id !== friend.id))} /><Avatar chars={[friend.name.slice(-1)]} tone="mint" size={30} /><span>{friend.name}</span></label>)}</div>
        {error && <div className="auth-notice auth-notice--error">{error}</div>}
        <div className="modal-actions"><button type="button" className="secondary-button" onClick={onClose}>取消</button><button type="submit" className="primary-button" disabled={ids.length === 0}>发送邀请</button></div>
      </form>
    </Modal>
  )
}

function TextEditModal({ open, title, label, initialValue, multiline, onClose, onSave }: { open: boolean; title: string; label: string; initialValue: string; multiline?: boolean; onClose: () => void; onSave: (value: string) => Promise<void> }) {
  const [value, setValue] = useState(initialValue)
  const [error, setError] = useState('')
  useEffect(() => { if (open) { setValue(initialValue); setError('') } }, [initialValue, open])
  return (
    <Modal open={open} title={title} onClose={onClose}>
      <form className="modal-form" onSubmit={(event) => { event.preventDefault(); void onSave(value).catch((reason) => setError(reasonMessage(reason, '保存失败'))) }}>
        <label className="form-field"><span>{label}</span>{multiline ? <textarea rows={6} value={value} onChange={(event) => setValue(event.target.value)} /> : <input value={value} onChange={(event) => setValue(event.target.value)} />}</label>
        {error && <div className="auth-notice auth-notice--error">{error}</div>}
        <div className="modal-actions"><button type="button" className="secondary-button" onClick={onClose}>取消</button><button type="submit" className="primary-button">保存</button></div>
      </form>
    </Modal>
  )
}

function ContactsState({ title, description, action }: { title: string; description: string; action?: React.ReactNode }) {
  return <div className="workspace-view view-state"><div className="view-state-card"><span className="view-state-icon"><Icon name="contacts" size={25} /></span><h2>{title}</h2><p>{description}</p>{action}</div></div>
}

function directConversationId(left: string, right: string) {
  return `si_${[left, right].sort().join('__')}`
}

function roleLabel(role: string) {
  if (role === 'OWNER') return '群主'
  if (role === 'ADMIN') return '管理员'
  return '成员'
}

function memberActionLabel(action: 'mute' | 'unmute' | 'remove' | 'admin' | 'member' | 'transfer') {
  return { mute: '已禁言成员', unmute: '已解除禁言', remove: '已移出成员', admin: '已设为管理员', member: '已取消管理员', transfer: '群主已转让' }[action]
}

function reasonMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}
