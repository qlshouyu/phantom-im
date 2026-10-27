package im.phantom.common.risk;

/** 账号处置与未成年人模式对消息链路的同步检查。 */
public interface UserSafetyChecker {
    void assertCanSend(String appId, String senderId);
    void assertCanReceive(String appId, String senderId, String receiverId);
}
