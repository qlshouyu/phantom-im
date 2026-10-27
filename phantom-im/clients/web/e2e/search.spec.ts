import { expect, test } from '@playwright/test'
import { openContacts, setupDirectChatPair } from './helpers'

test('全局搜索定位消息并支持会话内搜索高亮', async ({ browser }) => {
  const { alice, bob } = await setupDirectChatPair(browser)
  try {
    const keyword = `检索词${Date.now()}`
    await alice.page.getByLabel('消息内容').fill(keyword)
    await alice.page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(alice.page.locator('.message-bubble').filter({ hasText: keyword })).toBeVisible()

    await expect(bob.page.locator('.message-bubble').filter({ hasText: keyword })).toBeVisible({ timeout: 30_000 })
    await bob.page.getByLabel('搜索聊天').fill(keyword)
    const result = bob.page.locator('.chat-search-section button').filter({ hasText: keyword }).first()
    await expect(result).toBeVisible({ timeout: 40_000 })
    await result.click()
    await expect(bob.page.locator('.message-row.is-highlighted').filter({ hasText: keyword })).toBeVisible({ timeout: 30_000 })

    await bob.page.getByRole('button', { name: '在聊天中搜索', exact: true }).click()
    await bob.page.getByPlaceholder('输入消息关键词').fill(keyword)
    await bob.page.getByRole('button', { name: '搜索', exact: true }).click()
    const conversationResult = bob.page.locator('.chat-search-list button').filter({ hasText: keyword })
    await expect(conversationResult).toBeVisible({ timeout: 20_000 })
    await conversationResult.click()
    await expect(bob.page.locator('.message-row.is-highlighted').filter({ hasText: keyword })).toBeVisible()

    await openContacts(bob.page)
    await bob.page.getByLabel('搜索通讯录').fill(alice.account.nickname)
    await expect(bob.page.locator('.chat-search-section button').filter({ hasText: alice.account.nickname }))
      .toBeVisible({ timeout: 30_000 })
  } finally {
    await alice.context.close()
    await bob.context.close()
  }
})
