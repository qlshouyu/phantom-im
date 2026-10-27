import { PhantomClient } from '@phantom/sdk-web'
import type { PhantomToken, RuntimeConfig, SessionAccount } from '../api/types'
import type { PhantomTokenManager } from '../auth/token-manager'

export type ConnectionState = 'idle' | 'connecting' | 'authing' | 'online' | 'reconnecting' | 'closed'

export interface SdkClientLike {
  login(options: {
    userToken: string
    userId: string
    deviceId: string
    deviceType?: 'MOBILE' | 'DESKTOP' | 'WEB'
  }): Promise<void>
  logout(): void
  reconnect?(): void
  getState(): ConnectionState
  getCurrentUserId(): string | undefined
  on(event: string, handler: (...args: any[]) => void): unknown
  off?(event: string, handler: (...args: any[]) => void): unknown
  [key: string]: unknown
}

export interface SdkAdapterOptions {
  runtime: RuntimeConfig
  account: SessionAccount
  deviceId: string
  tokenManager: Pick<PhantomTokenManager, 'refresh' | 'clear'>
  token?: PhantomToken
  createClient?: (options: {
    url: string
    appId: string
    httpBaseUrl: string
  }) => SdkClientLike
  onStateChange?: (state: ConnectionState) => void
  onKicked?: (reason: string) => void
  onSessionExpired?: (error: unknown) => void
  onTokenRefreshed?: (token: PhantomToken) => void
  onDiagnostic?: (message: string, level?: 'info' | 'warn' | 'error') => void
}

/**
 * SDK 边界适配器：页面只依赖稳定的应用身份/状态，
 * SDK 构造与登录参数在此集中校验，便于回归测试。
 */
export class PhantomSdkAdapter {
  private readonly options: SdkAdapterOptions
  private client?: SdkClientLike
  private token?: PhantomToken
  private refreshInFlight?: Promise<PhantomToken>

  constructor(options: SdkAdapterOptions) {
    this.options = options
    this.token = options.token
  }

  get sdk(): SdkClientLike {
    if (!this.client) throw new Error('SDK 尚未连接')
    return this.client
  }

  get rawClient(): PhantomClient {
    return this.sdk as unknown as PhantomClient
  }

  get currentToken(): PhantomToken | undefined {
    return this.token
  }

  get state(): ConnectionState {
    return this.client?.getState() ?? 'idle'
  }

  async connect(token = this.token): Promise<SdkClientLike> {
    if (!token) throw new Error('缺少 Phantom UserToken')
    this.token = token
    this.options.onDiagnostic?.('SDK 登录请求已发起')
    if (!this.client) {
      const factory = this.options.createClient ?? ((input) => new PhantomClient(input) as unknown as SdkClientLike)
      this.client = factory({
        url: this.options.runtime.wsUrl,
        appId: this.options.runtime.appId,
        httpBaseUrl: '/api/phantom',
      })
      this.bindEvents()
    }
    await this.login(token)
    return this.client
  }

  async simulateTokenExpiry(expiredToken: PhantomToken): Promise<void> {
    this.client?.logout()
    this.client = undefined
    this.token = expiredToken
    this.options.onDiagnostic?.('诊断：使用过期 Token 发起 SDK 认证', 'warn')
    await this.connect(expiredToken).catch(() => {
      // tokenExpired 监听器会执行单飞刷新并重新登录。
    })
    if (this.refreshInFlight) await this.refreshInFlight
  }

  async reconnect(): Promise<void> {
    if (!this.token) throw new Error('缺少可重连的 Phantom UserToken')
    if (this.client?.reconnect) {
      this.client.reconnect()
      return
    }
    await this.login(this.token)
  }

  refreshToken(): Promise<PhantomToken> {
    if (this.refreshInFlight) return this.refreshInFlight
    this.refreshInFlight = this.options.tokenManager.refresh()
      .then(async (token) => {
        this.token = token
        this.options.onTokenRefreshed?.(token)
        if (this.client) await this.login(token)
        this.options.onDiagnostic?.('Phantom Token 刷新成功')
        return token
      })
      .catch((error) => {
        this.options.onDiagnostic?.('Phantom Token 刷新失败', 'error')
        this.options.onSessionExpired?.(error)
        throw error
      })
      .finally(() => {
        this.refreshInFlight = undefined
      })
    return this.refreshInFlight
  }

  logout(): void {
    this.client?.logout()
    this.client = undefined
    this.token = undefined
    this.refreshInFlight = undefined
    this.options.tokenManager.clear()
  }

  private async login(token: PhantomToken): Promise<void> {
    if (!this.client) throw new Error('SDK 尚未创建')
    await this.client.login({
      userToken: token.userToken,
      userId: this.options.account.phantomUserId,
      deviceId: this.options.deviceId,
      deviceType: 'WEB',
    })
  }

  private bindEvents(): void {
    if (!this.client) return
    this.client.on('statechange', (state: ConnectionState) => {
      this.options.onStateChange?.(state)
      this.options.onDiagnostic?.(`连接状态：${state}`, state === 'closed' ? 'warn' : 'info')
    })
    this.client.on('connected', () => this.options.onDiagnostic?.('SDK 连接已认证'))
    this.client.on('disconnected', () => this.options.onDiagnostic?.('SDK 连接已断开', 'warn'))
    this.client.on('tokenExpired', () => {
      this.options.onDiagnostic?.('SDK 报告 Phantom Token 过期', 'warn')
      void this.refreshToken().catch(() => { /* onSessionExpired 已通知应用 */ })
    })
    this.client.on('kicked', (reason: string) => {
      this.options.onDiagnostic?.(`当前设备被顶替：${reason}`, 'warn')
      this.options.onKicked?.(reason)
    })
  }
}
