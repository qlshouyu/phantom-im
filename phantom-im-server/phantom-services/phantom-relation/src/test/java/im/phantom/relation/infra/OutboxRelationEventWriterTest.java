package im.phantom.relation.infra;

import im.phantom.infra.db.outbox.JdbcOutboxStore;
import im.phantom.proto.v1.RelationEvent;
import im.phantom.proto.v1.SyncEvent;
import im.phantom.proto.v1.SyncEventEnvelope;
import im.phantom.relation.support.RelationTestSchema;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelationEventWriterTest {

    @Test
    void writesRoutableEnvelopeIntoOutbox() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:relation-outbox-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        RelationTestSchema.create(jdbc);
        OutboxRelationEventWriter writer = new OutboxRelationEventWriter(new JdbcOutboxStore(jdbc));
        SyncEvent event = SyncEvent.newBuilder().setEventTime(20L).setRelation(RelationEvent.newBuilder()
                .setType(RelationEvent.RelationEventType.RELATION_EVENT_TYPE_BLACKLIST_ADDED)
                .setPeerId("u2")).build();

        writer.append("u1", "evt-1", event);

        byte[] payload = jdbc.queryForObject("SELECT payload FROM sync_outbox WHERE outbox_id='evt-1'", byte[].class);
        SyncEventEnvelope envelope = SyncEventEnvelope.parseFrom(payload);
        assertThat(envelope.getUserId()).isEqualTo("u1");
        assertThat(envelope.getEventId()).isEqualTo("evt-1");
        assertThat(envelope.getEvent().getRelation().getType())
                .isEqualTo(RelationEvent.RelationEventType.RELATION_EVENT_TYPE_BLACKLIST_ADDED);
        assertThat(jdbc.queryForObject("SELECT aggregate_type FROM sync_outbox", String.class)).isEqualTo("relation");
    }
}
