import { expect, test } from '@playwright/test'
import { setupDirectChatPair } from './helpers'

test('浏览器通知点击定位到真实会话', async ({ browser }) => {
  const { alice, bob } = await setupDirectChatPair(browser)
  try {
    await alice.page.evaluate(() => {
      class FakeNotification {
        static permission = 'granted'
        onclick: (() => void) | null = null
        close = () => {}
        constructor(title: string, options?: NotificationOptions) {
          ;(window as any).__phantomNotification = { title, options, notification: this }
        }
      }
      ;(window as any).Notification = FakeNotification
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
    })

    const text = `通知消息 ${Date.now()}`
    await bob.page.getByLabel('消息内容').fill(text)
    await bob.page.getByRole('button', { name: '发送', exact: true }).click()
    await expect.poll(async () => alice.page.evaluate(() => (window as any).__phantomNotification?.options?.body))
      .toBe(text)

    await alice.page.evaluate(() => (window as any).__phantomNotification.notification.onclick?.())
    await expect(alice.page.locator('.message-bubble').filter({ hasText: text })).toBeVisible({ timeout: 30_000 })
  } finally {
    await alice.context.close()
    await bob.context.close()
  }
})
