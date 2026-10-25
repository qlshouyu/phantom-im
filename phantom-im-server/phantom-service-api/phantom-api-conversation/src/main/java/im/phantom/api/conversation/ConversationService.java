package im.phantom.api.conversation;

import im.phantom.proto.v1.Msg;

import java.util.List;

/** 会话状态、未读、草稿与同步事件的跨模块契约。 */
public interface ConversationService {

    void onNewMessage(Msg msg);

    void createGroupConversation(String appId, String groupId, String convId, String name,
                                 String avatarUrl, List<String> memberIds, String ownerId, long createdAt);

    ConversationPageDto listConversations(String userId, String cursor, int limit);

    ConversationSnapshotDto getConversation(String userId, String convId);

    ConversationSnapshotDto updateConversation(String userId, String convId, UpdateConversationRequest request);

    void deleteConversation(String userId, String convId);

    ReadPositionDto markRead(String appId, String userId, String convId, long readConvSeq);

    ReadPositionDto getReadPosition(String userId, String convId);

    ConversationDraftDto saveDraft(String userId, String convId, String draft, Long editedAt);

    ConversationDraftDto clearDraft(String userId, String convId, Long editedAt);

    void setReadOnly(String userId, String convId, boolean readOnly);

    ConversationSnapshotDto refreshReadOnly(String appId, String userId, String convId);

    ConversationSnapshotDto handleMessageRequest(String userId, String convId, boolean accept);
}
