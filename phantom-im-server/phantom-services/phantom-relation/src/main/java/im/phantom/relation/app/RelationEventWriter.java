package im.phantom.relation.app;

import im.phantom.proto.v1.SyncEvent;

/** 关系领域事件写入契约；实现必须与业务状态处于同一本地事务。 */
public interface RelationEventWriter {

    void append(String userId, String eventId, SyncEvent event);
}
