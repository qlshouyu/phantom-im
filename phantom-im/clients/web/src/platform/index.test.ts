import { describe, expect, it } from 'vitest'
import { resolveDesktopRuntime } from './index'

describe('resolveDesktopRuntime', () => {
  it('优先使用 Tauri IPC 检测结果', () => {
    expect(resolveDesktopRuntime(true)).toBe('tauri')
  })

  it('IPC 不可用时使用只读桌面标识', () => {
    expect(resolveDesktopRuntime(false, { runtime: 'tauri' })).toBe('tauri')
  })

  it('浏览器环境不误判为桌面', () => {
    expect(resolveDesktopRuntime(false, undefined)).toBe('browser')
    expect(resolveDesktopRuntime(false, { runtime: 'browser' })).toBe('browser')
  })
})
