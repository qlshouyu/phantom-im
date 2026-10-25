package im.phantom.api.conversation;

import java.util.List;

/** 会话分页快照与同步基线。 */
public record ConversationPageDto(
        List<ConversationSnapshotDto> items,
        String nextCursor,
        boolean hasMore,
        long maxUserSeq,
        int badgeCount
) {
    public ConversationPageDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
