package im.phantom.api.config;

/** AppSecret 版本元数据，不包含密钥明文。 */
public record AppSecretMetadataDto(
        String version,
        long createdAt,
        Long expiresAt,
        String status
) {}
