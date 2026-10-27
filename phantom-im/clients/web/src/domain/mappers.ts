import type { ChatMessage as SdkMessage, MessageKind as SdkMessageKind } from '@phantom/sdk-web'
import type {
  Attachment,
  AvatarTone,
  ChatMessage,
  Conversation,
  DeviceInfo,
  Friend,
  FriendGroup,
  FriendRequest,
  Group,
  GroupMember,
  MessageKind,
  MessageState,
  PushSettings,
  QrCard,
  SearchResult,
  UserProfile,
} from './types'

const TONES: AvatarTone[] = ['mint', 'sky', 'coral', 'amber', 'violet', 'rose', 'sage', 'ocean']

export function toneFor(value: string): AvatarTone {
  let hash = 0
  for (const char of value) hash = ((hash << 5) - hash + char.charCodeAt(0)) | 0
  return TONES[Math.abs(hash) % TONES.length]
}

export function avatarCharsFor(value: string, group = false): string[] {
  const compact = value.trim()
  if (!compact) return [group ? '群' : '友']
  return group
    ? [compact[0], compact[1] ?? '群', compact[2] ?? '聊', compact[3] ?? '组']
    : [compact.slice(-1)]
}

export function formatMessageTime(timestamp: number, now = Date.now()): string {
  if (!timestamp) return ''
  const date = new Date(timestamp)
  const current = new Date(now)
  const start = new Date(current.getFullYear(), current.getMonth(), current.getDate()).getTime()
  const target = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
  const clock = date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  if (target === start) return clock
  if (target === start - 86_400_000) return `昨天 ${clock}`
  const diff = Math.floor((start - target) / 86_400_000)
  if (diff > 1 && diff < 7) {
    return `${date.toLocaleDateString('zh-CN', { weekday: 'short' })} ${clock}`
  }
  return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}

export function mapConversation(raw: any): Conversation {
  const id = String(raw.conv_id ?? raw.convId ?? '')
  const title = String(raw.title ?? raw.name ?? raw.target_id ?? '未命名会话')
  const kind = String(raw.conv_type ?? raw.convType) === 'GROUP' ? 'group' : 'direct'
  return {
    id,
    targetId: raw.target_id ?? raw.targetId,
    name: title,
    kind,
    subtitle: kind === 'group' ? '群组会话' : '单聊',
    lastMessage: String(raw.last_message_digest ?? raw.lastMessageDigest ?? ''),
    time: formatMessageTime(Number(raw.last_message_at ?? raw.lastMessageAt ?? 0)),
    timestamp: Number(raw.last_message_at ?? raw.lastMessageAt ?? 0),
    unread: Number(raw.unread_count ?? raw.unreadCount ?? 0),
    pinned: Boolean(raw.pinned),
    muted: Boolean(raw.muted),
    mentionMe: Boolean(raw.mention_me ?? raw.mentionMe),
    draft: String(raw.draft ?? ''),
    readOnly: Boolean(raw.read_only ?? raw.readOnly),
    maxConvSeq: Number(raw.max_conv_seq ?? raw.maxConvSeq ?? 0),
    readConvSeq: Number(raw.read_conv_seq ?? raw.readConvSeq ?? 0),
    online: false,
    tone: toneFor(id || title),
    avatarChars: avatarCharsFor(title, kind === 'group'),
    avatarUrl: raw.avatar_url ?? raw.avatarUrl,
  }
}

export function mapChatMessage(
  raw: SdkMessage,
  currentUserId: string,
  author?: string,
): ChatMessage {
  const kind = normalizeMessageKind(raw.kind)
  const state = normalizeMessageState(raw.state)
  return {
    id: raw.msgId || raw.clientMsgId,
    clientMsgId: raw.clientMsgId,
    convId: raw.convId,
    senderId: raw.senderId,
    from: kind === 'system' ? 'system' : raw.senderId === currentUserId ? 'me' : 'them',
    author,
    text: raw.text,
    time: formatMessageTime(raw.serverTime),
    serverTime: raw.serverTime,
    convSeq: raw.convSeq,
    kind,
    state,
    attachment: attachmentFor(raw),
    replyToMsgId: raw.replyToMsgId,
    replyDigest: raw.replySnapshot?.digest,
    recalled: raw.recalled || state === 'recalled',
    restricted: raw.restricted,
  }
}

export function mapFriend(raw: any): Friend {
  const ext = raw.ext ?? {}
  return {
    id: String(raw.user_id ?? raw.userId ?? ''),
    name: String(raw.display_name ?? raw.displayName ?? raw.remark ?? raw.nickname ?? raw.userId ?? '好友'),
    nickname: String(raw.nickname ?? ''),
    remark: raw.remark ?? undefined,
    pinned: Boolean(raw.pinned),
    groupIds: raw.group_ids ?? raw.groupIds ?? [],
    avatarUrl: raw.avatar_url ?? raw.avatarUrl,
    title: ext.title,
    location: ext.location,
    signature: ext.signature,
    email: ext.email,
    phone: ext.phone,
  }
}

export function mapFriendRequest(raw: any): FriendRequest {
  const peer = raw.peer ?? {}
  return {
    id: String(raw.request_id ?? raw.requestId ?? ''),
    fromUserId: String(raw.from_user_id ?? raw.fromUserId ?? ''),
    toUserId: String(raw.to_user_id ?? raw.toUserId ?? ''),
    peerId: String(peer.user_id ?? peer.userId ?? raw.from_user_id ?? ''),
    peerName: String(peer.nickname ?? peer.userId ?? '用户'),
    verificationMessage: String(raw.verification_message ?? raw.verificationMessage ?? ''),
    status: String(raw.status ?? 'PENDING'),
    direction: String(raw.direction ?? 'INCOMING'),
    createdAt: Number(raw.created_at ?? raw.createdAt ?? 0),
    unread: Boolean(raw.unread),
  }
}

export function mapFriendGroup(raw: any): FriendGroup {
  return {
    id: String(raw.group_id ?? raw.groupId ?? ''),
    name: String(raw.name ?? '未命名分组'),
    memberCount: Number(raw.member_count ?? raw.memberCount ?? 0),
  }
}

export function mapGroup(raw: any): Group {
  const summary = raw.summary ?? raw
  const announcement = raw.announcement
  const name = String(summary.name ?? '未命名群组')
  return {
    id: String(summary.group_id ?? summary.groupId ?? ''),
    convId: String(summary.conv_id ?? summary.convId ?? ''),
    name,
    avatarUrl: summary.avatar_url ?? summary.avatarUrl,
    memberCount: Number(summary.member_count ?? summary.memberCount ?? 0),
    ownerId: String(summary.owner_id ?? summary.ownerId ?? ''),
    status: String(summary.status ?? 'ACTIVE'),
    announcement: typeof announcement === 'string'
      ? announcement
      : String(announcement?.content ?? ''),
    memberInviteEnabled: Boolean(raw.member_invite_enabled ?? raw.memberInviteEnabled ?? true),
    tone: toneFor(name),
    avatarChars: avatarCharsFor(name, true),
  }
}

export function mapGroupMember(raw: any, profile?: UserProfile): GroupMember {
  return {
    groupId: String(raw.group_id ?? raw.groupId ?? ''),
    userId: String(raw.user_id ?? raw.userId ?? ''),
    name: profile?.name ?? String(raw.group_nickname ?? raw.groupNickname ?? raw.user_id ?? '成员'),
    role: String(raw.role ?? 'MEMBER'),
    groupNickname: raw.group_nickname ?? raw.groupNickname ?? undefined,
    mutedUntil: raw.muted_until ?? raw.mutedUntil ?? undefined,
    mutedAll: Boolean(raw.muted_all ?? raw.mutedAll),
    avatarUrl: profile?.avatarUrl,
  }
}

export function mapUserProfile(raw: any, fallbackHandle = ''): UserProfile {
  const ext = raw.ext ?? {}
  return {
    id: String(raw.user_id ?? raw.userId ?? ''),
    name: String(raw.nickname ?? '未设置昵称'),
    handle: String(ext.handle ?? fallbackHandle),
    avatarUrl: raw.avatar_url ?? raw.avatarUrl,
    signature: String(ext.signature ?? ''),
    location: String(ext.location ?? ''),
    email: String(ext.email ?? ''),
    phone: String(ext.phone ?? ''),
    title: String(ext.title ?? ''),
    ext,
  }
}

export function mapQrCard(raw: any): QrCard {
  return {
    cardId: String(raw.card_id ?? raw.cardId ?? ''),
    token: String(raw.token ?? ''),
    cardUrl: String(raw.card_url ?? raw.cardUrl ?? ''),
    expiresAt: raw.expires_at ?? raw.expiresAt ?? undefined,
  }
}

export function mapDevice(raw: any): DeviceInfo {
  return {
    deviceId: String(raw.device_id ?? raw.deviceId ?? ''),
    deviceType: Number(raw.device_type ?? raw.deviceType ?? 0),
    platform: String(raw.platform ?? ''),
    deviceName: String(raw.device_name ?? raw.deviceName ?? ''),
    loginAt: Number(raw.login_at ?? raw.loginAt ?? 0),
    lastActiveAt: Number(raw.last_active_at ?? raw.lastActiveAt ?? 0),
    location: String(raw.location ?? ''),
    foreground: Boolean(raw.foreground),
  }
}

export function mapPushSettings(raw: any): PushSettings {
  return {
    pushEnabled: Boolean(raw.push_enabled ?? raw.pushEnabled ?? true),
    previewEnabled: Boolean(raw.preview_enabled ?? raw.previewEnabled ?? true),
    privacyPolicyUrl: String(raw.privacy_policy_url ?? raw.privacyPolicyUrl ?? ''),
    termsUrl: String(raw.terms_url ?? raw.termsUrl ?? ''),
  }
}

export function mapSearchResult(raw: any): SearchResult {
  const contacts = (raw.contacts ?? []).map((item: any) => ({
    userId: String(item.user_id ?? item.userId ?? ''),
    nickname: String(item.nickname ?? item.userId ?? '用户'),
    remark: item.remark,
    avatarUrl: item.avatar_url ?? item.avatarUrl,
  }))
  const groups = (raw.groups ?? []).map((item: any) => ({
    groupId: String(item.group_id ?? item.groupId ?? ''),
    convId: String(item.conv_id ?? item.convId ?? ''),
    name: String(item.name ?? '群组'),
    avatarUrl: item.avatar_url ?? item.avatarUrl,
  }))
  const messages = (raw.messages ?? []).map((item: any) => ({
    msgId: String(item.msg_id ?? item.msgId ?? ''),
    convId: String(item.conv_id ?? item.convId ?? ''),
    convTitle: String(item.conv_title ?? item.convTitle ?? '会话'),
    senderId: String(item.sender_id ?? item.senderId ?? ''),
    digest: String(item.digest ?? ''),
    convSeq: Number(item.conv_seq ?? item.convSeq ?? 0),
    serverTime: Number(item.server_time ?? item.serverTime ?? 0),
    recalled: Boolean(item.recalled),
    burned: Boolean(item.burned),
  }))
  return {
    contacts,
    groups,
    messages,
    contactTotal: Number(raw.contact_total ?? raw.contactTotal ?? contacts.length),
    groupTotal: Number(raw.group_total ?? raw.groupTotal ?? groups.length),
    messageTotal: Number(raw.message_total ?? raw.messageTotal ?? messages.length),
  }
}

function normalizeMessageKind(kind: SdkMessageKind): MessageKind {
  const value = String(kind).toUpperCase()
  if (value === 'IMAGE') return 'image'
  if (value === 'VOICE') return 'voice'
  if (value === 'VIDEO') return 'video'
  if (value === 'FILE') return 'file'
  if (value === 'SYSTEM') return 'system'
  return 'text'
}

function normalizeMessageState(state: SdkMessage['state']): MessageState {
  return state as MessageState
}

function attachmentFor(raw: SdkMessage): Attachment | undefined {
  const content: any = raw.content ?? {}
  const media = content.image ?? content.file ?? content.video ?? content.voice
  if (!media) return undefined
  const kind = String(raw.kind).toUpperCase()
  const title = media.fileName ?? media.file_name ?? (kind === 'IMAGE' ? '图片' : kind === 'FILE' ? '文件' : kind === 'VOICE' ? '语音' : '视频')
  const size = Number(media.sizeBytes ?? media.size_bytes ?? 0)
  const width = Number(media.width ?? 0)
  const height = Number(media.height ?? 0)
  const meta = [
    media.mimeType ?? media.mime_type ?? kind,
    size > 0 ? formatBytes(size) : '',
  ].filter(Boolean).join(' · ')
  return {
    title: String(title),
    meta,
    url: media.url,
    thumbUrl: media.thumbUrl ?? media.thumb_url,
    mediaId: media.mediaId ?? media.media_id,
    width: width > 0 ? width : undefined,
    height: height > 0 ? height : undefined,
  }
}

function formatBytes(value: number): string {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}


export function mergeDomainMessages(...lists: ChatMessage[][]): ChatMessage[] {
  const byKey = new Map<string, ChatMessage>()
  for (const message of lists.flat()) {
    const key = message.id || message.clientMsgId
    const existing = byKey.get(key)
    byKey.set(key, existing ? { ...existing, ...message } : message)
  }
  return [...byKey.values()].sort((left, right) => {
    const leftPending = !Number.isFinite(left.convSeq) || left.convSeq <= 0
    const rightPending = !Number.isFinite(right.convSeq) || right.convSeq <= 0
    if (leftPending !== rightPending) return leftPending ? 1 : -1
    return (left.convSeq || 0) - (right.convSeq || 0)
      || left.serverTime - right.serverTime
      || (left.clientMsgId || left.id).localeCompare(right.clientMsgId || right.id)
  })
}
