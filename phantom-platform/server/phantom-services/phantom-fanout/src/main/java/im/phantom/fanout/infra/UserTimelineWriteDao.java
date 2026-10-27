package im.phantom.fanout.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 用户同步 Timeline 写入（扇出服务为唯一写入方）。 */
@Repository
public class UserTimelineWriteDao {

    private final JdbcTemplate jdbc;

    public UserTimelineWriteDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 该接收者是否已有此 msg 的事件（扇出幂等预检）。 */
    public boolean exists(String userId, String msgId) {
        Integer n = jdbc.query("SELECT 1 FROM user_timeline WHERE user_id=? AND msg_id=? LIMIT 1",
                rs -> rs.next() ? 1 : 0, userId, msgId);
        return n != null && n == 1;
    }

    public List<String> listParticipants(String convId) {
        return jdbc.queryForList("SELECT user_id FROM conversation_member " +
                "WHERE conv_id=? AND left_at IS NULL ORDER BY joined_at", String.class, convId);
    }

    /**
     * 本地事务：写用户同步 Timeline + 推进该用户取号水位。
     * 命中 (user_id, msg_id) 唯一键抛 DuplicateKeyException（并发重复消费幂等）。
     */
    @Transactional
    public void insert(String userId, long userSeq, int eventType, String msgId,
                       byte[] eventBytes, long eventTime) {
        jdbc.update(
                "INSERT INTO user_timeline(user_id, user_seq, event_type, msg_id, event_bytes, event_time) " +
                "VALUES(?,?,?,?,?,?)",
                userId, userSeq, eventType, msgId, eventBytes, eventTime);
        jdbc.update(
                "INSERT INTO seq_watermark(scope, seq) VALUES(?, ?) " +
                "ON DUPLICATE KEY UPDATE seq=GREATEST(seq, VALUES(seq))",
                "user:" + userId, userSeq);
    }
}
