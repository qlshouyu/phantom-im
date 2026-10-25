package im.phantom.fanout.adapter;

import im.phantom.fanout.app.SyncEventApplier;
import im.phantom.infra.mq.MqConsumers;
import im.phantom.infra.mq.Topics;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** 消费 TOPIC_SYNC_EVENT，由 fanout 统一分配 user_seq。 */
@Component
public class SyncEventConsumer implements SmartInitializingSingleton {

    private static final String GROUP = "phantom-fanout-sync";

    private final MqConsumers mqConsumers;
    private final SyncEventApplier applier;

    public SyncEventConsumer(MqConsumers mqConsumers, SyncEventApplier applier) {
        this.mqConsumers = mqConsumers;
        this.applier = applier;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            mqConsumers.newConsumer(GROUP, Topics.SYNC_EVENT, Topics.TAG_STATE_CHANGED, body -> {
                applier.apply(body);
                return true;
            });
        } catch (Exception e) {
            throw new IllegalStateException("同步事件消费者启动失败", e);
        }
    }
}
