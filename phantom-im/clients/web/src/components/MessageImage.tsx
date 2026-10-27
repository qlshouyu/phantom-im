import { useEffect, useState, type CSSProperties } from 'react'
import type { Attachment } from '../domain/types'
import { Icon } from '../icons'
import { Modal } from './ui'

export type MediaBlobLoader = (
  mediaId: string,
  variant: 'thumb' | 'original',
) => Promise<Blob>

type ImageLoadState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; src: string }
  | { status: 'error' }

function useMediaObjectUrl(
  mediaId: string | undefined,
  variant: 'thumb' | 'original',
  loadMedia: MediaBlobLoader,
  enabled = true,
) {
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<ImageLoadState>({ status: 'idle' })

  useEffect(() => {
    if (!enabled || !mediaId) {
      setState({ status: 'idle' })
      return
    }
    let cancelled = false
    let objectUrl: string | undefined
    setState({ status: 'loading' })
    void loadMedia(mediaId, variant)
      .then((blob) => {
        if (cancelled) return
        objectUrl = URL.createObjectURL(blob)
        setState({ status: 'ready', src: objectUrl })
      })
      .catch(() => {
        if (!cancelled) setState({ status: 'error' })
      })
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [attempt, enabled, loadMedia, mediaId, variant])

  return {
    ...state,
    retry: () => setAttempt((current) => current + 1),
  }
}

export function MessageImage({
  attachment,
  loadMedia,
  onOpen,
}: {
  attachment: Attachment
  loadMedia: MediaBlobLoader
  onOpen: () => void
}) {
  const image = useMediaObjectUrl(attachment.mediaId, 'thumb', loadMedia)
  const title = attachment.title || '图片'
  const aspectRatio = attachment.width && attachment.height
    ? `${attachment.width} / ${attachment.height}`
    : undefined
  const aspectStyle = aspectRatio ? { aspectRatio } as CSSProperties : undefined

  return (
    <div className="image-attachment">
      {image.status === 'loading' && (
        <div
          className="message-image-skeleton"
          style={aspectStyle}
          role="status"
          aria-label="正在加载图片"
        >
          <span className="message-image-shimmer" />
        </div>
      )}
      {image.status === 'ready' && (
        <button
          type="button"
          className="message-image-open"
          aria-label={`查看原图：${title}`}
          onClick={onOpen}
        >
          <img className="message-image" src={image.src} alt={title} decoding="async" />
          <span className="message-image-zoom">
            <Icon name="image" size={14} />
            查看原图
          </span>
        </button>
      )}
      {(image.status === 'error' || image.status === 'idle') && (
        <div className={`message-image-error ${image.status === 'error' ? 'is-error' : ''}`} role="status">
          <span className="message-image-error-icon"><Icon name="image" size={20} /></span>
          <strong>{image.status === 'error' ? '图片加载失败' : '图片信息不完整'}</strong>
          {image.status === 'error' && (
            <button type="button" onClick={image.retry}>重新加载</button>
          )}
        </div>
      )}
      <div className="attachment-caption">
        <strong>{title}</strong>
        <small>{attachment.meta}</small>
      </div>
    </div>
  )
}

export function ImagePreviewModal({
  open,
  attachment,
  loadMedia,
  onClose,
}: {
  open: boolean
  attachment?: Attachment
  loadMedia: MediaBlobLoader
  onClose: () => void
}) {
  const image = useMediaObjectUrl(
    attachment?.mediaId,
    'original',
    loadMedia,
    open && Boolean(attachment?.mediaId),
  )
  const title = attachment?.title || '图片'

  return (
    <Modal
      open={open && Boolean(attachment)}
      title="图片预览"
      description={[attachment?.title, attachment?.meta].filter(Boolean).join(' · ')}
      onClose={onClose}
      width={920}
    >
      <div className="image-preview-panel" aria-live="polite">
        {image.status === 'loading' && (
          <div className="image-preview-loading" role="status" aria-label="正在加载原图">
            <span className="message-image-shimmer" />
            <small>正在加载原图…</small>
          </div>
        )}
        {image.status === 'ready' && (
          <img className="image-preview-original" src={image.src} alt={`${title} 原图`} decoding="async" />
        )}
        {(image.status === 'error' || image.status === 'idle') && (
          <div className="image-preview-error" role="status">
            <span className="message-image-error-icon"><Icon name="image" size={24} /></span>
            <strong>{image.status === 'error' ? '原图加载失败' : '原图暂不可用'}</strong>
            {image.status === 'error' && (
              <button type="button" className="secondary-button" onClick={image.retry}>重新加载</button>
            )}
          </div>
        )}
      </div>
    </Modal>
  )
}
