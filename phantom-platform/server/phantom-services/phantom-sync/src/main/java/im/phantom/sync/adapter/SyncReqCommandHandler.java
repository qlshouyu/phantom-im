package im.phantom.sync.adapter;

import im.phantom.common.Envelopes;
import im.phantom.common.ErrorCodes;
import im.phantom.common.gateway.CommandHandler;
import im.phantom.common.gateway.SessionContext;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.Envelope;
import im.phantom.proto.v1.SyncReq;
import im.phantom.proto.v1.SyncResp;
import im.phantom.sync.app.SyncAppService;
import org.springframework.stereotype.Component;

/** SYNC_REQ 指令处理器（网关 SPI 注册点）。 */
@Component
public class SyncReqCommandHandler implements CommandHandler {

    private final SyncAppService appService;

    public SyncReqCommandHandler(SyncAppService appService) {
        this.appService = appService;
    }

    @Override
    public int command() {
        return Command.COMMAND_SYNC_REQ_VALUE;
    }

    @Override
    public Envelope handle(SessionContext ctx, Envelope req) throws Exception {
        SyncReq body = SyncReq.parseFrom(req.getPayload());
        SyncResp resp = appService.sync(ctx.userId(), body);
        return Envelopes.resp(Command.COMMAND_SYNC_RESP, req.getCid(), ErrorCodes.OK, resp);
    }
}
