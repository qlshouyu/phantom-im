package im.phantom.gateway.ws;

import im.phantom.api.route.RouteEntry;
import im.phantom.api.route.RouteTableService;
import im.phantom.api.route.UserSeqQuery;
import im.phantom.common.BizException;
import im.phantom.common.Envelopes;
import im.phantom.common.ErrorCodes;
import im.phantom.common.gateway.CommandHandler;
import im.phantom.common.gateway.SessionContext;
import im.phantom.gateway.GatewayProperties;
import im.phantom.gateway.auth.AuthService;
import im.phantom.infra.redis.AckCursorStore;
import im.phantom.proto.v1.AuthReq;
import im.phantom.proto.v1.AuthResp;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.Envelope;
import im.phantom.proto.v1.KickPush;
import im.phantom.proto.v1.MsgAck;
import im.phantom.proto.v1.Pong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * WS 长连接处理器（connection-gateway spec）：Envelope 编解码、首帧 AuthReq 验签、
 * 路由表登记与同类型互踢、心跳判活、指令 SPI 分发。
 */
public class PhantomWsHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PhantomWsHandler.class);
    private static final String ATTR_CONN_ID = "connId";
    private static final String ATTR_CTX = "ctx";
    private static final String ATTR_AUTH_FUTURE = "authFuture";

    private final AuthService authService;
    private final RouteTableService routeTable;
    private final UserSeqQuery userSeqQuery;
    private final ConnectionRegistry registry;
    private final CommandDispatcher dispatcher;
    private final AckCursorStore ackCursor;
    private final GatewayProperties props;
    private final ScheduledExecutorService scheduler;

    public PhantomWsHandler(AuthService authService, RouteTableService routeTable, UserSeqQuery userSeqQuery,
                            ConnectionRegistry registry, CommandDispatcher dispatcher, AckCursorStore ackCursor,
                            GatewayProperties props, ScheduledExecutorService scheduler) {
        this.authService = authService;
        this.routeTable = routeTable;
        this.userSeqQuery = userSeqQuery;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.ackCursor = ackCursor;
        this.props = props;
        this.scheduler = scheduler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String connId = "c_" + UUID.randomUUID().toString().replace("-", "");
        session.getAttributes().put(ATTR_CONN_ID, connId);
        registry.register(connId, session);
        // 认证超时：10s 未认证主动断开
        ScheduledFuture<?> f = scheduler.schedule(() -> {
            if (session.getAttributes().get(ATTR_CTX) == null) {
                closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("auth timeout"));
            }
        }, props.getAuthTimeoutS(), TimeUnit.SECONDS);
        session.getAttributes().put(ATTR_AUTH_FUTURE, f);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        Envelope env;
        try {
            env = Envelope.parseFrom(message.getPayload());
        } catch (Exception e) {
            log.warn("非法帧，丢弃/断开: {}", e.toString());
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }

        SessionContext ctx = (SessionContext) session.getAttributes().get(ATTR_CTX);
        if (ctx == null) {
            // 未认证：首帧必须是 AUTH_REQ
            if (env.getCmd() == Command.COMMAND_AUTH_REQ) {
                doAuth(session, env);
            } else {
                closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("first frame must be AuthReq"));
            }
            return;
        }
        dispatch(session, ctx, env);
    }

    private void doAuth(WebSocketSession session, Envelope env) throws Exception {
        AuthReq req = AuthReq.parseFrom(env.getPayload());
        String connId = (String) session.getAttributes().get(ATTR_CONN_ID);
        try {
            authService.verify(req.getAccessToken(), req.getAppId(), req.getUserId());
        } catch (BizException e) {
            reply(session, Envelopes.resp(Command.COMMAND_AUTH_RESP, env.getCid(), e.code(), null));
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE.withReason("auth failed"));
            return;
        }

        int deviceType = req.getDeviceTypeValue();
        long now = System.currentTimeMillis();
        RouteEntry entry = new RouteEntry(req.getUserId(), deviceType, req.getDeviceId(),
                connId, props.getId(), req.getAppId(), now, true);
        Optional<RouteEntry> old = routeTable.register(entry);
        old.ifPresent(this::kick);   // 同类型顶替

        SessionContext ctx = new SessionContext(req.getUserId(), req.getDeviceId(), deviceType,
                req.getAppId(), connId, props.getId());
        session.getAttributes().put(ATTR_CTX, ctx);
        cancelAuthTimeout(session);

        AuthResp resp = AuthResp.newBuilder()
                .setServerTime(now)
                .setMaxUserSeq(userSeqQuery.maxUserSeq(req.getUserId()))
                .setHeartbeatIntervalS(props.getHeartbeatIntervalS())
                .build();
        reply(session, Envelopes.resp(Command.COMMAND_AUTH_RESP, env.getCid(), ErrorCodes.OK, resp));
        log.info("认证成功 user={} deviceType={} connId={}", req.getUserId(), deviceType, connId);
    }

    private void kick(RouteEntry old) {
        // 同类型登录顶替：向旧连接推 KickPush 并断开（M0 单实例：旧连接在本进程）
        Envelope kick = Envelopes.push(Command.COMMAND_KICK_PUSH,
                KickPush.newBuilder().setReason(KickPush.KickReason.KICK_REASON_SAME_TYPE_LOGIN).build());
        registry.send(old.connId(), kick);
        registry.close(old.connId());
        log.info("同类型顶替，踢下线 oldConnId={}", old.connId());
    }

    private void dispatch(WebSocketSession session, SessionContext ctx, Envelope env) throws Exception {
        Command cmd = env.getCmd();
        switch (cmd) {
            case COMMAND_PING -> {
                routeTable.heartbeat(ctx.userId(), ctx.deviceType(), ctx.connId(), System.currentTimeMillis());
                reply(session, Envelopes.resp(Command.COMMAND_PONG, env.getCid(), ErrorCodes.OK,
                        Pong.newBuilder().setServerTime(System.currentTimeMillis()).build()));
            }
            case COMMAND_MSG_ACK -> {
                MsgAck ack = MsgAck.parseFrom(env.getPayload());
                ackCursor.advance(ctx.userId(), ctx.deviceId(), ack.getUserSeq());
                // MSG_ACK 无需回帧
            }
            case COMMAND_LOGOUT_REQ -> {
                routeTable.remove(ctx.userId(), ctx.deviceType(), ctx.connId());
                reply(session, Envelopes.resp(Command.COMMAND_LOGOUT_RESP, env.getCid(), ErrorCodes.OK, null));
                closeQuietly(session, CloseStatus.NORMAL);
            }
            default -> {
                CommandHandler handler = dispatcher.find(cmd.getNumber());
                if (handler == null) {
                    log.warn("无处理器的指令: {}", cmd);
                    reply(session, Envelopes.resp(cmd, env.getCid(), ErrorCodes.INVALID_PARAM, null));
                    return;
                }
                Envelope resp = handler.handle(ctx, env);
                if (resp != null) {
                    reply(session, resp);
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String connId = (String) session.getAttributes().get(ATTR_CONN_ID);
        SessionContext ctx = (SessionContext) session.getAttributes().get(ATTR_CTX);
        if (ctx != null) {
            routeTable.remove(ctx.userId(), ctx.deviceType(), ctx.connId());
        }
        if (connId != null) {
            registry.remove(connId);
        }
        cancelAuthTimeout(session);
    }

    private void reply(WebSocketSession session, Envelope env) {
        String connId = (String) session.getAttributes().get(ATTR_CONN_ID);
        registry.send(connId, env);
    }

    private void cancelAuthTimeout(WebSocketSession session) {
        Object f = session.getAttributes().remove(ATTR_AUTH_FUTURE);
        if (f instanceof ScheduledFuture<?> future) {
            future.cancel(false);
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignore) {
            // 忽略
        }
    }
}
