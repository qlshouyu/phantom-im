package im.phantom.gateway.ws;

import im.phantom.api.route.GatewayDeliveryService;
import im.phantom.proto.v1.Envelope;
import org.springframework.stereotype.Component;

/**
 * 网关下发本地实现：向本进程内 connId 对应连接发帧。
 * M0 单网关实例，忽略 gatewayId；多实例时此实现由 gRPC stub 路由到目标网关。
 */
@Component
public class GatewayDeliveryServiceImpl implements GatewayDeliveryService {

    private final ConnectionRegistry registry;

    public GatewayDeliveryServiceImpl(ConnectionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean send(String gatewayId, String connId, Envelope envelope) {
        return registry.send(connId, envelope);
    }
}
