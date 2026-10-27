package im.phantom.openapi.security;

import im.phantom.infra.redis.RedisRateLimiter;

/** 基于 Redis 的 OpenAPI 每分钟应用限流。 */
public class RedisOpenApiRateLimiter implements OpenApiRateLimiter {

    private final RedisRateLimiter limiter;
    private final long limitPerMinute;

    public RedisOpenApiRateLimiter(RedisRateLimiter limiter, long limitPerMinute) {
        this.limiter = limiter;
        this.limitPerMinute = limitPerMinute;
    }

    @Override
    public boolean allow(String appId) {
        return limiter.allow("openapi", appId, limitPerMinute, 60).allowed();
    }
}
