package im.phantom.fanout.app;

import im.phantom.api.sync.UserSyncEventWriter;
import im.phantom.api.route.OnlineDeliveryService;
import im.phantom.common.Envelopes;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.SyncEventEnvelope;
import im.phantom.proto.v1.SyncEventPush;
import org.springframework.stereotype.Component;

/** 将 TOPIC_SYNC_EVENT 外包装幂等写入 fanout 用户 Timeline。 */
@Component
public class SyncEventApplier {

    private final UserSyncEventWriter writer;
    private final OnlineDeliveryService delivery;

    public SyncEventApplier(UserSyncEventWriter writer, OnlineDeliveryService delivery) {
        this.writer = writer;
        this.delivery = delivery;
    }

    public long apply(byte[] body) throws Exception {
        SyncEventEnvelope envelope = SyncEventEnvelope.parseFrom(body);
        if (envelope.getUserId().isBlank() || envelope.getEventId().isBlank()
                || !envelope.hasEvent()) {
            throw new IllegalArgumentException("同步事件外包装字段缺失");
        }
        long userSeq = writer.append(envelope.getUserId(), envelope.getEventId(), envelope.getEvent());
        if (userSeq > 0) {
            var stored = envelope.getEvent().toBuilder().setUserSeq(userSeq).build();
            delivery.deliverEnvelope(envelope.getUserId(), Envelopes.push(
                    Command.COMMAND_SYNC_EVENT_PUSH, SyncEventPush.newBuilder().setEvent(stored).build()));
        }
        return userSeq;
    }
}
