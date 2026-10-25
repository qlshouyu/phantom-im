package im.phantom.infra.db.idempotency;

/** 消费幂等状态存储。 */
public interface ConsumedEventStore {

    boolean claim(String consumerGroup, String eventId, long claimedAt);

    void complete(String consumerGroup, String eventId, long consumedAt);

    void release(String consumerGroup, String eventId);
}
