package im.phantom.push;

import im.phantom.api.route.GatewayDeliveryService;
import im.phantom.api.route.OnlineDeliveryService;
import im.phantom.api.route.RouteEntry;
import im.phantom.api.route.RouteTableService;
import im.phantom.common.Envelopes;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.Envelope;
import im.phantom.proto.v1.MsgPush;
import im.phantom.infra.redis.AckCursorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 在线投递本地实现（message-fanout-delivery spec）：查路由表 → 对每个在线连接下发 MsgPush。
 * 无在线连接返回 0（留待 SyncReq 补齐）。
 */
@Service
public class OnlineDeliveryServiceImpl implements OnlineDeliveryService {

    private final RouteTableService routeTable;
    private final GatewayDeliveryService gateway;
    private final ObjectProvider<AckCursorStore> ackCursor;
    private final ScheduledExecutorService scheduler;

    public OnlineDeliveryServiceImpl(RouteTableService routeTable, GatewayDeliveryService gateway,
                                     ObjectProvider<AckCursorStore> ackCursor,
                                     @Qualifier("pushScheduler") ScheduledExecutorService pushScheduler) {
        this.routeTable = routeTable;
        this.gateway = gateway;
        this.ackCursor = ackCursor;
        this.scheduler = pushScheduler;
    }

    @Override
    public int deliver(String receiverId, MsgPush push) {
        int sent = deliverEnvelope(receiverId, Envelopes.push(Command.COMMAND_MSG_PUSH, push));
        if (sent > 0) {
            scheduler.schedule(() -> retryUnacked(receiverId, push),
                    5, TimeUnit.SECONDS);
        }
        return sent;
    }

    @Override
    public int deliverEnvelope(String receiverId, Envelope envelope) {
        List<RouteEntry> entries = routeTable.query(receiverId);
        if (entries.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (RouteEntry e : entries) {
            if (gateway.send(e.gatewayId(), e.connId(), envelope)) {
                sent++;
            }
        }
        return sent;
    }

    private void retryUnacked(String receiverId, MsgPush push) {
        AckCursorStore cursors = ackCursor.getIfAvailable();
        if (cursors == null) {
            return;
        }
        Envelope envelope = Envelopes.push(Command.COMMAND_MSG_PUSH, push);
        for (RouteEntry entry : routeTable.query(receiverId)) {
            if (cursors.get(receiverId, entry.deviceId()) < push.getUserSeq()) {
                gateway.send(entry.gatewayId(), entry.connId(), envelope);
            }
        }
    }
}
