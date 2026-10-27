package im.phantom.conversation.infra;

import im.phantom.infra.db.outbox.OutboxRecord;
import im.phantom.infra.db.outbox.OutboxStore;
import im.phantom.proto.v1.SyncEvent;
import im.phantom.proto.v1.SyncEventEnvelope;
import im.phantom.conversation.app.ConversationEventWriter;
import org.springframework.stereotype.Component;

/** 会话事件 outbox 写入。 */
@Component
public class OutboxConversationEventWriter implements ConversationEventWriter {

    private static final String AGGREGATE_TYPE = "conversation";

    private final OutboxStore outboxStore;

    public OutboxConversationEventWriter(OutboxStore outboxStore) {
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
