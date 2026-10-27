package im.phantom.conversation.app;

import im.phantom.common.time.TimeProvider;
import im.phantom.conversation.infra.ConversationOutboxDao;
import im.phantom.infra.db.outbox.OutboxRecord;
import im.phantom.infra.mq.EventPublisher;
import im.phantom.infra.mq.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 会话 outbox 发布器。 */
@Component
public class ConversationSyncOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(ConversationSyncOutboxRelay.class);
    private static final int BATCH_SIZE = 100;
    private static final long RETRY_DELAY_MS = 5_000L;

    private final ConversationOutboxDao dao;
    private final EventPublisher publisher;
    private final TimeProvider time;

    public ConversationSyncOutboxRelay(ConversationOutboxDao dao, EventPublisher publisher, TimeProvider time) {
        this.dao = dao;
        this.publisher = publisher;
        this.time = time;
    }

    @Scheduled(fixedDelayString = "${phantom.conversation.outbox-poll-ms:1000}")
    public void relayPending() {
        relayOnce();
    }

    public int relayOnce() {
        int published = 0;
        for (OutboxRecord record : dao.fetchPending(BATCH_SIZE, time.nowMillis())) {
            try {
                publisher.publish(Topics.SYNC_EVENT, Topics.TAG_STATE_CHANGED,
                        record.userId(), record.payload());
                dao.markPublished(record.outboxId(), time.nowMillis());
                published++;
            } catch (Exception error) {
                dao.markRetry(record.outboxId(), time.nowMillis() + RETRY_DELAY_MS);
                log.warn("会话事件发布失败 outboxId={}: {}", record.outboxId(), error.toString());
            }
        }
        return published;
    }
}
