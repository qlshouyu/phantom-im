package im.phantom.relation.app;

import im.phantom.infra.db.outbox.JdbcOutboxStore;
import im.phantom.infra.mq.EventPublisher;
import im.phantom.proto.v1.RelationEvent;
import im.phantom.proto.v1.SyncEvent;
import im.phantom.relation.infra.OutboxRelationEventWriter;
import im.phantom.relation.infra.RelationOutboxDao;
import im.phantom.relation.support.RelationTestSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RelationSyncOutboxRelayTest {

    private JdbcTemplate jdbc;
    private OutboxRelationEventWriter writer;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:relation-relay-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        RelationTestSchema.create(jdbc);
        writer = new OutboxRelationEventWriter(new JdbcOutboxStore(jdbc));
    }

    @Test
    void publishesPendingRelationEventsAndMarksThemPublished() {
        writer.append("u1", "evt-1", event());
        RecordingPublisher publisher = new RecordingPublisher(false);
        RelationSyncOutboxRelay relay = new RelationSyncOutboxRelay(
                new RelationOutboxDao(jdbc), publisher, () -> 100L);

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(publisher.keys).containsExactly("u1");
        assertThat(jdbc.queryForObject("SELECT status FROM sync_outbox WHERE outbox_id='evt-1'", String.class))
                .isEqualTo("PUBLISHED");
    }

    @Test
    void failedPublishRemainsPendingForRetry() {
        writer.append("u1", "evt-1", event());
        RelationSyncOutboxRelay relay = new RelationSyncOutboxRelay(
                new RelationOutboxDao(jdbc), new RecordingPublisher(true), () -> 100L);

        assertThat(relay.relayOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM sync_outbox WHERE outbox_id='evt-1'", String.class))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM sync_outbox WHERE outbox_id='evt-1'", Integer.class))
                .isEqualTo(1);
    }

    private SyncEvent event() {
        return SyncEvent.newBuilder().setEventTime(10L).setRelation(RelationEvent.newBuilder()
                .setType(RelationEvent.RelationEventType.RELATION_EVENT_TYPE_APPLY_SENT)
                .setPeerId("u2")).build();
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final boolean fail;
        private final List<String> keys = new ArrayList<>();

        private RecordingPublisher(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void publish(String topic, String tag, String key, byte[] body) throws Exception {
            keys.add(key);
            if (fail) {
                throw new IllegalStateException("mq unavailable");
            }
        }
    }
}
