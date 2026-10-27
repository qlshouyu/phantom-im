import { expect, test } from '@playwright/test'
import {
  acceptFirstFriendRequest,
  addFriend,
  createUserContext,
  openContacts,
  openMessages,
  phantomUserId,
} from './helpers'

test('双用户好友申请、单聊与群聊主链路', async ({ browser }) => {
  const alice = await createUserContext(browser)
  const bob = await createUserContext(browser)
  const carol = await createUserContext(browser)
  try {
    const bobId = await phantomUserId(bob.page)
    const carolId = await phantomUserId(carol.page)
    await addFriend(alice.page, bobId)
    await addFriend(alice.page, carolId)
    await acceptFirstFriendRequest(bob.page)
    await acceptFirstFriendRequest(carol.page)

    await alice.page.reload()
    await expect(alice.page.locator('.desktop-app')).toBeVisible()
    await openContacts(alice.page)
    const bobRow = alice.page.locator('.contact-list-row').filter({ hasText: bob.account.nickname })
    await expect(bobRow).toBeVisible()
    await bobRow.click()
    await alice.page.getByRole('button', { name: '发消息', exact: true }).click()
    await expect(alice.page.locator('.desktop-app')).toBeVisible()

    const directText = `单聊 ${Date.now()}`
    await alice.page.getByLabel('消息内容').fill(directText)
    await alice.page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(alice.page.locator('.message-bubble').filter({ hasText: directText })).toBeVisible()

    await openMessages(bob.page)
    const aliceConversation = bob.page.locator('.conversation-item').filter({ hasText: alice.account.nickname })
    await expect(aliceConversation).toBeVisible({ timeout: 30_000 })
    await aliceConversation.click()
    await expect(bob.page.locator('.message-bubble').filter({ hasText: directText })).toBeVisible()

    await openContacts(alice.page)
    await alice.page.locator('.contact-tabs button').filter({ hasText: '群组' }).click()
    await alice.page.getByRole('button', { name: '添加好友或群组', exact: true }).click()
    await alice.page.locator('.modal-segmented button').filter({ hasText: '创建群组' }).click()
    const groupName = `群组 ${Date.now()}`
    await alice.page.getByLabel('群组名称').fill(groupName)
    await alice.page.getByRole('checkbox', { name: bob.account.nickname }).check()
    await alice.page.getByRole('checkbox', { name: carol.account.nickname }).check()
    await alice.page.locator('.modal-actions button[type="submit"]').click()
    await expect(alice.page.getByText('群组已创建')).toBeVisible()
    await alice.page.getByRole('button', { name: '进入群聊', exact: true }).click()

    const groupText = `群聊 ${Date.now()}`
    await alice.page.getByLabel('消息内容').fill(groupText)
    await alice.page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(alice.page.locator('.message-bubble').filter({ hasText: groupText })).toBeVisible()

    await openMessages(bob.page)
    const groupConversation = bob.page.locator('.conversation-item').filter({ hasText: groupName })
    await expect(groupConversation).toBeVisible()
    await groupConversation.click()
    await expect(bob.page.locator('.message-bubble').filter({ hasText: groupText })).toBeVisible()
  } finally {
    await alice.context.close()
    await bob.context.close()
    await carol.context.close()
  }
})
