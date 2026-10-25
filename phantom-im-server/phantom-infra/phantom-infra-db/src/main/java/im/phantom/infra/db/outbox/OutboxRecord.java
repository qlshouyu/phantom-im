package im.phantom.infra.db.outbox;

import java.util.Arrays;

/** 一条待发布的领域事件 outbox 记录。 */
public record OutboxRecord(
        String outboxId,
        String aggregateType,
        String aggregateId,
        int eventType,
        String userId,
        byte[] payload,
        String status,
        int attempts,
        long createdAt,
        long nextRetryAt,
        Long publishedAt
) {
    public OutboxRecord {
        payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }
}
