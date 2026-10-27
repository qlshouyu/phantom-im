package im.phantom.message.app;

import im.phantom.message.infra.ConvTimelineDao;
import im.phantom.message.infra.MessageOutboxDao;
import im.phantom.proto.v1.ConvType;
import im.phantom.proto.v1.Msg;
import im.phantom.proto.v1.MsgContent;
import im.phantom.proto.v1.MsgStatus;
import im.phantom.proto.v1.MsgType;
import im.phantom.proto.v1.TextContent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.Resource;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@SpringJUnitConfig(MessagePersistenceServiceTest.TestConfig.class)
class MessagePersistenceServiceTest {

    @Resource
    private JdbcTemplate jdbc;
    @Resource
    private MessagePersistenceService persistence;
    @Resource
    private MessageEventOutboxRelay relay;
    @Resource
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM sync_outbox");
        jdbc.update("DELETE FROM message_meta");
        jdbc.update("DELETE FROM conv_timeline");
        jdbc.update("DELETE FROM seq_watermark");
        reset(relay);
    }

    @Test
    void commitsTimelineMetaWatermarkAndOutboxTogetherThenWakesRelay() {
        persistence.persistWithOutbox(message());

        assertThat(count("conv_timeline")).isEqualTo(1);
        assertThat(count("message_meta")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT seq FROM seq_watermark WHERE scope='conv:si_u1__u2'",
                Long.class)).isEqualTo(7L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sync_outbox
                WHERE outbox_id='me_m_1' AND aggregate_type='message_event' AND aggregate_id='m_1'
                  AND status='PENDING'
                """, Integer.class)).isEqualTo(1);
        verify(relay).wakeup();
    }

    @Test
    void rollbackLeavesTimelineMetaWatermarkAndOutboxEmpty() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            persistence.persistWithOutbox(message());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("conv_timeline")).isZero();
        assertThat(count("message_meta")).isZero();
        assertThat(count("seq_watermark")).isZero();
        assertThat(count("sync_outbox")).isZero();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private Msg message() {
        return Msg.newBuilder()
                .setMsgId("m_1")
                .setClientMsgId("c_1")
                .setConvId("si_u1__u2")
                .setConvType(ConvType.CONV_TYPE_SINGLE)
                .setSenderId("u1")
                .setMsgType(MsgType.MSG_TYPE_TEXT)
                .setContent(MsgContent.newBuilder().setText(TextContent.newBuilder().setText("hello")))
                .setConvSeq(7)
                .setServerTime(1_000)
                .setStatus(MsgStatus.MSG_STATUS_SENT)
                .build();
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:message-outbox-" + System.nanoTime()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("""
                    CREATE TABLE conv_timeline(conv_id VARCHAR(128), conv_seq BIGINT, msg_id VARCHAR(64),
                      client_msg_id VARCHAR(64), sender_id VARCHAR(64), conv_type INT, msg_type INT,
                      msg_bytes BLOB, server_time BIGINT, recalled BOOLEAN,
                      PRIMARY KEY(conv_id, conv_seq), UNIQUE(sender_id, client_msg_id), UNIQUE(msg_id))
                    """);
            jdbc.execute("""
                    CREATE TABLE message_meta(msg_id VARCHAR(64) PRIMARY KEY, status INT, restricted BOOLEAN,
                      reply_to_msg_id VARCHAR(64), mentions_json VARCHAR(1000), burn_ttl_seconds INT,
                      updated_at BIGINT)
                    """);
            jdbc.execute("CREATE TABLE seq_watermark(scope VARCHAR(160) PRIMARY KEY, seq BIGINT)");
            jdbc.execute("""
                    CREATE TABLE sync_outbox(outbox_id VARCHAR(64) PRIMARY KEY, aggregate_type VARCHAR(64),
                      aggregate_id VARCHAR(128), event_type INT, user_id VARCHAR(64), payload BLOB,
                      status VARCHAR(24), attempts INT, created_at BIGINT, next_retry_at BIGINT,
                      published_at BIGINT)
                    """);
            return jdbc;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        ConvTimelineDao convTimelineDao(JdbcTemplate jdbc) {
            return new ConvTimelineDao(jdbc);
        }

        @Bean
        MessageOutboxDao messageOutboxDao(JdbcTemplate jdbc) {
            return new MessageOutboxDao(jdbc);
        }

        @Bean
        MessageEventOutboxRelay messageEventOutboxRelay() {
            return mock(MessageEventOutboxRelay.class);
        }

        @Bean
        MessagePersistenceService messagePersistenceService(ConvTimelineDao timelineDao,
                                                            MessageOutboxDao outboxDao,
                                                            MessageEventOutboxRelay relay) {
            return new MessagePersistenceService(timelineDao, outboxDao, relay);
        }
    }
}
