package im.phantom.message.app;

import im.phantom.common.time.TimeProvider;
import im.phantom.infra.db.outbox.OutboxRecord;
import im.phantom.infra.mq.EventPublisher;
import im.phantom.infra.mq.Topics;
import im.phantom.infra.observability.PlatformMetrics;
import im.phantom.message.infra.MessageOutboxDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Timer;

/** 消息状态、撤回和已读状态事件 outbox 发布器。 */
@Component
public class MessageSyncOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(MessageSyncOutboxRelay.class);
    private static final int BATCH_SIZE = 100;
    private static final long RETRY_DELAY_MS = 5_000L;

    private final MessageOutboxDao dao;
    private final EventPublisher publisher;
    private final TimeProvider time;
    private final PlatformMetrics metrics;

    public MessageSyncOutboxRelay(MessageOutboxDao dao, EventPublisher publisher, TimeProvider time,
                                  PlatformMetrics metrics) {
        this.dao = dao;
        this.publisher = publisher;
        this.time = time;
        this.metrics = metrics;
        if (metrics != null) {
            metrics.registerOutboxPending(dao::countPending);
            metrics.registerOutboxOldestAge(() -> dao.oldestPendingAge(time.nowMillis()));
        }
    }

    @Scheduled(fixedDelayString = "${phantom.message.outbox-poll-ms:1000}")
    public void relayPending() {
        relayOnce();
    }

    public int relayOnce() {
        int published = 0;
        for (OutboxRecord record : dao.fetchPending(BATCH_SIZE, time.nowMillis())) {
            Timer.Sample stage = metrics == null ? null : metrics.startMessageStage();
            try {
                publisher.publish(Topics.SYNC_EVENT, Topics.TAG_STATE_CHANGED,
                        record.userId(), record.payload());
                dao.markPublished(record.outboxId(), time.nowMillis());
                if (stage != null) {
                    metrics.recordMessageStage(stage, "outbox_publish", "success");
                    metrics.recordMessageStage("outbox_delay", "success",
                            Math.max(0, time.nowMillis() - record.createdAt()) * 1_000_000L);
                }
                published++;
            } catch (Exception error) {
                dao.markRetry(record.outboxId(), time.nowMillis() + RETRY_DELAY_MS);
                if (stage != null) {
                    metrics.recordMessageStage(stage, "outbox_publish", "failure");
                    metrics.recordOutboxRetry();
                }
                log.warn("消息同步事件发布失败 outboxId={}: {}", record.outboxId(), error.toString());
            }
        }
        return published;
    }
}
