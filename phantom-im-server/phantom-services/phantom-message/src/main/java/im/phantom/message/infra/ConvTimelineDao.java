package im.phantom.message.infra;

import im.phantom.proto.v1.Msg;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话 Timeline DAO（消息服务自管其表）。
 * 落库与取号水位在同一本地事务内推进（design D2），保证真相源不落后于已落库消息。
 */
@Repository
public class ConvTimelineDao {

    private final JdbcTemplate jdbc;

    public ConvTimelineDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等命中结果。 */
    public record Existing(String msgId, long convSeq, long serverTime) {}

    /** 按 (sender, client_msg_id) 查已落库消息（发送幂等）。 */
    public Existing findByClientMsgId(String senderId, String clientMsgId) {
        return jdbc.query(
                "SELECT msg_id, conv_seq, server_time FROM conv_timeline WHERE sender_id=? AND client_msg_id=?",
                rs -> rs.next() ? new Existing(rs.getString(1), rs.getLong(2), rs.getLong(3)) : null,
                senderId, clientMsgId);
    }

    /** 事务回查依据：会话 Timeline 是否存在该 msg_id。 */
    public boolean existsByMsgId(String msgId) {
        Integer n = jdbc.query("SELECT 1 FROM conv_timeline WHERE msg_id=? LIMIT 1",
                rs -> rs.next() ? 1 : 0, msgId);
        return n != null && n == 1;
    }

    /**
     * 本地事务：插入会话 Timeline + 推进取号水位。
     * 命中 (sender, client_msg_id) 唯一键时抛 DuplicateKeyException（幂等竞态由上层处理）。
     */
    @Transactional
    public void insert(Msg msg) {
        jdbc.update(
                "INSERT INTO conv_timeline(conv_id, conv_seq, msg_id, client_msg_id, sender_id, " +
                "conv_type, msg_type, msg_bytes, server_time, recalled) VALUES(?,?,?,?,?,?,?,?,?,0)",
                msg.getConvId(), msg.getConvSeq(), msg.getMsgId(), msg.getClientMsgId(), msg.getSenderId(),
                msg.getConvTypeValue(), msg.getMsgTypeValue(), msg.toByteArray(), msg.getServerTime());
        // 水位真相源：GREATEST 保证只增不减（sequence-allocation 恢复对齐读取此值）
        jdbc.update(
                "INSERT INTO seq_watermark(scope, seq) VALUES(?, ?) " +
                "ON DUPLICATE KEY UPDATE seq=GREATEST(seq, VALUES(seq))",
                "conv:" + msg.getConvId(), msg.getConvSeq());
    }
}
