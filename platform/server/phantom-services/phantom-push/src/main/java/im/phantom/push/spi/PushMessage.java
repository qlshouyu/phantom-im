package im.phantom.push.spi;

import java.util.Map;

/** 与供应商无关的推送消息。 */
public record PushMessage(
        String token,
        String title,
        String body,
        String collapseKey,
        Map<String, String> data
) {
    public PushMessage {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
