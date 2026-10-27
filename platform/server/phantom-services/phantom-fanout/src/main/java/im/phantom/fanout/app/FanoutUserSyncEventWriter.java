package im.phantom.fanout.app;

import im.phantom.api.sync.UserSyncEventWriter;
import im.phantom.fanout.infra.UserTimelineWriteDao;
import im.phantom.infra.redis.SeqAllocator;
import im.phantom.proto.v1.SyncEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 通用领域事件写入实现，保持 fanout 为 user_seq 唯一分配方。 */
@Service
public class FanoutUserSyncEventWriter implements UserSyncEventWriter {

    private final UserTimelineWriteDao dao;
    private final SeqAllocator seqAllocator;

    public FanoutUserSyncEventWriter(UserTimelineWriteDao dao, SeqAllocator seqAllocator) {
        this.dao = dao;
        this.seqAllocator = seqAllocator;
    }

    @Override
    public long append(String userId, String eventId, SyncEvent event) {
        if (dao.exists(userId, eventId)) {
            return 0L;
        }
        long userSeq = seqAllocator.nextUserSeq(userId);
        SyncEvent stored = event.toBuilder()
                .setUserSeq(userSeq)
                .setEventTime(event.getEventTime() == 0 ? System.currentTimeMillis() : event.getEventTime())
                .build();
        try {
            dao.insert(userId, userSeq, event.getEventCase().getNumber(), eventId,
                    stored.toByteArray(), stored.getEventTime());
            return userSeq;
        } catch (DuplicateKeyException duplicate) {
            return 0L;
        }
    }
}
