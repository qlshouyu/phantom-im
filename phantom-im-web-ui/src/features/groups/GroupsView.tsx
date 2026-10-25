import { useCallback, useEffect, useState } from "react";
import { getClient, openConversation } from "../../services/sdk-adapter";
import { useAppStore } from "../../store/app-store";

type Row = Record<string, any>;

export function GroupsView() {
  const setNav = useAppStore((state) => state.setNav);
  const [groups, setGroups] = useState<Row[]>([]);
  const [selected, setSelected] = useState<Row | null>(null);
  const [detail, setDetail] = useState<Row | null>(null);
  const [members, setMembers] = useState<Row[]>([]);
  const [name, setName] = useState("");
  const [memberIds, setMemberIds] = useState("");
  const [inviteIds, setInviteIds] = useState("");
  const [announcement, setAnnouncement] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    try {
      setGroups(await getClient().listGroups() as Row[]);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "群列表加载失败");
    }
  }, []);

  useEffect(() => {
    void load();
    const refresh = () => void load();
    window.addEventListener("phantom:group", refresh);
    return () => window.removeEventListener("phantom:group", refresh);
  }, [load]);

  async function selectGroup(group: Row) {
    setSelected(group);
    setError("");
    try {
      const [groupDetail, groupMembers] = await Promise.all([
        getClient().getGroup(String(group.group_id)) as Promise<Row>,
        getClient().listGroupMembers(String(group.group_id)) as Promise<Row[]>,
      ]);
      setDetail(groupDetail);
      setMembers(groupMembers);
      setAnnouncement(String(groupDetail.announcement?.content ?? ""));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "群详情加载失败");
    }
  }

  async function createGroup() {
    const ids = memberIds.split(/[,\s]+/).map((item) => item.trim()).filter(Boolean);
    if (!name.trim() || ids.length < 2) {
      setError("创建群聊需要群名和至少两名好友的 Phantom ID");
      return;
    }
    try {
      const group = await getClient().createGroup(name.trim(), ids) as Row;
      setNotice("群聊创建成功");
      setName("");
      setMemberIds("");
      await load();
      await selectGroup(group);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "创建群聊失败");
    }
  }

  async function open(group: Row) {
    setNav("chat");
    await openConversation(String(group.conv_id));
  }

  async function invite() {
    if (!selected) return;
    const ids = inviteIds.split(/[,\s]+/).map((item) => item.trim()).filter(Boolean);
    try {
      await getClient().inviteGroupMembers(String(selected.group_id), ids);
      setInviteIds("");
      setNotice("成员邀请已提交");
      await selectGroup(selected);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "邀请成员失败");
    }
  }

  async function muteMember(userId: string) {
    if (!selected) return;
    try {
      await getClient().muteGroupMember(String(selected.group_id), userId, 3600);
      setNotice("成员已禁言 1 小时");
      await selectGroup(selected);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "禁言失败");
    }
  }

  async function saveAnnouncement() {
    if (!selected) return;
    try {
      await getClient().updateAnnouncement(String(selected.group_id), announcement);
      setNotice("群公告已发布");
      await selectGroup(selected);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "发布公告失败");
    }
  }

  async function leave() {
    if (!selected) return;
    try {
      await getClient().leaveGroup(String(selected.group_id));
      setSelected(null);
      setDetail(null);
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "退群失败");
    }
  }

  async function dissolve() {
    if (!selected || !window.confirm("解散后群成员将无法继续发送消息，确认解散？")) return;
    try {
      await getClient().dissolveGroup(String(selected.group_id));
      setSelected(null);
      setDetail(null);
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "解散群失败");
    }
  }

  return (
    <div className="content-layout">
      <section className="list-pane">
        <header className="pane-header"><div><h2>群聊</h2><span className="muted">{groups.length} 个群</span></div></header>
        <div className="section-block">
          <h3>从好友建群</h3>
          <label>群名称<input data-testid="group-name" value={name} onChange={(event) => setName(event.target.value)} /></label>
          <label>成员 Phantom ID<input data-testid="group-members" value={memberIds}
            onChange={(event) => setMemberIds(event.target.value)} placeholder="至少两名，逗号分隔" /></label>
          <button className="primary" data-testid="create-group" onClick={() => void createGroup()}>创建群聊</button>
          {notice && <div className="notice" role="status">{notice}</div>}
          {error && <div className="form-error" role="alert">{error}</div>}
        </div>
        <div className="section-block">
          <h3>我的群</h3>
          {groups.map((group) => <div key={group.group_id}
            className={selected?.group_id === group.group_id ? "data-row selected" : "data-row"}
            data-testid="group-item">
            <button className="row-main" onClick={() => void selectGroup(group)}>
              <strong>{group.name}</strong><small>{group.member_count} 人 · {group.group_id}</small>
            </button>
            <button onClick={() => void open(group)}>打开会话</button>
          </div>)}
          {!groups.length && <div className="empty-state">暂无群聊</div>}
        </div>
      </section>
      <section className="detail-pane">
        {!selected ? <div className="empty-state tall">选择群聊查看详情</div> : <>
          <header className="pane-header"><div><h2>{detail?.summary?.name ?? selected.name}</h2>
            <span className="muted">{detail?.summary?.member_count ?? selected.member_count} 人</span></div>
            <button className="primary" onClick={() => void open(selected)}>进入群会话</button></header>
          <div className="section-block">
            <h3>群公告</h3>
            <textarea data-testid="group-announcement" value={announcement}
              onChange={(event) => setAnnouncement(event.target.value)} placeholder="发布群公告" />
            <button data-testid="group-announce" onClick={() => void saveAnnouncement()}>发布公告</button>
          </div>
          <div className="section-block">
            <h3>邀请成员</h3>
            <div className="inline-form"><input data-testid="group-invite-ids" value={inviteIds}
              onChange={(event) => setInviteIds(event.target.value)} placeholder="Phantom ID，逗号分隔" />
              <button data-testid="group-invite" onClick={() => void invite()}>邀请</button></div>
          </div>
          <div className="section-block">
            <h3>成员</h3>
            {members.map((member) => <div className="data-row" data-testid="group-member" key={member.user_id}>
              <div><strong>{member.group_nickname || member.user_id}</strong><small>{member.role}</small></div>
              <div className="row-actions">
                <button data-testid="group-mute" onClick={() => void muteMember(String(member.user_id))}>禁言</button>
              </div>
            </div>)}
          </div>
          <div className="danger-zone">
            <button onClick={() => void leave()}>退群</button>
            {detail?.summary?.owner_id === useAppStore.getState().account?.phantom_user_id &&
              <button className="danger" onClick={() => void dissolve()}>解散群聊</button>}
          </div>
        </>}
      </section>
    </div>
  );
}
