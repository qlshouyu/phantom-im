package im.phantom.openapi.security;

/** OpenAPI 应用维度限流契约。 */
public interface OpenApiRateLimiter {

    boolean allow(String appId);
}
