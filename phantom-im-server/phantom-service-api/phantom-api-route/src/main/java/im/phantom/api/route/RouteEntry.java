package im.phantom.api.route;

/** 在线路由表一条记录：某用户某类型设备当前所在网关与连接。 */
public record RouteEntry(
        String userId,
        int deviceType,     // proto DeviceType 枚举序号
        String deviceId,
        String connId,
        String gatewayId,
        String appId,
        long lastActive,
        boolean foreground
) {}
