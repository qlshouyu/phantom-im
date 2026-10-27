package im.phantom.message.adapter;

import im.phantom.common.BizException;
import im.phantom.common.Envelopes;
import im.phantom.common.ErrorCodes;
import im.phantom.common.gateway.CommandHandler;
import im.phantom.common.gateway.SessionContext;
import im.phantom.api.message.MessageService;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.Envelope;
import im.phantom.proto.v1.RecallMsgReq;
import im.phantom.proto.v1.RecallMsgResp;
import org.springframework.stereotype.Component;

/** 撤回消息 WS 指令。 */
@Component
public class RecallMsgCommandHandler implements CommandHandler {

    private final MessageService messages;

    public RecallMsgCommandHandler(MessageService messages) {
        this.messages = messages;
    }

    @Override
    public int command() {
        return Command.COMMAND_RECALL_MSG_REQ_VALUE;
    }

    @Override
    public Envelope handle(SessionContext ctx, Envelope req) throws Exception {
        RecallMsgReq body = RecallMsgReq.parseFrom(req.getPayload());
        try {
            messages.recall(ctx.userId(), body.getMsgId());
            return Envelopes.resp(Command.COMMAND_RECALL_MSG_RESP, req.getCid(), ErrorCodes.OK,
                    RecallMsgResp.newBuilder().setMsgId(body.getMsgId()).build());
        } catch (BizException error) {
            return Envelopes.resp(Command.COMMAND_RECALL_MSG_RESP, req.getCid(), error.code(), null);
        }
    }
}
