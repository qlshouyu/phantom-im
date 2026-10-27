package im.phantom.message.infra;

import im.phantom.infra.db.outbox.OutboxRecord;
import im.phantom.proto.v1.Msg;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 只读取消息域 outbox，避免与其他领域 relay 重复发布。 */
@Repository
public class MessageOutboxDao {

    public static final String AGGREGATE_MESSAGE_EVENT = "message_event";
    private static final int EVENT_TYPE_NEW_MESSAGE = 1;

    private final JdbcTemplate jdbc;

    public MessageOutboxDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OutboxRecord> fetchPending(int limit, long now) {
        return jdbc.query("""
                SELECT outbox_id, aggregate_type, aggregate_id, event_type, user_id, payload, status,
                       attempts, created_at, next_retry_at, published_at
                FROM sync_outbox
                WHERE aggregate_type='message' AND status='PENDING' AND next_retry_at<=?
                ORDER BY created_at ASC LIMIT ?
                """, (rs, rowNum) -> new OutboxRecord(
                rs.getString("outbox_id"), rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                rs.getInt("event_type"), rs.getString("user_id"), rs.getBytes("payload"),
                rs.getString("status"), rs.getInt("attempts"), rs.getLong("created_at"),
                rs.getLong("next_retry_at"), (Long) rs.getObject("published_at")), now, limit);
    }

    public void appendMessageEvent(Msg message) {
        long now = message.getServerTime();
        jdbc.update("""
                INSERT INTO sync_outbox(outbox_id, aggregate_type, aggregate_id, event_type, user_id,
                    payload, status, attempts, created_at, next_retry_at, published_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, NULL)
                """, "me_" + message.getMsgId(), AGGREGATE_MESSAGE_EVENT, message.getMsgId(),
                EVENT_TYPE_NEW_MESSAGE, message.getSenderId(), message.toByteArray(), now, now);
    }

    public List<OutboxRecord> fetchPendingMessageEvents(int limit, long now) {
        return jdbc.query("""
                SELECT outbox_id, aggregate_type, aggregate_id, event_type, user_id, payload, status,
                       attempts, created_at, next_retry_at, published_at
                FROM sync_outbox
                WHERE aggregate_type=? AND status='PENDING' AND next_retry_at<=?
                ORDER BY created_at ASC LIMIT ?
                """, (rs, rowNum) -> new OutboxRecord(
                rs.getString("outbox_id"), rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                rs.getInt("event_type"), rs.getString("user_id"), rs.getBytes("payload"),
                rs.getString("status"), rs.getInt("attempts"), rs.getLong("created_at"),
                rs.getLong("next_retry_at"), (Long) rs.getObject("published_at")), AGGREGATE_MESSAGE_EVENT,
                now, limit);
    }

    public int countPending() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sync_outbox
                WHERE aggregate_type IN ('message', 'message_event') AND status='PENDING'
                """, Integer.class);
        return count == null ? 0 : count;
    }

    public long oldestPendingAge(long now) {
        Long oldest = jdbc.queryForObject("""
                SELECT MIN(created_at) FROM sync_outbox
                WHERE aggregate_type IN ('message', 'message_event') AND status='PENDING'
                """, Long.class);
        return oldest == null ? 0 : Math.max(0, now - oldest);
    }

    public void markPublished(String outboxId, long publishedAt) {
        jdbc.update("UPDATE sync_outbox SET status='PUBLISHED', attempts=attempts+1, published_at=? " +
                "WHERE outbox_id=? AND status='PENDING'", publishedAt, outboxId);
    }

    public void markRetry(String outboxId, long nextRetryAt) {
        jdbc.update("UPDATE sync_outbox SET attempts=attempts+1, next_retry_at=? " +
                "WHERE outbox_id=? AND status='PENDING'", nextRetryAt, outboxId);
    }
}
