import {
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  useEffect,
} from 'react'
import { createPortal } from 'react-dom'
import type { AvatarTone } from '../data'
import { Icon, type IconName } from '../icons'

interface AvatarProps {
  chars: string[]
  tone: AvatarTone
  size?: number
  group?: boolean
  online?: boolean
  className?: string
}

export function Avatar({
  chars,
  tone,
  size = 48,
  group = false,
  online = false,
  className = '',
}: AvatarProps) {
  return (
    <span
      className={`avatar tone-${tone} ${group ? 'avatar--group' : ''} ${className}`}
      style={{ width: size, height: size, borderRadius: group ? Math.round(size * 0.31) : '50%' }}
      aria-hidden="true"
    >
      {group ? (
        chars.slice(0, 4).map((char, index) => (
          <span key={`${char}-${index}`} className={`avatar-tile avatar-tile-${index + 1}`}>
            {char}
          </span>
        ))
      ) : (
        <span className="avatar-char">{chars[0]}</span>
      )}
      {online && !group && <span className="avatar-online" />}
    </span>
  )
}

interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  icon: IconName
  label: string
  size?: number
  variant?: 'plain' | 'soft' | 'dark'
}

export function IconButton({
  icon,
  label,
  size = 20,
  variant = 'plain',
  className = '',
  ...props
}: IconButtonProps) {
  return (
    <button
      type="button"
      className={`icon-button icon-button--${variant} ${className}`}
      aria-label={label}
      title={label}
      {...props}
    >
      <Icon name={icon} size={size} />
    </button>
  )
}

interface SearchFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  compact?: boolean
  onClear?: () => void
}

export function SearchField({ compact = false, className = '', onClear, ...props }: SearchFieldProps) {
  return (
    <label className={`search-field ${compact ? 'search-field--compact' : ''} ${className}`}>
      <Icon name="search" size={18} />
      <input type="search" {...props} />
      {props.value && onClear && (
        <button type="button" className="search-clear" aria-label="清空搜索" onClick={onClear}>
          <Icon name="close" size={14} />
        </button>
      )}
    </label>
  )
}

interface ModalProps {
  open: boolean
  title: string
  description?: string
  children: ReactNode
  onClose: () => void
  width?: number
}

export function Modal({ open, title, description, children, onClose, width = 460 }: ModalProps) {
  useEffect(() => {
    if (!open) return
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', handleKeyDown)
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [open, onClose])

  if (!open) return null

  return createPortal(
    <div className="modal-backdrop" role="presentation" onMouseDown={onClose}>
      <section
        className="modal-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="modal-title"
        style={{ width }}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="modal-header">
          <div>
            <h2 id="modal-title">{title}</h2>
            {description && <p>{description}</p>}
          </div>
          <IconButton icon="close" label="关闭" onClick={onClose} />
        </header>
        {children}
      </section>
    </div>,
    document.body,
  )
}

interface SwitchProps {
  checked: boolean
  onChange: (checked: boolean) => void
  label: string
}

export function Switch({ checked, onChange, label }: SwitchProps) {
  return (
    <button
      type="button"
      className={`switch ${checked ? 'is-checked' : ''}`}
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={() => onChange(!checked)}
    >
      <span />
    </button>
  )
}

export function EmptyState({ title, description }: { title: string; description: string }) {
  return (
    <div className="empty-state">
      <span className="empty-state-icon">
        <Icon name="search" size={24} />
      </span>
      <strong>{title}</strong>
      <p>{description}</p>
    </div>
  )
}
