import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test'

export interface TestAccount {
  nickname: string
  loginName: string
  password: string
}

let sequence = 0

export function uniqueAccount(prefix: string): TestAccount {
  sequence += 1
  const suffix = `${Date.now().toString(36)}${sequence}`.slice(-12)
  return {
    nickname: `${prefix} ${suffix}`,
    loginName: `${prefix}_${suffix}`.slice(0, 32),
    password: 'password-e2e-1',
  }
}

export async function registerAccount(page: Page, account: TestAccount): Promise<void> {
  await page.goto('/')
  await page.getByRole('button', { name: '注册', exact: true }).click()
  await page.getByLabel('昵称').fill(account.nickname)
  await page.getByLabel('登录名').fill(account.loginName)
  await page.getByLabel('密码', { exact: true }).fill(account.password)
  await page.getByLabel('确认密码', { exact: true }).fill(account.password)
  await page.getByTestId('auth-register-submit').click()
  await expect(page.getByText('注册成功，请使用新账号登录')).toBeVisible()
}

export async function loginExisting(page: Page, account: TestAccount): Promise<void> {
  await page.goto('/')
  if (await page.getByRole('button', { name: '立即注册', exact: true }).isVisible().catch(() => false)) {
    // 已在登录页
  }
  await page.getByLabel('登录名').fill(account.loginName)
  await page.getByLabel('密码', { exact: true }).fill(account.password)
  await page.getByTestId('auth-login-submit').click()
  await expect(page.locator('.desktop-app')).toBeVisible()
}

export async function registerAndLogin(page: Page, account: TestAccount): Promise<void> {
  await registerAccount(page, account)
  await loginExisting(page, account)
}

export async function openContacts(page: Page): Promise<void> {
  await page.getByRole('button', { name: '通讯录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '通讯录', exact: true })).toBeVisible()
}

export async function openMessages(page: Page): Promise<void> {
  const button = page.getByRole('button', { name: '消息', exact: true })
  await button.click()
  await expect(button).toHaveAttribute('aria-current', 'page')
}

export async function phantomUserId(page: Page): Promise<string> {
  const payload = await page.evaluate(async () => {
    const response = await fetch('/api/auth/me', { credentials: 'include' })
    return response.json()
  })
  expect(payload.code).toBe(0)
  return payload.data.phantom_user_id
}

export async function logout(page: Page): Promise<void> {
  await page.getByRole('button', { name: '个人中心', exact: true }).click()
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await page.getByRole('button', { name: '确认退出', exact: true }).click()
  await expect(page.getByTestId('auth-login-submit')).toBeVisible()
}

export async function addFriend(page: Page, identifier: string): Promise<void> {
  await openContacts(page)
  await page.getByRole('button', { name: '添加好友或群组', exact: true }).click()
  await page.getByLabel('登录名或 Phantom ID').fill(identifier)
  await page.getByRole('button', { name: '下一步', exact: true }).click()
  await expect(page.getByRole('heading', { name: '确认好友信息', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '发送申请', exact: true }).click()
  await expect(page.getByText('好友申请已发送')).toBeVisible()
}

export async function acceptFirstFriendRequest(page: Page): Promise<void> {
  await openContacts(page)
  await page.locator('.contact-tabs button').filter({ hasText: '申请' }).click()
  const card = page.locator('.friend-request-card').first()
  await expect(card).toBeVisible()
  await card.getByRole('button', { name: '接受', exact: true }).click()
  await expect(page.getByText('已接受好友申请')).toBeVisible()
}

export async function createUserContext(browser: Browser): Promise<{ context: BrowserContext; page: Page; account: TestAccount }> {
  const context = await browser.newContext({ viewport: { width: 1280, height: 820 }, locale: 'zh-CN' })
  const page = await context.newPage()
  const account = uniqueAccount('e2e')
  await registerAndLogin(page, account)
  return { context, page, account }
}

export async function setupDirectChatPair(browser: Browser) {
  const alice = await createUserContext(browser)
  const bob = await createUserContext(browser)
  const bobId = await phantomUserId(bob.page)
  await addFriend(alice.page, bobId)
  await acceptFirstFriendRequest(bob.page)
  await alice.page.reload()
  await openContacts(alice.page)
  const bobRow = alice.page.locator('.contact-list-row').filter({ hasText: bob.account.nickname })
  await expect(bobRow).toBeVisible()
  await bobRow.click()
  await alice.page.getByRole('button', { name: '发消息', exact: true }).click()
  await expect(alice.page.getByLabel('消息内容')).toBeVisible()
  const greeting = `会话初始化 ${Date.now()}`
  await alice.page.getByLabel('消息内容').fill(greeting)
  await alice.page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(alice.page.locator('.message-bubble').filter({ hasText: greeting })).toBeVisible()

  await openMessages(bob.page)
  const aliceConversation = bob.page.locator('.conversation-item').filter({ hasText: alice.account.nickname })
  await expect(aliceConversation).toBeVisible({ timeout: 30_000 })
  await aliceConversation.click()
  await expect(bob.page.getByLabel('消息内容')).toBeVisible()
  return { alice, bob }
}
