package im.phantom.api.relation;

import java.util.List;

/** 关系链服务间契约。 */
public interface RelationService {

    UserCardDto getUserCard(String appId, String viewerId, String targetId);

    FriendRequestResultDto createFriendRequest(String appId, String fromUserId,
                                                CreateFriendRequestRequest request);

    FriendRequestPageDto listFriendRequests(String userId, boolean includeHandled);

    FriendRequestDto handleFriendRequest(String appId, String userId, String requestId,
                                         FriendRequestAction action);

    List<FriendSummaryDto> listFriends(String userId);

    FriendSummaryDto updateFriend(String userId, String friendId, UpdateFriendRequest request);

    void deleteFriend(String userId, String friendId);

    List<FriendGroupDto> listFriendGroups(String userId);

    FriendGroupDto createFriendGroup(String userId, String name);

    FriendGroupDto renameFriendGroup(String userId, String groupId, String name);

    void deleteFriendGroup(String userId, String groupId);

    RelationSettingsDto getRelationSettings(String userId);

    RelationSettingsDto updateRelationSettings(String userId, UpdateRelationSettingsRequest request);

    void blockUser(String appId, String userId, String blockedId);

    void unblockUser(String userId, String blockedId);

    List<BlacklistEntryDto> listBlacklist(String userId);

    QrCardDto getOrCreateQrCard(String userId);

    QrCardDto refreshQrCard(String userId);

    QrCardScanResultDto scanQrCard(String appId, String viewerId, String token);

    String displayName(String ownerId, String targetId, String fallbackNickname);

    void authorizeDirectMessage(String appId, String senderId, String receiverId);

    /**
     * 单聊收单策略。默认保持 M0 的强校验语义；关系链实现会扩展消息请求和受限投递分支。
     */
    default DirectMessagePolicy evaluateDirectMessage(String appId, String senderId, String receiverId) {
        authorizeDirectMessage(appId, senderId, receiverId);
        return DirectMessagePolicy.ALLOWED;
    }
}
