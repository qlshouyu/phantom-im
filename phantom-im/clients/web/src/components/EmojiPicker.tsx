import { useState, type MouseEvent } from 'react'

interface EmojiGroup {
  id: string
  label: string
  icon: string
  emojis: string[]
}

export const EMOJI_GROUPS: EmojiGroup[] = [
  {
    id: 'frequent',
    label: '常用',
    icon: '😊',
    emojis: [
      '😊', '😂', '🥰', '😍', '😘', '😎', '🤔', '😭',
      '😡', '🥳', '😴', '🤗', '🤭', '🙃', '😅', '🥹',
      '👍', '👏', '🙏', '🎉', '🔥', '❤️', '✨', '💯',
    ],
  },
  {
    id: 'faces',
    label: '笑脸',
    icon: '😀',
    emojis: [
      '😀', '😃', '😄', '😁', '😆', '😉', '😇', '🙂',
      '😌', '😋', '😛', '😜', '🤪', '😝', '🤫', '🤐',
      '🤨', '😐', '😑', '😶', '😏', '😒', '🙄', '😬',
      '😮‍💨', '😵‍💫', '🤯', '🥺', '😢', '😤', '😠', '🤬',
    ],
  },
  {
    id: 'gestures',
    label: '手势',
    icon: '👍',
    emojis: [
      '👋', '🤚', '🖐️', '✋', '🖖', '👌', '🤌', '🤏',
      '✌️', '🤞', '🫰', '🤟', '🤘', '🤙', '👈', '👉',
      '👆', '👇', '☝️', '👍', '👎', '✊', '👊', '🤛',
      '🤜', '👏', '🙌', '🫶', '👐', '🤲', '🤝', '🙏',
    ],
  },
  {
    id: 'hearts',
    label: '心情',
    icon: '💛',
    emojis: [
      '❤️', '🧡', '💛', '💚', '💙', '💜', '🖤', '🤍',
      '🤎', '💔', '❤️‍🔥', '💕', '💞', '💓', '💗', '💖',
      '💘', '💝', '💟', '☮️', '🫂', '💪', '🧠', '👀',
    ],
  },
  {
    id: 'animals',
    label: '动物',
    icon: '🐱',
    emojis: [
      '🐶', '🐱', '🐭', '🐹', '🐰', '🦊', '🐻', '🐼',
      '🐨', '🐯', '🦁', '🐮', '🐷', '🐸', '🐵', '🐔',
      '🐧', '🐦', '🦄', '🐝', '🦋', '🐢', '🐙', '🐳',
      '🌵', '🌲', '🌻', '🌹', '🌈', '☀️', '🌙', '⭐',
    ],
  },
  {
    id: 'food',
    label: '美食',
    icon: '🍜',
    emojis: [
      '🍎', '🍊', '🍋', '🍉', '🍇', '🍓', '🍒', '🍑',
      '🥭', '🍍', '🥥', '🥝', '🍅', '🥑', '🥦', '🍞',
      '🥐', '🧀', '🍔', '🍟', '🍕', '🌮', '🍜', '🍣',
      '🍰', '🍩', '🍪', '☕', '🧋', '🍺', '🥂', '🍿',
    ],
  },
  {
    id: 'activities',
    label: '活动',
    icon: '🎮',
    emojis: [
      '⚽', '🏀', '🏈', '⚾', '🎾', '🏐', '🎱', '🏓',
      '🏸', '🥊', '🛹', '🎮', '🎲', '🎯', '🎸', '🎧',
      '🎤', '🎬', '📚', '✈️', '🚗', '🏕️', '🎨', '📷',
    ],
  },
  {
    id: 'symbols',
    label: '符号',
    icon: '✨',
    emojis: [
      '✅', '❌', '⚠️', '❓', '❗', '💯', '⭐', '🌟',
      '✨', '⚡', '💥', '💫', '💦', '💨', '🎉', '🎊',
      '🔥', '👑', '💎', '📌', '🔔', '🔒', '🔑', '♻️',
    ],
  },
]

interface EmojiPickerProps {
  onSelect: (emoji: string) => void
}

export function EmojiPicker({ onSelect }: EmojiPickerProps) {
  const [activeGroupId, setActiveGroupId] = useState(EMOJI_GROUPS[0].id)
  const activeGroup = EMOJI_GROUPS.find((group) => group.id === activeGroupId) ?? EMOJI_GROUPS[0]

  function keepComposerSelection(event: MouseEvent<HTMLButtonElement>) {
    event.preventDefault()
  }

  return (
    <div className="emoji-picker" role="dialog" aria-label="表情选择器">
      <header className="emoji-picker-header">
        <strong>{activeGroup.label}</strong>
        <small>{activeGroup.emojis.length} 个表情</small>
      </header>
      <div className="emoji-tabs" role="tablist" aria-label="表情分类">
        {EMOJI_GROUPS.map((group) => (
          <button
            type="button"
            key={group.id}
            id={`emoji-tab-${group.id}`}
            role="tab"
            aria-label={group.label}
            aria-controls="emoji-tabpanel"
            aria-selected={group.id === activeGroup.id}
            className={group.id === activeGroup.id ? 'is-active' : ''}
            onClick={() => setActiveGroupId(group.id)}
          >
            <span aria-hidden="true">{group.icon}</span>
          </button>
        ))}
      </div>
      <div
        id="emoji-tabpanel"
        className="emoji-grid"
        role="tabpanel"
        aria-labelledby={`emoji-tab-${activeGroup.id}`}
      >
        {activeGroup.emojis.map((emoji) => (
          <button
            type="button"
            key={emoji}
            aria-label={`插入 ${emoji}`}
            title={emoji}
            onMouseDown={keepComposerSelection}
            onClick={() => onSelect(emoji)}
          >
            {emoji}
          </button>
        ))}
      </div>
    </div>
  )
}
