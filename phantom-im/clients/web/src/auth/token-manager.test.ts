import { describe, expect, it, vi } from 'vitest'
import { BffApiError } from '../api/bff'
import { PhantomTokenManager } from './token-manager'

const token = {
  userToken: 'jwt-in-memory',
  phantomUserId: 'u1',
  appId: 'app1',
  expiresAt: Date.now() + 60_000,
  expiresInSeconds: 60,
}

describe('PhantomTokenManager', () => {
  it('并发 tokenExpired 仅触发一次刷新', async () => {
    let resolveRefresh!: (value: typeof token) => void
    const phantomToken = vi.fn(() => new Promise<typeof token>((resolve) => {
      resolveRefresh = resolve
    }))
    const manager = new PhantomTokenManager({ bff: { phantomToken } })

    const first = manager.refresh()
    const second = manager.refresh()
    expect(phantomToken).toHaveBeenCalledOnce()

    resolveRefresh(token)
    await expect(Promise.all([first, second])).resolves.toEqual([token, token])
    expect(manager.current).toEqual(token)
  })

  it('刷新失败回到登录状态且不写浏览器存储', async () => {
    const storage = new Map<string, string>()
    const setItem = vi.fn((key: string, value: string) => storage.set(key, value))
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem,
      removeItem: (key: string) => storage.delete(key),
      clear: () => storage.clear(),
      get length() { return storage.size },
    })
    const onRefreshFailed = vi.fn()
    const manager = new PhantomTokenManager({
      bff: {
        phantomToken: vi.fn(async () => {
          throw new BffApiError({
            message: 'Session 已失效',
            code: 2003,
            status: 401,
            unauthorized: true,
          })
        }),
      },
      onRefreshFailed,
    })

    await expect(manager.refresh()).rejects.toMatchObject({ unauthorized: true })
    expect(manager.current).toBeUndefined()
    expect(onRefreshFailed).toHaveBeenCalledOnce()
    expect(storage.size).toBe(0)
    expect(setItem).not.toHaveBeenCalled()
  })

  it('清理后不再暴露旧 Token', () => {
    const manager = new PhantomTokenManager({ bff: { phantomToken: vi.fn() } })
    manager.set(token)
    expect(manager.current?.userToken).toBe('jwt-in-memory')
    manager.clear()
    expect(manager.current).toBeUndefined()
  })
})
