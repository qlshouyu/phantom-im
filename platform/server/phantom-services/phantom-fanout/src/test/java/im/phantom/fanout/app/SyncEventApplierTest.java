package im.phantom.fanout.app;

import im.phantom.fanout.infra.UserTimelineWriteDao;
import im.phantom.api.route.OnlineDeliveryService;
import im.phantom.infra.redis.SeqAllocator;
import im.phantom.proto.v1.RelationEvent;
import im.phantom.proto.v1.SyncEvent;
import im.phantom.proto.v1.SyncEventEnvelope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SyncEventApplierTest {

    @Test
    void appliesEnvelopeByUserSeqAndDropsDuplicateEventId() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:fanout-sync-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE user_timeline(user_id VARCHAR(64), user_seq BIGINT, event_type INT,
                  msg_id VARCHAR(64), event_bytes BLOB, event_time BIGINT, PRIMARY KEY(user_id,user_seq),
                  UNIQUE(user_id,msg_id))
                """);
        jdbc.execute("CREATE TABLE seq_watermark(scope VARCHAR(160) PRIMARY KEY, seq BIGINT)");
        FanoutUserSyncEventWriter writer = new FanoutUserSyncEventWriter(
                new UserTimelineWriteDao(jdbc), new StubSeqAllocator());
        RecordingDelivery delivery = new RecordingDelivery();
        SyncEventApplier applier = new SyncEventApplier(writer, delivery);
        SyncEvent event = SyncEvent.newBuilder().setEventTime(10L).setRelation(RelationEvent.newBuilder()
                .setType(RelationEvent.RelationEventType.RELATION_EVENT_TYPE_APPLY_ACCEPTED)
                .setPeerId("u2")).build();
        byte[] envelope = SyncEventEnvelope.newBuilder()
                .setUserId("u1").setEventId("evt-1").setEvent(event).build().toByteArray();

        assertThat(applier.apply(envelope)).isEqualTo(1L);
        assertThat(applier.apply(envelope)).isZero();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_timeline WHERE user_id='u1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_type FROM user_timeline WHERE user_id='u1'", Integer.class))
                .isEqualTo(16);
        assertThat(delivery.envelopes).singleElement().satisfies(pushed -> {
            assertThat(pushed.getCmd()).isEqualTo(im.phantom.proto.v1.Command.COMMAND_SYNC_EVENT_PUSH);
            assertThat(pushed.getPayload()).isNotEmpty();
        });
    }

    private static final class StubSeqAllocator extends SeqAllocator {
        private final AtomicLong seq = new AtomicLong();

        private StubSeqAllocator() {
            super(null, null);
        }

        @Override
        public long nextUserSeq(String userId) {
            return seq.incrementAndGet();
        }
    }

    private static final class RecordingDelivery implements OnlineDeliveryService {
        private final java.util.List<im.phantom.proto.v1.Envelope> envelopes = new java.util.ArrayList<>();

        @Override
        public int deliver(String receiverId, im.phantom.proto.v1.MsgPush push) {
            return 0;
        }

        @Override
        public int deliverEnvelope(String receiverId, im.phantom.proto.v1.Envelope envelope) {
            envelopes.add(envelope);
            return 1;
        }
    }
}
