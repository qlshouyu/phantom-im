package im.phantom.conversation.adapter;

import im.phantom.api.conversation.ConversationService;
import im.phantom.common.BizException;
import im.phantom.common.ErrorCodes;
import im.phantom.common.time.TimeProvider;
import im.phantom.infra.db.idempotency.ConsumedEventStore;
import im.phantom.infra.db.idempotency.IdempotentEventHandler;
import im.phantom.infra.mq.MqConsumers;
import im.phantom.infra.mq.Topics;
import im.phantom.proto.v1.Msg;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 消费新消息事件，幂等更新会话列表、未读与摘要投影。 */
@Component
public class ConversationMsgConsumer implements SmartInitializingSingleton {

    private static final String GROUP = "phantom-conversation";
    private static final Logger log = LoggerFactory.getLogger(ConversationMsgConsumer.class);

    private final MqConsumers mqConsumers;
    private final ConversationService conversationService;
    private final IdempotentEventHandler idempotent;

    public ConversationMsgConsumer(MqConsumers mqConsumers, ConversationService conversationService,
                                   ConsumedEventStore consumedEventStore, TimeProvider time) {
        this.mqConsumers = mqConsumers;
        this.conversationService = conversationService;
        this.idempotent = new IdempotentEventHandler(consumedEventStore, time);
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            mqConsumers.newConsumer(GROUP, Topics.MSG_EVENT, Topics.TAG_NEW_MSG, body -> {
                Msg msg = Msg.parseFrom(body);
                try {
                    idempotent.handleOnce(GROUP, msg.getMsgId(), () -> conversationService.onNewMessage(msg));
                } catch (BizException error) {
                    if (error.code() != ErrorCodes.USER_NOT_FOUND) {
                        throw error;
                    }
                    log.warn("跳过无法投影的历史消息 msgId={} sender={}: {}",
                            msg.getMsgId(), msg.getSenderId(), error.getMessage());
                }
                return true;
            });
        } catch (Exception e) {
            throw new IllegalStateException("会话投影消费者启动失败", e);
        }
    }
}
