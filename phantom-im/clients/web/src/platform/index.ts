import { isTauri } from '@tauri-apps/api/core'
import { createBrowserAdapter } from './browser'
import { createTauriAdapter } from './tauri'
import type { DesktopPlatformAdapter } from './types'

export const desktopPlatform: DesktopPlatformAdapter = isTauri()
  ? createTauriAdapter()
  : createBrowserAdapter()

export function applyPlatformAttributes(platform = desktopPlatform) {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  root.dataset.runtime = platform.runtime
  root.dataset.platform = platform.os
  root.classList.toggle('is-tauri', platform.isTauri)
}

export type { DesktopOS, DesktopPlatformAdapter, DesktopRuntime } from './types'
