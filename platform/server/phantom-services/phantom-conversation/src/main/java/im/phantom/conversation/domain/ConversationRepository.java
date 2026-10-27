package im.phantom.conversation.domain;

import java.util.List;
import java.util.Optional;

/** 会话投影与状态持久化契约。 */
public interface ConversationRepository {

    Optional<String> findAppIdByUserId(String userId);

    void insertConversationIfAbsent(ConversationRecord conversation);

    void ensureMember(String convId, String userId, String role, long joinedAt);

    List<String> listActiveMemberIds(String convId);

    Optional<String> findPeerId(String convId, String userId);

    Optional<GroupInfo> findGroupInfo(String convId, String userId);

    void ensureUserConversation(String userId, String convId, long now);

    void resetDeletedState(String userId, String convId, long now);

    void appendMessageProjection(String userId, String convId, String digest, long messageAt,
                                 long convSeq, boolean incrementUnread, boolean mentionMe, long now);

    void markMessageRequest(String userId, String convId, long now);

    void clearMessageRequest(String userId, String convId, int unreadCount, long now);

    void clearDraft(String userId, String convId, long editedAt);

    Optional<UserConversationState> findUserConversation(String userId, String convId);

    List<ConversationRow> listConversations(String userId, int offset, int limit);

    Optional<ConversationRow> findConversation(String userId, String convId);

    int badgeCount(String userId);

    long maxUserSeq(String userId);

    long maxConvSeq(String convId);

    void advanceConvSeq(String convId, long convSeq);

    void updateConversationState(String userId, String convId, boolean pinned, boolean muted,
                                 long pinnedAt, long now);

    void markConversationDeleted(String userId, String convId, long now);

    void updateReadOnly(String userId, String convId, boolean readOnly, long now);

    long upsertReadPosition(String userId, String convId, long readConvSeq, long now);

    long currentReadPosition(String userId, String convId);

    int countUnreadAfter(String userId, String convId, long readConvSeq);

    List<String> listMentionJsonAfter(String convId, long readConvSeq);

    int countReadersAtLeast(String convId, long readConvSeq);

    void updateUnreadProjection(String userId, String convId, int unreadCount, boolean mentionMe, long now);

    long saveDraftIfNewer(String userId, String convId, String draft, long editedAt, long now);

    Optional<DraftState> findDraft(String userId, String convId);

    void upsertMessageMeta(String msgId, int status, boolean restricted, String replyToMsgId,
                           String mentionsJson, int burnTtlSeconds, long now);

    record ConversationRecord(String convId, int convType, String ownerAppId, String targetId,
                              String status, boolean readOnly, long createdAt, long updatedAt) {}

    record GroupInfo(String groupId, String convId, String name, String avatarUrl, String ownerId,
                     String status, int memberCount, String memberStatus) {}

    record UserConversationState(String userId, String convId, boolean pinned, long pinnedAt,
                                 boolean muted, boolean deleted, String draft, long draftEditedAt,
                                 int unreadCount, boolean mentionMe, boolean isRequest,
                                 String lastMessageDigest, long lastMessageAt, long lastMessageConvSeq,
                                 boolean readOnly, long updatedAt) {}

    record ConversationRow(
            String convId,
            int convType,
            String ownerAppId,
            String targetId,
            String convStatus,
            boolean conversationReadOnly,
            String groupId,
            String groupName,
            String groupAvatarUrl,
            String groupStatus,
            String groupMemberStatus,
            String peerId,
            String peerNickname,
            String peerAvatarUrl,
            boolean pinned,
            long pinnedAt,
            boolean muted,
            boolean deleted,
            String draft,
            long draftEditedAt,
            int unreadCount,
            boolean mentionMe,
            boolean isRequest,
            String lastMessageDigest,
            long lastMessageAt,
            long lastMessageConvSeq,
            boolean userReadOnly,
            long readConvSeq,
            long maxConvSeq,
            long updatedAt
    ) {}

    record DraftState(String draft, long editedAt) {}
}
