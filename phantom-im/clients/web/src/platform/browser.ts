import { detectDesktopOS } from './detect'
import type { DesktopPlatformAdapter } from './types'

export function createBrowserAdapter(): DesktopPlatformAdapter {
  return {
    runtime: 'browser',
    os: detectDesktopOS(),
    isTauri: false,
    window: {
      startDragging: async () => undefined,
      minimize: async () => undefined,
      toggleMaximize: async () => undefined,
      close: async () => undefined,
    },
  }
}
