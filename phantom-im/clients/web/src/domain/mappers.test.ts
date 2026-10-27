import { describe, expect, it } from 'vitest'
import type { ChatMessage as SdkMessage } from '@phantom/sdk-web'
import {
  mapChatMessage,
  mapConversation,
  mapFriend,
  mapGroup,
  mapSearchResult,
  mergeDomainMessages,
} from './mappers'

const now = new Date('2026-10-27T10:24:00+08:00').getTime()

describe('领域映射', () => {
  it('完整映射会话快照字段并保持置顶排序所需时间', () => {
    const conversation = mapConversation({
      conv_id: 'gr_1',
      conv_type: 'GROUP',
      target_id: 'group_1',
      title: '产品设计组',
      last_message_digest: '交互稿已更新',
      last_message_at: now,
      unread_count: 2,
      mention_me: true,
      pinned: true,
      muted: true,
      draft: '继续讨论',
      read_only: true,
      max_conv_seq: 88,
      read_conv_seq: 86,
    })

    expect(conversation).toMatchObject({
      id: 'gr_1',
      targetId: 'group_1',
      name: '产品设计组',
      kind: 'group',
      lastMessage: '交互稿已更新',
      unread: 2,
      mentionMe: true,
      pinned: true,
      muted: true,
      draft: '继续讨论',
      readOnly: true,
      maxConvSeq: 88,
      readConvSeq: 86,
    })
    expect(conversation.time).toBe('10:24')
  })

  it('映射消息方向、撤回状态与媒体附件', () => {
    const raw = {
      msgId: 'm1',
      clientMsgId: 'c1',
      convId: 'si_a__b',
      convType: 'SINGLE',
      senderId: 'u2',
      kind: 'IMAGE',
      content: { image: { media_id: 'media_1', fileName: 'photo.jpg', mimeType: 'image/jpeg', sizeBytes: 2048, width: 640, height: 480 } },
      text: '',
      convSeq: 3,
      serverTime: now,
      state: 'sent',
      recalled: false,
      restricted: false,
      isRequest: false,
      mentions: [],
      burnAfterReadTtlSeconds: 0,
    } as unknown as SdkMessage

    const message = mapChatMessage(raw, 'u1', '沈屿')

    expect(message).toMatchObject({
      id: 'm1',
      from: 'them',
      kind: 'image',
      author: '沈屿',
      convSeq: 3,
      state: 'sent',
      attachment: { title: 'photo.jpg', mediaId: 'media_1', meta: 'image/jpeg · 2.0 KB', width: 640, height: 480 },
    })
  })

  it('历史消息与实时乱序消息按 conv_seq 合并且按 msg_id 去重', () => {
    const make = (id: string, seq: number, state: 'sent' | 'read') => mapChatMessage({
      msgId: id,
      clientMsgId: `c-${id}`,
      convId: 'c1',
      convType: 'SINGLE',
      senderId: 'u2',
      kind: 'TEXT',
      content: { text: { text: id } },
      text: id,
      convSeq: seq,
      serverTime: now + seq,
      state,
      recalled: false,
      restricted: false,
      isRequest: false,
      mentions: [],
      burnAfterReadTtlSeconds: 0,
    } as unknown as SdkMessage, 'u1')

    const merged = mergeDomainMessages(
      [make('m2', 2, 'sent'), make('m4', 4, 'sent')],
      [make('m3', 3, 'sent'), make('m2', 2, 'read')],
    )

    expect(merged.map((message) => [message.id, message.state])).toEqual([
      ['m2', 'read'],
      ['m3', 'sent'],
      ['m4', 'sent'],
    ])
  })

  it('映射好友、群组与分类搜索结果', () => {
    const friend = mapFriend({
      user_id: 'u2',
      nickname: '沈屿',
      display_name: '沈屿（好友）',
      remark: '看展搭子',
      pinned: true,
      group_ids: ['g1'],
    })
    expect(friend).toMatchObject({ id: 'u2', name: '沈屿（好友）', remark: '看展搭子', pinned: true, groupIds: ['g1'] })

    const group = mapGroup({
      summary: { group_id: 'gr1', conv_id: 'gr_1', name: '设计组', member_count: 8, owner_id: 'u1' },
      announcement: { content: '周五评审' },
      member_invite_enabled: true,
    })
    expect(group).toMatchObject({ id: 'gr1', convId: 'gr_1', name: '设计组', memberCount: 8, announcement: '周五评审' })

    const result = mapSearchResult({
      contacts: [{ user_id: 'u2', nickname: '沈屿' }],
      groups: [{ group_id: 'gr1', conv_id: 'gr_1', name: '设计组' }],
      messages: [{ msg_id: 'm1', conv_id: 'gr_1', conv_title: '设计组', digest: '评审时间', conv_seq: 4 }],
      contact_total: 1,
      group_total: 1,
      message_total: 1,
    })
    expect(result.contacts[0].nickname).toBe('沈屿')
    expect(result.groups[0].convId).toBe('gr_1')
    expect(result.messages[0].convSeq).toBe(4)
  })
})
