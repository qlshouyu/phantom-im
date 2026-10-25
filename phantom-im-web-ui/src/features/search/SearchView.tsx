import { useEffect, useRef, useState } from "react";
import { singleConvId } from "@phantom/sdk-web";
import { getClient, openConversation } from "../../services/sdk-adapter";
import { useAppStore } from "../../store/app-store";
import type { SearchResults } from "../../types";

type Row = Record<string, any>;

export function SearchView() {
  const account = useAppStore((state) => state.account)!;
  const nav = useAppStore((state) => state.nav);
  const activeConvId = useAppStore((state) => state.activeConvId);
  const setNav = useAppStore((state) => state.setNav);
  const setHighlightConvSeq = useAppStore((state) => state.setHighlightConvSeq);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchResults | null>(null);
  const [conversationResults, setConversationResults] = useState<Row[] | null>(null);
  const [suggestions, setSuggestions] = useState<SearchResults | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const sequence = useRef(0);

  useEffect(() => {
    setResults(null);
    setConversationResults(null);
  }, [nav]);

  useEffect(() => {
    const keyword = query.trim();
    if (!keyword) {
      setSuggestions(null);
      return;
    }
    const timer = setTimeout(() => {
      void getClient().searchSuggestionsDebounced(keyword, 300)
        .then((value) => setSuggestions(value as SearchResults))
        .catch(() => setSuggestions(null));
      void search();
    }, 350);
    return () => clearTimeout(timer);
  }, [query]);

  async function search() {
    const keyword = query.trim();
    if (!keyword) return;
    const requestSequence = ++sequence.current;
    setLoading(true);
    setError("");
    try {
      const result = await getClient().search(keyword, 20) as SearchResults;
      if (requestSequence === sequence.current) {
        setResults(result);
        setConversationResults(null);
      }
    } catch (reason) {
      if (requestSequence === sequence.current) setError(reason instanceof Error ? reason.message : "搜索失败");
    } finally {
      if (requestSequence === sequence.current) setLoading(false);
    }
  }

  async function searchCurrentConversation() {
    const keyword = query.trim();
    if (!activeConvId || !keyword) return;
    const requestSequence = ++sequence.current;
    setLoading(true);
    setError("");
    try {
      const result = await getClient().searchConversation(activeConvId, keyword, 20) as Row;
      if (requestSequence === sequence.current) {
        setConversationResults((result.items ?? []) as Row[]);
        setResults(null);
      }
    } catch (reason) {
      if (requestSequence === sequence.current) setError(reason instanceof Error ? reason.message : "会话内搜索失败");
    } finally {
      if (requestSequence === sequence.current) setLoading(false);
    }
  }

  async function openContact(hit: Row) {
    const userId = String(hit.user_id ?? hit.userId);
    const convId = singleConvId(account.phantom_user_id, userId);
    setNav("chat");
    await openConversation(convId);
  }

  async function openMessage(hit: Row) {
    const convId = String(hit.conv_id ?? hit.convId);
    setHighlightConvSeq(Number(hit.conv_seq ?? hit.convSeq ?? 0));
    setNav("chat");
    await openConversation(convId);
  }

  const contactHits = (results?.contacts ?? []) as Row[];
  const groupHits = (results?.groups ?? []) as Row[];
  const messageHits = (results?.messages ?? []) as Row[];
  const noResults = results && !contactHits.length && !groupHits.length && !messageHits.length;
  const noConversationResults = conversationResults && !conversationResults.length;

  return (
    <div className="content-layout">
      <section className="list-pane">
        <header className="pane-header"><div><h2>搜索</h2><span className="muted">联系人、群组和聊天记录</span></div></header>
        <div className="section-block">
          <div className="inline-form">
            <input data-testid="search-query" value={query} onChange={(event) => setQuery(event.target.value)}
              onKeyDown={(event) => { if (event.key === "Enter") void search(); }} placeholder="输入关键词" />
            <button className="primary" data-testid="search-button" onClick={() => void search()}>全局搜索</button>
          </div>
          <button data-testid="conversation-search-button" disabled={!activeConvId}
            onClick={() => void searchCurrentConversation()}>在当前会话中搜索</button>
          {loading && <div className="muted">搜索中…</div>}
          {error && <div className="form-error" role="alert">{error}</div>}
        </div>
        <div data-testid="search-results" className="search-results">
          {suggestions && Boolean((suggestions.contacts?.length ?? 0) + (suggestions.groups?.length ?? 0)) &&
            <div className="section-block"><h3>联想结果</h3>
              {(suggestions.contacts ?? []).map((hit) => <button className="search-hit" key={String(hit.user_id)}
                onClick={() => void openContact(hit)}><strong>{String(hit.remark || hit.nickname || hit.user_id)}</strong><small>联系人</small></button>)}
              {(suggestions.groups ?? []).map((hit) => <button className="search-hit" key={String(hit.group_id)}
                onClick={async () => { setNav("chat"); await openConversation(String(hit.conv_id)); }}>
                <strong>{String(hit.name || hit.group_id)}</strong><small>群组</small></button>)}
            </div>}
          {noResults && <div className="empty-state">未找到相关结果</div>}
          {contactHits.length > 0 && <div className="section-block"><h3>联系人 {contactHits.length}</h3>
            {contactHits.map((hit) => <button className="search-hit" data-testid="search-contact"
              key={String(hit.user_id)} onClick={() => void openContact(hit)}>
              <strong>{hit.remark || hit.nickname || hit.user_id}</strong><small>{hit.user_id}</small>
            </button>)}</div>}
          {groupHits.length > 0 && <div className="section-block"><h3>群组 {groupHits.length}</h3>
            {groupHits.map((hit) => <button className="search-hit" data-testid="search-group"
              key={String(hit.group_id)} onClick={async () => {
                setNav("chat");
                await openConversation(String(hit.conv_id));
              }}><strong>{hit.name}</strong><small>{hit.group_id}</small></button>)}</div>}
          {messageHits.length > 0 && <div className="section-block"><h3>聊天记录 {messageHits.length}</h3>
            {messageHits.map((hit) => <button className="search-hit" data-testid="search-message"
              key={String(hit.msg_id)} onClick={() => void openMessage(hit)}>
              <strong>{hit.conversation_title || hit.conv_id}</strong><small>{hit.digest}</small>
            </button>)}</div>}
          {conversationResults && !noConversationResults && <div className="section-block"><h3>当前会话 {conversationResults.length}</h3>
            {conversationResults.map((hit) => <button className="search-hit" key={String(hit.msg_id)}
              onClick={() => void openMessage(hit)}><strong>seq {hit.conv_seq}</strong>
              <small>{hit.recalled ? "消息已撤回" : hit.burned ? "消息已焚毁" : hit.digest}</small></button>)}
          </div>}
          {noConversationResults && <div className="empty-state">当前会话未找到相关消息</div>}
        </div>
      </section>
      <section className="detail-pane">
        <div className="search-guide">
          <h2>快速定位历史消息</h2>
          <p>搜索结果按联系人、群组和聊天记录分类。点击消息命中项会打开对应会话并高亮目标 <code>conv_seq</code>。</p>
          <p>目标消息已撤回或焚毁时，会话内仍展示明确占位，不回填敏感内容。</p>
        </div>
      </section>
    </div>
  );
}
