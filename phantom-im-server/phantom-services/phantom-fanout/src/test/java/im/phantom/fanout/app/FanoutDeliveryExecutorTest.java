package im.phantom.fanout.app;

import im.phantom.fanout.FanoutDeliveryProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FanoutDeliveryExecutorTest {

    @Test
    void ordersTasksForOneReceiverByUserSequence() throws Exception {
        FanoutDeliveryExecutor executor = executor(1, 10, 10);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(3);
        List<Long> order = new CopyOnWriteArrayList<>();
        try {
            executor.submit("u1", 1, () -> {
                firstStarted.countDown();
                await(releaseFirst);
                order.add(1L);
                completed.countDown();
            });
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            executor.submit("u1", 4, () -> { order.add(4L); completed.countDown(); });
            executor.submit("u1", 3, () -> { order.add(3L); completed.countDown(); });
            executor.submit("u1", 2, () -> { order.add(2L); completed.countDown(); });
            releaseFirst.countDown();

            assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly(1L, 2L, 3L, 4L);
        } finally {
            releaseFirst.countDown();
            executor.shutdown();
        }
    }

    @Test
    void receiversCanMakeProgressInParallel() throws Exception {
        FanoutDeliveryExecutor executor = executor(2, 10, 10);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Runnable task = () -> {
                started.countDown();
                await(release);
            };
            executor.submit("u1", 1, task);
            executor.submit("u2", 1, task);

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void rejectsWhenPerUserQueueIsFull() throws Exception {
        FanoutDeliveryExecutor executor = executor(1, 10, 1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submit("u1", 1, () -> {
                firstStarted.countDown();
                await(release);
            });
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit("u1", 2, () -> { })).isTrue();
            assertThat(executor.submit("u1", 3, () -> { })).isFalse();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private FanoutDeliveryExecutor executor(int workers, int capacity, int perUser) {
        FanoutDeliveryProperties properties = new FanoutDeliveryProperties();
        properties.setWorkerThreads(workers);
        properties.setQueueCapacity(capacity);
        properties.setPerUserCapacity(perUser);
        properties.setShutdownTimeoutMs(500);
        return new FanoutDeliveryExecutor(properties, null);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
