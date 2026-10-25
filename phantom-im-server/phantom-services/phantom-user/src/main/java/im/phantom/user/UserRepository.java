package im.phantom.user;

import java.util.Optional;

/** 用户与资料持久化契约。 */
public interface UserRepository {

    Optional<String> findByExternalUserId(String appId, String externalUserId);

    boolean exists(String appId, String userId);

    Optional<UserRecord> findById(String appId, String userId);

    void insert(String appId, String userId, String externalUserId, String nickname,
                String avatarUrl, String extJson, long now);

    void updateProfile(String userId, String nickname, String avatarUrl, String extJson, long now);

    void updateStatus(String userId, String status, long now);

    void markDeleted(String userId, String releasedExternalUserId, long now);

    void anonymizeProfile(String userId, long now);

    void leaveGroupsAndConversations(String userId, long now);

    record UserRecord(String userId, String appId, String externalUserId, String status,
                      String nickname, String avatarUrl, String extJson, String displaySnapshot) {}
}
