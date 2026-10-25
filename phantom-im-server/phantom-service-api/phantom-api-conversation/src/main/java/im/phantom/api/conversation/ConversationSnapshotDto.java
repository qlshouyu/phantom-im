package im.phantom.api.conversation;

/** 会话列表快照项，服务端为未读和排序权威。 */
public record ConversationSnapshotDto(
        String convId,
        String convType,
        String targetId,
        String title,
        String avatarUrl,
        String lastMessageDigest,
        long lastMessageAt,
        int unreadCount,
        boolean mentionMe,
        boolean pinned,
        long pinnedAt,
        boolean muted,
        String draft,
        long draftEditedAt,
        long maxConvSeq,
        long readConvSeq,
        boolean readOnly
) {}
