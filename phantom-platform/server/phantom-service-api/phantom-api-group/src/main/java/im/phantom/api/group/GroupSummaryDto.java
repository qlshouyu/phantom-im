package im.phantom.api.group;

/** 群聊概要。 */
public record GroupSummaryDto(
        String groupId,
        String convId,
        String name,
        String avatarUrl,
        int memberCount,
        String ownerId,
        String status
) {}
