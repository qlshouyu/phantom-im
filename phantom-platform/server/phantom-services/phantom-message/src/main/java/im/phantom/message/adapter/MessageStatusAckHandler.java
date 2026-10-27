package im.phantom.message.adapter;

import im.phantom.api.message.MessageService;
import im.phantom.common.gateway.MessageAckHandler;
import im.phantom.common.gateway.SessionContext;
import org.springframework.stereotype.Component;

@Component
public class MessageStatusAckHandler implements MessageAckHandler {

    private final MessageService messages;

    public MessageStatusAckHandler(MessageService messages) {
        this.messages = messages;
    }

    @Override
    public void onAck(SessionContext context, long userSeq) {
        messages.onDelivered(context.userId(), userSeq);
    }
}
