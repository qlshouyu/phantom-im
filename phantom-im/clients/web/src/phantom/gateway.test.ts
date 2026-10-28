import type { PhantomClient } from '@phantom/sdk-web'
import { describe, expect, it, vi } from 'vitest'
import { PhantomGateway } from './gateway'

describe('PhantomGateway 会话分页', () => {
  it('保留服务端 badge_count 并兼容现有会话列表接口', async () => {
    const listConversations = vi.fn().mockResolvedValue({
      items: [{
        conv_id: 'si_u1__u2',
        conv_type: 'SINGLE',
        title: '测试会话',
        unread_count: 4,
        muted: true,
      }],
      badge_count: 7,
      max_user_seq: 12,
      has_more: true,
      next_cursor: 'next-page',
    })
    const client = { listConversations } as unknown as PhantomClient
    const gateway = new PhantomGateway(client)

    const page = await gateway.listConversationPage()
    expect(page).toMatchObject({
      badgeCount: 7,
      maxUserSeq: 12,
      hasMore: true,
      nextCursor: 'next-page',
    })
    expect(page.conversations[0]).toMatchObject({ id: 'si_u1__u2', unread: 4, muted: true })

    await expect(gateway.listConversations()).resolves.toEqual(page.conversations)
    expect(listConversations).toHaveBeenCalledTimes(2)
  })
})
