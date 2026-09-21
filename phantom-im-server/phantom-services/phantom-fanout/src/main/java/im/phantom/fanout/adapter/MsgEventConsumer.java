package im.phantom.fanout.adapter;

import im.phantom.fanout.app.FanoutService;
import im.phantom.infra.mq.MqConsumers;
import im.phantom.infra.mq.Topics;
import im.phantom.proto.v1.Msg;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 消费 TOPIC_MSG_EVENT，驱动扇出。消费失败自动重试，重试耗尽进 DLQ（不丢）。
 */
@Component
public class MsgEventConsumer implements SmartInitializingSingleton {

    private static final String GROUP = "phantom-fanout";

    private final MqConsumers mqConsumers;
    private final FanoutService fanoutService;

    public MsgEventConsumer(MqConsumers mqConsumers, FanoutService fanoutService) {
        this.mqConsumers = mqConsumers;
        this.fanoutService = fanoutService;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            mqConsumers.newConsumer(GROUP, Topics.MSG_EVENT, Topics.TAG_NEW_MSG, body -> {
                Msg msg = Msg.parseFrom(body);
                fanoutService.onNewMessage(msg);
                return true;
            });
        } catch (Exception e) {
            throw new IllegalStateException("扇出消费者启动失败", e);
        }
    }
}
