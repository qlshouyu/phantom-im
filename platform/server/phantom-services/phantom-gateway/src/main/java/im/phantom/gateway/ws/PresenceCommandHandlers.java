package im.phantom.gateway.ws;

import im.phantom.api.route.RouteTableService;
import im.phantom.common.Envelopes;
import im.phantom.common.ErrorCodes;
import im.phantom.common.gateway.CommandHandler;
import im.phantom.common.gateway.SessionContext;
import im.phantom.proto.v1.AppStateReq;
import im.phantom.proto.v1.AppStateResp;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.Envelope;
import im.phantom.proto.v1.PresenceInfo;
import im.phantom.proto.v1.PresenceSubReq;
import im.phantom.proto.v1.PresenceSubResp;
import im.phantom.proto.v1.PresenceUnsubReq;
import im.phantom.proto.v1.TypingPush;
import im.phantom.proto.v1.TypingSignal;
import im.phantom.common.ConvIds;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/** Presence、Typing 与 AppState 指令处理。 */
public final class PresenceCommandHandlers {
    private PresenceCommandHandlers() {}

    @Component
    public static class PresenceSubHandler implements CommandHandler {
        private final PresenceRegistry presence;

        public PresenceSubHandler(PresenceRegistry presence) { this.presence = presence; }

        @Override public int command() { return Command.COMMAND_PRESENCE_SUB_REQ_VALUE; }

        @Override public Envelope handle(SessionContext ctx, Envelope req) throws Exception {
            PresenceSubReq body = PresenceSubReq.parseFrom(req.getPayload());
            Set<String> ids = new LinkedHashSet<>(body.getUserIdsList());
            if (ids.size() > 50) {
                return Envelopes.resp(Command.COMMAND_PRESENCE_SUB_RESP, req.getCid(),
                        ErrorCodes.INVALID_PARAM, null);
            }
            presence.subscribe(ctx, ids);
            PresenceSubResp.Builder resp = PresenceSubResp.newBuilder();
            for (String id : ids) {
                PresenceInfo info = presence.snapshot(id);
                resp.addPresences(info);
            }
            return Envelopes.resp(Command.COMMAND_PRESENCE_SUB_RESP, req.getCid(),
                    ErrorCodes.OK, resp.build());
        }
    }

    @Component
    public static class PresenceUnsubHandler implements CommandHandler {
        private final PresenceRegistry presence;
        public PresenceUnsubHandler(PresenceRegistry presence) { this.presence = presence; }
        @Override public int command() { return Command.COMMAND_PRESENCE_UNSUB_REQ_VALUE; }
        @Override public Envelope handle(SessionContext ctx, Envelope req) throws Exception {
            PresenceUnsubReq body = PresenceUnsubReq.parseFrom(req.getPayload());
            presence.unsubscribe(ctx, new LinkedHashSet<>(body.getUserIdsList()));
            return null;
        }
    }

    @Component
    public static class TypingHandler implements CommandHandler {
        private final RouteTableService routes;
        private final ConnectionRegistry connections;

        public TypingHandler(RouteTableService routes, ConnectionRegistry connections) {
            this.routes = routes;
            this.connections = connections;
        }

        @Override public int command() { return Command.COMMAND_TYPING_SIGNAL_VALUE; }
        @Override public Envelope handle(SessionContext ctx, Envelope req) throws Exception {
            TypingSignal body = TypingSignal.parseFrom(req.getPayload());
            if (!ConvIds.isSingle(body.getConvId())) {
                return Envelopes.resp(Command.COMMAND_TYPING_PUSH, req.getCid(),
                        ErrorCodes.CONV_INVALID, null);
            }
            String[] members = ConvIds.participants(body.getConvId());
            String target = members[0].equals(ctx.userId()) ? members[1]
                    : members[1].equals(ctx.userId()) ? members[0] : null;
            if (target == null) {
                return Envelopes.resp(Command.COMMAND_TYPING_PUSH, req.getCid(),
                        ErrorCodes.CONV_INVALID, null);
            }
            Envelope push = Envelopes.push(Command.COMMAND_TYPING_PUSH,
                    TypingPush.newBuilder().setConvId(body.getConvId())
                            .setUserId(ctx.userId()).setTyping(body.getTyping()).build());
            for (var route : routes.query(target)) {
                connections.send(route.connId(), push);
            }
            return null;
        }
    }

    @Component
    public static class AppStateHandler implements CommandHandler {
        private final RouteTableService routes;
        public AppStateHandler(RouteTableService routes) { this.routes = routes; }
        @Override public int command() { return Command.COMMAND_APP_STATE_REQ_VALUE; }
        @Override public Envelope handle(SessionContext ctx, Envelope req) throws Exception {
            AppStateReq body = AppStateReq.parseFrom(req.getPayload());
            routes.updateForeground(ctx.userId(), ctx.deviceType(), ctx.connId(),
                    body.getForeground(), System.currentTimeMillis());
            return Envelopes.resp(Command.COMMAND_APP_STATE_RESP, req.getCid(), ErrorCodes.OK,
                    AppStateResp.getDefaultInstance());
        }
    }
}
