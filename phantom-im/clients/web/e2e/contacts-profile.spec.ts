import { expect, test } from '@playwright/test'
import {
  acceptFirstFriendRequest,
  addFriend,
  createUserContext,
  openContacts,
  phantomUserId,
  registerAndLogin,
  uniqueAccount,
} from './helpers'

test('好友申请错误、备注、分组、黑名单与删除使用真实结果', async ({ browser }) => {
  const alice = await createUserContext(browser)
  const bob = await createUserContext(browser)
  try {
    await openContacts(alice.page)
    await alice.page.getByRole('button', { name: '添加好友或群组', exact: true }).click()
    await alice.page.getByLabel('登录名或 Phantom ID').fill(`missing_${Date.now()}`)
    await alice.page.getByRole('button', { name: '下一步', exact: true }).click()
    await expect(alice.page.getByRole('alert')).toBeVisible({ timeout: 20_000 })
    await alice.page.getByRole('button', { name: '取消', exact: true }).click()

    const bobId = await phantomUserId(bob.page)
    await alice.page.getByRole('button', { name: '添加好友或群组', exact: true }).click()
    await alice.page.getByLabel('登录名或 Phantom ID').fill(bob.account.loginName)
    await alice.page.getByRole('button', { name: '下一步', exact: true }).click()
    const friendDialog = alice.page.getByRole('dialog')
    await expect(friendDialog.getByRole('heading', { name: '确认好友信息', exact: true })).toBeVisible()
    await expect(friendDialog.getByText(bob.account.loginName, { exact: true })).toBeVisible()
    await expect(friendDialog.getByText(bobId, { exact: true })).toBeVisible()
    await alice.page.getByLabel('验证消息').fill('自动化好友申请')
    await alice.page.getByRole('button', { name: '发送申请', exact: true }).click()
    await expect(alice.page.getByText('好友申请已发送')).toBeVisible()
    await acceptFirstFriendRequest(bob.page)

    await alice.page.reload()
    await openContacts(alice.page)
    const bobRow = alice.page.locator('.contact-list-row').filter({ hasText: bob.account.nickname })
    await expect(bobRow).toBeVisible({ timeout: 30_000 })
    await bobRow.click()

    await alice.page.getByRole('button', { name: '编辑备注', exact: true }).click()
    await alice.page.getByLabel('好友备注').fill('重点好友')
    await alice.page.getByRole('button', { name: '保存修改', exact: true }).click()
    await expect(alice.page.getByText('好友资料已更新')).toBeVisible()
    await expect(alice.page.getByText('备注：重点好友', { exact: true })).toBeVisible()

    await alice.page.locator('.detail-topbar-actions').getByRole('button', { name: '更多操作', exact: true }).click()
    await alice.page.getByRole('button', { name: '管理好友分组', exact: true }).click()
    await alice.page.getByPlaceholder('新分组名称').fill('自动化分组')
    await alice.page.getByRole('button', { name: '创建', exact: true }).click()
    await expect(alice.page.getByText('自动化分组')).toBeVisible()
    await alice.page.getByRole('button', { name: '关闭', exact: true }).click()

    await alice.page.locator('.detail-topbar-actions').getByRole('button', { name: '更多操作', exact: true }).click()
    await alice.page.getByRole('button', { name: '加入黑名单', exact: true }).click()
    await expect(alice.page.getByText('已加入黑名单')).toBeVisible()
    const blacklistEntry = alice.page.locator('.blacklist-entry')
    await expect(blacklistEntry).toBeVisible({ timeout: 30_000 })
    await blacklistEntry.click()
    await expect(alice.page.getByRole('dialog')).toBeVisible()
    await alice.page.getByRole('button', { name: '解除拉黑', exact: true }).click()
    await expect(alice.page.getByText('已解除拉黑')).toBeVisible()
    await expect(alice.page.locator('.contact-list-row').filter({ hasText: bob.account.nickname })).toHaveCount(0)

    await alice.page.getByRole('button', { name: '添加好友或群组', exact: true }).click()
    await alice.page.getByLabel('登录名或 Phantom ID').fill(bobId)
    await alice.page.getByRole('button', { name: '下一步', exact: true }).click()
    await alice.page.getByRole('button', { name: '发送申请', exact: true }).click()
    await expect(alice.page.getByText('好友申请已发送')).toBeVisible()
    await acceptFirstFriendRequest(bob.page)
    await alice.page.reload()
    await openContacts(alice.page)
    const reAddedRow = alice.page.locator('.contact-list-row').filter({ hasText: bob.account.nickname })
    await expect(reAddedRow).toBeVisible({ timeout: 30_000 })
    await reAddedRow.click()

    await alice.page.locator('.detail-topbar-actions').getByRole('button', { name: '更多操作', exact: true }).click()
    await alice.page.getByRole('button', { name: '删除好友', exact: true }).click()
    await alice.page.getByRole('button', { name: '确认删除', exact: true }).click()
    await expect(alice.page.getByText('好友已删除')).toBeVisible()
    await alice.page.reload()
    await openContacts(alice.page)
    await expect(alice.page.locator('.contact-list-row').filter({ hasText: bob.account.nickname })).toHaveCount(0, { timeout: 30_000 })
  } finally {
    await alice.context.close()
    await bob.context.close()
  }
})

test('个人资料、二维码、设备与通知偏好来自真实接口', async ({ page }) => {
  const account = uniqueAccount('profile')
  await registerAndLogin(page, account)
  await page.getByRole('button', { name: '个人中心', exact: true }).click()
  await expect(page.getByRole('heading', { name: '个人中心', exact: true })).toBeVisible()

  await page.getByRole('button', { name: '编辑资料', exact: true }).click()
  const signature = `签名 ${Date.now()}`
  await page.getByLabel('个性签名').fill(signature)
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.getByText('个人资料已保存')).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: '个人中心', exact: true }).click()
  await expect(page.locator('.profile-card-signature')).toHaveText(signature, { timeout: 30_000 })

  await page.locator('.profile-menu button').filter({ hasText: '我的二维码' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByAltText('Phantom 好友二维码')).toBeVisible({ timeout: 20_000 })
  await page.getByRole('button', { name: '刷新二维码', exact: true }).click()
  await expect(page.getByText('二维码已刷新')).toBeVisible()
  await page.getByRole('button', { name: '关闭', exact: true }).click()

  await page.locator('.profile-menu button').filter({ hasText: '账号与安全' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.locator('.device-row')).toHaveCount(1, { timeout: 20_000 })
  await page.getByRole('button', { name: '关闭', exact: true }).click()

  await page.locator('.profile-menu button').filter({ hasText: '偏好设置' }).click()
  const pushSwitch = page.getByRole('switch', { name: '推送通知', exact: true })
  await expect(pushSwitch).toBeChecked()
  await pushSwitch.click()
  await expect(page.getByText('通知偏好已同步')).toBeVisible()
  await pushSwitch.click()
  await expect(page.getByText('通知偏好已同步')).toBeVisible()
  await page.getByRole('button', { name: '关闭', exact: true }).click()

  await page.locator('.profile-menu button').filter({ hasText: '我的收藏' }).click()
  await expect(page.getByText('收藏夹暂未开放')).toBeVisible()
})

test('好友申请接受、拒绝和忽略分别产生真实关系结果', async ({ browser }) => {
  const alice = await createUserContext(browser)
  const bob = await createUserContext(browser)
  const carol = await createUserContext(browser)
  const dave = await createUserContext(browser)
  try {
    const aliceId = await phantomUserId(alice.page)
    await addFriend(bob.page, aliceId)
    await addFriend(carol.page, aliceId)
    await addFriend(dave.page, aliceId)

    await openContacts(alice.page)
    await alice.page.locator('.contact-tabs button').filter({ hasText: '申请' }).click()
    const bobRequest = alice.page.locator('.friend-request-card').filter({ hasText: bob.account.nickname })
    const carolRequest = alice.page.locator('.friend-request-card').filter({ hasText: carol.account.nickname })
    const daveRequest = alice.page.locator('.friend-request-card').filter({ hasText: dave.account.nickname })
    await expect(bobRequest).toBeVisible()
    await expect(carolRequest).toBeVisible()
    await expect(daveRequest).toBeVisible()

    await bobRequest.getByRole('button', { name: '拒绝', exact: true }).click()
    await expect(alice.page.getByText('已拒绝好友申请')).toBeVisible()
    await carolRequest.getByRole('button', { name: '忽略', exact: true }).click()
    await expect(alice.page.getByText('已忽略好友申请')).toBeVisible()
    await daveRequest.getByRole('button', { name: '接受', exact: true }).click()
    await expect(alice.page.getByText('已接受好友申请')).toBeVisible()

    await alice.page.reload()
    await openContacts(alice.page)
    await expect(alice.page.locator('.contact-list-row').filter({ hasText: dave.account.nickname })).toBeVisible({ timeout: 30_000 })
    await expect(alice.page.locator('.contact-list-row').filter({ hasText: bob.account.nickname })).toHaveCount(0)
    await expect(alice.page.locator('.contact-list-row').filter({ hasText: carol.account.nickname })).toHaveCount(0)
  } finally {
    await alice.context.close()
    await bob.context.close()
    await carol.context.close()
    await dave.context.close()
  }
})

test('设备列表可远程下线另一类型 SDK 连接', async ({ page }) => {
  const account = uniqueAccount('device')
  await registerAndLogin(page, account)

  const desktopState = await page.evaluate(async () => {
    const runtime = await (await fetch('/api/runtime-config', { credentials: 'include' })).json()
    const token = await (await fetch('/api/auth/phantom-token', {
      method: 'POST',
      credentials: 'include',
    })).json()
    const module = await import('/node_modules/@phantom/sdk-web/src/index.ts')
    const client = new module.PhantomClient({
      url: runtime.data.ws_url,
      appId: runtime.data.app_id,
      httpBaseUrl: '/api/phantom',
    })
    await client.login({
      userToken: token.data.user_token,
      userId: token.data.phantom_user_id,
      deviceId: `desktop-${Date.now()}`,
      deviceType: 'DESKTOP',
    })
    ;(window as any).__phantomDesktop = client
    return client.getState()
  })
  expect(desktopState).toBe('online')

  await page.getByRole('button', { name: '个人中心', exact: true }).click()
  await page.getByRole('button', { name: '刷新个人中心', exact: true }).click()
  await page.locator('.profile-menu button').filter({ hasText: '账号与安全' }).click()
  await expect(page.locator('.device-row')).toHaveCount(2, { timeout: 30_000 })
  await page.getByRole('button', { name: '下线', exact: true }).click()
  await expect(page.getByText('设备已下线')).toBeVisible()
  await expect.poll(async () => page.evaluate(() => (window as any).__phantomDesktop?.getState()))
    .not.toBe('online')
})
