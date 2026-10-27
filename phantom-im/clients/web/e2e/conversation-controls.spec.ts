import { expect, test } from '@playwright/test'
import { openMessages, setupDirectChatPair } from './helpers'

test('未读、草稿、媒体、会话状态、撤回与本地删除均以真实结果为准', async ({ browser }) => {
  const { alice, bob } = await setupDirectChatPair(browser)
  try {
    await bob.page.getByRole('button', { name: '个人中心', exact: true }).click()
    const unreadText = `未读消息 ${Date.now()}`
    await alice.page.getByLabel('消息内容').fill(unreadText)
    await alice.page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(alice.page.locator('.message-bubble').filter({ hasText: unreadText })).toBeVisible()

    const conversationsResponse = bob.page.waitForResponse((response) =>
      response.url().includes('/api/phantom/conversations?') && response.request().method() === 'GET')
    const readResponse = bob.page.waitForResponse((response) =>
      response.url().includes('/read-positions') && response.request().method() === 'PUT')
    await openMessages(bob.page)
    const listed = await (await conversationsResponse).json()
    const listedConversation = listed.data.items.find((item: any) => item.unread_count === 1)
    expect(listedConversation).toBeTruthy()
    expect((await readResponse).ok()).toBeTruthy()

    const conversation = bob.page.locator('.conversation-item').filter({ hasText: alice.account.nickname })
    await expect(conversation).toBeVisible({ timeout: 30_000 })
    await conversation.click()
    await expect(bob.page.locator('.message-bubble').filter({ hasText: unreadText })).toBeVisible()

    const draftText = `草稿 ${Date.now()}`
    await alice.page.getByLabel('消息内容').fill(draftText)
    await expect(alice.page.getByText('[草稿]', { exact: false })).toBeVisible({ timeout: 10_000 })
    await alice.page.reload()
    await expect(alice.page.getByLabel('消息内容')).toHaveValue(draftText, { timeout: 30_000 })
    await alice.page.getByLabel('消息内容').fill('')

    await alice.page.getByRole('button', { name: '聊天设置', exact: true }).click()
    await alice.page.getByRole('menuitem', { name: '置顶会话', exact: true }).click()
    await alice.page.getByRole('button', { name: '聊天设置', exact: true }).click()
    await expect(alice.page.getByRole('menuitem', { name: '取消置顶', exact: true })).toBeVisible()
    await alice.page.getByRole('menuitem', { name: '消息免打扰', exact: true }).click()
    await alice.page.getByRole('button', { name: '聊天设置', exact: true }).click()
    await expect(alice.page.getByRole('menuitem', { name: '开启提醒', exact: true })).toBeVisible()
    await alice.page.getByRole('button', { name: '聊天设置', exact: true }).click()

    const pixel = Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
      'base64',
    )
    await alice.page.getByTestId('image-input').setInputFiles({
      name: `pixel-${Date.now()}.png`,
      mimeType: 'image/png',
      buffer: pixel,
    })
    await expect(alice.page.locator('.image-attachment')).toBeVisible({ timeout: 30_000 })

    const quoteRow = alice.page.locator('.message-row').filter({ hasText: unreadText })
    await quoteRow.hover()
    await quoteRow.getByRole('button', { name: '消息操作' }).click()
    await alice.page.getByRole('menuitem', { name: '引用回复', exact: true }).click()
    const quoteText = `引用回复 ${Date.now()}`
    await alice.page.getByLabel('消息内容').fill(quoteText)
    await alice.page.getByRole('button', { name: '发送', exact: true }).click()
    const quotedBubble = alice.page.locator('.message-bubble').filter({ hasText: quoteText })
    await expect(quotedBubble.locator('.message-reply-quote')).toBeVisible()

    await quoteRow.hover()
    await quoteRow.getByRole('button', { name: '消息操作' }).click()
    await alice.page.getByRole('menuitem', { name: '转发', exact: true }).click()
    await alice.page.locator('.new-chat-row').first().click()
    await expect(alice.page.getByText(/已转发给/)).toBeVisible()

    const recallText = `撤回 ${Date.now()}`
    await alice.page.getByLabel('消息内容').fill(recallText)
    await alice.page.getByRole('button', { name: '发送', exact: true }).click()
    const recallRow = alice.page.locator('.message-row').filter({ hasText: recallText })
    await expect(recallRow).toBeVisible()
    await recallRow.hover()
    await recallRow.getByRole('button', { name: '消息操作' }).click()
    await alice.page.getByRole('menuitem', { name: '撤回', exact: true }).click()
    await expect(alice.page.getByText('消息已撤回').last()).toBeVisible()

    const matchingBubbles = alice.page.locator('.message-bubble').filter({ hasText: unreadText })
    const beforeDelete = await matchingBubbles.count()
    const deleteRow = alice.page.locator('.message-row').filter({ hasText: unreadText }).first()
    await deleteRow.hover()
    await deleteRow.getByRole('button', { name: '消息操作' }).click()
    await alice.page.getByRole('menuitem', { name: '本地删除', exact: true }).click()
    await expect(matchingBubbles).toHaveCount(beforeDelete - 1)

    await bob.page.reload()
    await expect(bob.page.locator('.message-bubble').filter({ hasText: unreadText }).first()).toBeVisible({ timeout: 30_000 })
    await expect(bob.page.locator('.message-bubble').filter({ hasText: '消息已撤回' }).first()).toBeVisible({ timeout: 30_000 })

    await alice.page.getByRole('button', { name: '聊天设置', exact: true }).click()
    await alice.page.getByRole('menuitem', { name: '删除会话', exact: true }).click()
    await alice.page.getByRole('button', { name: '确认删除', exact: true }).click()
    await expect(alice.page.locator('.conversation-item').filter({ hasText: bob.account.nickname })).toHaveCount(0, { timeout: 15_000 })
    await alice.page.reload()
    await expect(alice.page.locator('.conversation-item').filter({ hasText: bob.account.nickname })).toHaveCount(0, { timeout: 30_000 })
  } finally {
    await alice.context.close()
    await bob.context.close()
  }
})
