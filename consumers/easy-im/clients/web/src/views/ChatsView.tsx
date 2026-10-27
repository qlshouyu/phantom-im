import { useEffect, useMemo, useRef, useState } from 'react'
import {
  initialConversations,
  type ChatMessage,
  type Conversation,
  type MessageKind,
} from '../data'
import { Icon } from '../icons'
import { Avatar, EmptyState, IconButton, Modal, SearchField } from '../components/ui'

interface ChatsViewProps {
  onUnreadChange: (count: number) => void
}

type ChatFilter = 'all' | 'unread'

const attachmentLabel: Partial<Record<MessageKind, string>> = {
  image: '[图片]',
  file: '[文件]',
  link: '[链接]',
}

export default function ChatsView({ onUnreadChange }: ChatsViewProps) {
  const [conversations, setConversations] = useState<Conversation[]>(initialConversations)
  const [activeId, setActiveId] = useState(initialConversations[0].id)
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<ChatFilter>('all')
  const [draft, setDraft] = useState('')
  const [newChatOpen, setNewChatOpen] = useState(false)
  const [mobileDetailOpen, setMobileDetailOpen] = useState(false)
  const messageEndRef = useRef<HTMLDivElement>(null)

  const active = conversations.find((item) => item.id === activeId) ?? conversations[0]
  const unreadTotal = conversations.reduce((sum, item) => sum + item.unread, 0)

  const filteredConversations = useMemo(() => {
    const normalized = query.trim().toLowerCase()
    return conversations.filter((item) => {
      if (filter === 'unread' && item.unread === 0) return false
      if (!normalized) return true
      return `${item.name} ${item.lastMessage}`.toLowerCase().includes(normalized)
    })
  }, [conversations, filter, query])

  useEffect(() => {
    onUnreadChange(unreadTotal)
  }, [onUnreadChange, unreadTotal])

  useEffect(() => {
    messageEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [activeId, active?.messages.length])

  function selectConversation(id: string) {
    setActiveId(id)
    setMobileDetailOpen(true)
    setConversations((current) =>
      current.map((item) => (item.id === id ? { ...item, unread: 0 } : item)),
    )
  }

  function sendMessage(kind: MessageKind = 'text') {
    if (kind === 'text' && !draft.trim()) return

    const now = new Date()
    const time = now.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    const message: ChatMessage =
      kind === 'text'
        ? {
            id: `msg-${Date.now()}`,
            from: 'me',
            text: draft.trim(),
            time,
          }
        : kind === 'image'
          ? {
              id: `msg-${Date.now()}`,
              from: 'me',
              time,
              kind: 'image',
              attachment: {
                title: '刚刚分享的图片',
                meta: 'IMAGE.JPG · 3.2 MB',
              },
            }
          : {
              id: `msg-${Date.now()}`,
              from: 'me',
              time,
              kind: 'file',
              attachment: {
                title: '品牌视觉规范_V2.pdf',
                meta: 'PDF · 12.8 MB',
              },
            }
    const lastMessage =
      kind === 'text'
        ? message.text ?? ''
        : (attachmentLabel[kind] ?? '[文件]')

    setConversations((current) =>
      current.map((item) =>
        item.id === active.id
          ? { ...item, messages: [...item.messages, message], lastMessage, time }
          : item,
      ),
    )
    if (kind === 'text') setDraft('')
  }

  return (
    <div className={`workspace-view chats-view ${mobileDetailOpen ? 'mobile-detail-open' : ''}`}>
      <section className="list-pane conversation-pane" aria-label="聊天列表">
        <header className="pane-heading">
          <div>
            <span className="eyebrow">PHANTOM CHAT</span>
            <h1>消息</h1>
          </div>
          <IconButton
            icon="plus"
            label="发起新会话"
            variant="soft"
            onClick={() => setNewChatOpen(true)}
          />
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
            <button
              type="button"
              className={filter === 'all' ? 'is-active' : ''}
              onClick={() => setFilter('all')}
            >
              全部
            </button>
            <button
              type="button"
              className={filter === 'unread' ? 'is-active' : ''}
              onClick={() => setFilter('unread')}
            >
              未读
              {unreadTotal > 0 && <span className="segment-count">{unreadTotal}</span>}
            </button>
          </div>
          <button type="button" className="toolbar-icon" aria-label="更多会话操作">
            <Icon name="more" size={19} />
          </button>
        </div>

        <div className="conversation-list">
          {filteredConversations.length === 0 ? (
            <EmptyState title="没有找到相关会话" description="换个关键词或查看全部消息" />
          ) : (
            filteredConversations.map((conversation) => {
              const isActive = conversation.id === active.id
              return (
                <button
                  type="button"
                  key={conversation.id}
                  className={`conversation-item ${isActive ? 'is-active' : ''}`}
                  onClick={() => selectConversation(conversation.id)}
                  aria-current={isActive ? 'page' : undefined}
                >
                  <Avatar
                    chars={conversation.avatarChars}
                    tone={conversation.tone}
                    size={52}
                    group={conversation.kind === 'group'}
                    online={conversation.online}
                  />
                  <span className="conversation-copy">
                    <span className="conversation-line">
                      <strong>{conversation.name}</strong>
                      <time>{conversation.time}</time>
                    </span>
                    <span className="conversation-line conversation-line--bottom">
                      <span className="conversation-preview">{conversation.lastMessage}</span>
                      <span className="conversation-meta">
                        {conversation.muted && <Icon name="bellOff" size={13} />}
                        {conversation.pinned && <Icon name="pin" size={12} />}
                        {conversation.unread > 0 && (
                          <span className="unread-badge">
                            {conversation.unread > 99 ? '99+' : conversation.unread}
                          </span>
                        )}
                      </span>
                    </span>
                  </span>
                </button>
              )
            })
          )}
        </div>

        <div className="list-footer-note">
          <span className="online-pulse" />
          <span>已连接 Phantom 服务 · 消息实时同步</span>
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
              online={active.online}
            />
            <div>
              <div className="chat-title-line">
                <h2>{active.name}</h2>
                {active.kind === 'group' && <span className="soft-badge">群组</span>}
              </div>
              <p>
                {active.kind === 'group'
                  ? `${active.members} 位成员 · 5 人在线`
                  : active.online
                    ? '在线'
                    : active.subtitle}
              </p>
            </div>
          </div>
          <div className="chat-header-actions">
            <IconButton icon="phone" label="语音通话" />
            <IconButton icon="video" label="视频通话" />
            <span className="header-divider" />
            <IconButton icon="search" label="在聊天中搜索" />
            <IconButton icon="more" label="聊天设置" />
          </div>
        </header>

        <div className="message-canvas">
          <div className="message-scroll">
            <div className="date-divider">
              <span>今天</span>
            </div>

            <div className="message-helper">
              <span className="helper-icon">
                <Icon name="shield" size={14} />
              </span>
              会话内容仅参与者可见
            </div>

            {active.messages.map((message) => (
              <MessageBubble key={message.id} message={message} group={active.kind === 'group'} />
            ))}
            <div ref={messageEndRef} />
          </div>
        </div>

        <footer className="composer">
          <div className="composer-toolbar">
            <IconButton
              icon="smile"
              label="添加表情"
              onClick={() => setDraft((current) => `${current}😊`)}
            />
            <IconButton icon="image" label="发送图片" onClick={() => sendMessage('image')} />
            <IconButton icon="paperclip" label="发送文件" onClick={() => sendMessage('file')} />
            <span className="composer-hint">Enter 发送 · Shift + Enter 换行</span>
          </div>
          <div className="composer-input">
            <textarea
              value={draft}
              rows={2}
              placeholder={`发送消息给 ${active.name}…`}
              aria-label="消息内容"
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                  event.preventDefault()
                  sendMessage()
                }
              }}
            />
            <button
              type="button"
              className="send-button"
              disabled={!draft.trim()}
              onClick={() => sendMessage()}
            >
              <span>发送</span>
              <Icon name="send" size={18} />
            </button>
          </div>
        </footer>
      </section>

      <Modal
        open={newChatOpen}
        title="发起新会话"
        description="选择一个最近联系人开始聊天"
        onClose={() => setNewChatOpen(false)}
        width={430}
      >
        <div className="new-chat-list">
          {conversations
            .filter((conversation) => conversation.kind === 'direct')
            .map((conversation) => (
              <button
                type="button"
                key={conversation.id}
                className="new-chat-row"
                onClick={() => {
                  selectConversation(conversation.id)
                  setNewChatOpen(false)
                }}
              >
                <Avatar
                  chars={conversation.avatarChars}
                  tone={conversation.tone}
                  size={42}
                  online={conversation.online}
                />
                <span>
                  <strong>{conversation.name}</strong>
                  <small>{conversation.subtitle}</small>
                </span>
                <Icon name="chevronRight" size={17} />
              </button>
            ))}
        </div>
      </Modal>
    </div>
  )
}

function MessageBubble({ message, group }: { message: ChatMessage; group: boolean }) {
  const mine = message.from === 'me'

  return (
    <div className={`message-row ${mine ? 'message-row--mine' : ''}`}>
      {!mine && <Avatar chars={[message.author?.slice(-1) ?? '友']} tone="sage" size={34} />}
      <div className="message-content">
        {!mine && group && message.author && <span className="message-author">{message.author}</span>}
        <div className={`message-bubble ${mine ? 'message-bubble--mine' : ''}`}>
          {message.text && <p>{message.text}</p>}
          {message.kind === 'image' && message.attachment && (
            <div className="image-attachment">
              <div className="image-art">
                <span className="image-art-glow" />
                <span className="image-art-card">
                  <span />
                  <span />
                  <span />
                </span>
                <span className="image-art-sun" />
              </div>
              <div className="attachment-caption">
                <strong>{message.attachment.title}</strong>
                <small>{message.attachment.meta}</small>
              </div>
            </div>
          )}
          {message.kind === 'file' && message.attachment && (
            <div className="file-attachment">
              <span className="file-icon">
                <Icon name="file" size={22} />
              </span>
              <span>
                <strong>{message.attachment.title}</strong>
                <small>{message.attachment.meta}</small>
              </span>
              <span className="file-download">
                <Icon name="chevronDown" size={17} />
              </span>
            </div>
          )}
        </div>
        <span className="message-time">{message.time}</span>
      </div>
    </div>
  )
}
