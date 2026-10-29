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

const appMock = vi.hoisted(() => ({
  state: {
    phase: 'ready',
    account: {
      accountId: 'a1',
      phantomUserId: 'u1',
      loginName: 'tester',
      nickname: '测试用户',
    },
    retryable: false,
  } as any,
  retry: vi.fn(),
}))

vi.mock('./app/AppProvider', () => ({
  useApp: () => ({
    state: appMock.state,
    login: vi.fn(),
    register: vi.fn(),
    retry: appMock.retry,
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
  beforeEach(() => {
    gatewayMock.reset()
    appMock.retry.mockReset()
    appMock.state = {
      phase: 'ready',
      account: {
        accountId: 'a1',
        phantomUserId: 'u1',
        loginName: 'tester',
        nickname: '测试用户',
      },
      retryable: false,
    }
  })

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

  it('连接错误页重试时立即显示进行中并禁用按钮', async () => {
    let finishRetry!: (value: any) => void
    appMock.state = {
      phase: 'error',
      account: undefined,
      error: new Error('UserToken 已过期'),
      retryable: true,
    }
    appMock.retry.mockImplementation(() => new Promise((resolve) => {
      finishRetry = resolve
    }))
    gatewayMock.gateway.listConversationPage.mockResolvedValue({ badgeCount: 0 })
    render(<App />)

    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    const retrying = screen.getByRole('button', { name: '正在重试…' })
    expect(retrying).toBeDisabled()

    await act(async () => finishRetry(appMock.state))
    await waitFor(() => expect(screen.getByRole('button', { name: '重试' })).toBeEnabled())
  })

  it('连接错误页展示错误码和 request ID 便于诊断', () => {
    const error = Object.assign(new Error('UserToken 已过期'), {
      code: 2001,
      requestId: 'request-2001',
    })
    appMock.state = {
      phase: 'error',
      account: undefined,
      error,
      retryable: true,
    }
    gatewayMock.gateway.listConversationPage.mockResolvedValue({ badgeCount: 0 })

    render(<App />)

    expect(screen.getByText('UserToken 已过期（错误码 2001 · request_id request-2001）')).toBeVisible()
  })
})
