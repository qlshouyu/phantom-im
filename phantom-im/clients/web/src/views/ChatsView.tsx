import { singleConvId } from '@phantom/sdk-web'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useApp } from '../app/AppProvider'
import { LatestRequestTracker } from '../app/LatestRequestTracker'
import { CountBadge } from '../components/CountBadge'
import { EmojiPicker } from '../components/EmojiPicker'
import { ImagePreviewModal, MessageImage, type MediaBlobLoader } from '../components/MessageImage'
import { Avatar, EmptyState, IconButton, Modal, SearchField } from '../components/ui'
import type {
  ChatMessage,
  Conversation,
  Friend,
  SearchMessageHit,
  SearchResult,
} from '../domain/types'
import { Icon } from '../icons'
import type { ConversationTarget } from './ContactsView'

interface ChatsViewProps {
  onConversationRead: (unreadCount: number) => void
  onUnreadRefresh: () => Promise<void>
  initialTarget?: ConversationTarget
  onTargetConsumed?: () => void
}

type ChatFilter = 'all' | 'unread'

interface UploadTask {
  fileName: string
  kind: 'IMAGE' | 'FILE'
  progress: number
  status: 'uploading' | 'sending' | 'failed'
  error?: string
  file?: File
}

export default function ChatsView({
  onConversationRead,
  onUnreadRefresh,
  initialTarget,
  onTargetConsumed,
}: ChatsViewProps) {
  const { gateway, state } = useApp()
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [activeId, setActiveId] = useState('')
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<ChatFilter>('all')
  const [draft, setDraft] = useState('')
  const [replyTo, setReplyTo] = useState<ChatMessage | null>(null)
  const [newChatOpen, setNewChatOpen] = useState(false)
  const [friends, setFriends] = useState<Friend[]>([])
  const [mobileDetailOpen, setMobileDetailOpen] = useState(false)
  const [loading, setLoading] = useState(true)
  const [loadingMessages, setLoadingMessages] = useState(false)
  const [historyHasMore, setHistoryHasMore] = useState(false)
  const [loadingHistory, setLoadingHistory] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [searchResult, setSearchResult] = useState<SearchResult | null>(null)
  const [searching, setSearching] = useState(false)
  const [conversationMenuOpen, setConversationMenuOpen] = useState(false)
  const [messageMenu, setMessageMenu] = useState<ChatMessage | null>(null)
  const [forwardMessage, setForwardMessage] = useState<ChatMessage | null>(null)
  const [deleteConversationId, setDeleteConversationId] = useState('')
  const [chatSearchOpen, setChatSearchOpen] = useState(false)
  const [chatSearchQuery, setChatSearchQuery] = useState('')
  const [chatSearchResults, setChatSearchResults] = useState<SearchMessageHit[]>([])
  const [chatSearchBusy, setChatSearchBusy] = useState(false)
  const [highlightSeq, setHighlightSeq] = useState<number | null>(null)
  const [typingNames, setTypingNames] = useState<string[]>([])
  const [presence, setPresence] = useState<Record<string, boolean>>({})
  const [uploadTask, setUploadTask] = useState<UploadTask | null>(null)
  const [uploadController, setUploadController] = useState<AbortController | null>(null)
  const [previewImage, setPreviewImage] = useState<ChatMessage | null>(null)
  const [emojiPickerOpen, setEmojiPickerOpen] = useState(false)

  const messageEndRef = useRef<HTMLDivElement>(null)
  const messageScrollRef = useRef<HTMLDivElement>(null)
  const imageInputRef = useRef<HTMLInputElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const draftInputRef = useRef<HTMLTextAreaElement>(null)
  const emojiAnchorRef = useRef<HTMLDivElement>(null)
  const activeIdRef = useRef('')
  const activeRef = useRef<Conversation | undefined>(undefined)
  const typingTimers = useRef(new Map<string, number>())
  const draftSavedRef = useRef(new Map<string, string>())
  const initScrollRef = useRef(true)
  const conversationRefreshTimer = useRef<number | null>(null)
  const conversationRefreshVersion = useRef(new LatestRequestTracker())
  const inFlightReads = useRef(new Set<string>())

  const active = conversations.find((item) => item.id === activeId) ?? conversations[0]
  activeIdRef.current = active?.id ?? ''
  activeRef.current = active
  const unreadTotal = conversations.reduce((sum, item) => sum + item.unread, 0)

  const loadMedia: MediaBlobLoader = useCallback(async (mediaId, variant) => {
    if (!gateway) throw new Error('媒体服务尚未连接')
    return gateway.getMediaBlob(mediaId, variant)
  }, [gateway])

  const filteredConversations = useMemo(() => {
    const normalized = query.trim().toLowerCase()
    return conversations
      .filter((item) => filter === 'all' || item.unread > 0)
      .filter((item) => !normalized
        || `${item.name} ${item.lastMessage}`.toLowerCase().includes(normalized))
      .sort((left, right) =>
        Number(right.pinned) - Number(left.pinned) || right.timestamp - left.timestamp)
  }, [conversations, filter, query])

  const refreshConversations = useCallback(async () => {
    if (!gateway) return
    const version = conversationRefreshVersion.current.begin()
    try {
      const next = await gateway.listConversations()
      if (!conversationRefreshVersion.current.isCurrent(version)) return
      setConversations((current) => {
        const merged = mergeConversationState(next, current)
        const activePending = current.find((item) =>
          item.id === activeIdRef.current && !next.some((remote) => remote.id === item.id))
        return activePending ? [activePending, ...merged] : merged
      })
      setError('')
      setActiveId((current) => current || next[0]?.id || '')
    } catch (reason) {
      if (!conversationRefreshVersion.current.isCurrent(version)) return
      setError(reasonMessage(reason, '会话加载失败'))
    } finally {
      if (conversationRefreshVersion.current.isCurrent(version)) setLoading(false)
    }
  }, [gateway])

  const scheduleConversationRefresh = useCallback(() => {
    if (conversationRefreshTimer.current) window.clearTimeout(conversationRefreshTimer.current)
    conversationRefreshTimer.current = window.setTimeout(() => {
      conversationRefreshTimer.current = null
      void refreshConversations()
    }, 300)
  }, [refreshConversations])

  const markConversationRead = useCallback((
    conversationId: string,
    readSeq: number,
    unreadToClear: number,
    muted: boolean,
  ) => {
    if (!gateway || readSeq <= 0) return
    const operationKey = `${conversationId}:${readSeq}`
    if (inFlightReads.current.has(operationKey)) return
    inFlightReads.current.add(operationKey)

    void gateway.markRead(conversationId, readSeq)
      .then(() => {
        inFlightReads.current.delete(operationKey)
        conversationRefreshVersion.current.invalidate()
        setConversations((current) => current.map((item) => {
          if (item.id !== conversationId) return item
          const unread = Math.max(0, item.unread - unreadToClear)
          return {
            ...item,
            unread,
            maxConvSeq: Math.max(item.maxConvSeq, readSeq),
            readConvSeq: Math.max(item.readConvSeq, readSeq),
            mentionMe: unread > 0 ? item.mentionMe : false,
          }
        }))
        onConversationRead(muted ? 0 : unreadToClear)
        scheduleConversationRefresh()
      })
      .catch((reason) => {
        inFlightReads.current.delete(operationKey)
        setNotice(reasonMessage(reason, '已读状态同步失败'))
        void onUnreadRefresh()
        scheduleConversationRefresh()
      })
      .finally(() => inFlightReads.current.delete(operationKey))
  }, [gateway, onConversationRead, onUnreadRefresh, scheduleConversationRefresh])

  useEffect(() => {
    if (!gateway) {
      setLoading(false)
      setError('SDK 尚未连接')
      return
    }
    void refreshConversations()
    const offs = [
      gateway.on('message', (message: { convId?: string; senderId?: string; convSeq?: number }) => {
        if (message?.convId && message.convId === activeIdRef.current) {
          setMessages(gateway.messages(message.convId))
          const conversation = activeRef.current
          if (conversation && message.senderId !== gateway.currentUserId) {
            markConversationRead(
              conversation.id,
              Number(message.convSeq ?? 0),
              conversation.unread,
              conversation.muted,
            )
          }
        }
        scheduleConversationRefresh()
      }),
      gateway.on('sync', () => {
        scheduleConversationRefresh()
      }),
      gateway.on('conversation', () => {
        scheduleConversationRefresh()
      }),
      gateway.on('presence', (event: any) => {
        const userId = String(event?.user_id ?? event?.userId ?? '')
        const online = typeof event?.online === 'boolean'
          ? event.online
          : Number(event?.status) === 1
        if (userId) setPresence((current) => ({ ...current, [userId]: online }))
      }),
      gateway.on('typing', (event: any) => {
        const convId = String(event?.conv_id ?? event?.convId ?? '')
        const userId = String(event?.user_id ?? event?.userId ?? '')
        if (convId !== activeIdRef.current || !userId || userId === gateway.currentUserId) return
        setTypingName(userId, Boolean(event.typing), gateway)
      }),
      gateway.on('disconnected', () => {
        setTypingNames([])
        setPresence({})
      }),
    ]
    return () => {
      for (const off of offs) off()
      for (const timer of typingTimers.current.values()) window.clearTimeout(timer)
      typingTimers.current.clear()
      if (conversationRefreshTimer.current) window.clearTimeout(conversationRefreshTimer.current)
    }
  }, [gateway, markConversationRead, refreshConversations, scheduleConversationRefresh])

  useEffect(() => {
    if (!gateway || !active) return
    let cancelled = false
    initScrollRef.current = true
    setLoadingMessages(true)
    setError('')
    setDraft(active.draft)
    setReplyTo(null)
    setHighlightSeq(null)
    void gateway.history(active.id, 0, 50)
      .then(async ({ messages: next, hasMore }) => {
        if (cancelled) return
        setMessages(next)
        setHistoryHasMore(hasMore)
        requestAnimationFrame(() => messageEndRef.current?.scrollIntoView({ block: 'end' }))
      })
      .catch((reason) => {
        if (!cancelled) setError(reasonMessage(reason, '消息加载失败'))
      })
      .finally(() => {
        if (!cancelled) setLoadingMessages(false)
      })

    let unsubscribePresence = () => {}
    if (active.kind === 'direct' && active.targetId) {
      void gateway.subscribePresence([active.targetId]).catch(() => {})
      unsubscribePresence = () => {
        void gateway.unsubscribePresence([active.targetId!]).catch(() => {})
      }
    }
    return () => {
      cancelled = true
      unsubscribePresence()
    }
  }, [gateway, active?.id])

  useEffect(() => {
    if (!gateway || !active || loadingMessages) return
    if (active.unread <= 0 && active.maxConvSeq <= active.readConvSeq) return

    const conversationId = active.id
    const readSeq = active.maxConvSeq
    const unreadToClear = active.unread
    markConversationRead(conversationId, readSeq, unreadToClear, active.muted)
  }, [
    active?.id,
    active?.maxConvSeq,
    active?.muted,
    active?.readConvSeq,
    active?.unread,
    gateway,
    loadingMessages,
    markConversationRead,
  ])

  useEffect(() => {
    setEmojiPickerOpen(false)
  }, [active?.id])

  useEffect(() => {
    if (!emojiPickerOpen) return
    const handlePointerDown = (event: PointerEvent) => {
      if (!emojiAnchorRef.current?.contains(event.target as Node)) {
        setEmojiPickerOpen(false)
      }
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setEmojiPickerOpen(false)
      requestAnimationFrame(() => draftInputRef.current?.focus())
    }
    document.addEventListener('pointerdown', handlePointerDown)
    window.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [emojiPickerOpen])

  useEffect(() => {
    if (!gateway || !active) return
    const saved = draftSavedRef.current.get(active.id) ?? active.draft
    if (saved === draft) return
    const timer = window.setTimeout(() => {
      const operation = draft.trim()
        ? gateway.saveDraft(active.id, draft)
        : gateway.clearDraft(active.id)
      void operation.then(() => {
        draftSavedRef.current.set(active.id, draft)
        setConversations((current) => current.map((item) =>
          item.id === active.id ? { ...item, draft } : item))
      }).catch((reason) => setNotice(reasonMessage(reason, '草稿同步失败')))
    }, 2000)
    return () => window.clearTimeout(timer)
  }, [active?.id, draft, gateway])

  useEffect(() => {
    if (!gateway || !active || !draft.trim()) return
    void gateway.sendTyping(active.id, true).catch(() => {})
    const timer = window.setTimeout(() => {
      void gateway.sendTyping(active.id, false).catch(() => {})
    }, 4000)
    return () => {
      window.clearTimeout(timer)
      void gateway.sendTyping(active.id, false).catch(() => {})
    }
  }, [active?.id, draft, gateway])

  useEffect(() => {
    if (!initialTarget) return
    switchConversation(initialTarget)
    onTargetConsumed?.()
  }, [initialTarget?.id])

  useEffect(() => {
    if (!query.trim()) {
      setSearchResult(null)
      return
    }
    if (!gateway || query.trim().length < 2) return
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
    if (highlightSeq === null) return
    requestAnimationFrame(() => {
      document.querySelector(`[data-conv-seq="${highlightSeq}"]`)?.scrollIntoView({
        behavior: 'smooth',
        block: 'center',
      })
    })
  }, [highlightSeq, messages])

  useEffect(() => {
    if (!notice) return
    const timer = window.setTimeout(() => setNotice(''), 2800)
    return () => window.clearTimeout(timer)
  }, [notice])

  function setTypingName(userId: string, typing: boolean, sdk: typeof gateway) {
    const previous = typingTimers.current.get(userId)
    if (previous) window.clearTimeout(previous)
    const name = sdk ? activePeerName(activeRef.current, userId) : userId
    if (!typing) {
      typingTimers.current.delete(userId)
      setTypingNames((current) => current.filter((item) => item !== name))
      return
    }
    setTypingNames((current) => current.includes(name) ? current : [...current, name])
    const timer = window.setTimeout(() => {
      typingTimers.current.delete(userId)
      setTypingNames((current) => current.filter((item) => item !== name))
    }, 5000)
    typingTimers.current.set(userId, timer)
  }

  function switchConversation(target: ConversationTarget) {
    const current = activeRef.current
    if (current && draft !== (draftSavedRef.current.get(current.id) ?? current.draft)) {
      const operation = draft.trim()
        ? gateway?.saveDraft(current.id, draft)
        : gateway?.clearDraft(current.id)
      void operation?.catch(() => {})
    }
    const existing = conversations.find((item) => item.id === target.id)
    if (!existing) {
      const now = Date.now()
      setConversations((currentList) => currentList.some((item) => item.id === target.id)
        ? currentList
        : [{
            id: target.id,
            targetId: target.targetId,
            name: target.name,
            kind: target.kind,
            subtitle: target.kind === 'group' ? '群组会话' : '单聊',
            lastMessage: '',
            time: '',
            timestamp: now,
            unread: 0,
            pinned: false,
            muted: false,
            mentionMe: false,
            draft: '',
            readOnly: false,
            maxConvSeq: 0,
            readConvSeq: 0,
            online: false,
            tone: 'mint',
            avatarChars: [target.name.slice(-1)],
          }, ...currentList])
    }
    setActiveId(target.id)
    setMobileDetailOpen(true)
    setConversationMenuOpen(false)
  }

  async function loadOlderMessages() {
    if (!gateway || !active || !historyHasMore || loadingHistory || messages.length === 0) return
    setLoadingHistory(true)
    const scroll = messageScrollRef.current
    const previousHeight = scroll?.scrollHeight ?? 0
    try {
      const firstSeq = messages.find((message) => message.convSeq > 0)?.convSeq ?? 0
      const result = await gateway.history(active.id, firstSeq, 50)
      setMessages(result.messages)
      setHistoryHasMore(result.hasMore)
      requestAnimationFrame(() => {
        if (scroll) scroll.scrollTop += scroll.scrollHeight - previousHeight
      })
    } catch (reason) {
      setNotice(reasonMessage(reason, '历史消息加载失败'))
    } finally {
      setLoadingHistory(false)
    }
  }

  function insertEmoji(emoji: string) {
    if (!active || active.readOnly) return
    const input = draftInputRef.current
    const start = input?.selectionStart ?? draft.length
    const end = input?.selectionEnd ?? draft.length
    setDraft((current) => `${current.slice(0, start)}${emoji}${current.slice(end)}`)
    requestAnimationFrame(() => {
      input?.focus()
      input?.setSelectionRange(start + emoji.length, start + emoji.length)
    })
  }

  async function sendText() {
    if (!gateway || !active || !draft.trim()) return
    const text = draft.trim()
    const reply = replyTo
    setDraft('')
    setReplyTo(null)
    draftSavedRef.current.set(active.id, '')
    void gateway.clearDraft(active.id).catch(() => {})
    try {
      const operation = gateway.sendText(active.id, text, reply?.id)
      setMessages(gateway.messages(active.id))
      await operation
      setMessages(gateway.messages(active.id))
      initScrollRef.current = true
    } catch (reason) {
      setNotice(reasonMessage(reason, '消息发送失败'))
    }
  }

  async function retryMessage(message: ChatMessage) {
    if (!gateway) return
    try {
      await gateway.resend(message)
      setMessages(gateway.messages(message.convId))
    } catch (reason) {
      setNotice(reasonMessage(reason, '重发失败'))
    }
  }

  async function handleFile(file: File, kind: 'IMAGE' | 'FILE') {
    if (!gateway || !active) return
    if (file.size > 500 * 1024 * 1024) {
      setNotice('文件超过 500MB，无法发送')
      return
    }
    const controller = new AbortController()
    setUploadController(controller)
    setUploadTask({ fileName: file.name, kind, progress: 0, status: 'uploading', file })
    try {
      const media = await gateway.uploadMedia(file, {
        fileName: file.name,
        mediaType: kind,
        sha256: await sha256(file),
        signal: controller.signal,
        onProgress: (uploaded, total) => {
          setUploadTask((current) => current && {
            ...current,
            progress: total > 0 ? Math.round(uploaded / total * 100) : 0,
          })
        },
      })
      setUploadTask((current) => current && { ...current, status: 'sending', progress: 100 })
      await gateway.sendMedia(active.id, kind, media)
      setMessages(gateway.messages(active.id))
      setUploadTask(null)
    } catch (reason) {
      if (controller.signal.aborted) {
        setUploadTask(null)
        setNotice('已取消上传')
      } else {
        setUploadTask((current) => current && {
          ...current,
          status: 'failed',
          error: reasonMessage(reason, '上传失败'),
        })
      }
    } finally {
      setUploadController(null)
    }
  }

  async function toggleConversation(patch: { pinned?: boolean; muted?: boolean }) {
    if (!gateway || !active) return
    const previous = active
    setConversations((current) => current.map((item) =>
      item.id === active.id ? { ...item, ...patch } : item))
    setConversationMenuOpen(false)
    try {
      await gateway.updateConversation(active.id, patch)
    } catch (reason) {
      setConversations((current) => current.map((item) => item.id === previous.id ? previous : item))
      setNotice(reasonMessage(reason, '会话状态更新失败'))
    }
  }

  async function removeConversation() {
    if (!gateway || !deleteConversationId) return
    try {
      await gateway.deleteConversation(deleteConversationId)
      setConversations((current) => current.filter((item) => item.id !== deleteConversationId))
      if (activeId === deleteConversationId) {
        setActiveId('')
        setMessages([])
      }
      setDeleteConversationId('')
    } catch (reason) {
      setNotice(reasonMessage(reason, '删除会话失败'))
    }
  }

  async function runMessageAction(action: 'reply' | 'forward' | 'recall' | 'delete' | 'copy') {
    if (!gateway || !messageMenu) return
    const message = messageMenu
    setMessageMenu(null)
    try {
      if (action === 'reply') {
        setReplyTo(message)
        return
      }
      if (action === 'forward') {
        setForwardMessage(message)
        return
      }
      if (action === 'recall') {
        await gateway.recall(message.id)
        setMessages(gateway.messages(message.convId))
        setNotice('消息已撤回')
        return
      }
      if (action === 'delete') {
        await gateway.deleteLocal(message.id)
        setMessages(gateway.messages(message.convId))
        setNotice('已从当前设备删除')
        return
      }
      if (action === 'copy' && message.text) {
        await navigator.clipboard.writeText(message.text)
        setNotice('消息已复制')
      }
    } catch (reason) {
      setNotice(reasonMessage(reason, '消息操作失败'))
    }
  }

  async function forwardTo(conversation: Conversation) {
    if (!gateway || !forwardMessage) return
    try {
      await gateway.forward([forwardMessage.id], [conversation.id])
      setForwardMessage(null)
      setNotice(`已转发给 ${conversation.name}`)
    } catch (reason) {
      setNotice(reasonMessage(reason, '转发失败'))
    }
  }

  async function openNewChat() {
    setNewChatOpen(true)
    if (!gateway) return
    try {
      const next = await gateway.listFriends()
      setFriends(next)
    } catch (reason) {
      setNotice(reasonMessage(reason, '好友列表加载失败'))
    }
  }

  async function runChatSearch() {
    if (!gateway || !active || !chatSearchQuery.trim()) return
    setChatSearchBusy(true)
    try {
      const result = await gateway.searchConversation(active.id, chatSearchQuery.trim())
      setChatSearchResults(result.messages)
    } catch (reason) {
      setNotice(reasonMessage(reason, '会话内搜索失败'))
    } finally {
      setChatSearchBusy(false)
    }
  }

  async function locateSearchHit(hit: SearchMessageHit) {
    if (!gateway || !active) return
    try {
      const result = await gateway.history(active.id, hit.convSeq + 1, 50)
      setMessages(result.messages)
      setHighlightSeq(hit.convSeq)
      setChatSearchOpen(false)
    } catch (reason) {
      setNotice(reasonMessage(reason, '定位消息失败'))
    }
  }

  function openResultConversation(target: ConversationTarget) {
    switchConversation(target)
    setQuery('')
  }

  if (!active && loading) {
    return <ViewState title="正在加载会话" description="正在读取 Phantom 会话快照…" />
  }

  if (!active && error) {
    return (
      <ViewState
        title="会话加载失败"
        description={error}
        action={<button type="button" className="primary-button" onClick={() => void refreshConversations()}>重试</button>}
      />
    )
  }

  if (!active) {
    return (
      <ViewState
        title="还没有会话"
        description="从通讯录选择好友开始一段新对话。"
        action={<button type="button" className="primary-button" onClick={() => void openNewChat()}>发起会话</button>}
        empty
      />
    )
  }

  return (
    <div className={`workspace-view chats-view ${mobileDetailOpen ? 'mobile-detail-open' : ''}`}>
      <section className="list-pane conversation-pane" aria-label="聊天列表">
        <header className="pane-heading">
          <div>
            <span className="eyebrow">PHANTOM CHAT</span>
            <h1>消息</h1>
          </div>
          <IconButton icon="plus" label="发起新会话" variant="soft" onClick={() => void openNewChat()} />
        </header>

        <SearchField
          placeholder="搜索联系人或消息"
          aria-label="搜索聊天"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onClear={() => setQuery('')}
        />

        <div className="list-toolbar" aria-label="会话筛选">
          <div className="segmented segmented--compact">
            <button type="button" className={filter === 'all' ? 'is-active' : ''} onClick={() => setFilter('all')}>
              全部
            </button>
            <button type="button" className={filter === 'unread' ? 'is-active' : ''} onClick={() => setFilter('unread')}>
              未读
              {unreadTotal > 0 && (
                <CountBadge
                  count={unreadTotal}
                  label="未读消息"
                  tone="accent"
                  size="sm"
                  className="segment-count"
                />
              )}
            </button>
          </div>
          <button type="button" className="toolbar-icon" aria-label="刷新会话" onClick={() => void refreshConversations()}>
            <Icon name="refresh" size={18} />
          </button>
        </div>

        {query.trim() && (
          <ChatSearchPanel
            query={query}
            searching={searching}
            result={searchResult}
            onContact={(contact) => openResultConversation({
              id: singleConvId(gateway?.currentUserId ?? '', contact.userId),
              name: contact.remark || contact.nickname,
              kind: 'direct',
              targetId: contact.userId,
            })}
            onGroup={(group) => openResultConversation({
              id: group.convId,
              name: group.name,
              kind: 'group',
            })}
            onMessage={(hit) => {
              const conversation = conversations.find((item) => item.id === hit.convId)
              openResultConversation({
                id: hit.convId,
                name: conversation?.name ?? hit.convTitle,
                kind: conversation?.kind ?? 'direct',
                targetId: conversation?.targetId,
              })
              setHighlightSeq(hit.convSeq)
            }}
          />
        )}

        <div className="conversation-list">
          {filteredConversations.length === 0 ? (
            <EmptyState title="没有找到相关会话" description="换个关键词或查看全部消息" />
          ) : filteredConversations.map((conversation) => (
            <button
              type="button"
              key={conversation.id}
              className={`conversation-item ${conversation.id === active.id ? 'is-active' : ''}`}
              onClick={() => switchConversation({
                id: conversation.id,
                name: conversation.name,
                kind: conversation.kind,
                targetId: conversation.targetId,
              })}
              aria-current={conversation.id === active.id ? 'page' : undefined}
            >
              <Avatar
                chars={conversation.avatarChars}
                tone={conversation.tone}
                size={52}
                group={conversation.kind === 'group'}
                online={conversation.kind === 'direct'
                  && Boolean(presence[conversation.targetId ?? ''])
                  || conversation.online}
              />
              <span className="conversation-copy">
                <span className="conversation-line">
                  <strong>{conversation.name}</strong>
                  <time>{conversation.time}</time>
                </span>
                <span className="conversation-line conversation-line--bottom">
                  <span className="conversation-preview">
                    {conversation.unread > 0 && conversation.mentionMe && <b className="mention-tag">@我</b>}
                    {conversation.draft ? `[草稿] ${conversation.draft}` : conversation.lastMessage || '暂无消息'}
                  </span>
                  <span className="conversation-meta">
                    {conversation.muted && <Icon name="bellOff" size={13} />}
                    {conversation.pinned && <Icon name="pin" size={12} />}
                    {conversation.unread > 0 && (conversation.muted
                      ? <span className="unread-dot" aria-label={`${conversation.name}有未读消息`} />
                      : (
                        <CountBadge
                          count={conversation.unread}
                          label={`${conversation.name}未读消息`}
                          tone="danger"
                          size="md"
                          className="unread-badge"
                        />
                      ))}
                  </span>
                </span>
              </span>
            </button>
          ))}
        </div>

        <div className="list-footer-note">
          <span className={`online-pulse ${state.phase === 'ready' ? '' : 'is-idle'}`} />
          <span>{state.phase === 'ready' ? '已连接 Phantom 服务 · 消息实时同步' : '连接恢复中…'}</span>
        </div>
      </section>

      <section className="detail-pane chat-pane" aria-label={`与${active.name}的聊天`}>
        <header className="chat-header">
          <IconButton
            icon="arrowLeft"
            label="返回聊天列表"
            className="mobile-back-button"
            onClick={() => setMobileDetailOpen(false)}
          />
          <div className="chat-identity">
            <Avatar
              chars={active.avatarChars}
              tone={active.tone}
              size={46}
              group={active.kind === 'group'}
              online={active.kind === 'direct' && Boolean(presence[active.targetId ?? ''])}
            />
            <div>
              <div className="chat-title-line">
                <h2>{active.name}</h2>
                {active.kind === 'group' && <span className="soft-badge">群组</span>}
                {active.readOnly && <span className="soft-badge">只读</span>}
              </div>
              <p className="chat-presence">
                {typingNames.length > 0
                  ? `${typingNames.join('、')}正在输入…`
                  : active.kind === 'group'
                    ? `${active.members ?? '多'} 位成员`
                    : presence[active.targetId ?? '']
                      ? '在线'
                      : '离线'}
              </p>
            </div>
          </div>
          <div className="chat-header-actions">
            <IconButton icon="phone" label="语音通话暂未开放" onClick={() => setNotice('语音通话暂未开放')} />
            <IconButton icon="video" label="视频通话暂未开放" onClick={() => setNotice('视频通话暂未开放')} />
            <span className="header-divider" />
            <IconButton
              icon="search"
              label="在聊天中搜索"
              onClick={() => {
                setChatSearchOpen(true)
                setChatSearchResults([])
                setChatSearchQuery('')
              }}
            />
            <div className="relative-action">
              <IconButton
                icon="more"
                label="聊天设置"
                onClick={() => setConversationMenuOpen((current) => !current)}
              />
              {conversationMenuOpen && (
                <div className="action-menu action-menu--right" role="menu">
                  <button type="button" role="menuitem" onClick={() => void toggleConversation({ pinned: !active.pinned })}>
                    <Icon name="pin" size={15} />{active.pinned ? '取消置顶' : '置顶会话'}
                  </button>
                  <button type="button" role="menuitem" onClick={() => void toggleConversation({ muted: !active.muted })}>
                    <Icon name="bellOff" size={15} />{active.muted ? '开启提醒' : '消息免打扰'}
                  </button>
                  <button type="button" role="menuitem" className="is-danger" onClick={() => {
                    setDeleteConversationId(active.id)
                    setConversationMenuOpen(false)
                  }}>
                    <Icon name="trash" size={15} />删除会话
                  </button>
                </div>
              )}
            </div>
          </div>
        </header>

        <div
          className="message-canvas"
          ref={messageScrollRef}
          onScroll={(event) => {
            if (event.currentTarget.scrollTop < 50) void loadOlderMessages()
          }}
        >
          <div className="message-scroll">
            {historyHasMore && (
              <button type="button" className="history-loader" disabled={loadingHistory} onClick={() => void loadOlderMessages()}>
                {loadingHistory ? '正在加载更早消息…' : '加载更早消息'}
              </button>
            )}
            {loadingMessages ? (
              <div className="inline-loading">正在加载消息…</div>
            ) : messages.length === 0 ? (
              <EmptyState title="还没有消息" description="发出第一条消息，开始这段对话" />
            ) : (
              <>
                <div className="date-divider"><span>近期消息</span></div>
                <div className="message-helper">
                  <span className="helper-icon"><Icon name="shield" size={14} /></span>
                  会话内容仅参与者可见
                </div>
                {messages.map((message) => (
                  <MessageBubble
                    key={message.id || message.clientMsgId}
                    message={message}
                    group={active.kind === 'group'}
                    highlight={message.convSeq === highlightSeq}
                    onMenu={() => setMessageMenu(message)}
                    onRetry={() => void retryMessage(message)}
                    onOpenImage={() => setPreviewImage(message)}
                    loadMedia={loadMedia}
                  />
                ))}
              </>
            )}
            <div ref={messageEndRef} />
          </div>
        </div>

        {typingNames.length > 0 && (
          <div className="typing-indicator">
            <span className="auth-typing-dots"><i /><i /><i /></span>
            {typingNames.join('、')}正在输入
          </div>
        )}

        {uploadTask && (
          <div className={`upload-progress ${uploadTask.status === 'failed' ? 'is-error' : ''}`}>
            <span><Icon name={uploadTask.kind === 'IMAGE' ? 'image' : 'paperclip'} size={16} /></span>
            <div>
              <strong>{uploadTask.fileName}</strong>
              <small>
                {uploadTask.status === 'failed'
                  ? uploadTask.error
                  : uploadTask.status === 'sending'
                    ? '上传完成，正在发送…'
                    : `上传中 ${uploadTask.progress}%`}
              </small>
              <div className="upload-track"><span style={{ width: `${uploadTask.progress}%` }} /></div>
            </div>
            {uploadTask.status === 'uploading' ? (
              <button type="button" onClick={() => uploadController?.abort()}>取消</button>
            ) : uploadTask.status === 'failed' && uploadTask.file ? (
              <button type="button" onClick={() => void handleFile(uploadTask.file!, uploadTask.kind)}>重试</button>
            ) : null}
          </div>
        )}

        {replyTo && (
          <div className="reply-preview">
            <div>
              <strong>回复 {replyTo.author ?? (replyTo.from === 'me' ? '自己' : active.name)}</strong>
              <span>{replyTo.text || replyTo.attachment?.title || '附件消息'}</span>
            </div>
            <button type="button" aria-label="取消回复" onClick={() => setReplyTo(null)}>×</button>
          </div>
        )}

        <footer className="composer">
          <div className="composer-toolbar">
            <div className="emoji-anchor" ref={emojiAnchorRef}>
              <IconButton
                icon="smile"
                label="添加表情"
                aria-expanded={emojiPickerOpen}
                aria-haspopup="dialog"
                aria-controls="emoji-picker-panel"
                disabled={active.readOnly}
                onClick={() => setEmojiPickerOpen((open) => !open)}
              />
              {emojiPickerOpen && (
                <div id="emoji-picker-panel">
                  <EmojiPicker onSelect={insertEmoji} />
                </div>
              )}
            </div>
            <input
              ref={imageInputRef}
              hidden
              type="file"
              accept="image/*"
              data-testid="image-input"
              onChange={(event) => {
                const file = event.target.files?.[0]
                if (file) void handleFile(file, 'IMAGE')
                event.target.value = ''
              }}
            />
            <IconButton icon="image" label="发送图片" onClick={() => imageInputRef.current?.click()} />
            <input
              ref={fileInputRef}
              hidden
              type="file"
              data-testid="file-input"
              onChange={(event) => {
                const file = event.target.files?.[0]
                if (file) void handleFile(file, 'FILE')
                event.target.value = ''
              }}
            />
            <IconButton icon="paperclip" label="发送文件" onClick={() => fileInputRef.current?.click()} />
            <span className="composer-hint">Enter 发送 · Shift + Enter 换行</span>
          </div>
          <div className="composer-input">
            <textarea
              ref={draftInputRef}
              value={draft}
              rows={2}
              disabled={active.readOnly}
              placeholder={active.readOnly ? '当前会话为只读' : `发送消息给 ${active.name}…`}
              aria-label="消息内容"
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                  event.preventDefault()
                  void sendText()
                }
              }}
            />
            <button type="button" className="send-button" disabled={!draft.trim() || active.readOnly} onClick={() => void sendText()}>
              <span>发送</span>
              <Icon name="send" size={18} />
            </button>
          </div>
        </footer>
      </section>

      {messageMenu && (
        <div className="action-menu action-menu--floating" role="menu">
          <button type="button" role="menuitem" onClick={() => void runMessageAction('reply')}>
            <Icon name="message" size={15} />引用回复
          </button>
          <button type="button" role="menuitem" onClick={() => void runMessageAction('forward')}>
            <Icon name="arrowRight" size={15} />转发
          </button>
          {messageMenu.from === 'me' && !messageMenu.recalled && (
            <button type="button" role="menuitem" onClick={() => void runMessageAction('recall')}>
              <Icon name="refresh" size={15} />撤回
            </button>
          )}
          <button type="button" role="menuitem" onClick={() => void runMessageAction('copy')}>
            <Icon name="copy" size={15} />复制
          </button>
          <button type="button" role="menuitem" className="is-danger" onClick={() => void runMessageAction('delete')}>
            <Icon name="trash" size={15} />本地删除
          </button>
          <button type="button" className="action-menu-close" onClick={() => setMessageMenu(null)}>关闭</button>
        </div>
      )}

      <Modal open={newChatOpen} title="发起新会话" description="从真实好友中选择聊天对象" onClose={() => setNewChatOpen(false)} width={430}>
        <div className="new-chat-list">
          {friends.length === 0 ? (
            <EmptyState title="还没有好友" description="请先在通讯录添加好友" />
          ) : friends.map((friend) => (
            <button
              type="button"
              key={friend.id}
              className="new-chat-row"
              onClick={() => {
                switchConversation({
                  id: singleConvId(gateway?.currentUserId ?? '', friend.id),
                  name: friend.name,
                  kind: 'direct',
                  targetId: friend.id,
                })
                setNewChatOpen(false)
              }}
            >
              <Avatar chars={[friend.name.slice(-1)]} tone="mint" size={42} />
              <span><strong>{friend.name}</strong><small>@{friend.id}</small></span>
              <Icon name="chevronRight" size={17} />
            </button>
          ))}
        </div>
      </Modal>

      <Modal open={Boolean(forwardMessage)} title="转发消息" description="选择目标会话" onClose={() => setForwardMessage(null)} width={430}>
        <div className="new-chat-list">
          {conversations.map((conversation) => (
            <button type="button" key={conversation.id} className="new-chat-row" onClick={() => void forwardTo(conversation)}>
              <Avatar chars={conversation.avatarChars} tone={conversation.tone} size={42} group={conversation.kind === 'group'} />
              <span><strong>{conversation.name}</strong><small>{conversation.kind === 'group' ? '群组' : '单聊'}</small></span>
              <Icon name="chevronRight" size={17} />
            </button>
          ))}
        </div>
      </Modal>

      <Modal open={Boolean(deleteConversationId)} title="删除会话" description="该操作仅删除当前账号的会话视图。" onClose={() => setDeleteConversationId('')} width={420}>
        <div className="modal-actions">
          <button type="button" className="secondary-button" onClick={() => setDeleteConversationId('')}>取消</button>
          <button type="button" className="primary-button" onClick={() => void removeConversation()}>确认删除</button>
        </div>
      </Modal>

      <ImagePreviewModal
        open={Boolean(previewImage)}
        attachment={previewImage?.attachment}
        loadMedia={loadMedia}
        onClose={() => setPreviewImage(null)}
      />

      <Modal open={chatSearchOpen} title={`在「${active.name}」中搜索`} description="仅返回你有权限查看的消息" onClose={() => setChatSearchOpen(false)} width={560}>
        <div className="modal-form">
          <div className="inline-search">
            <input
              autoFocus
              value={chatSearchQuery}
              placeholder="输入消息关键词"
              onChange={(event) => setChatSearchQuery(event.target.value)}
              onKeyDown={(event) => event.key === 'Enter' && void runChatSearch()}
            />
            <button type="button" className="primary-button" disabled={chatSearchBusy || !chatSearchQuery.trim()} onClick={() => void runChatSearch()}>
              {chatSearchBusy ? '搜索中…' : '搜索'}
            </button>
          </div>
          <div className="chat-search-list">
            {chatSearchResults.length === 0 ? (
              <p className="card-empty-copy">{chatSearchQuery ? '没有匹配消息' : '输入关键词开始搜索'}</p>
            ) : chatSearchResults.map((hit) => (
              <button type="button" key={hit.msgId} onClick={() => void locateSearchHit(hit)}>
                <strong>{hit.digest}</strong>
                <small>#{hit.convSeq} · {hit.senderId}</small>
              </button>
            ))}
          </div>
        </div>
      </Modal>

      {notice && (
        <div className="toast toast--neutral" role="status">
          <Icon name="shield" size={16} />
          {notice}
        </div>
      )}
    </div>
  )
}

function MessageBubble({
  message,
  group,
  highlight,
  onMenu,
  onRetry,
  onOpenImage,
  loadMedia,
}: {
  message: ChatMessage
  group: boolean
  highlight: boolean
  onMenu: () => void
  onRetry: () => void
  onOpenImage: () => void
  loadMedia: MediaBlobLoader
}) {
  const mine = message.from === 'me'
  const author = message.author ?? (mine ? '我' : '好友')
  return (
    <div
      className={`message-row ${mine ? 'message-row--mine' : ''} ${highlight ? 'is-highlighted' : ''}`}
      data-conv-seq={message.convSeq || undefined}
      onContextMenu={(event) => {
        event.preventDefault()
        onMenu()
      }}
    >
      {!mine && message.from !== 'system' && <Avatar chars={[author.slice(-1)]} tone="sage" size={34} />}
      <div className="message-content">
        {!mine && group && message.from !== 'system' && <span className="message-author">{author}</span>}
        <div className={`message-bubble ${mine ? 'message-bubble--mine' : ''} ${message.from === 'system' ? 'message-bubble--system' : ''}`}>
          {message.replyToMsgId && (
            <div className="message-reply-quote">
              <small>引用消息</small>
              <span>{message.replyDigest || '已引用一条消息'}</span>
            </div>
          )}
          {message.text && <p>{message.text}</p>}
          {message.kind === 'image' && message.attachment && (
            <MessageImage
              attachment={message.attachment}
              loadMedia={loadMedia}
              onOpen={onOpenImage}
            />
          )}
          {(message.kind === 'file' || message.kind === 'voice' || message.kind === 'video') && message.attachment && (
            <div className="file-attachment">
              <span className="file-icon"><Icon name="file" size={22} /></span>
              <span><strong>{message.attachment.title}</strong><small>{message.attachment.meta}</small></span>
              <span className="file-download"><Icon name="chevronDown" size={17} /></span>
            </div>
          )}
          {message.restricted && <small className="message-restricted">消息可能不会被对方看到</small>}
        </div>
        <div className="message-meta-line">
          <span className="message-time">{message.time}</span>
          {mine && (
            <span className={`message-state message-state--${message.state}`}>
              {message.state === 'sending' && '发送中'}
              {message.state === 'failed' && (
                <button
                  type="button"
                  className="message-retry"
                  aria-label="消息发送失败，点击重试"
                  onClick={onRetry}
                >
                  <span className="message-retry-icon"><Icon name="refresh" size={11} /></span>
                  <span>发送失败</span>
                  <span className="message-retry-action">重试</span>
                </button>
              )}
              {message.state === 'sent' && '已发送'}
              {message.state === 'delivered' && '已送达'}
              {message.state === 'read' && '已读'}
              {message.state === 'recalled' && '已撤回'}
            </span>
          )}
          <button type="button" className="message-more" aria-label="消息操作" onClick={onMenu}>
            <Icon name="more" size={14} />
          </button>
        </div>
      </div>
    </div>
  )
}

function ChatSearchPanel({
  query,
  searching,
  result,
  onContact,
  onGroup,
  onMessage,
}: {
  query: string
  searching: boolean
  result: SearchResult | null
  onContact: (contact: SearchResult['contacts'][number]) => void
  onGroup: (group: SearchResult['groups'][number]) => void
  onMessage: (message: SearchMessageHit) => void
}) {
  if (searching) return <div className="chat-search-panel"><span>正在搜索…</span></div>
  if (!result) return null
  const total = result.contacts.length + result.groups.length + result.messages.length
  return (
    <div className="chat-search-panel">
      <div className="chat-search-heading"><span>搜索「{query}」</span><small>{total} 个结果</small></div>
      {result.contacts.length > 0 && (
        <div className="chat-search-section">
          <small>联系人</small>
          {result.contacts.map((contact) => (
            <button type="button" key={contact.userId} onClick={() => onContact(contact)}>
              <strong>{contact.remark || contact.nickname}</strong>
              <span>@{contact.userId}</span>
            </button>
          ))}
        </div>
      )}
      {result.groups.length > 0 && (
        <div className="chat-search-section">
          <small>群组</small>
          {result.groups.map((group) => (
            <button type="button" key={group.groupId} onClick={() => onGroup(group)}>
              <strong>{group.name}</strong><span>群组</span>
            </button>
          ))}
        </div>
      )}
      {result.messages.length > 0 && (
        <div className="chat-search-section">
          <small>聊天记录</small>
          {result.messages.map((message) => (
            <button type="button" key={message.msgId} onClick={() => onMessage(message)}>
              <strong>{message.digest}</strong><span>{message.convTitle}</span>
            </button>
          ))}
        </div>
      )}
      {total === 0 && <p className="card-empty-copy">没有匹配结果</p>}
    </div>
  )
}

function ViewState({
  title,
  description,
  action,
  empty,
}: {
  title: string
  description: string
  action?: React.ReactNode
  empty?: boolean
}) {
  return (
    <div className="workspace-view view-state">
      <div className={`view-state-card ${empty ? 'is-empty' : ''}`}>
        <span className="view-state-icon"><Icon name={empty ? 'chat' : 'refresh'} size={25} /></span>
        <h2>{title}</h2>
        <p>{description}</p>
        {action}
      </div>
    </div>
  )
}

function mergeConversationState(next: Conversation[], current: Conversation[]): Conversation[] {
  const byId = new Map(current.map((item) => [item.id, item]))
  return next.map((item) => {
    const previous = byId.get(item.id)
    return previous ? {
      ...item,
      draft: item.draft || previous.draft,
      online: previous.online,
    } : item
  })
}

function activePeerName(active: Conversation | undefined, userId: string): string {
  if (!active) return userId
  if (active.targetId === userId) return active.name
  return userId
}

async function sha256(file: File): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

function reasonMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}
