export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  request_id?: string | null
}

export interface RuntimeConfig {
  appId: string
  wsUrl: string
  proxyBaseUrl: string
  testMode: boolean
}

export interface SessionAccount {
  accountId: string
  phantomUserId: string
  loginName: string
  nickname: string
}

export interface AccountLookup {
  phantomUserId: string
  loginName: string
  nickname: string
}

export interface RegisterResult {
  accountId: string
  phantomUserId: string
  status: string
}

export interface PhantomToken {
  userToken: string
  phantomUserId: string
  appId: string
  expiresAt: number
  expiresInSeconds: number
}

export interface LoginInput {
  loginName: string
  password: string
}

export interface RegistrationInput extends LoginInput {
  nickname: string
  passwordConfirm: string
}

export interface RequestOptions {
  signal?: AbortSignal
  expireSessionOn401?: boolean
}
