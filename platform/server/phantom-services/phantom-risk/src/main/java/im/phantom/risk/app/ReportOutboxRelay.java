package im.phantom.risk.app;

import im.phantom.common.time.TimeProvider;
import im.phantom.infra.db.outbox.OutboxRecord;
import im.phantom.infra.db.outbox.OutboxStore;
import im.phantom.infra.mq.EventPublisher;
import im.phantom.infra.mq.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 举报结果 outbox 发布器。 */
@Component
public class ReportOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(ReportOutboxRelay.class);
    private final OutboxStore outboxStore;
    private final EventPublisher publisher;
    private final TimeProvider time;

    public ReportOutboxRelay(OutboxStore outboxStore, EventPublisher publisher, TimeProvider time) {
        this.outboxStore = outboxStore;
        this.publisher = publisher;
        this.time = time;
    }

    @Scheduled(fixedDelayString = "${phantom.risk.outbox-poll-ms:1000}")
    public void relayPending() {
        relayOnce();
    }

    public int relayOnce() {
        int published = 0;
        for (OutboxRecord record : outboxStore.fetchPending(100, time.nowMillis())) {
            try {
                publisher.publish(Topics.SYNC_EVENT, Topics.TAG_STATE_CHANGED,
                        record.userId(), record.payload());
                outboxStore.markPublished(record.outboxId(), time.nowMillis());
                published++;
            } catch (Exception error) {
                outboxStore.markRetry(record.outboxId(), time.nowMillis() + 5000);
                log.warn("举报事件发布失败 outboxId={}: {}", record.outboxId(), error.toString());
            }
        }
        return published;
    }
}
