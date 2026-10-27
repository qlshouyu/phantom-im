package im.phantom.fanout.app;

import im.phantom.fanout.infra.UserTimelineWriteDao;
import im.phantom.infra.redis.SeqAllocator;
import im.phantom.proto.v1.ProfileEvent;
import im.phantom.proto.v1.SyncEvent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class FanoutUserSyncEventWriterTest {

    @Test
    void writesProfileEventAndIsIdempotentByEventId() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:fanout-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE user_timeline(user_id VARCHAR(64), user_seq BIGINT, event_type INT,
                  msg_id VARCHAR(64), event_bytes BLOB, event_time BIGINT, PRIMARY KEY(user_id,user_seq),
                  UNIQUE(user_id,msg_id))
                """);
        jdbc.execute("CREATE TABLE seq_watermark(scope VARCHAR(160) PRIMARY KEY, seq BIGINT)");
        FanoutUserSyncEventWriter writer = new FanoutUserSyncEventWriter(
                new UserTimelineWriteDao(jdbc), new StubSeqAllocator());
        SyncEvent event = SyncEvent.newBuilder().setEventTime(10L)
                .setProfile(ProfileEvent.newBuilder()
                        .setType(ProfileEvent.ProfileEventType.PROFILE_EVENT_TYPE_MY_PROFILE)
                        .setTargetId("u1").putChanged("nickname", "新昵称"))
                .build();

        assertThat(writer.append("u1", "e1", event)).isEqualTo(1L);
        assertThat(writer.append("u1", "e1", event)).isZero();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_timeline WHERE user_id='u1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_type FROM user_timeline WHERE user_id='u1'", Integer.class))
                .isEqualTo(18);
    }

    private static final class StubSeqAllocator extends SeqAllocator {
        private StubSeqAllocator() {
            super(null, null);
        }

        @Override
        public long nextUserSeq(String userId) {
            return 1L;
        }
    }
}
