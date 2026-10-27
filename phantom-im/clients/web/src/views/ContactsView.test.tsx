import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { AddContactModal } from './ContactsView'

describe('AddContactModal', () => {
  it('先解析登录名或 Phantom ID，确认用户资料后再发送好友申请', async () => {
    const user = userEvent.setup()
    const onResolveFriend = vi.fn(async () => ({
      phantomUserId: 'u_bob',
      loginName: 'bob_1',
      nickname: 'Bob',
      avatarUrl: 'https://example.com/bob.png',
    }))
    const onCreateFriend = vi.fn(async () => {})

    render(
      <AddContactModal
        open
        kind="friend"
        prefill=""
        friends={[]}
        busy={false}
        onKindChange={vi.fn()}
        onClose={vi.fn()}
        onResolveFriend={onResolveFriend}
        onCreateFriend={onCreateFriend}
        onCreateGroup={vi.fn()}
      />,
    )

    await user.type(screen.getByLabelText('登录名或 Phantom ID'), 'bob_1')
    await user.click(screen.getByRole('button', { name: '下一步' }))

    expect(onResolveFriend).toHaveBeenCalledWith('bob_1')
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('确认好友信息')).toBeInTheDocument()
    expect(within(dialog).getByText('Bob')).toBeInTheDocument()
    expect(within(dialog).getByText('bob_1')).toBeInTheDocument()
    expect(within(dialog).getByText('u_bob')).toBeInTheDocument()
    expect(dialog.querySelector('.avatar-image')).toHaveAttribute('src', 'https://example.com/bob.png')

    await user.type(screen.getByLabelText('验证消息'), '你好，我是 Alice')
    await user.click(screen.getByRole('button', { name: '发送申请' }))

    expect(onCreateFriend).toHaveBeenCalledWith('u_bob', '你好，我是 Alice')
  })
})
