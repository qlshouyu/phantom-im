package im.phantom.conversation.infra;

import im.phantom.infra.db.outbox.OutboxRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 会话 outbox 发布批次。 */
@Repository
public class ConversationOutboxDao {

    private final JdbcTemplate jdbc;

    public ConversationOutboxDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OutboxRecord> fetchPending(int limit, long now) {
        return jdbc.query("""
                SELECT outbox_id, aggregate_type, aggregate_id, event_type, user_id, payload, status,
                       attempts, created_at, next_retry_at, published_at
                FROM sync_outbox
                WHERE aggregate_type='conversation' AND status='PENDING' AND next_retry_at<=?
                ORDER BY created_at ASC LIMIT ?
                """, (rs, rowNum) -> new OutboxRecord(
                rs.getString("outbox_id"), rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                rs.getInt("event_type"), rs.getString("user_id"), rs.getBytes("payload"),
                rs.getString("status"), rs.getInt("attempts"), rs.getLong("created_at"),
                rs.getLong("next_retry_at"), (Long) rs.getObject("published_at")), now, limit);
    }

    public void markPublished(String outboxId, long publishedAt) {
        jdbc.update("""
                UPDATE sync_outbox SET status='PUBLISHED', attempts=attempts+1, published_at=?
                WHERE outbox_id=? AND status='PENDING'
                """, publishedAt, outboxId);
    }

    public void markRetry(String outboxId, long nextRetryAt) {
        jdbc.update("""
                UPDATE sync_outbox SET attempts=attempts+1, next_retry_at=?
                WHERE outbox_id=? AND status='PENDING'
                """, nextRetryAt, outboxId);
    }
}
