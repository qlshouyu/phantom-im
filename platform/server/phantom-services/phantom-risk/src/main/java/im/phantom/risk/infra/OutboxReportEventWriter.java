package im.phantom.risk.infra;

import im.phantom.infra.db.outbox.OutboxRecord;
import im.phantom.infra.db.outbox.OutboxStore;
import im.phantom.proto.v1.SyncEvent;
import im.phantom.proto.v1.SyncEventEnvelope;
import im.phantom.risk.app.ReportEventWriter;
import org.springframework.stereotype.Component;

/** 举报结果 outbox 写入器。 */
@Component
public class OutboxReportEventWriter implements ReportEventWriter {

    private static final String AGGREGATE_TYPE = "risk-report";
    private final OutboxStore outboxStore;

    public OutboxReportEventWriter(OutboxStore outboxStore) {
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
