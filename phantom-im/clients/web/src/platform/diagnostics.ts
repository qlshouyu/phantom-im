import { invoke, isTauri } from '@tauri-apps/api/core'

export type DiagnosticLevel = 'info' | 'warn' | 'error'

const MAX_MESSAGE_LENGTH = 8 * 1024
let errorHandlersInstalled = false

export function writeDiagnosticLog(level: DiagnosticLevel, message: string): void {
  if (!isTauri()) return
  const safeMessage = message.slice(0, MAX_MESSAGE_LENGTH)
  void invoke('write_frontend_log', { level, message: safeMessage }).catch(() => {
    // 日志失败不能影响用户主流程。
  })
}

export async function getDiagnosticLogPath(): Promise<string | undefined> {
  if (!isTauri()) return undefined
  try {
    return (await invoke<string | null>('diagnostic_log_path')) ?? undefined
  } catch {
    return undefined
  }
}

export async function openDiagnosticLogDirectory(): Promise<void> {
  if (!isTauri()) return
  try {
    await invoke('open_diagnostic_log_dir')
  } catch {
    // 打开目录失败不能影响登录页。
  }
}

export function installDiagnosticErrorHandlers(): void {
  if (errorHandlersInstalled || typeof window === 'undefined') return
  errorHandlersInstalled = true

  window.addEventListener('error', (event) => {
    const location = [event.filename, event.lineno, event.colno]
      .filter((part) => part !== undefined && part !== '')
      .join(':')
    writeDiagnosticLog('error', `WebView 未捕获异常 message=${event.message || 'unknown'} location=${location || 'unknown'}`)
  })

  window.addEventListener('unhandledrejection', (event) => {
    writeDiagnosticLog('error', `WebView 未处理的 Promise 拒绝 message=${errorMessage(event.reason)}`)
  })
}

function errorMessage(value: unknown): string {
  if (value instanceof Error) return `${value.name}: ${value.message}`
  return typeof value === 'string' ? value : '未知错误'
}
