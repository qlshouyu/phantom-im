import type {
  ChatMessage as SdkChatMessage,
  MediaUploadOptions,
  PhantomClient,
  UploadSession,
} from '@phantom/sdk-web'
import {
  mapChatMessage,
  mapConversation,
  mapDevice,
  mapFriend,
  mapFriendGroup,
  mapFriendRequest,
  mapGroup,
  mapGroupMember,
  mapPushSettings,
  mapQrCard,
  mapSearchResult,
  mapUserProfile,
  mergeDomainMessages,
} from '../domain/mappers'
import type {
  ChatMessage,
  Conversation,
  DeviceInfo,
  Friend,
  FriendGroup,
  FriendRequest,
  Group,
  GroupMember,
  PushSettings,
  QrCard,
  SearchResult,
  UserProfile,
} from '../domain/types'

type EventHandler = (...args: any[]) => void

export interface ConversationPageSnapshot {
  conversations: Conversation[]
  badgeCount: number
  maxUserSeq: number
  hasMore: boolean
  nextCursor?: string
}

/**
 * Phantom SDK 的领域适配器。
 * 页面不直接处理 snake_case 快照、协议字段或 SDK 生命周期细节。
 */
export class PhantomGateway {
  private readonly client: PhantomClient
  private readonly names = new Map<string, string>()

  constructor(client: PhantomClient) {
    this.client = client
  }

  get currentUserId(): string {
    return this.client.getCurrentUserId() ?? ''
  }

  get state() {
    return this.client.getState()
  }

  on(event: string, handler: EventHandler): () => void {
    this.client.on(event as never, handler as never)
    return () => this.client.off(event as never, handler as never)
  }

  rememberUser(userId: string, name: string): void {
    if (userId && name) this.names.set(userId, name)
  }

  async listConversationPage(limit = 200): Promise<ConversationPageSnapshot> {
    const page = await this.client.listConversations(undefined, limit)
    const conversations = (page.items ?? []).map(mapConversation)
      .sort((left: Conversation, right: Conversation) =>
        Number(right.pinned) - Number(left.pinned) || right.timestamp - left.timestamp)
    return {
      conversations,
      badgeCount: Number(page.badge_count ?? page.badgeCount ?? 0),
      maxUserSeq: Number(page.max_user_seq ?? page.maxUserSeq ?? 0),
      hasMore: Boolean(page.has_more ?? page.hasMore),
      nextCursor: page.next_cursor ?? page.nextCursor ?? undefined,
    }
  }

  async listConversations(limit = 200): Promise<Conversation[]> {
    return (await this.listConversationPage(limit)).conversations
  }

  async getConversation(convId: string): Promise<Conversation> {
    return mapConversation(await this.client.getConversation(convId))
  }

  async history(
    convId: string,
    beforeConvSeq = 0,
    limit = 50,
  ): Promise<{ items: ChatMessage[]; messages: ChatMessage[]; hasMore: boolean }> {
    const page = await this.client.history(convId, beforeConvSeq, limit)
    return {
      items: page.map((item) => this.mapMessage(item as SdkChatMessage)),
      messages: this.messages(convId),
      hasMore: page.length >= limit,
    }
  }

  messages(convId: string): ChatMessage[] {
    return mergeDomainMessages(this.client.getMessages(convId).map((message) =>
      mapChatMessage(message, this.currentUserId, this.names.get(message.senderId))))
  }

  async sendText(convId: string, text: string, replyToMsgId?: string): Promise<ChatMessage> {
    const message = await this.client.sendMessage({
      convId,
      kind: 'TEXT',
      content: { text: { text } },
      replyToMsgId,
    })
    return this.mapMessage(message)
  }

  async resend(message: ChatMessage): Promise<ChatMessage> {
    const raw = this.client.getMessages(message.convId)
      .find((item) => item.clientMsgId === message.clientMsgId)
    if (!raw) throw new Error('找不到待重发消息')
    await this.client.resend(raw)
    return this.mapMessage(raw)
  }

  async recall(msgId: string): Promise<void> {
    await this.client.recall(msgId)
  }

  async forward(msgIds: string[], targetConvIds: string[]): Promise<void> {
    await this.client.forward(msgIds, targetConvIds)
  }

  async deleteLocal(msgId: string): Promise<void> {
    await this.client.deleteLocal(msgId)
  }

  async clearHistory(convId: string): Promise<void> {
    await this.client.clearHistory(convId)
  }

  async markRead(convId: string, readConvSeq: number): Promise<void> {
    await this.client.markRead(convId, readConvSeq)
  }

  async updateConversation(convId: string, patch: { pinned?: boolean; muted?: boolean }): Promise<void> {
    await this.client.updateConversation(convId, patch)
  }

  async deleteConversation(convId: string): Promise<void> {
    await this.client.deleteConversation(convId)
  }

  async saveDraft(convId: string, draft: string): Promise<void> {
    await this.client.saveDraft(convId, draft)
  }

  async clearDraft(convId: string): Promise<void> {
    await this.client.clearDraft(convId)
  }

  async uploadMedia(
    file: Blob,
    options: Omit<MediaUploadOptions, 'sizeBytes' | 'mimeType'> & {
      sizeBytes?: number
      mimeType?: string
    },
  ): Promise<UploadSession> {
    return this.client.uploadMedia(file, options)
  }

  async sendMedia(
    convId: string,
    kind: 'IMAGE' | 'VOICE' | 'VIDEO' | 'FILE',
    media: UploadSession,
    extra: Record<string, unknown> = {},
  ): Promise<ChatMessage> {
    return this.mapMessage(await this.client.sendMedia(convId, kind, media, extra))
  }

  async getMediaBlob(mediaId: string, variant: 'original' | 'thumb' = 'thumb'): Promise<Blob> {
    return this.client.getMediaContent(mediaId, variant)
  }

  async getUserProfile(userId?: string): Promise<UserProfile> {
    const raw = await this.client.getUserProfile(userId)
    const profile = mapUserProfile(raw)
    this.rememberUser(profile.id, profile.name)
    return profile
  }

  async updateProfile(profile: Partial<UserProfile>): Promise<UserProfile> {
    const current = await this.getUserProfile()
    const ext = {
      ...current.ext,
      ...(profile.signature === undefined ? {} : { signature: profile.signature }),
      ...(profile.location === undefined ? {} : { location: profile.location }),
      ...(profile.email === undefined ? {} : { email: profile.email }),
      ...(profile.phone === undefined ? {} : { phone: profile.phone }),
      ...(profile.title === undefined ? {} : { title: profile.title }),
      ...(profile.handle === undefined ? {} : { handle: profile.handle }),
    }
    const raw = await this.client.updateProfile({
      nickname: profile.name ?? current.name,
      avatar_url: profile.avatarUrl ?? current.avatarUrl,
      ext,
    })
    return mapUserProfile(raw, current.handle)
  }

  async listFriends(): Promise<Friend[]> {
    const friends = (await this.client.listFriends()).map(mapFriend)
    for (const friend of friends) this.rememberUser(friend.id, friend.name)
    return friends
  }

  async createFriendRequest(userId: string, message = ''): Promise<void> {
    await this.client.createFriendRequest(userId, message)
  }

  async listFriendRequests(includeHandled = true): Promise<{ items: FriendRequest[]; unreadCount: number }> {
    const raw = await this.client.listFriendRequests(includeHandled)
    return {
      items: (raw.items ?? []).map(mapFriendRequest),
      unreadCount: Number(raw.unread_count ?? raw.unreadCount ?? 0),
    }
  }

  async handleFriendRequest(
    requestId: string,
    action: 'ACCEPT' | 'REJECT' | 'IGNORE',
  ): Promise<void> {
    await this.client.handleFriendRequest(requestId, action)
  }

  async updateFriend(
    friendId: string,
    patch: { remark?: string | null; pinned?: boolean; groupIds?: string[] },
  ): Promise<Friend> {
    const raw = await this.client.updateFriend(friendId, {
      ...(patch.remark === undefined ? {} : { remark: patch.remark }),
      ...(patch.pinned === undefined ? {} : { pinned: patch.pinned }),
      ...(patch.groupIds === undefined ? {} : { group_ids: patch.groupIds }),
    })
    const friend = mapFriend(raw)
    this.rememberUser(friend.id, friend.name)
    return friend
  }

  async deleteFriend(friendId: string): Promise<void> {
    await this.client.deleteFriend(friendId)
  }

  async listFriendGroups(): Promise<FriendGroup[]> {
    return (await this.client.listFriendGroups()).map(mapFriendGroup)
  }

  async createFriendGroup(name: string): Promise<FriendGroup> {
    return mapFriendGroup(await this.client.createFriendGroup(name))
  }

  async renameFriendGroup(groupId: string, name: string): Promise<FriendGroup> {
    return mapFriendGroup(await this.client.renameFriendGroup(groupId, name))
  }

  async deleteFriendGroup(groupId: string): Promise<void> {
    await this.client.deleteFriendGroup(groupId)
  }

  async listBlacklist(): Promise<Friend[]> {
    return (await this.client.listBlacklist()).map(mapFriend)
  }

  async blockUser(userId: string): Promise<void> {
    await this.client.blockUser(userId)
  }

  async unblockUser(userId: string): Promise<void> {
    await this.client.unblockUser(userId)
  }

  async getQrCard(): Promise<QrCard> {
    return mapQrCard(await this.client.getQrCard())
  }

  async refreshQrCard(): Promise<QrCard> {
    return mapQrCard(await this.client.refreshQrCard())
  }

  async listGroups(): Promise<Group[]> {
    return (await this.client.listGroups()).map(mapGroup)
  }

  async getGroup(groupId: string): Promise<Group> {
    return mapGroup(await this.client.getGroup(groupId))
  }

  async createGroup(name: string, memberIds: string[]): Promise<Group> {
    return mapGroup(await this.client.createGroup(name, memberIds))
  }

  async listGroupMembers(groupId: string): Promise<GroupMember[]> {
    const rawMembers = await this.client.listGroupMembers(groupId)
    return Promise.all(rawMembers.map(async (raw) => {
      let profile: UserProfile | undefined
      try {
        profile = await this.getUserProfile(String(raw.user_id ?? raw.userId ?? ''))
      } catch {
        profile = undefined
      }
      return mapGroupMember(raw, profile)
    }))
  }

  async inviteGroupMembers(groupId: string, userIds: string[]): Promise<void> {
    await this.client.inviteGroupMembers(groupId, userIds)
  }

  async removeGroupMember(groupId: string, userId: string): Promise<void> {
    await this.client.removeGroupMember(groupId, userId)
  }

  async updateGroupRole(groupId: string, userId: string, role: 'ADMIN' | 'MEMBER'): Promise<void> {
    await this.client.updateGroupRole(groupId, userId, role)
  }

  async transferGroupOwner(groupId: string, userId: string): Promise<void> {
    await this.client.transferGroupOwner(groupId, userId)
  }

  async leaveGroup(groupId: string): Promise<void> {
    await this.client.leaveGroup(groupId)
  }

  async dissolveGroup(groupId: string): Promise<void> {
    await this.client.dissolveGroup(groupId)
  }

  async muteGroupMember(groupId: string, userId: string | null, durationSeconds: number | null): Promise<void> {
    await this.client.muteGroupMember(groupId, userId, durationSeconds)
  }

  async unmuteGroupMember(groupId: string, userId: string | null): Promise<void> {
    await this.client.unmuteGroupMember(groupId, userId)
  }

  async updateAnnouncement(groupId: string, content: string): Promise<string> {
    const raw = await this.client.updateAnnouncement(groupId, content)
    return String(raw.content ?? content)
  }

  async updateGroupNickname(groupId: string, userId: string, nickname: string): Promise<void> {
    await this.client.updateGroupNickname(groupId, userId, nickname)
  }

  async listDevices(): Promise<DeviceInfo[]> {
    return (await this.client.listDevices()).map(mapDevice)
  }

  async remoteLogout(deviceId: string): Promise<void> {
    await this.client.remoteLogout(deviceId)
  }

  async getPushSettings(): Promise<PushSettings> {
    return mapPushSettings(await this.client.getPushSettings())
  }

  async updatePushSettings(pushEnabled: boolean, previewEnabled: boolean): Promise<PushSettings> {
    return mapPushSettings(await this.client.updatePushSettings(pushEnabled, previewEnabled))
  }

  getNotificationPermission(): NotificationPermission | 'unsupported' {
    return this.client.getNotificationPermission()
  }

  async requestNotificationPermission(): Promise<NotificationPermission | 'unsupported'> {
    return this.client.requestNotificationPermission()
  }

  async search(query: string, limit = 20): Promise<SearchResult> {
    return mapSearchResult(await this.client.search(query, limit))
  }

  async searchSuggestions(query: string, limit = 10): Promise<SearchResult> {
    return mapSearchResult(await this.client.searchSuggestions(query, limit))
  }

  async searchConversation(convId: string, query: string, limit = 20) {
    const raw = await this.client.searchConversation(convId, query, limit)
    return {
      convId: String(raw.conv_id ?? raw.convId ?? convId),
      total: Number(raw.total ?? 0),
      messages: (raw.messages ?? []).map((item: any): import('../domain/types').SearchMessageHit => ({
        msgId: String(item.msg_id ?? item.msgId ?? ''),
        convId: String(item.conv_id ?? item.convId ?? convId),
        convTitle: String(item.conv_title ?? item.convTitle ?? ''),
        senderId: String(item.sender_id ?? item.senderId ?? ''),
        digest: String(item.digest ?? ''),
        convSeq: Number(item.conv_seq ?? item.convSeq ?? 0),
        serverTime: Number(item.server_time ?? item.serverTime ?? 0),
        recalled: Boolean(item.recalled),
        burned: Boolean(item.burned),
      })),
    }
  }

  async sendTyping(convId: string, typing: boolean): Promise<void> {
    await this.client.sendTyping(convId, typing)
  }

  async subscribePresence(userIds: string[]): Promise<void> {
    if (userIds.length > 0) await this.client.subscribePresence(userIds)
  }

  async unsubscribePresence(userIds: string[]): Promise<void> {
    if (userIds.length > 0) await this.client.unsubscribePresence(userIds)
  }

  private mapMessage(message: SdkChatMessage): ChatMessage {
    return mapChatMessage(message, this.currentUserId, this.names.get(message.senderId))
  }
}
