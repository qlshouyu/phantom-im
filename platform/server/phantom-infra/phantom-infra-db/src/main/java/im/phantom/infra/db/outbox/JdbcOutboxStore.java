package im.phantom.infra.db.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** JdbcTemplate outbox 实现。 */
@Repository
public class JdbcOutboxStore implements OutboxStore {

    private final JdbcTemplate jdbc;

    public JdbcOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(OutboxRecord record) {
        jdbc.update("""
                INSERT INTO sync_outbox(outbox_id, aggregate_type, aggregate_id, event_type, user_id,
                    payload, status, attempts, created_at, next_retry_at, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.outboxId(), record.aggregateType(), record.aggregateId(), record.eventType(),
                record.userId(), record.payload(), record.status(), record.attempts(), record.createdAt(),
                record.nextRetryAt(), record.publishedAt());
    }

    @Override
    public List<OutboxRecord> fetchPending(int limit, long now) {
        return jdbc.query("""
                SELECT outbox_id, aggregate_type, aggregate_id, event_type, user_id, payload, status,
                       attempts, created_at, next_retry_at, published_at
                FROM sync_outbox
                WHERE status='PENDING' AND next_retry_at<=?
                ORDER BY created_at ASC
                LIMIT ?
                """, (rs, rowNum) -> new OutboxRecord(
                rs.getString("outbox_id"), rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                rs.getInt("event_type"), rs.getString("user_id"), rs.getBytes("payload"),
                rs.getString("status"), rs.getInt("attempts"), rs.getLong("created_at"),
                rs.getLong("next_retry_at"), (Long) rs.getObject("published_at")), now, limit);
    }

    @Override
    public void markPublished(String outboxId, long publishedAt) {
        jdbc.update("UPDATE sync_outbox SET status='PUBLISHED', attempts=attempts+1, published_at=? WHERE outbox_id=?",
                publishedAt, outboxId);
    }

    @Override
    public void markRetry(String outboxId, long nextRetryAt) {
        jdbc.update("UPDATE sync_outbox SET attempts=attempts+1, next_retry_at=? WHERE outbox_id=?",
                nextRetryAt, outboxId);
    }
}
