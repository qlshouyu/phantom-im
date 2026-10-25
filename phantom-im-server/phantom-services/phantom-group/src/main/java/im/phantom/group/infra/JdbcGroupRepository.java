package im.phantom.group.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 群、成员、邀请、禁言和公告持久化。 */
@Repository
public class JdbcGroupRepository {

    private final JdbcTemplate jdbc;

    public JdbcGroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findAppId(String userId) {
        return jdbc.query("SELECT app_id FROM app_user WHERE user_id=? AND status='ACTIVE'",
                rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(), userId);
    }

    public void insertGroup(GroupRecord group) {
        jdbc.update("""
                INSERT INTO chat_group(group_id, conv_id, name, avatar_url, owner_id, status,
                    invite_verification, member_invite_enabled, member_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, group.groupId(), group.convId(), group.name(), group.avatarUrl(), group.ownerId(),
                group.status(), group.inviteVerification(), group.memberInviteEnabled(), group.memberCount(),
                group.createdAt(), group.updatedAt());
    }

    public boolean insertMember(String groupId, String userId, String role, long now) {
        int changed = jdbc.update("""
                INSERT INTO group_member(group_id, user_id, member_role, group_nickname, status, joined_at, left_at)
                VALUES (?, ?, ?, '', 'ACTIVE', ?, NULL)
                ON DUPLICATE KEY UPDATE member_role=VALUES(member_role), status='ACTIVE', joined_at=VALUES(joined_at),
                    left_at=NULL
                """, groupId, userId, role, now);
        if (changed > 0) refreshMemberCount(groupId);
        return changed > 0;
    }

    public Optional<GroupRecord> findGroup(String groupId) {
        return jdbc.query("""
                SELECT group_id, conv_id, name, avatar_url, owner_id, status, invite_verification,
                       member_invite_enabled, member_count, created_at, updated_at
                FROM chat_group WHERE group_id=?
                """, rs -> rs.next() ? Optional.of(mapGroup(rs)) : Optional.empty(), groupId);
    }

    public List<GroupRecord> listGroups(String userId) {
        return jdbc.query("""
                SELECT g.group_id, g.conv_id, g.name, g.avatar_url, g.owner_id, g.status,
                       g.invite_verification, g.member_invite_enabled, g.member_count,
                       g.created_at, g.updated_at
                FROM chat_group g JOIN group_member gm ON gm.group_id=g.group_id
                WHERE gm.user_id=? AND gm.status='ACTIVE'
                ORDER BY g.updated_at DESC
                """, (rs, row) -> mapGroup(rs), userId);
    }

    public Optional<MemberRecord> findMember(String groupId, String userId) {
        return jdbc.query("""
                SELECT gm.group_id, gm.user_id, gm.member_role, gm.group_nickname, gm.status,
                       gm.joined_at, gm.left_at,
                       CASE WHEN mm.user_id IS NOT NULL AND mm.enabled=TRUE
                             AND (mm.muted_until IS NULL OR mm.muted_until>?) THEN mm.muted_until END AS member_muted_until,
                       CASE WHEN mall.user_id IS NOT NULL AND mall.enabled=TRUE
                             AND (mall.muted_until IS NULL OR mall.muted_until>?) THEN TRUE ELSE FALSE END AS muted_all
                FROM group_member gm
                LEFT JOIN group_mute mm ON mm.group_id=gm.group_id AND mm.user_id=gm.user_id
                LEFT JOIN group_mute mall ON mall.group_id=gm.group_id AND mall.user_id='ALL'
                WHERE gm.group_id=? AND gm.user_id=?
                """, rs -> rs.next() ? Optional.of(new MemberRecord(rs.getString("group_id"),
                rs.getString("user_id"), rs.getString("member_role"), rs.getString("group_nickname"),
                rs.getString("status"), rs.getLong("joined_at"), (Long) rs.getObject("left_at"),
                (Long) rs.getObject("member_muted_until"), rs.getBoolean("muted_all"))) : Optional.empty(),
                System.currentTimeMillis(), System.currentTimeMillis(), groupId, userId);
    }

    public List<MemberRecord> listMembers(String groupId) {
        long now = System.currentTimeMillis();
        return jdbc.query("""
                SELECT gm.group_id, gm.user_id, gm.member_role, gm.group_nickname, gm.status,
                       gm.joined_at, gm.left_at,
                       CASE WHEN mm.user_id IS NOT NULL AND mm.enabled=TRUE
                             AND (mm.muted_until IS NULL OR mm.muted_until>?) THEN mm.muted_until END AS member_muted_until,
                       CASE WHEN mall.user_id IS NOT NULL AND mall.enabled=TRUE
                             AND (mall.muted_until IS NULL OR mall.muted_until>?) THEN TRUE ELSE FALSE END AS muted_all
                FROM group_member gm
                LEFT JOIN group_mute mm ON mm.group_id=gm.group_id AND mm.user_id=gm.user_id
                LEFT JOIN group_mute mall ON mall.group_id=gm.group_id AND mall.user_id='ALL'
                WHERE gm.group_id=? AND gm.status='ACTIVE'
                ORDER BY CASE gm.member_role WHEN 'OWNER' THEN 1 WHEN 'ADMIN' THEN 2 ELSE 3 END,
                         gm.joined_at
                """, (rs, row) -> new MemberRecord(rs.getString("group_id"), rs.getString("user_id"),
                rs.getString("member_role"), rs.getString("group_nickname"), rs.getString("status"),
                rs.getLong("joined_at"), (Long) rs.getObject("left_at"),
                (Long) rs.getObject("member_muted_until"), rs.getBoolean("muted_all")), now, now, groupId);
    }

    public List<String> activeMemberIds(String groupId) {
        return jdbc.queryForList("SELECT user_id FROM group_member WHERE group_id=? AND status='ACTIVE' " +
                "ORDER BY joined_at", String.class, groupId);
    }

    public int activeMemberCount(String groupId) {
        Integer count = jdbc.query("SELECT COUNT(*) FROM group_member WHERE group_id=? AND status='ACTIVE'",
                rs -> rs.next() ? rs.getInt(1) : 0, groupId);
        return count == null ? 0 : count;
    }

    public boolean removeMember(String groupId, String userId, long now) {
        return removeMember(groupId, userId, "LEFT", now);
    }

    public boolean removeMember(String groupId, String userId, String status, long now) {
        int changed = jdbc.update("UPDATE group_member SET status=?, left_at=? " +
                "WHERE group_id=? AND user_id=? AND status='ACTIVE'", status, now, groupId, userId);
        if (changed == 1) {
            refreshMemberCount(groupId);
        }
        return changed == 1;
    }

    public void updateRole(String groupId, String userId, String role) {
        jdbc.update("UPDATE group_member SET member_role=? WHERE group_id=? AND user_id=? AND status='ACTIVE'",
                role, groupId, userId);
    }

    public void transferOwner(String groupId, String oldOwnerId, String newOwnerId,
                              String oldOwnerRole, long now) {
        jdbc.update("UPDATE chat_group SET owner_id=?, updated_at=? WHERE group_id=?",
                newOwnerId, now, groupId);
        updateRole(groupId, oldOwnerId, oldOwnerRole);
        updateRole(groupId, newOwnerId, "OWNER");
    }

    public void updateProfile(String groupId, String name, String avatarUrl,
                              boolean memberInviteEnabled, long now) {
        jdbc.update("UPDATE chat_group SET name=?, avatar_url=?, member_invite_enabled=?, updated_at=? " +
                "WHERE group_id=?", name, avatarUrl, memberInviteEnabled, now, groupId);
    }

    public void dissolve(String groupId, long now) {
        jdbc.update("UPDATE chat_group SET status='DISSOLVED', updated_at=? WHERE group_id=?", now, groupId);
        jdbc.update("UPDATE group_invite SET status='REVOKED' WHERE group_id=? AND status='ACTIVE'", groupId);
    }

    public void updateGroupNickname(String groupId, String userId, String nickname, long now) {
        jdbc.update("UPDATE group_member SET group_nickname=? WHERE group_id=? AND user_id=? AND status='ACTIVE'",
                nickname, groupId, userId);
    }

    public void upsertMute(String groupId, String userId, Long mutedUntil, String operatorId, long now) {
        jdbc.update("""
                INSERT INTO group_mute(group_id, user_id, muted_until, enabled, operator_id, updated_at)
                VALUES (?, ?, ?, TRUE, ?, ?)
                ON DUPLICATE KEY UPDATE muted_until=VALUES(muted_until), enabled=TRUE,
                    operator_id=VALUES(operator_id), updated_at=VALUES(updated_at)
                """, groupId, userId, mutedUntil, operatorId, now);
    }

    public void removeMute(String groupId, String userId, String operatorId, long now) {
        jdbc.update("UPDATE group_mute SET enabled=FALSE, operator_id=?, updated_at=? " +
                "WHERE group_id=? AND user_id=?", operatorId, now, groupId, userId);
    }

    public Optional<AnnouncementRecord> announcement(String groupId) {
        return jdbc.query("SELECT group_id, content, operator_id, version, updated_at " +
                        "FROM group_announcement WHERE group_id=?",
                rs -> rs.next() ? Optional.of(new AnnouncementRecord(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getLong(4), rs.getLong(5))) : Optional.empty(), groupId);
    }

    public AnnouncementRecord upsertAnnouncement(String groupId, String content, String operatorId, long now) {
        jdbc.update("""
                INSERT INTO group_announcement(group_id, content, operator_id, version, updated_at)
                VALUES (?, ?, ?, 1, ?)
                ON DUPLICATE KEY UPDATE content=VALUES(content), operator_id=VALUES(operator_id),
                    version=version+1, updated_at=VALUES(updated_at)
                """, groupId, content, operatorId, now);
        return announcement(groupId).orElseThrow();
    }

    public boolean announcementUnread(String groupId, String userId, long version) {
        Integer read = jdbc.query("SELECT COUNT(*) FROM group_announcement_read " +
                        "WHERE group_id=? AND user_id=? AND version>=?",
                rs -> rs.next() ? rs.getInt(1) : 0, groupId, userId, version);
        return read == null || read == 0;
    }

    public void markAnnouncementRead(String groupId, String userId, long version, long now) {
        jdbc.update("""
                INSERT INTO group_announcement_read(group_id, user_id, version, read_at) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE version=GREATEST(version, VALUES(version)), read_at=VALUES(read_at)
                """, groupId, userId, version, now);
    }

    public void insertInvite(InviteRecord invite) {
        jdbc.update("""
                INSERT INTO group_invite(invite_id, group_id, token_hash, invite_type, status,
                    created_by, created_at, expires_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, invite.inviteId(), invite.groupId(), invite.tokenHash(), invite.inviteType(),
                invite.createdBy(), invite.createdAt(), invite.expiresAt());
    }

    public boolean hasPendingMute(String groupId, String userId, long now) {
        Integer count = jdbc.query("""
                SELECT COUNT(*) FROM group_mute
                WHERE group_id=? AND user_id IN (?, 'ALL') AND enabled=TRUE
                  AND (muted_until IS NULL OR muted_until>?)
                """, rs -> rs.next() ? rs.getInt(1) : 0, groupId, userId, now);
        return count != null && count > 0;
    }

    public boolean hasActiveInvite(String groupId, String tokenHash, long now) {
        Integer count = jdbc.query("""
                SELECT COUNT(*) FROM group_invite WHERE group_id=? AND token_hash=? AND status='ACTIVE'
                  AND (expires_at IS NULL OR expires_at>?)
                """, rs -> rs.next() ? rs.getInt(1) : 0, groupId, tokenHash, now);
        return count != null && count > 0;
    }

    public Optional<InviteRecord> findActiveInvite(String tokenHash, long now) {
        return jdbc.query("""
                SELECT invite_id, group_id, token_hash, invite_type, created_by, created_at, expires_at
                FROM group_invite WHERE token_hash=? AND status='ACTIVE'
                  AND (expires_at IS NULL OR expires_at>?) LIMIT 1
                """, rs -> rs.next() ? Optional.of(new InviteRecord(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6),
                (Long) rs.getObject(7))) : Optional.empty(), tokenHash, now);
    }

    public boolean hasPendingJoin(String groupId, String userId) {
        Integer count = jdbc.query("SELECT COUNT(*) FROM group_join_request " +
                        "WHERE group_id=? AND user_id=? AND status='PENDING'",
                rs -> rs.next() ? rs.getInt(1) : 0, groupId, userId);
        return count != null && count > 0;
    }

    public void insertJoinRequest(String requestId, String groupId, String userId,
                                  String inviteId, long now) {
        jdbc.update("INSERT INTO group_join_request(request_id, group_id, user_id, invite_id, status, " +
                "created_at) VALUES (?, ?, ?, ?, 'PENDING', ?)", requestId, groupId, userId, inviteId, now);
    }

    public Optional<JoinRequestRecord> findJoinRequest(String requestId, String groupId) {
        return jdbc.query("SELECT request_id, group_id, user_id, invite_id, status, created_at, " +
                        "handled_at, operator_id FROM group_join_request WHERE request_id=? AND group_id=?",
                rs -> rs.next() ? Optional.of(new JoinRequestRecord(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6),
                        (Long) rs.getObject(7), rs.getString(8))) : Optional.empty(), requestId, groupId);
    }

    public boolean finishJoinRequest(String requestId, String status, String operatorId, long now) {
        return jdbc.update("UPDATE group_join_request SET status=?, operator_id=?, handled_at=? " +
                        "WHERE request_id=? AND status='PENDING'", status, operatorId, now, requestId) == 1;
    }

    private void refreshMemberCount(String groupId) {
        jdbc.update("UPDATE chat_group SET member_count=(SELECT COUNT(*) FROM group_member " +
                "WHERE group_id=? AND status='ACTIVE') WHERE group_id=?", groupId, groupId);
    }

    private GroupRecord mapGroup(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GroupRecord(rs.getString("group_id"), rs.getString("conv_id"), rs.getString("name"),
                rs.getString("avatar_url"), rs.getString("owner_id"), rs.getString("status"),
                rs.getBoolean("invite_verification"), rs.getBoolean("member_invite_enabled"),
                rs.getInt("member_count"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    public record GroupRecord(String groupId, String convId, String name, String avatarUrl, String ownerId,
                              String status, boolean inviteVerification, boolean memberInviteEnabled,
                              int memberCount, long createdAt, long updatedAt) {}
    public record MemberRecord(String groupId, String userId, String role, String groupNickname,
                               String status, long joinedAt, Long leftAt, Long mutedUntil,
                               boolean mutedAll) {}
    public record AnnouncementRecord(String groupId, String content, String operatorId,
                                     long version, long updatedAt) {}
    public record InviteRecord(String inviteId, String groupId, String tokenHash, String inviteType,
                               String createdBy, long createdAt, Long expiresAt) {}
    public record JoinRequestRecord(String requestId, String groupId, String userId, String inviteId,
                                    String status, long createdAt, Long handledAt, String operatorId) {}
}
