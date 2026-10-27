package im.phantom.message.app;

import im.phantom.common.time.TimeProvider;
import im.phantom.infra.db.outbox.OutboxRecord;
import im.phantom.infra.mq.EventPublisher;
import im.phantom.infra.mq.Topics;
import im.phantom.infra.observability.PlatformMetrics;
import im.phantom.message.MessagePublishProperties;
import im.phantom.message.infra.MessageOutboxDao;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** 新消息事件 Outbox relay：提交后立即唤醒，100ms 轮询兜底，至少一次发布。 */
@Component
public class MessageEventOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(MessageEventOutboxRelay.class);

    private final MessageOutboxDao dao;
    private final EventPublisher publisher;
    private final TimeProvider time;
    private final MessagePublishProperties properties;
    private final PlatformMetrics metrics;
    private final ReentrantLock drainLock = new ReentrantLock();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean wakeRequested = new AtomicBoolean();

    public MessageEventOutboxRelay(MessageOutboxDao dao, EventPublisher publisher, TimeProvider time,
                                   MessagePublishProperties properties, PlatformMetrics metrics) {
        this.dao = dao;
        this.publisher = publisher;
        this.time = time;
        this.properties = properties;
        this.metrics = metrics;
        log.info("消息 Outbox relay 配置 pollMs={} batchSize={} retryBaseMs={} retryMaxMs={}",
                properties.getOutboxPollMs(), properties.getOutboxBatchSize(),
                properties.getOutboxRetryBaseMs(), properties.getOutboxRetryMaxMs());
    }

    public void wakeup() {
        log.debug("收到 Outbox relay 唤醒信号");
        wakeRequested.set(true);
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(this::drainWakeups);
    }

    private void drainWakeups() {
        try {
            do {
                wakeRequested.set(false);
                relayOnce();
            } while (wakeRequested.get());
        } finally {
            draining.set(false);
            if (wakeRequested.get()) {
                wakeup();
            }
        }
    }

    @Scheduled(fixedDelayString = "${phantom.message.outbox-poll-ms:100}")
    public void relayPending() {
        relayOnce();
    }

    public int relayOnce() {
        if (!drainLock.tryLock()) {
            return 0;
        }
        try {
            int batchSize = Math.max(1, properties.getOutboxBatchSize());
            int published = 0;
            var pending = dao.fetchPendingMessageEvents(batchSize, time.nowMillis());
            if (!pending.isEmpty()) {
                log.debug("Outbox relay 拉取待发布记录 count={}", pending.size());
            }
            for (OutboxRecord record : pending) {
                Timer.Sample stage = metrics == null ? null : metrics.startMessageStage();
                try {
                    publisher.publish(Topics.MSG_EVENT, Topics.TAG_NEW_MSG,
                            record.aggregateId(), record.payload());
                    long publishedAt = time.nowMillis();
                    dao.markPublished(record.outboxId(), publishedAt);
                    if (stage != null) {
                        metrics.recordMessageStage(stage, "outbox_publish", "success");
                        metrics.recordMessageStage("outbox_delay", "success",
                                Math.max(0, publishedAt - record.createdAt()) * 1_000_000L);
                    }
                    published++;
                    log.debug("新消息 Outbox 发布成功 outboxId={} msgId={} attempts={}",
                            record.outboxId(), record.aggregateId(), record.attempts());
                } catch (Exception error) {
                    long retryAt = time.nowMillis() + retryDelayMs(record.attempts());
                    dao.markRetry(record.outboxId(), retryAt);
                    if (stage != null) {
                        metrics.recordMessageStage(stage, "outbox_publish", "failure");
                        metrics.recordOutboxRetry();
                    }
                    log.warn("新消息 Outbox 发布失败 outboxId={} msgId={} attempts={}: {}",
                            record.outboxId(), record.aggregateId(), record.attempts(), error.toString());
                }
            }
            return published;
        } finally {
            drainLock.unlock();
        }
    }

    private long retryDelayMs(int attempts) {
        long base = Math.max(1, properties.getOutboxRetryBaseMs());
        long max = Math.max(base, properties.getOutboxRetryMaxMs());
        int shift = Math.min(Math.max(0, attempts), 20);
        return Math.min(max, base << shift);
    }
}
