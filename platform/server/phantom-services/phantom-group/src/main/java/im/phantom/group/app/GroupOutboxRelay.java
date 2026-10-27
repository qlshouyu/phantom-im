package im.phantom.group.app;

import im.phantom.common.time.TimeProvider;
import im.phantom.group.infra.GroupOutboxDao;
import im.phantom.infra.db.outbox.OutboxRecord;
import im.phantom.infra.mq.EventPublisher;
import im.phantom.infra.mq.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GroupOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(GroupOutboxRelay.class);
    private final GroupOutboxDao dao;
    private final EventPublisher publisher;
    private final TimeProvider time;

    public GroupOutboxRelay(GroupOutboxDao dao, EventPublisher publisher, TimeProvider time) {
        this.dao = dao; this.publisher = publisher; this.time = time;
    }

    @Scheduled(fixedDelayString = "${phantom.group.outbox-poll-ms:1000}")
    public void relayPending() { relayOnce(); }

    public int relayOnce() {
        int published = 0;
        for (OutboxRecord record : dao.fetchPending(100, time.nowMillis())) {
            try {
                publisher.publish(Topics.SYNC_EVENT, Topics.TAG_STATE_CHANGED,
                        record.userId(), record.payload());
                dao.markPublished(record.outboxId(), time.nowMillis());
                published++;
            } catch (Exception error) {
                dao.markRetry(record.outboxId(), time.nowMillis() + 5000);
                log.warn("群事件发布失败 outboxId={}: {}", record.outboxId(), error.toString());
            }
        }
        return published;
    }
}
