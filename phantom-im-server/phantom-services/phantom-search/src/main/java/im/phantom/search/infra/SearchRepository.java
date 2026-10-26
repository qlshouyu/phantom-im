package im.phantom.search.infra;

import im.phantom.proto.v1.Msg;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SearchRepository {
    private final JdbcTemplate jdbc;
    public SearchRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ContactRow> contacts(String userId, String query, int limit) {
        String like = "%" + query + "%";
        return jdbc.query("""
                SELECT u.user_id, p.nickname, COALESCE(s.remark,'') remark, p.avatar_url
                FROM friend_relation r JOIN app_user u ON u.user_id=r.friend_id
                JOIN user_profile p ON p.user_id=u.user_id
                LEFT JOIN friend_setting s ON s.user_id=r.user_id AND s.friend_id=r.friend_id
                WHERE r.user_id=? AND u.status='ACTIVE'
                  AND (p.nickname LIKE ? OR s.remark LIKE ? OR u.user_id LIKE ?)
                  AND NOT EXISTS (SELECT 1 FROM blacklist b WHERE b.user_id=? AND b.blocked_id=r.friend_id)
                ORDER BY s.pinned DESC, COALESCE(NULLIF(s.remark,''), p.nickname), u.user_id LIMIT ?
                """, (rs, row) -> new ContactRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4)), userId, like, like, like, userId, limit);
    }

    public List<GroupRow> groups(String userId, String query, int limit) {
        String like = "%" + query + "%";
        return jdbc.query("""
                SELECT g.group_id, g.conv_id, g.name, g.avatar_url
                FROM chat_group g JOIN group_member gm ON gm.group_id=g.group_id
                WHERE gm.user_id=? AND gm.status='ACTIVE' AND g.status='ACTIVE'
                  AND (g.name LIKE ? OR EXISTS (
                    SELECT 1 FROM group_member gm2 JOIN user_profile p2 ON p2.user_id=gm2.user_id
                    LEFT JOIN friend_setting fs ON fs.user_id=? AND fs.friend_id=gm2.user_id
                    WHERE gm2.group_id=g.group_id AND gm2.status='ACTIVE'
                      AND (p2.nickname LIKE ? OR fs.remark LIKE ?)))
                ORDER BY g.updated_at DESC LIMIT ?
                """, (rs, row) -> new GroupRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4)), userId, like, userId, like, like, limit);
    }

    public List<Msg> accessibleMessages(String userId, String convId, int limit) {
        String filter = convId == null ? "" : " AND ct.conv_id=?";
        String sql = """
                SELECT ct.msg_bytes FROM conv_timeline ct
                WHERE EXISTS (SELECT 1 FROM conversation_member cm
                              WHERE cm.conv_id=ct.conv_id AND cm.user_id=? AND cm.left_at IS NULL)
                """ + filter + " ORDER BY ct.server_time DESC LIMIT ?";
        Object[] args = convId == null
                ? new Object[]{userId, limit} : new Object[]{userId, convId, limit};
        return jdbc.query(sql, (rs, row) -> parse(rs.getBytes(1)), args);
    }

    public String conversationTitle(String userId, String convId) {
        String title = jdbc.query("SELECT name FROM chat_group WHERE conv_id=?",
                rs -> rs.next() ? rs.getString(1) : null, convId);
        if (title != null) return title;
        return jdbc.query("""
                SELECT p.nickname FROM conversation_member cm JOIN user_profile p ON p.user_id=cm.user_id
                WHERE cm.conv_id=? AND cm.user_id<>? AND cm.left_at IS NULL LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : "", convId, userId);
    }

    public List<String> participantIds(String convId) {
        return jdbc.queryForList("SELECT user_id FROM conversation_member " +
                "WHERE conv_id=? AND left_at IS NULL", String.class, convId);
    }

    public void upsertDocument(String docId, String appId, String type, String ownerId,
                               String convId, String msgId, String title, String content, long now) {
        jdbc.update("""
                INSERT INTO search_document(doc_id, app_id, doc_type, owner_id, conv_id, msg_id,
                    title, content, metadata_json, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '{}', ?)
                ON DUPLICATE KEY UPDATE title=VALUES(title), content=VALUES(content), updated_at=VALUES(updated_at)
                """, docId, appId, type, ownerId, convId, msgId, title, content, now);
    }

    public void deleteConversationDocuments(String convId) {
        jdbc.update("DELETE FROM search_document WHERE conv_id=?", convId);
    }

    public Optional<ContactRow> contact(String ownerId, String peerId) {
        return jdbc.query("""
                SELECT u.user_id, p.nickname, COALESCE(s.remark,'') remark, p.avatar_url
                FROM friend_relation r JOIN app_user u ON u.user_id=r.friend_id
                JOIN user_profile p ON p.user_id=u.user_id
                LEFT JOIN friend_setting s ON s.user_id=r.user_id AND s.friend_id=r.friend_id
                WHERE r.user_id=? AND r.friend_id=? AND u.status='ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM blacklist b WHERE b.user_id=? AND b.blocked_id=r.friend_id)
                """, rs -> rs.next() ? Optional.of(new ContactRow(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4))) : Optional.empty(), ownerId, peerId, ownerId);
    }

    public Optional<GroupRow> group(String ownerId, String groupId) {
        return jdbc.query("""
                SELECT g.group_id, g.conv_id, g.name, g.avatar_url
                FROM chat_group g JOIN group_member gm ON gm.group_id=g.group_id
                WHERE gm.user_id=? AND gm.group_id=? AND gm.status='ACTIVE' AND g.status='ACTIVE'
                """, rs -> rs.next() ? Optional.of(new GroupRow(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4))) : Optional.empty(), ownerId, groupId);
    }

    public Optional<Msg> message(String msgId) {
        return jdbc.query("SELECT msg_bytes FROM conv_timeline WHERE msg_id=?",
                rs -> rs.next() ? Optional.of(parse(rs.getBytes(1))) : Optional.empty(), msgId);
    }

    public void deleteContactDocument(String ownerId, String peerId) {
        jdbc.update("DELETE FROM search_document WHERE doc_id=?",
                "contact:" + ownerId + ":" + peerId);
    }

    public void deleteGroupDocument(String ownerId, String groupId) {
        jdbc.update("DELETE FROM search_document WHERE doc_id=?",
                "group:" + ownerId + ":" + groupId);
    }

    public void deleteConversationDocumentsForOwner(String ownerId, String convId) {
        jdbc.update("DELETE FROM search_document WHERE owner_id=? AND conv_id=?", ownerId, convId);
    }

    public String conversationIdForGroup(String groupId) {
        return jdbc.query("SELECT conv_id FROM chat_group WHERE group_id=?",
                rs -> rs.next() ? rs.getString(1) : null, groupId);
    }

    public String appIdForUser(String userId) {
        return jdbc.query("SELECT app_id FROM app_user WHERE user_id=?",
                rs -> rs.next() ? rs.getString(1) : null, userId);
    }

    private Msg parse(byte[] bytes) {
        try { return Msg.parseFrom(bytes); }
        catch (Exception error) { throw new IllegalStateException("消息体损坏", error); }
    }

    public record ContactRow(String userId, String nickname, String remark, String avatarUrl) {}
    public record GroupRow(String groupId, String convId, String name, String avatarUrl) {}
}
