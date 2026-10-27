import { expect, test } from '@playwright/test'
import { openContacts, setupDirectChatPair } from './helpers'

const docsDir = '../../../docs/phantom-im/acceptance'

test('生成关键页面验收截图', async ({ browser, page }) => {
  await page.goto('/')
  await expect(page.getByTestId('auth-login-submit')).toBeVisible()
  await page.screenshot({ path: `${docsDir}/auth-login.png`, fullPage: true })

  const { alice, bob } = await setupDirectChatPair(browser)
  try {
    const text = `验收消息 ${Date.now()}`
    await alice.page.getByLabel('消息内容').fill(text)
    await alice.page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(alice.page.locator('.message-bubble').filter({ hasText: text })).toBeVisible()
    await expect(alice.page.locator('.message-state--sent').last()).toBeVisible({ timeout: 15_000 })
    await alice.page.waitForTimeout(600)
    await alice.page.screenshot({ path: `${docsDir}/desktop-chat.png`, fullPage: true })

    await openContacts(alice.page)
    await expect(alice.page.locator('.contact-list-row').first()).toBeVisible()
    await alice.page.waitForTimeout(600)
    await alice.page.screenshot({ path: `${docsDir}/desktop-contacts.png`, fullPage: true })

    await alice.page.getByRole('button', { name: '个人中心', exact: true }).click()
    await expect(alice.page.getByRole('heading', { name: '个人中心', exact: true })).toBeVisible()
    await expect(alice.page.locator('.profile-main-scroll')).toBeVisible()
    await alice.page.waitForTimeout(800)
    await alice.page.screenshot({ path: `${docsDir}/desktop-profile.png`, fullPage: true })
  } finally {
    await alice.context.close()
    await bob.context.close()
  }
})
