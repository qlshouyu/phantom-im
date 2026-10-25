package im.phantom.openapi.security;

/** OpenAPI 请求防重放：同一签名在时间窗内只能成功使用一次。 */
public interface OpenApiReplayGuard {
    boolean claim(String appId, String signature, long ttlSeconds);
}
