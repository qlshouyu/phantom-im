import { expect, test } from '@playwright/test'
import {
  acceptFirstFriendRequest,
  addFriend,
  createUserContext,
  openContacts,
  phantomUserId,
} from './helpers'

test('群邀请、禁言、管理员、公告、群昵称、退出与解散使用真实权限', async ({ browser }) => {
  const alice = await createUserContext(browser)
  const bob = await createUserContext(browser)
  const carol = await createUserContext(browser)
  const dave = await createUserContext(browser)
  try {
    const bobId = await phantomUserId(bob.page)
    const carolId = await phantomUserId(carol.page)
    const daveId = await phantomUserId(dave.page)
    await addFriend(alice.page, bobId)
    await addFriend(alice.page, carolId)
    await addFriend(alice.page, daveId)
    await acceptFirstFriendRequest(bob.page)
    await acceptFirstFriendRequest(carol.page)
    await acceptFirstFriendRequest(dave.page)

    await alice.page.reload()
    await openContacts(alice.page)
    await alice.page.locator('.contact-tabs button').filter({ hasText: '群组' }).click()
    await alice.page.getByRole('button', { name: '添加好友或群组', exact: true }).click()
    await alice.page.locator('.modal-segmented button').filter({ hasText: '创建群组' }).click()
    const groupName = `管理群 ${Date.now()}`
    await alice.page.getByLabel('群组名称').fill(groupName)
    await alice.page.getByRole('checkbox', { name: bob.account.nickname }).check()
    await alice.page.getByRole('checkbox', { name: carol.account.nickname }).check()
    await alice.page.locator('.modal-actions button[type="submit"]').click()
    await expect(alice.page.getByText('群组已创建')).toBeVisible()
    await expect(alice.page.locator('.group-member-row').filter({ hasText: bob.account.nickname })).toBeVisible()

    await alice.page.getByRole('button', { name: '邀请成员', exact: true }).click()
    await alice.page.getByRole('checkbox', { name: dave.account.nickname }).check()
    await alice.page.getByRole('button', { name: '发送邀请', exact: true }).click()
    await expect(alice.page.getByText('成员邀请已发送')).toBeVisible()
    await expect(alice.page.locator('.group-member-row').filter({ hasText: dave.account.nickname })).toBeVisible({ timeout: 30_000 })

    const bobMember = alice.page.locator('.group-member-row').filter({ hasText: bob.account.nickname })
    await bobMember.getByRole('button', { name: '成员操作', exact: true }).click()
    await alice.page.getByRole('button', { name: '设为管理员', exact: true }).click()
    await expect(alice.page.getByText('已设为管理员')).toBeVisible()

    const carolMember = alice.page.locator('.group-member-row').filter({ hasText: carol.account.nickname })
    await carolMember.getByRole('button', { name: '成员操作', exact: true }).click()
    await alice.page.getByRole('button', { name: '禁言 10 分钟', exact: true }).click()
    await expect(alice.page.getByText('已禁言成员')).toBeVisible()

    const daveMember = alice.page.locator('.group-member-row').filter({ hasText: dave.account.nickname })
    await daveMember.getByRole('button', { name: '成员操作', exact: true }).click()
    await alice.page.getByRole('button', { name: '移出群组', exact: true }).click()
    await expect(alice.page.getByText('已移出成员')).toBeVisible()

    await alice.page.locator('.announcement-card').getByRole('button', { name: '编辑', exact: true }).click()
    const announcement = `群公告 ${Date.now()}`
    await alice.page.getByRole('textbox', { name: '群公告', exact: true }).fill(announcement)
    await alice.page.getByRole('button', { name: '保存', exact: true }).click()
    await expect(alice.page.getByText('群公告已更新')).toBeVisible()

    await alice.page.getByRole('button', { name: '修改群昵称', exact: true }).click()
    await alice.page.getByRole('textbox', { name: '群昵称', exact: true }).fill('Alice 群昵称')
    await alice.page.getByRole('button', { name: '保存', exact: true }).click()
    await expect(alice.page.getByText('群昵称已更新')).toBeVisible()

    await alice.page.getByRole('button', { name: '开启群消息免打扰', exact: true }).click()
    await expect(alice.page.getByText('已开启群消息免打扰')).toBeVisible()

    await openContacts(bob.page)
    await bob.page.locator('.contact-tabs button').filter({ hasText: '群组' }).click()
    const bobGroup = bob.page.locator('.group-list-row').filter({ hasText: groupName })
    await expect(bobGroup).toBeVisible({ timeout: 30_000 })
    await bobGroup.click()
    await expect(bob.page.getByText(announcement, { exact: true })).toBeVisible({ timeout: 30_000 })
    await expect(bob.page.locator('.announcement-card').getByRole('button', { name: '编辑', exact: true })).toBeVisible()

    await openContacts(carol.page)
    await carol.page.locator('.contact-tabs button').filter({ hasText: '群组' }).click()
    const carolGroup = carol.page.locator('.group-list-row').filter({ hasText: groupName })
    await expect(carolGroup).toBeVisible({ timeout: 30_000 })
    await carolGroup.click()
    await carol.page.getByRole('button', { name: '退出群组', exact: true }).click()
    await carol.page.getByRole('button', { name: '确认退出', exact: true }).click()
    await expect(carol.page.getByText('已退出群组')).toBeVisible()

    await alice.page.getByRole('button', { name: '群文件', exact: true }).click()
    await expect(alice.page.getByText('群文件暂未开放')).toBeVisible()
    await alice.page.getByRole('button', { name: '群相册', exact: true }).click()
    await expect(alice.page.getByText('群相册暂未开放')).toBeVisible()

    await alice.page.getByRole('button', { name: '解散群组', exact: true }).click()
    await alice.page.getByRole('button', { name: '确认解散', exact: true }).click()
    await expect(alice.page.getByText('群组已解散')).toBeVisible()
    await alice.page.reload()
    await openContacts(alice.page)
    await alice.page.locator('.contact-tabs button').filter({ hasText: '群组' }).click()
    await expect(alice.page.locator('.group-list-row').filter({ hasText: groupName })).toHaveCount(0, { timeout: 30_000 })
  } finally {
    await alice.context.close()
    await bob.context.close()
    await carol.context.close()
    await dave.context.close()
  }
})
