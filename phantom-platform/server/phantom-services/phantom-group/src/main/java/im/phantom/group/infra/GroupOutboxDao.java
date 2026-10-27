package im.phantom.group.infra;

import im.phantom.infra.db.outbox.OutboxRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class GroupOutboxDao {
    private final JdbcTemplate jdbc;

    public GroupOutboxDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<OutboxRecord> fetchPending(int limit, long now) {
        return jdbc.query("""
                SELECT outbox_id, aggregate_type, aggregate_id, event_type, user_id, payload, status,
                       attempts, created_at, next_retry_at, published_at
                FROM sync_outbox WHERE aggregate_type='group' AND status='PENDING' AND next_retry_at<=?
                ORDER BY created_at LIMIT ?
                """, (rs, row) -> new OutboxRecord(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), rs.getString(5), rs.getBytes(6), rs.getString(7), rs.getInt(8),
                rs.getLong(9), rs.getLong(10), (Long) rs.getObject(11)), now, limit);
    }

    public void markPublished(String id, long now) {
        jdbc.update("UPDATE sync_outbox SET status='PUBLISHED', attempts=attempts+1, published_at=? " +
                "WHERE outbox_id=? AND status='PENDING'", now, id);
    }

    public void markRetry(String id, long retryAt) {
        jdbc.update("UPDATE sync_outbox SET attempts=attempts+1, next_retry_at=? " +
                "WHERE outbox_id=? AND status='PENDING'", retryAt, id);
    }
}
