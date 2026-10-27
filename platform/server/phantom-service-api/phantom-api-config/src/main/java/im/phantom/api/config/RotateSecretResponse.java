package im.phantom.api.config;

/** 轮换响应；新 AppSecret 仅在本响应中返回一次。 */
public record RotateSecretResponse(
        String appId,
        String secretVersion,
        String appSecret,
        Long oldSecretExpiresAt
) {}
