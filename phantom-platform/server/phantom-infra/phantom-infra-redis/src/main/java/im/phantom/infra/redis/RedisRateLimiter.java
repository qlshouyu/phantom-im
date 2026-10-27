package im.phantom.infra.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Redis 固定窗口限流器，按应用、用户和操作维度使用。 */
@Component
public class RedisRateLimiter {

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();

    static {
        SCRIPT.setLocation(new ClassPathResource("lua/rate_limit.lua"));
        SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public RateLimitDecision allow(String scope, String subject, long limit, long windowSeconds) {
        if (limit <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("limit 和 windowSeconds 必须大于 0");
        }
        String key = "rate:" + scope + ":" + subject;
        Long current = redis.execute(SCRIPT, List.of(key), String.valueOf(windowSeconds));
        long count = current == null ? 0L : current;
        if (count <= limit) {
            return new RateLimitDecision(true, count, limit, 0);
        }
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        return new RateLimitDecision(false, count, limit, ttl == null || ttl < 0 ? windowSeconds : ttl);
    }
}
