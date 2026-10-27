import { describe, expect, it, vi } from 'vitest'
import { BffApiError } from '../api/bff'
import type { LoginInput, PhantomToken, RuntimeConfig, SessionAccount } from '../api/types'
import { AppBootMachine, type BootBff } from './boot-machine'

const runtime: RuntimeConfig = {
  appId: 'app_test',
  wsUrl: 'ws://test/ws',
  proxyBaseUrl: '/api/phantom',
  testMode: false,
}
const account: SessionAccount = {
  accountId: 'a1',
  phantomUserId: 'u1',
  loginName: 'alice',
  nickname: 'Alice',
}
const token: PhantomToken = {
  userToken: 'token',
  phantomUserId: 'u1',
  appId: 'app_test',
  expiresAt: Date.now() + 60_000,
  expiresInSeconds: 60,
}

function bff(overrides: Partial<BootBff> = {}): BootBff {
  return {
    runtimeConfig: vi.fn(async () => runtime),
    me: vi.fn(async () => account),
    login: vi.fn(async (_input: LoginInput) => account),
    phantomToken: vi.fn(async () => token),
    ...overrides,
  }
}

const unauthorized = new BffApiError({
  message: '未登录',
  code: 2003,
  status: 401,
  unauthorized: true,
})

describe('AppBootMachine', () => {
  it('无有效 Session 时进入未登录且不连接 SDK', async () => {
    const connect = vi.fn()
    const machine = new AppBootMachine({
      bff: bff({ me: vi.fn(async () => { throw unauthorized }) }),
      connect,
    })

    await machine.start()

    expect(machine.state.phase).toBe('unauthenticated')
    expect(connect).not.toHaveBeenCalled()
  })

  it('有效 Session 时获取 Token、连接 SDK 并进入 ready', async () => {
    const connect = vi.fn(async () => ({ id: 'conn' }))
    const service = bff()
    const machine = new AppBootMachine({ bff: service, connect })

    await machine.start()

    expect(machine.state).toMatchObject({ phase: 'ready', account, runtime })
    expect(service.phantomToken).toHaveBeenCalledOnce()
    expect(connect).toHaveBeenCalledWith(expect.objectContaining({ runtime, account, token }))
  })

  it('BFF 不可用时进入可重试错误', async () => {
    const machine = new AppBootMachine({
      bff: bff({ runtimeConfig: vi.fn(async () => { throw new Error('offline') }) }),
      connect: vi.fn(),
    })

    await machine.start()

    expect(machine.state).toMatchObject({ phase: 'error', retryable: true })
  })

  it('失败后重试可恢复到未登录', async () => {
    const runtimeConfig = vi.fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(runtime)
    const machine = new AppBootMachine({
      bff: bff({
        runtimeConfig,
        me: vi.fn(async () => { throw unauthorized }),
      }),
      connect: vi.fn(),
    })

    await machine.start()
    expect(machine.state.phase).toBe('error')
    await machine.retry()
    expect(runtimeConfig).toHaveBeenCalledTimes(2)
    expect(machine.state.phase).toBe('unauthenticated')
  })

  it('登录成功后建立连接', async () => {
    const connect = vi.fn(async () => ({ id: 'conn' }))
    const service = bff()
    const machine = new AppBootMachine({ bff: service, connect })

    await machine.login({ loginName: 'alice', password: 'password1' })

    expect(service.login).toHaveBeenCalledOnce()
    expect(machine.state.phase).toBe('ready')
  })
})
