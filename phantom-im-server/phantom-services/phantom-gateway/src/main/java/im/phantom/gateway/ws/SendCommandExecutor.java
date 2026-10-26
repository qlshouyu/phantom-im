package im.phantom.gateway.ws;

import im.phantom.gateway.GatewayProperties;
import im.phantom.infra.observability.PlatformMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SEND_MSG_REQ 专用执行器：进程级有界、按连接串行，不同连接并行。
 * 网络 I/O 线程只负责解析和入队，不执行数据库、Redis 或 MQ 调用。
 */
public class SendCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(SendCommandExecutor.class);

    private final ThreadPoolExecutor workers;
    private final GatewayProperties properties;
    private final PlatformMetrics metrics;
    private final Map<String, ConnectionQueue> queues = new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean accepting = true;

    public SendCommandExecutor(GatewayProperties properties, PlatformMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
        int threads = Math.max(1, properties.getSendWorkerThreads());
        int capacity = Math.max(1, properties.getSendQueueCapacity());
        this.workers = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), threadFactory(), new ThreadPoolExecutor.AbortPolicy());
        this.workers.allowCoreThreadTimeOut(true);
        if (metrics != null) {
            metrics.registerDeliveryQueueDepth("gateway_send", pending::get);
        }
    }

    public boolean submit(String connectionId, Runnable action, Runnable onTimeout) {
        int capacity = Math.max(1, properties.getSendQueueCapacity());
        int current = pending.incrementAndGet();
        if (!accepting || current > capacity) {
            pending.decrementAndGet();
            reject("global");
            return false;
        }

        ConnectionQueue queue = queues.computeIfAbsent(connectionId, ignored -> new ConnectionQueue());
        synchronized (queue) {
            int perConnection = Math.max(1, properties.getSendPerConnectionCapacity());
            if (queue.tasks.size() >= perConnection) {
                pending.decrementAndGet();
                reject("connection");
                if (!queue.running) {
                    queues.remove(connectionId, queue);
                }
                return false;
            }
            queue.tasks.addLast(new SendTask(action, onTimeout, System.nanoTime()));
            if (queue.running) {
                return true;
            }
            queue.running = true;
            try {
                workers.execute(() -> drain(connectionId, queue));
                return true;
            } catch (RejectedExecutionException rejected) {
                queue.tasks.pollLast();
                queue.running = false;
                pending.decrementAndGet();
                queues.remove(connectionId, queue);
                reject("executor");
                return false;
            }
        }
    }

    private void drain(String connectionId, ConnectionQueue queue) {
        while (true) {
            SendTask task;
            synchronized (queue) {
                task = queue.tasks.pollFirst();
                if (task == null) {
                    queue.running = false;
                    queues.remove(connectionId, queue);
                    return;
                }
            }
            try {
                long timeoutNanos = Math.max(0, properties.getSendTaskTimeoutMs()) * 1_000_000L;
                if (timeoutNanos > 0 && System.nanoTime() - task.enqueuedAtNanos() > timeoutNanos) {
                    reject("timeout");
                    task.onTimeout().run();
                } else {
                    task.action().run();
                }
            } catch (Throwable error) {
                log.warn("网关发送任务异常 connId={}: {}", connectionId, error.toString());
            } finally {
                pending.decrementAndGet();
            }
        }
    }

    public void shutdown() {
        accepting = false;
        workers.shutdown();
        long timeoutMs = Math.max(0, properties.getSendShutdownTimeoutMs());
        try {
            if (!workers.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
        expireQueuedTasks();
    }

    int pendingTasks() {
        return pending.get();
    }

    private void expireQueuedTasks() {
        List<Runnable> timeouts = new ArrayList<>();
        for (ConnectionQueue queue : queues.values()) {
            synchronized (queue) {
                for (SendTask task : queue.tasks) {
                    pending.decrementAndGet();
                    timeouts.add(task.onTimeout());
                    reject("shutdown");
                }
                queue.tasks.clear();
                queue.running = false;
            }
        }
        queues.clear();
        timeouts.forEach(Runnable::run);
    }

    private void reject(String reason) {
        if (metrics != null) {
            metrics.recordDeliveryQueueRejected("gateway_send", reason);
        }
    }

    private ThreadFactory threadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "gateway-send-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    private static final class ConnectionQueue {
        private final ArrayDeque<SendTask> tasks = new ArrayDeque<>();
        private boolean running;
    }

    private record SendTask(Runnable action, Runnable onTimeout, long enqueuedAtNanos) {}
}
