package im.phantom.infra.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import im.phantom.api.route.RouteEntry;
import im.phantom.api.route.RouteTableService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

// 说明：自建 ObjectMapper 而非注入，避免自动装配阶段早于 JacksonAutoConfiguration 的时序问题。
// RouteEntry 是简单 record，普通 ObjectMapper 即可序列化。

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 在线路由表 Redis 实现（connection-gateway spec，MQ 架构 §6）。
 * route:{user_id} Hash，field=device_type，value=RouteEntry JSON。
 * M0 提供的本地实现（@ConditionalOnMissingBean 未命中远程 stub 时生效）。
 */
@Component
public class RouteTableRedisService implements RouteTableService {

    /** 原子登记：写新值并返回旧值（供同类型顶替判定）。 */
    private static final DefaultRedisScript<String> REGISTER = new DefaultRedisScript<>(
            "local old = redis.call('HGET', KEYS[1], ARGV[1]) " +
            "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]) " +
            "if old == false then return nil else return old end",
            String.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final int heartbeatIntervalS;

    public RouteTableRedisService(StringRedisTemplate redis,
                                  @Value("${phantom.gateway.heartbeat-interval-s:270}") int heartbeatIntervalS) {
        this.redis = redis;
        this.heartbeatIntervalS = heartbeatIntervalS;
    }

    private static String key(String userId) {
        return "route:" + userId;
    }

    @Override
    public Optional<RouteEntry> register(RouteEntry entry) {
        String field = String.valueOf(entry.deviceType());
        String old = redis.execute(REGISTER, List.of(key(entry.userId())), field, toJson(entry));
        if (old == null) {
            return Optional.empty();
        }
        RouteEntry oldEntry = fromJson(old);
        // deviceId 不同 → 同类型顶替，返回旧记录供调用方踢下线
        return (oldEntry != null && !oldEntry.deviceId().equals(entry.deviceId()))
                ? Optional.of(oldEntry) : Optional.empty();
    }

    @Override
    public void heartbeat(String userId, int deviceType, String connId, long now) {
        String field = String.valueOf(deviceType);
        String json = (String) redis.opsForHash().get(key(userId), field);
        if (json == null) {
            return;
        }
        RouteEntry e = fromJson(json);
        if (e == null || !e.connId().equals(connId)) {
            return; // 已被顶替/摘除，忽略迟到心跳
        }
        RouteEntry updated = new RouteEntry(e.userId(), e.deviceType(), e.deviceId(),
                e.connId(), e.gatewayId(), e.appId(), now, e.foreground());
        redis.opsForHash().put(key(userId), field, toJson(updated));
    }

    @Override
    public void remove(String userId, int deviceType, String connId) {
        String field = String.valueOf(deviceType);
        String json = (String) redis.opsForHash().get(key(userId), field);
        if (json == null) {
            return;
        }
        RouteEntry e = fromJson(json);
        // 先比对 connId 再删：防旧连接迟到断连误删刚登记的新连接
        if (e != null && e.connId().equals(connId)) {
            redis.opsForHash().delete(key(userId), field);
        }
    }

    @Override
    public List<RouteEntry> query(String userId) {
        Map<Object, Object> all = redis.opsForHash().entries(key(userId));
        long now = System.currentTimeMillis();
        long ttlMs = 2L * heartbeatIntervalS * 1000L;
        List<RouteEntry> result = new ArrayList<>();
        for (Object v : all.values()) {
            RouteEntry e = fromJson((String) v);
            if (e == null) {
                continue;
            }
            // 惰性判活：超时的不返回（判活规则同协议 2.4）
            if (now - e.lastActive() <= ttlMs) {
                result.add(e);
            }
        }
        return result;
    }

    private String toJson(RouteEntry e) {
        try {
            return MAPPER.writeValueAsString(e);
        } catch (Exception ex) {
            throw new IllegalStateException("路由记录序列化失败", ex);
        }
    }

    private RouteEntry fromJson(String json) {
        try {
            return MAPPER.readValue(json, RouteEntry.class);
        } catch (Exception ex) {
            return null;
        }
    }
}
