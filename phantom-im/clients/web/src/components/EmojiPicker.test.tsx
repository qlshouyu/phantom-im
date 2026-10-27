import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { EmojiPicker } from './EmojiPicker'

describe('EmojiPicker', () => {
  it('提供多分类表情，并将选中的表情回传给输入区', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()

    render(<EmojiPicker onSelect={onSelect} />)

    const dialog = screen.getByRole('dialog', { name: '表情选择器' })
    expect(within(dialog).getByRole('tab', { name: '常用' })).toHaveAttribute('aria-selected', 'true')
    expect(within(dialog).getAllByRole('button', { name: /^插入 / }).length).toBeGreaterThan(1)

    await user.click(within(dialog).getByRole('tab', { name: '手势' }))
    await user.click(within(dialog).getByRole('button', { name: '插入 👍' }))

    expect(onSelect).toHaveBeenCalledWith('👍')
  })
})
