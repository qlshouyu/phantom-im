import { afterEach, describe, expect, it, vi } from 'vitest'
import { PhantomAuthError, type PhantomClient } from '@phantom/sdk-web'
import type { PhantomToken, RuntimeConfig, SessionAccount } from '../api/types'
import { PhantomSdkAdapter, type SdkClientLike } from './sdk-adapter'

type ClientOptions = ConstructorParameters<typeof PhantomClient>[0]

const runtime: RuntimeConfig = {
  appId: 'app_test',
  wsUrl: 'ws://127.0.0.1:28080/ws',
  proxyBaseUrl: '/api/phantom',
  testMode: true,
}
const account: SessionAccount = {
  accountId: 'account_1',
  phantomUserId: 'user_1',
  loginName: 'alice',
  nickname: 'Alice',
}
const token: PhantomToken = {
  userToken: 'token_1',
  phantomUserId: 'user_1',
  appId: 'app_test',
  expiresAt: Date.now() + 60_000,
  expiresInSeconds: 60,
}

function fakeClient() {
  const handlers = new Map<string, (...args: any[]) => void>()
  const login = vi.fn(async () => {})
  const client: SdkClientLike = {
    login,
    logout: vi.fn(),
    getState: vi.fn(() => 'online' as const),
    getCurrentUserId: vi.fn(() => 'user_1'),
    on: vi.fn((event: string, handler: (...args: any[]) => void) => {
      handlers.set(event, handler)
      return client
    }),
    off: vi.fn(),
  }
  return { client, login, handlers }
}

describe('PhantomSdkAdapter', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('使用运行时配置与同源代理构造 SDK，并传入正确的登录参数', async () => {
    const fake = fakeClient()
    const createClient = vi.fn((_options: ClientOptions) => fake.client)
    const adapter = new PhantomSdkAdapter({
      runtime,
      account,
      deviceId: 'device_1',
      token,
      tokenManager: { refresh: vi.fn(), clear: vi.fn() },
      createClient,
    })

    await adapter.connect()

    expect(createClient).toHaveBeenCalledWith({
      baseUrl: 'http://127.0.0.1:28080',
      appId: 'app_test',
      fetch: expect.any(Function),
    })
    expect(fake.login).toHaveBeenCalledWith({
      userToken: 'token_1',
      userId: 'user_1',
      deviceId: 'device_1',
      deviceType: 'WEB',
    })
  })

  it('HTTP 代理保留路径、签名参数、认证头、请求体和取消信号', async () => {
    const fake = fakeClient()
    let options!: ClientOptions
    const adapter = new PhantomSdkAdapter({
      runtime: { ...runtime, wsUrl: 'wss://platform.test/gateway/ws', proxyBaseUrl: '/api/phantom/' },
      account,
      deviceId: 'device_1',
      token,
      tokenManager: { refresh: vi.fn(), clear: vi.fn() },
      createClient: (input) => { options = input; return fake.client },
    })
    await adapter.connect()
    expect(options.baseUrl).toBe('https://platform.test/gateway')
    const fetchMock = vi.fn<typeof fetch>(async () => Response.json({ code: 0, data: {} }))
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()
    await options.fetch!(`${options.baseUrl}/media/media_1/content?signature=test`, {
      method: 'POST',
      headers: { Authorization: 'Bearer token_1', 'X-App-Id': runtime.appId },
      body: 'content',
      signal: controller.signal,
    })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/phantom/media/media_1/content?signature=test')
    expect(init?.method).toBe('POST')
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer token_1')
    expect(new Headers(init?.headers).get('X-App-Id')).toBe(runtime.appId)
    expect(init?.body).toBe('content')
    expect(init?.signal).toBe(controller.signal)
  })

  it('并发 tokenExpired 单飞刷新并用新 Token 重登录', async () => {
    const fake = fakeClient()
    const nextToken = { ...token, userToken: 'token_2' }
    let resolveRefresh!: (value: PhantomToken) => void
    const refresh = vi.fn(() => new Promise<PhantomToken>((resolve) => {
      resolveRefresh = resolve
    }))
    const adapter = new PhantomSdkAdapter({
      runtime,
      account,
      deviceId: 'device_1',
      token,
      tokenManager: { refresh, clear: vi.fn() },
      createClient: () => fake.client,
    })
    await adapter.connect()

    fake.handlers.get('tokenExpired')?.()
    fake.handlers.get('tokenExpired')?.()
    expect(refresh).toHaveBeenCalledOnce()
    resolveRefresh(nextToken)
    await vi.waitFor(() => expect(fake.login).toHaveBeenCalledTimes(2))
    expect(fake.login).toHaveBeenLastCalledWith(expect.objectContaining({
      userToken: 'token_2',
      userId: 'user_1',
    }))
  })

  it('首次连接遇到 Token 过期时等待刷新和重新登录完成', async () => {
    const fake = fakeClient()
    const nextToken = { ...token, userToken: 'token_2' }
    fake.login.mockImplementationOnce(async () => {
      fake.handlers.get('tokenExpired')?.()
      throw new PhantomAuthError(2001)
    })
    const refresh = vi.fn(async () => nextToken)
    const onSessionExpired = vi.fn()
    const adapter = new PhantomSdkAdapter({
      runtime,
      account,
      deviceId: 'device_1',
      token,
      tokenManager: { refresh, clear: vi.fn() },
      createClient: () => fake.client,
      onSessionExpired,
    })

    await expect(adapter.connect()).resolves.toBe(fake.client)
    expect(refresh).toHaveBeenCalledOnce()
    expect(fake.login).toHaveBeenCalledTimes(2)
    expect(fake.login).toHaveBeenLastCalledWith(expect.objectContaining({ userToken: 'token_2' }))
    expect(onSessionExpired).not.toHaveBeenCalled()
  })

  it('首次连接刷新失败时交给启动状态机分类，不提前通知会话失效', async () => {
    const fake = fakeClient()
    const refreshError = new Error('refresh failed')
    fake.login.mockImplementationOnce(async () => {
      fake.handlers.get('tokenExpired')?.()
      throw new PhantomAuthError(2001)
    })
    const onSessionExpired = vi.fn()
    const adapter = new PhantomSdkAdapter({
      runtime,
      account,
      deviceId: 'device_1',
      token,
      tokenManager: { refresh: vi.fn(async () => { throw refreshError }), clear: vi.fn() },
      createClient: () => fake.client,
      onSessionExpired,
    })

    await expect(adapter.connect()).rejects.toBe(refreshError)
    expect(onSessionExpired).not.toHaveBeenCalled()
  })

  it('刷新失败通知应用回到登录页', async () => {
    const fake = fakeClient()
    const error = new Error('session expired')
    const onSessionExpired = vi.fn()
    const adapter = new PhantomSdkAdapter({
      runtime,
      account,
      deviceId: 'device_1',
      token,
      tokenManager: { refresh: vi.fn(async () => { throw error }), clear: vi.fn() },
      createClient: () => fake.client,
      onSessionExpired,
    })
    await adapter.connect()

    await expect(adapter.refreshToken()).rejects.toBe(error)
    expect(onSessionExpired).toHaveBeenCalledWith(error)
  })

  it('退出时清理 SDK 与内存 Token', async () => {
    const fake = fakeClient()
    const clear = vi.fn()
    const adapter = new PhantomSdkAdapter({
      runtime,
      account,
      deviceId: 'device_1',
      token,
      tokenManager: { refresh: vi.fn(), clear },
      createClient: () => fake.client,
    })
    await adapter.connect()
    adapter.logout()
    expect(fake.client.logout).toHaveBeenCalledOnce()
    expect(clear).toHaveBeenCalledOnce()
  })
})
