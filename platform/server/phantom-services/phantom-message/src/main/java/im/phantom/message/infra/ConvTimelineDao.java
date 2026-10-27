package im.phantom.message.infra;

import im.phantom.proto.v1.Msg;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会话 Timeline DAO（消息服务自管其表）。
 * 落库与取号水位在同一本地事务内推进（design D2），保证真相源不落后于已落库消息。
 */
@Repository
public class ConvTimelineDao {

    private final JdbcTemplate jdbc;

    public ConvTimelineDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等命中结果。 */
    public record Existing(String msgId, long convSeq, long serverTime) {}

    /** 按 (sender, client_msg_id) 查已落库消息（发送幂等）。 */
    public Existing findByClientMsgId(String senderId, String clientMsgId) {
        return jdbc.query(
                "SELECT msg_id, conv_seq, server_time FROM conv_timeline WHERE sender_id=? AND client_msg_id=?",
                rs -> rs.next() ? new Existing(rs.getString(1), rs.getLong(2), rs.getLong(3)) : null,
                senderId, clientMsgId);
    }

    /** 事务回查依据：会话 Timeline 是否存在该 msg_id。 */
    public boolean existsByMsgId(String msgId) {
        Integer n = jdbc.query("SELECT 1 FROM conv_timeline WHERE msg_id=? LIMIT 1",
                rs -> rs.next() ? 1 : 0, msgId);
        return n != null && n == 1;
    }

    /** 读取完整消息体；消息不存在时返回 null。 */
    public Msg findByMsgId(String msgId) {
        return jdbc.query(
                "SELECT msg_bytes FROM conv_timeline WHERE msg_id=?",
                rs -> rs.next() ? parse(rs.getBytes(1)) : null,
                msgId);
    }

    /** 按 conv_seq 向前分页，返回结果按 seq 升序，便于客户端直接追加。 */
    public List<Msg> history(String convId, long beforeConvSeq, int limit) {
        return jdbc.query(
                "SELECT msg_bytes FROM conv_timeline WHERE conv_id=? AND conv_seq<? " +
                        "ORDER BY conv_seq DESC LIMIT ?",
                (rs, rowNum) -> parse(rs.getBytes(1)), convId, beforeConvSeq, limit)
                .reversed();
    }

    public List<Msg> historyForUser(String userId, String convId, long beforeConvSeq, int limit) {
        return jdbc.query("""
                SELECT ct.msg_bytes FROM conv_timeline ct
                WHERE ct.conv_id=? AND ct.conv_seq<?
                  AND ct.conv_seq>COALESCE((SELECT cleared_conv_seq FROM user_conversation_clear
                                             WHERE user_id=? AND conv_id=?), 0)
                  AND NOT EXISTS (SELECT 1 FROM user_message_deletion d
                                  WHERE d.user_id=? AND d.msg_id=ct.msg_id)
                ORDER BY ct.conv_seq DESC LIMIT ?
                """, (rs, rowNum) -> parse(rs.getBytes(1)), convId, beforeConvSeq, userId, convId,
                userId, limit).reversed();
    }

    public void deleteLocal(String userId, String msgId, String convId, long now) {
        jdbc.update("""
                INSERT INTO user_message_deletion(user_id, msg_id, conv_id, deleted_at) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE deleted_at=VALUES(deleted_at)
                """, userId, msgId, convId, now);
    }

    public void clearHistory(String userId, String convId, long now) {
        long max = jdbc.query("SELECT COALESCE(MAX(conv_seq),0) FROM conv_timeline WHERE conv_id=?",
                rs -> rs.next() ? rs.getLong(1) : 0L, convId);
        jdbc.update("""
                INSERT INTO user_conversation_clear(user_id, conv_id, cleared_conv_seq, updated_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE cleared_conv_seq=GREATEST(cleared_conv_seq, VALUES(cleared_conv_seq)),
                    updated_at=VALUES(updated_at)
                """, userId, convId, max, now);
    }

    public String displayName(String userId) {
        String nickname = jdbc.query("SELECT nickname FROM user_profile WHERE user_id=?",
                rs -> rs.next() ? rs.getString(1) : null, userId);
        return nickname == null || nickname.isBlank() ? userId : nickname;
    }

    public boolean isMember(String convId, String userId) {
        Integer n = jdbc.query("SELECT 1 FROM conversation_member WHERE conv_id=? AND user_id=? " +
                        "AND left_at IS NULL LIMIT 1",
                rs -> rs.next() ? 1 : 0, convId, userId);
        return n != null && n == 1;
    }

    public List<String> listParticipantIds(String convId) {
        return jdbc.queryForList("SELECT user_id FROM conversation_member " +
                "WHERE conv_id=? AND left_at IS NULL ORDER BY joined_at", String.class, convId);
    }

    public List<Msg> messagesUpTo(String convId, long maxConvSeq, int limit) {
        return jdbc.query("SELECT msg_bytes FROM conv_timeline WHERE conv_id=? AND conv_seq<=? " +
                        "ORDER BY conv_seq ASC LIMIT ?",
                (rs, rowNum) -> parse(rs.getBytes(1)), convId, maxConvSeq, limit);
    }

    public String findNewMessageIdByUserSeq(String userId, long userSeq) {
        return jdbc.query("SELECT msg_id FROM user_timeline WHERE user_id=? AND user_seq=? " +
                        "AND event_type=10",
                rs -> rs.next() ? rs.getString(1) : null, userId, userSeq);
    }

    public boolean ownsReadyMedia(String appId, String ownerId, String mediaId) {
        Integer count = jdbc.query("SELECT COUNT(*) FROM media_object WHERE media_id=? AND app_id=? " +
                        "AND owner_id=? AND status='READY'",
                rs -> rs.next() ? rs.getInt(1) : 0, mediaId, appId, ownerId);
        return count != null && count > 0;
    }

    public String mediaMimeType(String mediaId) {
        return jdbc.query("SELECT mime_type FROM media_object WHERE media_id=?",
                rs -> rs.next() ? rs.getString(1) : null, mediaId);
    }

    /** 撤回状态只能从未撤回变为已撤回。 */
    public boolean markRecalled(Msg recalled, long recalledAt) {
        int meta = jdbc.update("UPDATE message_meta SET recalled_at=?, updated_at=? " +
                        "WHERE msg_id=? AND recalled_at IS NULL", recalledAt, recalledAt, recalled.getMsgId());
        if (meta != 1) {
            return false;
        }
        jdbc.update("UPDATE conv_timeline SET recalled=TRUE, msg_bytes=? WHERE msg_id=?",
                recalled.toByteArray(), recalled.getMsgId());
        return true;
    }

    /** 状态只进不退；FAILED 仅允许由发送端首次失败进入。 */
    public boolean advanceStatus(String msgId, int targetStatus, long now) {
        return jdbc.update("""
                UPDATE message_meta SET status=?, updated_at=?,
                    delivered_at=CASE WHEN ?=3 AND delivered_at IS NULL THEN ? ELSE delivered_at END,
                    read_at=CASE WHEN ?=4 AND read_at IS NULL THEN ? ELSE read_at END
                WHERE msg_id=? AND (status<? OR (status=1 AND ?=5))
                """, targetStatus, now, targetStatus, now, targetStatus, now, msgId,
                targetStatus, targetStatus) == 1;
    }

    /**
     * 本地事务：插入会话 Timeline + 推进取号水位。
     * 命中 (sender, client_msg_id) 唯一键时抛 DuplicateKeyException（幂等竞态由上层处理）。
     */
    @Transactional
    public void insert(Msg msg) {
        jdbc.update(
                "INSERT INTO conv_timeline(conv_id, conv_seq, msg_id, client_msg_id, sender_id, " +
                "conv_type, msg_type, msg_bytes, server_time, recalled) VALUES(?,?,?,?,?,?,?,?,?,0)",
                msg.getConvId(), msg.getConvSeq(), msg.getMsgId(), msg.getClientMsgId(), msg.getSenderId(),
                msg.getConvTypeValue(), msg.getMsgTypeValue(), msg.toByteArray(), msg.getServerTime());
        jdbc.update("""
                INSERT INTO message_meta(msg_id, status, restricted, reply_to_msg_id, mentions_json,
                    burn_ttl_seconds, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE status=GREATEST(status, VALUES(status)),
                    restricted=VALUES(restricted), reply_to_msg_id=VALUES(reply_to_msg_id),
                    mentions_json=VALUES(mentions_json), burn_ttl_seconds=VALUES(burn_ttl_seconds),
                    updated_at=VALUES(updated_at)
                """, msg.getMsgId(), msg.getStatusValue(), msg.getRestricted(),
                msg.getReplyToMsgId().isBlank() ? null : msg.getReplyToMsgId(),
                mentionsJson(msg), msg.getBurnAfterReadTtlSeconds(), msg.getServerTime());
        for (String mediaId : mediaIds(msg)) {
            jdbc.update("INSERT INTO message_media_ref(media_id, msg_id, conv_id, created_at) " +
                            "VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE conv_id=VALUES(conv_id)",
                    mediaId, msg.getMsgId(), msg.getConvId(), msg.getServerTime());
            long burnExpiresAt = msg.getServerTime() + Math.max(1, msg.getBurnAfterReadTtlSeconds()) * 1000L;
            jdbc.update("UPDATE media_object SET expires_at=CASE WHEN burn_after_read=TRUE THEN ? ELSE NULL END " +
                    "WHERE media_id=?", burnExpiresAt, mediaId);
        }
        // 水位真相源：GREATEST 保证只增不减（sequence-allocation 恢复对齐读取此值）
        jdbc.update(
                "INSERT INTO seq_watermark(scope, seq) VALUES(?, ?) " +
                "ON DUPLICATE KEY UPDATE seq=GREATEST(seq, VALUES(seq))",
                "conv:" + msg.getConvId(), msg.getConvSeq());
    }

    private Msg parse(byte[] bytes) {
        try {
            return Msg.parseFrom(bytes);
        } catch (Exception malformed) {
            throw new IllegalStateException("会话 Timeline 消息体损坏", malformed);
        }
    }

    private String mentionsJson(Msg msg) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < msg.getMentionsCount(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(msg.getMentions(i).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return json.append(']').toString();
    }

    private List<String> mediaIds(Msg msg) {
        return switch (msg.getMsgType()) {
            case MSG_TYPE_IMAGE -> List.of(msg.getContent().getImage().getMediaId());
            case MSG_TYPE_VOICE -> List.of(msg.getContent().getVoice().getMediaId());
            case MSG_TYPE_VIDEO -> List.of(msg.getContent().getVideo().getMediaId());
            case MSG_TYPE_FILE -> List.of(msg.getContent().getFile().getMediaId());
            default -> List.of();
        };
    }
}
