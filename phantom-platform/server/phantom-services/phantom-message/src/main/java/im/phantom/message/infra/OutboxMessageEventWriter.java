package im.phantom.message.infra;

import im.phantom.infra.db.outbox.OutboxRecord;
import im.phantom.infra.db.outbox.OutboxStore;
import im.phantom.message.app.MessageEventWriter;
import im.phantom.proto.v1.SyncEvent;
import im.phantom.proto.v1.SyncEventEnvelope;
import org.springframework.stereotype.Component;

/** 消息状态/撤回事件走 outbox，由 fanout 统一分配 user_seq。 */
@Component
public class OutboxMessageEventWriter implements MessageEventWriter {

    public static final String AGGREGATE_TYPE = "message";

    private final OutboxStore outboxStore;

    public OutboxMessageEventWriter(OutboxStore outboxStore) {
        this.outboxStore = outboxStore;
    }

    @Override
    public void append(String userId, String eventId, SyncEvent event) {
        long now = event.getEventTime() == 0 ? System.currentTimeMillis() : event.getEventTime();
        SyncEventEnvelope envelope = SyncEventEnvelope.newBuilder()
                .setUserId(userId).setEventId(eventId).setEvent(event).build();
        outboxStore.append(new OutboxRecord(eventId, AGGREGATE_TYPE, eventId,
                event.getEventCase().getNumber(), userId, envelope.toByteArray(), "PENDING", 0,
                now, now, null));
    }
}
