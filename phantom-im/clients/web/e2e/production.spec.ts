import { expect, test } from '@playwright/test'
import {
  acceptFirstFriendRequest,
  addFriend,
  createUserContext,
  openContacts,
  openMessages,
  phantomUserId,
} from './helpers'

test.skip(!process.env.E2E_PROD, '仅在生产式 BFF 托管环境运行')

test('BFF 同源托管下 SPA 路由、Cookie、代理和消息收发可用', async ({ browser, page }) => {
  await page.goto('/login')
  await expect(page.getByTestId('auth-login-submit')).toBeVisible()

  const alice = await createUserContext(browser)
  const bob = await createUserContext(browser)
  try {
    const bobId = await phantomUserId(bob.page)
    await addFriend(alice.page, bobId)
    await acceptFirstFriendRequest(bob.page)
    await alice.page.reload()
    await openContacts(alice.page)
    const bobRow = alice.page.locator('.contact-list-row').filter({ hasText: bob.account.nickname })
    await expect(bobRow).toBeVisible()
    await bobRow.click()
    await alice.page.getByRole('button', { name: '发消息', exact: true }).click()

    const text = `生产式冒烟 ${Date.now()}`
    await alice.page.getByLabel('消息内容').fill(text)
    await alice.page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(alice.page.locator('.message-bubble').filter({ hasText: text })).toBeVisible()

    await openMessages(bob.page)
    const conversation = bob.page.locator('.conversation-item').filter({ hasText: alice.account.nickname })
    await expect(conversation).toBeVisible({ timeout: 30_000 })
    await conversation.click()
    await expect(bob.page.locator('.message-bubble').filter({ hasText: text })).toBeVisible()
  } finally {
    await alice.context.close()
    await bob.context.close()
  }
})
