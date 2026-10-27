package im.phantom.conversation.app;

import im.phantom.conversation.infra.ConversationOutboxDao;
import im.phantom.conversation.infra.OutboxConversationEventWriter;
import im.phantom.conversation.support.ConversationTestSchema;
import im.phantom.infra.db.outbox.JdbcOutboxStore;
import im.phantom.infra.mq.EventPublisher;
import im.phantom.proto.v1.DraftEvent;
import im.phantom.proto.v1.SyncEvent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSyncOutboxRelayTest {

    @Test
    void publishesConversationEventAndMarksItPublished() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:conversation-relay-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ConversationTestSchema.create(jdbc);
        OutboxConversationEventWriter writer = new OutboxConversationEventWriter(new JdbcOutboxStore(jdbc));
        writer.append("u1", "evt-d1", SyncEvent.newBuilder().setEventTime(10L)
                .setDraft(DraftEvent.newBuilder().setConvId("c1").setDraft("d").setEditedAt(9L)).build());
        RecordingPublisher publisher = new RecordingPublisher();
        ConversationSyncOutboxRelay relay = new ConversationSyncOutboxRelay(
                new ConversationOutboxDao(jdbc), publisher, () -> 100L);

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(publisher.keys).containsExactly("u1");
        assertThat(jdbc.queryForObject("SELECT status FROM sync_outbox WHERE outbox_id='evt-d1'", String.class))
                .isEqualTo("PUBLISHED");
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final List<String> keys = new ArrayList<>();

        @Override
        public void publish(String topic, String tag, String key, byte[] body) {
            keys.add(key);
        }
    }
}
