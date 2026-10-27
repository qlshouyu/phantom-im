package im.phantom.push;

import im.phantom.api.route.GatewayDeliveryService;
import im.phantom.api.route.RouteEntry;
import im.phantom.api.route.RouteTableService;
import im.phantom.infra.redis.AckCursorStore;
import im.phantom.proto.v1.Command;
import im.phantom.proto.v1.Envelope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineDeliveryServiceImplTest {

    @Test
    void slowDeviceDoesNotBlockAnotherDeviceOfSameReceiver() throws Exception {
        RouteTableService routes = mock(RouteTableService.class);
        GatewayDeliveryService gateway = mock(GatewayDeliveryService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AckCursorStore> cursors = mock(ObjectProvider.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ExecutorService deviceExecutor = Executors.newVirtualThreadPerTaskExecutor();
        OnlineDeliveryServiceImpl delivery = new OnlineDeliveryServiceImpl(
                routes, gateway, cursors, scheduler, null, deviceExecutor);
        when(routes.query("u1")).thenReturn(List.of(
                new RouteEntry("u1", 3, "slow", "conn-slow", "gw", "app", 1, true),
                new RouteEntry("u1", 4, "fast", "conn-fast", "gw", "app", 1, true)));
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch fastSent = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        when(gateway.send("gw", "conn-slow", envelope())).thenAnswer(invocation -> {
            slowStarted.countDown();
            releaseSlow.await(2, TimeUnit.SECONDS);
            return false;
        });
        when(gateway.send("gw", "conn-fast", envelope())).thenAnswer(invocation -> {
            fastSent.countDown();
            return true;
        });

        Envelope envelope = envelope();
        CompletableFuture<Integer> result = CompletableFuture.supplyAsync(
                () -> delivery.deliverEnvelope("u1", envelope));
        try {
            assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(fastSent.await(1, TimeUnit.SECONDS)).isTrue();
            releaseSlow.countDown();
            assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            releaseSlow.countDown();
            deviceExecutor.close();
        }
    }

    private Envelope envelope() {
        return Envelope.newBuilder().setCmd(Command.COMMAND_MSG_PUSH).setCid(0).build();
    }
}
