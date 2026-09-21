package im.phantom.message.infra;

import im.phantom.infra.mq.TransactionChecker;
import org.springframework.stereotype.Component;

/** 事务回查：按 msg_id 查会话 Timeline 是否已落库（MQ 架构 §8 失败推演）。 */
@Component
public class MsgTransactionChecker implements TransactionChecker {

    private final ConvTimelineDao dao;

    public MsgTransactionChecker(ConvTimelineDao dao) {
        this.dao = dao;
    }

    @Override
    public boolean committed(String msgId) {
        return dao.existsByMsgId(msgId);
    }
}
