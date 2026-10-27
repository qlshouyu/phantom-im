package im.phantom.api.config;

import java.util.List;

/** 应用详情，不包含任何 AppSecret 明文。 */
public record ApplicationDetailDto(ApplicationDto application, List<AppSecretMetadataDto> secrets) {
    public ApplicationDetailDto {
        secrets = secrets == null ? List.of() : List.copyOf(secrets);
    }
}
