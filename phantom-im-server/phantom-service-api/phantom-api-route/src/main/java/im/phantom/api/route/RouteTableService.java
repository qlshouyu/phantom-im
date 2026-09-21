package im.phantom.api.route;

import java.util.List;
import java.util.Optional;

/**
 * 在线路由表读写契约（connection-gateway spec）。Redis Hash route:{user_id}，field=device_type。
 * M0 提供 Redis 本地实现；未来拆分时由 gRPC stub 兜底（@ConditionalOnMissingBean）。
 */
public interface RouteTableService {

    /**
     * 原子登记：写入新记录并返回被顶替的同类型旧记录（若存在且 deviceId 不同）。
     * 调用方据返回值向旧连接推 KickPush（SAME_TYPE_LOGIN）。
     */
    Optional<RouteEntry> register(RouteEntry entry);

    /** 心跳续期：刷新 last_active。 */
    void heartbeat(String userId, int deviceType, String connId, long now);

    /** 主动摘除：先比对 connId 再删，防旧连接迟到断连误删新连接。 */
    void remove(String userId, int deviceType, String connId);

    /** 查询某用户全部在线端（至多 3 条：MOBILE/DESKTOP/WEB）。 */
    List<RouteEntry> query(String userId);
}
