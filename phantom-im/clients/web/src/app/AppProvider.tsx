import {
  createContext,
  use,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react'
import { BffClient, createBffClient, type BffApiError } from '../api/bff'
import type { LoginInput, PhantomToken, RegistrationInput, RuntimeConfig } from '../api/types'
import { PhantomTokenManager } from '../auth/token-manager'
import { getOrCreateDeviceId } from '../phantom/device'
import { PhantomSdkAdapter } from '../phantom/sdk-adapter'
import { PhantomGateway } from '../phantom/gateway'
import {
  AppBootMachine,
  type BootConnectionContext,
  type BootSnapshot,
} from './boot-machine'

export interface DiagnosticEntry {
  id: number
  at: number
  level: 'info' | 'warn' | 'error'
  message: string
}

interface AppServices {
  bff: BffClient
  machine: AppBootMachine<PhantomSdkAdapter>
  adapter?: PhantomSdkAdapter
  gateway?: PhantomGateway
  connectionAttempt: number
  logs: DiagnosticEntry[]
  nextLogId: number
  onLogChanged?: () => void
}

export interface AppContextValue {
  state: BootSnapshot
  bff: BffClient
  adapter?: PhantomSdkAdapter
  gateway?: PhantomGateway
  runtime?: RuntimeConfig
  requestId?: string
  connectionState: string
  diagnostics: DiagnosticEntry[]
  login(input: LoginInput): Promise<BootSnapshot>
  register(input: RegistrationInput): Promise<void>
  retry(): Promise<BootSnapshot>
  logout(): Promise<void>
  dismissKicked(): void
  refreshToken(): Promise<PhantomToken>
  simulateTokenExpiry(): Promise<void>
  reconnect(): Promise<void>
}

const AppContext = createContext<AppContextValue | null>(null)

export function AppProvider({ children }: { children: ReactNode }) {
  const servicesRef = useRef<AppServices | null>(null)
  const startedRef = useRef(false)
  const [diagnosticVersion, setDiagnosticVersion] = useState(0)

  if (!servicesRef.current) {
    const services = {} as AppServices
    const bff = createBffClient({
      onSessionInvalid: () => {
        services.connectionAttempt += 1
        services.adapter?.logout()
        services.adapter = undefined
        services.gateway = undefined
        appendLog(services, 'BFF Session 已失效', 'warn')
        services.machine.sessionExpired(new Error('BFF Session 已失效'))
      },
    })
    const machine = new AppBootMachine<PhantomSdkAdapter>({
      bff,
      connect: async (context) => connectServices(services, context),
    })
    services.bff = bff
    services.machine = machine
    services.connectionAttempt = 0
    services.logs = []
    services.nextLogId = 1
    services.onLogChanged = () => setDiagnosticVersion((version) => version + 1)
    servicesRef.current = services
  }

  const services = servicesRef.current
  const state = useSyncExternalStore(
    (listener) => services.machine.subscribe(listener),
    () => services.machine.state,
    () => services.machine.state,
  )

  useEffect(() => {
    if (startedRef.current) return
    startedRef.current = true
    void services.machine.start()
  }, [services])

  const value = useMemo<AppContextValue>(() => ({
    state,
    bff: services.bff,
    adapter: services.adapter,
    gateway: services.gateway,
    runtime: state.runtime,
    requestId: services.bff.lastRequestId,
    connectionState: services.adapter?.state ?? 'idle',
    diagnostics: services.logs.slice().reverse(),
    login: (input) => services.machine.login(input),
    register: async (input) => {
      await services.bff.register(input)
    },
    retry: () => services.machine.retry(),
    logout: async () => {
      await services.machine.logout(async () => {
        services.connectionAttempt += 1
        services.adapter?.logout()
        services.adapter = undefined
        services.gateway = undefined
        appendLog(services, '用户已退出登录')
        await services.bff.logout()
      })
    },
    dismissKicked: () => services.machine.dismissKicked(),
    refreshToken: async () => {
      if (!services.adapter) throw new Error('SDK 尚未连接')
      return services.adapter.refreshToken()
    },
    simulateTokenExpiry: async () => {
      if (!services.adapter) throw new Error('SDK 尚未连接')
      if (!state.runtime?.testMode) throw new Error('测试模式未启用')
      const expired = await services.bff.issueExpiredToken()
      await services.adapter.simulateTokenExpiry(expired)
    },
    reconnect: async () => {
      if (!services.adapter) throw new Error('SDK 尚未连接')
      await services.adapter.reconnect()
    },
  }), [services, state, diagnosticVersion])

  return <AppContext value={value}>{children}</AppContext>
}

export function useApp(): AppContextValue {
  const value = use(AppContext)
  if (!value) throw new Error('useApp 必须在 AppProvider 内使用')
  return value
}

export function isBffError(error: unknown): error is BffApiError {
  return error instanceof Error && error.name === 'BffApiError'
}

async function connectServices(
  services: AppServices,
  context: BootConnectionContext,
): Promise<PhantomSdkAdapter> {
  services.connectionAttempt += 1
  const attempt = services.connectionAttempt
  const isCurrentAttempt = () => services.connectionAttempt === attempt
  services.adapter?.logout()
  services.adapter = undefined
  services.gateway = undefined

  const tokenManager = new PhantomTokenManager({
    bff: services.bff,
  })
  tokenManager.set(context.token)
  let adapter: PhantomSdkAdapter
  adapter = new PhantomSdkAdapter({
    runtime: context.runtime,
    account: context.account,
    token: context.token,
    deviceId: getOrCreateDeviceId(),
    tokenManager,
    onStateChange: (connectionState) => {
      if (isCurrentAttempt()) context.onConnectionState?.(connectionState)
    },
    onSessionExpired: (error) => {
      if (!isCurrentAttempt()) return
      adapter.logout()
      services.connectionAttempt += 1
      services.adapter = undefined
      services.gateway = undefined
      appendLog(services, 'Token 刷新失败，返回登录页', 'error')
      services.machine.sessionExpired(error)
    },
    onDiagnostic: (message, level) => appendLog(services, message, level),
    onKicked: (reason) => {
      if (!isCurrentAttempt()) return
      adapter.logout()
      services.connectionAttempt += 1
      services.adapter = undefined
      services.gateway = undefined
      appendLog(services, `连接被顶替：${reason}`, 'warn')
      services.machine.kicked(reason)
    },
  })
  try {
    await adapter.connect(context.token)
  } catch (error) {
    adapter.logout()
    throw error
  }
  if (!isCurrentAttempt()) {
    adapter.logout()
    throw new Error('连接尝试已被新的请求替代')
  }
  services.adapter = adapter
  services.gateway = new PhantomGateway(adapter.rawClient)
  appendLog(services, 'SDK 连接与初始同步完成')
  return adapter
}

function appendLog(
  services: AppServices,
  message: string,
  level: 'info' | 'warn' | 'error' = 'info',
): void {
  services.logs.push({
    id: services.nextLogId++,
    at: Date.now(),
    level,
    message,
  })
  if (services.logs.length > 100) services.logs.splice(0, services.logs.length - 100)
  services.onLogChanged?.()
}
