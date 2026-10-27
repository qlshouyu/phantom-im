export type AvatarTone = 'mint' | 'coral' | 'sky' | 'violet' | 'amber' | 'rose' | 'ocean' | 'sage'
export type MessageKind = 'text' | 'image' | 'file' | 'link' | 'voice' | 'video' | 'system'
export type MessageState = 'sending' | 'sent' | 'delivered' | 'read' | 'failed' | 'recalled' | 'burned'

export interface Attachment {
  title: string
  meta: string
  url?: string
  thumbUrl?: string
  mediaId?: string
  width?: number
  height?: number
}

export interface ChatMessage {
  id: string
  clientMsgId: string
  convId: string
  senderId: string
  from: 'me' | 'them' | 'system'
  author?: string
  text: string
  time: string
  serverTime: number
  convSeq: number
  kind: MessageKind
  state: MessageState
  attachment?: Attachment
  replyToMsgId?: string
  replyDigest?: string
  recalled: boolean
  restricted: boolean
}

export interface Conversation {
  id: string
  targetId?: string
  name: string
  kind: 'direct' | 'group'
  subtitle: string
  lastMessage: string
  time: string
  timestamp: number
  unread: number
  pinned: boolean
  muted: boolean
  mentionMe: boolean
  draft: string
  readOnly: boolean
  maxConvSeq: number
  readConvSeq: number
  online: boolean
  members?: number
  tone: AvatarTone
  avatarChars: string[]
  avatarUrl?: string
}

export interface Friend {
  id: string
  name: string
  nickname: string
  remark?: string
  pinned: boolean
  groupIds: string[]
  avatarUrl?: string
  title?: string
  location?: string
  signature?: string
  email?: string
  phone?: string
}

export interface FriendRequest {
  id: string
  fromUserId: string
  toUserId: string
  peerId: string
  peerName: string
  verificationMessage: string
  status: string
  direction: string
  createdAt: number
  unread: boolean
}

export interface FriendGroup {
  id: string
  name: string
  memberCount: number
}

export interface Group {
  id: string
  convId: string
  name: string
  avatarUrl?: string
  memberCount: number
  ownerId: string
  status: string
  announcement: string
  memberInviteEnabled: boolean
  tone: AvatarTone
  avatarChars: string[]
}

export interface GroupMember {
  groupId: string
  userId: string
  name: string
  role: string
  groupNickname?: string
  mutedUntil?: number
  mutedAll: boolean
  avatarUrl?: string
}

export interface UserProfile {
  id: string
  name: string
  handle: string
  avatarUrl?: string
  signature: string
  location: string
  email: string
  phone: string
  title: string
  ext: Record<string, string>
}

export interface FriendCandidate {
  phantomUserId: string
  nickname: string
  loginName: string
  avatarUrl?: string
}

export interface QrCard {
  cardId: string
  token: string
  cardUrl: string
  expiresAt?: number
}

export interface DeviceInfo {
  deviceId: string
  deviceType: number
  platform: string
  deviceName: string
  loginAt: number
  lastActiveAt: number
  location: string
  foreground: boolean
}

export interface PushSettings {
  pushEnabled: boolean
  previewEnabled: boolean
  privacyPolicyUrl: string
  termsUrl: string
}

export interface SearchContactHit {
  userId: string
  nickname: string
  remark?: string
  avatarUrl?: string
}

export interface SearchGroupHit {
  groupId: string
  convId: string
  name: string
  avatarUrl?: string
}

export interface SearchMessageHit {
  msgId: string
  convId: string
  convTitle: string
  senderId: string
  digest: string
  convSeq: number
  serverTime: number
  recalled: boolean
  burned: boolean
}

export interface SearchResult {
  contacts: SearchContactHit[]
  groups: SearchGroupHit[]
  messages: SearchMessageHit[]
  contactTotal: number
  groupTotal: number
  messageTotal: number
}

export interface PresenceState {
  userId: string
  online: boolean
  lastSeenAt?: number
}

export interface TypingState {
  convId: string
  userId: string
  typing: boolean
}
