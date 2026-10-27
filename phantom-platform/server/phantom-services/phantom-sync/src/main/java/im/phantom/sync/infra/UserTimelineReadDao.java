package im.phantom.sync.infra;

import com.google.protobuf.InvalidProtocolBufferException;
import im.phantom.proto.v1.SyncEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/** 用户同步 Timeline 读取（同步服务只读）。 */
@Repository
public class UserTimelineReadDao {

    private final JdbcTemplate jdbc;

    public UserTimelineReadDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 取 user_seq > since 的事件，升序，至多 limit 条。 */
    public List<SyncEvent> fetch(String userId, long since, int limit) {
        return jdbc.query(
                "SELECT event_bytes FROM user_timeline WHERE user_id=? AND user_seq>? " +
                "ORDER BY user_seq ASC LIMIT ?",
                (rs, rowNum) -> parse(rs.getBytes(1)),
                userId, since, limit);
    }

    /** 该用户当前最新 user_seq（无则 0）。 */
    public long maxUserSeq(String userId) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(user_seq), 0) FROM user_timeline WHERE user_id=?",
                Long.class, userId);
        return max == null ? 0L : max;
    }

    private SyncEvent parse(byte[] bytes) {
        try {
            return SyncEvent.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("同步事件反序列化失败", e);
        }
    }

    static List<SyncEvent> empty() {
        return new ArrayList<>();
    }
}
