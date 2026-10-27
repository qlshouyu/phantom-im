import type { DesktopOS } from './types'

export function detectDesktopOS(): DesktopOS {
  if (typeof navigator === 'undefined') return 'unknown'

  const navigatorWithHints = navigator as Navigator & {
    userAgentData?: { platform?: string }
  }
  const source = `${navigatorWithHints.userAgentData?.platform ?? ''} ${navigator.userAgent}`.toLowerCase()

  if (source.includes('mac')) return 'macos'
  if (source.includes('win')) return 'windows'
  if (source.includes('linux') || source.includes('x11')) return 'linux'
  return 'unknown'
}
