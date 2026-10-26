package im.phantom.relation.infra;

import im.phantom.relation.domain.RelationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 关系链 JdbcTemplate 仓库。 */
@Repository
public class JdbcRelationRepository implements RelationRepository {

    private final JdbcTemplate jdbc;

    public JdbcRelationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserRecord> findUser(String appId, String userId) {
        return jdbc.query("""
                SELECT u.user_id, u.app_id, p.nickname, p.avatar_url, u.status
                FROM app_user u JOIN user_profile p ON p.user_id=u.user_id
                WHERE u.app_id=? AND u.user_id=?
                """, rs -> rs.next() ? Optional.of(new UserRecord(
                rs.getString("user_id"), rs.getString("app_id"), rs.getString("nickname"),
                rs.getString("avatar_url"), rs.getString("status"))) : Optional.empty(), appId, userId);
    }

    @Override
    public Optional<UserRecord> findUserById(String userId) {
        return jdbc.query("""
                SELECT u.user_id, u.app_id, p.nickname, p.avatar_url, u.status
                FROM app_user u JOIN user_profile p ON p.user_id=u.user_id
                WHERE u.user_id=?
                """, rs -> rs.next() ? Optional.of(new UserRecord(
                rs.getString("user_id"), rs.getString("app_id"), rs.getString("nickname"),
                rs.getString("avatar_url"), rs.getString("status"))) : Optional.empty(), userId);
    }

    @Override
    public void insertFriendRequest(FriendRequestRecord request) {
        jdbc.update("""
                INSERT INTO friend_request(request_id, app_id, from_user_id, to_user_id,
                    verification_message, status, created_at, handled_at, read_at, pending_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, request.requestId(), request.appId(), request.fromUserId(), request.toUserId(),
                request.verificationMessage(), request.status(), request.createdAt(), request.handledAt(),
                request.readAt(), request.pendingKey());
    }

    @Override
    public Optional<FriendRequestRecord> findFriendRequest(String appId, String requestId) {
        return jdbc.query("""
                SELECT request_id, app_id, from_user_id, to_user_id, verification_message, status,
                       created_at, handled_at, read_at, pending_key
                FROM friend_request WHERE app_id=? AND request_id=?
                """, rs -> rs.next() ? Optional.of(mapRequest(rs)) : Optional.empty(), appId, requestId);
    }

    @Override
    public Optional<FriendRequestRecord> findPendingFriendRequest(String appId, String fromUserId, String toUserId) {
        return jdbc.query("""
                SELECT request_id, app_id, from_user_id, to_user_id, verification_message, status,
                       created_at, handled_at, read_at, pending_key
                FROM friend_request WHERE app_id=? AND from_user_id=? AND to_user_id=? AND status='PENDING'
                """, rs -> rs.next() ? Optional.of(mapRequest(rs)) : Optional.empty(),
                appId, fromUserId, toUserId);
    }

    @Override
    public List<FriendRequestRecord> listFriendRequests(String userId, boolean includeHandled) {
        String statusClause = includeHandled ? "" : " AND status='PENDING'";
        return jdbc.query("""
                SELECT request_id, app_id, from_user_id, to_user_id, verification_message, status,
                       created_at, handled_at, read_at, pending_key
                FROM friend_request
                WHERE (from_user_id=? OR to_user_id=?)
                """ + statusClause + " ORDER BY created_at DESC", (rs, rowNum) -> mapRequest(rs), userId, userId);
    }

    @Override
    public int markIncomingRequestsRead(String userId, long readAt) {
        return jdbc.update("""
                UPDATE friend_request SET read_at=? WHERE to_user_id=? AND status='PENDING' AND read_at IS NULL
                """, readAt, userId);
    }

    @Override
    public int countUnreadRequests(String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM friend_request
                WHERE to_user_id=? AND status='PENDING' AND read_at IS NULL
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }

    @Override
    public boolean transitionRequest(String requestId, String fromStatus, String toStatus, long handledAt) {
        return jdbc.update("""
                UPDATE friend_request
                SET status=?, handled_at=?, read_at=COALESCE(read_at, ?), pending_key=NULL
                WHERE request_id=? AND status=?
                """, toStatus, handledAt, handledAt, requestId, fromStatus) == 1;
    }

    @Override
    public boolean isFriend(String userId, String friendId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM friend_relation WHERE user_id=? AND friend_id=?
                """, Integer.class, userId, friendId);
        return count != null && count > 0;
    }

    @Override
    public DirectMessageRelationState directMessageRelationState(String senderId, String receiverId) {
        return jdbc.queryForObject("""
                SELECT
                  EXISTS(SELECT 1 FROM blacklist WHERE user_id=? AND blocked_id=?) AS sender_blocked_receiver,
                  EXISTS(SELECT 1 FROM blacklist WHERE user_id=? AND blocked_id=?) AS receiver_blocked_sender,
                  EXISTS(SELECT 1 FROM friend_relation WHERE user_id=? AND friend_id=?) AS sender_has_peer,
                  EXISTS(SELECT 1 FROM friend_relation WHERE user_id=? AND friend_id=?) AS peer_has_sender
                """, (rs, rowNum) -> new DirectMessageRelationState(
                rs.getBoolean("sender_blocked_receiver"), rs.getBoolean("receiver_blocked_sender"),
                rs.getBoolean("sender_has_peer"), rs.getBoolean("peer_has_sender")),
                senderId, receiverId, receiverId, senderId, senderId, receiverId, receiverId, senderId);
    }

    @Override
    public int countFriends(String userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM friend_relation WHERE user_id=?",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    @Override
    public void insertFriend(String userId, String friendId, long createdAt) {
        jdbc.update("INSERT INTO friend_relation(user_id, friend_id, created_at) VALUES (?, ?, ?)",
                userId, friendId, createdAt);
    }

    @Override
    public void deleteFriend(String userId, String friendId) {
        jdbc.update("DELETE FROM friend_relation WHERE user_id=? AND friend_id=?", userId, friendId);
    }

    @Override
    public void deleteFriendData(String userId, String friendId) {
        jdbc.update("DELETE FROM friend_group_member WHERE user_id=? AND friend_id=?", userId, friendId);
        jdbc.update("DELETE FROM friend_setting WHERE user_id=? AND friend_id=?", userId, friendId);
        deleteFriend(userId, friendId);
    }

    @Override
    public List<FriendRecord> listFriends(String userId) {
        List<FriendRecord> friends = jdbc.query("""
                SELECT r.friend_id, p.nickname, p.avatar_url,
                       COALESCE(s.remark, '') AS remark, COALESCE(s.pinned, FALSE) AS pinned, r.created_at
                FROM friend_relation r
                JOIN user_profile p ON p.user_id=r.friend_id
                LEFT JOIN friend_setting s ON s.user_id=r.user_id AND s.friend_id=r.friend_id
                WHERE r.user_id=?
                ORDER BY r.created_at ASC
                """, (rs, rowNum) -> new FriendRecord(
                rs.getString("friend_id"), rs.getString("nickname"), rs.getString("avatar_url"),
                rs.getString("remark"), rs.getBoolean("pinned"), rs.getLong("created_at"), List.of()), userId);
        List<FriendRecord> result = new ArrayList<>(friends.size());
        for (FriendRecord friend : friends) {
            result.add(new FriendRecord(friend.friendId(), friend.nickname(), friend.avatarUrl(),
                    friend.remark(), friend.pinned(), friend.createdAt(), listFriendGroupIds(userId, friend.friendId())));
        }
        return result;
    }

    @Override
    public Optional<FriendSettingRecord> findFriendSetting(String userId, String friendId) {
        return jdbc.query("""
                SELECT remark, pinned FROM friend_setting WHERE user_id=? AND friend_id=?
                """, rs -> rs.next() ? Optional.of(new FriendSettingRecord(
                rs.getString("remark"), rs.getBoolean("pinned"))) : Optional.empty(), userId, friendId);
    }

    @Override
    public void upsertFriendSetting(String userId, String friendId, String remark, boolean pinned, long updatedAt) {
        jdbc.update("""
                INSERT INTO friend_setting(user_id, friend_id, remark, pinned, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE remark=VALUES(remark), pinned=VALUES(pinned), updated_at=VALUES(updated_at)
                """, userId, friendId, remark, pinned, updatedAt);
    }

    @Override
    public void replaceFriendGroups(String userId, String friendId, List<String> groupIds) {
        jdbc.update("DELETE FROM friend_group_member WHERE user_id=? AND friend_id=?", userId, friendId);
        for (String groupId : groupIds) {
            jdbc.update("INSERT INTO friend_group_member(group_id, user_id, friend_id) VALUES (?, ?, ?)",
                    groupId, userId, friendId);
        }
    }

    @Override
    public List<FriendGroupRecord> listFriendGroups(String userId) {
        return jdbc.query("""
                SELECT g.group_id, g.name, g.created_at, COUNT(m.friend_id) AS member_count
                FROM friend_group g
                LEFT JOIN friend_group_member m ON m.group_id=g.group_id
                WHERE g.user_id=?
                GROUP BY g.group_id, g.name, g.created_at
                ORDER BY g.created_at ASC
                """, (rs, rowNum) -> new FriendGroupRecord(
                rs.getString("group_id"), rs.getString("name"), rs.getLong("created_at"),
                rs.getInt("member_count")), userId);
    }

    @Override
    public Optional<FriendGroupRecord> findFriendGroup(String groupId, String userId) {
        return jdbc.query("""
                SELECT g.group_id, g.name, g.created_at, COUNT(m.friend_id) AS member_count
                FROM friend_group g LEFT JOIN friend_group_member m ON m.group_id=g.group_id
                WHERE g.group_id=? AND g.user_id=? GROUP BY g.group_id, g.name, g.created_at
                """, rs -> rs.next() ? Optional.of(new FriendGroupRecord(
                rs.getString("group_id"), rs.getString("name"), rs.getLong("created_at"),
                rs.getInt("member_count"))) : Optional.empty(), groupId, userId);
    }

    @Override
    public void insertFriendGroup(String userId, FriendGroupRecord group) {
        jdbc.update("INSERT INTO friend_group(group_id, user_id, name, created_at) VALUES (?, ?, ?, ?)",
                group.groupId(), userId, group.name(), group.createdAt());
    }

    @Override
    public boolean renameFriendGroup(String groupId, String userId, String name) {
        return jdbc.update("UPDATE friend_group SET name=? WHERE group_id=? AND user_id=?",
                name, groupId, userId) == 1;
    }

    @Override
    public boolean deleteFriendGroup(String groupId, String userId) {
        jdbc.update("DELETE FROM friend_group_member WHERE group_id=? AND user_id=?", groupId, userId);
        return jdbc.update("DELETE FROM friend_group WHERE group_id=? AND user_id=?", groupId, userId) == 1;
    }

    @Override
    public boolean isGroupOwnedBy(String groupId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM friend_group WHERE group_id=? AND user_id=?
                """, Integer.class, groupId, userId);
        return count != null && count > 0;
    }

    @Override
    public boolean isBlacklisted(String userId, String blockedId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM blacklist WHERE user_id=? AND blocked_id=?",
                Integer.class, userId, blockedId);
        return count != null && count > 0;
    }

    @Override
    public void insertBlacklist(String userId, String blockedId, long createdAt) {
        jdbc.update("INSERT INTO blacklist(user_id, blocked_id, created_at) VALUES (?, ?, ?)",
                userId, blockedId, createdAt);
    }

    @Override
    public boolean deleteBlacklist(String userId, String blockedId) {
        return jdbc.update("DELETE FROM blacklist WHERE user_id=? AND blocked_id=?", userId, blockedId) == 1;
    }

    @Override
    public List<BlacklistRecord> listBlacklist(String userId) {
        return jdbc.query("""
                SELECT b.blocked_id, p.nickname, p.avatar_url, b.created_at
                FROM blacklist b JOIN user_profile p ON p.user_id=b.blocked_id
                WHERE b.user_id=? ORDER BY b.created_at DESC
                """, (rs, rowNum) -> new BlacklistRecord(
                rs.getString("blocked_id"), rs.getString("nickname"), rs.getString("avatar_url"),
                rs.getLong("created_at")), userId);
    }

    @Override
    public Optional<RelationSettingRecord> findRelationSetting(String userId) {
        return jdbc.query("""
                SELECT auto_accept_requests, friend_limit FROM user_relation_setting WHERE user_id=?
                """, rs -> rs.next() ? Optional.of(new RelationSettingRecord(
                rs.getBoolean("auto_accept_requests"), rs.getInt("friend_limit"))) : Optional.empty(), userId);
    }

    @Override
    public void upsertAutoAccept(String userId, boolean autoAccept, long updatedAt) {
        jdbc.update("""
                INSERT INTO user_relation_setting(user_id, auto_accept_requests, friend_limit, updated_at)
                VALUES (?, ?, 10000, ?)
                ON DUPLICATE KEY UPDATE auto_accept_requests=VALUES(auto_accept_requests), updated_at=VALUES(updated_at)
                """, userId, autoAccept, updatedAt);
    }

    @Override
    public Optional<QrCardRecord> findActiveQrCardByHash(String tokenHash, long now) {
        return jdbc.query("""
                SELECT card_id, user_id, token_hash, token_value, status, created_at, expires_at FROM qr_card
                WHERE token_hash=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>?)
                """, rs -> rs.next() ? Optional.of(mapQr(rs)) : Optional.empty(), tokenHash, now);
    }

    @Override
    public Optional<QrCardRecord> findActiveQrCardByUser(String userId, long now) {
        return jdbc.query("""
                SELECT card_id, user_id, token_hash, token_value, status, created_at, expires_at FROM qr_card
                WHERE user_id=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>?)
                ORDER BY created_at DESC LIMIT 1
                """, rs -> rs.next() ? Optional.of(mapQr(rs)) : Optional.empty(), userId, now);
    }

    @Override
    public void revokeActiveQrCards(String userId) {
        jdbc.update("UPDATE qr_card SET status='REVOKED' WHERE user_id=? AND status='ACTIVE'", userId);
    }

    @Override
    public void insertQrCard(QrCardRecord card) {
        jdbc.update("""
                INSERT INTO qr_card(card_id, user_id, token_hash, token_value, status, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, card.cardId(), card.userId(), card.tokenHash(), card.tokenValue(), card.status(),
                card.createdAt(), card.expiresAt());
    }

    private List<String> listFriendGroupIds(String userId, String friendId) {
        return jdbc.query("""
                SELECT group_id FROM friend_group_member WHERE user_id=? AND friend_id=? ORDER BY group_id
                """, (rs, rowNum) -> rs.getString(1), userId, friendId);
    }

    private FriendRequestRecord mapRequest(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FriendRequestRecord(
                rs.getString("request_id"), rs.getString("app_id"), rs.getString("from_user_id"),
                rs.getString("to_user_id"), rs.getString("verification_message"), rs.getString("status"),
                rs.getLong("created_at"), (Long) rs.getObject("handled_at"),
                (Long) rs.getObject("read_at"), rs.getString("pending_key"));
    }

    private QrCardRecord mapQr(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new QrCardRecord(rs.getString("card_id"), rs.getString("user_id"), rs.getString("token_hash"),
                rs.getString("token_value"), rs.getString("status"), rs.getLong("created_at"),
                (Long) rs.getObject("expires_at"));
    }
}
