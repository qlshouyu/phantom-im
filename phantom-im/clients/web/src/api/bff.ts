import type {
  AccountLookup,
  ApiEnvelope,
  LoginInput,
  PhantomToken,
  RegisterResult,
  RegistrationInput,
  RequestOptions,
  RuntimeConfig,
  SessionAccount,
} from './types'
import { writeDiagnosticLog } from '../platform/diagnostics'

export class BffApiError extends Error {
  readonly code: number
  readonly status: number
  readonly requestId?: string
  readonly fields: Record<string, string>
  readonly unauthorized: boolean
  readonly network: boolean
  readonly invalidResponse: boolean

  constructor(input: {
    message: string
    code?: number
    status?: number
    requestId?: string
    fields?: Record<string, string>
    unauthorized?: boolean
    network?: boolean
    invalidResponse?: boolean
  }) {
    super(input.message)
    this.name = 'BffApiError'
    this.code = input.code ?? 0
    this.status = input.status ?? 0
    this.requestId = input.requestId
    this.fields = input.fields ?? {}
    this.unauthorized = input.unauthorized ?? false
    this.network = input.network ?? false
    this.invalidResponse = input.invalidResponse ?? false
  }
}

type FetchLike = typeof fetch

interface ClientOptions {
  fetch?: FetchLike
  baseUrl?: string
  onSessionInvalid?: () => void
}

interface RawRuntimeConfig {
  app_id?: string
  appId?: string
  ws_url?: string
  wsUrl?: string
  proxy_base_url?: string
  proxyBaseUrl?: string
  test_mode?: boolean
  testMode?: boolean
}

interface RawSessionAccount {
  account_id?: string
  accountId?: string
  phantom_user_id?: string
  phantomUserId?: string
  login_name?: string
  loginName?: string
  nickname?: string
}

interface RawAccountLookup {
  phantom_user_id?: string
  phantomUserId?: string
  login_name?: string
  loginName?: string
  nickname?: string
}

interface RawRegisterResult {
  account_id?: string
  accountId?: string
  phantom_user_id?: string
  phantomUserId?: string
  status?: string
}

interface RawPhantomToken {
  user_token?: string
  userToken?: string
  phantom_user_id?: string
  phantomUserId?: string
  app_id?: string
  appId?: string
  expires_at?: number
  expiresAt?: number
  expires_in_seconds?: number
  expiresInSeconds?: number
}

export class BffClient {
  private readonly fetchImpl: FetchLike
  private readonly baseUrl: string
  private readonly onSessionInvalid?: () => void
  private requestId: string | undefined

  constructor(options: ClientOptions = {}) {
    this.fetchImpl = options.fetch ?? globalThis.fetch.bind(globalThis)
    this.baseUrl = (options.baseUrl ?? '').replace(/\/$/, '')
    this.onSessionInvalid = options.onSessionInvalid
  }

  get lastRequestId(): string | undefined {
    return this.requestId
  }

  async runtimeConfig(options: RequestOptions = {}): Promise<RuntimeConfig> {
    const raw = await this.request<RawRuntimeConfig>('GET', '/api/runtime-config', undefined, options)
    return {
      appId: required(raw.app_id ?? raw.appId, 'runtime-config.app_id'),
      wsUrl: required(raw.ws_url ?? raw.wsUrl, 'runtime-config.ws_url'),
      proxyBaseUrl: raw.proxy_base_url ?? raw.proxyBaseUrl ?? '/api/phantom',
      testMode: Boolean(raw.test_mode ?? raw.testMode),
    }
  }

  async register(input: RegistrationInput, options: RequestOptions = {}): Promise<RegisterResult> {
    const raw = await this.request<RawRegisterResult>('POST', '/api/auth/register', {
      nickname: input.nickname,
      login_name: input.loginName,
      password: input.password,
      password_confirm: input.passwordConfirm,
    }, options)
    return {
      accountId: required(raw.account_id ?? raw.accountId, 'register.account_id'),
      phantomUserId: required(raw.phantom_user_id ?? raw.phantomUserId, 'register.phantom_user_id'),
      status: raw.status ?? 'ACTIVE',
    }
  }

  async login(input: LoginInput, options: RequestOptions = {}): Promise<SessionAccount> {
    const raw = await this.request<RawSessionAccount>('POST', '/api/auth/login', {
      login_name: input.loginName,
      password: input.password,
    }, options)
    return mapSessionAccount(raw)
  }

  async me(options: RequestOptions = {}): Promise<SessionAccount> {
    const raw = await this.request<RawSessionAccount>('GET', '/api/auth/me', undefined, {
      ...options,
      expireSessionOn401: true,
    })
    return mapSessionAccount(raw)
  }

  async lookupAccount(identifier: string, options: RequestOptions = {}): Promise<AccountLookup> {
    const query = encodeURIComponent(identifier.trim())
    const raw = await this.request<RawAccountLookup>('GET', `/api/accounts/lookup?identifier=${query}`,
      undefined, {
        ...options,
        expireSessionOn401: true,
      })
    return {
      phantomUserId: required(raw.phantom_user_id ?? raw.phantomUserId, 'account.phantom_user_id'),
      loginName: required(raw.login_name ?? raw.loginName, 'account.login_name'),
      nickname: raw.nickname ?? '',
    }
  }

  async phantomToken(options: RequestOptions = {}): Promise<PhantomToken> {
    const raw = await this.request<RawPhantomToken>('POST', '/api/auth/phantom-token', undefined, {
      ...options,
      expireSessionOn401: true,
    })
    return {
      userToken: required(raw.user_token ?? raw.userToken, 'phantom-token.user_token'),
      phantomUserId: required(raw.phantom_user_id ?? raw.phantomUserId,
        'phantom-token.phantom_user_id'),
      appId: required(raw.app_id ?? raw.appId, 'phantom-token.app_id'),
      expiresAt: Number(raw.expires_at ?? raw.expiresAt ?? 0),
      expiresInSeconds: Number(raw.expires_in_seconds ?? raw.expiresInSeconds ?? 0),
    }
  }

  async issueExpiredToken(options: RequestOptions = {}): Promise<PhantomToken> {
    const raw = await this.request<RawPhantomToken>('POST',
      '/api/auth/phantom-token/test-expired', undefined, {
        ...options,
        expireSessionOn401: true,
      })
    return {
      userToken: required(raw.user_token ?? raw.userToken, 'expired-token.user_token'),
      phantomUserId: required(raw.phantom_user_id ?? raw.phantomUserId,
        'expired-token.phantom_user_id'),
      appId: required(raw.app_id ?? raw.appId, 'expired-token.app_id'),
      expiresAt: Number(raw.expires_at ?? raw.expiresAt ?? 0),
      expiresInSeconds: Number(raw.expires_in_seconds ?? raw.expiresInSeconds ?? 0),
    }
  }

  async logout(options: RequestOptions = {}): Promise<void> {
    await this.request<null>('POST', '/api/auth/logout', undefined, options)
  }

  private async request<T>(
    method: string,
    path: string,
    body?: Record<string, unknown> | null,
    options: RequestOptions = {},
  ): Promise<T> {
    let response: Response
    try {
      response = await this.fetchImpl(`${this.baseUrl}${path}`, {
        method,
        credentials: 'include',
        headers: {
          Accept: 'application/json',
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: options.signal,
      })
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') throw error
      writeDiagnosticLog('error', `BFF 网络失败 method=${method} path=${diagnosticPath(path)} code=9001`)
      throw new BffApiError({
        message: '无法连接 Easy IM BFF',
        network: true,
        code: 9001,
      })
    }

    const headerRequestId = response.headers.get('X-Request-Id') ?? undefined
    let payload: ApiEnvelope<T> | null = null
    const text = response.status === 204 ? '' : await response.text()
    if (text) {
      try {
        payload = JSON.parse(text) as ApiEnvelope<T>
      } catch {
        const requestId = headerRequestId
        this.requestId = requestId
        writeDiagnosticLog(
          'error',
          `BFF 响应格式错误 method=${method} path=${diagnosticPath(path)} status=${response.status} request_id=${requestId ?? '-'}`,
        )
        throw new BffApiError({
          message: 'BFF 返回了无效 JSON',
          status: response.status,
          requestId,
          unauthorized: response.status === 401,
          invalidResponse: true,
        })
      }
    }

    const requestId = payload?.request_id ?? headerRequestId
    this.requestId = requestId
    const code = Number(payload?.code ?? (response.ok ? 0 : response.status))
    const unauthorized = response.status === 401 || code === 2003
    if (!response.ok || code !== 0) {
      writeDiagnosticLog(
        response.status >= 500 ? 'error' : 'warn',
        `BFF 请求失败 method=${method} path=${diagnosticPath(path)} status=${response.status} code=${code} request_id=${requestId ?? '-'} message=${payload?.message ?? '-'}`,
      )
      if (unauthorized && options.expireSessionOn401) this.onSessionInvalid?.()
      throw new BffApiError({
        message: payload?.message || `BFF 请求失败（HTTP ${response.status}）`,
        code,
        status: response.status,
        requestId,
        fields: fieldErrors(payload?.data),
        unauthorized,
      })
    }
    if (payload === null) {
      throw new BffApiError({
        message: 'BFF 响应缺少 JSON 数据',
        status: response.status,
        requestId,
        invalidResponse: true,
      })
    }
    return payload.data
  }
}

function diagnosticPath(path: string): string {
  return path.split('?', 1)[0] || '/'
}

function mapSessionAccount(raw: RawSessionAccount): SessionAccount {
  return {
    accountId: required(raw.account_id ?? raw.accountId, 'auth.account_id'),
    phantomUserId: required(raw.phantom_user_id ?? raw.phantomUserId, 'auth.phantom_user_id'),
    loginName: required(raw.login_name ?? raw.loginName, 'auth.login_name'),
    nickname: raw.nickname ?? '',
  }
}

function fieldErrors(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  return Object.fromEntries(
    Object.entries(value)
      .filter((entry): entry is [string, string] => typeof entry[1] === 'string')
      .map(([key, message]) => [toCamelCase(key), message]),
  )
}

function toCamelCase(value: string): string {
  return value.replace(/_([a-z])/g, (_, letter: string) => letter.toUpperCase())
}

function required(value: string | undefined, field: string): string {
  if (!value) {
    throw new BffApiError({
      message: `BFF 响应缺少字段 ${field}`,
      invalidResponse: true,
    })
  }
  return value
}

export function createBffClient(options: ClientOptions = {}): BffClient {
  return new BffClient(options)
}
