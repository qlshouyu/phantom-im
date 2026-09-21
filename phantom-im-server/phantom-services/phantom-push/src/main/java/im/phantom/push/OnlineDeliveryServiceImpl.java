package im.phantom.push;

import im.phantom.api.route.GatewayDeliveryService;
import im.phantom.api.route.OnlineDeliveryService;
import im.phantom.api.route.RouteEntry;
import im.phantom.api.route.RouteTableService;
import im.phantom.common.Envelopes;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.Envelope;
import im.phantom.proto.v1.MsgPush;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 在线投递本地实现（message-fanout-delivery spec）：查路由表 → 对每个在线连接下发 MsgPush。
 * 无在线连接返回 0（留待 SyncReq 补齐）。
 */
@Service
public class OnlineDeliveryServiceImpl implements OnlineDeliveryService {

    private final RouteTableService routeTable;
    private final GatewayDeliveryService gateway;

    public OnlineDeliveryServiceImpl(RouteTableService routeTable, GatewayDeliveryService gateway) {
        this.routeTable = routeTable;
        this.gateway = gateway;
    }

    @Override
    public int deliver(String receiverId, MsgPush push) {
        List<RouteEntry> entries = routeTable.query(receiverId);
        if (entries.isEmpty()) {
            return 0;
        }
        Envelope env = Envelopes.push(Command.COMMAND_MSG_PUSH, push);
        int sent = 0;
        for (RouteEntry e : entries) {
            if (gateway.send(e.gatewayId(), e.connId(), env)) {
                sent++;
            }
        }
        return sent;
    }
}
