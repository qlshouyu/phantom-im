package im.phantom.message.adapter;

import im.phantom.common.BizException;
import im.phantom.common.Envelopes;
import im.phantom.common.ErrorCodes;
import im.phantom.common.gateway.CommandHandler;
import im.phantom.common.gateway.SessionContext;
import im.phantom.message.app.MessageAppService;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.Envelope;
import im.phantom.proto.v1.SendMsgReq;
import im.phantom.proto.v1.SendMsgResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** SEND_MSG_REQ 指令处理器（网关 SPI 注册点）。 */
@Component
public class SendMsgCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(SendMsgCommandHandler.class);

    private final MessageAppService appService;

    public SendMsgCommandHandler(MessageAppService appService) {
        this.appService = appService;
    }

    @Override
    public int command() {
        return Command.COMMAND_SEND_MSG_REQ_VALUE;
    }

    @Override
    public Envelope handle(SessionContext ctx, Envelope req) throws Exception {
        SendMsgReq body = SendMsgReq.parseFrom(req.getPayload());
        try {
            SendMsgResp resp = appService.handleSend(ctx.userId(), body);
            return Envelopes.resp(Command.COMMAND_SEND_MSG_RESP, req.getCid(), ErrorCodes.OK, resp);
        } catch (BizException e) {
            log.info("收单失败 code={} msg={}", e.code(), e.getMessage());
            return Envelopes.resp(Command.COMMAND_SEND_MSG_RESP, req.getCid(), e.code(), null);
        }
    }
}
