package im.phantom.risk.app;

import im.phantom.proto.v1.SyncEvent;

/** 举报结果事件写入 outbox，由 fanout 统一分配 user_seq。 */
public interface ReportEventWriter {
    void append(String userId, String eventId, SyncEvent event);
}
