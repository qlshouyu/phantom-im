package im.phantom.user;

import im.phantom.api.route.GatewayDeliveryService;
import im.phantom.api.route.RouteEntry;
import im.phantom.api.route.RouteTableService;
import im.phantom.common.Envelopes;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.KickPush;
import org.springframework.stereotype.Component;

/** 基于在线路由表的用户设备强制下线实现。 */
@Component
public class RouteUserSessionKicker implements UserSessionKicker {

    private final RouteTableService routeTable;
    private final GatewayDeliveryService gateway;

    public RouteUserSessionKicker(RouteTableService routeTable, GatewayDeliveryService gateway) {
        this.routeTable = routeTable;
        this.gateway = gateway;
    }

    @Override
    public void kickAll(String userId, KickPush.KickReason reason) {
        for (RouteEntry entry : routeTable.query(userId)) {
            kick(entry, reason);
        }
    }

    @Override
    public void kickDevice(String userId, String deviceId, KickPush.KickReason reason) {
        for (RouteEntry entry : routeTable.query(userId)) {
            if (deviceId == null || deviceId.isBlank() || deviceId.equals(entry.deviceId())) {
                kick(entry, reason);
            }
        }
    }

    private void kick(RouteEntry entry, KickPush.KickReason reason) {
        gateway.send(entry.gatewayId(), entry.connId(),
                Envelopes.push(Command.COMMAND_KICK_PUSH, KickPush.newBuilder().setReason(reason).build()));
        routeTable.remove(entry.userId(), entry.deviceType(), entry.connId());
    }
}
