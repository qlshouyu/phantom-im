package im.phantom.api.config;

/** 应用管理面视图，不包含 AppSecret 明文。 */
public record ApplicationDto(
        String appId,
        String name,
        String status,
        long createdAt,
        AppFeatureConfigDto features
) {}
