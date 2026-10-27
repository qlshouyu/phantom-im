import { expect, test } from '@playwright/test'
import { setupDirectChatPair } from './helpers'

test('单聊在线状态与输入信令来自真实连接并在超时后清除', async ({ browser }) => {
  const { alice, bob } = await setupDirectChatPair(browser)
  try {
    await expect(alice.page.locator('.chat-presence')).toHaveText('在线', { timeout: 30_000 })

    await bob.page.getByLabel('消息内容').fill('正在输入但不发送')
    await expect(alice.page.locator('.typing-indicator')).toContainText(bob.account.nickname, { timeout: 15_000 })
    await expect(alice.page.locator('.typing-indicator')).toHaveCount(0, { timeout: 10_000 })

    await bob.page.getByLabel('消息内容').fill('')
  } finally {
    await alice.context.close()
    await bob.context.close()
  }
})
