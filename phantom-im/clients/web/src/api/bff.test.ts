import { describe, expect, it, vi } from 'vitest'
import { BffApiError, BffClient } from './bff'

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

describe('BffClient', () => {
  it('携带 Cookie 并解包成功响应和 request_id', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({
      code: 0,
      message: 'ok',
      data: {
        account_id: 'a1',
        phantom_user_id: 'u1',
        login_name: 'alice',
        nickname: 'Alice',
      },
      request_id: 'req-1',
    }))
    const client = new BffClient({ fetch: fetchMock as typeof fetch })

    const account = await client.login({ loginName: 'alice', password: 'password1' })

    expect(account).toEqual({
      accountId: 'a1',
      phantomUserId: 'u1',
      loginName: 'alice',
      nickname: 'Alice',
    })
    expect(client.lastRequestId).toBe('req-1')
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({
      credentials: 'include',
      method: 'POST',
      body: JSON.stringify({ login_name: 'alice', password: 'password1' }),
    }))
  })

  it('将业务字段错误映射为 camelCase 并保留 request_id', async () => {
    const client = new BffClient({
      fetch: vi.fn(async () => jsonResponse({
        code: 1001,
        message: '注册字段不合法',
        data: { login_name: '登录名已存在' },
        request_id: 'req-field',
      }, 400)) as typeof fetch,
    })

    await expect(client.register({
      nickname: 'Alice',
      loginName: 'alice',
      password: 'password1',
      passwordConfirm: 'password1',
    })).rejects.toMatchObject({
      code: 1001,
      status: 400,
      requestId: 'req-field',
      fields: { loginName: '登录名已存在' },
    })
  })

  it('按登录名或 Phantom ID 解析待添加用户', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({
      code: 0,
      message: 'ok',
      data: {
        phantom_user_id: 'u_bob',
        login_name: 'bob_1',
        nickname: 'Bob',
      },
      request_id: 'req-lookup',
    }))
    const client = new BffClient({ fetch: fetchMock as typeof fetch })

    await expect(client.lookupAccount(' Bob 1 ')).resolves.toEqual({
      phantomUserId: 'u_bob',
      loginName: 'bob_1',
      nickname: 'Bob',
    })
    expect(fetchMock).toHaveBeenCalledWith('/api/accounts/lookup?identifier=Bob%201',
      expect.objectContaining({ credentials: 'include', method: 'GET' }))
  })

  it('仅在 Session 敏感请求的 401 上触发失效回调', async () => {
    const onSessionInvalid = vi.fn()
    const client = new BffClient({
      fetch: vi.fn(async () => jsonResponse({
        code: 2003,
        message: '未登录或会话已失效',
        data: null,
        request_id: 'req-401',
      }, 401)) as typeof fetch,
      onSessionInvalid,
    })

    await expect(client.login({ loginName: 'alice', password: 'wrong' }))
      .rejects.toBeInstanceOf(BffApiError)
    expect(onSessionInvalid).not.toHaveBeenCalled()

    await expect(client.me()).rejects.toMatchObject({
      unauthorized: true,
      requestId: 'req-401',
    })
    expect(onSessionInvalid).toHaveBeenCalledOnce()
  })

  it('无效 JSON 返回可诊断错误', async () => {
    const client = new BffClient({
      fetch: vi.fn(async () => new Response('<html>bad gateway</html>', {
        status: 502,
        headers: { 'X-Request-Id': 'req-json' },
      })) as typeof fetch,
    })

    await expect(client.runtimeConfig()).rejects.toMatchObject({
      status: 502,
      requestId: 'req-json',
      invalidResponse: true,
    })
  })

  it('网络失败返回统一的网络错误', async () => {
    const client = new BffClient({
      fetch: vi.fn(async () => { throw new TypeError('fetch failed') }) as typeof fetch,
    })

    await expect(client.runtimeConfig()).rejects.toMatchObject({
      network: true,
      code: 9001,
    })
  })
})
