package im.phantom.api.user;

import java.util.Map;

/** 平台保存的 Phantom ID 极简资料。 */
public record UserProfileDto(
        String userId,
        String appId,
        String nickname,
        String avatarUrl,
        Map<String, String> ext,
        String status
) {
    public UserProfileDto {
        ext = ext == null ? Map.of() : Map.copyOf(ext);
    }
}
