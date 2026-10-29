import { BffApiError } from '../api/bff'
import type {
  LoginInput,
  PhantomToken,
  RuntimeConfig,
  SessionAccount,
} from '../api/types'

export type AppPhase =
  | 'bootstrapping'
  | 'unauthenticated'
  | 'connecting'
  | 'syncing'
  | 'ready'
  | 'error'
  | 'kicked'

export interface BootSnapshot {
  phase: AppPhase
  runtime?: RuntimeConfig
  account?: SessionAccount
  error?: unknown
  reason?: string
  retryable: boolean
}

export interface BootBff {
  runtimeConfig(): Promise<RuntimeConfig>
  me(): Promise<SessionAccount>
  login(input: LoginInput): Promise<SessionAccount>
  phantomToken(): Promise<PhantomToken>
}

export interface BootConnectionContext {
  runtime: RuntimeConfig
  account: SessionAccount
  token: PhantomToken
  onConnectionState?: (state: string) => void
}

export type BootConnector<TConnection> = (
  context: BootConnectionContext,
) => Promise<TConnection>

interface BootMachineOptions<TConnection> {
  bff: BootBff
  connect: BootConnector<TConnection>
}

/**
 * 应用启动状态机：
 * bootstrapping -> unauthenticated
 * bootstrapping -> connecting -> syncing -> ready
 * 任意恢复/连接错误 -> error（可重试）
 */
export class AppBootMachine<TConnection = unknown> {
  private readonly bff: BootBff
  private readonly connect: BootConnector<TConnection>
  private listeners = new Set<(snapshot: BootSnapshot) => void>()
  private snapshot: BootSnapshot = { phase: 'bootstrapping', retryable: false }
  private running?: Promise<BootSnapshot>
  private connection?: TConnection
  private attemptVersion = 0

  constructor(options: BootMachineOptions<TConnection>) {
    this.bff = options.bff
    this.connect = options.connect
  }

  get state(): BootSnapshot {
    return this.snapshot
  }

  get currentConnection(): TConnection | undefined {
    return this.connection
  }

  subscribe(listener: (snapshot: BootSnapshot) => void): () => void {
    this.listeners.add(listener)
    listener(this.snapshot)
    return () => this.listeners.delete(listener)
  }

  start(): Promise<BootSnapshot> {
    if (this.running) return this.running
    const attempt = ++this.attemptVersion
    const task = this.restore(attempt)
    const running = task.finally(() => {
      if (this.running === running) this.running = undefined
    })
    this.running = running
    return running
  }

  retry(): Promise<BootSnapshot> {
    if (this.running) return this.running
    this.connection = undefined
    return this.start()
  }

  async login(input: LoginInput): Promise<BootSnapshot> {
    const attempt = ++this.attemptVersion
    // 登录失败必须留在当前登录表单，并把异常交回表单展示。
    this.update({ error: undefined, reason: undefined, retryable: false })
    let runtime: RuntimeConfig
    let account: SessionAccount
    try {
      runtime = this.snapshot.runtime ?? await this.bff.runtimeConfig()
      account = await this.bff.login(input)
    } catch (error) {
      this.update({
        phase: 'unauthenticated',
        account: undefined,
        error,
        retryable: false,
      })
      throw error
    }
    try {
      return await this.connectAccount(runtime, account, attempt)
    } catch (error) {
      const unauthorized = isUnauthorized(error)
      if (attempt !== this.attemptVersion) return this.snapshot
      this.update({
        phase: unauthorized ? 'unauthenticated' : 'error',
        error,
        retryable: !unauthorized,
      })
      throw error
    }
  }

  async logout(logoutRemote?: () => Promise<void>): Promise<void> {
    this.attemptVersion += 1
    try {
      await logoutRemote?.()
    } finally {
      this.connection = undefined
      this.update({
        phase: 'unauthenticated',
        account: undefined,
        error: undefined,
        reason: undefined,
        retryable: false,
      })
    }
  }

  sessionExpired(error?: unknown): void {
    this.attemptVersion += 1
    this.connection = undefined
    this.update({
      phase: 'unauthenticated',
      account: undefined,
      error,
      reason: '登录状态已失效，请重新登录',
      retryable: false,
    })
  }

  kicked(reason: string): void {
    this.attemptVersion += 1
    this.connection = undefined
    this.update({
      phase: 'kicked',
      reason,
      retryable: false,
    })
  }

  dismissKicked(): void {
    this.attemptVersion += 1
    this.connection = undefined
    this.update({
      phase: 'unauthenticated',
      account: undefined,
      reason: '当前设备已被顶替，请重新登录',
      retryable: false,
    })
  }

  private async restore(attempt: number): Promise<BootSnapshot> {
    if (attempt !== this.attemptVersion) return this.snapshot
    this.update({ phase: 'bootstrapping', error: undefined, reason: undefined, retryable: false })
    try {
      const runtime = await this.bff.runtimeConfig()
      if (attempt !== this.attemptVersion) return this.snapshot
      this.update({ runtime })
      let account: SessionAccount
      try {
        account = await this.bff.me()
      } catch (error) {
        if (attempt !== this.attemptVersion) return this.snapshot
        if (isUnauthorized(error)) {
          this.update({ phase: 'unauthenticated', account: undefined, retryable: false })
          return this.snapshot
        }
        throw error
      }
      return await this.connectAccount(runtime, account, attempt)
    } catch (error) {
      if (attempt !== this.attemptVersion) return this.snapshot
      if (isUnauthorized(error)) {
        this.update({
          phase: 'unauthenticated',
          account: undefined,
          error,
          reason: '登录状态已失效，请重新登录',
          retryable: false,
        })
        return this.snapshot
      }
      this.update({
        phase: 'error',
        error,
        retryable: true,
      })
      return this.snapshot
    }
  }

  private async connectAccount(
    runtime: RuntimeConfig,
    account: SessionAccount,
    attempt: number,
  ): Promise<BootSnapshot> {
    if (attempt !== this.attemptVersion) return this.snapshot
    this.update({ phase: 'connecting', runtime, account, error: undefined, retryable: false })
    const token = await this.bff.phantomToken()
    if (attempt !== this.attemptVersion) return this.snapshot
    this.update({ phase: 'syncing' })
    const connection = await this.connect({
      runtime,
      account,
      token,
      onConnectionState: (connectionState) => {
        if (attempt !== this.attemptVersion) return
        if (connectionState === 'reconnecting') this.update({ phase: 'connecting' })
        if (connectionState === 'online' && this.connection) {
          this.update({ phase: 'ready', retryable: false })
        }
      },
    })
    if (attempt !== this.attemptVersion) return this.snapshot
    this.connection = connection
    this.update({ phase: 'ready', account, runtime, retryable: false })
    return this.snapshot
  }

  private update(patch: Partial<BootSnapshot>): void {
    this.snapshot = { ...this.snapshot, ...patch }
    for (const listener of this.listeners) listener(this.snapshot)
  }
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof BffApiError && error.unauthorized
}
