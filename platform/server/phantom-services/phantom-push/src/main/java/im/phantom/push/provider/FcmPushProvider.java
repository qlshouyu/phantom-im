package im.phantom.push.provider;

import im.phantom.common.Ids;
import im.phantom.push.spi.PushMessage;
import im.phantom.push.spi.PushProvider;
import im.phantom.push.spi.PushDeliveryException;

/** FCM 适配器契约；生产部署注入 service account 后替换本地 provider。 */
public class FcmPushProvider implements PushProvider {

    private final String projectId;

    public FcmPushProvider(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("FCM projectId 不能为空");
        }
        this.projectId = projectId;
    }

    @Override
    public String send(PushMessage message) {
        if (message.token() == null || message.token().isBlank()) {
            throw PushDeliveryException.invalidToken("FCM token 不能为空");
        }
        if (message.title() == null || message.body() == null) {
            throw PushDeliveryException.retryable("FCM payload 不完整");
        }
        return Ids.newId("fcm");
    }

    public String projectId() { return projectId; }
}
