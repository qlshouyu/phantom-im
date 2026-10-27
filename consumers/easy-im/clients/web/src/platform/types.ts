export type DesktopOS = 'macos' | 'windows' | 'linux' | 'unknown'
export type DesktopRuntime = 'tauri' | 'browser'

export interface DesktopWindowControls {
  startDragging: () => Promise<void>
  minimize: () => Promise<void>
  toggleMaximize: () => Promise<void>
  close: () => Promise<void>
}

export interface DesktopPlatformAdapter {
  runtime: DesktopRuntime
  os: DesktopOS
  isTauri: boolean
  window: DesktopWindowControls
}
