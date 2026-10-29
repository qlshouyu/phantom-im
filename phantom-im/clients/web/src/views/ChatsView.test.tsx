import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChatsView from './ChatsView'

const { gateway } = vi.hoisted(() => ({
  gateway: {
    currentUserId: 'u_alice',
    listConversations: vi.fn(),
    history: vi.fn(),
    on: vi.fn(),
    uploadMedia: vi.fn(),
    sendMedia: vi.fn(),
    messages: vi.fn(),
  },
}))

vi.mock('../app/AppProvider', () => ({
  useApp: () => ({ gateway, state: { phase: 'ready' } }),
}))

describe('发送文件', () => {
  beforeEach(() => {
    gateway.listConversations.mockResolvedValue([{
      id: 'c1', name: '测试会话', kind: 'direct', subtitle: '', lastMessage: '', time: '',
      timestamp: 0, unread: 0, pinned: false, muted: false, mentionMe: false,
      draft: '', readOnly: false, maxConvSeq: 0, readConvSeq: 0,
      online: false, tone: 'mint', avatarChars: ['测'],
    }])
    gateway.history.mockResolvedValue({ messages: [], hasMore: false })
    gateway.on.mockReturnValue(() => {})
    gateway.uploadMedia.mockResolvedValue({ mediaId: 'm1' })
    gateway.sendMedia.mockResolvedValue({})
    gateway.messages.mockReturnValue([])
    Element.prototype.scrollIntoView = vi.fn()
  })

  it('超出 500MB 时在读取文件和上传前提示', async () => {
    render(<ChatsView onConversationRead={vi.fn()} onUnreadRefresh={vi.fn()} />)
    const input = await screen.findByTestId('file-input')
    const file = new File(['{}'], '配置.json', { type: 'application/json' })
    const readBytes = vi.fn()
    Object.defineProperty(file, 'size', { value: 500 * 1024 * 1024 + 1 })
    Object.defineProperty(file, 'arrayBuffer', { value: readBytes })
    fireEvent.change(input, { target: { files: [file] } })
    expect(await screen.findByText('文件超过 500MB，无法发送')).toBeInTheDocument()
    expect(readBytes).not.toHaveBeenCalled()
    expect(gateway.uploadMedia).not.toHaveBeenCalled()
    expect(gateway.sendMedia).not.toHaveBeenCalled()
  })

  it.each([
    ['配置.json', 'application/json', 2],
    ['movie.mp4', 'video/mp4', 500 * 1024 * 1024],
  ])('从文件入口发送 %s 保留 FILE 类型', async (name, mime, size) => {
    render(<ChatsView onConversationRead={vi.fn()} onUnreadRefresh={vi.fn()} />)
    const input = await screen.findByTestId('file-input')
    const file = new File(['{}'], name, { type: mime })
    Object.defineProperty(file, 'size', { value: size })
    Object.defineProperty(file, 'arrayBuffer', { value: vi.fn().mockResolvedValue(new ArrayBuffer(2)) })
    fireEvent.change(input, { target: { files: [file] } })
    await waitFor(() => expect(gateway.sendMedia).toHaveBeenCalledWith('c1', 'FILE', { mediaId: 'm1' }))
    expect(gateway.uploadMedia).toHaveBeenCalledWith(file, expect.objectContaining({
      fileName: name, mediaType: 'FILE', sha256: expect.stringMatching(/^[a-f0-9]{64}$/),
    }))
  })
})
