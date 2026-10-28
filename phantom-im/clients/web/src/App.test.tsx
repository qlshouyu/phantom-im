import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'

const gatewayMock = vi.hoisted(() => {
  const handlers = new Map<string, Set<(...args: any[]) => void>>()
  const gateway = {
    currentUserId: 'u1',
    listConversationPage: vi.fn(),
    on: vi.fn((event: string, handler: (...args: any[]) => void) => {
      const eventHandlers = handlers.get(event) ?? new Set<(...args: any[]) => void>()
      eventHandlers.add(handler)
      handlers.set(event, eventHandlers)
      return () => eventHandlers.delete(handler)
    }),
  }
  return {
    gateway,
    emit(event: string, ...args: any[]) {
      for (const handler of handlers.get(event) ?? []) handler(...args)
    },
    reset() {
      handlers.clear()
      gateway.listConversationPage.mockReset()
      gateway.on.mockClear()
    },
  }
})

vi.mock('./app/AppProvider', () => ({
  useApp: () => ({
    state: {
      phase: 'ready',
      account: {
        accountId: 'a1',
        phantomUserId: 'u1',
        loginName: 'tester',
        nickname: '测试用户',
      },
      retryable: false,
    },
    login: vi.fn(),
    register: vi.fn(),
    retry: vi.fn(),
    logout: vi.fn(),
    dismissKicked: vi.fn(),
    adapter: { state: 'online' },
    gateway: gatewayMock.gateway,
  }),
}))

vi.mock('./views/ChatsView', () => ({
  default: () => <div data-testid="chats-view">聊天页</div>,
}))

vi.mock('./views/ContactsView', () => ({
  default: () => <div data-testid="contacts-view">通讯录页</div>,
}))

vi.mock('./views/ProfileView', () => ({
  default: () => <div data-testid="profile-view">个人中心页</div>,
}))

describe('App 导航未读角标', () => {
  beforeEach(() => gatewayMock.reset())

  it('切换到通讯录后仍接收实时 badge_count 更新', async () => {
    gatewayMock.gateway.listConversationPage.mockResolvedValue({ badgeCount: 5 })
    render(<App />)

    await waitFor(() => expect(screen.getByLabelText('未读消息：5')).toHaveTextContent('5'))
    fireEvent.click(screen.getByRole('button', { name: '通讯录' }))
    expect(screen.getByTestId('contacts-view')).toBeVisible()

    gatewayMock.gateway.listConversationPage.mockResolvedValue({ badgeCount: 9 })
    act(() => gatewayMock.emit('message', { senderId: 'u2', convId: 'si_u1__u2' }))
    await waitFor(() => expect(screen.getByLabelText('未读消息：9')).toHaveTextContent('9'))
  })
})
