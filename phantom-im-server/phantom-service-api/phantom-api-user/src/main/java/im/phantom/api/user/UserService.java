package im.phantom.api.user;

import java.util.Optional;

/** 用户导入与存在性校验契约。 */
public interface UserService {

    ImportUserResponse importUser(String appId, ImportUserRequest request);

    boolean exists(String appId, String userId);

    Optional<UserStatus> findStatus(String appId, String userId);

    UserProfileDto getProfile(String appId, String userId);

    UserProfileDto updateProfile(String appId, String userId, UpdateUserProfileRequest request, boolean sdkWrite);

    void disable(String appId, String userId);

    void restore(String appId, String userId);

    void forceLogout(String appId, String userId, String deviceId);

    void delete(String appId, String userId);
}
