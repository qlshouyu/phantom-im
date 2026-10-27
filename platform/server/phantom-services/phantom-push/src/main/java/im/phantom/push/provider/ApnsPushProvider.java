package im.phantom.push.provider;

import im.phantom.common.Ids;
import im.phantom.push.spi.PushMessage;
import im.phantom.push.spi.PushProvider;
import im.phantom.push.spi.PushDeliveryException;

/** APNs 适配器契约；生产部署注入 team/key/topic 后替换本地 provider。 */
public class ApnsPushProvider implements PushProvider {

    private final String topic;

    public ApnsPushProvider(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("APNs topic 不能为空");
        }
        this.topic = topic;
    }

    @Override
    public String send(PushMessage message) {
        if (message.token() == null || message.token().isBlank()) {
            throw PushDeliveryException.invalidToken("APNs token 不能为空");
        }
        if (message.title() == null || message.body() == null) {
            throw PushDeliveryException.retryable("APNs payload 不完整");
        }
        return Ids.newId("apns");
    }

    public String topic() { return topic; }
}
