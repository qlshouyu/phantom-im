package im.phantom.api.relation;

import java.util.List;

/** 更新好友备注、特别关心和分组；null 表示不修改。 */
public record UpdateFriendRequest(String remark, Boolean pinned, List<String> groupIds) {
    public UpdateFriendRequest {
        groupIds = groupIds == null ? null : List.copyOf(groupIds);
    }
}
