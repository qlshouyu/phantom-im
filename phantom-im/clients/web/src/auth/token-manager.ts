import type { BffClient } from '../api/bff'
import { BffApiError } from '../api/bff'
import type { PhantomToken } from '../api/types'

interface TokenManagerOptions {
  bff: Pick<BffClient, 'phantomToken'>
  onRefreshFailed?: (error: unknown) => void
  onToken?: (token: PhantomToken) => void
}

/**
 * Phantom UserToken 只保存在 JavaScript 内存中。
 * 多个 tokenExpired 并发到达时共享同一个刷新 Promise。
 */
export class PhantomTokenManager {
  private readonly bff: Pick<BffClient, 'phantomToken'>
  private readonly onRefreshFailed?: (error: unknown) => void
  private readonly onToken?: (token: PhantomToken) => void
  private token?: PhantomToken
  private inFlight?: Promise<PhantomToken>

  constructor(options: TokenManagerOptions) {
    this.bff = options.bff
    this.onRefreshFailed = options.onRefreshFailed
    this.onToken = options.onToken
  }

  get current(): PhantomToken | undefined {
    return this.token
  }

  set(token: PhantomToken): void {
    this.token = token
    this.onToken?.(token)
  }

  refresh(): Promise<PhantomToken> {
    if (this.inFlight) return this.inFlight
    this.inFlight = this.bff.phantomToken()
      .then((token) => {
        this.set(token)
        return token
      })
      .catch((error) => {
        this.clear()
        if (error instanceof BffApiError && error.unauthorized) {
          this.onRefreshFailed?.(error)
        } else {
          this.onRefreshFailed?.(error)
        }
        throw error
      })
      .finally(() => {
        this.inFlight = undefined
      })
    return this.inFlight
  }

  clear(): void {
    this.token = undefined
  }
}
