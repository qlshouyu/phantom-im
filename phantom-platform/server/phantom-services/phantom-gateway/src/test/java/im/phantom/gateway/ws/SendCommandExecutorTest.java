package im.phantom.gateway.ws;

import im.phantom.gateway.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SendCommandExecutorTest {

    @Test
    void preservesSubmissionOrderWithinOneConnection() throws Exception {
        GatewayProperties properties = properties(2, 10, 10, 2_000);
        SendCommandExecutor executor = new SendCommandExecutor(properties, null);
        List<Integer> order = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(3);
        try {
            for (int i = 0; i < 3; i++) {
                int value = i;
                assertThat(executor.submit("conn-1", () -> {
                    order.add(value);
                    completed.countDown();
                }, () -> completed.countDown())).isTrue();
            }

            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly(0, 1, 2);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void differentConnectionsCanRunInParallel() throws Exception {
        GatewayProperties properties = properties(2, 10, 10, 2_000);
        SendCommandExecutor executor = new SendCommandExecutor(properties, null);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Runnable task = () -> {
                started.countDown();
                try {
                    release.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            };
            executor.submit("conn-1", task, () -> { });
            executor.submit("conn-2", task, () -> { });

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void rejectsConnectionCapacityWithoutRunningRejectedTask() throws Exception {
        GatewayProperties properties = properties(1, 10, 1, 2_000);
        SendCommandExecutor executor = new SendCommandExecutor(properties, null);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean rejectedRan = new AtomicBoolean();
        try {
            executor.submit("conn-1", () -> {
                firstStarted.countDown();
                await(release);
            }, () -> { });
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit("conn-1", () -> { }, () -> { })).isTrue();

            assertThat(executor.submit("conn-1", () -> rejectedRan.set(true), () -> { })).isFalse();
            assertThat(rejectedRan).isFalse();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void taskFailureDoesNotBlockLaterTasksOrOtherConnections() throws Exception {
        GatewayProperties properties = properties(1, 10, 10, 2_000);
        SendCommandExecutor executor = new SendCommandExecutor(properties, null);
        CountDownLatch completed = new CountDownLatch(2);
        try {
            executor.submit("conn-1", () -> {
                throw new IllegalStateException("boom");
            }, () -> { });
            executor.submit("conn-2", completed::countDown, () -> { });
            executor.submit("conn-1", completed::countDown, () -> { });

            assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void timesOutQueuedTaskWithoutExecutingIt() throws Exception {
        GatewayProperties properties = properties(1, 10, 1, 30);
        SendCommandExecutor executor = new SendCommandExecutor(properties, null);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch timedOut = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        try {
            executor.submit("conn-1", () -> {
                firstStarted.countDown();
                await(releaseFirst);
            }, () -> { });
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            executor.submit("conn-1", () -> queuedRan.set(true), timedOut::countDown);
            Thread.sleep(60);
            releaseFirst.countDown();

            assertThat(timedOut.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(queuedRan).isFalse();
        } finally {
            releaseFirst.countDown();
            executor.shutdown();
        }
    }

    @Test
    void shutdownStopsAcceptingAndDoesNotHangBeyondConfiguredLimit() throws Exception {
        GatewayProperties properties = properties(1, 10, 10, 2_000);
        properties.setSendShutdownTimeoutMs(100);
        SendCommandExecutor executor = new SendCommandExecutor(properties, null);
        CountDownLatch started = new CountDownLatch(1);
        executor.submit("conn-1", () -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, () -> { });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        long before = System.nanoTime();
        executor.shutdown();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        assertThat(elapsedMs).isLessThan(1_000L);
        assertThat(executor.submit("conn-2", () -> { }, () -> { })).isFalse();
    }

    private GatewayProperties properties(int workers, int global, int perConnection, long timeoutMs) {
        GatewayProperties properties = new GatewayProperties();
        properties.setSendWorkerThreads(workers);
        properties.setSendQueueCapacity(global);
        properties.setSendPerConnectionCapacity(perConnection);
        properties.setSendTaskTimeoutMs(timeoutMs);
        properties.setSendShutdownTimeoutMs(500);
        return properties;
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
