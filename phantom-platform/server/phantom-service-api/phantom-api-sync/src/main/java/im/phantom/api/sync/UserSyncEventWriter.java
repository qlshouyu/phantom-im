package im.phantom.api.sync;

import im.phantom.proto.v1.SyncEvent;

/** 由扇出服务实现的用户同步事件写入契约。 */
public interface UserSyncEventWriter {

    /**
     * 幂等写入一条事件。event 的 user_seq 由实现分配。
     * @param eventId 幂等键
     * @return 分配后的 user_seq；重复事件返回既有值或 0
     */
    long append(String userId, String eventId, SyncEvent event);
}
