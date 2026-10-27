package im.phantom.api.user;

import java.util.Map;

/** 接入方用户导入请求。 */
public record ImportUserRequest(String externalUserId, String nickname, String avatarUrl,
                                Map<String, String> ext) {
    public ImportUserRequest {
        ext = ext == null ? Map.of() : Map.copyOf(ext);
    }
}
