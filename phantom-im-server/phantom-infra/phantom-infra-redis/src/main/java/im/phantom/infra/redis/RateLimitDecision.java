package im.phantom.infra.redis;

/** 一次限流判定结果。 */
public record RateLimitDecision(boolean allowed, long current, long limit, long retryAfterSeconds) {}
