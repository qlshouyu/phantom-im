import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { CountBadge, formatCountBadge } from './CountBadge'

describe('CountBadge', () => {
  it('在 99 阈值内显示真实数量，超过阈值显示 99+', () => {
    expect(formatCountBadge(1)).toBe('1')
    expect(formatCountBadge(99)).toBe('99')
    expect(formatCountBadge(100)).toBe('99+')
    expect(formatCountBadge(1000)).toBe('99+')
    expect(formatCountBadge(Number.MAX_SAFE_INTEGER)).toBe('99+')
  })

  it('保留真实数量作为无障碍描述并渲染视觉变体', () => {
    render(<CountBadge count={128} label="未读消息" tone="danger" size="sm" />)

    const badge = screen.getByLabelText('未读消息：128')
    expect(badge).toHaveTextContent('99+')
    expect(badge).toHaveAttribute('title', '未读消息：128')
    expect(badge).toHaveClass('count-badge', 'count-badge--danger', 'count-badge--sm')
  })
})
