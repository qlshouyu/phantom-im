import { getCurrentWindow } from '@tauri-apps/api/window'
import { detectDesktopOS } from './detect'
import type { DesktopPlatformAdapter } from './types'

export function createTauriAdapter(): DesktopPlatformAdapter {
  const currentWindow = getCurrentWindow()

  return {
    runtime: 'tauri',
    os: detectDesktopOS(),
    isTauri: true,
    window: {
      startDragging: () => currentWindow.startDragging(),
      minimize: () => currentWindow.minimize(),
      toggleMaximize: () => currentWindow.toggleMaximize(),
      close: () => currentWindow.close(),
    },
  }
}
