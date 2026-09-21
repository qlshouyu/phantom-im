package im.phantom.api.route;

import im.phantom.proto.v1.Envelope;

/**
 * 网关下发契约：向某 connId 对应的本地 WebSocket 连接推一帧。
 * 由 gateway module 本地实现；push module 调用它完成「最后一公里」。
 * M0 单网关实例；多实例时此接口由 gRPC stub 路由到目标网关。
 */
public interface GatewayDeliveryService {

    /** 下发一帧到指定连接；连接不存在返回 false（惰性清理/转离线的判定输入）。 */
    boolean send(String gatewayId, String connId, Envelope envelope);
}
