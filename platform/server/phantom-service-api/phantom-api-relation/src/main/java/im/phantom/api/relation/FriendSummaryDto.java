package im.phantom.api.relation;

import java.util.List;

/** 好友列表展示项。 */
public record FriendSummaryDto(
        String userId,
        String nickname,
        String avatarUrl,
        String displayName,
        String remark,
        boolean pinned,
        List<String> groupIds
) {
    public FriendSummaryDto {
        groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
    }
}
