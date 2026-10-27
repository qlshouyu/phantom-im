import { expect, test } from '@playwright/test'
import { createUserContext, loginExisting, registerAndLogin, uniqueAccount } from './helpers'

test('Token 过期恢复与断线重连状态可观察', async ({ page }) => {
  const account = uniqueAccount('life')
  await registerAndLogin(page, account)
  await expect(page.locator('.desktop-app')).toBeVisible()

  await page.getByRole('button', { name: '个人中心', exact: true }).click()
  await page.locator('.profile-menu button').filter({ hasText: '连接诊断' }).click()
  await page.getByRole('button', { name: '模拟 Token 过期', exact: true }).click()
  await expect(page.getByText('Token 刷新成功')).toBeVisible({ timeout: 30_000 })

  await page.getByRole('button', { name: '断线重连', exact: true }).click()
  await expect(page.locator('.connection-pill--online')).toBeVisible({ timeout: 30_000 })
})

test('同账号新设备登录后旧连接停止并展示被顶替原因', async ({ browser }) => {
  const first = await createUserContext(browser)
  const secondContext = await browser.newContext({ viewport: { width: 1280, height: 820 }, locale: 'zh-CN' })
  const second = await secondContext.newPage()
  try {
    await loginExisting(second, first.account)
    await expect(first.page.getByRole('heading', { name: '当前设备已被顶替' })).toBeVisible({ timeout: 30_000 })
    await expect(first.page.getByRole('button', { name: '重新登录', exact: true })).toBeVisible()
    await expect(second.locator('.desktop-app')).toBeVisible()
  } finally {
    await first.context.close()
    await secondContext.close()
  }
})
