package im.phantom.api.route;

import im.phantom.proto.v1.Msg;

/** 在线投递数为 0 时触发的离线推送扩展点。 */
public interface OfflinePushNotifier {
    void notifyOffline(String receiverId, Msg message);
}
