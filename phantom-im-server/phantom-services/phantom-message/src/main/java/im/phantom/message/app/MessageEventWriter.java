package im.phantom.message.app;

import im.phantom.proto.v1.SyncEvent;

/** 消息领域状态事件写入契约；与状态变更处于同一本地事务。 */
public interface MessageEventWriter {
    void append(String userId, String eventId, SyncEvent event);
}
