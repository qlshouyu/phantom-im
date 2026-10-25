package im.phantom.infra.db;

import im.phantom.common.time.TimeProvider;
import im.phantom.infra.db.idempotency.IdempotentEventHandler;
import im.phantom.infra.db.idempotency.JdbcConsumedEventStore;
import im.phantom.infra.db.outbox.JdbcOutboxStore;
import im.phantom.infra.db.outbox.OutboxRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InfraPersistenceTest {

    private JdbcTemplate jdbc;
    private final TimeProvider time = () -> 1_000L;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:infra-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE sync_outbox(
                  outbox_id VARCHAR(64) PRIMARY KEY,
                  aggregate_type VARCHAR(64), aggregate_id VARCHAR(128), event_type INT,
                  user_id VARCHAR(64), payload BLOB, status VARCHAR(24), attempts INT,
                  created_at BIGINT, next_retry_at BIGINT, published_at BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE consumed_event(
                  consumer_group VARCHAR(128), event_id VARCHAR(128), status VARCHAR(24),
                  claimed_at BIGINT, consumed_at BIGINT, PRIMARY KEY(consumer_group, event_id))
                """);
    }

    @Test
    void outboxCanBeAppendedFetchedAndPublished() {
        JdbcOutboxStore store = new JdbcOutboxStore(jdbc);
        store.append(new OutboxRecord("o1", "message", "m1", 20, "u1",
                new byte[]{1, 2}, "PENDING", 0, 10L, 10L, null));

        assertThat(store.fetchPending(10, 20L)).singleElement()
                .satisfies(record -> assertThat(record.payload()).containsExactly(1, 2));

        store.markPublished("o1", 30L);
        assertThat(store.fetchPending(10, 40L)).isEmpty();
    }

    @Test
    void duplicateEventIsHandledOnlyOnce() {
        IdempotentEventHandler handler = new IdempotentEventHandler(new JdbcConsumedEventStore(jdbc), time);
        AtomicInteger invocations = new AtomicInteger();

        assertThat(handler.handleOnce("search", "e1", invocations::incrementAndGet)).isTrue();
        assertThat(handler.handleOnce("search", "e1", invocations::incrementAndGet)).isFalse();
        assertThat(invocations).hasValue(1);
    }

    @Test
    void failedHandlerReleasesClaimForRetry() {
        IdempotentEventHandler handler = new IdempotentEventHandler(new JdbcConsumedEventStore(jdbc), time);

        assertThatThrownBy(() -> handler.handleOnce("fanout", "e2", () -> {
            throw new IllegalStateException("temporary");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(handler.handleOnce("fanout", "e2", () -> { })).isTrue();
    }
}
