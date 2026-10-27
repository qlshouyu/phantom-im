package im.phantom.api.group;

/** 群成员与权限展示项。 */
public record GroupMemberDto(
        String groupId,
        String userId,
        String role,
        String groupNickname,
        Long mutedUntil,
        boolean mutedAll
) {}
