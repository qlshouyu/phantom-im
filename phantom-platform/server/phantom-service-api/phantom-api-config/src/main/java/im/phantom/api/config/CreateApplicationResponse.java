package im.phantom.api.config;

/** 创建应用响应；appSecret 仅在本响应中出现一次。 */
public record CreateApplicationResponse(String appId, String appSecret, AppFeatureConfigDto features) {}
