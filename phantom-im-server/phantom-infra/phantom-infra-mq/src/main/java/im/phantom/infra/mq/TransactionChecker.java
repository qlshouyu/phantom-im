package im.phantom.infra.mq;

/**
 * 事务回查契约：Broker 对悬挂半消息回查时，按 msg_id 判定本地事务是否已提交。
 * 由消息服务实现（查会话 Timeline 是否存在该 msg_id）。
 */
public interface TransactionChecker {
    boolean committed(String msgId);
}
