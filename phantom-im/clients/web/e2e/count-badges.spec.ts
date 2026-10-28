import { expect, test, type Page } from '@playwright/test'
import { openContacts, openMessages, setupDirectChatPair } from './helpers'

function navMessage(page: Page) {
  return page.locator('.nav-item').filter({ hasText: '消息' })
}

test('非聊天页收到消息更新导航角标，打开会话后扣减并隐藏', async ({ browser }) => {
  const { alice, bob } = await setupDirectChatPair(browser)
  const navBadge = navMessage(bob.page).locator('.nav-badge')
  try {
    await expect(navBadge).toHaveCount(0, { timeout: 30_000 })
    await openContacts(bob.page)

    const first = `角标消息一 ${Date.now()}`
    const second = `角标消息二 ${Date.now()}`
    await alice.page.getByLabel('消息内容').fill(first)
    await alice.page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(alice.page.locator('.message-bubble').filter({ hasText: first })).toBeVisible()
    await alice.page.getByLabel('消息内容').fill(second)
    await alice.page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(alice.page.locator('.message-bubble').filter({ hasText: second })).toBeVisible()

    await expect(navBadge).toBeVisible({ timeout: 30_000 })
    expect(Number(await navBadge.textContent())).toBeGreaterThan(0)
    await openMessages(bob.page)
    await expect(bob.page.locator('.message-bubble').filter({ hasText: second })).toBeVisible({ timeout: 30_000 })
    await expect(navBadge).toHaveCount(0, { timeout: 30_000 })

    const whileOpen = `已打开会话消息 ${Date.now()}`
    await alice.page.getByLabel('消息内容').fill(whileOpen)
    await alice.page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(bob.page.locator('.message-bubble').filter({ hasText: whileOpen })).toBeVisible({ timeout: 30_000 })
    await expect.poll(async () => bob.page.evaluate(async () => {
      const response = await fetch('/api/phantom/conversations?limit=200', { credentials: 'include' })
      const payload = await response.json()
      const data = payload.data ?? payload
      const items = data.items ?? []
      return items.filter((item: any) => !item.muted)
        .reduce((total: number, item: any) => total + Number(item.unread_count ?? 0), 0)
    })).toBe(0)
    await expect(navBadge).toHaveCount(0)
  } finally {
    await alice.context.close()
    await bob.context.close()
  }
})

test('免打扰会话不增加导航角标并显示非数字未读标识', async ({ browser }) => {
  const { alice, bob } = await setupDirectChatPair(browser)
  const navBadge = navMessage(bob.page).locator('.nav-badge')
  let releaseRead: (() => void) | undefined
  try {
    await expect(navBadge).toHaveCount(0, { timeout: 30_000 })
    await bob.page.getByRole('button', { name: '聊天设置', exact: true }).click()
    await bob.page.getByRole('menuitem', { name: '消息免打扰', exact: true }).click()
    await openContacts(bob.page)

    const text = `免打扰消息 ${Date.now()}`
    const unreadListed = bob.page.waitForResponse(async (response) => {
      if (!response.url().includes('/api/phantom/conversations?')
          || response.request().method() !== 'GET') return false
      const payload = await response.json().catch(() => null)
      return payload?.data?.items?.some((item: any) =>
        item.unread_count === 1 && item.muted === true) ?? false
    })
    await alice.page.getByLabel('消息内容').fill(text)
    await alice.page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(alice.page.locator('.message-bubble').filter({ hasText: text })).toBeVisible()
    await unreadListed
    await expect(navBadge).toHaveCount(0)

    const gate = new Promise<void>((resolve) => { releaseRead = resolve })
    await bob.page.route('**/read-positions', async (route) => {
      await gate
      await route.continue()
    })
    const readCompleted = bob.page.waitForResponse((response) =>
      response.url().includes('/read-positions') && response.request().method() === 'PUT')
    await openMessages(bob.page)
    const conversation = bob.page.locator('.conversation-item').filter({ hasText: alice.account.nickname })
    await expect(conversation.locator('.unread-dot')).toBeVisible({ timeout: 30_000 })
    releaseRead?.()
    await readCompleted
    await bob.page.unroute('**/read-positions')
    await expect(navBadge).toHaveCount(0)
  } finally {
    releaseRead?.()
    await alice.context.close()
    await bob.context.close()
  }
})

test('导航未读超过 99 时显示 99+ 并保留真实无障碍数量', async ({ browser }) => {
  const { alice, bob } = await setupDirectChatPair(browser)
  const navBadge = navMessage(bob.page).locator('.nav-badge')
  let routeRegistered = false
  try {
    await expect(navBadge).toHaveCount(0, { timeout: 30_000 })
    await bob.page.route('**/api/phantom/conversations?*', async (route) => {
      try {
        const response = await route.fetch()
        const payload = JSON.parse(await response.text())
        const data = payload.data ?? payload
        data.badge_count = 100
        await route.fulfill({ response, json: payload })
      } catch {
        await route.continue().catch(() => {})
      }
    })
    routeRegistered = true

    const text = `超限角标 ${Date.now()}`
    await alice.page.getByLabel('消息内容').fill(text)
    await alice.page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(alice.page.locator('.message-bubble').filter({ hasText: text })).toBeVisible()
    await expect(navBadge).toHaveText('99+', { timeout: 30_000 })
    await expect(bob.page.getByLabel('未读消息：100')).toHaveCount(1)
  } finally {
    if (routeRegistered) await bob.page.unroute('**/api/phantom/conversations?*').catch(() => {})
    await alice.context.close()
    await bob.context.close()
  }
})
