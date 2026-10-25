package im.phantom.relation.infra;

import im.phantom.infra.db.outbox.OutboxRecord;
import im.phantom.infra.db.outbox.OutboxStore;
import im.phantom.proto.v1.SyncEvent;
import im.phantom.proto.v1.SyncEventEnvelope;
import im.phantom.relation.app.RelationEventWriter;
import org.springframework.stereotype.Component;

/** 将关系事件写入 sync_outbox，由 fanout 消费后统一分配 user_seq。 */
@Component
public class OutboxRelationEventWriter implements RelationEventWriter {

    private static final String AGGREGATE_TYPE = "relation";
    private static final String PENDING = "PENDING";

    private final OutboxStore outboxStore;

    public OutboxRelationEventWriter(OutboxStore outboxStore) {
        this.outboxStore = outboxStore;
    }

    @Override
    public void append(String userId, String eventId, SyncEvent event) {
        long now = event.getEventTime() == 0 ? System.currentTimeMillis() : event.getEventTime();
        SyncEventEnvelope envelope = SyncEventEnvelope.newBuilder()
                .setUserId(userId)
                .setEventId(eventId)
                .setEvent(event)
                .build();
        outboxStore.append(new OutboxRecord(
                eventId, AGGREGATE_TYPE, eventId, event.getEventCase().getNumber(), userId,
                envelope.toByteArray(), PENDING, 0, now, now, null));
    }
}
