package im.phantom.fanout.app;

import im.phantom.fanout.FanoutDeliveryProperties;
import im.phantom.infra.observability.PlatformMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 按 receiver_id 串行、receiver 间并行的有界在线投递执行器。 */
public class FanoutDeliveryExecutor {

    private static final Logger log = LoggerFactory.getLogger(FanoutDeliveryExecutor.class);

    private final ThreadPoolExecutor workers;
    private final FanoutDeliveryProperties properties;
    private final PlatformMetrics metrics;
    private final Map<String, UserQueue> queues = new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean accepting = true;

    public FanoutDeliveryExecutor(FanoutDeliveryProperties properties, PlatformMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
        int threads = Math.max(1, properties.getWorkerThreads());
        int capacity = Math.max(1, properties.getQueueCapacity());
        this.workers = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), threadFactory(), new ThreadPoolExecutor.AbortPolicy());
        this.workers.allowCoreThreadTimeOut(true);
        if (metrics != null) {
            metrics.registerDeliveryQueueDepth("fanout_delivery", pending::get);
        }
    }

    public boolean submit(String receiverId, long userSeq, Runnable delivery) {
        int capacity = Math.max(1, properties.getQueueCapacity());
        int current = pending.incrementAndGet();
        if (!accepting || current > capacity) {
            pending.decrementAndGet();
            reject("global");
            return false;
        }

        UserQueue queue = queues.computeIfAbsent(receiverId, ignored -> new UserQueue());
        synchronized (queue) {
            if (queue.tasks.size() >= Math.max(1, properties.getPerUserCapacity())) {
                pending.decrementAndGet();
                reject("user");
                if (!queue.running) {
                    queues.remove(receiverId, queue);
                }
                return false;
            }
            DeliveryTask deliveryTask = new DeliveryTask(userSeq, delivery);
            queue.tasks.add(deliveryTask);
            if (queue.running) {
                return true;
            }
            queue.running = true;
            try {
                workers.execute(() -> drain(receiverId, queue));
                return true;
            } catch (RejectedExecutionException rejected) {
                queue.tasks.remove(deliveryTask);
                queue.running = false;
                pending.decrementAndGet();
                queues.remove(receiverId, queue);
                reject("executor");
                return false;
            }
        }
    }

    private void drain(String receiverId, UserQueue queue) {
        while (true) {
            DeliveryTask task;
            synchronized (queue) {
                task = queue.tasks.poll();
                if (task == null) {
                    queue.running = false;
                    queues.remove(receiverId, queue);
                    return;
                }
            }
            try {
                task.delivery().run();
            } catch (Throwable error) {
                log.warn("fanout 在线投递失败 receiver={} userSeq={}: {}",
                        receiverId, task.userSeq(), error.toString());
            } finally {
                pending.decrementAndGet();
            }
        }
    }

    public void shutdown() {
        accepting = false;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(Math.max(0, properties.getShutdownTimeoutMs()), TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    int pendingTasks() {
        return pending.get();
    }

    private void reject(String reason) {
        if (metrics != null) {
            metrics.recordDeliveryQueueRejected("fanout_delivery", reason);
        }
    }

    private ThreadFactory threadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "fanout-delivery-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    private static final class UserQueue {
        private final PriorityQueue<DeliveryTask> tasks = new PriorityQueue<>(
                Comparator.comparingLong(DeliveryTask::userSeq));
        private boolean running;
    }

    private record DeliveryTask(long userSeq, Runnable delivery) {}
}
