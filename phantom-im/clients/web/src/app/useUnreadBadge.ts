import { useCallback, useEffect, useRef, useState } from 'react'
import type { PhantomGateway } from '../phantom/gateway'

const REFRESH_DELAY_MS = 150

export interface UnreadBadgeController {
  unreadTotal: number
  markConversationRead: (unreadCount: number) => void
  refreshUnread: () => Promise<void>
}

export function useUnreadBadge(gateway?: PhantomGateway): UnreadBadgeController {
  const [unreadTotal, setUnreadTotal] = useState(0)
  const requestVersion = useRef(0)
  const refreshTimer = useRef<number | null>(null)

  const refreshUnread = useCallback(async () => {
    if (!gateway) return
    const version = ++requestVersion.current
    try {
      const page = await gateway.listConversationPage()
      if (version === requestVersion.current) setUnreadTotal(page.badgeCount)
    } catch {
      // 保留上一次已知角标，等待后续实时事件或手动刷新校准。
    }
  }, [gateway])

  const scheduleRefresh = useCallback(() => {
    if (refreshTimer.current !== null) window.clearTimeout(refreshTimer.current)
    refreshTimer.current = window.setTimeout(() => {
      refreshTimer.current = null
      void refreshUnread()
    }, REFRESH_DELAY_MS)
  }, [refreshUnread])

  const markConversationRead = useCallback((unreadCount: number) => {
    requestVersion.current += 1
    if (unreadCount > 0) {
      setUnreadTotal((current) => Math.max(0, current - unreadCount))
    }
    scheduleRefresh()
  }, [scheduleRefresh])

  useEffect(() => {
    requestVersion.current += 1
    setUnreadTotal(0)
    if (!gateway) return

    void refreshUnread()
    const offs = ['message', 'sync', 'conversation'].map((event) =>
      gateway.on(event, scheduleRefresh))

    return () => {
      for (const off of offs) off()
      if (refreshTimer.current !== null) {
        window.clearTimeout(refreshTimer.current)
        refreshTimer.current = null
      }
      requestVersion.current += 1
    }
  }, [gateway, refreshUnread, scheduleRefresh])

  return { unreadTotal, markConversationRead, refreshUnread }
}
