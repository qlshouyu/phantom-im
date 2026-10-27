package im.phantom.push.provider;

import im.phantom.common.Ids;
import im.phantom.push.spi.PushMessage;
import im.phantom.push.spi.PushProvider;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 开发/测试推送实现：记录消息但不连接真实推送供应商。 */
public class LocalPushProvider implements PushProvider {

    private final List<PushMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public String send(PushMessage message) {
        sent.add(message);
        return Ids.newId("push");
    }

    public List<PushMessage> sentMessages() {
        return List.copyOf(sent);
    }
}
