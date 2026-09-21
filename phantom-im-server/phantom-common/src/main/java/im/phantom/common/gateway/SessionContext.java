package im.phantom.common.gateway;

/**
 * 已认证连接的会话上下文，传给各业务 CommandHandler。
 * 保持在 common，使业务 module 无需依赖 gateway 即可实现指令处理。
 */
public record SessionContext(
        String userId,
        String deviceId,
        int deviceType,   // proto DeviceType 枚举序号
        String appId,
        String connId,
        String gatewayId
) {}
