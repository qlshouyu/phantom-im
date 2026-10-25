package im.phantom.api.message;

/** 消息域服务间契约。 */
public interface MessageService {

    MessagePageDto history(String userId, String convId, long beforeConvSeq, int limit);

    MessageActionDto recall(String userId, String msgId);

    MessageActionDto forward(String appId, String userId, ForwardMessageRequest request);

    void deleteLocal(String userId, String msgId);

    void clearHistory(String userId, String convId);

    /** 接收端 ACK 到达后推进发送者可见的 DELIVERED 状态。 */
    void onDelivered(String receiverId, long userSeq);

    /** 已读位置推进后将该会话中不超过 readConvSeq 的发送者消息推进到 READ。 */
    void onRead(String readerId, String convId, long readConvSeq);
}
