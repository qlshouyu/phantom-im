package im.phantom.user;

import im.phantom.proto.v1.KickPush;

/** 用户会话下线契约。 */
public interface UserSessionKicker {

    void kickAll(String userId, KickPush.KickReason reason);

    void kickDevice(String userId, String deviceId, KickPush.KickReason reason);
}
