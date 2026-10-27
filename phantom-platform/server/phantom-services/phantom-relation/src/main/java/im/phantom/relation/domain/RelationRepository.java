package im.phantom.relation.domain;

import java.util.List;
import java.util.Optional;

/** 关系链持久化契约；所有查询均以应用和用户为边界。 */
public interface RelationRepository {

    Optional<UserRecord> findUser(String appId, String userId);

    Optional<UserRecord> findUserById(String userId);

    void insertFriendRequest(FriendRequestRecord request);

    Optional<FriendRequestRecord> findFriendRequest(String appId, String requestId);

    Optional<FriendRequestRecord> findPendingFriendRequest(String appId, String fromUserId, String toUserId);

    List<FriendRequestRecord> listFriendRequests(String userId, boolean includeHandled);

    int markIncomingRequestsRead(String userId, long readAt);

    int countUnreadRequests(String userId);

    boolean transitionRequest(String requestId, String fromStatus, String toStatus, long handledAt);

    boolean isFriend(String userId, String friendId);

    DirectMessageRelationState directMessageRelationState(String senderId, String receiverId);

    int countFriends(String userId);

    void insertFriend(String userId, String friendId, long createdAt);

    void deleteFriend(String userId, String friendId);

    void deleteFriendData(String userId, String friendId);

    List<FriendRecord> listFriends(String userId);

    Optional<FriendSettingRecord> findFriendSetting(String userId, String friendId);

    void upsertFriendSetting(String userId, String friendId, String remark, boolean pinned, long updatedAt);

    void replaceFriendGroups(String userId, String friendId, List<String> groupIds);

    List<FriendGroupRecord> listFriendGroups(String userId);

    Optional<FriendGroupRecord> findFriendGroup(String groupId, String userId);

    void insertFriendGroup(String userId, FriendGroupRecord group);

    boolean renameFriendGroup(String groupId, String userId, String name);

    boolean deleteFriendGroup(String groupId, String userId);

    boolean isGroupOwnedBy(String groupId, String userId);

    boolean isBlacklisted(String userId, String blockedId);

    void insertBlacklist(String userId, String blockedId, long createdAt);

    boolean deleteBlacklist(String userId, String blockedId);

    List<BlacklistRecord> listBlacklist(String userId);

    Optional<RelationSettingRecord> findRelationSetting(String userId);

    void upsertAutoAccept(String userId, boolean autoAccept, long updatedAt);

    Optional<QrCardRecord> findActiveQrCardByHash(String tokenHash, long now);

    Optional<QrCardRecord> findActiveQrCardByUser(String userId, long now);

    void revokeActiveQrCards(String userId);

    void insertQrCard(QrCardRecord card);

    record UserRecord(String userId, String appId, String nickname, String avatarUrl, String status) {}

    record FriendRequestRecord(
            String requestId,
            String appId,
            String fromUserId,
            String toUserId,
            String verificationMessage,
            String status,
            long createdAt,
            Long handledAt,
            Long readAt,
            String pendingKey
    ) {}

    record FriendRecord(
            String friendId,
            String nickname,
            String avatarUrl,
            String remark,
            boolean pinned,
            long createdAt,
            List<String> groupIds
    ) {
        public FriendRecord {
            groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
        }
    }

    record FriendSettingRecord(String remark, boolean pinned) {}

    record DirectMessageRelationState(boolean senderBlacklistedReceiver, boolean receiverBlacklistedSender,
                                      boolean senderStillHasPeer, boolean peerStillHasSender) {}

    record FriendGroupRecord(String groupId, String name, long createdAt, int memberCount) {}

    record BlacklistRecord(String blockedId, String nickname, String avatarUrl, long createdAt) {}

    record RelationSettingRecord(boolean autoAcceptRequests, int friendLimit) {}

    record QrCardRecord(String cardId, String userId, String tokenHash, String tokenValue, String status,
                        long createdAt, Long expiresAt) {}
}
