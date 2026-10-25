import { useCallback, useEffect, useState } from "react";
import { singleConvId } from "@phantom/sdk-web";
import { getClient, openConversation } from "../../services/sdk-adapter";
import { useAppStore } from "../../store/app-store";

type Row = Record<string, any>;

export function ContactsView() {
  const account = useAppStore((state) => state.account)!;
  const setNav = useAppStore((state) => state.setNav);
  const [friends, setFriends] = useState<Row[]>([]);
  const [requests, setRequests] = useState<Row[]>([]);
  const [blacklist, setBlacklist] = useState<Row[]>([]);
  const [phantomId, setPhantomId] = useState("");
  const [remark, setRemark] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [profile, setProfile] = useState<Row | null>(null);

  const load = useCallback(async () => {
    try {
      const [friendRows, requestPage, blockedRows] = await Promise.all([
        getClient().listFriends(), getClient().listFriendRequests(), getClient().listBlacklist(),
      ]);
      setFriends(friendRows as Row[]);
      setRequests((requestPage?.items ?? []) as Row[]);
      setBlacklist(blockedRows as Row[]);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "联系人加载失败");
    }
  }, []);

  useEffect(() => {
    void load();
    const refresh = () => void load();
    window.addEventListener("phantom:relation", refresh);
    return () => window.removeEventListener("phantom:relation", refresh);
  }, [load]);

  async function addFriend() {
    if (!phantomId.trim()) return;
    setError("");
    try {
      const result = await getClient().createFriendRequest(phantomId.trim());
      setNotice(result?.auto_accepted ? "已互相添加为好友" : "好友申请已发送");
      setPhantomId("");
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "好友申请发送失败");
    }
  }

  async function handleRequest(requestId: string, action: "ACCEPT" | "REJECT") {
    try {
      await getClient().handleFriendRequest(requestId, action);
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "处理好友申请失败");
    }
  }

  async function block(userId: string) {
    try {
      await getClient().blockUser(userId);
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "拉黑失败");
    }
  }

  async function unblock(userId: string) {
    try {
      await getClient().unblockUser(userId);
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "解除拉黑失败");
    }
  }

  async function startChat(friendId: string) {
    const convId = singleConvId(account.phantom_user_id, friendId);
    setNav("chat");
    await openConversation(convId);
  }

  async function showProfile(friendId: string) {
    try {
      setProfile(await getClient().getUserProfile(friendId) as Row);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "联系人资料加载失败");
    }
  }

  async function saveRemark(friendId: string, value: string) {
    if (!value.trim()) return;
    try {
      await getClient().updateFriend(friendId, { remark: value.trim() });
      setNotice("联系人备注已更新");
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "备注更新失败");
    }
  }

  return (
    <div className="content-layout">
      <section className="list-pane">
        <header className="pane-header"><div><h2>联系人</h2>
          <span className="muted">我的 Phantom ID：<code data-testid="my-phantom-id">{account.phantom_user_id}</code></span></div>
        </header>
        <div className="section-block">
          <h3>添加好友</h3>
          <div className="inline-form">
            <input data-testid="friend-id" value={phantomId} onChange={(event) => setPhantomId(event.target.value)} placeholder="输入 Phantom ID" />
            <button className="primary" data-testid="add-friend" onClick={() => void addFriend()}>申请</button>
          </div>
          {notice && <div className="notice" role="status">{notice}</div>}
          {error && <div className="form-error" role="alert">{error}</div>}
        </div>
        <div className="section-block">
          <h3>好友 <span>{friends.length}</span></h3>
          {friends.map((friend) => {
            const id = String(friend.user_id ?? friend.userId);
            const name = String(friend.remark || friend.nickname || id);
            return <div className="data-row" data-testid="friend-item" key={id}>
              <div><strong>{name}</strong><small>{id}</small></div>
              <div className="row-actions">
                <button onClick={() => void startChat(id)}>发消息</button>
                <button onClick={() => void showProfile(id)}>资料</button>
                <button onClick={() => {
                  const value = window.prompt("设置联系人备注", String(friend.remark ?? ""));
                  if (value !== null) void saveRemark(id, value);
                }}>备注</button>
                <button className="danger" onClick={() => void block(id)}>拉黑</button>
              </div>
            </div>;
          })}
          {!friends.length && <div className="empty-state">暂无好友</div>}
        </div>
        <div className="section-block">
          <h3>好友申请</h3>
          {requests.map((request) => <div className="data-row" data-testid="friend-request" key={request.request_id}>
            <div><strong>{request.from_nickname || request.from_user_id}</strong><small>{request.message || "请求添加好友"}</small></div>
            <div className="row-actions">
              <button onClick={() => void handleRequest(request.request_id, "ACCEPT")}>接受</button>
              <button onClick={() => void handleRequest(request.request_id, "REJECT")}>拒绝</button>
            </div>
          </div>)}
          {!requests.length && <div className="empty-state">暂无待处理申请</div>}
        </div>
        <div className="section-block">
          <h3>黑名单</h3>
          {blacklist.map((entry) => {
            const id = String(entry.user_id ?? entry.userId);
            return <div className="data-row" data-testid="blacklist-item" key={id}>
              <div><strong>{entry.nickname || id}</strong><small>{id}</small></div>
              <button onClick={() => void unblock(id)}>解除拉黑</button>
            </div>;
          })}
          {!blacklist.length && <div className="empty-state">黑名单为空</div>}
        </div>
      </section>
      <section className="detail-pane">
        {profile ? <div className="profile-hero" data-testid="contact-profile">
          <div className="large-avatar">{String(profile.nickname || profile.user_id).slice(0, 1)}</div>
          <h2>{profile.nickname || profile.user_id}</h2>
          <code>{profile.user_id}</code>
          {profile.signature && <p>{profile.signature}</p>}
          <button onClick={() => void startChat(String(profile.user_id))}>开始单聊</button>
          <button onClick={() => setProfile(null)}>返回我的资料</button>
        </div> : <div className="profile-hero">
          <div className="large-avatar">{account.nickname.slice(0, 1)}</div>
          <h2>{account.nickname}</h2>
          <code>{account.phantom_user_id}</code>
          <p>通过 Phantom ID 发起好友申请。关系变化会通过 SDK 同步事件刷新。</p>
          <button onClick={() => navigator.clipboard?.writeText(account.phantom_user_id)}>复制 Phantom ID</button>
        </div>}
      </section>
    </div>
  );
}
