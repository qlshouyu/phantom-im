package im.phantom.sync.adapter;

import im.phantom.api.route.UserSeqQuery;
import im.phantom.sync.infra.UserTimelineReadDao;
import org.springframework.stereotype.Component;

/** UserSeqQuery 本地实现：读用户同步 Timeline 最大 user_seq。 */
@Component
public class UserSeqQueryImpl implements UserSeqQuery {

    private final UserTimelineReadDao dao;

    public UserSeqQueryImpl(UserTimelineReadDao dao) {
        this.dao = dao;
    }

    @Override
    public long maxUserSeq(String userId) {
        return dao.maxUserSeq(userId);
    }
}
