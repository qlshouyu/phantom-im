import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PhantomGateway } from '../phantom/gateway'
import { useUnreadBadge } from './useUnreadBadge'

afterEach(() => vi.useRealTimers())

function fakeGateway() {
  const handlers = new Map<string, Set<() => void>>()
  const gateway = {
    listConversationPage: vi.fn(),
    on: vi.fn((event: string, handler: () => void) => {
      const eventHandlers = handlers.get(event) ?? new Set<() => void>()
      eventHandlers.add(handler)
      handlers.set(event, eventHandlers)
      return () => eventHandlers.delete(handler)
    }),
  }
  return {
    gateway: gateway as unknown as PhantomGateway,
    emit(event: string) {
      for (const handler of handlers.get(event) ?? []) handler()
    },
  }
}

describe('useUnreadBadge', () => {
  it('登录后加载权威汇总，并合并实时事件刷新', async () => {
    vi.useFakeTimers()
    const fake = fakeGateway()
    fake.gateway.listConversationPage = vi.fn()
      .mockResolvedValueOnce({ badgeCount: 4 })
      .mockResolvedValueOnce({ badgeCount: 6 })

    const { result } = renderHook(() => useUnreadBadge(fake.gateway))
    await act(async () => { await Promise.resolve() })
    expect(result.current.unreadTotal).toBe(4)

    act(() => {
      fake.emit('message')
      fake.emit('sync')
    })
    await act(async () => {
      vi.advanceTimersByTime(150)
      await Promise.resolve()
    })

    expect(result.current.unreadTotal).toBe(6)
    expect(fake.gateway.listConversationPage).toHaveBeenCalledTimes(2)
  })

  it('打开会话后先扣减，再以服务端权威值校准', async () => {
    vi.useFakeTimers()
    const fake = fakeGateway()
    fake.gateway.listConversationPage = vi.fn()
      .mockResolvedValueOnce({ badgeCount: 8 })
      .mockResolvedValueOnce({ badgeCount: 3 })

    const { result } = renderHook(() => useUnreadBadge(fake.gateway))
    await act(async () => { await Promise.resolve() })
    act(() => result.current.markConversationRead(5))
    expect(result.current.unreadTotal).toBe(3)

    await act(async () => {
      vi.advanceTimersByTime(150)
      await Promise.resolve()
    })
    expect(result.current.unreadTotal).toBe(3)
  })

  it('切换登录态时清理监听和待执行刷新', async () => {
    vi.useFakeTimers()
    const fake = fakeGateway()
    fake.gateway.listConversationPage = vi.fn().mockResolvedValue({ badgeCount: 2 })

    const { result, rerender, unmount } = renderHook(
      ({ gateway }) => useUnreadBadge(gateway),
      { initialProps: { gateway: fake.gateway as PhantomGateway | undefined } },
    )
    await act(async () => { await Promise.resolve() })
    expect(result.current.unreadTotal).toBe(2)

    rerender({ gateway: undefined })
    expect(result.current.unreadTotal).toBe(0)
    act(() => fake.emit('message'))
    await act(async () => {
      vi.advanceTimersByTime(150)
      await Promise.resolve()
    })
    expect(fake.gateway.listConversationPage).toHaveBeenCalledTimes(1)
    unmount()
  })
})
