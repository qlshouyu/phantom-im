package im.phantom.conversation.infra;

import im.phantom.conversation.support.ConversationTestSchema;
import im.phantom.infra.db.outbox.JdbcOutboxStore;
import im.phantom.proto.v1.ConvStateEvent;
import im.phantom.proto.v1.SyncEvent;
import im.phantom.proto.v1.SyncEventEnvelope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxConversationEventWriterTest {

    @Test
    void writesConversationEnvelopeToOutbox() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:conversation-outbox-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ConversationTestSchema.create(jdbc);
        OutboxConversationEventWriter writer = new OutboxConversationEventWriter(new JdbcOutboxStore(jdbc));

        writer.append("u1", "evt-c1", SyncEvent.newBuilder().setEventTime(10L)
                .setConvState(ConvStateEvent.newBuilder().setConvId("c1")
                        .setField(ConvStateEvent.ConvStateField.CONV_STATE_FIELD_PINNED).setBoolValue(true))
                .build());

        byte[] payload = jdbc.queryForObject("SELECT payload FROM sync_outbox WHERE outbox_id='evt-c1'", byte[].class);
        SyncEventEnvelope envelope = SyncEventEnvelope.parseFrom(payload);
        assertThat(envelope.getUserId()).isEqualTo("u1");
        assertThat(envelope.getEvent().getConvState().getConvId()).isEqualTo("c1");
        assertThat(jdbc.queryForObject("SELECT aggregate_type FROM sync_outbox", String.class))
                .isEqualTo("conversation");
    }
}
