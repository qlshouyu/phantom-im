import { expect, test } from '@playwright/test'
import { loginExisting, logout, registerAccount, uniqueAccount } from './helpers'

test('注册后手动登录、刷新恢复 Session、退出后回到登录页', async ({ page }) => {
  const account = uniqueAccount('auth')
  await registerAccount(page, account)

  await expect(page.getByTestId('auth-login-submit')).toBeVisible()
  await loginExisting(page, account)
  await expect(page.locator('.desktop-app')).toBeVisible()

  await page.reload()
  await expect(page.locator('.desktop-app')).toBeVisible()
  await expect(page.getByText('在线', { exact: true })).toBeVisible()

  await logout(page)
  await page.reload()
  await expect(page.getByTestId('auth-login-submit')).toBeVisible()
})
