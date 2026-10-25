package im.phantom.conversation.app;

import im.phantom.proto.v1.SyncEvent;

/** 会话领域事件写入契约；实现与业务状态写入处于同一事务。 */
public interface ConversationEventWriter {
    void append(String userId, String eventId, SyncEvent event);
}
