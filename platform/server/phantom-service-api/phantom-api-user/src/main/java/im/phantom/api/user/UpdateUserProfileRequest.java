package im.phantom.api.user;

import java.util.Map;

/** 用户资料更新请求。 */
public record UpdateUserProfileRequest(String nickname, String avatarUrl, Map<String, String> ext) {
    public UpdateUserProfileRequest {
        ext = ext == null ? Map.of() : Map.copyOf(ext);
    }
}
