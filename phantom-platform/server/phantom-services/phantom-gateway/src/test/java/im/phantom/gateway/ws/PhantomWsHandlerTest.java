package im.phantom.gateway.ws;

import im.phantom.api.config.AppConfigProvider;
import im.phantom.api.route.RouteTableService;
import im.phantom.api.route.UserSeqQuery;
import im.phantom.common.gateway.CommandHandler;
import im.phantom.common.gateway.MessageAckHandler;
import im.phantom.common.gateway.SessionContext;
import im.phantom.gateway.GatewayProperties;
import im.phantom.gateway.auth.AuthResponseFactory;
import im.phantom.gateway.auth.AuthService;
import im.phantom.gateway.device.DeviceRegistryService;
import im.phantom.infra.redis.AckCursorStore;
import im.phantom.infra.redis.RateLimitDecision;
import im.phantom.infra.redis.RedisRateLimiter;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.Envelope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhantomWsHandlerTest {

    @Test
    void heartbeatKeepsFastPathWhileSendTaskIsRunning() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        properties.setSendWorkerThreads(1);
        SendCommandExecutor executor = new SendCommandExecutor(properties, null);
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        CommandHandler sendHandler = mock(CommandHandler.class);
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        when(dispatcher.find(Command.COMMAND_SEND_MSG_REQ_VALUE)).thenReturn(sendHandler);
        when(sendHandler.handle(any(), any())).thenAnswer(invocation -> {
            sendStarted.countDown();
            releaseSend.await(2, TimeUnit.SECONDS);
            return Envelope.newBuilder().setCmd(Command.COMMAND_SEND_MSG_RESP).setCid(1).build();
        });
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        when(limiter.allow(anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(new RateLimitDecision(true, 1, 120, 0));
        RouteTableService routes = mock(RouteTableService.class);
        ConnectionRegistry registry = mock(ConnectionRegistry.class);
        PhantomWsHandler handler = handler(properties, executor, dispatcher, limiter, routes, registry);
        WebSocketSession session = session();

        Envelope send = Envelope.newBuilder().setCmd(Command.COMMAND_SEND_MSG_REQ).setCid(1).build();
        handler.handleBinaryMessage(session, new BinaryMessage(send.toByteArray()));
        assertThat(sendStarted.await(1, TimeUnit.SECONDS)).isTrue();

        long before = System.nanoTime();
        Envelope ping = Envelope.newBuilder().setCmd(Command.COMMAND_PING).setCid(2).build();
        handler.handleBinaryMessage(session, new BinaryMessage(ping.toByteArray()));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        assertThat(elapsedMs).isLessThan(500);
        verify(routes).heartbeat(anyString(), anyInt(), anyString(), anyLong());
        releaseSend.countDown();
        executor.shutdown();
    }

    @SuppressWarnings("unchecked")
    private PhantomWsHandler handler(GatewayProperties properties, SendCommandExecutor executor,
                                     CommandDispatcher dispatcher, RedisRateLimiter limiter,
                                     RouteTableService routes, ConnectionRegistry registry) {
        return new PhantomWsHandler(mock(AuthService.class), routes, mock(UserSeqQuery.class), registry,
                dispatcher, mock(AckCursorStore.class), properties, mock(ScheduledExecutorService.class),
                mock(AuthResponseFactory.class), mock(ObjectProvider.class), mock(PresenceRegistry.class),
                mock(ObjectProvider.class), mock(DeviceRegistryService.class), limiter, executor);
    }

    private WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("connId", "conn-1");
        attributes.put("ctx", new SessionContext("u1", "d1", 3, "app", "conn-1", "gw-1"));
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
