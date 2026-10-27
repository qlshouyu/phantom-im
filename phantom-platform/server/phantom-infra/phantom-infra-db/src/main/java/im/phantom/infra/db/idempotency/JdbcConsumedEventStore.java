package im.phantom.infra.db.idempotency;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 基于唯一键的消费幂等存储。 */
@Repository
public class JdbcConsumedEventStore implements ConsumedEventStore {

    private final JdbcTemplate jdbc;

    public JdbcConsumedEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(String consumerGroup, String eventId, long claimedAt) {
        try {
            jdbc.update("""
                    INSERT INTO consumed_event(consumer_group, event_id, status, claimed_at, consumed_at)
                    VALUES (?, ?, 'PROCESSING', ?, NULL)
                    """, consumerGroup, eventId, claimedAt);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override
    public void complete(String consumerGroup, String eventId, long consumedAt) {
        jdbc.update("""
                UPDATE consumed_event SET status='DONE', consumed_at=?
                WHERE consumer_group=? AND event_id=?
                """, consumedAt, consumerGroup, eventId);
    }

    @Override
    public void release(String consumerGroup, String eventId) {
        jdbc.update("DELETE FROM consumed_event WHERE consumer_group=? AND event_id=? AND status='PROCESSING'",
                consumerGroup, eventId);
    }
}
