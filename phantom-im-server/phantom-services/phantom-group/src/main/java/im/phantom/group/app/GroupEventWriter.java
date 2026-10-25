package im.phantom.group.app;

import im.phantom.proto.v1.SyncEvent;

/** 群事件写入契约；实现与状态变更同事务。 */
public interface GroupEventWriter {
    void append(String userId, String eventId, SyncEvent event);
}
