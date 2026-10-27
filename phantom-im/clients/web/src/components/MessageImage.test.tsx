import { useState } from 'react'
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ImagePreviewModal, MessageImage } from './MessageImage'

const originalCreateObjectURL = URL.createObjectURL
const originalRevokeObjectURL = URL.revokeObjectURL
let createObjectURL: ReturnType<typeof vi.fn>
let revokeObjectURL: ReturnType<typeof vi.fn>

beforeEach(() => {
  let sequence = 0
  createObjectURL = vi.fn(() => `blob:media-${++sequence}`)
  revokeObjectURL = vi.fn()
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectURL })
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectURL })
})

afterAll(() => {
  if (originalCreateObjectURL) {
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: originalCreateObjectURL })
  } else {
    Reflect.deleteProperty(URL, 'createObjectURL')
  }
  if (originalRevokeObjectURL) {
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: originalRevokeObjectURL })
  } else {
    Reflect.deleteProperty(URL, 'revokeObjectURL')
  }
})

describe('MessageImage', () => {
  it('加载鉴权缩略图，并在点击后按需加载原图预览', async () => {
    const user = userEvent.setup()
    const loadMedia = vi.fn(async (_mediaId: string, variant: 'thumb' | 'original') =>
      new Blob([variant], { type: 'image/jpeg' }))
    const attachment = {
      title: 'photo.jpg',
      meta: 'image/jpeg · 2.0 KB',
      mediaId: 'media_1',
      width: 640,
      height: 480,
    }

    function Harness() {
      const [previewOpen, setPreviewOpen] = useState(false)
      return (
        <>
          <MessageImage attachment={attachment} loadMedia={loadMedia} onOpen={() => setPreviewOpen(true)} />
          <ImagePreviewModal
            open={previewOpen}
            attachment={attachment}
            loadMedia={loadMedia}
            onClose={() => setPreviewOpen(false)}
          />
        </>
      )
    }

    const view = render(<Harness />)
    const thumbnail = await screen.findByRole('img', { name: 'photo.jpg' })
    expect(thumbnail).toHaveAttribute('src', 'blob:media-1')
    expect(loadMedia).toHaveBeenNthCalledWith(1, 'media_1', 'thumb')

    await user.click(screen.getByRole('button', { name: '查看原图：photo.jpg' }))

    const dialog = await screen.findByRole('dialog')
    const original = await within(dialog).findByRole('img', { name: 'photo.jpg 原图' })
    expect(original).toHaveAttribute('src', 'blob:media-2')
    expect(loadMedia).toHaveBeenNthCalledWith(2, 'media_1', 'original')

    view.unmount()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:media-1')
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:media-2')
  })

  it('缩略图加载失败后可重新加载', async () => {
    const user = userEvent.setup()
    const loadMedia = vi.fn()
      .mockRejectedValueOnce(new Error('network failed'))
      .mockResolvedValueOnce(new Blob(['thumb'], { type: 'image/jpeg' }))

    render(
      <MessageImage
        attachment={{ title: 'photo.jpg', meta: 'image/jpeg', mediaId: 'media_2' }}
        loadMedia={loadMedia}
        onOpen={vi.fn()}
      />,
    )

    expect(await screen.findByText('图片加载失败')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重新加载' }))

    await waitFor(() => expect(screen.getByRole('img', { name: 'photo.jpg' })).toBeInTheDocument())
    expect(loadMedia).toHaveBeenCalledTimes(2)
  })
})
