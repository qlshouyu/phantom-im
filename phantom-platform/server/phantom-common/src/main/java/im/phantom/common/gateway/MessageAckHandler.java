package im.phantom.common.gateway;

/** 网关 MSG_ACK 之后的业务扩展点；实现方负责推进送达状态。 */
public interface MessageAckHandler {
    void onAck(SessionContext context, long userSeq);
}
