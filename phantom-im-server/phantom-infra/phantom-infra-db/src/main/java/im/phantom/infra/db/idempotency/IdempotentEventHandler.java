package im.phantom.infra.db.idempotency;

import im.phantom.common.time.TimeProvider;

/** 消费幂等模板：先抢占事件，成功完成后标记 DONE，失败释放以便重试。 */
public class IdempotentEventHandler {

    private final ConsumedEventStore store;
    private final TimeProvider timeProvider;

    public IdempotentEventHandler(ConsumedEventStore store, TimeProvider timeProvider) {
        this.store = store;
        this.timeProvider = timeProvider;
    }

    /**
     * @return true 表示首次处理成功；false 表示事件已处理或正在处理。
     */
    public boolean handleOnce(String consumerGroup, String eventId, Runnable handler) {
        long now = timeProvider.nowMillis();
        if (!store.claim(consumerGroup, eventId, now)) {
            return false;
        }
        try {
            handler.run();
            store.complete(consumerGroup, eventId, timeProvider.nowMillis());
            return true;
        } catch (RuntimeException e) {
            store.release(consumerGroup, eventId);
            throw e;
        }
    }
}
