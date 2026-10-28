import type { HTMLAttributes } from 'react'

export type CountBadgeTone = 'danger' | 'accent' | 'neutral'
export type CountBadgeSize = 'sm' | 'md'

interface CountBadgeProps extends Omit<HTMLAttributes<HTMLSpanElement>, 'children'> {
  count: number
  max?: number
  label?: string
  tone?: CountBadgeTone
  size?: CountBadgeSize
}

export function formatCountBadge(count: number, max = 99): string {
  return count > max ? `${max}+` : String(Math.max(0, count))
}

export function CountBadge({
  count,
  max = 99,
  label,
  tone = 'danger',
  size = 'md',
  className = '',
  ...props
}: CountBadgeProps) {
  const accessibleLabel = label ? `${label}：${count}` : `数量：${count}`
  return (
    <span
      {...props}
      className={`count-badge count-badge--${tone} count-badge--${size} ${className}`.trim()}
      aria-label={accessibleLabel}
      title={accessibleLabel}
    >
      {formatCountBadge(count, max)}
    </span>
  )
}
