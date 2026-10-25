package im.phantom.api.relation;

/** 好友申请列表项。 */
public record FriendRequestDto(
        String requestId,
        String fromUserId,
        String toUserId,
        RelationUserDto peer,
        String verificationMessage,
        String status,
        String direction,
        long createdAt,
        Long handledAt,
        boolean unread
) {}
