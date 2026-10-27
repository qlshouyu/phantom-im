package im.phantom.conversation.infra;

import im.phantom.conversation.domain.ConversationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** JdbcTemplate 会话投影仓库。 */
@Repository
public class JdbcConversationRepository implements ConversationRepository {

    private final JdbcTemplate jdbc;

    public JdbcConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> findAppIdByUserId(String userId) {
        return jdbc.query("SELECT app_id FROM app_user WHERE user_id=?",
                rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(), userId);
    }

    @Override
    public void insertConversationIfAbsent(ConversationRecord conversation) {
        jdbc.update("""
                INSERT INTO conversation(conv_id, conv_type, owner_app_id, target_id, status,
                    read_only, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE updated_at=GREATEST(updated_at, VALUES(updated_at))
                """, conversation.convId(), conversation.convType(), conversation.ownerAppId(),
                conversation.targetId(), conversation.status(), conversation.readOnly(),
                conversation.createdAt(), conversation.updatedAt());
    }

    @Override
    public void ensureMember(String convId, String userId, String role, long joinedAt) {
        jdbc.update("""
                INSERT INTO conversation_member(conv_id, user_id, member_role, joined_at, left_at)
                VALUES (?, ?, ?, ?, NULL)
                ON DUPLICATE KEY UPDATE left_at=NULL
                """, convId, userId, role, joinedAt);
    }

    @Override
    public List<String> listActiveMemberIds(String convId) {
        return jdbc.query("""
                SELECT user_id FROM conversation_member WHERE conv_id=? AND left_at IS NULL ORDER BY joined_at, user_id
                """, (rs, rowNum) -> rs.getString(1), convId);
    }

    @Override
    public Optional<String> findPeerId(String convId, String userId) {
        return jdbc.query("""
                SELECT cm.user_id FROM conversation_member cm
                JOIN conversation c ON c.conv_id=cm.conv_id
                WHERE cm.conv_id=? AND cm.user_id<>? AND cm.left_at IS NULL AND c.conv_type=1 LIMIT 1
                """, rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(), convId, userId);
    }

    @Override
    public Optional<GroupInfo> findGroupInfo(String convId, String userId) {
        return jdbc.query("""
                SELECT g.group_id, g.conv_id, g.name, g.avatar_url, g.owner_id, g.status,
                       g.member_count, m.status AS member_status
                FROM chat_group g LEFT JOIN group_member m ON m.group_id=g.group_id AND m.user_id=?
                WHERE g.conv_id=?
                """, rs -> rs.next() ? Optional.of(new GroupInfo(
                rs.getString("group_id"), rs.getString("conv_id"), rs.getString("name"),
                rs.getString("avatar_url"), rs.getString("owner_id"), rs.getString("status"),
                rs.getInt("member_count"), rs.getString("member_status"))) : Optional.empty(), userId, convId);
    }

    @Override
    public void ensureUserConversation(String userId, String convId, long now) {
        jdbc.update("""
                INSERT INTO user_conversation(user_id, conv_id, pinned, pinned_at, muted, deleted,
                    draft, draft_edited_at, unread_count, mention_me, is_request, last_message_at,
                    last_message_digest, last_message_conv_seq, read_only, updated_at)
                VALUES (?, ?, FALSE, 0, FALSE, FALSE, '', 0, 0, FALSE, FALSE, 0, '', 0, FALSE, ?)
                ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)
                """, userId, convId, now);
    }

    @Override
    public void resetDeletedState(String userId, String convId, long now) {
        jdbc.update("""
                UPDATE user_conversation
                SET pinned=FALSE, pinned_at=0, muted=FALSE, deleted=FALSE, draft='', draft_edited_at=0,
                    unread_count=0, mention_me=FALSE, is_request=FALSE, read_only=FALSE, updated_at=?
                WHERE user_id=? AND conv_id=? AND deleted=TRUE
                """, now, userId, convId);
    }

    @Override
    public void appendMessageProjection(String userId, String convId, String digest, long messageAt,
                                        long convSeq, boolean incrementUnread, boolean mentionMe, long now) {
        jdbc.update("""
                UPDATE user_conversation
                SET last_message_digest=CASE WHEN ?>=last_message_conv_seq THEN ? ELSE last_message_digest END,
                    last_message_at=CASE WHEN ?>=last_message_conv_seq THEN ? ELSE last_message_at END,
                    last_message_conv_seq=GREATEST(last_message_conv_seq, ?),
                    unread_count=unread_count + ?,
                    mention_me=CASE WHEN ?=TRUE THEN TRUE ELSE mention_me END,
                    updated_at=?
                WHERE user_id=? AND conv_id=?
                """, convSeq, digest, convSeq, messageAt, convSeq, incrementUnread ? 1 : 0, mentionMe,
                now, userId, convId);
    }

    @Override
    public void markMessageRequest(String userId, String convId, long now) {
        jdbc.update("UPDATE user_conversation SET is_request=TRUE, unread_count=0, mention_me=FALSE, " +
                        "updated_at=? WHERE user_id=? AND conv_id=?",
                now, userId, convId);
    }

    @Override
    public void clearMessageRequest(String userId, String convId, int unreadCount, long now) {
        jdbc.update("UPDATE user_conversation SET is_request=FALSE, unread_count=?, updated_at=? " +
                "WHERE user_id=? AND conv_id=?", unreadCount, now, userId, convId);
    }

    @Override
    public void clearDraft(String userId, String convId, long editedAt) {
        jdbc.update("""
                UPDATE user_conversation SET draft='', draft_edited_at=?, updated_at=?
                WHERE user_id=? AND conv_id=?
                """, editedAt, editedAt, userId, convId);
    }

    @Override
    public Optional<UserConversationState> findUserConversation(String userId, String convId) {
        return jdbc.query("""
                SELECT user_id, conv_id, pinned, pinned_at, muted, deleted, draft, draft_edited_at,
                       unread_count, mention_me, is_request, last_message_digest, last_message_at,
                       last_message_conv_seq, read_only, updated_at
                FROM user_conversation WHERE user_id=? AND conv_id=?
                """, rs -> rs.next() ? Optional.of(new UserConversationState(
                rs.getString("user_id"), rs.getString("conv_id"), rs.getBoolean("pinned"),
                rs.getLong("pinned_at"), rs.getBoolean("muted"), rs.getBoolean("deleted"),
                rs.getString("draft"), rs.getLong("draft_edited_at"), rs.getInt("unread_count"),
                rs.getBoolean("mention_me"), rs.getBoolean("is_request"),
                rs.getString("last_message_digest"), rs.getLong("last_message_at"),
                rs.getLong("last_message_conv_seq"), rs.getBoolean("read_only"),
                rs.getLong("updated_at"))) : Optional.empty(), userId, convId);
    }

    @Override
    public List<ConversationRow> listConversations(String userId, int offset, int limit) {
        return queryRows(userId, null, offset, limit);
    }

    @Override
    public Optional<ConversationRow> findConversation(String userId, String convId) {
        List<ConversationRow> rows = queryRows(userId, convId, 0, 1);
        return rows.stream().findFirst();
    }

    @Override
    public int badgeCount(String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COALESCE(SUM(unread_count), 0) FROM user_conversation
                WHERE user_id=? AND deleted=FALSE AND muted=FALSE
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }

    @Override
    public long maxUserSeq(String userId) {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(user_seq), 0) FROM user_timeline WHERE user_id=?",
                Long.class, userId);
        return max == null ? 0 : max;
    }

    @Override
    public long maxConvSeq(String convId) {
        Long seq = jdbc.query("SELECT seq FROM seq_watermark WHERE scope=?",
                rs -> rs.next() ? rs.getLong(1) : 0L, "conv:" + convId);
        return seq == null ? 0 : seq;
    }

    @Override
    public void advanceConvSeq(String convId, long convSeq) {
        jdbc.update("""
                INSERT INTO seq_watermark(scope, seq) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE seq=GREATEST(seq, VALUES(seq))
                """, "conv:" + convId, convSeq);
    }

    @Override
    public void updateConversationState(String userId, String convId, boolean pinned, boolean muted,
                                        long pinnedAt, long now) {
        int updated = jdbc.update("""
                UPDATE user_conversation SET pinned=?, pinned_at=?, muted=?, updated_at=?
                WHERE user_id=? AND conv_id=? AND deleted=FALSE
                """, pinned, pinnedAt, muted, now, userId, convId);
        if (updated == 0) {
            throw new IllegalStateException("会话不存在或已删除");
        }
    }

    @Override
    public void markConversationDeleted(String userId, String convId, long now) {
        int updated = jdbc.update("""
                UPDATE user_conversation SET deleted=TRUE, pinned=FALSE, pinned_at=0, muted=FALSE, updated_at=?
                WHERE user_id=? AND conv_id=? AND deleted=FALSE
                """, now, userId, convId);
        if (updated == 0) {
            throw new IllegalStateException("会话不存在或已删除");
        }
    }

    @Override
    public void updateReadOnly(String userId, String convId, boolean readOnly, long now) {
        jdbc.update("""
                UPDATE user_conversation SET read_only=?, updated_at=? WHERE user_id=? AND conv_id=?
                """, readOnly, now, userId, convId);
    }

    @Override
    public long upsertReadPosition(String userId, String convId, long readConvSeq, long now) {
        jdbc.update("""
                INSERT INTO read_position(user_id, conv_id, read_conv_seq, updated_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE read_conv_seq=GREATEST(read_conv_seq, VALUES(read_conv_seq)),
                    updated_at=VALUES(updated_at)
                """, userId, convId, readConvSeq, now);
        return currentReadPosition(userId, convId);
    }

    @Override
    public long currentReadPosition(String userId, String convId) {
        Long position = jdbc.query("SELECT read_conv_seq FROM read_position WHERE user_id=? AND conv_id=?",
                rs -> rs.next() ? rs.getLong(1) : 0L, userId, convId);
        return position == null ? 0 : position;
    }

    @Override
    public int countUnreadAfter(String userId, String convId, long readConvSeq) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM conv_timeline
                WHERE conv_id=? AND conv_seq>? AND sender_id<>?
                """, Integer.class, convId, readConvSeq, userId);
        return count == null ? 0 : count;
    }

    @Override
    public List<String> listMentionJsonAfter(String convId, long readConvSeq) {
        return jdbc.query("""
                SELECT mm.mentions_json FROM message_meta mm
                JOIN conv_timeline ct ON ct.msg_id=mm.msg_id
                WHERE ct.conv_id=? AND ct.conv_seq>? ORDER BY ct.conv_seq
                """, (rs, rowNum) -> rs.getString(1), convId, readConvSeq);
    }

    @Override
    public int countReadersAtLeast(String convId, long readConvSeq) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM read_position WHERE conv_id=? AND read_conv_seq>=?
                """, Integer.class, convId, readConvSeq);
        return count == null ? 0 : count;
    }

    @Override
    public void updateUnreadProjection(String userId, String convId, int unreadCount, boolean mentionMe, long now) {
        jdbc.update("""
                UPDATE user_conversation SET unread_count=?, mention_me=?, updated_at=?
                WHERE user_id=? AND conv_id=?
                """, unreadCount, mentionMe, now, userId, convId);
    }

    @Override
    public long saveDraftIfNewer(String userId, String convId, String draft, long editedAt, long now) {
        jdbc.update("""
                UPDATE user_conversation SET draft=?, draft_edited_at=?, updated_at=?
                WHERE user_id=? AND conv_id=? AND draft_edited_at<=?
                """, draft, editedAt, now, userId, convId, editedAt);
        return findDraft(userId, convId).map(DraftState::editedAt).orElse(0L);
    }

    @Override
    public Optional<DraftState> findDraft(String userId, String convId) {
        return jdbc.query("""
                SELECT draft, draft_edited_at FROM user_conversation WHERE user_id=? AND conv_id=?
                """, rs -> rs.next() ? Optional.of(new DraftState(rs.getString("draft"),
                rs.getLong("draft_edited_at"))) : Optional.empty(), userId, convId);
    }

    @Override
    public void upsertMessageMeta(String msgId, int status, boolean restricted, String replyToMsgId,
                                  String mentionsJson, int burnTtlSeconds, long now) {
        jdbc.update("""
                INSERT INTO message_meta(msg_id, status, restricted, reply_to_msg_id, mentions_json,
                    burn_ttl_seconds, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE status=VALUES(status), restricted=VALUES(restricted),
                    reply_to_msg_id=VALUES(reply_to_msg_id), mentions_json=VALUES(mentions_json),
                    burn_ttl_seconds=VALUES(burn_ttl_seconds), updated_at=VALUES(updated_at)
                """, msgId, status, restricted, replyToMsgId, mentionsJson, burnTtlSeconds, now);
    }

    private List<ConversationRow> queryRows(String userId, String convId, int offset, int limit) {
        String convFilter = convId == null ? "" : " AND uc.conv_id=?";
        String sql = """
                SELECT uc.conv_id, c.conv_type, c.owner_app_id, c.target_id, c.status AS conv_status,
                       c.read_only AS conversation_read_only,
                       g.group_id, g.name AS group_name, g.avatar_url AS group_avatar_url,
                       g.status AS group_status, gm.status AS group_member_status,
                       peer_m.user_id AS peer_id, peer_p.nickname AS peer_nickname,
                       peer_p.avatar_url AS peer_avatar_url,
                       uc.pinned, uc.pinned_at, uc.muted, uc.deleted, uc.draft, uc.draft_edited_at,
                       uc.unread_count, uc.mention_me, uc.is_request, uc.last_message_digest,
                       uc.last_message_at, uc.last_message_conv_seq, uc.read_only AS user_read_only,
                       COALESCE(rp.read_conv_seq, 0) AS read_conv_seq, COALESCE(sw.seq, 0) AS max_conv_seq,
                       uc.updated_at
                FROM user_conversation uc
                JOIN conversation c ON c.conv_id=uc.conv_id
                LEFT JOIN chat_group g ON g.conv_id=uc.conv_id
                LEFT JOIN group_member gm ON gm.group_id=g.group_id AND gm.user_id=uc.user_id
                LEFT JOIN conversation_member peer_m ON peer_m.conv_id=uc.conv_id
                    AND c.conv_type=1 AND peer_m.user_id<>uc.user_id AND peer_m.left_at IS NULL
                LEFT JOIN user_profile peer_p ON peer_p.user_id=peer_m.user_id
                LEFT JOIN read_position rp ON rp.user_id=uc.user_id AND rp.conv_id=uc.conv_id
                LEFT JOIN seq_watermark sw ON sw.scope=CONCAT('conv:', uc.conv_id)
                WHERE uc.user_id=? AND uc.deleted=FALSE
                """ + convFilter + """
                ORDER BY uc.pinned DESC,
                    CASE WHEN uc.pinned=TRUE THEN uc.pinned_at ELSE uc.last_message_at END DESC,
                    uc.last_message_conv_seq DESC, uc.conv_id DESC
                LIMIT ? OFFSET ?
                """;
        Object[] params = convId == null
                ? new Object[]{userId, limit, offset}
                : new Object[]{userId, convId, limit, offset};
        return jdbc.query(sql, (rs, rowNum) -> new ConversationRow(
                rs.getString("conv_id"), rs.getInt("conv_type"), rs.getString("owner_app_id"),
                rs.getString("target_id"), rs.getString("conv_status"), rs.getBoolean("conversation_read_only"),
                rs.getString("group_id"), rs.getString("group_name"), rs.getString("group_avatar_url"),
                rs.getString("group_status"), rs.getString("group_member_status"),
                rs.getString("peer_id"), rs.getString("peer_nickname"), rs.getString("peer_avatar_url"),
                rs.getBoolean("pinned"), rs.getLong("pinned_at"), rs.getBoolean("muted"),
                rs.getBoolean("deleted"), rs.getString("draft"), rs.getLong("draft_edited_at"),
                rs.getInt("unread_count"), rs.getBoolean("mention_me"), rs.getBoolean("is_request"),
                rs.getString("last_message_digest"), rs.getLong("last_message_at"),
                rs.getLong("last_message_conv_seq"), rs.getBoolean("user_read_only"),
                rs.getLong("read_conv_seq"), rs.getLong("max_conv_seq"), rs.getLong("updated_at")),
                params);
    }
}
