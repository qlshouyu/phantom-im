import { useEffect, useMemo, useRef, useState } from "react";
import type { ChatMessage } from "@phantom/sdk-web";
import { getClient, loadConversations, loadOlder, openConversation, sendFile, sendText } from "../../services/sdk-adapter";
import { useAppStore } from "../../store/app-store";
import type { Conversation } from "../../types";

function conversationTitle(conversation: Conversation): string {
  return String(conversation.title ?? conversation.peer_nickname ?? conversation.conv_id);
}

function messageContent(message: ChatMessage): string {
  if (message.recalled) return "消息已撤回";
  if (message.state === "burned") return "消息已焚毁";
  return message.text || `[${message.kind}]`;
}

export function ChatView() {
  const account = useAppStore((state) => state.account)!;
  const conversations = useAppStore((state) => state.conversations);
  const activeConvId = useAppStore((state) => state.activeConvId);
  const messagesByConv = useAppStore((state) => state.messagesByConv);
  const typingByConv = useAppStore((state) => state.typingByConv);
  const presenceByUser = useAppStore((state) => state.presenceByUser);
  const highlightConvSeq = useAppStore((state) => state.highlightConvSeq);
  const drafts = useAppStore((state) => state.drafts);
  const setConversations = useAppStore((state) => state.setConversations);
  const patchConversation = useAppStore((state) => state.patchConversation);
  const setDraft = useAppStore((state) => state.setDraft);
  const upsertMessage = useAppStore((state) => state.upsertMessage);
  const mergeMessages = useAppStore((state) => state.mergeMessages);
  const [input, setInput] = useState("");
  const [error, setError] = useState("");
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  const [quoteTarget, setQuoteTarget] = useState<ChatMessage | null>(null);
  const [menuConvId, setMenuConvId] = useState<string | null>(null);
  const [selectedMsgIds, setSelectedMsgIds] = useState<Set<string>>(new Set());
  const [conversationCursor, setConversationCursor] = useState<string | null>(null);
  const [hasMoreConversations, setHasMoreConversations] = useState(false);
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const feedRef = useRef<HTMLDivElement>(null);
  const preserveScrollRef = useRef(false);
  const uploadAbortRef = useRef<AbortController | null>(null);
  const currentMessages = activeConvId ? messagesByConv[activeConvId] ?? [] : [];
  const activeConversation = conversations.find((item) => item.conv_id === activeConvId);
  const typing = activeConvId ? typingByConv[activeConvId] : false;
  const peerUserId = activeConversation?.peer_user_id;
  const peerOnline = peerUserId ? presenceByUser[peerUserId] : undefined;

  useEffect(() => {
    setInput(activeConvId ? drafts[activeConvId] ?? "" : "");
    setQuoteTarget(null);
  }, [activeConvId, drafts]);

  useEffect(() => {
    if (!preserveScrollRef.current) feedRef.current?.scrollTo({ top: feedRef.current.scrollHeight });
  }, [activeConvId, currentMessages.length]);

  useEffect(() => () => {
    if (saveTimer.current) clearTimeout(saveTimer.current);
  }, []);

  const grouped = useMemo(() => conversations, [conversations]);

  function changeInput(value: string) {
    setInput(value);
    if (!activeConvId) return;
    setDraft(activeConvId, value);
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(() => {
      getClient().saveDraft(activeConvId, value).catch(() => undefined);
    }, 700);
    if (value.length > 0) getClient().sendTyping(activeConvId, true).catch(() => undefined);
  }

  async function submit() {
    if (!activeConvId) return;
    const text = input.trim();
    if (!text) return;
    if (text.length > 5000) {
      setError("文本超过 5000 个字符，已阻止发送");
      return;
    }
    setError("");
    const target = quoteTarget?.msgId;
    setInput("");
    setQuoteTarget(null);
    setDraft(activeConvId, "");
    // 新单聊在第一条消息前可能还没有服务端会话/草稿，清草稿失败不能阻塞发送。
    await getClient().clearDraft(activeConvId).catch(() => undefined);
    try {
      const message = await sendText(activeConvId, text, target);
      upsertMessage(message);
      getClient().sendTyping(activeConvId, false).catch(() => undefined);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "发送失败");
      setInput(text);
    }
  }

  async function chooseFile(file: File) {
    if (!activeConvId) return;
    setError("");
    setUploadProgress(0);
    const controller = new AbortController();
    uploadAbortRef.current = controller;
    try {
      const message = await sendFile(activeConvId, file,
        (uploaded, total) => setUploadProgress(total ? Math.round(uploaded / total * 100) : 0),
        controller.signal);
      upsertMessage(message);
    } catch (reason) {
      setError(reason instanceof DOMException && reason.name === "AbortError"
        ? "媒体上传已取消" : reason instanceof Error ? reason.message : "媒体上传失败");
    } finally {
      uploadAbortRef.current = null;
      setUploadProgress(null);
    }
  }

  async function openOlderMessages() {
    if (!activeConvId || !feedRef.current) return;
    const feed = feedRef.current;
    const previousHeight = feed.scrollHeight;
    const previousTop = feed.scrollTop;
    preserveScrollRef.current = true;
    try {
      await loadOlder(activeConvId);
      requestAnimationFrame(() => {
        feed.scrollTop = previousTop + (feed.scrollHeight - previousHeight);
        preserveScrollRef.current = false;
      });
    } catch (reason) {
      preserveScrollRef.current = false;
      setError(reason instanceof Error ? reason.message : "历史消息加载失败");
    }
  }

  async function nextConversationPage() {
    try {
      const page = await loadConversations(conversationCursor ?? undefined);
      setConversationCursor(page?.next_cursor ? String(page.next_cursor) : null);
      setHasMoreConversations(Boolean(page?.has_more));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "会话分页加载失败");
    }
  }

  async function forwardSelected() {
    if (!selectedMsgIds.size) return;
    const target = window.prompt("输入目标会话 ID");
    if (!target) return;
    try {
      await getClient().forward([...selectedMsgIds], [target]);
      setSelectedMsgIds(new Set());
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "批量转发失败");
    }
  }

  async function action(message: ChatMessage, actionName: string) {
    const client = getClient();
    try {
      if (actionName === "quote") {
        setQuoteTarget(message);
      } else if (actionName === "recall") {
        await client.recall(message.msgId);
      } else if (actionName === "delete") {
        await client.deleteLocal(message.msgId);
        mergeMessages(message.convId, client.getMessages(message.convId));
      } else if (actionName === "resend") {
        await client.resend(message);
        mergeMessages(message.convId, client.getMessages(message.convId));
      } else if (actionName === "forward") {
        const target = window.prompt("输入目标会话 ID");
        if (target) await client.forward([message.msgId], [target]);
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "消息操作失败");
    }
  }

  async function updateConversation(conversation: Conversation, patch: { pinned?: boolean; muted?: boolean }) {
    const previous = { pinned: conversation.pinned, muted: conversation.muted };
    patchConversation(conversation.conv_id, patch);
    setMenuConvId(null);
    try {
      await getClient().updateConversation(conversation.conv_id, patch);
    } catch (reason) {
      patchConversation(conversation.conv_id, previous);
      setError(reason instanceof Error ? reason.message : "会话设置失败");
    }
  }

  async function deleteConversation(conversation: Conversation) {
    setMenuConvId(null);
    try {
      await getClient().deleteConversation(conversation.conv_id);
      setConversations(conversations.filter((item) => item.conv_id !== conversation.conv_id));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "删除会话失败");
    }
  }

  return (
    <div className="chat-layout">
      <section className="list-pane" aria-label="会话列表">
        <header className="pane-header">
          <div><h2>聊天</h2><span className="muted">{conversations.length} 个会话</span></div>
        </header>
        <div className="conversation-list">
          {grouped.map((conversation) => {
            const unread = Number(conversation.unread_count ?? 0);
            return (
              <div key={conversation.conv_id} data-testid="conversation-item" role="button" tabIndex={0}
                className={conversation.conv_id === activeConvId ? "conversation active" : "conversation"}
                onClick={() => openConversation(conversation.conv_id)}
                onKeyDown={(event) => { if (event.key === "Enter") void openConversation(conversation.conv_id); }}
                onContextMenu={(event) => {
                  event.preventDefault();
                  setMenuConvId(conversation.conv_id);
                }}>
                <div className="conversation-top">
                  <strong>{conversationTitle(conversation)}</strong>
                  <time>{conversation.last_message_at
                    ? new Date(Number(conversation.last_message_at)).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
                    : ""}</time>
                </div>
                <div className="conversation-bottom">
                  <span>{conversation.draft ? `[草稿] ${conversation.draft}` : String(conversation.last_message_digest ?? "暂无消息")}</span>
                  {conversation.pinned && <i title="已置顶">📌</i>}
                  {conversation.muted ? (unread > 0 ? <i className="unread-dot" title="免打扰会话有未读" /> : null)
                    : unread > 0 ? <b className="unread-count">{unread > 99 ? "99+" : unread}</b> : null}
                </div>
                <div className="conversation-flags">
                  {Boolean(conversation.mention_count) && <em>@{String(conversation.mention_count)}</em>}
                  {conversation.read_only && <em>只读</em>}
                  {conversation.muted && <em>免打扰</em>}
                </div>
                {menuConvId === conversation.conv_id && (
                  <div className="context-menu" onClick={(event) => event.stopPropagation()}>
                    <button onClick={() => updateConversation(conversation, { pinned: !conversation.pinned })}>
                      {conversation.pinned ? "取消置顶" : "置顶"}
                    </button>
                    <button onClick={() => updateConversation(conversation, { muted: !conversation.muted })}>
                      {conversation.muted ? "取消免打扰" : "免打扰"}
                    </button>
                    <button className="danger" onClick={() => deleteConversation(conversation)}>删除会话</button>
                  </div>
                )}
              </div>
            );
          })}
          {hasMoreConversations && <button onClick={() => void nextConversationPage()}>加载更多会话</button>}
          {!conversations.length && <div className="empty-state">暂无会话。前往联系人开始单聊，或从群聊创建群。</div>}
        </div>
      </section>

      <section className="chat-pane" aria-label="聊天内容">
        {!activeConvId ? <div className="empty-state tall">选择一个会话开始聊天</div> : (
          <>
            <header className="chat-header">
              <div>
                <h2>{activeConversation ? conversationTitle(activeConversation) : activeConvId}</h2>
                <span className="muted" data-testid="typing-status">{typing ? "对方正在输入…"
                  : peerOnline === true ? "对方在线" : peerOnline === false ? "对方离线" : "在线状态由 Phantom 同步"}</span>
              </div>
              <button className="ghost" onClick={() => void openOlderMessages()}>加载更早消息</button>
            </header>
            <div className="message-feed" data-testid="messages" ref={feedRef}>
              {currentMessages.map((message) => {
                const self = message.senderId === account.phantom_user_id;
                return (
                  <article key={message.msgId || message.clientMsgId}
                    data-testid="message" data-msg-id={message.msgId} data-conv-seq={message.convSeq}
                    className={`${self ? "message self" : "message peer"}${highlightConvSeq === message.convSeq ? " highlighted" : ""}`}>
                    {message.replyToMsgId && <div className="quote" data-testid="quoted-message">引用 {message.replySnapshot?.digest ?? message.replyToMsgId}</div>}
                    <p>{messageContent(message)}</p>
                    <small>{self ? "我" : message.senderId} · {message.state} · seq={message.convSeq || "-"}{message.restricted ? " · 可能未送达" : ""}</small>
                    <div className="message-actions">
                      {message.msgId && <button onClick={() => setSelectedMsgIds((current) => {
                        const next = new Set(current);
                        next.has(message.msgId) ? next.delete(message.msgId) : next.add(message.msgId);
                        return next;
                      })}>{selectedMsgIds.has(message.msgId) ? "取消选择" : "选择"}</button>}
                      {message.senderId === account.phantom_user_id && !message.recalled && message.msgId &&
                        <button onClick={() => action(message, "recall")}>撤回</button>}
                      {message.msgId && <button onClick={() => action(message, "forward")}>转发</button>}
                      {message.msgId && <button onClick={() => action(message, "quote")}>引用</button>}
                      {message.msgId && <button onClick={() => action(message, "delete")}>本地删除</button>}
                      {message.state === "failed" && <button onClick={() => action(message, "resend")}>重发</button>}
                    </div>
                  </article>
                );
              })}
              {!currentMessages.length && <div className="empty-state">暂无消息，发送第一条消息吧。</div>}
            </div>
            <div className="composer">
              {quoteTarget && <div className="reply-banner">引用：{messageContent(quoteTarget)}
                <button onClick={() => setQuoteTarget(null)}>取消</button></div>}
              {uploadProgress !== null && <div className="upload-row"><progress max={100} value={uploadProgress} data-testid="upload-progress" />
                <button onClick={() => uploadAbortRef.current?.abort()}>取消上传</button></div>}
              {selectedMsgIds.size > 0 && <div className="reply-banner">已选择 {selectedMsgIds.size} 条消息
                <button onClick={() => void forwardSelected()}>批量转发</button></div>}
              {error && <div className="form-error" role="alert">{error}</div>}
              <div className="composer-tools">
                <span className="emoji-menu">😀
                  <div className="emoji-popover">
                    {["😀", "👍", "🎉", "❤️"].map((emoji) =>
                      <button key={emoji} onClick={() => changeInput(input + emoji)}>{emoji}</button>)}
                  </div>
                </span>
                <label className="file-button">📎
                  <input type="file" data-testid="media-input" onChange={(event) => {
                    const file = event.target.files?.[0];
                    if (file) void chooseFile(file);
                    event.target.value = "";
                  }} />
                </label>
                <span className="char-count">{input.length}/5000</span>
              </div>
              <textarea data-testid="message-input" value={input} maxLength={5001}
                onChange={(event) => changeInput(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault();
                    void submit();
                  }
                }} placeholder="输入消息，Enter 发送，Shift+Enter 换行" />
              <button className="primary send-button" data-testid="send-message" onClick={() => void submit()}>发送</button>
            </div>
          </>
        )}
      </section>
    </div>
  );
}
