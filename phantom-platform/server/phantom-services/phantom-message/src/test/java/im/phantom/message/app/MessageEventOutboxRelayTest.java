package im.phantom.message.app;

import im.phantom.common.time.TimeProvider;
import im.phantom.infra.db.outbox.OutboxRecord;
import im.phantom.infra.mq.EventPublisher;
import im.phantom.infra.mq.Topics;
import im.phantom.message.MessagePublishProperties;
import im.phantom.message.infra.MessageOutboxDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageEventOutboxRelayTest {

    private MessageOutboxDao dao;
    private EventPublisher publisher;
    private TimeProvider time;
    private MessagePublishProperties properties;
    private MessageEventOutboxRelay relay;

    @BeforeEach
    void setUp() {
        dao = mock(MessageOutboxDao.class);
        publisher = mock(EventPublisher.class);
        time = mock(TimeProvider.class);
        properties = new MessagePublishProperties();
        relay = new MessageEventOutboxRelay(dao, publisher, time, properties, null);
    }

    @Test
    void publishesPendingMessageEventAndMarksPublished() throws Exception {
        OutboxRecord record = record(0);
        when(dao.fetchPendingMessageEvents(100, 1_000L)).thenReturn(List.of(record));
        when(time.nowMillis()).thenReturn(1_000L, 1_007L);

        relay.relayOnce();

        verify(publisher).publish(Topics.MSG_EVENT, Topics.TAG_NEW_MSG, "m_1", record.payload());
        verify(dao).markPublished("me_m_1", 1_007L);
    }

    @Test
    void failureSchedulesExponentialRetry() throws Exception {
        OutboxRecord record = record(2);
        when(dao.fetchPendingMessageEvents(100, 1_000L)).thenReturn(List.of(record));
        when(time.nowMillis()).thenReturn(1_000L, 1_010L);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher).publish(any(), any(), any(), any());

        relay.relayOnce();

        verify(dao).markRetry("me_m_1", 1_410L);
    }

    @Test
    void brokerRecoveryPublishesPreviouslyFailedEventWithoutClientResend() throws Exception {
        OutboxRecord record = record(0);
        when(dao.fetchPendingMessageEvents(anyInt(), anyLong())).thenReturn(List.of(record));
        when(time.nowMillis()).thenReturn(1_000L, 1_010L, 2_000L, 2_007L);
        doThrow(new IllegalStateException("broker unavailable"))
                .doNothing()
                .when(publisher).publish(any(), any(), any(), any());

        relay.relayOnce();
        relay.relayOnce();

        verify(dao).markRetry("me_m_1", 1_110L);
        verify(dao).markPublished("me_m_1", 2_007L);
    }

    private OutboxRecord record(int attempts) {
        return new OutboxRecord("me_m_1", MessageOutboxDao.AGGREGATE_MESSAGE_EVENT, "m_1", 1,
                "u1", new byte[]{1, 2}, "PENDING", attempts, 900L, 900L, null);
    }
}
