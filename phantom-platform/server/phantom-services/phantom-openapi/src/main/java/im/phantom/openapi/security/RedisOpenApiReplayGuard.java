package im.phantom.openapi.security;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/** Redis SET NX 实现跨实例原子防重放。 */
public class RedisOpenApiReplayGuard implements OpenApiReplayGuard {

    private static final String PREFIX = "phantom:openapi:replay:";
    private final StringRedisTemplate redis;

    public RedisOpenApiReplayGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean claim(String appId, String signature, long ttlSeconds) {
        Boolean claimed = redis.opsForValue().setIfAbsent(
                PREFIX + appId + ":" + signature, "1", Duration.ofSeconds(Math.max(1, ttlSeconds)));
        return Boolean.TRUE.equals(claimed);
    }
}
