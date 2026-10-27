package im.phantom.sync.app;

import im.phantom.proto.v1.SyncEvent;
import im.phantom.proto.v1.SyncReq;
import im.phantom.proto.v1.SyncResp;
import im.phantom.sync.infra.UserTimelineReadDao;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 增量同步用例（sync-catchup spec）：按 since_user_seq 升序返回 ≤limit 事件 + max_user_seq + has_more。
 * 客户端自带游标，已 ACK 的事件（user_seq ≤ since）天然不再下发，与在线推送共用同一进度。
 */
@Service
public class SyncAppService {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;

    private final UserTimelineReadDao dao;

    public SyncAppService(UserTimelineReadDao dao) {
        this.dao = dao;
    }

    public SyncResp sync(String userId, SyncReq req) {
        int limit = req.getLimit() <= 0 ? DEFAULT_LIMIT : Math.min(req.getLimit(), MAX_LIMIT);
        long since = req.getSinceUserSeq();

        List<SyncEvent> events = dao.fetch(userId, since, limit);
        long maxUserSeq = dao.maxUserSeq(userId);
        // 还有更多：本批最后一条仍小于最新（或本批为空但 max>since 的极端不成立，因空即已最新）
        boolean hasMore = !events.isEmpty() && events.get(events.size() - 1).getUserSeq() < maxUserSeq;

        return SyncResp.newBuilder()
                .addAllEvents(events)
                .setMaxUserSeq(maxUserSeq)
                .setHasMore(hasMore)
                .setFullResync(false)   // M0 不强制 30 天窗口，恒 false
                .build();
    }
}
