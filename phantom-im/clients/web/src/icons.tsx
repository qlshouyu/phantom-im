import type { SVGProps } from 'react'

export type IconName =
  | 'chat'
  | 'contacts'
  | 'user'
  | 'search'
  | 'plus'
  | 'more'
  | 'pin'
  | 'bellOff'
  | 'image'
  | 'smile'
  | 'paperclip'
  | 'send'
  | 'phone'
  | 'video'
  | 'chevronRight'
  | 'chevronDown'
  | 'edit'
  | 'qr'
  | 'shield'
  | 'palette'
  | 'info'
  | 'logout'
  | 'users'
  | 'group'
  | 'close'
  | 'check'
  | 'mapPin'
  | 'mail'
  | 'tag'
  | 'copy'
  | 'message'
  | 'arrowLeft'
  | 'arrowRight'
  | 'eye'
  | 'eyeOff'
  | 'star'
  | 'grid'
  | 'link'
  | 'file'
  | 'smartphone'
  | 'lock'
  | 'sparkles'
  | 'camera'
  | 'volume'
  | 'moon'
  | 'help'
  | 'folder'
  | 'refresh'
  | 'trash'

interface IconProps extends Omit<SVGProps<SVGSVGElement>, 'name'> {
  name: IconName
  size?: number
}

export function Icon({ name, size = 20, strokeWidth = 1.8, ...props }: IconProps) {
  const common = {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
    ...props,
  }

  switch (name) {
    case 'chat':
      return (
        <svg {...common}>
          <path d="M20.5 11.5a7.9 7.9 0 0 1-8.2 7.9 8.9 8.9 0 0 1-3.9-.9L3.5 20l1.4-4.8a8 8 0 1 1 15.6-3.7Z" />
          <path d="M8 11.5h.01M12 11.5h.01M16 11.5h.01" strokeWidth="2.4" />
        </svg>
      )
    case 'contacts':
      return (
        <svg {...common}>
          <rect x="4.5" y="3.5" width="15" height="17" rx="3" />
          <circle cx="12" cy="9" r="2.4" />
          <path d="M8.2 16.2c.5-2 1.9-3.1 3.8-3.1s3.3 1.1 3.8 3.1" />
        </svg>
      )
    case 'user':
      return (
        <svg {...common}>
          <circle cx="12" cy="8" r="3.5" />
          <path d="M5.2 20c.6-3.4 3-5.4 6.8-5.4s6.2 2 6.8 5.4" />
        </svg>
      )
    case 'search':
      return (
        <svg {...common}>
          <circle cx="10.7" cy="10.7" r="6.2" />
          <path d="m15.4 15.4 4.1 4.1" />
        </svg>
      )
    case 'plus':
      return (
        <svg {...common}>
          <path d="M12 5v14M5 12h14" />
        </svg>
      )
    case 'more':
      return (
        <svg {...common}>
          <circle cx="5" cy="12" r="1" fill="currentColor" stroke="none" />
          <circle cx="12" cy="12" r="1" fill="currentColor" stroke="none" />
          <circle cx="19" cy="12" r="1" fill="currentColor" stroke="none" />
        </svg>
      )
    case 'pin':
      return (
        <svg {...common}>
          <path d="m8.5 4 7 7M9.5 3.5l11 11-3 1.2-4.8 4.8-3.1-3.1-4.7 2.2 2.2-4.7-3.1-3.1 4.8-4.8 1.2-3Z" />
        </svg>
      )
    case 'bellOff':
      return (
        <svg {...common}>
          <path d="M9 18h6M6.3 9.8V9a5.7 5.7 0 0 1 9.8-4M18 11.5V13l1.5 2.5H7M3 3l18 18" />
        </svg>
      )
    case 'image':
      return (
        <svg {...common}>
          <rect x="3.5" y="4.5" width="17" height="15" rx="2.5" />
          <circle cx="9" cy="9.5" r="1.5" />
          <path d="m5 17 4.5-4.5 3 3 2.3-2.3 4.2 4.2" />
        </svg>
      )
    case 'smile':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="8.5" />
          <path d="M8.5 14.2c.9 1.3 2.1 1.9 3.5 1.9s2.6-.6 3.5-1.9" />
          <path d="M9 9.5h.01M15 9.5h.01" strokeWidth="2.4" />
        </svg>
      )
    case 'paperclip':
      return (
        <svg {...common}>
          <path d="m9.4 12.3 4.8-4.8a3.2 3.2 0 0 1 4.5 4.5l-6.5 6.5a5 5 0 0 1-7.1-7.1l6.4-6.4a3.3 3.3 0 0 1 4.7 4.7l-6.2 6.2a1.8 1.8 0 0 1-2.5-2.5l5.5-5.5" />
        </svg>
      )
    case 'send':
      return (
        <svg {...common}>
          <path d="m21 3-7.7 18-3.1-7.2L3 10.7 21 3Z" />
          <path d="m10.2 13.8 4.2-4.2" />
        </svg>
      )
    case 'phone':
      return (
        <svg {...common}>
          <path d="M7.2 3.5 4.8 5.8c-1.2 1.2-.6 4.5 3.5 8.2s7.4 4.6 8.7 3.3l2-2-3.8-3-1.8 1.7c-1.2-.3-2.5-1.1-3.8-2.4S7.8 9 7.5 7.9l1.7-1.8-2-2.6Z" />
        </svg>
      )
    case 'video':
      return (
        <svg {...common}>
          <rect x="3" y="6.5" width="12.5" height="11" rx="2.5" />
          <path d="m15.5 11 5.5-3v8l-5.5-3" />
        </svg>
      )
    case 'chevronRight':
      return (
        <svg {...common}>
          <path d="m9 5 7 7-7 7" />
        </svg>
      )
    case 'chevronDown':
      return (
        <svg {...common}>
          <path d="m5 9 7 7 7-7" />
        </svg>
      )
    case 'edit':
      return (
        <svg {...common}>
          <path d="m13.5 5.5 5 5M4 20l1-5 11.1-11.1a2.1 2.1 0 0 1 3 3L8 18l-4 2Z" />
        </svg>
      )
    case 'qr':
      return (
        <svg {...common}>
          <path d="M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4z" />
          <path d="M14 14h2v2h-2zM18 14h2v4h-2zM14 18h4v2h-4z" />
        </svg>
      )
    case 'shield':
      return (
        <svg {...common}>
          <path d="M12 3 5 6v5.4c0 4.2 2.7 7.9 7 9.6 4.3-1.7 7-5.4 7-9.6V6l-7-3Z" />
          <path d="m9 12 2 2 4-4" />
        </svg>
      )
    case 'palette':
      return (
        <svg {...common}>
          <path d="M12 3a9 9 0 0 0 0 18h1.4a1.8 1.8 0 0 0 1.1-3.2 1.7 1.7 0 0 1 1-3h2a3.5 3.5 0 0 0 3.5-3.5C21 6.3 17 3 12 3Z" />
          <path d="M7.5 10h.01M9.5 6.8h.01M14 6.5h.01M16.2 9h.01" strokeWidth="2.4" />
        </svg>
      )
    case 'info':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="8.5" />
          <path d="M12 10.5V16M12 8h.01" strokeWidth="2.2" />
        </svg>
      )
    case 'logout':
      return (
        <svg {...common}>
          <path d="M10 4H5.5A1.5 1.5 0 0 0 4 5.5v13A1.5 1.5 0 0 0 5.5 20H10M14 8l4 4-4 4M8 12h10" />
        </svg>
      )
    case 'users':
      return (
        <svg {...common}>
          <circle cx="9" cy="9" r="3" />
          <path d="M3.5 19c.5-3 2.3-4.7 5.5-4.7s5 1.7 5.5 4.7M15 6.2a3 3 0 0 1 0 5.6M16.2 14.5c2.6.2 4 1.7 4.4 4.5" />
        </svg>
      )
    case 'group':
      return (
        <svg {...common}>
          <circle cx="8.2" cy="9" r="2.8" />
          <circle cx="16.5" cy="8" r="2.2" />
          <path d="M3.5 18.5c.4-3 2.2-4.8 5.3-4.8s4.9 1.8 5.3 4.8M14.2 14.2c2.9 0 4.7 1.5 5.3 4.3" />
        </svg>
      )
    case 'close':
      return (
        <svg {...common}>
          <path d="m6 6 12 12M18 6 6 18" />
        </svg>
      )
    case 'check':
      return (
        <svg {...common}>
          <path d="m5 12.5 4.5 4.5L19 7" />
        </svg>
      )
    case 'mapPin':
      return (
        <svg {...common}>
          <path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z" />
          <circle cx="12" cy="10" r="2.3" />
        </svg>
      )
    case 'mail':
      return (
        <svg {...common}>
          <rect x="3" y="5" width="18" height="14" rx="2.5" />
          <path d="m4 7 8 6 8-6" />
        </svg>
      )
    case 'tag':
      return (
        <svg {...common}>
          <path d="M4 5.5V12l8.5 8.5 7-7L11 5H4Z" />
          <circle cx="8" cy="8.5" r="1" fill="currentColor" stroke="none" />
        </svg>
      )
    case 'copy':
      return (
        <svg {...common}>
          <rect x="8" y="8" width="11" height="11" rx="2" />
          <path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3" />
        </svg>
      )
    case 'message':
      return (
        <svg {...common}>
          <path d="M20 11.3a7.2 7.2 0 0 1-7.4 7.2 8.5 8.5 0 0 1-3.3-.8L4 19.5l1.7-4.4a7 7 0 0 1-1.2-3.8A7.2 7.2 0 0 1 12 4a7.2 7.2 0 0 1 8 7.3Z" />
        </svg>
      )
    case 'arrowLeft':
      return (
        <svg {...common}>
          <path d="m15 5-7 7 7 7M8 12h12" />
        </svg>
      )
    case 'arrowRight':
      return (
        <svg {...common}>
          <path d="m9 5 7 7-7 7M4 12h12" />
        </svg>
      )
    case 'eye':
      return (
        <svg {...common}>
          <path d="M3 12s3.3-5.5 9-5.5 9 5.5 9 5.5-3.3 5.5-9 5.5S3 12 3 12Z" />
          <circle cx="12" cy="12" r="2.5" />
        </svg>
      )
    case 'eyeOff':
      return (
        <svg {...common}>
          <path d="M4.5 7.2C3.5 8.4 3 9.5 3 9.5S6.3 15 12 15c1.3 0 2.4-.3 3.5-.7M9.8 6.7A9.6 9.6 0 0 1 12 6.5c5.7 0 9 5.5 9 5.5s-1.1 1.9-3.2 3.4M3 3l18 18" />
          <path d="M10.3 10.3a2.5 2.5 0 0 0 3.4 3.4" />
        </svg>
      )
    case 'star':
      return (
        <svg {...common}>
          <path d="m12 3 2.7 5.6 6.1.9-4.4 4.3 1 6.1-5.4-2.9-5.4 2.9 1-6.1-4.4-4.3 6.1-.9L12 3Z" />
        </svg>
      )
    case 'grid':
      return (
        <svg {...common}>
          <rect x="4" y="4" width="6" height="6" rx="1" />
          <rect x="14" y="4" width="6" height="6" rx="1" />
          <rect x="4" y="14" width="6" height="6" rx="1" />
          <rect x="14" y="14" width="6" height="6" rx="1" />
        </svg>
      )
    case 'link':
      return (
        <svg {...common}>
          <path d="m9.5 14.5 5-5M7.2 16.8l-1 1a3.5 3.5 0 0 1-5-5l4-4a3.5 3.5 0 0 1 5 0M16.8 7.2l1-1a3.5 3.5 0 0 1 5 5l-4 4a3.5 3.5 0 0 1-5 0" />
        </svg>
      )
    case 'file':
      return (
        <svg {...common}>
          <path d="M6 3.5h8l4 4V20H6z" />
          <path d="M14 3.5v4h4M9 13h6M9 16h4" />
        </svg>
      )
    case 'smartphone':
      return (
        <svg {...common}>
          <rect x="6.5" y="2.5" width="11" height="19" rx="2.5" />
          <path d="M10 5h4M11 18.5h2" />
        </svg>
      )
    case 'lock':
      return (
        <svg {...common}>
          <rect x="5" y="10" width="14" height="11" rx="2.5" />
          <path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3" />
        </svg>
      )
    case 'sparkles':
      return (
        <svg {...common}>
          <path d="m12 3 1.2 3.8L17 8l-3.8 1.2L12 13l-1.2-3.8L7 8l3.8-1.2L12 3ZM19 14l.7 2.3L22 17l-2.3.7L19 20l-.7-2.3L16 17l2.3-.7L19 14ZM5 13l.7 2.3L8 16l-2.3.7L5 19l-.7-2.3L2 16l2.3-.7L5 13Z" />
        </svg>
      )
    case 'camera':
      return (
        <svg {...common}>
          <path d="M4 8h3l1.3-2h7.4L17 8h3v11H4Z" />
          <circle cx="12" cy="13.5" r="3" />
        </svg>
      )
    case 'volume':
      return (
        <svg {...common}>
          <path d="M4 10v4h3l4 3V7l-4 3H4ZM15 9.5a4 4 0 0 1 0 5M17.5 7a7 7 0 0 1 0 10" />
        </svg>
      )
    case 'moon':
      return (
        <svg {...common}>
          <path d="M20 15.5A8.5 8.5 0 0 1 8.5 4 8.5 8.5 0 1 0 20 15.5Z" />
        </svg>
      )
    case 'help':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="8.5" />
          <path d="M9.7 9a2.5 2.5 0 1 1 3.7 2.2c-.9.5-1.4 1-1.4 2M12 17h.01" />
        </svg>
      )
    case 'folder':
      return (
        <svg {...common}>
          <path d="M3 6.5h6l1.5 2H21v10.8H3z" />
        </svg>
      )
    case 'refresh':
      return (
        <svg {...common}>
          <path d="M20 6v5h-5" /><path d="M4 18v-5h5" />
          <path d="M18.4 9A7 7 0 0 0 6.2 6.2L4 11" />
          <path d="M5.6 15A7 7 0 0 0 17.8 17.8L20 13" />
        </svg>
      )
    case 'trash':
      return (
        <svg {...common}>
          <path d="M4 7h16" /><path d="M9 7V4h6v3" />
          <path d="M6.5 7l1 13h9l1-13" /><path d="M10 11v5M14 11v5" />
        </svg>
      )
  }
}

export function PhantomMark({ size = 30 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 36 36" fill="none" aria-hidden="true">
      <path
        d="M18 4C10.8 4 5 9.3 5 15.9c0 3.5 1.6 6.6 4.2 8.8L7.6 31l7-3.1c1.1.3 2.2.4 3.4.4 7.2 0 13-5.3 13-11.9S25.2 4 18 4Z"
        fill="currentColor"
      />
      <circle cx="13" cy="16" r="1.6" fill="#1e2923" />
      <circle cx="18" cy="16" r="1.6" fill="#1e2923" />
      <circle cx="23" cy="16" r="1.6" fill="#1e2923" />
    </svg>
  )
}
