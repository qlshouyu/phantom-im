import { isTauri } from '@tauri-apps/api/core'
import { createBrowserAdapter } from './browser'
import { createTauriAdapter } from './tauri'
import type { DesktopPlatformAdapter, DesktopRuntime } from './types'

interface PhantomDesktopMarker {
  runtime?: DesktopRuntime
}

declare global {
  interface Window {
    __PHANTOM_IM_DESKTOP__?: PhantomDesktopMarker
  }
}

export function resolveDesktopRuntime(
  tauriRuntime: boolean,
  marker = typeof window === 'undefined' ? undefined : window.__PHANTOM_IM_DESKTOP__,
): DesktopRuntime {
  return tauriRuntime || marker?.runtime === 'tauri' ? 'tauri' : 'browser'
}

export const desktopPlatform: DesktopPlatformAdapter =
  resolveDesktopRuntime(isTauri()) === 'tauri'
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
